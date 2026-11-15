package com.cyanite.client.system;

import com.cyanite.client.config.Config;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Checks that the Cyanite jar in the mods folder is the one released on GitHub, byte for byte. A
 * copy that someone opened up and added code to (a RAT, a token stealer) hashes differently from the
 * release, so it gets a warning on the title screen telling you to delete it.
 *
 * <p>It asks the official repository, never the jar's own "sources" link, which a repacked jar can
 * point anywhere. Whoever changes the jar can also take this check out, so it catches lazy repacks,
 * not careful ones: the real answer is downloading from the releases page only.
 *
 * <p>Jars built on this PC aren't on GitHub, so their hashes can go in {@code cyanite/trusted-jars.txt}
 * (one per line), which skips the check.
 */
public final class JarCheck {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MOD_ID = "cyanite_client";
    private static final String OFFICIAL = "maetriqs/Cyanite-Client";
    public static final String DOWNLOADS = "https://github.com/" + OFFICIAL + "/releases";

    public enum Verdict {
        /** Matches the GitHub release of its version. */
        OFFICIAL,
        /** Listed in trusted-jars.txt: built on this PC. */
        TRUSTED,
        /** Its version is released, but this jar isn't that release. */
        MODIFIED,
        /** No release has its version, so it didn't come from GitHub. */
        UNRELEASED,
        /** The release has no hash to compare against. */
        UNKNOWN
    }

    /** What the check found about {@code jar}: its sha256, and the release's when there is one. */
    public record Result(Verdict verdict, Path jar, String version, String sha256, String expected) {
        public boolean warn() {
            return verdict == Verdict.MODIFIED || verdict == Verdict.UNRELEASED;
        }
    }

    /** A bad result waiting for the title screen. */
    private static volatile Result pending;

    private JarCheck() {
    }

    /** Checks the running jar in the background. Called once the game's up. */
    public static void start() {
        FabricLoader loader = FabricLoader.getInstance();
        if (loader.isDevelopmentEnvironment()) return;
        ModContainer mod = loader.getModContainer(MOD_ID).orElse(null);
        Path jar = mod == null ? null : Updater.runningJar(mod);
        if (jar == null) return;
        String version = mod.getMetadata().getVersion().getFriendlyString();

        Thread thread = new Thread(() -> {
            try {
                Result result = check(jar, version, Config.dir().resolve("trusted-jars.txt"), mod);
                switch (result.verdict()) {
                    case OFFICIAL -> LOGGER.info("Cyanite {} matches its GitHub release", version);
                    case TRUSTED -> LOGGER.info("Cyanite {} is a build trusted in trusted-jars.txt", version);
                    case UNKNOWN -> LOGGER.info("Cyanite {}'s GitHub release has no hash to check against", version);
                    case MODIFIED -> LOGGER.warn("Cyanite {} does NOT match its GitHub release (sha256 {}, release {}): this jar has been modified",
                        version, result.sha256(), result.expected());
                    case UNRELEASED -> LOGGER.warn("Cyanite {} was never released on GitHub (sha256 {}): not an official jar."
                        + " If you built it yourself, add that hash to cyanite/trusted-jars.txt", version, result.sha256());
                }
                if (result.warn()) Minecraft.getInstance().execute(() -> warn(result));
            } catch (Exception e) {
                LOGGER.warn("Couldn't check the Cyanite jar against GitHub", e);
            }
        }, "Cyanite jar check");
        thread.setDaemon(true);
        thread.start();
    }

    /** Hashes {@code jar} and compares it with Cyanite {@code version}'s jar on GitHub, unless {@code trusted} lists it. */
    public static Result check(Path jar, String version, Path trusted, ModContainer mod) throws Exception {
        String sha = Updater.sha256(jar);
        if (isTrusted(trusted, sha)) return new Result(Verdict.TRUSTED, jar, version, sha, null);

        String tag = URLEncoder.encode("v" + version, StandardCharsets.UTF_8);
        HttpResponse<String> response = Updater.HTTP.send(Updater.request("https://api.github.com/repos/" + OFFICIAL + "/releases/tags/" + tag,
            "application/vnd.github+json", mod), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) return new Result(Verdict.UNRELEASED, jar, version, sha, null);
        if (response.statusCode() != 200) throw new IOException("GitHub answered " + response.statusCode());

        JsonObject release = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonObject asset = Updater.asset(release, MOD_ID + "-" + version + ".jar");
        // Every release has its jar, and GitHub has hashed every asset since 2025.
        if (asset == null) return new Result(Verdict.UNRELEASED, jar, version, sha, null);
        if (!(asset.get("digest") instanceof JsonElement digest) || !digest.isJsonPrimitive()
            || !digest.getAsString().startsWith("sha256:")) {
            return new Result(Verdict.UNKNOWN, jar, version, sha, null);
        }
        String expected = digest.getAsString().substring(7).toLowerCase(Locale.ROOT);
        return new Result(expected.equals(sha) ? Verdict.OFFICIAL : Verdict.MODIFIED, jar, version, sha, expected);
    }

    private static boolean isTrusted(Path file, String sha) throws IOException {
        if (!Files.isRegularFile(file)) return false;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.strip().equalsIgnoreCase(sha)) return true;
        }
        return false;
    }

    private static void warn(Result result) {
        pending = result;
        Notify.send("Warning", result.verdict() == Verdict.MODIFIED
            ? "this Cyanite jar has been modified, it isn't the GitHub release"
            : "this Cyanite jar isn't an official release", true, true, true);
    }

    /** The bad result for the title screen to show, once; null when there's none. */
    public static Result takeWarning() {
        Result result = pending;
        pending = null;
        return result;
    }
}
