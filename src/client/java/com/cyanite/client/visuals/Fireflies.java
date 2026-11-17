package com.cyanite.client.visuals;

import com.cyanite.client.render.Render3D;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Little glowing lights drifting in the air around you, blinking on and off like fireflies. They only fly
 * through air and stay near you; only you see them.
 */
public final class Fireflies {
    public static final Group GROUP = new Group("fireflies", "Fireflies",
        "Little glowing lights drift and blink in the air around you, like fireflies. Only you see them.",
        "fireflies firefly ambient ambience lights glow particles cozy night magic");
    static final ColorSetting COLOR = GROUP.add(new ColorSetting("Color", 0xD4FF6B).full());
    static final SliderSetting AMOUNT = GROUP.add(new SliderSetting("Amount", 30, 5, 100, 1));
    static final SliderSetting SIZE = GROUP.add(new SliderSetting("Size", 1, 0.5, 2.5, 0.05).format(Particles::times));
    static final SliderSetting SPREAD = GROUP.add(new SliderSetting("Spread", 10, 4, 24, 1));
    static final BoolSetting NIGHT = GROUP.add(new BoolSetting("Only at night", false));

    private static final RandomSource RANDOM = RandomSource.create();
    private static final List<Fly> flies = new ArrayList<>();

    private static final class Fly {
        double x, y, z, ox, oy, oz, vx, vy, vz;
        final float phase = RANDOM.nextFloat() * 100;
        final float speed = 1.2f + RANDOM.nextFloat() * 1.6f;
    }

    private Fireflies() {
    }

    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        int want = AMOUNT.get().intValue();
        while (flies.size() > want) flies.removeLast();
        while (flies.size() < want) flies.add(new Fly());
        double spread = SPREAD.get();
        Vec3 me = mc.player.position();
        for (Fly f : flies) {
            f.ox = f.x;
            f.oy = f.y;
            f.oz = f.z;
            double dx = f.x - me.x, dy = f.y - me.y, dz = f.z - me.z;
            if (dx * dx + dz * dz > spread * spread * 1.6 || Math.abs(dy) > spread || solid(mc, f.x, f.y, f.z)) {
                place(mc, f, me, spread);
                continue;
            }
            // Wander: a little push each tick, slowed by the air, and drift back up off the ground.
            f.vx = (f.vx + (RANDOM.nextFloat() - 0.5) * 0.012) * 0.95;
            f.vy = (f.vy + (RANDOM.nextFloat() - 0.5) * 0.008) * 0.95;
            f.vz = (f.vz + (RANDOM.nextFloat() - 0.5) * 0.012) * 0.95;
            if (solid(mc, f.x, f.y - 0.6, f.z)) f.vy += 0.002;
            double nx = f.x + f.vx, ny = f.y + f.vy, nz = f.z + f.vz;
            if (solid(mc, nx, ny, nz)) {
                f.vx = -f.vx;
                f.vy = -f.vy;
                f.vz = -f.vz;
            } else {
                f.x = nx;
                f.y = ny;
                f.z = nz;
            }
        }
    }

    /** Somewhere in the air near you: a few tries, and if all are in blocks it waits for the next tick. */
    private static void place(Minecraft mc, Fly f, Vec3 me, double spread) {
        for (int i = 0; i < 4; i++) {
            double a = RANDOM.nextDouble() * Math.PI * 2, r = Math.sqrt(RANDOM.nextDouble()) * spread;
            double x = me.x + Math.cos(a) * r, y = me.y + 0.3 + RANDOM.nextDouble() * 3.5, z = me.z + Math.sin(a) * r;
            if (solid(mc, x, y, z)) continue;
            f.x = f.ox = x;
            f.y = f.oy = y;
            f.z = f.oz = z;
            f.vx = f.vy = f.vz = 0;
            return;
        }
        f.x = f.ox = me.x;
        f.y = f.oy = me.y - 1000;
        f.z = f.oz = me.z;
    }

    private static boolean solid(Minecraft mc, double x, double y, double z) {
        BlockPos pos = BlockPos.containing(x, y, z);
        return !mc.level.getBlockState(pos).getCollisionShape(mc.level, pos).isEmpty() || !mc.level.getFluidState(pos).isEmpty();
    }

    static void render3D(Render3D render) {
        Minecraft mc = Minecraft.getInstance();
        float dark = 1;
        if (NIGHT.get()) {
            // The sky's light where you are: none at night, full by day.
            float sky = mc.level.getSkyDarken() / 11f;
            dark = Math.max(0, Math.min(1, sky * 1.6f - 0.3f));
            if (dark <= 0) return;
        }
        float partial = render.partialTick();
        double size = SIZE.get();
        int rgb = COLOR.get();
        float seconds = (System.nanoTime() % 3_600_000_000_000L) / 1e9f;
        for (Fly f : flies) {
            if (f.y < mc.level.getMinY() - 64) continue;
            // Each blinks on its own beat: mostly lit, fading out now and then.
            float blink = (float) Math.sin(seconds * f.speed + f.phase);
            float lit = 0.25f + 0.75f * Math.max(0, blink * 0.8f + 0.4f);
            if (lit <= 0.02f) continue;
            Vec3 at = new Vec3(f.ox + (f.x - f.ox) * partial, f.oy + (f.y - f.oy) * partial, f.oz + (f.z - f.oz) * partial);
            render.glow(at, 0.16 * size, Visuals.argb(rgb, 0.95 * lit * dark), false);
        }
    }

    static void clear() {
        flies.clear();
    }
}
