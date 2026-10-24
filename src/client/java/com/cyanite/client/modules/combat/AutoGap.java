package com.cyanite.client.modules.combat;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Keys;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Eats a golden apple when your health gets low, from the offhand if one's there, else the hotbar. */
public class AutoGap extends Module {
    private final SliderSetting health = add(new SliderSetting("Health", 10, 1, 20, 1).format(v -> "at " + (int) v + " HP"));
    private final BoolSetting preferEnchanted = add(new BoolSetting("Prefer enchanted", true));
    private final BoolSetting skipRegen = add(new BoolSetting("Skip if regenerating", true));
    private final BoolSetting switchBack = add(new BoolSetting("Switch back", true));

    private boolean eating;
    private boolean started;
    private InteractionHand hand;
    private int previousSlot = -1;

    public AutoGap() {
        super(Category.COMBAT, "Auto Gap", "Eats a golden apple when you're low");
    }

    /** True while a golden apple is being eaten, so Auto Eat and Kill Aura can wait. */
    public static boolean busy() {
        AutoGap gap = Modules.get(AutoGap.class);
        return gap != null && gap.isEnabled() && gap.eating;
    }

    @Override
    public void onTick() {
        if (eating) {
            if (!isGap(mc.player.getItemInHand(hand))) {
                stop();
                return;
            }
            if (mc.player.isUsingItem()) {
                started = true;
            } else if (started) {
                // It finished: the apple's eaten.
                stop();
                return;
            }
            mc.options.keyUse.setDown(true);
            if (!mc.player.isUsingItem()) mc.gameMode.useItem(mc.player, hand);
            return;
        }

        if (mc.screen != null || mc.player.isUsingItem() || mc.player.getHealth() > health.get()) return;
        if (skipRegen.get() && mc.player.hasEffect(MobEffects.REGENERATION)) return;

        if (isGap(mc.player.getOffhandItem())) {
            hand = InteractionHand.OFF_HAND;
        } else {
            int slot = findGap();
            if (slot < 0) return;
            previousSlot = mc.player.getInventory().getSelectedSlot();
            mc.player.getInventory().setSelectedSlot(slot);
            hand = InteractionHand.MAIN_HAND;
        }
        eating = true;
        started = false;
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
        started = false;
    }

    private int findGap() {
        int plain = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.is(Items.ENCHANTED_GOLDEN_APPLE) && preferEnchanted.get()) return i;
            if (isGap(stack) && plain < 0) plain = i;
        }
        return plain;
    }

    private static boolean isGap(ItemStack stack) {
        return stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE);
    }
}
