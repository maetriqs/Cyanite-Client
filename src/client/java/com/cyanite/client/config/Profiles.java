package com.cyanite.client.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** Named snapshots of module states and GUI settings, one JSON file each. Some ship with the client. */
public final class Profiles {
    public static final int MAX_NAME = 24;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String EXT = ".json";
    private static final String BUNDLED = "/assets/cyanite_client/profiles/";
    private static final String DEFAULTS_FILE = ".installed-defaults";

    private static String active;

    public record Entry(String name, long modified) {
    }

    private Profiles() {
    }

    public static Path dir() {
        return Config.dir().resolve("profiles");
    }

    /** The profile last saved or loaded, or null. */
    public static String active() {
        return active;
    }

    static void setActive(String name) {
        active = name;
    }

    public static boolean isNameChar(int cp) {
        return Character.isLetterOrDigit(cp) || cp == ' ' || cp == '-' || cp == '_';
    }

    public static String sanitize(String name) {
        StringBuilder sb = new StringBuilder();
        name.strip().codePoints().filter(Profiles::isNameChar).forEach(sb::appendCodePoint);
        String clean = sb.toString().strip();
        return clean.length() > MAX_NAME ? clean.substring(0, MAX_NAME) : clean;
    }

    public static List<Entry> list() {
        Path dir = dir();
        if (!Files.isDirectory(dir)) return List.of();

        List<Entry> entries = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(EXT)).forEach(p -> {
                String file = p.getFileName().toString();
                try {
                    entries.add(new Entry(file.substring(0, file.length() - EXT.length()),
                        Files.getLastModifiedTime(p).toMillis()));
                } catch (IOException ignored) {
                }
            });
        } catch (IOException e) {
            LOGGER.error("Couldn't list profiles", e);
        }
        entries.sort(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
        return entries;
    }

    /**
     * Copies the profiles that ship inside the jar into the profiles folder. Each is installed only
     * once (remembered in a hidden list), so one you delete stays deleted, and a profile you already
     * have under the same name is never overwritten.
     */
    public static void installDefaults() {
        Path dir = dir();
        Path installedFile = dir.resolve(DEFAULTS_FILE);
        try {
            Files.createDirectories(dir);
            Set<String> installed = new HashSet<>();
            if (Files.exists(installedFile)) installed.addAll(Files.readAllLines(installedFile, StandardCharsets.UTF_8));

            boolean changed = false;
            for (String name : bundledNames()) {
                if (installed.contains(name)) continue;
                Path target = dir.resolve(name + EXT);
                if (!Files.exists(target)) {
                    try (InputStream in = Profiles.class.getResourceAsStream(BUNDLED + name + EXT)) {
                        if (in == null) continue;
                        Files.copy(in, target);
                    }
                }
                installed.add(name);
                changed = true;
            }
            if (changed) Files.write(installedFile, installed, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("Couldn't install the built-in profiles", e);
        }
    }

    private static List<String> bundledNames() {
        try (InputStream in = Profiles.class.getResourceAsStream(BUNDLED + "index.json")) {
            if (in == null) return List.of();
            List<String> names = new ArrayList<>();
            for (JsonElement e : JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray()) {
                names.add(e.getAsString());
            }
            return names;
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Couldn't read the built-in profile list", e);
            return List.of();
        }
    }

    public static boolean save(String name) {
        if (!Config.write(dir().resolve(name + EXT), Config.snapshot(false))) return false;
        active = name;
        Config.save();
        return true;
    }

    public static boolean load(String name) {
        JsonObject json = Config.read(dir().resolve(name + EXT));
        if (json == null) return false;

        Config.apply(json, false);
        active = name;
        Config.save();
        return true;
    }

    public static boolean delete(String name) {
        try {
            Files.deleteIfExists(dir().resolve(name + EXT));
        } catch (IOException e) {
            LOGGER.error("Couldn't delete profile {}", name, e);
            return false;
        }
        if (name.equals(active)) active = null;
        Config.save();
        return true;
    }
}
