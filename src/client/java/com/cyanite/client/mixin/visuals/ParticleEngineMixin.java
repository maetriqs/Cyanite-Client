package com.cyanite.client.mixin.visuals;

import com.cyanite.client.visuals.Particles;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Particle density (left out before it's made), and size and colour (just after).
@Mixin(ParticleEngine.class)
public class ParticleEngineMixin {
    @Inject(method = "createParticle", at = @At("HEAD"), cancellable = true)
    private void cyanite$density(ParticleOptions options, double x, double y, double z, double dx, double dy, double dz,
                                 CallbackInfoReturnable<Particle> cir) {
        if (!Particles.keep(options)) cir.setReturnValue(null);
    }

    @Inject(method = "createParticle", at = @At("RETURN"))
    private void cyanite$style(ParticleOptions options, double x, double y, double z, double dx, double dy, double dz,
                               CallbackInfoReturnable<Particle> cir) {
        Particle particle = cir.getReturnValue();
        if (particle != null) Particles.style(options, particle, x, y, z, dx, dy, dz);
    }
}
