package com.cyanite.client.visuals;

import com.cyanite.client.render.Render3D;
import com.cyanite.client.system.WorldScan;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.SliderSetting;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.object.crystal.EndCrystalModel;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** End crystals: how big they're drawn, how they spin and bob, their beam, and boxes round the ones near you. */
public final class Crystals {
    public static final Group MODEL = new Group("crystal_model", "End Crystal Size",
        "Size, scale of the floating cube, colour and see-through-ness of end crystals.", "end crystal size scale model render color opacity");
    static final SliderSetting SIZE = MODEL.add(new SliderSetting("Size", 1, 0.2, 2, 0.05).format(Particles::times));
    static final SliderSetting CUBE_SCALE = MODEL.add(new SliderSetting("Render scale", 1, 0.2, 2, 0.05).format(Particles::times));
    static final BoolSetting TINT = MODEL.add(new BoolSetting("Tint", false));
    static final ColorSetting COLOR = MODEL.add(new ColorSetting("Color", 0xC56BFF).full());
    static final SliderSetting OPACITY = MODEL.add(new SliderSetting("Opacity", 1, 0.15, 1, 0.05).percent());
    static final BoolSetting HIDE_BASE = MODEL.add(new BoolSetting("Hide bedrock base", false));

    public static final Group ANIMATION = new Group("crystal_animation", "Crystal Animation",
        "How fast end crystals spin, and how high and fast they bob.", "end crystal animation spin rotate bounce bob speed");
    static final SliderSetting SPIN = ANIMATION.add(new SliderSetting("Spin speed", 1, 0, 5, 0.05).format(Particles::times));
    static final SliderSetting BOB_HEIGHT = ANIMATION.add(new SliderSetting("Bounce height", 1, 0, 3, 0.05).format(Particles::times));
    static final SliderSetting BOB_SPEED = ANIMATION.add(new SliderSetting("Bounce speed", 1, 0, 4, 0.05).format(Particles::times));

    public static final Group BEAM = new Group("crystal_beam", "Crystal Beam",
        "Colour of the beam end crystals shine, and a beam into the sky from every crystal if you like.", "end crystal beam color laser sky");
    static final ColorSetting BEAM_COLOR = BEAM.add(new ColorSetting("Color", 0x4DE1FF).full());
    static final BoolSetting SKY_BEAM = BEAM.add(new BoolSetting("Beam into the sky", false));
    static final SliderSetting SKY_LENGTH = BEAM.add(new SliderSetting("Sky beam length", 12, 3, 64, 1));

    public static final Group HIGHLIGHT = new Group("crystal_highlight", "Highlight Crystals",
        "Boxes round end crystals near you, so you see them through blocks and smoke.", "end crystal highlight esp box nearby outline");
    static final ColorSetting HIGHLIGHT_COLOR = HIGHLIGHT.add(new ColorSetting("Color", 0xFF4DD2).full());
    static final SliderSetting RANGE = HIGHLIGHT.add(new SliderSetting("Range", 12, 3, 48, 1));
    static final ModeSetting STYLE = HIGHLIGHT.add(new ModeSetting("Style", "Box", List.of("Box", "Corners", "Outline")));
    static final BoolSetting CLOSEST = HIGHLIGHT.add(new BoolSetting("Brighter when closest", true));

    private static final Identifier TEXTURE = Identifier.withDefaultNamespace("textures/entity/end_crystal/end_crystal.png");
    private static final RenderType TRANSLUCENT = RenderTypes.entityTranslucent(TEXTURE);

    private Crystals() {
    }

    // ---- Model

    /** The 2x scale the game draws crystals at, times ours. */
    public static float size(float scale) {
        return MODEL.on() ? scale * SIZE.get().floatValue() : scale;
    }

    /** The render type: see-through when the opacity is turned down. */
    public static RenderType renderType(RenderType type) {
        return MODEL.on() && OPACITY.get() < 0.999 ? TRANSLUCENT : type;
    }

