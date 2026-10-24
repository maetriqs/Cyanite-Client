package com.cyanite.client.system;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The entities in the world, gathered once a frame (and once a tick) for every module to share,
 * instead of each module walking the world's entities itself. Game thread only.
 */
public final class WorldScan {
    private static int stamp;
    private static int builtAt = -1;
    private static final List<Entity> entities = new ArrayList<>();
    private static final List<Entity> view = Collections.unmodifiableList(entities);

    private WorldScan() {
    }

    /** A new frame or tick: the next ask gathers them again. */
    public static void next() {
        stamp++;
    }

    /** Every entity in the world, as of this frame. */
    public static List<Entity> entities() {
        if (builtAt != stamp) {
            builtAt = stamp;
            entities.clear();
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) for (Entity e : mc.level.entitiesForRendering()) entities.add(e);
        }
        return view;
    }
}
