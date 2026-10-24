package com.cyanite.client.mixin;

import com.cyanite.client.modules.render.Freecam;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Freecam: work out which chunk sections to draw from the player, where chunks are loaded, not the camera. */
@Mixin(SectionOcclusionGraph.class)
public class SectionOcclusionGraphMixin {
    @ModifyExpressionValue(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;position()Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 cyanite$freecamCentre(Vec3 camera) {
        Vec3 centre = Freecam.renderCentre();
        return centre == null ? camera : centre;
    }

    @ModifyExpressionValue(method = "initializeQueueForFullUpdate", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;blockPosition()Lnet/minecraft/core/BlockPos;"))
    private BlockPos cyanite$freecamStart(BlockPos camera) {
        Vec3 centre = Freecam.renderCentre();
        return centre == null ? camera : BlockPos.containing(centre);
    }
}
