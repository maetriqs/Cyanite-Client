package com.cyanite.client.mixin;

import com.cyanite.client.modules.movement.NoFall;
import com.cyanite.client.modules.movement.NoSlow;
import com.cyanite.client.system.Macros;
import com.cyanite.client.system.Modules;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No Slow and No Fall, plus what macro recording hears of drops and closed menus. Using an item does two separate things: it scales your movement input
 * by the item's speed multiplier, and it stops you sprinting. Each has its own check.
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

    /** No Fall: the movement packets of a fall say you're on the ground. */
    @ModifyExpressionValue(method = "sendPosition", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;onGround()Z"))
    private boolean cyanite$noFall(boolean onGround) {
        return onGround || Modules.get(NoFall.class).claimsGround();
    }

    // Macro recording: Q drops, and menus you close yourself (the server closing one goes another way).
    @Inject(method = "drop", at = @At("RETURN"))
    private void cyanite$recordDrop(boolean wholeStack, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) Macros.recorder().dropped(wholeStack);
    }

    @Inject(method = "closeContainer", at = @At("HEAD"))
    private void cyanite$recordClose(CallbackInfo ci) {
        Macros.recorder().closedMenu();
    }
}
