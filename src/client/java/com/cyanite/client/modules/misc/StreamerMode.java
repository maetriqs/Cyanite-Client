package com.cyanite.client.modules.misc;

import com.cyanite.client.config.Config;
import com.cyanite.client.modules.world.ChestIndex;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.Notify;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Like Discord's streamer mode: while OBS or another streaming or recording app is open (or
 * always, if you'd rather), coordinates come off your screen: F3's position lines, and the
 * coordinates in Cyanite's own messages and labels. And whether you're streaming or not, it
 * stops you sending your base's coordinates in chat by accident: a message or command with
 * coordinates near your bases, your chests or where you're standing is held back, and sending
 * it again straight away lets it through.
 */
public class StreamerMode extends Module {
    /** Streaming and recording apps, by executable name in lower case, without ".exe". */
    private static final Set<String> APPS = Set.of(
        "obs64", "obs32", "obs", "streamlabs obs", "streamlabs desktop", "xsplit.core", "twitch studio", "twitchstudio",
        "prismlivestudio", "vmix64", "vmix", "wirecast", "bdcam", "bandicam");
    private static final Pattern SHOWN = Pattern.compile("-?\\d+(?:\\.\\d+)?, ?-?\\d+(?:\\.\\d+)?, ?-?\\d+(?:\\.\\d+)?");
    private static final Pattern TRIPLE = Pattern.compile("(-?\\d{1,8})[ ,]+(-?\\d{1,3})[ ,]+(-?\\d{1,8})");
    private static final Pattern PAIR = Pattern.compile("(-?\\d{3,8})[ ,]+(-?\\d{3,8})");
    private static final long SEND_ANYWAY = 15_000;

    private final ModeSetting when = add(new ModeSetting("Hide coords", "While streaming", List.of("While streaming", "Always")));
    private final BoolSetting f3 = add(new BoolSetting("Hide F3 coords", true));
    private final BoolSetting guard = add(new BoolSetting("Guard chat", true));
    private final SliderSetting radius = add(new SliderSetting("Guard radius", 300, 50, 2000, 50).format(v -> (int) v + " blocks"));

    private ScheduledExecutorService scanner;
    private volatile String app;
    private String held;
    private long heldAt;
    private final List<String> bases = new ArrayList<>();
    private boolean basesLoaded;

    public StreamerMode() {
        super(Category.MISC, "Streamer Mode", "Hides coordinates while you stream and stops you sending your base's coordinates");
    }

    @Override
    public String getInfo() {
        return hiding() ? (app != null ? "Live" : "On") : null;
    }

