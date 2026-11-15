package com.cyanite.client.mixin;

import net.minecraft.client.gui.screens.dialog.DialogScreen;
import net.minecraft.server.dialog.Dialog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** ServerStats reads a server's dialog (DonutSMP's /stats) as it came, not as it's laid out. */
@Mixin(DialogScreen.class)
public interface DialogScreenAccessor {
    @Accessor("dialog")
    Dialog cyanite$dialog();
}
