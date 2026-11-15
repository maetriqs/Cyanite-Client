package com.cyanite.client.mixin.visuals;

import com.cyanite.client.visuals.Particles;
import com.cyanite.client.visuals.PotionImpact;
import com.cyanite.client.visuals.Visuals;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.renderer.LevelEventHandler;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;

// Splash Particles and Potion Impact: a splash or lingering potion breaking (events 2002 and 2007, its colour in data).
@Mixin(LevelEventHandler.class)
public class LevelEventHandlerMixin {
    @WrapMethod(method = "levelEvent")
    private void cyanite$splash(int type, BlockPos pos, int data, Operation<Void> original) {
        if (type != 2002 && type != 2007) {
            original.call(type, pos, data);
            return;
        }
        Particles.beginSplash();
        try {
            original.call(type, pos, data);
        } finally {
            Particles.end();
        }
        Visuals.run(PotionImpact.GROUP, () -> PotionImpact.onSplash(pos, data));
    }
}
