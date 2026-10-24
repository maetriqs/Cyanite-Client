package com.cyanite.client.system;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

public final class Keys {
    private Keys() {
    }

    /** Whether the key or mouse button bound to this mapping is physically held right now. */
    public static boolean isHeld(KeyMapping mapping) {
        InputConstants.Key key = KeyBindingHelper.getBoundKeyOf(mapping);
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
}
