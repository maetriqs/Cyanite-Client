package com.cyanite.client.mixin;

import com.cyanite.client.modules.render.AcidMode;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.state.GuiItemRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(GuiRenderer.class)
public class GuiRendererMixin {
    // Acid Mode's rainbow items in the hotbar and menus: each item's copy out of the item atlas is
    // tinted, so the colour can move every frame without drawing the item again.
    @ModifyArg(method = "submitBlitFromItemAtlas", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/render/state/BlitRenderState;<init>(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/client/gui/render/TextureSetup;Lorg/joml/Matrix3x2f;IIIIFFFFILnet/minecraft/client/gui/navigation/ScreenRectangle;Lnet/minecraft/client/gui/navigation/ScreenRectangle;)V"),
        index = 11)
    private int cyanite$acidItems(int color, @Local(argsOnly = true) GuiItemRenderState item) {
        return AcidMode.guiItemColor(color, item.x(), item.y());
    }
}
