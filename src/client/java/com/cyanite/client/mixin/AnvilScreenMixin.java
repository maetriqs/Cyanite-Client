package com.cyanite.client.mixin;

import com.cyanite.client.modules.misc.AntiModCheck;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

// Anti Mod Check: the anvil puts the item's name in the rename box, and that goes straight back to the server.
@Mixin(AnvilScreen.class)
public class AnvilScreenMixin {
    @WrapMethod(method = "slotChanged")
    private void cyanite$guardName(AbstractContainerMenu menu, int slot, ItemStack stack, Operation<Void> original) {
        AntiModCheck.guard("anvil", () -> original.call(menu, slot, stack));
    }
}
