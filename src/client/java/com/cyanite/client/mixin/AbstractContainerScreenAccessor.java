package com.cyanite.client.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Where a container screen's slots sit on screen, for Auto Totem's inventory-style swap.
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {
    @Accessor("leftPos")
    int cyanite$leftPos();

    @Accessor("topPos")
    int cyanite$topPos();
}
