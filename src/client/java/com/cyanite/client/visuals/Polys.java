package com.cyanite.client.visuals;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Matrix3x2f;

import java.util.Arrays;

/**
 * Free-form shapes for the Visuals previews: quads with any four corners, lines at any angle, rings and discs,
 * each corner its own colour. However many there are, they're one piece for the game's GUI, like {@link Shapes}.
 */
final class Polys implements GuiElementRenderState {
    private final Matrix3x2f pose;
    private final ScreenRectangle scissor;
    private float[] xy = new float[8 * 16];
    private int[] colors = new int[4 * 16];
    private int n;
    private int color;
    private float minX = Float.MAX_VALUE;
    private float minY = Float.MAX_VALUE;
    private float maxX = -Float.MAX_VALUE;
    private float maxY = -Float.MAX_VALUE;
    private ScreenRectangle bounds;

    private Polys(GuiGraphics g, int argb) {
        this.pose = new Matrix3x2f(g.pose());
        this.scissor = g.scissorStack.peek();
        this.color = argb;
    }

    static Polys of(GuiGraphics g, int argb) {
        return new Polys(g, argb);
    }

    /** The colour of what's added next. */
    Polys color(int argb) {
        color = argb;
        return this;
    }

    Polys quad(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3) {
        return quad(x0, y0, color, x1, y1, color, x2, y2, color, x3, y3, color);
    }

    Polys quad(float x0, float y0, int c0, float x1, float y1, int c1, float x2, float y2, int c2, float x3, float y3, int c3) {
        if ((c0 | c1 | c2 | c3) >>> 24 == 0) return this;
        if ((n + 1) * 8 > xy.length) {
            xy = Arrays.copyOf(xy, xy.length * 2);
            colors = Arrays.copyOf(colors, colors.length * 2);
        }
        // The GUI culls back faces: keep every quad wound the way the game's own rectangles are.
        float area = (x0 * y1 - x1 * y0) + (x1 * y2 - x2 * y1) + (x2 * y3 - x3 * y2) + (x3 * y0 - x0 * y3);
        int i = n * 8;
        int c = n * 4;
        if (area <= 0) {
            put(i, c, x0, y0, c0, x1, y1, c1, x2, y2, c2, x3, y3, c3);
        } else {
            put(i, c, x0, y0, c0, x3, y3, c3, x2, y2, c2, x1, y1, c1);
        }
        n++;
        minX = Math.min(minX, Math.min(Math.min(x0, x1), Math.min(x2, x3)));
        minY = Math.min(minY, Math.min(Math.min(y0, y1), Math.min(y2, y3)));
        maxX = Math.max(maxX, Math.max(Math.max(x0, x1), Math.max(x2, x3)));
        maxY = Math.max(maxY, Math.max(Math.max(y0, y1), Math.max(y2, y3)));
        return this;
    }

    private void put(int i, int c, float x0, float y0, int c0, float x1, float y1, int c1, float x2, float y2, int c2, float x3, float y3, int c3) {
        xy[i] = x0;
        xy[i + 1] = y0;
        xy[i + 2] = x1;
        xy[i + 3] = y1;
        xy[i + 4] = x2;
        xy[i + 5] = y2;
        xy[i + 6] = x3;
        xy[i + 7] = y3;
        colors[c] = c0;
        colors[c + 1] = c1;
        colors[c + 2] = c2;
        colors[c + 3] = c3;
    }

    Polys tri(float ax, float ay, float bx, float by, float cx, float cy) {
        return quad(ax, ay, bx, by, cx, cy, cx, cy);
    }

    Polys rect(float x0, float y0, float x1, float y1) {
        return quad(x0, y0, x0, y1, x1, y1, x1, y0);
    }

    /** A straight line {@code width} thick from a to b. */
    Polys line(float ax, float ay, float bx, float by, float width) {
        return line(ax, ay, bx, by, width, color, color);
    }

