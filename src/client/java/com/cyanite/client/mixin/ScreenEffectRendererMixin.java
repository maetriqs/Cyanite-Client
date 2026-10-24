package com.cyanite.client.mixin;

import com.cyanite.client.modules.movement.BoatFly;
import com.cyanite.client.modules.render.SmallTotem;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.cyanite.client.system.Modules;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Boat Fly's Noclip: no dark in-a-block screen while you drive through walls, so you can see where you're going.
@Mixin(ScreenEffectRenderer.class)
public class ScreenEffectRendererMixin {
    @Inject(method = "getViewBlockingState", at = @At("HEAD"), cancellable = true)
    private static void cyanite$noclipView(Player player, CallbackInfoReturnable<BlockState> cir) {
        BoatFly boatFly = Modules.get(BoatFly.class);
        if (boatFly != null && boatFly.noclipping()) cir.setReturnValue(null);
    }

    // Small Totem: the totem that flies up when one pops, scaled down where it's sized.
    @WrapOperation(method = "renderItemActivationAnimation", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;scale(FFF)V"))
    private void cyanite$smallTotem(PoseStack pose, float x, float y, float z, Operation<Void> original) {
        SmallTotem small = Modules.get(SmallTotem.class);
        float f = small != null ? small.factor() : 1;
        original.call(pose, x * f, y * f, z * f);
    }
}
