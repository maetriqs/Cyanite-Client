package com.cyanite.client.mixin;

import com.cyanite.client.gui.Home;
import com.cyanite.client.modules.render.Cinematic;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cinematic's Smooth menus: a menu opened over the game fades its darkened backdrop in instead of cutting to it.
 * Out of a world, Cyanite's backdrop in place of the panorama.
 */
@Mixin(Screen.class)
public class ScreenMixin {
    // The tint behind in-game menus (inventory, chests): top and bottom colors of its gradient.
    @ModifyArg(method = "renderTransparentBackground", index = 4,
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;fillGradient(IIIIII)V"))
    private int cyanite$fadeTop(int color) {
        return fade(color);
    }

    @ModifyArg(method = "renderTransparentBackground", index = 5,
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;fillGradient(IIIIII)V"))
    private int cyanite$fadeBottom(int color) {
        return fade(color);
    }

    // The dark texture behind the pause menu, options and the like.
    @WrapOperation(method = "renderMenuBackgroundTexture", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/GuiGraphics;blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIII)V"))
    private static void cyanite$fadeMenuTexture(GuiGraphics graphics, RenderPipeline pipeline, Identifier texture, int x, int y,
                                                float u, float v, int width, int height, int textureWidth, int textureHeight,
                                                Operation<Void> original) {
        float progress = Cinematic.menuProgress();
        if (progress >= 1) {
            original.call(graphics, pipeline, texture, x, y, u, v, width, height, textureWidth, textureHeight);
            return;
        }
        graphics.blit(pipeline, texture, x, y, u, v, width, height, textureWidth, textureHeight, ARGB.white(progress));
    }

    // Out of a world the menus show Cyanite's backdrop instead of the panorama ("Cyanite title screen").
    @Inject(method = "renderPanorama", at = @At("HEAD"), cancellable = true)
    private void cyanite$backdrop(GuiGraphics graphics, float partialTick, CallbackInfo ci) {
        if (Home.backdrop(graphics)) ci.cancel();
    }

    private static int fade(int color) {
        float progress = Cinematic.menuProgress();
        return progress >= 1 ? color : ARGB.multiplyAlpha(color, progress);
    }
}
