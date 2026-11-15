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
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.lang.reflect.Method;
import java.util.OptionalInt;

/**
 * Cinematic's picture, drawn over the world right after it (and the hand) are drawn, before the HUD,
 * so menus and the HUD stay untouched. Two full-screen passes, plus two at half size for the
 * background blur:
 * <ol>
 *   <li>trail: this frame mixed with the frames before it (Motion Blur's own shader), kept as the
 *       history for next frame. It holds the picture before grading, or the grade would build up.</li>
 *   <li>grade: background blur by depth, brightness, contrast, saturation, warmth, the rain's mood
 *       and the vignette, written back over the game's picture.</li>
 * </ol>
 * The depth of the world is copied just before the game clears it for the hand ({@link #keepDepth}).
 * Textures are made on first use and freed once the effect has faded out.
 */
public final class CinematicRenderer {
    private static final Minecraft mc = Minecraft.getInstance();
    private static final int VEC4 = new Std140SizeCalculator().putVec4().get();

    private static RenderPipeline trailPipeline;
    private static RenderPipeline gradePipeline;
    private static RenderPipeline blurPipeline;
    private static MappableRingBuffer trailConfig;
    private static MappableRingBuffer gradeConfig;
    private static MappableRingBuffer blurAcross;
    private static MappableRingBuffer blurDown;

    /** Two pictures in turn: one is written this frame while the other, last frame's, is read. */
    private static final TextureTarget[] frames = new TextureTarget[2];
    private static int current;
    /** Whether the other frame holds last frame's picture yet. */
    private static boolean history;
    private static TextureTarget depth;
    private static boolean depthKept;
    private static TextureTarget blurA;
    private static TextureTarget blurB;
    /** Asked for by the last frame: whether to keep the world's depth before the hand clears it. */
    private static boolean wantsDepth;

    private static boolean irisLooked;
    private static Object iris;
    private static Method irisShaderPackInUse;

    private CinematicRenderer() {
    }

    /** Called just before the game clears the depth buffer to draw the hand: keeps a copy for the background blur. */
    public static void keepDepth() {
        depthKept = false;
        if (!wantsDepth) return;
        RenderTarget main = mc.getMainRenderTarget();
        if (main.getDepthTexture() == null) return;
        if (depth == null) depth = new TextureTarget("Cyanite cinematic depth", main.width, main.height, true);
        else if (depth.width != main.width || depth.height != main.height) depth.resize(main.width, main.height);
        depth.copyDepthFrom(main);
        depthKept = true;
    }

    /**
     * Draws this frame's picture over the game's.
     * @param trail how much of the frames before to keep (0 for none)
     * @param depthBlur how strongly what's behind the focus blurs (0 for none)
     * @param focus how far away, in blocks, the picture stays sharp
     * @param mood how much the weather greys and cools it
     */
    public static void draw(float saturation, float contrast, float brightness, float temperature, float vignette,
                            float trail, float depthBlur, float focus, float mood) {
        RenderTarget main = mc.getMainRenderTarget();
        GpuTextureView scene = main.getColorTextureView();
        if (scene == null) return;
        setup();
        size(main.width, main.height);

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        GpuSampler linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);

        // 1. Trail: always run, so last frame's picture is there the moment motion blur is wanted.
        TextureTarget out = frames[current];
        TextureTarget previous = frames[1 - current];
        try (GpuBuffer.MappedView view = encoder.mapBuffer(trailConfig.currentBuffer(), false, true)) {
            Std140Builder.intoBuffer(view.data()).putFloat(history ? trail : 0);
        }
        try (RenderPass pass = encoder.createRenderPass(() -> "Cyanite cinematic trail", out.getColorTextureView(), OptionalInt.empty())) {
            pass.setPipeline(trailPipeline);
            pass.setUniform("MotionBlurConfig", trailConfig.currentBuffer());
            pass.bindTexture("InSampler", scene, nearest);
            pass.bindTexture("PrevSampler", history ? previous.getColorTextureView() : scene, nearest);
            pass.draw(0, 3);
        }
        trailConfig.rotate();
        history = true;
        current = 1 - current;

        // Background blur: the world at half size, blurred across then down.
        wantsDepth = depthBlur > 0;
        boolean blurs = wantsDepth && depthKept && depth != null && main.getDepthTextureView() != null
            && depth.width == main.width && depth.height == main.height;
        if (blurs) {
            float spread = 0.6f + 1.9f * depthBlur;
            blur(encoder, blurAcross, out.getColorTextureView(), blurA, spread / blurA.width, 0, linear);
            blur(encoder, blurDown, blurA.getColorTextureView(), blurB, 0, spread / blurB.height, linear);
        }

