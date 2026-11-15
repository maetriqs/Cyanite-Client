package com.cyanite.client.mixin;

import com.cyanite.client.modules.misc.Pets;
import com.cyanite.client.render.MonkeyRenderer;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherMixin {
    @Unique
    private MonkeyRenderer cyanite$monkey;

    // Pets' monkey is a wolf underneath: made alongside the game's own renderers on every resource reload.
    @Inject(method = "onResourceManagerReload", at = @At("TAIL"))
    private void cyanite$makeMonkey(ResourceManager manager, CallbackInfo ci, @Local EntityRendererProvider.Context context) {
        cyanite$monkey = new MonkeyRenderer(context);
    }

    @Inject(method = "getRenderer(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/client/renderer/entity/EntityRenderer;",
        at = @At("HEAD"), cancellable = true)
    private void cyanite$monkeyFor(Entity entity, CallbackInfoReturnable<EntityRenderer<?, ?>> cir) {
        if (cyanite$monkey != null && Pets.isMonkey(entity)) cir.setReturnValue(cyanite$monkey);
    }

    // Drawing goes by the render state's entity type (a wolf's): the monkey's own state picks it out.
    @Inject(method = "getRenderer(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;)Lnet/minecraft/client/renderer/entity/EntityRenderer;",
        at = @At("HEAD"), cancellable = true)
    private void cyanite$monkeyDraws(EntityRenderState state, CallbackInfoReturnable<EntityRenderer<?, ?>> cir) {
        if (cyanite$monkey != null && state instanceof MonkeyRenderer.State) cir.setReturnValue(cyanite$monkey);
    }
}
