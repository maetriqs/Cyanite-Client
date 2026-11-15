package com.cyanite.client.system;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DecoratedPotBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Every block entity the server names, kept as it names it. A chunk comes with a list of its block
 * entities (spawners with their mob, chests, signs...), and the client only keeps the ones whose block
 * it was told is the right one. A server that hides what's underground (Donut fakes everything under
 * Y 16 as deepslate while you're up top) has to strip that list too; when it doesn't, the spawner under
 * the made-up rock is still named, and the client just drops it. Those are the leaks: named by the
 * server, missing from the client's world. Block entity updates can leak the same way, and so can
 * the events a block sends out (a chest opening, a spawner spawning) at a spot the client has as rock.
 *
 * <p>Fed from {@code BlockEntityLeakMixin}, at the tail of the packet handlers, which only the game
 * thread reaches; everything here is game-thread only. It's always on: the server sends a chunk once,
 * so a leak missed can't be had again later. Leaks are kept after their chunk unloads, until the world
 * changes; the ones the client has too go with their chunk, and come back with it.
 *
 * <p>The method is the Anubis client's (GPL-3.0, its block entity leak tracker); the code is Cyanite's own.
 */
public final class BlockEntityLeaks {
    /** Past this many leaks, ones in chunks that unload are let go too. */
    private static final int KEEP_LEAKS = 20_000;

    /** How the server named it. */
    public enum Source {
        /** In a chunk's list of block entities. */
        CHUNK,
        /** In a block entity update. */
        UPDATE,
        /** By something it did: a chest opening, a spawner spawning, a dispenser firing. */
        EVENT
    }

    /** A block entity the server named, at one spot. */
    public static final class Found {
        private final BlockPos pos;
        private final BlockEntityType<?> type;
        private final CompoundTag tag;
        private Source source;
        private String event;
        private boolean hidden;
        private BlockState disguise;

        private Found(BlockPos pos, BlockEntityType<?> type, CompoundTag tag, Source source) {
            this.pos = pos;
            this.type = type;
            this.tag = tag;
            this.source = source;
        }

        public BlockPos pos() {
            return pos;
        }

        public BlockEntityType<?> type() {
            return type;
        }

        /** What the server sent with it (a spawner's mob, a sign's text), or null for nothing. */
        public CompoundTag tag() {
            return tag;
        }

        public Source source() {
            return source;
        }

        /** What it did, when an event gave it away ("opened", "spawned a mob"); else null. */
        public String event() {
            return event;
        }

        /** The leak: the client doesn't have it, because it was told the block there is something else. */
        public boolean hidden() {
            return hidden;
        }

        /** What the server last said is at its spot instead (the made-up rock), while it's hidden; else null. */
        public BlockState disguise() {
            return hidden ? disguise : null;
        }

        /** Its type's id, "mob_spawner", "chest". */
        public String id() {
            Identifier key = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
            return key == null ? "" : key.getPath();
        }
    }

    private static final Long2ObjectOpenHashMap<Long2ObjectOpenHashMap<Found>> BY_CHUNK = new Long2ObjectOpenHashMap<>();
    /** The block entity type each block event's block has, looked up once. */
    private static final Map<Block, Optional<BlockEntityType<?>>> TYPE_OF = new HashMap<>();
    /** The world it all belongs to: weakly, so a world you've left isn't kept in memory by this. */
    private static WeakReference<ClientLevel> world = new WeakReference<>(null);
    private static int leaks;
    private static int generation;

    private BlockEntityLeaks() {
    }

    // ---- Reading

    /** Every one known in this world, leaks and the client's own. Don't feed anything in from inside. */
    public static void forEach(Consumer<Found> action) {
        if (!sync()) return;
        for (Long2ObjectOpenHashMap<Found> in : BY_CHUNK.values()) {
            for (Found f : in.values()) action.accept(f);
        }
    }

    /** The one at {@code pos}, or null. */
    public static Found at(BlockPos pos) {
        if (!sync()) return null;
        Long2ObjectOpenHashMap<Found> in = BY_CHUNK.get(ChunkPos.asLong(pos));
        return in == null ? null : in.get(pos.asLong());
    }

    /** How many leaks are known in this world. */
    public static int leaks() {
        return sync() ? leaks : 0;
    }

    /** Goes up every time the world changes and everything is forgotten. */
    public static int generation() {
        sync();
        return generation;
    }

