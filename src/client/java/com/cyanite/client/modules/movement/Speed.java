package com.cyanite.client.modules.movement;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

public class Speed extends Module {
    @Override
    public boolean anticheatDownOnly() {
        return true;
    }

    // Roughly vanilla sprinting speed on the ground, in blocks per tick.
    private static final double BASE = 0.2873;

    private final ModeSetting mode = add(new ModeSetting("Mode", "Strafe", List.of("Strafe", "Ground")));
    private final SliderSetting speed = add(new SliderSetting("Speed", 1.4, 1, 3, 0.05).format(v -> String.format(Locale.ROOT, "%.2fx", v)));
    private final SliderSetting airControl = add(new SliderSetting("Air control", 0.95, 0.5, 1, 0.05).percent());

    public Speed() {
        super(Category.MOVEMENT, "Speed", "Moves faster");
    }

    @Override
    public String getInfo() {
        return mode.get();
    }

    @Override
    public void onTick() {
        if (mc.player.getAbilities().flying || mc.player.isFallFlying() || mc.player.isPassenger()) return;
        if (mc.player.isInWater() || mc.player.isInLava() || mc.player.isShiftKeyDown()) return;
        if (mc.player.zza == 0 && mc.player.xxa == 0) return;

        double target = BASE * speed.get();

        if (mc.player.onGround()) {
            // Strafe bunny-hops; the jump also stops ground friction eating the speed.
            if (mode.is("Strafe")) mc.player.jumpFromGround();
            setHorizontal(target);
        } else if (mode.is("Strafe")) {
            setHorizontal(target * airControl.get());
        }
    }

    private void setHorizontal(double amount) {
        double forward = mc.player.zza;
        double strafe = mc.player.xxa;
        double length = Math.sqrt(forward * forward + strafe * strafe);
        forward /= length;
        strafe /= length;

        double yaw = Math.toRadians(mc.player.getYRot());
        double x = (-Math.sin(yaw) * forward + Math.cos(yaw) * strafe) * amount;
        double z = (Math.cos(yaw) * forward + Math.sin(yaw) * strafe) * amount;

        Vec3 motion = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(x, motion.y, z);
    }
}
