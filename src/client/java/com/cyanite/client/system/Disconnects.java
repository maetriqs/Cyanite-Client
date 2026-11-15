package com.cyanite.client.system;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Why the connection to a server ended, and whether going back makes sense. A reset or a timed-out
 * connection, a restart, a full server or a proxy that still has you logged in can be waited out;
 * a ban, a dead session, someone else on the account, an anticheat or a staff kick can't (or
 * shouldn't be). It reads the game's own message keys first, so it works in any language, then the
 * text the server sent: DonutSMP, Velocity and BungeeCord, Paper and the usual anticheats.
 */
public final class Disconnects {
    public enum Kind {
        /** The connection dropped (reset, timed out, end of stream): back in soon. */
        DROPPED(1),
        /** Still logged in on the proxy, throttled, joining too fast: a short wait. */
        BUSY(2),
        /** Restarting, closed, full, maintenance, a queue, login servers down: a longer wait. */
        DOWN(6),
        /** Banned, a dead session, logged in elsewhere, kicked by staff or an anticheat: never back. */
        FINAL(0),
        /** Nothing it recognises (a bare "Disconnected", or a custom kick). */
        UNKNOWN(2);

        /** How many times the module's rejoin delay to wait before the first try. */
        public final int scale;

        Kind(int scale) {
            this.scale = scale;
        }
    }

    /** The game's own reasons, by translation key. */
    private static final Map<String, Kind> KEYS = Map.ofEntries(
        Map.entry("disconnect.loginFailedInfo.invalidSession", Kind.FINAL),
        Map.entry("disconnect.loginFailedInfo.insufficientPrivileges", Kind.FINAL),
        Map.entry("disconnect.loginFailedInfo.userBanned", Kind.FINAL),
        Map.entry("disconnect.loginFailedInfo.serversUnavailable", Kind.DOWN),
        Map.entry("disconnect.spam", Kind.FINAL),
        Map.entry("disconnect.exceeded_packet_rate", Kind.FINAL),
        Map.entry("disconnect.timeout", Kind.DROPPED),
        Map.entry("disconnect.endOfStream", Kind.DROPPED),
        Map.entry("disconnect.lost", Kind.DROPPED),
        Map.entry("disconnect.unknownHost", Kind.DROPPED),
        Map.entry("disconnect.packetError", Kind.DROPPED),
        Map.entry("multiplayer.disconnect.authservers_down", Kind.DOWN),
        Map.entry("multiplayer.disconnect.server_shutdown", Kind.DOWN),
        Map.entry("multiplayer.disconnect.server_full", Kind.DOWN),
        Map.entry("multiplayer.disconnect.slow_login", Kind.DOWN),
        Map.entry("multiplayer.disconnect.missing_tags", Kind.DOWN),
        Map.entry("multiplayer.disconnect.invalid_packet", Kind.DROPPED),
        Map.entry("multiplayer.disconnect.configuration_error", Kind.DROPPED),
        Map.entry("multiplayer.disconnect.banned", Kind.FINAL),
        Map.entry("multiplayer.disconnect.banned.reason", Kind.FINAL),
        Map.entry("multiplayer.disconnect.banned.reason.default", Kind.FINAL),
        Map.entry("multiplayer.disconnect.banned.expiration", Kind.FINAL),
        Map.entry("multiplayer.disconnect.banned_ip.reason", Kind.FINAL),
        Map.entry("multiplayer.disconnect.banned_ip.expiration", Kind.FINAL),
        Map.entry("multiplayer.disconnect.ip_banned", Kind.FINAL),
        Map.entry("multiplayer.disconnect.duplicate_login", Kind.FINAL),
        Map.entry("multiplayer.disconnect.not_whitelisted", Kind.FINAL),
        Map.entry("multiplayer.disconnect.kicked", Kind.FINAL),
        Map.entry("multiplayer.disconnect.unverified_username", Kind.FINAL),
        Map.entry("multiplayer.disconnect.name_taken", Kind.FINAL),
        Map.entry("multiplayer.disconnect.incompatible", Kind.FINAL),
        Map.entry("multiplayer.disconnect.outdated_client", Kind.FINAL),
        Map.entry("multiplayer.disconnect.outdated_server", Kind.FINAL),
        Map.entry("multiplayer.disconnect.flying", Kind.FINAL),
        Map.entry("multiplayer.disconnect.idling", Kind.FINAL),
        Map.entry("multiplayer.disconnect.illegal_characters", Kind.FINAL),
        Map.entry("multiplayer.disconnect.invalid_player_movement", Kind.FINAL),
        Map.entry("multiplayer.disconnect.invalid_vehicle_movement", Kind.FINAL),
        Map.entry("multiplayer.disconnect.invalid_entity_attacked", Kind.FINAL),
        Map.entry("multiplayer.disconnect.invalid_player_data", Kind.FINAL),
        Map.entry("multiplayer.disconnect.chat_validation_failed", Kind.FINAL),
        Map.entry("multiplayer.disconnect.unsigned_chat", Kind.FINAL),
        Map.entry("multiplayer.disconnect.out_of_order_chat", Kind.FINAL),
        Map.entry("multiplayer.disconnect.too_many_pending_chats", Kind.FINAL),
        Map.entry("multiplayer.disconnect.bad_chat_index", Kind.FINAL),
        Map.entry("multiplayer.disconnect.expired_public_key", Kind.FINAL),
        Map.entry("multiplayer.disconnect.missing_public_key", Kind.FINAL),
        Map.entry("multiplayer.disconnect.invalid_public_key_signature", Kind.FINAL),
        Map.entry("multiplayer.disconnect.invalid_public_key_signature.new", Kind.FINAL),
        Map.entry("multiplayer.disconnect.code_of_conduct", Kind.FINAL),
        Map.entry("multiplayer.disconnect.transfers_disabled", Kind.FINAL),
        Map.entry("multiplayer.disconnect.unexpected_query_response", Kind.FINAL)
    );

