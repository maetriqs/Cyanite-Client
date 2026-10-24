package com.cyanite.client.mixin;

import com.cyanite.client.system.Stealth;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Stealth Mode: another key bound to the same button as the client's menu key isn't flagged red,
 * and its tooltip doesn't name the menu key as the one it clashes with.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.options.controls.KeyBindsList$KeyEntry")
public class KeyBindsEntryMixin {
    @ModifyExpressionValue(method = "refreshEntry", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Options;keyMappings:[Lnet/minecraft/client/KeyMapping;"))
    private KeyMapping[] cyanite$hideMenuKeyClash(KeyMapping[] all) {
        return Stealth.visibleKeys(all);
    }
}
