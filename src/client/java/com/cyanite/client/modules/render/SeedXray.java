package com.cyanite.client.modules.render;

import com.cyanite.client.modules.render.seedxray.Predictor;
import com.cyanite.client.render.Render3D;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.Depths;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.BlockListSetting;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.SliderSetting;
import com.cyanite.client.system.setting.StringSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Boxes the blocks the world's seed put around you, worked out from vanilla's own worldgen, so it
 * finds them where the server hides them. The blocks loaded around you can rule a guess out (it's
 * been mined, or it was never there); hidden-ore servers show stone where an ore is, so that can't.
 */
public class SeedXray extends Module {
    private static final int MAX_DRAWN = 2000;
    private static final int SORT_INTERVAL = 10;
    private static final int OTHER_COLOR = 0x8CF0FF;

    private final StringSetting seed = add(new StringSetting("Seed", "", 32).placeholder("World seed"));
    private final BlockListSetting blocks = add(new BlockListSetting("Blocks", Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.ANCIENT_DEBRIS));
    private final SliderSetting range = add(new SliderSetting("Range", 4, 1, 8, 1).format(v -> (int) v + " chunks"));
    private final ModeSetting dimension = add(new ModeSetting("Dimension", "Auto", List.of("Auto", "Overworld", "Nether", "End")));
    private final BoolSetting hideMined = add(new BoolSetting("Hide mined", true));
    private final EspStyle style = new EspStyle(false);

    private final Predictor predictor = new Predictor();
    /** Everything the thread looks for: the chosen blocks and every ore, so picking another ore needs no new pass. */
    private Set<Block> wanted = Set.of();
    private Set<Block> shown = Set.of();
    private List<Hit> nearest = List.of();
    private int sortTicks;
    private int total;

    private record Hit(BlockPos pos, int rgb) {
    }

    public SeedXray() {
        super(Category.RENDER, "Seed Xray", "Shows where the seed put ores, even where the server hides them");
        style.settings().forEach(this::add);
        blocks.onChange(this::pickBlocks);
        pickBlocks();
    }

    private void pickBlocks() {
        Set<Block> chosen = Collections.newSetFromMap(new IdentityHashMap<>());
        chosen.addAll(blocks.get());
        shown = chosen;
        Set<Block> all = Collections.newSetFromMap(new IdentityHashMap<>());
        all.addAll(chosen);
        for (Ore ore : Ore.values()) all.addAll(ore.blocks);
        wanted = Set.copyOf(all);
        sortTicks = SORT_INTERVAL;
    }

    @Override
    public String getInfo() {
        if (seed.isBlank()) return "no seed";
        String status = predictor.status();
        if (status.equals("generating")) return total + " (" + predictor.pendingTiles() + " left)";
        return status.isEmpty() ? Integer.toString(total) : status;
    }

    @Override
    protected void onDisable() {
        predictor.want(null);
        nearest = List.of();
        total = 0;
    }

    @Override
    public void onTick() {
        Throwable failure = predictor.takeFailure();
        if (failure != null) throw new IllegalStateException("Seed Xray couldn't generate the world", failure);

        OptionalLong worldSeed = WorldOptions.parseSeed(seed.get().trim());
        ResourceKey<LevelStem> stem = stem();
        if (worldSeed.isEmpty() || stem == null) {
            predictor.want(null);
            nearest = List.of();
            total = 0;
            return;
        }
        ChunkPos center = mc.player.chunkPosition();
        int r = range.get().intValue();
        predictor.want(new Predictor.Request(worldSeed.getAsLong(), stem, wanted, center.x, center.z, r));

        if (++sortTicks >= SORT_INTERVAL) {
            sortTicks = 0;
            gather(center, r);
        }
    }

