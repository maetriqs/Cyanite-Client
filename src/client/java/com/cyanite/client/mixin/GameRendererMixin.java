package com.cyanite.client.mixin;

import com.cyanite.client.modules.world.AreaMiner;
import com.cyanite.client.modules.world.BedrockBurrow;
import com.cyanite.client.modules.world.AutoMine;
import com.cyanite.client.modules.render.AcidMode;
import com.cyanite.client.modules.render.Cinematic;
import com.cyanite.client.render.CameraShot;
import com.cyanite.client.render.CinematicRenderer;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import com.cyanite.client.modules.render.Freecam;
import org.joml.Matrix4f;
import com.cyanite.client.modules.render.NoHurtCam;
import com.cyanite.client.system.Headless;
import com.cyanite.client.system.Modules;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
    /** Started by Cyanite Miner: nothing drawn (and so no pausing for the hidden window) unless it wants a look. */
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void cyanite$headless(net.minecraft.client.DeltaTracker deltaTracker, boolean tick, CallbackInfo ci) {
        if (Headless.skipFrame()) ci.cancel();
    }

    // Zoom: the world's field of view narrows; the hand's (useFovSetting false) stays as it is.
    // Cinematic then zooms on its own key and glides to any change.
    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void cyanite$zoom(net.minecraft.client.Camera camera, float partialTick, boolean useFovSetting,
                              org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Float> cir) {
        if (!useFovSetting) return;
        // A CameraShot's camera has its own.
        if (CameraShot.taking()) {
            cir.setReturnValue(CameraShot.fov());
            return;
        }
        // Acid Mode opens it up first, so zooming still narrows it.
        float fov = AcidMode.fov(cir.getReturnValue());
        double zoom = com.cyanite.client.modules.render.Zoom.fovDivisor();
        if (zoom != 1) fov = (float) (fov / zoom);
        fov = Cinematic.fov(fov);
        if (fov != cir.getReturnValue()) cir.setReturnValue(fov);
    }

    // FOV Changer, then Cinematic's Custom FOV, in place of the one in Options (the hand's path doesn't read it).
    @ModifyExpressionValue(method = "getFov", at = @At(value = "INVOKE", target = "Ljava/lang/Integer;intValue()I"))
    private int cyanite$fovChanger(int fov) {
        return Cinematic.baseFov(com.cyanite.client.modules.render.FovChanger.fov(fov));
    }

    // Cinematic's Camera bobbing: on even with View Bobbing off in Options, and scaled.
    @WrapOperation(method = {"renderLevel", "renderItemInHand"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/OptionInstance;get()Ljava/lang/Object;"))
    private Object cyanite$cinematicBobs(OptionInstance<?> option, Operation<Object> original) {
        Object value = original.call(option);
        return value instanceof Boolean on && option == Minecraft.getInstance().options.bobView() ? Cinematic.bobs(on) : value;
    }

    @ModifyExpressionValue(method = "bobView", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/entity/ClientAvatarState;getInterpolatedBob(F)F"))
    private float cyanite$cinematicBob(float bob) {
        return Cinematic.bob(bob);
    }

    // Cinematic's background blur needs the world's depth, which the game clears here to draw the hand.
    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V"))
    private void cyanite$cinematicDepth(net.minecraft.client.DeltaTracker deltaTracker, CallbackInfo ci) {
        CinematicRenderer.keepDepth();
    }

    // Cinematic's Smooth menus: the menu blur grows in with the backdrop.
    @ModifyExpressionValue(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;getMenuBackgroundBlurriness()I"))
    private int cyanite$cinematicMenuBlur(int blur) {
        return Cinematic.menuBlur(blur);
    }

    // FOV Changer's Steady: sprinting, Speed and bows don't widen or narrow it.
    @ModifyExpressionValue(method = "tickFov", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/player/AbstractClientPlayer;getFieldOfViewModifier(ZF)F"))
    private float cyanite$steadyFov(float modifier) {
        return com.cyanite.client.modules.render.FovChanger.steady() ? 1.0F : modifier;
    }

    // A CameraShot draws the world from its own camera first; your view is then drawn over it.
    @Inject(method = "render", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V"))
    private void cyanite$cameraShot(net.minecraft.client.DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        CameraShot.beforeWorld((GameRenderer) (Object) this, deltaTracker);
    }

    @Inject(method = "render", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
        target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V"))
    private void cyanite$motionBlur(net.minecraft.client.DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        com.cyanite.client.modules.render.MotionBlur blur = Modules.get(com.cyanite.client.modules.render.MotionBlur.class);
        if (blur != null) blur.apply();
        Cinematic.afterWorld();
        AcidMode.afterWorld();
    }

    // Acid Mode's sway: the world's view rolls gently, after the hurt shake (never where you aim).
    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/GameRenderer;bobHurt(Lcom/mojang/blaze3d/vertex/PoseStack;F)V"))
    private void cyanite$acidSway(GameRenderer renderer, PoseStack poseStack, float partialTick, Operation<Void> original) {
        original.call(renderer, poseStack, partialTick);
        if (!CameraShot.taking()) AcidMode.sway(poseStack);
    }

    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
    private void cyanite$noHurtCam(PoseStack poseStack, float partialTick, CallbackInfo ci) {
        if (Modules.get(NoHurtCam.class).isEnabled() || CameraShot.taking()) ci.cancel();
    }

    // A CameraShot's camera stands still, whatever your player's doing.
    @Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
    private void cyanite$shotBob(PoseStack poseStack, float partialTick, CallbackInfo ci) {
        if (CameraShot.taking()) ci.cancel();
    }

    // ...and outlines nothing: the block you're looking at isn't the picture's business.
    @Inject(method = "shouldRenderBlockOutline", at = @At("HEAD"), cancellable = true)
    private void cyanite$shotOutline(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        if (CameraShot.taking()) cir.setReturnValue(false);
    }

    // Freecam: no first-person hand floating in front of a camera that isn't your player. Cinematic's Hide hand and CameraShot too.
    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void cyanite$freecamHand(float partialTick, boolean sleeping, Matrix4f projection, CallbackInfo ci) {
        Freecam freecam = Modules.get(Freecam.class);
        if (freecam != null && freecam.isActive() || Cinematic.hidesHand() || CameraShot.taking()) ci.cancel();
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
