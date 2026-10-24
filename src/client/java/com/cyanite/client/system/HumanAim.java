package com.cyanite.client.system;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * Turns the player toward a point the way a hand on a mouse would, one frame at a time: a short
 * reaction pause, a quick swing that slows as it lands, a slight curve, a little tremor, and only
 * ever in whole mouse counts at your real sensitivity. Call {@link #frame()} every frame.
 */
public final class HumanAim {
    private static final Minecraft mc = Minecraft.getInstance();

    private static boolean turning;

    private final Random random = new Random();
    private final double[] phase = new double[6];

    /** Overall turn speed multiplier. */
    public double speed = 1;
    /** 0 is perfectly mechanical, 1 is as sloppy as it gets. */
    public double randomness = 0.5;

    private Vec3 target;
    private long lastFrame;
    private long moveAfter;
    private double speedFactor = 1;
    private double curve;
    private double countsX;
    private double countsY;
    private double time;

    public HumanAim() {
        for (int i = 0; i < phase.length; i++) phase[i] = random.nextDouble() * Math.PI * 2;
    }

    /** True while a module is turning the player, as opposed to the real mouse. */
    public static boolean isTurning() {
        return turning;
    }

    /** Starts a new movement toward {@code point}, with a fresh reaction time and feel. */
    public void setTarget(Vec3 point) {
        target = point;
        double r = randomness;
        moveAfter = System.nanoTime() + (long) ((35 + random.nextDouble() * 170 * r) * 1_000_000);
        speedFactor = 1 + (random.nextDouble() * 2 - 1) * 0.3 * r;
        curve = (random.nextDouble() * 2 - 1) * 0.18 * r;
    }

    /** Moves the target without starting a new movement, for following something that moves. */
    public void track(Vec3 point) {
        if (target == null) setTarget(point);
        else target = point;
    }

    public Vec3 target() {
        return target;
    }

    public void clear() {
        target = null;
    }

    /** Skips the time spent paused, so the next frame doesn't jump. */
    public void idle() {
        lastFrame = 0;
    }

    /** How far, in degrees, the crosshair is from the target. */
    public double error() {
        LocalPlayer player = mc.player;
        if (target == null || player == null) return 0;
        double[] want = angles(player, target);
        return Math.hypot(Mth.wrapDegrees(want[0] - player.getYRot()), want[1] - player.getXRot());
    }

    public void frame() {
        long now = System.nanoTime();
        double dt = lastFrame == 0 ? 0 : Math.min((now - lastFrame) / 1e9, 0.05);
        lastFrame = now;

        LocalPlayer player = mc.player;
        if (target == null || player == null || dt == 0) return;
        time += dt;
        if (now < moveAfter) return;

        double[] want = angles(player, target);
        // Tremor: a slow wander of a fraction of a degree, made of a few out-of-step waves.
        double tremor = 0.4 * randomness;
        double yaw = want[0] + tremor * wave(time, 0);
        double pitch = want[1] + tremor * wave(time * 0.9, 3);

        double errYaw = Mth.wrapDegrees(yaw - player.getYRot());
        double errPitch = pitch - player.getXRot();
        double err = Math.hypot(errYaw, errPitch);
        // Closer than one mouse count is as close as a real mouse gets; chasing it would just flicker.
        if (err < degPerCount()) return;

        // Fast across big gaps and easing in at the end, like a swipe that slows to land.
        double degPerSec = Mth.clamp(err * 7, 10, 420) * speed * speedFactor;
        double step = Math.min(err, degPerSec * dt);
        double moveYaw = errYaw / err * step;
        double movePitch = errPitch / err * step;
        // Wrists don't move in straight lines: bend the path a little, straightening out near the end.
        movePitch += moveYaw * curve * Math.min(1, err / 4);

        turn(player, moveYaw, movePitch);
    }

    /** Applies a turn in whole mouse counts, exactly as MouseHandler would at the current sensitivity. */
    private void turn(LocalPlayer player, double yawDeg, double pitchDeg) {
        double f = countScale();
        double degPerCount = f * 0.15;

        countsX += yawDeg / degPerCount;
        countsY += pitchDeg / degPerCount;
        long x = (long) countsX;
        long y = (long) countsY;
        countsX -= x;
        countsY -= y;
        if (x != 0 || y != 0) {
            // Turns the player even while Freecam has the mouse; see EntityMixin.
            turning = true;
            try {
                player.turn(x * f, y * f);
            } finally {
                turning = false;
            }
        }
    }

    /** What one mouse count is worth before MouseHandler's 0.15: the sensitivity curve. */
    private static double countScale() {
        double f = mc.options.sensitivity().get() * 0.6 + 0.2;
        return f * f * f * 8;
    }

    private static double degPerCount() {
        return countScale() * 0.15;
    }

    private double wave(double t, int offset) {
        return Math.sin(t * 1.1 + phase[offset]) * 0.6
            + Math.sin(t * 2.7 + phase[offset + 1]) * 0.3
            + Math.sin(t * 5.3 + phase[offset + 2]) * 0.1;
    }

    private static double[] angles(LocalPlayer player, Vec3 point) {
        Vec3 d = point.subtract(player.getEyePosition());
        double yaw = Math.toDegrees(Math.atan2(d.z, d.x)) - 90;
        double pitch = -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        return new double[] {yaw, pitch};
    }
}
