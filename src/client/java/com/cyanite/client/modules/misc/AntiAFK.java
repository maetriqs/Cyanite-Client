package com.cyanite.client.modules.misc;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.world.InteractionHand;

import java.util.concurrent.ThreadLocalRandom;

public class AntiAFK extends Module {
    private final SliderSetting interval = add(new SliderSetting("Interval", 30, 5, 120, 5).format(v -> (int) v + "s"));
    private final BoolSetting jump = add(new BoolSetting("Jump", true));
    private final BoolSetting swing = add(new BoolSetting("Swing", true));
    private final BoolSetting rotate = add(new BoolSetting("Rotate", false));

    private int ticks;

    public AntiAFK() {
        super(Category.MISC, "Anti AFK", "Moves now and then so you aren't kicked for idling");
    }

    @Override
    public void onTick() {
        if (++ticks < interval.get() * 20) return;
        ticks = 0;

        if (jump.get() && mc.player.onGround()) mc.player.jumpFromGround();
        if (swing.get()) mc.player.swing(InteractionHand.MAIN_HAND);
        if (rotate.get()) mc.player.setYRot(mc.player.getYRot() + (float) ThreadLocalRandom.current().nextDouble(-25, 25));
    }
}
