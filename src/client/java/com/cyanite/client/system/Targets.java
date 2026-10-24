package com.cyanite.client.system;

import com.cyanite.client.modules.combat.AntiBot;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Who counts as a target, and the geometry combat modules need to reach them. */
public final class Targets {
    private static final Minecraft mc = Minecraft.getInstance();

    private Targets() {
    }

    /** A living, attackable entity of a kind the caller wants, never yourself or a bot. */
    public static boolean isTarget(Entity entity, boolean players, boolean hostiles, boolean passives) {
        if (!(entity instanceof LivingEntity living) || entity == mc.player) return false;
        if (!living.isAlive() || living.getHealth() <= 0 || entity instanceof ArmorStand) return false;
        if (AntiBot.isBot(entity) || com.cyanite.client.modules.misc.Pets.isPet(entity)) return false;
        if (entity instanceof Player player) return players && !player.isSpectator() && !player.isCreative();
        if (entity instanceof Enemy) return hostiles;
        return passives;
    }

    /** Distance from your eyes to the nearest point of the hitbox, which is what reach is measured against. */
    public static double reach(Entity entity) {
        Vec3 eyes = mc.player.getEyePosition();
        AABB box = entity.getBoundingBox();
        double x = Mth.clamp(eyes.x, box.minX, box.maxX);
        double y = Mth.clamp(eyes.y, box.minY, box.maxY);
        double z = Mth.clamp(eyes.z, box.minZ, box.maxZ);
        return eyes.distanceTo(new Vec3(x, y, z));
    }

    /** Yaw and pitch that look from your eyes at {@code point}. */
    public static float[] rotationTo(Vec3 point) {
        Vec3 eyes = mc.player.getEyePosition();
        double dx = point.x - eyes.x;
        double dy = point.y - eyes.y;
        double dz = point.z - eyes.z;
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        return new float[]{Mth.wrapDegrees(yaw), Mth.clamp(pitch, -90f, 90f)};
    }

    /** How far, in degrees, {@code point} is from where you're looking. */
    public static double angleTo(Vec3 point) {
        float[] wanted = rotationTo(point);
        return Math.hypot(Mth.wrapDegrees(wanted[0] - mc.player.getYRot()), wanted[1] - mc.player.getXRot());
    }

    /** The middle of the chest, where a person would aim. */
    public static Vec3 chest(Entity entity, float partialTick) {
        return entity.getPosition(partialTick).add(0, entity.getBbHeight() * 0.6, 0);
    }
}
