package com.cyanite.client.mixin.visuals;

import com.cyanite.client.visuals.SkyColors;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(EnvironmentAttributeProbe.class)
public class EnvironmentAttributeProbeMixin {
    // Sky Colors: the sky, fog and cloud colours the camera reads (only the camera and the sky use a probe).
    @ModifyReturnValue(method = "getValue", at = @At("RETURN"))
    private Object cyanite$skyColors(Object value, EnvironmentAttribute<?> attribute, float partialTick) {
        return SkyColors.value(attribute, value);
    }
}
