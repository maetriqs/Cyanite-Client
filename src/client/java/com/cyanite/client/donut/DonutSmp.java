package com.cyanite.client.donut;

import com.cyanite.client.config.Profiles;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Everything behind the Donut SMP tab: the server, its command shortcuts and the join profile. */
public final class DonutSmp {
    public static final String NAME = "Donut SMP";
    public static final String NO_PROFILE = "None";
    private static final List<String> DEFAULT_COMMANDS = List.of("/spawn", "/home", "/rtp", "/shop", "/ah", "/sell");
    private static final Logger LOGGER = LogUtils.getLogger();

    private static String address = "";
    private static final List<String> COMMANDS = new ArrayList<>(DEFAULT_COMMANDS);
    private static String joinProfile = NO_PROFILE;
    private static long sessionStart;

    private DonutSmp() {
    }

    public static String address() {
        return address;
    }

    public static void setAddress(String value) {
        address = value.strip();
    }

    public static List<String> commands() {
        return COMMANDS;
    }

    public static String joinProfile() {
        return joinProfile;
    }

    public static void setJoinProfile(String profile) {
        joinProfile = profile;
    }

    /** True while connected to the saved address. */
    public static boolean isConnected() {
        ServerData current = Minecraft.getInstance().getCurrentServer();
        return current != null && !address.isEmpty() && normalize(current.ip).equals(normalize(address));
    }

    /** Milliseconds since joining the server, or 0 when not on it. */
    public static long sessionMillis() {
        return isConnected() && sessionStart > 0 ? System.currentTimeMillis() - sessionStart : 0;
    }

    public static boolean canJoin() {
        return !address.isEmpty() && ServerAddress.isValidAddress(address) && !isConnected();
    }

    /** Leaves the current world (saving it, if singleplayer) and connects to the server. */
    public static void join() {
        if (!canJoin()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) mc.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);

        ServerData data = new ServerData(NAME, address, ServerData.Type.OTHER);
        ConnectScreen.startConnecting(new JoinMultiplayerScreen(new TitleScreen()), mc, ServerAddress.parseString(address), data, false, null);
    }

    /** Sends a shortcut as if typed in chat. */
    public static boolean run(String command) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return false;
        String text = command.strip();
        if (text.startsWith("/")) text = text.substring(1);
        if (text.isEmpty()) return false;
        mc.getConnection().sendCommand(text);
        return true;
    }

    public static void addCommand(String command) {
        String text = command.strip();
        if (text.isEmpty()) return;
        if (!text.startsWith("/")) text = "/" + text;
        if (!COMMANDS.contains(text)) COMMANDS.add(text);
    }

    public static void removeCommand(String command) {
        COMMANDS.remove(command);
    }

    /** Fabric JOIN event. */
    public static void onJoin() {
        if (!isConnected()) return;
        sessionStart = System.currentTimeMillis();

        if (!joinProfile.equals(NO_PROFILE) && Profiles.list().stream().anyMatch(p -> p.name().equals(joinProfile))) {
            Profiles.load(joinProfile);
            LOGGER.info("Loaded profile {} for {}", joinProfile, NAME);
        }
    }

    /** Fabric DISCONNECT event. */
    public static void onDisconnect() {
        sessionStart = 0;
    }

    public static JsonObject save() {
        JsonObject json = new JsonObject();
        json.addProperty("address", address);
        json.addProperty("joinProfile", joinProfile);
        JsonArray commands = new JsonArray();
        COMMANDS.forEach(commands::add);
        json.add("commands", commands);
        return json;
    }

    public static void load(JsonObject json) {
        if (json.get("address") instanceof JsonElement value && value.isJsonPrimitive()) address = value.getAsString();
        if (json.get("joinProfile") instanceof JsonElement value && value.isJsonPrimitive()) joinProfile = value.getAsString();
        if (json.get("commands") instanceof JsonArray commands) {
            COMMANDS.clear();
            for (JsonElement command : commands) {
                if (command.isJsonPrimitive()) COMMANDS.add(command.getAsString());
            }
        }
    }

    /** "Play.Example.com" and "play.example.com:25565" are the same server. */
    private static String normalize(String ip) {
        String s = ip.strip().toLowerCase(Locale.ROOT);
        return s.endsWith(":25565") ? s.substring(0, s.length() - 6) : s;
    }
}
