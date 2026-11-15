package com.cyanite.client.hud;

import com.cyanite.client.config.Config;
import com.cyanite.client.gui.Theme;
import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.system.Ffmpeg;
import com.cyanite.client.system.Stealth;
import com.cyanite.client.system.setting.SliderSetting;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Subway Surfers clip on the HUD, Shorts style: tall (9:16), looping, no sound. A short clip comes with the
 * client and plays out of the box; a video of your own at cyanite/subway.mp4 (or .webm, .mkv) in the game folder
 * plays instead, and takes over within a second of being put there.
 *
 * <p>The built-in clip is JPEG frames already cut to size, decoded one at a time as they're shown, so it needs
 * nothing installed. Your own video needs ffmpeg, which decodes it (on the graphics card's video decoder where it
 * can) into small frames, at the video's own rate up to 30 a second, cropped to fill the frame. Either way a
 * thread of ours hands the frames on in time, and the newest one goes to the graphics card when the HUD is drawn.
 * It only decodes while it's on screen: hidden (F1, F3, Stealth, the title screen), decoding waits, and after a
 * while stops, carrying on from the same spot when it's back. Switching it off stops it straight away.
 */
public class SubwaySurfers extends HudElement {
    public static final String NAME = "Subway Surfers";
    private static final Logger LOGGER = LoggerFactory.getLogger("Cyanite");
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("cyanite_client", "subway_surfers");
    /** Decoded size: 9:16, about as big as it's drawn. */
    private static final int VIDEO_W = 270;
    private static final int VIDEO_H = 480;
    private static final int FRAME_BYTES = VIDEO_W * VIDEO_H * 4;
    private static final double MAX_FPS = 30;
    private static final List<String> EXTENSIONS = List.of("mp4", "webm", "mkv");
    /** Not drawn for this long: decoding waits. */
    private static final long PAUSE_NS = 500_000_000L;
    /** Not drawn for this long: ffmpeg is closed. */
    private static final long CLOSE_NS = 10_000_000_000L;
    private static final long LOOK_NS = 1_000_000_000L;
    private static final long RETRY_NS = 3_000_000_000L;
    /** The phone's edge round the video, in HUD units. */
    private static final int BEZEL = 2;
    private static final String NO_FFMPEG = "Needs ffmpeg: winget install ffmpeg, then restart the game";
    /** The clip that comes with the client (made by dev/subway_clip.py). */
    private static final String CLIP = "/assets/cyanite_client/video/subway_clip.bin";
    /** {@link #failedOn} for the built-in clip. */
    private static final String BUILT_IN = "built-in";

    /** Every ffmpeg this has started and not yet seen stop, so the game never leaves one behind. */
    private static final Set<Process> RUNNING = ConcurrentHashMap.newKeySet();
    private static SubwaySurfers instance;

    private final SliderSetting size = add(new SliderSetting("Width", 110, 60, 240, 5).format(v -> (int) v + ""));
    private final SliderSetting opacity = add(new SliderSetting("Opacity", 1, 0.2, 1, 0.05).percent());

    private volatile long lastWanted;
    private Decoder decoder;
    private long nextLook;
    private long retryAt;
    /** File and version (or the built-in clip) that wouldn't play, so it isn't tried again until it changes. */
    private String failedOn;
    private String problem;
    /** Seconds into the video to carry on from. */
    private double resumeAt;
    private Picture texture;
    private boolean hasFrame;
    private long uploads;
    private long uploadNanos;

    public SubwaySurfers() {
        super(NAME, false, Horizontal.RIGHT, Vertical.CENTER, 4, 0);
        instance = this;
        Runtime.getRuntime().addShutdownHook(new Thread(SubwaySurfers::killAll, "Cyanite Subway Surfers exit"));
    }

    /** Closes ffmpeg as the game closes. */
    public static void shutdown() {
        if (instance != null) instance.stop();
        killAll();
    }

    private static void killAll() {
        for (Process p : RUNNING) p.destroyForcibly();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (!enabled) stop();
    }

    @Override
    public void toggle() {
        super.toggle();
        if (!isEnabled()) stop();
    }

    @Override
    public void reset() {
        super.reset();
        if (!isEnabled()) stop();
    }

