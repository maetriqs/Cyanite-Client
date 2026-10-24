package com.cyanite.client.system;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;

/** Movement keys that walk the player to a point whichever way they're facing, the way a person strafes. */
public final class Steer {
    private Steer() {
    }

    /** Forward, back, strafe or a diagonal towards {@code target}, plus jump and sneak as asked. */
    public static Input toward(LocalPlayer player, Vec3 target, boolean jump, boolean sneak) {
        double dx = target.x - player.getX();
        double dz = target.z - player.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.05) return new Input(false, false, false, false, jump, sneak, false);
        dx /= len;
        dz /= len;
        double yaw = Math.toRadians(player.getYRot());
        double forward = dx * -Math.sin(yaw) + dz * Math.cos(yaw);
        double left = dx * Math.cos(yaw) + dz * Math.sin(yaw);
        return new Input(forward > 0.38, forward < -0.38, left > 0.38, left < -0.38, jump, sneak, false);
    }
}
