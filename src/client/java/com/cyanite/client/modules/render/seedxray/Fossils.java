package com.cyanite.client.modules.render.seedxray;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.util.RandomSource;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.FossilFeatureConfiguration;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;

/**
 * FossilFeature, which reads its bones from the server's structure manager. Deep fossils carry
 * diamond ore and shallow ones coal, so they're worth having: the same code, with the bones read
 * straight from the game's data pack.
 */
final class Fossils {
    private static final FileToIdConverter FILES = new FileToIdConverter("structure", ".nbt");
    private static volatile PackResources vanilla;

    private final HolderGetter<Block> blocks;
    /** Templates keep caches of their own, so each world (and so each thread) has its own. */
    private final Map<Identifier, StructureTemplate> templates = new HashMap<>();

    Fossils(HolderGetter<Block> blocks) {
        this.blocks = blocks;
    }

    /** FossilFeature.place, line for line. */
    boolean place(GenLevel level, FossilFeatureConfiguration config, RandomSource random, BlockPos origin) {
        Rotation rotation = Rotation.getRandom(random);
        int index = random.nextInt(config.fossilStructures.size());
        StructureTemplate fossil = template(config.fossilStructures.get(index));
        StructureTemplate overlay = template(config.overlayStructures.get(index));
        ChunkPos chunk = new ChunkPos(origin);
        BoundingBox area = new BoundingBox(chunk.getMinBlockX() - 16, level.getMinY(), chunk.getMinBlockZ() - 16,
            chunk.getMaxBlockX() + 16, level.getMaxY(), chunk.getMaxBlockZ() + 16);
        StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(rotation).setBoundingBox(area).setRandom(random);
        Vec3i size = fossil.getSize(rotation);
        BlockPos corner = origin.offset(-size.getX() / 2, 0, -size.getZ() / 2);
        int floor = origin.getY();
        for (int x = 0; x < size.getX(); x++) {
            for (int z = 0; z < size.getZ(); z++) {
                floor = Math.min(floor, level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, corner.getX() + x, corner.getZ() + z));
            }
        }
        int y = Math.max(floor - 15 - random.nextInt(10), level.getMinY() + 10);
        BlockPos at = fossil.getZeroPositionWithTransform(corner.atY(y), Mirror.NONE, rotation);
        if (emptyCorners(level, fossil.getBoundingBox(settings, at)) > config.maxEmptyCornersAllowed) return false;

        settings.clearProcessors();
        config.fossilProcessors.value().list().forEach(settings::addProcessor);
        fossil.placeInWorld(level, at, at, settings, random, 260);
        settings.clearProcessors();
        config.overlayProcessors.value().list().forEach(settings::addProcessor);
        overlay.placeInWorld(level, at, at, settings, random, 260);
        return true;
    }

    private static int emptyCorners(GenLevel level, BoundingBox box) {
        int[] empty = {0};
        box.forAllCorners(pos -> {
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.is(Blocks.LAVA) || state.is(Blocks.WATER)) empty[0]++;
        });
        return empty[0];
    }

    private StructureTemplate template(Identifier id) {
        StructureTemplate template = templates.get(id);
        if (template == null) {
            template = load(id);
            templates.put(id, template);
        }
        return template;
    }

    /** StructureTemplateManager's loading from the data pack, with the same data fixing. */
    private StructureTemplate load(Identifier id) {
        IoSupplier<InputStream> file = vanilla().getResource(PackType.SERVER_DATA, FILES.idToFile(id));
        if (file == null) throw new IllegalStateException("The game's data has no structure " + id);
        try (InputStream in = file.get()) {
            CompoundTag tag = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
            int version = NbtUtils.getDataVersion(tag, 500);
            StructureTemplate template = new StructureTemplate();
            template.load(blocks, DataFixTypes.STRUCTURE.updateToCurrentVersion(DataFixers.getDataFixer(), tag, version));
            return template;
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't read structure " + id, e);
        }
    }

    private static PackResources vanilla() {
        PackResources pack = vanilla;
        if (pack == null) vanilla = pack = ServerPacksSource.createVanillaPackSource();
        return pack;
    }
}
