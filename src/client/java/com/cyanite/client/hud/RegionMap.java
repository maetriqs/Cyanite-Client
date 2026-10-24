package com.cyanite.client.hud;

import com.cyanite.client.gui.Theme;
import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.modules.misc.StreamerMode;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;

import java.util.Locale;

/**
 * Donut SMP's region map: the world cut into a 9x9 grid of numbered regions, each coloured by
 * the server area it's hosted in, with an arrow where you are and which way you face, your
 * position, the region you're in, and a key to the colours.
 *
 * <p>Its colours are Cyanite's, not Donut's: drawn from your accent colour, each continent a hue
 * of its own (North America your accent's, Europe and Asia/Oceania a third of the way round the
 * colour wheel either side), its two areas a light and a dark shade of it. Change the accent in
 * the GUI tab and the map follows.
 *
 * <p>Each square is one region, centred on 0, 0 for the middle one (region 5). The default size,
 * 50,000 blocks a side (25,000 each way from a square's middle), lines the squares up with
 * Donut's own map; it's a setting in case the regions change.
 */
public class RegionMap extends HudElement {
    private static final int GAP = 1;
    private static final int PAD = 4;

    /** A server area: a hue (degrees round from your accent's) and whether it's the dark shade of it. */
    enum Area {
        EU_CENTRAL("EU Central", 120, false),
        EU_WEST("EU West", 120, true),
        NA_EAST("NA East", 0, false),
        NA_WEST("NA West", 0, true),
        ASIA("Asia", 240, false),
        OCEANIA("Oceania", 240, true);

        final String label;
        final int turn;
        final boolean dark;

        Area(String label, int turn, boolean dark) {
            this.label = label;
            this.turn = turn;
            this.dark = dark;
        }

        /** Its colour from the accent: soft enough to sit on the dark panel, bright enough for white numbers. */
        int color() {
            int accent = Theme.accent();
            float[] hsb = java.awt.Color.RGBtoHSB((accent >> 16) & 0xFF, (accent >> 8) & 0xFF, accent & 0xFF, null);
            float hue = hsb[0] + turn / 360f;
            return 0xFF000000 | java.awt.Color.HSBtoRGB(hue - (float) Math.floor(hue), 0.52f, dark ? 0.46f : 0.70f);
        }
    }

    private static final Area C = Area.EU_CENTRAL;
    private static final Area W = Area.EU_WEST;
    private static final Area E = Area.NA_EAST;
    private static final Area N = Area.NA_WEST;
    private static final Area A = Area.ASIA;
    private static final Area O = Area.OCEANIA;

    /** Region numbers, north at the top, west on the left. */
    private static final int[][] NUMBERS = {
        {82, 100, 101, 102, 103, 104, 105, 106, 91},
        {83, 44, 75, 42, 41, 40, 39, 38, 92},
        {84, 45, 14, 13, 12, 11, 10, 37, 93},
        {85, 46, 74, 3, 2, 1, 25, 36, 94},
        {86, 47, 72, 71, 5, 4, 24, 35, 95},
        {87, 51, 17, 9, 8, 7, 23, 34, 96},
        {88, 54, 18, 61, 62, 21, 22, 33, 97},
        {89, 26, 27, 28, 29, 30, 59, 32, 98},
        {90, 107, 108, 109, 110, 111, 112, 113, 99},
    };

    private static final Area[][] AREAS = {
        {O, N, N, N, E, E, E, E, E},
        {O, N, N, N, E, E, E, E, E},
        {O, N, N, N, E, E, E, E, E},
        {O, O, O, N, E, E, E, E, E},
        {A, A, A, A, E, E, E, E, E},
        {A, W, W, C, C, C, C, C, E},
        {A, W, W, C, C, C, C, C, C},
        {C, W, C, C, C, C, C, C, C},
        {C, W, W, W, W, W, W, W, C},
    };

    private final SliderSetting regionSize = add(new SliderSetting("Region size", 50_000, 5_000, 200_000, 5_000)
        .format(v -> String.format(Locale.ROOT, "%,d blocks", (int) (double) v)));
    private final BoolSetting position = add(new BoolSetting("Position", true));
    private final BoolSetting legend = add(new BoolSetting("Legend", true));
    private final BoolSetting background = add(new BoolSetting("Background", true));
    private final BoolSetting donutOnly = add(new BoolSetting("Only on Donut SMP", true));

    private int cell;
    private boolean shown;

    public RegionMap() {
        super("Region Map", true, Horizontal.LEFT, Vertical.TOP, 4, 24);
    }

    /** Where (x, z) falls, as {row, column}; outside the grid when either is off 0..8. */
    private int[] regionAt(double x, double z) {
        double size = regionSize.get();
        int col = 4 + (int) Math.floor((x + size / 2) / size);
        int row = 4 + (int) Math.floor((z + size / 2) / size);
        return new int[] {row, col};
    }

