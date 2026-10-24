package com.cyanite.client.system;

import com.cyanite.client.gui.Theme;
import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.system.macro.Macro;
import com.cyanite.client.system.macro.MacroRunner;
import com.cyanite.client.system.macro.MacroStep;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Your macros, and the one running now. A macro's key starts it, and pressing the key again stops
 * it. One runs at a time: starting another stops the first.
 */
public final class Macros {
    public static final List<Integer> GAPS = List.of(250, 500, 1000, 2000, 3000);
    public static final int MAX_MACROS = 100;

    private static final List<Macro> MACROS = new ArrayList<>();
    private static final MacroRunner RUNNER = new MacroRunner();
    private static final java.util.ArrayDeque<Heard> CHAT = new java.util.ArrayDeque<>();
    private static int gapMs = 500;

    private Macros() {
    }

    public static List<Macro> list() {
        return Collections.unmodifiableList(MACROS);
    }

    public static Macro add(Macro macro) {
        if (MACROS.size() >= MAX_MACROS) return null;
        MACROS.add(macro);
        return macro;
    }

    public static void remove(Macro macro) {
        if (RUNNER.macro() == macro) RUNNER.stop();
        MACROS.remove(macro);
    }

    public static void move(Macro macro, int direction) {
        int i = MACROS.indexOf(macro);
        int j = i + direction;
        if (i < 0 || j < 0 || j >= MACROS.size()) return;
        Collections.swap(MACROS, i, j);
    }

    public static MacroRunner runner() {
        return RUNNER;
    }

    /** Gap between one "Send" and the next, so the server's spam filter never sees a burst. */
    public static int gapMs() {
        return gapMs;
    }

    public static void setGapMs(int ms) {
        gapMs = Math.max(50, Math.min(10_000, ms));
    }

    public static void start(Macro macro) {
        RUNNER.start(macro);
    }

    public static void stop() {
        RUNNER.stop();
    }

    /** A key pressed in game, with no screen open: its macro starts, or stops if it's already running. */
    public static void onKey(int key) {
        Macro running = RUNNER.macro();
        if (running != null && running.key == key) {
            RUNNER.stop();
            return;
        }
        for (Macro macro : MACROS) {
            if (macro.key != Macro.NO_KEY && macro.key == key) {
                RUNNER.start(macro);
                return;
            }
        }
    }

    public static void tick() {
        RUNNER.tick();
    }

    public static void frame() {
        RUNNER.frame();
    }

    public static void onChat(String line) {
        String plain = net.minecraft.ChatFormatting.stripFormatting(line);
        if (plain != null) {
            long now = System.currentTimeMillis();
            CHAT.addLast(new Heard(now, plain.toLowerCase(java.util.Locale.ROOT)));
            while (CHAT.size() > 200 || !CHAT.isEmpty() && now - CHAT.peekFirst().at > 600_000) CHAT.pollFirst();
        }
        RUNNER.onChat(line);
    }

    private record Heard(long at, String line) {
    }

    /** Whether a chat line containing {@code text} came in during the last {@code withinMs}. */
    public static boolean chatSaid(String text, long withinMs) {
        String needle = text.strip().toLowerCase(java.util.Locale.ROOT);
        long since = System.currentTimeMillis() - withinMs;
        for (Heard heard : CHAT) {
            if (heard.at >= since && (needle.isEmpty() || heard.line.contains(needle))) return true;
        }
        return false;
    }

    /** A small bar at the top of the screen while a macro runs, saying where it's got to and how to stop it. */
    public static void drawHud(GuiGraphics g, int screenWidth) {
        Macro macro = RUNNER.macro();
        if (macro == null) return;
        String head = "Macro: " + macro.name;
        String where = "";
        if (macro.repeat != 1) where += macro.repeat == 0 ? "  round " + (RUNNER.round() + 1) : "  round " + (RUNNER.round() + 1) + "/" + macro.repeat;
        MacroStep current = RUNNER.current();
        String step = current == null ? "" : "  " + current.summary();
        if (step.length() > 48) step = step.substring(0, 47) + "…";
        String hint = macro.key == Macro.NO_KEY ? "" : "  ·  " + macro.keyName() + " stops";
        int w = Text.width(head) + Text.width(where) + Text.width(step) + Text.width(hint) + 14;
        int x = (screenWidth - w) / 2;
        int y = 4;
        int h = Text.CAP + 8;
        int s = Render2D.scale();
        int accent = Theme.accent();
        Render2D.pixels(g);
        Render2D.glow(g, x * s, y * s, w * s, h * s, 3 * s, 6 * s, Theme.alpha(accent, 60 * Theme.glow()));
        Render2D.rect(g, x * s, y * s, w * s, h * s, 3 * s, 0xE6101217);
        Render2D.rect(g, x * s + 3 * s, (y + h) * s - Math.max(1, s / 2), w * s - 6 * s, Math.max(1, s / 2), 0, Theme.alpha(accent, 220));
        Render2D.end(g);
        int tx = x + 7;
        int ty = y + 4;
        Text.draw(g, head, tx, ty, Theme.accentText());
        tx += Text.width(head);
        Text.draw(g, where, tx, ty, Theme.TEXT_FAINT);
        tx += Text.width(where);
        Text.draw(g, step, tx, ty, Theme.TEXT);
        tx += Text.width(step);
        Text.draw(g, hint, tx, ty, Theme.TEXT_FAINT);
    }

    // ---- Saving

    public static JsonObject save() {
        JsonObject json = new JsonObject();
        json.addProperty("version", 2);
        json.addProperty("gapMs", gapMs);
        JsonArray list = new JsonArray();
        for (Macro macro : MACROS) list.add(macro.toJson(true));
        json.add("macros", list);
        return json;
    }

    public static void load(JsonObject json) {
        JsonElement gap = json.has("gapMs") ? json.get("gapMs") : json.get("delayMs");
        if (gap != null && gap.isJsonPrimitive()) setGapMs(gap.getAsInt());

        if (json.get("macros") instanceof JsonArray list) {
            RUNNER.stop();
            MACROS.clear();
            for (JsonElement e : list) {
                if (e instanceof JsonObject o && MACROS.size() < MAX_MACROS) MACROS.add(Macro.fromJson(o));
            }
        } else if (json.get("list") instanceof JsonArray old) {
            // The first version: a key and some commands split with ";". Each becomes a Send step.
            RUNNER.stop();
            MACROS.clear();
            for (JsonElement e : old) {
                if (!(e instanceof JsonObject o) || !(o.get("text") instanceof JsonElement text) || !(o.get("key") instanceof JsonElement key)) continue;
                Macro macro = new Macro(Macro.cleanName(text.getAsString()));
                macro.key = key.getAsInt();
                for (String part : text.getAsString().split(";")) {
                    if (part.isBlank()) continue;
                    MacroStep step = new MacroStep(MacroStep.Type.SAY);
                    step.set("text", part.strip());
                    macro.steps.add(step);
                }
                MACROS.add(macro);
            }
        }
    }
}
