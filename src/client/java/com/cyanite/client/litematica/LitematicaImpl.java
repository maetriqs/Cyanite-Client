package com.cyanite.client.litematica;

import com.cyanite.client.modules.world.area.Region;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.PositionUtils;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The Litematica calls themselves. Only loaded when Litematica is installed. */
final class LitematicaImpl {
    private LitematicaImpl() {
    }

    static List<Litematica.Placement> placements() {
        SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
        if (manager == null) return List.of();
        SchematicPlacement selected = manager.getSelectedSchematicPlacement();
        List<Litematica.Placement> out = new ArrayList<>();
        for (SchematicPlacement placement : manager.getAllSchematicsPlacements()) {
            List<Region> boxes = new ArrayList<>();
            for (Box box : placement.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED).values()) {
                if (box.getPos1() != null && box.getPos2() != null) boxes.add(Region.of(box.getPos1(), box.getPos2()));
            }
            if (boxes.isEmpty()) continue;
            out.add(new Litematica.Placement(key(placement), placement.getName(), placement.getOrigin(),
                placement.getRotation(), placement.getMirror(), List.copyOf(boxes), placement.isEnabled(), placement == selected));
        }
        return out;
    }

    static List<Region> selection() {
        AreaSelection selection = DataManager.getSelectionManager().getCurrentSelection();
        if (selection == null) return List.of();
        List<Region> out = new ArrayList<>();
        for (Box box : selection.getAllSubRegionBoxes()) {
            if (box.getPos1() != null && box.getPos2() != null) out.add(Region.of(box.getPos1(), box.getPos2()));
        }
        return out;
    }

    static LongSet blocks(String key, BlockPos origin, long limit) {
        SchematicPlacement placement = find(key, origin);
        if (placement == null || placement.getSchematic() == null) return null;
        LitematicaSchematic schematic = placement.getSchematic();
        LongSet out = new LongOpenHashSet();

        for (SubRegionPlacement sub : placement.getAllSubRegionsPlacements()) {
            if (!sub.matchesRequirement(SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED)) continue;
            String name = sub.getName();
            LitematicaBlockStateContainer container = schematic.getSubRegionContainer(name);
            BlockPos areaSize = schematic.getAreaSize(name);
            if (container == null || areaSize == null) continue;

            // Same steps Litematica takes to paste a sub-region: positions inside the container are
            // relative to the region's minimum corner, which sits somewhere relative to the region's
            // own origin; the schematic's then the region's mirror and rotation turn that offset,
            // and it lands at the placement origin plus the turned region position.
            BlockPos regionPos = sub.getPos();
            BlockPos endRel = PositionUtils.getRelativeEndPositionFromAreaSize(areaSize).offset(regionPos);
            BlockPos minRel = PositionUtils.getMinCorner(regionPos, endRel);
            BlockPos regionPosTurned = PositionUtils.getTransformedBlockPos(regionPos, placement.getMirror(), placement.getRotation());
            BlockPos base = regionPosTurned.offset(placement.getOrigin());

            Vec3i size = container.getSize();
            long cells = (long) size.getX() * size.getY() * size.getZ();
            if (cells > limit) return null;
            for (int y = 0; y < size.getY(); y++) {
                for (int z = 0; z < size.getZ(); z++) {
                    for (int x = 0; x < size.getX(); x++) {
                        BlockState state = container.get(x, y, z);
                        if (state == null || state.isAir() || state.is(Blocks.STRUCTURE_VOID)) continue;
                        BlockPos rel = new BlockPos(minRel.getX() + x - regionPos.getX(), minRel.getY() + y - regionPos.getY(),
                            minRel.getZ() + z - regionPos.getZ());
                        BlockPos world = PositionUtils.getTransformedPlacementPosition(rel, placement, sub).offset(base);
                        out.add(world.asLong());
                        if (out.size() > limit) return null;
                    }
                }
            }
        }
        return out;
    }

    static Map<BlockPos, BlockState> states(String key, BlockPos origin, long limit) {
        SchematicPlacement placement = find(key, origin);
        if (placement == null || placement.getSchematic() == null) return null;
        LitematicaSchematic schematic = placement.getSchematic();
        Map<BlockPos, BlockState> out = new HashMap<>();

        for (SubRegionPlacement sub : placement.getAllSubRegionsPlacements()) {
            if (!sub.matchesRequirement(SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED)) continue;
            String name = sub.getName();
            LitematicaBlockStateContainer container = schematic.getSubRegionContainer(name);
            BlockPos areaSize = schematic.getAreaSize(name);
            if (container == null || areaSize == null) continue;

            BlockPos regionPos = sub.getPos();
            BlockPos endRel = PositionUtils.getRelativeEndPositionFromAreaSize(areaSize).offset(regionPos);
            BlockPos minRel = PositionUtils.getMinCorner(regionPos, endRel);
            BlockPos regionPosTurned = PositionUtils.getTransformedBlockPos(regionPos, placement.getMirror(), placement.getRotation());
            BlockPos base = regionPosTurned.offset(placement.getOrigin());

            // Turning the states the way Litematica pastes them: the placement's mirror, then the
            // sub-region's (swapped round when the placement is turned a quarter), then both turns.
            Rotation turn = placement.getRotation().getRotated(sub.getRotation());
            Mirror mirror = placement.getMirror();
            Mirror subMirror = sub.getMirror();
            if (subMirror != Mirror.NONE && (placement.getRotation() == Rotation.CLOCKWISE_90 || placement.getRotation() == Rotation.COUNTERCLOCKWISE_90)) {
                subMirror = subMirror == Mirror.FRONT_BACK ? Mirror.LEFT_RIGHT : Mirror.FRONT_BACK;
            }

            Vec3i size = container.getSize();
            long cells = (long) size.getX() * size.getY() * size.getZ();
            if (cells > limit) return null;
            for (int y = 0; y < size.getY(); y++) {
                for (int z = 0; z < size.getZ(); z++) {
                    for (int x = 0; x < size.getX(); x++) {
                        BlockState state = container.get(x, y, z);
                        if (state == null || state.isAir() || state.is(Blocks.STRUCTURE_VOID)) continue;
                        if (mirror != Mirror.NONE) state = state.mirror(mirror);
                        if (subMirror != Mirror.NONE) state = state.mirror(subMirror);
                        if (turn != Rotation.NONE) state = state.rotate(turn);
                        BlockPos rel = new BlockPos(minRel.getX() + x - regionPos.getX(), minRel.getY() + y - regionPos.getY(),
                            minRel.getZ() + z - regionPos.getZ());
                        BlockPos world = PositionUtils.getTransformedPlacementPosition(rel, placement, sub).offset(base);
                        out.put(world, state);
                        if (out.size() > limit) return null;
                    }
                }
            }
        }
        return out;
    }

    /** The placement a saved key points at; with several copies of one schematic, the one at {@code origin}. */
    private static SchematicPlacement find(String key, BlockPos origin) {
        SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
        if (manager == null) return null;
        SchematicPlacement match = null;
        for (SchematicPlacement placement : manager.getAllSchematicsPlacements()) {
            if (!key(placement).equals(key)) continue;
            if (origin != null && placement.getOrigin().equals(origin)) return placement;
            if (match == null) match = placement;
        }
        return match;
    }

    private static String key(SchematicPlacement placement) {
        Path file = placement.getSchematicFile();
        return placement.getName() + "|" + (file == null ? "" : file.getFileName().toString());
    }
}
