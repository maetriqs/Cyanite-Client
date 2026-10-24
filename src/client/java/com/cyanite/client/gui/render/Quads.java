package com.cyanite.client.gui.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2f;

import java.util.Arrays;

/**
 * Many textured rectangles from one texture, in one colour, handed to the game's GUI as one piece.
 * The game checks every piece of a frame against the pieces before it, so the cost grows with the
 * square of how many there are: a rounded box was seven pieces, a glow nine and every letter of
 * the smooth font one each. As one piece each, a screen full of name tags stays cheap.
 */
public final class Quads implements GuiElementRenderState {
    private final TextureSetup texture;
    private final Matrix3x2f pose;
    private final ScreenRectangle scissor;
    private final int color;
    private float[] q = new float[8 * 9];
    private int n;
    private float minX = Float.MAX_VALUE;
    private float minY = Float.MAX_VALUE;
    private float maxX = -Float.MAX_VALUE;
    private float maxY = -Float.MAX_VALUE;
    private ScreenRectangle bounds;

    private Quads(GuiGraphics g, Identifier textureId, int color) {
        AbstractTexture tex = Minecraft.getInstance().getTextureManager().getTexture(textureId);
        this.texture = TextureSetup.singleTexture(tex.getTextureView(), tex.getSampler());
        this.pose = new Matrix3x2f(g.pose());
        this.scissor = g.scissorStack.peek();
        this.color = color;
    }

    public static Quads of(GuiGraphics g, Identifier texture, int color) {
        return new Quads(g, texture, color);
    }

    /** A rectangle (x0, y0)-(x1, y1) showing the texture from (u0, v0) to (u1, v1), as fractions of it. */
    public void add(float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1) {
        if (x1 <= x0 || y1 <= y0) return;
        if ((n + 1) * 8 > q.length) q = Arrays.copyOf(q, q.length * 2);
        int i = n * 8;
        q[i] = x0;
        q[i + 1] = y0;
        q[i + 2] = x1;
        q[i + 3] = y1;
        q[i + 4] = u0;
        q[i + 5] = v0;
        q[i + 6] = u1;
        q[i + 7] = v1;
        n++;
        minX = Math.min(minX, x0);
        minY = Math.min(minY, y0);
        maxX = Math.max(maxX, x1);
        maxY = Math.max(maxY, y1);
    }

    /** Hands it to the game; nothing happens if it's empty or wholly clipped away. */
    public void submit(GuiGraphics g) {
        if (n == 0) return;
        int x = (int) Math.floor(minX);
        int y = (int) Math.floor(minY);
        ScreenRectangle area = new ScreenRectangle(x, y, (int) Math.ceil(maxX) - x, (int) Math.ceil(maxY) - y).transformMaxBounds(pose);
        bounds = scissor != null ? scissor.intersection(area) : area;
        if (bounds == null) return;
        g.guiRenderState.submitGuiElement(this);
    }

    @Override
    public void buildVertices(VertexConsumer out) {
        for (int k = 0; k < n; k++) {
            int i = k * 8;
            out.addVertexWith2DPose(pose, q[i], q[i + 1]).setUv(q[i + 4], q[i + 5]).setColor(color);
            out.addVertexWith2DPose(pose, q[i], q[i + 3]).setUv(q[i + 4], q[i + 7]).setColor(color);
            out.addVertexWith2DPose(pose, q[i + 2], q[i + 3]).setUv(q[i + 6], q[i + 7]).setColor(color);
            out.addVertexWith2DPose(pose, q[i + 2], q[i + 1]).setUv(q[i + 6], q[i + 5]).setColor(color);
        }
    }

    @Override
    public RenderPipeline pipeline() {
        return RenderPipelines.GUI_TEXTURED;
    }

    @Override
    public TextureSetup textureSetup() {
        return texture;
    }

    @Override
    public ScreenRectangle scissorArea() {
        return scissor;
    }

    @Override
    public ScreenRectangle bounds() {
        return bounds;
    }
}
