package com.cyanite.client.modules.render;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.ModeSetting;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

import java.util.List;

public class Fullbright extends Module {
    private final ModeSetting mode = add(new ModeSetting("Mode", "Gamma", List.of("Gamma", "Night vision")));

    private boolean addedEffect;

    public Fullbright() {
        super(Category.RENDER, "Fullbright", "Lights up everything");
        mode.onChange(this::removeEffect);
    }

    @Override
    public String getInfo() {
        return mode.get();
    }

    /** Read by LightTextureMixin in place of the brightness slider. */
    public boolean overridesGamma() {
        return isEnabled() && mode.is("Gamma");
    }

    @Override
    public void onTick() {
        if (!mode.is("Night vision") || mc.player.hasEffect(MobEffects.NIGHT_VISION)) return;
        // -1 is an infinite duration, which also skips the flicker before an effect runs out.
        mc.player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, -1, 0, false, false));
        addedEffect = true;
    }

    @Override
    protected void onDisable() {
        removeEffect();
    }

    private void removeEffect() {
        if (addedEffect && inGame()) mc.player.removeEffect(MobEffects.NIGHT_VISION);
        addedEffect = false;
    }
}
