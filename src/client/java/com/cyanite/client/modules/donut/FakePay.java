package com.cyanite.client.modules.donut;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;

import java.text.NumberFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fakes /pay: cancels the real command and, a server round trip later, shows Donut's "You paid name $ 1M"
 * in chat and the action bar, as the server would. From then on your balance reads lower and theirs higher
 * wherever Donut shows it: /bal, /bal name (each in chat and the action bar) and the sidebar.
 * Nothing is actually sent to the other player.
 */
public class FakePay extends Module {
    /** "pay name 1500", "pay name 1,500", "pay name 1.5k", "pay name 2M": the amount has to end there, so "1m" never reads as 1. */
    private static final Pattern PAY = Pattern.compile(
        "pay\\s+([A-Za-z0-9_]{1,16})\\s+([0-9][0-9,]*(?:\\.[0-9]+)?|\\.[0-9]+)([kmbt])?(?=\\s|$)",
        Pattern.CASE_INSENSITIVE);
    /** "bal", "balance", "bal name". */
    private static final Pattern BAL = Pattern.compile("bal(?:ance)?(?:\\s+([A-Za-z0-9_]{1,16}))?\\s*", Pattern.CASE_INSENSITIVE);
    /** Donut's green dollar sign; the rest of its money lines are plain white. */
    private static final int DOLLAR = 0x00FC00;
    /** A $-prefixed money amount anywhere in a line: "$ 1,234,567,890", "$ 772M". */
    private static final Pattern MONEY_IN = Pattern.compile(
        "\\$\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)(?:\\s*([KkMmBbTt])(?![a-z]))?");
    /** Donut answers in chat and the action bar; both arrive well within this. */
    private static final long WINDOW = 4000L;
    /** Longest a /bal you typed holds back the fake answers typed after it, if no answer to it comes (a name Donut doesn't know). */
    private static final long ANSWER_WAIT = 1000L;

    /** About one round trip to Donut. Each answer lands up to a fifth either side of it, as real ones do. */
    private final SliderSetting delay = add(new SliderSetting("Delay", 200, 0, 1000, 10).format(v -> (int) v + " ms"));

    private Double fakeBalance;
    /** Until then, "You have $ ..." is the answer to Fake Pay's own /bal: read it and keep it off screen. */
    private long quietUntil;
    /** Until then, "You have $ ..." answers a /bal you typed: show the fake balance in it. */
    private long ownUntil;
    /** The player in a "/bal name" you typed, and until when their "has $ ..." gets adjusted. */
    private String otherName;
    private long otherUntil;
    private long nextQuery;
    private int retries;
    private boolean sendingQuiet;
    /** What's been fake-paid to each player (lower-case name), added to their /bal. */
    private final Map<String, Double> paid = new HashMap<>();
    /** Each /pay and /bal you typed, oldest first, until it's answered: the answers come back in this order, as Donut's do. */
    private final ArrayDeque<Typed> typed = new ArrayDeque<>();
    /** When the last fake answer shows; the next one never shows before it. */
    private long lastShowAt;
    /** A /bal that was held back is going out now, for real. */
    private boolean releasing;

    public FakePay() {
        super(Category.DONUT, "Fake Pay", "Fakes /pay: looks real but keeps your money");
    }

    @Override
    public String getInfo() {
        return fakeBalance == null ? null : "$" + BountyBalance.compact(fakeBalance);
    }

    @Override
    protected void onEnable() {
        fakeBalance = null;
        quietUntil = 0;
        ownUntil = 0;
        otherName = null;
        retries = 0;
        nextQuery = 0;
        typed.clear();
        lastShowAt = 0;
    }

    @Override
    protected void onDisable() {
        // Fake answers still on their way never show; a /bal held back behind one goes out now.
        List<String> held = new ArrayList<>();
        for (Typed t : typed) if (t.bal != null && t.answerBy == 0 && t.connection == mc.getConnection()) held.add(t.bal);
        typed.clear();
        for (String command : held) mc.getConnection().sendCommand(command);
        fakeBalance = null;
        otherName = null;
        paid.clear();
    }

    @Override
    public void onTick() {
        long now = System.currentTimeMillis();
        // Ask for your real balance once, quietly, so the fakes have something to start from.
        if (fakeBalance == null && now >= quietUntil && retries < 3 && now >= nextQuery && mc.getConnection() != null) {
            quietUntil = now + WINDOW;
            nextQuery = now + 5000;
            retries++;
            // It passes through allowCommand like one you typed; this tells them apart.
            sendingQuiet = true;
            try {
                mc.getConnection().sendCommand("bal");
            } finally {
                sendingQuiet = false;
            }
        }
    }

