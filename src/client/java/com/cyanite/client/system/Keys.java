package com.cyanite.client.system;

import com.cyanite.client.CyaniteClientMod;
import com.cyanite.client.system.macro.Macro;
import com.cyanite.client.system.setting.KeybindSetting;
import com.cyanite.client.system.setting.Setting;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public final class Keys {
    private Keys() {
    }

    /** Whether the key or mouse button bound to this mapping is physically held right now. */
    public static boolean isHeld(KeyMapping mapping) {
        return isHeld(KeyBindingHelper.getBoundKeyOf(mapping));
    }

    /** Whether a key or mouse button is physically held right now. */
    public static boolean isHeld(InputConstants.Key key) {
        long window = Minecraft.getInstance().getWindow().handle();

        return switch (key.getType()) {
            case KEYSYM -> key.getValue() != InputConstants.UNKNOWN.getValue() && InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), key.getValue());
            case MOUSE -> GLFW.glfwGetMouseButton(window, key.getValue()) == GLFW.GLFW_PRESS;
            default -> false;
        };
    }

    /** Puts a mapping back to whatever the player's hand is actually doing. */
    public static void restore(KeyMapping mapping) {
        mapping.setDown(isHeld(mapping));
    }

    /**
     * Everything else in game on {@code setting}'s key: the menu, other modules' binds and keys,
     * macros, and the game's and other mods' controls. A press does all of them at once. Empty when it's alone.
     */
    public static List<String> clashes(KeybindSetting setting) {
        List<String> found = new ArrayList<>();
        int key = setting.get();
        if (key == KeybindSetting.NONE) return found;
        KeyMapping menu = CyaniteClientMod.clickGuiKey();
        if (menu != null && boundTo(menu, key)) found.add("the Cyanite menu");
        for (Module module : Modules.getAll()) {
            for (Setting<?> other : module.settings()) {
                if (other == setting || !(other instanceof KeybindSetting bind) || bind.get() != key) continue;
                found.add(bind == module.bind() ? module.name : possessive(module.name) + " " + bind.name);
            }
        }
        for (Macro macro : Macros.list()) {
            if (macro.key == key) found.add("the macro " + macro.name);
        }
        for (KeyMapping mapping : Minecraft.getInstance().options.keyMappings) {
            if (mapping != menu && boundTo(mapping, key)) found.add(Component.translatable(mapping.getName()).getString() + " in Controls");
        }
        return found;
    }

    private static boolean boundTo(KeyMapping mapping, int key) {
        InputConstants.Key bound = KeyBindingHelper.getBoundKeyOf(mapping);
        return bound.getType() == InputConstants.Type.KEYSYM && bound.getValue() == key;
    }

    private static String possessive(String name) {
        return name.endsWith("s") ? name + "'" : name + "'s";
    }
}
