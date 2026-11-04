package com.cyanite.client.render;

import com.cyanite.client.gui.Theme;
import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.modules.misc.StreamerMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** A label over a spot in the world: "Sus chunk · 312m", and when you look at it, where and why. */
public final class Badge {
    private Badge() {
    }

    /** {@code screen} is where the spot is on screen ({@link Projection#toScreen}); {@code c} the spot, for the distance and where. */
    public static void draw(GuiGraphics g, String title, Vec3 c, String reasons, int rgb, double[] screen, boolean focused) {
        var mc = Minecraft.getInstance();
        int scale = Render2D.scale();
        String dist = Math.round(Math.hypot(c.x - mc.player.getX(), c.z - mc.player.getZ())) + "m";
        List<String> lines = new ArrayList<>();
        if (focused) {
            lines.add(StreamerMode.mask(Math.round(c.x) + ", " + Math.round(c.z)));
            lines.add(reasons);
        }
        int pad = 4;
        int line = Text.CAP + 4;
        int w = Text.width(title) + 6 + Text.width(dist);
        for (String l : lines) w = Math.max(w, Text.width(l));
        w += pad * 2;
        int h = pad * 2 + Text.CAP + 1 + lines.size() * line;
        int x = (int) Math.round(screen[0] / scale) - w / 2;
        int y = (int) Math.round(screen[1] / scale) - h - 6;
        float a = focused ? 1 : 0.85f;
        Render2D.pixels(g);
        Render2D.glow(g, x * scale, y * scale, w * scale, h * scale, 3 * scale, 6 * scale, Theme.alpha(rgb, (focused ? 90 : 50) * Theme.glow()));
        Render2D.rect(g, x * scale, y * scale, w * scale, h * scale, 3 * scale, Theme.fade(Theme.BORDER, a));
        Render2D.rect(g, x * scale + 1, y * scale + 1, w * scale - 2, h * scale - 2, 3 * scale - 1, Theme.fade(0xE6101217, a));
        Render2D.rect(g, x * scale + 3 * scale, y * scale + 1, w * scale - 6 * scale, Math.max(1, scale / 2), 0, rgb);
        Render2D.end(g);
        Text.draw(g, title, x + pad, y + pad + 1, Theme.fade(rgb, a));
        Text.draw(g, dist, x + pad + Text.width(title) + 6, y + pad + 1, Theme.TEXT_FAINT);
        int ly = y + pad + 1 + line;
        for (String l : lines) {
            Text.draw(g, l, x + pad, ly, Theme.TEXT_DIM);
            ly += line;
        }
    }
}
