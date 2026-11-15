package com.cyanite.client.mixin;

import com.cyanite.client.modules.misc.NameProtect;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.StringSplitter;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSink;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;

/**
 * Name Protect and the splitter, which works out where text gets cut by width. Where it keeps the
 * text and cuts it up itself (wrapping chat, signs, tooltips) the name is swapped in first, so it
 * measures and cuts the same text it hands on. Where it hands positions back to whoever owns the
 * string (text boxes, books) the string is measured as it is, so the positions fit it.
 */
@Mixin(StringSplitter.class)
public class StringSplitterMixin {
    @WrapOperation(method = {"headByWidth", "splitLines(Lnet/minecraft/network/chat/FormattedText;ILnet/minecraft/network/chat/Style;Ljava/util/function/BiConsumer;)V"},
        at = @At(value = "INVOKE", target = "Lnet/minecraft/network/chat/FormattedText;visit(Lnet/minecraft/network/chat/FormattedText$StyledContentConsumer;Lnet/minecraft/network/chat/Style;)Ljava/util/Optional;"))
    private <T> Optional<T> cyanite$protectPieces(FormattedText text, FormattedText.StyledContentConsumer<T> consumer, Style style, Operation<Optional<T>> original) {
        FormattedText.StyledContentConsumer<T> protecting = (pieceStyle, piece) -> consumer.accept(pieceStyle, NameProtect.protect(piece));
        return original.call(text, protecting, style);
    }

    @WrapOperation(method = "plainIndexAtWidth", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/util/StringDecomposer;iterate(Ljava/lang/String;Lnet/minecraft/network/chat/Style;Lnet/minecraft/util/FormattedCharSink;)Z"))
    private boolean cyanite$indexAtWidth(String text, Style style, FormattedCharSink sink, Operation<Boolean> original) {
        return original.call(text, style, NameProtect.indexed(sink));
    }

    @WrapOperation(method = "plainTailByWidth", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/util/StringDecomposer;iterateBackwards(Ljava/lang/String;Lnet/minecraft/network/chat/Style;Lnet/minecraft/util/FormattedCharSink;)Z"))
    private boolean cyanite$tailByWidth(String text, Style style, FormattedCharSink sink, Operation<Boolean> original) {
        return original.call(text, style, NameProtect.indexed(sink));
    }

    @WrapOperation(method = "findLineBreak", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/util/StringDecomposer;iterateFormatted(Ljava/lang/String;Lnet/minecraft/network/chat/Style;Lnet/minecraft/util/FormattedCharSink;)Z"))
    private boolean cyanite$lineBreak(String text, Style style, FormattedCharSink sink, Operation<Boolean> original) {
        return original.call(text, style, NameProtect.indexed(sink));
    }

    @WrapOperation(method = "splitLines(Ljava/lang/String;ILnet/minecraft/network/chat/Style;ZLnet/minecraft/client/StringSplitter$LinePosConsumer;)V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/util/StringDecomposer;iterateFormatted(Ljava/lang/String;ILnet/minecraft/network/chat/Style;Lnet/minecraft/network/chat/Style;Lnet/minecraft/util/FormattedCharSink;)Z"))
    private boolean cyanite$splitPlain(String text, int start, Style style, Style reset, FormattedCharSink sink, Operation<Boolean> original) {
        return original.call(text, start, style, reset, NameProtect.indexed(sink));
    }
}