    /** A line fading from {@code ca} at a to {@code cb} at b. */
    Polys line(float ax, float ay, float bx, float by, float width, int ca, int cb) {
        float dx = bx - ax;
        float dy = by - ay;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-4f) return this;
        float nx = -dy / len * width / 2;
        float ny = dx / len * width / 2;
        return quad(ax + nx, ay + ny, ca, ax - nx, ay - ny, ca, bx - nx, by - ny, cb, bx + nx, by + ny, cb);
    }

    /** The outline of an ellipse round (cx, cy). */
    Polys ring(float cx, float cy, float rx, float ry, float width, int segments) {
        float half = width / 2;
        for (int i = 0; i < segments; i++) {
            double a0 = Math.PI * 2 * i / segments;
            double a1 = Math.PI * 2 * (i + 1) / segments;
            float c0 = (float) Math.cos(a0);
            float s0 = (float) Math.sin(a0);
            float c1 = (float) Math.cos(a1);
            float s1 = (float) Math.sin(a1);
            quad(cx + c0 * (rx - half), cy + s0 * (ry - half), cx + c0 * (rx + half), cy + s0 * (ry + half),
                cx + c1 * (rx + half), cy + s1 * (ry + half), cx + c1 * (rx - half), cy + s1 * (ry - half));
        }
        return this;
    }

    /** A filled ellipse, {@code inner} in the middle fading to {@code outer} at the edge (the same for a flat one). */
    Polys disc(float cx, float cy, float rx, float ry, int segments, int inner, int outer) {
        for (int i = 0; i < segments; i++) {
            double a0 = Math.PI * 2 * i / segments;
            double a1 = Math.PI * 2 * (i + 1) / segments;
            float x0 = cx + (float) Math.cos(a0) * rx;
            float y0 = cy + (float) Math.sin(a0) * ry;
            float x1 = cx + (float) Math.cos(a1) * rx;
            float y1 = cy + (float) Math.sin(a1) * ry;
            quad(cx, cy, inner, x0, y0, outer, x1, y1, outer, x1, y1, outer);
        }
        return this;
    }

    Polys disc(float cx, float cy, float rx, float ry, int segments) {
        return disc(cx, cy, rx, ry, segments, color, color);
    }

    // Glow, the way the world draws it: from the middle out, where each band ends and how strong it is there.
    private static final float[] GLOW_AT = {0, 0.2f, 0.52f, 1};
    private static final float[] GLOW_FALL = {1, 0.48f, 0.15f, 0};
    private static final int GLOW_SEGMENTS = 20;

    /** Soft light round (cx, cy): whiter in the middle, gone {@code r} out. */
    Polys glow(float cx, float cy, float r, int argb) {
        if ((argb >>> 24) == 0 || r <= 0) return this;
        int middle = whiter(argb);
        for (int k = 0; k < GLOW_AT.length - 1; k++) {
            float r0 = GLOW_AT[k] * r, r1 = GLOW_AT[k + 1] * r;
            int c0 = k == 0 ? middle : faint(argb, GLOW_FALL[k]), c1 = faint(argb, GLOW_FALL[k + 1]);
            for (int i = 0; i < GLOW_SEGMENTS; i++) {
                double a0 = Math.PI * 2 * i / GLOW_SEGMENTS, a1 = Math.PI * 2 * (i + 1) / GLOW_SEGMENTS;
                float x0 = (float) Math.cos(a0), y0 = (float) Math.sin(a0), x1 = (float) Math.cos(a1), y1 = (float) Math.sin(a1);
                quad(cx + x0 * r0, cy + y0 * r0, c0, cx + x0 * r1, cy + y0 * r1, c1, cx + x1 * r1, cy + y1 * r1, c1, cx + x1 * r0, cy + y1 * r0, c0);
            }
        }
        return this;
    }

    /** Soft light along a line, fading out {@code reach} to each side, {@code ca} at a shading to {@code cb} at b. */
    Polys glowLine(float ax, float ay, float bx, float by, float reach, int ca, int cb) {
        float dx = bx - ax;
        float dy = by - ay;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-4f || reach <= 0) return this;
        float nx = -dy / len, ny = dx / len;
        for (int side = -1; side <= 1; side += 2) {
            for (int k = 0; k < GLOW_AT.length - 1; k++) {
                float o0 = GLOW_AT[k] * reach * side, o1 = GLOW_AT[k + 1] * reach * side;
                quad(ax + nx * o0, ay + ny * o0, faint(ca, GLOW_FALL[k]), bx + nx * o0, by + ny * o0, faint(cb, GLOW_FALL[k]),
                    bx + nx * o1, by + ny * o1, faint(cb, GLOW_FALL[k + 1]), ax + nx * o1, ay + ny * o1, faint(ca, GLOW_FALL[k + 1]));
            }
        }
        return this;
    }

    /**
     * Soft light along a path through the first {@code n} points (back round to the first if {@code closed}), each
     * point its own reach and colour. The bands of one piece meet the next one's, so curves glow without seams.
     */
    Polys glowPath(float[] xs, float[] ys, float[] reach, int[] colors, int n, boolean closed) {
        if (n < 2) return this;
        float[] nx = new float[n];
        float[] ny = new float[n];
        across(xs, ys, n, closed, nx, ny);
        for (int i = 0, pieces = closed ? n : n - 1; i < pieces; i++) {
            int j = (i + 1) % n;
            for (int side = -1; side <= 1; side += 2) {
                for (int k = 0; k < GLOW_AT.length - 1; k++) {
                    float i0 = GLOW_AT[k] * reach[i] * side, i1 = GLOW_AT[k + 1] * reach[i] * side;
                    float j0 = GLOW_AT[k] * reach[j] * side, j1 = GLOW_AT[k + 1] * reach[j] * side;
                    quad(xs[i] + nx[i] * i0, ys[i] + ny[i] * i0, faint(colors[i], GLOW_FALL[k]),
                        xs[j] + nx[j] * j0, ys[j] + ny[j] * j0, faint(colors[j], GLOW_FALL[k]),
                        xs[j] + nx[j] * j1, ys[j] + ny[j] * j1, faint(colors[j], GLOW_FALL[k + 1]),
                        xs[i] + nx[i] * i1, ys[i] + ny[i] * i1, faint(colors[i], GLOW_FALL[k + 1]));
                }
            }
        }
        return this;
    }

    /** A line along a path, as {@link #glowPath}: each point its own width and colour, joined at the bends. */
    Polys linePath(float[] xs, float[] ys, float[] width, int[] colors, int n, boolean closed) {
        if (n < 2) return this;
        float[] nx = new float[n];
        float[] ny = new float[n];
        across(xs, ys, n, closed, nx, ny);
        for (int i = 0, pieces = closed ? n : n - 1; i < pieces; i++) {
            int j = (i + 1) % n;
            float hi = width[i] / 2, hj = width[j] / 2;
            quad(xs[i] + nx[i] * hi, ys[i] + ny[i] * hi, colors[i], xs[j] + nx[j] * hj, ys[j] + ny[j] * hj, colors[j],
                xs[j] - nx[j] * hj, ys[j] - ny[j] * hj, colors[j], xs[i] - nx[i] * hi, ys[i] - ny[i] * hi, colors[i]);
        }
        return this;
    }

    /** Each point's way across the path: halfway between its two pieces', longer at a bend so bands keep their width. */
    private static void across(float[] xs, float[] ys, int n, boolean closed, float[] nx, float[] ny) {
        for (int i = 0; i < n; i++) {
            int a = i > 0 ? i - 1 : closed ? n - 1 : i;
            int b = i < n - 1 ? i + 1 : closed ? 0 : i;
            float dx0 = xs[i] - xs[a], dy0 = ys[i] - ys[a];
            float dx1 = xs[b] - xs[i], dy1 = ys[b] - ys[i];
            float l0 = (float) Math.sqrt(dx0 * dx0 + dy0 * dy0), l1 = (float) Math.sqrt(dx1 * dx1 + dy1 * dy1);
            if (l0 < 1e-4f) {
                dx0 = dx1;
                dy0 = dy1;
                l0 = l1;
            }
            if (l1 < 1e-4f) {
                dx1 = dx0;
                dy1 = dy0;
                l1 = l0;
            }
            if (l0 < 1e-4f) continue;
            float bx = -dy1 / l1, by = dx1 / l1;
            float mx = -dy0 / l0 + bx, my = dx0 / l0 + by;
            float ml = (float) Math.sqrt(mx * mx + my * my);
            if (ml < 1e-4f) {
                nx[i] = bx;
                ny[i] = by;
                continue;
            }
            // Not a spike at a sharp turn: at most twice as long.
            float scale = 1 / (ml * Math.max(0.5f, (mx * bx + my * by) / ml));
            nx[i] = mx * scale;
            ny[i] = my * scale;
        }
    }

    private static int faint(int argb, float k) {
        return Math.min(255, Math.round((argb >>> 24) * k)) << 24 | argb & 0xFFFFFF;
    }

    /** Part way to white, alpha kept. */
    static int whiter(int argb) {
        int r = argb >> 16 & 0xFF, g = argb >> 8 & 0xFF, b = argb & 0xFF;
        return argb & 0xFF000000 | (r + (255 - r) * 11 / 20) << 16 | (g + (255 - g) * 11 / 20) << 8 | (b + (255 - b) * 11 / 20);
    }

    /** Hands it to the game; nothing happens if it's empty or wholly clipped away. */
    void submit(GuiGraphics g) {
        if (n == 0) return;
        int x = (int) Math.floor(minX);
        int y = (int) Math.floor(minY);
        ScreenRectangle area = new ScreenRectangle(x, y, Math.max(1, (int) Math.ceil(maxX) - x), Math.max(1, (int) Math.ceil(maxY) - y))
            .transformMaxBounds(pose);
        bounds = scissor != null ? scissor.intersection(area) : area;
        if (bounds == null) return;
        g.guiRenderState.submitGuiElement(this);
    }

    @Override
    public void buildVertices(VertexConsumer out) {
        for (int k = 0; k < n; k++) {
            int i = k * 8;
            int c = k * 4;
            out.addVertexWith2DPose(pose, xy[i], xy[i + 1]).setColor(colors[c]);
            out.addVertexWith2DPose(pose, xy[i + 2], xy[i + 3]).setColor(colors[c + 1]);
            out.addVertexWith2DPose(pose, xy[i + 4], xy[i + 5]).setColor(colors[c + 2]);
            out.addVertexWith2DPose(pose, xy[i + 6], xy[i + 7]).setColor(colors[c + 3]);
        }
    }

    @Override
    public RenderPipeline pipeline() {
        return RenderPipelines.GUI;
    }

    @Override
    public TextureSetup textureSetup() {
        return TextureSetup.noTexture();
    }

    @Override
    public ScreenRectangle scissorArea() {
        return scissor;
    }

    @Override
    public ScreenRectangle bounds() {
        return bounds;
    }
}
