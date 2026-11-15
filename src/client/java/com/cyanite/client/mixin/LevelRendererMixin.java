package com.cyanite.client.mixin;


import com.cyanite.client.modules.render.Cinematic;
import com.cyanite.client.render.CameraShot;
import com.cyanite.client.render.Projection;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.SharedConstants;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.BlockOutlineRenderState;
import net.minecraft.client.renderer.state.LevelRenderState;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
    // Remember this frame's camera matrices so 2D overlays like nametags can project world positions.
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void cyanite$captureMatrices(GraphicsResourceAllocator allocator, DeltaTracker deltaTracker, boolean renderOutline,
                                         Camera camera, Matrix4f modelView, Matrix4f projection, Matrix4f cullingProjection,
                                         GpuBufferSlice fog, Vector4f fogColor, boolean renderSky, CallbackInfo ci) {
        if (!CameraShot.taking()) Projection.capture(camera.position(), modelView, projection);
    }

    // Cinematic's block outline: once a frame, the block the game picked to outline (or none) sets where it glides to.
    @Inject(method = "extractBlockOutline", at = @At("TAIL"))
    private void cyanite$aimOutline(Camera camera, LevelRenderState state, CallbackInfo ci) {
        if (CameraShot.taking()) return;
        BlockOutlineRenderState outline = state.blockOutlineRenderState;
        Cinematic.aimOutline(outline == null ? null : outline.pos(), outline == null ? null : outline.shape());
    }

    // Its color, on the outline itself (the second call; the first is High Contrast's dark backing, which stays dark).
    @ModifyArg(method = "renderBlockOutline", index = 6, at = @At(value = "INVOKE", ordinal = 1,
        target = "Lnet/minecraft/client/renderer/LevelRenderer;renderHitOutline(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;DDDLnet/minecraft/client/renderer/state/BlockOutlineRenderState;IF)V"))
    private int cyanite$outlineColor(int color) {
        return Cinematic.outlineColor(color);
    }

    // And drawn where the glide has got to, still with the game's own lines (so walls hide it as usual).
    @Inject(method = "renderHitOutline", at = @At("HEAD"), cancellable = true)
    private void cyanite$glideOutline(PoseStack poseStack, VertexConsumer lines, double camX, double camY, double camZ,
                                      BlockOutlineRenderState state, int color, float width, CallbackInfo ci) {
        if (SharedConstants.DEBUG_SHAPES || !Cinematic.drawsOutline()) return;
        if (Cinematic.drawOutline(poseStack, lines, camX, camY, camZ, state.shape(), color, width)) ci.cancel();
    }
}
