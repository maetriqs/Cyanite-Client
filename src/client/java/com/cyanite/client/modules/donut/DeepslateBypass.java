package com.cyanite.client.modules.donut;

import com.cyanite.client.system.BlockEntityLeaks;
import com.cyanite.client.system.BlockEntityLeaks.Found;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Notify;
import com.cyanite.client.system.setting.BoolSetting;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Leak Finder's finds put back in the world: where the server named a spawner or a chest but sent
 * made-up deepslate, the client gets the spawner (with its mob turning inside) or the chest instead, so
 * you see what's really there rather than what the server wants you to load. It's still in the server's
 * rock, so from the surface it takes Freecam, or Xray for spawners.
 *
 * <p>It only ever shows what {@link BlockEntityLeaks} still holds as hidden, and never holds back a packet:
 * when the server breaks one, or shows it for real, its word wins and the spot is let go; when it sends its
 * rock again, the real block goes back a moment later. Switched off, every spot gets the server's rock back.
 *
 * <p>Doesn't work on every server: one that strips the block entity list as well as faking the blocks
 * leaks nothing, so there's nothing to put back.
 */
public class DeepslateBypass extends Module {
    private static final int EVERY = 4;

    private final BoolSetting spawners = add(new BoolSetting("Spawners", true));
    private final BoolSetting storage = add(new BoolSetting("Storage", true));
    private final BoolSetting other = add(new BoolSetting("Other blocks", true));
    private final BoolSetting natural = add(new BoolSetting("Natural blocks", false));

    /** The block each block entity type is put back as, looked up once. */
    private static final Map<BlockEntityType<?>, Optional<BlockState>> REAL = new HashMap<>();

    /** Spots this put a real block in, with the block it put there. */
    private final Long2ObjectOpenHashMap<BlockState> placed = new Long2ObjectOpenHashMap<>();
    private int generation = -1;
    private int ticks;

    public DeepslateBypass() {
        super(Category.DONUT, "Deepslate Bypass", "Shows the spawners and chests Leak Finder finds in place of the made-up deepslate (doesn't work on every server)");
        spawners.onChange(this::changed);
        storage.onChange(this::changed);
        other.onChange(this::changed);
        natural.onChange(this::changed);
    }

    @Override
    public String getInfo() {
        return Integer.toString(placed.size());
    }

    @Override
    public boolean hiddenInStealth() {
        return true;
    }

    @Override
    protected void onEnable() {
        Notify.send("Deepslate Bypass", "Doesn't work on every server: it only shows what the server still names under the made-up rock (see Leak Finder).", true, false, false);
        ticks = EVERY;
    }

    @Override
    protected void onDisable() {
        restoreAll();
    }

    @Override
    public void onTick() {
        // A new world: the spots of the last one mean nothing here, and there's nothing to put back.
        if (BlockEntityLeaks.generation() != generation) {
            generation = BlockEntityLeaks.generation();
            placed.clear();
        }
        if (++ticks < EVERY) return;
        ticks = 0;
        update();
    }

    private void changed() {
        if (!isEnabled() || !inGame()) return;
        restoreAll();
        ticks = EVERY;
    }

    private void update() {
        // Spots the server has had its say on since: shown for real, broken, or its rock sent again.
        var it = placed.long2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            Long2ObjectMap.Entry<BlockState> e = it.next();
            BlockPos pos = BlockPos.of(e.getLongKey());
            Found f = BlockEntityLeaks.at(pos);
            if (f == null || !f.hidden() || mc.level.getBlockState(pos) != e.getValue()) it.remove();
        }
        List<Found> todo = new ArrayList<>();
        BlockEntityLeaks.forEach(f -> {
            if (!f.hidden() || !wanted(f) || !mc.level.hasChunkAt(f.pos())) return;
            if (!f.type().isValid(mc.level.getBlockState(f.pos()))) todo.add(f);
        });
        for (Found f : todo) place(f);
    }

    private boolean wanted(Found f) {
        if (f.type() == BlockEntityType.PISTON) return false;
        return switch (LeakFinder.Kind.of(f.id())) {
            case SPAWNER -> spawners.get();
            case CHEST, BARREL, SHULKER, ENDER, STORAGE -> storage.get();
            case OTHER -> other.get();
            case NATURAL -> natural.get();
        };
    }

    private void place(Found f) {
        BlockState real = REAL.computeIfAbsent(f.type(), DeepslateBypass::realOf).orElse(null);
        if (real == null) return;
        BlockPos pos = f.pos();
        // No neighbour shapes: a chest put back mustn't reach out and join the server's blocks.
        if (!mc.level.setBlock(pos, real, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE)) return;
        BlockEntity entity = mc.level.getBlockEntity(pos);
        // What the server sent with it: a spawner's mob, a sign's text.
        if (entity != null && entity.getType() == f.type() && f.tag() != null) {
            entity.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, mc.level.registryAccess(), f.tag()));
        }
        placed.put(pos.asLong(), mc.level.getBlockState(pos));
    }

    /** The server's rock back in every spot that still has what this put there. */
    private void restoreAll() {
        if (inGame() && BlockEntityLeaks.generation() == generation) {
            for (Long2ObjectMap.Entry<BlockState> e : placed.long2ObjectEntrySet()) {
                BlockPos pos = BlockPos.of(e.getLongKey());
                Found f = BlockEntityLeaks.at(pos);
                if (f == null || !f.hidden() || f.disguise() == null || mc.level.getBlockState(pos) != e.getValue()) continue;
                mc.level.setBlock(pos, f.disguise(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
        }
        placed.clear();
    }

    /** The block a type belongs to: the one of its own name if it has one (chest, barrel), else its first. */
    private static Optional<BlockState> realOf(BlockEntityType<?> type) {
        if (type == BlockEntityType.MOB_SPAWNER) return Optional.of(Blocks.SPAWNER.defaultBlockState());
        Identifier id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
        Block first = null;
        for (Block block : BuiltInRegistries.BLOCK) {
            if (!type.isValid(block.defaultBlockState())) continue;
            if (BuiltInRegistries.BLOCK.getKey(block).equals(id)) return Optional.of(block.defaultBlockState());
            if (first == null) first = block;
        }
        return Optional.ofNullable(first).map(Block::defaultBlockState);
    }
}
