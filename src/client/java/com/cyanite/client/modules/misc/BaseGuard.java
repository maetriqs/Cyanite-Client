package com.cyanite.client.modules.misc;

import com.cyanite.client.config.Config;
import com.cyanite.client.modules.combat.AntiBot;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Notify;
import com.cyanite.client.system.Stealth;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.SliderSetting;
import com.cyanite.client.system.setting.StringSetting;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Base Guard: for while you're AFK at your base. When a player comes within range it can send
 * who it was to your Discord through a webhook (pinging you, a role or everyone), log you out, or
 * both. The webhook is a secret setting: hidden in the menu and never saved into profiles.
 *
 * <p>Only real players count: ones in the tab list with a proper account (NPCs and the fake
 * players some servers spawn aren't), and Anti Bot's checks too while it's on. It waits a moment
 * after you join or switch it on before it starts watching, so the world has loaded in.
 */
public class BaseGuard extends Module {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Pattern WEBHOOK = Pattern.compile("https://(?:\\w+\\.)?discord(?:app)?\\.com/api/webhooks/\\d+/[\\w-]+");
    private static final Pattern ID = Pattern.compile("\\d{15,21}");
    private static final int ALERT_COLOR = 0xFF4D57;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final StringSetting webhook = add(new StringSetting("Webhook", "", 200).placeholder("paste a Discord webhook URL").secret());
    private final ModeSetting ping = add(new ModeSetting("Ping", "@everyone", List.of("Nobody", "@everyone", "@here", "User", "Role")));
    private final StringSetting pingId = add(new StringSetting("Ping ID", "", 21).placeholder("user or role ID, for User/Role"));
    private final BoolSetting logOut = add(new BoolSetting("Log out", true));
    private final SliderSetting range = add(new SliderSetting("Range", 256, 8, 256, 4)
        .format(v -> v >= 256 ? "Anyone you can see" : (int) (double) v + " blocks"));
    private final StringSetting ignore = add(new StringSetting("Ignore", "", 200).placeholder("names, comma separated"));
    private final SliderSetting armAfter = add(new SliderSetting("Start after", 5, 0, 60, 1).format(v -> (int) (double) v + " s"));
    private final SliderSetting repeat = add(new SliderSetting("Repeat after", 120, 10, 600, 10)
        .format(v -> (int) (double) v + " s"));
    private final BoolSetting sendServer = add(new BoolSetting("Send server", true));
    private final BoolSetting sendCoords = add(new BoolSetting("Send coords", false));
    private final BoolSetting screenshot = add(new BoolSetting("Send screenshot", false));
    private final BoolSetting sound = add(new BoolSetting("Sound", true));
    private final BoolSetting test = add(new BoolSetting("Send test message", false));

    private ClientLevel lastLevel;
    private long armedAt;
    private boolean toldNoWebhook;
    /** When each player was last reported, so the same one doesn't flood the channel. */
    private final Map<UUID, Long> reported = new HashMap<>();

    public BaseGuard() {
        super(Category.MISC, "Base Guard", "While you're AFK: a player comes near, it pings your Discord with who, and can log you out");
        test.onChange(() -> {
            if (!test.get()) return;
            test.set(false);
            if (!validWebhook(webhook.get().strip())) {
                Notify.send("Base Guard", "Paste a Discord webhook URL into Webhook first", true, false, false);
                return;
            }
            send(embed("Base Guard test", "If you can see this, Base Guard can reach this channel.", 0x5DADFF, List.of()), null, true);
        });
    }

    @Override
    public String getInfo() {
        if (!validWebhook(webhook.get().strip())) return logOut.get() ? "Log out only" : "No webhook";
        long wait = armedAt - System.currentTimeMillis();
        return wait > 0 ? "Starting " + (wait / 1000 + 1) + "s" : "Watching";
    }

    @Override
    protected void onEnable() {
        lastLevel = null;
        toldNoWebhook = false;
        reported.clear();
    }

    private static boolean validWebhook(String url) {
        if (WEBHOOK.matcher(url).matches()) return true;
        // Lets the tests aim it at a stand-in for Discord on this machine; never outside the dev environment.
        return FabricLoader.getInstance().isDevelopmentEnvironment() && url.startsWith("http://127.0.0.1:");
    }

    @Override
    public void onTick() {
        long now = System.currentTimeMillis();
        if (mc.level != lastLevel) {
            // Just joined (or came back): give the world a moment to load in first.
            lastLevel = mc.level;
            armedAt = now + (long) (armAfter.get() * 1000);
            return;
        }
        if (now < armedAt) return;
        boolean hasWebhook = validWebhook(webhook.get().strip());
        if (!hasWebhook && !toldNoWebhook) {
            toldNoWebhook = true;
            Notify.send("Base Guard", webhook.isBlank() ? "No webhook set: it'll only log you out" : "That webhook URL doesn't look right: it'll only log you out", true, false, false);
        }

        List<Player> seen = new ArrayList<>();
        for (Player other : mc.level.players()) {
            if (other == mc.player || other.isSpectator() || !real(other) || ignored(other.getGameProfile().name())) continue;
            if (range.get() < 256 && other.distanceTo(mc.player) > range.get()) continue;
            seen.add(other);
        }
        if (seen.isEmpty()) return;
        seen.sort((a, b) -> Float.compare(a.distanceTo(mc.player), b.distanceTo(mc.player)));

        // Only players not reported lately make a message; logging out doesn't wait for that.
        List<Player> fresh = new ArrayList<>();
        for (Player p : seen) {
            Long last = reported.get(p.getUUID());
            if (last == null || now - last > repeat.get() * 1000) fresh.add(p);
        }
        String names = describe(seen);
        if (!fresh.isEmpty()) {
            for (Player p : fresh) reported.put(p.getUUID(), now);
            Notify.send("Base Guard", names + (logOut.get() ? ": logging out" : ""), true, sound.get(), true);
            if (hasWebhook) alert(fresh, seen);
        }
        if (logOut.get() && mc.getConnection() != null) {
            mc.getConnection().getConnection().disconnect(Stealth.disconnectReason("Cyanite Base Guard\n\n" + names + " came near"));
        }
    }

    /** In the tab list with a real account, and not something Anti Bot would call fake. */
    private boolean real(Player player) {
        var connection = mc.getConnection();
        PlayerInfo info = connection == null ? null : connection.getPlayerInfo(player.getUUID());
        int version = player.getUUID().version();
        return info != null && (version == 3 || version == 4) && !AntiBot.isBot(player);
    }

    private boolean ignored(String name) {
        for (String entry : ignore.get().split(",")) {
            if (!entry.isBlank() && entry.strip().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private String describe(List<Player> players) {
        List<String> parts = new ArrayList<>();
        for (Player p : players) parts.add(p.getGameProfile().name() + " (" + Math.round(p.distanceTo(mc.player)) + "m)");
        return String.join(", ", parts);
    }

    // ---- The Discord message

    private void alert(List<Player> fresh, List<Player> all) {
        List<String[]> fields = new ArrayList<>();
        StringBuilder who = new StringBuilder();
        for (Player p : fresh) who.append("**").append(p.getGameProfile().name()).append("**  ").append(Math.round(p.distanceTo(mc.player))).append("m away\n");
        fields.add(new String[] {fresh.size() == 1 ? "Player" : "Players", who.toString().strip()});
        if (all.size() > fresh.size()) fields.add(new String[] {"Also nearby", describe(all.stream().filter(p -> !fresh.contains(p)).toList())});
        if (sendServer.get()) fields.add(new String[] {"Server", server()});
        if (sendCoords.get()) {
            BlockPos pos = mc.player.blockPosition();
            fields.add(new String[] {"Where", pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + " (" + mc.level.dimension().identifier().getPath() + ")"});
        }
        fields.add(new String[] {"You", logOut.get() ? "Logged out" : "Still online"});
        String title = fresh.size() == 1 ? fresh.get(0).getGameProfile().name() + " is at your base" : fresh.size() + " players are at your base";
        JsonObject message = embed(title, null, ALERT_COLOR, fields);
        if (screenshot.get()) {
            // A copy of the screen as it was, taken before logging out changes it.
            Screenshot.takeScreenshot(mc.getMainRenderTarget(), image -> {
                Thread t = new Thread(() -> {
                    byte[] png = null;
                    Path file = Config.dir().resolve("base-guard.png");
                    try (image) {
                        Files.createDirectories(file.getParent());
                        image.writeToFile(file);
                        png = Files.readAllBytes(file);
                    } catch (IOException e) {
                        LOGGER.warn("Base Guard: couldn't take the screenshot", e);
                    } finally {
                        try {
                            Files.deleteIfExists(file);
                        } catch (IOException ignored) {
                            // Gone next time.
                        }
                    }
                    send(message, png, false);
                }, "Cyanite Base Guard");
                t.setDaemon(true);
                t.start();
            });
        } else {
            send(message, null, false);
        }
    }

    private String server() {
        if (mc.getCurrentServer() != null) return mc.getCurrentServer().ip;
        return mc.getSingleplayerServer() != null ? "Singleplayer" : "Unknown";
    }

    private JsonObject embed(String title, String description, int color, List<String[]> fields) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", title);
        if (description != null) embed.addProperty("description", description);
        embed.addProperty("color", color);
        JsonArray list = new JsonArray();
        for (String[] f : fields) {
            JsonObject field = new JsonObject();
            field.addProperty("name", f[0]);
            field.addProperty("value", f[1].isEmpty() ? "-" : f[1]);
            field.addProperty("inline", false);
            list.add(field);
        }
        if (!list.isEmpty()) embed.add("fields", list);
        JsonObject footer = new JsonObject();
        footer.addProperty("text", "Cyanite Base Guard");
        embed.add("footer", footer);
        embed.addProperty("timestamp", Instant.now().toString());

        JsonObject message = new JsonObject();
        message.addProperty("username", "Base Guard");
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        message.add("embeds", embeds);
        return message;
    }

    /** Adds the ping and posts it, off the game's thread. A test message never pings. */
    private void send(JsonObject message, byte[] png, boolean isTest) {
        String url = webhook.get().strip();
        JsonObject allowed = new JsonObject();
        JsonArray parse = new JsonArray();
        String content = "";
        String id = pingId.get().strip();
        if (!isTest) {
            switch (ping.get()) {
                case "@everyone", "@here" -> {
                    content = ping.get();
                    parse.add("everyone");
                }
                case "User" -> {
                    if (ID.matcher(id).matches()) {
                        content = "<@" + id + ">";
                        JsonArray users = new JsonArray();
                        users.add(id);
                        allowed.add("users", users);
                    }
                }
                case "Role" -> {
                    if (ID.matcher(id).matches()) {
                        content = "<@&" + id + ">";
                        JsonArray roles = new JsonArray();
                        roles.add(id);
                        allowed.add("roles", roles);
                    }
                }
                default -> {
                }
            }
        }
        allowed.add("parse", parse);
        message.addProperty("content", content);
        message.add("allowed_mentions", allowed);

        Thread t = new Thread(() -> {
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "Cyanite-Client (Base Guard)")
                    .timeout(Duration.ofSeconds(20));
                if (png == null) {
                    request.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(message.toString(), StandardCharsets.UTF_8));
                } else {
                    JsonArray embeds = message.getAsJsonArray("embeds");
                    JsonObject image = new JsonObject();
                    image.addProperty("url", "attachment://base-guard.png");
                    embeds.get(0).getAsJsonObject().add("image", image);
                    String boundary = "cyanite" + UUID.randomUUID().toString().replace("-", "");
                    ByteArrayOutputStream body = new ByteArrayOutputStream();
                    body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"payload_json\"\r\nContent-Type: application/json\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    body.writeBytes(message.toString().getBytes(StandardCharsets.UTF_8));
                    body.writeBytes(("\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"files[0]\"; filename=\"base-guard.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    body.writeBytes(png);
                    body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                    request.header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
                }
                HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
                int code = response.statusCode();
                if (code / 100 != 2) {
                    LOGGER.warn("Base Guard: Discord answered {}: {}", code, response.body());
                    tell(code == 404 || code == 401 ? "That webhook doesn't work any more (it was deleted?)" : "Discord didn't take the message (" + code + ")");
                } else if (isTest) {
                    tell("Test message sent. Check the channel.");
                }
            } catch (IOException | InterruptedException | IllegalArgumentException e) {
                LOGGER.warn("Base Guard: couldn't reach Discord", e);
                tell("Couldn't reach Discord: " + e.getMessage());
            }
        }, "Cyanite Base Guard");
        t.setDaemon(true);
        t.start();
    }

    private void tell(String text) {
        mc.execute(() -> {
            if (mc.player != null) Notify.send("Base Guard", text, true, false, false);
        });
    }
}
