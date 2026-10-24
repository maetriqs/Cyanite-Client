package com.cyanite.client.modules.combat;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.Notify;
import com.cyanite.client.system.setting.BoolSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Totem pops: every player's count since they last died. Counted all the time (Target HUD and
 * Nametags show it); switched on, it also says each pop and death in chat.
 */
public class PopCounter extends Module {
    private static final Map<UUID, Integer> POPS = new HashMap<>();
    private static ClientLevel level;

    private final BoolSetting yours = add(new BoolSetting("Count yours", true));
    private final BoolSetting deaths = add(new BoolSetting("Say deaths", true));

    public PopCounter() {
        super(Category.COMBAT, "Pop Counter", "Says in chat when a player pops a totem and how many; Target HUD and Nametags show the count");
    }

    /** How many totems this player's popped since they last died. */
    public static int pops(Player player) {
        return POPS.getOrDefault(player.getUUID(), 0);
    }

    /** An entity event from the server (game thread). */
    public static void onEntityEvent(Entity entity, byte event) {
        if (!(entity instanceof Player player)) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != level) {
            level = mc.level;
            POPS.clear();
        }
        PopCounter self = Modules.get(PopCounter.class);
        boolean tell = self != null && self.isEnabled() && (player != mc.player || self.yours.get());
        String name = player.getGameProfile().name();
        if (event == EntityEvent.PROTECTED_FROM_DEATH) {
            int n = POPS.merge(player.getUUID(), 1, Integer::sum);
            if (tell) Notify.send("Pop Counter", (player == mc.player ? "You" : name) + " popped " + n + (n == 1 ? " totem" : " totems"), false, false, false);
        } else if (event == EntityEvent.DEATH) {
            Integer n = POPS.remove(player.getUUID());
            if (tell && self.deaths.get() && n != null) {
                Notify.send("Pop Counter", (player == mc.player ? "You" : name) + " died after popping " + n + (n == 1 ? " totem" : " totems"), false, false, false);
            }
        }
    }
}
