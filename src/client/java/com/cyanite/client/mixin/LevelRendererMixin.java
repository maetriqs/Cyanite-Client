package com.cyanite.client.mixin;


import com.cyanite.client.render.Projection;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
    // Remember this frame's camera matrices so 2D overlays like nametags can project world positions.
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void cyanite$captureMatrices(GraphicsResourceAllocator allocator, DeltaTracker deltaTracker, boolean renderOutline,
                                         Camera camera, Matrix4f modelView, Matrix4f projection, Matrix4f cullingProjection,
                                         GpuBufferSlice fog, Vector4f fogColor, boolean renderSky, CallbackInfo ci) {
        Projection.capture(camera.position(), modelView, projection);
    }
}
