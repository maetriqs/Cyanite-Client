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
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The live config in {@code .minecraft/cyanite/config.json}. It's loaded on startup and
 * written when the GUI closes, when the game exits, and a few seconds after a module switches
 * outside the menu (a keybind, one stopping itself, a disconnect), so a crash doesn't lose it.
 * Profiles reuse the same format, minus the window layout.
 */
public final class Config {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** How long a change waits to be written, so a burst of them is one write. */
    private static final long SAVE_DELAY = 3_000_000_000L;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss", Locale.ROOT);

    /** When the waiting change gets written ({@link System#nanoTime}); 0 for none. */
    private static volatile long saveAt;
    /** Where an unreadable config was put aside on startup, to say so once the game's up; null for none. */
    private static String keptBroken;

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

        JsonObject json;
        try {
            json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        } catch (Exception e) {
            keepBroken(file, e);
            return;
        }
        apply(json, true);
    }

    /**
     * An unreadable config (a bad hand edit, a disk that filled up) is copied aside before anything
     * saves over it, so every setting, bind and webhook in it can still be got back.
     */
    private static void keepBroken(Path file, Exception why) {
        // Named like the game's crash reports, so the newest sorts last.
        Path aside = file.resolveSibling("config.broken-" + LocalDateTime.now().format(STAMP) + ".json");
        try {
            Files.copy(file, aside, StandardCopyOption.REPLACE_EXISTING);
            keptBroken = aside.getFileName().toString();
            LOGGER.warn("Couldn't read {} ({}); started from defaults and kept it as {}", file.getFileName(), why.toString(), keptBroken);
        } catch (IOException e) {
            LOGGER.error("Couldn't keep a copy of the unreadable {}", file, e);
        }
    }

    /** Once the game's up: says the config couldn't be read, if it couldn't. */
    public static void reportBroken() {
        if (keptBroken == null) return;
        com.cyanite.client.system.Notify.send("Settings reset",
            "Couldn't read config.json; a copy is in the cyanite folder", true, false, false);
        keptBroken = null;
    }

    public static void save() {
        saveAt = 0;
        write(file(), snapshot(true));
    }

    /** Writes the config a few seconds from now; another change before then pushes it back. Any thread. */
    public static void saveSoon() {
        saveAt = System.nanoTime() + SAVE_DELAY;
    }

    /** Once a client tick: writes the config once a change has waited long enough. */
    public static void tick() {
        long at = saveAt;
        if (at != 0 && System.nanoTime() - at >= 0) save();
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
            // Profiles get shared: private settings (webhooks and the like) stay out of them.
            if (!module.settings().isEmpty()) entry.add("settings", writeSettings(module.settings(), includeLayout));
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
            root.add("importAsked", Imports.saveAsked());
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
            if (root.get("importAsked") instanceof JsonArray asked) Imports.loadAsked(asked);
            if (root.get("profile") instanceof JsonElement profile && profile.isJsonPrimitive()) {
                Profiles.setActive(profile.getAsString());
            }
        }
    }

    /**
     * A saved profile cut down for sharing as text: the modules it switches on and each setting it
     * changes from the default. Secret settings are never in it.
     */
    static JsonObject shareable(JsonObject profile) {
        JsonObject out = new JsonObject();
        out.addProperty("version", 1);
        if (profile.get("gui") instanceof JsonObject gui) out.add("gui", changed(GuiSettings.ALL, gui));

        JsonArray on = new JsonArray();
        JsonObject settings = new JsonObject();
        if (profile.get("modules") instanceof JsonObject modules) {
            for (Module module : Modules.getAll()) {
                if (!(modules.get(module.name) instanceof JsonObject entry)) continue;
                if (entry.get("enabled") instanceof JsonPrimitive enabled && enabled.isBoolean() && enabled.getAsBoolean()) {
                    on.add(module.name);
                }
                if (entry.get("settings") instanceof JsonObject saved) {
                    JsonObject diff = changed(module.settings(), saved);
                    if (!diff.isEmpty()) settings.add(module.name, diff);
                }
            }
        }
        out.add("on", on);
        out.add("settings", settings);
        if (profile.get("hud") instanceof JsonObject hud) out.add("hud", hud);
        return out;
    }

    /**
     * A shared profile made whole again: every module it doesn't switch on is off, and every
     * setting it doesn't mention is at its default. Only settings this version has are kept, and
     * secret ones never, whatever the text holds.
     */
    static JsonObject expand(JsonObject shared) {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("gui", filled(GuiSettings.ALL, shared.get("gui") instanceof JsonObject gui ? gui : new JsonObject()));

        Set<String> on = new HashSet<>();
        if (shared.get("on") instanceof JsonArray names) {
            for (JsonElement name : names) if (name.isJsonPrimitive()) on.add(name.getAsString());
        }
        JsonObject settings = shared.get("settings") instanceof JsonObject s ? s : new JsonObject();
        JsonObject modules = new JsonObject();
        for (Module module : Modules.getAll()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("enabled", on.contains(module.name));
            if (!module.settings().isEmpty()) {
                entry.add("settings", filled(module.settings(), settings.get(module.name) instanceof JsonObject mine ? mine : new JsonObject()));
            }
            modules.add(module.name, entry);
        }
        root.add("modules", modules);
        if (shared.get("hud") instanceof JsonObject hud) root.add("hud", hud);
        return root;
    }

    /** The settings whose saved value isn't the default, secrets left out. */
    private static JsonObject changed(List<Setting<?>> settings, JsonObject saved) {
        JsonObject out = new JsonObject();
        for (Setting<?> setting : settings) {
            JsonElement value = saved.get(setting.name);
            if (value != null && !setting.isSecret() && !value.equals(setting.defaultJson())) out.add(setting.name, value);
        }
        return out;
    }

    /** Every setting but the secret ones: the given value where there is one, otherwise the default. */
    private static JsonObject filled(List<Setting<?>> settings, JsonObject given) {
        JsonObject out = new JsonObject();
        for (Setting<?> setting : settings) {
            if (setting.isSecret()) continue;
            JsonElement value = given.get(setting.name);
            out.add(setting.name, value != null ? value : setting.defaultJson());
        }
        return out;
    }

    private static JsonObject writeSettings(List<Setting<?>> settings) {
        return writeSettings(settings, true);
    }

    private static JsonObject writeSettings(List<Setting<?>> settings, boolean withSecrets) {
        JsonObject json = new JsonObject();
        for (Setting<?> setting : settings) {
            if (withSecrets || !setting.isSecret()) json.add(setting.name, setting.toJson());
        }
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

    public static boolean write(Path file, JsonObject json) {
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
