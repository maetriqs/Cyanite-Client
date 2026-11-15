package com.cyanite.client.mixin;

import com.cyanite.client.modules.render.Cinematic;
import com.cyanite.client.system.Macros;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.cyanite.client.system.Modules;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public class MouseHandlerMixin {
    // Once per frame, right where the real mouse turns the player, so module turns land at the same moment.
    // Zoom: while the zoom key's held, the scroll wheel zooms instead of changing your hotbar slot.
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void cyanite$zoomScroll(long window, double xOffset, double yOffset, CallbackInfo ci) {
        if (com.cyanite.client.modules.render.Zoom.onScroll(yOffset)) ci.cancel();
    }

    // Cinematic's Smooth camera: mouse turns glide to a stop instead of snapping (every bit still arrives).
    @WrapOperation(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private void cyanite$cinematicTurn(LocalPlayer player, double yaw, double pitch, Operation<Void> original,
                                       @Local(argsOnly = true) double seconds) {
        if (!Cinematic.easesTurns()) {
            original.call(player, yaw, pitch);
            return;
        }
        Cinematic.easeTurn(yaw, pitch, seconds);
        original.call(player, Cinematic.turnYaw(), Cinematic.turnPitch());
    }

    @Inject(method = "handleAccumulatedMovement", at = @At("TAIL"))
    private void cyanite$frame(CallbackInfo ci) {
        Modules.frame();
        Macros.frame();
    }
}
