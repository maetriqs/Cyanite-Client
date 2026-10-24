package com.cyanite.client.modules.movement;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.ModeSetting;

import java.util.List;

public class Sprint extends Module {
    private final ModeSetting mode = add(new ModeSetting("Mode", "Legit", List.of("Legit", "Omni")));

    public Sprint() {
        super(Category.MOVEMENT, "Sprint", "Sprints automatically");
    }

    @Override
    public String getInfo() {
        return mode.get();
    }

    @Override
    public void onTick() {
        if (mc.player.isShiftKeyDown() || mc.player.isSprinting()) return;
        if (mc.player.getFoodData().getFoodLevel() <= 6 && !mc.player.getAbilities().mayfly) return;

        boolean moving = mode.is("Omni")
            ? mc.player.zza != 0 || mc.player.xxa != 0
            : mc.player.zza > 0 && !mc.player.horizontalCollision && !mc.player.isUsingItem();

        if (moving) mc.player.setSprinting(true);
    }
}
