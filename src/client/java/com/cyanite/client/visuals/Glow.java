package com.cyanite.client.visuals;

import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.SliderSetting;

/** The glow round trails, rings, outlines and lights, everywhere at once: how bright, how wide, and the white cores. */
public final class Glow {
    public static final Group GROUP = new Group("glow", "Glow",
        "Turn the glow round trails, rings, outlines and lights up or down, wider or tighter.",
        "glow bloom bright neon light halo shine soft");
    static final SliderSetting STRENGTH = GROUP.add(new SliderSetting("Strength", 1.5, 0, 3, 0.1).format(Particles::times));
    static final SliderSetting SIZE = GROUP.add(new SliderSetting("Size", 1.25, 0.5, 2.5, 0.05).format(Particles::times));
    static final BoolSetting CORES = GROUP.add(new BoolSetting("White cores", true));

    private Glow() {
    }

    /** How bright glows are, 1 when the group is off. */
    public static float strength() {
        return GROUP.on() ? STRENGTH.get().floatValue() : 1;
    }

    /** How far the glow round edges and lines reaches, 1 when the group is off. */
    public static float size() {
        return GROUP.on() ? SIZE.get().floatValue() : 1;
    }

    /** Whether glowing lines get a whiter core along the middle. */
    public static boolean cores() {
        return !GROUP.on() || CORES.get();
    }
}
