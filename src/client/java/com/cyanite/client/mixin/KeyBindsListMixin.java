package com.cyanite.client.mixin;

import com.cyanite.client.system.Stealth;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.options.controls.KeyBindsList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Stealth Mode: the client's menu key isn't listed under Controls. */
@Mixin(KeyBindsList.class)
public class KeyBindsListMixin {
    @ModifyExpressionValue(method = "<init>", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Options;keyMappings:[Lnet/minecraft/client/KeyMapping;"))
    private KeyMapping[] cyanite$hideMenuKey(KeyMapping[] all) {
        return Stealth.visibleKeys(all);
    }
}
