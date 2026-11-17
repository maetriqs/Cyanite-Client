package com.cyanite.client.render;

import com.cyanite.client.mixin.GameRendererAccessor;
import com.cyanite.client.mixin.RenderPipelinesAccessor;
import com.cyanite.client.mixin.RenderTypeAccessor;
import com.cyanite.client.system.Modules;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.List;

/**
 * World-space drawing for ESP-style features: boxes, lines and text that show through walls.
 * Modules queue shapes from {@code onRender3D} each frame and everything is drawn in one
 * go at the end of the main world pass. Positions are world coordinates.
 */
public final class Render3D {
    /**
     * Glow is light added over the scene round a glowing edge, so overlapping glows brighten each other the
     * way light does. It reaches this many line widths out to each side, and never less than GLOW_MIN pixels.
     */
    private static final float GLOW_REACH = 4.5f;
    private static final float GLOW_MIN = 7f;
    /** How strong it is next to the edge, for the edge's own alpha. */
    private static final float GLOW_ALPHA = 0.55f;
    /** Across a glow from the middle out: where each band ends (share of the reach) and how strong the light is there. */
    private static final float[] GLOW_AT = {0, 0.2f, 0.52f, 1};
    private static final float[] GLOW_FALL = {1, 0.48f, 0.15f, 0};
    /** A glowing line also gets a thin, whiter core on top, like a neon tube. */
    private static final float CORE_WIDTH = 0.45f;
    private static final float CORE_WHITE = 0.55f;
    private static final int BALL_SEGMENTS = 18;
    private static final float[] BALL_COS = new float[BALL_SEGMENTS + 1];
    private static final float[] BALL_SIN = new float[BALL_SEGMENTS + 1];

    static {
        for (int i = 0; i <= BALL_SEGMENTS; i++) {
            double a = Math.PI * 2 * i / BALL_SEGMENTS;
            BALL_COS[i] = (float) Math.cos(a);
            BALL_SIN[i] = (float) Math.sin(a);
        }
    }

    /** How far along each edge a corner bracket reaches. */
    private static final float CORNER = 0.28f;
    /** ...but never shorter on screen than this many line widths (up to half the edge), so a far box keeps its brackets. */
    private static final float CORNER_MIN_WIDTHS = 3f;
    /** Closer than this to the camera's plane (in blocks), a point has no sensible place on screen. */
    private static final double NEAR = 0.05;
    /** Tracers are drawn in this many pieces so they can fade in evenly on screen. */
    private static final int TRACER_STEPS = 8;

    private static RenderType lines;
    private static RenderType quads;
    private static RenderType light;
    private static RenderType lightDepth;
    private static MultiBufferSource.BufferSource buffers;

    private final Vec3 camera;
    private final Vector3fc forward;
    private final Vector3fc left;
    private final Vector3fc up;
    private final float partialTick;
    private final List<Box> boxes = new ArrayList<>();
    private final List<Line> segments = new ArrayList<>();
    private final List<Label> labels = new ArrayList<>();
    private final List<Streak> streaks = new ArrayList<>();
    private final List<Ball> balls = new ArrayList<>();
    /** Framebuffer pixels per 1080p pixel, and per block face-on one block ahead; set when the frame is drawn. */
    private float pixelScale;
    private double focal;

    private record Box(AABB box, int fill, int fillTop, int outline, float width, boolean glow, boolean corners) {
    }

    private record Line(Vec3 from, Vec3 to, int color, int colorTo, float width, boolean glow) {
    }

    private record Label(Vec3 at, Quaternionf facing, float size, float x, float y, String text, int color, int background, boolean seeThrough) {
    }

    private record Streak(Vec3 from, Vec3 to, int color, int colorTo, double radius, boolean seeThrough) {
    }

    private record Ball(Vec3 at, double radius, int color, boolean seeThrough) {
    }

    /** Takes the pieces a box's edges are drawn in, ends in world coordinates. */
    private interface EdgeSink {
        void edge(double ax, double ay, double az, double bx, double by, double bz);
    }

    private Render3D(Camera cam, float partialTick) {
        this.camera = cam.position();
        this.forward = cam.forwardVector();
        this.left = cam.leftVector();
        this.up = cam.upVector();
        this.partialTick = partialTick;
    }

    public float partialTick() {
        return partialTick;
    }

