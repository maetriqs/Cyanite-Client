package com.cyanite.client.mixin;

import com.cyanite.client.modules.render.Freecam;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Freecam on vanilla's chunk renderer: keep the grid of drawable chunk sections around the player,
 * where chunks are loaded. Sodium replaces these methods, so CyaniteMixinPlugin skips this mixin
 * when Sodium is installed and SodiumCullingMixin does the job instead.
 */
@Mixin(LevelRenderer.class)
public class VanillaTerrainMixin {
    @ModifyArg(method = {"cullTerrain", "allChanged"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ViewArea;repositionCamera(Lnet/minecraft/core/SectionPos;)V"))
    private SectionPos cyanite$freecamGrid(SectionPos camera) {
        Vec3 centre = Freecam.renderCentre();
        return centre == null ? camera : SectionPos.of(centre);
    }
}
