package com.cyanite.client.mixin.visuals;

import com.cyanite.client.visuals.TimeChanger;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Level.class)
public class LevelVisualsMixin {
    // Time Changer: the time of day your own world reports, which its sky, sun, moon and daylight all follow.
    // The client's clock ticks on its own field, so the real time keeps counting underneath.
    @ModifyReturnValue(method = "getDayTime", at = @At("RETURN"))
    private long cyanite$timeChanger(long time) {
        return (Object) this instanceof ClientLevel ? TimeChanger.dayTime(time) : time;
    }
}
