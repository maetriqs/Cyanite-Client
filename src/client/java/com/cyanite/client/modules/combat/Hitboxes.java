package com.cyanite.client.modules.combat;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Targets;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.world.entity.Entity;

import java.util.Locale;

/**
 * Makes targets easier to click by growing the area your crosshair picks them up in. Only your
 * crosshair is affected (EntityMixin): it changes nothing in the world, and only on the render thread.
 */
public class Hitboxes extends Module {
    private final SliderSetting expand = add(new SliderSetting("Expand", 0.3, 0.05, 1, 0.05).format(v -> String.format(Locale.ROOT, "%.2f", v)));
    private final BoolSetting players = add(new BoolSetting("Players", true));
    private final BoolSetting hostiles = add(new BoolSetting("Hostiles", true));
    private final BoolSetting passives = add(new BoolSetting("Passives", false));

    public Hitboxes() {
        super(Category.COMBAT, "Hitboxes", "Makes targets easier to hit");
    }

    @Override
    public String getInfo() {
        return String.format(Locale.ROOT, "%.2f", expand.get());
    }

    /** How much bigger this entity's pick box should be. */
    public float extra(Entity entity) {
        if (!isEnabled() || mc.player == null || !mc.isSameThread()) return 0;
        return Targets.isTarget(entity, players.get(), hostiles.get(), passives.get()) ? expand.get().floatValue() : 0;
    }
}
