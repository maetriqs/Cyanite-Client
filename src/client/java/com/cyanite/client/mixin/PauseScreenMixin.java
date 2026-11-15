package com.cyanite.client.mixin;

import com.cyanite.client.gui.Home;
import com.cyanite.client.gui.HomePause;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The pause menu in Cyanite's look, with the title screen (the "Cyanite title screen" setting). The screen keeps
 * its own buttons and whatever other mods add; Stealth or the setting hands it back untouched, and F3 + Esc's
 * menu-less pause is left alone.
 */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {
    @Unique
    private HomePause cyanite$home;

    protected PauseScreenMixin(Component title) {
        super(title);
    }

    @Unique
    private boolean cyanite$themed() {
        return ((PauseScreen) (Object) this).showsPauseMenu() && Home.themed();
    }

    // The screen made its buttons again (opened, resized): the stand-ins for the old ones go.
    @Inject(method = "init()V", at = @At("TAIL"))
    private void cyanite$forget(CallbackInfo ci) {
        if (cyanite$home != null) cyanite$home.forget();
    }

    // The background is drawn before the buttons, so the stand-ins are placed here, then the tint and title go under them.
    @Inject(method = "renderBackground(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("TAIL"))
    private void cyanite$backdrop(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (!cyanite$themed()) {
            if (cyanite$home != null) cyanite$home.restore();
            return;
        }
        if (cyanite$home == null) cyanite$home = new HomePause(this, button -> this.addRenderableWidget(button), this::removeWidget);
        cyanite$home.arrange();
        cyanite$home.backdrop(g);
    }
}
