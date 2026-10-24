package com.cyanite.client.modules.render;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.SliderSetting;

import java.util.Locale;

/** Shrinks the totem that flies up your screen when one pops, so it doesn't hide the fight. 0 hides it. */
public class SmallTotem extends Module {
    private final SliderSetting size = add(new SliderSetting("Size", 0.35, 0, 1, 0.05)
        .format(v -> v == 0 ? "Hidden" : String.format(Locale.ROOT, "%.0f%%", v * 100)));

    public SmallTotem() {
        super(Category.RENDER, "Small Totem", "Shrinks the totem pop animation so you can still see");
    }

    @Override
    public String getInfo() {
        return size.display();
    }

    /** How much smaller the totem pop is drawn; 1 when off. */
    public float factor() {
        return isEnabled() ? size.get().floatValue() : 1;
    }
}