    public Vec3 camera() {
        return camera;
    }

    /**
     * @param fill    ARGB for the faces; alpha 0 skips them
     * @param outline ARGB for the edges; alpha 0 skips them
     * @param width   edge width in pixels at 1080p
     * @param glow    soft light round the edges, and a whiter core along them
     */
    public void box(AABB box, int fill, int outline, float width, boolean glow) {
        boxes.add(new Box(box, fill, fill, outline, width, glow, false));
    }

    /** A box whose faces shade from {@code fill} at the bottom to {@code fillTop} at the top. */
    public void box(AABB box, int fill, int fillTop, int outline, float width, boolean glow) {
        boxes.add(new Box(box, fill, fillTop, outline, width, glow, false));
    }

    /** Like {@link #box(AABB, int, int, int, float, boolean)}, but only the corners of the edges are drawn, as brackets. */
    public void corners(AABB box, int fill, int fillTop, int outline, float width, boolean glow) {
        boxes.add(new Box(box, fill, fillTop, outline, width, glow, true));
    }

    public void line(Vec3 from, Vec3 to, int color, float width, boolean glow) {
        segments.add(new Line(from, to, color, color, width, glow));
    }

    /** A line shading from {@code color} at {@code from} to {@code colorTo} at {@code to}. */
    public void line(Vec3 from, Vec3 to, int color, int colorTo, float width, boolean glow) {
        segments.add(new Line(from, to, color, colorTo, width, glow));
    }

    /**
     * Text standing in the world, in Minecraft's font with a drop shadow.
     *
     * @param at         where font pixel (0, 0) is
     * @param facing     turns the text the way a camera with this rotation looks, so it reads facing back at it
     * @param size       blocks per font pixel (name tags are 0.025)
     * @param x          font pixels right of {@code at}
     * @param y          font pixels down from {@code at}, to the top of the text
     * @param background ARGB behind the text; alpha 0 for none
     * @param seeThrough drawn over everything, walls included; otherwise hidden behind blocks
     */
    public void text(Vec3 at, Quaternionf facing, float size, float x, float y, String text, int color, int background, boolean seeThrough) {
        labels.add(new Label(at, facing, size, x, y, text, color, background, seeThrough));
    }

    /**
     * A soft ball of light facing the camera, brightest in the middle and gone {@code radius} blocks out.
     * Its light is added to what's behind it, so glows that overlap brighten each other.
     *
     * @param seeThrough drawn over everything, walls included; otherwise hidden behind blocks
     */
    public void glow(Vec3 at, double radius, int argb, boolean seeThrough) {
        if ((argb >>> 24) != 0 && radius > 0) balls.add(new Ball(at, radius, argb, seeThrough));
    }

    /** A soft streak of light from {@code from} to {@code to}, fading out {@code radius} blocks to each side. */
    public void glowLine(Vec3 from, Vec3 to, int color, int colorTo, double radius, boolean seeThrough) {
        if (((color | colorTo) >>> 24) != 0 && radius > 0) streaks.add(new Streak(from, to, color, colorTo, radius, seeThrough));
    }

    /** A line from the middle of the screen out to {@code to}, fading in from the crosshair. */
    public void tracer(Vec3 to, int color, float width, boolean glow) {
        tracer(to, color, width, glow, true);
    }

    /**
     * A line from the middle of the screen out to {@code to}.
     *
     * @param fade starts faint at the crosshair and reaches full strength part way out, so a lot of
     *             tracers don't knot into a blob in the middle of the screen
     */
    public void tracer(Vec3 to, int color, float width, boolean glow, boolean fade) {
        // Start a little in front of the eye; a line from exactly the eye has no length on screen.
        Vec3 start = camera.add(forward.x(), forward.y(), forward.z());
        if (!fade) {
            line(start, to, color, width, glow);
            return;
        }
        // Nearly all of a tracer's length on screen is its first few blocks, so pieces of equal
        // length in the world would fade in a blink. Cut it where it's evenly spaced on screen
        // instead: a point s of the way out on screen is t = s / (s + d (1 - s)) of the way along,
        // d being how far ahead of you the target is.
        Vec3 off = to.subtract(camera);
        double ahead = off.x * forward.x() + off.y * forward.y() + off.z * forward.z();
        int alpha = color >>> 24;
        Vec3 from = start;
        int fromColor = fadeAt(color, alpha, 0);
        for (int i = 1; i <= TRACER_STEPS; i++) {
            double s = i / (double) TRACER_STEPS;
            double t = ahead > 1.05 ? s / (s + ahead * (1 - s)) : s;
            Vec3 at = start.lerp(to, t);
            int atColor = fadeAt(color, alpha, s);
            segments.add(new Line(from, at, fromColor, atColor, width, glow));
            from = at;
            fromColor = atColor;
        }
    }

