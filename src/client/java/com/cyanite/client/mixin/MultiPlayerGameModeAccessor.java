package com.cyanite.client.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MultiPlayerGameMode.class)
public interface MultiPlayerGameModeAccessor {
    @Accessor("destroyDelay")
    int cyanite$getDestroyDelay();

    @Accessor("destroyDelay")
    void cyanite$setDestroyDelay(int delay);

    @Accessor("destroyProgress")
    float cyanite$getDestroyProgress();

    @Accessor("destroyBlockPos")
    BlockPos cyanite$getDestroyBlockPos();
}
