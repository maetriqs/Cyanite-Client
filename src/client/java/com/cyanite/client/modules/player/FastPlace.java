package com.cyanite.client.modules.player;

import com.cyanite.client.mixin.MinecraftAccessor;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.SliderSetting;

public class FastPlace extends Module {
    private final SliderSetting delay = add(new SliderSetting("Delay", 0, 0, 3, 1).format(v -> (int) v + " ticks"));

    public FastPlace() {
        super(Category.PLAYER, "Fast Place", "Removes the delay between right clicks");
    }

    @Override
    public void onTick() {
        // Vanilla waits 4 ticks between uses while the button is held.
        MinecraftAccessor accessor = (MinecraftAccessor) mc;
        int max = delay.get().intValue();
        if (accessor.cyanite$getRightClickDelay() > max) accessor.cyanite$setRightClickDelay(max);
    }
}