    /** A tracer's colour {@code s} of the way out on screen: faint at the crosshair, full by 60%. */
    private static int fadeAt(int color, int alpha, double s) {
        double k = Math.min(1, 0.06 + s / 0.6);
        return (int) Math.round(alpha * k * k) << 24 | (color & 0xFFFFFF);
    }

    /** Called from Fabric's END_MAIN world render event. */
    public static void render(WorldRenderContext context) {
        // A CameraShot's picture: no shapes, and modules mustn't be asked twice in a frame.
        if (CameraShot.taking()) return;
        Minecraft mc = Minecraft.getInstance();
        EspGlow.begin();
        if (mc.level == null || mc.player == null || com.cyanite.client.system.Stealth.active()) {
            EspGlow.end();
            return;
        }

        Camera cam = mc.gameRenderer.getMainCamera();
        Render3D frame = new Render3D(cam, mc.getDeltaTracker().getGameTimeDeltaPartialTick(true));
        try {
            Modules.render3D(frame);
            com.cyanite.client.visuals.Visuals.render3D(frame);
        } finally {
            EspGlow.end();
        }
        com.cyanite.client.modules.world.build.StandIns.render(frame);
        if (frame.boxes.isEmpty() && frame.segments.isEmpty() && frame.labels.isEmpty() && frame.streaks.isEmpty() && frame.balls.isEmpty()) return;

        double focal = Projection.pixelsPerBlock();
        if (focal <= 0) focal = mc.getWindow().getHeight() / (2 * Math.tan(Math.toRadians(mc.options.fov().get()) / 2));
        frame.flush(context.matrices().last(), mc.getWindow().getHeight() / 1080f, focal);
    }

    private void flush(PoseStack.Pose pose, float pixelScale, double focal) {
        this.pixelScale = pixelScale;
        this.focal = focal;
        setup();

        // ESP is for spotting things far away, which is exactly where world fog would wash the lines out.
        GpuBufferSlice worldFog = RenderSystem.getShaderFog();
        FogRenderer fog = ((GameRendererAccessor) Minecraft.getInstance().gameRenderer).cyanite$fogRenderer();
        RenderSystem.setShaderFog(fog.getBuffer(FogRenderer.FogMode.NONE));
        try {
            draw(pose, pixelScale);
        } finally {
            RenderSystem.setShaderFog(worldFog);
        }
    }

    private void draw(PoseStack.Pose pose, float pixelScale) {

        VertexConsumer faces = buffers.getBuffer(quads);
        for (Box b : boxes) {
            if ((b.fill >>> 24) != 0 || (b.fillTop >>> 24) != 0) faces(faces, pose, b.box, b.fill, b.fillTop);
        }
        buffers.endBatch(quads);

        // The light first, so the crisp edges sit on top of it.
        float strength = com.cyanite.client.visuals.Glow.strength();
        if (strength > 0) {
            light(buffers.getBuffer(light), pose, true, strength);
            buffers.endBatch(light);
            light(buffers.getBuffer(lightDepth), pose, false, strength);
            buffers.endBatch(lightDepth);
        }

        VertexConsumer edges = buffers.getBuffer(lines);
        boolean cores = com.cyanite.client.visuals.Glow.cores();
        for (Box b : boxes) {
            if ((b.outline >>> 24) == 0) continue;
            float width = b.width * pixelScale;
            edges(b, (ax, ay, az, bx, by, bz) -> boxEdge(edges, pose, ax, ay, az, bx, by, bz, b.outline, width));
            if (b.glow && cores && width >= 2) {
                int core = whiter(b.outline);
                edges(b, (ax, ay, az, bx, by, bz) -> boxEdge(edges, pose, ax, ay, az, bx, by, bz, core, width * CORE_WIDTH));
            }
        }
        for (Line l : segments) {
            float width = l.width * pixelScale;
            segment(edges, pose, l.from, l.to, l.color, l.colorTo, width);
            if (l.glow && cores && width >= 2) segment(edges, pose, l.from, l.to, whiter(l.color), whiter(l.colorTo), width * CORE_WIDTH);
        }
        buffers.endBatch(lines);

        if (labels.isEmpty()) return;
        Font font = Minecraft.getInstance().font;
        Matrix4f m = new Matrix4f();
        for (Label l : labels) {
            m.set(pose.pose())
                .translate((float) (l.at.x - camera.x), (float) (l.at.y - camera.y), (float) (l.at.z - camera.z))
                .rotate(l.facing)
                .scale(l.size, -l.size, l.size);
            font.drawInBatch(l.text, l.x, l.y, l.color, true, m, buffers,
                l.seeThrough ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL, l.background, LightTexture.FULL_BRIGHT);
        }
        buffers.endBatch();
    }

