package com.cyanite.client.system.macro;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One block of a macro, Scratch style. Most blocks do something (send a command, walk, mine);
 * C-shaped ones like "repeat" and "if" hold more blocks inside them; and hexagon-ish condition
 * blocks ("health < 10", "a and b") slot into the gaps "if", "repeat until" and "wait until"
 * leave for them. Each {@link Type} says how it looks with a template: plain words, {@code {key}}
 * for a value you can set, and {@code <n>} for a condition slot.
 */
public final class MacroStep {
    public static final int MAX_TEXT = 256;
    public static final int MAX_DEPTH = 24;
    /** Comparisons the condition blocks offer. */
    public static final String[] OPS = {"<", "<=", "=", ">=", ">", "!="};

    public enum Group {
        CHAT("Chat", 0xCF63CF),
        MOVE("Movement", 0x4C97FF),
        LOOK("Looking", 0x9966FF),
        ACT("Actions", 0xFF6680),
        ITEMS("Items & menus", 0xE6A000),
        CONTROL("Control", 0xFFAB19),
        SENSING("Sensing", 0x5CB1D6),
        OPERATORS("Operators", 0x59C059),
        VARIABLES("Variables", 0xFF8C1A),
        CYANITE("Cyanite", 0x2EC4B6);

        public final String label;
        public final int color;

        Group(String label, int color) {
            this.label = label;
            this.color = color;
        }
    }

    /** STACK does one thing; C wraps blocks; C_ELSE wraps two lots; CAP ends the script; CONDITION is true or false. */
    public enum Shape { STACK, C, C_ELSE, CAP, CONDITION }

    public enum Kind { TEXT, NUMBER, POS, CHOICE, MODULE }

    /** A setting of a block. For numbers {@code min}, {@code max} and {@code step} bound what can be typed. */
    public record Param(String key, String label, Kind kind, Object def, double min, double max, double step, String unit,
                        List<String> choices) {
        static Param text(String key, String label, String def) {
            return new Param(key, label, Kind.TEXT, def, 0, 0, 0, "", List.of());
        }

        static Param number(String key, String label, double def, double min, double max, double step, String unit) {
            return new Param(key, label, Kind.NUMBER, def, min, max, step, unit, List.of());
        }

        static Param pos(String key, String label) {
            return new Param(key, label, Kind.POS, null, 0, 0, 0, "", List.of());
        }

        static Param choice(String key, String label, String... choices) {
            return new Param(key, label, Kind.CHOICE, choices[0], 0, 0, 0, "", List.of(choices));
        }

        static Param op() {
            return new Param("op", "Compare", Kind.CHOICE, "<", 0, 0, 0, "", List.of(OPS));
        }

        static Param module(String key, String label) {
            return new Param(key, label, Kind.MODULE, "Auto Mine", 0, 0, 0, "", List.of());
        }
    }

    public enum Type {
        // ---- Chat
        SAY(Group.CHAT, Shape.STACK, "send {text}", "Sends a chat message, or a command if it starts with /. {name} puts in a variable",
            Param.text("text", "Message", "/home")),

        // ---- Movement
        WALK(Group.MOVE, Shape.STACK, "walk {dir} {blocks} blocks", "Walks a set distance the way you're facing (or back, left, right)",
            Param.choice("dir", "Direction", "Forward", "Back", "Left", "Right"), Param.number("blocks", "Blocks", 3, 0.5, 256, 0.5, "")),
        WALK_TO(Group.MOVE, Shape.STACK, "walk to {pos}", "Finds a way to a block and walks there",
            Param.pos("pos", "Block")),
        JUMP(Group.MOVE, Shape.STACK, "jump", "Jumps once"),
        HOLD(Group.MOVE, Shape.STACK, "hold {key} for {seconds} s", "Holds a key down for a while",
            Param.choice("key", "Key", "Forward", "Back", "Left", "Right", "Jump", "Sneak", "Sprint", "Attack", "Use"),
            Param.number("seconds", "Seconds", 1, 0.05, 600, 0.05, "s")),

