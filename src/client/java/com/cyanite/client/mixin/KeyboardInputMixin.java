package com.cyanite.client.mixin;

import com.cyanite.client.modules.donut.DrillTester;
import com.cyanite.client.modules.render.Freecam;
import com.cyanite.client.modules.world.AreaMiner;
import com.cyanite.client.modules.world.BedrockBurrow;
import com.cyanite.client.modules.world.AutoMine;
import com.cyanite.client.system.Macros;
import com.cyanite.client.system.Modules;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {
    // While Freecam flies, the movement keys drive the camera and the player stands still.
    @Inject(method = "tick", at = @At("TAIL"))
    private void cyanite$freecamFreeze(CallbackInfo ci) {
        Freecam freecam = Modules.get(Freecam.class);
        if (freecam != null && freecam.isActive()) {
            this.keyPresses = Input.EMPTY;
            this.moveVector = Vec2.ZERO;
        }
        // Auto Mine and Area Miner drive the player themselves, with or without Freecam, leaving your keys alone.
        // A running macro's walk or held key comes first.
        Input steering = Macros.runner().steering();
        DrillTester tester = Modules.get(DrillTester.class);
        if (steering == null && tester != null) steering = tester.steering();
        AreaMiner areaMiner = Modules.get(AreaMiner.class);
        if (steering == null && areaMiner != null) steering = areaMiner.steering();
        BedrockBurrow burrow = Modules.get(BedrockBurrow.class);
        if (steering == null && burrow != null) steering = burrow.steering();
        AutoMine autoMine = Modules.get(AutoMine.class);
        if (steering == null && autoMine != null) steering = autoMine.steering();
        com.cyanite.client.modules.world.SchematicBuilder builder = Modules.get(com.cyanite.client.modules.world.SchematicBuilder.class);
        if (steering == null && builder != null) steering = builder.steering();
        if (steering != null) {
            this.keyPresses = steering;
            this.moveVector = new Vec2(impulse(steering.left(), steering.right()), impulse(steering.forward(), steering.backward())).normalized();
        }
    }

    private static float impulse(boolean positive, boolean negative) {
        return (positive ? 1 : 0) - (negative ? 1 : 0);
    }
}
