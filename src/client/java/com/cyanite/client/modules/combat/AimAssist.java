package com.cyanite.client.modules.combat;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.WorldScan;
import com.cyanite.client.system.HumanAim;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Targets;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * Pulls your aim toward the nearest target in front of you, a frame at a time, the way a steady
 * hand would. It only helps: you still choose who to fight, and it lets go when they leave your view.
 */
public class AimAssist extends Module {
    private final SliderSetting range = add(new SliderSetting("Range", 4.5, 1, 8, 0.1));
    private final SliderSetting fov = add(new SliderSetting("FOV", 90, 10, 180, 5).format(v -> (int) v + "°"));
    private final SliderSetting speed = add(new SliderSetting("Speed", 0.6, 0.1, 2, 0.05).format(v -> String.format(Locale.ROOT, "%.2fx", v)));
    private final BoolSetting players = add(new BoolSetting("Players", true));
    private final BoolSetting hostiles = add(new BoolSetting("Hostiles", true));
    private final BoolSetting passives = add(new BoolSetting("Passives", false));
    private final BoolSetting clicking = add(new BoolSetting("Only while clicking", true));
    private final BoolSetting weaponOnly = add(new BoolSetting("Weapon only", false));
    private final BoolSetting throughWalls = add(new BoolSetting("Through walls", false));

    private final HumanAim aim = new HumanAim();
    private Entity target;

    public AimAssist() {
        super(Category.COMBAT, "Aim Assist", "Smoothly pulls your aim onto targets");
    }

    @Override
    protected void onDisable() {
        target = null;
        aim.clear();
    }

    @Override
    public void onFrame() {
        if (mc.screen != null || (clicking.get() && !mc.options.keyAttack.isDown())
            || (weaponOnly.get() && !isWeapon(mc.player.getMainHandItem()))) {
            release();
            return;
        }

        float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        Entity best = null;
        double bestAngle = fov.get() / 2;
        for (Entity entity : WorldScan.entities()) {
            if (!(entity instanceof LivingEntity) || !Targets.isTarget(entity, players.get(), hostiles.get(), passives.get())) continue;
            if (Targets.reach(entity) > range.get()) continue;
            if (!throughWalls.get() && !mc.player.hasLineOfSight(entity)) continue;
            double angle = Targets.angleTo(Targets.chest(entity, partialTick));
            if (angle < bestAngle) {
                best = entity;
                bestAngle = angle;
            }
        }
        if (best == null) {
            release();
            return;
        }

        Vec3 point = Targets.chest(best, partialTick);
        if (best != target) aim.setTarget(point);
        else aim.track(point);
        target = best;
        aim.speed = speed.get();
        aim.randomness = 0.4;
        aim.frame();
    }

    private void release() {
        target = null;
        aim.clear();
        aim.idle();
    }

    private static boolean isWeapon(ItemStack stack) {
        return stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES);
    }
}
