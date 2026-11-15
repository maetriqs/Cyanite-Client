package com.cyanite.client.visuals;

import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;

import java.util.List;

/**
 * Your own crosshair in place of the game's: its shape, size, thickness, gap and colour. Sizes are in
 * pixels on a 1080p screen and grow with the window. The attack indicator under it stays the game's.
 * Each visual profile keeps its own, so every PvP mode can have a different one.
 */
public final class Crosshair {
    public static final Group GROUP = new Group("crosshair", "Custom Crosshair",
        "Your own crosshair shape, size, thickness, gap and colour, per profile.", "crosshair reticle aim cross dot circle gap");
    static final ModeSetting SHAPE = GROUP.add(new ModeSetting("Shape", "Cross",
        List.of("Cross", "Dot", "Circle", "Cross + Dot", "Circle + Dot", "T", "Square", "X")));
    static final SliderSetting SIZE = GROUP.add(new SliderSetting("Size", 7, 1, 30, 1));
    static final SliderSetting THICKNESS = GROUP.add(new SliderSetting("Thickness", 2, 1, 8, 1));
    static final SliderSetting GAP = GROUP.add(new SliderSetting("Gap", 3, 0, 20, 1));
    static final ColorSetting COLOR = GROUP.add(new ColorSetting("Color", 0x4DE1FF).full());
    static final SliderSetting OPACITY = GROUP.add(new SliderSetting("Opacity", 1, 0.1, 1, 0.05).percent());
    static final BoolSetting OUTLINE = GROUP.add(new BoolSetting("Outline", true));
    static final BoolSetting DYNAMIC = GROUP.add(new BoolSetting("Spread while moving", false));
    static final BoolSetting COOLDOWN = GROUP.add(new BoolSetting("Dim while recharging", false));

    private static float spread;

    private Crosshair() {
    }

    /** In place of the game's crosshair sprite: true when it's been drawn here. */
    public static boolean render(GuiGraphics g) {
        if (!GROUP.on()) return false;
        Minecraft mc = Minecraft.getInstance();
        int scale = mc.getWindow().getGuiScale();
        float k = mc.getWindow().getHeight() / 1080f;
        int color = COLOR.get();
        float alpha = OPACITY.get().floatValue();
        LocalPlayer player = mc.player;
        if (player != null && COOLDOWN.get()) alpha *= 0.35f + 0.65f * player.getAttackStrengthScale(0);
        float target = 0;
        if (player != null && DYNAMIC.get()) {
            double speed = player.getDeltaMovement().horizontalDistance();
            target = (float) Math.min(8, speed * 30) + (player.onGround() ? 0 : 3);
        }
        spread += (target - spread) * 0.2f;

        g.pose().pushMatrix();
        g.pose().scale(1f / scale);
        int cx = mc.getWindow().getWidth() / 2;
        int cy = mc.getWindow().getHeight() / 2;
        draw(g, cx, cy, k, Visuals.argb(color, alpha), spread);
        g.pose().popMatrix();
        return true;
    }

    /**
     * Draws it centred on ({@code cx, cy}) in whatever units the pose is in, {@code k} of them per 1080p pixel.
     * Also used by the Visuals tab's preview.
     */
    public static void draw(GuiGraphics g, float cx, float cy, float k, int argb, float extraGap) {
        float t = Math.max(1, Math.round(THICKNESS.get() * k));
        float size = Math.max(1, Math.round(SIZE.get() * k));
        float gap = Math.round((GAP.get() + extraGap) * k);
        // Thickness centred on the middle pixel: odd widths sit on it, even ones a half pixel off, like the game's.
        float x0 = Math.round(cx - t / 2);
        float y0 = Math.round(cy - t / 2);
        String shape = SHAPE.get();
        if (OUTLINE.get()) {
            Shapes back = Shapes.of(g, Visuals.argb(0, (argb >>> 24) / 255f * 0.75f));
            parts(back, shape, x0, y0, t, size, gap, Math.max(1, Math.round(k)));
            back.submit();
        }
        Shapes front = Shapes.of(g, argb);
        parts(front, shape, x0, y0, t, size, gap, 0);
        front.submit();
    }

    /** Every rectangle of the shape, grown by {@code grow} on all sides (for the outline under it). */
    private static void parts(Shapes s, String shape, float x0, float y0, float t, float size, float gap, float grow) {
        float x1 = x0 + t;
        float y1 = y0 + t;
        boolean dot = shape.equals("Dot") || shape.endsWith("+ Dot");
        switch (shape) {
            case "Cross", "Cross + Dot", "T" -> {
                if (!shape.equals("T")) s.rect(x0 - grow, y0 - gap - size - grow, x1 + grow, y0 - gap + grow);
                s.rect(x0 - grow, y1 + gap - grow, x1 + grow, y1 + gap + size + grow);
                s.rect(x0 - gap - size - grow, y0 - grow, x0 - gap + grow, y1 + grow);
                s.rect(x1 + gap - grow, y0 - grow, x1 + gap + size + grow, y1 + grow);
            }
            case "Circle", "Circle + Dot" -> {
                float r = gap + size / 2 + t;
                int steps = Math.max(16, Math.round(r * 3));
                float cx = (x0 + x1) / 2;
                float cy = (y0 + y1) / 2;
                for (int i = 0; i < steps; i++) {
                    double a = Math.PI * 2 * i / steps;
                    float px = Math.round(cx + Math.cos(a) * r - t / 2);
                    float py = Math.round(cy + Math.sin(a) * r - t / 2);
                    s.rect(px - grow, py - grow, px + t + grow, py + t + grow);
                }
            }
            case "Square" -> {
                float h = gap + size / 2;
                float l = x0 - h;
                float r = x1 + h;
                float top = y0 - h;
                float b = y1 + h;
                s.rect(l - grow, top - grow, r + grow, top + t + grow);
                s.rect(l - grow, b - t - grow, r + grow, b + grow);
                s.rect(l - grow, top - grow, l + t + grow, b + grow);
                s.rect(r - t - grow, top - grow, r + grow, b + grow);
            }
            case "X" -> {
                // Diagonal arms, a square of the thickness for each pixel along them.
                for (int i = Math.round(gap); i < gap + size; i++) {
                    for (int sx = -1; sx <= 1; sx += 2) {
                        for (int sy = -1; sy <= 1; sy += 2) {
                            float px = x0 + sx * i;
                            float py = y0 + sy * i;
                            s.rect(px - grow, py - grow, px + t + grow, py + t + grow);
                        }
                    }
                }
            }
            default -> {
            }
        }
        if (dot) {
            float d = Math.max(t, 2);
            float dx = Math.round((x0 + x1) / 2 - d / 2);
            float dy = Math.round((y0 + y1) / 2 - d / 2);
            s.rect(dx - grow, dy - grow, dx + d + grow, dy + d + grow);
        }
    }
}
