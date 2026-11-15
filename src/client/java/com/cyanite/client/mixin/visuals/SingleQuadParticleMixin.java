package com.cyanite.client.mixin.visuals;

import com.cyanite.client.visuals.Particles;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.SingleQuadParticle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

// A particle's colour and opacity as it's drawn; a see-through one goes to the translucent layer.
@Mixin(SingleQuadParticle.class)
public class SingleQuadParticleMixin {
    @ModifyArg(method = "extractRotatedQuad(Lnet/minecraft/client/renderer/state/QuadParticleRenderState;Lorg/joml/Quaternionf;FFFF)V", index = 13,
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/state/QuadParticleRenderState;add(Lnet/minecraft/client/particle/SingleQuadParticle$Layer;FFFFFFFFFFFFII)V"))
    private int cyanite$color(int argb) {
        return Particles.drawColor((Particle) (Object) this, argb);
    }

    @ModifyArg(method = "extractRotatedQuad(Lnet/minecraft/client/renderer/state/QuadParticleRenderState;Lorg/joml/Quaternionf;FFFF)V", index = 0,
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/state/QuadParticleRenderState;add(Lnet/minecraft/client/particle/SingleQuadParticle$Layer;FFFFFFFFFFFFII)V"))
    private SingleQuadParticle.Layer cyanite$layer(SingleQuadParticle.Layer layer) {
        return Particles.layer((Particle) (Object) this, layer);
    }
}
