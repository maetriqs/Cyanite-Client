package com.cyanite.client.render;

import com.cyanite.client.system.setting.ColorSetting;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;

/**
 * Cinematic's space sky, drawn in place of the overworld's sky disc, sunrise glow, stars and dark
 * disc (the sun and moon stay). One full-screen pass looks the panorama up by view direction, so
 * it never stretches with the screen, and draws stars of its own at screen resolution, so zooming
 * in shows sharp points instead of a blown-up picture.
 *
 * <p>The pictures are from Kenney's "Skyboxes Space" (kenney.nl, CC0), cleaned up by the dev
 * tooling: their few blurry stars taken out and the 256-colour palette smoothed. They're all one
 * blue-violet ({@link #PICTURE_COLOR}); any Sky color turns them around the grey axis to its hue
 * and scales their colour and brightness by its own, which keeps every hue looking like space.
 * The fog takes the sky's average colour so far terrain blends into the haze at the horizon.
 */
public final class SpaceSky {
    private static final Minecraft mc = Minecraft.getInstance();
    private static final Logger LOGGER = LoggerFactory.getLogger("Cyanite");

    /** The pictures, and the contrast curve (power, gain) that takes each one to deep space. */
    public enum Picture {
        GALAXY("Galaxy", "galaxy", 1.5f, 1.5f),
        NEBULA("Nebula", "nebula", 2.0f, 1.5f),
        BAND("Milky band", "band", 1.4f, 1.4f);

        public final String label;
        private final String file;
        private final float power;
        private final float gain;

        Picture(String label, String file, float power, float gain) {
            this.label = label;
            this.file = file;
            this.power = power;
            this.gain = gain;
        }

        public static List<String> labels() {
            return Arrays.stream(values()).map(p -> p.label).toList();
        }

        public static Picture of(String label) {
            for (Picture p : values()) if (p.label.equals(label)) return p;
            return GALAXY;
        }
    }

    /** The colour that leaves the pictures as they are: their hue, saturation and brightness. */
    public static final int PICTURE_COLOR = 0x4040A0;
    private static final float PICTURE_HUE = 2 / 3f;
    private static final float PICTURE_SATURATION = 0.6f;
    private static final float PICTURE_VALUE = 0.63f;
    /** How much the horizon fades into the fog's colour. */
    private static final float HAZE = 0.8f;
    /** Star size in pixels on a 1080p screen (bigger screens scale it up, so stars look the same). */
    private static final float STAR_PIXELS = 1;
    private static final int CONFIG_SIZE = new Std140SizeCalculator()
        .putMat4f().putMat4f().putMat4f().putMat4f().putVec4().putVec4().putVec4().putVec4().get();

    private static RenderPipeline pipeline;
    private static MappableRingBuffer config;

    private static Picture loaded;
    private static GpuTexture texture;
    private static GpuTextureView view;
    /** The loaded picture's average colour over the whole sky, after its curve. */
    private static final float[] average = new float[3];

    // This frame's look, from Cinematic.
    private static float shown;
    private static Picture picture = Picture.GALAXY;
    private static float rainDim = 1;
    private static float stars;
    private static boolean flicker;
    private static final Matrix4f TINT = new Matrix4f();
    private static final Matrix4f TURN = new Matrix4f();
    private static final Matrix4f SCREEN = new Matrix4f();
    private static final Matrix4f STARS = new Matrix4f();
    /** The fog colour the game used last, which the haze at the horizon matches. */
    private static final Vector4f fog = new Vector4f(0, 0, 0, 1);
    private static final long START = System.nanoTime();

    private SpaceSky() {
    }

