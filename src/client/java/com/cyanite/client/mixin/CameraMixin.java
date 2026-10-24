package com.cyanite.client.mixin;

import com.cyanite.client.modules.render.F5Camera;
import com.cyanite.client.modules.render.Freecam;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.cyanite.client.system.Modules;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    protected abstract void setPosition(Vec3 position);

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    // Treat Freecam as third person, so your own player is drawn and the first-person hand isn't.
    @ModifyVariable(method = "setup", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private boolean cyanite$freecamDetached(boolean detached) {
        Freecam freecam = Modules.get(Freecam.class);
        return detached || freecam != null && freecam.isActive();
    }

    // F5 Camera: how far back third person sits (vanilla's 4 blocks, times your scale).
    @ModifyArg(method = "setup", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;getMaxZoom(F)F"), index = 0)
    private float cyanite$f5Distance(float distance) {
        F5Camera f5 = Modules.get(F5Camera.class);
        return f5 != null ? f5.distance(distance) : distance;
    }

    // F5 Camera: straight through walls rather than pulled in front of them.
    @Inject(method = "getMaxZoom", at = @At("HEAD"), cancellable = true)
    private void cyanite$f5ThroughWalls(float distance, CallbackInfoReturnable<Float> cir) {
        F5Camera f5 = Modules.get(F5Camera.class);
        if (f5 != null && f5.clips()) cir.setReturnValue(distance);
    }

    @Inject(method = "setup", at = @At("TAIL"))
    private void cyanite$freecam(Level level, Entity entity, boolean detached, boolean mirrored, float partialTick, CallbackInfo ci) {
        Freecam freecam = Modules.get(Freecam.class);
        if (freecam == null || !freecam.isActive()) return;
        setRotation(freecam.yaw(), freecam.pitch());
        setPosition(freecam.cameraPosition(partialTick));
    }
}
