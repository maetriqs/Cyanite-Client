package com.cyanite.client.render;

import com.cyanite.client.mixin.GameRendererAccessor;
import com.cyanite.client.mixin.RenderPipelinesAccessor;
import com.cyanite.client.mixin.RenderTypeAccessor;
import com.cyanite.client.system.Modules;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
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
    /** Glow is two faint passes under the edges, a wide one and a closer one, so it falls off softly. */
    private static final float GLOW_OUTER_WIDTH = 6f;
    private static final float GLOW_OUTER_ALPHA = 0.08f;
    private static final float GLOW_INNER_WIDTH = 3f;
    private static final float GLOW_INNER_ALPHA = 0.18f;
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
    private static MultiBufferSource.BufferSource buffers;

    private final Vec3 camera;
    private final Vector3fc forward;
    private final float partialTick;
    private final List<Box> boxes = new ArrayList<>();
    private final List<Line> segments = new ArrayList<>();
    private final List<Label> labels = new ArrayList<>();
    /** Framebuffer pixels per 1080p pixel, and per block face-on one block ahead; set when the frame is drawn. */
    private float pixelScale;
    private double focal;

    private record Box(AABB box, int fill, int fillTop, int outline, float width, boolean glow, boolean corners) {
    }

    private record Line(Vec3 from, Vec3 to, int color, int colorTo, float width, boolean glow) {
    }

    private record Label(Vec3 at, Quaternionf facing, float size, float x, float y, String text, int color, int background, boolean seeThrough) {
    }

    private Render3D(Vec3 camera, Vector3fc forward, float partialTick) {
        this.camera = camera;
        this.forward = forward;
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
     * @param glow    adds a wide faint pass under the edges
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
        Render3D frame = new Render3D(cam.position(), cam.forwardVector(), mc.getDeltaTracker().getGameTimeDeltaPartialTick(true));
        try {
            Modules.render3D(frame);
            com.cyanite.client.visuals.Visuals.render3D(frame);
        } finally {
            EspGlow.end();
        }
        com.cyanite.client.modules.world.build.StandIns.render(frame);
        if (frame.boxes.isEmpty() && frame.segments.isEmpty() && frame.labels.isEmpty()) return;

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

        VertexConsumer edges = buffers.getBuffer(lines);
        // Glow first, wide then close, so the crisp edges sit on top of it.
        glow(edges, pose, GLOW_OUTER_WIDTH * pixelScale, GLOW_OUTER_ALPHA);
        glow(edges, pose, GLOW_INNER_WIDTH * pixelScale, GLOW_INNER_ALPHA);
        for (Box b : boxes) {
            if ((b.outline >>> 24) != 0) edges(edges, pose, b, b.outline, b.width * pixelScale);
        }
        for (Line l : segments) {
            segment(edges, pose, l.from, l.to, l.color, l.colorTo, l.width * pixelScale);
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

    /** One faint, wide pass under every glowing box edge and line. */
    private void glow(VertexConsumer out, PoseStack.Pose pose, float widthScale, float alpha) {
        for (Box b : boxes) {
            if (b.glow && (b.outline >>> 24) != 0) edges(out, pose, b, faint(b.outline, alpha), b.width * widthScale);
        }
        for (Line l : segments) {
            if (l.glow) segment(out, pose, l.from, l.to, faint(l.color, alpha), faint(l.colorTo, alpha), l.width * widthScale);
        }
    }

    private static int faint(int argb, float alpha) {
        int a = Math.round((argb >>> 24) * alpha);
        return a << 24 | (argb & 0xFFFFFF);
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

    private void edges(VertexConsumer out, PoseStack.Pose pose, Box b, int color, float width) {
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
                boxEdge(out, pose, x, y, z, x + sx, y, z, color, width);
                boxEdge(out, pose, x, y, z, x, y + sy, z, color, width);
                boxEdge(out, pose, x, y, z, x, y, z + sz, color, width);
            }
            return;
        }

        // Bottom ring, top ring, then the four uprights.
        boxEdge(out, pose, x1, y1, z1, x2, y1, z1, color, width);
        boxEdge(out, pose, x2, y1, z1, x2, y1, z2, color, width);
        boxEdge(out, pose, x2, y1, z2, x1, y1, z2, color, width);
        boxEdge(out, pose, x1, y1, z2, x1, y1, z1, color, width);
        boxEdge(out, pose, x1, y2, z1, x2, y2, z1, color, width);
        boxEdge(out, pose, x2, y2, z1, x2, y2, z2, color, width);
        boxEdge(out, pose, x2, y2, z2, x1, y2, z2, color, width);
        boxEdge(out, pose, x1, y2, z2, x1, y2, z1, color, width);
        boxEdge(out, pose, x1, y1, z1, x1, y2, z1, color, width);
        boxEdge(out, pose, x2, y1, z1, x2, y2, z1, color, width);
        boxEdge(out, pose, x2, y1, z2, x2, y2, z2, color, width);
        boxEdge(out, pose, x1, y1, z2, x1, y2, z2, color, width);
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

        lines = RenderTypeAccessor.cyanite$create("cyanite_esp_lines", RenderSetup.builder(linePipeline).createRenderSetup());
        quads = RenderTypeAccessor.cyanite$create("cyanite_esp_quads", RenderSetup.builder(quadPipeline).createRenderSetup());
        buffers = MultiBufferSource.immediate(new ByteBufferBuilder(1 << 18));
    }
}
