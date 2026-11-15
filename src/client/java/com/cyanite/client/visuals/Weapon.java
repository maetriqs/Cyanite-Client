package com.cyanite.client.visuals;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** What kind of fight the item in hand is for; picks the per-weapon visuals and, with Auto, the profile. */
public enum Weapon {
    CRYSTAL, SWORD, AXE, POT, UHC;

    /** null for anything that isn't one of them (blocks, food, tools). */
    public static Weapon of(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        if (stack.is(ItemTags.SWORDS)) return SWORD;
        if (stack.is(ItemTags.AXES)) return AXE;
        if (stack.is(Items.END_CRYSTAL) || stack.is(Items.OBSIDIAN) || stack.is(Items.RESPAWN_ANCHOR) || stack.is(Items.GLOWSTONE)) return CRYSTAL;
        if (stack.is(Items.SPLASH_POTION) || stack.is(Items.LINGERING_POTION)) return POT;
        if (stack.is(Items.BOW) || stack.is(Items.CROSSBOW) || stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE)
            || stack.is(Items.WATER_BUCKET) || stack.is(Items.LAVA_BUCKET) || stack.is(Items.FISHING_ROD) || stack.is(Items.COBWEB)) return UHC;
        return null;
    }
}
