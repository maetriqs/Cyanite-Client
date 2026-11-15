package com.cyanite.client.mixin;

import com.cyanite.client.modules.render.Freecam;
import com.cyanite.client.render.CameraShot;
import com.cyanite.client.render.SectionBuilds;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Freecam on vanilla's chunk renderer: keep the grid of drawable chunk sections around the player,
 * where chunks are loaded. Sodium replaces these methods, so CyaniteMixinPlugin skips this mixin
 * when Sodium is installed and SodiumCullingMixin does the job instead. Also tells SectionBuilds
 * whether a section is drawn as its blocks are now.
 */
@Mixin(LevelRenderer.class)
public class VanillaTerrainMixin implements SectionBuilds.Vanilla {
    @Shadow
    private double prevCamRotX;
    @Shadow
    private ViewArea viewArea;

    @ModifyArg(method = {"cullTerrain", "allChanged"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ViewArea;repositionCamera(Lnet/minecraft/core/SectionPos;)V"))
    private SectionPos cyanite$freecamGrid(SectionPos camera) {
        Vec3 centre = Freecam.renderCentre();
        return centre == null ? camera : SectionPos.of(centre);
    }

    // A CameraShot (and your view again after it): the sections to draw are only picked again once the
    // camera has turned, so make sure it counts as turned.
    @Inject(method = "cullTerrain", at = @At("HEAD"))
    private void cyanite$shotSections(net.minecraft.client.Camera camera, net.minecraft.client.renderer.culling.Frustum frustum,
                                      boolean spectator, CallbackInfo ci) {
        if (CameraShot.takeRefreshSections()) prevCamRotX = Double.NaN;
    }

    @Override
    public boolean cyanite$sectionCurrent(BlockPos pos) {
        if (viewArea == null) return false;
        SectionRenderDispatcher.RenderSection section = ((ViewAreaAccessor) viewArea).cyanite$sectionAt(pos);
        return section != null && !section.isDirty() && !((SectionBuilds.Building) section).cyanite$building();
    }
}
