package com.cyanite.client.modules.render;

import com.cyanite.client.render.Render3D;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.BlockListSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Boxes whichever blocks you pick, through walls, like Meteor's Block ESP: any block in the
 * game, chosen from a searchable list. Ores and spawners keep Search's colours; everything else
 * wears its own map colour, brightened so it shows, or one colour of your choosing.
 */
public class BlockESP extends Module {
    private static final int CHUNKS_PER_TICK = 4;
    private static final int MAX_PER_CHUNK = 512;
    private static final int MAX_DRAWN = 2000;
    private static final int SORT_INTERVAL = 10;

    private final BlockListSetting blocks = add(new BlockListSetting("Blocks", Blocks.SPAWNER));
    private final ModeSetting colorMode = add(new ModeSetting("Color", "Per block", List.of("Per block", "Custom")));
    private final ColorSetting color = add(new ColorSetting("Custom color", 0x5CF2E8));
    private final SliderSetting range = add(new SliderSetting("Range", 4, 1, 8, 1).format(v -> (int) v + " chunks"));
    private final EspStyle style = new EspStyle(false);

    private final Map<Long, List<Hit>> found = new HashMap<>();
    /** The picked blocks and the colour each is drawn in. */
    private Map<Block, Integer> targets = Map.of();
    private List<Hit> nearest = List.of();
    private int sortTicks;
    private boolean dirty = true;
    private int cursor;
    private int total;

    private record Hit(BlockPos pos, Block block) {
    }

    public BlockESP() {
        super(Category.RENDER, "Block ESP", "Highlights the blocks you pick through walls");
        style.settings().forEach(this::add);
        blocks.onChange(() -> dirty = true);
        colorMode.onChange(() -> dirty = true);
        color.onChange(() -> dirty = true);
    }

    @Override
    public String getInfo() {
        return Integer.toString(total);
    }

    @Override
    protected void onEnable() {
        dirty = true;
    }

    @Override
    protected void onDisable() {
        found.clear();
        nearest = List.of();
        total = 0;
    }

    @Override
    public void onTick() {
        if (dirty) {
            dirty = false;
            Map<Block, Integer> wanted = new IdentityHashMap<>();
            for (Block block : blocks.get()) {
                int rgb = colorOf(block);
                wanted.put(block, rgb);
                // A sign means wall signs too, a torch wall torches: every block that drops the same item.
                if (block.asItem() != Items.AIR) {
                    for (Block variant : byItem().getOrDefault(block.asItem(), List.of())) wanted.putIfAbsent(variant, rgb);
                }
            }
            // Only a colour change: keep what's been found, just draw it differently.
            if (!wanted.keySet().equals(targets.keySet())) {
                found.clear();
                nearest = List.of();
                cursor = 0;
            }
            targets = wanted;
        }

        // Walk the square round the player a few chunks a tick, so a full pass is spread out.
        ChunkPos center = mc.player.chunkPosition();
        int r = range.get().intValue();
        int side = r * 2 + 1;
        for (int i = 0; i < CHUNKS_PER_TICK; i++) {
            int index = cursor++ % (side * side);
            scan(center.x - r + index % side, center.z - r + index / side);
        }

        found.keySet().removeIf(key -> Math.max(Math.abs(ChunkPos.getX(key) - center.x), Math.abs(ChunkPos.getZ(key) - center.z)) > r);
        total = 0;
        for (List<Hit> hits : found.values()) total += hits.size();

        if (++sortTicks >= SORT_INTERVAL) {
            sortTicks = 0;
            BlockPos eye = BlockPos.containing(mc.player.getEyePosition());
            List<Hit> all = new ArrayList<>(total);
            for (List<Hit> hits : found.values()) all.addAll(hits);
            all.sort(Comparator.comparingDouble(hit -> hit.pos.distSqr(eye)));
            nearest = all.size() > MAX_DRAWN ? List.copyOf(all.subList(0, MAX_DRAWN)) : all;
        }
    }

    private void scan(int cx, int cz) {
        long key = ChunkPos.asLong(cx, cz);
        if (targets.isEmpty() || !mc.level.hasChunk(cx, cz)) {
            found.remove(key);
            return;
        }
        LevelChunk chunk = mc.level.getChunk(cx, cz);
        LevelChunkSection[] sections = chunk.getSections();
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < sections.length && hits.size() < MAX_PER_CHUNK; i++) {
            LevelChunkSection section = sections[i];
            // The palette check skips almost every section without touching a block.
            if (section.hasOnlyAir() || !section.maybeHas(state -> targets.containsKey(state.getBlock()))) continue;
            int baseY = chunk.getMinY() + i * 16;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (targets.containsKey(state.getBlock())) hits.add(new Hit(new BlockPos(cx * 16 + x, baseY + y, cz * 16 + z), state.getBlock()));
                    }
                }
            }
        }
        if (hits.isEmpty()) found.remove(key);
        else found.put(key, hits);
    }

    private static Map<Item, List<Block>> byItem;

    /** Every block, grouped by the item it gives. */
    private static Map<Item, List<Block>> byItem() {
        if (byItem == null) {
            Map<Item, List<Block>> map = new IdentityHashMap<>();
            for (Block block : BuiltInRegistries.BLOCK) map.computeIfAbsent(block.asItem(), i -> new ArrayList<>()).add(block);
            byItem = map;
        }
        return byItem;
    }

    private int colorOf(Block block) {
        if (colorMode.is("Custom")) return color.get();
        Ore ore = Ore.of(block);
        if (ore != null) return ore.color;
        int map = block.defaultMapColor().col;
        if (map == 0) return 0xFFFFFF;
        // Map colours are muted, made to sit on a map; lift them so a box stands out underground.
        float[] hsb = Color.RGBtoHSB((map >> 16) & 0xFF, (map >> 8) & 0xFF, map & 0xFF, null);
        return Color.HSBtoRGB(hsb[0], Math.min(1, hsb[1] * 1.15f), Math.max(hsb[2], 0.9f)) & 0xFFFFFF;
    }

    @Override
    public void onRender3D(Render3D render) {
        for (Hit hit : nearest) {
            Integer rgb = targets.get(hit.block);
            // Broken since the last scan: gone now, not when the scan comes round again.
            if (rgb != null && mc.level.getBlockState(hit.pos).is(hit.block)) style.draw(render, new AABB(hit.pos), rgb);
        }
    }
}