    /** The predicted blocks in range, minus the ones the world shows aren't there, nearest first. */
    private void gather(ChunkPos center, int r) {
        BlockPos eye = BlockPos.containing(mc.player.getEyePosition());
        Map<Long, Integer> floors = new HashMap<>();
        List<Hit> all = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int cx = center.x - r; cx <= center.x + r; cx++) {
            for (int cz = center.z - r; cz <= center.z + r; cz++) {
                Predictor.Found found = predictor.found(cx, cz);
                if (found == null) continue;
                for (int i = 0; i < found.positions().length; i++) {
                    Block block = found.blocks()[i];
                    if (!shown.contains(block)) continue;
                    pos.set(found.positions()[i]);
                    if (hideMined.get() && ruledOut(pos, block, floors)) continue;
                    Ore ore = Ore.of(block);
                    all.add(new Hit(pos.immutable(), ore != null ? ore.color : OTHER_COLOR));
                }
            }
        }
        total = all.size();
        all.sort(Comparator.comparingDouble(hit -> hit.pos.distSqr(eye)));
        nearest = all.size() > MAX_DRAWN ? List.copyOf(all.subList(0, MAX_DRAWN)) : all;
    }

    /**
     * Whether the loaded world shows the block isn't there. Air, water or a block nothing would
     * disguise it as means it's been mined (or the guess was wrong). Stone or another ore could be
     * a server hiding it, unless it's next to open air: hidden ores show once they're exposed.
     * Where the world can't be seen (not loaded, nothing sent, made-up layers) it stays.
     */
    private boolean ruledOut(BlockPos pos, Block predicted, Map<Long, Integer> floors) {
        LevelChunk chunk = mc.level.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false);
        if (chunk == null) return false;
        BlockState real = seen(chunk, pos);
        if (real == null || real.is(predicted)) return false;
        int madeUp = floors.computeIfAbsent(chunk.getPos().toLong(), k -> Depths.madeUpTop(chunk));
        if (pos.getY() < madeUp) return false;
        if (!couldHide(real)) return true;
        BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();
        for (Direction direction : Direction.values()) {
            next.setWithOffset(pos, direction);
            LevelChunk nextChunk = mc.level.getChunkSource().getChunk(next.getX() >> 4, next.getZ() >> 4, ChunkStatus.FULL, false);
            BlockState beside = nextChunk == null ? null : seen(nextChunk, next);
            if (beside != null && beside.isAir()) return true;
        }
        return false;
    }

    /** The block the server sent, or null where it sent nothing (an empty section reads as air). */
    private static @Nullable BlockState seen(LevelChunk chunk, BlockPos pos) {
        int index = chunk.getSectionIndex(pos.getY());
        if (index < 0 || index >= chunk.getSectionsCount()) return null;
        LevelChunkSection section = chunk.getSection(index);
        if (section.hasOnlyAir()) return null;
        return section.getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
    }

    /** What a hidden-ore server shows in an ore's place: plain stone of the dimension, or a made-up ore. */
    private static boolean couldHide(BlockState state) {
        return state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.BASE_STONE_NETHER) || state.is(Blocks.END_STONE)
            || Ore.of(state.getBlock()) != null;
    }

    private @Nullable ResourceKey<LevelStem> stem() {
        switch (dimension.get()) {
            case "Overworld" -> {
                return LevelStem.OVERWORLD;
            }
            case "Nether" -> {
                return LevelStem.NETHER;
            }
            case "End" -> {
                return LevelStem.END;
            }
            default -> {
            }
        }
        ResourceKey<Level> key = mc.level.dimension();
        if (key.equals(Level.OVERWORLD)) return LevelStem.OVERWORLD;
        if (key.equals(Level.NETHER)) return LevelStem.NETHER;
        if (key.equals(Level.END)) return LevelStem.END;
        // A server's own name for a world: go by what kind of world it is.
        DimensionType type = mc.level.dimensionType();
        if (type.hasCeiling()) return LevelStem.NETHER;
        return type.minY() < 0 ? LevelStem.OVERWORLD : LevelStem.END;
    }

    @Override
    public void onRender3D(Render3D render) {
        for (Hit hit : nearest) style.draw(render, new AABB(hit.pos), hit.rgb);
    }
}
