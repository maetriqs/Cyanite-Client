package com.cyanite.client.music;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * What Spotify is playing, read from Windows' media controls (what the volume flyout shows) by a
 * small hidden PowerShell loop. Nothing to sign in to. Windows only.
 */
public final class Spotify {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long RESTART_AFTER = 10_000;
    private static final int GIVE_UP_AFTER = 3;
    private static final long SANE_CLOCK = 6 * 3600_000L;

    // Asks Windows for Spotify's media session twice a second and prints it as one JSON line, or
    // "none". Only single quotes: the whole script goes through as one argument. It stops by itself
    // once the game's gone, so a crash can't leave it running.
    private static final String SCRIPT = String.join("\n",
        "$ErrorActionPreference = 'Stop'",
        "[Console]::OutputEncoding = New-Object Text.UTF8Encoding $false",
        "Add-Type -AssemblyName System.Runtime.WindowsRuntime",
        "$asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' } | Select-Object -First 1",
        "function Await($op, [Type]$type) { $t = $asTask.MakeGenericMethod($type).Invoke($null, @($op)); $t.Wait(-1) | Out-Null; $t.Result }",
        "$managerType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]",
        "$propsType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties, Windows.Media.Control, ContentType = WindowsRuntime]",
        "$manager = Await ($managerType::RequestAsync()) $managerType",
        "while ($true) {",
        "  try { $null = [Diagnostics.Process]::GetProcessById(%PARENT%) } catch { exit }",
        "  $line = 'none'",
        "  try {",
        "    $s = $manager.GetSessions() | Where-Object { $_.SourceAppUserModelId -like '*Spotify*' } | Select-Object -First 1",
        "    if ($s) {",
        "      $m = Await ($s.TryGetMediaPropertiesAsync()) $propsType",
        "      $tl = $s.GetTimelineProperties()",
        "      $line = [pscustomobject]@{ title = $m.Title; artist = $m.Artist; album = $m.AlbumTitle; status = [int]$s.GetPlaybackInfo().PlaybackStatus; position = [long]$tl.Position.TotalMilliseconds; length = [long]$tl.EndTime.TotalMilliseconds; updated = $tl.LastUpdatedTime.ToUnixTimeMilliseconds() } | ConvertTo-Json -Compress",
        "    }",
        "  } catch { $line = 'none' }",
        "  [Console]::Out.WriteLine($line)",
        "  [Console]::Out.Flush()",
        "  Start-Sleep -Milliseconds 500",
        "}");

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    // Windows' "playing" in GlobalSystemMediaTransportControlsSessionPlaybackStatus.
    private static final int PLAYING = 4;

    /** One song. {@code length} in ms, 0 if Spotify didn't say. */
    public record Track(String title, String artist, String album, long length) {
        /** The same for the same song, whatever the playback's doing. */
        public String key() {
            return artist + "\n" + title + "\n" + album + "\n" + Math.round(length / 1000.0);
        }
    }

    /** {@code position} ms into the track at {@code at} (wall clock, ms). */
    public record State(Track track, boolean playing, long position, long at) {
        public long positionNow() {
            long p = playing ? position + System.currentTimeMillis() - at : position;
            if (track.length() > 0) p = Math.min(p, track.length());
            return Math.max(0, p);
        }
    }

    private static volatile State state;
    private static volatile String problem;
    private static Process process;
    private static boolean wanted;
    private static int failures;

    private Spotify() {
    }

    public static boolean supported() {
        return WINDOWS;
    }

    /** What's playing, or null when Spotify isn't open (or isn't being read). */
    public static State state() {
        return state;
    }

    /** Why it can't read Spotify at all, or null. */
    public static String problem() {
        return problem;
    }

    public static synchronized void start() {
        if (!WINDOWS || wanted) return;
        wanted = true;
        failures = 0;
        problem = null;
        launch();
    }

    public static synchronized void stop() {
        wanted = false;
        state = null;
        Process p = process;
        process = null;
        if (p != null) p.destroy();
    }

    private static synchronized void launch() {
        if (!wanted || process != null) return;
        try {
            String script = SCRIPT.replace("%PARENT%", Long.toString(ProcessHandle.current().pid()));
            Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", script)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            process = p;
            Thread reader = new Thread(() -> read(p), "Cyanite Spotify");
            reader.setDaemon(true);
            reader.start();
        } catch (IOException e) {
            LOGGER.warn("Lyrics couldn't start PowerShell to read Spotify", e);
            problem = "couldn't start PowerShell";
            wanted = false;
        }
    }

    private static void read(Process p) {
        boolean heard = false;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                heard = true;
                if (process == p) accept(line);
            }
        } catch (IOException ignored) {
            // Closed by stop().
        }

        synchronized (Spotify.class) {
            if (process != p) return;
            process = null;
            state = null;
            // A loop that never printed a line can't read the media controls here; don't keep trying for ever.
            failures = heard ? 0 : failures + 1;
            if (failures >= GIVE_UP_AFTER) {
                LOGGER.warn("Lyrics: PowerShell couldn't read Windows' media controls ({} tries), giving up", failures);
                problem = "couldn't read Windows' media controls";
                wanted = false;
                return;
            }
            if (!wanted) return;
        }
        LOGGER.warn("Lyrics: the Spotify reader stopped, starting it again in {} s", RESTART_AFTER / 1000);
        try {
            Thread.sleep(RESTART_AFTER);
        } catch (InterruptedException e) {
            return;
        }
        launch();
    }

    /** One line from the loop. */
    static void accept(String line) {
        line = line.strip();
        if (line.startsWith("﻿")) line = line.substring(1);
        if (!line.startsWith("{")) {
            state = null;
            return;
        }
        try {
            JsonObject o = JsonParser.parseString(line).getAsJsonObject();
            String title = text(o, "title");
            if (title.isEmpty()) {
                state = null;
                return;
            }
            Track track = new Track(title, text(o, "artist"), text(o, "album"), Math.max(0, o.get("length").getAsLong()));
            long now = System.currentTimeMillis();
            long updated = o.get("updated").getAsLong();
            // Windows says when it last heard the position; a time that makes no sense is taken as now.
            long at = updated > 0 && Math.abs(now - updated) < SANE_CLOCK ? updated : now;
            state = new State(track, o.get("status").getAsInt() == PLAYING, Math.max(0, o.get("position").getAsLong()), at);
        } catch (RuntimeException e) {
            LOGGER.debug("Lyrics: couldn't read a line from the Spotify reader", e);
        }
    }

    private static String text(JsonObject o, String name) {
        JsonElement e = o.get(name);
        return e == null || e.isJsonNull() ? "" : e.getAsString().strip();
    }
}
