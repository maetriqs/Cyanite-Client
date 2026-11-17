package com.cyanite.client.visuals;

import com.cyanite.client.render.Render3D;
import com.cyanite.client.system.WorldScan;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Arrows, pearls and potions in the air: a glowing box round each, and where pearls (and, if you like, the rest)
 * will come down, worked out from how they fly.
 */
public final class ProjectileFx {
    public static final Group GROUP = new Group("projectile_fx", "Projectile Effects",
        "A glowing box round arrows, pearls and potions in flight, and a marker where pearls will land.",
        "projectile effect arrow pearl potion landing predict box glow");
    static final ColorSetting COLOR = GROUP.add(new ColorSetting("Color", 0xFFE14D).full());
    static final BoolSetting BOXES = GROUP.add(new BoolSetting("Boxes in flight", true));
    static final BoolSetting LANDING = GROUP.add(new BoolSetting("Where pearls land", true));
    static final BoolSetting ALL_LANDINGS = GROUP.add(new BoolSetting("Where everything lands", false));
    static final ColorSetting LANDING_COLOR = GROUP.add(new ColorSetting("Landing color", 0x3DFFB0).full());
    static final SliderSetting RANGE = GROUP.add(new SliderSetting("Range", 48, 8, 96, 1));

    private static final int MAX_PATHS = 8;
    private static final int STEPS = 160;

    private ProjectileFx() {
    }

    static void render3D(Render3D render) {
        Minecraft mc = Minecraft.getInstance();
        double range = RANGE.get();
        float partial = render.partialTick();
        int rgb = COLOR.get();
        int paths = 0;
        for (Entity e : WorldScan.entities()) {
            if (!(e instanceof Projectile) || e.isRemoved() || e.distanceToSqr(mc.player) > range * range) continue;
            if (!(e instanceof AbstractArrow) && !(e instanceof ThrowableItemProjectile)) continue;
            Vec3 motion = e.getDeltaMovement();
            if (motion.lengthSqr() < 1e-4) continue;
            if (BOXES.get()) {
                Vec3 at = e.getPosition(partial);
                AABB box = e.getBoundingBox().move(at.subtract(e.position())).inflate(0.08);
                render.box(box, Visuals.argb(rgb, 0.18), Visuals.argb(rgb, 0.9), 1.8f, true);
            }
            boolean pearl = e instanceof ThrownEnderpearl;
            if ((pearl && LANDING.get() || ALL_LANDINGS.get()) && paths < MAX_PATHS) {
                paths++;
                landing(render, e, motion);
            }
        }
    }

    /** Follows {@code e}'s flight tick by tick (gravity, then drag, then the move, as the game does) until it hits a block. */
    private static void landing(Render3D render, Entity e, Vec3 motion) {
        Minecraft mc = Minecraft.getInstance();
        double gravity = e instanceof AbstractArrow || e instanceof AbstractThrownPotion ? 0.05 : 0.03;
        int rgb = LANDING_COLOR.get();
        Vec3 pos = e.position();
        Vec3 v = motion;
        for (int i = 0; i < STEPS; i++) {
            v = v.add(0, -gravity, 0).scale(0.99);
            Vec3 next = pos.add(v);
            BlockHitResult hit = mc.level.clip(new ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, e));
            if (hit.getType() != HitResult.Type.MISS) {
                render.line(pos, hit.getLocation(), Visuals.argb(rgb, 0.7), 1.5f, false);
                Vec3 land = hit.getLocation();
                AABB marker = new AABB(land.x - 0.35, land.y - 0.02, land.z - 0.35, land.x + 0.35, land.y + 0.05, land.z + 0.35);
                render.box(marker, Visuals.argb(rgb, 0.3), Visuals.argb(rgb, 1), 2f, true);
                // A soft pulse of light where it lands, so the spot shows from far off.
                double pulse = 0.5 + 0.5 * Math.sin(System.nanoTime() / 1.6e8);
                render.glow(land.add(0, 0.15, 0), 0.7 + 0.25 * pulse, Visuals.argb(rgb, 0.35 + 0.25 * pulse), true);
                return;
            }
            if (i % 2 == 0) render.line(pos, next, Visuals.argb(rgb, 0.55), 1.5f, false);
            pos = next;
            if (pos.y < mc.level.getMinY() - 8) return;
        }
    }
}
