package com.cyanite.client.modules.render.seedxray;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.attribute.EnvironmentAttributeReader;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.LevelTickAccess;
import net.minecraft.world.ticks.WorldGenTickAccess;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Just enough of a world for vanilla's terrain and feature code to run in: the chunks it's given,
 * held in memory, with no server behind them. Modelled on the server's WorldGenRegion. Writes only
 * land within a chunk of the one being decorated, as on a server; anything that needs the server
 * itself (fossils, which load structure files) gets null and fails on its own.
 */
final class GenLevel implements WorldGenLevel {
    private static final Identifier REGION_RANDOM = Identifier.withDefaultNamespace("worldgen_region_random");

    private final SeedWorld world;
    private final Long2ObjectMap<ProtoChunk> chunks = new Long2ObjectOpenHashMap<>();
    private final BiomeManager biomeManager;
    private final WorldGenTickAccess<Block> blockTicks = new WorldGenTickAccess<>(pos -> getChunk(pos).getBlockTicks());
    private final WorldGenTickAccess<Fluid> fluidTicks = new WorldGenTickAccess<>(pos -> getChunk(pos).getFluidTicks());
    private final WorldBorder border = new WorldBorder();
    private final AtomicLong subTicks = new AtomicLong();
    private ChunkPos center = ChunkPos.ZERO;
    private RandomSource random;

    GenLevel(SeedWorld world) {
        this.world = world;
        this.biomeManager = new BiomeManager(this, BiomeManager.obfuscateSeed(world.seed));
        this.random = world.randomState.getOrCreateRandomFactory(REGION_RANDOM).at(center.getWorldPosition());
    }

    void put(ProtoChunk chunk) {
        chunks.put(chunk.getPos().toLong(), chunk);
    }

    @Nullable ProtoChunk chunk(int x, int z) {
        return chunks.get(ChunkPos.asLong(x, z));
    }

    /** The chunk being decorated: writes go only as far as the chunks around it. */
    void center(ChunkPos pos) {
        center = pos;
        random = world.randomState.getOrCreateRandomFactory(REGION_RANDOM).at(pos.getWorldPosition());
    }

    @Override
    public ChunkAccess getChunk(int x, int z) {
        return getChunk(x, z, ChunkStatus.EMPTY);
    }

