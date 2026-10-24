package com.cyanite.client.modules.world;

import com.cyanite.client.mixin.MultiPlayerGameModeAccessor;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.SliderSetting;

public class FastBreak extends Module {
    private final SliderSetting delay = add(new SliderSetting("Delay", 0, 0, 4, 1).format(v -> (int) v + " ticks"));

    public FastBreak() {
        super(Category.WORLD, "Fast Break", "Removes the pause between breaking blocks");
    }

    @Override
    public void onTick() {
        // Vanilla waits 5 ticks after a block breaks before starting on the next.
        MultiPlayerGameModeAccessor gameMode = (MultiPlayerGameModeAccessor) mc.gameMode;
        int max = delay.get().intValue();
        if (gameMode.cyanite$getDestroyDelay() > max) gameMode.cyanite$setDestroyDelay(max);
    }
}
