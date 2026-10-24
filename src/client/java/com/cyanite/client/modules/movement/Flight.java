package com.cyanite.client.modules.movement;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

public class Flight extends Module {
    private static final float DEFAULT_FLY_SPEED = 0.05f;

    private final ModeSetting mode = add(new ModeSetting("Mode", "Velocity", List.of("Velocity", "Vanilla")));
    private final SliderSetting speed = add(new SliderSetting("Speed", 1, 0.1, 5, 0.1).format(v -> String.format(Locale.ROOT, "%.1fx", v)));
    private final SliderSetting vertical = add(new SliderSetting("Vertical speed", 0.6, 0.1, 3, 0.1));
    private final BoolSetting antiKick = add(new BoolSetting("Anti kick", true));

    private int ticks;

    public Flight() {
        super(Category.MOVEMENT, "Flight", "Lets you fly");
        mode.onChange(this::restoreAbilities);
    }

    @Override
    public String getInfo() {
        return mode.get();
    }

    @Override
    public void onTick() {
        ticks++;

        if (mode.is("Vanilla")) {
            Abilities abilities = mc.player.getAbilities();
            abilities.flying = true;
            abilities.setFlyingSpeed(DEFAULT_FLY_SPEED * speed.get().floatValue());
        } else {
            mc.player.setDeltaMovement(inputVelocity());
        }

        // Vanilla servers kick after a few seconds of hovering; dipping slightly every two seconds resets that.
        if (antiKick.get() && ticks % 40 == 0 && !mc.player.onGround()) {
            Vec3 motion = mc.player.getDeltaMovement();
            mc.player.setDeltaMovement(motion.x, Math.min(motion.y, 0) - 0.04, motion.z);
        }
    }

    private Vec3 inputVelocity() {
        double horizontal = 0.55 * speed.get();
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

    @Override
    protected void onDisable() {
        restoreAbilities();
        if (inGame() && mode.is("Velocity")) mc.player.setDeltaMovement(Vec3.ZERO);
    }

    private void restoreAbilities() {
        if (!inGame()) return;
        Abilities abilities = mc.player.getAbilities();
        abilities.setFlyingSpeed(DEFAULT_FLY_SPEED);
        if (!mc.player.isCreative() && !mc.player.isSpectator()) abilities.flying = false;
    }
}
