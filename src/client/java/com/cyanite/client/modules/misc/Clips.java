package com.cyanite.client.modules.misc;

import com.cyanite.client.gui.Theme;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Notify;
import com.cyanite.client.system.Stealth;
import com.cyanite.client.system.setting.KeybindSetting;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.SliderSetting;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.logging.LogUtils;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Clips: keeps the last minute of what's on your screen, so when something happens (a base, a
 * fight) one key saves it as an MP4 in .minecraft/clips. No sound. Needs ffmpeg.
 *
 * <p>It works like a replay buffer. Up to 30 times a second the finished picture is copied off the
 * graphics card the way F2 screenshots are, without waiting for it, and handed to ffmpeg, which
 * shrinks it and encodes it on the graphics card's own video encoder when there is one (NVIDIA,
 * AMD or Intel), so it costs the game very little. ffmpeg keeps it as two-second pieces on disk,
 * reusing the oldest; saving just joins the latest pieces into an MP4, no re-encoding, so it's
 * done in a moment. Only switched on does it record, so switch it on before you go looking.
 */
public class Clips extends Module {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final int FPS = 30;
    private static final int PIECE_SECONDS = 2;
    /** Frames waiting for ffmpeg; past this it's behind and frames are skipped (ffmpeg fills the gaps). */
    private static final int MAX_QUEUED = 3;

    private final KeybindSetting saveKey = add(new KeybindSetting("Save clip", GLFW.GLFW_KEY_F8));
    private final SliderSetting length = add(new SliderSetting("Length", 60, 10, 120, 5).format(v -> (int) (double) v + " s"));
    private final ModeSetting quality = add(new ModeSetting("Quality", "High", List.of("Low", "Medium", "High")));

    /** A buffer on the graphics card a frame is copied into, and whether a copy's on its way. */
    private static final class Slot {
        GpuBuffer buffer;
        boolean busy;
        boolean dropAfter;
    }

    private final Slot[] slots = {new Slot(), new Slot(), new Slot()};
    private int slotW;
    private int slotH;
    private long lastCapture;
    private ClientLevel lastLevel;
    private Session session;
    /** ffmpeg stopped soon after starting: don't keep relaunching it every frame. */
    private long retryAt;
    private boolean warned;
    private final AtomicBoolean saving = new AtomicBoolean();
    private static String ffmpeg;
    private static boolean ffmpegLooked;
    private static List<String> encoder;

    public Clips() {
        super(Category.MISC, "Clips", "Keeps the last minute of gameplay; F8 saves it as a video");
    }

    @Override
    public String getInfo() {
        Session s = session;
        if (s == null) return "0s";
        return Math.min(length.get().intValue(), (System.nanoTime() - s.started) / 1_000_000_000L) + "s";
    }

    @Override
    protected void onEnable() {
        lastCapture = 0;
        lastLevel = null;
        retryAt = 0;
        warned = false;
        // Looking for ffmpeg can take a moment the first time: not on the game's thread.
        Thread look = new Thread(() -> {
            if (ffmpeg() == null) {
                mc.execute(() -> {
                    Notify.send("Clips", "Clips needs ffmpeg to record. Install it (on Windows: winget install ffmpeg), then switch Clips on again.", true, false, false);
                    setEnabled(false);
                });
            }
        }, "Cyanite clips setup");
        look.setDaemon(true);
        look.start();
    }

    @Override
    protected void onDisable() {
        stopSession();
        dropSlots();
    }

    @Override
    public void onKey(int key) {
        if (saveKey.isBound() && key == saveKey.get()) save();
    }

    @Override
    public void onTick() {
        if (mc.level != lastLevel) {
            // A clip never runs from one world into another.
            lastLevel = mc.level;
            stopSession();
        }
    }

    // ---- Recording

    /** Target height for the quality; ffmpeg shrinks to it. */
    private int targetHeight() {
        return switch (quality.get()) {
            case "Low" -> 480;
            case "Medium" -> 720;
            default -> 1080;
        };
    }

    private String bitrate() {
        return switch (quality.get()) {
            case "Low" -> "3M";
            case "Medium" -> "6M";
            default -> "10M";
        };
    }

