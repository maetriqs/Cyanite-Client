package com.cyanite.client.modmenu;

import com.cyanite.client.gui.ClickGuiScreen;
import com.cyanite.client.gui.GuiSettings;
import com.cyanite.client.system.Stealth;
import com.cyanite.client.system.Updater;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import com.terraformersmc.modmenu.api.UpdateChannel;
import com.terraformersmc.modmenu.api.UpdateChecker;
import com.terraformersmc.modmenu.api.UpdateInfo;
import net.minecraft.network.chat.Component;

import java.util.concurrent.TimeUnit;

/**
 * Cyanite in Mod Menu, when it's installed (only Mod Menu loads this): its Configure button opens
 * Cyanite's menu, whichever one you use, and Back comes back to the mod list; and its update badge
 * comes from Cyanite's own check of its GitHub releases, not a second one.
 */
public class ModMenuIntegration implements ModMenuApi {
    /** How long to wait for Cyanite's own check before saying nothing. */
    private static final long WAIT_SECONDS = 30;

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        // Stealth keeps the menu shut, the same as the key does.
        return parent -> Stealth.active() ? parent : ClickGuiScreen.open(parent);
    }

    @Override
    public UpdateChecker getUpdateChecker() {
        return () -> {
            Updater.Release release;
            try {
                release = Updater.found().get(WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                release = null;
            }
            return info(release);
        };
    }

    /** What Mod Menu shows for what Cyanite's check found. */
    static UpdateInfo info(Updater.Release release) {
        boolean newer = release != null && release.newer();
        return new UpdateInfo() {
            @Override
            public boolean isUpdateAvailable() {
                return newer;
            }

            @Override
            public Component getUpdateMessage() {
                if (!newer) return null;
                return Component.literal("Cyanite " + release.version() + (GuiSettings.UPDATES.is("Install")
                    ? " is out, and goes in by itself when you close the game" : " is out"));
            }

            @Override
            public String getDownloadLink() {
                return release == null ? null : release.page();
            }

            @Override
            public UpdateChannel getUpdateChannel() {
                return UpdateChannel.RELEASE;
            }
        };
    }
}
