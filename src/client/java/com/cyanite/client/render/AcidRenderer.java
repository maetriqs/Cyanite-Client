package com.cyanite.client.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.util.OptionalInt;

/**
 * Acid Mode's picture, drawn over the world right after it (and the hand) are drawn, before the HUD,
 * like {@link CinematicRenderer}: the world's picture is copied aside and drawn back over itself in
 * drifting rainbow bands. It works on the finished picture, so Sodium and Iris (shader pack or not)
 * draw the world as usual underneath. The copy is made on first use and freed once it's faded out.
 */
public final class AcidRenderer {
    private static final Minecraft mc = Minecraft.getInstance();
    private static final int VEC4 = new Std140SizeCalculator().putVec4().get();

    private static RenderPipeline pipeline;
    private static MappableRingBuffer config;
    private static TextureTarget copy;

    private AcidRenderer() {
    }

    /**
     * Draws this frame's picture over the game's.
     * @param hue how far round the hue wheel the bands are, in turns
     * @param time a clock in seconds for the wobble
     * @param strength how much of it shows, 0 to 1
     */
    public static void draw(float hue, float time, float strength, float rainbow, float wobble, float split, float saturation) {
        RenderTarget main = mc.getMainRenderTarget();
        if (main.getColorTexture() == null || main.getColorTextureView() == null) return;
        setup();
        if (copy == null) copy = new TextureTarget("Cyanite acid", main.width, main.height, false);
        else if (copy.width != main.width || copy.height != main.height) copy.resize(main.width, main.height);
        if (copy.getColorTexture() == null) return;

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.copyTextureToTexture(main.getColorTexture(), copy.getColorTexture(), 0, 0, 0, 0, 0, main.width, main.height);
        try (GpuBuffer.MappedView view = encoder.mapBuffer(config.currentBuffer(), false, true)) {
            Std140Builder.intoBuffer(view.data())
                .putVec4(hue, time, strength, 0)
                .putVec4(rainbow, wobble, split, saturation);
        }
        try (RenderPass pass = encoder.createRenderPass(() -> "Cyanite acid", main.getColorTextureView(), OptionalInt.empty())) {
            pass.setPipeline(pipeline);
            pass.setUniform("AcidConfig", config.currentBuffer());
            pass.bindTexture("SceneSampler", copy.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.draw(0, 3);
        }
        config.rotate();
    }

    /** Frees the copy once the picture's back to plain; made again next time it's needed. */
    public static void release() {
        if (copy == null) return;
        copy.destroyBuffers();
        copy = null;
    }

    private static void setup() {
        if (pipeline != null) return;
        pipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("cyanite_client", "pipeline/acid"))
            .withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("cyanite_client", "post/acid"))
            .withSampler("SceneSampler")
            .withUniform("AcidConfig", UniformType.UNIFORM_BUFFER)
            .build();
        // UNIFORM | MAP_WRITE, written every frame.
        config = new MappableRingBuffer(() -> "Cyanite acid", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, VEC4 * 2);
    }
}
