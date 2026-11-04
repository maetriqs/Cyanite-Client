package com.cyanite.client.modules.render;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.SliderSetting;

/**
 * Your field of view, from 10 to 170 degrees instead of vanilla's 30 to 110. It takes the place of
 * the FOV in Options; sprinting, Speed and drawing a bow still widen or narrow it unless Steady is
 * on, and Zoom narrows it from there. The hand keeps vanilla's fixed 70.
 */
public class FovChanger extends Module {
    private final SliderSetting fov = add(new SliderSetting("FOV", 100, 10, 170, 1).format(v -> (int) v + "°"));
    private final BoolSetting steady = add(new BoolSetting("Steady", false));

    public FovChanger() {
        super(Category.RENDER, "FOV Changer", "Sets your field of view anywhere from 10 to 170 degrees, past vanilla's 30 to 110");
    }

    @Override
    public String getInfo() {
        return fov.display();
    }

    /** The FOV to use in place of the one in Options. */
    public static int fov(int vanilla) {
        FovChanger changer = Modules.get(FovChanger.class);
        return changer != null && changer.isEnabled() ? changer.fov.get().intValue() : vanilla;
    }

    /** Sprinting, Speed and bows leave the FOV alone. */
    public static boolean steady() {
        FovChanger changer = Modules.get(FovChanger.class);
        return changer != null && changer.isEnabled() && changer.steady.get();
    }
}
