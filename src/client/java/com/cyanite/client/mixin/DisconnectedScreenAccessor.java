package com.cyanite.client.mixin;

import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.DisconnectionDetails;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Rejoin reads why the server let you go, to tell a restart from a ban; the screen's rejoin row goes back from where Back goes. */
@Mixin(DisconnectedScreen.class)
public interface DisconnectedScreenAccessor {
    @Accessor("details")
    DisconnectionDetails cyanite$details();

    @Accessor("parent")
    Screen cyanite$parent();
}
