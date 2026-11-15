package com.cyanite.client.mixin.visuals;

import com.cyanite.client.visuals.BlockHighlight;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

// Block Highlight: the outline's colour and width (the second call; the first is High Contrast's dark backing).
@Mixin(LevelRenderer.class)
public class LevelRendererVisualsMixin {
    @ModifyArg(method = "renderBlockOutline", index = 6, at = @At(value = "INVOKE", ordinal = 1,
        target = "Lnet/minecraft/client/renderer/LevelRenderer;renderHitOutline(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;DDDLnet/minecraft/client/renderer/state/BlockOutlineRenderState;IF)V"))
    private int cyanite$outlineColor(int color) {
        return BlockHighlight.color(color);
    }

    @ModifyArg(method = "renderBlockOutline", index = 7, at = @At(value = "INVOKE", ordinal = 1,
        target = "Lnet/minecraft/client/renderer/LevelRenderer;renderHitOutline(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;DDDLnet/minecraft/client/renderer/state/BlockOutlineRenderState;IF)V"))
    private float cyanite$outlineWidth(float width) {
        return BlockHighlight.width(width);
    }
}
