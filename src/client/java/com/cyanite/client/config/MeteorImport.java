package com.cyanite.client.config;

import com.cyanite.client.modules.render.Waypoints;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.BlockListSetting;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.KeybindSetting;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.Setting;
import com.cyanite.client.system.setting.StringSetting;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Reads a Meteor Client setup for {@link Imports}. Meteor keeps its modules in {@code meteor-client/modules.nbt}
 * next to ours, one more set per profile under {@code meteor-client/profiles/<name>/}, and each server's or
 * world's waypoints in {@code meteor-client/waypoints/}, saving only the settings changed from its defaults.
 * Each Meteor module is matched to the Cyanite one that does the same job: by the table below where the
 * names or units differ, by name otherwise (addon modules too).
 */
public final class MeteorImport {
    private static final String PROFILE_PREFIX = "Meteor ";
    /** Meteor's own purple, what its waypoints are unless changed. */
    private static final int METEOR_PURPLE = 0x913DE2;

    private static final Map<String, Rule> RULES = new HashMap<>();

    private MeteorImport() {
    }

    // ---- How Meteor's modules map onto ours, where same-name matching isn't enough

    static {
        // Combat
        rule("auto-armor", "Auto Armor", num("swap-delay", "Delay"), bool("ignore-elytra", "Keep elytra"));
        rule("auto-log", "Auto Log", num("health", "Health"), bool("toggle-off", "Turn off after"));
        rule("auto-totem", "Auto Totem", pick("mode", "Mode", "Strict", "Always", "Smart", "Low health"),
            num("health", "Health"), num("delay", "Delay"));
        rule("auto-weapon", "Auto Weapon", pick("weapon", "Prefer", "Sword", "Sword", "Axe", "Axe"));
        rule("bow-aimbot", "Bow Aimbot", num("range", "Range"), entities("entities"));
        rule("hitboxes", "Hitboxes", num("expand", "Expand"), entities("entities"));
        rule("kill-aura", "Kill Aura", num("range", "Range"), entities("entities"),
            pick("priority", "Priority", "LowestDistance", "Distance", "HighestDistance", "Distance",
                "LowestHealth", "Health", "HighestHealth", "Health", "ClosestAngle", "Angle"),
            flag("rotate", "Rotate", mode -> !mode.equals("None")),
            flag("attack-when-holding", "Weapon only", mode -> mode.equals("Weapons")),
            above("walls-range", "Through walls", 0),
            bool("pause-on-use", "Pause while eating"));
        rule("surround", "Surround", num("blocks-per-tick", "Blocks per tick"), bool("toggle-on-y-change", "Off when you move"));
        rule("velocity", "Velocity", num("knockback-horizontal", "Horizontal"), num("knockback-vertical", "Vertical"));

        // Movement
        rule("flight", "Flight", pick("mode", "Mode", "Abilities", "Vanilla", "Velocity", "Velocity"));
        rule("gui-move", "Inventory Move");
        rule("spider", "Spider", num("climb-speed", "Speed"));
        rule("sprint", "Sprint", pick("sprint-mode", "Mode", "Strict", "Legit", "Rage", "Omni"));
        rule("step", "Step", num("height", "Height"));

        // Player
        rule("anti-afk", "Anti AFK", bool("spin", "Rotate"));
        rule("auto-eat", "Auto Eat", num("hunger-threshold", "Hunger"));
        rule("auto-fish", "Auto Fish", num("cast-delay", "Recast delay"));
        rule("auto-gap", "Auto Gap", num("health-threshold", "Health"));
        rule("auto-replenish", "Hotbar Refill", num("min-count", "Refill below"), num("delay", "Delay"));
        rule("break-delay", "Fast Break", num("cooldown", "Delay"));
        rule("fast-use", "Fast Place", num("cooldown", "Delay"));
        rule("speed-mine", "Fast Mine", num("modifier", "Speed"));

        // Render
        rule("breadcrumbs", "Breadcrumbs", num("max-sections", "Length"));
        // Camera Tweaks only changes third person, it doesn't switch to it: its settings, not its on state.
        RULES.put("camera-tweaks", new Rule("F5 Camera", true,
            List.of(num("camera-distance", "Distance"), bool("clip", "Through walls"))));
        rule("free-look", "F5 Camera");
        rule("esp", "ESP", entities("entities"), color("players-color", "Player color"),
            color("monsters-color", "Hostile color"), color("animals-color", "Passive color"));
        rule("freecam", "Freecam", num("speed", "Speed"));
        rule("fullbright", "Fullbright", pick("mode", "Mode", "Gamma", "Gamma", "Potion", "Night vision"));
        rule("nametags", "Nametags", entities("entities"), num("scale", "Scale"),
            flag("durability", "Durability", mode -> !mode.equals("None")));
        rule("storage-esp", "Storage ESP", storage("storage-blocks"), color("chest", "Chest color"),
            color("ender-chest", "Ender color"), color("shulker", "Shulker color"), color("barrel", "Barrel color"));
        rule("tracers", "Tracers", entities("entities"), num("max-distance", "Range"),
            conv("distance-colors", (v, out) -> asBool(v).ifPresent(on -> out.put("Color", new JsonPrimitive(on ? "Distance" : "Custom")))));
        rule("zoom", "Zoom", num("zoom", "Zoom"), bool("cinematic", "Cinematic camera"));

        // Misc and world
        rule("discord-presence", "Discord RPC");
        rule("timer", "Timer", num("multiplier", "Speed"));
    }

