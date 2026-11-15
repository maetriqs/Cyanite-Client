package com.cyanite.client.hud;

import com.cyanite.client.gui.Theme;
import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.modules.misc.StreamerMode;
import com.cyanite.client.system.setting.SliderSetting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Base Alert's picture of a base it's just found, taken from a camera of its own (CameraShot) placed
 * so the whole base is in frame, with where it is and what's there. Slides in, stays a while, fades.
 */
public class BasePicture extends HudElement {
    private static final Logger LOGGER = LoggerFactory.getLogger("Cyanite");
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("cyanite_client", "base_picture");
    private static final float SLIDE = 0.3f;
    private static final int PAD = 5;
    private static final int LINE = Text.CAP + 4;

    private final SliderSetting size = add(new SliderSetting("Width", 200, 120, 360, 10).format(v -> (int) v + ""));
    private final SliderSetting time = add(new SliderSetting("Show for", 15, 3, 60, 1).format(v -> (int) v + "s"));

    private record Shown(int pictureWidth, int pictureHeight, Vec3 at, String contents, ClientLevel level) {
    }

    private static BasePicture instance;
    private static Shown shown;
    private static float age;

    public BasePicture() {
        super("Base Picture", true, Horizontal.LEFT, Vertical.CENTER, 4, 0);
        instance = this;
    }

    /**
     * Shows {@code picture} (and closes it): Base Alert's shot of the base around {@code at}. Shrunk to
     * size, and saved full size to {@code saveTo} if given, off the render thread.
     */
    public static void show(NativeImage picture, Vec3 at, String contents, Path saveTo) {
        Minecraft mc = Minecraft.getInstance();
        if (instance == null || !instance.isEnabled()) {
            picture.close();
            return;
        }
        int width = (int) instance.size.get().doubleValue() * Hud.unit();
        ClientLevel level = mc.level;
        Thread worker = new Thread(() -> {
            NativeImage small;
            try (picture) {
                if (saveTo != null) save(picture, saveTo);
                small = shrink(picture, width);
            }
            mc.execute(() -> {
                if (mc.level != level) {
                    small.close();
                    return;
                }
                mc.getTextureManager().register(TEXTURE, new Picture(small));
                shown = new Shown(small.getWidth(), small.getHeight(), at, contents, level);
                age = 0;
            });
        }, "Cyanite Base Picture");
        worker.setDaemon(true);
        worker.start();
    }

    private static void save(NativeImage picture, Path file) {
        try {
            Files.createDirectories(file.getParent());
            picture.writeToFile(file);
        } catch (IOException e) {
            LOGGER.warn("Base Alert: couldn't save the picture", e);
        }
    }

