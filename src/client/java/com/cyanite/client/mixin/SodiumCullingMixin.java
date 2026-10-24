package com.cyanite.client.mixin;

import com.cyanite.client.modules.render.Freecam;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sodium replaces vanilla's chunk renderer and measures how far to draw from the camera. With
 * Freecam on, it reaches further by how far the camera has flown from the player, so chunks loaded
 * around the player still get drawn. Does nothing when Sodium isn't installed.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager", remap = false)
public class SodiumCullingMixin {
    @Inject(method = "getSearchDistance", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
    private void cyanite$freecamDistance(CallbackInfoReturnable<Float> cir) {
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        int extra = Freecam.extraChunks(camera.position());
        if (extra > 0) cir.setReturnValue(cir.getReturnValue() + extra * 16f);
    }
}
