package com.cyanite.client.mixin;

import com.cyanite.client.modules.misc.NameProtect;
import net.minecraft.util.StringDecomposer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Name Protect: all text the game draws or measures passes through here first. */
@Mixin(StringDecomposer.class)
public class StringDecomposerMixin {
    @ModifyVariable(method = "iterateFormatted(Ljava/lang/String;ILnet/minecraft/network/chat/Style;Lnet/minecraft/network/chat/Style;Lnet/minecraft/util/FormattedCharSink;)Z",
        at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static String cyanite$protectFormatted(String text) {
        return NameProtect.protect(text);
    }

    @ModifyVariable(method = "iterate", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static String cyanite$protect(String text) {
        return NameProtect.protect(text);
    }

    @ModifyVariable(method = "iterateBackwards", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static String cyanite$protectBackwards(String text) {
        return NameProtect.protect(text);
    }
}
