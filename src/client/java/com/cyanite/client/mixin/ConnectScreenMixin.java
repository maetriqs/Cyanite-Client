package com.cyanite.client.mixin;

import com.cyanite.client.gui.HomeDisconnect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The server being joined, for the disconnected screen's row back onto it: a failed join never has a connection to ask. */
@Mixin(ConnectScreen.class)
public class ConnectScreenMixin {
    @Inject(method = "startConnecting", at = @At("HEAD"))
    private static void cyanite$joining(Screen parent, Minecraft minecraft, ServerAddress address, ServerData server, boolean quickPlay,
                                        @Nullable TransferState transfer, CallbackInfo ci) {
        HomeDisconnect.connecting(server);
    }
}
