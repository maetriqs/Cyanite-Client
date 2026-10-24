package com.cyanite.client.modules.misc;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.Notify;
import com.cyanite.client.system.Stealth;

/**
 * Stealth Mode: the game looks like plain Minecraft. Every render module (ESP, tracers, Xray,
 * Fullbright, Freecam, Zoom, nametags, waypoints...) goes off, along with pets and the Discord
 * status, and nothing else the client draws is drawn: no HUD, no module list, no boxes or beams
 * in the world, no chat lines, toasts or sounds. The menu key does nothing and the client's own
 * screens won't open, and its menu key isn't listed under Controls. Things that only change what's already there keep going: Scoreboard Spoof,
 * Name Protect, Streamer Mode, Bounty and Balance, and every module that does rather than shows.
 *
 * <p>It stays that way, through a restart too, until the menu key and I are pressed together;
 * then everything it switched off comes back on.
 */
public class StealthMode extends Module {
    public StealthMode() {
        super(Category.MISC, "Stealth Mode", "Hides every sign of the client until you press Insert + I");
    }

    @Override
    protected void onEnable() {
        Stealth.set(true);
        for (Module module : Modules.getAll()) {
            if (module.hiddenInStealth()) module.holdForStealth();
        }
        // Out of the menu (or wherever) and back to the game, or the screen it was opened from.
        if (Stealth.isClientScreen(mc.screen)) mc.screen.onClose();
        if (Stealth.isClientScreen(mc.screen)) mc.setScreen(null);
        Stealth.refreshControls();
        Stealth.scrub();
    }

    @Override
    protected void onDisable() {
        Stealth.set(false);
        for (Module module : Modules.getAll()) module.releaseFromStealth();
        Stealth.refreshControls();
        if (inGame()) Notify.send("Stealth Mode", "Off: everything's back", false, false, false);
    }
}
