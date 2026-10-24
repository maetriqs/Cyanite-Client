package com.cyanite.client.modules.donut;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Notify;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.StringSetting;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Who's staff in the tab list right now. On Donut SMP every mod and admin has a green or purple
 * star by their name in tab, so anyone whose tab name carries a star in one of those colours
 * counts, plus whoever's named in "Also staff" (DrDonutt, who has no star). Listed in the same
 * order as tab, on the HUD's Staff List panel; staff joining or leaving get a chat line.
 * Staff who are vanished aren't in tab, so they can't be listed here either.
 */
public class StaffList extends Module {
    private static final String STARS = "★☆✦✧✩✪✫✬✭✮✯✰✡⋆✶✷✸✹✺✴✵⭐";
    /** After joining or changing world the tab list fills in over a moment: nobody "joined" then. */
    private static final long SETTLE = 3000;
    /** A star by DrDonutt's (or another "Also staff" name's) name when tab gives them none. */
    public static final int NAMED = 0xFFFFC83D;

    private final StringSetting also = add(new StringSetting("Also staff", "DrDonutt").placeholder("names, comma separated"));
    private final BoolSetting notify = add(new BoolSetting("Notify", true));
    private final BoolSetting sound = add(new BoolSetting("Sound", false));
    private final BoolSetting donutOnly = add(new BoolSetting("Only on Donut SMP", true));

    /** One staff member: their name and the colour of their star (ARGB). */
    public record Staff(String name, int star) {
    }

    private final List<Staff> online = new ArrayList<>();
    private final Set<String> known = new HashSet<>();
    private ClientPacketListener connection;
    private ClientLevel level;
    private long settleUntil;
    private int ticks;

    public StaffList() {
        super(Category.DONUT, "Staff List", "Lists the mods and admins online: green or purple stars in tab, and DrDonutt");
    }

    @Override
    protected void onEnable() {
        online.clear();
        known.clear();
        settleUntil = System.currentTimeMillis() + SETTLE;
        ticks = 0;
    }

    @Override
    protected void onDisable() {
        online.clear();
        known.clear();
    }

    @Override
    public String getInfo() {
        return applies() ? Integer.toString(online.size()) : null;
    }

    /** Whether the list means anything here: on Donut, or anywhere with "Only on Donut SMP" off. */
    public boolean applies() {
        if (!donutOnly.get()) return true;
        var server = mc.getCurrentServer();
        return server != null && server.ip.toLowerCase(Locale.ROOT).contains("donutsmp");
    }

    public List<Staff> online() {
        return Collections.unmodifiableList(online);
    }

    @Override
    public void onTick() {
        if (ticks++ % 10 == 0) scan();
    }

    private void scan() {
        long now = System.currentTimeMillis();
        ClientPacketListener conn = mc.getConnection();
        if (conn != connection || mc.level != level) {
            connection = conn;
            level = mc.level;
            settleUntil = now + SETTLE;
        }
        online.clear();
        if (conn == null || !applies()) {
            known.clear();
            return;
        }

        Set<String> named = names(also.get());
        List<PlayerInfo> players = new ArrayList<>(conn.getListedOnlinePlayers());
        // Tab's own order, so ranks come out the way the server sorts them.
        players.sort(Comparator.comparingInt((PlayerInfo p) -> -p.getTabListOrder())
            .thenComparing(p -> p.getTeam() == null ? "" : p.getTeam().getName())
            .thenComparing(p -> p.getProfile().name(), String.CASE_INSENSITIVE_ORDER));
        for (PlayerInfo info : players) {
            String name = info.getProfile().name();
            int star = starColor(mc.gui.getTabList().getNameForDisplay(info));
            if (star != 0) online.add(new Staff(name, star));
            else if (named.contains(name.toLowerCase(Locale.ROOT))) online.add(new Staff(name, NAMED));
        }

        Set<String> current = new LinkedHashSet<>();
        for (Staff s : online) current.add(s.name);
        if (now >= settleUntil && notify.get()) {
            List<String> joined = new ArrayList<>();
            for (String n : current) if (!known.contains(n)) joined.add(n);
            List<String> left = new ArrayList<>();
            for (String n : known) if (!current.contains(n)) left.add(n);
            if (!joined.isEmpty()) Notify.send("Staff List", String.join(", ", joined) + " joined", false, sound.get(), false);
            if (!left.isEmpty()) Notify.send("Staff List", String.join(", ", left) + " left", false, false, false);
        }
        known.clear();
        known.addAll(current);
    }

    private static Set<String> names(String list) {
        Set<String> out = new HashSet<>();
        for (String part : list.split("[,\\s]+")) {
            if (!part.isBlank()) out.add(part.strip().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** The colour (ARGB) of the first green or purple star in a tab name, or 0 for none. */
    static int starColor(Component display) {
        return display.visit((style, text) -> {
            TextColor color = style.getColor();
            if (color == null || !staffHue(color.getValue())) return Optional.<Integer>empty();
            return text.codePoints().anyMatch(StaffList::isStar) ? Optional.of(0xFF000000 | color.getValue()) : Optional.<Integer>empty();
        }, Style.EMPTY).orElse(0);
    }

    /** A star character, or a resource-pack glyph (those live in the private-use blocks). */
    private static boolean isStar(int cp) {
        return STARS.indexOf(cp) >= 0 || cp >= 0xE000 && cp <= 0xF8FF || cp >= 0xF0000;
    }

    /** Green (Donut's mod star is #00FF89) or purple through magenta, and not washed out. */
    private static boolean staffHue(int rgb) {
        float[] hsb = Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, null);
        if (hsb[1] < 0.35f || hsb[2] < 0.35f) return false;
        float hue = hsb[0] * 360;
        return hue >= 70 && hue <= 175 || hue >= 250 && hue <= 335;
    }
}
