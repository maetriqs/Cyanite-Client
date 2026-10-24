package com.cyanite.client.mixin;

import net.minecraft.world.level.BaseSpawner;
import net.minecraft.world.level.SpawnData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BaseSpawner.class)
public interface BaseSpawnerAccessor {
    /** What the spawner shows spinning inside it, which is what it spawns. Null if the server never said. */
    @Accessor("nextSpawnData")
    SpawnData cyanite$nextSpawnData();
}