        // ---- Looking
        LOOK(Group.LOOK, Shape.STACK, "face yaw {yaw} pitch {pitch}", "Turns to an exact direction: yaw 0 is south, 90 west, 180 north, -90 east",
            Param.number("yaw", "Yaw", 0, -180, 180, 1, "°"), Param.number("pitch", "Pitch", 0, -90, 90, 1, "°")),
        LOOK_AT(Group.LOOK, Shape.STACK, "look at {pos}", "Turns to look at a block",
            Param.pos("pos", "Block")),
        TURN(Group.LOOK, Shape.STACK, "turn {dir} {degrees} degrees", "Turns from wherever you're facing",
            Param.choice("dir", "Direction", "Right", "Left"), Param.number("degrees", "Degrees", 90, 1, 360, 1, "°")),

        // ---- Actions
        ATTACK(Group.ACT, Shape.STACK, "left click", "Clicks attack once: hits whatever you're looking at"),
        MINE(Group.ACT, Shape.STACK, "mine block at {pos}", "Looks at a block and mines it until it breaks",
            Param.pos("pos", "Block")),
        USE(Group.ACT, Shape.STACK, "right click", "Clicks use once: eats, places, opens whatever you're looking at"),
        USE_ON(Group.ACT, Shape.STACK, "use block at {pos}", "Looks at a block and right clicks it: chests, buttons, levers, signs",
            Param.pos("pos", "Block")),

        // ---- Items & menus
        SLOT(Group.ITEMS, Shape.STACK, "select hotbar slot {slot}", "Picks a hotbar slot",
            Param.number("slot", "Slot", 1, 1, 9, 1, "")),
        DROP(Group.ITEMS, Shape.STACK, "drop {amount}", "Drops what you're holding",
            Param.choice("amount", "Amount", "One", "Whole stack")),
        CLICK_SLOT(Group.ITEMS, Shape.STACK, "{button} click menu slot {slot}", "Clicks a slot in the open menu: chests, /ah, /shop. Waits for the menu to open",
            Param.choice("button", "Click", "Left", "Right", "Shift"), Param.number("slot", "Slot", 0, 0, 89, 1, "")),
        CLOSE(Group.ITEMS, Shape.STACK, "close menu", "Closes whatever menu is open"),

        // ---- Control
        WAIT(Group.CONTROL, Shape.STACK, "wait {seconds} seconds", "Waits a while",
            Param.number("seconds", "Seconds", 1, 0.05, 3600, 0.05, "s")),
        REPEAT(Group.CONTROL, Shape.C, "repeat {times}", "Runs the blocks inside this many times",
            Param.number("times", "Times", 10, 1, 100_000, 1, "")),
        FOREVER(Group.CONTROL, Shape.C, "forever", "Runs the blocks inside over and over until the macro is stopped"),
        IF(Group.CONTROL, Shape.C, "if <0> then", "Runs the blocks inside only when the condition is true"),
        IF_ELSE(Group.CONTROL, Shape.C_ELSE, "if <0> then", "Runs the first blocks when the condition is true, the ones under else when it isn't"),
        REPEAT_UNTIL(Group.CONTROL, Shape.C, "repeat until <0>", "Keeps running the blocks inside until the condition is true"),
        WAIT_UNTIL(Group.CONTROL, Shape.STACK, "wait until <0> up to {timeout} s", "Waits until the condition is true. Gives up and stops after the time, or never if it's 0",
            Param.number("timeout", "Give up after", 0, 0, 3600, 1, "s")),
        WAIT_CHAT(Group.CONTROL, Shape.STACK, "wait for chat {text} up to {timeout} s", "Waits for a chat line containing some text, then carries on",
            Param.text("text", "Contains", ""), Param.number("timeout", "Give up after", 10, 1, 3600, 1, "s")),
        STOP(Group.CONTROL, Shape.CAP, "stop macro", "Stops the macro right here"),

        // ---- Variables
        SET_VAR(Group.VARIABLES, Shape.STACK, "set {var} to {value}", "Gives a variable a number. Use it in a message as {name}",
            Param.text("var", "Variable", "count"), Param.number("value", "Value", 0, -1_000_000, 1_000_000, 0.01, "")),
        CHANGE_VAR(Group.VARIABLES, Shape.STACK, "change {var} by {value}", "Adds to a variable (take away with a minus number)",
            Param.text("var", "Variable", "count"), Param.number("value", "By", 1, -1_000_000, 1_000_000, 0.01, "")),
        COMPARE_VAR(Group.VARIABLES, Shape.CONDITION, "{var} {op} {value}", "Compares a variable with a number",
            Param.text("var", "Variable", "count"), Param.op(), Param.number("value", "Value", 10, -1_000_000, 1_000_000, 0.01, "")),

