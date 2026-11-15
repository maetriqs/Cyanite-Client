package com.cyanite.client.mixin.visuals;

import com.cyanite.client.visuals.Hands;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// Sword/Axe Swing and Attack Animations, Golden Apple Eating, Totem Pop's held size: your first-person hand.
@Mixin(ItemInHandRenderer.class)
public class ItemInHandRendererMixin {
    @WrapOperation(method = "renderArmWithItem", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;swingArm(FLcom/mojang/blaze3d/vertex/PoseStack;ILnet/minecraft/world/entity/HumanoidArm;)V"))
    private void cyanite$swing(ItemInHandRenderer self, float progress, PoseStack pose, int arm, HumanoidArm side, Operation<Void> original,
                               @Local(argsOnly = true) ItemStack stack) {
        if (!Hands.swing(pose, progress, arm, stack)) original.call(self, progress, pose, arm, side);
    }

    @WrapOperation(method = "renderArmWithItem", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V"))
    private void cyanite$heldItem(ItemInHandRenderer self, LivingEntity entity, ItemStack stack, ItemDisplayContext context, PoseStack pose,
                                  SubmitNodeCollector collector, int light, Operation<Void> original, @Local(argsOnly = true) InteractionHand hand) {
        pose.pushPose();
        Hands.beforeItem(pose, stack, context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND ? -1 : 1, hand == InteractionHand.MAIN_HAND);
        original.call(self, entity, stack, context, pose, collector, light);
        pose.popPose();
    }

    @WrapOperation(method = "renderArmWithItem", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;applyEatTransform(Lcom/mojang/blaze3d/vertex/PoseStack;FLnet/minecraft/world/entity/HumanoidArm;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/player/Player;)V"))
    private void cyanite$eat(ItemInHandRenderer self, PoseStack pose, float partial, HumanoidArm arm, ItemStack stack, Player player, Operation<Void> original) {
        if (!Hands.eat(pose, partial, arm, stack, player)) original.call(self, pose, partial, arm, stack, player);
    }
}
