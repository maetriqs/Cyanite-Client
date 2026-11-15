package com.cyanite.client.system;

import com.cyanite.client.gui.GuiSettings;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.ProfileKeyPair;
import net.minecraft.world.entity.player.ProfilePublicKey;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * How many people have Cyanite open, from Cyanite's online counter (a Cloudflare Worker).
 *
 * <p>The counter only counts real accounts. Every Microsoft account gets a chat key from Mojang, with
 * Mojang's signature saying which account it belongs to (servers check it on every signed chat message).
 * The counter hands out a challenge, the game signs it with that key and sends the key's certificate, and
 * the counter checks both against Mojang's public keys. Nothing secret leaves the game: no access token,
 * no private key. The counter keeps one count per account and a few accounts per connection at most, so
 * it can't be pumped up with made-up players. An account without a chat key (an offline one) isn't counted
 * but still sees the count. After that the game pings every few minutes, and says goodbye when it closes
 * or the count's switched off.
 */
public final class Online {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** The counter. A dev run only talks to one named with {@code -Dcyanite.online=<url>}, so tests count nobody. */
    private static final String SERVER = "https://cyanite-online.wsgheadphones.workers.dev";
    /** How long an account the counter turned away waits before trying again (it still sees the count meanwhile). */
    private static final long RETRY_MS = 30 * 60_000L;
    private static final String AGENT = "Cyanite-Client/" + FabricLoader.getInstance().getModContainer("cyanite_client")
        .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("?");

    private static volatile int count = -1;
    private static volatile boolean stopping;
    private static volatile String token;
    private static String base;
    private static UUID owner;
    private static long joinAfter;
    private static Thread thread;

    private Online() {
    }

    /** "12 online", or null with the count off or before the counter has answered. */
    public static String label() {
        int n = count;
        return n < 0 || !GuiSettings.ONLINE.get() ? null : n + " online";
    }

    /** Starts counting in the background. Called once the game's up. */
    public static void start() {
        String url = System.getProperty("cyanite.online", FabricLoader.getInstance().isDevelopmentEnvironment() ? "" : SERVER);
        if (url.isBlank()) return;
        base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        thread = new Thread(Online::loop, "Cyanite online");
        thread.setDaemon(true);
        thread.start();
    }

    /** The game's closing: stops being counted now rather than when the counter stops hearing from it. */
    public static void stop() {
        if (thread == null) return;
        stopping = true;
        thread.interrupt();
        try {
            thread.join(1000);
            leave(Duration.ofSeconds(2));
        } catch (Exception ignored) {
            // It drops off the count on its own a few minutes later.
        }
    }

    private static void loop() {
        long due = 0;
        int failures = 0;
        while (!stopping) {
            try {
                boolean on = GuiSettings.ONLINE.get();
                UUID who = Minecraft.getInstance().getUser().getProfileId();
                if (token != null && (!on || !who.equals(owner))) {
                    // Switched off, or switched to another account, which has to prove itself.
                    leave(Duration.ofSeconds(10));
                    joinAfter = 0;
                    due = 0;
                }
                if (!on) count = -1;
                if (!on || System.currentTimeMillis() < due) {
                    Thread.sleep(1000);
                    continue;
                }
                int next = token != null ? ping() : System.currentTimeMillis() >= joinAfter ? join() : look();
                failures = 0;
                due = System.currentTimeMillis() + Math.clamp(next, 60, 1800) * 1000L;
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                if (failures++ == 0) LOGGER.warn("Couldn't reach Cyanite's online counter: {}", e.toString());
                due = System.currentTimeMillis() + Math.min(30 * 60_000L, 30_000L << Math.min(failures, 6));
            }
        }
    }

