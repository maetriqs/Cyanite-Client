package com.cyanite.client.mixin;

import com.cyanite.client.modules.render.Xray;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Xray. Both vanilla and Sodium only mesh blocks whose render shape is MODEL, and both
 * cull a face against its neighbour's face occlusion shape, so these two hooks cover either renderer.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public class BlockStateBaseMixin {
    @Inject(method = "getRenderShape", at = @At("HEAD"), cancellable = true)
    private void cyanite$xrayHide(CallbackInfoReturnable<RenderShape> cir) {
        if (Xray.hides((BlockState) (Object) this)) cir.setReturnValue(RenderShape.INVISIBLE);
    }

    // Hidden blocks mustn't cull the faces of the ores next to them.
    @Inject(method = "getFaceOcclusionShape", at = @At("HEAD"), cancellable = true)
    private void cyanite$xrayFaces(Direction direction, CallbackInfoReturnable<VoxelShape> cir) {
        if (Xray.hides((BlockState) (Object) this)) cir.setReturnValue(Shapes.empty());
    }
}
