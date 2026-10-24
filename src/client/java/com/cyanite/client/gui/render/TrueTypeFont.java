package com.cyanite.client.gui.render;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A system font rasterised into a glyph atlas at the exact pixel size it's drawn at,
 * so text stays sharp. One instance covers one family at one GUI scale.
 */
final class TrueTypeFont {
    private static final int ATLAS_WIDTH = 512;
    private static final int PAD = 2;

    final String family;
    final int unit;

    private final Identifier texture;
    private final Map<Integer, Glyph> glyphs = new HashMap<>();
    private final Glyph fallback;
    private final int atlasHeight;
    private final int baseline;

    private record Glyph(int u, int v, int w, int h, int x, int y, float advance) {
    }

    TrueTypeFont(String family, int unit) {
        this.family = family;
        this.unit = unit;

        FontRenderContext frc = new FontRenderContext(null, true, true);

        // Size the font by its cap height so it lines up with the layout like the pixel font does.
        Font probe = new Font(family, Font.PLAIN, 100);
        double capRatio = probe.createGlyphVector(frc, "H").getVisualBounds().getHeight() / 100.0;
        int capBox = Text.CAP * unit;
        float capTarget = Math.max(5f, 6.4f * unit);
        Font font = probe.deriveFont((float) (capTarget / capRatio));

        float capPx = (float) font.createGlyphVector(frc, "H").getVisualBounds().getHeight();
        baseline = Math.round((capBox - capPx) / 2f + capPx);

        String charset = charset();
        int count = charset.codePointCount(0, charset.length());
        GlyphVector[] vectors = new GlyphVector[count];
        Rectangle[] bounds = new Rectangle[count];
        int[] codepoints = charset.codePoints().toArray();

        int penX = PAD;
        int penY = PAD;
        int rowHeight = 0;
        int[] us = new int[count];
        int[] vs = new int[count];

        for (int i = 0; i < count; i++) {
            vectors[i] = font.createGlyphVector(frc, Character.toString(codepoints[i]));
            bounds[i] = vectors[i].getPixelBounds(frc, 0, 0);

            if (penX + bounds[i].width + PAD > ATLAS_WIDTH) {
                penX = PAD;
                penY += rowHeight + PAD;
                rowHeight = 0;
            }
            us[i] = penX;
            vs[i] = penY;
            penX += bounds[i].width + PAD;
            rowHeight = Math.max(rowHeight, bounds[i].height);
        }

        atlasHeight = Integer.highestOneBit(Math.max(1, penY + rowHeight + PAD) - 1) << 1;

        BufferedImage image = new BufferedImage(ATLAS_WIDTH, atlasHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = image.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2.setColor(Color.WHITE);

        for (int i = 0; i < count; i++) {
            Rectangle b = bounds[i];
            g2.drawGlyphVector(vectors[i], us[i] - b.x, vs[i] - b.y);
            float advance = vectors[i].getGlyphMetrics(0).getAdvanceX();
            glyphs.put(codepoints[i], new Glyph(us[i], vs[i], b.width, b.height, b.x, b.y, advance));
        }
        g2.dispose();

        NativeImage pixels = new NativeImage(ATLAS_WIDTH, atlasHeight, false);
        for (int y = 0; y < atlasHeight; y++) {
            for (int x = 0; x < ATLAS_WIDTH; x++) {
                float coverage = (image.getRGB(x, y) >>> 24) / 255f;
                // AWT's coverage is linear, which reads thin as light text on a dark background.
                int a = Math.round((float) Math.pow(coverage, 0.75) * 255f);
                pixels.setPixel(x, y, a << 24 | 0xFFFFFF);
            }
        }

        String id = "font/" + family.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "_") + "_" + unit;
        texture = Render2D.upload(id, pixels);
        fallback = glyphs.get((int) '?');
    }

    float width(String text) {
        float width = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            width += glyphs.getOrDefault(cp, fallback).advance;
        }
        return width;
    }

    void draw(GuiGraphics g, String text, int x, int y, int color) {
        float pen = x;
        int base = y + baseline;
        // The whole string as one piece for the game's GUI, not one per letter.
        Quads quads = Quads.of(g, texture, color);
        float tw = ATLAS_WIDTH;
        float th = atlasHeight;

        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            Glyph glyph = glyphs.getOrDefault(cp, fallback);

            if (glyph.w > 0 && glyph.h > 0) {
                int gx = Math.round(pen) + glyph.x;
                int gy = base + glyph.y;
                quads.add(gx, gy, gx + glyph.w, gy + glyph.h, glyph.u / tw, glyph.v / th, (glyph.u + glyph.w) / tw, (glyph.v + glyph.h) / th);
            }
            pen += glyph.advance;
        }
        quads.submit(g);
    }

    void close() {
        Minecraft.getInstance().getTextureManager().release(texture);
    }

    private static String charset() {
        StringBuilder sb = new StringBuilder();
        for (char c = 32; c < 127; c++) sb.append(c);
        for (char c = 160; c < 256; c++) sb.append(c);
        sb.append("•…–—‘’“”");
        return sb.toString();
    }
}
