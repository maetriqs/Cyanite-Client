package com.cyanite.client.modules.combat;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.WorldScan;
import com.cyanite.client.system.HumanAim;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Targets;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * While you draw a bow (or hold a loaded crossbow), aims where the arrow will actually land on the
 * target: it flies a simulated arrow with the game's own drag and gravity, and leads moving targets
 * by how far they'll go while it's in the air.
 */
public class BowAimbot extends Module {
    private static final double GRAVITY = 0.05;
    private static final double DRAG = 0.99;
    private static final double CROSSBOW_SPEED = 3.15;

    private final SliderSetting range = add(new SliderSetting("Range", 60, 10, 120, 5).format(v -> (int) v + "m"));
    private final SliderSetting fov = add(new SliderSetting("FOV", 120, 10, 360, 10).format(v -> (int) v + "°"));
    private final BoolSetting players = add(new BoolSetting("Players", true));
    private final BoolSetting hostiles = add(new BoolSetting("Hostiles", true));
    private final BoolSetting passives = add(new BoolSetting("Passives", false));
    private final BoolSetting predict = add(new BoolSetting("Lead targets", true));
    private final BoolSetting smooth = add(new BoolSetting("Smooth", true));
    private final SliderSetting speed = add(new SliderSetting("Speed", 1.5, 0.3, 3, 0.1).format(v -> String.format(Locale.ROOT, "%.1fx", v)));
    private final BoolSetting throughWalls = add(new BoolSetting("Through walls", false));

    private final HumanAim aim = new HumanAim();
    private Entity target;

    public BowAimbot() {
        super(Category.COMBAT, "Bow Aimbot", "Aims your bow where the arrow will hit");
    }

    @Override
    protected void onDisable() {
        target = null;
        aim.clear();
    }

    @Override
    public void onFrame() {
        double arrowSpeed = arrowSpeed();
        if (mc.screen != null || arrowSpeed <= 0) {
            release();
            return;
        }

        float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        Entity best = null;
        double bestAngle = fov.get() / 2;
        for (Entity entity : WorldScan.entities()) {
            if (!(entity instanceof LivingEntity) || !Targets.isTarget(entity, players.get(), hostiles.get(), passives.get())) continue;
            if (entity.distanceTo(mc.player) > range.get()) continue;
            if (!throughWalls.get() && !mc.player.hasLineOfSight(entity)) continue;
            double angle = Targets.angleTo(entity.getBoundingBox().getCenter());
            if (angle < bestAngle) {
                best = entity;
                bestAngle = angle;
            }
        }
        if (best == null) {
            release();
            return;
        }

        Vec3 eye = mc.player.getEyePosition(partialTick);
        Vec3 aimAt = best.getPosition(partialTick).add(0, best.getBbHeight() * 0.5, 0);
        Vec3 velocity = new Vec3(best.getX() - best.xo, best.getY() - best.yo, best.getZ() - best.zo);
        double[] shot = solve(eye, aimAt, arrowSpeed);
        // Lead the target: where it'll be after the flight time, and solve again for that spot.
        if (predict.get()) {
            for (int i = 0; i < 2; i++) {
                Vec3 ahead = aimAt.add(velocity.x * shot[2], best.onGround() ? 0 : velocity.y * shot[2], velocity.z * shot[2]);
                shot = solve(eye, ahead, arrowSpeed);
            }
        }

        float yaw = (float) shot[0];
        float pitch = (float) shot[1];
        if (!smooth.get()) {
            mc.player.setYRot(yaw);
            mc.player.setXRot(pitch);
            target = best;
            return;
        }
        Vec3 look = Vec3.directionFromRotation(pitch, yaw).scale(10).add(mc.player.getEyePosition());
        if (best != target) aim.setTarget(look);
        else aim.track(look);
        target = best;
        aim.speed = speed.get();
        aim.randomness = 0;
        aim.frame();
    }

    private void release() {
        target = null;
        aim.clear();
        aim.idle();
    }

    /** How fast an arrow would leave right now, or 0 if you aren't about to shoot one. */
    private double arrowSpeed() {
        if (mc.player.isUsingItem() && mc.player.getUseItem().getItem() instanceof BowItem) {
            double power = BowItem.getPowerForTime(mc.player.getTicksUsingItem()) * 3;
            return power >= 0.3 ? power : 0;
        }
        ItemStack main = mc.player.getMainHandItem();
        if (main.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(main)) return CROSSBOW_SPEED;
        ItemStack off = mc.player.getOffhandItem();
        if (off.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(off)) return CROSSBOW_SPEED;
        return 0;
    }

    /** {yaw, pitch, flight ticks} for the low arc that lands on {@code target}. */
    private static double[] solve(Vec3 eye, Vec3 target, double speed) {
        double dx = target.x - eye.x;
        double dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double dy = target.y - eye.y;
        double yaw = Mth.wrapDegrees(Math.toDegrees(Math.atan2(dz, dx)) - 90);

        // Height at the target's distance only climbs with the angle up to the farthest shot, so bisect.
        double low = -89;
        double high = 45;
        double ticks = 0;
        for (int i = 0; i < 32; i++) {
            double mid = (low + high) / 2;
            double[] flight = fly(mid, speed, horizontal);
            if (Double.isNaN(flight[0]) || flight[0] < dy) {
                low = mid;
            } else {
                high = mid;
                ticks = flight[1];
            }
        }
        double elevation = (low + high) / 2;
        if (ticks == 0) ticks = fly(elevation, speed, horizontal)[1];
        return new double[]{yaw, -elevation, Double.isNaN(ticks) ? 0 : ticks};
    }

    /** {height, ticks} when an arrow fired at {@code elevation} degrees reaches {@code distance}; NaN if it never does. */
    private static double[] fly(double elevation, double speed, double distance) {
        double rad = Math.toRadians(elevation);
        double vx = Math.cos(rad) * speed;
        double vy = Math.sin(rad) * speed;
        double x = 0;
        double y = 0;
        for (int tick = 1; tick <= 400; tick++) {
            double nx = x + vx;
            double ny = y + vy;
            if (nx >= distance) {
                double t = vx == 0 ? 1 : (distance - x) / vx;
                return new double[]{y + vy * t, tick - 1 + t};
            }
            x = nx;
            y = ny;
            vx *= DRAG;
            vy = vy * DRAG - GRAVITY;
            if (y < -200) break;
        }
        return new double[]{Double.NaN, Double.NaN};
    }
}
