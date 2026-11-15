package com.cyanite.client.mixin;

import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The server list's rows ask it which one is picked, to light their card. */
@Mixin(JoinMultiplayerScreen.class)
public interface JoinMultiplayerScreenAccessor {
    @Accessor("serverSelectionList")
    ServerSelectionList cyanite$list();
}
