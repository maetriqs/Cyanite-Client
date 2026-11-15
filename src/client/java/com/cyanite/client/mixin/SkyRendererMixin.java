package com.cyanite.client.mixin;

import com.cyanite.client.modules.render.Cinematic;
import com.cyanite.client.render.SpaceSky;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.SkyRenderState;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SkyRenderer.class)
public class SkyRendererMixin {
    // Cinematic's space sky: once a frame, as the game works out its sky, ease it in or out.
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void cyanite$spaceSkyFrame(ClientLevel level, float partialTick, Camera camera, SkyRenderState state, CallbackInfo ci) {
        Cinematic.skyFrame(state.skybox == DimensionType.Skybox.OVERWORLD, state.starAngle, state.rainBrightness);
    }

    // It's drawn instead of the sky disc (the sun and moon still go over it)...
    @Inject(method = "renderSkyDisc", at = @At("HEAD"), cancellable = true)
    private void cyanite$spaceSky(int color, CallbackInfo ci) {
        if (!SpaceSky.full()) return;
        SpaceSky.draw();
        ci.cancel();
    }

    // ...or over it while fading in or out.
    @Inject(method = "renderSkyDisc", at = @At("TAIL"))
    private void cyanite$spaceSkyFading(int color, CallbackInfo ci) {
        if (SpaceSky.fading()) SpaceSky.draw();
    }

    // The sunrise glow, the game's stars and the dark disc under the horizon would cover it.
    @Inject(method = {"renderSunriseAndSunset", "renderStars", "renderDarkDisc"}, at = @At("HEAD"), cancellable = true)
    private void cyanite$underSpaceSky(CallbackInfo ci) {
        if (SpaceSky.full()) ci.cancel();
    }
}
