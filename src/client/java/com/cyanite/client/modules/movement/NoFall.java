package com.cyanite.client.modules.movement;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

/**
 * The server works out fall damage when you land, from how far it thinks you've fallen.
 * Telling it you're on the ground every tick of a fall resets that count before it's
 * enough to hurt.
 */
public class NoFall extends Module {
    public NoFall() {
        super(Category.MOVEMENT, "No Fall", "Prevents fall damage");
    }

    @Override
    public String getInfo() {
        return "Packet";
    }

    @Override
    public void onTick() {
        if (mc.player.isFallFlying() || mc.player.onGround()) return;
        if (mc.player.fallDistance <= 2) return;

        mc.getConnection().send(new ServerboundMovePlayerPacket.StatusOnly(true, mc.player.horizontalCollision));
    }
}
