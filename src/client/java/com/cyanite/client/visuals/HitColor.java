package com.cyanite.client.visuals;

import com.cyanite.client.mixin.visuals.OverlayTextureAccessor;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.SliderSetting;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;

/**
 * The colour mobs and players flash when they're hurt, in place of the game's red, and a quick wash over
 * your screen when you land a hit. The hurt colour is the game's own overlay texture, rewritten.
 */
public final class HitColor {
    public static final Group GROUP = new Group("hit_color", "Custom Hit Colors",
        "The colour players and mobs flash when they're hit, and a flash on your screen when you land one.",
        "hit color hurt red flash damage entity overlay");
    static final ColorSetting COLOR = GROUP.add(new ColorSetting("Hurt color", 0x4DE1FF).full());
    static final SliderSetting INTENSITY = GROUP.add(new SliderSetting("Intensity", 0.4, 0.05, 1, 0.05).percent());
    static final BoolSetting FLASH = GROUP.add(new BoolSetting("Screen flash on your hits", false));
    static final ColorSetting FLASH_COLOR = GROUP.add(new ColorSetting("Flash color", 0xFFFFFF).full());
    static final SliderSetting FLASH_STRENGTH = GROUP.add(new SliderSetting("Flash strength", 0.15, 0.05, 0.6, 0.05).percent());

    /** What the game puts in the hurt rows: red, 70% the entity's own colour. */
    private static final int VANILLA = 0xB2FF0000;
    private static int applied = VANILLA;

    private HitColor() {
    }

    /** The hurt colour as the overlay texture holds it (alpha is how much of the entity's own colour stays). */
    static int overlay() {
        if (!GROUP.on()) return VANILLA;
        return Visuals.argb(COLOR.get(), 1 - INTENSITY.get());
    }

    static void tick() {
        int want = overlay();
        if (want == applied) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameRenderer == null) return;
        DynamicTexture texture = ((OverlayTextureAccessor) mc.gameRenderer.overlayTexture()).cyanite$texture();
        NativeImage pixels = texture.getPixels();
        if (pixels == null) return;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 16; x++) pixels.setPixel(x, y, want);
        }
        texture.upload();
        applied = want;
    }

    static void onAttack() {
        if (GROUP.on() && FLASH.get()) WorldFx.flash(FLASH_COLOR.get(), FLASH_STRENGTH.get().floatValue(), 0.2f);
    }
}
