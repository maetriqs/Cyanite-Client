package com.cyanite.client.visuals;

import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.SliderSetting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Your first-person hand: how a sword or axe swings, how it kicks forward when you hit something, and how
 * a golden apple is held while you eat it. All of it only how your own hand looks; nothing is sent.
 */
public final class Hands {
    private static final List<String> SWING_STYLES = List.of("Vanilla", "Smooth", "Slash", "Stab", "Spin", "Low", "Chop");

    /** One weapon's swing settings. */
    static final class Swing {
        final Group group;
        final ModeSetting style;
        final SliderSetting speed;
        final SliderSetting strength;
        final SliderSetting size;

        Swing(Group group, String style) {
            this.group = group;
            this.style = group.add(new ModeSetting("Style", style, SWING_STYLES));
            this.speed = group.add(new SliderSetting("Swing speed", 1, 0.5, 2, 0.05).format(Particles::times));
            this.strength = group.add(new SliderSetting("Strength", 1, 0.2, 2, 0.05).format(Particles::times));
            this.size = group.add(new SliderSetting("Item size", 1, 0.4, 1.6, 0.05).format(Particles::times));
        }
    }

    /** One weapon's hit kick. */
    static final class Attack {
        final Group group;
        final SliderSetting lunge;
        final SliderSetting tilt;
        final SliderSetting length;
        final BoolSetting onMiss;

        Attack(Group group, double lunge, double tilt) {
            this.group = group;
            this.lunge = group.add(new SliderSetting("Lunge", lunge, 0, 1, 0.05).percent());
            this.tilt = group.add(new SliderSetting("Tilt", tilt, -60, 60, 1).format(v -> Math.round(v) + "°"));
            this.length = group.add(new SliderSetting("Length", 0.25, 0.08, 0.8, 0.02).format(v -> String.format(java.util.Locale.ROOT, "%.2fs", v)));
            this.onMiss = group.add(new BoolSetting("On misses too", false));
        }
    }

    public static final Group SWORD_SWING = new Group("sword_swing", "Sword Swing Animation",
        "How your sword swings: style, speed, strength and size in your hand.", "sword swing animation slash stab spin speed size hand");
    static final Swing SWORD = new Swing(SWORD_SWING, "Smooth");

    public static final Group AXE_SWING = new Group("axe_swing", "Axe Swing Animation",
        "How your axe swings: style, speed, strength and size in your hand.", "axe swing animation chop speed size hand");
    static final Swing AXE = new Swing(AXE_SWING, "Chop");

    public static final Group SWORD_ATTACK = new Group("sword_attack", "Sword Attack Animation",
        "Your sword kicks forward and tilts when you hit something.", "sword attack animation hit lunge tilt kick");
    static final Attack SWORD_HIT = new Attack(SWORD_ATTACK, 0.35, -12);

    public static final Group AXE_ATTACK = new Group("axe_attack", "Axe Attack Animation",
        "Your axe kicks forward and tilts when you hit something.", "axe attack animation hit lunge tilt kick");
    static final Attack AXE_HIT = new Attack(AXE_ATTACK, 0.5, 18);

    public static final Group GAPPLE = new Group("gapple_eat", "Golden Apple Eating",
        "How a golden apple is held while you eat it.", "golden apple gapple eat eating animation food");
    static final ModeSetting EAT_STYLE = GAPPLE.add(new ModeSetting("Style", "Steady", List.of("Vanilla", "Steady", "Bob", "Spin", "Close")));
    static final SliderSetting BOB = GAPPLE.add(new SliderSetting("Bob", 1, 0, 3, 0.05).format(Particles::times));
    static final SliderSetting EAT_SIZE = GAPPLE.add(new SliderSetting("Size", 1, 0.4, 1.6, 0.05).format(Particles::times));
    static final BoolSetting ALL_FOOD = GAPPLE.add(new BoolSetting("All food and drink", false));

    private static long attackAt;
    private static Weapon attackWith;

    private Hands() {
    }

    static void tick() {
        if (attackWith != null && (System.nanoTime() - attackAt) / 1e9 > 1) attackWith = null;
    }

    private static Swing swingFor(ItemStack stack) {
        Weapon kind = Weapon.of(stack);
        if (kind == Weapon.SWORD && SWORD_SWING.on()) return SWORD;
        if (kind == Weapon.AXE && AXE_SWING.on()) return AXE;
        return null;
    }

    private static Attack attackFor(Weapon kind) {
        if (kind == Weapon.SWORD && SWORD_ATTACK.on()) return SWORD_HIT;
        if (kind == Weapon.AXE && AXE_ATTACK.on()) return AXE_HIT;
        return null;
    }

    // ---- Called from the mixins