    /** The mob a spawner spawns, from what the server sent with it; null if it sent none. */
    public static EntityType<?> mob(Found found) {
        if (found.tag == null) return null;
        String id = found.tag.getCompoundOrEmpty("SpawnData").getCompoundOrEmpty("entity").getStringOr("id", "");
        Identifier key = id.isEmpty() ? null : Identifier.tryParse(id);
        return key == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(key).orElse(null);
    }

    // ---- What comes in (game thread, from BlockEntityLeakMixin)

    /** A chunk, just taken in: what its list names, against what the client now has there. */
    public static void onChunk(int x, int z, ClientboundLevelChunkPacketData data) {
        if (!sync()) return;
        // Out of what the client keeps: it took nothing in, so there's nothing to compare.
        LevelChunk chunk = level().getChunkSource().getChunk(x, z, false);
        if (chunk == null) return;
        long key = ChunkPos.asLong(x, z);
        // The list is the server's word for the whole chunk: what's not on it now is gone.
        Long2ObjectOpenHashMap<Found> old = BY_CHUNK.remove(key);
        if (old != null) {
            for (Found f : old.values()) if (f.hidden) leaks--;
        }
        data.getBlockEntitiesTagsConsumer(x, z).accept((pos, type, tag) -> {
            if (type == null) return;
            // What it did, when that's how it first gave itself away, says more than "named in the chunk".
            Found was = old == null ? null : old.get(pos.asLong());
            boolean event = was != null && was.type == type && was.source == Source.EVENT;
            Found f = new Found(pos.immutable(), type, tag, event ? Source.EVENT : Source.CHUNK);
            if (event) f.event = was.event;
            f.disguise = chunk.getBlockState(pos);
            f.hidden = !type.isValid(f.disguise);
            add(key, f);
        });
        // Except what gave itself away some other way: a server that strips the list doesn't make those untrue.
        if (old != null) {
            for (Found f : old.values()) {
                if (f.source == Source.EVENT && !chunk.getBlockState(f.pos).hasBlockEntity() && at(f.pos) == null) {
                    f.disguise = chunk.getBlockState(f.pos);
                    f.hidden = true;
                    add(key, f);
                }
            }
        }
    }

    /** A block entity update. */
    public static void onBlockEntityData(BlockPos pos, BlockEntityType<?> type, CompoundTag tag) {
        if (!sync() || !level().hasChunkAt(pos)) return;
        Found was = at(pos);
        boolean same = was != null && was.type == type;
        // An update that carries nothing keeps what was known (a spawner's mob).
        CompoundTag keep = tag != null && !tag.isEmpty() ? tag : same ? was.tag : null;
        Found f = new Found(pos.immutable(), type, keep, same ? was.source : Source.UPDATE);
        if (same) f.event = was.event;
        f.disguise = level().getBlockState(pos);
        f.hidden = !type.isValid(f.disguise);
        add(ChunkPos.asLong(pos), f);
    }

    /** A block changing, one update or one of a batch. */
    public static void onBlockChanged(BlockPos pos, BlockState state) {
        if (!sync()) return;
        Long2ObjectOpenHashMap<Found> in = BY_CHUNK.get(ChunkPos.asLong(pos));
        if (in == null) return;
        Found f = in.get(pos.asLong());
        if (f == null) return;
        if (f.type.isValid(state)) {
            // Shown after all (you went down to it).
            hide(f, false);
        } else if (state.isAir() || state.hasBlockEntity()) {
            // Broken, or something else put there: gone for real (a new one gets named).
            remove(in, ChunkPos.asLong(pos), f);
        } else {
            // Swapped for rock: the server hiding it again.
            f.disguise = state;
            hide(f, true);
        }
    }

    /** A batch of block changes in one section: only looked through when there's anything to look for. */
    public static void onBlocksChanged(ClientboundSectionBlocksUpdatePacket packet) {
        if (!sync() || BY_CHUNK.isEmpty()) return;
        packet.runUpdates(BlockEntityLeaks::onBlockChanged);
    }

    /** A chunk unloading: the client's own go with it, leaks stay (the server may not name them again). */
    public static void onForget(ChunkPos pos) {
        if (!sync()) return;
        long key = pos.toLong();
        Long2ObjectOpenHashMap<Found> in = BY_CHUNK.get(key);
        if (in == null) return;
        boolean keep = leaks <= KEEP_LEAKS;
        in.values().removeIf(f -> {
            if (f.hidden && keep) return false;
            if (f.hidden) leaks--;
            return true;
        });
        if (in.isEmpty()) BY_CHUNK.remove(key);
    }

