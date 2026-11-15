package com.cyanite.client.mixin;

import com.cyanite.client.gui.Home;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The loading screen in Cyanite's look (the "Cyanite title screen" setting): the crystal where the title screen
 * has it and a slim bar, on a dark backdrop. Only the drawing is replaced; when the load counts as finished and
 * what happens then are still the game's.
 */
@Mixin(LoadingOverlay.class)
public abstract class LoadingOverlayMixin {
    // After the load: the bar goes, then the screen underneath shows through.
    @Unique private static final float BAR_OUT = 0.25f;
    @Unique private static final float GONE = 0.70f;
    @Unique private static final int BACKDROP = 0xFF05070B;

    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private ReloadInstance reload;
    @Shadow @Final private boolean fadeIn;
    @Shadow private float currentProgress;
    @Shadow private long fadeOutStart;
    @Shadow private long fadeInStart;

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("HEAD"), cancellable = true)
    private void cyanite$render(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (!Home.themed()) return;
        long now = Util.getMillis();
        if (fadeIn && fadeInStart == -1L) fadeInStart = now;
        float out = fadeOutStart > -1L ? (now - fadeOutStart) / 1000f : -1f;
        float in = fadeInStart > -1L ? (now - fadeInStart) / 500f : -1f;

        // The same three cases as the game's: fading out over the screen, fading in over it, or nothing under it yet.
        Screen screen = minecraft.screen;
        boolean beneath = false;
        float cover;
        if (out >= BAR_OUT) {
            beneath = cyanite$beneath(g, screen, 0, 0, partialTick);
            cover = 1f - Mth.clamp((out - BAR_OUT) / (GONE - BAR_OUT), 0f, 1f);
        } else if (fadeIn) {
            if (in < 1f) beneath = cyanite$beneath(g, screen, mouseX, mouseY, partialTick);
            else cyanite$beneath(g, null, 0, 0, partialTick);
            cover = Mth.clamp(in, 0.15f, 1f);
        } else {
            RenderSystem.getDevice().createCommandEncoder().clearColorTexture(minecraft.getMainRenderTarget().getColorTexture(), BACKDROP);
            cover = 1f;
        }

        // A start (no fade in) is paced by how long the last ones took, and timed for the next.
        float actual = reload.getActualProgress();
        int secondsLeft = 0;
        if (!fadeIn) {
            if (fadeOutStart > -1L) Home.startupDone(fadeOutStart);
            else {
                actual = Home.startupProgress(actual);
                secondsLeft = Home.startupSecondsLeft();
            }
        }
        currentProgress = Mth.clamp(currentProgress * 0.95F + actual * 0.050000012F, 0f, 1f);
        // Over the title screen the crystal never fades: it's the title screen's own, in the same place.
        float mark = beneath && screen instanceof TitleScreen ? 1f : cover;
        float bar = out < 0f ? 1f : 1f - Mth.clamp(out / BAR_OUT, 0f, 1f);
        // The game's font is only there once something has loaded: not on the first screen of a start.
        Home.loading(g, cover, mark, fadeIn || out >= 0f, currentProgress, bar, secondsLeft);

        if (out >= GONE) minecraft.setOverlay(null);
        ci.cancel();
    }

    /** Draws the screen under this one, as the game does; whether there was one to draw. */
    @Unique
    private boolean cyanite$beneath(GuiGraphics g, Screen screen, int mouseX, int mouseY, float partialTick) {
        if (screen == null) {
            minecraft.gui.renderDeferredSubtitles();
            g.nextStratum();
            return false;
        }
        Home.lockupAbove = true;
        try {
            screen.renderWithTooltipAndSubtitles(g, mouseX, mouseY, partialTick);
        } finally {
            Home.lockupAbove = false;
        }
        g.nextStratum();
        return true;
    }
}
