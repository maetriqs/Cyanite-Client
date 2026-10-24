package com.cyanite.client.modules.player;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Keys;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Set;

public class AutoEat extends Module {
    private static final Set<Item> BAD = Set.of(Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO,
        Items.PUFFERFISH, Items.CHORUS_FRUIT, Items.SUSPICIOUS_STEW);

    private final SliderSetting hunger = add(new SliderSetting("Hunger", 14, 1, 19, 1).format(v -> "at " + (int) v));
    private final BoolSetting avoidBad = add(new BoolSetting("Avoid bad food", true));
    private final BoolSetting switchBack = add(new BoolSetting("Switch back", true));

    private boolean eating;
    private int previousSlot = -1;

    public AutoEat() {
        super(Category.PLAYER, "Auto Eat", "Eats when you get hungry");
    }

    public boolean isEating() {
        return eating;
    }

    @Override
    public void onTick() {
        int food = mc.player.getFoodData().getFoodLevel();

        if (eating) {
            if (food > hunger.get() || !isFood(mc.player.getMainHandItem())) {
                stop();
                return;
            }
            // Holding use keeps the eat going; starting it directly avoids clicking on a block we're looking at.
            mc.options.keyUse.setDown(true);
            if (!mc.player.isUsingItem()) mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            return;
        }

        // A golden apple from Auto Gap comes first.
        if (mc.screen != null || food > hunger.get() || com.cyanite.client.modules.combat.AutoGap.busy()) return;
        int slot = findFood();
        if (slot < 0) return;

        previousSlot = mc.player.getInventory().getSelectedSlot();
        mc.player.getInventory().setSelectedSlot(slot);
        eating = true;
    }

    @Override
    protected void onDisable() {
        if (eating && inGame()) stop();
        eating = false;
    }

    private void stop() {
        Keys.restore(mc.options.keyUse);
        if (switchBack.get() && previousSlot >= 0) mc.player.getInventory().setSelectedSlot(previousSlot);
        previousSlot = -1;
        eating = false;
    }

    private int findFood() {
        int best = -1;
        int bestNutrition = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!isFood(stack)) continue;
            int nutrition = stack.get(DataComponents.FOOD).nutrition();
            if (nutrition > bestNutrition) {
                best = i;
                bestNutrition = nutrition;
            }
        }
        return best;
    }

    private boolean isFood(ItemStack stack) {
        if (!stack.has(DataComponents.FOOD)) return false;
        return !avoidBad.get() || !BAD.contains(stack.getItem());
    }
}
