package com.cyanite.client.visuals;

import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;

import java.util.List;

/** A wash of colour over the screen when you're hurt, fading as the hurt does; and a pulse when you're low. */
public final class DamageTint {
    public static final Group GROUP = new Group("damage_tint", "Damage Tint",
        "Colours the screen when you take damage, and pulses it while your health is low.", "damage hurt tint red screen flash low health");
    static final ColorSetting COLOR = GROUP.add(new ColorSetting("Color", 0xFF2A2A).full());
    static final SliderSetting INTENSITY = GROUP.add(new SliderSetting("Intensity", 0.45, 0.05, 1, 0.05).percent());
    static final ModeSetting STYLE = GROUP.add(new ModeSetting("Style", "Edges", List.of("Edges", "Full screen")));
    static final SliderSetting LENGTH = GROUP.add(new SliderSetting("Length", 1, 0.3, 3, 0.1).format(Particles::times));
    static final BoolSetting LOW_HEALTH = GROUP.add(new BoolSetting("Pulse at low health", false));
    static final SliderSetting LOW_AT = GROUP.add(new SliderSetting("Low health at", 6, 1, 14, 1).format(v -> Math.round(v) / 2.0 + " hearts"));

    /** When the last hurt started, and how strong it is now. */
    private static long hurtAt;
    private static int lastHurtTime;

    private DamageTint() {
    }

    static void render(GuiGraphics g, float partial) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player.hurtTime > lastHurtTime) hurtAt = System.nanoTime();
        lastHurtTime = player.hurtTime;
        float seconds = 0.5f * LENGTH.get().floatValue();
        float t = (System.nanoTime() - hurtAt) / 1e9f / seconds;
        float strength = t < 1 ? (1 - t) * (1 - t) : 0;
        if (LOW_HEALTH.get() && player.isAlive() && player.getHealth() <= LOW_AT.get()) {
            float pulse = 0.5f + 0.5f * (float) Math.sin(System.nanoTime() / 1e9 * Math.PI * 1.6);
            strength = Math.max(strength, 0.35f + 0.35f * pulse);
        }
        if (strength <= 0.003f) return;
        draw(g, 0, 0, g.guiWidth(), g.guiHeight(), strength * INTENSITY.get().floatValue());
    }

    /** The tint over (x, y, w, h) at {@code a} (0 to 1). Also the tab's preview. */
    static void draw(GuiGraphics g, int x, int y, int w, int h, float a) {
        int rgb = COLOR.get();
        if (STYLE.is("Full screen")) {
            g.fill(x, y, x + w, y + h, Visuals.argb(rgb, a * 0.7));
            return;
        }
        int edge = Visuals.argb(rgb, Math.min(1, a * 1.1));
        int none = Visuals.argb(rgb, 0);
        int bandX = Math.max(8, w / 4);
        int bandY = Math.max(8, h / 4);
        g.fillGradient(x, y, x + w, y + bandY, edge, none);
        g.fillGradient(x, y + h - bandY, x + w, y + h, none, edge);
        side(g, x, y, bandX, h, edge, none);
        side(g, x + w - bandX, y, bandX, h, none, edge);
    }

    /** A blend from {@code left} to {@code right} across (x, y, w, h): the game's top-to-bottom gradient, turned on its side. */
    private static void side(GuiGraphics g, int x, int y, int w, int h, int left, int right) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        // A quarter turn back: down the gradient becomes rightwards across the screen.
        g.pose().rotate((float) (-Math.PI / 2));
        g.fillGradient(-h, 0, 0, w, left, right);
        g.pose().popMatrix();
    }
}