    /** A block event: a chest opening, a spawner resetting after a spawn, a bell. It names its block. */
    public static void onBlockEvent(BlockPos pos, Block block, int b0, int b1) {
        if (!sync() || !level().hasChunkAt(pos) || level().getBlockState(pos).is(block)) return;
        BlockEntityType<?> type = TYPE_OF.computeIfAbsent(block, BlockEntityLeaks::typeOf).orElse(null);
        if (type == null) return;
        String what = block == Blocks.SPAWNER ? "spawned mobs"
            : block instanceof BellBlock ? "rang"
            : block instanceof ChestBlock || block instanceof EnderChestBlock || block instanceof ShulkerBoxBlock ? b1 > 0 ? "opened" : "closed"
            : block instanceof DecoratedPotBlock ? "wobbled"
            : "went off";
        event(pos, type, what);
    }

    /** A level event the server sends from a block entity's spot. */
    public static void onLevelEvent(int event, BlockPos pos) {
        BlockEntityType<?> type;
        String what;
        switch (event) {
            case LevelEvent.PARTICLES_MOBBLOCK_SPAWN -> {
                type = BlockEntityType.MOB_SPAWNER;
                what = "spawned a mob";
            }
            case LevelEvent.SOUND_DISPENSER_DISPENSE, LevelEvent.SOUND_DISPENSER_FAIL, LevelEvent.SOUND_DISPENSER_PROJECTILE_LAUNCH,
                 LevelEvent.PARTICLES_SHOOT_SMOKE -> {
                // Droppers send the same: it's one or the other.
                type = BlockEntityType.DISPENSER;
                what = "fired";
            }
            case LevelEvent.SOUND_PLAY_JUKEBOX_SONG -> {
                type = BlockEntityType.JUKEBOX;
                what = "played a disc";
            }
            case LevelEvent.SOUND_CRAFTER_CRAFT, LevelEvent.SOUND_CRAFTER_FAIL -> {
                type = BlockEntityType.CRAFTER;
                what = "crafted";
            }
            case LevelEvent.SOUND_BREWING_STAND_BREW -> {
                type = BlockEntityType.BREWING_STAND;
                what = "brewed";
            }
            default -> {
                return;
            }
        }
        if (!sync() || !level().hasChunkAt(pos) || level().getBlockState(pos).hasBlockEntity()) return;
        event(pos, type, what);
    }

    // ---- Keeping

    /** Forgets the last world's: a spot in one world means nothing in another. */
    private static boolean sync() {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) return false;
        if (mc.level != world.get()) {
            BY_CHUNK.clear();
            leaks = 0;
            world = new WeakReference<>(mc.level);
            generation++;
        }
        return mc.level != null;
    }

    /** The client's world, once sync() has said there is one. Not kept: a world you've left mustn't stay in memory. */
    private static ClientLevel level() {
        return Minecraft.getInstance().level;
    }

    private static void event(BlockPos pos, BlockEntityType<?> type, String what) {
        Found was = at(pos);
        if (was != null && was.type == type && was.hidden) return;
        Found f = new Found(pos.immutable(), type, was != null && was.type == type ? was.tag : null, Source.EVENT);
        f.event = what;
        f.disguise = level().getBlockState(pos);
        f.hidden = true;
        add(ChunkPos.asLong(pos), f);
    }

    private static void add(long chunk, Found f) {
        Found was = BY_CHUNK.computeIfAbsent(chunk, k -> new Long2ObjectOpenHashMap<>()).put(f.pos.asLong(), f);
        if (was != null && was.hidden) leaks--;
        if (f.hidden) leaks++;
    }

    private static void remove(Long2ObjectOpenHashMap<Found> in, long chunk, Found f) {
        in.remove(f.pos.asLong());
        if (f.hidden) leaks--;
        if (in.isEmpty()) BY_CHUNK.remove(chunk);
    }

    private static void hide(Found f, boolean hidden) {
        if (f.hidden == hidden) return;
        f.hidden = hidden;
        leaks += hidden ? 1 : -1;
    }

    private static Optional<BlockEntityType<?>> typeOf(Block block) {
        BlockState state = block.defaultBlockState();
        if (!state.hasBlockEntity()) return Optional.empty();
        for (BlockEntityType<?> type : BuiltInRegistries.BLOCK_ENTITY_TYPE) {
            if (type.isValid(state)) return Optional.of(type);
        }
        return Optional.empty();
    }
}
