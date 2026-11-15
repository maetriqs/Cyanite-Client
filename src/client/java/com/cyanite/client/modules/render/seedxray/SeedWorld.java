package com.cyanite.client.modules.render.seedxray;

import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.feature.FossilFeatureConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.ticks.ProtoChunkTicks;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * One dimension of one seed, generated the way a vanilla server does it: the same generator, noise,
 * surface rules, carvers and features, read from the game's own data pack. Only used from one
 * thread at a time (the prediction thread).
 *
 * <p>Steps, as on a server: biomes, noise, surface and carvers make a chunk's terrain; then each
 * chunk is decorated (lakes, geodes, dungeons, ores...) over the terrain of the chunks around it,
 * since a feature started in one chunk spills into its neighbours. Structures aren't placed: they'd
 * need a running server. Fossils would too, so they're placed by a copy ({@link Fossils}).
 */
public final class SeedWorld {
    private static final Logger LOGGER = LoggerFactory.getLogger("Cyanite");
    /** Features after this (springs, plants and trees, snow) change nothing underground worth predicting. */
    private static final int LAST_STEP = GenerationStep.Decoration.UNDERGROUND_DECORATION.ordinal();
    private static final EnumSet<Heightmap.Types> FINAL_HEIGHTMAPS = EnumSet.of(Heightmap.Types.MOTION_BLOCKING,
        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.WORLD_SURFACE);
    private static volatile RegistryAccess.@Nullable Frozen registries;

    final long seed;
    final RegistryAccess.Frozen access;
    final NoiseBasedChunkGenerator generator;
    final RandomState randomState;
    final DimensionType dimensionType;
    final LevelHeightAccessor height;
    private final PalettedContainerFactory containers;
    private final List<FeatureSorter.StepFeatureData> featuresPerStep;
    private final Set<Holder<Biome>> possibleBiomes;
    private final WorldOptions options;
    private final BiomeManager carverBiomes;
    private final Registry<Biome> biomeRegistry;
    private final Registry<PlacedFeature> placedFeatures;
    private final Fossils fossils;
    /** Which carvers start in a chunk depends only on its biome: worked out once per chunk. */
    private final Long2ObjectMap<BiomeGenerationSettings> carverSettings = new Long2ObjectOpenHashMap<>();
    private int failedFeatures;
    /** Which features failed, with the first error, and how often. */
    private final Map<String, Integer> failures = new LinkedHashMap<>();

    private SeedWorld(long seed, RegistryAccess.Frozen access, LevelStem stem, NoiseBasedChunkGenerator generator) {
        this.seed = seed;
        this.access = access;
        this.generator = generator;
        this.dimensionType = stem.type().value();
        this.height = LevelHeightAccessor.create(dimensionType.minY(), dimensionType.height());
        this.randomState = RandomState.create(generator.generatorSettings().value(), access.lookupOrThrow(Registries.NOISE), seed);
        this.containers = PalettedContainerFactory.create(access);
        this.possibleBiomes = generator.getBiomeSource().possibleBiomes();
        // Built the way ChunkGenerator builds its own, so every feature gets the same index (and so the same random seed).
        this.featuresPerStep = FeatureSorter.buildFeaturesPerStep(List.copyOf(possibleBiomes),
            biome -> generator.getBiomeGenerationSettings(biome).features(), true);
        this.options = new WorldOptions(seed, false, false);
        this.carverBiomes = new BiomeManager((x, y, z) -> generator.getBiomeSource().getNoiseBiome(x, y, z, randomState.sampler()),
            BiomeManager.obfuscateSeed(seed));
        this.biomeRegistry = access.lookupOrThrow(Registries.BIOME);
        this.placedFeatures = access.lookupOrThrow(Registries.PLACED_FEATURE);
        this.fossils = new Fossils(access.lookupOrThrow(Registries.BLOCK));
    }

