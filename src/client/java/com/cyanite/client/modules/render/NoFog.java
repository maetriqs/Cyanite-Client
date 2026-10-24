package com.cyanite.client.modules.render;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.BoolSetting;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import net.minecraft.client.renderer.fog.environment.FogEnvironment;
import net.minecraft.client.renderer.fog.environment.LavaFogEnvironment;
import net.minecraft.client.renderer.fog.environment.MobEffectFogEnvironment;
import net.minecraft.client.renderer.fog.environment.PowderedSnowFogEnvironment;
import net.minecraft.client.renderer.fog.environment.WaterFogEnvironment;
import net.minecraft.world.level.Level;

/**
 * Pushes fog out of sight. The game builds fog in two layers: the haze of wherever you are (the
 * Nether's thick fog, rain, water, lava, blindness...) and the fade at the edge of your render
 * distance. FogRendererMixin hands both to this module, per dimension and per environment.
 */
public class NoFog extends Module {
    /** Far enough that nothing in view gets any fog, near enough that the shader's maths stays sane. */
    private static final float FAR = 1.0e7f;

    private final BoolSetting overworld = add(new BoolSetting("Overworld", true));
    private final BoolSetting nether = add(new BoolSetting("Nether", true));
    private final BoolSetting end = add(new BoolSetting("End", true));
    private final BoolSetting water = add(new BoolSetting("Water", false));
    private final BoolSetting lava = add(new BoolSetting("Lava", false));
    private final BoolSetting powderSnow = add(new BoolSetting("Powder snow", false));
    private final BoolSetting effects = add(new BoolSetting("Blindness & darkness", false));

    public NoFog() {
        super(Category.RENDER, "No Fog", "Removes fog in the overworld, nether and end");
    }

    /** Whether the fog this environment just set up should be cleared. */
    public boolean clears(FogEnvironment environment) {
        if (!isEnabled()) return false;
        if (environment instanceof AtmosphericFogEnvironment) return dimension();
        if (environment instanceof WaterFogEnvironment) return water.get();
        if (environment instanceof LavaFogEnvironment) return lava.get();
        if (environment instanceof PowderedSnowFogEnvironment) return powderSnow.get();
        if (environment instanceof MobEffectFogEnvironment) return effects.get();
        return false;
    }

    /** Whether the fade at the edge of your render distance should go, in the dimension you're in. */
    public boolean clearsDistance() {
        return isEnabled() && dimension();
    }

    private boolean dimension() {
        if (mc.level == null) return false;
        if (mc.level.dimension() == Level.NETHER) return nether.get();
        if (mc.level.dimension() == Level.END) return end.get();
        return overworld.get();
    }

    public static void clear(FogData data) {
        data.environmentalStart = FAR;
        data.environmentalEnd = FAR * 2;
        data.skyEnd = FAR * 2;
        data.cloudEnd = FAR * 2;
    }

    public static float far() {
        return FAR;
    }
}
