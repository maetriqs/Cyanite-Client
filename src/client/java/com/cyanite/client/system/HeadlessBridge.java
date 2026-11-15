package com.cyanite.client.system;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.Consumer;

/**
 * The line Cyanite Miner talks to the game on: a socket on this machine only, one JSON object per
 * line each way. The app has to open with the token it started the game with, so nothing else on
 * the machine can drive it. One app at a time; a new one takes over from the last.
 */
final class HeadlessBridge {
    private static final Logger LOG = LoggerFactory.getLogger("Cyanite Miner");
    private static final Gson GSON = new Gson();

    /** Runs one request; {@code reply} may be called later (a screenshot) or not at all. */
    interface Handler {
        void handle(JsonObject request, Consumer<JsonObject> reply);
    }

    private final int port;
    private final String token;
    private final Handler handler;
    private final Object lock = new Object();
    private Socket client;
    private Writer out;

    HeadlessBridge(int port, String token, Handler handler) {
        this.port = port;
        this.token = token;
        this.handler = handler;
    }

    void start() {
        Thread thread = new Thread(this::serve, "Cyanite Miner bridge");
        thread.setDaemon(true);
        thread.start();
    }

    boolean connected() {
        synchronized (lock) {
            return out != null;
        }
    }

    void send(JsonObject json) {
        String line = GSON.toJson(json);
        synchronized (lock) {
            if (out == null) return;
            try {
                out.write(line);
                out.write('\n');
                out.flush();
            } catch (IOException e) {
                drop(client);
            }
        }
    }

    private void serve() {
        if (token == null || token.isEmpty()) {
            LOG.warn("Cyanite Miner: no token given, so nothing can drive this game");
            return;
        }
        try (ServerSocket server = new ServerSocket(port, 4, InetAddress.getLoopbackAddress())) {
            LOG.info("Cyanite Miner: listening on 127.0.0.1:{}", server.getLocalPort());
            while (true) {
                Socket socket = server.accept();
                Thread reader = new Thread(() -> session(socket), "Cyanite Miner bridge reader");
                reader.setDaemon(true);
                reader.start();
            }
        } catch (IOException e) {
            LOG.warn("Cyanite Miner: the bridge stopped ({})", e.toString());
        }
    }

    private void session(Socket socket) {
        try (socket; BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            socket.setTcpNoDelay(true);
            JsonObject hello = parse(in.readLine());
            String given = hello != null && hello.get("token") instanceof JsonElement t && t.isJsonPrimitive() ? t.getAsString() : "";
            if (hello == null || !"auth".equals(text(hello, "cmd"))
                || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8))) {
                LOG.warn("Cyanite Miner: turned away a connection without the right token");
                return;
            }
            synchronized (lock) {
                if (client != null) drop(client);
                client = socket;
                out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            }
            JsonObject ok = new JsonObject();
            ok.add("id", hello.get("id"));
            ok.addProperty("ok", true);
            send(ok);
            String line;
            while ((line = in.readLine()) != null) {
                JsonObject request = parse(line);
                if (request == null) continue;
                JsonElement id = request.get("id");
                handler.handle(request, answer -> {
                    answer.add("id", id);
                    send(answer);
                });
            }
        } catch (IOException ignored) {
            // The app closed or went away: wait for the next one.
        } finally {
            synchronized (lock) {
                if (client == socket) {
                    client = null;
                    out = null;
                }
            }
        }
    }

    /** Must hold the lock. */
    private void drop(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already gone.
        }
        if (client == socket) {
            client = null;
            out = null;
        }
    }

    private static JsonObject parse(String line) {
        if (line == null || line.isBlank()) return null;
        try {
            JsonElement json = JsonParser.parseString(line);
            return json.isJsonObject() ? json.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String text(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }
}