    /** Maintenance first: its messages often say "whitelist", which on its own means you're not let in. */
    private static final Pattern MAINTENANCE = words("maintenance", "under construction");

    private static final Pattern FINAL = words(
        // A dead login: needs the launcher, not another try.
        "invalid session", "failed to verify username", "not logged in(to)? ?(to )?your (minecraft|microsoft) account",
        "session (has )?expired", "restart(ing)? your (game|client|minecraft|launcher)", "update your (game|client|minecraft)",
        "public key", "invalid signature", "multiplayer is disabled", "banned from playing online",
        "account (is |has been )?(suspended|locked|disabled|terminated)",
        // Banned, or not let in.
        "\\bbann?(ed)?\\b", "blacklist", "\\bsuspended\\b", "\\bpunish", "white-?list", "not whitelisted",
        // Someone else on the account: coming back would kick them, and they'd kick you.
        "logged in from another", "another location", "logged in elsewhere", "logged in from somewhere",
        "someone (else )?(has )?(logged|joined)", "duplicate login", "joined from another",
        // Anticheats, mod checks and the vanilla movement and packet kicks.
        "cheat", "\\bhack", "unfair advantage", "\\billegal\\b", "disallowed", "forbidden", "blocked (mod|client)",
        "modified client", "\\bmods?\\b.{0,30}\\b(not allowed|detected|remove)", "client (is )?not allowed",
        "\\b(fly|flying|flight)\\b", "invalid (move|movement|player movement|vehicle movement)", "moved (too quickly|wrongly)",
        "too many packets", "packet (spam|limit|rate|flood)", "exceed(ed|ing)? (the )?packet", "sending (packets|too many)",
        "bad ?packets", "kill ?aura", "\\bx-?ray\\b", "\\bnuker\\b", "auto ?click", "suspicious", "\\bspam",
        "\\b(grim(ac)?|vulcan|polar|intave|matrix|verus|spartan|karhu|themis|negativity|nocheatplus|ncp|aac|watchdog|anti-?cheat)\\b",
        // Kicked for standing still: the run had stalled.
        "\\bafk\\b", "\\bidl(e|ing)\\b", "inactiv",
        // Wrong version, name or account.
        "outdated (client|server)", "incompatible client", "unsupported (client|version|protocol)",
        "please use (minecraft )?\\d", "only compatible with", "name (is )?(already )?taken", "invalid (user)?name",
        "\\bvpns?\\b", "too many accounts", "\\balt(ernate)? accounts?\\b", "multi-?account",
        "code of conduct", "resource ?pack", "chat (message )?validation", "out-of-order chat", "unacknowledged chat",
        "reordered chat", "illegal characters",
        // Staff.
        "by (an? )?(staff( member)?|operator|admin(istrator)?|moderator|owner)"
    );

    private static final Pattern BUSY = words(
        "already connected", "already (trying to )?connect", "already (logged in|online|playing|on (the|this) server)",
        "logging in too fast", "logged in too (fast|quickly)", "joining too (fast|quickly)", "throttl",
        "wait (a (moment|bit|few seconds)|\\d+ ?(s|sec|secs|seconds?)\\b|before)", "reconnecting too",
        "too many (connections|login attempts|logins|joins)", "rate ?limit", "try again in \\d+"
    );

