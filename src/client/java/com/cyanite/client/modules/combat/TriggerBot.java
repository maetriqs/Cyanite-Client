package com.cyanite.client.modules.combat;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;

/** Attacks whatever is under the crosshair once the swing is charged. */
public class TriggerBot extends Module {
    private final BoolSetting players = add(new BoolSetting("Players", true));
    private final BoolSetting hostiles = add(new BoolSetting("Hostiles", true));
    private final BoolSetting passives = add(new BoolSetting("Passives", false));
    private final SliderSetting charge = add(new SliderSetting("Charge", 1, 0.5, 1, 0.05).percent());
    private final BoolSetting weaponOnly = add(new BoolSetting("Weapon only", false));

    public TriggerBot() {
        super(Category.COMBAT, "Trigger Bot", "Attacks what you're aiming at");
    }

    @Override
    public void onTick() {
        if (mc.screen != null || mc.player.isUsingItem()) return;
        if (!(mc.hitResult instanceof EntityHitResult hit) || !(hit.getEntity() instanceof LivingEntity target)) return;
        if (!target.isAlive() || !isTarget(target)) return;
        if (weaponOnly.get() && !isWeapon(mc.player.getMainHandItem())) return;
        if (mc.player.getAttackStrengthScale(0.5f) < charge.get()) return;

        mc.gameMode.attack(mc.player, target);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    private boolean isTarget(LivingEntity entity) {
        if (entity instanceof ArmorStand || AntiBot.isBot(entity)) return false;
        if (entity instanceof Player player) return players.get() && !player.isSpectator() && !player.isCreative();
        if (entity instanceof Enemy) return hostiles.get();
        return passives.get();
    }

    private static boolean isWeapon(ItemStack stack) {
        return stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES);
    }
}
