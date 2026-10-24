package com.cyanite.client.system.macro;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** A named script of blocks, run by a key. Shared as a short code anyone can paste in. */
public final class Macro {
    public static final int NO_KEY = -1;
    /** Every block counted, however deeply it's nested. */
    public static final int MAX_BLOCKS = 500;
    public static final int MAX_NAME = 32;
    /** How many times it runs per press; 0 means until stopped. */
    public static final List<Integer> REPEATS = List.of(1, 2, 3, 5, 10, 25, 100, 0);

    private static final String CODE_PREFIX = "CYM1-";
    private static final int MAX_CODE_BYTES = 64 * 1024;

    /**
     * Commands someone could slip into a shared macro to take your stuff: money, items, homes,
     * teleports, trust. Shown loudly before an import is saved.
     */
    private static final Pattern RISKY = Pattern.compile(
        "^/(pay|give|trade|trust|untrust|ah|auction|sell|shop|withdraw|deposit|transfer|gift|eco|bal ?pay|tpa|tpahere|tpaccept|tpyes|"
            + "sethome|delhome|home ?set|team|party|is|island|claim|unclaim|f|faction|kill|suicide|op|deop|ban|kick|msg|w|tell|mail|"
            + "warp|spawn|rtp|wild|ignore|block|friend|ex|exchange|order|orders|coinflip|cf|gamble|bet)\\b",
        Pattern.CASE_INSENSITIVE);

    public String name;
    public int key = NO_KEY;
    public int repeat = 1;
    public final List<MacroStep> steps = new ArrayList<>();

    public Macro(String name) {
        this.name = name;
    }

    public String keyName() {
        return key == NO_KEY ? "No key" : keyName(key);
    }

    public static String keyName(int key) {
        return InputConstants.Type.KEYSYM.getOrCreate(key).getDisplayName().getString();
    }

    public String repeatName() {
        return repeat == 0 ? "Forever" : repeat == 1 ? "Once" : repeat + " times";
    }

    public Macro copy() {
        Macro copy = new Macro(name);
        copy.key = key;
        copy.repeat = repeat;
        for (MacroStep step : steps) copy.steps.add(step.copy());
        return copy;
    }

    public JsonObject toJson(boolean withKey) {
        JsonObject json = new JsonObject();
        json.addProperty("name", name);
        if (withKey && key != NO_KEY) json.addProperty("key", key);
        json.addProperty("repeat", repeat);
        JsonArray list = new JsonArray();
        for (MacroStep step : steps) list.add(step.toJson());
        json.add("steps", list);
        return json;
    }

    public static Macro fromJson(JsonObject json) {
        String name = json.get("name") instanceof JsonElement n && n.isJsonPrimitive() ? n.getAsString() : "Macro";
        Macro macro = new Macro(cleanName(name));
        if (json.get("key") instanceof JsonElement k && k.isJsonPrimitive()) macro.key = k.getAsInt();
        if (json.get("repeat") instanceof JsonElement r && r.isJsonPrimitive()) {
            int repeat = r.getAsInt();
            macro.repeat = REPEATS.contains(repeat) ? repeat : 1;
        }
        MacroStep.readList(json.get("steps"), macro.steps, -1, new int[] {0}, false);
        return macro;
    }

    public static String cleanName(String name) {
        String clean = name.replace("§", "").replace("\n", " ").strip();
        if (clean.isEmpty()) clean = "Macro";
        return clean.length() > MAX_NAME ? clean.substring(0, MAX_NAME) : clean;
    }

    // ---- Sharing

    /** The macro as a short code: its steps squeezed and written as text. Your key isn't included. */
    public String shareCode() {
        byte[] raw = toJson(false).toString().getBytes(StandardCharsets.UTF_8);
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setInput(raw);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer));
        deflater.end();
        return CODE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray());
    }

    /**
     * The code out of a message that has one in it, like "here you go: CYM1-..." with whatever
     * came after it left off. The whole text if there's no code in it.
     */
    public static String findCode(String text) {
        if (text == null) return "";
        int at = text.indexOf(CODE_PREFIX);
        if (at < 0) return text.strip();
        int end = at + CODE_PREFIX.length();
        while (end < text.length() && isCodeChar(text.charAt(end))) end++;
        return text.substring(at, end);
    }

    private static boolean isCodeChar(char c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '_';
    }

    /**
     * Reads a code someone shared. Copied from chat or Discord it can come with spaces, line
     * breaks or quotes around it, which are ignored. It never brings a key with it: you choose one.
     * @return null if it isn't a macro code.
     */
    public static Macro fromShareCode(String code) {
        // The code on its own first; failing that, as if a line break or space had split it.
        Macro strict = decode(findCode(code));
        return strict != null ? strict : decode(code.replaceAll("[\\s\"'`]", ""));
    }

    private static Macro decode(String code) {
        String clean = code;
        int at = clean.indexOf(CODE_PREFIX);
        if (at < 0) return null;
        clean = clean.substring(at + CODE_PREFIX.length());
        try {
            byte[] packed = Base64.getUrlDecoder().decode(clean);
            Inflater inflater = new Inflater();
            inflater.setInput(packed);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                out.write(buffer, 0, n);
                // A code that unpacks to megabytes is not a macro.
                if (out.size() > MAX_CODE_BYTES) {
                    inflater.end();
                    return null;
                }
            }
            inflater.end();
            JsonElement json = JsonParser.parseString(out.toString(StandardCharsets.UTF_8));
            if (!json.isJsonObject()) return null;
            Macro macro = fromJson(json.getAsJsonObject());
            macro.key = NO_KEY;
            return macro;
        } catch (DataFormatException | RuntimeException e) {
            return null;
        }
    }

    /** Whether a block deserves a second look before you run someone else's macro. Doesn't look inside it. */
    public static boolean risky(MacroStep step) {
        return switch (step.type) {
            case SAY -> RISKY.matcher(step.text("text").strip()).find();
            case DROP, CLICK_SLOT, MODULE -> true;
            default -> false;
        };
    }

    /** Every block in the script, inside loops and ifs too, with how deep each one sits. */
    public List<Line> lines() {
        List<Line> out = new ArrayList<>();
        addLines(steps, 0, out);
        return out;
    }

    public record Line(MacroStep step, int depth, boolean elseHeader) {
    }

    private static void addLines(List<MacroStep> list, int depth, List<Line> out) {
        for (MacroStep step : list) {
            out.add(new Line(step, depth, false));
            addLines(step.body, depth + 1, out);
            if (step.type.shape == MacroStep.Shape.C_ELSE) {
                out.add(new Line(step, depth, true));
                addLines(step.elseBody, depth + 1, out);
            }
        }
    }

    /** Every block counted, however deep. */
    public int size() {
        int n = 0;
        for (MacroStep step : steps) n += step.size();
        return n;
    }

    /** Whether anything in the script, or in its conditions, deserves a second look. */
    public boolean anyRisky() {
        for (Line line : lines()) {
            if (!line.elseHeader() && risky(line.step())) return true;
        }
        return false;
    }
}