        // ---- Cyanite
        MODULE(Group.CYANITE, Shape.STACK, "turn {module} {state}", "Turns a Cyanite module on or off",
            Param.module("module", "Module"), Param.choice("state", "Set", "On", "Off", "Toggle")),
        MODULE_ON(Group.CYANITE, Shape.CONDITION, "{module} is on", "Whether a Cyanite module is switched on",
            Param.module("module", "Module")),

        // ---- Operators
        AND(Group.OPERATORS, Shape.CONDITION, "<0> and <1>", "True when both are true"),
        OR(Group.OPERATORS, Shape.CONDITION, "<0> or <1>", "True when either is true"),
        NOT(Group.OPERATORS, Shape.CONDITION, "not <0>", "True when the condition inside isn't"),
        CHANCE(Group.OPERATORS, Shape.CONDITION, "{percent} % chance", "True this often, at random",
            Param.number("percent", "Percent", 50, 0, 100, 1, "%")),

        // ---- Sensing
        HEALTH(Group.SENSING, Shape.CONDITION, "health {op} {amount}", "Your health, out of 20 (a heart is 2)",
            Param.op(), Param.number("amount", "Health", 10, 0, 40, 1, "")),
        HUNGER(Group.SENSING, Shape.CONDITION, "hunger {op} {amount}", "Your food bar, out of 20",
            Param.op(), Param.number("amount", "Food", 10, 0, 20, 1, "")),
        Y_LEVEL(Group.SENSING, Shape.CONDITION, "Y level {op} {amount}", "How high up you are",
            Param.op(), Param.number("amount", "Y", 0, -64, 320, 1, "")),
        INVENTORY_FULL(Group.SENSING, Shape.CONDITION, "inventory full", "No empty slot left in your inventory"),
        HOLDING(Group.SENSING, Shape.CONDITION, "holding {item}", "What's in your hand, by name or id: pickaxe, diamond_sword, totem",
            Param.text("item", "Item", "pickaxe")),
        HAS_ITEM(Group.SENSING, Shape.CONDITION, "have {count} {item}", "At least this many of an item in your inventory",
            Param.number("count", "At least", 1, 1, 100_000, 1, ""), Param.text("item", "Item", "diamond")),
        BLOCK_IS(Group.SENSING, Shape.CONDITION, "block at {pos} is {block}", "What a block is, by name or id: stone, chest, air",
            Param.pos("pos", "Block"), Param.text("block", "Is", "stone")),
        LOOKING_AT(Group.SENSING, Shape.CONDITION, "looking at {block}", "The block under your crosshair, by name or id",
            Param.text("block", "Block", "chest")),
        MENU_OPEN(Group.SENSING, Shape.CONDITION, "a menu is open", "A chest, shop or other menu is open"),
        MENU_TITLE(Group.SENSING, Shape.CONDITION, "menu title has {text}", "The open menu's title contains some text",
            Param.text("text", "Title has", "Auction")),
        CHAT_SAID(Group.SENSING, Shape.CONDITION, "chat said {text} in last {seconds} s", "A chat line with this text came in lately",
            Param.text("text", "Text", "sold"), Param.number("seconds", "Within", 5, 1, 600, 1, "s")),
        PLAYER_NEAR(Group.SENSING, Shape.CONDITION, "a player within {range} blocks", "Another player is close",
            Param.number("range", "Range", 16, 1, 256, 1, "")),
        MOB_NEAR(Group.SENSING, Shape.CONDITION, "a hostile mob within {range} blocks", "A monster is close",
            Param.number("range", "Range", 8, 1, 128, 1, "")),
        ON_GROUND(Group.SENSING, Shape.CONDITION, "on the ground", "Standing on something, not falling or jumping"),
        IN_WATER(Group.SENSING, Shape.CONDITION, "in water", "Standing or swimming in water");

        public final Group group;
        public final Shape shape;
        public final String template;
        public final String description;
        public final List<Param> params;
        public final int slots;

