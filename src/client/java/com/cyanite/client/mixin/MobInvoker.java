package com.cyanite.client.mixin;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Mob.class)
public interface MobInvoker {
    /** A mob's own idle sound, for Pets to play: theirs only exist on your screen, so the game never plays it. */
    @Invoker("getAmbientSound")
    SoundEvent cyanite$ambientSound();
}