    /** In place of the game's swing: true when it's been done here. {@code arm} is 1 for the right hand, -1 for the left. */
    public static boolean swing(PoseStack pose, float progress, int arm, ItemStack stack) {
        Swing s = swingFor(stack);
        if (s == null || s.style.is("Vanilla")) return false;
        float k = s.strength.get().floatValue();
        float f = progress;
        float sin = Mth.sin(f * Mth.PI);
        float sinRoot = Mth.sin(Mth.sqrt(f) * Mth.PI);
        switch (s.style.get()) {
            case "Smooth" -> {
                pose.translate(arm * -0.25f * sinRoot * k, 0.08f * sin * k, -0.2f * sin * k);
                pose.mulPose(Axis.YP.rotationDegrees(arm * (45 - 45 * (1 - sin)) * k * 0.6f));
                pose.mulPose(Axis.ZP.rotationDegrees(arm * -25 * sin * k));
                pose.mulPose(Axis.XP.rotationDegrees(-70 * sinRoot * k));
            }
            case "Slash" -> {
                pose.translate(arm * -0.35f * sinRoot * k, 0.15f * Mth.sin(Mth.sqrt(f) * Mth.TWO_PI) * k, -0.25f * sin * k);
                pose.mulPose(Axis.ZP.rotationDegrees(arm * -75 * sin * k));
                pose.mulPose(Axis.YP.rotationDegrees(arm * -50 * sinRoot * k));
                pose.mulPose(Axis.XP.rotationDegrees(-30 * sinRoot * k));
            }
            case "Stab" -> {
                pose.translate(arm * -0.1f * sin * k, 0.05f * sin * k, -0.55f * sin * k);
                pose.mulPose(Axis.XP.rotationDegrees(-15 * sin * k));
            }
            case "Spin" -> {
                float eased = 1 - (1 - f) * (1 - f);
                pose.translate(0, 0.1f * sin * k, -0.15f * sin * k);
                pose.mulPose(Axis.ZP.rotationDegrees(arm * -360 * eased * Math.min(1, k)));
            }
            case "Low" -> {
                pose.translate(arm * -0.08f * sin * k, -0.04f * sin * k, -0.08f * sin * k);
                pose.mulPose(Axis.XP.rotationDegrees(-25 * sinRoot * k));
            }
            case "Chop" -> {
                // Up and then hard down, like splitting a log.
                float up = f < 0.3f ? f / 0.3f : 1 - (f - 0.3f) / 0.7f;
                pose.translate(arm * -0.15f * sin * k, 0.15f * up * k, -0.25f * sin * k);
                pose.mulPose(Axis.XP.rotationDegrees((40 * up - 100 * Mth.sin(Mth.clamp((f - 0.3f) / 0.7f, 0, 1) * Mth.PI)) * k));
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Just before the held item is drawn: its size, and the kick after a hit. */
    public static void beforeItem(PoseStack pose, ItemStack stack, int arm, boolean mainHand) {
        float scale = TotemPop.heldScale(stack);
        if (mainHand) {
            Swing s = swingFor(stack);
            if (s != null) scale *= s.size.get().floatValue();
            Attack a = attackFor(Weapon.of(stack));
            if (a != null && attackWith == Weapon.of(stack)) {
                float t = (float) ((System.nanoTime() - attackAt) / 1e9 / a.length.get());
                if (t < 1) {
                    float e = Mth.sin(t * Mth.PI) * (1 - t * 0.3f);
                    pose.translate(arm * -0.05f * e, 0.04f * e, -0.45f * a.lunge.get().floatValue() * e);
                    pose.mulPose(Axis.XP.rotationDegrees(a.tilt.get().floatValue() * e));
                }
            }
        }
        if (eats(stack)) scale *= EAT_SIZE.get().floatValue();
        if (scale != 1) pose.scale(scale, scale, scale);
    }

    /** You attacked (hit is false for a swing at nothing). */
    public static void onAttack(boolean hit) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Weapon kind = Weapon.of(mc.player.getMainHandItem());
        Attack a = attackFor(kind);
        if (a == null || !hit && !a.onMiss.get()) return;
        attackWith = kind;
        attackAt = System.nanoTime();
    }

    /** How many ticks your own swing lasts. */
    public static int swingDuration(LivingEntity entity, int ticks) {
        if (entity != Minecraft.getInstance().player) return ticks;
        Swing s = swingFor(entity.getMainHandItem());
        if (s == null) return ticks;
        return Math.max(1, Math.round(ticks / s.speed.get().floatValue()));
    }

    private static boolean eats(ItemStack stack) {
        if (!GAPPLE.on()) return false;
        if (stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE)) return true;
        return ALL_FOOD.get() && (stack.has(DataComponents.FOOD) || stack.has(DataComponents.CONSUMABLE));
    }

    /** In place of the game's eating pose: true when it's been done here. */
    public static boolean eat(PoseStack pose, float partial, HumanoidArm arm, ItemStack stack, Player player) {
        if (!eats(stack) || EAT_STYLE.is("Vanilla")) return false;
        float g = player.getUseItemRemainingTicks() - partial + 1;
        float h = g / Math.max(1, stack.getUseDuration(player));
        int j = arm == HumanoidArm.RIGHT ? 1 : -1;
        float in = 1 - (float) Math.pow(h, 27);
        float bob = BOB.get().floatValue();
        switch (EAT_STYLE.get()) {
            case "Steady" -> {
                pose.translate(in * 0.6f * j, in * -0.5f, 0);
                pose.mulPose(Axis.YP.rotationDegrees(j * in * 90));
                pose.mulPose(Axis.XP.rotationDegrees(in * 10));
                pose.mulPose(Axis.ZP.rotationDegrees(j * in * 30));
            }
            case "Bob" -> {
                float y = Mth.abs(Mth.sin(g / 2.5f * Mth.PI)) * 0.12f * bob;
                pose.translate(in * 0.6f * j, in * -0.5f + y, 0);
                pose.mulPose(Axis.YP.rotationDegrees(j * in * 90));
                pose.mulPose(Axis.ZP.rotationDegrees(j * in * 30 + Mth.sin(g / 2.5f * Mth.PI) * 8 * bob));
            }
            case "Spin" -> {
                pose.translate(in * 0.55f * j, in * -0.45f, 0);
                pose.mulPose(Axis.YP.rotationDegrees(j * in * 90 + g * 18 * bob));
            }
            case "Close" -> {
                pose.translate(in * 0.45f * j, in * -0.35f, in * 0.25f);
                pose.mulPose(Axis.YP.rotationDegrees(j * in * 70));
                pose.mulPose(Axis.XP.rotationDegrees(in * 20 + Mth.sin(g / 3 * Mth.PI) * 4 * bob));
            }
            default -> {
                return false;
            }
        }
        return true;
    }
}
