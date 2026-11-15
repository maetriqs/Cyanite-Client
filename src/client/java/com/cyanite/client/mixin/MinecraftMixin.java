package com.cyanite.client.mixin;

import com.cyanite.client.modules.donut.DrillTester;
import com.cyanite.client.modules.world.AreaMiner;
import com.cyanite.client.modules.world.AutoMine;
import com.cyanite.client.modules.world.BedrockBurrow;
import com.cyanite.client.modules.world.SchematicBuilder;
import com.cyanite.client.system.Macros;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.Stealth;
import com.cyanite.client.system.macro.MacroRecorder;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.Entity;
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

    // ESP's Silhouette: its entities count as glowing, so they get the Glowing effect's outline, and
    // culling mods (which leave glowing entities alone) don't hide them behind walls.
    @Inject(method = "shouldEntityAppearGlowing", at = @At("HEAD"), cancellable = true)
    private void cyanite$espSilhouette(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (com.cyanite.client.render.EspGlow.color(entity) != 0) cir.setReturnValue(true);
    }

    // Auto Mine, Area Miner, Bedrock Burrow, Schematic Builder and macros hold "attack" through vanilla's own path, so mining looks exactly like holding left click.
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
        SchematicBuilder builder = Modules.get(SchematicBuilder.class);
        if (builder != null && builder.shouldAttack()) return true;
        return Macros.runner().shouldAttack();
    }

    // Stealth Mode: none of the client's own screens open, whatever tries to open them.
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void cyanite$stealthScreens(Screen screen, CallbackInfo ci) {
        if (Stealth.active() && Stealth.isClientScreen(screen)) ci.cancel();
    }

    // Cinematic's Smooth menus: a menu opening over the game fades its backdrop in.
    @Inject(method = "setScreen", at = @At("HEAD"))
    private void cyanite$cinematicMenu(Screen screen, CallbackInfo ci) {
        com.cyanite.client.modules.render.Cinematic.screenChanging(((Minecraft) (Object) this).screen, screen);
    }

    // Macro recording: what vanilla's click handlers do while they run is your own click, not a module's.
    @WrapMethod(method = "startAttack")
    private boolean cyanite$recordAttack(Operation<Boolean> original) {
        MacroRecorder recorder = Macros.recorder();
        recorder.userAttack(true);
        try {
            return original.call();
        } finally {
            recorder.userAttack(false);
        }
    }

    @WrapMethod(method = "startUseItem")
    private void cyanite$recordUse(Operation<Void> original) {
        MacroRecorder recorder = Macros.recorder();
        recorder.userUse(true);
        try {
            original.call();
        } finally {
            recorder.userUse(false);
        }
    }

    // Drill Tester: a click on the test block before it's lined up would waste that block.
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void cyanite$drillTesterHold(CallbackInfoReturnable<Boolean> cir) {
        if (DrillTester.blocksAttack()) cir.setReturnValue(false);
    }
}
