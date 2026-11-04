package com.cyanite.client.modules.movement;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * A Riptide trident launches you when you let go of right click, once it has been held for 10
 * ticks, in water or rain. This lets go for you as soon as it's charged. Vanilla starts using it
 * again on the next tick because right click is still down (the {@code keyUse.isDown()} check at
 * the end of {@code Minecraft.handleKeybinds}), so holding right click launches you again and again.
 */
public class AutoTrident extends Module {
    // One tick past the 10 the trident needs, so a server running a tick behind still counts it charged.
    private final SliderSetting charge = add(new SliderSetting("Charge", 11, 10, 20, 1).format(v -> (int) v + " ticks"));

    public AutoTrident() {
        super(Category.MOVEMENT, "Auto Trident", "Lets go of a Riptide trident as soon as it's charged while you hold right click, so you launch again and again");
    }

    @Override
    public String getInfo() {
        return charge.display();
    }

    @Override
    public void onTick() {
        LocalPlayer player = mc.player;
        if (!player.isUsingItem() || !mc.options.keyUse.isDown()) return;
        ItemStack stack = player.getUseItem();
        if (!(stack.getItem() instanceof TridentItem)) return;
        // Without Riptide letting go throws the trident away.
        if (EnchantmentHelper.getTridentSpinAttackStrength(stack, player) <= 0) return;
        if (player.getTicksUsingItem() < charge.get()) return;
        // Out of the water it wouldn't launch: keep it charged for when you're back in.
        if (!player.isInWaterOrRain()) return;
        mc.gameMode.releaseUsingItem(player);
    }
}
