package com.cyanite.client.mixin.visuals;

import com.cyanite.client.visuals.Crystals;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.model.object.crystal.EndCrystalModel;
import net.minecraft.client.renderer.entity.state.EndCrystalRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Crystal Animation and End Crystal Size: spin, bob, the floating cube's scale and the base.
@Mixin(EndCrystalModel.class)
public class EndCrystalModelMixin {
    @ModifyVariable(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/EndCrystalRenderState;)V", at = @At("STORE"), ordinal = 0)
    private float cyanite$spin(float degrees) {
        return Crystals.spin(degrees);
    }

    @WrapOperation(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/EndCrystalRenderState;)V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/entity/EndCrystalRenderer;getY(F)F"))
    private float cyanite$bob(float age, Operation<Float> original) {
        return Crystals.bobHeight(original.call(Crystals.bobAge(age)));
    }

    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/EndCrystalRenderState;)V", at = @At("TAIL"))
    private void cyanite$cube(EndCrystalRenderState state, CallbackInfo ci) {
        Crystals.afterSetupAnim((EndCrystalModel) (Object) this);
    }
}