    @Override
    public void load(JsonObject json) {
        super.load(json);
        if (!isEnabled()) stop();
    }

    @Override
    public void update(float dt, boolean editing) {
        width = (int) (double) size.get();
        height = Math.round((width - BEZEL * 2) * 16 / 9f) + BEZEL * 2;
    }

    @Override
    public void draw(GuiGraphics g, float alpha, boolean editing) {
        // Disabled and ghosted in the HUD editor: just the frame.
        if (isEnabled() && !Stealth.active()) play();

        int s = Render2D.scale();
        float a = alpha * (float) (double) opacity.get();
        int r = 4 * s;
        int vx = (x + BEZEL) * s;
        int vy = (y + BEZEL) * s;
        int vw = (width - BEZEL * 2) * s;
        int vh = (height - BEZEL * 2) * s;

        Render2D.pixels(g);
        Render2D.glow(g, x * s, y * s + s, width * s, height * s, r, 7 * s, Theme.fade(Theme.SHADOW, a * 0.8f));
        Render2D.rect(g, x * s, y * s, width * s, height * s, r, Theme.fade(Theme.BORDER, a));
        Render2D.rect(g, x * s + 1, y * s + 1, width * s - 2, height * s - 2, Math.max(0, r - 1), Theme.fade(0xFF07080B, a));
        boolean video = hasFrame && isEnabled();
        if (video) {
            g.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, vx, vy, 0, 0, vw, vh, VIDEO_W, VIDEO_H, VIDEO_W, VIDEO_H, Theme.fade(0xFFFFFFFF, a));
        } else {
            Render2D.rect(g, vx, vy, vw, vh, 0, Theme.fade(Theme.FIELD, a));
        }
        Render2D.end(g);
        if (video) return;

