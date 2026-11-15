package com.cyanite.client.gui;

import com.cyanite.client.config.Config;
import com.cyanite.client.gui.render.Quads;
import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.system.Notify;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Window;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.slf4j.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

/**
 * What's behind the title and loading screens, and the game's menus out of a world, in place of the panorama
 * ("Title background"). Crystal: deep navy lit from where the crystal is, a faint crystal lattice either side of the
 * buttons and a few sparks drifting up. Minimal: just the navy and its light. Picture: yours, under a navy tint.
 * Panorama leaves the game's own to the menus and tints it on the title screen. All of them get fine grain so the
 * soft light doesn't band, and drift a little against the mouse. Drawn in framebuffer pixels like the rest of {@link Home}.
 */
final class Backdrop {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int TOP = 0xFF0B1120;
    private static final int BOTTOM = 0xFF04060C;
    private static final int WHITE = 0xFFFFFFFF;
    // Over the game's panorama, and lighter over a picture of your own so it still shows.
    private static final int PANORAMA_TOP = 0x8C05070C;
    private static final int PANORAMA_BOTTOM = 0xD905070C;
    private static final int PICTURE_TOP = 0x5905070C;
    private static final int PICTURE_BOTTOM = 0xC705070C;
    private static final int WORLD_TOP = 0x4005070C;
    private static final int WORLD_BOTTOM = 0xA605070C;
    /** Units between the lattice's points. */
    private static final int LATTICE = 18;
    private static final int GRAIN = 128;
    private static final int MOTES = 22;
    /** How far, in units, each layer drifts against the mouse: the near ones further. */
    private static final float DRIFT_LIGHT = 3, DRIFT_LATTICE = 6, DRIFT_PICTURE = 8, DRIFT_MOTES = 10;
    private static final String[] PICTURES = {"background.png", "background.jpg", "background.jpeg"};
    /** Longest side a picture is kept at; bigger ones are scaled down when they're read. */
    private static final int PICTURE_MAX = 3200;
    private static final float TAU = (float) (Math.PI * 2);

    // Each spark: across (0-1), rise (units a second), size (units), sway (units), sway period, phase, twinkle period, brightness.
    private static final float[][] MOTE = new float[MOTES][];

    static {
        Random r = new Random(1729);
        for (int i = 0; i < MOTES; i++) {
            MOTE[i] = new float[] {0.04f + 0.92f * r.nextFloat(), 5 + 7 * r.nextFloat(), 0.9f + 1.3f * r.nextFloat(), 4 + 10 * r.nextFloat(),
                6 + 7 * r.nextFloat(), r.nextFloat(), 2 + 2.5f * r.nextFloat(), 0.35f + 0.4f * r.nextFloat()};
        }
    }

    private static Identifier light, dot, grain;
    private record LatticeKey(int width, int height, int cx, int cy, float s) {
    }
    private static LatticeKey latticeKey;
    private static Identifier lattice;
    private static int bakes;

    // The mouse, eased: -1 to 1 across the window each way.
    private static float lookX, lookY;
    private static long looked;

    // Your picture: what's shown, what's being read, and which file and version of it they are.
    private static Identifier picture;
    private static int pictureW, pictureH;
    private static long pictureShownAt;
    private static String pictureStamp = "";
    private static volatile NativeImage pictureRead;
    private static volatile boolean reading;
    private static long pictureChecked;

    private Backdrop() {
    }

    /** The game draws its panorama behind the menus, and the title screen tints it. */
    static boolean panorama() {
        return GuiSettings.BACKGROUND.is("Panorama");
    }