    /** ARGB the model's drawn in; -1 for as it is. */
    public static int tint() {
        if (!MODEL.on()) return -1;
        int rgb = TINT.get() ? COLOR.get() : 0xFFFFFF;
        return Visuals.argb(rgb, OPACITY.get());
    }

    /** After the game posed the model's parts. */
    public static void afterSetupAnim(EndCrystalModel model) {
        if (!MODEL.on()) return;
        float s = CUBE_SCALE.get().floatValue();
        if (s != 1) {
            model.outerGlass.xScale *= s;
            model.outerGlass.yScale *= s;
            model.outerGlass.zScale *= s;
        }
        if (HIDE_BASE.get()) model.base.visible = false;
    }

    // ---- Animation

    /** The spin angle, from the game's. */
    public static float spin(float degrees) {
        return ANIMATION.on() ? degrees * SPIN.get().floatValue() : degrees;
    }

    /** The age the bob is worked out from: faster or slower. */
    public static float bobAge(float age) {
        return ANIMATION.on() ? age * BOB_SPEED.get().floatValue() : age;
    }

    /** The bob's height (EndCrystalRenderer.getY, -1.4 at the bottom), stretched or squashed. */
    public static float bobHeight(float y) {
        return ANIMATION.on() ? (y + 1.4f) * BOB_HEIGHT.get().floatValue() - 1.4f : y;
    }

    // ---- Beam

    public static boolean skyBeam() {
        return BEAM.on() && SKY_BEAM.get();
    }

    public static float skyLength() {
        return SKY_LENGTH.get().floatValue();
    }

    /** The beam's vertices, recoloured. */
    public static VertexConsumer beam(VertexConsumer out) {
        return BEAM.on() ? new Tinted(out, 0xFF000000 | BEAM_COLOR.get()) : out;
    }

    // ---- Highlight

    static void highlight(Render3D render) {
        Minecraft mc = Minecraft.getInstance();
        double range = RANGE.get();
        Vec3 eye = mc.player.getEyePosition(render.partialTick());
        EndCrystal closest = null;
        double best = Double.MAX_VALUE;
        for (Entity e : WorldScan.entities()) {
            if (!(e instanceof EndCrystal crystal)) continue;
            double d = crystal.position().distanceToSqr(eye);
            if (d > range * range) continue;
            if (d < best) {
                best = d;
                closest = crystal;
            }
        }
        if (closest == null) return;
        int rgb = HIGHLIGHT_COLOR.get();
        for (Entity e : WorldScan.entities()) {
            if (!(e instanceof EndCrystal crystal)) continue;
            double d = crystal.position().distanceToSqr(eye);
            if (d > range * range) continue;
            boolean top = CLOSEST.get() && crystal == closest;
            Vec3 at = crystal.getPosition(render.partialTick());
            AABB box = crystal.getBoundingBox().move(at.subtract(crystal.position()));
            int fill = STYLE.is("Outline") ? 0 : Visuals.argb(rgb, top ? 0.28 : 0.14);
            int line = Visuals.argb(rgb, top ? 1 : 0.7);
            if (STYLE.is("Corners")) render.corners(box, fill, fill, line, top ? 2.5f : 1.8f, top);
            else render.box(box, fill, line, top ? 2.5f : 1.8f, top);
            if (top) render.glow(box.getCenter(), 1.2, Visuals.argb(rgb, 0.4), true);
        }
    }

    /** Hands every vertex on, with its colour times {@code tint}. */
    private record Tinted(VertexConsumer out, int tint) implements VertexConsumer {
        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            out.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            out.setColor(r * (tint >> 16 & 0xFF) / 255, g * (tint >> 8 & 0xFF) / 255, b * (tint & 0xFF) / 255, a);
            return this;
        }

        @Override
        public VertexConsumer setColor(int argb) {
            out.setColor(Visuals.multiply(argb, tint));
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            out.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            out.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            out.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            out.setNormal(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            out.setLineWidth(width);
            return this;
        }
    }
}
