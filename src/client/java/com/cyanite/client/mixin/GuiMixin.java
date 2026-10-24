package com.cyanite.client.mixin;

import com.cyanite.client.modules.donut.BountyBalance;
import com.cyanite.client.modules.donut.FakePay;
import com.cyanite.client.modules.donut.ScoreboardSpoof;
import com.cyanite.client.system.Modules;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.world.scores.Objective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Scoreboard Spoof. The sidebar measures every line, then draws the title, each line and each
 * score in that order, so the three call sites below cover the whole thing.
 */
@Mixin(Gui.class)
public class GuiMixin {
    @Inject(method = "displayScoreboardSidebar", at = @At("HEAD"))
    private void cyanite$sidebarStart(GuiGraphics graphics, Objective objective, CallbackInfo ci) {
        ScoreboardSpoof spoof = Modules.get(ScoreboardSpoof.class);
        if (spoof != null) spoof.beginSidebar();
    }

    @ModifyExpressionValue(
        method = "displayScoreboardSidebar",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/scores/Objective;getDisplayName()Lnet/minecraft/network/chat/Component;")
    )
    private Component cyanite$sidebarTitle(Component original) {
        ScoreboardSpoof spoof = Modules.get(ScoreboardSpoof.class);
        return spoof == null ? original : spoof.spoofTitle(original);
    }

    // The measuring pass, so the dark box behind the sidebar fits the replacement text.
    @WrapOperation(
        method = "displayScoreboardSidebar",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Font;width(Lnet/minecraft/network/chat/FormattedText;)I", ordinal = 1)
    )
    private int cyanite$sidebarLineWidth(Font font, FormattedText text, Operation<Integer> original) {
        if (text instanceof Component component) {
            FakePay fakePay = Modules.get(FakePay.class);
            ScoreboardSpoof spoof = Modules.get(ScoreboardSpoof.class);
            Component result = fakePay != null ? fakePay.spoofLine(component) : component;
            if (spoof != null) result = spoof.spoofMeasuredLine(result);
            return original.call(font, result);
        }
        return original.call(font, text);
    }

    @WrapOperation(
        method = "displayScoreboardSidebar",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V", ordinal = 1)
    )
    private void cyanite$sidebarLine(GuiGraphics graphics, Font font, Component text, int x, int y, int color,
                                     boolean shadow, Operation<Void> original) {
        FakePay fakePay = Modules.get(FakePay.class);
        ScoreboardSpoof spoof = Modules.get(ScoreboardSpoof.class);
        Component result = fakePay != null ? fakePay.spoofLine(text) : text;
        if (spoof != null) result = spoof.spoofDrawnLine(result);
        original.call(graphics, font, result, x, y, color, shadow);
    }

    // Donut's /bal, /bounty and /pay lines show in the action bar too. Everything bound for it
    // passes through here, whether it came as a chat packet or the action-bar packet. Fake Pay
    // fakes the amounts; Bounty & Balance keeps the replies to its own lookups off.
    @WrapMethod(method = "setOverlayMessage")
    private void cyanite$actionBar(Component text, boolean animateColor, Operation<Void> original) {
        Component shown = FakePay.actionBar(text);
        if (shown != null) shown = BountyBalance.actionBar(shown);
        if (shown != null) original.call(shown, animateColor);
    }

    @WrapOperation(
        method = "displayScoreboardSidebar",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V", ordinal = 2)
    )
    private void cyanite$sidebarScore(GuiGraphics graphics, Font font, Component text, int x, int y, int color,
                                      boolean shadow, Operation<Void> original) {
        ScoreboardSpoof spoof = Modules.get(ScoreboardSpoof.class);
        if (spoof != null && spoof.hidesNumbers()) return;
        original.call(graphics, font, text, x, y, color, shadow);
    }
}
