package com.cyanite.client.modules.donut;

import com.cyanite.client.modules.render.Nametags;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.Notify;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.ChatFormatting;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Puts each nearby player's balance and bounty on their name tag. It asks the server the same
 * way you would, {@code /bal name} and {@code /bounty name}, one command every couple of seconds
 * so it never floods chat, reads the number out of the reply, and keeps it off your screen.
 * Donut puts each reply in the action bar as well, so that copy is kept off too.
 * Answers are kept for a few minutes before it asks again. Shows on Nametags.
 */
public class BountyBalance extends Module {
    private static final long ANSWER_WITHIN = 3000;
    /** The action-bar copy of a reply can come a little before or after the chat line. */
    private static final long ACTION_BAR_WITHIN = 3000;
    private static final long RETRY_UNKNOWN = 60_000;
    /** A money amount: optional $, digits with commas or a decimal, and an optional K/M/B/T. */
    private static final Pattern MONEY = Pattern.compile("(\\$)?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([KkMmBbTt](?![a-z]))?");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    /** A /bal or /bounty you typed: "bal name", "balance name", "money name", "bounty name". */
    private static final Pattern TYPED = Pattern.compile("(?:bal(?:ance)?|money|bounty)(?:\\s|$)");

    private enum Ask {
        BALANCE("bal", "balance", "bal", "money"),
        BOUNTY("bounty", "bounty");

        final String command;
        final String[] words;

        Ask(String command, String... words) {
            this.command = command;
            this.words = words;
        }
    }

    /** What's known about one player; null fields aren't known yet. */
    private static final class Known {
        Double balance;
        long balanceAt;
        Double bounty;
        long bountyAt;
    }

    private record Pending(String name, Ask ask, long at) {
    }

    private final BoolSetting balance = add(new BoolSetting("Balance", true));
    private final BoolSetting bounty = add(new BoolSetting("Bounty", true));
    private final SliderSetting refresh = add(new SliderSetting("Refresh every", 5, 1, 30, 1).format(v -> (int) v + " min"));
    private final SliderSetting delay = add(new SliderSetting("Between commands", 2, 0.5, 6, 0.5).format(v -> String.format(Locale.ROOT, "%.1fs", v)));
    private final SliderSetting range = add(new SliderSetting("Range", 48, 8, 128, 4).format(v -> (int) v + "m"));
    private final BoolSetting hide = add(new BoolSetting("Hide replies", true));
    private final BoolSetting donutOnly = add(new BoolSetting("Only on Donut SMP", true));

    private final Map<String, Known> known = new HashMap<>();
    private Pending pending;
    /** The last lookup that got its chat reply, and when, so its action-bar copy can be kept off too. */
    private Pending answered;
    private long nextAt;
    /** Set while sending our own lookup, which passes through the command events like one you typed. */
    private boolean sending;
    /** The last /bal or /bounty you typed (lower-case), and when. Its replies are left alone. */
    private String typed;
    private long typedAt;

    public BountyBalance() {
        super(Category.DONUT, "Bounty & Balance", "Shows players' balance and bounty on their name tags");
    }

    @Override
    protected void onEnable() {
        pending = null;
        answered = null;
        nextAt = 0;
        Nametags tags = Modules.get(Nametags.class);
        if (inGame() && tags != null && !tags.isEnabled()) {
            Notify.send("Bounty & Balance", "Turn on Nametags to see them on players", false, false, false);
        }
    }

    @Override
    protected void onDisable() {
        pending = null;
        answered = null;
    }

    @Override
    public String getInfo() {
        return known.isEmpty() ? null : Integer.toString(known.size());
    }

    // ---- Asking

