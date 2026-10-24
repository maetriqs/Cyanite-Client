package com.cyanite.client.modules.misc;

import com.cyanite.client.config.Config;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.setting.BoolSetting;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Saves every chat line to cyanite/chat-logs, one file a day (and per server, if you like). */
public class MessageLogger extends Module {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final BoolSetting perServer = add(new BoolSetting("File per server", true));
    private final BoolSetting timestamps = add(new BoolSetting("Timestamps", true));

    public MessageLogger() {
        super(Category.MISC, "Message Logger", "Saves all chat to a text file");
    }

    /** Called by ChatComponentMixin for everything that reaches the chat box. */
    public static void log(Component message) {
        MessageLogger logger = Modules.get(MessageLogger.class);
        if (logger == null || !logger.isEnabled()) return;
        logger.write(message.getString());
    }

    private void write(String text) {
        String line = (timestamps.get() ? "[" + LocalTime.now().format(TIME) + "] " : "") + text.replace("\n", " / ") + System.lineSeparator();
        try {
            Path dir = Config.dir().resolve("chat-logs");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(fileName()), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Losing a log line beats breaking chat.
        }
    }

    private String fileName() {
        String day = LocalDate.now().toString();
        if (!perServer.get()) return day + ".txt";
        String server;
        if (mc.getCurrentServer() != null) server = mc.getCurrentServer().ip;
        else if (mc.getSingleplayerServer() != null) server = "singleplayer";
        else server = "menu";
        // Keep it a valid file name: ports and odd characters become underscores.
        return day + " " + server.replaceAll("[^A-Za-z0-9._-]", "_") + ".txt";
    }
}
