package com.cyanite.client.mixin;

import com.cyanite.client.system.BlockEntityLeaks;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Block entities the server names, for {@link BlockEntityLeaks} (Leak Finder, Spawner Finder, Deepslate Bypass). Every
 * hook is at TAIL: each handler first hands itself over to the game thread and throws on the netty one,
 * so only the game thread gets here, after the client has taken in what it was told.
 */
@Mixin(ClientPacketListener.class)
public class BlockEntityLeakMixin {
    // The chunk's list of block entities, against what the client kept of it.
    @Inject(method = "handleLevelChunkWithLight", at = @At("TAIL"))
    private void cyanite$leaksChunk(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        BlockEntityLeaks.onChunk(packet.getX(), packet.getZ(), packet.getChunkData());
    }

    @Inject(method = "handleBlockEntityData", at = @At("TAIL"))
    private void cyanite$leaksBlockEntity(ClientboundBlockEntityDataPacket packet, CallbackInfo ci) {
        BlockEntityLeaks.onBlockEntityData(packet.getPos(), packet.getType(), packet.getTag());
    }

    @Inject(method = "handleBlockUpdate", at = @At("TAIL"))
    private void cyanite$leaksBlockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        BlockEntityLeaks.onBlockChanged(packet.getPos(), packet.getBlockState());
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At("TAIL"))
    private void cyanite$leaksBlocksUpdate(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        BlockEntityLeaks.onBlocksChanged(packet);
    }

    @Inject(method = "handleForgetLevelChunk", at = @At("TAIL"))
    private void cyanite$leaksForget(ClientboundForgetLevelChunkPacket packet, CallbackInfo ci) {
        BlockEntityLeaks.onForget(packet.pos());
    }

    // A chest opening, a spawner after a spawn: at a spot the client has as rock, that's one hidden there.
    @Inject(method = "handleBlockEvent", at = @At("TAIL"))
    private void cyanite$leaksBlockEvent(ClientboundBlockEventPacket packet, CallbackInfo ci) {
        BlockEntityLeaks.onBlockEvent(packet.getPos(), packet.getBlock(), packet.getB0(), packet.getB1());
    }

    @Inject(method = "handleLevelEvent", at = @At("TAIL"))
    private void cyanite$leaksLevelEvent(ClientboundLevelEventPacket packet, CallbackInfo ci) {
        if (!packet.isGlobalEvent()) BlockEntityLeaks.onLevelEvent(packet.getType(), packet.getPos());
    }
}
