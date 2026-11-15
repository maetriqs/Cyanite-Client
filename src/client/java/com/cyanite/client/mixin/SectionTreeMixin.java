package com.cyanite.client.mixin;

import com.cyanite.client.render.CameraShot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * A CameraShot's camera can be far below you (a base under the deepslate, you up top). The sections the
 * game may draw are kept in a tree centred on you, a cube as wide as your render distance: under 8 it
 * reaches only 16 sections up and down, so nothing further shows from any camera. While a shot is on
 * its way, the tree covers the world's whole height. Vanilla only; Sodium searches from the camera.
 */
@Mixin(targets = "net.minecraft.client.renderer.SectionOcclusionGraph$GraphStorage")
public class SectionTreeMixin {
    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/Octree;<init>(Lnet/minecraft/core/SectionPos;III)V"), index = 1)
    private int cyanite$shotHeight(int viewDistance) {
        if (!CameraShot.widened()) return viewDistance;
        ClientLevel level = Minecraft.getInstance().level;
        // The cube is 2 * distance + 1 sections across, rounded up to a power of two.
        return level == null ? viewDistance : Math.max(viewDistance, level.getSectionsCount() / 2);
    }
}
