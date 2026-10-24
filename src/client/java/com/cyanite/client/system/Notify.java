package com.cyanite.client.system;

import com.cyanite.client.gui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

/** Gets your attention: a chat line always, plus whichever of toast, sound and taskbar flash the caller wants. */
public final class Notify {
    private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId(8000L);

    private Notify() {
    }

    public static void send(String title, String body, boolean toast, boolean sound, boolean flash) {
        Minecraft mc = Minecraft.getInstance();
        // Stealth Mode: nothing on screen, nothing to hear.
        if (Stealth.active()) return;
        com.cyanite.client.modules.misc.Pets.onNotify(title, body, toast || sound);
        // Streamer Mode: no coordinates in chat or toasts while you're live.
        body = com.cyanite.client.modules.misc.StreamerMode.mask(body);
        Component line = Component.literal("Cyanite ").withColor(Theme.accent() & 0xFFFFFF)
            .append(Component.literal(title + ": ").withColor(0xFFFFFF))
            .append(Component.literal(body).withColor(0xB8C0CC));
        Stealth.wrote(line);
        mc.gui.getChat().addMessage(line);

        if (toast) SystemToast.addOrUpdate(mc.getToastManager(), TOAST, Component.literal(title), Component.literal(body));
        if (sound) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1.2f));
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL, 0.9f));
        }
        // Blinks the game's taskbar button until you click back in, for when you've tabbed out.
        if (flash && !mc.isWindowActive()) GLFW.glfwRequestWindowAttention(mc.getWindow().handle());
    }
}
