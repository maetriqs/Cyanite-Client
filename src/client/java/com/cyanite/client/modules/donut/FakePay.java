package com.cyanite.client.modules.donut;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Modules;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.text.NumberFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fakes /pay: cancels the real command and shows Donut's "You paid name $ 1M" in chat and the
 * action bar, as the server would. After that, your balance reads lower and theirs higher
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
    }

    @Override
    protected void onDisable() {
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

    // ---- Commands you send

    /** ALLOW_COMMAND. False cancels the command: every /pay, which never reaches the server. */
    public static boolean allowCommand(String command) {
        FakePay self = Modules.get(FakePay.class);
        if (self == null || !self.isEnabled() || self.sendingQuiet) return true;
        String text = command.strip();
        long now = System.currentTimeMillis();

        Matcher bal = BAL.matcher(text);
        if (bal.matches()) {
            String name = bal.group(1);
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
        String target = m.group(1);
        double amount = amount(m.group(2), m.group(3));
        if (self.fakeBalance != null) self.fakeBalance = Math.max(0, self.fakeBalance - amount);
        self.paid.merge(target.toLowerCase(Locale.ROOT), amount, Double::sum);

        // Donut says it twice: in chat and in the action bar.
        Component line = Component.literal("You paid " + target + " ").withColor(0xFFFFFF)
            .append(Component.literal("$").withColor(DOLLAR))
            .append(Component.literal(" " + BountyBalance.compact(amount)).withColor(0xFFFFFF));
        mc.gui.getChat().addMessage(line);
        mc.gui.setOverlayMessage(line, false);
        return false;
    }

    // ---- What the server says back

    /** ALLOW_GAME, chat only. False hides the line: the answer to Fake Pay's own quiet /bal. */
    public static boolean allowChat(Component message) {
        FakePay self = Modules.get(FakePay.class);
        return self == null || !self.isEnabled() || !self.readQuietly(message);
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
        return self.readQuietly(text) ? null : self.adjust(text);
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
