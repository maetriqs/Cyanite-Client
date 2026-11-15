package com.cyanite.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * World-to-screen projection using the exact matrices the last frame was rendered with,
 * captured by LevelRendererMixin, so view bobbing and FOV changes line up.
 */
public final class Projection {
    private static final Matrix4f VIEW = new Matrix4f();
    private static final Matrix4f PROJECTION = new Matrix4f();
    private static final Vector4f SCRATCH = new Vector4f();
    private static Vec3 camera = Vec3.ZERO;
    private static boolean ready;

    private Projection() {
    }

    public static void capture(Vec3 cameraPos, Matrix4f view, Matrix4f projection) {
        // Don't rely on argument order: the perspective matrix is the one with w taken from -z.
        if (isPerspective(view) && !isPerspective(projection)) {
            Matrix4f swap = view;
            view = projection;
            projection = swap;
        }
        camera = cameraPos;
        VIEW.set(view);
        PROJECTION.set(projection);
        ready = true;
    }

    private static boolean isPerspective(Matrix4f m) {
        return Math.abs(m.m23() + 1f) < 1e-3f && Math.abs(m.m33()) < 1e-3f;
    }

    /**
     * Sets {@code out} to take a screen position (x, y in -1..1, z -1 near to 1 far) to a point
     * relative to the camera, for drawing the sky: the inverse of this frame's projection and view.
     * @return false before the first frame
     */
    public static boolean skyRay(Matrix4f out) {
        if (!ready) return false;
        out.set(VIEW).setTranslation(0, 0, 0).mulLocal(PROJECTION).invert();
        return true;
    }

    /**
     * Framebuffer pixels a block spans, face-on and one block in front of the camera, at this frame's
     * FOV (zoom and all); divide by how far ahead something is for its size there. 0 before the first frame.
     */
    public static double pixelsPerBlock() {
        if (!ready) return 0;
        return PROJECTION.m11() * 0.5 * Minecraft.getInstance().getWindow().getHeight();
    }

    /** Framebuffer pixel position of a world point, or null if it's behind the camera. */
    public static double[] toScreen(Vec3 world) {
        if (!ready) return null;

        SCRATCH.set((float) (world.x - camera.x), (float) (world.y - camera.y), (float) (world.z - camera.z), 1f);
        VIEW.transform(SCRATCH);
        PROJECTION.transform(SCRATCH);
        if (SCRATCH.w <= 0.05f) return null;

        float ndcX = SCRATCH.x / SCRATCH.w;
        float ndcY = SCRATCH.y / SCRATCH.w;
        var window = Minecraft.getInstance().getWindow();
        return new double[]{
            (ndcX * 0.5 + 0.5) * window.getWidth(),
            (0.5 - ndcY * 0.5) * window.getHeight()
        };
    }
}
