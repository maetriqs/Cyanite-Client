package com.cyanite.client.system;

import com.cyanite.client.gui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

/** Gets your attention: a chat line always, plus whichever of toast, sound and taskbar flash the caller wants. */
public final class Notify {
    private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId(8000L);
    /** The least time between two dings: alerts closer together than this are one burst. */
    public static final long DING_GAP_MS = 2500;
    private static long lastDing = Long.MIN_VALUE / 2;

    private Notify() {
    }

    public static void send(String title, String body, boolean toast, boolean sound, boolean flash) {
        Minecraft mc = Minecraft.getInstance();
        Headless.notified(title, body);
        // Stealth Mode: nothing on screen, nothing to hear. Before the game's up (a module switched on as
        // the config loads) there's no chat or toasts to show it in yet.
        if (Stealth.active() || mc.gui == null) return;
        com.cyanite.client.modules.misc.Pets.onNotify(title, body, toast || sound);
        // Streamer Mode: no coordinates in chat or toasts while you're live.
        body = com.cyanite.client.modules.misc.StreamerMode.mask(body);
        Component line = Component.literal("Cyanite ").withColor(Theme.accent() & 0xFFFFFF)
            .append(Component.literal(title + ": ").withColor(0xFFFFFF))
            .append(Component.literal(body).withColor(0xB8C0CC));
        Stealth.wrote(line);
        mc.gui.getChat().addMessage(line);

        if (toast) SystemToast.addOrUpdate(mc.getToastManager(), TOAST, Component.literal(title), Component.literal(body));
        if (sound) ding(mc);
        // Blinks the game's taskbar button until you click back in, for when you've tabbed out.
        if (flash && !mc.isWindowActive()) GLFW.glfwRequestWindowAttention(mc.getWindow().handle());
    }

    /**
     * A soft two-note harp, low and short: easy to notice, easy to live with. At most one every
     * {@link #DING_GAP_MS}, so a burst of alerts gives one ding rather than a pile of them.
     */
    private static void ding(Minecraft mc) {
        long now = Util.getMillis();
        if (now - lastDing < DING_GAP_MS) return;
        lastDing = now;
        // D then the A above it (note block pitches 2^((n - 12) / 12) for n = 8 and 15).
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_HARP.value(), 0.794f, 0.25f));
        mc.getSoundManager().playDelayed(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_HARP.value(), 1.189f, 0.2f), 2);
    }
}
