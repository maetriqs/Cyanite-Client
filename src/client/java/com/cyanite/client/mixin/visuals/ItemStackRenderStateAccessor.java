package com.cyanite.client.mixin.visuals;

import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ItemStackRenderState.class)
public interface ItemStackRenderStateAccessor {
    @Accessor("layers")
    ItemStackRenderState.LayerRenderState[] cyanite$layers();

    @Accessor("activeLayerCount")
    int cyanite$activeLayerCount();
}
