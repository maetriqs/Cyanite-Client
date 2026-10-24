package com.cyanite.client.system;

import net.minecraft.client.Minecraft;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Occlusion culling guesses what's hidden behind solid blocks from where the camera is. Xray,
 * Freecam and No Clip all need it off; this keeps it off while any of them does, and puts back
 * what it was once none do. Vanilla and Sodium both read the flag.
 */
public final class Culling {
    private static final Set<Object> HOLDERS = Collections.newSetFromMap(new IdentityHashMap<>());
    private static boolean saved = true;

    private Culling() {
    }

    public static void hold(Object holder) {
        Minecraft mc = Minecraft.getInstance();
        if (HOLDERS.isEmpty()) saved = mc.smartCull;
        HOLDERS.add(holder);
        mc.smartCull = false;
    }

    public static void release(Object holder) {
        if (HOLDERS.remove(holder) && HOLDERS.isEmpty()) Minecraft.getInstance().smartCull = saved;
    }
}
