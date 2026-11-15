package com.cyanite.client.visuals;

import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Where a splash potion breaks: a ring on the ground and a dome in its colour, and a tint on your screen when it lands on you. */
public final class PotionImpact {
    public static final Group GROUP = new Group("potion_impact", "Potion Impact Effects",
        "A ring and a dome where a splash potion breaks, and a tint on your screen when one lands on you.",
        "potion impact splash pot heal ring effect break");
    static final BoolSetting OWN_COLOR = GROUP.add(new BoolSetting("Potion's own color", true));
    static final ColorSetting COLOR = GROUP.add(new ColorSetting("Color", 0xFF4D7A).full());
    static final BoolSetting RING = GROUP.add(new BoolSetting("Ground ring", true));
    static final BoolSetting DOME = GROUP.add(new BoolSetting("Dome", true));
    static final SliderSetting SIZE = GROUP.add(new SliderSetting("Size", 1, 0.3, 2.5, 0.05).format(Particles::times));
    static final SliderSetting LENGTH = GROUP.add(new SliderSetting("Length", 0.8, 0.2, 2, 0.05).format(Trails::seconds));
    static final BoolSetting TINT_SELF = GROUP.add(new BoolSetting("Tint screen when hit", true));

    /** A splash potion reaches 4 blocks either way. */
    private static final double REACH = 4;

    private PotionImpact() {
    }

    /** A splash or lingering potion broke at {@code pos} with colour {@code rgb} (level events 2002 and 2007). */
    public static void onSplash(BlockPos pos, int rgb) {
        if (!GROUP.on()) return;
        int color = OWN_COLOR.get() ? rgb & 0xFFFFFF : COLOR.get();
        Vec3 center = Vec3.atBottomCenterOf(pos).add(0, 0.05, 0);
        double size = SIZE.get() * REACH * 2;
        float seconds = LENGTH.get().floatValue();
        if (RING.get()) {
            WorldFx.ring(center, color, 0.3, size, seconds, 3f);
            WorldFx.ring(center.add(0, 0.02, 0), color, 0.2, size * 0.6, seconds * 0.75f, 2f);
        }
        if (DOME.get()) {
            for (int i = 1; i <= 3; i++) {
                double up = i * 0.45 * SIZE.get();
                WorldFx.ring(center.add(0, up, 0), color, 0.2, size * Math.sqrt(1 - Math.pow(i / 3.6, 2)) * 0.8, seconds * (1 - i * 0.12f), 1.6f);
            }
            WorldFx.uprightRing(center.add(0, 0.6, 0), color, 0.2, size * 0.7, seconds * 0.7f, 2f);
        }
        Minecraft mc = Minecraft.getInstance();
        if (TINT_SELF.get() && mc.player != null && mc.player.position().distanceTo(center) < REACH) {
            WorldFx.flash(color, 0.35f, 0.6f);
        }
    }
}
