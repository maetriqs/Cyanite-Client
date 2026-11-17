package com.cyanite.client.modules.combat;

import com.cyanite.client.mixin.MinecraftAccessor;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.ClickTimer;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.world.phys.EntityHitResult;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Keeps clicking while you hold attack on an entity. Cooldown mode swings each time the attack is
 * charged (best for 1.9+ combat); CPS mode clicks at a random rate in your range. Blocks are left
 * to normal mining, so holding attack on stone still just breaks it.
 */
public class AutoClicker extends Module {
    private final ModeSetting mode = add(new ModeSetting("Mode", "Cooldown", List.of("Cooldown", "CPS")));
    private final SliderSetting charge = add(new SliderSetting("Charge", 0.95, 0.5, 1, 0.01).percent());
    private final SliderSetting minCps = add(new SliderSetting("Min CPS", 8, 1, 20, 1));
    private final SliderSetting maxCps = add(new SliderSetting("Max CPS", 12, 1, 20, 1));

    private final ClickTimer clicks = new ClickTimer();
    private double nextCharge = 1;

    public AutoClicker() {
        super(Category.COMBAT, "Auto Clicker", "Keeps attacking while you hold left click");
    }

    @Override
    public String getInfo() {
        int low = minCps.get().intValue();
        return mode.is("CPS") ? String.format(Locale.ROOT, "%d-%d", low, Math.max(low, maxCps.get().intValue())) : "Cooldown";
    }

    @Override
    protected boolean ticksEarly() {
        return true;
    }

    @Override
    public void onTick() {
        if (mc.screen != null || mc.player.isUsingItem() || !mc.options.keyAttack.isDown()) return;
        if (!(mc.hitResult instanceof EntityHitResult)) return;
        for (int i = swings(); i > 0; i--) ((MinecraftAccessor) mc).cyanite$startAttack();
    }

    private int swings() {
        if (mode.is("CPS")) return clicks.due(minCps.get(), maxCps.get());
        if (mc.player.getAttackStrengthScale(0.5f) < nextCharge) return 0;
        // Wait for a slightly different charge each swing, so the rhythm isn't machine-perfect.
        nextCharge = Math.min(1, charge.get() + ThreadLocalRandom.current().nextDouble(0, 0.05));
        return 1;
    }
}