    private static final Pattern DOWN = words(
        "restart", "reboot", "shut ?down", "shutting down", "\\bqueue", "crash", "updat(e|ing)",
        "server (is |has )?(closed|closing|stopping|stopped|offline|down|full|starting|loading|not (online|ready|available|responding|reachable))",
        "proxy (is )?(restarting|shutting|closed|closing|stopping|reloading)", "\\bis full\\b", "server full", "too many players",
        "no (available|fallback|lobby|hub|default) servers?", "no servers? (are )?available", "could not connect to a (default|fallback)",
        "unable to connect", "(can ?not|can't|could ?not|couldn't|failed to) connect", "try again later", "please try again",
        "not reachable", "unreachable", "connection refused", "connection timed out", "authentication (servers?|service)",
        "took too long to log in", "lost connection to (the )?(server|proxy|backend|lobby)", "encountered a problem",
        "internal (server )?(connection )?error", "internal error occurred", "went down", "being (restarted|updated)",
        "back (soon|shortly|in a)", "closed by (the )?server", "server closed"
    );

    private static final Pattern DROPPED = words(
        "connection reset", "reset by peer", "forcibly closed", "broken pipe", "end of stream",
        "connection (was )?(lost|closed|aborted|dropped)", "connection.{0,20}aborted", "timed ?out", "time ?out",
        "closedchannel", "no route to host", "network is unreachable", "unknown ?host", "unresolved",
        "internal exception", "ioexception", "socketexception", "nativeioexception", "decoderexception", "encoderexception",
        "network protocol error", "badly compressed", "packet .{0,40}(too (big|large)|larger than)", "lost connection"
    );

    /** Anything else that says you were kicked: a person did that, or a plugin for a reason it didn't give. */
    private static final Pattern KICKED = words("\\bkick");

    private static final Pattern NOISE = Pattern.compile("§.");
    private static final Pattern EXCEPTION = Pattern.compile("(?i)internal exception: |(?:[a-z0-9_]+\\.)+[a-z0-9_$]*(?:exception|error): ");
    private static final Pattern SPACE = Pattern.compile("\\s+");

    /** When a module last left on purpose (Auto Log, Base Guard, ...): that's never rejoined. */
    private static long leftAt;

    private Disconnects() {
    }

    /** What kind of disconnect this was. */
    public static Kind judge(Component reason) {
        Kind byKey = null;
        for (String key : keys(reason)) {
            Kind kind = KEYS.get(key);
            if (kind == Kind.FINAL) return Kind.FINAL;
            if (kind != null && (byKey == null || kind.scale > byKey.scale)) byKey = kind;
        }
        Kind byText = judge(text(reason));
        if (byText == Kind.FINAL) return Kind.FINAL;
        if (byKey != null) return byKey;
        return byText;
    }

    /** What kind of disconnect a plain message is (lower or upper case, colour codes and all). */
    public static Kind judge(String text) {
        String s = NOISE.matcher(text).replaceAll("").toLowerCase(Locale.ROOT);
        if (MAINTENANCE.matcher(s).find()) return Kind.DOWN;
        if (FINAL.matcher(s).find()) return Kind.FINAL;
        if (BUSY.matcher(s).find()) return Kind.BUSY;
        if (DOWN.matcher(s).find()) return Kind.DOWN;
        if (DROPPED.matcher(s).find()) return Kind.DROPPED;
        if (KICKED.matcher(s).find()) return Kind.FINAL;
        return Kind.UNKNOWN;
    }

    /** The reason on one line, without colour codes. */
    public static String text(Component reason) {
        return SPACE.matcher(NOISE.matcher(reason.getString()).replaceAll("")).replaceAll(" ").strip();
    }

    /** Short enough for a toast, without the Java noise: "Internal Exception: java.net.SocketException: Connection reset" is "Connection reset". */
    public static String brief(String text, int max) {
        String plain = EXCEPTION.matcher(text).replaceAll("").strip();
        if (plain.isEmpty()) plain = text;
        return plain.length() <= max ? plain : plain.substring(0, max - 1).stripTrailing() + "…";
    }

    /** Leaves the server on purpose, with this on the disconnect screen. Modules waiting to rejoin don't. */
    public static void leave(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return;
        leftAt = System.currentTimeMillis();
        mc.getConnection().getConnection().disconnect(Stealth.disconnectReason(text));
    }

    /** A module left on purpose just now. */
    public static boolean leftOnPurpose() {
        return System.currentTimeMillis() - leftAt < 5000;
    }

    /** Every translation key in the message, arguments included. */
    private static List<String> keys(Component component) {
        List<String> out = new ArrayList<>();
        collect(component, out, 0);
        return out;
    }

    private static void collect(Component component, List<String> out, int depth) {
        if (depth > 8) return;
        if (component.getContents() instanceof TranslatableContents t) {
            out.add(t.getKey());
            for (Object arg : t.getArgs()) {
                if (arg instanceof Component c) collect(c, out, depth + 1);
            }
        }
        for (Component sibling : component.getSiblings()) collect(sibling, out, depth + 1);
    }

    private static Pattern words(String... patterns) {
        return Pattern.compile(String.join("|", patterns));
    }
}
