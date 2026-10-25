package com.cyanite.client.system;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Where the real world starts, as far as you can see. Servers that hide what's underground send
 * made-up layers in its place: nothing but deepslate (and maybe the bedrock floor). Donut's go up
 * to Y 16 while you're up top (Y 32 in some chunks); go down among them and the real ones come in.
 */
public final class Depths {
    private Depths() {
    }

    /** The top of a chunk's made-up layers, or Y 0 where nothing's made up. */
    public static int floor(LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        int top = chunk.getMinY();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section.hasOnlyAir()) break;
            // Counted, not read off the palette: a palette can still list blocks that are gone.
            int[] other = {0};
            section.getStates().count((st, n) -> {
                if (n > 0 && !st.is(Blocks.DEEPSLATE) && !st.is(Blocks.BEDROCK)) other[0] += n;
            });
            if (other[0] > 0) break;
            top = chunk.getMinY() + (i + 1) * 16;
        }
        return Math.max(0, top);
    }

    /** {@link #floor(LevelChunk)} for the chunk at a block, or 0 if that chunk isn't loaded. */
    public static int floorAt(Level level, int x, int z) {
        if (!(level.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) instanceof LevelChunk chunk)) return 0;
        return floor(chunk);
    }
}
