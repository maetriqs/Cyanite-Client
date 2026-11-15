package com.cyanite.client.mixin.visuals;

import com.cyanite.client.visuals.Glint;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.renderer.rendertype.TextureTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// Enchantment Glint: how fast it scrolls.
@Mixin(TextureTransform.class)
public class TextureTransformMixin {
    @ModifyExpressionValue(method = "setupGlintTexturing", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Util;getMillis()J"))
    private static long cyanite$glintSpeed(long millis) {
        return Glint.millis(millis);
    }
}
