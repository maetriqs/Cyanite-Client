package com.cyanite.client.mixin;

import com.cyanite.client.modules.world.Timer;
import com.cyanite.client.system.Modules;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(DeltaTracker.Timer.class)
public class TimerMixin {
    // Ticks are counted in milliseconds per tick; shrinking that runs the game faster.
    @ModifyExpressionValue(
        method = "advanceGameTime",
        at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/floats/FloatUnaryOperator;apply(F)F")
    )
    private float cyanite$timer(float msPerTick) {
        Timer timer = Modules.get(Timer.class);
        return timer == null ? msPerTick : msPerTick / timer.multiplier();
    }
}
