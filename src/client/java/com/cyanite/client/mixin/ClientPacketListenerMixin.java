package com.cyanite.client.mixin;

import com.cyanite.client.modules.combat.PopCounter;
import com.cyanite.client.modules.combat.Velocity;
import com.cyanite.client.modules.donut.ChunkFinder;
import com.cyanite.client.modules.misc.AntiVanish;
import com.cyanite.client.modules.movement.BoatFly;
import com.cyanite.client.system.ChunkScanner;
import com.cyanite.client.system.Modules;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {
    @WrapOperation(
        method = "handleSetEntityMotion",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;lerpMotion(Lnet/minecraft/world/phys/Vec3;)V")
    )
    private void cyanite$velocity(Entity entity, Vec3 motion, Operation<Void> original) {
        Velocity velocity = Modules.get(Velocity.class);
        if (velocity.isEnabled() && entity == Minecraft.getInstance().player) {
            motion = velocity.modifyMotion(entity.getDeltaMovement(), motion);
        }
        original.call(entity, motion);
    }

    @ModifyExpressionValue(
        method = "handleExplosion",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/game/ClientboundExplodePacket;playerKnockback()Ljava/util/Optional;")
    )
    private Optional<Vec3> cyanite$explosionKnockback(Optional<Vec3> knockback) {
        Velocity velocity = Modules.get(Velocity.class);
        return velocity.isEnabled() ? knockback.map(velocity::modifyExplosion) : knockback;
    }

    // Boat Fly's "Ignore setbacks": the server snapping your boat back to where it thinks it is.
    @Inject(method = "handleMoveVehicle", at = @At("HEAD"), cancellable = true)
    private void cyanite$boatSetback(ClientboundMoveVehiclePacket packet, CallbackInfo ci) {
        if (!Minecraft.getInstance().isSameThread()) return;
        if (Modules.get(BoatFly.class).ignoresSetbacks()) ci.cancel();
    }

    // Anti Vanish: containers opening with nobody there. TAIL only runs on the game thread.
    @Inject(method = "handleBlockEvent", at = @At("TAIL"))
    private void cyanite$antiVanishBlockEvent(ClientboundBlockEventPacket packet, CallbackInfo ci) {
        AntiVanish.onBlockEvent(packet.getPos(), packet.getBlock(), packet.getB0(), packet.getB1());
    }

    // Chunk Finder: a chest or piston going off below Y 0, where the server hides the blocks themselves.
    @Inject(method = "handleBlockEvent", at = @At("TAIL"))
    private void cyanite$chunkFinderBlockEvent(ClientboundBlockEventPacket packet, CallbackInfo ci) {
        ChunkFinder.onBlockEvent(packet.getPos(), packet.getBlock());
    }

    // Chunk Finder: sounds from below Y 0 (chests, furnaces, villagers, farm animals).
    @Inject(method = "handleSoundEvent", at = @At("TAIL"))
    private void cyanite$chunkFinderSound(ClientboundSoundPacket packet, CallbackInfo ci) {
        ChunkFinder.onSound(packet.getSound(), packet.getX(), packet.getY(), packet.getZ());
    }

    @Inject(method = "handleBlockUpdate", at = @At("TAIL"))
    private void cyanite$antiVanishBlockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        AntiVanish.onBlockChanged(packet.getPos(), packet.getBlockState());
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At("TAIL"))
    private void cyanite$antiVanishBlocksUpdate(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        if (AntiVanish.watching()) packet.runUpdates(AntiVanish::onBlockChanged);
    }

    // Pop Counter: totem pops and deaths. TAIL only runs on the game thread.
    @Inject(method = "handleEntityEvent", at = @At("TAIL"))
    private void cyanite$popCounter(ClientboundEntityEventPacket packet, CallbackInfo ci) {
        Entity entity = packet.getEntity(Minecraft.getInstance().level);
        if (entity != null) PopCounter.onEntityEvent(entity, packet.getEventId());
    }

    // Chunk Finder: real blocks below Y 0 in block changes, where the chunk itself came through as rock.
    @Inject(method = "handleBlockUpdate", at = @At("TAIL"))
    private void cyanite$chunkFinderBlockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        ChunkFinder.onBlockChanged(packet.getPos(), packet.getBlockState());
        ChunkScanner.blockChanged(packet.getPos());
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At("TAIL"))
    private void cyanite$chunkFinderBlocksUpdate(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        if (ChunkFinder.listening()) packet.runUpdates(ChunkFinder::onBlockChanged);
        // Every change in a section is in one chunk: one more look at it does for all of them.
        packet.runUpdates((pos, state) -> ChunkScanner.blockChanged(pos));
    }

    // Chunk Finder: a chunk's block entities, read before the client drops the ones at spots it's been told are rock.
    @Inject(method = "handleLevelChunkWithLight", at = @At("HEAD"))
    private void cyanite$chunkFinderChunk(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        if (!Minecraft.getInstance().isSameThread() || !ChunkFinder.listening()) return;
        packet.getChunkData().getBlockEntitiesTagsConsumer(packet.getX(), packet.getZ())
            .accept((pos, type, tag) -> ChunkFinder.onChunkBlockEntity(pos, type));
    }

    @Inject(method = "handleBlockEntityData", at = @At("HEAD"))
    private void cyanite$chunkFinderBlockEntity(ClientboundBlockEntityDataPacket packet, CallbackInfo ci) {
        if (Minecraft.getInstance().isSameThread()) ChunkFinder.onBlockEntity(packet.getPos(), packet.getType());
    }

    // Chunk Finder: dispensers, jukeboxes, anvils below Y 0, and "block broken" particles, which name the real block.
    @Inject(method = "handleLevelEvent", at = @At("HEAD"))
    private void cyanite$chunkFinderLevelEvent(ClientboundLevelEventPacket packet, CallbackInfo ci) {
        if (Minecraft.getInstance().isSameThread()) ChunkFinder.onLevelEvent(packet.getType(), packet.getPos(), packet.getData());
    }
}
