package com.cyanite.client.modules.combat;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.WorldScan;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.Targets;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ModeSetting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.phys.EntityHitResult;

import java.util.List;

/**
 * Picks up your best weapon as soon as a fight starts, before the first swing: the game reads a
 * weapon's damage a tick after you switch to it, so switching in the same tick as the hit would
 * deal the old item's damage. Swaps to an axe while the target holds up a shield.
 */
public class AutoWeapon extends Module {
    private static final int SWITCH_BACK_TICKS = 40;

    private final ModeSetting prefer = add(new ModeSetting("Prefer", "Sword", List.of("Sword", "Axe", "Most damage")));
    private final BoolSetting breakShields = add(new BoolSetting("Axe vs shields", true));
    private final BoolSetting players = add(new BoolSetting("Players", true));
    private final BoolSetting hostiles = add(new BoolSetting("Hostiles", true));
    private final BoolSetting passives = add(new BoolSetting("Passives", false));
    private final BoolSetting switchBack = add(new BoolSetting("Switch back", false));

    private int previousSlot = -1;
    private int idleTicks;

    public AutoWeapon() {
        super(Category.COMBAT, "Auto Weapon", "Switches to your best weapon when a fight starts");
    }

    @Override
    public String getInfo() {
        return prefer.get();
    }

    @Override
    protected void onDisable() {
        previousSlot = -1;
    }

    @Override
    public void onTick() {
        if (mc.screen != null || mc.player.isUsingItem()) return;

        LivingEntity target = target();
        if (target == null) {
            if (switchBack.get() && previousSlot >= 0 && ++idleTicks >= SWITCH_BACK_TICKS) {
                mc.player.getInventory().setSelectedSlot(previousSlot);
                previousSlot = -1;
            }
            return;
        }
        idleTicks = 0;

        boolean shielded = breakShields.get() && target.isBlocking();
        int slot = shielded ? best(true) : best(false);
        if (slot < 0) slot = best(false);
        int current = mc.player.getInventory().getSelectedSlot();
        if (slot < 0 || slot == current) return;
        if (previousSlot < 0) previousSlot = current;
        mc.player.getInventory().setSelectedSlot(slot);
    }

    /** What's under the crosshair, or, with Kill Aura on, anything it could hit. */
    private LivingEntity target() {
        if (mc.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof LivingEntity living
            && Targets.isTarget(living, players.get(), hostiles.get(), passives.get())) {
            return living;
        }
        if (!Modules.get(KillAura.class).isEnabled()) return null;
        double reach = mc.player.entityInteractionRange();
        for (Entity entity : WorldScan.entities()) {
            if (entity instanceof LivingEntity living && Targets.isTarget(living, players.get(), hostiles.get(), passives.get())
                && Targets.reach(living) <= reach) {
                return living;
            }
        }
        return null;
    }

    private int best(boolean axeOnly) {
        int best = -1;
        double bestScore = 1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (axeOnly && !stack.is(ItemTags.AXES)) continue;
            double score = score(stack);
            if (score > bestScore) {
                best = i;
                bestScore = score;
            }
        }
        return best;
    }

    private double score(ItemStack stack) {
        double damage = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY)
            .compute(Attributes.ATTACK_DAMAGE, 1, EquipmentSlot.MAINHAND);
        if (damage <= 1) return 0;
        // A big bonus for the preferred kind, so damage only decides between swords (or axes).
        if (prefer.is("Sword") && stack.is(ItemTags.SWORDS)) damage += 100;
        if (prefer.is("Axe") && stack.is(ItemTags.AXES)) damage += 100;
        return damage;
    }
}