    /**
     * Every glow of one kind: drawn over everything ({@code seeThrough}) or behind blocks. Glowing edges and
     * lines get theirs here too, through walls like the edges themselves.
     */
    private void light(VertexConsumer out, PoseStack.Pose pose, boolean seeThrough, float strength) {
        if (seeThrough) {
            float size = com.cyanite.client.visuals.Glow.size();
            for (Box b : boxes) {
                if (!b.glow || (b.outline >>> 24) == 0) continue;
                int c = faint(b.outline, GLOW_ALPHA * strength);
                double reach = reach(b.width) * size;
                edges(b, (ax, ay, az, bx, by, bz) -> streak(out, pose, ax, ay, az, bx, by, bz, c, c, reach, true));
            }
            for (Line l : segments) {
                if (!l.glow) continue;
                streak(out, pose, l.from.x, l.from.y, l.from.z, l.to.x, l.to.y, l.to.z,
                    faint(l.color, GLOW_ALPHA * strength), faint(l.colorTo, GLOW_ALPHA * strength), reach(l.width) * size, true);
            }
        }
        for (Streak s : streaks) {
            if (s.seeThrough != seeThrough) continue;
            streak(out, pose, s.from.x, s.from.y, s.from.z, s.to.x, s.to.y, s.to.z, faint(s.color, strength), faint(s.colorTo, strength), s.radius, false);
        }
        for (Ball b : balls) {
            if (b.seeThrough == seeThrough) ball(out, pose, b, faint(b.color, strength));
        }
    }

    /** How far a glow reaches to each side of an edge this wide, in framebuffer pixels. */
    private float reach(float width) {
        return Math.max(GLOW_MIN, width * GLOW_REACH) * pixelScale;
    }

    private static int faint(int argb, float alpha) {
        int a = Math.min(255, Math.round((argb >>> 24) * alpha));
        return a << 24 | (argb & 0xFFFFFF);
    }

    /** Part way to white, alpha kept: the core of a glowing line. */
    private static int whiter(int argb) {
        int r = argb >> 16 & 0xFF, g = argb >> 8 & 0xFF, b = argb & 0xFF;
        r += Math.round((255 - r) * CORE_WHITE);
        g += Math.round((255 - g) * CORE_WHITE);
        b += Math.round((255 - b) * CORE_WHITE);
        return argb & 0xFF000000 | r << 16 | g << 8 | b;
    }

