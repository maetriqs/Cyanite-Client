package com.cyanite.client.mixin;

import com.cyanite.client.modules.misc.Pets;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public class LivingEntityMixin {
    // Pets only exist on your screen: never in the crosshair's way, never pushed or pushing you.
    @Inject(method = "isPickable", at = @At("HEAD"), cancellable = true)
    private void cyanite$petNotPickable(CallbackInfoReturnable<Boolean> cir) {
        if (Pets.isPet((Entity) (Object) this)) cir.setReturnValue(false);
    }

    @Inject(method = "isPushable", at = @At("HEAD"), cancellable = true)
    private void cyanite$petNotPushable(CallbackInfoReturnable<Boolean> cir) {
        if (Pets.isPet((Entity) (Object) this)) cir.setReturnValue(false);
    }

    @Inject(method = "pushEntities", at = @At("HEAD"), cancellable = true)
    private void cyanite$petPushesNothing(CallbackInfo ci) {
        if (Pets.isPet((Entity) (Object) this)) ci.cancel();
    }
}
