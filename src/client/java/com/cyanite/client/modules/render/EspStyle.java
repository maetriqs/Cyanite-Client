package com.cyanite.client.modules.render;

import com.cyanite.client.render.Render3D;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.Setting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.world.phys.AABB;

import java.util.List;

/** The look settings ESP and Storage ESP share, and the drawing that uses them. */
public final class EspStyle {
    private final ModeSetting shape = new ModeSetting("Shape", "Box", List.of("Box", "Outline", "Fill"));
    private final SliderSetting fill = new SliderSetting("Fill opacity", 0.18, 0.05, 0.6, 0.01).percent();
    private final SliderSetting width = new SliderSetting("Line width", 2, 1, 5, 0.5);
    private final BoolSetting glow = new BoolSetting("Glow", true);
    private final BoolSetting tracers;

    public EspStyle(boolean tracersByDefault) {
        tracers = new BoolSetting("Tracers", tracersByDefault);
    }

    public List<Setting<?>> settings() {
        return List.of(shape, fill, width, glow, tracers);
    }

    public void draw(Render3D render, AABB box, int rgb) {
        int fillColor = shape.is("Outline") ? 0 : Math.round(fill.get().floatValue() * 255) << 24 | rgb;
        int lineColor = shape.is("Fill") ? 0 : 0xFF000000 | rgb;
        float lineWidth = width.get().floatValue();

        render.box(box, fillColor, lineColor, lineWidth, glow.get());
        if (tracers.get()) render.tracer(box.getCenter(), 0xC0000000 | rgb, Math.max(1, lineWidth - 0.5f), glow.get());
    }
}
