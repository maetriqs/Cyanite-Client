package com.cyanite.client.mixin;

import com.cyanite.client.modules.donut.ChunkChangeFinder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Chunk Change Finder: what the client had of a chunk before the server takes it away or sends it
// again, and what came in. HEAD runs twice (the network thread hands the packet on to the game's):
// only the game thread's counts. TAIL only runs on the game thread.
@Mixin(ClientPacketListener.class)
public class ChunkResendMixin {
    @Inject(method = "handleLevelChunkWithLight", at = @At("HEAD"))
    private void cyanite$chunkComing(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        if (Minecraft.getInstance().isSameThread()) ChunkChangeFinder.onChunkComing(packet.getX(), packet.getZ());
    }

    @Inject(method = "handleLevelChunkWithLight", at = @At("TAIL"))
    private void cyanite$chunkCame(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        ChunkChangeFinder.onChunkCame(packet.getX(), packet.getZ());
    }

    @Inject(method = "handleForgetLevelChunk", at = @At("HEAD"))
    private void cyanite$chunkForgetting(ClientboundForgetLevelChunkPacket packet, CallbackInfo ci) {
        if (Minecraft.getInstance().isSameThread()) ChunkChangeFinder.onChunkForgetting(packet.pos());
    }
}
