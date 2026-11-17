package com.cyanite.client.visuals;

import com.cyanite.client.render.EspGlow;
import com.cyanite.client.render.Render3D;
import com.cyanite.client.system.WorldScan;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Dropped items glow: outlined like the Glowing effect, a soft light under each, and a beam of light over the
 * good ones so they show from far off. Coloured by how rare they are, or all one colour.
 */
public final class ItemGlow {
    public static final Group GROUP = new Group("item_glow", "Item Glow",
        "Dropped items glow in their rarity's colour, with beams of light over the good ones so you can spot them from far off.",
        "item drop dropped loot glow outline beam rarity rare epic pickup ground");
    static final ModeSetting COLORS = GROUP.add(new ModeSetting("Colors", "By rarity", List.of("By rarity", "One color")));
    static final ColorSetting COLOR = GROUP.add(new ColorSetting("Color", 0x7DF9FF).full());
    static final BoolSetting OUTLINE = GROUP.add(new BoolSetting("Outline", true));
    static final BoolSetting LIGHT = GROUP.add(new BoolSetting("Light", true));
    static final ModeSetting BEAMS = GROUP.add(new ModeSetting("Beams", "Good items", List.of("Off", "Good items", "All")));
    static final SliderSetting BEAM_HEIGHT = GROUP.add(new SliderSetting("Beam height", 5, 1, 16, 0.5));
    static final BoolSetting NAMES = GROUP.add(new BoolSetting("Names", false));
    static final SliderSetting RANGE = GROUP.add(new SliderSetting("Range", 32, 8, 96, 1));

    /** Common items worth a beam anyway. */
    private static final Set<Item> GOOD = Set.of(Items.DIAMOND, Items.DIAMOND_BLOCK, Items.NETHERITE_INGOT, Items.NETHERITE_SCRAP,
        Items.NETHERITE_BLOCK, Items.ANCIENT_DEBRIS, Items.EMERALD, Items.EMERALD_BLOCK, Items.SHULKER_SHELL, Items.SPAWNER,
        Items.END_CRYSTAL, Items.EXPERIENCE_BOTTLE, Items.GOLD_BLOCK, Items.TOTEM_OF_UNDYING);
    /** White, yellow, aqua and pink, like the game's rarity colours, a little richer. */
    static final int[] RARITY = {0xF2F2F2, 0xFFDD4D, 0x4DE8FF, 0xE070FF};
    /** Lights, beams and names go on the nearest this many; outlines on everything in range. */
    private static final int MOST_LIT = 48;

    private ItemGlow() {
    }

    static void render3D(Render3D render) {
        Minecraft mc = Minecraft.getInstance();
        float partial = render.partialTick();
        Vec3 eye = render.camera();
        double range = RANGE.get();
        List<ItemEntity> near = new ArrayList<>();
        for (Entity e : WorldScan.entities()) {
            if (e instanceof ItemEntity item && !item.getItem().isEmpty() && e.position().distanceToSqr(eye) <= range * range) near.add(item);
        }
        if (near.isEmpty()) return;
        near.sort(Comparator.comparingDouble(e -> e.position().distanceToSqr(eye)));
        String beams = BEAMS.get();
        for (int i = 0; i < near.size(); i++) {
            ItemEntity item = near.get(i);
            ItemStack stack = item.getItem();
            int tier = tier(stack);
            int rgb = COLORS.is("One color") ? COLOR.get() : RARITY[tier];
            if (OUTLINE.get()) EspGlow.mark(item, rgb);
            if (i >= MOST_LIT) continue;

            // Where the game draws it: bobbing a little over where it lies.
            float bob = Mth.sin((item.getAge() + partial) / 10f + item.bobOffs) * 0.1f + 0.1f;
            Vec3 at = item.getPosition(partial);
            Vec3 middle = at.add(0, bob + 0.15, 0);
            if (LIGHT.get()) render.glow(middle, 0.5 + 0.12 * tier, Visuals.argb(rgb, 0.32 + 0.08 * tier), false);
            if (beams.equals("All") || beams.equals("Good items") && tier > 0) {
                Vec3 top = at.add(0, BEAM_HEIGHT.get(), 0);
                render.glowLine(at, top, Visuals.argb(rgb, 0.5), Visuals.argb(rgb, 0), 0.16, false);
                render.glowLine(at, top, Visuals.argb(rgb, 0.85), Visuals.argb(rgb, 0), 0.045, false);
            }
            if (NAMES.get()) {
                String text = stack.getHoverName().getString() + (stack.getCount() > 1 ? " x" + stack.getCount() : "");
                var font = mc.font;
                render.text(at.add(0, bob + 0.6, 0), WorldFx.facing(mc.gameRenderer.getMainCamera()), 0.02f,
                    -font.width(text) / 2f, -font.lineHeight, text, 0xFF000000 | rgb, 0x60000000, false);
            }
        }
    }

    /** 0 to 3, common to epic; good common items and shulker boxes count as rare, golden apples a step up from the game's. */
    static int tier(ItemStack stack) {
        if (stack.is(Items.ENCHANTED_GOLDEN_APPLE)) return 3;
        if (stack.is(Items.GOLDEN_APPLE)) return 1;
        int tier = switch (stack.getRarity()) {
            case COMMON -> 0;
            case UNCOMMON -> 1;
            case RARE -> 2;
            case EPIC -> 3;
        };
        if (tier == 0 && (GOOD.contains(stack.getItem()) || Block.byItem(stack.getItem()) instanceof ShulkerBoxBlock)) tier = 2;
        return tier;
    }
}
