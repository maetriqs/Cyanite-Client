package com.cyanite.client.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.util.Util;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Time-synced lyrics from LRCLIB (lrclib.net), a free and open lyrics database. Only the song's name,
 * artist, album and length are sent. Answers are kept for the session; a failed lookup is tried again
 * after a while.
 */
public final class LrcLib {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String API = "https://lrclib.net/api/";
    private static final String AGENT = "Cyanite-Client (Lyrics; https://github.com/maetriqs/Cyanite-Client)";
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
    private static final int KEEP = 64;
    private static final long RETRY_AFTER = 30_000;
    // A search hit this many seconds off the song's length is a different recording.
    private static final double SAME_LENGTH = 10;

    private static final Pattern STAMP = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern OFFSET = Pattern.compile("^\\[offset:\\s*([+-]?\\d+)\\s*]", Pattern.CASE_INSENSITIVE);

    public enum Status { LOOKING, FOUND, INSTRUMENTAL, UNSYNCED, MISSING, FAILED }

    /** A line and the ms into the song it starts at. An empty line is a break in the singing. */
    public record Line(long at, String text) {
    }

    public record Words(Status status, List<Line> lines, long since) {
        /** The line being sung {@code ms} into the song, or -1 before the first. */
        public int lineAt(long ms) {
            int lo = 0, hi = lines.size() - 1, found = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (lines.get(mid).at() <= ms) {
                    found = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            return found;
        }
    }

