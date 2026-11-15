package com.cyanite.client.mixin;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The look your last movement packet carried: what the server judges your next click by. */
@Mixin(LocalPlayer.class)
public interface LocalPlayerAccessor {
    @Accessor("yRotLast")
    float cyanite$getSentYaw();

    @Accessor("xRotLast")
    float cyanite$getSentPitch();
}
