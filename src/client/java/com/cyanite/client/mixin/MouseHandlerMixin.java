package com.cyanite.client.mixin;

import com.cyanite.client.system.Macros;
import com.cyanite.client.system.Modules;
import net.minecraft.client.MouseHandler;
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

    @Inject(method = "handleAccumulatedMovement", at = @At("TAIL"))
    private void cyanite$frame(CallbackInfo ci) {
        Modules.frame();
        Macros.frame();
    }
}
