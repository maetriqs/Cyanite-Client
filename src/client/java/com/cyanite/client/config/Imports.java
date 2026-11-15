package com.cyanite.client.config;

import com.cyanite.client.CyaniteClientMod;
import com.cyanite.client.modules.render.Waypoints;
import com.cyanite.client.system.Macros;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.macro.Macro;
import com.cyanite.client.system.setting.BlockListSetting;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.KeybindSetting;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.Setting;
import com.cyanite.client.system.setting.SliderSetting;
import com.cyanite.client.system.setting.StringSetting;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Copies another client's setup into Cyanite: Meteor Client's modules, profiles and waypoints
 * ({@link MeteorImport}), Wurst's hacks ({@link WurstImport}) and Xaero's Minimap waypoints
 * ({@link XaeroImport}). Each of their modules comes over as the Cyanite one that does the same job:
 * what was on comes on, keys come over, and so does each setting that means the same thing. Waypoints
 * go onto the same server's list. Their files are only read.
 *
 * <p>Asked once per client, on the title screen, the first time its folder has something to copy;
 * Profiles' Import starts it again any time.
 */
public final class Imports {
    static final Logger LOGGER = LogUtils.getLogger();
    static final String BIND = "Bind";
    /** Cyanite modules that start working by themselves the moment they're on: an import never switches them on. */
    private static final Set<String> NEVER_ON = Set.of(
        "Auto Mine", "Area Miner", "Bedrock Burrow", "Schematic Builder", "Auto Elytra", "Auto Sell", "Fake Pay");
    /** Far past any real file of theirs (a full Meteor modules.nbt is under 100 KB). */
    static final long MAX_FILE = 16L << 20;

    /** The clients asked about (either answer): not asked again. Saved in config.json. */
    private static final Set<Source> asked = EnumSet.noneOf(Source.class);

    private Imports() {
    }

    public enum Source {
        METEOR("meteor", "Meteor Client", "Meteor"),
        WURST("wurst", "Wurst", "Wurst"),
        XAERO("xaero", "Xaero's Minimap", "Xaero");

        final String id;
        public final String title;
        public final String shortName;

        Source(String id, String title, String shortName) {
            this.id = id;
            this.title = title;
            this.shortName = shortName;
        }

        /** Whether it has a folder in the game folder at all. */
        boolean present() {
            return switch (this) {
                case METEOR -> Files.isDirectory(MeteorImport.dir());
                case WURST -> Files.isDirectory(WurstImport.dir());
                case XAERO -> XaeroImport.roots().stream().anyMatch(Files::isDirectory);
            };
        }

        /** Whether it can bring modules over, not just waypoints. */
        public boolean hasModules() {
            return this != XAERO;
        }
    }

    // ---- What there is, and copying it

    /** What there is to copy from one client: modules (Cyanite's names), its profiles, new waypoints and where, and what has no match. */
    public record Preview(Source source, List<String> modules, List<String> profiles, int waypoints, int places, List<String> leftOut) {
        public boolean isEmpty() {
            return modules.isEmpty() && profiles.isEmpty() && waypoints == 0;
        }
    }

    /** What copying from one client did. {@code problem} says why nothing was, when none of it could be read. */
    public record Result(Source source, List<String> modules, int on, int binds, int settings, List<String> keysTaken,
                         List<String> profiles, int waypoints, int places, List<String> leftOut, String problem) {
    }

    /**
     * Every client's result; the profile the setup from before was kept as (null when no modules changed);
     * whether Waypoints was switched on to show what came; or why nothing was done.
     */
    public record Outcome(List<Result> results, String backup, boolean waypointsOn, String problem) {
    }

    /** The clients here with something to copy, ones already asked about left out unless {@code all}. */
    public static List<Preview> found(boolean all) {
        List<Preview> found = new ArrayList<>();
        for (Source source : Source.values()) {
            if ((!all && asked.contains(source)) || !source.present()) continue;
            Preview preview = preview(source);
            if (!preview.isEmpty()) found.add(preview);
        }
        return found;
    }

    /** Whether any client's folder is here, for the Import button. */
    public static boolean anyPresent() {
        for (Source source : Source.values()) if (source.present()) return true;
        return false;
    }

    public static Preview preview(Source source) {
        Converted live = live(source);
        int[] counted = countNew(waypoints(source));
        return new Preview(source, live == null ? List.of() : live.names(),
            source == Source.METEOR ? MeteorImport.readableProfiles() : List.of(),
            counted[0], counted[1], live == null ? List.of() : live.leftOut());
    }

