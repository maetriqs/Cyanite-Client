package com.cyanite.client.mixin;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(BlockItem.class)
public interface BlockItemInvoker {
    /** What a block item would place here: torches and signs pick their wall or standing block in this. */
    @Invoker("getPlacementState")
    BlockState cyanite$placementState(BlockPlaceContext context);
}