    /**
     * This frame's sky, before it's drawn.
     * @param shown how much of the space sky shows over the game's (0 none, 1 all of it)
     * @param starDensity how many stars, 1 for the usual (0 for none)
     * @param starAngle how far the stars have turned with the time of day, in radians
     * @param rainBrightness 1 in clear weather, less in rain
     */
    public static void frame(float shown, Picture picture, int color, float brightness, float starDensity, boolean flicker,
                             float starAngle, float rainBrightness) {
        SpaceSky.shown = shown;
        if (shown <= 0) {
            free();
            return;
        }
        SpaceSky.picture = picture;
        if (!load(picture)) {
            SpaceSky.shown = 0;
            return;
        }
        tint(color, brightness, TINT);
        // Vanilla turns its stars by Y -90 then X by the star angle; the picture turns the same way.
        TURN.rotationX(-starAngle).rotateY((float) (Math.PI / 2));
        rainDim = 0.55f + 0.45f * rainBrightness;
        stars = starDensity;
        SpaceSky.flicker = flicker;
    }

    /** Whether the space sky covers the game's sky completely this frame. */
    public static boolean full() {
        return shown >= 1 && view != null;
    }

    /** Whether it's fading in or out over the game's sky. */
    public static boolean fading() {
        return shown > 0 && shown < 1 && view != null;
    }

    /** The fog's base colour with the space sky showing: turned toward the sky's average colour. */
    public static int fog(int vanilla) {
        if (shown <= 0 || view == null) return vanilla;
        float r = average[0], g = average[1], b = average[2];
        int space = ARGB.colorFromFloat(1,
            clamp(TINT.m00() * r + TINT.m10() * g + TINT.m20() * b),
            clamp(TINT.m01() * r + TINT.m11() * g + TINT.m21() * b),
            clamp(TINT.m02() * r + TINT.m12() * g + TINT.m22() * b));
        return ARGB.srgbLerp(shown, vanilla, space);
    }

    /** The fog colour the game ended up with this frame (after rain, darkness and the like). */
    public static void fogUsed(Vector4f color) {
        fog.set(color);
    }

    /** Draws the sky into the game's picture, over whatever's there (in the sky pass, before the world). */
    public static void draw() {
        RenderTarget main = mc.getMainRenderTarget();
        if (view == null || main.getColorTextureView() == null || !Projection.skyRay(SCREEN)) return;
        setup();
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        float seconds = (float) ((System.nanoTime() - START) / 1e9 % 3600);
        // Picture direction to screen, so each star is measured in pixels: round and the same size
        // anywhere on screen at any FOV, where a fixed angle stretches toward the edges.
        STARS.set(SCREEN).invert().mul(new Matrix4f(TURN).transpose());
        try (GpuBuffer.MappedView mapped = encoder.mapBuffer(config.currentBuffer(), false, true)) {
            Std140Builder.intoBuffer(mapped.data())
                .putMat4f(SCREEN)
                .putMat4f(TURN)
                .putMat4f(STARS)
                .putMat4f(TINT)
                .putVec4(picture.power, picture.gain, rainDim, shown)
                .putVec4(fog.x, fog.y, fog.z, HAZE)
                .putVec4(stars, flicker ? 1 : 0, seconds, STAR_PIXELS * Math.max(1, main.height / 1080f))
                .putVec4(main.width, main.height, 0, 0);
        }
        try (RenderPass pass = encoder.createRenderPass(() -> "Cyanite space sky", main.getColorTextureView(), OptionalInt.empty())) {
            pass.setPipeline(pipeline);
            pass.setUniform("SpaceSkyConfig", config.currentBuffer());
            pass.bindTexture("SkySampler", view, RenderSystem.getSamplerCache()
                .getSampler(AddressMode.REPEAT, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, false));
            pass.draw(0, 3);
        }
        config.rotate();
    }