    private static boolean inside(int[] rc) {
        return rc[0] >= 0 && rc[0] < 9 && rc[1] >= 0 && rc[1] < 9;
    }

    @Override
    public void update(float dt, boolean editing) {
        Minecraft mc = Minecraft.getInstance();
        boolean donut = mc.getCurrentServer() != null && mc.getCurrentServer().ip.toLowerCase(Locale.ROOT).contains("donutsmp");
        shown = editing || !donutOnly.get() || donut;
        cell = Math.max(15, Text.width("113") + 4);
        int grid = 9 * cell + 8 * GAP;
        int w = grid;
        int h = grid;
        int line = Text.CAP + 3;
        if (position.get()) {
            w = Math.max(w, Text.width(positionLine(mc.player)));
            w = Math.max(w, Text.width(regionLine(mc.player)));
            h += 4 + line * 2;
        }
        if (legend.get()) {
            for (Area a : Area.values()) w = Math.max(w, Text.CAP + 4 + Text.width(a.label));
            h += 4 + line * Area.values().length;
        }
        int pad = background.get() ? PAD : 0;
        width = shown ? w + pad * 2 : 0;
        height = shown ? h + pad * 2 : 0;
    }

    private String positionLine(LocalPlayer player) {
        if (player == null) return "Position: X: 0, Z: 0";
        if (StreamerMode.hiding()) return "Position: hidden";
        return "Position: X: " + player.getBlockX() + ", Z: " + player.getBlockZ();
    }

    private String regionLine(LocalPlayer player) {
        if (player == null) return "Current Region: -";
        int[] rc = regionAt(player.getX(), player.getZ());
        if (!inside(rc)) return "Current Region: outside the map";
        return "Current Region: " + NUMBERS[rc[0]][rc[1]] + " (" + AREAS[rc[0]][rc[1]].label + ")";
    }

    @Override
    public void draw(GuiGraphics g, float alpha, boolean editing) {
        if (!shown) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        int s = Render2D.scale();
        int pad = background.get() ? PAD : 0;
        boolean shadow = Hud.SHADOW.get() && !background.get();

        int accent = Theme.accent();
        Render2D.pixels(g);
        if (background.get()) {
            // The same chip as the watermark: shadow, accent glow, border, and the accent line along the bottom.
            int r = Math.round(Theme.radius() * s);
            Render2D.glow(g, x * s, y * s + s, width * s, height * s, r, 7 * s, Theme.fade(Theme.SHADOW, alpha * 0.8f));
            Render2D.glow(g, x * s, y * s, width * s, height * s, r, 7 * s, Theme.alpha(accent, 35 * Theme.glow() * alpha));
            Render2D.rect(g, x * s, y * s, width * s, height * s, r, Theme.fade(Theme.BORDER, alpha));
            Render2D.rect(g, x * s + 1, y * s + 1, width * s - 2, height * s - 2, Math.max(0, r - 1), Theme.fade(0xE8101217, alpha));
            int line = Math.max(1, s / 2);
            Render2D.fadeRight(g, x * s + 1, (y + height) * s - line - 1, width * s - 2, line, Theme.alpha(accent, 200 * alpha));
        }
        int gx = x + pad;
        int gy = y + pad;
        int pitch = cell + GAP;
        int[] here = player == null ? new int[] {-1, -1} : regionAt(player.getX(), player.getZ());
        int corner = Math.max(1, Math.round(Math.min(Theme.radius(), 3f) * 0.6f * s));
        for (int row = 0; row < 9; row++) {
            for (int col = 0; col < 9; col++) {
                int cx = gx + col * pitch;
                int cy = gy + row * pitch;
                int color = AREAS[row][col].color();
                if (row == here[0] && col == here[1]) {
                    // The square you're in: an accent glow and a light rim round it.
                    int rim = Math.max(1, s);
                    Render2D.glow(g, cx * s, cy * s, cell * s, cell * s, corner, 6 * s, Theme.alpha(accent, 190 * alpha));
                    Render2D.rect(g, cx * s - rim, cy * s - rim, cell * s + 2 * rim, cell * s + 2 * rim, corner + rim, Theme.fade(Theme.TEXT, alpha));
                }
                Render2D.rect(g, cx * s, cy * s, cell * s, cell * s, corner, Theme.fade(color, alpha));
                // A faint lighter top edge, the way the client's buttons catch the light.
                Render2D.rect(g, cx * s + corner, cy * s, cell * s - 2 * corner, Math.max(1, s / 2), 0, Theme.alpha(0xFFFFFFFF, 40 * alpha));
            }
        }
        Render2D.end(g);

        for (int row = 0; row < 9; row++) {
            for (int col = 0; col < 9; col++) {
                String n = Integer.toString(NUMBERS[row][col]);
                int cx = gx + col * pitch + (cell - Text.width(n)) / 2;
                int cy = gy + row * pitch + (cell - Text.CAP) / 2;
                Text.draw(g, n, cx, cy, Theme.fade(Theme.TEXT, alpha), true);
            }
        }

        if (player != null && inside(here)) arrow(g, gx, gy, pitch, player, alpha);

        int ty = gy + 9 * pitch - GAP + 4;
        int line = Text.CAP + 3;
        if (position.get()) {
            Text.draw(g, positionLine(player), gx, ty, Theme.fade(Theme.TEXT, alpha), shadow);
            Text.draw(g, regionLine(player), gx, ty + line, Theme.fade(Theme.TEXT, alpha), shadow);
            ty += line * 2 + 4;
        }
        if (legend.get()) {
            for (Area a : Area.values()) {
                Render2D.pixels(g);
                int dot = (Text.CAP + 1) * s;
                Render2D.rect(g, gx * s, ty * s, dot, dot, dot / 2, Theme.fade(a.color(), alpha));
                Render2D.end(g);
                Text.draw(g, a.label, gx + Text.CAP + 6, ty, Theme.fade(Theme.TEXT_DIM, alpha), shadow);
                ty += line;
            }
        }
    }

