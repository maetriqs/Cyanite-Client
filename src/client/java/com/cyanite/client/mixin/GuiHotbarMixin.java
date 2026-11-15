package com.cyanite.client.mixin;

import com.cyanite.client.modules.render.AcidMode;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Gui.class)
public class GuiHotbarMixin {
    // Acid Mode: the hotbar and its selection box cycle through the rainbow with everything else.
    @WrapOperation(method = "renderItemHotbar", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/GuiGraphics;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
    private void cyanite$acidHotbar(GuiGraphics graphics, RenderPipeline pipeline, Identifier sprite, int x, int y, int width, int height,
                                    Operation<Void> original) {
        int color = AcidMode.hotbarColor(x);
        if (color == -1) original.call(graphics, pipeline, sprite, x, y, width, height);
        else graphics.blitSprite(pipeline, sprite, x, y, width, height, color);
    }
}
