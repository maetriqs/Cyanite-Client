package com.cyanite.client.mixin;

import com.cyanite.client.modules.combat.Criticals;
import com.cyanite.client.modules.world.ChestIndex;
import com.cyanite.client.modules.world.FastMine;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.cyanite.client.system.Macros;
import com.cyanite.client.system.Modules;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {
    @Shadow
    private boolean isDestroying;

    // A block that comes back after breaking (the server said no) is started again the way a fresh
    // click starts it, with START_DESTROY_BLOCK. Vanilla, with attack still held, goes straight on to
    // finishing it without a start, and a server that wants the start refuses that every time.
    @Inject(method = "sameDestroyTarget", at = @At("HEAD"), cancellable = true)
    private void cyanite$startAgain(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (!isDestroying) cir.setReturnValue(false);
    }

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

    // Macro recording: blocks you mine, place and right click, what you hit or use, menu slots you click.
    @Inject(method = "startDestroyBlock", at = @At("HEAD"))
    private void cyanite$recordMining(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> cir) {
        Macros.recorder().startedMining(pos);
    }

    @Inject(method = "destroyBlock", at = @At("RETURN"))
    private void cyanite$recordBroke(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) Macros.recorder().broke(pos);
    }

    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void cyanite$recordBeforeUseOn(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        Macros.recorder().beforeUseOn(hit);
    }

    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void cyanite$recordUseOn(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        Macros.recorder().usedOn(hit, cir.getReturnValue());
    }

    @Inject(method = "useItem", at = @At("RETURN"))
    private void cyanite$recordUseItem(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        Macros.recorder().usedItem(cir.getReturnValue());
    }

    @Inject(method = "interact", at = @At("RETURN"))
    private void cyanite$recordInteract(Player player, Entity entity, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        Macros.recorder().usedEntity(cir.getReturnValue());
    }

    @Inject(method = "interactAt", at = @At("RETURN"))
    private void cyanite$recordInteractAt(Player player, Entity entity, EntityHitResult hit, InteractionHand hand,
                                          CallbackInfoReturnable<InteractionResult> cir) {
        Macros.recorder().usedEntity(cir.getReturnValue());
    }

    @Inject(method = "attack", at = @At("HEAD"))
    private void cyanite$recordAttack(Player player, Entity target, CallbackInfo ci) {
        Macros.recorder().attacked();
    }

    @Inject(method = "handleInventoryMouseClick", at = @At("HEAD"))
    private void cyanite$recordMenuClick(int containerId, int slot, int button, ClickType type, Player player, CallbackInfo ci) {
        Macros.recorder().clicked(containerId, slot, button, type);
    }
}
