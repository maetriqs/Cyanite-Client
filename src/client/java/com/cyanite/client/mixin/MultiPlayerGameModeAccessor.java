package com.cyanite.client.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MultiPlayerGameMode.class)
public interface MultiPlayerGameModeAccessor {
    @Accessor("destroyDelay")
    int cyanite$getDestroyDelay();

    @Accessor("destroyDelay")
    void cyanite$setDestroyDelay(int delay);
}
