package com.cyanite.client.mixin;

import com.cyanite.client.gui.HomeServers;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A LAN world's row in the server list, on a card like the servers'. */
@Mixin(ServerSelectionList.NetworkServerEntry.class)
public abstract class NetworkServerEntryMixin {
    @Shadow
    @Final
    private JoinMultiplayerScreen screen;

    @Inject(method = "renderContent", at = @At("HEAD"))
    private void cyanite$card(GuiGraphics g, int mouseX, int mouseY, boolean hovered, float partialTick, CallbackInfo ci) {
        ServerSelectionList list = ((JoinMultiplayerScreenAccessor) screen).cyanite$list();
        if (!HomeServers.styles(list)) return;
        ServerSelectionList.Entry row = (ServerSelectionList.Entry) (Object) this;
        HomeServers.card(g, row.getX(), row.getY(), row.getWidth(), row.getHeight(), hovered, list.getSelected() == row, list.isFocused());
    }
}
