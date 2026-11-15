package com.cyanite.client.visuals;

import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.SliderSetting;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * The totem that flies at the screen when one of yours pops, drawn here from scratch: its size, how it moves,
 * how long it stays and where. Also the totem in your hand, and a burst where someone else pops one.
 */
public final class TotemPop {
    public static final Group GROUP = new Group("totem_pop", "Totem Pop",
        "Size and animation of the totem that pops on screen, your held totem, and other players' pops.",
        "totem size scale undying pop animation popped");
    static final SliderSetting SIZE = GROUP.add(new SliderSetting("Pop size", 1, 0.1, 2, 0.05).format(Particles::times));
    static final SliderSetting HELD = GROUP.add(new SliderSetting("Held totem size", 1, 0.3, 1.5, 0.05).format(Particles::times));
    static final ModeSetting STYLE = GROUP.add(new ModeSetting("Animation", "Vanilla", List.of("Vanilla", "Pop", "Spin", "Slide", "Float", "Off")));
    static final SliderSetting LENGTH = GROUP.add(new SliderSetting("Length", 2, 0.5, 4, 0.1).format(v -> String.format(java.util.Locale.ROOT, "%.1fs", v)));
    static final SliderSetting SPIN = GROUP.add(new SliderSetting("Spin", 1, 0, 3, 0.05).format(Particles::times));
    static final SliderSetting X = GROUP.add(new SliderSetting("Position X", 0, -1, 1, 0.02));
    static final SliderSetting Y = GROUP.add(new SliderSetting("Position Y", 0, -1, 1, 0.02));
    static final BoolSetting RANDOM_OFFSET = GROUP.add(new BoolSetting("Random drift", true));
    static final BoolSetting OTHERS = GROUP.add(new BoolSetting("Burst on others' pops", true));
    static final BoolSetting TINT = GROUP.add(new BoolSetting("Color totem particles", false));
    static final ColorSetting COLOR = GROUP.add(new ColorSetting("Burst color", 0xFFD54A).full());

    private static final RandomSource RANDOM = RandomSource.create();
    private static ItemStack item = ItemStack.EMPTY;
    private static long start;
    private static float offX;
    private static float offY;

    private TotemPop() {
    }

    /** The game shows a popped item (displayItemActivation): start our own clock for it. */
    public static void start(ItemStack stack) {
        item = stack.copy();
        start = System.nanoTime();
        offX = RANDOM.nextFloat() * 2 - 1;
        offY = RANDOM.nextFloat() * 2 - 1;
    }

    /** For the preview: a pop as if one just happened. */
    public static void preview() {
        start(new ItemStack(Items.TOTEM_OF_UNDYING));
    }

    /** Seconds into the current pop, or -1 when none is showing. */
    public static float progress() {
        if (item.isEmpty()) return -1;
        float t = (System.nanoTime() - start) / 1e9f / LENGTH.get().floatValue();
        return t >= 1 ? -1 : t;
    }

