package com.cyanite.client.modules.combat;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;

import java.util.Locale;

/**
 * Leaves the server before you die: when your health drops too low, or when another player comes
 * close. The disconnect screen says why. It switches itself off after, so rejoining doesn't kick
 * you straight back out.
 */
public class AutoLog extends Module {
    private final SliderSetting health = add(new SliderSetting("Health", 6, 1, 19, 1).format(v -> "at " + (int) v + " HP"));
    private final BoolSetting noTotem = add(new BoolSetting("Only without totem", true));
    private final SliderSetting playerRange = add(new SliderSetting("Player nearby", 0, 0, 64, 4).format(v -> v == 0 ? "Off" : (int) v + "m"));
    private final BoolSetting turnOff = add(new BoolSetting("Turn off after", true));

    public AutoLog() {
        super(Category.COMBAT, "Auto Log", "Disconnects when you're about to die");
    }

    @Override
    public String getInfo() {
        return health.get().intValue() + " HP";
    }

    @Override
    public void onTick() {
        // Leaving singleplayer this way would strand the world mid-save; this is for servers.
        if (mc.isLocalServer() || mc.getConnection() == null) return;

        String reason = null;
        float hp = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        boolean totem = mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING) || mc.player.getMainHandItem().is(Items.TOTEM_OF_UNDYING);
        if (hp <= health.get() && !(noTotem.get() && totem)) {
            reason = String.format(Locale.ROOT, "Health dropped to %.1f", hp);
        }
        if (reason == null && playerRange.get() > 0) {
            for (Player other : mc.level.players()) {
                if (other == mc.player || other.isSpectator() || AntiBot.isBot(other)) continue;
                double distance = other.distanceTo(mc.player);
                if (distance <= playerRange.get()) {
                    reason = other.getName().getString() + " came within " + Math.round(distance) + "m";
                    break;
                }
            }
        }
        if (reason == null) return;

        if (turnOff.get()) setEnabled(false);
        mc.getConnection().getConnection().disconnect(com.cyanite.client.system.Stealth.disconnectReason("Cyanite Auto Log\n\n" + reason));
    }
}
