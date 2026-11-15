package com.cyanite.client.visuals;

import com.cyanite.client.render.Render3D;
import com.cyanite.client.system.WorldScan;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * Lines left behind things in flight: pearls, potions, snowballs and the like in one group, arrows and
 * tridents in another. Each is followed a tick at a time and the line fades from its head back.
 */
public final class Trails {
    public static final Group PROJECTILES = new Group("projectile_trails", "Projectile Trails",
        "Lines behind thrown pearls, potions, snowballs and eggs, so you can see where they went.",
        "projectile trail pearl ender potion splash throw snowball egg line");
    static final ColorSetting PEARL = PROJECTILES.add(new ColorSetting("Pearl color", 0x3DFFB0).full());
    static final BoolSetting POTION_OWN = PROJECTILES.add(new BoolSetting("Potions in their own color", true));
    static final ColorSetting OTHER = PROJECTILES.add(new ColorSetting("Other color", 0xFFFFFF).full());
    static final SliderSetting LENGTH = PROJECTILES.add(new SliderSetting("Length", 1.5, 0.3, 5, 0.1).format(Trails::seconds));
    static final SliderSetting WIDTH = PROJECTILES.add(new SliderSetting("Width", 2.5, 1, 8, 0.5));

    public static final Group ARROWS = new Group("arrow_trails", "Arrow Trails",
        "Lines behind arrows and tridents in flight, a brighter one for crits.", "arrow trail bow crossbow trident crit line color");
    static final ColorSetting ARROW = ARROWS.add(new ColorSetting("Arrow color", 0xFFE14D).full());
    static final ColorSetting CRIT = ARROWS.add(new ColorSetting("Crit arrow color", 0xFF4D4D).full());
    static final ColorSetting TRIDENT = ARROWS.add(new ColorSetting("Trident color", 0x4DE1FF).full());
    static final SliderSetting ARROW_LENGTH = ARROWS.add(new SliderSetting("Length", 1.2, 0.3, 5, 0.1).format(Trails::seconds));
    static final SliderSetting ARROW_WIDTH = ARROWS.add(new SliderSetting("Width", 2.5, 1, 8, 0.5));

    private static final double RANGE = 96;
    private static final int MAX_TRAILS = 64;

    private record Point(Vec3 at, long time) {
    }

    private static final class Trail {
        final ArrayDeque<Point> points = new ArrayDeque<>();
        Entity entity;
        int rgb;
    }

    private static final Map<Integer, Trail> thrown = new HashMap<>();
    private static final Map<Integer, Trail> arrows = new HashMap<>();

    private Trails() {
    }

    static String seconds(double v) {
        return String.format(Locale.ROOT, "%.1fs", v);
    }

    static void tick(boolean arrowGroup) {
        Map<Integer, Trail> trails = arrowGroup ? arrows : thrown;
        long now = System.nanoTime();
        long keep = (long) ((arrowGroup ? ARROW_LENGTH : LENGTH).get() * 1e9);
        var player = net.minecraft.client.Minecraft.getInstance().player;
        for (Entity e : WorldScan.entities()) {
            if (!(e instanceof Projectile) || e.isRemoved() || e.distanceToSqr(player) > RANGE * RANGE) continue;
            boolean isArrow = e instanceof AbstractArrow;
            if (isArrow != arrowGroup) continue;
            if (!isArrow && !(e instanceof ThrowableItemProjectile)) continue;
            if (stopped(e)) continue;
            Trail trail = trails.get(e.getId());
            if (trail == null) {
                if (trails.size() >= MAX_TRAILS) continue;
                trail = new Trail();
                trails.put(e.getId(), trail);
            }
            trail.entity = e;
            trail.rgb = color(e);
            trail.points.add(new Point(e.position(), now));
        }
        Iterator<Trail> it = trails.values().iterator();
        while (it.hasNext()) {
            Trail trail = it.next();
            while (!trail.points.isEmpty() && now - trail.points.peekFirst().time > keep) trail.points.pollFirst();
            if (trail.points.isEmpty()) it.remove();
        }
    }

    /** An arrow stuck in something, or anything that's stopped moving, leaves no more trail. */
    private static boolean stopped(Entity e) {
        return e.getDeltaMovement().lengthSqr() < 1e-4 || e.onGround();
    }

    private static int color(Entity e) {
        if (e instanceof ThrownTrident) return TRIDENT.get();
        if (e instanceof AbstractArrow arrow) return arrow.isCritArrow() ? CRIT.get() : ARROW.get();
        if (e instanceof ThrownEnderpearl) return PEARL.get();
        if (e instanceof AbstractThrownPotion potion && POTION_OWN.get()) {
            PotionContents contents = potion.getItem().getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
            return contents.getColor() & 0xFFFFFF;
        }
        return OTHER.get();
    }

    static void render3D(Render3D render, boolean arrowGroup) {
        Map<Integer, Trail> trails = arrowGroup ? arrows : thrown;
        if (trails.isEmpty()) return;
        long now = System.nanoTime();
        double length = (arrowGroup ? ARROW_LENGTH : LENGTH).get() * 1e9;
        float width = (arrowGroup ? ARROW_WIDTH : WIDTH).get().floatValue();
        float partial = render.partialTick();
        for (Trail trail : trails.values()) {
            Point prev = null;
            float prevAlpha = 0;
            for (Point p : trail.points) {
                float alpha = (float) Math.max(0, 1 - (now - p.time) / length);
                if (prev != null) {
                    render.line(prev.at, p.at, Visuals.argb(trail.rgb, prevAlpha * 0.85), Visuals.argb(trail.rgb, alpha * 0.85), width, true);
                }
                prev = p;
                prevAlpha = alpha;
            }
            // On to where it is this frame, so the line keeps up with it between ticks.
            if (prev != null && !trail.entity.isRemoved() && !stopped(trail.entity)) {
                render.line(prev.at, trail.entity.getPosition(partial), Visuals.argb(trail.rgb, prevAlpha * 0.85), Visuals.argb(trail.rgb, 0.85), width, true);
            }
        }
    }
}
