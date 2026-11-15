package com.cyanite.client.visuals;

import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.SliderSetting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

import java.io.InputStream;
import java.util.Optional;

/**
 * The shimmer on enchanted items and armour in a colour of yours, brighter or fainter, faster or slower.
 * The game's glint pictures are swapped for recoloured copies while it's on, and given back when it's off.
 */
public final class Glint {
    public static final Group GROUP = new Group("glint", "Enchantment Glint",
        "Colour, strength and speed of the shimmer on enchanted items and armour.", "enchantment glint shimmer foil enchanted color");
    static final ColorSetting COLOR = GROUP.add(new ColorSetting("Color", 0xB04DFF).full());
    static final SliderSetting INTENSITY = GROUP.add(new SliderSetting("Intensity", 1, 0.1, 2, 0.05).percent());
    static final SliderSetting SPEED = GROUP.add(new SliderSetting("Speed", 1, 0.1, 4, 0.05).format(Particles::times));
    static final BoolSetting ARMOR = GROUP.add(new BoolSetting("Armor too", true));

    private static final Identifier[] TEXTURES = {ItemRenderer.ENCHANTED_GLINT_ITEM, ItemRenderer.ENCHANTED_GLINT_ARMOR};
    /** What's swapped in now, as colour, intensity and armour packed together; 0 when the game's own are in. */
    private static long baked;
    private static boolean armorBaked;

    private Glint() {
    }

    /** Once a tick: swap the pictures in or out as the settings say. */
    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getResourceManager() == null || RenderSystem.tryGetDevice() == null) return;
        if (!GROUP.on()) {
            if (baked != 0) restore();
            return;
        }
        long key = 1L << 62 | (long) COLOR.get() << 16 | Math.round(INTENSITY.get() * 100) << 1 | (ARMOR.get() ? 1 : 0);
        if (key == baked) return;
        bake(TEXTURES[0], true);
        if (ARMOR.get()) {
            bake(TEXTURES[1], true);
        } else if (armorBaked) {
            mc.getTextureManager().release(TEXTURES[1]);
        }
        armorBaked = ARMOR.get();
        baked = key;
    }

    private static void restore() {
        var textures = Minecraft.getInstance().getTextureManager();
        textures.release(TEXTURES[0]);
        if (armorBaked) textures.release(TEXTURES[1]);
        armorBaked = false;
        baked = 0;
    }

    /** Reads the game's own picture from the resource packs and registers a recoloured copy under its name. */
    private static void bake(Identifier id, boolean smooth) {
        Minecraft mc = Minecraft.getInstance();
        Optional<Resource> resource = mc.getResourceManager().getResource(id);
        if (resource.isEmpty()) return;
        NativeImage img;
        try (InputStream in = resource.get().open()) {
            img = NativeImage.read(in);
        } catch (Exception e) {
            Visuals.LOGGER.warn("Visuals: couldn't read {}", id, e);
            return;
        }
        int rgb = COLOR.get();
        float intensity = INTENSITY.get().floatValue();
        float cr = (rgb >> 16 & 0xFF) / 255f;
        float cg = (rgb >> 8 & 0xFF) / 255f;
        float cb = (rgb & 0xFF) / 255f;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int argb = img.getPixel(x, y);
                // The picture's brightness, carried over in the new colour (the glint adds light, so brighter = stronger).
                float lum = Math.max(argb >> 16 & 0xFF, Math.max(argb >> 8 & 0xFF, argb & 0xFF)) / 255f * intensity;
                int r = Math.min(255, Math.round(cr * lum * 255));
                int g = Math.min(255, Math.round(cg * lum * 255));
                int b = Math.min(255, Math.round(cb * lum * 255));
                img.setPixel(x, y, argb & 0xFF000000 | r << 16 | g << 8 | b);
            }
        }
        DynamicTexture texture = new DynamicTexture(() -> "cyanite_client:glint", img) {
            {
                if (smooth) sampler = RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR);
            }
        };
        texture.upload();
        mc.getTextureManager().register(id, texture);
    }

    /** The clock the glint scrolls by, sped up or slowed down. */
    public static long millis(long millis) {
        return GROUP.on() ? (long) (millis * SPEED.get()) : millis;
    }

    /** For the preview: the colour as it shows. */
    static int previewColor() {
        return Visuals.argb(COLOR.get(), Math.min(1, 0.45 * INTENSITY.get()));
    }
}