    /**
     * The Sky color as a matrix on the picture's colours: around the grey axis by the difference in
     * hue, colour scaled by its saturation and the lot by its brightness (and Sky brightness). The
     * grey axis stays put, so white stays white and the pictures' colours keep their spread.
     */
    static void tint(int rgb, float brightness, Matrix4f out) {
        float[] hsv = ColorSetting.hsv(rgb);
        double turn = (hsv[0] - PICTURE_HUE) * 2 * Math.PI;
        double saturation = Math.min(1.6, hsv[1] / PICTURE_SATURATION);
        double value = Math.clamp(hsv[2] / PICTURE_VALUE, 0.25, 1.6) * brightness;
        double cos = Math.cos(turn), sin = Math.sin(turn), k = 1 / Math.sqrt(3);
        out.identity();
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                // Rodrigues' rotation around (1,1,1)/sqrt(3): cos I + sin [k]x + (1 - cos) k k^T.
                int cross = row == col ? 0 : (col - row + 3) % 3 == 2 ? 1 : -1;
                double rotated = (row == col ? cos : 0) + sin * cross * k + (1 - cos) * k * k;
                // Grey (1/3 everywhere) plus the turned colour, scaled.
                out.setRowColumn(row, col, (float) (value * (1 / 3.0 + saturation * (rotated - 1 / 3.0))));
            }
        }
    }

    private static float clamp(float v) {
        return Math.clamp(v, 0, 1);
    }

    /** Makes sure the picture is on the GPU. A picture that fails to load is logged once and not tried again. */
    private static boolean load(Picture wanted) {
        if (loaded == wanted) return view != null;
        free();
        loaded = wanted;
        Identifier id = Identifier.fromNamespaceAndPath("cyanite_client", "textures/sky/" + wanted.file + ".jpg");
        try (InputStream in = mc.getResourceManager().open(id)) {
            byte[] bytes = in.readAllBytes();
            ByteBuffer file = MemoryUtil.memAlloc(bytes.length);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                file.put(bytes).flip();
                IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1), channels = stack.mallocInt(1);
                ByteBuffer pixels = STBImage.stbi_load_from_memory(file, w, h, channels, 4);
                if (pixels == null) throw new IOException(STBImage.stbi_failure_reason());
                try {
                    int width = w.get(0), height = h.get(0);
                    texture = RenderSystem.getDevice().createTexture(() -> "Cyanite space sky",
                        GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, TextureFormat.RGBA8, width, height, 1, 1);
                    RenderSystem.getDevice().createCommandEncoder()
                        .writeToTexture(texture, pixels, NativeImage.Format.RGBA, 0, 0, 0, 0, width, height);
                    view = RenderSystem.getDevice().createTextureView(texture);
                    average(pixels, width, height, wanted);
                } finally {
                    STBImage.stbi_image_free(pixels);
                }
            } finally {
                MemoryUtil.memFree(file);
            }
            return true;
        } catch (IOException e) {
            LOGGER.error("Cinematic couldn't load its space sky picture {}", id, e);
            free();
            loaded = wanted;
            return false;
        }
    }

    /** The picture's average colour over the sphere (rows weighted by how much sky they cover), after its curve. */
    private static void average(ByteBuffer pixels, int width, int height, Picture p) {
        double r = 0, g = 0, b = 0, weight = 0;
        for (int y = 0; y < height; y += 4) {
            double row = Math.sin(Math.PI * (y + 0.5) / height);
            for (int x = 0; x < width; x += 4) {
                int i = (y * width + x) * 4;
                float pr = (pixels.get(i) & 0xFF) / 255f, pg = (pixels.get(i + 1) & 0xFF) / 255f, pb = (pixels.get(i + 2) & 0xFF) / 255f;
                float luma = Math.max(0.2126f * pr + 0.7152f * pg + 0.0722f * pb, 1e-4f);
                double curve = p.gain * Math.pow(luma, p.power - 1);
                r += pr * curve * row;
                g += pg * curve * row;
                b += pb * curve * row;
                weight += row;
            }
        }
        average[0] = (float) (r / weight);
        average[1] = (float) (g / weight);
        average[2] = (float) (b / weight);
    }

    private static void free() {
        if (view != null) view.close();
        if (texture != null) texture.close();
        view = null;
        texture = null;
        loaded = null;
    }

    private static void setup() {
        if (pipeline != null) return;
        pipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("cyanite_client", "pipeline/space_sky"))
            .withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("cyanite_client", "post/space_sky"))
            .withSampler("SkySampler")
            .withUniform("SpaceSkyConfig", UniformType.UNIFORM_BUFFER)
            .withBlend(BlendFunction.TRANSLUCENT)
            .build();
        config = new MappableRingBuffer(() -> "Cyanite space sky", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, CONFIG_SIZE);
    }
}
