package com.cyanite.client.mixin;

import com.cyanite.client.modules.render.Cinematic;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(WeatherEffectRenderer.class)
public class WeatherEffectRendererMixin {
    // Cinematic's Enhanced weather: rain and snow fall out to 15 blocks around you (Options stops at 10;
    // the renderer's column table holds up to 15).
    @ModifyExpressionValue(method = "extractRenderState", at = @At(value = "INVOKE", target = "Ljava/lang/Integer;intValue()I"))
    private int cyanite$cinematicWeather(int radius) {
        return Cinematic.weatherRadius(radius);
    }
}