    /** A white arrow with an accent glow where you are on the grid, pointing the way you face (north is up). */
    private void arrow(GuiGraphics g, int gx, int gy, int pitch, LocalPlayer player, float alpha) {
        double size = regionSize.get();
        double fx = 4 + (player.getX() + size / 2) / size;
        double fz = 4 + (player.getZ() + size / 2) / size;
        int s = Render2D.scale();
        double px = (gx + fx * pitch) * s;
        double pz = (gy + fz * pitch) * s;
        // Facing in the world (x east, z south) is the same as on the map (right, down).
        double yaw = Math.toRadians(player.getYRot());
        double dx = -Math.sin(yaw);
        double dz = Math.cos(yaw);
        double len = Math.round(cell * 0.8f) * s;
        Render2D.pixels(g);
        int glow = (int) len;
        Render2D.glow(g, (int) Math.round(px) - glow / 2, (int) Math.round(pz) - glow / 2, glow, glow, glow / 2, 5 * s,
            Theme.alpha(Theme.accent(), 200 * alpha));
        dart(g, px, pz, dx, dz, len / 2 + Math.max(1, s / 2) + 1, Theme.fade(0xFF0B0D11, alpha));
        dart(g, px, pz, dx, dz, len / 2, Theme.fade(Theme.TEXT, alpha));
        Render2D.end(g);
    }

    /**
     * A dart centred on (cx, cy) pointing along (dx, dy), {@code r} from its middle to its tip:
     * long and narrow with a notch in the back, so which end is the point is never in doubt.
     * Filled a pixel row at a time straight on the screen, so it points true at any angle.
     */
    private static void dart(GuiGraphics g, double cx, double cy, double dx, double dy, double r, int color) {
        // (-dy, dx) is a quarter turn round from the way it points.
        double[] tip = {cx + dx * r, cy + dy * r};
        double[] left = {cx - dx * r * 0.85 - dy * r * 0.62, cy - dy * r * 0.85 + dx * r * 0.62};
        double[] right = {cx - dx * r * 0.85 + dy * r * 0.62, cy - dy * r * 0.85 - dx * r * 0.62};
        double[] notch = {cx - dx * r * 0.35, cy - dy * r * 0.35};
        fill(g, new double[][] {tip, left, notch, right}, color);
    }

    /** A solid shape (corners in order, any shape), a pixel row at a time: inside is between each pair of edge crossings. */
    static void fill(GuiGraphics g, double[][] p, int color) {
        double top = Double.MAX_VALUE;
        double bottom = -Double.MAX_VALUE;
        for (double[] v : p) {
            top = Math.min(top, v[1]);
            bottom = Math.max(bottom, v[1]);
        }
        double[] xs = new double[p.length];
        for (int y = (int) Math.floor(top); y < (int) Math.ceil(bottom); y++) {
            double row = y + 0.5;
            int n = 0;
            for (int i = 0; i < p.length; i++) {
                double[] u = p[i];
                double[] v = p[(i + 1) % p.length];
                if ((row < u[1]) == (row < v[1])) continue;
                xs[n++] = u[0] + (row - u[1]) / (v[1] - u[1]) * (v[0] - u[0]);
            }
            java.util.Arrays.sort(xs, 0, n);
            for (int k = 0; k + 1 < n; k += 2) {
                int x0 = (int) Math.round(xs[k]);
                int x1 = Math.max(x0 + 1, (int) Math.round(xs[k + 1]));
                g.fill(x0, y, x1, y + 1, color);
            }
        }
    }
}
