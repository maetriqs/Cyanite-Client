package com.cyanite.client.mixin;

import com.cyanite.client.modules.render.Nametags;
import com.cyanite.client.system.Modules;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderer.class)
public class EntityRendererMixin {
    // Hide the vanilla name above anything Nametags draws its own tag for.
    @Inject(method = "shouldShowName", at = @At("HEAD"), cancellable = true)
    private void cyanite$hideVanillaName(Entity entity, double distanceSq, CallbackInfoReturnable<Boolean> cir) {
        if (Modules.get(Nametags.class).tags(entity)) cir.setReturnValue(false);
    }
}