    /** The whole backdrop at {@code alpha}, the loading screen's fade over the screen underneath. */
    static void draw(GuiGraphics g, Home.Metrics m, float alpha) {
        if (alpha <= 0.004f) return;
        int w = m.width();
        int h = m.height();
        look();
        g.fillGradient(0, 0, w, h, Theme.fade(TOP, alpha), Theme.fade(BOTTOM, alpha));

        String mode = GuiSettings.BACKGROUND.get();
        boolean crystal = mode.equals("Crystal");
        boolean own = mode.equals("Picture") && picture(g, m, alpha);
        if (!mode.equals("Picture") && picture != null) {
            // Not wanted any more: let it go, and look for the file afresh if it's picked again.
            drop();
            pictureStamp = "";
        }
        if (own) g.fillGradient(0, 0, w, h, Theme.fade(PICTURE_TOP, alpha), Theme.fade(PICTURE_BOTTOM, alpha));

        // One clock for both screens, so the loading screen hands over to the title screen without a jump.
        float t = clock();
        lights(g, m, t, alpha, crystal, own ? 0.6f : 1f);
        if (crystal) {
            Quads dots = Quads.of(g, lattice(m, Math.round(m.cx()), Math.round(lightY(m))), Theme.fade(Theme.mix(Theme.accent(), WHITE, 0.45f), alpha));
            int ox = Math.round(-lookX * m.u(DRIFT_LATTICE));
            int oy = Math.round(-lookY * m.u(DRIFT_LATTICE));
            dots.add(ox, oy, w + ox, h + oy, 0, 0, 1, 1);
            dots.submit(g);
            motes(g, m, t, Theme.accent(), alpha);
        }
        grain(g, m, alpha);
    }

    /** Over the game's panorama on the title screen: a navy tint and the crystal's light, so the buttons read. */
    static void overPanorama(GuiGraphics g, Home.Metrics m) {
        look();
        g.fillGradient(0, 0, m.width(), m.height(), PANORAMA_TOP, PANORAMA_BOTTOM);
        lights(g, m, clock(), 1, false, 0.8f);
        grain(g, m, 1);
    }

    /** Over the blurred world behind the pause menu: lighter, so where you are still shows through. */
    static void overWorld(GuiGraphics g, Home.Metrics m) {
        look();
        g.fillGradient(0, 0, m.width(), m.height(), WORLD_TOP, WORLD_BOTTOM);
        lights(g, m, clock(), 1, false, 0.7f);
        grain(g, m, 1);
    }

    private static float clock() {
        return Anim.enabled() ? (Util.getMillis() % 3_600_000L) / 1000f : 0;
    }

    private static float lightY(Home.Metrics m) {
        return m.top() + m.u(Home.CRYSTAL * 0.55f);
    }

    /** The crystal's light breathing slowly, and with {@code all} two faint ones drifting low down. */
    private static void lights(GuiGraphics g, Home.Metrics m, float t, float alpha, boolean all, float scale) {
        float strength = Math.min(Theme.glow(), 1.5f) * alpha * scale;
        if (strength <= 0.004f) return;
        int w = m.width();
        int h = m.height();
        int accent = Theme.accent();
        float dx = -lookX * m.u(DRIFT_LIGHT);
        float dy = -lookY * m.u(DRIFT_LIGHT);
        if (all) {
            light(g, dx + w * 0.14f + m.u(50) * wave(t, 41, 0), dy + h * 0.92f + m.u(24) * wave(t, 53, 0.3f), m.u(360), m.u(300), Theme.fade(accent, 0.09f * strength));
            light(g, dx + w * 0.88f + m.u(40) * wave(t, 59, 0.6f), dy + h * 0.60f + m.u(30) * wave(t, 47, 0.1f), m.u(300), m.u(260), Theme.fade(accent, 0.07f * strength));
        }
        light(g, dx + m.cx(), dy + lightY(m), m.u(430), m.u(300), Theme.fade(accent, 0.20f * (0.9f + 0.1f * wave(t, 8, 0)) * strength));
    }

    private static void grain(GuiGraphics g, Home.Metrics m, float alpha) {
        Quads noise = Quads.of(g, grain(), Theme.fade(WHITE, alpha));
        noise.add(0, 0, m.width(), m.height(), 0, 0, m.width() / (float) GRAIN, m.height() / (float) GRAIN);
        noise.submit(g);
    }

