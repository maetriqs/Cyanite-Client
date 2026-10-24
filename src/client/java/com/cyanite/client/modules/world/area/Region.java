package com.cyanite.client.modules.world.area;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

/** A box of blocks, both corners inclusive. */
public record Region(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    public static Region of(BlockPos a, BlockPos b) {
        return new Region(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
            Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
    }

    public int sizeX() {
        return maxX - minX + 1;
    }

    public int sizeY() {
        return maxY - minY + 1;
    }

    public int sizeZ() {
        return maxZ - minZ + 1;
    }

    public long volume() {
        return (long) sizeX() * sizeY() * sizeZ();
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public boolean contains(BlockPos pos) {
        return contains(pos.getX(), pos.getY(), pos.getZ());
    }

    public Region union(Region o) {
        return new Region(Math.min(minX, o.minX), Math.min(minY, o.minY), Math.min(minZ, o.minZ),
            Math.max(maxX, o.maxX), Math.max(maxY, o.maxY), Math.max(maxZ, o.maxZ));
    }

    public Region grow(int xz, int y) {
        return new Region(minX - xz, minY - y, minZ - xz, maxX + xz, maxY + y, maxZ + xz);
    }

    /** Distance from a point to the nearest block of the box, 0 inside it. */
    public double distanceTo(double x, double y, double z) {
        double dx = Math.max(0, Math.max(minX - x, x - (maxX + 1)));
        double dy = Math.max(0, Math.max(minY - y, y - (maxY + 1)));
        double dz = Math.max(0, Math.max(minZ - z, z - (maxZ + 1)));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public AABB aabb() {
        return new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
    }

    public String size() {
        return sizeX() + " × " + sizeY() + " × " + sizeZ();
    }
}
