package com.cyanite.client.gui;

import com.cyanite.client.config.Config;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * What the title and loading screens remember from one start to the next, in {@code cyanite/home.json}.
 * Nothing in it is precious: an unreadable file just starts over.
 */
final class HomeData {
    private static JsonObject json;

    private HomeData() {
    }

    private static Path file() {
        return Config.dir().resolve("home.json");
    }

    private static JsonObject json() {
        if (json == null) {
            json = new JsonObject();
            Path file = file();
            try {
                if (Files.exists(file) && JsonParser.parseString(Files.readString(file)) instanceof JsonObject read) json = read;
            } catch (Exception ignored) {
                // Starts over: it's only what the screens remember.
            }
        }
        return json;
    }

    /** How long the last few starts spent on the loading screen, in milliseconds; 0 before the first. */
    static long startupMs() {
        try {
            return Math.max(0, json().get("startupMs").getAsLong());
        } catch (RuntimeException missingOrNotANumber) {
            return 0;
        }
    }

    /** This start's loading screen took {@code ms}: the next one paces its bar by it. */
    static void startupTook(long ms) {
        if (ms <= 0) return;
        long before = startupMs();
        // Half and half with the starts before, so one slow start (a new mod, an update) doesn't throw it.
        json().addProperty("startupMs", before <= 0 ? ms : (before + ms) / 2);
        save();
    }

    /** The server you last joined, for the title screen's rejoin row; null before the first (or after singleplayer only). */
    record Server(String name, String ip) {
    }

    static Server lastServer() {
        try {
            JsonObject server = json().getAsJsonObject("lastServer");
            String ip = server.get("ip").getAsString();
            return ip.isBlank() ? null : new Server(server.get("name").getAsString(), ip);
        } catch (RuntimeException missing) {
            return null;
        }
    }

    /** Joined a server (not singleplayer or Realms). */
    static void joined(String name, String ip) {
        if (ip == null || ip.isBlank()) return;
        Server last = lastServer();
        if (last != null && last.ip().equals(ip) && last.name().equals(name)) return;
        JsonObject server = new JsonObject();
        server.addProperty("name", name == null || name.isBlank() ? ip : name);
        server.addProperty("ip", ip);
        json().add("lastServer", server);
        save();
    }

    /** The Cyanite version that ran before this one, null on the first start with this file. */
    static String lastVersion() {
        try {
            return json().get("lastVersion").getAsString();
        } catch (RuntimeException missing) {
            return null;
        }
    }

    static void ran(String version) {
        if (version.equals(lastVersion())) return;
        json().addProperty("lastVersion", version);
        save();
    }

    private static void save() {
        Config.write(file(), json());
    }
}
