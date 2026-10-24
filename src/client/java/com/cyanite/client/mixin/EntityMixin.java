package com.cyanite.client.mixin;

import com.cyanite.client.modules.render.Freecam;
import com.cyanite.client.system.HumanAim;
import com.cyanite.client.system.Modules;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.cyanite.client.modules.combat.Hitboxes;

@Mixin(Entity.class)
public class EntityMixin {
    // Hitboxes: a bigger area for the crosshair to pick targets up in.
    @Inject(method = "getPickRadius", at = @At("RETURN"), cancellable = true)
    private void cyanite$hitboxes(CallbackInfoReturnable<Float> cir) {
        Hitboxes hitboxes = Modules.get(Hitboxes.class);
        if (hitboxes == null) return;
        float extra = hitboxes.extra((Entity) (Object) this);
        if (extra > 0) cir.setReturnValue(cir.getReturnValue() + extra);
    }

    // Zoom: your mouse turns slow down with the zoom, so a flick covers the same ground in view.
    // Turns from a module aiming for you (HumanAim) are left alone.
    @ModifyVariable(method = "turn", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private double cyanite$zoomYaw(double yRot) {
        return zoomed() ? yRot * com.cyanite.client.modules.render.Zoom.turnScale() : yRot;
    }

    @ModifyVariable(method = "turn", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private double cyanite$zoomPitch(double xRot) {
        return zoomed() ? xRot * com.cyanite.client.modules.render.Zoom.turnScale() : xRot;
    }

    private boolean zoomed() {
        return (Object) this == Minecraft.getInstance().player && !HumanAim.isTurning();
    }

    // Mouse look turns the Freecam camera instead of the player.
    @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
    private void cyanite$freecamLook(double yRot, double xRot, CallbackInfo ci) {
        if ((Object) this != Minecraft.getInstance().player) return;
        // A module aiming the player (Auto Mine, Aim Assist...) turns the player, not the camera.
        if (HumanAim.isTurning()) return;
        Freecam freecam = Modules.get(Freecam.class);
        if (freecam == null || !freecam.isActive()) return;
        freecam.turn(yRot, xRot);
        ci.cancel();
    }
}