    /** Proves the account with its Mojang-signed chat key and starts being counted; the seconds until the next ping. */
    private static int join() throws Exception {
        Minecraft mc = Minecraft.getInstance();
        UUID who = mc.getUser().getProfileId();
        // On the game thread: switching accounts swaps the key manager, and fetching a key isn't thread-safe.
        Optional<ProfileKeyPair> keys = CompletableFuture.supplyAsync(() -> mc.getProfileKeyPairManager().prepareKeyPair(), mc)
            .thenCompose(future -> future).get(60, TimeUnit.SECONDS);
        if (keys.isEmpty()) {
            LOGGER.info("Not counted online: this account has no chat key from Mojang (offline, or Mojang wouldn't give one)");
            joinAfter = System.currentTimeMillis() + RETRY_MS;
            return look();
        }
        ProfileKeyPair pair = keys.get();
        ProfilePublicKey.Data certificate = pair.publicKey().data();

        Answer challenge = call("/v1/challenge", "{}", Duration.ofSeconds(10));
        if (!(challenge.json.get("challenge") instanceof JsonElement id) || !id.isJsonPrimitive()) throw new IOException("no challenge");
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(pair.privateKey());
        signer.update(("cyanite-online:" + id.getAsString()).getBytes(StandardCharsets.UTF_8));

        Base64.Encoder base64 = Base64.getEncoder();
        JsonObject body = new JsonObject();
        body.addProperty("uuid", who.toString().replace("-", ""));
        body.addProperty("expiresAt", certificate.expiresAt().toEpochMilli());
        body.addProperty("key", base64.encodeToString(certificate.key().getEncoded()));
        body.addProperty("keySignature", base64.encodeToString(certificate.keySignature()));
        body.addProperty("challenge", id.getAsString());
        body.addProperty("signature", base64.encodeToString(signer.sign()));
        Answer joined = call("/v1/join", body.toString(), Duration.ofSeconds(15));
        if (joined.status == 200 && joined.json.get("token") instanceof JsonElement t && t.isJsonPrimitive()) {
            token = t.getAsString();
            owner = who;
            LOGGER.info("Counted online ({} with Cyanite open)", count);
            return joined.next();
        }
        // 401: the counter didn't take the key (run out, or Mojang has new keys the counter doesn't know yet).
        // 429: this connection already has its accounts counted, or tried too often.
        if (joined.status != 401 && joined.status != 429) throw new IOException("the counter answered " + joined.status);
        LOGGER.info("Not counted online: {}", joined.status == 401 ? "the counter didn't accept this account's chat key" : "too many accounts on this connection");
        joinAfter = System.currentTimeMillis() + RETRY_MS;
        return joined.json.has("online") ? joined.next() : look();
    }

    private static int ping() throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("token", token);
        Answer answer = call("/v1/ping", body.toString(), Duration.ofSeconds(10));
        if (answer.status == 200) return answer.next();
        // 401: the token's run out, so the account proves itself again. 429: dropped off and its connection is full now.
        if (answer.status != 401 && answer.status != 429) throw new IOException("the counter answered " + answer.status);
        token = null;
        if (answer.status == 429) joinAfter = System.currentTimeMillis() + RETRY_MS;
        return 0;
    }

    /** Just the count, for an account that isn't counted. */
    private static int look() throws Exception {
        Answer answer = call("/v1/count", null, Duration.ofSeconds(10));
        if (answer.status != 200) throw new IOException("the counter answered " + answer.status);
        return answer.next();
    }

    private static void leave(Duration timeout) throws IOException, InterruptedException {
        String t = token;
        token = null;
        owner = null;
        if (t == null) return;
        JsonObject body = new JsonObject();
        body.addProperty("token", t);
        call("/v1/leave", body.toString(), timeout);
    }

    private record Answer(int status, JsonObject json) {
        /** When the counter wants the next ping, in seconds. */
        int next() {
            return json.get("next") instanceof JsonElement n && n.isJsonPrimitive() && n.getAsJsonPrimitive().isNumber() ? n.getAsInt() : 180;
        }
    }

    /** GET when there's no body, otherwise POSTs it. Whatever count comes back is kept. */
    private static Answer call(String path, String body, Duration timeout) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
            .header("User-Agent", AGENT)
            .timeout(timeout);
        if (body == null) request.GET();
        else request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = Updater.HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 500) throw new IOException("the counter answered " + response.statusCode());
        JsonObject json;
        try {
            json = JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException e) {
            json = new JsonObject();
        }
        if (json.get("online") instanceof JsonElement n && n.isJsonPrimitive() && n.getAsJsonPrimitive().isNumber()) count = n.getAsInt();
        return new Answer(response.statusCode(), json);
    }
}
