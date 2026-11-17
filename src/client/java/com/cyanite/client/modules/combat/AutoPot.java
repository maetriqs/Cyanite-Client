package com.cyanite.client.modules.combat;

import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;

/**
 * Throws splash potions at your feet from the hotbar: healing when you're low, and strength, speed or
 * fire resistance when they've run out (fire resistance only while you burn).
 */
public class AutoPot extends ThrowAtFeet {
    private final SliderSetting health = add(new SliderSetting("Health", 10, 1, 20, 1).format(v -> "at " + (int) v + " HP"));
    private final BoolSetting healing = add(new BoolSetting("Healing", true));
    private final BoolSetting strength = add(new BoolSetting("Strength", true));
    private final BoolSetting speed = add(new BoolSetting("Speed", true));
    private final BoolSetting fireResistance = add(new BoolSetting("Fire resistance", true));
    private final BoolSetting onGroundOnly = add(new BoolSetting("Only on ground", true));
    private final BoolSetting switchBack = add(new BoolSetting("Switch back", true));

    public AutoPot() {
        super("Auto Pot", "Throws splash potions at your feet when you need them");
    }

    @Override
    protected int afterThrow() {
        // Long enough for the splash to land and the health or effect to show, so it doesn't throw a second for nothing.
        return 8;
    }

    @Override
    protected boolean mayStart() {
        return !onGroundOnly.get() || mc.player.onGround();
    }

    @Override
    protected boolean switchBack() {
        return switchBack.get();
    }

    /** The hotbar slot of a splash potion you need now, healing first, or -1. */
    @Override
    protected int find() {
        int best = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!wanted(stack)) continue;
            if (has(stack, MobEffects.INSTANT_HEALTH)) return i;
            if (best < 0) best = i;
        }
        return best;
    }

    private boolean wanted(ItemStack stack) {
        if (!stack.is(Items.SPLASH_POTION)) return false;
        if (healing.get() && mc.player.getHealth() <= health.get() && has(stack, MobEffects.INSTANT_HEALTH)) return true;
        if (strength.get() && !mc.player.hasEffect(MobEffects.STRENGTH) && has(stack, MobEffects.STRENGTH)) return true;
        if (speed.get() && !mc.player.hasEffect(MobEffects.SPEED) && has(stack, MobEffects.SPEED)) return true;
        return fireResistance.get() && mc.player.isOnFire() && !mc.player.hasEffect(MobEffects.FIRE_RESISTANCE) && has(stack, MobEffects.FIRE_RESISTANCE);
    }

    private static boolean has(ItemStack stack, Holder<MobEffect> effect) {
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        if (contents == null) return false;
        for (MobEffectInstance instance : contents.getAllEffects()) if (instance.is(effect)) return true;
        return false;
    }
}
