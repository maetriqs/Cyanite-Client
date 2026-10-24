package com.cyanite.client.modules.world;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.SliderSetting;

import java.util.Locale;

/**
 * Breaks each block faster: every tick of holding the button counts for more. A vanilla server
 * takes the break once it's 70% done by its own count, so up to about 1.4x always gets through.
 * Its count also runs through the game's five-tick pause before each next block, so with Fast
 * Break off about 2x gets through too (stone with a wooden pickaxe: 18 ticks instead of 28).
 * Past that the server breaks it on its own time, and the block shows gone before it is.
 */
public class FastMine extends Module {
    private final SliderSetting speed = add(new SliderSetting("Speed", 1.3, 1, 3, 0.05).format(v -> String.format(Locale.ROOT, "%.2fx", v)));

    public FastMine() {
        super(Category.WORLD, "Fast Mine", "Breaks blocks faster: up to 1.4x gets past a vanilla server, about 2x with Fast Break off");
    }

    @Override
    public boolean anticheatDownOnly() {
        return true;
    }

    @Override
    public String getInfo() {
        return speed.display();
    }

    /** A tick's breaking progress, sped up when on. */
    public float speedUp(float progress) {
        return isEnabled() ? progress * speed.get().floatValue() : progress;
    }
}