        // 2. Grade, back over the game's picture.
        try (GpuBuffer.MappedView view = encoder.mapBuffer(gradeConfig.currentBuffer(), false, true)) {
            Std140Builder.intoBuffer(view.data())
                .putVec4(saturation, contrast, brightness, temperature)
                .putVec4(vignette, blurs ? depthBlur : 0, focus, 0)
                .putVec4(0.05f, mc.gameRenderer.getDepthFar(), mood, 0);
        }
        try (RenderPass pass = encoder.createRenderPass(() -> "Cyanite cinematic grade", scene, OptionalInt.empty())) {
            pass.setPipeline(gradePipeline);
            pass.setUniform("CinematicConfig", gradeConfig.currentBuffer());
            pass.bindTexture("SceneSampler", out.getColorTextureView(), nearest);
            pass.bindTexture("BlurSampler", blurs ? blurB.getColorTextureView() : out.getColorTextureView(), linear);
            pass.bindTexture("DepthSampler", blurs ? depth.getDepthTextureView() : out.getColorTextureView(), nearest);
            // After the world's depth was cleared only the hand is in it, so this says where the hand is.
            pass.bindTexture("HandDepthSampler", blurs ? main.getDepthTextureView() : out.getColorTextureView(), nearest);
            pass.draw(0, 3);
        }
        gradeConfig.rotate();
        depthKept = false;
    }

    private static void blur(CommandEncoder encoder, MappableRingBuffer config, GpuTextureView in, TextureTarget out,
                             float stepX, float stepY, GpuSampler linear) {
        try (GpuBuffer.MappedView view = encoder.mapBuffer(config.currentBuffer(), false, true)) {
            Std140Builder.intoBuffer(view.data()).putVec4(stepX, stepY, 0, 0);
        }
        try (RenderPass pass = encoder.createRenderPass(() -> "Cyanite cinematic blur", out.getColorTextureView(), OptionalInt.empty())) {
            pass.setPipeline(blurPipeline);
            pass.setUniform("BlurConfig", config.currentBuffer());
            pass.bindTexture("InSampler", in, linear);
            pass.draw(0, 3);
        }
        config.rotate();
    }

    /** Frees the textures once the picture's back to plain; made again next time they're needed. */
    public static void release() {
        wantsDepth = false;
        if (frames[0] == null) return;
        for (int i = 0; i < frames.length; i++) {
            frames[i].destroyBuffers();
            frames[i] = null;
        }
        if (depth != null) depth.destroyBuffers();
        if (blurA != null) blurA.destroyBuffers();
        if (blurB != null) blurB.destroyBuffers();
        depth = blurA = blurB = null;
        history = false;
        depthKept = false;
    }

    /** Whether an Iris shader pack is drawing the world, which brings its own depth (and its own depth of field). */
    public static boolean shaderPackOn() {
        if (!irisLooked) {
            irisLooked = true;
            try {
                Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                iris = api.getMethod("getInstance").invoke(null);
                irisShaderPackInUse = api.getMethod("isShaderPackInUse");
            } catch (ReflectiveOperationException | LinkageError absent) {
                iris = null;
            }
        }
        if (iris == null) return false;
        try {
            return (boolean) irisShaderPackInUse.invoke(iris);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Iris wouldn't say whether a shader pack is on", e);
        }
    }

    private static void size(int width, int height) {
        int halfW = Math.max(1, width / 2);
        int halfH = Math.max(1, height / 2);
        if (frames[0] == null) {
            frames[0] = new TextureTarget("Cyanite cinematic 0", width, height, false);
            frames[1] = new TextureTarget("Cyanite cinematic 1", width, height, false);
            blurA = new TextureTarget("Cyanite cinematic blur 0", halfW, halfH, false);
            blurB = new TextureTarget("Cyanite cinematic blur 1", halfW, halfH, false);
            history = false;
        } else if (frames[0].width != width || frames[0].height != height) {
            for (TextureTarget frame : frames) frame.resize(width, height);
            blurA.resize(halfW, halfH);
            blurB.resize(halfW, halfH);
            history = false;
        }
    }

    private static void setup() {
        if (gradePipeline != null) return;
        Identifier screenQuad = Identifier.withDefaultNamespace("core/screenquad");
        trailPipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("cyanite_client", "pipeline/cinematic_trail"))
            .withVertexShader(screenQuad)
            .withFragmentShader(Identifier.fromNamespaceAndPath("cyanite_client", "post/motion_blur"))
            .withSampler("InSampler")
            .withSampler("PrevSampler")
            .withUniform("MotionBlurConfig", UniformType.UNIFORM_BUFFER)
            .build();
        gradePipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("cyanite_client", "pipeline/cinematic_grade"))
            .withVertexShader(screenQuad)
            .withFragmentShader(Identifier.fromNamespaceAndPath("cyanite_client", "post/cinematic"))
            .withSampler("SceneSampler")
            .withSampler("BlurSampler")
            .withSampler("DepthSampler")
            .withSampler("HandDepthSampler")
            .withUniform("CinematicConfig", UniformType.UNIFORM_BUFFER)
            .build();
        blurPipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("cyanite_client", "pipeline/cinematic_blur"))
            .withVertexShader(screenQuad)
            .withFragmentShader(Identifier.fromNamespaceAndPath("cyanite_client", "post/cinematic_blur"))
            .withSampler("InSampler")
            .withUniform("BlurConfig", UniformType.UNIFORM_BUFFER)
            .build();
        // UNIFORM | MAP_WRITE, written every frame.
        int usage = GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE;
        trailConfig = new MappableRingBuffer(() -> "Cyanite cinematic trail", usage, VEC4);
        gradeConfig = new MappableRingBuffer(() -> "Cyanite cinematic grade", usage, VEC4 * 3);
        blurAcross = new MappableRingBuffer(() -> "Cyanite cinematic blur across", usage, VEC4);
        blurDown = new MappableRingBuffer(() -> "Cyanite cinematic blur down", usage, VEC4);
    }
}
