package com.cyanite.client.visuals;

import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/** Explosions (crystals, anchors, TNT) given a ring on the ground, a shockwave, and a flash when they're close. */
public final class Explosions {
    public static final Group GROUP = new Group("explosion_effects", "Custom Explosion Effects",
        "A ring and shockwave where something blows up, and a flash when it's close to you.", "explosion effect ring shockwave flash crystal anchor tnt boom");
    static final ColorSetting COLOR = GROUP.add(new ColorSetting("Color", 0xFF7A2E).full());
    static final BoolSetting RING = GROUP.add(new BoolSetting("Ground ring", true));
    static final BoolSetting SHOCKWAVE = GROUP.add(new BoolSetting("Shockwave", true));
    static final SliderSetting SIZE = GROUP.add(new SliderSetting("Size", 1, 0.3, 3, 0.05).format(Particles::times));
    static final SliderSetting LENGTH = GROUP.add(new SliderSetting("Length", 0.7, 0.2, 2, 0.05).format(v -> String.format(java.util.Locale.ROOT, "%.1fs", v)));
    static final BoolSetting FLASH = GROUP.add(new BoolSetting("Flash when close", true));
    static final SliderSetting FLASH_STRENGTH = GROUP.add(new SliderSetting("Flash strength", 0.35, 0.05, 1, 0.05).percent());

    private Explosions() {
    }

    /** The game was told something exploded at {@code center}. */
    public static void onExplosion(Vec3 center, float radius) {
        if (!GROUP.on()) return;
        int rgb = COLOR.get();
        double size = SIZE.get() * Math.max(2, radius * 2);
        float seconds = LENGTH.get().floatValue();
        if (RING.get()) WorldFx.ring(center.add(0, -0.4, 0), rgb, 0.4, size * 1.6, seconds, 3f);
        if (SHOCKWAVE.get()) {
            WorldFx.uprightRing(center, rgb, 0.3, size * 1.3, seconds * 0.8f, 2.5f);
            WorldFx.ring(center.add(0, 0.6, 0), rgb, 0.2, size, seconds * 0.6f, 2f);
        }
        Minecraft mc = Minecraft.getInstance();
        if (FLASH.get() && mc.player != null) {
            double d = mc.player.getEyePosition().distanceTo(center);
            double reach = Math.max(6, radius * 3);
            if (d < reach) WorldFx.flash(rgb, (float) (FLASH_STRENGTH.get() * (1 - d / reach)), 0.5f);
        }
    }
}