    /** Every frame, so an answer shows when it's due rather than on the next tick. */
    @Override
    public void onFrame() {
        long now = System.currentTimeMillis();
        while (!typed.isEmpty()) {
            Typed t = typed.peek();
            if (t.connection != mc.getConnection()) {
                // Left that server: nothing more is coming from it.
                typed.clear();
                lastShowAt = 0;
                return;
            }
            if (t.bal != null) {
                // Its turn: it goes out now, and whatever was typed after it waits for its answer.
                if (t.answerBy == 0) release(t, now);
                if (now < t.answerBy) return;
                typed.poll();
            } else {
                if (now < t.showAt) return;
                typed.poll();
                answerPay(t);
            }
        }
    }

    // ---- Commands you send

    /** ALLOW_COMMAND. False cancels the command: every /pay, which never reaches the server, and a /bal held back behind one. */
    public static boolean allowCommand(String command) {
        FakePay self = Modules.get(FakePay.class);
        if (self == null || !self.isEnabled() || self.sendingQuiet) return true;
        String text = command.strip();
        long now = System.currentTimeMillis();

        Matcher bal = BAL.matcher(text);
        if (bal.matches()) {
            String name = bal.group(1);
            if (!self.releasing) {
                Typed t = new Typed();
                t.bal = text;
                t.balName = name;
                Typed last = self.typed.peekLast();
                self.typed.add(t);
                // Typed while a fake answer is still on its way: it goes out once that shows, so the answers come in order.
                if (last != null && last.answerBy == 0) return false;
                t.answerBy = now + ANSWER_WAIT;
            }
            String me = mc.player != null ? mc.player.getGameProfile().name() : "";
            if (name == null) {
                self.ownUntil = now + WINDOW;
            } else if (name.equalsIgnoreCase(me) || self.paid.containsKey(name.toLowerCase(Locale.ROOT))) {
                self.otherName = name;
                self.otherUntil = now + WINDOW;
            }
            return true;
        }

        Matcher m = PAY.matcher(text);
        if (!m.lookingAt()) return true;
        Typed t = new Typed();
        t.payee = m.group(1);
        t.amount = amount(m.group(2), m.group(3));
        // A round trip later, as Donut would answer, and never ahead of the answer to an earlier /pay.
        t.showAt = self.lastShowAt = Math.max(now + self.roundTrip(), self.lastShowAt);
        self.typed.add(t);
        return false;
    }

    /** The delay, give or take a fifth. */
    private long roundTrip() {
        return Math.round(delay.get() * (0.8 + 0.4 * ThreadLocalRandom.current().nextDouble()));
    }

    /** Sends a /bal that was held back; allowCommand lets it through this time. */
    private void release(Typed t, long now) {
        releasing = true;
        try {
            t.connection.sendCommand(t.bal);
        } finally {
            releasing = false;
        }
        t.answerBy = now + ANSWER_WAIT;
    }

    /** Donut's answer to a /pay, in chat and the action bar. The money moves now, so the sidebar changes with it. */
    private void answerPay(Typed t) {
        if (fakeBalance != null) fakeBalance = Math.max(0, fakeBalance - t.amount);
        paid.merge(t.payee.toLowerCase(Locale.ROOT), t.amount, Double::sum);
        Component line = Component.literal("You paid " + t.payee + " ").withColor(0xFFFFFF)
            .append(Component.literal("$").withColor(DOLLAR))
            .append(Component.literal(" " + BountyBalance.compact(t.amount)).withColor(0xFFFFFF));
        mc.gui.getChat().addMessage(line);
        mc.gui.setOverlayMessage(line, false);
    }

    /** A /pay or /bal you typed, until it's answered. */
    private static final class Typed {
        /** The server it was typed on: whatever's left unanswered goes when you leave it. */
        final ClientPacketListener connection = mc.getConnection();
        /** /pay: who gets how much, and when the fake answer shows. */
        String payee;
        double amount;
        long showAt;
        /** /bal: the command, the player it asks about (null for you), and 0 until it's sent, then the latest it waits for its answer. */
        String bal;
        String balName;
        long answerBy;
    }

    // ---- What the server says back

    /** ALLOW_GAME, chat only. False hides the line: the answer to Fake Pay's own quiet /bal. */
    public static boolean allowChat(Component message) {
        FakePay self = Modules.get(FakePay.class);
        if (self == null || !self.isEnabled()) return true;
        self.noteAnswer(message);
        return !self.readQuietly(message);
    }

    /** MODIFY_GAME, chat only: a /bal answer with the fake amounts in. */
    public static Component modifyChat(Component message) {
        FakePay self = Modules.get(FakePay.class);
        return self == null || !self.isEnabled() ? message : self.adjust(message);
    }

