package com.cyanite.client.modules.combat;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.equipment.Equippable;

/** Puts on the best armor in your inventory. */
public class AutoArmor extends Module {
    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private final SliderSetting delay = add(new SliderSetting("Delay", 2, 0, 10, 1).format(v -> (int) v + " ticks"));
    private final BoolSetting keepElytra = add(new BoolSetting("Keep elytra", true));

    private int cooldown;

    public AutoArmor() {
        super(Category.COMBAT, "Auto Armor", "Equips your best armor");
    }

    @Override
    public void onTick() {
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        // Clicks go to the player's own inventory menu, so don't fire while another container is open.
        if (mc.screen != null && !(mc.screen instanceof InventoryScreen)) return;
        if (!mc.player.containerMenu.getCarried().isEmpty()) return;

        Inventory inventory = mc.player.getInventory();
        for (EquipmentSlot slot : SLOTS) {
            ItemStack worn = mc.player.getItemBySlot(slot);
            if (slot == EquipmentSlot.CHEST && keepElytra.get() && worn.is(Items.ELYTRA)) continue;

            int best = -1;
            double bestScore = worn.isEmpty() ? 0 : score(worn);
            for (int i = 0; i < 36; i++) {
                ItemStack stack = inventory.getItem(i);
                Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
                if (equippable == null || equippable.slot() != slot) continue;

                double score = score(stack);
                if (score > bestScore) {
                    best = i;
                    bestScore = score;
                }
            }
            if (best < 0) continue;

            equip(best, slot, worn.isEmpty());
            cooldown = delay.get().intValue();
            return;
        }
    }

    private void equip(int inventorySlot, EquipmentSlot slot, boolean slotEmpty) {
        int id = mc.player.inventoryMenu.containerId;
        int from = inventorySlot < 9 ? 36 + inventorySlot : inventorySlot;
        // Armor slots in the player's inventory menu run head (5) to feet (8).
        int armor = 5 + (3 - slot.getIndex());

        if (slotEmpty) {
            mc.gameMode.handleInventoryMouseClick(id, from, 0, ClickType.QUICK_MOVE, mc.player);
        } else {
            // Pick up the new piece, swap it into the armor slot, drop the old piece where the new one was.
            mc.gameMode.handleInventoryMouseClick(id, from, 0, ClickType.PICKUP, mc.player);
            mc.gameMode.handleInventoryMouseClick(id, armor, 0, ClickType.PICKUP, mc.player);
            mc.gameMode.handleInventoryMouseClick(id, from, 0, ClickType.PICKUP, mc.player);
        }
    }

    private double score(ItemStack stack) {
        double score = 0;
        ItemAttributeModifiers modifiers = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
            if (entry.attribute().is(Attributes.ARMOR)) score += entry.modifier().amount();
            else if (entry.attribute().is(Attributes.ARMOR_TOUGHNESS)) score += entry.modifier().amount() * 0.5;
        }

        Holder<Enchantment> protection = mc.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.PROTECTION);
        score += EnchantmentHelper.getItemEnchantmentLevel(protection, stack) * 0.75;
        return score;
    }
}
