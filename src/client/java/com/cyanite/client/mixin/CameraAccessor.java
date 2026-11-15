package com.cyanite.client.mixin;

import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Moves the camera for a CameraShot and puts it back after. */
@Mixin(Camera.class)
public interface CameraAccessor {
    @Invoker("setPosition")
    void cyanite$setPosition(Vec3 position);

    @Invoker("setRotation")
    void cyanite$setRotation(float yRot, float xRot);

    @Accessor("detached")
    void cyanite$setDetached(boolean detached);
}
