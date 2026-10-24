package com.cyanite.client.gui.render;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.slf4j.Logger;

import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * All GUI text goes through here so the font can be swapped between Minecraft's own
 * and a font installed on the system. Coordinates are GUI units, and {@code y} is the
 * top of capital letters, which are always {@link #CAP} units tall.
 */
public final class Text {
    public static final int CAP = 7;
    public static final String MINECRAFT = "Minecraft";

    // Only offer fonts that look decent at small sizes, and only if they're installed.
    private static final List<String> CANDIDATES = List.of(
        "Segoe UI", "Bahnschrift", "Inter", "Roboto", "Verdana", "Tahoma",
        "Trebuchet MS", "Calibri", "Arial", "JetBrains Mono", "Consolas"
    );

    private static final Logger LOGGER = LogUtils.getLogger();

    private static List<String> choices;
    private static String family = MINECRAFT;
    /** A font one screen draws in instead of the setting's, while it's set. */
    private static String override;
    // One atlas per font and GUI scale, since the HUD and a menu can be at different scales at once.
    private static final Map<String, TrueTypeFont> LOADED = new HashMap<>();
    private static final Set<String> BROKEN = new HashSet<>();

    private Text() {
    }

    public static List<String> choices() {
        if (choices == null) {
            List<String> list = new ArrayList<>();
            list.add(MINECRAFT);
            try {
                Set<String> installed = new HashSet<>(Arrays.asList(
                    GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(Locale.ROOT)));
                for (String name : CANDIDATES) {
                    if (installed.contains(name)) list.add(name);
                }
            } catch (Throwable t) {
                LOGGER.warn("Couldn't list system fonts, only the Minecraft font will be available", t);
            }
            choices = List.copyOf(list);
        }
        return choices;
    }

    public static void setFamily(String name) {
        if (name.equals(family)) return;
        family = name;
        LOADED.values().forEach(TrueTypeFont::close);
        LOADED.clear();
    }

    /**
     * Draws and measures in {@code family} (null for the Font setting's) until set again.
     * @return what was in use, to hand back afterwards
     */
    public static String using(String family) {
        String previous = override;
        override = family;
        return previous;
    }

    /** A clean font for menus that shouldn't look pixelated: the Font setting's if it's one, else the first installed. */
    public static String smoothFamily() {
        if (!MINECRAFT.equals(family)) return family;
        for (String name : choices()) {
            if (!MINECRAFT.equals(name) && !BROKEN.contains(name)) return name;
        }
        return MINECRAFT;
    }

    public static int width(String text) {
        TrueTypeFont font = font();
        if (font == null) return Minecraft.getInstance().font.width(text);
        return (int) Math.ceil(font.width(text) / Render2D.scale());
    }

    public static void draw(GuiGraphics g, String text, int x, int y, int color) {
        draw(g, text, x, y, color, false);
    }

    public static void draw(GuiGraphics g, String text, int x, int y, int color, boolean shadow) {
        if ((color >>> 24) < 4 || text.isEmpty()) return;

        TrueTypeFont font = font();
        if (font == null) {
            g.drawString(Minecraft.getInstance().font, text, x, y, color, shadow);
            return;
        }

        int unit = Render2D.scale();
        Render2D.pixels(g);
        if (shadow) {
            int offset = Math.max(1, unit / 2);
            int shadowColor = Math.round((color >>> 24) * 0.6f) << 24;
            font.draw(g, text, x * unit + offset, y * unit + offset, shadowColor);
        }
        font.draw(g, text, x * unit, y * unit, color);
        Render2D.end(g);
    }

    private static TrueTypeFont font() {
        String name = override != null ? override : family;
        if (MINECRAFT.equals(name) || BROKEN.contains(name)) return null;

        int unit = Render2D.scale();
        String key = name + "@" + unit;
        TrueTypeFont font = LOADED.get(key);
        if (font != null) return font;

        try {
            font = new TrueTypeFont(name, unit);
            LOADED.put(key, font);
        } catch (Throwable t) {
            LOGGER.error("Couldn't load font '{}', falling back to the Minecraft font", name, t);
            BROKEN.add(name);
            if (name.equals(family)) family = MINECRAFT;
        }
        return font;
    }
}
