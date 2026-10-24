package com.cyanite.client.modules.world.area;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * The blocks Area Miner is allowed to dig: one or more boxes, optionally narrowed to an exact
 * set of positions (a schematic's own blocks). Nothing outside this is ever broken.
 */
public final class AreaTarget {
    public final List<Region> regions;
    /** Exact positions, or null for "everything in the boxes". */
    public final LongSet mask;
    public final String label;
    public final Region bounds;

    public AreaTarget(List<Region> regions, LongSet mask, String label) {
        if (regions.isEmpty()) throw new IllegalArgumentException("no regions");
        this.regions = List.copyOf(regions);
        this.mask = mask;
        this.label = label;
        Region b = regions.getFirst();
        for (Region r : regions) b = b.union(r);
        this.bounds = b;
    }

    public boolean contains(int x, int y, int z) {
        if (!bounds.contains(x, y, z)) return false;
        if (mask != null) return mask.contains(BlockPos.asLong(x, y, z));
        for (Region r : regions) {
            if (r.contains(x, y, z)) return true;
        }
        return false;
    }

    public boolean contains(BlockPos pos) {
        return contains(pos.getX(), pos.getY(), pos.getZ());
    }

    /** Whether {@code pos} is counted under region {@code index} and no earlier one, so overlaps count once. */
    public boolean owns(int index, int x, int y, int z) {
        if (!regions.get(index).contains(x, y, z)) return false;
        for (int i = 0; i < index; i++) {
            if (regions.get(i).contains(x, y, z)) return false;
        }
        return mask == null || mask.contains(BlockPos.asLong(x, y, z));
    }

    /** How many positions the target covers, for showing its size. */
    public long volume() {
        if (mask != null) return mask.size();
        long total = 0;
        for (Region r : regions) total += r.volume();
        return total;
    }
}
