package com.cyanite.client.mixin.visuals;

import com.cyanite.client.visuals.Particles;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.multiplayer.ClientExplosionTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;

// Explosion Particles: the block particles an explosion leaves over the next ticks count as its too.
@Mixin(ClientExplosionTracker.class)
public class ClientExplosionTrackerMixin {
    @WrapMethod(method = "tick")
    private void cyanite$explosionParticles(ClientLevel level, Operation<Void> original) {
        Particles.beginExplosion();
        try {
            original.call(level);
        } finally {
            Particles.end();
        }
    }
}
