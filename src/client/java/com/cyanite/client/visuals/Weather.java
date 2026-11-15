package com.cyanite.client.visuals;

import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.client.renderer.state.WeatherRenderState;

/** Rain and snow: either one off, the splashes off, or all of it fainter. Only how it looks; the weather's the same. */
public final class Weather {
    public static final Group GROUP = new Group("weather", "Weather Visuals",
        "Turn rain, snow and rain splashes on or off, or fade them.", "weather rain snow splash particles storm");
    static final BoolSetting RAIN = GROUP.add(new BoolSetting("Rain", true));
    static final BoolSetting SNOW = GROUP.add(new BoolSetting("Snow", true));
    static final BoolSetting SPLASHES = GROUP.add(new BoolSetting("Rain splashes", true));
    static final SliderSetting OPACITY = GROUP.add(new SliderSetting("Opacity", 1, 0.1, 1, 0.05).percent());

    private Weather() {
    }

    /** Before the rain and snow are drawn. */
    public static void beforeRender(WeatherRenderState state) {
        if (!GROUP.on()) return;
        if (!RAIN.get()) state.rainColumns.clear();
        if (!SNOW.get()) state.snowColumns.clear();
        state.intensity *= OPACITY.get().floatValue();
    }

    /** Whether the splash and drip particles where rain lands are made. */
    public static boolean splashes() {
        return !GROUP.on() || SPLASHES.get() && RAIN.get();
    }
}
