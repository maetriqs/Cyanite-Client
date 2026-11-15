package com.cyanite.client.mixin.visuals;

import com.cyanite.client.visuals.LowFire;
import com.cyanite.client.visuals.TotemPop;
import com.cyanite.client.visuals.Visuals;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Totem Pop: the popped totem drawn our way. Low Fire: the flames over your screen lower and fainter.
@Mixin(ScreenEffectRenderer.class)
public class ScreenEffectVisualsMixin {
    @Inject(method = "displayItemActivation", at = @At("HEAD"))
    private void cyanite$totemStart(ItemStack stack, RandomSource random, CallbackInfo ci) {
        TotemPop.start(stack);
    }

    @Inject(method = "renderItemActivationAnimation", at = @At("HEAD"), cancellable = true)
    private void cyanite$totemPop(PoseStack pose, float partialTick, SubmitNodeCollector collector, CallbackInfo ci) {
        if (Visuals.test(TotemPop.GROUP, () -> TotemPop.render(pose, collector))) ci.cancel();
    }

    @ModifyArg(method = "renderFire", index = 1, at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"))
    private static float cyanite$fireHeight(float y) {
        return y - LowFire.drop();
    }

    @ModifyArg(method = "renderFire", index = 3, at = @At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/vertex/VertexConsumer;setColor(FFFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;"))
    private static float cyanite$fireAlpha(float alpha) {
        return LowFire.alpha(alpha);
    }
}
