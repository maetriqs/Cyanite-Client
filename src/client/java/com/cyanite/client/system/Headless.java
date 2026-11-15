package com.cyanite.client.system;

import com.cyanite.client.mixin.DisconnectedScreenAccessor;
import com.cyanite.client.modules.world.AutoMine;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.Setting;
import com.cyanite.client.system.setting.SliderSetting;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Cyanite Miner's side of the game. The app starts the game with {@code -Dcyanite.headless=<port>}
 * and a token, and from then on it runs with its window hidden and draws nothing (the loading
 * screen still runs, since it only finishes while drawn), and the app drives it over
 * {@link HeadlessBridge}: it joins servers, switches modules and settings, sends commands, and gets
 * the game's state once a second, plus chat, Cyanite's notifications and disconnects as they come.
 * For a look at the game it draws again for a moment and saves a screenshot.
 */
public final class Headless {
    private static final Logger LOG = LoggerFactory.getLogger("Cyanite Miner");
    /** How fast the game loop runs with nothing drawn: plenty for turning and mining, next to nothing for the CPU. */
    private static final int FPS = 30;
    private static final long STATUS_EVERY = 1000;

    private static HeadlessBridge bridge;
    /** Drawing is on until then (ms), for a screenshot. */
    private static volatile long drawUntil;
    private static final List<Shot> SHOTS = new ArrayList<>();
    /**
     * The app's requests, run on the game thread at the end of each tick. Not mc.execute: leaving a
     * server drops everything queued there, and a request that never answers looks like a hung game.
     */
    private static final ConcurrentLinkedQueue<Runnable> REQUESTS = new ConcurrentLinkedQueue<>();
    private static long lastStatus;
    /** A /stats being read, and who's waiting for it. */
    private static ServerStats statsRead;
    private static Consumer<JsonObject> statsReply;
    private static DisconnectedScreen lastDisconnected;
    private static JsonObject lastDisconnect;
    private static boolean wasDead;

    private record Shot(Path path, long at, Consumer<JsonObject> reply) {
    }

    private Headless() {
    }

    public static boolean active() {
        return HeadlessMode.on();
    }

    /**
     * False while the game should draw: starting up and joining only finish while drawn (the world's
     * loading screen waits for the chunk you're in to be built for drawing, or 30 seconds), and a
     * screenshot on its way.
     */
    public static boolean skipFrame() {
        if (!active()) return false;
        Minecraft mc = Minecraft.getInstance();
        return mc.getOverlay() == null && !(mc.screen instanceof LevelLoadingScreen) && Util.getMillis() > drawUntil;
    }