    private static final Map<String, Words> KNOWN = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Words> eldest) {
            return size() > KEEP;
        }
    });

    private LrcLib() {
    }

    /** What's known of {@code track} so far. The first call (and one after a failure has aged) starts a lookup. */
    public static Words words(Spotify.Track track) {
        String key = track.key();
        Words known = KNOWN.get(key);
        if (known != null && !(known.status() == Status.FAILED && System.currentTimeMillis() - known.since() > RETRY_AFTER)) return known;

        Words looking = new Words(Status.LOOKING, List.of(), System.currentTimeMillis());
        KNOWN.put(key, looking);
        CompletableFuture.supplyAsync(() -> fetch(track), Util.ioPool()).thenAccept(w -> KNOWN.put(key, w));
        return looking;
    }

    private static Words fetch(Spotify.Track t) {
        try {
            JsonObject exact = null;
            long seconds = Math.round(t.length() / 1000.0);
            if (!t.album().isEmpty() && seconds > 0) {
                try {
                    JsonElement e = get("get?" + query("track_name", t.title(), "artist_name", t.artist(),
                        "album_name", t.album(), "duration", Long.toString(seconds)));
                    if (e != null && e.isJsonObject()) exact = e.getAsJsonObject();
                } catch (IOException e) {
                    // The exact lookup sometimes answers 503 while the search works: go on to that.
                    LOGGER.debug("Lyrics: LRCLIB's exact lookup failed ({}), searching instead", e.toString());
                }
            }
            if (exact != null && has(exact, "syncedLyrics")) return read(exact);

            // Spotify gives every artist ("A, B"); LRCLIB usually files a song under the first.
            String artist = t.artist().split(", ")[0];
            JsonObject hit = pick(get("search?" + query("track_name", t.title(), "artist_name", artist)), t.length());
            if (hit == null || !has(hit, "syncedLyrics")) {
                JsonObject wider = pick(get("search?" + query("q", t.title() + " " + artist)), t.length());
                if (wider != null && (hit == null || has(wider, "syncedLyrics"))) hit = wider;
            }
            // Synced words from anywhere beat the exact match's "instrumental" or plain words: some
            // uploads are tagged instrumental by mistake.
            if (hit != null && has(hit, "syncedLyrics")) return read(hit);
            return read(exact != null ? exact : hit);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Lyrics: couldn't look the song up on LRCLIB: {}", e.toString());
            return failed();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed();
        }
    }

    private static Words failed() {
        return new Words(Status.FAILED, List.of(), System.currentTimeMillis());
    }

    private static boolean instrumental(JsonObject o) {
        return o.has("instrumental") && !o.get("instrumental").isJsonNull() && o.get("instrumental").getAsBoolean();
    }

    /** The search hit with synced words whose length is closest to the song's; failing that, instrumental, then plain words. */
    private static JsonObject pick(JsonElement hits, long lengthMs) {
        if (hits == null || !hits.isJsonArray()) return null;
        double seconds = lengthMs / 1000.0;
        List<JsonObject> near = new ArrayList<>();
        for (JsonElement e : hits.getAsJsonArray()) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            if (seconds > 0 && o.has("duration") && !o.get("duration").isJsonNull()
                && Math.abs(o.get("duration").getAsDouble() - seconds) > SAME_LENGTH) continue;
            near.add(o);
        }
        Comparator<JsonObject> closest = Comparator.comparingDouble(o ->
            seconds > 0 && o.has("duration") && !o.get("duration").isJsonNull() ? Math.abs(o.get("duration").getAsDouble() - seconds) : 0);
        return near.stream().filter(o -> has(o, "syncedLyrics")).min(closest)
            .or(() -> near.stream().filter(LrcLib::instrumental).min(closest))
            .orElse(near.stream().filter(o -> has(o, "plainLyrics")).min(closest).orElse(null));
    }

    private static Words read(JsonObject o) {
        long now = System.currentTimeMillis();
        if (o == null) return new Words(Status.MISSING, List.of(), now);
        if (instrumental(o)) return new Words(Status.INSTRUMENTAL, List.of(), now);
        if (has(o, "syncedLyrics")) {
            List<Line> lines = parse(o.get("syncedLyrics").getAsString());
            if (!lines.isEmpty()) return new Words(Status.FOUND, List.copyOf(lines), now);
        }
        return new Words(has(o, "plainLyrics") ? Status.UNSYNCED : Status.MISSING, List.of(), now);
    }

    /** LRC text: "[mm:ss.xx] words" lines, sometimes with several stamps on one line, and an optional [offset:ms]. */
    public static List<Line> parse(String lrc) {
        List<Line> out = new ArrayList<>();
        long offset = 0;
        for (String raw : lrc.split("\\R")) {
            Matcher off = OFFSET.matcher(raw);
            if (off.find()) {
                offset = Long.parseLong(off.group(1));
                continue;
            }
            Matcher m = STAMP.matcher(raw);
            List<Long> times = new ArrayList<>();
            int end = 0;
            m.region(0, raw.length());
            while (m.lookingAt()) {
                long ms = Long.parseLong(m.group(1)) * 60_000 + Long.parseLong(m.group(2)) * 1000;
                String frac = m.group(3);
                if (frac != null) ms += Long.parseLong(frac) * (frac.length() == 1 ? 100 : frac.length() == 2 ? 10 : 1);
                times.add(ms);
                end = m.end();
                m.region(end, raw.length());
            }
            if (times.isEmpty()) continue;
            String text = raw.substring(end).strip();
            for (long at : times) out.add(new Line(at, text));
        }
        // A positive offset means the words come sooner.
        if (offset != 0) {
            long shift = offset;
            out.replaceAll(l -> new Line(Math.max(0, l.at() - shift), l.text()));
        }
        out.sort(Comparator.comparingLong(Line::at));
        return out;
    }

    private static boolean has(JsonObject o, String name) {
        return o.has(name) && !o.get(name).isJsonNull() && !o.get(name).getAsString().isBlank();
    }

    private static String query(String... pairs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) sb.append('&');
            sb.append(pairs[i]).append('=').append(URLEncoder.encode(pairs[i + 1], StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /** The JSON answer, or null for "not found". A busy server (5xx) gets one more try a second later. */
    private static JsonElement get(String path) throws IOException, InterruptedException {
        try {
            return getOnce(path);
        } catch (IOException e) {
            Thread.sleep(1000);
            return getOnce(path);
        }
    }

    private static JsonElement getOnce(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(API + path))
            .timeout(Duration.ofSeconds(10))
            .header("User-Agent", AGENT)
            .GET()
            .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 404) return null;
        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
        return JsonParser.parseString(response.body());
    }
}
