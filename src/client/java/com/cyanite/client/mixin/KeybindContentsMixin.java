package com.cyanite.client.mixin;

import com.cyanite.client.modules.misc.AntiModCheck;
import com.cyanite.client.system.ModCheckGuard;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.KeybindContents;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Anti Mod Check: a keybind in server text names the key a plain game would, and a mod's bind not at all.
@Mixin(KeybindContents.class)
public class KeybindContentsMixin {
    @Shadow
    @Final
    private String name;

    @Inject(method = "getNestedComponent", at = @At("HEAD"), cancellable = true)
    private void cyanite$plainGame(CallbackInfoReturnable<Component> cir) {
        if (ModCheckGuard.guarding()) cir.setReturnValue(AntiModCheck.keybind(name));
    }
}
