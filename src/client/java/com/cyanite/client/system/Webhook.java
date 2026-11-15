package com.cyanite.client.system;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Posting to a Discord webhook, for the modules that can (Base Guard, Base Alert): embeds, with
 * pictures attached if there are any. It's sent off the game's thread, and whatever goes wrong is
 * told in chat and logged as a warning, never thrown.
 */
public final class Webhook {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Pattern DISCORD = Pattern.compile("https://(?:\\w+\\.)?discord(?:app)?\\.com/api/webhooks/\\d+/[\\w-]+");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** A file sent with the message: an embed shows it as {@code attachment://name}. */
    public record File(String name, byte[] bytes) {
    }

    private Webhook() {
    }

    /** Whether {@code url} is a Discord webhook. */
    public static boolean valid(String url) {
        if (DISCORD.matcher(url).matches()) return true;
        // Lets the tests aim it at a stand-in for Discord on this machine; never outside the dev environment.
        return FabricLoader.getInstance().isDevelopmentEnvironment() && url.startsWith("http://127.0.0.1:");
    }

    /** An embed with {@code {name, value}} fields, stamped with the time now. */
    public static JsonObject embed(String title, String description, int color, List<String[]> fields, String footer) {
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
        JsonObject foot = new JsonObject();
        foot.addProperty("text", footer);
        embed.add("footer", foot);
        embed.addProperty("timestamp", Instant.now().toString());
        return embed;
    }

    /** A message from {@code username}: {@code content} over {@code embeds}, pinging only who {@code allowedMentions} lets it. */
    public static JsonObject message(String username, String content, JsonObject allowedMentions, List<JsonObject> embeds) {
        JsonObject message = new JsonObject();
        message.addProperty("username", username);
        JsonArray list = new JsonArray();
        for (JsonObject embed : embeds) list.add(embed);
        message.add("embeds", list);
        message.addProperty("content", content);
        message.add("allowed_mentions", allowedMentions);
        return message;
    }

    /** Pings nobody, whatever the text says. */
    public static JsonObject noPings() {
        JsonObject allowed = new JsonObject();
        allowed.add("parse", new JsonArray());
        return allowed;
    }

    /**
     * Posts {@code message} and {@code files} to {@code url}, off the game's thread. If Discord doesn't
     * take it, says so in chat as {@code who}; if it does and {@code sent} isn't null, says that.
     */
    public static void post(String who, String url, JsonObject message, List<File> files, String sent) {
        Thread t = new Thread(() -> {
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "Cyanite-Client (" + who + ")")
                    .timeout(Duration.ofSeconds(30));
                if (files.isEmpty()) {
                    request.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(message.toString(), StandardCharsets.UTF_8));
                } else {
                    String boundary = "cyanite" + UUID.randomUUID().toString().replace("-", "");
                    ByteArrayOutputStream body = new ByteArrayOutputStream();
                    body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"payload_json\"\r\nContent-Type: application/json\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    body.writeBytes(message.toString().getBytes(StandardCharsets.UTF_8));
                    for (int i = 0; i < files.size(); i++) {
                        File file = files.get(i);
                        body.writeBytes(("\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"files[" + i + "]\"; filename=\"" + file.name()
                            + "\"\r\nContent-Type: " + type(file.name()) + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                        body.writeBytes(file.bytes());
                    }
                    body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                    request.header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
                }
                HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
                int code = response.statusCode();
                if (code / 100 != 2) {
                    LOGGER.warn("{}: Discord answered {}: {}", who, code, response.body());
                    tell(who, code == 404 || code == 401 ? "That webhook doesn't work any more (it was deleted?)"
                        : code == 413 ? "Discord didn't take the message: too big" : "Discord didn't take the message (" + code + ")");
                } else if (sent != null) {
                    tell(who, sent);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.warn("{}: stopped while sending to Discord", who);
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("{}: couldn't reach Discord", who, e);
                // A refused connection comes without a message.
                tell(who, "Couldn't reach Discord: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            }
        }, "Cyanite " + who);
        t.setDaemon(true);
        t.start();
    }

    private static String type(String name) {
        return name.endsWith(".png") ? "image/png" : "application/octet-stream";
    }

    private static void tell(String who, String text) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) Notify.send(who, text, true, false, false);
        });
    }
}
