package com.cyanite.client.mixin;

import com.cyanite.client.modules.movement.NoSlow;
import com.cyanite.client.system.Modules;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No Slow. Using an item does two separate things: it scales your movement input by the
 * item's speed multiplier, and it stops you sprinting. Each has its own check.
 */
@Mixin(LocalPlayer.class)
public class LocalPlayerMixin {
    @Inject(method = "itemUseSpeedMultiplier", at = @At("HEAD"), cancellable = true)
    private void cyanite$noSlowSpeed(CallbackInfoReturnable<Float> cir) {
        if (Modules.get(NoSlow.class).isEnabled()) cir.setReturnValue(1f);
    }

    @Inject(method = "isSlowDueToUsingItem", at = @At("HEAD"), cancellable = true)
    private void cyanite$noSlowSprint(CallbackInfoReturnable<Boolean> cir) {
        if (Modules.get(NoSlow.class).isEnabled()) cir.setReturnValue(false);
    }
}
