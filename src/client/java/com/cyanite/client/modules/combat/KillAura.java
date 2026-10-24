package com.cyanite.client.modules.combat;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.WorldScan;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class KillAura extends Module {
    private final SliderSetting range = add(new SliderSetting("Range", 3.5, 1, 6, 0.1));
    private final ModeSetting timing = add(new ModeSetting("Timing", "Cooldown", List.of("Cooldown", "CPS")));
    private final SliderSetting cps = add(new SliderSetting("CPS", 10, 1, 20, 1));
    private final ModeSetting priority = add(new ModeSetting("Priority", "Distance", List.of("Distance", "Health", "Angle")));
    private final BoolSetting players = add(new BoolSetting("Players", true));
    private final BoolSetting hostiles = add(new BoolSetting("Hostiles", true));
    private final BoolSetting passives = add(new BoolSetting("Passives", false));
    private final BoolSetting invisibles = add(new BoolSetting("Invisibles", false));
    private final BoolSetting throughWalls = add(new BoolSetting("Through walls", false));
    private final BoolSetting rotate = add(new BoolSetting("Rotate", true));
    private final BoolSetting weaponOnly = add(new BoolSetting("Weapon only", false));
    private final BoolSetting pauseOnUse = add(new BoolSetting("Pause while eating", true));

    private long nextAttack;

    public KillAura() {
        super(Category.COMBAT, "Kill Aura", "Attacks nearby entities");
    }

    @Override
    public String getInfo() {
        return priority.get();
    }

    @Override
    public void onTick() {
        if (mc.screen != null) return;
        if (pauseOnUse.get() && (mc.player.isUsingItem() || AutoGap.busy())) return;
        if (weaponOnly.get() && !isWeapon(mc.player.getMainHandItem())) return;

        LivingEntity target = findTarget();
        if (target == null) return;

        if (rotate.get()) face(target);
        if (!ready()) return;

        mc.gameMode.attack(mc.player, target);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    private boolean ready() {
        if (timing.is("Cooldown")) return mc.player.getAttackStrengthScale(0.5f) >= 1f;

        long now = System.currentTimeMillis();
        if (now < nextAttack) return false;
        // A little jitter so clicks aren't perfectly even.
        double interval = 1000.0 / cps.get();
        nextAttack = now + Math.round(interval * ThreadLocalRandom.current().nextDouble(0.85, 1.15));
        return true;
    }

    private LivingEntity findTarget() {
        Comparator<LivingEntity> order = switch (priority.get()) {
            case "Health" -> Comparator.comparingDouble(LivingEntity::getHealth);
            case "Angle" -> Comparator.comparingDouble(this::angleTo);
            default -> Comparator.comparingDouble(this::reachDistance);
        };

        LivingEntity best = null;
        for (Entity entity : WorldScan.entities()) {
            if (!(entity instanceof LivingEntity living) || !isTarget(living)) continue;
            if (best == null || order.compare(living, best) < 0) best = living;
        }
        return best;
    }

    private boolean isTarget(LivingEntity entity) {
        if (entity == mc.player || !entity.isAlive() || entity.getHealth() <= 0) return false;
        if (AntiBot.isBot(entity) || com.cyanite.client.modules.misc.Pets.isPet(entity)) return false;
        if (entity instanceof ArmorStand) return false;
        if (reachDistance(entity) > range.get()) return false;
        if (entity.isInvisible() && !invisibles.get()) return false;
        if (!throughWalls.get() && !mc.player.hasLineOfSight(entity)) return false;

        if (entity instanceof Player player) return players.get() && !player.isSpectator() && !player.isCreative();
        if (entity instanceof Enemy) return hostiles.get();
        return passives.get();
    }

    /** Distance from the eyes to the nearest point of the hitbox, which is what reach is measured against. */
    private double reachDistance(Entity entity) {
        Vec3 eyes = mc.player.getEyePosition();
        AABB box = entity.getBoundingBox();
        double x = Mth.clamp(eyes.x, box.minX, box.maxX);
        double y = Mth.clamp(eyes.y, box.minY, box.maxY);
        double z = Mth.clamp(eyes.z, box.minZ, box.maxZ);
        return eyes.distanceTo(new Vec3(x, y, z));
    }

    private double angleTo(Entity entity) {
        float[] wanted = rotationTo(entity);
        return Math.abs(Mth.wrapDegrees(wanted[0] - mc.player.getYRot())) + Math.abs(wanted[1] - mc.player.getXRot());
    }

    private void face(Entity entity) {
        float[] wanted = rotationTo(entity);
        mc.player.setYRot(wanted[0]);
        mc.player.setXRot(wanted[1]);
    }

    private float[] rotationTo(Entity entity) {
        Vec3 eyes = mc.player.getEyePosition();
        Vec3 aim = entity.getBoundingBox().getCenter();
        double dx = aim.x - eyes.x;
        double dy = aim.y - eyes.y;
        double dz = aim.z - eyes.z;
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        return new float[]{Mth.wrapDegrees(yaw), Mth.clamp(pitch, -90f, 90f)};
    }

    private static boolean isWeapon(ItemStack stack) {
        return stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES);
    }
}