        Type(Group group, Shape shape, String template, String description, Param... params) {
            this.group = group;
            this.shape = shape;
            this.template = template;
            this.description = description;
            this.params = List.of(params);
            int n = 0;
            for (String token : template.split(" ")) {
                if (token.startsWith("<") && token.endsWith(">")) n++;
            }
            this.slots = n;
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public boolean isCondition() {
            return shape == Shape.CONDITION;
        }

        public boolean holdsBlocks() {
            return shape == Shape.C || shape == Shape.C_ELSE;
        }

        /** The name for lists and the HUD: the template's first words, capitalised. */
        public String label() {
            StringBuilder sb = new StringBuilder();
            for (String token : template.split(" ")) {
                if (token.startsWith("{") || token.startsWith("<")) {
                    if (sb.isEmpty()) continue;
                    break;
                }
                if (!sb.isEmpty()) sb.append(' ');
                sb.append(token);
            }
            String s = sb.isEmpty() ? id() : sb.toString();
            return Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }

        public static Type byId(String id) {
            for (Type type : values()) {
                if (type.id().equals(id)) return type;
            }
            return null;
        }
    }

    public final Type type;
    private final Map<String, Object> values = new LinkedHashMap<>();
    /** The blocks inside a C block. */
    public final List<MacroStep> body = new ArrayList<>();
    /** The blocks under "else". */
    public final List<MacroStep> elseBody = new ArrayList<>();
    /** Condition blocks in this block's slots; null where a slot is empty. */
    public final MacroStep[] slots;

    public MacroStep(Type type) {
        this.type = type;
        this.slots = new MacroStep[type.slots];
        for (Param param : type.params) {
            if (param.def() != null) values.put(param.key(), param.def());
        }
    }

    public Param param(String key) {
        for (Param param : type.params) {
            if (param.key().equals(key)) return param;
        }
        return null;
    }

    public String text(String key) {
        return values.get(key) instanceof String s ? s : "";
    }

    public double number(String key) {
        return values.get(key) instanceof Double d ? d : 0;
    }

    public BlockPos pos(String key) {
        return values.get(key) instanceof BlockPos p ? p : null;
    }

    /** Stores a value, cleaned up to what the param allows. Unknown keys and wrong kinds are ignored. */
    public void set(String key, Object value) {
        Param param = param(key);
        if (param == null || value == null) return;
        switch (param.kind()) {
            case TEXT, MODULE -> {
                if (!(value instanceof String s)) return;
                String clean = s.replace("\n", " ").replace("\r", "").replace("§", "");
                values.put(key, clean.length() > MAX_TEXT ? clean.substring(0, MAX_TEXT) : clean);
            }
            case NUMBER -> {
                if (!(value instanceof Number n)) return;
                double v = Math.max(param.min(), Math.min(param.max(), n.doubleValue()));
                if (param.step() > 0) v = Math.round(v / param.step()) * param.step();
                values.put(key, Math.round(v * 1000) / 1000.0);
            }
            case POS -> {
                if (value instanceof BlockPos p) values.put(key, p.immutable());
            }
            case CHOICE -> {
                if (value instanceof String s && param.choices().contains(s)) values.put(key, s);
            }
        }
    }

    /** Steps a choice forwards or back through its options. */
    public void cycle(String key, int direction) {
        Param param = param(key);
        if (param == null || param.kind() != Kind.CHOICE) return;
        List<String> choices = param.choices();
        int i = Math.max(0, choices.indexOf(text(key)));
        values.put(key, choices.get(Math.floorMod(i + direction, choices.size())));
    }

    /** How a value reads on the block. */
    public String display(Param param) {
        return switch (param.kind()) {
            case NUMBER -> amount(number(param.key()));
            case POS -> pos(param.key()) == null ? "?" : pos(param.key()).getX() + " " + pos(param.key()).getY() + " " + pos(param.key()).getZ();
            case CHOICE -> text(param.key()).toLowerCase(Locale.ROOT);
            default -> text(param.key()).isEmpty() ? " " : text(param.key());
        };
    }

    /** The block written out in a line, like "if health < 10 then" or "walk forward 3 blocks". */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        int slot = 0;
        for (String token : type.template.split(" ")) {
            if (!sb.isEmpty()) sb.append(' ');
            if (token.startsWith("{") && token.endsWith("}")) {
                Param param = param(token.substring(1, token.length() - 1));
                sb.append(param == null ? token : display(param));
            } else if (token.startsWith("<") && token.endsWith(">")) {
                MacroStep c = slot < slots.length ? slots[slot] : null;
                slot++;
                sb.append(c == null ? "<?>" : "(" + c.summary() + ")");
            } else {
                sb.append(token);
            }
        }
        return sb.toString();
    }

