package com.cyanite.client.modules.movement;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;

/**
 * The server works out fall damage when you land, from how far it thinks you've fallen.
 * Telling it you're on the ground every tick of a fall resets that count before it's
 * enough to hurt. It goes in the movement packets the game sends anyway (LocalPlayerMixin):
 * an extra packet every tick is more movement than a tick allows, which anticheats count.
 */
public class NoFall extends Module {
    public NoFall() {
        super(Category.MOVEMENT, "No Fall", "Prevents fall damage");
    }

    @Override
    public String getInfo() {
        return "Packet";
    }

    /** Whether the movement packet going out now should say you're on the ground. */
    public boolean claimsGround() {
        return isEnabled() && !mc.player.isFallFlying() && mc.player.fallDistance > 2;
    }
}
