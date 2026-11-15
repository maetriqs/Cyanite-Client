package com.cyanite.client.visuals;

import com.cyanite.client.render.Render3D;
import com.cyanite.client.system.WorldScan;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * A streak behind the tip of a swinging sword, yours and other players', fading from one colour to another.
 * The tip is followed every frame along the swing's arc and kept relative to whoever swings it, so the trail
 * moves with them.
 */
public final class SwordTrail {
    public static final Group GROUP = new Group("sword_trail", "Sword Trail",
        "A streak behind the tip of a swinging sword, fading from one colour to another.", "sword trail swing streak slash effect");
    static final ColorSetting COLOR = GROUP.add(new ColorSetting("Color", 0x4DE1FF).full());
    static final ColorSetting END_COLOR = GROUP.add(new ColorSetting("Fade to", 0xB04DFF).full());
    static final SliderSetting LENGTH = GROUP.add(new SliderSetting("Length", 0.25, 0.08, 0.8, 0.02).format(v -> String.format(Locale.ROOT, "%.2fs", v)));
    static final SliderSetting WIDTH = GROUP.add(new SliderSetting("Width", 4, 1, 10, 0.5));
    static final BoolSetting OTHERS = GROUP.add(new BoolSetting("Other players' swords", true));
    static final BoolSetting GLOW = GROUP.add(new BoolSetting("Glow", true));

    private static final double RANGE = 32;
    private static final int MAX_POINTS = 90;

    /** An offset from its swinger and when it was there. */
    private record Point(double x, double y, double z, long at) {
    }

    private static final class Trail {
        final ArrayDeque<Point> points = new ArrayDeque<>();
        Entity entity;
        boolean firstPerson;
    }

    private static final Map<Integer, Trail> trails = new HashMap<>();

    private SwordTrail() {
    }

    static void tick() {
        long now = System.nanoTime();
        long keep = (long) (LENGTH.get() * 1e9);
        Iterator<Trail> it = trails.values().iterator();
        while (it.hasNext()) {
            Trail trail = it.next();
            while (!trail.points.isEmpty() && now - trail.points.peekFirst().at > keep) trail.points.pollFirst();
            if (trail.points.isEmpty() || trail.entity.isRemoved()) it.remove();
        }
    }

    static void render3D(Render3D render) {
        Minecraft mc = Minecraft.getInstance();
        float partial = render.partialTick();
        long now = System.nanoTime();
        for (Entity e : WorldScan.entities()) {
            if (!(e instanceof Player player) || e.distanceToSqr(mc.player) > RANGE * RANGE) continue;
            if (e != mc.player && !OTHERS.get()) continue;
            if (Weapon.of(player.getMainHandItem()) != Weapon.SWORD) continue;
            float swing = player.getAttackAnim(partial);
            if (swing <= 0 || swing >= 1) continue;
            boolean firstPerson = e == mc.player && mc.options.getCameraType().isFirstPerson();
            Vec3 tip = firstPerson ? firstPersonTip(player, swing, partial) : tip(player, swing, partial);
            Trail trail = trails.computeIfAbsent(e.getId(), id -> new Trail());
            trail.entity = e;
            trail.firstPerson = firstPerson;
            if (trail.points.size() >= MAX_POINTS) trail.points.pollFirst();
            trail.points.add(new Point(tip.x, tip.y, tip.z, now));
        }

        double length = LENGTH.get() * 1e9;
        int from = COLOR.get();
        int to = END_COLOR.get();
        float width = WIDTH.get().floatValue();
        boolean glow = GLOW.get();
        for (Trail trail : trails.values()) {
            if (trail.points.size() < 2) continue;
            Vec3 base = base(trail.entity, trail.firstPerson, partial);
            Point prev = null;
            for (Point p : trail.points) {
                if (prev != null) {
                    float a0 = (float) (1 - (now - prev.at) / length);
                    float a1 = (float) (1 - (now - p.at) / length);
                    if (a1 > 0) {
                        int c0 = Visuals.argb(Visuals.lerp(0xFF000000 | to, 0xFF000000 | from, a0), Math.max(0, a0) * 0.9);
                        int c1 = Visuals.argb(Visuals.lerp(0xFF000000 | to, 0xFF000000 | from, a1), a1 * 0.9);
                        render.line(base.add(prev.x, prev.y, prev.z), base.add(p.x, p.y, p.z), c0, c1, width * (0.35f + 0.65f * a1), glow);
                    }
                }
                prev = p;
            }
        }
    }

    /** What a trail's offsets are from: your eyes in first person, everyone else's feet. */
    private static Vec3 base(Entity entity, boolean firstPerson, float partial) {
        return firstPerson ? entity.getEyePosition(partial) : entity.getPosition(partial);
    }

    /** Your sword's tip in first person, from your eyes: a sweep from upper right to lower left across your view. */
    private static Vec3 firstPersonTip(Player player, float swing, float partial) {
        float eased = Mth.sin(Mth.sqrt(swing) * Mth.HALF_PI);
        float yaw = player.getViewYRot(partial) + Mth.lerp(eased, 50, -40);
        float pitch = player.getViewXRot(partial) + Mth.lerp(eased, -18, 30);
        return Vec3.directionFromRotation(pitch, yaw).scale(1.25).add(0, -0.12, 0);
    }

    /** Someone's sword tip seen from outside, from their feet: right shoulder, down and across in front of them. */
    private static Vec3 tip(LivingEntity entity, float swing, float partial) {
        float bodyYaw = Mth.rotLerp(partial, entity.yBodyRotO, entity.yBodyRot) * Mth.DEG_TO_RAD;
        float eased = Mth.sin(Mth.sqrt(swing) * Mth.HALF_PI);
        double right = Mth.lerp(eased, 0.75, -0.35);
        double up = entity.getBbHeight() * 0.78 + Mth.lerp(eased, 0.55, -0.45);
        double ahead = 0.55 + Mth.sin(swing * Mth.PI) * 0.6;
        // Body yaw 0 faces +Z; right of that is -X.
        double sin = Math.sin(bodyYaw);
        double cos = Math.cos(bodyYaw);
        double x = -right * cos - ahead * sin;
        double z = -right * sin + ahead * cos;
        return new Vec3(x, up, z);
    }
}
