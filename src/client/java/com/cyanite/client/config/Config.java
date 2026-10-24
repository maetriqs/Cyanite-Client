package com.cyanite.client.config;

import com.cyanite.client.donut.DonutSmp;
import com.cyanite.client.gui.ClickGuiScreen;
import com.cyanite.client.gui.GuiSettings;
import com.cyanite.client.hud.Hud;
import com.cyanite.client.system.Macros;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.setting.Setting;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * The live config in {@code .minecraft/cyanite/config.json}. It's loaded on startup and
 * written when the GUI closes and when the game exits. Profiles reuse the same format,
 * minus the window layout.
 */
public final class Config {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private Config() {
    }

    public static Path dir() {
        return FabricLoader.getInstance().getGameDir().resolve("cyanite");
    }

    private static Path file() {
        return dir().resolve("config.json");
    }

    public static void load() {
        Path file = file();
        if (!Files.exists(file)) return;

        JsonObject json = read(file);
        if (json != null) apply(json, true);
    }

    public static void save() {
        write(file(), snapshot(true));
    }

    static JsonObject snapshot(boolean includeLayout) {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("gui", writeSettings(GuiSettings.ALL));

        JsonObject modules = new JsonObject();
        for (Module module : Modules.getAll()) {
            JsonObject entry = new JsonObject();
            // Held off by Stealth Mode still counts as on, so it comes back after.
            entry.addProperty("enabled", module.isWanted());
            if (!module.settings().isEmpty()) entry.add("settings", writeSettings(module.settings()));
            modules.add(module.name, entry);
        }
        root.add("modules", modules);
        root.add("hud", Hud.save());

        if (includeLayout) {
            root.add("layout", ClickGuiScreen.saveLayout());
            root.add("favourites", com.cyanite.client.gui.BetaGuiScreen.saveFavourites());
            root.add("donut", DonutSmp.save());
            root.add("macros", Macros.save());
            root.addProperty("tutorialSeen", ClickGuiScreen.tutorialSeen());
            if (Profiles.active() != null) root.addProperty("profile", Profiles.active());
        }
        return root;
    }

    static void apply(JsonObject root, boolean includeLayout) {
        if (root.get("gui") instanceof JsonObject gui) readSettings(GuiSettings.ALL, gui);

        if (root.get("modules") instanceof JsonObject modules) {
            for (Module module : Modules.getAll()) {
                if (!(modules.get(module.name) instanceof JsonObject entry)) continue;

                if (entry.get("enabled") instanceof JsonElement enabled && enabled.isJsonPrimitive()) {
                    module.setEnabled(enabled.getAsBoolean());
                }
                if (entry.get("settings") instanceof JsonObject settings) readSettings(module.settings(), settings);
            }
        }

        if (root.get("hud") instanceof JsonObject hud) Hud.load(hud);

        if (includeLayout) {
            if (root.get("layout") instanceof JsonObject layout) ClickGuiScreen.loadLayout(layout);
            if (root.get("favourites") instanceof com.google.gson.JsonArray favourites) com.cyanite.client.gui.BetaGuiScreen.loadFavourites(favourites);
            if (root.get("donut") instanceof JsonObject donut) DonutSmp.load(donut);
            if (root.get("macros") instanceof JsonObject macros) Macros.load(macros);
            if (root.get("tutorialSeen") instanceof JsonElement seen && seen.isJsonPrimitive()) {
                ClickGuiScreen.setTutorialSeen(seen.getAsBoolean());
            }
            if (root.get("profile") instanceof JsonElement profile && profile.isJsonPrimitive()) {
                Profiles.setActive(profile.getAsString());
            }
        }
    }

    private static JsonObject writeSettings(List<Setting<?>> settings) {
        JsonObject json = new JsonObject();
        for (Setting<?> setting : settings) json.add(setting.name, setting.toJson());
        return json;
    }

    private static void readSettings(List<Setting<?>> settings, JsonObject json) {
        for (Setting<?> setting : settings) {
            JsonElement value = json.get(setting.name);
            if (value != null) setting.fromJson(value);
        }
    }

    static JsonObject read(Path file) {
        try {
            return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        } catch (Exception e) {
            LOGGER.error("Couldn't read {}", file, e);
            return null;
        }
    }

    static boolean write(Path file, JsonObject json) {
        try {
            Files.createDirectories(file.getParent());
            // Write beside the target and swap it in, so a crash mid-write can't eat the old file.
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(json));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            LOGGER.error("Couldn't write {}", file, e);
            return false;
        }
    }
}
