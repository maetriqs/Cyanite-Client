package com.cyanite.client.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.animal.allay.Allay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Allay.class)
public interface AllayAccessor {
    /** Whether an allay is dancing: Allay#setDancing only works on the server, and a pet's allay lives on your screen. */
    @Accessor("DATA_DANCING")
    static EntityDataAccessor<Boolean> cyanite$dancing() {
        throw new AssertionError();
    }
}
