package com.cyanite.client.modules.combat;

import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Throws bottles o' enchanting at your feet from the hotbar to mend your armor: starts when a piece
 * drops below the set durability and keeps throwing, at the pace held right click would, until every
 * piece is whole or the bottles run out. Mending does the repairing, so it's for armor that has it.
 */
public class AutoXp extends ThrowAtFeet {
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private final SliderSetting below = add(new SliderSetting("Start below", 70, 5, 99, 1).format(v -> (int) v + "%"));
    private final BoolSetting onGroundOnly = add(new BoolSetting("Only on ground", true));
    private final BoolSetting switchBack = add(new BoolSetting("Switch back", true));

    private boolean mending;

    public AutoXp() {
        super("Auto XP", "Throws XP bottles at your feet to mend worn armor");
    }

    @Override
    public String getInfo() {
        return mending ? (int) (worst() * 100) + "%" : null;
    }

    @Override
    protected int afterThrow() {
        return 4;
    }

    @Override
    protected boolean mayStart() {
        return !onGroundOnly.get() || mc.player.onGround();
    }

    @Override
    protected boolean switchBack() {
        return switchBack.get();
    }

    @Override
    protected void onDisable() {
        super.onDisable();
        mending = false;
    }

    @Override
    protected int find() {
        double worst = worst();
        if (worst * 100 < below.get()) mending = true;
        if (worst >= 1) mending = false;
        if (!mending) return -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).is(Items.EXPERIENCE_BOTTLE)) return i;
        }
        mending = false;
        return -1;
    }

    /** The durability left on your most worn armor piece, 0 to 1 (1 with none worn). */
    private double worst() {
        double worst = 1;
        for (EquipmentSlot slot : ARMOR) {
            ItemStack stack = mc.player.getItemBySlot(slot);
            if (!stack.isDamageableItem()) continue;
            worst = Math.min(worst, 1 - (double) stack.getDamageValue() / stack.getMaxDamage());
        }
        return worst;
    }
}
