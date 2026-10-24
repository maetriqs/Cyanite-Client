package com.cyanite.client.modules.player;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.client.gui.screens.DeathScreen;

public class AutoRespawn extends Module {
    private final SliderSetting delay = add(new SliderSetting("Delay", 0, 0, 60, 1).format(v -> (int) v + " ticks"));

    private int waited;

    public AutoRespawn() {
        super(Category.PLAYER, "Auto Respawn", "Respawns as soon as you die");
    }

    @Override
    public void onTick() {
        if (!(mc.screen instanceof DeathScreen)) {
            waited = 0;
            return;
        }
        if (waited++ < delay.get()) return;

        mc.player.respawn();
        mc.setScreen(null);
        waited = 0;
    }
}
