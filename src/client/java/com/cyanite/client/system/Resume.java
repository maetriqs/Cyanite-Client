package com.cyanite.client.system;

import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * The half of a {@link Rejoin} that lives in the module: its four settings, where you were last
 * tick, and, once you're back on, whether that's where you left off with the world loaded round
 * you (carry on), not yet (wait), or never going to be (stop, saying why). Auto Mine and Schematic
 * Builder each keep one.
 */
public final class Resume {
    /** Carries on only this close to where it left. */
    private static final int RANGE = 24;
    /** Chunks and the server's corrections settle first. */
    private static final long SETTLE = 3000;
    /** Back but somewhere else (a lobby on the way in, say): how long it waits to be put back. */
    private static final long WAIT = 60_000;

    /** What to do now that the world's back. */
    public enum State { WAIT, HERE, LOST }

    /** A dropped connection or a restart doesn't end the run: back in, and on from where it left. */
    private final BoolSetting rejoin;
    /** The wait after a dropped connection; twice that for a busy proxy, six times for a restart, doubling each try. */
    private final SliderSetting delay;
    private final SliderSetting tries;
    /** A kick it can't place (a bare "Disconnected", a plugin's own message) might be a person: stops unless told otherwise. */
    private final ModeSetting unknown;

    private BlockPos spot;
    private ResourceKey<Level> level;
    private long seen;

    /** The settings go into the module's menu here, in the order they're made. */
    public Resume(Module module) {
        rejoin = module.add(new BoolSetting("Rejoin after disconnect", true));
        delay = module.add(new SliderSetting("Rejoin delay", 5, 1, 60, 1).format(v -> (int) v + "s"));
        tries = module.add(new SliderSetting("Rejoin tries", 10, 1, 50, 1).format(v -> (int) v + (v == 1 ? " try" : " tries")));
        unknown = module.add(new ModeSetting("Unknown disconnects", "Stop", List.of("Stop", "Rejoin")));
    }

    // ---- For Rejoin.Client

    public boolean on() {
        return rejoin.get();
    }

    public boolean unknown() {
        return unknown.is("Rejoin");
    }

    public int tries() {
        return tries.get().intValue();
    }

    public long delay() {
        return Math.round(delay.get() * 1000);
    }

    // ---- Where you were

    /** A new run: nowhere yet. */
    public void clear() {
        spot = null;
        level = null;
        seen = 0;
    }

    /** Every tick you're on: where you are. */
    public void saw(LocalPlayer player) {
        spot = player.blockPosition();
        level = player.level().dimension();
        seen = System.currentTimeMillis();
    }

    /** How long it was gone, for timers that shouldn't count it. */
    public long gone(long now) {
        return seen == 0 ? 0 : Math.max(0, now - seen);
    }

    /**
     * Back on the server after a rejoin: HERE once you're where it left with the world in round
     * you, LOST if that's not going to happen ({@link #lostWhy}), else WAIT.
     */
    public State back(LocalPlayer player) {
        long back = Rejoin.backFor();
        if (back < 0) return State.WAIT;
        boolean here = here(player);
        if (here && back >= SETTLE && loadedRound(player)) return State.HERE;
        return back < WAIT ? State.WAIT : State.LOST;
    }

    /** Why it can't carry on, after {@link State#LOST}. */
    public String lostWhy(LocalPlayer player) {
        if (here(player)) return "Back on the server, but the world round you never loaded";
        if (spot == null || player.level().dimension() != level) return "Back on the server, but not in the same world";
        return "Back on the server, but " + (int) Math.sqrt(player.blockPosition().distSqr(spot)) + " blocks from where it left";
    }

    private boolean here(LocalPlayer player) {
        return spot != null && player.level().dimension() == level && player.blockPosition().closerThan(spot, RANGE);
    }

    private static boolean loadedRound(LocalPlayer player) {
        ChunkPos at = player.chunkPosition();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!player.level().hasChunk(at.x + dx, at.z + dz)) return false;
            }
        }
        return true;
    }
}
