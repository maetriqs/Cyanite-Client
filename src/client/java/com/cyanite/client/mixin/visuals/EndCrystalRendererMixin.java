package com.cyanite.client.mixin.visuals;

import com.cyanite.client.visuals.Crystals;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EndCrystalRenderer;
import net.minecraft.client.renderer.entity.EnderDragonRenderer;
import net.minecraft.client.renderer.entity.state.EndCrystalRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// End Crystal Size and Crystal Beam: the crystal's size, colour and see-through-ness, and a beam into the sky.
@Mixin(EndCrystalRenderer.class)
public class EndCrystalRendererMixin {
    @WrapOperation(method = "submit(Lnet/minecraft/client/renderer/entity/state/EndCrystalRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;scale(FFF)V"))
    private void cyanite$crystalSize(PoseStack pose, float x, float y, float z, Operation<Void> original) {
        original.call(pose, Crystals.size(x), Crystals.size(y), Crystals.size(z));
    }

    @WrapOperation(method = "submit(Lnet/minecraft/client/renderer/entity/state/EndCrystalRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V"))
    private void cyanite$crystalLook(SubmitNodeCollector collector, Model<?> model, Object state, PoseStack pose, RenderType type, int light, int overlay,
                                     int outline, ModelFeatureRenderer.CrumblingOverlay crumbling, Operation<Void> original) {
        int tint = Crystals.tint();
        if (tint == -1) {
            original.call(collector, model, state, pose, type, light, overlay, outline, crumbling);
            return;
        }
        @SuppressWarnings("unchecked") Model<Object> m = (Model<Object>) model;
        collector.submitModel(m, state, pose, Crystals.renderType(type), light, overlay, tint, null, outline, crumbling);
    }

    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/EndCrystalRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V",
        at = @At("HEAD"))
    private void cyanite$skyBeam(EndCrystalRenderState state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
        if (state.beamOffset != null || !Crystals.skyBeam()) return;
        // Drawn the way the game draws a beam to a block above: from up there, back down to the crystal.
        float length = Crystals.skyLength();
        pose.pushPose();
        pose.translate(0, length, 0);
        EnderDragonRenderer.submitCrystalBeams(0, -length + EndCrystalRenderer.getY(state.ageInTicks), 0, state.ageInTicks, pose, collector, state.lightCoords);
        pose.popPose();
    }
}