    /**
     * Light along a line, as bands either side of it that fade out, turned square to the way you see it at
     * each end. {@code reach} is in framebuffer pixels when {@code pixels}, so it's as wide on screen near or far;
     * otherwise in blocks.
     */
    private void streak(VertexConsumer out, PoseStack.Pose pose, double ax, double ay, double az, double bx, double by, double bz,
                        int color, int colorTo, double reach, boolean pixels) {
        double da = ahead(ax, ay, az), db = ahead(bx, by, bz);
        if (da < NEAR && db < NEAR) return;
        // Behind the camera there's no sensible place on screen: cut that end back to just in front of it.
        if (da < NEAR || db < NEAR) {
            double t = (NEAR - da) / (db - da);
            double cx = ax + (bx - ax) * t, cy = ay + (by - ay) * t, cz = az + (bz - az) * t;
            if (da < NEAR) {
                ax = cx;
                ay = cy;
                az = cz;
                da = NEAR;
            } else {
                bx = cx;
                by = cy;
                bz = cz;
                db = NEAR;
            }
        }
        float x1 = (float) (ax - camera.x), y1 = (float) (ay - camera.y), z1 = (float) (az - camera.z);
        float x2 = (float) (bx - camera.x), y2 = (float) (by - camera.y), z2 = (float) (bz - camera.z);
        float dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        if (dx * dx + dy * dy + dz * dz < 1e-10f) return;
        // Square to the line and to the way from the eye to each end.
        float[] sa = across(dx, dy, dz, x1, y1, z1), sb = across(dx, dy, dz, x2, y2, z2);
        if (sa == null) sa = sb;
        if (sb == null) sb = sa;
        if (sa == null) return;
        float wa = (float) (pixels ? reach * da / focal : reach), wb = (float) (pixels ? reach * db / focal : reach);
        for (int side = -1; side <= 1; side += 2) {
            for (int k = 0; k < GLOW_AT.length - 1; k++) {
                float i0 = GLOW_AT[k] * side, i1 = GLOW_AT[k + 1] * side;
                quad(out, pose,
                    x1 + sa[0] * wa * i0, y1 + sa[1] * wa * i0, z1 + sa[2] * wa * i0, faint(color, GLOW_FALL[k]),
                    x2 + sb[0] * wb * i0, y2 + sb[1] * wb * i0, z2 + sb[2] * wb * i0, faint(colorTo, GLOW_FALL[k]),
                    x2 + sb[0] * wb * i1, y2 + sb[1] * wb * i1, z2 + sb[2] * wb * i1, faint(colorTo, GLOW_FALL[k + 1]),
                    x1 + sa[0] * wa * i1, y1 + sa[1] * wa * i1, z1 + sa[2] * wa * i1, faint(color, GLOW_FALL[k + 1]));
            }
        }
    }

    /** The unit vector square to both (dx, dy, dz) and (vx, vy, vz), or null when they're in line. */
    private static float[] across(float dx, float dy, float dz, float vx, float vy, float vz) {
        float sx = dy * vz - dz * vy, sy = dz * vx - dx * vz, sz = dx * vy - dy * vx;
        float length = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (length < 1e-7f) return null;
        return new float[] {sx / length, sy / length, sz / length};
    }

    /** A disc facing the camera: a whiter middle, then rings of the colour fading out to its edge. */
    private void ball(VertexConsumer out, PoseStack.Pose pose, Ball b, int color) {
        if ((color >>> 24) == 0) return;
        float cx = (float) (b.at.x - camera.x), cy = (float) (b.at.y - camera.y), cz = (float) (b.at.z - camera.z);
        float r = (float) b.radius;
        int middle = whiter(color);
        for (int k = 0; k < GLOW_AT.length - 1; k++) {
            float r0 = GLOW_AT[k] * r, r1 = GLOW_AT[k + 1] * r;
            int c0 = k == 0 ? middle : faint(color, GLOW_FALL[k]), c1 = faint(color, GLOW_FALL[k + 1]);
            for (int i = 0; i < BALL_SEGMENTS; i++) {
                float ax = BALL_COS[i], ay = BALL_SIN[i], bx = BALL_COS[i + 1], by = BALL_SIN[i + 1];
                quad(out, pose,
                    cx + (left.x() * ax + up.x() * ay) * r0, cy + (left.y() * ax + up.y() * ay) * r0, cz + (left.z() * ax + up.z() * ay) * r0, c0,
                    cx + (left.x() * ax + up.x() * ay) * r1, cy + (left.y() * ax + up.y() * ay) * r1, cz + (left.z() * ax + up.z() * ay) * r1, c1,
                    cx + (left.x() * bx + up.x() * by) * r1, cy + (left.y() * bx + up.y() * by) * r1, cz + (left.z() * bx + up.z() * by) * r1, c1,
                    cx + (left.x() * bx + up.x() * by) * r0, cy + (left.y() * bx + up.y() * by) * r0, cz + (left.z() * bx + up.z() * by) * r0, c0);
            }
        }
    }