    /**
     * Copies each client in turn, after saving the setup as it is as a profile if any modules will change.
     * Nothing is switched off, and only waypoints that aren't there yet are added.
     */
    public static Outcome copy(List<Source> sources) {
        asked.addAll(sources);
        List<String> changing = new ArrayList<>();
        for (Source source : sources) {
            Converted live = live(source);
            if (live != null && !live.names().isEmpty()) changing.add(source.shortName);
        }
        String backup = null;
        if (!changing.isEmpty()) {
            backup = Profiles.freeName(Profiles.sanitize("Before " + String.join(" and ", changing)));
            if (!Config.write(Profiles.dir().resolve(backup + Profiles.EXT), Config.snapshot(false))) {
                Config.save();
                return new Outcome(List.of(), null, false, "Couldn't save your setup first, so nothing was changed");
            }
        }

        List<Result> results = new ArrayList<>();
        int allWaypoints = 0;
        for (Source source : sources) {
            // Converted again once the one before has come over, so keys it took count as taken.
            Converted live = live(source);
            if (live != null && !live.names().isEmpty()) Config.apply(live.asProfile(), false);
            List<String> profiles = source == Source.METEOR ? MeteorImport.copyProfiles() : List.of();
            Map<String, List<Waypoints.Waypoint>> waypoints = waypoints(source);
            int[] added = addWaypoints(waypoints);
            allWaypoints += added[0];

            boolean readNothing = live == null && profiles.isEmpty() && waypoints.isEmpty();
            results.add(live == null
                ? new Result(source, List.of(), 0, 0, 0, List.of(), profiles, added[0], added[1], List.of(),
                    readNothing ? "Couldn't read " + source.shortName + "'s files" : null)
                : new Result(source, live.names(), live.on(), live.binds(), live.settings(), live.keysTaken(), profiles,
                    added[0], added[1], live.leftOut(), null));
        }

        // Waypoints that came over should show.
        Waypoints waypointsModule = Modules.get(Waypoints.class);
        boolean waypointsOn = allWaypoints > 0 && !waypointsModule.isWanted();
        if (waypointsOn) waypointsModule.setEnabled(true);
        Config.save();

        for (Result r : results) {
            LOGGER.info("Copied from {}: {} module(s), {} profile(s), {} waypoint(s)", r.source().title, r.modules().size(), r.profiles().size(), r.waypoints());
        }
        return new Outcome(results, backup, waypointsOn, null);
    }

    /** "No thanks": these aren't asked about again. */
    public static void decline(Collection<Source> sources) {
        asked.addAll(sources);
        Config.save();
    }

    static JsonArray saveAsked() {
        JsonArray out = new JsonArray();
        for (Source source : asked) out.add(source.id);
        return out;
    }

    static void loadAsked(JsonArray ids) {
        asked.clear();
        for (JsonElement id : ids) {
            for (Source source : Source.values()) if (id.isJsonPrimitive() && source.id.equals(id.getAsString())) asked.add(source);
        }
    }

    public static boolean asked(Source source) {
        return asked.contains(source);
    }

    /** For tests: forget the answers. */
    public static void resetAsked() {
        asked.clear();
    }

    private static Converted live(Source source) {
        return switch (source) {
            case METEOR -> MeteorImport.live();
            case WURST -> WurstImport.live();
            case XAERO -> null;
        };
    }

    private static Map<String, List<Waypoints.Waypoint>> waypoints(Source source) {
        return switch (source) {
            case METEOR -> MeteorImport.waypoints();
            case XAERO -> XaeroImport.waypoints();
            case WURST -> Map.of();
        };
    }

    /** New waypoints and how many servers and worlds they're on. */
    private static int[] countNew(Map<String, List<Waypoints.Waypoint>> byServer) {
        Waypoints module = Modules.get(Waypoints.class);
        int count = 0, places = 0;
        for (Map.Entry<String, List<Waypoints.Waypoint>> e : byServer.entrySet()) {
            int n = module.countNew(e.getKey(), e.getValue());
            count += n;
            if (n > 0) places++;
        }
        return new int[] {count, places};
    }

    private static int[] addWaypoints(Map<String, List<Waypoints.Waypoint>> byServer) {
        Waypoints module = Modules.get(Waypoints.class);
        int count = 0, places = 0;
        for (Map.Entry<String, List<Waypoints.Waypoint>> e : byServer.entrySet()) {
            int n = module.addAll(e.getKey(), e.getValue());
            count += n;
            if (n > 0) places++;
        }
        return new int[] {count, places};
    }

