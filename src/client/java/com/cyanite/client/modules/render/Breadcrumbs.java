package com.cyanite.client.modules.render;

import com.cyanite.client.render.Render3D;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Iterator;

/** Leaves a fading trail behind you. */
public class Breadcrumbs extends Module {
    private static final double SPACING_SQ = 0.25 * 0.25;

    private final SliderSetting length = add(new SliderSetting("Length", 500, 50, 2000, 50).format(v -> (int) v + " points"));
    private final ColorSetting color = add(new ColorSetting("Color", 0x4C9BFF));
    private final SliderSetting width = add(new SliderSetting("Line width", 2, 1, 5, 0.5));
    private final BoolSetting glow = add(new BoolSetting("Glow", true));

    private final ArrayDeque<Vec3> points = new ArrayDeque<>();
    private Level level;

    public Breadcrumbs() {
        super(Category.RENDER, "Breadcrumbs", "Draws the path you've walked");
    }

    @Override
    protected void onDisable() {
        points.clear();
    }

    @Override
    public void onTick() {
        // A trail from another dimension would just be a line to nowhere.
        if (mc.level != level) {
            points.clear();
            level = mc.level;
        }

        Vec3 here = mc.player.position().add(0, 0.05, 0);
        if (points.isEmpty() || points.getLast().distanceToSqr(here) > SPACING_SQ) points.addLast(here);
        while (points.size() > length.get()) points.removeFirst();
    }

    @Override
    public void onRender3D(Render3D render) {
        if (points.size() < 2) return;

        int rgb = color.get();
        int count = points.size();
        Iterator<Vec3> it = points.iterator();
        Vec3 last = it.next();
        for (int i = 1; it.hasNext(); i++) {
            Vec3 next = it.next();
            // Older points fade out towards the tail.
            int alpha = 40 + Math.round(215f * i / count);
            render.line(last, next, alpha << 24 | rgb, width.get().floatValue(), glow.get());
            last = next;
        }
    }
}
