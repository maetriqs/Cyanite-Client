package com.cyanite.client.modules.combat;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.world.phys.Vec3;

/** Scales the knockback the server applies to you. Hooked in from ClientPacketListenerMixin. */
public class Velocity extends Module {
    private final SliderSetting horizontal = add(new SliderSetting("Horizontal", 0, 0, 1, 0.05).percent());
    private final SliderSetting vertical = add(new SliderSetting("Vertical", 0, 0, 1, 0.05).percent());
    private final BoolSetting explosions = add(new BoolSetting("Explosions", true));

    public Velocity() {
        super(Category.COMBAT, "Velocity", "Reduces knockback");
    }

    @Override
    public String getInfo() {
        return horizontal.display() + " " + vertical.display();
    }

    /** Knockback set outright by the server, e.g. from being hit. */
    public Vec3 modifyMotion(Vec3 current, Vec3 incoming) {
        // The packet replaces your velocity, so blend between what you had and what it wants.
        double h = horizontal.get();
        double v = vertical.get();
        return new Vec3(
            current.x + (incoming.x - current.x) * h,
            current.y + (incoming.y - current.y) * v,
            current.z + (incoming.z - current.z) * h
        );
    }

    /** Knockback added on top of your velocity by an explosion. */
    public Vec3 modifyExplosion(Vec3 push) {
        if (!explosions.get()) return push;
        return new Vec3(push.x * horizontal.get(), push.y * vertical.get(), push.z * horizontal.get());
    }
}