        String message = !isEnabled() ? "Subway Surfers" : problem != null ? problem : "Loading...";
        List<String> lines = wrap(message, width - BEZEL * 2 - 8);
        int ty = y + (height - lines.size() * (Text.CAP + 4) + 4) / 2;
        for (String line : lines) {
            Text.draw(g, line, x + (width - Text.width(line)) / 2, ty, Theme.fade(Theme.TEXT_DIM, a), false);
            ty += Text.CAP + 4;
        }
    }

    // ---- Playing

    /** Called each time it's drawn: keeps the decoding going (or starts it) and puts the newest frame up. */
    private void play() {
        long now = System.nanoTime();
        lastWanted = now;
        Decoder d = decoder;
        if (d != null && d.ended) {
            resumeAt = d.position();
            if (d.failure != null) {
                problem = d.failure;
                failedOn = d.failure.equals(NO_FFMPEG) ? "*" : d.file == null ? BUILT_IN : version(d.file);
                LOGGER.warn("Subway Surfers: {} ({})", d.failure, d.file == null ? "built-in clip" : d.file.getFileName());
            } else if (!d.closedByUs) {
                // ffmpeg went away by itself: try again in a moment rather than every frame.
                retryAt = now + RETRY_NS;
            }
            decoder = d = null;
            nextLook = 0;
        }
        if (d != null && d.file == null && now >= nextLook) {
            // The built-in clip gives way as soon as a video of your own is put in the folder.
            nextLook = now + LOOK_NS;
            if (video() != null) {
                resumeAt = d.position();
                d.close();
                decoder = d = null;
                nextLook = 0;
            }
        }
        if (d == null) {
            if (now < nextLook) return;
            nextLook = now + LOOK_NS;
            // Your own video if there's one, otherwise the clip that comes with the client.
            Path file = video();
            String version = file == null ? BUILT_IN : version(file);
            if (version.equals(failedOn) || file != null && "*".equals(failedOn) || now < retryAt) return;
            failedOn = null;
            problem = null;
            decoder = d = new Decoder(file, resumeAt);
            d.start();
        }
        upload(d);
    }

    /** The newest decoded frame, if there's one, onto the graphics card. */
    private void upload(Decoder d) {
        ByteBuffer frame = d.ready.getAndSet(null);
        if (frame == null) return;
        long started = System.nanoTime();
        if (texture == null) {
            texture = new Picture();
            Minecraft.getInstance().getTextureManager().register(TEXTURE, texture);
        }
        NativeImage pixels = texture.getPixels();
        if (pixels != null) {
            MemoryUtil.memCopy(MemoryUtil.memAddress(frame), pixels.getPointer(), FRAME_BYTES);
            texture.upload();
            hasFrame = true;
        }
        d.free.offer(frame);
        uploads++;
        uploadNanos += System.nanoTime() - started;
    }

    private void stop() {
        Decoder d = decoder;
        decoder = null;
        if (d != null) {
            resumeAt = d.position();
            d.close();
        }
        hasFrame = false;
        if (texture != null) {
            Picture t = texture;
            texture = null;
            Minecraft mc = Minecraft.getInstance();
            if (mc.isSameThread()) mc.getTextureManager().release(TEXTURE);
            else mc.execute(() -> mc.getTextureManager().release(TEXTURE));
        }
    }

    /** cyanite/subway.mp4, .webm or .mkv, whichever is there. */
    private static Path video() {
        for (String ext : EXTENSIONS) {
            Path p = Config.dir().resolve("subway." + ext);
            if (Files.isRegularFile(p)) return p;
        }
        return null;
    }

    private static String version(Path file) {
        try {
            return file + "|" + Files.size(file) + "|" + Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return file.toString();
        }
    }

    private static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && Text.width(next) > width) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(next);
            }
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }

    // ---- Decoding

    private record Probe(double fps, double duration) {
    }

    private static final Pattern FPS = Pattern.compile("([0-9.]+) (?:fps|tbr)");
    private static final Pattern DURATION = Pattern.compile("Duration: (\\d+):(\\d+):([0-9.]+)");

    /** The video's frame rate and length (0 if it doesn't say), from what ffmpeg prints about it; null if it has no picture. */
    private static Probe probe(String tool, Path file) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(tool, "-hide_banner", "-nostdin", "-i", file.toString()).redirectErrorStream(true).start();
        RUNNING.add(p);
        String out;
        try (InputStream in = p.getInputStream()) {
            p.getOutputStream().close();
            out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly();
            RUNNING.remove(p);
        }
        double fps = 0;
        for (String line : out.split("\\R")) {
            if (!line.contains(": Video:") || line.contains("attached pic")) continue;
            Matcher m = FPS.matcher(line);
            if (m.find()) fps = Double.parseDouble(m.group(1));
            break;
        }
        if (!(fps > 0)) return null;
        Matcher d = DURATION.matcher(out);
        double duration = d.find() ? Integer.parseInt(d.group(1)) * 3600 + Integer.parseInt(d.group(2)) * 60 + Double.parseDouble(d.group(3)) : 0;
        return new Probe(fps, duration);
    }

    /** Fills a frame buffer to its limit with the next frame; false once there are no more. */
    private interface FrameSource {
        boolean fill(ByteBuffer frame) throws IOException;
    }

    /** One run of ffmpeg (or of the built-in clip), and the thread handing its frames on in time with the video. */
    private final class Decoder extends Thread {
        /** Null for the built-in clip. */
        final Path file;
        final double from;
        final AtomicReference<ByteBuffer> ready = new AtomicReference<>();
        final BlockingQueue<ByteBuffer> free = new ArrayBlockingQueue<>(3);
        volatile boolean ended;
        volatile boolean closedByUs;
        volatile String failure;
        volatile Process process;
        volatile long frames;
        volatile double fps;

        Decoder(Path file, double from) {
            super("Cyanite Subway Surfers");
            setDaemon(true);
            this.file = file;
            this.from = from;
            for (int i = 0; i < 3; i++) free.add(ByteBuffer.allocateDirect(FRAME_BYTES));
        }

        /** Seconds of video played so far, counting every time round. */
        double position() {
            return fps > 0 ? from + frames / fps : from;
        }

        void close() {
            closedByUs = true;
            Process p = process;
            if (p != null) p.destroyForcibly();
            interrupt();
        }

        @Override
        public void run() {
            try {
                decode();
            } catch (IOException e) {
                if (!closedByUs) LOGGER.warn("Subway Surfers: ffmpeg stopped", e);
            } catch (InterruptedException e) {
                // Closed.
            } finally {
                end();
                ended = true;
            }
        }

        /** Stops the ffmpeg running, if there is one, and waits for it to go. */
        private void end() {
            Process p = process;
            if (p == null) return;
            p.destroyForcibly();
            try {
                p.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                // Gone or going.
            }
            RUNNING.remove(p);
        }

        private void decode() throws IOException, InterruptedException {
            if (file == null) {
                playClip();
                return;
            }
            String tool = Ffmpeg.find();
            if (tool == null) {
                failure = NO_FFMPEG;
                return;
            }
            if (closedByUs) return;
            Probe probe = probe(tool, file);
            if (probe == null) {
                failure = "Can't play " + file.getFileName();
                return;
            }
            double rate = Math.min(probe.fps, MAX_FPS);
            double start = probe.duration > 0 ? from % probe.duration : 0;
            fps = rate;
            // The graphics card's video decoder takes well over half the work off the processor. Where it
            // can't manage the video, ffmpeg does it all itself.
            stream(tool, rate, start, true);
            if (frames > 0 || closedByUs) return;
            end();
            LOGGER.info("Subway Surfers: no picture with hardware decoding, decoding {} on the processor", file.getFileName());
            stream(tool, rate, start, false);
            if (frames == 0 && !closedByUs) failure = "Can't play " + file.getFileName();
        }

        /** Runs ffmpeg and hands its frames on in time, until it stops or there's no one watching. */
        private void stream(String tool, double rate, double start, boolean hardware) throws IOException, InterruptedException {
            List<String> cmd = new ArrayList<>(List.of(tool, "-hide_banner", "-loglevel", "error", "-nostdin"));
            if (hardware) cmd.addAll(List.of("-hwaccel", "auto"));
            cmd.addAll(List.of("-threads", "2", "-stream_loop", "-1"));
            if (start > 0) cmd.addAll(List.of("-ss", String.format(Locale.ROOT, "%.3f", start)));
            cmd.addAll(List.of("-i", file.toString(), "-an", "-sn", "-dn",
                // Filled and cropped to 9:16, like Shorts, whatever shape it was.
                "-vf", "scale=" + VIDEO_W + ":" + VIDEO_H + ":force_original_aspect_ratio=increase:flags=area,crop=" + VIDEO_W + ":" + VIDEO_H
                    + ",fps=" + String.format(Locale.ROOT, "%.3f", rate),
                "-pix_fmt", "rgba", "-f", "rawvideo", "pipe:1"));
            if (closedByUs) return;
            Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            process = p;
            RUNNING.add(p);
            if (closedByUs) return;
            p.getOutputStream().close();

            ReadableByteChannel in = Channels.newChannel(p.getInputStream());
            pump(rate, frame -> {
                while (frame.hasRemaining()) {
                    if (in.read(frame) < 0) return false;
                }
                return true;
            });
        }

        /** The built-in clip, round and round from where it was left, decoded a frame at a time. */
        private void playClip() throws InterruptedException {
            Clip clip;
            try {
                clip = Clip.get();
            } catch (IOException e) {
                LOGGER.warn("Subway Surfers: the built-in clip didn't load", e);
                failure = "Can't play the built-in clip";
                return;
            }
            fps = clip.fps();
            int[] next = {(int) Math.round(from % clip.duration() * clip.fps()) % clip.count()};
            try {
                pump(clip.fps(), frame -> {
                    clip.decode(next[0], frame);
                    next[0] = (next[0] + 1) % clip.count();
                    return true;
                });
            } catch (IOException e) {
                LOGGER.warn("Subway Surfers: a frame of the built-in clip didn't decode", e);
                failure = "Can't play the built-in clip";
            }
        }

        /** Hands frames from {@code source} on in time, until it runs out or there's no one watching. */
        private void pump(double rate, FrameSource source) throws IOException, InterruptedException {
            long frameNanos = Math.round(1e9 / rate);
            long due = 0;
            boolean restart = true;
            while (!closedByUs) {
                long hidden = System.nanoTime() - lastWanted;
                if (hidden > CLOSE_NS) {
                    // Off screen a good while: stop; it carries on from here when it's back.
                    closedByUs = true;
                    return;
                }
                if (hidden > PAUSE_NS) {
                    // ffmpeg waits on the pipe meanwhile, costing nothing; the clip just isn't decoded.
                    restart = true;
                    Thread.sleep(50);
                    continue;
                }
                ByteBuffer frame = free.poll(100, TimeUnit.MILLISECONDS);
                if (frame == null) continue;
                frame.clear();
                if (!source.fill(frame)) {
                    free.offer(frame);
                    return;
                }
                long now = System.nanoTime();
                if (restart || now - due > 250_000_000L) {
                    // The first frame, back from a pause, or fallen behind: the clock starts again from now.
                    due = now;
                    restart = false;
                }
                if (due > now) LockSupport.parkNanos(due - now);
                frame.flip();
                ByteBuffer old = ready.getAndSet(frame);
                if (old != null) free.offer(old);
                frames++;
                due += frameNanos;
            }
        }
    }

    /**
     * The clip that comes with the client: its frames as JPEGs (see dev/subway_clip.py for the layout), read once
     * and kept, then decoded one at a time with STB, which the game already has, as they're shown.
     */
    private record Clip(ByteBuffer data, int[] offsets, int[] lengths, double fps) {
        private static final int MAGIC = 0x43595631; // "CYV1"
        private static volatile Clip loaded;

        static Clip get() throws IOException {
            Clip clip = loaded;
            if (clip == null) loaded = clip = read();
            return clip;
        }

        private static Clip read() throws IOException {
            try (InputStream raw = SubwaySurfers.class.getResourceAsStream(CLIP)) {
                if (raw == null) throw new IOException(CLIP + " isn't in the jar");
                DataInputStream in = new DataInputStream(new BufferedInputStream(raw));
                if (in.readInt() != MAGIC) throw new IOException(CLIP + " isn't a clip");
                int w = in.readUnsignedShort();
                int h = in.readUnsignedShort();
                if (w != VIDEO_W || h != VIDEO_H) throw new IOException("the clip is " + w + "x" + h);
                double fps = in.readInt() / 1000.0;
                int count = in.readInt();
                if (!(fps > 0) || count <= 0) throw new IOException("the clip is empty");
                int[] offsets = new int[count];
                int[] lengths = new int[count];
                int total = 0;
                for (int i = 0; i < count; i++) {
                    offsets[i] = total;
                    lengths[i] = in.readInt();
                    total += lengths[i];
                }
                byte[] bytes = in.readNBytes(total);
                if (bytes.length != total) throw new IOException("the clip is cut short");
                ByteBuffer data = ByteBuffer.allocateDirect(total).put(bytes).flip();
                return new Clip(data, offsets, lengths, fps);
            }
        }

        int count() {
            return offsets.length;
        }

        double duration() {
            return count() / fps;
        }

        /** Frame {@code index} as RGBA into {@code frame}, filling it to its limit. */
        void decode(int index, ByteBuffer frame) throws IOException {
            ByteBuffer jpeg = data.slice(offsets[index], lengths[index]);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer w = stack.mallocInt(1);
                IntBuffer h = stack.mallocInt(1);
                IntBuffer channels = stack.mallocInt(1);
                ByteBuffer rgba = STBImage.stbi_load_from_memory(jpeg, w, h, channels, 4);
                if (rgba == null) throw new IOException("frame " + index + ": " + STBImage.stbi_failure_reason());
                try {
                    if (w.get(0) != VIDEO_W || h.get(0) != VIDEO_H) throw new IOException("frame " + index + " is " + w.get(0) + "x" + h.get(0));
                    MemoryUtil.memCopy(MemoryUtil.memAddress(rgba), MemoryUtil.memAddress(frame), FRAME_BYTES);
                } finally {
                    STBImage.stbi_image_free(rgba);
                }
            }
            frame.position(FRAME_BYTES);
        }
    }

    /** The video's texture, smoothly scaled. */
    private static final class Picture extends DynamicTexture {
        Picture() {
            super(() -> "Cyanite Subway Surfers", VIDEO_W, VIDEO_H, false);
            sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        }
    }
}
