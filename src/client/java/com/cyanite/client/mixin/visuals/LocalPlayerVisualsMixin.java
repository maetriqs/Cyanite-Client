package com.cyanite.client.mixin.visuals;

import com.cyanite.client.visuals.Combat;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Attack animations "On misses too": a swing that hit nothing.
@Mixin(LocalPlayer.class)
public class LocalPlayerVisualsMixin {
    @Inject(method = "swing(Lnet/minecraft/world/InteractionHand;)V", at = @At("HEAD"))
    private void cyanite$visualsSwing(InteractionHand hand, CallbackInfo ci) {
        if (hand == InteractionHand.MAIN_HAND) Combat.swung();
    }
}
