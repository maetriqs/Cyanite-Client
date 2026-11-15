package com.cyanite.client.system;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A song played on a loop beside the game's own sound. Java can't read mp3, so ffmpeg decodes it
 * (any format ffmpeg knows) to plain 16-bit stereo, which goes straight to a sound line; the
 * volume is applied to the samples, fading in at the start so it doesn't pop. One thread per song,
 * and {@link #stop} ends it at once, ffmpeg included.
 */
public final class MusicLoop {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int RATE = 44100;
    /** Bytes per frame: two channels of 16 bits. */
    private static final int FRAME = 4;
    /** Seconds the volume takes to fade in, and to follow a change. */
    private static final double FADE = 0.6;

    private final Path file;
    private final Thread thread;
    private volatile float volume;
    private volatile boolean stopped;
    private volatile Process process;
    private volatile SourceDataLine line;
    /** Why it isn't playing (no ffmpeg, no file, not audio), for the module to say; null while all's well. */
    private volatile String problem;
    private volatile boolean playing;

    public MusicLoop(Path file, float volume) {
        this.file = file;
        this.volume = volume;
        thread = new Thread(this::run, "Cyanite music");
        thread.setDaemon(true);
    }

    public MusicLoop start() {
        thread.start();
        return this;
    }

    /** 0 silent to 1 as loud as the file. */
    public void setVolume(float volume) {
        this.volume = Math.max(0, Math.min(1, volume));
    }

    public boolean isPlaying() {
        return playing;
    }

    public String problem() {
        return problem;
    }

    /** Stops straight away: the line goes quiet and ffmpeg is ended. */
    public void stop() {
        stopped = true;
        playing = false;
        Process p = process;
        if (p != null) p.destroyForcibly();
        SourceDataLine out = line;
        if (out != null) {
            out.stop();
            out.flush();
        }
        thread.interrupt();
    }

    private void run() {
        if (!Files.isRegularFile(file)) {
            problem = "no file at " + file;
            return;
        }
        String ffmpeg = Ffmpeg.find();
        if (stopped) return;
        if (ffmpeg == null) {
            problem = "it needs ffmpeg to play mp3s (on Windows: winget install ffmpeg)";
            return;
        }
        AudioFormat format = new AudioFormat(RATE, 16, 2, true, false);
        try (SourceDataLine out = AudioSystem.getSourceDataLine(format)) {
            // A quarter of a second of buffer: enough not to stutter, short enough to stop promptly.
            out.open(format, RATE / 4 * FRAME);
            line = out;
            if (stopped) return;
            out.start();
            double gain = 0;
            while (!stopped) {
                // -stream_loop -1 loops the file inside ffmpeg, without a gap; it's started again only if it stops.
                Process p = new ProcessBuilder(ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", "-stream_loop", "-1",
                    "-i", file.toString(), "-vn", "-f", "s16le", "-ac", "2", "-ar", Integer.toString(RATE), "-")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                process = p;
                if (stopped) {
                    p.destroyForcibly();
                    break;
                }
                long frames = 0;
                byte[] buffer = new byte[RATE / 20 * FRAME];
                int filled = 0;
                try (InputStream in = p.getInputStream()) {
                    int read;
                    while (!stopped && (read = in.read(buffer, filled, buffer.length - filled)) >= 0) {
                        filled += read;
                        int whole = filled - filled % FRAME;
                        if (whole == 0) continue;
                        gain = scale(buffer, whole, gain);
                        out.write(buffer, 0, whole);
                        playing = true;
                        frames += whole / FRAME;
                        System.arraycopy(buffer, whole, buffer, 0, filled - whole);
                        filled -= whole;
                    }
                }
                p.destroyForcibly();
                if (!stopped && frames == 0) {
                    problem = "ffmpeg couldn't play " + file.getFileName();
                    break;
                }
            }
        } catch (LineUnavailableException | IllegalArgumentException e) {
            problem = "no sound output to play it on";
            LOGGER.warn("Music: no sound line", e);
        } catch (IOException e) {
            if (!stopped) {
                problem = "ffmpeg stopped playing it";
                LOGGER.warn("Music: ffmpeg failed", e);
            }
        } finally {
            playing = false;
            line = null;
            Process p = process;
            if (p != null) p.destroyForcibly();
        }
    }

    /**
     * Scales the samples in place by the volume, squared so the slider feels even, gliding from
     * {@code gain} toward it over the buffer.
     * @return the gain it ended on
     */
    private double scale(byte[] pcm, int length, double gain) {
        double target = (double) volume * volume;
        int frames = length / FRAME;
        double step = 1 - Math.exp(-1.0 / (RATE * FADE));
        for (int f = 0; f < frames; f++) {
            gain += (target - gain) * step;
            for (int c = 0; c < 2; c++) {
                int i = f * FRAME + c * 2;
                int sample = (short) ((pcm[i] & 0xFF) | pcm[i + 1] << 8);
                int scaled = (int) Math.round(sample * gain);
                pcm[i] = (byte) scaled;
                pcm[i + 1] = (byte) (scaled >> 8);
            }
        }
        return gain;
    }
}
