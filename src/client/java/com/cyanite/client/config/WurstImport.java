package com.cyanite.client.config;

import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.KeybindSetting;
import com.cyanite.client.system.setting.ModeSetting;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Reads a Wurst setup for {@link Imports}. Wurst keeps it in {@code wurst/}: {@code enabled-hacks.json} (the
 * names of the hacks that are on), {@code keybinds.json} (a key name to the commands it runs, a bare hack name
 * toggling that hack) and {@code settings.json} (every hack's settings by their names, defaults included).
 * Because the defaults are saved too, a setting comes over only when it differs from Wurst's own default,
 * through the table below; hacks matched only by name bring their on state and key, not settings.
 */
public final class WurstImport {
    /** Wurst names (loosely) to what they do here, where the names differ or settings come over. */
    private static final Map<String, Rule> RULES = new HashMap<>();
    /** Its menus: Cyanite has its own, so these are neither copied nor said to have no match. */
    private static final Set<String> MENUS = Set.of("clickgui", "navigator");
    /** Wurst hacks that share a name with a Cyanite module that does something else. */
    private static final Set<String> NOT_THE_SAME = Set.of("automine");
    /** Its ESP hacks: what each shows, drawn as boxes (our ESP), lines (our Tracers) or both. */
    private static final List<String> ESP_HACKS = List.of("PlayerESP", "MobESP", "ItemESP");

    private WurstImport() {
    }

    static {
        rule("AimAssist", "Aim Assist", num("Range", 4.5, "Range"), num("FOV", 120, "FOV"),
            not("Check line of sight", true, "Through walls"));
        rule("AntiKnockback", "Velocity",
            // Wurst's strength is how much knockback it takes away; ours is how much is still taken.
            conv("Horizontal Strength", 1, (v, out) -> out.put("Horizontal", new JsonPrimitive(clamp01(1 - v.getAsDouble())))),
            conv("Vertical Strength", 1, (v, out) -> out.put("Vertical", new JsonPrimitive(clamp01(1 - v.getAsDouble())))));
        rule("AutoArmor", "Auto Armor", num("Delay", 2, "Delay"));
        // Wurst's health is in hearts, ours in health points.
        rule("AutoLeave", "Auto Log", conv("Health", 4, (v, out) -> out.put("Health", new JsonPrimitive(Math.round(v.getAsDouble() * 2)))));
        rule("AutoSprint", "Sprint", conv("Omnidirectional Sprint", false,
            (v, out) -> out.put("Mode", new JsonPrimitive(v.getAsBoolean() ? "Omni" : "Legit"))));
        // Milliseconds there, ticks here.
        rule("AutoSteal", "Chest Stealer", conv("Delay", 100, (v, out) -> out.put("Delay", new JsonPrimitive(Math.round(v.getAsDouble() / 50)))));
        rule("AutoSword", "Auto Weapon", bool("Switch back", true, "Switch back"),
            conv("Priority", "Speed (swords)", (v, out) -> out.put("Prefer", new JsonPrimitive(v.getAsString().startsWith("Damage") ? "Most damage" : "Sword"))));
        rule("AutoTool", "Auto Tool", bool("Switch back", false, "Switch back"));
        rule("AutoTotem", "Auto Totem", num("Delay", 0, "Delay"), conv("Health", 0, (v, out) -> {
            // 0 hearts is Wurst's "always".
            double hearts = v.getAsDouble();
            out.put("Mode", new JsonPrimitive(hearts <= 0 ? "Always" : "Low health"));
            if (hearts > 0) out.put("Health", new JsonPrimitive(Math.round(hearts * 2)));
        }));
        RULES.put(Imports.loose("CameraDistance"), new Rule("F5 Camera", true, List.of(num("Distance", 12, "Distance"))));
        rule("ChestESP", "Storage ESP",
            bool("Include normal chests", true, "Chests"), bool("Include ender chests", true, "Ender chests"),
            bool("Include shulkers", true, "Shulkers"), bool("Include barrels", true, "Barrels"),
            bool("Include hoppers", false, "Hoppers"), bool("Include furnaces", false, "Furnaces"),
            bool("Include dispensers", false, "Dispensers"),
            color("Chest color", "#00FF00", "Chest color"), color("Ender color", "#00FFFF", "Ender color"),
            color("Shulker color", "#FF00FF", "Shulker color"), color("Barrel color", "#00FF00", "Barrel color"),
            color("Hopper color", "#FFFFFF", "Hopper color"), color("Furnace color", "#FF0000", "Furnace color"),
            color("Dispenser color", "#FF8000", "Dispenser color"));
        rule("Flight", "Flight", bool("Anti-Kick", false, "Anti kick"));
        rule("Fullbright", "Fullbright", choice("Method", "Gamma", "Mode"));
        rule("InvWalk", "Inventory Move", bool("Allow ClickGUI", true, "In click GUI"), bool("Allow sneak key", true, "Sneak"),
            bool("Allow sprint key", true, "Sprint"), bool("Allow jump key", true, "Jump"));
        rule("Killaura", "Kill Aura", num("Range", 5, "Range"), choice("Priority", "Distance", "Priority"),
            not("Check line of sight", false, "Through walls"));
        rule("KillauraLegit", "Kill Aura");
        rule("NameTags", "Nametags", num("Scale", 1, "Scale"));
        rule("NoSlowdown", "No Slow");
        rule("Step", "Step", num("Height", 1, "Height"));
        rule("Timer", "Timer", num("Speed", 2, "Speed"));
    }

    /** Where one Wurst hack goes, and which of its settings come over. */
    private record Rule(String target, boolean settingsOnly, List<Conv> settings) {
    }

    /** One Wurst setting, its default there, and what it is here when it isn't that default. */
    private record Conv(String from, JsonPrimitive wurstDefault, BiConsumer<JsonPrimitive, Map<String, JsonElement>> to) {
    }

    private static void rule(String wurst, String cyanite, Conv... settings) {
        RULES.put(Imports.loose(wurst), new Rule(cyanite, false, List.of(settings)));
    }

    private static Conv conv(String from, Object wurstDefault, BiConsumer<JsonPrimitive, Map<String, JsonElement>> to) {
        JsonPrimitive def = wurstDefault instanceof Number n ? new JsonPrimitive(n)
            : wurstDefault instanceof Boolean b ? new JsonPrimitive(b) : new JsonPrimitive(wurstDefault.toString());
        return new Conv(from, def, to);
    }

    private static Conv num(String from, double wurstDefault, String to) {
        return conv(from, wurstDefault, (v, out) -> { if (v.isNumber()) out.put(to, new JsonPrimitive(v.getAsDouble())); });
    }

    private static Conv bool(String from, boolean wurstDefault, String to) {
        return conv(from, wurstDefault, (v, out) -> { if (v.isBoolean()) out.put(to, new JsonPrimitive(v.getAsBoolean())); });
    }

    /** A Wurst switch that means the opposite of ours ("Check line of sight" and "Through walls"). */
    private static Conv not(String from, boolean wurstDefault, String to) {
        return conv(from, wurstDefault, (v, out) -> { if (v.isBoolean()) out.put(to, new JsonPrimitive(!v.getAsBoolean())); });
    }

    private static Conv color(String from, String wurstDefault, String to) {
        return conv(from, wurstDefault, (v, out) -> { if (v.isString() && v.getAsString().matches("#[0-9A-Fa-f]{6}")) out.put(to, v); });
    }

    /** A Wurst choice to one of ours by a loose match of the name, resolved once the target is known. */
    private static Conv choice(String from, String wurstDefault, String to) {
        return conv(from, wurstDefault, (v, out) -> { if (v.isString()) out.put(to, new JsonPrimitive(CHOICE + v.getAsString())); });
    }

    /** Marks a value from {@link #choice} as still to be matched to one of the target's modes. */
    private static final String CHOICE = "\u0000choice:";

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    // ---- Reading Wurst's files

    public static Path dir() {
        return FabricLoader.getInstance().getGameDir().resolve("wurst");
    }

    /** One of Wurst's JSON files, or null if there isn't one or it can't be read. */
    private static JsonElement read(String name) {
        Path file = dir().resolve(name);
        if (!Files.isRegularFile(file)) return null;
        try {
            if (Files.size(file) > Imports.MAX_FILE) throw new IOException("too big");
            return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            // Their file, not our bug: a warning, not an error.
            Imports.LOGGER.warn("Couldn't read Wurst's {}: {}", name, e.toString());
            return null;
        }
    }

    /** The hacks Wurst has on, loose name to Wurst's own, in its order. */
    private static Map<String, String> enabled(JsonElement json) {
        Map<String, String> on = new LinkedHashMap<>();
        if (json != null && json.isJsonArray()) {
            for (JsonElement e : json.getAsJsonArray()) if (e.isJsonPrimitive()) on.putIfAbsent(Imports.loose(e.getAsString()), e.getAsString());
        }
        return on;
    }

    /**
     * Each hack a key toggles, by loose name, to the key's GLFW code (the first key, when two do). A keybind
     * runs commands split by ";": a bare hack name toggles it, and so does "t name" or ".t name".
     */
    private static Map<String, Integer> keys(JsonElement json) {
        Map<String, Integer> keys = new LinkedHashMap<>();
        if (json == null || !json.isJsonObject()) return keys;
        for (Map.Entry<String, JsonElement> e : json.getAsJsonObject().entrySet()) {
            if (!e.getValue().isJsonPrimitive()) continue;
            int key;
            try {
                InputConstants.Key k = InputConstants.getKey(e.getKey());
                if (k.getType() != InputConstants.Type.KEYSYM || k.getValue() < 0) continue;
                key = k.getValue();
            } catch (RuntimeException ex) {
                continue;
            }
            for (String cmd : e.getValue().getAsString().split(";")) {
                String c = cmd.strip();
                if (c.startsWith(".")) c = c.substring(1);
                if (c.startsWith("t ")) c = c.substring(2).strip();
                if (c.isEmpty() || c.contains(" ")) continue;
                keys.putIfAbsent(Imports.loose(c), key);
            }
        }
        return keys;
    }

    // ---- What Imports copies

    /** Wurst's setup, matched, to switch on here; null if none of its files read. */
    static Imports.Converted live() {
        JsonElement enabledJson = read("enabled-hacks.json");
        JsonElement keysJson = read("keybinds.json");
        JsonElement settingsJson = read("settings.json");
        if (enabledJson == null && keysJson == null && settingsJson == null) return null;
        return convert(enabled(enabledJson), keys(keysJson),
            settingsJson != null && settingsJson.isJsonObject() ? settingsJson.getAsJsonObject() : new JsonObject());
    }

    /** Wurst's hacks onto ours: what's on, keys, and settings changed from Wurst's defaults. */
    static Imports.Converted convert(Map<String, String> enabledNames, Map<String, Integer> keys, JsonObject settings) {
        Imports.Builder out = new Imports.Builder(false);
        Set<String> enabled = enabledNames.keySet();
        // Every hack named anywhere, loose name to Wurst's own spelling (a key's word is only known as typed).
        Map<String, String> hacks = new LinkedHashMap<>();
        for (String name : settings.keySet()) hacks.putIfAbsent(Imports.loose(name), name);
        enabledNames.forEach(hacks::putIfAbsent);
        for (String loose : keys.keySet()) hacks.putIfAbsent(loose, loose);

        esp(out, enabled, keys, settings);
        for (Map.Entry<String, String> hack : hacks.entrySet()) {
            String loose = hack.getKey();
            if (MENUS.contains(loose) || ESP_HACKS.stream().anyMatch(n -> Imports.loose(n).equals(loose))) continue;
            boolean on = enabled.contains(loose);
            int key = keys.getOrDefault(loose, KeybindSetting.NONE);

            Rule rule = RULES.get(loose);
            Module target = rule != null ? Imports.byName(rule.target())
                : NOT_THE_SAME.contains(loose) ? null : Imports.byLooseName(loose);
            if (target == null) {
                // A key's word may be a command rather than a hack: named only when it's a hack Wurst knows.
                if (on || settings.has(hack.getValue())) out.noMatch(spaced(hack.getValue()), on, key);
                continue;
            }
            Map<String, JsonElement> wanted = new LinkedHashMap<>();
            if (rule != null && settings.get(hack.getValue()) instanceof JsonObject saved) {
                for (Conv conv : rule.settings()) {
                    if (saved.get(conv.from()) instanceof JsonPrimitive v && !same(v, conv.wurstDefault())) conv.to().accept(v, wanted);
                }
            }
            resolveChoices(target, wanted);
            out.module(target, on, rule != null && rule.settingsOnly(), key, wanted);
        }
        return out.build();
    }

    /**
     * PlayerESP, MobESP and ItemESP onto ESP (boxes) and Tracers (lines): each of ours shows the kinds the
     * Wurst hacks that are on draw that way. A key goes to whichever of ours its hack draws with, boxes first.
     */
    private static void esp(Imports.Builder out, Set<String> enabled, Map<String, Integer> keys, JsonObject settings) {
        Module esp = Imports.byName("ESP"), tracers = Imports.byName("Tracers");
        if (esp == null || tracers == null) return;
        Map<String, Boolean> boxes = new LinkedHashMap<>(), lines = new LinkedHashMap<>();
        for (String kind : List.of("Players", "Hostiles", "Passives", "Items")) {
            boxes.put(kind, false);
            lines.put(kind, false);
        }
        boolean anyOn = false;
        int espKey = KeybindSetting.NONE, tracersKey = KeybindSetting.NONE;
        Map<String, JsonElement> espExtra = new LinkedHashMap<>();
        for (String hack : ESP_HACKS) {
            String loose = Imports.loose(hack);
            JsonObject s = settings.get(hack) instanceof JsonObject o ? o : new JsonObject();
            String style = s.get("Style") instanceof JsonPrimitive p && p.isString() ? p.getAsString()
                : hack.equals("PlayerESP") ? "Lines and boxes" : "Boxes only";
            boolean drawsBoxes = !style.equals("Lines only"), drawsLines = !style.equals("Boxes only");
            int key = keys.getOrDefault(loose, KeybindSetting.NONE);
            if (key != KeybindSetting.NONE) {
                if (drawsBoxes && espKey == KeybindSetting.NONE) espKey = key;
                else if (!drawsBoxes && tracersKey == KeybindSetting.NONE) tracersKey = key;
            }
            if (hack.equals("ItemESP") && s.get("Color") instanceof JsonPrimitive c && c.isString()
                && c.getAsString().matches("#[0-9A-Fa-f]{6}") && !c.getAsString().equalsIgnoreCase("#FFFF00")) {
                espExtra.put("Item color", c);
            }
            if (!enabled.contains(loose)) continue;
            anyOn = true;
            List<String> kinds = switch (hack) {
                case "PlayerESP" -> List.of("Players");
                case "ItemESP" -> List.of("Items");
                default -> {
                    // MobESP hides the kinds its filters are ticked for.
                    List<String> mobs = new ArrayList<>();
                    if (!ticked(s, "Filter hostile mobs")) mobs.add("Hostiles");
                    if (!ticked(s, "Filter passive mobs")) mobs.add("Passives");
                    yield mobs;
                }
            };
            for (String kind : kinds) {
                if (drawsBoxes) boxes.put(kind, true);
                if (drawsLines) lines.put(kind, true);
            }
        }
        boolean anyBoxes = boxes.containsValue(true), anyLines = lines.containsValue(true);
        Map<String, JsonElement> espWanted = new LinkedHashMap<>(espExtra);
        if (anyBoxes) boxes.forEach((kind, on) -> espWanted.put(kind, new JsonPrimitive(on)));
        if (anyOn || espKey != KeybindSetting.NONE || !espWanted.isEmpty()) out.module(esp, anyBoxes, false, espKey, espWanted);
        Map<String, JsonElement> tracersWanted = new LinkedHashMap<>();
        if (anyLines) lines.forEach((kind, on) -> tracersWanted.put(kind, new JsonPrimitive(on)));
        if (anyLines || tracersKey != KeybindSetting.NONE) out.module(tracers, anyLines, false, tracersKey, tracersWanted);
    }

    /** Swaps a Wurst choice for the target's mode with the same name, or drops it. */
    private static void resolveChoices(Module target, Map<String, JsonElement> wanted) {
        for (Map.Entry<String, JsonElement> e : Set.copyOf(wanted.entrySet())) {
            if (!(e.getValue() instanceof JsonPrimitive p && p.isString() && p.getAsString().startsWith(CHOICE))) continue;
            String mode = Imports.exact(target, e.getKey()) instanceof ModeSetting m
                ? Imports.mode(m, p.getAsString().substring(CHOICE.length())) : null;
            if (mode != null) wanted.put(e.getKey(), new JsonPrimitive(mode));
            else wanted.remove(e.getKey());
        }
    }

    /** Whether a saved value is Wurst's default: numbers by value, colours whatever their case. */
    private static boolean same(JsonPrimitive v, JsonPrimitive def) {
        if (v.isNumber() && def.isNumber()) return Math.abs(v.getAsDouble() - def.getAsDouble()) < 1e-9;
        if (v.isString() && def.isString()) return v.getAsString().equalsIgnoreCase(def.getAsString());
        return v.equals(def);
    }

    private static boolean ticked(JsonObject settings, String name) {
        return settings.get(name) instanceof JsonPrimitive p && p.isBoolean() && p.getAsBoolean();
    }

    /** "AutoReconnect" as "Auto Reconnect", to show. */
    private static String spaced(String wurstName) {
        return wurstName.replaceAll("(?<=[a-z])(?=[A-Z])", " ");
    }
}
