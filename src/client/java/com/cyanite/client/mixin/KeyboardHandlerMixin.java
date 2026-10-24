package com.cyanite.client.mixin;

import com.cyanite.client.system.Macros;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.Stealth;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public class KeyboardHandlerMixin {
    /** The "i" that typing sends separately after menu key + I, so it doesn't land in chat or a text box. */
    @org.spongepowered.asm.mixin.Unique
    private boolean cyanite$eatI;

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void cyanite$stealthChar(long window, CharacterEvent event, CallbackInfo ci) {
        if (!cyanite$eatI) return;
        cyanite$eatI = false;
        if (Character.toLowerCase(event.codepoint()) == 'i') ci.cancel();
    }

    // Module binds and macros fire on the press itself rather than being polled each tick, so quick taps aren't lost.
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void cyanite$moduleBinds(long window, int action, KeyEvent event, CallbackInfo ci) {
        // Menu key + I flips Stealth Mode from anywhere, and the I goes no further (not typed into chat).
        if (action == GLFW.GLFW_PRESS && Stealth.isToggleKeys(event.key())) {
            Stealth.toggle();
            cyanite$eatI = true;
            ci.cancel();
            return;
        }
        if (action != GLFW.GLFW_PRESS || Minecraft.getInstance().screen != null) return;
        Modules.onKey(event.key());
        Macros.onKey(event.key());
    }
}
