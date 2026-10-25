package com.cyanite.client.modules.player;

import com.cyanite.client.gui.BetaGuiScreen;
import com.cyanite.client.gui.ClickGuiScreen;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.Keys;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.BoolSetting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;

import java.util.ArrayList;
import java.util.List;

/** Keeps the movement keys working while inventories and the click GUI are open. */
public class InventoryMove extends Module {
    private final BoolSetting jump = add(new BoolSetting("Jump", true));
    private final BoolSetting sprint = add(new BoolSetting("Sprint", true));
    private final BoolSetting sneak = add(new BoolSetting("Sneak", false));
    private final BoolSetting clickGui = add(new BoolSetting("In click GUI", true));

    public InventoryMove() {
        super(Category.PLAYER, "Inventory Move", "Walk around with inventories open");
    }

    @Override
    public void onTick() {
        if (!applies()) return;
        for (KeyMapping key : keys()) key.setDown(Keys.isHeld(key));
    }

    /** On, and walking you around while the click GUI is open (so typing there isn't a search). */
    public boolean walksInClickGui() {
        return isEnabled() && clickGui.get();
    }

    private boolean applies() {
        if (mc.screen instanceof ClickGuiScreen gui) return clickGui.get() && !gui.isTyping();
        if (mc.screen instanceof BetaGuiScreen gui) return clickGui.get() && !gui.isTyping();
        // The creative search box takes typing, so movement keys stay with it.
        return mc.screen instanceof AbstractContainerScreen<?> && !(mc.screen instanceof CreativeModeInventoryScreen);
    }

    private List<KeyMapping> keys() {
        List<KeyMapping> keys = new ArrayList<>(List.of(mc.options.keyUp, mc.options.keyDown, mc.options.keyLeft, mc.options.keyRight));
        if (jump.get()) keys.add(mc.options.keyJump);
        if (sprint.get()) keys.add(mc.options.keySprint);
        if (sneak.get()) keys.add(mc.options.keyShift);
        return keys;
    }
}
