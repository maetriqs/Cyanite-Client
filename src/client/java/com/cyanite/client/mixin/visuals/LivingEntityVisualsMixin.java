package com.cyanite.client.mixin.visuals;

import com.cyanite.client.visuals.Hands;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Sword/Axe Swing Animation's speed: how long your own swing takes to play (only the look; hits aren't timed by it).
@Mixin(LivingEntity.class)
public class LivingEntityVisualsMixin {
    @Inject(method = "getCurrentSwingDuration", at = @At("RETURN"), cancellable = true)
    private void cyanite$swingSpeed(CallbackInfoReturnable<Integer> cir) {
        int ticks = Hands.swingDuration((LivingEntity) (Object) this, cir.getReturnValue());
        if (ticks != cir.getReturnValue()) cir.setReturnValue(ticks);
    }
}
