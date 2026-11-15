package com.cyanite.client.mixin;

import com.cyanite.client.render.SpaceSky;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AtmosphericFogEnvironment.class)
public class AtmosphericFogEnvironmentMixin {
    // Cinematic's space sky: the air's fog takes the sky's colour, so far terrain fades into its haze.
    @ModifyReturnValue(method = "getBaseColor", at = @At("RETURN"))
    private int cyanite$spaceSkyFog(int color) {
        return SpaceSky.fog(color);
    }
}