    @Override
    protected void onEnable() {
        app = null;
        scanner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Cyanite streamer mode");
            t.setDaemon(true);
            return t;
        });
        // Looking through running programs takes a moment, so it's done off the game thread.
        scanner.scheduleWithFixedDelay(this::scan, 0, 5, TimeUnit.SECONDS);
    }

    @Override
    protected void onDisable() {
        if (scanner != null) scanner.shutdownNow();
        scanner = null;
        app = null;
    }

    private void scan() {
        String found = null;
        try {
            for (ProcessHandle p : (Iterable<ProcessHandle>) ProcessHandle.allProcesses()::iterator) {
                Optional<String> command = p.info().command();
                if (command.isEmpty()) continue;
                String file = Path.of(command.get()).getFileName().toString().toLowerCase(Locale.ROOT);
                if (file.endsWith(".exe")) file = file.substring(0, file.length() - 4);
                if (APPS.contains(file)) {
                    found = file;
                    break;
                }
            }
        } catch (Exception ignored) {
            // Can't see other programs: stay as we were.
            return;
        }
        String before = app;
        String now = found;
        app = now;
        if (now != null && before == null && when.is("While streaming")) {
            mc.execute(() -> Notify.send("Streamer Mode", "Spotted " + now + ": coordinates are hidden now", false, false, false));
        }
    }

    // ---- Hiding

    /** Whether coordinates are off the screen right now. */
    public static boolean hiding() {
        StreamerMode self = Modules.get(StreamerMode.class);
        return self != null && self.isEnabled() && (self.when.is("Always") || self.app != null);
    }

    /** Whether F3's position lines should go. */
    public static boolean hidesF3() {
        StreamerMode self = Modules.get(StreamerMode.class);
        return hiding() && self.f3.get();
    }

    /** {@code text} with any "x, y, z" taken out while hiding. */
    public static String mask(String text) {
        if (text == null || !hiding()) return text;
        return SHOWN.matcher(text).replaceAll("[coords hidden]");
    }

    // ---- Guarding chat

    /** A message or command about to go out. @return false to hold it back. */
    public static boolean allowOutgoing(String text) {
        StreamerMode self = Modules.get(StreamerMode.class);
        if (self == null || !self.isEnabled() || !self.guard.get() || mc.player == null) return true;
        long now = System.currentTimeMillis();
        // The same thing again straight after the warning: you meant it.
        if (text.equals(self.held) && now - self.heldAt < SEND_ANYWAY) {
            self.held = null;
            return true;
        }
        String near = self.nearSomething(text);
        if (near == null) return true;
        self.held = text;
        self.heldAt = now;
        Notify.send("Streamer Mode", "Held back a message with coordinates near " + near + ". Send it again to send it anyway", true, true, false);
        return false;
    }

    /** What the coordinates in {@code text} are near, or null if none of them are near anything of yours. */
    private String nearSomething(String text) {
        List<int[]> found = new ArrayList<>();
        Matcher m = TRIPLE.matcher(text);
        while (m.find()) {
            int y = Integer.parseInt(m.group(2));
            if (y >= -64 && y <= 320) found.add(new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(3))});
        }
        if (found.isEmpty()) {
            m = PAIR.matcher(text);
            while (m.find()) found.add(new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))});
        }
        int r = radius.get().intValue();
        for (int[] xz : found) {
            if (Math.hypot(xz[0] - mc.player.getX(), xz[1] - mc.player.getZ()) <= r) return "where you are";
            ChestIndex chests = Modules.get(ChestIndex.class);
            if (chests != null && chests.anyNear(xz[0], xz[1], r)) return "your chests";
            for (String b : bases()) {
                String[] parts = b.split("\\|");
                if (parts.length != 4 || !parts[0].equals(server())) continue;
                if (Math.hypot(xz[0] - Integer.parseInt(parts[1]), xz[1] - Integer.parseInt(parts[3])) <= r) return "your base";
            }
        }
        return null;
    }

    /** Remembers a spot as yours (Bedrock Burrow's pockets), for the chat guard. */
    public static void protect(BlockPos pos) {
        StreamerMode self = Modules.get(StreamerMode.class);
        if (self == null) return;
        String line = server() + "|" + pos.getX() + "|" + pos.getY() + "|" + pos.getZ();
        if (self.bases().contains(line)) return;
        self.bases.add(line);
        try {
            Files.createDirectories(file().getParent());
            Files.write(file(), self.bases, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // Kept for this session anyway.
        }
    }

    /** Whether a spot is within {@code r} of a base remembered as yours on this server (Base Finder skips those). */
    public static boolean ownBaseNear(int x, int z, int r) {
        StreamerMode self = Modules.get(StreamerMode.class);
        if (self == null) return false;
        for (String b : self.bases()) {
            String[] parts = b.split("\\|");
            if (parts.length != 4 || !parts[0].equals(server())) continue;
            try {
                if (Math.hypot(x - Integer.parseInt(parts[1]), z - Integer.parseInt(parts[3])) <= r) return true;
            } catch (NumberFormatException ignored) {
                // A line someone typed wrong.
            }
        }
        return false;
    }

    private List<String> bases() {
        if (!basesLoaded) {
            basesLoaded = true;
            try {
                if (Files.exists(file())) bases.addAll(Files.readAllLines(file(), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // Nothing remembered yet.
            }
        }
        return bases;
    }

    private static Path file() {
        return Config.dir().resolve("bases.txt");
    }

    private static String server() {
        if (mc.getCurrentServer() != null) return mc.getCurrentServer().ip.toLowerCase(Locale.ROOT);
        return "singleplayer";
    }
}