    @Override
    public @Nullable ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean required) {
        ProtoChunk chunk = chunks.get(ChunkPos.asLong(x, z));
        if (chunk == null && required) throw new IllegalStateException("Chunk " + x + ", " + z + " is outside what's generated");
        return chunk;
    }

    @Override
    public boolean hasChunk(int x, int z) {
        return chunks.containsKey(ChunkPos.asLong(x, z));
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return getChunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ())).getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getChunk(pos).getFluidState(pos);
    }

    @Override
    public boolean ensureCanWrite(BlockPos pos) {
        int x = SectionPos.blockToSectionCoord(pos.getX());
        int z = SectionPos.blockToSectionCoord(pos.getZ());
        return Math.abs(center.x - x) <= 1 && Math.abs(center.z - z) <= 1 && hasChunk(x, z);
    }

    @Override
    public boolean setBlock(BlockPos pos, BlockState state, @Block.UpdateFlags int flags, int recursionLeft) {
        if (!ensureCanWrite(pos)) return false;
        ChunkAccess chunk = getChunk(pos);
        BlockState old = chunk.setBlockState(pos, state, flags);
        if (state.hasBlockEntity()) {
            // As on a server, a proto-chunk only notes that a block entity belongs here.
            CompoundTag tag = new CompoundTag();
            tag.putInt("x", pos.getX());
            tag.putInt("y", pos.getY());
            tag.putInt("z", pos.getZ());
            tag.putString("id", "DUMMY");
            chunk.setBlockEntityNbt(tag);
        } else if (old != null && old.hasBlockEntity()) {
            chunk.removeBlockEntity(pos);
        }
        if (state.hasPostProcess(this, pos) && (flags & Block.UPDATE_KNOWN_SHAPE) == 0) chunk.markPosForPostprocessing(pos);
        return true;
    }

    @Override
    public boolean removeBlock(BlockPos pos, boolean movedByPiston) {
        return setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    @Override
    public boolean destroyBlock(BlockPos pos, boolean drop, @Nullable Entity entity, int recursionLeft) {
        if (getBlockState(pos).isAir()) return false;
        return setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL, recursionLeft);
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
        ChunkAccess chunk = getChunk(pos);
        BlockEntity entity = chunk.getBlockEntity(pos);
        if (entity != null) return entity;
        CompoundTag tag = chunk.getBlockEntityNbt(pos);
        BlockState state = chunk.getBlockState(pos);
        if (tag == null || !"DUMMY".equals(tag.getStringOr("id", "")) || !state.hasBlockEntity()) return null;
        entity = ((EntityBlock) state.getBlock()).newBlockEntity(pos, state);
        if (entity != null) chunk.setBlockEntity(entity);
        return entity;
    }

    @Override
    public boolean addFreshEntity(Entity entity) {
        return true;
    }

    @Override
    public BiomeManager getBiomeManager() {
        return biomeManager;
    }

    @Override
    public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) {
        return world.generator.getBiomeSource().getNoiseBiome(x, y, z, world.randomState.sampler());
    }

    @Override
    public long getSeed() {
        return world.seed;
    }

    @Override
    public RegistryAccess registryAccess() {
        return world.access;
    }

    @Override
    public FeatureFlagSet enabledFeatures() {
        return FeatureFlags.VANILLA_SET;
    }

    @Override
    public DimensionType dimensionType() {
        return world.dimensionType;
    }

    @Override
    public int getMinY() {
        return world.height.getMinY();
    }

    @Override
    public int getHeight() {
        return world.height.getHeight();
    }

    @Override
    public int getSeaLevel() {
        return world.generator.getSeaLevel();
    }

    @Override
    public int getHeight(Heightmap.Types type, int x, int z) {
        return getChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z)).getHeight(type, x & 15, z & 15) + 1;
    }

    @Override
    public RandomSource getRandom() {
        return random;
    }

    @Override
    public LevelTickAccess<Block> getBlockTicks() {
        return blockTicks;
    }

    @Override
    public LevelTickAccess<Fluid> getFluidTicks() {
        return fluidTicks;
    }

    @Override
    public long nextSubTickCount() {
        return subTicks.getAndIncrement();
    }

    @Override
    public DifficultyInstance getCurrentDifficultyAt(BlockPos pos) {
        return new DifficultyInstance(Difficulty.NORMAL, 0L, 0L, 0f);
    }

    @Override
    public WorldBorder getWorldBorder() {
        return border;
    }

    @Override
    public boolean isClientSide() {
        return false;
    }

    @Override
    public boolean isStateAtPosition(BlockPos pos, Predicate<BlockState> predicate) {
        return predicate.test(getBlockState(pos));
    }

    @Override
    public boolean isFluidAtPosition(BlockPos pos, Predicate<FluidState> predicate) {
        return predicate.test(getFluidState(pos));
    }

    @Override
    public EnvironmentAttributeReader environmentAttributes() {
        return EnvironmentAttributeReader.EMPTY;
    }

    // ---- What there's no server for

    @SuppressWarnings("deprecation")
    @Override
    public @Nullable ServerLevel getLevel() {
        return null;
    }

    @Override
    public @Nullable MinecraftServer getServer() {
        return null;
    }

    @Override
    public @Nullable ChunkSource getChunkSource() {
        return null;
    }

    /** The time, for fluid ticks a lake or spring schedules; nothing else here asks. */
    @Override
    public LevelData getLevelData() {
        return FIRST_TICK;
    }

    private static final LevelData FIRST_TICK = new LevelData() {
        @Override
        public RespawnData getRespawnData() {
            return RespawnData.DEFAULT;
        }

        @Override
        public long getGameTime() {
            return 0;
        }

        @Override
        public long getDayTime() {
            return 0;
        }

        @Override
        public boolean isThundering() {
            return false;
        }

        @Override
        public boolean isRaining() {
            return false;
        }

        @Override
        public void setRaining(boolean raining) {
        }

        @Override
        public boolean isHardcore() {
            return false;
        }

        @Override
        public Difficulty getDifficulty() {
            return Difficulty.NORMAL;
        }

        @Override
        public boolean isDifficultyLocked() {
            return false;
        }
    };

    /** Dark everywhere, as unlit chunks are on a server (Nether mushrooms ask before they're placed). */
    @Override
    public LevelLightEngine getLightEngine() {
        return LevelLightEngine.EMPTY;
    }

    @Override
    public @Nullable Player getNearestPlayer(double x, double y, double z, double distance, @Nullable Predicate<Entity> predicate) {
        return null;
    }

    @Override
    public List<Player> players() {
        return Collections.emptyList();
    }

    @Override
    public <T extends Entity> List<T> getEntities(EntityTypeTest<Entity, T> test, AABB box, Predicate<? super T> predicate) {
        return Collections.emptyList();
    }

    @Override
    public List<Entity> getEntities(@Nullable Entity except, AABB box, @Nullable Predicate<? super Entity> predicate) {
        return Collections.emptyList();
    }

    @Override
    public int getSkyDarken() {
        return 0;
    }

    @Override
    public float getShade(Direction direction, boolean shade) {
        return 1f;
    }

    @Override
    public void playSound(@Nullable Entity entity, BlockPos pos, SoundEvent sound, SoundSource source, float volume, float pitch) {
    }

    @Override
    public void addParticle(ParticleOptions particle, double x, double y, double z, double dx, double dy, double dz) {
    }

    @Override
    public void levelEvent(@Nullable Entity entity, int type, BlockPos pos, int data) {
    }

    @Override
    public void gameEvent(Holder<GameEvent> event, Vec3 pos, GameEvent.Context context) {
    }
}
