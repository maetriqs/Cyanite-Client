package com.cyanite.client.mixin;

import com.cyanite.client.modules.donut.DrillTester;
import com.cyanite.client.modules.world.AreaMiner;
import com.cyanite.client.modules.world.AutoMine;
import com.cyanite.client.modules.world.BedrockBurrow;
import com.cyanite.client.system.Macros;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.Stealth;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public class MinecraftMixin {
    @Shadow
    protected int missTime;

    // Auto Mine, Area Miner, Bedrock Burrow and macros hold "attack" through vanilla's own path, so mining looks exactly like holding left click.
    @ModifyVariable(method = "continueAttack", at = @At("HEAD"), argsOnly = true)
    private boolean cyanite$autoMineAttack(boolean leftClick) {
        if (DrillTester.blocksAttack()) return false;
        if (leftClick) return true;
        // With a screen open vanilla parks attacking for 10000 ticks, and only lets go once attack
        // is released. Release it for a tick after closing the menu, or mining never starts again.
        if (missTime > 0) return false;
        AutoMine autoMine = Modules.get(AutoMine.class);
        if (autoMine != null && autoMine.shouldAttack()) return true;
        AreaMiner areaMiner = Modules.get(AreaMiner.class);
        if (areaMiner != null && areaMiner.shouldAttack()) return true;
        BedrockBurrow burrow = Modules.get(BedrockBurrow.class);
        if (burrow != null && burrow.shouldAttack()) return true;
        return Macros.runner().shouldAttack();
    }

    // Stealth Mode: none of the client's own screens open, whatever tries to open them.
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void cyanite$stealthScreens(Screen screen, CallbackInfo ci) {
        if (Stealth.active() && Stealth.isClientScreen(screen)) ci.cancel();
    }

    // Drill Tester: a click on the test block before it's lined up would waste that block.
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void cyanite$drillTesterHold(CallbackInfoReturnable<Boolean> cir) {
        if (DrillTester.blocksAttack()) cir.setReturnValue(false);
    }
}
