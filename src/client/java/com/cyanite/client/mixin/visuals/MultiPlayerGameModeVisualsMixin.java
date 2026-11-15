package com.cyanite.client.mixin.visuals;

import com.cyanite.client.visuals.Combat;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The Visuals that answer your hits: attack animations, hit particles, target highlight.
@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeVisualsMixin {
    @Inject(method = "attack", at = @At("HEAD"))
    private void cyanite$visualsAttack(Player player, Entity target, CallbackInfo ci) {
        Combat.attacked(target);
    }
}
