package com.cyanite.client.mixin.visuals;

import com.cyanite.client.visuals.ParticleTint;
import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

// Custom Particles and the rest: the colour and opacity Visuals gave a particle when it was made.
@Mixin(Particle.class)
public class ParticleMixin implements ParticleTint {
    @Unique
    private int cyanite$tint;
    @Unique
    private float cyanite$alpha = 1;

    @Override
    public int cyanite$tint() {
        return cyanite$tint;
    }

    @Override
    public void cyanite$setTint(int argb) {
        cyanite$tint = argb;
    }

    @Override
    public float cyanite$alpha() {
        return cyanite$alpha;
    }

    @Override
    public void cyanite$setAlpha(float alpha) {
        cyanite$alpha = alpha;
    }
}
