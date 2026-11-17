package com.cyanite.client.modules.combat;

import com.cyanite.client.mixin.LocalPlayerAccessor;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Targets;
import net.minecraft.world.InteractionHand;

/**
 * What Auto Pot and Auto XP share: switch to what's wanted, look straight down, and throw it at your
 * feet once the server has that look (a throw goes where the server thinks you look), then look back
 * up and switch back. Only one of them throws at a time, and Kill Aura and Crystal Aura hold off while
 * one does, since they'd turn you away.
 */
abstract class ThrowAtFeet extends Module {
    private static ThrowAtFeet throwing;

    private int previousSlot = -1;
    private float pitchBefore;
    private int wait;

    ThrowAtFeet(String name, String description) {
        super(Category.COMBAT, name, description);
    }

    /** The hotbar slot to throw from now, or -1 when nothing's wanted. */
    protected abstract int find();

    /** Ticks to stay looking down after a throw before looking for the next. */
    protected abstract int afterThrow();

    protected abstract boolean mayStart();

    protected abstract boolean switchBack();

    /** Whether one of them is looking down to throw. */
    public static boolean busy() {
        return throwing != null && throwing.isEnabled();
    }

    @Override
    protected boolean ticksEarly() {
        return true;
    }

    @Override
    protected void onDisable() {
        if (throwing == this && inGame()) finish();
        if (throwing == this) throwing = null;
    }

    @Override
    public void onTick() {
        if (wait > 0) {
            wait--;
            return;
        }
        if (throwing != this) {
            if (busy() || mc.screen != null || mc.player.isUsingItem() || AutoGap.busy() || !mayStart() || find() < 0) return;
            throwing = this;
            previousSlot = mc.player.getInventory().getSelectedSlot();
            pitchBefore = mc.player.getXRot();
        }
        int slot = find();
        if (slot < 0 || mc.screen != null) {
            finish();
            return;
        }
        mc.player.getInventory().setSelectedSlot(slot);
        Targets.turnTo(mc.player.getYRot(), 90);
        if (((LocalPlayerAccessor) mc.player).cyanite$getSentPitch() < 80) return;
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        mc.player.swing(InteractionHand.MAIN_HAND);
        wait = afterThrow();
    }

    private void finish() {
        Targets.turnTo(mc.player.getYRot(), pitchBefore);
        if (switchBack() && previousSlot >= 0) mc.player.getInventory().setSelectedSlot(previousSlot);
        previousSlot = -1;
        throwing = null;
        // A moment before starting again, so a wanted throw that didn't take (no item left) can't flicker.
        wait = 4;
    }
}
