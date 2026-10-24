package com.cyanite.client.modules.render;

import com.cyanite.client.render.Render3D;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Boxes chosen ores and spawners through walls. */
public class Search extends Module {
    private static final int CHUNKS_PER_TICK = 4;
    private static final int MAX_PER_CHUNK = 256;
    private static final int MAX_DRAWN = 2000;
    private static final int SORT_INTERVAL = 10;

    private final Map<Ore, BoolSetting> ores = new EnumMap<>(Ore.class);
    // Added after the ore toggles in the constructor, so it sits below them in the list.
    private final SliderSetting range = new SliderSetting("Range", 4, 1, 8, 1).format(v -> (int) v + " chunks");
    private final EspStyle style = new EspStyle(false);

    private final Map<Long, List<Hit>> found = new HashMap<>();
    private Set<Block> targets = Set.of();
    // What actually gets drawn: the closest matches, so a capped list still covers what's around you.
    private List<Hit> nearest = List.of();
    private int sortTicks;
    private boolean dirty = true;
    private int cursor;
    private int total;

    private record Hit(BlockPos pos, int rgb) {
    }

    public Search() {
        super(Category.RENDER, "Search", "Highlights ores and spawners through walls");
        for (Ore ore : Ore.values()) {
            BoolSetting setting = add(new BoolSetting(ore.title, ore.searchedByDefault));
            setting.onChange(() -> dirty = true);
            ores.put(ore, setting);
        }
        add(range);
        style.settings().forEach(this::add);
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
            Set<Block> wanted = Collections.newSetFromMap(new IdentityHashMap<>());
            ores.forEach((ore, setting) -> {
                if (setting.get()) wanted.addAll(ore.blocks);
            });
            targets = wanted;
            found.clear();
            cursor = 0;
        }

        // Walk the square around the player a few chunks per tick, so a full pass is spread out.
        ChunkPos center = mc.player.chunkPosition();
        int r = range.get().intValue();
        int side = r * 2 + 1;
        for (int i = 0; i < CHUNKS_PER_TICK; i++) {
            int index = cursor++ % (side * side);
            int cx = center.x - r + index % side;
            int cz = center.z - r + index / side;
            scan(cx, cz);
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
            // The palette check skips the vast majority of sections without touching a block.
            if (section.hasOnlyAir() || !section.maybeHas(state -> targets.contains(state.getBlock()))) continue;

            int baseY = chunk.getMinY() + i * 16;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (!targets.contains(state.getBlock())) continue;
                        hits.add(new Hit(new BlockPos(cx * 16 + x, baseY + y, cz * 16 + z), Ore.of(state.getBlock()).color));
                    }
                }
            }
        }

        if (hits.isEmpty()) found.remove(key);
        else found.put(key, hits);
    }

    @Override
    public void onRender3D(Render3D render) {
        for (Hit hit : nearest) style.draw(render, new AABB(hit.pos), hit.rgb);
    }
}
