package com.cyanite.client.modules.donut;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Notify;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.client.player.LocalPlayer;

import java.util.List;

/**
 * Tells you to relog when you cross a set height. It fires on the crossing, not on being there,
 * so rejoining below the line doesn't set it off again, and it waits until you've gone a couple
 * of blocks back past the line before it can fire again, so hopping on the boundary stays quiet.
 */
public class DepthAlert extends Module {
    private static final int REARM_BLOCKS = 2;

    private final SliderSetting level = add(new SliderSetting("Y level", -2, -64, 320, 1).format(v -> "Y " + (int) v));
    private final ModeSetting trigger = add(new ModeSetting("Trigger", "Going down", List.of("Going down", "Going up", "Either way")));
    private final BoolSetting toast = add(new BoolSetting("Toast", true));
    private final BoolSetting sound = add(new BoolSetting("Sound", true));
    private final BoolSetting flash = add(new BoolSetting("Flash taskbar", true));

    private LocalPlayer player;
    private int lastY;
    private boolean armedDown;
    private boolean armedUp;

    public DepthAlert() {
        super(Category.DONUT, "Depth Alert", "Tells you to relog at a set Y level");
    }

    @Override
    public String getInfo() {
        return "Y " + level.get().intValue();
    }

    @Override
    protected void onEnable() {
        player = null;
    }

    @Override
    public void onTick() {
        int y = mc.player.getBlockY();
        int line = level.get().intValue();

        // Fresh player (joined, relogged, respawned, changed dimension): start from where you are.
        if (mc.player != player) {
            player = mc.player;
            lastY = y;
            armedDown = y > line;
            armedUp = y < line;
            return;
        }

        if (armedDown && lastY > line && y <= line && !trigger.is("Going up")) {
            armedDown = false;
            alert(y);
        } else if (armedUp && lastY < line && y >= line && !trigger.is("Going down")) {
            armedUp = false;
            alert(y);
        }

        if (y >= line + REARM_BLOCKS) armedDown = true;
        if (y <= line - REARM_BLOCKS) armedUp = true;
        lastY = y;
    }

    private void alert(int y) {
        int line = level.get().intValue();
        // Falling fast can skip straight past the line in one tick.
        String body = y == line ? "You reached Y " + line : "You passed Y " + line + " (now Y " + y + ")";
        Notify.send("Relog now", body, toast.get(), sound.get(), flash.get());
    }
}
