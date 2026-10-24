package com.cyanite.client.litematica;

import com.cyanite.client.modules.world.area.Region;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;

/**
 * What Cyanite reads from Litematica, when it's installed: your schematic placements (where
 * they sit, how they're turned and mirrored) and your area selection. Litematica is optional,
 * so nothing here touches its classes unless it's loaded; {@link LitematicaImpl} does the work.
 */
public final class Litematica {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean INSTALLED = FabricLoader.getInstance().isModLoaded("litematica");

    private static boolean warned;

    private Litematica() {
    }

    /**
     * A schematic placed in the world. {@code boxes} are its sub-regions in world coordinates,
     * already moved, rotated and mirrored the way Litematica shows them.
     */
    public record Placement(String key, String name, BlockPos origin, Rotation rotation, Mirror mirror,
                            List<Region> boxes, boolean enabled, boolean selected) {
        public Region bounds() {
            Region b = boxes.getFirst();
            for (Region r : boxes) b = b.union(r);
            return b;
        }
    }

    public static boolean installed() {
        return INSTALLED;
    }

    public static List<Placement> placements() {
        if (!INSTALLED) return List.of();
        try {
            return LitematicaImpl.placements();
        } catch (Throwable t) {
            warn(t);
            return List.of();
        }
    }

    /** Litematica's current area selection, one box per sub-region. Empty if there isn't one. */
    public static List<Region> selection() {
        if (!INSTALLED) return List.of();
        try {
            return LitematicaImpl.selection();
        } catch (Throwable t) {
            warn(t);
            return List.of();
        }
    }

    /**
     * Every position where the placement has a block, in world coordinates. Null if the
     * placement is gone or too big to list.
     */
    public static LongSet blocks(String key, BlockPos origin, long limit) {
        if (!INSTALLED) return null;
        try {
            return LitematicaImpl.blocks(key, origin, limit);
        } catch (Throwable t) {
            warn(t);
            return null;
        }
    }

    /**
     * Every block the placement has, where it goes in the world and what it should be: turned and
     * mirrored the way Litematica shows it. Null if the placement is gone or too big to list.
     */
    public static Map<BlockPos, net.minecraft.world.level.block.state.BlockState> states(String key, BlockPos origin, long limit) {
        if (!INSTALLED) return null;
        try {
            return LitematicaImpl.states(key, origin, limit);
        } catch (Throwable t) {
            warn(t);
            return null;
        }
    }

    private static void warn(Throwable t) {
        // A Litematica update that renames something shouldn't spam the log every frame.
        if (warned) return;
        warned = true;
        LOGGER.error("Couldn't read from Litematica; schematic selection won't work with this version", t);
    }
}
