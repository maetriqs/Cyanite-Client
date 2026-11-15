package com.cyanite.client.mixin.visuals;

import com.cyanite.client.visuals.ArmorDurability;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Armor Durability: the durability bar's colour.
@Mixin(ItemStack.class)
public class ItemStackVisualsMixin {
    @Inject(method = "getBarColor", at = @At("RETURN"), cancellable = true)
    private void cyanite$barColor(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(ArmorDurability.barColor((ItemStack) (Object) this, cir.getReturnValueI()));
    }
}
