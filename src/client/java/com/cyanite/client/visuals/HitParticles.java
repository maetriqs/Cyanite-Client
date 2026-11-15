package com.cyanite.client.visuals;

import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.Map;

/** Particles of your choice bursting from whatever you hit: one set for swords, one for axes, one for everything else and arrows. */
public final class HitParticles {
    private static final Map<String, ParticleOptions> TYPES = Map.ofEntries(
        Map.entry("Crit", ParticleTypes.CRIT), Map.entry("Magic", ParticleTypes.ENCHANTED_HIT), Map.entry("Flame", ParticleTypes.FLAME),
        Map.entry("Soul flame", ParticleTypes.SOUL_FIRE_FLAME), Map.entry("Hearts", ParticleTypes.HEART), Map.entry("Sparks", ParticleTypes.ELECTRIC_SPARK),
        Map.entry("Totem", ParticleTypes.TOTEM_OF_UNDYING), Map.entry("Damage", ParticleTypes.DAMAGE_INDICATOR), Map.entry("Glow", ParticleTypes.GLOW),
        Map.entry("End rod", ParticleTypes.END_ROD), Map.entry("Cloud", ParticleTypes.CLOUD), Map.entry("Firework", ParticleTypes.FIREWORK));
    private static final List<String> NAMES = List.of("Crit", "Magic", "Flame", "Soul flame", "Hearts", "Sparks", "Totem", "Damage", "Glow",
        "End rod", "Cloud", "Firework");

    /** One weapon's hit burst. */
    static final class Burst {
        final Group group;
        final ModeSetting type;
        final SliderSetting amount;
        final SliderSetting spread;
        final BoolSetting tint;
        final ColorSetting color;

        Burst(Group group, String type, int amount, int color) {
            this.group = group;
            this.type = group.add(new ModeSetting("Particle", type, NAMES));
            this.amount = group.add(new SliderSetting("Amount", amount, 1, 40, 1));
            this.spread = group.add(new SliderSetting("Spread", 0.35, 0.05, 1.2, 0.05));
            this.tint = group.add(new BoolSetting("Tint", true));
            this.color = group.add(new ColorSetting("Color", color).full());
        }

        void spawn(Entity target) {
            ParticleOptions options = TYPES.getOrDefault(type.get(), ParticleTypes.CRIT);
            double y = target.getY() + target.getBbHeight() * 0.6;
            Particles.spawn(options, target.getX(), y, target.getZ(), amount.get().intValue(), spread.get() * Math.max(0.5, target.getBbWidth()),
                tint.get() ? color.get() : 0, 0.12);
        }
    }

    public static final Group SWORD = new Group("sword_hit_particles", "Sword Hit Particles",
        "A burst of particles from whatever your sword hits.", "sword hit particles burst effect");
    static final Burst SWORD_BURST = new Burst(SWORD, "Sparks", 10, 0x4DE1FF);

    public static final Group AXE = new Group("axe_hit_particles", "Axe Hit Particles",
        "A burst of particles from whatever your axe hits.", "axe hit particles burst effect");
    static final Burst AXE_BURST = new Burst(AXE, "Crit", 14, 0xFF8A3D);

    public static final Group UHC = new Group("uhc_hit_particles", "Hit Particles",
        "A burst from whatever you hit with anything else, and wherever your arrows land on someone.", "uhc smp hit particles arrow bow burst");
    static final Burst UHC_BURST = new Burst(UHC, "Hearts", 6, 0xFF4D7A);
    static final BoolSetting ARROWS = UHC.add(new BoolSetting("On arrow hits", true));

    private HitParticles() {
    }

    static void onAttack(Entity target) {
        Weapon kind = Weapon.of(Minecraft.getInstance().player.getMainHandItem());
        Burst burst = kind == Weapon.SWORD ? SWORD_BURST : kind == Weapon.AXE ? AXE_BURST : UHC_BURST;
        Visuals.run(burst.group, () -> burst.spawn(target));
    }

    /** Something of yours (an arrow) hit {@code target}. */
    public static void onProjectileHit(Entity target) {
        if (!UHC.on() || !ARROWS.get()) return;
        Visuals.run(UHC, () -> UHC_BURST.spawn(target));
    }
}
