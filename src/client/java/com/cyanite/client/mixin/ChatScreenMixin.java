package com.cyanite.client.mixin;

import com.cyanite.client.system.Macros;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatScreen.class)
public class ChatScreenMixin {
    // Macro recording: chat and commands you type yourself, not ones mods send.
    @Inject(method = "handleChatInput", at = @At("HEAD"))
    private void cyanite$recordChat(String text, boolean addToHistory, CallbackInfo ci) {
        Macros.recorder().typed(text);
    }
}
