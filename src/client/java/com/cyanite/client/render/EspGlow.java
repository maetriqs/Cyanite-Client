package com.cyanite.client.render;

import net.minecraft.world.entity.Entity;

import java.util.HashMap;
import java.util.Map;

/**
 * ESP's Silhouette shape: entities outlined the way the Glowing effect outlines them, in their
 * ESP colour and through walls. Modules mark entities while they queue a frame's drawing and the
 * marks are used from the next frame on; a frame nobody marks anything in (module off, Stealth)
 * clears them. Read from the render thread and from culling mods' own threads.
 */
public final class EspGlow {
    private static volatile Map<Integer, Integer> shown = Map.of();
    private static Map<Integer, Integer> next;

    private EspGlow() {
    }

    static void begin() {
        next = null;
    }

    static void end() {
        shown = next == null ? Map.of() : next;
        next = null;
    }

    /** Outline {@code entity} in {@code rgb} from the next frame on. */
    public static void mark(Entity entity, int rgb) {
        if (next == null) next = new HashMap<>();
        next.put(entity.getId(), 0xFF000000 | rgb);
    }

    /** The outline colour for {@code entity} (opaque ARGB), or 0 for none. */
    public static int color(Entity entity) {
        Map<Integer, Integer> now = shown;
        if (now.isEmpty()) return 0;
        Integer argb = now.get(entity.getId());
        return argb == null ? 0 : argb;
    }
}
