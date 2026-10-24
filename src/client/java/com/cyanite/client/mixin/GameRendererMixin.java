package com.cyanite.client.mixin;

import com.cyanite.client.modules.world.AreaMiner;
import com.cyanite.client.modules.world.BedrockBurrow;
import com.cyanite.client.modules.world.AutoMine;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.cyanite.client.modules.render.Freecam;
import org.joml.Matrix4f;
import com.cyanite.client.modules.render.NoHurtCam;
import com.cyanite.client.system.Modules;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
    // Zoom: the world's field of view narrows; the hand's (useFovSetting false) stays as it is.
    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void cyanite$zoom(net.minecraft.client.Camera camera, float partialTick, boolean useFovSetting,
                              org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Float> cir) {
        if (!useFovSetting) return;
        double zoom = com.cyanite.client.modules.render.Zoom.fovDivisor();
        if (zoom != 1) cir.setReturnValue((float) (cir.getReturnValue() / zoom));
    }

    @Inject(method = "render", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
        target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V"))
    private void cyanite$motionBlur(net.minecraft.client.DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        com.cyanite.client.modules.render.MotionBlur blur = Modules.get(com.cyanite.client.modules.render.MotionBlur.class);
        if (blur != null) blur.apply();
    }

    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
    private void cyanite$noHurtCam(PoseStack poseStack, float partialTick, CallbackInfo ci) {
        if (Modules.get(NoHurtCam.class).isEnabled()) ci.cancel();
    }

    // Freecam: no first-person hand floating in front of a camera that isn't your player.
    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void cyanite$freecamHand(float partialTick, boolean sleeping, Matrix4f projection, CallbackInfo ci) {
        Freecam freecam = Modules.get(Freecam.class);
        if (freecam != null && freecam.isActive()) ci.cancel();
    }

    // Auto Mine, Area Miner, Bedrock Burrow and Schematic Builder keep going while you're tabbed out: no pause menu when the window loses focus.
    @ModifyExpressionValue(method = "render", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Options;pauseOnLostFocus:Z"))
    private boolean cyanite$keepRunningUnfocused(boolean pauseOnLostFocus) {
        AutoMine autoMine = Modules.get(AutoMine.class);
        AreaMiner areaMiner = Modules.get(AreaMiner.class);
        BedrockBurrow burrow = Modules.get(BedrockBurrow.class);
        com.cyanite.client.modules.world.SchematicBuilder builder = Modules.get(com.cyanite.client.modules.world.SchematicBuilder.class);
        boolean mining = autoMine != null && autoMine.isEnabled() || areaMiner != null && areaMiner.isEnabled()
            || burrow != null && burrow.isEnabled() || builder != null && builder.isEnabled();
        return pauseOnLostFocus && !mining;
    }
}
