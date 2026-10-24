package com.cyanite.client.modules.world;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.SliderSetting;

import java.util.Locale;

/** Speeds up (or slows down) the client's game clock. Hooked in from TimerMixin. */
public class Timer extends Module {
    private final SliderSetting speed = add(new SliderSetting("Speed", 1.5, 0.1, 5, 0.05).format(v -> String.format(Locale.ROOT, "%.2fx", v)));

    public Timer() {
        super(Category.WORLD, "Timer", "Changes the game speed");
    }

    @Override
    public String getInfo() {
        return speed.display();
    }

    public float multiplier() {
        return isEnabled() ? speed.get().floatValue() : 1f;
    }
}
