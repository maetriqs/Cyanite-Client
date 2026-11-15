package com.cyanite.client.visuals;

import com.cyanite.client.system.setting.SliderSetting;

/** The flames over your screen while you burn: lower down, and fainter, so they cover less of it. */
public final class LowFire {
    public static final Group GROUP = new Group("low_fire", "Low Fire",
        "Lowers and fades the fire over your screen while you're burning.", "fire burning flames overlay low screen");
    static final SliderSetting HEIGHT = GROUP.add(new SliderSetting("Height", 0.45, 0, 1, 0.05).percent());
    static final SliderSetting OPACITY = GROUP.add(new SliderSetting("Opacity", 0.6, 0.05, 1, 0.05).percent());

    private LowFire() {
    }

    /** How far down the flames move from where the game puts them (full height 0). */
    public static float drop() {
        if (!GROUP.on()) return 0;
        return (float) (1 - HEIGHT.get()) * 0.5f;
    }

    /** The flames' opacity, from the game's 0.9. */
    public static float alpha(float alpha) {
        return GROUP.on() ? alpha * OPACITY.get().floatValue() / 0.9f : alpha;
    }
}
