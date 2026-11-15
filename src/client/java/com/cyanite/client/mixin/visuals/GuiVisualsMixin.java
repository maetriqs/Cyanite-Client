package com.cyanite.client.mixin.visuals;

import com.cyanite.client.visuals.Crosshair;
import com.cyanite.client.visuals.Visuals;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// Custom Crosshair: drawn in place of the game's crosshair sprite (the attack indicator under it stays).
@Mixin(Gui.class)
public class GuiVisualsMixin {
    @WrapOperation(method = "renderCrosshair", at = @At(value = "INVOKE", ordinal = 0,
        target = "Lnet/minecraft/client/gui/GuiGraphics;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
    private void cyanite$crosshair(GuiGraphics g, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h, Operation<Void> original) {
        if (!Visuals.test(Crosshair.GROUP, () -> Crosshair.render(g))) original.call(g, pipeline, sprite, x, y, w, h);
    }
}
