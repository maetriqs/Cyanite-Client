package com.cyanite.client.mixin;

import com.cyanite.client.modules.misc.AntiModCheck;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Anti Mod Check: the sign editor turns the sign's text into the lines it sends back when you're done.
@Mixin(AbstractSignEditScreen.class)
public class AbstractSignEditScreenMixin {
    private static final String INIT = "<init>(Lnet/minecraft/world/level/block/entity/SignBlockEntity;ZZLnet/minecraft/network/chat/Component;)V";

    @Inject(method = INIT, at = @At(value = "INVOKE", target = "Ljava/util/stream/IntStream;range(II)Ljava/util/stream/IntStream;"))
    private void cyanite$guardLines(CallbackInfo ci) {
        AntiModCheck.enter();
    }

    @Inject(method = INIT, at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER,
        target = "Lnet/minecraft/client/gui/screens/inventory/AbstractSignEditScreen;messages:[Ljava/lang/String;"))
    private void cyanite$linesDone(CallbackInfo ci) {
        AntiModCheck.exit("sign");
    }
}