    // ---- Turning their modules into ours

    /** Matched modules as a Cyanite profile, with counts, their on or bound modules with no match, and keys left out as taken. */
    record Converted(JsonObject modules, List<String> names, int on, int binds, int settings, List<String> leftOut, List<String> keysTaken) {
        JsonObject asProfile() {
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            root.add("modules", modules);
            return root;
        }
    }

    /**
     * Builds one Cyanite profile out of another client's modules, one at a time. With {@code withOff} the
     * matched modules they had off are saved off (a profile); without, only what was on is switched on and
     * nothing is switched off (copying into the live setup), and a key something here already uses is left out.
     */
    static final class Builder {
        private final boolean withOff;
        private final Map<String, JsonObject> entries = new LinkedHashMap<>();
        private final List<String> leftOut = new ArrayList<>();
        private final List<String> keysTaken = new ArrayList<>();

        Builder(boolean withOff) {
            this.withOff = withOff;
        }

        /** One of theirs with nothing here to go to: worth saying when it was on or had a key. */
        void noMatch(String name, boolean active, int key) {
            if ((active || key != KeybindSetting.NONE) && !leftOut.contains(name)) leftOut.add(name);
        }

        /**
         * One of their modules onto ours: on if it was on (unless only its settings come over, or it's one
         * that runs by itself), its key, and each wanted setting value that fits. Two of theirs on one of
         * ours: on wins, and the first key.
         */
        void module(Module target, boolean active, boolean settingsOnly, int key, Map<String, JsonElement> wanted) {
            JsonObject entry = entries.computeIfAbsent(target.name, n -> new JsonObject());
            JsonObject values = entry.get("settings") instanceof JsonObject s ? s : new JsonObject();

            if (!settingsOnly && !NEVER_ON.contains(target.name)) {
                if (active) entry.addProperty("enabled", true);
                else if (withOff && !entry.has("enabled")) entry.addProperty("enabled", false);
            }
            if (!settingsOnly && key != KeybindSetting.NONE && !values.has(BIND)) {
                // Two things on one key both go off at once: one already here keeps it.
                if (!withOff && taken(target, key)) {
                    String said = target.name + " (" + Macro.keyName(key) + ")";
                    if (!keysTaken.contains(said)) keysTaken.add(said);
                } else {
                    values.addProperty(BIND, key);
                }
            }
            for (Map.Entry<String, JsonElement> w : wanted.entrySet()) {
                Setting<?> setting = exact(target, w.getKey());
                if (setting != null && setting != target.bind() && fits(setting, w.getValue())) values.add(setting.name, w.getValue());
            }
            if (!values.isEmpty()) entry.add("settings", values);
        }

        Converted build() {
            JsonObject modules = new JsonObject();
            List<String> names = new ArrayList<>();
            int on = 0, binds = 0, settings = 0;
            for (Map.Entry<String, JsonObject> e : entries.entrySet()) {
                JsonObject entry = e.getValue();
                boolean enabled = entry.get("enabled") instanceof JsonPrimitive p && p.getAsBoolean();
                JsonObject values = entry.get("settings") instanceof JsonObject s ? s : new JsonObject();
                // Copying into the live setup, a module with nothing to bring is left out altogether.
                if (!withOff && !enabled && values.isEmpty()) continue;
                modules.add(e.getKey(), entry);
                names.add(e.getKey());
                if (enabled) on++;
                if (values.has(BIND)) binds++;
                // Counted only when it isn't our default: an entity list sets four switches, often one changes.
                Module module = byName(e.getKey());
                for (Map.Entry<String, JsonElement> v : values.entrySet()) {
                    Setting<?> setting = v.getKey().equals(BIND) ? null : exact(module, v.getKey());
                    if (setting != null && !v.getValue().equals(setting.defaultJson())) settings++;
                }
            }
            return new Converted(modules, names, on, binds, settings, leftOut, keysTaken);
        }
    }

    /** Whether the menu, another module's bind or key, or a macro already uses this key. */
    private static boolean taken(Module target, int key) {
        KeyMapping menu = CyaniteClientMod.clickGuiKey();
        if (menu != null) {
            InputConstants.Key bound = KeyBindingHelper.getBoundKeyOf(menu);
            if (bound.getType() == InputConstants.Type.KEYSYM && bound.getValue() == key) return true;
        }
        for (Module module : Modules.getAll()) {
            if (module == target) continue;
            for (Setting<?> s : module.settings()) if (s instanceof KeybindSetting bind && bind.get() == key) return true;
        }
        for (Macro macro : Macros.list()) if (macro.key == key) return true;
        return false;
    }