    @Override
    public void onTick() {
        if (mc.getConnection() == null) return;
        if (donutOnly.get() && (mc.getCurrentServer() == null || !mc.getCurrentServer().ip.toLowerCase(Locale.ROOT).contains("donutsmp"))) return;
        long now = System.currentTimeMillis();
        if (pending != null) {
            if (now - pending.at < ANSWER_WITHIN) return;
            // No answer: try this one again in a while rather than straight away.
            store(pending.name, pending.ask, null, now);
            pending = null;
        }
        if (now < nextAt || mc.screen != null) return;
        Pending next = nextQuestion(now);
        if (next == null) return;
        pending = next;
        nextAt = now + (long) (delay.get() * 1000);
        sending = true;
        try {
            mc.getConnection().sendCommand(next.ask.command + " " + next.name);
        } finally {
            sending = false;
        }
    }

    /** COMMAND: remembers a /bal or /bounty you typed, so its replies show as normal. */
    public static void onCommand(String command) {
        BountyBalance self = Modules.get(BountyBalance.class);
        if (self == null || self.sending) return;
        String lower = command.strip().toLowerCase(Locale.ROOT);
        if (!TYPED.matcher(lower).lookingAt()) return;
        self.typed = lower;
        self.typedAt = System.currentTimeMillis();
    }

    /** True if you typed a /bal or /bounty about this player just now. */
    private boolean typedAbout(String name, long now) {
        return typed != null && now - typedAt <= ANSWER_WITHIN && mentions(typed, name);
    }

    /** The nearest player in range with something stale or unknown. */
    private Pending nextQuestion(long now) {
        long fresh = (long) (refresh.get() * 60_000);
        double max = range.get() * range.get();
        List<AbstractClientPlayer> players = new ArrayList<>(mc.level.players());
        players.sort(Comparator.comparingDouble(p -> p.distanceToSqr(mc.player)));
        for (AbstractClientPlayer p : players) {
            if (p == mc.player || p.distanceToSqr(mc.player) > max) continue;
            String name = p.getGameProfile().name();
            if (name == null || !NAME.matcher(name).matches()) continue;
            Known k = known.get(name.toLowerCase(Locale.ROOT));
            if (balance.get() && stale(k == null ? null : k.balance, k == null ? 0 : k.balanceAt, fresh, now)) return new Pending(name, Ask.BALANCE, now);
            if (bounty.get() && stale(k == null ? null : k.bounty, k == null ? 0 : k.bountyAt, fresh, now)) return new Pending(name, Ask.BOUNTY, now);
        }
        return null;
    }

    private static boolean stale(Double value, long at, long fresh, long now) {
        if (at == 0) return true;
        return now - at > (value == null ? RETRY_UNKNOWN : fresh);
    }

    private void store(String name, Ask ask, Double value, long now) {
        Known k = known.computeIfAbsent(name.toLowerCase(Locale.ROOT), n -> new Known());
        if (ask == Ask.BALANCE) {
            k.balance = value;
            k.balanceAt = now;
        } else {
            k.bounty = value;
            k.bountyAt = now;
        }
    }

    // ---- Reading the answer

    /** A server message. @return false to keep it out of chat (it was one of our answers). */
    public static boolean onServerMessage(Component message) {
        BountyBalance self = Modules.get(BountyBalance.class);
        if (self == null || !self.isEnabled() || self.pending == null) return true;
        String text = ChatFormatting.stripFormatting(message.getString());
        if (text == null) return true;
        Pending p = self.pending;
        long now = System.currentTimeMillis();
        if (now - p.at > ANSWER_WITHIN) return true;
        String lower = text.toLowerCase(Locale.ROOT);
        boolean mentionsName = lower.contains(p.name.toLowerCase(Locale.ROOT));
        boolean mentionsWord = false;
        for (String w : p.ask.words) mentionsWord |= lower.contains(w);
        // Someone typing "$5M" in chat right then mustn't count: it has to be about this.
        if (!mentionsName && !mentionsWord && !looksLikeError(lower)) return true;
        // You asked the same thing yourself: still read it, but leave the reply in chat.
        boolean show = !self.hide.get() || self.typedAbout(p.name, now);

        Double value;
        if (looksLikeError(lower)) {
            value = null;
        } else if (p.ask == Ask.BOUNTY && (lower.contains("no bounty") || lower.contains("doesn't have") || lower.contains("does not have")
            || lower.contains("not have a bounty"))) {
            value = 0.0;
        } else {
            value = money(text, p.name);
            // About them, but no number yet: likely a header line. Treat it like the reply and wait for the rest.
            if (value == null) return show;
        }
        self.store(p.name, p.ask, value, now);
        self.pending = null;
        self.answered = new Pending(p.name, p.ask, now);
        return show;
    }

