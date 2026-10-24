package com.cyanite.client.modules.combat;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.setting.BoolSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.regex.Pattern;

/**
 * Tells fake players apart from real ones, so combat modules leave server NPCs and anti-cheat
 * bait alone. Real players are on the tab list, have a real name and a version 3 or 4 UUID;
 * NPC plugins usually miss at least one of those.
 */
public class AntiBot extends Module {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private final BoolSetting tabList = add(new BoolSetting("Not in tab list", true));
    private final BoolSetting npcUuid = add(new BoolSetting("NPC UUIDs", true));
    private final BoolSetting badName = add(new BoolSetting("Invalid names", true));
    private final BoolSetting zeroPing = add(new BoolSetting("Zero ping", false));

    public AntiBot() {
        super(Category.COMBAT, "Anti Bot", "Stops combat modules targeting fake players");
    }

    /** Whether combat modules should ignore this entity. Only players can be bots; mobs never are. */
    public static boolean isBot(Entity entity) {
        AntiBot antiBot = Modules.get(AntiBot.class);
        if (antiBot == null || !antiBot.isEnabled() || !(entity instanceof Player player)) return false;
        if (player == Minecraft.getInstance().player) return false;
        return antiBot.check(player);
    }

    private boolean check(Player player) {
        var connection = mc.getConnection();
        PlayerInfo info = connection == null ? null : connection.getPlayerInfo(player.getUUID());
        if (tabList.get() && info == null) return true;
        // Mojang hands out version 4 (online) and 3 (offline) UUIDs; Citizens and friends use 2.
        int version = player.getUUID().version();
        if (npcUuid.get() && version != 3 && version != 4) return true;
        if (badName.get() && !NAME.matcher(player.getGameProfile().name()).matches()) return true;
        return zeroPing.get() && info != null && info.getLatency() == 0;
    }
}
