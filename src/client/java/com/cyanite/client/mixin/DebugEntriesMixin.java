package com.cyanite.client.mixin;

import com.cyanite.client.modules.misc.StreamerMode;
import net.minecraft.client.gui.components.debug.DebugEntryChunkGeneration;
import net.minecraft.client.gui.components.debug.DebugEntryLookingAtBlock;
import net.minecraft.client.gui.components.debug.DebugEntryLookingAtFluid;
import net.minecraft.client.gui.components.debug.DebugEntryPosition;
import net.minecraft.client.gui.components.debug.DebugEntrySectionPosition;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Streamer Mode: the F3 lines that say where you are, or where what you're looking at is, don't show. */
@Mixin({DebugEntryPosition.class, DebugEntrySectionPosition.class, DebugEntryLookingAtBlock.class,
    DebugEntryLookingAtFluid.class, DebugEntryChunkGeneration.class})
public class DebugEntriesMixin {
    @Inject(method = "display", at = @At("HEAD"), cancellable = true)
    private void cyanite$hideWhereYouAre(DebugScreenDisplayer displayer, Level level, LevelChunk clientChunk, LevelChunk serverChunk, CallbackInfo ci) {
        if (StreamerMode.hidesF3()) ci.cancel();
    }
}
