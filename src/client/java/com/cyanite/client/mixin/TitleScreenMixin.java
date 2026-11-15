package com.cyanite.client.mixin;

import com.cyanite.client.gui.GuiSettings;
import com.cyanite.client.gui.Home;
import com.cyanite.client.gui.HomeTitle;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The title screen in Cyanite's look (the "Cyanite title screen" setting). The screen keeps its own buttons
 * and whatever other mods add; only how it's drawn changes, and Stealth or the setting hands it back untouched.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
    @Shadow
    private boolean fading;

    @Unique
    private HomeTitle cyanite$home;

    protected TitleScreenMixin(Component title) {
        super(title);
    }

    // The screen made its buttons again (opened, resized): the stand-ins for the old ones go.
    @Inject(method = "init()V", at = @At("TAIL"))
    private void cyanite$forget(CallbackInfo ci) {
        if (cyanite$home != null) cyanite$home.forget();
    }

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("HEAD"), cancellable = true)
    private void cyanite$render(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (!Home.themed()) {
            if (cyanite$home != null) cyanite$home.restore();
            return;
        }
        if (cyanite$home == null) cyanite$home = new HomeTitle(this, button -> this.addRenderableWidget(button), this::removeWidget);
        // The game's own fade-in is for its own buttons; ours arrive their own way.
        fading = false;
        cyanite$home.arrange();
        if (Home.panorama()) {
            this.renderPanorama(g, partialTick);
            // One blur a frame is all the game allows, and a screen drawn under another has had its turn.
            if (GuiSettings.BLUR.get() && this.minecraft.screen == this) this.renderBlurredBackground(g);
        }
        // Cyanite's backdrop (or a tint over the panorama), crystal and wordmark on it.
        cyanite$home.backdrop(g);
        super.render(g, mouseX, mouseY, partialTick);
        cyanite$home.footer(g);
        Home.titleDrawn++;
        ci.cancel();
    }
}