    /** Box-filtered down to {@code width} pixels across (never up). */
    private static NativeImage shrink(NativeImage from, int width) {
        int sw = from.getWidth();
        int sh = from.getHeight();
        int w = Math.max(1, Math.min(width, sw));
        int h = Math.max(1, Math.round(sh * (w / (float) sw)));
        NativeImage to = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            int y0 = y * sh / h;
            int y1 = Math.max(y0 + 1, (y + 1) * sh / h);
            for (int x = 0; x < w; x++) {
                int x0 = x * sw / w;
                int x1 = Math.max(x0 + 1, (x + 1) * sw / w);
                long r = 0, g = 0, b = 0;
                for (int yy = y0; yy < y1; yy++) {
                    for (int xx = x0; xx < x1; xx++) {
                        int c = from.getPixel(xx, yy);
                        r += c >> 16 & 0xFF;
                        g += c >> 8 & 0xFF;
                        b += c & 0xFF;
                    }
                }
                int n = (x1 - x0) * (y1 - y0);
                to.setPixel(x, y, 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n));
            }
        }
        return to;
    }

    /** Whether a picture's on screen. */
    public static boolean showing() {
        return shown != null;
    }

    private float total() {
        return time.get().floatValue() + SLIDE;
    }

    @Override
    public void update(float dt, boolean editing) {
        Minecraft mc = Minecraft.getInstance();
        if (shown != null) {
            age += dt;
            if (age > total() || mc.level != shown.level) {
                shown = null;
                mc.getTextureManager().release(TEXTURE);
            }
        }
        if (shown == null && !editing) {
            width = height = 0;
            return;
        }
        width = (int) size.get().doubleValue();
        height = PAD + LINE + 2 + pictureHeight() + 4 + LINE * 2 - 4 + PAD;
    }

    private int pictureHeight() {
        int inner = width - PAD * 2;
        return shown == null ? inner * 9 / 16 : Math.round(inner * shown.pictureHeight / (float) shown.pictureWidth);
    }

    @Override
    public void draw(GuiGraphics g, float alpha, boolean editing) {
        if (width == 0) return;
        Minecraft mc = Minecraft.getInstance();
        int s = Render2D.scale();
        float in = shown == null ? 1 : Math.min(1, age / SLIDE);
        float eased = 1 - (1 - in) * (1 - in) * (1 - in);
        float out = shown == null ? 1 : Math.min(1, (total() - age) / SLIDE);
        float fade = alpha * eased * Math.max(0, out);
        // Slides in from the edge it hangs off.
        int slide = Math.round((1 - eased) * 10) * (horizontal == Horizontal.RIGHT ? 1 : -1);
        int x = this.x + slide;
        int r = Math.round(Math.min(Theme.radius(), 4f) * s);
        int accent = Theme.accent();

        int px = x + PAD;
        int py = y + PAD + LINE + 2;
        int pw = width - PAD * 2;
        int ph = pictureHeight();

        Render2D.pixels(g);
        Render2D.glow(g, x * s, y * s, width * s, height * s, r, 6 * s, Theme.alpha(accent, 40 * Theme.glow() * fade));
        Render2D.rect(g, x * s, y * s, width * s, height * s, r, Theme.fade(Theme.BORDER, fade));
        Render2D.rect(g, x * s + 1, y * s + 1, width * s - 2, height * s - 2, Math.max(0, r - 1), Theme.fade(0xEC101217, fade));
        Render2D.rect(g, px * s - 1, py * s - 1, pw * s + 2, ph * s + 2, 0, Theme.fade(Theme.BORDER, fade));
        if (shown != null) {
            int tint = Theme.fade(0xFFFFFFFF, fade);
            g.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, px * s, py * s, 0, 0, pw * s, ph * s,
                shown.pictureWidth, shown.pictureHeight, shown.pictureWidth, shown.pictureHeight, tint);
        } else {
            Render2D.rect(g, px * s, py * s, pw * s, ph * s, 0, Theme.fade(Theme.FIELD, fade));
        }
        Render2D.end(g);

        String title = "Possible base";
        Text.draw(g, title, x + PAD, y + PAD, Theme.fade(Theme.TEXT, fade), false);
        String where = "x, y, z";
        String contents = "What's there";
        if (shown != null) {
            BlockPos p = BlockPos.containing(shown.at);
            String dist = mc.player == null ? "" : Math.round(shown.at.distanceTo(mc.player.position())) + "m";
            Text.draw(g, dist, x + width - PAD - Text.width(dist), y + PAD, Theme.fade(Theme.TEXT_FAINT, fade), false);
            where = StreamerMode.mask(p.getX() + ", " + p.getY() + ", " + p.getZ());
            contents = shown.contents;
        } else {
            String hint = "Base Alert's picture";
            Text.draw(g, hint, px + (pw - Text.width(hint)) / 2, py + (ph - Text.CAP) / 2, Theme.fade(Theme.TEXT_FAINT, fade), false);
        }
        int ty = py + ph + 4;
        Text.draw(g, fit(where, pw), x + PAD, ty, Theme.fade(Theme.TEXT_DIM, fade), false);
        Text.draw(g, fit(contents, pw), x + PAD, ty + LINE, Theme.fade(Theme.TEXT_DIM, fade), false);
    }

    /** {@code text}, cut short with an ellipsis if it's wider than {@code width}. */
    private static String fit(String text, int width) {
        if (Text.width(text) <= width) return text;
        String end = "...";
        int n = text.length();
        while (n > 0 && Text.width(text.substring(0, n) + end) > width) n--;
        return text.substring(0, n).stripTrailing() + end;
    }

    /** The picture, smoothly scaled if the HUD size has changed since it was taken. */
    private static final class Picture extends DynamicTexture {
        Picture(NativeImage image) {
            super(() -> "Cyanite base picture", image);
            sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        }
    }
}
