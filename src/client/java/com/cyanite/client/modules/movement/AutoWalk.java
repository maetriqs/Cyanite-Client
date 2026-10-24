package com.cyanite.client.modules.movement;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Keys;
import com.cyanite.client.system.Module;

public class AutoWalk extends Module {
    public AutoWalk() {
        super(Category.MOVEMENT, "Auto Walk", "Holds the forward key for you");
    }

    @Override
    public void onTick() {
        mc.options.keyUp.setDown(true);
    }

    @Override
    protected void onDisable() {
        if (inGame()) Keys.restore(mc.options.keyUp);
    }
}
