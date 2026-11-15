package com.cyanite.client.gui;

import com.cyanite.client.CyaniteClientMod;
import com.cyanite.client.gui.render.Quads;
import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.system.Stealth;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * What the title and loading screens share in Cyanite's look: the crystal and wordmark, and where things go.
 * Both are laid out in units of their own (about 540 to the window's height) and drawn in framebuffer pixels,
 * so the type and the crystal stay sharp whatever the GUI scale is. The loading screen puts the crystal exactly
 * where the title screen has it, so one turns into the other without it moving.
 */
public final class Home {
    static final int CRYSTAL = 56;
    static final int GAP_MARK = 14;
    static final int WORD = 20;
    static final int GAP_ROWS = 26;
    static final int COLUMN = 240;
    static final int ROW_GAP = 6;
    static final int BIG = 34;
    static final int ROW = 30;
    /** A plain title screen's rows: two big, three usual and the gaps between them. */
    static final float BASE_ROWS = 2 * BIG + 3 * ROW + 4 * ROW_GAP;

    private static final int LOCKUP = CRYSTAL + GAP_MARK + WORD + GAP_ROWS;
    private static final int MARGIN = 16;
    private static final int FOOTER = 32;
    private static final String WORDMARK = "Cyanite";

    /** Frames drawn in this look, for the tests. */
    public static int titleDrawn, loadingDrawn;
    /** Set while a loading screen draws the title screen under itself: it has the crystal, the title screen leaves its own out. */
    public static boolean lockupAbove;
    /** The title screen's rows as it last laid them out, so the loading screen matches it. */
    static float rows = BASE_ROWS;

    private static boolean ready;
    private static long entranceStart;

    private Home() {
    }

    /** Cyanite has started. Before this the screens stay the game's: the settings aren't loaded yet. */
    public static void ready() {
        ready = true;
    }

    static boolean isReady() {
        return ready;
    }

    public static boolean themed() {
        return ready && GuiSettings.HOME.get() && !Stealth.active();
    }

    /** Joined a world: the title screen's rejoin row remembers the server, and the disconnected screen's tries start again. */
    public static void joined(Minecraft mc) {
        HomeServer.joined(mc);
        HomeDisconnect.joined(mc);
    }

    /** The game's panorama is wanted behind the title screen, under a tint of Cyanite's. */
    public static boolean panorama() {
        return Backdrop.panorama();
    }

    /** Behind the game's menus out of a world, in place of the panorama. False when the game should draw its own. */
    public static boolean backdrop(GuiGraphics g) {
        if (!themed() || Backdrop.panorama()) return false;
        Metrics m = metrics(rows);
        if (m == null) return false;
        begin(g, m);
        Backdrop.draw(g, m, 1);
        end(g);
        return true;
    }

    // ---- Where things go

    /** One frame's measurements, in framebuffer pixels. {@code s} is how many of them a unit is. */
    record Metrics(float s, int guiScale, int width, int height, int top) {
        int u(float units) {
            return Math.round(units * s);
        }

        int cx() {
            return width / 2;
        }

        int rowsTop() {
            return top + u(LOCKUP);
        }
    }

    /** Null while the window has no size (minimised). */
    static Metrics metrics(float rows) {
        Window window = Minecraft.getInstance().getWindow();
        int w = window.getWidth();
        int h = window.getHeight();
        if (w <= 0 || h <= 0) return null;

        float need = MARGIN + LOCKUP + rows + FOOTER;
        float s = Math.max(1.25f, Math.round(h / 540f * 4) / 4f);
        while (s > 0.5f && (need * s > h || (COLUMN + 2 * MARGIN) * s > w)) s -= 0.25f;

        // The crystal sits where a plain title screen has it; more rows grow downwards while there's room.
        int top = Math.round((h - (LOCKUP + BASE_ROWS) * s) * 0.46f);
        float bottom = h - FOOTER * s;
        if (top + (LOCKUP + rows) * s > bottom) top = Math.round(bottom - (LOCKUP + rows) * s);
        top = Math.max(top, Math.round(MARGIN * s));
        return new Metrics(s, Math.max(1, window.getGuiScale()), w, h, top);
    }

    /** For a screen with a smaller {@code header} (units) over its column in place of the crystal and wordmark; {@code top} is the header's. */
    static Metrics metrics(float header, float rows) {
        Window window = Minecraft.getInstance().getWindow();
        int w = window.getWidth();
        int h = window.getHeight();
        if (w <= 0 || h <= 0) return null;

        float need = 2 * MARGIN + header + rows;
        float s = Math.max(1.25f, Math.round(h / 540f * 4) / 4f);
        while (s > 0.5f && (need * s > h || (COLUMN + 2 * MARGIN) * s > w)) s -= 0.25f;
        int top = Math.max(Math.round(MARGIN * s), Math.round((h - (header + rows) * s) * 0.45f));
        return new Metrics(s, Math.max(1, window.getGuiScale()), w, h, top);
    }

    static void begin(GuiGraphics g, Metrics m) {
        g.pose().pushMatrix();
        g.pose().scale(1f / m.guiScale());
    }

    static void end(GuiGraphics g) {
        g.pose().popMatrix();
    }

    /** 0 to 1 as the title screen's {@code index}th piece arrives, the first time the screen shows. */
    static float entrance(int index) {
        if (!Anim.enabled()) return 1;
        long now = Util.getMillis();
        if (entranceStart == 0) entranceStart = now;
        return Anim.easeOut((now - entranceStart - index * 30L) / 220f);
    }

    // ---- The crystal and wordmark

    static void lockup(GuiGraphics g, Metrics m, float alpha, boolean gameFont) {
        int h = m.u(CRYSTAL);
        crystal(g, m.cx() - crystalWidth(h) / 2, m.top(), h, alpha, true);

        int cap = m.u(WORD);
        Text.Face face = Text.face(true, cap, WORDMARK);
        // The game's own font isn't there yet on the first loading screen.
        if (!face.smooth(WORDMARK) && !gameFont) return;
        float tracking = 0.06f * cap;
        float width = face.width(WORDMARK, tracking);
        face.draw(g, WORDMARK, Math.round(m.cx() - width / 2f), m.top() + m.u(CRYSTAL + GAP_MARK), Theme.fade(Theme.TEXT, alpha), tracking);
    }

    /**
     * The loading screen: {@code cover} is how solid its backdrop is over what's underneath, {@code mark} the
     * crystal and wordmark, {@code bar} the progress bar, {@code secondsLeft} the guess beside it (below 1 for none).
     */
    public static void loading(GuiGraphics g, float cover, float mark, boolean gameFont, float progress, float bar, int secondsLeft) {
        Metrics m = metrics(rows);
        if (m == null) return;
        loadingDrawn++;

        begin(g, m);
        Backdrop.draw(g, m, cover);
        lockup(g, m, mark, gameFont);

        float a = cover * bar;
        if (a > 0.004f) {
            // Where the first button will be.
            int w = m.u(160);
            int h = Math.max(2, m.u(3));
            int x = m.cx() - w / 2;
            int y = m.rowsTop() + (m.u(BIG) - h) / 2;
            Render2D.rect(g, x, y, w, h, h, Theme.fade(Theme.TRACK, a));
            int fill = Math.round(w * Anim.clamp(progress));
            if (fill > 0) {
                fill = Math.max(h, fill);
                int accent = Theme.accent();
                Render2D.glow(g, x, y, fill, h, h, m.u(6), Theme.fade(accent, 0.35f * Math.min(Theme.glow(), 1.5f) * a));
                Render2D.rect(g, x, y, fill, h, h, Theme.fade(accent, a));
            }

            String charset = tipCharset();
            if (secondsLeft >= 1) {
                // Small and faint, over the bar's right end.
                String left = "About " + secondsLeft + " s";
                int cap = Math.max(4, m.u(5));
                Text.Face face = Text.face(false, cap, charset);
                if (face.smooth(left) || gameFont) {
                    face.draw(g, left, Math.round(x + w - face.width(left)), y - m.u(7) - cap, Theme.fade(Theme.TEXT_FAINT, a));
                }
            }
            tip(g, m, y + h + m.u(14), a, gameFont, charset);
        }
        end(g);
    }

    // ---- Tips under the loading bar

    /** {@code %k} is the menu's key. Plain facts about Cyanite, nothing about the player or a server. */
    private static final String[] TIPS = {
        "%k opens the Cyanite Menu, even out here",
        "%k + I hides every sign of Cyanite",
        "In the Cyanite Menu, just start typing to search",
        "Right click a module to see its settings",
        "Every module has a Bind setting: click it, then press a key",
        "Save your setup as a profile and switch in one click",
        "A profile shares as one line of text",
        "Drag the watermark and arraylist anywhere from the HUD tab",
        "Import brings your Meteor Client and Wurst setups across",
        "Switch accounts from the Accounts tab before you join",
        "With Clips on, F8 saves the last minute as a video",
        "Name Protect hides your real name on your screen",
    };
    /** How long each tip stays, and its fade in and out, in milliseconds. */
    private static final long TIP_MS = 5500, TIP_FADE = 250;
    private static final int TIP_FIRST = new Random().nextInt(TIPS.length);
    private static long tipsStart;
    private static String tipKey = "", tipCharset = "";

    private static String tipCharset() {
        String key = menuKeyName();
        if (!key.equals(tipKey)) {
            tipKey = key;
            StringBuilder all = new StringBuilder("About 0123456789s").append(key);
            for (String tip : TIPS) all.append(tip);
            tipCharset = all.chars().distinct().sorted().collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        }
        return tipCharset;
    }

    /** The menu key's name, before the game's language has loaded too ("key.keyboard.insert" becomes "Insert"). */
    private static String menuKeyName() {
        KeyMapping key = CyaniteClientMod.clickGuiKey();
        if (key == null || key.isUnbound()) return "Insert";
        String name = key.getTranslatedKeyMessage().getString();
        if (!name.startsWith("key.")) return name;
        String[] parts = key.saveString().replace("key.keyboard.", "").replace("key.mouse.", "Mouse ").split("\\.");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }

    private static void tip(GuiGraphics g, Metrics m, int y, float alpha, boolean gameFont, String charset) {
        long now = Util.getMillis();
        if (tipsStart == 0) tipsStart = now;
        long since = now - tipsStart;
        String tip = TIPS[(int) ((TIP_FIRST + since / TIP_MS) % TIPS.length)].replace("%k", tipKey);
        long into = since % TIP_MS;
        float a = alpha * (Anim.enabled() ? Math.min(Anim.clamp(into / (float) TIP_FADE), Anim.clamp((TIP_MS - into) / (float) TIP_FADE)) : 1);
        if (a <= 0.004f) return;
        int cap = Math.max(4, m.u(6));
        Text.Face face = Text.face(false, cap, charset);
        // The game's own font isn't there yet on the first loading screen.
        if (!face.smooth(tip) && !gameFont) return;
        face.draw(g, tip, Math.round(m.cx() - face.width(tip) / 2f), y, Theme.fade(Theme.TEXT_DIM, a));
    }

    // ---- Pacing the start-up bar

    private static long startupShownAt;
    private static boolean startupTimed;

    /**
     * The start-up loading screen is showing at {@code now}: how far along to show it, given the game's own
     * {@code actual} progress. The game's moves in jumps and stalls; this one also creeps on at the pace the last
     * starts took, but never past 95% before the game says it's done.
     */
    public static float startupProgress(float actual) {
        long now = Util.getMillis();
        if (startupShownAt == 0) startupShownAt = now;
        long expected = HomeData.startupMs();
        if (expected <= 0 || startupTimed) return actual;
        return Math.max(actual, 0.95f * Anim.clamp((now - startupShownAt) / (float) expected));
    }

    /** About how many seconds the start-up has left, by the last starts; 0 when there's no telling. */
    public static int startupSecondsLeft() {
        long expected = HomeData.startupMs();
        if (expected <= 0 || startupShownAt == 0 || startupTimed) return 0;
        long left = expected - (Util.getMillis() - startupShownAt);
        return left < 1500 ? 0 : (int) Math.ceil(left / 1000.0);
    }

    /** The start-up's loading finished at {@code at} (game millis): remembered to pace the next start. Once a session. */
    public static void startupDone(long at) {
        if (startupTimed || startupShownAt == 0) return;
        startupTimed = true;
        HomeData.startupTook(at - startupShownAt);
    }

    // ---- Baking the crystal

    /** Glow, body and highlight side by side in one texture, each with {@code pad} pixels of room around the crystal. */
    private record Crystal(Identifier texture, int w, int h, int pad) {
    }

    // The outline in a box 0.423 times as wide as it's tall: tip, right shoulder, down to the foot, and back up the left.
    private static final float[] OUTLINE = {0.5f, 0f, 1f, 0.21f, 0.767f, 0.958f, 0.5f, 1f, 0.233f, 0.958f, 0f, 0.21f};
    private static final float LEFT_FACET = 0.78f;
    private static final int MAX_CRYSTALS = 6;
    private static final Map<Integer, Crystal> CRYSTALS = new HashMap<>();
    private static int bakes;

    static int crystalWidth(int h) {
        return Math.max(3, Math.round(0.423f * h));
    }

    /** The crystal {@code h} pixels tall in the accent colour, its box's top left corner at (x, y). */
    static void crystal(GuiGraphics g, int x, int y, int h, float alpha, boolean glow) {
        if (h < 4 || alpha <= 0.004f) return;
        Crystal c = crystal(h);
        int accent = Theme.accent();
        if (glow) region(g, c, 0, x, y, Theme.fade(accent, alpha * Math.min(1f, 0.55f * Theme.glow())));
        region(g, c, 1, x, y, Theme.fade(accent, alpha));
        region(g, c, 2, x, y, Theme.fade(0xFFFFFFFF, alpha));
    }

    private static void region(GuiGraphics g, Crystal c, int which, int x, int y, int color) {
        if ((color >>> 24) == 0) return;
        float x0 = x - c.pad;
        float y0 = y - c.pad;
        Quads quads = Quads.of(g, c.texture, color);
        quads.add(x0, y0, x0 + c.w + 2 * c.pad, y0 + c.h + 2 * c.pad, which / 3f, 0, (which + 1) / 3f, 1);
        quads.submit(g);
    }

    private static Crystal crystal(int h) {
        Crystal cached = CRYSTALS.get(h);
        if (cached != null) return cached;

        if (CRYSTALS.size() >= MAX_CRYSTALS) {
            // Let go a frame late: what this frame drew with them hasn't reached the screen yet.
            List<Identifier> old = CRYSTALS.values().stream().map(Crystal::texture).toList();
            CRYSTALS.clear();
            Minecraft mc = Minecraft.getInstance();
            mc.schedule(() -> old.forEach(mc.getTextureManager()::release));
        }

        int w = crystalWidth(h);
        int pad = Math.round(0.45f * h);
        int cellW = w + 2 * pad;
        int cellH = h + 2 * pad;
        int points = OUTLINE.length / 2;
        float[] px = new float[points];
        float[] py = new float[points];
        for (int i = 0; i < points; i++) {
            px[i] = pad + OUTLINE[2 * i] * w;
            py[i] = pad + OUTLINE[2 * i + 1] * h;
        }
        float ridge = pad + w / 2f;
        float ridgeHalf = Math.max(1f, Math.round(h / 64f)) / 2f;

        NativeImage img = new NativeImage(cellW * 3, cellH, false);
        for (int y = 0; y < cellH; y++) {
            for (int x = 0; x < cellW; x++) {
                float fx = x + 0.5f;
                float fy = y + 0.5f;
                boolean near = x >= pad - 1 && x <= pad + w && y >= pad - 1 && y <= pad + h;
                float cover = near ? coverage(px, py, x, y) : 0;

                float glow = cover;
                if (cover < 1) {
                    float d = distance(px, py, fx, fy);
                    glow = Math.max(cover, (float) Math.pow(Anim.clamp(1f - d / pad), 3));
                }
                // Lit from the right: the left facet a shade darker, the right one lifted, a bright ridge between.
                float right = Anim.clamp(fx - ridge + 0.5f);
                float shade = LEFT_FACET + (1f - LEFT_FACET) * right;
                float line = Anim.clamp(ridgeHalf + 0.5f - Math.abs(fx - ridge));
                float light = Math.max(0.20f * right, 0.65f * line);

                img.setPixel(x, y, pixel(glow, 1));
                img.setPixel(cellW + x, y, pixel(cover, shade));
                img.setPixel(2 * cellW + x, y, pixel(cover * light, 1));
            }
        }

        // A name of its own each time: an old one may still be waiting to be let go.
        Crystal crystal = new Crystal(Render2D.upload("home/crystal_" + bakes++, img), w, h, pad);
        CRYSTALS.put(h, crystal);
        return crystal;
    }

    private static int pixel(float alpha, float grey) {
        int a = Math.round(Anim.clamp(alpha) * 255f);
        int v = Math.round(Anim.clamp(grey) * 255f);
        return a << 24 | v << 16 | v << 8 | v;
    }

    /** How much of pixel (x, y) the outline covers, from 16 samples. */
    private static float coverage(float[] px, float[] py, int x, int y) {
        int in = 0;
        for (int sy = 0; sy < 4; sy++) {
            for (int sx = 0; sx < 4; sx++) {
                if (inside(px, py, x + (sx + 0.5f) / 4f, y + (sy + 0.5f) / 4f)) in++;
            }
        }
        return in / 16f;
    }

    private static boolean inside(float[] px, float[] py, float x, float y) {
        boolean in = false;
        for (int i = 0, j = px.length - 1; i < px.length; j = i++) {
            if ((py[i] > y) != (py[j] > y) && x < (px[j] - px[i]) * (y - py[i]) / (py[j] - py[i]) + px[i]) in = !in;
        }
        return in;
    }

    /** Distance from a point to the outline. */
    private static float distance(float[] px, float[] py, float x, float y) {
        float best = Float.MAX_VALUE;
        for (int i = 0, j = px.length - 1; i < px.length; j = i++) {
            float vx = px[i] - px[j];
            float vy = py[i] - py[j];
            float t = Anim.clamp(((x - px[j]) * vx + (y - py[j]) * vy) / (vx * vx + vy * vy));
            float dx = x - (px[j] + vx * t);
            float dy = y - (py[j] + vy * t);
            best = Math.min(best, dx * dx + dy * dy);
        }
        return (float) Math.sqrt(best);
    }
}