    private static String amount(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.format(Locale.ROOT, "%.2f", v).replaceAll("0+$", "");
    }

    /** This block plus everything inside it. */
    public int size() {
        int n = 1;
        for (MacroStep b : body) n += b.size();
        for (MacroStep b : elseBody) n += b.size();
        for (MacroStep c : slots) {
            if (c != null) n += c.size();
        }
        return n;
    }

    public MacroStep copy() {
        MacroStep copy = new MacroStep(type);
        copy.values.putAll(values);
        for (MacroStep b : body) copy.body.add(b.copy());
        for (MacroStep b : elseBody) copy.elseBody.add(b.copy());
        for (int i = 0; i < slots.length; i++) copy.slots[i] = slots[i] == null ? null : slots[i].copy();
        return copy;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("type", type.id());
        for (Map.Entry<String, Object> e : values.entrySet()) {
            Object v = e.getValue();
            if (v instanceof String s) json.addProperty(e.getKey(), s);
            else if (v instanceof Double d) json.addProperty(e.getKey(), d);
            else if (v instanceof BlockPos p) {
                JsonArray a = new JsonArray();
                a.add(p.getX());
                a.add(p.getY());
                a.add(p.getZ());
                json.add(e.getKey(), a);
            }
        }
        if (type.holdsBlocks()) json.add("body", list(body));
        if (type.shape == Shape.C_ELSE) json.add("else", list(elseBody));
        if (slots.length > 0) {
            JsonArray a = new JsonArray();
            for (MacroStep c : slots) a.add(c == null ? JsonNull.INSTANCE : c.toJson());
            json.add("slots", a);
        }
        return json;
    }

    private static JsonArray list(List<MacroStep> blocks) {
        JsonArray a = new JsonArray();
        for (MacroStep b : blocks) a.add(b.toJson());
        return a;
    }

    /** @return null for a block type this version doesn't know. */
    public static MacroStep fromJson(JsonObject json) {
        return fromJson(json, 0, new int[] {0});
    }

    /** {@code budget[0]} counts blocks read so far, so a shared code can't be made enormous. */
    static MacroStep fromJson(JsonObject json, int depth, int[] budget) {
        if (depth > MAX_DEPTH || budget[0] >= Macro.MAX_BLOCKS) return null;
        if (!(json.get("type") instanceof JsonElement t) || !t.isJsonPrimitive()) return null;
        Type type = Type.byId(t.getAsString());
        if (type == null) return null;
        budget[0]++;
        MacroStep step = new MacroStep(type);
        for (Param param : type.params) {
            JsonElement v = json.get(param.key());
            if (v == null) continue;
            try {
                switch (param.kind()) {
                    case TEXT, MODULE, CHOICE -> step.set(param.key(), v.getAsString());
                    case NUMBER -> step.set(param.key(), v.getAsDouble());
                    case POS -> {
                        if (v instanceof JsonArray a && a.size() == 3) {
                            step.set(param.key(), new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()));
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                // A malformed value keeps the default.
            }
        }
        if (type.holdsBlocks()) readList(json.get("body"), step.body, depth, budget, false);
        if (type.shape == Shape.C_ELSE) readList(json.get("else"), step.elseBody, depth, budget, false);
        if (json.get("slots") instanceof JsonArray a) {
            for (int i = 0; i < step.slots.length && i < a.size(); i++) {
                if (a.get(i) instanceof JsonObject o) {
                    MacroStep c = fromJson(o, depth + 1, budget);
                    if (c != null && c.type.isCondition()) step.slots[i] = c;
                }
            }
        }
        return step;
    }

    static void readList(JsonElement json, List<MacroStep> into, int depth, int[] budget, boolean conditions) {
        if (!(json instanceof JsonArray a)) return;
        for (JsonElement e : a) {
            if (!(e instanceof JsonObject o)) continue;
            MacroStep b = fromJson(o, depth + 1, budget);
            if (b != null && b.type.isCondition() == conditions) into.add(b);
        }
    }
}
