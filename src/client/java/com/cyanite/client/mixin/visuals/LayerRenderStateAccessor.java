package com.cyanite.client.mixin.visuals;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(ItemStackRenderState.LayerRenderState.class)
public interface LayerRenderStateAccessor {
    @Accessor("quads")
    List<BakedQuad> cyanite$quads();

    @Accessor("tintLayers")
    int[] cyanite$tintLayers();

    @Accessor("tintLayers")
    void cyanite$setTintLayers(int[] tints);

    @Accessor("specialRenderer")
    SpecialModelRenderer<Object> cyanite$specialRenderer();
}