    /** Everything headed for the action bar, from GuiMixin. Null keeps it off: Donut's copy of a reply to our own lookup. */
    public static Component actionBar(Component text) {
        BountyBalance self = Modules.get(BountyBalance.class);
        if (self == null || !self.isEnabled() || !self.hide.get()) return text;
        String plain = ChatFormatting.stripFormatting(text.getString());
        if (plain == null) return text;
        long now = System.currentTimeMillis();
        // It can arrive just before the chat line (still pending) or just after (answered).
        return self.replyTo(self.pending, plain, now) || self.replyTo(self.answered, plain, now) ? null : text;
    }

    /** A line about this lookup's player with a money amount or "bounty" in it, while the lookup is fresh. */
    private boolean replyTo(Pending p, String text, long now) {
        if (p == null || now - p.at > ACTION_BAR_WITHIN || typedAbout(p.name, now) || !mentions(text, p.name)) return false;
        return text.toLowerCase(Locale.ROOT).contains("bounty") || money(text, p.name) != null;
    }

    /** The name as a whole word, so "Steve" doesn't match "Steve_2". */
    private static boolean mentions(String text, String name) {
        return Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(name) + "(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE).matcher(text).find();
    }

    private static boolean looksLikeError(String lower) {
        return lower.contains("unknown command") || lower.contains("not found") || lower.contains("never played") || lower.contains("never joined")
            || lower.contains("doesn't exist") || lower.contains("does not exist") || lower.contains("no player") || lower.contains("invalid");
    }

    /** The money amount in a reply, skipping numbers inside the player's own name. */
    static Double money(String text, String name) {
        String cleaned = text.replaceAll("(?i)" + Pattern.quote(name), " ");
        Double plain = null;
        Matcher m = MONEY.matcher(cleaned);
        while (m.find()) {
            double v;
            try {
                v = Double.parseDouble(m.group(2).replace(",", ""));
            } catch (NumberFormatException e) {
                continue;
            }
            if (m.group(3) != null) {
                v *= switch (Character.toUpperCase(m.group(3).charAt(0))) {
                    case 'K' -> 1e3;
                    case 'M' -> 1e6;
                    case 'B' -> 1e9;
                    default -> 1e12;
                };
            }
            // A $ or a K/M/B makes it money for sure; otherwise keep the first plain number as a fallback.
            if (m.group(1) != null || m.group(3) != null) return v;
            if (plain == null) plain = v;
        }
        return plain;
    }

    // ---- What Nametags shows

    /** Adds this player's balance and bounty to their tag, if known. */
    public void addTo(String name, List<String> parts, List<Integer> colors) {
        Known k = known.get(name.toLowerCase(Locale.ROOT));
        if (k == null) return;
        if (balance.get() && k.balance != null) {
            parts.add("$" + compact(k.balance));
            colors.add(0xFF5BE07A);
        }
        if (bounty.get() && k.bounty != null && k.bounty > 0) {
            parts.add("$" + compact(k.bounty) + " bounty");
            colors.add(0xFFFF7A5C);
        }
    }

    static String compact(double v) {
        if (v >= 1e12) return trim(v / 1e12) + "T";
        if (v >= 1e9) return trim(v / 1e9) + "B";
        if (v >= 1e6) return trim(v / 1e6) + "M";
        if (v >= 1e3) return trim(v / 1e3) + "K";
        return trim(v);
    }

    private static String trim(double v) {
        String s = v >= 100 ? String.format(Locale.ROOT, "%.0f", v) : String.format(Locale.ROOT, "%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }
}
