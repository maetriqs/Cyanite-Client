package com.cyanite.client.mixin;

import com.cyanite.client.modules.misc.NameProtect;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.util.FormattedCharSink;
import net.minecraft.util.StringDecomposer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Name Protect: all text the game draws or measures passes through here first. Not for a sink that
 * hands back positions in the string it was given ({@link NameProtect.Indexed}): with the name
 * swapped those positions would be in another string, past the end of it when the name is longer.
 */
@Mixin(StringDecomposer.class)
public class StringDecomposerMixin {
    @ModifyVariable(method = "iterateFormatted(Ljava/lang/String;ILnet/minecraft/network/chat/Style;Lnet/minecraft/network/chat/Style;Lnet/minecraft/util/FormattedCharSink;)Z",
        at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static String cyanite$protectFormatted(String text, @Local(argsOnly = true) FormattedCharSink sink) {
        return sink instanceof NameProtect.Indexed ? text : NameProtect.protect(text);
    }

    @ModifyVariable(method = "iterate", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static String cyanite$protect(String text, @Local(argsOnly = true) FormattedCharSink sink) {
        return sink instanceof NameProtect.Indexed ? text : NameProtect.protect(text);
    }

    @ModifyVariable(method = "iterateBackwards", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static String cyanite$protectBackwards(String text, @Local(argsOnly = true) FormattedCharSink sink) {
        return sink instanceof NameProtect.Indexed ? text : NameProtect.protect(text);
    }
}
