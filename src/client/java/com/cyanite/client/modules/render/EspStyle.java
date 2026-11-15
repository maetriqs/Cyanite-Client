package com.cyanite.client.modules.render;

import com.cyanite.client.render.EspGlow;
import com.cyanite.client.render.Render3D;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.Setting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import java.util.List;

/** The look settings the ESPs share, and the drawing that uses them. */
public final class EspStyle {
    /** How much of the fill is left at the top of a box with Fill fade on. */
    private static final float FADE_TOP = 0.15f;

    private final ModeSetting shape;
    private final SliderSetting fill = new SliderSetting("Fill opacity", 0.18, 0.05, 0.6, 0.01).percent();
    private final BoolSetting fade = new BoolSetting("Fill fade", true);
    private final SliderSetting width = new SliderSetting("Line width", 2, 1, 5, 0.5);
    private final BoolSetting glow = new BoolSetting("Glow", true);
    private final BoolSetting tracers;

    /** For blocks, which can't have a Silhouette. */
    public EspStyle(boolean tracersByDefault) {
        this(tracersByDefault, false);
    }

    /** @param entities offers Silhouette too: the entity's own outline instead of a box */
    public EspStyle(boolean tracersByDefault, boolean entities) {
        shape = new ModeSetting("Shape", "Box", entities
            ? List.of("Box", "Corners", "Outline", "Fill", "Silhouette")
            : List.of("Box", "Corners", "Outline", "Fill"));
        tracers = new BoolSetting("Tracers", tracersByDefault);
    }

    /** The shape's name, for the arraylist. */
    public String shape() {
        return shape.get();
    }

    public List<Setting<?>> settings() {
        return List.of(shape, fill, fade, width, glow, tracers);
    }

    public void draw(Render3D render, AABB box, int rgb) {
        int alpha = shape.is("Outline") ? 0 : Math.round(fill.get().floatValue() * 255);
        int bottom = alpha << 24 | rgb;
        // Fading toward the top grounds the box and keeps what's inside it easy to see.
        int top = Math.round(alpha * (fade.get() ? FADE_TOP : 1)) << 24 | rgb;
        int line = shape.is("Fill") ? 0 : 0xFF000000 | rgb;
        float lineWidth = width.get().floatValue();

        if (shape.is("Corners")) render.corners(box, bottom, top, line, lineWidth, glow.get());
        else render.box(box, bottom, top, line, lineWidth, glow.get());
        tracer(render, box, rgb);
    }

    /** An entity's box, or with Silhouette its own outline. */
    public void draw(Render3D render, Entity entity, AABB box, int rgb) {
        if (!shape.is("Silhouette")) {
            draw(render, box, rgb);
            return;
        }
        EspGlow.mark(entity, rgb);
        tracer(render, box, rgb);
    }

    private void tracer(Render3D render, AABB box, int rgb) {
        if (tracers.get()) render.tracer(box.getCenter(), 0xD0000000 | rgb, Math.max(1, width.get().floatValue() - 0.5f), glow.get());
    }
}
