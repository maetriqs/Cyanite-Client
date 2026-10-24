package com.cyanite.client.modules.donut;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Notify;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;

/**
 * One key to leave the server and come straight back. Bind it, press it, and it disconnects the
 * way the menu button does, waits the delay on a countdown screen, then rejoins the same server.
 * It switches itself back off, so the bind works every time.
 */
public class Relog extends Module {
    private final SliderSetting delay = add(new SliderSetting("Delay", 1, 0, 15, 0.5)
        .format(v -> v == 0 ? "Instant" : (v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v)) + "s"));

    public Relog() {
        super(Category.DONUT, "Relog", "Leaves and rejoins the server with one key");
    }

    @Override
    protected void onEnable() {
        // A profile can switch this on from the title screen; there's nothing to relog from there.
        if (!inGame()) {
            setEnabled(false);
            return;
        }
        ServerData server = mc.getCurrentServer();
        if (server == null || mc.isLocalServer()) {
            Notify.send("Relog", "Only works on a server, not in singleplayer", false, false, false);
            setEnabled(false);
            return;
        }
        long wait = Math.round(delay.get() * 1000);
        setEnabled(false);
        // Not from inside the key press or the module loop: the world is gone once this runs.
        mc.schedule(() -> relog(server, wait));
    }

    private static void relog(ServerData server, long waitMillis) {
        if (mc.level == null) return;
        mc.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
        mc.setScreen(new RelogScreen(server, waitMillis));
    }
}
