package com.cyanite.client.mixin;

import com.cyanite.client.modules.render.AcidMode;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

// Priority above Sodium's: Sodium wraps the same call with its own faster drawing, and this one has to be outside it.
@Mixin(value = ItemRenderer.class, priority = 1500)
public class ItemRendererMixin {
    // Acid Mode's rainbow items: held, dropped and framed items drawn with a rainbow drifting over them.
    // Not the menus' items: those are drawn once into the game's item atlas, and tinted as they're copied out (GuiRendererMixin).
    @WrapOperation(method = "renderItem", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/entity/ItemRenderer;renderQuadList(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;Ljava/util/List;[III)V"))
    private static void cyanite$acidItems(PoseStack poseStack, VertexConsumer consumer, List<BakedQuad> quads, int[] tints, int light, int overlay,
                                          Operation<Void> original, @Local(argsOnly = true) ItemDisplayContext context) {
        if (context == ItemDisplayContext.GUI || !AcidMode.rainbowItems()) {
            original.call(poseStack, consumer, quads, tints, light, overlay);
            return;
        }
        AcidMode.drawRainbowItem(poseStack, consumer, quads, tints, light, overlay);
    }
}