    /** Everything headed for the action bar. Null keeps it off: the action-bar half of the quiet /bal. */
    public static Component actionBar(Component text) {
        FakePay self = Modules.get(FakePay.class);
        if (self == null || !self.isEnabled()) return text;
        self.noteAnswer(text);
        return self.readQuietly(text) ? null : self.adjust(text);
    }

    /** The answer to a /bal you typed: what was typed after it can be answered now. */
    private void noteAnswer(Component message) {
        Typed first = typed.peek();
        if (first == null || first.bal == null || first.answerBy == 0) return;
        String text = plain(message);
        if (money(text) == null) return;
        String lower = text.toLowerCase(Locale.ROOT);
        int n = 0;
        for (Typed t : typed) {
            if (t.bal == null || t.answerBy == 0) return;
            n++;
            if (lower.startsWith(t.balName == null ? "you have" : t.balName.toLowerCase(Locale.ROOT) + " has")) {
                // Donut answers in order, so the ones before it have had their answers (or an error) already.
                for (int i = 0; i < n; i++) typed.poll();
                return;
            }
        }
    }

    /** True for the answer to Fake Pay's own /bal, after taking your real balance from it. A /bal you typed wins. */
    private boolean readQuietly(Component message) {
        long now = System.currentTimeMillis();
        if (now > quietUntil || now <= ownUntil) return false;
        Double real = ownBalance(message);
        if (real == null) return false;
        if (fakeBalance == null) fakeBalance = real;
        return true;
    }

    /** "You have $ X" with your fake balance, or "name has $ X" with what you've paid them added. */
    private Component adjust(Component message) {
        long now = System.currentTimeMillis();
        if (now <= ownUntil) {
            Double real = ownBalance(message);
            if (real != null) {
                if (fakeBalance == null) fakeBalance = real;
                return withAmount(message, fakeBalance);
            }
        }
        if (otherName != null && now <= otherUntil) {
            String text = plain(message);
            String lower = text.toLowerCase(Locale.ROOT);
            if (lower.startsWith(otherName.toLowerCase(Locale.ROOT) + " has")) {
                Double real = money(text);
                if (real != null) {
                    String me = mc.player != null ? mc.player.getGameProfile().name() : "";
                    double shown = otherName.equalsIgnoreCase(me) && fakeBalance != null
                        ? fakeBalance : real + paid.getOrDefault(otherName.toLowerCase(Locale.ROOT), 0.0);
                    return withAmount(message, shown);
                }
            }
        }
        return message;
    }

    /** The amount in Donut's "You have $ 1,234,567,890", or null for any other line. */
    private static Double ownBalance(Component message) {
        String text = plain(message);
        return text.toLowerCase(Locale.ROOT).startsWith("you have") ? money(text) : null;
    }

    /** The first $ amount in a line, or null for none. */
    private static Double money(String text) {
        Matcher m = MONEY_IN.matcher(text);
        return m.find() ? amount(m.group(1), m.group(2)) : null;
    }

    /** "1,500" or "1.5" with an optional K, M, B or T. */
    private static double amount(String number, String suffix) {
        double v = Double.parseDouble(number.replace(",", ""));
        if (suffix == null) return v;
        return v * switch (Character.toLowerCase(suffix.charAt(0))) {
            case 'k' -> 1e3;
            case 'm' -> 1e6;
            case 'b' -> 1e9;
            default -> 1e12;
        };
    }

    /** The line with only its amount changed, written the way the server wrote it: "772M" stays short, "1,234,567,890" stays long. */
    private static Component withAmount(Component message, double amount) {
        String text = plain(message);
        String value = text.substring(SidebarText.valueStart(text)).strip();
        return SidebarText.rewrite(message, value.matches(".*[KkMmBbTt]") ? BountyBalance.compact(amount) : fullNumber(amount), false);
    }

    private static String plain(Component message) {
        String text = ChatFormatting.stripFormatting(message.getString());
        return text == null ? "" : text.strip();
    }

    // ---- Sidebar

    /**
     * Called from GuiMixin for each sidebar line. Replaces the money amount with the fake
     * balance on any line that contains a $-prefixed number. ScoreboardSpoof runs after this,
     * so manual overrides win.
     */
    public Component spoofLine(Component original) {
        if (!isEnabled() || fakeBalance == null) return original;
        String plain = ChatFormatting.stripFormatting(original.getString());
        if (plain == null || !MONEY_IN.matcher(plain).find()) return original;
        // Pass the number only (no $): SidebarText keeps the label including the existing "$".
        return SidebarText.rewrite(original, BountyBalance.compact(fakeBalance), false);
    }

    /** Full integer with commas, matching DonutSMP's own-balance format: "1,234,567,890". */
    private static String fullNumber(double v) {
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.ROOT);
        nf.setMaximumFractionDigits(0);
        nf.setGroupingUsed(true);
        return nf.format((long) v);
    }
}
