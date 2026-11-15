package com.cyanite.client.visuals;

import com.cyanite.client.gui.render.Quads;
import com.cyanite.client.gui.render.Render2D;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;

/**
 * Plain filled rectangles for the Visuals HUD pieces, batched: many of them in one colour make one piece
 * for the game's GUI (see {@link Quads}), so a ring of dots costs what one box does.
 */
final class Shapes {
    private static Identifier white;

    private final GuiGraphics g;
    private final Quads quads;

    private Shapes(GuiGraphics g, int argb) {
        this.g = g;
        this.quads = Quads.of(g, white(), argb);
    }

    static Shapes of(GuiGraphics g, int argb) {
        return new Shapes(g, argb);
    }

    Shapes rect(float x0, float y0, float x1, float y1) {
        quads.add(Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1), 0.5f, 0.5f, 0.5f, 0.5f);
        return this;
    }

    void submit() {
        quads.submit(g);
    }

    private static Identifier white() {
        if (white == null) {
            NativeImage img = new NativeImage(2, 2, false);
            img.fillRect(0, 0, 2, 2, 0xFFFFFFFF);
            white = Render2D.upload("visuals_white", img);
        }
        return white;
    }
}
