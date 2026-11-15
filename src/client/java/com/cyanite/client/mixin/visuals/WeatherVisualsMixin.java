package com.cyanite.client.mixin.visuals;

import com.cyanite.client.visuals.Weather;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.WeatherRenderState;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Weather Visuals: rain or snow left out, or fainter; the splashes where rain lands left out (its sound stays).
@Mixin(WeatherEffectRenderer.class)
public class WeatherVisualsMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void cyanite$weather(MultiBufferSource buffers, Vec3 camera, WeatherRenderState state, CallbackInfo ci) {
        Weather.beforeRender(state);
    }

    @WrapOperation(method = "tickRainParticles", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/multiplayer/ClientLevel;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"))
    private void cyanite$splashes(ClientLevel level, ParticleOptions options, double x, double y, double z, double dx, double dy, double dz,
                                  Operation<Void> original) {
        if (Weather.splashes()) original.call(level, options, x, y, z, dx, dy, dz);
    }
}