    /** Where one Meteor module goes, and how its settings that need more than the same name come over. */
    private record Rule(String target, boolean settingsOnly, List<Conv> settings) {
    }

    /** One saved Meteor setting turned into Cyanite setting values (name to value, as Cyanite saves it). */
    private record Conv(String from, BiConsumer<Tag, Map<String, JsonElement>> to) {
    }

    private static void rule(String meteor, String cyanite, Conv... settings) {
        RULES.put(meteor, new Rule(cyanite, false, List.of(settings)));
    }

    private static Conv conv(String from, BiConsumer<Tag, Map<String, JsonElement>> to) {
        return new Conv(from, to);
    }

    /** A number in the same units, under another name. */
    private static Conv num(String from, String to) {
        return conv(from, (v, out) -> v.asNumber().ifPresent(n -> out.put(to, new JsonPrimitive(n.doubleValue()))));
    }

    private static Conv bool(String from, String to) {
        return conv(from, (v, out) -> asBool(v).ifPresent(b -> out.put(to, new JsonPrimitive(b))));
    }

    /** A Meteor choice to one of ours: pairs of Meteor's value, then ours. One not listed is left alone. */
    private static Conv pick(String from, String to, String... pairs) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return conv(from, (v, out) -> v.asString().map(map::get).ifPresent(mode -> out.put(to, new JsonPrimitive(mode))));
    }

    /** A Meteor choice to an on/off. */
    private static Conv flag(String from, String to, Predicate<String> on) {
        return conv(from, (v, out) -> v.asString().ifPresent(mode -> out.put(to, new JsonPrimitive(on.test(mode)))));
    }

    /** A Meteor number to an on/off: on above the given value. */
    private static Conv above(String from, String to, double over) {
        return conv(from, (v, out) -> v.asNumber().ifPresent(n -> out.put(to, new JsonPrimitive(n.doubleValue() > over))));
    }

    private static Conv color(String from, String to) {
        return conv(from, (v, out) -> v.asCompound().ifPresent(c -> out.put(to, colorJson(c))));
    }

    /** A Meteor entity list to our Players / Hostiles / Passives / Items switches, whichever the module has. */
    private static Conv entities(String from) {
        return conv(from, (v, out) -> {
            if (!(v instanceof ListTag list)) return;
            boolean players = false, hostiles = false, passives = false, items = false;
            for (Tag element : list) {
                Identifier id = element.asString().map(Identifier::tryParse).orElse(null);
                EntityType<?> type = id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
                if (type == null) continue;
                if (type == EntityType.PLAYER) players = true;
                else if (type == EntityType.ITEM) items = true;
                else if (type.getCategory() == MobCategory.MONSTER) hostiles = true;
                // Villagers and golems are filed under "misc" with arrows and boats, but they're mobs: passive.
                else if (type.getCategory() != MobCategory.MISC || type == EntityType.VILLAGER || type == EntityType.WANDERING_TRADER
                    || type == EntityType.IRON_GOLEM || type == EntityType.SNOW_GOLEM) passives = true;
            }
            out.put("Players", new JsonPrimitive(players));
            out.put("Hostiles", new JsonPrimitive(hostiles));
            out.put("Passives", new JsonPrimitive(passives));
            out.put("Items", new JsonPrimitive(items));
        });
    }

    /** Meteor's storage block list (block entity ids) to Storage ESP's switches. */
    private static Conv storage(String from) {
        Map<String, String> kinds = new HashMap<>();
        for (String id : List.of("chest", "trapped_chest")) kinds.put(id, "Chests");
        kinds.put("ender_chest", "Ender chests");
        kinds.put("shulker_box", "Shulkers");
        kinds.put("barrel", "Barrels");
        kinds.put("hopper", "Hoppers");
        for (String id : List.of("furnace", "blast_furnace", "smoker")) kinds.put(id, "Furnaces");
        for (String id : List.of("dispenser", "dropper")) kinds.put(id, "Dispensers");
        return conv(from, (v, out) -> {
            if (!(v instanceof ListTag list)) return;
            for (String kind : kinds.values()) out.put(kind, new JsonPrimitive(false));
            for (Tag element : list) {
                Identifier id = element.asString().map(Identifier::tryParse).orElse(null);
                String kind = id == null ? null : kinds.get(id.getPath());
                if (kind != null) out.put(kind, new JsonPrimitive(true));
            }
        });
    }

    // ---- Reading Meteor's files

    public static Path dir() {
        return FabricLoader.getInstance().getGameDir().resolve("meteor-client");
    }

    /** One of Meteor's .nbt files, or null if there isn't one or it can't be read. */
    private static CompoundTag read(Path file) {
        if (!Files.isRegularFile(file)) return null;
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(file))) {
            raw.mark(2);
            int first = raw.read();
            raw.reset();
            // Meteor writes it plain; gzip is taken too, in case a version or an addon packs it.
            InputStream in = first == 0x1F ? new GZIPInputStream(raw) : raw;
            return NbtIo.read(new DataInputStream(in), NbtAccounter.create(Imports.MAX_FILE));
        } catch (IOException | RuntimeException e) {
            // Their file, not our bug: a warning, not an error.
            Imports.LOGGER.warn("Couldn't read Meteor's {}: {}", file.getFileName(), e.toString());
            return null;
        }
    }

    /** Meteor's profiles that have modules saved, by name, sorted. */
    private static Map<String, Path> meteorProfiles() {
        Map<String, Path> found = new LinkedHashMap<>();
        Path profiles = dir().resolve("profiles");
        if (!Files.isDirectory(profiles)) return found;
        try (Stream<Path> dirs = Files.list(profiles)) {
            dirs.filter(Files::isDirectory).sorted().forEach(p -> {
                Path modules = p.resolve("modules.nbt");
                if (Files.isRegularFile(modules)) found.put(p.getFileName().toString(), modules);
            });
        } catch (IOException e) {
            Imports.LOGGER.warn("Couldn't list Meteor's profiles: {}", e.toString());
        }
        return found;
    }

    // ---- What Imports copies

    /** Meteor's live setup, matched, to switch on here; null if it has none or it can't be read. */
    static Imports.Converted live() {
        CompoundTag main = read(dir().resolve("modules.nbt"));
        return main == null ? null : convert(main, false);
    }

    /** Meteor's profiles that read (one that doesn't would be offered and then skipped). */
    static List<String> readableProfiles() {
        return meteorProfiles().entrySet().stream().filter(p -> read(p.getValue()) != null).map(Map.Entry::getKey).toList();
    }

    /** Each Meteor profile as a Cyanite profile called "Meteor name", replacing one from an earlier copy. */
    static List<String> copyProfiles() {
        List<String> added = new ArrayList<>();
        for (Map.Entry<String, Path> p : meteorProfiles().entrySet()) {
            CompoundTag tag = read(p.getValue());
            if (tag == null) continue;
            String name = Profiles.sanitize(PROFILE_PREFIX + p.getKey());
            if (name.length() <= PROFILE_PREFIX.length()) continue;
            if (Config.write(Profiles.dir().resolve(name + Profiles.EXT), convert(tag, true).asProfile())) added.add(name);
        }
        return added;
    }

    // ---- Matching modules

    /** One Meteor modules.nbt as a Cyanite profile holding only the modules it matches ({@link Imports.Builder}). */
    static Imports.Converted convert(CompoundTag meteor, boolean withOff) {
        Imports.Builder out = new Imports.Builder(withOff);
        for (Tag t : meteor.getListOrEmpty("modules")) {
            if (!(t instanceof CompoundTag m)) continue;
            String name = m.getStringOr("name", "");
            boolean active = m.getBooleanOr("active", false);
            int key = key(m.getCompoundOrEmpty("keybind"));

            Rule rule = RULES.get(name);
            Module target = rule != null ? Imports.byName(rule.target()) : Imports.byLooseName(name);
            if (target == null) {
                out.noMatch(title(name), active, key);
                continue;
            }

            Map<String, JsonElement> wanted = new LinkedHashMap<>();
            for (Map.Entry<String, Tag> saved : savedSettings(m).entrySet()) {
                Conv conv = rule == null ? null : rule.settings().stream().filter(c -> c.from().equals(saved.getKey())).findFirst().orElse(null);
                if (conv != null) {
                    conv.to().accept(saved.getValue(), wanted);
                } else {
                    Setting<?> same = Imports.byLooseName(target, saved.getKey());
                    JsonElement value = same == null ? null : sameNamed(same, saved.getValue());
                    if (value != null) wanted.put(same.name, value);
                }
            }
            out.module(target, active, rule != null && rule.settingsOnly(), key, wanted);
        }
        return out.build();
    }

    /**
     * Every saved setting of a Meteor module (or waypoint), all groups flattened. A name in two groups keeps
     * the first: the main one (Flight's "mode" is how it flies; its Anti Kick group has a "mode" too).
     */
    private static Map<String, Tag> savedSettings(CompoundTag module) {
        Map<String, Tag> out = new LinkedHashMap<>();
        for (Tag g : module.getCompoundOrEmpty("settings").getListOrEmpty("groups")) {
            if (!(g instanceof CompoundTag group)) continue;
            for (Tag s : group.getListOrEmpty("settings")) {
                if (s instanceof CompoundTag setting && setting.get("value") instanceof Tag value) {
                    out.putIfAbsent(setting.getStringOr("name", ""), value);
                }
            }
        }
        return out;
    }

    /**
     * A setting with the same name, as ours would save it. Switches, choices, colours, block lists and
     * text only: Meteor's numbers are often in other units (its Flight speed is blocks a tick, ours a
     * multiplier), so those come over only through the table.
     */
    private static JsonElement sameNamed(Setting<?> ours, Tag v) {
        if (ours instanceof BoolSetting) return v instanceof ByteTag ? asBool(v).map(JsonPrimitive::new).orElse(null) : null;
        if (ours instanceof ModeSetting mode) return v.asString().map(given -> Imports.mode(mode, given)).map(JsonPrimitive::new).orElse(null);
        if (ours instanceof ColorSetting) return v.asCompound().map(MeteorImport::colorJson).orElse(null);
        if (ours instanceof BlockListSetting) {
            if (!(v instanceof ListTag list)) return null;
            JsonArray ids = new JsonArray();
            for (Tag element : list) element.asString().ifPresent(ids::add);
            return ids;
        }
        if (ours instanceof StringSetting) return v.asString().map(JsonPrimitive::new).orElse(null);
        return null;
    }

    private static JsonPrimitive colorJson(CompoundTag c) {
        return new JsonPrimitive(Imports.hex(rgb(c)));
    }

    private static int rgb(CompoundTag c) {
        return (clamp(c.getIntOr("r", 0)) << 16) | (clamp(c.getIntOr("g", 0)) << 8) | clamp(c.getIntOr("b", 0));
    }

    private static int clamp(int channel) {
        return Math.max(0, Math.min(255, channel));
    }

    private static Optional<Boolean> asBool(Tag v) {
        return v instanceof ByteTag ? v.asBoolean() : Optional.empty();
    }

    /** A Meteor keybind as a GLFW key code; mouse buttons and none give {@link KeybindSetting#NONE}. */
    private static int key(CompoundTag bind) {
        if (bind.contains("key")) {
            // Newer Meteor: the key's name, like "key.keyboard.r".
            try {
                InputConstants.Key key = InputConstants.getKey(bind.getStringOr("key", ""));
                return key.getType() == InputConstants.Type.KEYSYM && key.getValue() >= 0 ? key.getValue() : KeybindSetting.NONE;
            } catch (RuntimeException e) {
                return KeybindSetting.NONE;
            }
        }
        // Older Meteor: the GLFW code, and whether it's a key or a mouse button.
        int value = bind.getIntOr("value", KeybindSetting.NONE);
        return bind.getBooleanOr("isKey", true) && value >= 0 ? value : KeybindSetting.NONE;
    }

    /** "auto-reconnect" as "Auto Reconnect", to show. */
    private static String title(String meteorName) {
        StringBuilder sb = new StringBuilder();
        for (String word : meteorName.split("-")) {
            if (word.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }

    // ---- Waypoints

    /**
     * Meteor's waypoints by the server or world Cyanite keeps them under. Each file is named after the server's
     * address or the world's save folder, with spaces, colons and the like as "_"; Realms all share one and are
     * left out, as are names that are neither a server nor a world here.
     */
    static Map<String, List<Waypoints.Waypoint>> waypoints() {
        Map<String, List<Waypoints.Waypoint>> out = new LinkedHashMap<>();
        Path folder = dir().resolve("waypoints");
        if (!Files.isDirectory(folder)) return out;
        List<Path> files;
        try (Stream<Path> list = Files.list(folder)) {
            files = list.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".nbt")).sorted().toList();
        } catch (IOException e) {
            Imports.LOGGER.warn("Couldn't list Meteor's waypoints: {}", e.toString());
            return out;
        }
        if (files.isEmpty()) return out;
        List<String> addresses = Imports.serverAddresses();
        Map<String, String> worlds = Imports.worlds();
        for (Path file : files) {
            String name = file.getFileName().toString();
            name = name.substring(0, name.length() - ".nbt".length());
            String place = name.equals("realms") ? null : Imports.placeFor(name, addresses, worlds);
            if (place == null) continue;
            CompoundTag tag = read(file);
            if (tag == null) continue;
            List<Waypoints.Waypoint> wps = new ArrayList<>();
            for (Tag t : tag.getListOrEmpty("waypoints")) {
                if (t instanceof CompoundTag wp) wps.add(waypoint(savedSettings(wp)));
            }
            if (!wps.isEmpty()) out.computeIfAbsent(place, s -> new ArrayList<>()).addAll(wps);
        }
        return out;
    }

    /** One Meteor waypoint from its saved settings; the ones not saved are Meteor's defaults. */
    private static Waypoints.Waypoint waypoint(Map<String, Tag> s) {
        String name = s.get("name") instanceof Tag t ? t.asString().orElse("") : "";
        int[] pos = s.get("location") instanceof IntArrayTag a && a.size() == 3 ? a.getAsIntArray() : new int[3];
        String dimension = switch (s.get("dimension") instanceof Tag t ? t.asString().orElse("") : "") {
            case "Nether" -> "minecraft:the_nether";
            case "End" -> "minecraft:the_end";
            default -> "minecraft:overworld";
        };
        int color = s.get("color") instanceof CompoundTag c ? rgb(c) : METEOR_PURPLE;
        boolean hidden = s.get("visible") instanceof Tag v && !asBool(v).orElse(true);
        return new Waypoints.Waypoint(name.isBlank() ? "Home" : name, pos[0], pos[1], pos[2], dimension, color, hidden);
    }
}
