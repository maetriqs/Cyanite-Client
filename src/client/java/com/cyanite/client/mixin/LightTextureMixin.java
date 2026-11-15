package com.cyanite.client.mixin;

import com.cyanite.client.modules.render.Fullbright;
import com.cyanite.client.modules.render.Xray;
import com.cyanite.client.render.CameraShot;
import com.cyanite.client.system.Modules;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LightTexture.class)
public class LightTextureMixin {
    @Shadow
    private boolean updateLightTexture;

    // The light map is worked out once a tick; a CameraShot wants it full bright for its picture and back after.
    @Inject(method = "updateLightTexture", at = @At("HEAD"))
    private void cyanite$shotRelight(float partialTick, CallbackInfo ci) {
        if (CameraShot.takeRelight()) updateLightTexture = true;
    }

    // The brightness slider tops out at 1; the lightmap happily takes far more.
    @ModifyExpressionValue(
        method = "updateLightTexture",
        at = @At(value = "INVOKE", target = "Ljava/lang/Double;floatValue()F", ordinal = 0),
        slice = @Slice(from = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;gamma()Lnet/minecraft/client/OptionInstance;"))
    )
    private float cyanite$fullbright(float gamma) {
        boolean bright = Modules.get(Fullbright.class).overridesGamma() || Modules.get(Xray.class).brightens() || CameraShot.taking();
        return bright ? 16f : gamma;
    }
}