    /** The six faces, {@code bottom} along the bottom shading to {@code top} along the top. */
    private void faces(VertexConsumer out, PoseStack.Pose pose, AABB box, int bottom, int top) {
        float x1 = (float) (box.minX - camera.x), y1 = (float) (box.minY - camera.y), z1 = (float) (box.minZ - camera.z);
        float x2 = (float) (box.maxX - camera.x), y2 = (float) (box.maxY - camera.y), z2 = (float) (box.maxZ - camera.z);

        quad(out, pose, x1, y1, z1, bottom, x2, y1, z1, bottom, x2, y1, z2, bottom, x1, y1, z2, bottom);
        quad(out, pose, x1, y2, z1, top, x1, y2, z2, top, x2, y2, z2, top, x2, y2, z1, top);
        quad(out, pose, x1, y1, z1, bottom, x1, y2, z1, top, x2, y2, z1, top, x2, y1, z1, bottom);
        quad(out, pose, x1, y1, z2, bottom, x2, y1, z2, bottom, x2, y2, z2, top, x1, y2, z2, top);
        quad(out, pose, x1, y1, z1, bottom, x1, y1, z2, bottom, x1, y2, z2, top, x1, y2, z1, top);
        quad(out, pose, x2, y1, z1, bottom, x2, y2, z1, top, x2, y2, z2, top, x2, y1, z2, bottom);
    }

    private static void quad(VertexConsumer out, PoseStack.Pose pose,
                             float ax, float ay, float az, int ac, float bx, float by, float bz, int bc,
                             float cx, float cy, float cz, int cc, float dx, float dy, float dz, int dc) {
        out.addVertex(pose, ax, ay, az).setColor(ac);
        out.addVertex(pose, bx, by, bz).setColor(bc);
        out.addVertex(pose, cx, cy, cz).setColor(cc);
        out.addVertex(pose, dx, dy, dz).setColor(dc);
    }

    private void edges(Box b, EdgeSink out) {
        AABB box = b.box;
        double x1 = box.minX, y1 = box.minY, z1 = box.minZ;
        double x2 = box.maxX, y2 = box.maxY, z2 = box.maxZ;

        if (b.corners) {
            // Three short arms out of each corner, along the three edges that meet there: a share of
            // each edge, but at least a few line widths on screen, so a far box's brackets don't shrink
            // into stubs. At half the edge they meet, and a box that small is simply drawn whole.
            double minArm = CORNER_MIN_WIDTHS * b.width * pixelScale / focal;
            double ex = x2 - x1, ey = y2 - y1, ez = z2 - z1;
            for (int i = 0; i < 8; i++) {
                double x = (i & 1) == 0 ? x1 : x2, y = (i & 2) == 0 ? y1 : y2, z = (i & 4) == 0 ? z1 : z2;
                double reach = minArm * Math.max(0, ahead(x, y, z));
                double cx = arm(ex, reach), cy = arm(ey, reach), cz = arm(ez, reach);
                double sx = x == x1 ? cx : -cx, sy = y == y1 ? cy : -cy, sz = z == z1 ? cz : -cz;
                out.edge(x, y, z, x + sx, y, z);
                out.edge(x, y, z, x, y + sy, z);
                out.edge(x, y, z, x, y, z + sz);
            }
            return;
        }

        // Bottom ring, top ring, then the four uprights.
        out.edge(x1, y1, z1, x2, y1, z1);
        out.edge(x2, y1, z1, x2, y1, z2);
        out.edge(x2, y1, z2, x1, y1, z2);
        out.edge(x1, y1, z2, x1, y1, z1);
        out.edge(x1, y2, z1, x2, y2, z1);
        out.edge(x2, y2, z1, x2, y2, z2);
        out.edge(x2, y2, z2, x1, y2, z2);
        out.edge(x1, y2, z2, x1, y2, z1);
        out.edge(x1, y1, z1, x1, y2, z1);
        out.edge(x2, y1, z1, x2, y2, z1);
        out.edge(x2, y1, z2, x2, y2, z2);
        out.edge(x1, y1, z2, x1, y2, z2);
    }

    /** A corner's arm along an edge this long: its share of the edge, at least {@code reach}, at most half of it. */
    private static double arm(double edge, double reach) {
        return Math.min(edge / 2, Math.max(edge * CORNER, reach));
    }

    /**
     * A box edge, drawn no wider than it is long on screen. The line shader only widens a line
     * sideways, never along it, so a short arm or an edge pointing at you drawn wider than it is long
     * turns into a bar across the edge instead (the glow, at several line widths, most of all), and
     * three of those at a corner make a star.
     */
    private void boxEdge(VertexConsumer out, PoseStack.Pose pose, double ax, double ay, double az,
                         double bx, double by, double bz, int color, float width) {
        edge(out, pose, ax, ay, az, bx, by, bz, color, color, (float) Math.min(width, onScreen(ax, ay, az, bx, by, bz)));
    }

