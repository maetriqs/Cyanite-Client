package com.cyanite.client.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/** Named snapshots of module states and GUI settings, one JSON file each. Some ship with the client. */
public final class Profiles {
    public static final int MAX_NAME = 24;
    /** How a profile shared as text starts. */
    public static final String SHARE_PREFIX = "CyaniteProfile:";
    /** The most a pasted profile may unpack to (a full one is about 30 KB). */
    private static final int MAX_SHARED = 1 << 20;

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

    /**
     * A saved profile as one line of text to paste anywhere: {@code CyaniteProfile:[...]} holding
     * its name, the modules it switches on and each setting it changes from the default, compressed
     * (Deflate, then Base64). Secret settings are never in it. Null if the profile can't be read.
     */
    public static String share(String name) {
        JsonObject profile = Config.read(dir().resolve(name + EXT));
        if (profile == null) return null;
        JsonObject shared = Config.shareable(profile);
        shared.addProperty("name", name);
        return SHARE_PREFIX + "[" + pack(shared.toString()) + "]";
    }

    /** What pasting a profile string did: the name it was added under, or why it wasn't. */
    public record Imported(String name, String problem) {
    }

    /**
     * Adds the profile in a pasted string to the list, under the name it was shared with (or the
     * first free one like it), without loading it. Text around the string is ignored.
     */
    public static Imported importShared(String text) {
        String body = body(text);
        if (body == null) return new Imported(null, "Copy a CyaniteProfile string first");

        JsonObject shared;
        try {
            shared = JsonParser.parseString(unpack(body)).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            return new Imported(null, "That profile string is broken or cut short");
        }
        String name = freeName(shared.get("name") instanceof JsonPrimitive given && given.isString()
            ? sanitize(given.getAsString()) : "");
        if (!Config.write(dir().resolve(name + EXT), Config.expand(shared))) return new Imported(null, "Couldn't save it");
        return new Imported(name, null);
    }

    /** The Base64 part of a profile string, or null if the text has none. */
    private static String body(String text) {
        if (text == null) return null;
        int at = text.indexOf(SHARE_PREFIX);
        if (at < 0) return null;
        // Chat and documents wrap long lines: spaces and line breaks inside it don't count.
        String rest = text.substring(at + SHARE_PREFIX.length()).replaceAll("\\s", "");
        if (rest.startsWith("[")) {
            int end = rest.indexOf(']');
            rest = end < 0 ? rest.substring(1) : rest.substring(1, end);
        }
        int i = 0;
        while (i < rest.length() && isBase64(rest.charAt(i))) i++;
        return i == 0 ? null : rest.substring(0, i);
    }

    private static boolean isBase64(char c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '+' || c == '/' || c == '=';
    }

    /** The name, or the same with the first number after it that isn't taken. */
    private static String freeName(String wanted) {
        String base = wanted.isEmpty() ? "Shared" : wanted;
        if (!Files.exists(dir().resolve(base + EXT))) return base;
        for (int n = 2; ; n++) {
            String suffix = " " + n;
            String name = (base.length() + suffix.length() > MAX_NAME ? base.substring(0, MAX_NAME - suffix.length()) : base) + suffix;
            if (!Files.exists(dir().resolve(name + EXT))) return name;
        }
    }

    // Standard Base64, not the URL kind: chat apps read underscores as italics and eat them.
    private static String pack(String json) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try (DeflaterOutputStream out = new DeflaterOutputStream(bytes, deflater)) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            deflater.end();
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private static String unpack(String base64) throws IOException {
        byte[] packed = Base64.getDecoder().decode(base64);
        try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(packed))) {
            // A pasted string comes from anyone: stop well short of something that unpacks forever.
            byte[] json = in.readNBytes(MAX_SHARED + 1);
            if (json.length > MAX_SHARED) throw new IOException("unpacks too big");
            return new String(json, StandardCharsets.UTF_8);
        }
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
