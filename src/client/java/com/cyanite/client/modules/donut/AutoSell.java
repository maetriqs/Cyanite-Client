package com.cyanite.client.modules.donut;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Notify;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.SliderSetting;
import com.cyanite.client.system.setting.StringSetting;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * Puts every item in your hotbar up on the auction house: it picks each slot in turn and sends
 * /ah sell with your price. Press its key (or switch it on) once and it works through the hotbar,
 * then switches itself off.
 *
 * <p>Picking a slot only reaches the server on the next tick, so it waits two ticks between
 * choosing a slot and selling, or the server would sell whatever you held before. If the server
 * opens a confirmation menu, it waits for you to deal with it and carries on once it's closed.
 */
public class AutoSell extends Module {
    private static final int SETTLE_TICKS = 2;

    private final StringSetting price = add(new StringSetting("Price", "1000", 16).placeholder("e.g. 1000"));
    private final SliderSetting delay = add(new SliderSetting("Delay", 1.5, 0.5, 5, 0.25).format(v -> String.format(Locale.ROOT, "%.2fs", v)));
    private final BoolSetting skipGear = add(new BoolSetting("Skip tools & weapons", true));
    private final BoolSetting skipFood = add(new BoolSetting("Skip food", false));
    private final BoolSetting switchBack = add(new BoolSetting("Switch back", true));

    private final Deque<Integer> queue = new ArrayDeque<>();
    private int previousSlot = -1;
    private int slot = -1;
    private int wait;
    private int sold;
    private boolean waitingForMenu;

    public AutoSell() {
        super(Category.DONUT, "Auto Sell", "Lists every hotbar item with /ah sell");
    }

    @Override
    public String getInfo() {
        return price.get();
    }

    @Override
    protected void onEnable() {
        queue.clear();
        sold = 0;
        slot = -1;
        waitingForMenu = false;
        // A profile can switch this on from the title screen; nothing to sell from there.
        if (!inGame()) {
            setEnabled(false);
            return;
        }
        String amount = price.get().strip();
        if (amount.isEmpty() || amount.contains(" ")) {
            Notify.send("Auto Sell", "Set a price first (Donut SMP > Auto Sell > Price)", true, false, false);
            setEnabled(false);
            return;
        }
        for (int i = 0; i < 9; i++) {
            if (sellable(mc.player.getInventory().getItem(i))) queue.add(i);
        }
        if (queue.isEmpty()) {
            Notify.send("Auto Sell", "Nothing in your hotbar to sell", true, false, false);
            setEnabled(false);
            return;
        }
        previousSlot = mc.player.getInventory().getSelectedSlot();
        wait = 0;
    }

    @Override
    protected void onDisable() {
        if (inGame() && switchBack.get() && previousSlot >= 0) mc.player.getInventory().setSelectedSlot(previousSlot);
        previousSlot = -1;
        queue.clear();
    }

    @Override
    public void onTick() {
        // A confirmation menu from the server: let the player answer it, then carry on.
        if (mc.screen instanceof AbstractContainerScreen<?>) {
            if (!waitingForMenu) {
                waitingForMenu = true;
                Notify.send("Auto Sell", "Confirm in the menu, then it carries on", false, false, false);
            }
            return;
        }
        if (mc.screen != null) return;
        waitingForMenu = false;

        if (wait > 0) {
            wait--;
            return;
        }

        if (slot >= 0) {
            // The slot change has reached the server by now: sell what's in hand.
            if (sellable(mc.player.getInventory().getItem(slot))) {
                mc.player.connection.sendCommand("ah sell " + price.get().strip());
                sold++;
            }
            slot = -1;
            wait = (int) Math.round(delay.get() * 20);
            return;
        }

        if (queue.isEmpty()) {
            Notify.send("Auto Sell", "Listed " + sold + (sold == 1 ? " item" : " items") + " at " + price.get().strip() + " each", true, true, false);
            setEnabled(false);
            return;
        }
        slot = queue.poll();
        mc.player.getInventory().setSelectedSlot(slot);
        wait = SETTLE_TICKS;
    }

    private boolean sellable(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (skipGear.get() && stack.isDamageableItem()) return false;
        return !(skipFood.get() && stack.has(DataComponents.FOOD));
    }
}