    /** Whether a value suits the setting: the right kind, and for a choice one it has. Secrets never. */
    static boolean fits(Setting<?> setting, JsonElement value) {
        if (setting.isSecret()) return false;
        if (setting instanceof BoolSetting) return value instanceof JsonPrimitive p && p.isBoolean();
        if (setting instanceof SliderSetting || setting instanceof KeybindSetting) return value instanceof JsonPrimitive p && p.isNumber();
        if (setting instanceof ModeSetting mode) return value instanceof JsonPrimitive p && p.isString() && mode.modes.contains(p.getAsString());
        if (setting instanceof ColorSetting || setting instanceof StringSetting) return value instanceof JsonPrimitive p && p.isString();
        if (setting instanceof BlockListSetting) return value instanceof JsonArray;
        return false;
    }

    /** One of a choice setting's modes by a loose match ("night-vision", "Night Vision"), or null. */
    static String mode(ModeSetting setting, String given) {
        String want = loose(given);
        for (String m : setting.modes) if (loose(m).equals(want)) return m;
        return null;
    }

    /** "auto-totem", "AutoTotem" and "Auto Totem" all as "autototem". */
    static String loose(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    static Module byName(String name) {
        for (Module m : Modules.getAll()) if (m.name.equals(name)) return m;
        return null;
    }

    static Module byLooseName(String theirName) {
        String want = loose(theirName);
        for (Module m : Modules.getAll()) if (loose(m.name).equals(want)) return m;
        return null;
    }

    static Setting<?> byLooseName(Module module, String theirName) {
        String want = loose(theirName);
        for (Setting<?> s : module.settings()) {
            // The key comes from their keybind, never a setting that happens to be called "bind".
            if (s != module.bind() && loose(s.name).equals(want)) return s;
        }
        return null;
    }

    static Setting<?> exact(Module module, String name) {
        for (Setting<?> s : module.settings()) if (s.name.equals(name)) return s;
        return null;
    }

    static String hex(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    // ---- Which server or world a file of theirs is for

    /** The server list's addresses, for telling which one a file name was made from. */
    static List<String> serverAddresses() {
        ServerList list = new ServerList(Minecraft.getInstance());
        list.load();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) out.add(list.get(i).ip);
        return out;
    }

    /**
     * The server or world a file or folder name of theirs was made from, as Cyanite keeps waypoints: a listed
     * server's address as typed there, a world in saves (by the name inside it), or an address that isn't
     * listed (a trailing "_25565" read as its port). Null for a name that's none of those, like a deleted world.
     */
    static String placeFor(String fileName, List<String> addresses, Map<String, String> worlds) {
        for (String address : addresses) if (asFileName(address).equalsIgnoreCase(fileName)) return address;
        String world = worldFor(fileName, worlds);
        if (world != null) return world;
        return fileName.contains(".") ? fileName.replaceFirst("_(\\d{1,5})$", ":$1") : null;
    }

    /** The world in saves a folder name of theirs was made from, as Cyanite keeps its waypoints, or null. */
    static String worldFor(String fileName, Map<String, String> worlds) {
        for (Map.Entry<String, String> world : worlds.entrySet()) {
            if (world.getKey().equals(fileName) || asFileName(world.getKey()).equals(fileName)) return Waypoints.worldKey(world.getValue());
        }
        return null;
    }

    /** An address or world folder as other clients write it in a file name: spaces, colons and the like as "_". */
    static String asFileName(String name) {
        return name.replaceAll("[\\s\\\\/:*?\"<>|]", "_");
    }

    /** The singleplayer worlds in saves, folder name to the name inside it (what Cyanite keys their waypoints by). */
    static Map<String, String> worlds() {
        Map<String, String> worlds = new HashMap<>();
        Path saves = FabricLoader.getInstance().getGameDir().resolve("saves");
        if (!Files.isDirectory(saves)) return worlds;
        try (Stream<Path> dirs = Files.list(saves)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                Path level = dir.resolve("level.dat");
                if (!Files.isRegularFile(level)) continue;
                String folder = dir.getFileName().toString();
                try {
                    CompoundTag root = NbtIo.readCompressed(level, NbtAccounter.create(MAX_FILE));
                    worlds.put(folder, root.getCompoundOrEmpty("Data").getStringOr("LevelName", folder));
                } catch (IOException | RuntimeException e) {
                    worlds.put(folder, folder);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Couldn't list the saves folder: {}", e.toString());
        }
        return worlds;
    }
}
