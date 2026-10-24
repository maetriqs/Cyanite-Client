package com.cyanite.client.modules.combat;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.BoolSetting;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * Makes every grounded hit a critical by telling the server you just hopped:
 * up a sixteenth of a block, then back down, both while airborne.
 * Hooked in from MultiPlayerGameModeMixin before each attack.
 */
public class Criticals extends Module {
    private final BoolSetting onlyCharged = add(new BoolSetting("Only full charge", true));

    public Criticals() {
        super(Category.COMBAT, "Criticals", "Turns hits into critical hits");
    }

    @Override
    public String getInfo() {
        return "Packet";
    }

    public void beforeAttack(Entity target) {
        if (!(target instanceof LivingEntity)) return;
        if (!mc.player.onGround() || mc.player.isInWater() || mc.player.isInLava() || mc.player.onClimbable()) return;
        if (onlyCharged.get() && mc.player.getAttackStrengthScale(0.5f) < 0.9f) return;

        double x = mc.player.getX();
        double y = mc.player.getY();
        double z = mc.player.getZ();
        boolean collision = mc.player.horizontalCollision;

        mc.getConnection().send(new ServerboundMovePlayerPacket.Pos(x, y + 0.0625, z, false, collision));
        mc.getConnection().send(new ServerboundMovePlayerPacket.Pos(x, y, z, false, collision));
    }
}
