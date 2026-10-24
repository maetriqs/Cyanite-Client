package com.cyanite.client.mixin;

import com.cyanite.client.modules.movement.Jesus;
import com.cyanite.client.system.Modules;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LiquidBlock.class)
public class LiquidBlockMixin {
    @Inject(method = "getCollisionShape", at = @At("HEAD"), cancellable = true)
    private void cyanite$jesus(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context,
                               CallbackInfoReturnable<VoxelShape> cir) {
        Jesus jesus = Modules.get(Jesus.class);
        if (jesus != null && jesus.walkable(state, pos, context)) cir.setReturnValue(Shapes.block());
    }
}