    /** Eases towards where the mouse is, once a frame however many screens draw a backdrop. */
    private static void look() {
        long now = Util.getMillis();
        float dt = looked == 0 ? 0 : Math.min(0.1f, (now - looked) / 1000f);
        if (looked != 0 && dt <= 0) return;
        looked = now;
        Minecraft mc = Minecraft.getInstance();
        Window window = mc.getWindow();
        float tx = 0, ty = 0;
        if (Anim.enabled() && window.getScreenWidth() > 0 && window.getScreenHeight() > 0) {
            tx = Math.clamp((float) (mc.mouseHandler.xpos() / window.getScreenWidth()) * 2 - 1, -1f, 1f);
            ty = Math.clamp((float) (mc.mouseHandler.ypos() / window.getScreenHeight()) * 2 - 1, -1f, 1f);
        }
        lookX = Anim.approach(lookX, tx, dt, 2.5f);
        lookY = Anim.approach(lookY, ty, dt, 2.5f);
    }

    private static float wave(float t, float period, float phase) {
        return (float) Math.sin(TAU * (t / period + phase));
    }

    /** A soft round light, {@code rx} by {@code ry} pixels from its middle to where it's gone. */
    private static void light(GuiGraphics g, float x, float y, float rx, float ry, int color) {
        if ((color >>> 24) == 0) return;
        Quads q = Quads.of(g, light(), color);
        q.add(x - rx, y - ry, x + rx, y + ry, 0, 0, 1, 1);
        q.submit(g);
    }

    private static void motes(GuiGraphics g, Home.Metrics m, float t, int accent, float alpha) {
        int w = m.width();
        int h = m.height();
        float margin = m.u(12);
        float travel = h + 2 * margin;
        int core = Theme.mix(accent, WHITE, 0.55f);
        float dx = -lookX * m.u(DRIFT_MOTES);
        float dy = -lookY * m.u(DRIFT_MOTES);
        for (float[] mote : MOTE) {
            float phase = mote[5];
            float p = ((t * mote[1] * m.s() + phase * travel) % travel) / travel;
            // In from the bottom, gone by the top.
            float life = smooth(0f, 0.12f, p) * (1f - smooth(0.5f, 0.95f, p));
            float a = mote[7] * life * (0.7f + 0.3f * wave(t, mote[6], phase)) * alpha;
            if (a <= 0.004f) continue;
            float x = dx + mote[0] * w + mote[3] * m.s() * wave(t, mote[4], phase);
            float y = dy + h + margin - p * travel;
            float size = mote[2] * m.s();
            light(g, x, y, 5 * size, 5 * size, Theme.fade(accent, 0.35f * a));
            Quads q = Quads.of(g, dot(), Theme.fade(core, a));
            q.add(x - size, y - size, x + size, y + size, 0, 0, 1, 1);
            q.submit(g);
        }
    }

    private static float smooth(float from, float to, float v) {
        float x = Anim.clamp((v - from) / (to - from));
        return x * x * (3 - 2 * x);
    }

    // ---- Your picture

    /** "Picture" was just picked: says where it goes when there isn't one yet. */
    static void picked() {
        if (!GuiSettings.BACKGROUND.is("Picture") || !Home.isReady() || Minecraft.getInstance().screen == null) return;
        if (pictureFile() == null) Notify.send("Title background", "Put background.png or .jpg in the cyanite folder", true, false, false);
    }

    private static Path pictureFile() {
        for (String name : PICTURES) {
            Path file = Config.dir().resolve(name);
            if (Files.isRegularFile(file)) return file;
        }
        return null;
    }

