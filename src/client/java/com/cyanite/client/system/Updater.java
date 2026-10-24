package com.cyanite.client.system;

import com.cyanite.client.config.Config;
import com.cyanite.client.gui.GuiSettings;
import com.cyanite.client.gui.Theme;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Keeps Cyanite up to date from its GitHub releases. Once per launch it asks GitHub for the newest
 * release. If that's newer than this jar, it downloads it into {@code cyanite/update}, checks it's
 * really the next Cyanite, and swaps it into the mods folder once the game has closed. The mods
 * folder is never touched while the game runs, so there are never two Cyanite jars in it.
 *
 * <p>The repository comes from the jar's own metadata (its "sources" link), so a fork's builds
 * update from the fork. Builds without one, and the dev environment, never look.
 */
public final class Updater {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MOD_ID = "cyanite_client";
    private static final Pattern REPO = Pattern.compile("github\\.com/([\\w.-]+/[\\w.-]+?)(?:\\.git)?/?$");
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    /** The checked jar waiting in cyanite/update, the jar it replaces, and its version. */
    private static volatile Path staged;
    private static volatile Path running;
    private static volatile String stagedVersion;

    private Updater() {
    }

    /** Looks for a new release in the background. Called once the game's up. */
    public static void start() {
        if (GuiSettings.UPDATES.is("Off")) return;
        FabricLoader loader = FabricLoader.getInstance();
        if (loader.isDevelopmentEnvironment()) return;
        ModContainer mod = loader.getModContainer(MOD_ID).orElse(null);
        if (mod == null) return;
        String repo = repo(mod);
        if (repo == null) return;

        Thread thread = new Thread(() -> {
            try {
                check(mod, repo);
            } catch (Exception e) {
                LOGGER.warn("Couldn't check for a Cyanite update", e);
            }
        }, "Cyanite updater");
        thread.setDaemon(true);
        thread.start();
    }

