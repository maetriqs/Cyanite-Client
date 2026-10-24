package com.cyanite.client.mixin;

import com.cyanite.client.modules.movement.BoatFly;
import com.cyanite.client.system.Modules;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractBoat.class)
public class AbstractBoatMixin {
    @Shadow
    private float deltaRotation;

    // After the boat has turned your keys into motion and before it moves, so Boat Fly gets the final say.
    @Inject(
        method = "tick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/vehicle/boat/AbstractBoat;controlBoat()V", shift = At.Shift.AFTER)
    )
    private void cyanite$boatFly(CallbackInfo ci) {
        BoatFly boatFly = Modules.get(BoatFly.class);
        // The boat turns its rider by deltaRotation too, which would spin the camera while strafing.
        if (boatFly != null && boatFly.fly((AbstractBoat) (Object) this)) deltaRotation = 0;
    }
}
