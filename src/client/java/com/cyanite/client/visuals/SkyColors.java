package com.cyanite.client.visuals;

import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributes;

/**
 * The sky, the fog along the horizon and the clouds in colours of your own. They still follow the day: as dark
 * as the game's own colours at night, and part way there at dusk.
 */
public final class SkyColors {
    public static final Group GROUP = new Group("sky_colors", "Sky Colors",
        "Paint the sky, the fog on the horizon and the clouds your own colours. They still get dark at night.",
        "sky fog clouds color colour tint horizon atmosphere purple pink aesthetic");
    static final BoolSetting SKY = GROUP.add(new BoolSetting("Sky", true));
    static final ColorSetting SKY_COLOR = GROUP.add(new ColorSetting("Sky color", 0x7B5CFF).full());
    static final BoolSetting FOG = GROUP.add(new BoolSetting("Fog", true));
    static final ColorSetting FOG_COLOR = GROUP.add(new ColorSetting("Fog color", 0xFF8FC8).full());
    static final BoolSetting CLOUDS = GROUP.add(new BoolSetting("Clouds", true));
    static final ColorSetting CLOUD_COLOR = GROUP.add(new ColorSetting("Cloud color", 0xFFE0F2).full());
    static final SliderSetting STRENGTH = GROUP.add(new SliderSetting("Strength", 1, 0.1, 1, 0.05).percent());
    static final BoolSetting DAY_NIGHT = GROUP.add(new BoolSetting("Darker at night", true));

    private SkyColors() {
    }

    /** What the camera reads for {@code attribute}, {@code value} being the game's. */
    public static Object value(EnvironmentAttribute<?> attribute, Object value) {
        if (attribute != EnvironmentAttributes.SKY_COLOR && attribute != EnvironmentAttributes.FOG_COLOR
            && attribute != EnvironmentAttributes.CLOUD_COLOR) return value;
        if (!GROUP.on() || !(value instanceof Integer game)) return value;
        if (attribute == EnvironmentAttributes.SKY_COLOR) return SKY.get() ? tint(game, SKY_COLOR.get()) : value;
        if (attribute == EnvironmentAttributes.FOG_COLOR) return FOG.get() ? tint(game, FOG_COLOR.get()) : value;
        return CLOUDS.get() ? tint(game, CLOUD_COLOR.get()) : value;
    }

    /** {@code rgb} in place of the game's colour, as bright as the game's is now, mixed in by Strength. */
    static int tint(int game, int rgb) {
        int want = rgb;
        if (DAY_NIGHT.get()) {
            // The game's colour is at full brightness by day and close to black at night.
            float light = Math.max(game >> 16 & 0xFF, Math.max(game >> 8 & 0xFF, game & 0xFF)) / 255f;
            want = Visuals.lerp(0xFF000000, 0xFF000000 | rgb, light);
        }
        return game & 0xFF000000 | Visuals.lerp(0xFF000000 | game, 0xFF000000 | want, STRENGTH.get().floatValue()) & 0xFFFFFF;
    }
}