    public static void init() {
        if (!active()) return;
        bridge = new HeadlessBridge(HeadlessMode.port(), System.getenv("CYANITE_BRIDGE_TOKEN"), Headless::request);
        bridge.start();
        ClientLifecycleEvents.CLIENT_STARTED.register(Headless::setUp);
        ClientTickEvents.END_CLIENT_TICK.register(Headless::tick);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) event("chat", "text", message.getString());
        });
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, receivedAt) ->
            event("chat", "text", message.getString()));
    }

    /** A Cyanite notification (Auto Mine stopping, a player near, ...), passed on to the app. */
    public static void notified(String title, String body) {
        if (!active() || bridge == null) return;
        JsonObject json = new JsonObject();
        json.addProperty("event", "notify");
        json.addProperty("title", title);
        json.addProperty("body", body);
        bridge.send(json);
    }

    private static void event(String name, String key, String value) {
        if (bridge == null || !bridge.connected()) return;
        JsonObject json = new JsonObject();
        json.addProperty("event", name);
        json.addProperty(key, value);
        bridge.send(json);
    }

    /** Quiet, unpaused and light, whatever options.txt said. */
    private static void setUp(Minecraft mc) {
        Options options = mc.options;
        options.pauseOnLostFocus = false;
        options.onboardAccessibility = false;
        options.skipMultiplayerWarning = true;
        options.joinedFirstServer = true;
        options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
        options.framerateLimit().set(FPS);
        options.save();
    }

    private static void tick(Minecraft mc) {
        for (Runnable request; (request = REQUESTS.poll()) != null; ) request.run();
        if (statsRead != null && statsRead.tick(mc)) {
            ServerStats.Result result = statsRead.result();
            statsReply.accept(result == null ? error(statsRead.error()) : ok(stats(result)));
            statsRead = null;
            statsReply = null;
        }
        long now = Util.getMillis();
        for (Iterator<Shot> it = SHOTS.iterator(); it.hasNext(); ) {
            Shot shot = it.next();
            if (now < shot.at()) continue;
            it.remove();
            take(mc, shot);
        }

        if (mc.screen instanceof DisconnectedScreen screen && screen != lastDisconnected) {
            lastDisconnected = screen;
            Component reason = ((DisconnectedScreenAccessor) screen).cyanite$details().reason();
            lastDisconnect = new JsonObject();
            lastDisconnect.addProperty("reason", Disconnects.text(reason));
            lastDisconnect.addProperty("kind", Disconnects.judge(reason).name());
            lastDisconnect.addProperty("onPurpose", Disconnects.leftOnPurpose());
            lastDisconnect.addProperty("at", System.currentTimeMillis());
            if (bridge.connected()) {
                JsonObject json = new JsonObject();
                json.addProperty("event", "disconnected");
                json.add("data", lastDisconnect);
                bridge.send(json);
            }
        }
        boolean dead = mc.player != null && (mc.player.isDeadOrDying() || mc.screen instanceof DeathScreen);
        if (dead && !wasDead) event("died", "text", mc.screen instanceof DeathScreen death ? death.getTitle().getString() : "");
        wasDead = dead;

        if (now - lastStatus >= STATUS_EVERY && bridge.connected()) {
            lastStatus = now;
            JsonObject json = new JsonObject();
            json.addProperty("event", "status");
            json.add("data", status(mc));
            bridge.send(json);
        }
    }

    // ---- Requests

    private static void request(JsonObject request, Consumer<JsonObject> reply) {
        Minecraft mc = Minecraft.getInstance();
        REQUESTS.add(() -> {
            JsonObject answer;
            try {
                answer = run(mc, HeadlessBridge.text(request, "cmd"), request, reply);
            } catch (RuntimeException e) {
                LOG.warn("Cyanite Miner: {} failed: {}", HeadlessBridge.text(request, "cmd"), e.toString());
                answer = error(e.getMessage() == null ? e.toString() : e.getMessage());
            }
            if (answer != null) reply.accept(answer);
        });
    }

    private static JsonObject run(Minecraft mc, String cmd, JsonObject req, Consumer<JsonObject> reply) {
        switch (cmd) {
            case "ping":
                return ok(null);
            case "status":
                return ok(status(mc));
            case "schema":
                return ok(schema());
            case "module": {
                Module module = module(HeadlessBridge.text(req, "name"));
                module.setEnabled(req.get("enabled").getAsBoolean());
                return ok(bool(module.isEnabled()));
            }
            case "set": {
                Module module = module(HeadlessBridge.text(req, "module"));
                Setting<?> setting = setting(module, HeadlessBridge.text(req, "setting"));
                if (setting.isSecret()) throw new IllegalArgumentException("That setting can't be changed from here");
                setting.fromJson(req.get("value"));
                return ok(setting.toJson());
            }
            case "connect": {
                if (mc.level != null) return error("Already on a server");
                if (mc.screen instanceof ConnectScreen) return error("Already connecting");
                String address = HeadlessBridge.text(req, "address").trim();
                if (!ServerAddress.isValidAddress(address)) return error("Not a server address: " + address);
                ServerData data = new ServerData(address, address, ServerData.Type.OTHER);
                // Servers that send a resource pack get it taken, never a question nobody's there to answer.
                data.setResourcePackStatus(ServerData.ServerPackStatus.ENABLED);
                lastDisconnected = null;
                lastDisconnect = null;
                ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(address), data, false, null);
                return ok(null);
            }
            case "disconnect": {
                if (mc.level == null) return ok(bool(false));
                // On purpose, so Auto Mine doesn't rejoin.
                Disconnects.leave("Disconnected");
                return ok(bool(true));
            }
            case "chat": {
                if (mc.player == null) return error("Not on a server");
                String text = HeadlessBridge.text(req, "text").trim();
                if (text.isEmpty()) return error("Nothing to send");
                if (text.startsWith("/")) mc.player.connection.sendCommand(text.substring(1));
                else mc.player.connection.sendChat(text);
                return ok(null);
            }
            case "face": {
                LocalPlayer p = mc.player;
                if (p == null) return error("Not on a server");
                float yaw = req.get("yaw").getAsFloat();
                float pitch = req.has("pitch") ? req.get("pitch").getAsFloat() : p.getXRot();
                p.setYRot(yaw);
                p.setXRot(pitch);
                p.yRotO = yaw;
                p.xRotO = pitch;
                return ok(null);
            }
            case "respawn": {
                if (mc.player == null || !mc.player.isDeadOrDying()) return ok(bool(false));
                mc.player.respawn();
                mc.setScreen(null);
                return ok(bool(true));
            }
            case "screenshot": {
                Path path = Path.of(HeadlessBridge.text(req, "path"));
                long now = Util.getMillis();
                // A couple of seconds of drawing first, so the world round you is built again.
                drawUntil = Math.max(drawUntil, now + 4000);
                SHOTS.add(new Shot(path, now + 2500, reply));
                return null;
            }
            case "stats": {
                // The server's own stats menu (DonutSMP's is a dialog): read and shut, nothing clicked.
                if (mc.player == null) return error("Not on a server");
                if (statsRead != null) return error("Already reading /stats");
                if (mc.screen != null) return error("Something else is open");
                String command = req.has("command") ? HeadlessBridge.text(req, "command").strip() : "stats";
                statsRead = ServerStats.start(mc, command.startsWith("/") ? command.substring(1) : command, 8000);
                statsReply = reply;
                return null;
            }
            case "quit":
                mc.stop();
                return ok(null);
            default:
                return error("Unknown command: " + cmd);
        }
    }

    private static JsonObject stats(ServerStats.Result result) {
        JsonObject json = new JsonObject();
        json.addProperty("kind", result.kind());
        json.addProperty("title", result.title());
        JsonArray lines = new JsonArray();
        result.lines().forEach(lines::add);
        json.add("lines", lines);
        JsonArray buttons = new JsonArray();
        result.buttons().forEach(buttons::add);
        json.add("buttons", buttons);
        JsonArray values = new JsonArray();
        result.values().forEach((label, value) -> {
            JsonArray pair = new JsonArray();
            pair.add(label);
            pair.add(value);
            values.add(pair);
        });
        json.add("values", values);
        return json;
    }

    private static void take(Minecraft mc, Shot shot) {
        Screenshot.takeScreenshot(mc.getMainRenderTarget(), image -> {
            try (NativeImage taken = image) {
                Files.createDirectories(shot.path().toAbsolutePath().getParent());
                taken.writeToFile(shot.path());
                JsonObject data = new JsonObject();
                data.addProperty("path", shot.path().toAbsolutePath().toString());
                data.addProperty("width", taken.getWidth());
                data.addProperty("height", taken.getHeight());
                shot.reply().accept(ok(data));
            } catch (IOException e) {
                shot.reply().accept(error("Couldn't save the screenshot: " + e.getMessage()));
            }
        });
    }

    private static Module module(String name) {
        for (Module module : Modules.getAll()) {
            if (module.name.equalsIgnoreCase(name)) return module;
        }
        throw new IllegalArgumentException("No module called " + name);
    }

    private static Setting<?> setting(Module module, String name) {
        for (Setting<?> setting : module.settings()) {
            if (setting.name.equalsIgnoreCase(name)) return setting;
        }
        throw new IllegalArgumentException(module.name + " has no setting called " + name);
    }

    // ---- What the app is told

    private static JsonObject status(Minecraft mc) {
        JsonObject s = new JsonObject();
        s.addProperty("version", FabricLoader.getInstance().getModContainer("cyanite_client")
            .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?"));
        s.addProperty("state", state(mc));
        s.addProperty("screen", mc.screen == null ? "" : mc.screen.getClass().getSimpleName());
        s.addProperty("fps", mc.getFps());
        ServerData server = mc.getCurrentServer();
        if (server != null) s.addProperty("server", server.ip);
        if (Rejoin.counting()) {
            JsonObject rejoin = new JsonObject();
            rejoin.addProperty("secondsLeft", Rejoin.millisLeft() / 1000);
            rejoin.addProperty("tries", Rejoin.tries());
            rejoin.addProperty("maxTries", Rejoin.maxTries());
            rejoin.addProperty("why", Rejoin.why());
            s.add("rejoin", rejoin);
        }
        if (lastDisconnect != null) s.add("disconnect", lastDisconnect);

        JsonArray on = new JsonArray();
        for (Module module : Modules.getAll()) if (module.isEnabled()) on.add(module.name);
        s.add("modules", on);
        AutoMine mine = Modules.get(AutoMine.class);
        JsonObject autoMine = new JsonObject();
        autoMine.addProperty("enabled", mine.isEnabled());
        autoMine.addProperty("info", mine.isEnabled() ? String.valueOf(mine.getInfo()) : "");
        s.add("autoMine", autoMine);

        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return s;
        JsonObject player = new JsonObject();
        player.addProperty("name", p.getGameProfile().name());
        player.addProperty("x", round(p.getX()));
        player.addProperty("y", round(p.getY()));
        player.addProperty("z", round(p.getZ()));
        player.addProperty("yaw", round(p.getYRot()));
        player.addProperty("pitch", round(p.getXRot()));
        player.addProperty("dimension", mc.level.dimension().identifier().toString());
        player.addProperty("health", round(p.getHealth()));
        player.addProperty("maxHealth", round(p.getMaxHealth()));
        player.addProperty("absorption", round(p.getAbsorptionAmount()));
        player.addProperty("food", p.getFoodData().getFoodLevel());
        player.addProperty("saturation", round(p.getFoodData().getSaturationLevel()));
        player.addProperty("xpLevel", p.experienceLevel);
        player.addProperty("xpProgress", round(p.experienceProgress));
        player.addProperty("air", p.getAirSupply());
        player.addProperty("onGround", p.onGround());
        player.addProperty("inWater", p.isInWater());
        player.addProperty("onFire", p.isOnFire());
        s.add("player", player);

        Inventory inventory = p.getInventory();
        s.add("held", stack(inventory.getSelectedItem()));
        s.add("offhand", stack(p.getOffhandItem()));
        int free = 0;
        Map<String, Integer> counts = new TreeMap<>();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) {
                free++;
                continue;
            }
            counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
        }
        ItemStack off = p.getOffhandItem();
        if (!off.isEmpty()) counts.merge(BuiltInRegistries.ITEM.getKey(off.getItem()).toString(), off.getCount(), Integer::sum);
        JsonObject items = new JsonObject();
        counts.forEach(items::addProperty);
        JsonObject inv = new JsonObject();
        inv.addProperty("free", free);
        inv.add("items", items);
        s.add("inventory", inv);

        JsonArray players = new JsonArray();
        for (AbstractClientPlayer other : mc.level.players()) {
            if (other == p) continue;
            JsonObject o = new JsonObject();
            o.addProperty("name", other.getGameProfile().name());
            o.addProperty("distance", Math.round(other.distanceTo(p)));
            players.add(o);
        }
        s.add("players", players);
        if (mc.getConnection() != null) {
            s.addProperty("online", mc.getConnection().getOnlinePlayers().size());
            PlayerInfo info = mc.getConnection().getPlayerInfo(p.getUUID());
            if (info != null) s.addProperty("ping", info.getLatency());
        }
        s.add("sidebar", sidebar(mc.level.getScoreboard()));
        return s;
    }

    private static String state(Minecraft mc) {
        if (mc.getOverlay() != null) return "loading";
        if (mc.level != null && mc.player != null) {
            if (mc.player.isDeadOrDying() || mc.screen instanceof DeathScreen) return "dead";
            return mc.screen instanceof LevelLoadingScreen ? "joining" : "playing";
        }
        if (Rejoin.counting()) return "rejoining";
        if (mc.screen instanceof DisconnectedScreen) return "disconnected";
        if (mc.screen instanceof ConnectScreen || mc.getConnection() != null || mc.screen instanceof LevelLoadingScreen) return "connecting";
        return "menu";
    }

    /** The sidebar's lines, top to bottom, the way the game shows them. */
    private static JsonArray sidebar(Scoreboard scoreboard) {
        JsonArray lines = new JsonArray();
        Objective objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (objective == null) return lines;
        lines.add(objective.getDisplayName().getString());
        scoreboard.listPlayerScores(objective).stream()
            .filter(entry -> !entry.isHidden())
            .sorted(Comparator.comparingInt(PlayerScoreEntry::value).reversed()
                .thenComparing(PlayerScoreEntry::owner, String.CASE_INSENSITIVE_ORDER))
            .limit(15)
            .forEach(entry -> {
                PlayerTeam team = scoreboard.getPlayersTeam(entry.owner());
                lines.add(PlayerTeam.formatNameForTeam(team, entry.ownerName()).getString());
            });
        return lines;
    }

    private static JsonObject stack(ItemStack stack) {
        JsonObject json = new JsonObject();
        if (stack.isEmpty()) return json;
        json.addProperty("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        json.addProperty("name", stack.getHoverName().getString());
        json.addProperty("count", stack.getCount());
        if (stack.isDamageableItem()) {
            json.addProperty("damage", stack.getDamageValue());
            json.addProperty("maxDamage", stack.getMaxDamage());
        }
        return json;
    }

    /** Every module and its settings, so the app can show them without knowing them in advance. */
    private static JsonObject schema() {
        JsonArray modules = new JsonArray();
        for (Module module : Modules.getAll()) {
            JsonObject m = new JsonObject();
            m.addProperty("name", module.name);
            m.addProperty("category", module.category.title);
            m.addProperty("description", module.description);
            m.addProperty("enabled", module.isEnabled());
            JsonArray settings = new JsonArray();
            for (Setting<?> setting : module.settings()) {
                if (setting.isSecret()) continue;
                JsonObject s = new JsonObject();
                s.addProperty("name", setting.name);
                s.addProperty("type", setting.getClass().getSimpleName().replace("Setting", "").toLowerCase(Locale.ROOT));
                s.add("value", setting.toJson());
                s.add("default", setting.defaultJson());
                if (setting.isRisky()) s.addProperty("risky", true);
                if (setting instanceof SliderSetting slider) {
                    s.addProperty("min", slider.min);
                    s.addProperty("max", slider.max);
                    s.addProperty("step", slider.step);
                    s.addProperty("display", slider.display());
                }
                if (setting instanceof ModeSetting mode) {
                    JsonArray modes = new JsonArray();
                    mode.modes.forEach(modes::add);
                    s.add("modes", modes);
                }
                settings.add(s);
            }
            m.add("settings", settings);
            modules.add(m);
        }
        JsonObject json = new JsonObject();
        json.add("modules", modules);
        return json;
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static JsonElement bool(boolean value) {
        return new com.google.gson.JsonPrimitive(value);
    }

    private static JsonObject ok(JsonElement data) {
        JsonObject json = new JsonObject();
        json.addProperty("ok", true);
        if (data != null) json.add("data", data);
        return json;
    }

    private static JsonObject error(String message) {
        JsonObject json = new JsonObject();
        json.addProperty("ok", false);
        json.addProperty("error", message);
        return json;
    }
}
