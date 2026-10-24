package com.cyanite.client.mixin;

import com.cyanite.client.modules.render.Fullbright;
import com.cyanite.client.modules.render.Xray;
import com.cyanite.client.system.Modules;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Slice;

@Mixin(LightTexture.class)
public class LightTextureMixin {
    // The brightness slider tops out at 1; the lightmap happily takes far more.
    @ModifyExpressionValue(
        method = "updateLightTexture",
        at = @At(value = "INVOKE", target = "Ljava/lang/Double;floatValue()F", ordinal = 0),
        slice = @Slice(from = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;gamma()Lnet/minecraft/client/OptionInstance;"))
    )
    private float cyanite$fullbright(float gamma) {
        boolean bright = Modules.get(Fullbright.class).overridesGamma() || Modules.get(Xray.class).brightens();
        return bright ? 16f : gamma;
    }
}
