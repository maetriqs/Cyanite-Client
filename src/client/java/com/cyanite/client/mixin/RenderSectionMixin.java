package com.cyanite.client.mixin;

import com.cyanite.client.render.SectionBuilds;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps track of whether one of vanilla's chunk sections is being rebuilt: from the build being asked
 * for to its new mesh being in place (a cancelled build is always followed by another). A CameraShot
 * waits for this on the sections it shows (SectionBuilds).
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public class RenderSectionMixin implements SectionBuilds.Building {
    @Unique
    private volatile boolean cyanite$building;

    @Inject(method = "createCompileTask", at = @At("HEAD"))
    private void cyanite$rebuilding(RenderRegionCache cache, CallbackInfoReturnable<SectionRenderDispatcher.RenderSection.CompileTask> cir) {
        cyanite$building = true;
    }

    @Inject(method = "setSectionMesh", at = @At("HEAD"))
    private void cyanite$rebuilt(SectionMesh mesh, CallbackInfo ci) {
        cyanite$building = false;
    }

    @Override
    public boolean cyanite$building() {
        return cyanite$building;
    }
}
