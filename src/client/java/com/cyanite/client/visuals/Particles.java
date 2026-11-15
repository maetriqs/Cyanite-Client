package com.cyanite.client.visuals;

import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;

/**
 * Particle size, amount, see-through-ness and colour. Every particle the game makes passes through here
 * twice: once before it's made (to thin them out) and once after (its size, and a colour it keeps until it's
 * drawn). Explosion and splash particles are told apart by what the game is doing when they're made.
 */
public final class Particles {
    public static final Group GENERAL = new Group("particles", "Custom Particles",
        "Size, amount and opacity of every particle.", "particle density visibility amount opacity size");
    static final SliderSetting SIZE = GENERAL.add(new SliderSetting("Size", 1, 0.25, 3, 0.05).format(Particles::times));
    static final SliderSetting DENSITY = GENERAL.add(new SliderSetting("Density", 1, 0, 1, 0.05).percent());
    static final SliderSetting OPACITY = GENERAL.add(new SliderSetting("Opacity", 1, 0.1, 1, 0.05).percent());

    public static final Group EXPLOSION = new Group("explosion_particles", "Explosion Particles",
        "Recolours and resizes the bursts and smoke from crystals, anchors and TNT.", "explosion boom smoke crystal anchor tnt");
    static final ColorSetting EXPLOSION_COLOR = EXPLOSION.add(new ColorSetting("Color", 0xFF8A3D).full());
    static final SliderSetting EXPLOSION_SIZE = EXPLOSION.add(new SliderSetting("Size", 1, 0.25, 3, 0.05).format(Particles::times));
    static final SliderSetting EXPLOSION_DENSITY = EXPLOSION.add(new SliderSetting("Density", 1, 0, 1, 0.05).percent());
    static final BoolSetting EXPLOSION_SMOKE = EXPLOSION.add(new BoolSetting("Show smoke", true));

    public static final Group CRIT = new Group("crit_particles", "Critical Hit Particles",
        "The stars on a critical hit, in your colour and size.", "crit critical stars enchanted hit sharpness");
    static final ColorSetting CRIT_COLOR = CRIT.add(new ColorSetting("Color", 0xFF4D4D).full());
    static final ColorSetting MAGIC_COLOR = CRIT.add(new ColorSetting("Enchanted hit color", 0x4DC8FF).full());
    static final SliderSetting CRIT_SIZE = CRIT.add(new SliderSetting("Size", 1, 0.25, 3, 0.05).format(Particles::times));
    static final SliderSetting CRIT_AMOUNT = CRIT.add(new SliderSetting("Amount", 1, 0, 3, 0.05).format(Particles::times));

    public static final Group SPLASH = new Group("splash_particles", "Splash Particles",
        "The cloud a splash potion leaves where it breaks.", "splash potion pot heal instant health cloud");
    static final BoolSetting SPLASH_OWN_COLOR = SPLASH.add(new BoolSetting("Keep the potion's color", true));
    static final ColorSetting SPLASH_COLOR = SPLASH.add(new ColorSetting("Color", 0xFF4D7A).full());
    static final SliderSetting SPLASH_SIZE = SPLASH.add(new SliderSetting("Size", 1, 0.25, 3, 0.05).format(Particles::times));
    static final SliderSetting SPLASH_DENSITY = SPLASH.add(new SliderSetting("Density", 1, 0, 1, 0.05).percent());
    static final SliderSetting SPLASH_OPACITY = SPLASH.add(new SliderSetting("Opacity", 1, 0.1, 1, 0.05).percent());

    /** What the game is in the middle of while particles are made. */
    private enum Context { NONE, EXPLOSION, SPLASH }

    private static Context context = Context.NONE;
    /** Set while our own hit particles are made: only the general settings touch them. */
    private static boolean own;
    /** Extra crits being made from one: no more extras for those. */
    private static boolean copying;
    private static final RandomSource RANDOM = RandomSource.create();

    private Particles() {
    }

    static String times(double v) {
        return String.format(java.util.Locale.ROOT, "%.2fx", v).replace(".00x", "x");
    }

    // ---- Called from the mixins

    public static void beginExplosion() {
        context = Context.EXPLOSION;
    }

    public static void beginSplash() {
        context = Context.SPLASH;
    }

    public static void end() {
        context = Context.NONE;
    }

    /** Before a particle is made: false leaves it out. */
    public static boolean keep(ParticleOptions options) {
        if (!Visuals.live()) return true;
        ParticleType<?> type = options.getType();
        double keep = GENERAL.on() ? DENSITY.get() : 1;
        if (!own) {
            if (EXPLOSION.on() && (context == Context.EXPLOSION || type == ParticleTypes.EXPLOSION || type == ParticleTypes.EXPLOSION_EMITTER)) {
                if (!EXPLOSION_SMOKE.get() && isSmoke(type)) return false;
                keep *= EXPLOSION_DENSITY.get();
            } else if (SPLASH.on() && context == Context.SPLASH) {
                keep *= SPLASH_DENSITY.get();
            } else if (CRIT.on() && isCrit(type)) {
                keep *= Math.min(1, CRIT_AMOUNT.get());
            }
        }
        return keep >= 1 || RANDOM.nextFloat() < keep;
    }