    /** Your picture filling the window and fading in once it's read; false while there's none to show. */
    private static boolean picture(GuiGraphics g, Home.Metrics m, float alpha) {
        long now = Util.getMillis();
        // A look for a new or changed file now and then, read off the game's thread.
        if (!reading && pictureRead == null && now - pictureChecked > 1000) {
            pictureChecked = now;
            Path file = pictureFile();
            String stamp = stamp(file);
            if (!stamp.equals(pictureStamp)) {
                pictureStamp = stamp;
                if (file == null) {
                    drop();
                } else {
                    reading = true;
                    CompletableFuture.runAsync(() -> read(file), Util.ioPool());
                }
            }
        }
        NativeImage read = pictureRead;
        if (read != null) {
            pictureRead = null;
            drop();
            pictureW = read.getWidth();
            pictureH = read.getHeight();
            picture = Render2D.upload("home/picture_" + bakes++, read, true);
            pictureShownAt = now;
        }
        if (picture == null) return false;

        // Covers the window with a little to spare for the drift.
        float spare = m.u(DRIFT_PICTURE);
        float scale = Math.max((m.width() + 2 * spare) / pictureW, (m.height() + 2 * spare) / pictureH);
        float w = pictureW * scale;
        float h = pictureH * scale;
        float x = (m.width() - w) / 2f - lookX * spare;
        float y = (m.height() - h) / 2f - lookY * spare;
        float in = Anim.enabled() ? Anim.easeOut((now - pictureShownAt) / 300f) : 1;
        Quads q = Quads.of(g, picture, Theme.fade(WHITE, alpha * in));
        q.add(x, y, x + w, y + h, 0, 0, 1, 1);
        q.submit(g);
        return true;
    }

    private static String stamp(Path file) {
        if (file == null) return "";
        try {
            return file.getFileName() + "|" + Files.size(file) + "|" + Files.getLastModifiedTime(file).toMillis();
        } catch (Exception e) {
            return "";
        }
    }

    private static void drop() {
        Identifier old = picture;
        picture = null;
        release(old);
    }

    private static void read(Path file) {
        try {
            NativeImage img;
            if (file.getFileName().toString().endsWith(".png")) {
                try (InputStream in = Files.newInputStream(file)) {
                    img = NativeImage.read(NativeImage.Format.RGBA, in);
                }
            } else {
                BufferedImage src = ImageIO.read(file.toFile());
                if (src == null) throw new IllegalArgumentException("not a picture Java can read");
                img = new NativeImage(src.getWidth(), src.getHeight(), false);
                int[] row = new int[src.getWidth()];
                for (int y = 0; y < src.getHeight(); y++) {
                    src.getRGB(0, y, row.length, 1, row, 0, row.length);
                    for (int x = 0; x < row.length; x++) img.setPixel(x, y, row[x] | 0xFF000000);
                }
            }
            int longest = Math.max(img.getWidth(), img.getHeight());
            if (longest > PICTURE_MAX) {
                float f = PICTURE_MAX / (float) longest;
                NativeImage smaller = new NativeImage(Math.max(1, Math.round(img.getWidth() * f)), Math.max(1, Math.round(img.getHeight() * f)), false);
                img.resizeSubRectTo(0, 0, img.getWidth(), img.getHeight(), smaller);
                img.close();
                img = smaller;
            }
            pictureRead = img;
        } catch (Throwable e) {
            LOGGER.warn("Couldn't read the title background {}: {}", file.getFileName(), e.toString());
        } finally {
            reading = false;
        }
    }

    // ---- Baked once