    /** Instead of the game's own: true when it's been drawn (or hidden) here. */
    public static boolean render(PoseStack pose, SubmitNodeCollector collector) {
        if (!GROUP.on()) return false;
        if (STYLE.is("Off")) return true;
        float t = progress();
        if (t < 0) return true;

        Minecraft mc = Minecraft.getInstance();
        float aspect = (float) mc.getWindow().getWidth() / mc.getWindow().getHeight();
        float size = SIZE.get().floatValue();
        float spin = SPIN.get().floatValue();
        float driftX = RANDOM_OFFSET.get() ? offX : 0;
        float driftY = RANDOM_OFFSET.get() ? offY : 0;
        float px = X.get().floatValue() * 0.45f * aspect;
        float py = Y.get().floatValue() * 0.45f;

        pose.pushPose();
        switch (STYLE.get()) {
            case "Pop" -> {
                // Bursts out, holds, then shrinks away.
                float in = Mth.clamp(t * 4, 0, 1);
                float grow = 1 + 1.7f * (float) Math.pow(in - 1, 3) + 0.7f * (float) Math.pow(in - 1, 2);
                float out = t > 0.75f ? 1 - (t - 0.75f) / 0.25f : 1;
                float z = -2.2f;
                pose.translate((px + driftX * 0.1f) * -z, (py + driftY * 0.1f) * -z, z);
                pose.scale(0.8f * size * grow * out, 0.8f * size * grow * out, 0.8f * size * grow * out);
                pose.mulPose(Axis.YP.rotationDegrees(360 * spin * t));
            }
            case "Spin" -> {
                float eased = 1 - (1 - t) * (1 - t);
                float out = t > 0.8f ? 1 - (t - 0.8f) / 0.2f : 1;
                float z = -1.8f;
                pose.translate(px * -z, py * -z, z);
                pose.scale(0.6f * size * out, 0.6f * size * out, 0.6f * size * out);
                pose.mulPose(Axis.YP.rotationDegrees(1440 * spin * eased));
            }
            case "Slide" -> {
                // Up from under the screen, a pause in the middle, then on out the top.
                float y = t < 0.35f ? -1.6f * (1 - easeOut(t / 0.35f)) : t > 0.75f ? 1.6f * easeIn((t - 0.75f) / 0.25f) : 0;
                float z = -2f;
                pose.translate(px * -z, (py + y * 0.5f) * -z, z);
                pose.scale(0.7f * size, 0.7f * size, 0.7f * size);
                pose.mulPose(Axis.YP.rotationDegrees(25 * spin * Mth.sin(t * 12)));
            }
            case "Float" -> {
                float z = -2.4f;
                float out = t > 0.8f ? 1 - (t - 0.8f) / 0.2f : 1;
                pose.translate((px + driftX * 0.15f) * -z, (py - 0.25f + t * 0.5f) * -z, z);
                pose.scale(0.65f * size * out, 0.65f * size * out, 0.65f * size * out);
                pose.mulPose(Axis.YP.rotationDegrees(180 * spin * t));
            }
            default -> {
                // The game's own path, sped to the chosen length and moved and scaled as set.
                float g = t;
                float h = g * g;
                float j = g * h;
                float k = 10.25f * j * h - 24.95f * h * h + 25.5f * j - 13.8f * h + 4 * g;
                float l = k * (float) Math.PI;
                float n = driftX * 0.3f * aspect;
                float o = driftY * 0.3f;
                float z = -10 + 9 * Mth.sin(l);
                pose.translate(n * Mth.abs(Mth.sin(l * 2)) + px * -z, o * Mth.abs(Mth.sin(l * 2)) + py * -z, z);
                pose.scale(0.8f * size, 0.8f * size, 0.8f * size);
                pose.mulPose(Axis.YP.rotationDegrees(900 * spin * Mth.abs(Mth.sin(l))));
                pose.mulPose(Axis.XP.rotationDegrees(6 * Mth.cos(g * 8)));
                pose.mulPose(Axis.ZP.rotationDegrees(6 * Mth.cos(g * 8)));
            }
        }
        mc.gameRenderer.getLighting().setupFor(Lighting.Entry.ITEMS_3D);
        ItemStackRenderState state = new ItemStackRenderState();
        mc.getItemModelResolver().updateForTopItem(state, item, ItemDisplayContext.FIXED, mc.level, null, 0);
        state.submit(pose, collector, 15728880, OverlayTexture.NO_OVERLAY, 0);
        pose.popPose();
        return true;
    }

    private static float easeOut(float t) {
        t = Mth.clamp(t, 0, 1);
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }

    private static float easeIn(float t) {
        t = Mth.clamp(t, 0, 1);
        return t * t * t;
    }

    /** How much bigger a totem in your hand is drawn. */
    public static float heldScale(ItemStack stack) {
        return GROUP.on() && stack.is(Items.TOTEM_OF_UNDYING) ? HELD.get().floatValue() : 1;
    }

    /** The colour for totem particles, or 0 to leave them. */
    static int particleTint() {
        return GROUP.on() && TINT.get() ? COLOR.get() : 0;
    }

    /** Someone else popped a totem (entity event 35). */
    public static void otherPopped(Entity entity) {
        if (!GROUP.on() || !OTHERS.get()) return;
        Vec3 feet = entity.position();
        int color = COLOR.get();
        WorldFx.ring(feet.add(0, 0.05, 0), color, 0.6, 3.2, 0.8f, 2.5f);
        WorldFx.uprightRing(feet.add(0, entity.getBbHeight() / 2, 0), color, 0.4, 2.6, 0.6f, 2f);
        WorldFx.word(feet.add(0, entity.getBbHeight() + 0.5, 0), "POP", 0xFF000000 | color, 0.035f, 1.2f);
    }
}
