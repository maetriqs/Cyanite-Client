package com.cyanite.client.mixin;

import com.cyanite.client.modules.misc.AntiModCheck;
import com.cyanite.client.system.ModCheckGuard;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.locale.Language;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// Anti Mod Check: while the sign editor or anvil turns server text into what it sends back, the language is a plain game's.
@Mixin(Language.class)
public abstract class LanguageMixin {
    // Asked on every text lookup, thousands of times a frame, from game start: only the guard flag is
    // touched until a sign or anvil is open, and nothing is made per call.
    @ModifyReturnValue(method = "getInstance", at = @At("RETURN"))
    private static Language cyanite$plainGame(Language language) {
        return ModCheckGuard.guarding() ? AntiModCheck.language(language) : language;
    }
}
