package com.cyanite.client.modules.misc;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.Notify;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.StringSetting;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;

/**
 * Shows "Playing Cyanite Client" on your Discord profile, with where you are and for how long.
 *
 * <p>Discord shows the name of a Discord application. Cyanite's own is built in; to show a
 * different name, make an application at discord.com/developers/applications and put its
 * Application ID in the setting. An image named "cyanite" under Rich Presence > Art Assets
 * gives the icon. Everything runs on its own thread, so a slow or missing Discord
 * never stalls the game.
 */
public class DiscordPresence extends Module {
    private static final long RETRY_MS = 15_000;
    private static final long REFRESH_MS = 30_000;

    /** Cyanite's own Discord application, so "Playing Cyanite Client" works out of the box. */
    private static final String CYANITE_APP_ID = "1561376423988695151";

    private final StringSetting appId = add(new StringSetting("Application ID", CYANITE_APP_ID, 20).placeholder("paste from Discord"));
    private final BoolSetting showServer = add(new BoolSetting("Show server", true));
    private final BoolSetting showModules = add(new BoolSetting("Show module count", true));

    private final String version = FabricLoader.getInstance().getModContainer("cyanite_client")
        .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("");

    private volatile boolean running;
    private volatile String status = "Off";
    private Thread worker;
    private long startedAt;
    private boolean warnedNoId;

    public DiscordPresence() {
        super(Category.MISC, "Discord RPC", "Shows Cyanite on your Discord profile");
    }

    @Override
    public String getInfo() {
        return status;
    }

    @Override
    protected void onEnable() {
        startedAt = System.currentTimeMillis() / 1000;
        running = true;
        warnedNoId = false;
        worker = new Thread(this::run, "Cyanite Discord RPC");
        worker.setDaemon(true);
        worker.start();
    }

    // "Cyanite Client" on your Discord profile would give it away.
    @Override
    public boolean hiddenInStealth() {
        return true;
    }

    @Override
    protected void onDisable() {
        running = false;
        if (worker != null) worker.interrupt();
        worker = null;
        status = "Off";
    }

    private void run() {
        DiscordIpc ipc = null;
        String lastSent = null;
        long lastSentAt = 0;
        try {
            while (running) {
                String id = appId.get().strip();
                if (!id.matches("\\d{15,20}")) {
                    status = "Set ID";
                    if (!warnedNoId) {
                        warnedNoId = true;
                        mc.execute(() -> Notify.send("Discord RPC", "Paste your Discord application's ID into the setting", true, false, false));
                    }
                    Thread.sleep(2000);
                    continue;
                }
                if (ipc == null) {
                    try {
                        ipc = DiscordIpc.connect(id);
                        status = "Connected";
                        lastSent = null;
                    } catch (IOException e) {
                        status = "No Discord";
                        Thread.sleep(RETRY_MS);
                        continue;
                    }
                }
                JsonObject activity = activity();
                String text = activity.toString();
                long now = System.currentTimeMillis();
                if (!text.equals(lastSent) || now - lastSentAt > REFRESH_MS) {
                    try {
                        ipc.setActivity(activity);
                        lastSent = text;
                        lastSentAt = now;
                    } catch (IOException e) {
                        ipc.close();
                        ipc = null;
                        status = "No Discord";
                        continue;
                    }
                }
                Thread.sleep(2000);
            }
        } catch (InterruptedException ignored) {
            // Switched off.
        } finally {
            if (ipc != null) {
                try {
                    ipc.setActivity(null);
                } catch (IOException ignored) {
                }
                ipc.close();
            }
        }
    }

    /** Where you are right now, read from the game without touching its thread. */
    private JsonObject activity() {
        String details;
        var server = mc.getCurrentServer();
        if (mc.level == null) details = "In the menus";
        else if (server != null && !mc.isLocalServer()) details = showServer.get() ? "Playing on " + server.ip : "Playing multiplayer";
        else details = "Playing singleplayer";

        JsonObject activity = new JsonObject();
        activity.addProperty("details", details);
        String state = "Cyanite Client" + (version.isEmpty() ? "" : " v" + version);
        if (showModules.get()) state += " · " + Modules.enabledCount() + " modules on";
        activity.addProperty("state", state);
        JsonObject timestamps = new JsonObject();
        timestamps.addProperty("start", startedAt);
        activity.add("timestamps", timestamps);
        JsonObject assets = new JsonObject();
        assets.addProperty("large_image", "cyanite");
        assets.addProperty("large_text", "Cyanite Client");
        activity.add("assets", assets);
        return activity;
    }
}