    /** How far in front of the camera a point is, along the way it looks. */
    private double ahead(double x, double y, double z) {
        return (x - camera.x) * forward.x() + (y - camera.y) * forward.y() + (z - camera.z) * forward.z();
    }

    /** About how many framebuffer pixels long a segment is on screen; endless when an end is beside or behind the camera. */
    private double onScreen(double ax, double ay, double az, double bx, double by, double bz) {
        double da = ahead(ax, ay, az), db = ahead(bx, by, bz);
        if (da < NEAR || db < NEAR) return Double.POSITIVE_INFINITY;
        // Each end over its distance ahead lands where it shows on a screen one block in front of the camera.
        double dx = (ax - camera.x) / da - (bx - camera.x) / db;
        double dy = (ay - camera.y) / da - (by - camera.y) / db;
        double dz = (az - camera.z) / da - (bz - camera.z) / db;
        return focal * Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private void segment(VertexConsumer out, PoseStack.Pose pose, Vec3 a, Vec3 b, int color, int colorTo, float width) {
        edge(out, pose, a.x, a.y, a.z, b.x, b.y, b.z, color, colorTo, width);
    }

    private void edge(VertexConsumer out, PoseStack.Pose pose, double ax, double ay, double az,
                      double bx, double by, double bz, int color, int colorTo, float width) {
        float x1 = (float) (ax - camera.x), y1 = (float) (ay - camera.y), z1 = (float) (az - camera.z);
        float x2 = (float) (bx - camera.x), y2 = (float) (by - camera.y), z2 = (float) (bz - camera.z);

        // The line shader widens each segment on screen along its direction, passed as the normal.
        float nx = x2 - x1, ny = y2 - y1, nz = z2 - z1;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length < 1e-5f) return;
        nx /= length;
        ny /= length;
        nz /= length;

        width = Math.max(1f, width);
        out.addVertex(pose, x1, y1, z1).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(width);
        out.addVertex(pose, x2, y2, z2).setColor(colorTo).setNormal(pose, nx, ny, nz).setLineWidth(width);
    }

    private static void setup() {
        if (buffers != null) return;

        RenderPipeline linePipeline = RenderPipeline.builder(RenderPipelinesAccessor.cyanite$linesSnippet())
            .withLocation(Identifier.fromNamespaceAndPath("cyanite_client", "pipeline/esp_lines"))
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withCull(false)
            .build();

        RenderPipeline quadPipeline = RenderPipeline.builder(RenderPipelinesAccessor.cyanite$filledSnippet())
            .withLocation(Identifier.fromNamespaceAndPath("cyanite_client", "pipeline/esp_quads"))
            .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withCull(false)
            .build();

        // Glow adds its light to what's already there, rather than covering it.
        RenderPipeline lightPipeline = RenderPipeline.builder(RenderPipelinesAccessor.cyanite$filledSnippet())
            .withLocation(Identifier.fromNamespaceAndPath("cyanite_client", "pipeline/esp_light"))
            .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
            .withBlend(BlendFunction.LIGHTNING)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withCull(false)
            .build();

        RenderPipeline lightDepthPipeline = RenderPipeline.builder(RenderPipelinesAccessor.cyanite$filledSnippet())
            .withLocation(Identifier.fromNamespaceAndPath("cyanite_client", "pipeline/world_light"))
            .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
            .withBlend(BlendFunction.LIGHTNING)
            .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
            .withDepthWrite(false)
            .withCull(false)
            .build();

        lines = RenderTypeAccessor.cyanite$create("cyanite_esp_lines", RenderSetup.builder(linePipeline).createRenderSetup());
        quads = RenderTypeAccessor.cyanite$create("cyanite_esp_quads", RenderSetup.builder(quadPipeline).createRenderSetup());
        light = RenderTypeAccessor.cyanite$create("cyanite_esp_light", RenderSetup.builder(lightPipeline).createRenderSetup());
        lightDepth = RenderTypeAccessor.cyanite$create("cyanite_world_light", RenderSetup.builder(lightDepthPipeline).createRenderSetup());
        buffers = MultiBufferSource.immediate(new ByteBufferBuilder(1 << 18));
    }
}
