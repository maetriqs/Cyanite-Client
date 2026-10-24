package com.cyanite.client.modules.movement;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.world.phys.Vec3;

public class Spider extends Module {
    private final SliderSetting speed = add(new SliderSetting("Speed", 0.2, 0.1, 0.6, 0.05));

    public Spider() {
        super(Category.MOVEMENT, "Spider", "Climbs walls");
    }

    @Override
    public void onTick() {
        if (!mc.player.horizontalCollision || (mc.player.zza == 0 && mc.player.xxa == 0)) return;
        Vec3 motion = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(motion.x, speed.get(), motion.z);
    }
}
