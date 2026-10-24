package com.cyanite.client.modules.render;

import com.cyanite.client.gui.Theme;
import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.KeybindSetting;
import com.cyanite.client.system.setting.SliderSetting;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

/**
 * Zoom: hold the key and the view zooms in, like a spyglass you don't have to carry. While it's
 * held, the scroll wheel zooms further in or back out instead of changing your hotbar slot, and
 * the mouse slows down to match so aiming stays steady. Let go and it eases back out. Your hand
 * isn't zoomed, only the world.
 *
 * <p>GameRendererMixin divides the field of view by {@link #fovDivisor()}, MouseHandlerMixin hands
 * the scroll wheel to {@link #onScroll} while zoomed, and EntityMixin slows your mouse turns by
 * {@link #turnScale()}.
 */
public class Zoom extends Module {
    /** Each notch of the scroll wheel zooms by this much. */
    private static final double PER_NOTCH = 1.2;
    /** How fast it eases toward the zoom it's heading for. */
    private static final double EASE = 14;
    /** How long the readout stays up after the zoom changes, in ticks. */
    private static final int READOUT_TICKS = 32;

    private final KeybindSetting key = add(new KeybindSetting("Zoom key", GLFW.GLFW_KEY_C));
    private final SliderSetting level = add(new SliderSetting("Zoom", 4, 1.5, 10, 0.5).format(Zoom::times));
    private final SliderSetting most = add(new SliderSetting("Max zoom", 50, 10, 100, 5).format(Zoom::times));
    private final BoolSetting scroll = add(new BoolSetting("Scroll to zoom", true));
    private final BoolSetting remember = add(new BoolSetting("Remember scroll", false));
    private final BoolSetting smooth = add(new BoolSetting("Smooth", true));
    private final BoolSetting slowMouse = add(new BoolSetting("Slower mouse", true));
    private final BoolSetting cinematic = add(new BoolSetting("Cinematic camera", false));
    private final BoolSetting readout = add(new BoolSetting("Show zoom", true));

    /** The zoom while the key's held: the Zoom setting to start with, then wherever the scroll wheel takes it. */
    private double wanted = -1;
    /** The zoom on screen right now, easing toward where it's heading. */
    private double shown = 1;
    private long lastFrame;
    private boolean held;
    /** The tick the zoom last changed (key down, or the wheel), for the readout. */
    private int changedAt;
    private boolean cinematicWas;

    public Zoom() {
        super(Category.RENDER, "Zoom", "Hold a key to zoom in, scroll to zoom further");
    }

    private static String times(double v) {
        return (v == Math.rint(v) ? Integer.toString((int) v) : String.format(Locale.ROOT, "%.1f", v)) + "x";
    }

    @Override
    public String getInfo() {
        return key.display();
    }

    @Override
    protected void onDisable() {
        if (held) letGo();
    }

    /** What the field of view gets divided by this frame: 1 when not zoomed. */
    public static double fovDivisor() {
        Zoom zoom = Modules.get(Zoom.class);
        return zoom == null ? 1 : zoom.frame();
    }

    /** The scroll wheel while zoomed zooms instead. @return true if it took the scroll, so the hotbar doesn't */
    public static boolean onScroll(double notches) {
        Zoom zoom = Modules.get(Zoom.class);
        if (zoom == null || !zoom.held || !zoom.scroll.get() || notches == 0) return false;
        zoom.wanted = Mth.clamp(zoom.wanted * Math.pow(PER_NOTCH, notches), 1, zoom.most.get());
        zoom.changedAt = zoom.tick();
        return true;
    }

    /** How much to slow your mouse turns: in step with the zoom on screen, so a flick covers the same ground in view. */
    public static double turnScale() {
        Zoom zoom = Modules.get(Zoom.class);
        return zoom == null || !zoom.slowMouse.get() || zoom.shown <= 1 ? 1 : 1 / zoom.shown;
    }

    /** Works out this frame's zoom: picks up the key going down or up and eases the view toward where it's heading. */
    private double frame() {
        long now = System.nanoTime();
        double dt = lastFrame == 0 ? 0 : Math.min((now - lastFrame) / 1e9, 0.1);
        lastFrame = now;

        boolean down = isEnabled() && mc.screen == null && mc.player != null && key.isBound()
            && InputConstants.isKeyDown(mc.getWindow(), key.get());
        if (down && !held) {
            held = true;
            if (!remember.get() || wanted < 1) wanted = level.get();
            changedAt = tick();
            if (cinematic.get()) {
                cinematicWas = mc.options.smoothCamera;
                mc.options.smoothCamera = true;
            }
        } else if (!down && held) {
            letGo();
        }

        double target = held ? wanted : 1;
        if (!smooth.get() || dt == 0 && shown == 1 && !held) {
            shown = target;
        } else {
            // Eased by ratio, not by difference: 2x to 4x feels the same as 20x to 40x.
            double step = 1 - Math.exp(-dt * EASE);
            shown = Math.exp(Math.log(shown) + (Math.log(target) - Math.log(shown)) * step);
            if (Math.abs(shown - target) < 0.002) shown = target;
        }
        return shown;
    }

    private int tick() {
        return mc.player == null ? 0 : mc.player.tickCount;
    }

    private void letGo() {
        held = false;
        if (cinematic.get()) mc.options.smoothCamera = cinematicWas;
    }

    /** The zoom, under the crosshair for a moment after it starts or the wheel changes it. */
    @Override
    public void onRender2D(GuiGraphics g, float partialTick) {
        if (!readout.get() || !held || mc.screen != null) return;
        float age = tick() - changedAt + partialTick;
        if (age > READOUT_TICKS) return;
        float alpha = age < READOUT_TICKS * 0.7f ? 1 : 1 - (age - READOUT_TICKS * 0.7f) / (READOUT_TICKS * 0.3f);
        String label = times(Math.round(wanted * 10) / 10.0);
        int s = Render2D.scale();
        int w = Text.width(label) + 10;
        int h = Text.CAP + 8;
        // In HUD units: the screen's middle is where the crosshair is.
        int x = com.cyanite.client.hud.Hud.screenWidth() / 2 - w / 2;
        int y = com.cyanite.client.hud.Hud.screenHeight() / 2 + 12;
        Render2D.pixels(g);
        Render2D.rect(g, x * s, y * s, w * s, h * s, h * s / 2, Theme.fade(0xE0101217, alpha));
        Render2D.end(g);
        Text.draw(g, label, x + 5, y + 4, Theme.fade(Theme.accent(), alpha));
    }
}