    private static Identifier light() {
        if (light == null) {
            int n = 128;
            NativeImage img = new NativeImage(n, n, false);
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    float dx = (x + 0.5f) / n * 2 - 1;
                    float dy = (y + 0.5f) / n * 2 - 1;
                    float r = (float) Math.sqrt(dx * dx + dy * dy);
                    float a = (float) Math.exp(-3.2f * r * r) * (1f - smooth(0.7f, 1f, r));
                    img.setPixel(x, y, white(a));
                }
            }
            light = Render2D.upload("home/light", img, true);
        }
        return light;
    }

    private static Identifier dot() {
        if (dot == null) {
            int n = 16;
            NativeImage img = new NativeImage(n, n, false);
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    float dx = (x + 0.5f) / n * 2 - 1;
                    float dy = (y + 0.5f) / n * 2 - 1;
                    img.setPixel(x, y, white(Anim.clamp((1f - (float) Math.sqrt(dx * dx + dy * dy)) * 3f)));
                }
            }
            dot = Render2D.upload("home/dot", img, true);
        }
        return dot;
    }

    /** Light and dark specks, a few steps either way: enough to break up banding, little enough to go unseen. */
    private static Identifier grain() {
        if (grain == null) {
            Random r = new Random(4111);
            NativeImage img = new NativeImage(GRAIN, GRAIN, false);
            for (int y = 0; y < GRAIN; y++) {
                for (int x = 0; x < GRAIN; x++) {
                    img.setPixel(x, y, r.nextInt(10) << 24 | (r.nextBoolean() ? 0xFFFFFF : 0));
                }
            }
            grain = Render2D.upload("home/grain", img);
        }
        return grain;
    }

    /**
     * The lattice's dots for this window: either side of the button column, fading out towards the edges.
     * Baked again only when the window or the layout changes.
     */
    private static Identifier lattice(Home.Metrics m, int cx, int cy) {
        LatticeKey key = new LatticeKey(m.width(), m.height(), cx, cy, m.s());
        if (key.equals(latticeKey)) return lattice;

        int w = m.width();
        int h = m.height();
        float spacing = LATTICE * m.s();
        float rowHeight = spacing * 0.8660254f;
        float radius = Math.max(0.55f, 0.5f * m.s());
        float half = m.u(Home.COLUMN) / 2f;
        float clear = half + m.u(8);
        float full = half + m.u(70);
        float my = cy + m.u(90);

        NativeImage img = new NativeImage(w, h, true);
        int rows = (int) Math.ceil(h / rowHeight) + 1;
        for (int row = 0; row <= rows; row++) {
            float py = row * rowHeight;
            float shift = (row & 1) == 0 ? 0 : spacing / 2;
            for (float px = shift; px < w + spacing; px += spacing) {
                float side = smooth(clear, full, Math.abs(px - cx));
                float dx = (px - cx) / (0.55f * w);
                float dy = (py - my) / (0.65f * h);
                float a = 0.32f * side * (1f - smooth(0.45f, 1f, (float) Math.sqrt(dx * dx + dy * dy)));
                if (a > 0.004f) stamp(img, px, py, radius, a);
            }
        }

        Identifier old = lattice;
        lattice = Render2D.upload("home/lattice_" + bakes++, img);
        latticeKey = key;
        release(old);
        return lattice;
    }

    /** Lets a texture go a frame late: this frame may have drawn with it already. */
    private static void release(Identifier old) {
        if (old == null) return;
        Minecraft mc = Minecraft.getInstance();
        mc.schedule(() -> mc.getTextureManager().release(old));
    }

    private static void stamp(NativeImage img, float cx, float cy, float radius, float alpha) {
        int x0 = Math.max(0, (int) Math.floor(cx - radius - 1));
        int x1 = Math.min(img.getWidth() - 1, (int) Math.ceil(cx + radius + 1));
        int y0 = Math.max(0, (int) Math.floor(cy - radius - 1));
        int y1 = Math.min(img.getHeight() - 1, (int) Math.ceil(cy + radius + 1));
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                float dx = x + 0.5f - cx;
                float dy = y + 0.5f - cy;
                float cover = Anim.clamp(radius + 0.5f - (float) Math.sqrt(dx * dx + dy * dy));
                if (cover > 0) img.setPixel(x, y, white(cover * alpha));
            }
        }
    }

    private static int white(float alpha) {
        return Math.round(Anim.clamp(alpha) * 255f) << 24 | 0xFFFFFF;
    }
}
