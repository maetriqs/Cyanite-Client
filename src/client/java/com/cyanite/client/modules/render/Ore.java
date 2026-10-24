package com.cyanite.client.modules.render;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The blocks Xray and Search care about, grouped the way players think of them. */
enum Ore {
    DIAMOND("Diamond", 0x5CF2E8, true, Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE),
    ANCIENT_DEBRIS("Ancient debris", 0xB0714F, true, Blocks.ANCIENT_DEBRIS),
    EMERALD("Emerald", 0x3DDC6A, true, Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE),
    GOLD("Gold", 0xF5C542, false, Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.NETHER_GOLD_ORE),
    IRON("Iron", 0xDDB59A, false, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE),
    REDSTONE("Redstone", 0xFF4A4A, false, Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE),
    LAPIS("Lapis", 0x3F6FE8, false, Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE),
    COPPER("Copper", 0xE07A4C, false, Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE),
    COAL("Coal", 0x6B6B6B, false, Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE),
    QUARTZ("Quartz", 0xEDE6DD, false, Blocks.NETHER_QUARTZ_ORE),
    SPAWNER("Spawners", 0xB45CFF, true, Blocks.SPAWNER);

    private static final Map<Block, Ore> BY_BLOCK = new HashMap<>();

    static {
        for (Ore ore : values()) {
            for (Block block : ore.blocks) BY_BLOCK.put(block, ore);
        }
    }

    final String title;
    final int color;
    /** Whether Search looks for it out of the box. Xray shows every ore by default. */
    final boolean searchedByDefault;
    final List<Block> blocks;

    Ore(String title, int color, boolean searchedByDefault, Block... blocks) {
        this.title = title;
        this.color = color;
        this.searchedByDefault = searchedByDefault;
        this.blocks = List.of(blocks);
    }

    static Ore of(Block block) {
        return BY_BLOCK.get(block);
    }
}
