package com.cyanite.client.modules.player;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ShulkerBoxMenu;

/** Shift-clicks everything out of chests, barrels and shulkers you open. */
public class ChestStealer extends Module {
    private final SliderSetting delay = add(new SliderSetting("Delay", 2, 0, 10, 1).format(v -> (int) v + " ticks"));
    private final BoolSetting autoClose = add(new BoolSetting("Close when empty", true));

    private int cooldown;

    public ChestStealer() {
        super(Category.PLAYER, "Chest Stealer", "Takes everything from containers");
    }

    @Override
    public void onTick() {
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) {
            cooldown = delay.get().intValue();
            return;
        }

        AbstractContainerMenu menu = screen.getMenu();
        int size;
        if (menu instanceof ChestMenu chest) size = chest.getRowCount() * 9;
        else if (menu instanceof ShulkerBoxMenu) size = 27;
        else return;

        if (cooldown-- > 0) return;

        for (int i = 0; i < size; i++) {
            if (!menu.getSlot(i).hasItem()) continue;
            mc.gameMode.handleInventoryMouseClick(menu.containerId, i, 0, ClickType.QUICK_MOVE, mc.player);
            cooldown = delay.get().intValue();
            return;
        }

        if (autoClose.get()) mc.player.closeContainer();
    }
}
