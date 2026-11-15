package com.cyanite.client.mixin.visuals;

import com.cyanite.client.visuals.Explosions;
import com.cyanite.client.visuals.HitParticles;
import com.cyanite.client.visuals.Particles;
import com.cyanite.client.visuals.ShieldBreak;
import com.cyanite.client.visuals.TotemPop;
import com.cyanite.client.visuals.Visuals;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Visuals that answer what the server tells us: others' totem pops, explosions, shield breaks, your arrows landing.
// TAIL only runs on the game thread (the handlers bounce off the network thread first).
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerVisualsMixin {
    @Inject(method = "handleEntityEvent", at = @At("TAIL"))
    private void cyanite$otherPop(ClientboundEntityEventPacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (packet.getEventId() != 35 || mc.level == null) return;
        Entity entity = packet.getEntity(mc.level);
        if (entity != null && entity != mc.player) Visuals.run(TotemPop.GROUP, () -> TotemPop.otherPopped(entity));
    }

    // The particles an explosion makes are marked as an explosion's while it's handled.
    @WrapMethod(method = "handleExplosion")
    private void cyanite$explosion(ClientboundExplodePacket packet, Operation<Void> original) {
        boolean game = Minecraft.getInstance().isSameThread();
        if (game) Particles.beginExplosion();
        try {
            original.call(packet);
        } finally {
            if (game) Particles.end();
        }
        if (game) Visuals.run(Explosions.GROUP, () -> Explosions.onExplosion(packet.center(), packet.radius()));
    }

    @Inject(method = "handleSoundEvent", at = @At("TAIL"))
    private void cyanite$shieldBreak(ClientboundSoundPacket packet, CallbackInfo ci) {
        Visuals.run(ShieldBreak.GROUP, () -> ShieldBreak.onSound(packet));
    }

    @Inject(method = "handleDamageEvent", at = @At("TAIL"))
    private void cyanite$projectileHit(ClientboundDamageEventPacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        int you = mc.player.getId();
        // Caused by you but dealt by something else of yours: an arrow, a trident.
        if (packet.sourceCauseId() != you || packet.sourceDirectId() == you || packet.entityId() == you) return;
        Entity hit = mc.level.getEntity(packet.entityId());
        if (hit != null) Visuals.run(HitParticles.UHC, () -> HitParticles.onProjectileHit(hit));
    }
}
