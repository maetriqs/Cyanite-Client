package com.cyanite.client.mixin;

import com.cyanite.client.gui.HomeServers;
import com.cyanite.client.gui.ServerPings;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A server's row in the server list: its card under it, and a graph of its recent pings at its end (the MOTD makes room). */
@Mixin(ServerSelectionList.OnlineServerEntry.class)
public abstract class OnlineServerEntryMixin {
    @Shadow
    @Final
    private JoinMultiplayerScreen screen;

    @Shadow
    @Final
    private ServerData serverData;

    @Inject(method = "renderContent", at = @At("HEAD"))
    private void cyanite$card(GuiGraphics g, int mouseX, int mouseY, boolean hovered, float partialTick, CallbackInfo ci) {
        ServerSelectionList list = ((JoinMultiplayerScreenAccessor) screen).cyanite$list();
        if (!HomeServers.styles(list)) return;
        ServerSelectionList.Entry row = (ServerSelectionList.Entry) (Object) this;
        HomeServers.card(g, row.getX(), row.getY(), row.getWidth(), row.getHeight(), hovered, list.getSelected() == row, list.isFocused());
    }

    @ModifyArg(method = "renderContent", index = 1, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/Font;split(Lnet/minecraft/network/chat/FormattedText;I)Ljava/util/List;"))
    private int cyanite$motdRoom(int width) {
        return HomeServers.styles(((JoinMultiplayerScreenAccessor) screen).cyanite$list()) ? width - HomeServers.GRAPH_W - 6 : width;
    }

    @Inject(method = "renderContent", at = @At("TAIL"))
    private void cyanite$graph(GuiGraphics g, int mouseX, int mouseY, boolean hovered, float partialTick, CallbackInfo ci) {
        if (!HomeServers.styles(((JoinMultiplayerScreenAccessor) screen).cyanite$list())) return;
        ServerSelectionList.Entry row = (ServerSelectionList.Entry) (Object) this;
        HomeServers.graph(g, serverData, row.getContentRight() - 5, row.getContentY() + 13);
    }

    // The list's own ping came back, or didn't: either goes on the graph.
    @Inject(method = "refreshStatus", at = @At("TAIL"))
    private void cyanite$heard(CallbackInfo ci) {
        ServerPings.heard(serverData);
    }
}
