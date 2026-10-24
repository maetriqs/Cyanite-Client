package com.cyanite.client.mixin;

import com.cyanite.client.modules.render.Freecam;
import com.cyanite.client.modules.render.NoFog;
import com.cyanite.client.system.Modules;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.FogEnvironment;
import org.objectweb.asm.Opcodes;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(FogRenderer.class)
public class FogRendererMixin {
    // Freecam: push the fog out by however far the camera is from the player, so loaded chunks
    // around the player aren't hidden in fog when you look back at them from a distance.
    @ModifyVariable(method = "setupFog", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int cyanite$freecamFog(int renderDistance, Camera camera, int unused, DeltaTracker deltaTracker, float partialTick, ClientLevel level) {
        return renderDistance + Freecam.extraChunks(camera.position());
    }

    // No Fog: clear the haze of the environment you're in (dimension, water, lava...) as it's set up.
    @WrapOperation(method = "setupFog", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/fog/environment/FogEnvironment;setupFog(Lnet/minecraft/client/renderer/fog/FogData;Lnet/minecraft/client/Camera;Lnet/minecraft/client/multiplayer/ClientLevel;FLnet/minecraft/client/DeltaTracker;)V"))
    private void cyanite$noFogEnvironment(FogEnvironment environment, FogData data, Camera camera, ClientLevel level, float renderDistance,
                                          DeltaTracker deltaTracker, Operation<Void> original) {
        original.call(environment, data, camera, level, renderDistance, deltaTracker);
        NoFog noFog = Modules.get(NoFog.class);
        if (noFog != null && noFog.clears(environment)) NoFog.clear(data);
    }

    // No Fog: and the fade at the edge of your render distance. Set on the fog data itself, not just
    // what gets uploaded, because Sodium copies these fields for its terrain shader.
    @WrapOperation(method = "setupFog", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/fog/FogData;renderDistanceStart:F", opcode = Opcodes.PUTFIELD))
    private void cyanite$noDistanceFogStart(FogData data, float value, Operation<Void> original) {
        NoFog noFog = Modules.get(NoFog.class);
        original.call(data, noFog != null && noFog.clearsDistance() ? NoFog.far() : value);
    }

    @WrapOperation(method = "setupFog", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/fog/FogData;renderDistanceEnd:F", opcode = Opcodes.PUTFIELD))
    private void cyanite$noDistanceFogEnd(FogData data, float value, Operation<Void> original) {
        NoFog noFog = Modules.get(NoFog.class);
        original.call(data, noFog != null && noFog.clearsDistance() ? NoFog.far() * 2 : value);
    }
}