    @Override
    public void onFrame() {
        if (ffmpeg == null || encoder == null) return;
        long now = System.nanoTime();
        if (now - lastCapture < 1_000_000_000L / FPS - 2_000_000L) return;
        RenderTarget target = mc.getMainRenderTarget();
        GpuTexture texture = target.getColorTexture();
        if (texture == null || texture.getFormat().pixelSize() != 4) return;
        int w = target.width;
        int h = target.height;
        if (w <= 0 || h <= 0) return;
        if (w != slotW || h != slotH) {
            // New size: a new recording. One video, one size.
            dropSlots();
            stopSession();
            slotW = w;
            slotH = h;
        }
        Session s = session;
        if (s == null || s.dead()) {
            if (s != null) {
                boolean early = now - s.started < 5_000_000_000L;
                stopSession();
                if (early) {
                    retryAt = now + 15_000_000_000L;
                    if (!warned) {
                        warned = true;
                        Notify.send("Clips", "ffmpeg stopped recording (see ffmpeg.log in clips/.buffer). Trying again in a bit.", true, false, false);
                    }
                    return;
                }
            }
            if (now < retryAt) return;
            s = session = startSession(w, h);
            if (s == null) {
                retryAt = now + 15_000_000_000L;
                return;
            }
        }
        if (s.queue.remainingCapacity() == 0) return;
        Slot slot = null;
        for (Slot candidate : slots) {
            if (!candidate.busy) {
                slot = candidate;
                break;
            }
        }
        if (slot == null) return;
        lastCapture = now;
        GpuDevice device = RenderSystem.getDevice();
        if (slot.buffer == null) slot.buffer = device.createBuffer(() -> "Cyanite clip frame", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) w * h * 4);
        slot.busy = true;
        Slot taken = slot;
        Session into = s;
        CommandEncoder commands = device.createCommandEncoder();
        commands.copyTextureToBuffer(texture, slot.buffer, 0, () -> copied(taken, commands, into, w * h * 4), 0);
    }

    /** The copy's arrived: take it off the graphics card and queue it for ffmpeg. */
    private void copied(Slot slot, CommandEncoder commands, Session s, int size) {
        slot.busy = false;
        if (slot.dropAfter || slot.buffer == null || slot.buffer.isClosed()) {
            if (slot.buffer != null && !slot.buffer.isClosed()) slot.buffer.close();
            slot.buffer = null;
            slot.dropAfter = false;
            return;
        }
        if (s != session || s.dead()) return;
        ByteBuffer frame = s.spare.poll();
        if (frame == null || frame.capacity() != size) frame = ByteBuffer.allocateDirect(size);
        try (GpuBuffer.MappedView view = commands.mapBuffer(slot.buffer, true, false)) {
            frame.clear();
            frame.put(0, view.data(), 0, size);
        }
        if (!s.queue.offer(frame)) s.spare.offer(frame);
    }

    private void dropSlots() {
        for (Slot s : slots) {
            if (s.busy) {
                s.dropAfter = true;
            } else if (s.buffer != null) {
                s.buffer.close();
                s.buffer = null;
            }
        }
    }

    private static Path clipsDir() {
        return mc.gameDirectory.toPath().resolve("clips");
    }

    private static Path bufferDir() {
        return clipsDir().resolve(".buffer");
    }

    /**
     * ffmpeg, fed raw frames, keeping the last stretch as two-second pieces it reuses in turn. Each
     * recording has its own folder: the last one's ffmpeg may still be closing its files, and Windows
     * won't delete a file that's open, so old folders go once their ffmpeg has stopped.
     */
    private Session startSession(int w, int h) {
        Path dir = bufferDir().resolve("rec" + System.currentTimeMillis());
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOGGER.warn("Clips: can't use {}", dir, e);
            return null;
        }
        sweepOld(dir);
        int outH = Math.min(targetHeight(), h) & ~1;
        int pieces = (int) Math.ceil(length.get() / PIECE_SECONDS) + 5;
        List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin",
            "-f", "rawvideo", "-pix_fmt", "rgba", "-video_size", w + "x" + h, "-use_wallclock_as_timestamps", "1", "-i", "pipe:0",
            // The copy comes bottom row first.
            "-vf", "vflip,scale=-2:" + outH + ":flags=bicubic,format=yuv420p",
            "-fps_mode", "cfr", "-r", String.valueOf(FPS)));
        cmd.addAll(encoder);
        cmd.addAll(List.of("-b:v", bitrate(), "-g", String.valueOf(FPS * PIECE_SECONDS),
            "-force_key_frames", "expr:gte(t,n_forced*" + PIECE_SECONDS + ")",
            "-f", "segment", "-segment_time", String.valueOf(PIECE_SECONDS), "-segment_wrap", String.valueOf(pieces),
            "-reset_timestamps", "1", "-segment_format", "mpegts", dir.resolve("piece%03d.ts").toString()));
        try {
            Process process = new ProcessBuilder(cmd).redirectErrorStream(true)
                .redirectOutput(dir.resolve("ffmpeg.log").toFile()).start();
            Session s = new Session(process, dir, w, h);
            s.writer.start();
            return s;
        } catch (IOException e) {
            LOGGER.warn("Clips: couldn't start ffmpeg", e);
            return null;
        }
    }

    private void stopSession() {
        Session s = session;
        session = null;
        if (s != null) s.close();
    }

    /** Deletes a recording's folder; anything still open stays for next time. */
    private static void delete(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.toList()) Files.deleteIfExists(f);
            Files.deleteIfExists(dir);
        } catch (IOException ignored) {
            // Still in use; swept up later.
        }
    }

    /** Old recordings' folders, left by a crash or by ffmpeg taking its time to close. */
    private static void sweepOld(Path keep) {
        try (Stream<Path> dirs = Files.list(keep.getParent())) {
            for (Path d : dirs.toList()) if (!d.equals(keep) && Files.isDirectory(d)) delete(d);
        } catch (IOException ignored) {
            // Nothing to sweep.
        }
    }

    /** One recording: an ffmpeg process and the thread feeding it frames. */
    private static final class Session {
        final Process process;
        final Path dir;
        final long started = System.nanoTime();
        final BlockingQueue<ByteBuffer> queue = new ArrayBlockingQueue<>(MAX_QUEUED);
        final BlockingQueue<ByteBuffer> spare = new ArrayBlockingQueue<>(MAX_QUEUED + 3);
        final Thread writer;
        volatile boolean closed;

        Session(Process process, Path dir, int w, int h) {
            this.process = process;
            this.dir = dir;
            OutputStream stdin = process.getOutputStream();
            WritableByteChannel channel = Channels.newChannel(stdin);
            writer = new Thread(() -> {
                try {
                    while (!closed) {
                        ByteBuffer frame = queue.poll(250, TimeUnit.MILLISECONDS);
                        if (frame == null) continue;
                        frame.position(0).limit(w * h * 4);
                        while (frame.hasRemaining()) channel.write(frame);
                        spare.offer(frame);
                    }
                } catch (IOException | InterruptedException e) {
                    // ffmpeg went away, or we're closing.
                } finally {
                    try {
                        stdin.close();
                    } catch (IOException ignored) {
                        // Closing anyway.
                    }
                }
            }, "Cyanite clips writer");
            writer.setDaemon(true);
        }

        boolean dead() {
            return closed || !process.isAlive();
        }

        void close() {
            closed = true;
            writer.interrupt();
            // Closing ffmpeg's input lets it finish the piece it's on and stop.
            Thread waiter = new Thread(() -> {
                try {
                    if (!process.waitFor(5, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        process.waitFor(5, TimeUnit.SECONDS);
                    }
                } catch (InterruptedException e) {
                    process.destroyForcibly();
                }
                delete(dir);
            }, "Cyanite clips stop");
            waiter.setDaemon(true);
            waiter.start();
        }
    }

    // ---- Saving

    /** Saves the last stretch: joins the latest pieces into an MP4. */
    public void save() {
        Session s = session;
        if (s == null || s.dead() || (System.nanoTime() - s.started) < 2_000_000_000L) {
            tell(ffmpeg == null ? "Clips needs ffmpeg to record." : "Nothing to save yet: Clips keeps what's on screen from when it's switched on.");
            return;
        }
        if (!saving.compareAndSet(false, true)) {
            tell("Still saving the last clip.");
            return;
        }
        double wanted = length.get();
        Path from = s.dir;
        tell("Saving...");
        Thread writer = new Thread(() -> {
            try {
                write(from, wanted);
            } finally {
                saving.set(false);
            }
        }, "Cyanite clip saver");
        writer.setDaemon(true);
        writer.start();
    }

    private void write(Path dir, double wanted) {
        Path out = clipsDir().resolve("clip_" + LocalDateTime.now().format(STAMP) + ".mp4");
        Path list = dir.resolve("join.txt");
        try {
            // The pieces from the last stretch, oldest first; the newest is still being written, which ffmpeg copes with.
            long cutoff = System.currentTimeMillis() - (long) ((wanted + PIECE_SECONDS) * 1000);
            List<Path> pieces;
            try (Stream<Path> files = Files.list(dir)) {
                pieces = files.filter(p -> p.getFileName().toString().endsWith(".ts"))
                    .filter(p -> modified(p) >= cutoff && size(p) > 0)
                    .sorted(Comparator.comparingLong(Clips::modified)).toList();
            }
            if (pieces.isEmpty()) {
                tell("Nothing recorded yet.");
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (Path p : pieces) sb.append("file '").append(p.toAbsolutePath().toString().replace("'", "'\\''")).append("'\n");
            Files.writeString(list, sb.toString());
            Process p = new ProcessBuilder(ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-f", "concat", "-safe", "0", "-i", list.toString(),
                "-c", "copy", "-movflags", "+faststart", out.toString())
                .redirectErrorStream(true).redirectOutput(dir.resolve("join.log").toFile()).start();
            if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0 || !Files.exists(out) || Files.size(out) == 0) {
                p.destroyForcibly();
                tell("Couldn't save the clip (see join.log in " + dir + ").");
                return;
            }
        } catch (IOException | InterruptedException e) {
            tell("Couldn't save the clip: " + e.getMessage());
            return;
        }
        long bytes = size(out);
        String what = String.format(Locale.ROOT, "Saved %s (%.1f MB)", out.getFileName(), bytes / 1048576.0);
        Path folder = clipsDir();
        mc.execute(() -> {
            Notify.send("Clips", what, true, false, false);
            if (Stealth.active()) return;
            Component link = Component.literal("  Open clips folder").withStyle(st -> st
                .withColor(Theme.accent() & 0xFFFFFF).withUnderlined(true).withClickEvent(new ClickEvent.OpenFile(folder)));
            Stealth.wrote(link);
            mc.gui.getChat().addMessage(link);
        });
    }

    private static long modified(Path p) {
        try {
            FileTime t = Files.getLastModifiedTime(p);
            return t.toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static long size(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }

    private void tell(String text) {
        mc.execute(() -> Notify.send("Clips", text, false, false, false));
    }

    // ---- ffmpeg

    /** ffmpeg on the PATH, or where WinGet puts it, and the best encoder it can use; null if it's not there. */
    private static synchronized String ffmpeg() {
        if (ffmpegLooked) return ffmpeg;
        ffmpegLooked = true;
        List<String> tries = new ArrayList<>(List.of("ffmpeg"));
        String local = System.getenv("LOCALAPPDATA");
        if (local != null) {
            Path winget = Path.of(local, "Microsoft", "WinGet");
            tries.add(winget.resolve("Links").resolve("ffmpeg.exe").toString());
            try (Stream<Path> found = Files.find(winget.resolve("Packages"), 5,
                (p, a) -> p.getFileName().toString().equalsIgnoreCase("ffmpeg.exe") && p.toString().toLowerCase(Locale.ROOT).contains("ffmpeg"))) {
                found.forEach(p -> tries.add(p.toString()));
            } catch (IOException | RuntimeException ignored) {
                // No WinGet packages.
            }
        }
        for (String tool : tries) {
            if (runs(List.of(tool, "-version"))) {
                encoder = bestEncoder(tool);
                ffmpeg = tool;
                LOGGER.info("Clips: using {} with {}", tool, encoder);
                return tool;
            }
        }
        return null;
    }

    /** The graphics card's encoder if it has one that works, else the quickest CPU one. */
    private static List<String> bestEncoder(String tool) {
        List<List<String>> options = List.of(
            List.of("-c:v", "h264_nvenc", "-preset", "p2", "-forced-idr", "1"),
            List.of("-c:v", "h264_amf", "-quality", "speed"),
            List.of("-c:v", "h264_qsv", "-preset", "veryfast"),
            List.of("-c:v", "libx264", "-preset", "ultrafast", "-threads", "2"));
        for (List<String> option : options) {
            List<String> test = new ArrayList<>(List.of(tool, "-hide_banner", "-loglevel", "error", "-f", "lavfi",
                "-i", "color=c=black:s=640x360:r=30:d=0.3"));
            test.addAll(option);
            test.addAll(List.of("-f", "null", "-"));
            if (runs(test)) return option;
        }
        return options.get(options.size() - 1);
    }

    private static boolean runs(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (p.waitFor(15, TimeUnit.SECONDS) && p.exitValue() == 0) return true;
            p.destroyForcibly();
        } catch (IOException | InterruptedException ignored) {
            // Not this one.
        }
        return false;
    }
}
