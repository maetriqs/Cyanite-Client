package com.cyanite.client.system;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Finds ffmpeg for Clips and the Subway Surfers video. Looking can take a moment the first time: not on the game's thread. */
public final class Ffmpeg {
    private static String path;
    private static boolean looked;

    private Ffmpeg() {
    }

    /** ffmpeg on the PATH, or where WinGet puts it; null if it's not there. */
    public static synchronized String find() {
        if (looked) return path;
        looked = true;
        List<String> tries = new ArrayList<>(List.of("ffmpeg"));
        String local = System.getenv("LOCALAPPDATA");
        if (local != null) {
            Path winget = Path.of(local, "Microsoft", "WinGet");
            tries.add(winget.resolve("Links").resolve("ffmpeg.exe").toString());
            try (Stream<Path> found = Files.find(winget.resolve("Packages"), 5,
                (p, a) -> p.getFileName().toString().equalsIgnoreCase("ffmpeg.exe") && p.toString().toLowerCase(Locale.ROOT).contains("ffmpeg"))) {
                found.forEach(p -> tries.add(p.toString()));
            } catch (IOException | RuntimeException ignored) {
                // No WinGet packages.
            }
        }
        for (String tool : tries) {
            if (runs(List.of(tool, "-version"))) {
                path = tool;
                return tool;
            }
        }
        return null;
    }

    /** Whether {@code cmd} runs and exits cleanly within 15 seconds. */
    public static boolean runs(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (p.waitFor(15, TimeUnit.SECONDS) && p.exitValue() == 0) return true;
            p.destroyForcibly();
        } catch (IOException | InterruptedException ignored) {
            // Not this one.
        }
        return false;
    }
}
