package com.cyanite.client.modules.render;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.SliderSetting;

/** The third-person camera (F5): further out than vanilla's 4 blocks, and through walls instead of bumping into them. */
public class F5Camera extends Module {
    private final SliderSetting distance = add(new SliderSetting("Distance", 8, 1, 30, 0.5).format(v -> v + " blocks"));
    private final BoolSetting throughWalls = add(new BoolSetting("Through walls", true));

    public F5Camera() {
        super(Category.RENDER, "F5 Camera", "Third person goes further out and straight through walls");
    }

    @Override
    public String getInfo() {
        return distance.display();
    }

    /** The camera distance to use in place of vanilla's 4 blocks (times the player's scale). */
    public float distance(float vanilla) {
        return isEnabled() ? vanilla / 4f * distance.get().floatValue() : vanilla;
    }

    public boolean clips() {
        return isEnabled() && throughWalls.get();
    }
}
