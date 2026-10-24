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
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.List;

/**
 * World-space drawing for ESP-style features: boxes and lines that show through walls.
 * Modules queue shapes from {@code onRender3D} each frame and everything is drawn in one
 * go at the end of the main world pass. Positions are world coordinates.
 */
public final class Render3D {
    private static final float GLOW_WIDTH = 3.5f;
    private static final float GLOW_ALPHA = 0.22f;

    private static RenderType lines;
    private static RenderType quads;
    private static MultiBufferSource.BufferSource buffers;

    private final Vec3 camera;
    private final Vector3fc forward;
    private final float partialTick;
    private final List<Box> boxes = new ArrayList<>();
    private final List<Line> segments = new ArrayList<>();

    private record Box(AABB box, int fill, int outline, float width, boolean glow) {
    }

    private record Line(Vec3 from, Vec3 to, int color, float width, boolean glow) {
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
        boxes.add(new Box(box, fill, outline, width, glow));
    }

    public void line(Vec3 from, Vec3 to, int color, float width, boolean glow) {
        segments.add(new Line(from, to, color, width, glow));
    }

    /** A line from the middle of the screen out to {@code to}. */
    public void tracer(Vec3 to, int color, float width, boolean glow) {
        // Start a little in front of the eye; a line from exactly the eye has no length on screen.
        Vec3 start = camera.add(forward.x(), forward.y(), forward.z());
        line(start, to, color, width, glow);
    }

    /** Called from Fabric's END_MAIN world render event. */
    public static void render(WorldRenderContext context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || com.cyanite.client.system.Stealth.active()) return;

        Camera cam = mc.gameRenderer.getMainCamera();
        Render3D frame = new Render3D(cam.position(), cam.forwardVector(), mc.getDeltaTracker().getGameTimeDeltaPartialTick(true));
        Modules.render3D(frame);
        com.cyanite.client.modules.world.build.StandIns.render(frame);
        if (frame.boxes.isEmpty() && frame.segments.isEmpty()) return;

        frame.flush(context.matrices().last(), mc.getWindow().getHeight() / 1080f);
    }

    private void flush(PoseStack.Pose pose, float pixelScale) {
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
            if ((b.fill >>> 24) != 0) faces(faces, pose, b.box, b.fill);
        }
        buffers.endBatch(quads);

        VertexConsumer edges = buffers.getBuffer(lines);
        // Glow first so the crisp edges sit on top of it.
        for (Box b : boxes) {
            if (b.glow && (b.outline >>> 24) != 0) edges(edges, pose, b.box, faint(b.outline), b.width * GLOW_WIDTH * pixelScale);
        }
        for (Line l : segments) {
            if (l.glow) segment(edges, pose, l.from, l.to, faint(l.color), l.width * GLOW_WIDTH * pixelScale);
        }
        for (Box b : boxes) {
            if ((b.outline >>> 24) != 0) edges(edges, pose, b.box, b.outline, b.width * pixelScale);
        }
        for (Line l : segments) {
            segment(edges, pose, l.from, l.to, l.color, l.width * pixelScale);
        }
        buffers.endBatch(lines);
    }

    private static int faint(int argb) {
        int a = Math.round((argb >>> 24) * GLOW_ALPHA);
        return a << 24 | (argb & 0xFFFFFF);
    }

    private void faces(VertexConsumer out, PoseStack.Pose pose, AABB box, int color) {
        float x1 = (float) (box.minX - camera.x), y1 = (float) (box.minY - camera.y), z1 = (float) (box.minZ - camera.z);
        float x2 = (float) (box.maxX - camera.x), y2 = (float) (box.maxY - camera.y), z2 = (float) (box.maxZ - camera.z);

        quad(out, pose, color, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2);
        quad(out, pose, color, x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1);
        quad(out, pose, color, x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1);
        quad(out, pose, color, x1, y1, z2, x2, y1, z2, x2, y2, z2, x1, y2, z2);
        quad(out, pose, color, x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1);
        quad(out, pose, color, x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2);
    }

    private static void quad(VertexConsumer out, PoseStack.Pose pose, int color,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz) {
        out.addVertex(pose, ax, ay, az).setColor(color);
        out.addVertex(pose, bx, by, bz).setColor(color);
        out.addVertex(pose, cx, cy, cz).setColor(color);
        out.addVertex(pose, dx, dy, dz).setColor(color);
    }

    private void edges(VertexConsumer out, PoseStack.Pose pose, AABB box, int color, float width) {
        double x1 = box.minX, y1 = box.minY, z1 = box.minZ;
        double x2 = box.maxX, y2 = box.maxY, z2 = box.maxZ;

        // Bottom ring, top ring, then the four uprights.
        edge(out, pose, x1, y1, z1, x2, y1, z1, color, width);
        edge(out, pose, x2, y1, z1, x2, y1, z2, color, width);
        edge(out, pose, x2, y1, z2, x1, y1, z2, color, width);
        edge(out, pose, x1, y1, z2, x1, y1, z1, color, width);
        edge(out, pose, x1, y2, z1, x2, y2, z1, color, width);
        edge(out, pose, x2, y2, z1, x2, y2, z2, color, width);
        edge(out, pose, x2, y2, z2, x1, y2, z2, color, width);
        edge(out, pose, x1, y2, z2, x1, y2, z1, color, width);
        edge(out, pose, x1, y1, z1, x1, y2, z1, color, width);
        edge(out, pose, x2, y1, z1, x2, y2, z1, color, width);
        edge(out, pose, x2, y1, z2, x2, y2, z2, color, width);
        edge(out, pose, x1, y1, z2, x1, y2, z2, color, width);
    }

    private void segment(VertexConsumer out, PoseStack.Pose pose, Vec3 a, Vec3 b, int color, float width) {
        edge(out, pose, a.x, a.y, a.z, b.x, b.y, b.z, color, width);
    }

    private void edge(VertexConsumer out, PoseStack.Pose pose, double ax, double ay, double az,
                      double bx, double by, double bz, int color, float width) {
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
        out.addVertex(pose, x2, y2, z2).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(width);
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
