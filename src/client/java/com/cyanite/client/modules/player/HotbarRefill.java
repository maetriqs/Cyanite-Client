package com.cyanite.client.modules.player;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Keeps your crystal PvP hotbar stocked: when a hotbar stack of crystals, obsidian, gapples and
 * the like runs low (or runs out), the fullest matching stack in your inventory is swapped in, in
 * one click, so the slot you're used to always has some.
 */
public class HotbarRefill extends Module {
    private final BoolSetting crystals = add(new BoolSetting("Crystals", true));
    private final BoolSetting obsidian = add(new BoolSetting("Obsidian", true));
    private final BoolSetting gapples = add(new BoolSetting("Gapples", true));
    private final BoolSetting anchors = add(new BoolSetting("Anchors and glowstone", true));
    private final BoolSetting pearls = add(new BoolSetting("Pearls", false));
    private final BoolSetting xp = add(new BoolSetting("XP bottles", false));
    private final SliderSetting below = add(new SliderSetting("Refill below", 16, 1, 63, 1));
    private final SliderSetting delay = add(new SliderSetting("Delay", 2, 0, 10, 1).format(v -> (int) (double) v + " ticks"));

    /** What each hotbar slot last held, so one that's run out gets refilled with the same thing. */
    private final ItemStack[] remembered = new ItemStack[9];
    private int wait;

    public HotbarRefill() {
        super(Category.PLAYER, "Hotbar Refill", "Keeps crystals, obsidian and gapples topped up in the same hotbar slots");
    }

    @Override
    protected void onDisable() {
        java.util.Arrays.fill(remembered, null);
        wait = 0;
    }

    private boolean wanted(Item item) {
        if (item == Items.END_CRYSTAL) return crystals.get();
        if (item == Items.OBSIDIAN) return obsidian.get();
        if (item == Items.GOLDEN_APPLE || item == Items.ENCHANTED_GOLDEN_APPLE) return gapples.get();
        if (item == Items.RESPAWN_ANCHOR || item == Items.GLOWSTONE) return anchors.get();
        if (item == Items.ENDER_PEARL) return pearls.get();
        if (item == Items.EXPERIENCE_BOTTLE) return xp.get();
        return false;
    }

    @Override
    public void onTick() {
        Inventory inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty()) remembered[i] = wanted(s.getItem()) ? s.copyWithCount(1) : null;
        }
        if (wait > 0) {
            wait--;
            return;
        }
        // Clicking your inventory only makes sense with nothing else open.
        if (mc.screen != null && !(mc.screen instanceof InventoryScreen) || mc.player.containerMenu != mc.player.inventoryMenu) return;
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getItem(i);
            ItemStack kind = s.isEmpty() ? remembered[i] : s;
            if (kind == null || !wanted(kind.getItem())) continue;
            int have = s.isEmpty() ? 0 : s.getCount();
            if (have >= below.get() || have >= kind.getMaxStackSize()) continue;
            int best = -1;
            for (int j = 9; j < 36; j++) {
                ItemStack t = inv.getItem(j);
                if (!ItemStack.isSameItemSameComponents(kind, t) || t.getCount() <= have) continue;
                if (best < 0 || t.getCount() > inv.getItem(best).getCount()) best = j;
            }
            if (best < 0) continue;
            // One click: the full stack into the hotbar slot, the low one back where it came from.
            mc.gameMode.handleInventoryMouseClick(mc.player.inventoryMenu.containerId, best, i, ClickType.SWAP, mc.player);
            wait = delay.get().intValue();
            return;
        }
    }
}
