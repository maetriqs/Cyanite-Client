package com.cyanite.client.modules.player;

import com.cyanite.client.mixin.FishingHookAccessor;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.Items;

public class AutoFish extends Module {
    private final SliderSetting recastDelay = add(new SliderSetting("Recast delay", 15, 5, 60, 1).format(v -> (int) v + " ticks"));
    private final BoolSetting autoCast = add(new BoolSetting("Auto cast", true));

    private boolean reeled;
    private int wait;
    private int caught;

    public AutoFish() {
        super(Category.PLAYER, "Auto Fish", "Reels in and recasts for you");
    }

    @Override
    public String getInfo() {
        return Integer.toString(caught);
    }

    @Override
    public void onTick() {
        if (mc.screen != null || !mc.player.getMainHandItem().is(Items.FISHING_ROD)) return;

        FishingHook hook = mc.player.fishing;
        if (hook != null) {
            // The server flags the bobber the moment something bites.
            if (!reeled && ((FishingHookAccessor) hook).cyanite$isBiting()) {
                use();
                reeled = true;
                caught++;
                wait = recastDelay.get().intValue();
            }
            return;
        }

        reeled = false;
        if (wait > 0) {
            wait--;
            return;
        }
        if (autoCast.get()) {
            use();
            wait = 20;
        }
    }

    private void use() {
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }
}
