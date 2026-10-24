package com.cyanite.client.mixin;

import com.cyanite.client.modules.combat.Criticals;
import com.cyanite.client.modules.world.ChestIndex;
import com.cyanite.client.modules.world.FastMine;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.cyanite.client.system.Modules;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {
    // Fast Mine: each tick of holding the button on a block counts for more.
    @ModifyExpressionValue(method = "continueDestroyBlock", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/block/state/BlockState;getDestroyProgress(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)F"))
    private float cyanite$fastMine(float progress) {
        FastMine fastMine = Modules.get(FastMine.class);
        return fastMine != null ? fastMine.speedUp(progress) : progress;
    }

    @Inject(method = "attack", at = @At("HEAD"))
    private void cyanite$criticals(Player player, Entity target, CallbackInfo ci) {
        Criticals criticals = Modules.get(Criticals.class);
        if (criticals.isEnabled()) criticals.beforeAttack(target);
        com.cyanite.client.modules.misc.Pets.onAttack(target);
        com.cyanite.client.hud.TargetHud.onAttack(target);
    }

    // Chest Index: the container you right clicked, so the menu that opens next is known to be its.
    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void cyanite$chestIndexUse(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        ChestIndex.onUse(hit.getBlockPos());
        com.cyanite.client.modules.combat.AutoAnchor.onUse(hand, hit);
    }
}