    /**
     * The vanilla generator for a dimension of the default world type ("minecraft:overworld" and so on),
     * or null if it isn't one of those. The first call reads the game's data pack, which takes a moment.
     */
    public static @Nullable SeedWorld create(long seed, ResourceKey<LevelStem> dimension) {
        RegistryAccess.Frozen access = registries();
        WorldPreset preset = access.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL).value();
        LevelStem stem = preset.createWorldDimensions().dimensions().get(dimension);
        if (stem == null || !(stem.generator() instanceof NoiseBasedChunkGenerator generator)) return null;
        return new SeedWorld(seed, access, stem, generator);
    }

    /**
     * The worldgen registries a new singleplayer world would get, read from the game jar's own data
     * pack the way the server's WorldLoader does. The client's registries came from the server and
     * hold none of this. The block and item tags it reads aren't applied anywhere.
     */
    private static RegistryAccess.Frozen registries() {
        RegistryAccess.Frozen done = registries;
        if (done != null) return done;
        synchronized (SeedWorld.class) {
            if (registries != null) return registries;
            List<PackResources> packs = List.of(ServerPacksSource.createVanillaPackSource());
            try (CloseableResourceManager resources = new MultiPackResourceManager(PackType.SERVER_DATA, packs)) {
                LayeredRegistryAccess<RegistryLayer> layers = RegistryLayer.createRegistryAccess();
                List<Registry.PendingTags<?>> tags = TagLoader.loadTagsForExistingRegistries(resources, layers.getLayer(RegistryLayer.STATIC));
                List<HolderLookup.RegistryLookup<?>> lookups = TagLoader.buildUpdatedLookups(layers.getAccessForLoading(RegistryLayer.WORLDGEN), tags);
                RegistryAccess.Frozen worldgen = RegistryDataLoader.load(resources, lookups, RegistryDataLoader.WORLDGEN_REGISTRIES);
                registries = layers.replaceFrom(RegistryLayer.WORLDGEN, worldgen).compositeAccess();
            }
            return registries;
        }
    }

    public int minY() {
        return height.getMinY();
    }

    /** Features that threw while placing (ones that need a server). */
    public int failedFeatures() {
        return failedFeatures;
    }

    /** The features that threw, how often, and why: "minecraft:x 3x (error)", most frequent first. */
    public String failures() {
        return failures.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .map(e -> e.getKey().replaceFirst(" ", " " + e.getValue() + "x ")).collect(Collectors.joining(", "));
    }

    // ---- Terrain

    /** A chunk's terrain as vanilla makes it before decoration: biomes, noise, surface, carvers. */
    LevelChunkSection[] terrain(int chunkX, int chunkZ) {
        ProtoChunk chunk = new ProtoChunk(new ChunkPos(chunkX, chunkZ), UpgradeData.EMPTY, height, containers, null);
        GenLevel level = new GenLevel(this);
        level.put(chunk);
        StructureManager structures = new StructureManager(level, options, null);

        chunk.setPersistedStatus(ChunkStatus.STRUCTURE_REFERENCES);
        generator.createBiomes(randomState, Blender.empty(), structures, chunk).join();
        chunk.setPersistedStatus(ChunkStatus.BIOMES);
        generator.fillFromNoise(Blender.empty(), randomState, structures, chunk).join();
        chunk.setPersistedStatus(ChunkStatus.NOISE);
        generator.buildSurface(chunk, new WorldGenerationContext(generator, level), randomState, structures,
            level.getBiomeManager(), biomeRegistry, Blender.empty());
        chunk.setPersistedStatus(ChunkStatus.SURFACE);
        carve(chunk);
        return copy(chunk.getSections());
    }

    /** NoiseBasedChunkGenerator.applyCarvers, minus the server region it reads biomes through. */
    private void carve(ProtoChunk chunk) {
        ChunkPos pos = chunk.getPos();
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
        NoiseChunk noise = chunk.getOrCreateNoiseChunk(c -> {
            throw new IllegalStateException("Carving a chunk with no noise");
        });
        Aquifer aquifer = noise.aquifer();
        CarvingContext context = new CarvingContext(generator, access, chunk.getHeightAccessorForGeneration(), noise, randomState,
            generator.generatorSettings().value().surfaceRule());
        CarvingMask mask = chunk.getOrCreateCarvingMask();

        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                ChunkPos from = new ChunkPos(pos.x + dx, pos.z + dz);
                int carver = 0;
                for (Holder<ConfiguredWorldCarver<?>> holder : carverSettings(from).getCarvers()) {
                    ConfiguredWorldCarver<?> configured = holder.value();
                    random.setLargeFeatureSeed(seed + carver, from.x, from.z);
                    if (configured.isStartChunk(random)) configured.carve(context, chunk, carverBiomes::getBiome, random, aquifer, from, mask);
                    carver++;
                }
            }
        }
        chunk.setPersistedStatus(ChunkStatus.CARVERS);
    }

    private BiomeGenerationSettings carverSettings(ChunkPos pos) {
        if (carverSettings.size() > 20_000) carverSettings.clear();
        return carverSettings.computeIfAbsent(pos.toLong(), k -> generator.getBiomeGenerationSettings(generator.getBiomeSource()
            .getNoiseBiome(QuartPos.fromBlock(pos.getMinBlockX()), 0, QuartPos.fromBlock(pos.getMinBlockZ()), randomState.sampler())));
    }

    /** A fresh chunk to decorate, from terrain made earlier (which stays as it was). */
    ProtoChunk chunk(int chunkX, int chunkZ, LevelChunkSection[] terrain) {
        ProtoChunk chunk = new ProtoChunk(new ChunkPos(chunkX, chunkZ), UpgradeData.EMPTY, copy(terrain),
            new ProtoChunkTicks<>(), new ProtoChunkTicks<>(), height, containers, null);
        chunk.setPersistedStatus(ChunkStatus.CARVERS);
        return chunk;
    }

    private static LevelChunkSection[] copy(LevelChunkSection[] sections) {
        LevelChunkSection[] out = new LevelChunkSection[sections.length];
        for (int i = 0; i < sections.length; i++) out[i] = sections[i].copy();
        return out;
    }

    // ---- Decoration

    /**
     * ChunkGenerator.applyBiomeDecoration for one chunk, up to the underground decoration step: the
     * same features in the same order with the same random seeds. Structures are skipped, and a
     * feature that fails is passed over instead of stopping the rest.
     */
    void decorate(GenLevel level, ProtoChunk chunk) {
        ChunkPos pos = chunk.getPos();
        level.center(pos);
        Heightmap.primeHeightmaps(chunk, FINAL_HEIGHTMAPS);
        BlockPos origin = SectionPos.of(pos, level.getMinSectionY()).origin();
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(RandomSupport.generateUniqueSeed()));
        long decorationSeed = random.setDecorationSeed(seed, origin.getX(), origin.getZ());

        Set<Holder<Biome>> biomes = new ObjectArraySet<>();
        ChunkPos.rangeClosed(pos, 1).forEach(near -> {
            for (LevelChunkSection section : level.getChunk(near.x, near.z).getSections()) section.getBiomes().getAll(biomes::add);
        });
        biomes.retainAll(possibleBiomes);

        int steps = Math.min(featuresPerStep.size(), LAST_STEP + 1);
        for (int step = 0; step < steps; step++) {
            FeatureSorter.StepFeatureData data = featuresPerStep.get(step);
            IntSet indexes = new IntArraySet();
            for (Holder<Biome> biome : biomes) {
                List<HolderSet<PlacedFeature>> perStep = generator.getBiomeGenerationSettings(biome).features();
                if (step < perStep.size()) perStep.get(step).stream().map(Holder::value).forEach(f -> indexes.add(data.indexMapping().applyAsInt(f)));
            }
            int[] sorted = indexes.toIntArray();
            Arrays.sort(sorted);
            for (int index : sorted) {
                PlacedFeature feature = data.features().get(index);
                random.setFeatureSeed(decorationSeed, index, step);
                try {
                    if (feature.feature().value().config() instanceof FossilFeatureConfiguration fossil) {
                        placeFossils(level, feature, fossil, random, origin);
                    } else {
                        feature.placeWithBiomeCheck(level, generator, random, origin);
                    }
                } catch (RuntimeException e) {
                    failed(feature, e);
                }
            }
        }
    }

    /** PlacedFeature.placeWithBiomeCheck for a fossil, with the fossil placed by our copy of the feature. */
    private void placeFossils(GenLevel level, PlacedFeature feature, FossilFeatureConfiguration config, WorldgenRandom random, BlockPos origin) {
        PlacementContext context = new PlacementContext(level, generator, Optional.of(feature));
        Stream<BlockPos> positions = Stream.of(origin);
        for (PlacementModifier modifier : feature.placement()) positions = positions.flatMap(pos -> modifier.getPositions(context, random, pos));
        positions.forEach(pos -> {
            if (level.ensureCanWrite(pos)) fossils.place(level, config, random, pos);
        });
    }

    private void failed(PlacedFeature feature, RuntimeException e) {
        failedFeatures++;
        Identifier id = placedFeatures.getKey(feature);
        String error = e.toString();
        failures.merge((id == null ? "?" : id.toString()) + " (" + (error.length() > 120 ? error.substring(0, 120) : error) + ")", 1, Integer::sum);
        LOGGER.debug("Seed Xray: feature {} failed", id, e);
    }
}
