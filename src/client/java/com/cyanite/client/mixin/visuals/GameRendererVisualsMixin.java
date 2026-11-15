package com.cyanite.client.mixin.visuals;

import com.cyanite.client.visuals.HurtCamera;
import com.cyanite.client.visuals.Visuals;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Hurt Camera: how your view moves when you're hurt (No Hurt Cam still wins; it's checked first).
@Mixin(GameRenderer.class)
public class GameRendererVisualsMixin {
    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
    private void cyanite$hurtCamera(PoseStack pose, float partialTick, CallbackInfo ci) {
        if (Visuals.test(HurtCamera.GROUP, () -> HurtCamera.bobHurt(pose, partialTick))) ci.cancel();
    }
}
