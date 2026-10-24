package com.cyanite.client.system;

import com.cyanite.client.CyaniteClientMod;
import com.cyanite.client.mixin.ChatComponentAccessor;
import com.cyanite.client.mixin.OptionsSubScreenAccessor;
import com.cyanite.client.modules.misc.StealthMode;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Stealth Mode's switch, checked everywhere the client would show itself: the HUD, anything drawn
 * in the world, chat lines, toasts and sounds, its own screens, the menu key. Menu key + I flips it.
 */
public final class Stealth {
    /** How many of the client's own chat lines it remembers, to take back out of chat. */
    private static final int KEEP = 300;
    private static final Set<Component> OURS = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Deque<Component> ORDER = new ArrayDeque<>();
    private static boolean active;
    /** The keys just flipped it, so the menu key's own press doesn't open the menu as well. */
    private static boolean swallowMenu;

    private Stealth() {
    }

    /** Whether everything's hidden right now. */
    public static boolean active() {
        return active;
    }

    /** Stealth Mode's own switch. */
    public static void set(boolean on) {
        active = on;
    }

    /** Menu key + I, from anywhere: in game, in a menu, on the title screen. */
    public static boolean isToggleKeys(int key) {
        if (CyaniteClientMod.clickGuiKey() == null) return false;
        InputConstants.Key menu = KeyBindingHelper.getBoundKeyOf(CyaniteClientMod.clickGuiKey());
        if (menu.getType() != InputConstants.Type.KEYSYM || menu.getValue() == InputConstants.UNKNOWN.getValue()) return false;
        var window = Minecraft.getInstance().getWindow();
        return key == GLFW.GLFW_KEY_I && InputConstants.isKeyDown(window, menu.getValue())
            || key == menu.getValue() && InputConstants.isKeyDown(window, GLFW.GLFW_KEY_I);
    }

    public static void toggle() {
        swallowMenu = true;
        StealthMode mode = Modules.get(StealthMode.class);
        if (mode != null) mode.toggle();
    }

    /** True once after the keys flipped it: the menu key's press that went with them shouldn't open the menu. */
    public static boolean takeSwallowedMenu() {
        boolean was = swallowMenu;
        swallowMenu = false;
        return was;
    }

    /** One of the client's own screens: the menu, waypoints, chest search and the rest. */
    public static boolean isClientScreen(Screen screen) {
        return screen != null && screen.getClass().getName().startsWith("com.cyanite.");
    }

    /** The game's key list as Controls shows it: without the client's menu key while hidden. */
    public static KeyMapping[] visibleKeys(KeyMapping[] all) {
        KeyMapping menu = CyaniteClientMod.clickGuiKey();
        if (!active || menu == null) return all;
        return java.util.Arrays.stream(all).filter(k -> k != menu).toArray(KeyMapping[]::new);
    }

    /** Controls open right now: swapped for a fresh one, so the menu key goes from it (or comes back) straight away. */
    public static void refreshControls() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof KeyBindsScreen screen) mc.setScreen(new KeyBindsScreen(((OptionsSubScreenAccessor) screen).cyanite$lastScreen(), mc.options));
    }

    /** A disconnect screen's text: what the module says, or just "Disconnected" while hidden. */
    public static Component disconnectReason(String text) {
        return Component.literal(active ? "Disconnected" : text);
    }

    /** Notes a chat line the client wrote, so it can be taken back out when going hidden. */
    static void wrote(Component line) {
        OURS.add(line);
        ORDER.addLast(line);
        while (ORDER.size() > KEEP) OURS.remove(ORDER.removeFirst());
    }

    /** Takes the client's own lines out of chat and its toasts off the screen. */
    public static void scrub() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui != null && !OURS.isEmpty()) {
            var chat = mc.gui.getChat();
            if (((ChatComponentAccessor) chat).cyanite$allMessages().removeIf(m -> OURS.contains(m.content()))) chat.rescaleChat();
        }
        OURS.clear();
        ORDER.clear();
        if (mc.getToastManager() != null) mc.getToastManager().clear();
    }
}
