package com.cyanite.client.modules.movement;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Culling;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * Fly through blocks like a spectator: no collisions, nothing pushing you out of walls, no
 * dark in-wall overlay, and no fall damage. Steer with your movement keys, jump to rise and
 * sneak to sink.
 *
 * <p>It works by switching off the player's physics where the game resets them each tick
 * (PlayerMixin). In singleplayer the built-in server is told the same about your player, so it
 * lets you through and you don't suffocate. A multiplayer server checks movement itself and
 * will pull you back out of the wall.
 */
public class NoClip extends Module {
    @Override
    public boolean anticheatDownOnly() {
        return true;
    }

    private final SliderSetting speed = add(new SliderSetting("Speed", 1, 0.1, 5, 0.1).format(v -> String.format(Locale.ROOT, "%.1fx", v)));
    private final SliderSetting vertical = add(new SliderSetting("Vertical speed", 0.5, 0.1, 3, 0.1));

    public NoClip() {
        super(Category.MOVEMENT, "No Clip", "Fly through walls (singleplayer; servers pull you back)");
    }

    /**
     * Whether {@code player} should have no physics this tick: you on the client, and, on the
     * built-in singleplayer server, the server's copy of you.
     */
    public static boolean appliesTo(Player player) {
        NoClip noClip = Modules.get(NoClip.class);
        if (noClip == null || !noClip.isEnabled() || mc.player == null) return false;
        if (player == mc.player) return true;
        IntegratedServer server = mc.getSingleplayerServer();
        return server != null && player instanceof ServerPlayer && player.getUUID().equals(mc.player.getUUID());
    }

    @Override
    protected void onEnable() {
        // From inside a block, occlusion culling thinks everything is hidden and stops drawing chunks.
        Culling.hold(this);
    }

    @Override
    protected void onDisable() {
        Culling.release(this);
        if (inGame()) {
            mc.player.noPhysics = false;
            mc.player.setDeltaMovement(Vec3.ZERO);
        }
    }

    @Override
    public void onTick() {
        mc.player.noPhysics = true;
        mc.player.resetFallDistance();
        mc.player.setDeltaMovement(velocity());
    }

    /** Where your keys point you: forward, back and strafe along your view, jump and sneak for up and down. */
    private Vec3 velocity() {
        double horizontal = 0.5 * speed.get();
        double forward = mc.player.zza;
        double strafe = mc.player.xxa;
        double x = 0;
        double z = 0;
        if (forward != 0 || strafe != 0) {
            double yaw = Math.toRadians(mc.player.getYRot());
            double length = Math.sqrt(forward * forward + strafe * strafe);
            forward /= length;
            strafe /= length;
            x = (-Math.sin(yaw) * forward + Math.cos(yaw) * strafe) * horizontal;
            z = (Math.cos(yaw) * forward + Math.sin(yaw) * strafe) * horizontal;
        }
        double y = 0;
        if (mc.options.keyJump.isDown()) y += vertical.get();
        if (mc.options.keyShift.isDown()) y -= vertical.get();
        return new Vec3(x, y, z);
    }
}
