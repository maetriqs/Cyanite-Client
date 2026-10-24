package com.cyanite.client.modules.movement;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;

public class Step extends Module {
    private static final double VANILLA = 0.6;

    private final SliderSetting height = add(new SliderSetting("Height", 1, 0.6, 3, 0.1));

    public Step() {
        super(Category.MOVEMENT, "Step", "Walks up full blocks");
    }

    @Override
    public String getInfo() {
        return height.display();
    }

    @Override
    public void onTick() {
        // Set every tick since respawning or changing dimension rebuilds the attribute.
        setStepHeight(mc.player.isShiftKeyDown() ? VANILLA : height.get());
    }

    @Override
    protected void onDisable() {
        if (inGame()) setStepHeight(VANILLA);
    }

    private static void setStepHeight(double value) {
        AttributeInstance attribute = mc.player.getAttribute(Attributes.STEP_HEIGHT);
        if (attribute != null && attribute.getBaseValue() != value) attribute.setBaseValue(value);
    }
}