    private static void check(ModContainer mod, String repo) throws Exception {
        HttpResponse<String> response = HTTP.send(request("https://api.github.com/repos/" + repo + "/releases/latest",
            "application/vnd.github+json", mod), HttpResponse.BodyHandlers.ofString());
        // 404: nothing released yet.
        if (response.statusCode() == 404) return;
        if (response.statusCode() != 200) throw new IOException("GitHub answered " + response.statusCode());

        JsonObject release = JsonParser.parseString(response.body()).getAsJsonObject();
        String tag = release.get("tag_name").getAsString();
        String latest = tag.startsWith("v") ? tag.substring(1) : tag;
        String page = release.get("html_url").getAsString();
        if (Version.parse(latest).compareTo(mod.getMetadata().getVersion()) <= 0) {
            clearUpdateDir();
            return;
        }

        Path jar = runningJar(mod);
        JsonObject asset = asset(release, MOD_ID + "-" + latest + ".jar");
        if (GuiSettings.UPDATES.is("Install") && jar != null && asset != null) {
            Path file = download(asset, latest, mod);
            running = jar;
            stagedVersion = latest;
            staged = file;
            Minecraft.getInstance().execute(() -> Notify.send("Update ready",
                "Cyanite " + latest + " goes in when you close the game", true, false, false));
            return;
        }
        Minecraft.getInstance().execute(() -> {
            Notify.send("Update", "Cyanite " + latest + " is out", true, false, false);
            if (Stealth.active()) return;
            Component link = Component.literal("Open the download page").withStyle(style -> style
                .withColor(Theme.accent() & 0xFFFFFF)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.OpenUrl(URI.create(page))));
            Stealth.wrote(link);
            Minecraft.getInstance().gui.getChat().addMessage(link);
        });
    }

    /**
     * The game's closing: puts the downloaded jar in the old one's place. On Windows the running
     * jar can't be moved until the game's gone, so a hidden PowerShell waits for it to exit first.
     */
    public static void applyOnExit() {
        Path from = staged;
        Path old = running;
        if (from == null || old == null || !Files.isRegularFile(from)) return;
        Path to = old.resolveSibling(MOD_ID + "-" + stagedVersion + ".jar");
        Path backup = from.resolveSibling("previous.jar");
        try {
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
                swapAfterExit(from, old, to, backup);
            } else {
                // Elsewhere a running jar can be moved, so it's done now.
                Files.move(old, backup, StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    Files.move(backup, old, StandardCopyOption.REPLACE_EXISTING);
                    throw e;
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Couldn't install the Cyanite update", e);
        }
    }

    private static void swapAfterExit(Path from, Path old, Path to, Path backup) throws IOException {
        // The old jar's moved aside first, and the new one only goes in once it has; if the new one
        // can't, the old one comes back. Either way the mods folder ends with exactly one Cyanite.
        String script = String.join("; ",
            "$ErrorActionPreference = 'Stop'",
            "try { Wait-Process -Id " + ProcessHandle.current().pid() + " -Timeout 300 } catch {}",
            "$moved = $false",
            "for ($i = 0; $i -lt 60 -and -not $moved; $i++) { try { Move-Item -LiteralPath " + quote(old)
                + " -Destination " + quote(backup) + " -Force; $moved = $true } catch { Start-Sleep -Milliseconds 500 } }",
            "if ($moved) { try { Move-Item -LiteralPath " + quote(from) + " -Destination " + quote(to)
                + " -Force } catch { Move-Item -LiteralPath " + quote(backup) + " -Destination " + quote(old) + " -Force } }");
        new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", script)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start();
    }

    private static String quote(Path path) {
        return "'" + path.toAbsolutePath().toString().replace("'", "''") + "'";
    }

    /** Downloads the release's jar and makes sure it's whole and really Cyanite at that version. */
    private static Path download(JsonObject asset, String version, ModContainer mod) throws Exception {
        clearUpdateDir();
        Path dir = updateDir();
        Files.createDirectories(dir);
        Path part = dir.resolve(MOD_ID + "-" + version + ".jar.part");
        HttpResponse<Path> response = HTTP.send(request(asset.get("browser_download_url").getAsString(),
            "application/octet-stream", mod), HttpResponse.BodyHandlers.ofFile(part));
        try {
            if (response.statusCode() != 200) throw new IOException("Download answered " + response.statusCode());
            if (Files.size(part) != asset.get("size").getAsLong()) throw new IOException("Download came out the wrong size");
            if (asset.get("digest") instanceof JsonElement digest && digest.isJsonPrimitive()
                && digest.getAsString().startsWith("sha256:")
                && !digest.getAsString().substring(7).equalsIgnoreCase(sha256(part))) {
                throw new IOException("Download doesn't match GitHub's checksum");
            }
            if (!isCyanite(part, version)) throw new IOException("Download isn't Cyanite " + version);
            Path jar = dir.resolve(MOD_ID + "-" + version + ".jar");
            Files.move(part, jar, StandardCopyOption.REPLACE_EXISTING);
            return jar;
        } finally {
            Files.deleteIfExists(part);
        }
    }

    private static boolean isCyanite(Path jar, String version) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("fabric.mod.json");
            if (entry == null) return false;
            try (InputStream in = zip.getInputStream(entry)) {
                JsonObject meta = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                return MOD_ID.equals(meta.get("id").getAsString()) && version.equals(meta.get("version").getAsString());
            }
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) > 0; ) digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static HttpRequest request(String url, String accept, ModContainer mod) {
        return HttpRequest.newBuilder(URI.create(url))
            .header("Accept", accept)
            .header("User-Agent", "Cyanite-Client/" + mod.getMetadata().getVersion().getFriendlyString())
            .timeout(Duration.ofSeconds(60))
            .GET()
            .build();
    }

    private static JsonObject asset(JsonObject release, String name) {
        if (!(release.get("assets") instanceof JsonElement assets) || !assets.isJsonArray()) return null;
        for (JsonElement element : assets.getAsJsonArray()) {
            if (element.isJsonObject() && name.equals(element.getAsJsonObject().get("name").getAsString())) {
                return element.getAsJsonObject();
            }
        }
        return null;
    }

    /** "owner/name" from the jar's sources link, or null when it isn't a GitHub repository. */
    private static String repo(ModContainer mod) {
        String sources = mod.getMetadata().getContact().get("sources").orElse("");
        Matcher matcher = REPO.matcher(sources);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** The jar Cyanite was loaded from, or null when it didn't come from one (a dev run, a bundled copy). */
    private static Path runningJar(ModContainer mod) {
        ModOrigin origin = mod.getOrigin();
        if (origin.getKind() != ModOrigin.Kind.PATH) return null;
        List<Path> paths = origin.getPaths();
        if (paths.size() != 1) return null;
        Path path = paths.get(0);
        return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar") ? path : null;
    }

    private static Path updateDir() {
        return Config.dir().resolve("update");
    }

    /** Leftovers: the last update's old jar, or a download that never went in. */
    private static void clearUpdateDir() throws IOException {
        Path dir = updateDir();
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) Files.deleteIfExists(file);
        }
    }
}
