package com.cyanite.client.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Minecraft.class)
public interface MinecraftAccessor {
    @Accessor("rightClickDelay")
    int cyanite$getRightClickDelay();

    @Accessor("rightClickDelay")
    void cyanite$setRightClickDelay(int delay);

    /** A left click, exactly as the attack key does it. */
    @Invoker("startAttack")
    boolean cyanite$startAttack();
}
