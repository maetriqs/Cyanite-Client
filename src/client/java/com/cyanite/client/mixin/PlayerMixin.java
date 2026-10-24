package com.cyanite.client.mixin;

import com.cyanite.client.modules.movement.NoClip;
import com.cyanite.client.modules.movement.SafeWalk;
import com.cyanite.client.system.Modules;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public class PlayerMixin {
    // The edge check sneaking uses. Only for the local player; the server's copy of you is untouched.
    @Inject(method = "isStayingOnGroundSurface", at = @At("HEAD"), cancellable = true)
    private void cyanite$safeWalk(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this == Minecraft.getInstance().player && Modules.get(SafeWalk.class).isEnabled()) cir.setReturnValue(true);
    }

    // No Clip: each tick starts with "no physics only for spectators". Everything after it in the
    // tick reads the result: collisions, being pushed out of walls, suffocating, the in-wall overlay.
    @WrapOperation(method = "tick", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/player/Player;noPhysics:Z", opcode = Opcodes.PUTFIELD))
    private void cyanite$noClip(Player player, boolean value, Operation<Void> original) {
        boolean noClip = !value && NoClip.appliesTo(player);
        original.call(player, value || noClip);
        if (noClip) player.resetFallDistance();
    }
}
