package com.cyanite.client.mixin;

import com.cyanite.client.gui.HomeServers;
import net.minecraft.client.gui.components.AbstractSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The server list in Cyanite's look: no dark panel, edges or selection box behind its rows; each row draws its own card. */
@Mixin(AbstractSelectionList.class)
public abstract class AbstractSelectionListMixin {
    @Inject(method = "renderListBackground", at = @At("HEAD"), cancellable = true)
    private void cyanite$panel(CallbackInfo ci) {
        if (HomeServers.styles(this)) ci.cancel();
    }

    @Inject(method = "renderListSeparators", at = @At("HEAD"), cancellable = true)
    private void cyanite$edges(CallbackInfo ci) {
        if (HomeServers.styles(this)) ci.cancel();
    }

    @Inject(method = "renderSelection", at = @At("HEAD"), cancellable = true)
    private void cyanite$selection(CallbackInfo ci) {
        if (HomeServers.styles(this)) ci.cancel();
    }
}