    /** After a particle is made: its size and colour. */
    public static void style(ParticleOptions options, Particle particle, double x, double y, double z, double dx, double dy, double dz) {
        if (!Visuals.live()) return;
        ParticleType<?> type = options.getType();
        float size = 1;
        float alpha = 1;
        int tint = 0;
        if (GENERAL.on()) {
            size *= SIZE.get().floatValue();
            alpha *= OPACITY.get().floatValue();
        }
        if (!own) {
            if (EXPLOSION.on() && (context == Context.EXPLOSION || type == ParticleTypes.EXPLOSION || type == ParticleTypes.EXPLOSION_EMITTER)) {
                size *= EXPLOSION_SIZE.get().floatValue();
                tint = EXPLOSION_COLOR.get();
            } else if (SPLASH.on() && context == Context.SPLASH) {
                size *= SPLASH_SIZE.get().floatValue();
                alpha *= SPLASH_OPACITY.get().floatValue();
                if (!SPLASH_OWN_COLOR.get()) tint = SPLASH_COLOR.get();
            } else if (CRIT.on() && isCrit(type)) {
                size *= CRIT_SIZE.get().floatValue();
                tint = type == ParticleTypes.ENCHANTED_HIT ? MAGIC_COLOR.get() : CRIT_COLOR.get();
                moreCrits(options, x, y, z, dx, dy, dz);
            } else if (type == ParticleTypes.TOTEM_OF_UNDYING) {
                tint = TotemPop.particleTint();
            }
        }
        if (size != 1) particle.scale(size);
        if (tint != 0 || alpha != 1) {
            ParticleTint duck = (ParticleTint) particle;
            if (tint != 0) duck.cyanite$setTint(0xFF000000 | tint);
            duck.cyanite$setAlpha(alpha);
        }
    }

    /** More than one of each crit: the rest of the amount as copies, jittered so they don't overlap. */
    private static void moreCrits(ParticleOptions options, double x, double y, double z, double dx, double dy, double dz) {
        double extra = CRIT_AMOUNT.get() - 1;
        if (extra <= 0 || copying) return;
        copying = true;
        try {
            var engine = net.minecraft.client.Minecraft.getInstance().particleEngine;
            while (extra > 0) {
                if (extra >= 1 || RANDOM.nextFloat() < extra) {
                    engine.createParticle(options, x + RANDOM.nextGaussian() * 0.15, y + RANDOM.nextGaussian() * 0.15, z + RANDOM.nextGaussian() * 0.15,
                        dx * (0.8 + RANDOM.nextFloat() * 0.4), dy * (0.8 + RANDOM.nextFloat() * 0.4), dz * (0.8 + RANDOM.nextFloat() * 0.4));
                }
                extra--;
            }
        } finally {
            copying = false;
        }
    }

    /** The colour a particle is drawn in: its own shade, turned towards its tint, and its opacity. */
    public static int drawColor(Particle particle, int argb) {
        ParticleTint duck = (ParticleTint) particle;
        int tint = duck.cyanite$tint();
        float alpha = duck.cyanite$alpha();
        if (tint == 0 && alpha == 1) return argb;
        if (tint != 0) {
            // The tint at the particle's own brightness, so a texture's light and dark parts stay.
            int r = argb >> 16 & 0xFF;
            int g = argb >> 8 & 0xFF;
            int b = argb & 0xFF;
            float lum = Math.min(1.25f, (0.3f * r + 0.59f * g + 0.11f * b) / 255f * 1.35f);
            int tr = Math.min(255, Math.round((tint >> 16 & 0xFF) * lum));
            int tg = Math.min(255, Math.round((tint >> 8 & 0xFF) * lum));
            int tb = Math.min(255, Math.round((tint & 0xFF) * lum));
            argb = argb & 0xFF000000 | tr << 16 | tg << 8 | tb;
        }
        if (alpha != 1) argb = Math.round((argb >>> 24) * alpha) << 24 | argb & 0xFFFFFF;
        return argb;
    }

    /** The layer a particle is drawn on: a see-through one moves off the opaque layer, which can't fade it. */
    public static SingleQuadParticle.Layer layer(Particle particle, SingleQuadParticle.Layer layer) {
        if (layer == SingleQuadParticle.Layer.OPAQUE && ((ParticleTint) particle).cyanite$alpha() < 1) return SingleQuadParticle.Layer.TRANSLUCENT;
        return layer;
    }

    /**
     * Makes particles of our own (hit effects): {@code count} of them around ({@code x, y, z}) within {@code spread},
     * tinted {@code rgb} (0 to keep their colour).
     */
    public static void spawn(ParticleOptions options, double x, double y, double z, int count, double spread, int rgb, double speed) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        own = true;
        try {
            for (int i = 0; i < count; i++) {
                Particle p = mc.particleEngine.createParticle(options,
                    x + RANDOM.nextGaussian() * spread, y + RANDOM.nextGaussian() * spread, z + RANDOM.nextGaussian() * spread,
                    RANDOM.nextGaussian() * speed, RANDOM.nextGaussian() * speed + speed * 0.5, RANDOM.nextGaussian() * speed);
                if (p != null && rgb != 0) ((ParticleTint) p).cyanite$setTint(0xFF000000 | rgb);
            }
        } finally {
            own = false;
        }
    }

    private static boolean isCrit(ParticleType<?> type) {
        return type == ParticleTypes.CRIT || type == ParticleTypes.ENCHANTED_HIT;
    }

    private static boolean isSmoke(ParticleType<?> type) {
        return type == ParticleTypes.SMOKE || type == ParticleTypes.LARGE_SMOKE || type == ParticleTypes.POOF;
    }
}
