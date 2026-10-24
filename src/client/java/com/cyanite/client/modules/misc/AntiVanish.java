package com.cyanite.client.modules.misc;

import com.cyanite.client.gui.Theme;
import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.hud.Hud;
import com.cyanite.client.render.Projection;
import com.cyanite.client.render.Render3D;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.Notify;
import com.cyanite.client.system.setting.BoolSetting;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.AbstractChestBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Spots staff watching you from vanish. A vanish plugin hides a player's body and tab entry, but
 * not every trace of them, so this watches the traces:
 * <ul>
 * <li>Left without leaving: someone drops off the tab list and the server, which normally
 * announces leaves, says nothing. It learns whether this server announces leaves before it
 * trusts that, so a server that never does doesn't flag everyone who logs off.</li>
 * <li>Spectators: anyone in spectator mode in the tab list.</li>
 * <li>Hidden players: a player moving around near you who isn't in the tab list, or one who
 * is but is invisible.</li>
 * <li>Silent chests: a chest, barrel or shulker box near you opening with nobody there.</li>
 * </ul>
 * None of these is proof on its own, so each line says what it actually saw.
 */
public class AntiVanish extends Module {
    /** How long to wait for a leave or join message after the tab list changes. */
    private static final long SETTLE = 3000;
    /** Leave messages usually come just before the tab entry goes. */
    private static final long BEFORE = 6000;
    /** After joining or switching servers the tab list fills in all at once. */
    private static final long GRACE = 6000;
    /** More drops than this in one go is a server switch or a tab plugin reshuffling, not people. */
    private static final int MASS = 6;
    private static final double CONTAINER_RANGE = 32;
    private static final double OPENER_REACH = 7;
    private static final long CONTAINER_KEEP = 60_000;
    private static final long GONE_KEEP = 20_000;
    private static final long REPEAT = 60_000;
    private static final int MAX_ROWS = 6;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final String[] LEAVE_WORDS = {"left", "quit", "disconnect", "logged out", "logged off", "logout", "leave", "[-]", "(-)"};
    private static final String[] JOIN_WORDS = {"joined", "join", "logged in", "login", "connected", "welcome", "[+]", "(+)"};

    enum Reason {
        VANISHED("vanished", 0xFF5B5B),
        UNSURE("dropped off tab", 0xFFB347),
        SPECTATOR("spectator", 0xB98CFF),
        HIDDEN("hidden from tab", 0xFF5B5B),
        INVISIBLE("invisible", 0xFFB347),
        CONTAINER("opened unseen", 0xFF5B5B);

        final String label;
        final int rgb;

        Reason(String label, int rgb) {
            this.label = label;
            this.rgb = rgb;
        }
    }

    /** One thing worth knowing about: a row in the list, and a marker if it has a place. */
    static final class Suspect {
        final UUID id;
        final String name;
        final Reason reason;
        final long since;
        long seen;
        Vec3 where;
        BlockPos block;

        Suspect(UUID id, String name, Reason reason, long now) {
            this.id = id;
            this.name = name;
            this.reason = reason;
            this.since = now;
            this.seen = now;
        }
    }

    private record Change(UUID id, String name, long at) {
    }

    private record Heard(long at, String line) {
    }

    private enum Trust { YES, NO, UNKNOWN }

    private final BoolSetting tabCheck = add(new BoolSetting("Tab list check", true));
    private final BoolSetting spectators = add(new BoolSetting("Spectators", true));
    private final BoolSetting hidden = add(new BoolSetting("Hidden players", true));
    private final BoolSetting containers = add(new BoolSetting("Silent chests", true));
    private final BoolSetting list = add(new BoolSetting("On-screen list", true));
    private final BoolSetting markers = add(new BoolSetting("Markers", true));
    private final BoolSetting toast = add(new BoolSetting("Toast", true));
    private final BoolSetting sound = add(new BoolSetting("Sound", true));
    private final BoolSetting flash = add(new BoolSetting("Flash taskbar", false));

    private final Map<String, Suspect> suspects = new LinkedHashMap<>();
    private final Map<String, Long> alerted = new HashMap<>();
    private final ArrayDeque<Heard> chat = new ArrayDeque<>();
    private final List<Change> drops = new ArrayList<>();
    private final List<Change> arrivals = new ArrayList<>();
    private final Map<UUID, Vec3> firstSeen = new HashMap<>();
    private final Map<BlockPos, Integer> viewers = new HashMap<>();
    private final Set<BlockPos> openBarrels = new HashSet<>();

    private ClientPacketListener connection;
    private ClientLevel level;
    private Map<UUID, String> tab;
    private long graceUntil;
    private long ownContainerAt;
    private int ticks;
    private int leaves;
    private int announcedLeaves;
    private int joins;
    private int announcedJoins;
    private boolean toldNoLeaves;

    public AntiVanish() {
        super(Category.MISC, "Anti Vanish", "Warns you when staff might be watching you in vanish");
    }

    @Override
    public String getInfo() {
        return suspects.isEmpty() ? null : Integer.toString(suspects.size());
    }

    @Override
    protected void onEnable() {
        reset();
        graceUntil = System.currentTimeMillis() + 1000;
    }

    @Override
    protected void onDisable() {
        reset();
    }

    private void reset() {
        suspects.clear();
        alerted.clear();
        chat.clear();
        drops.clear();
        arrivals.clear();
        firstSeen.clear();
        viewers.clear();
        openBarrels.clear();
        tab = null;
        connection = null;
        level = null;
        leaves = 0;
        announcedLeaves = 0;
        joins = 0;
        announcedJoins = 0;
        toldNoLeaves = false;
    }

    // ---- Hooks

    /** Every chat and server line, for telling a real leave from a vanish. */
    public static void onChat(String line) {
        AntiVanish self = Modules.get(AntiVanish.class);
        if (self == null || !self.isEnabled()) return;
        String plain = ChatFormatting.stripFormatting(line);
        if (plain == null) return;
        long now = System.currentTimeMillis();
        self.chat.addLast(new Heard(now, plain.toLowerCase(Locale.ROOT)));
        while (self.chat.size() > 300 || now - self.chat.peekFirst().at > 60_000) self.chat.pollFirst();
    }

    /** A block event: for chests and shulker boxes, how many people have it open. */
    public static void onBlockEvent(BlockPos pos, Block block, int type, int data) {
        AntiVanish self = Modules.get(AntiVanish.class);
        if (self == null || !self.isEnabled() || type != 1) return;
        if (!(block instanceof AbstractChestBlock<?>) && !(block instanceof ShulkerBoxBlock)) return;
        BlockPos key = pos.immutable();
        int before = self.viewers.getOrDefault(key, 0);
        if (data <= 0) self.viewers.remove(key);
        else self.viewers.put(key, data);
        if (data > before) self.opened(key, block instanceof ShulkerBoxBlock ? "shulker box" : block == Blocks.ENDER_CHEST ? "ender chest" : "chest");
    }

    /** A block changed: barrels show they're open in their block state instead. */
    public static void onBlockChanged(BlockPos pos, BlockState state) {
        AntiVanish self = Modules.get(AntiVanish.class);
        if (self == null || !self.isEnabled()) return;
        if (!state.is(Blocks.BARREL) || !state.getValue(BarrelBlock.OPEN)) {
            if (!self.openBarrels.isEmpty()) self.openBarrels.remove(pos);
            return;
        }
        if (self.openBarrels.add(pos.immutable())) self.opened(pos.immutable(), "barrel");
    }

    /** Whether anything is listening, so packet hooks can skip work. */
    public static boolean watching() {
        AntiVanish self = Modules.get(AntiVanish.class);
        return self != null && self.isEnabled();
    }

    // ---- Every tick

    @Override
    public void onTick() {
        ClientPacketListener conn = mc.getConnection();
        LocalPlayer me = mc.player;
        if (conn == null || me == null) return;
        long now = System.currentTimeMillis();
        if (conn != connection) {
            reset();
            connection = conn;
            graceUntil = now + GRACE;
        }
        if (mc.level != level) {
            // New world or server behind a proxy: the tab list is rebuilt, and containers start over.
            level = mc.level;
            tab = null;
            drops.clear();
            arrivals.clear();
            viewers.clear();
            openBarrels.clear();
            firstSeen.clear();
            suspects.values().removeIf(s -> s.reason == Reason.CONTAINER || s.reason == Reason.HIDDEN || s.reason == Reason.INVISIBLE);
            graceUntil = Math.max(graceUntil, now + GRACE);
        }
        if (me.containerMenu != me.inventoryMenu) ownContainerAt = now;

        UUID self = me.getUUID();
        Map<UUID, String> listed = new HashMap<>();
        Map<UUID, String> spectating = new HashMap<>();
        for (PlayerInfo info : conn.getListedOnlinePlayers()) {
            UUID id = info.getProfile().id();
            String name = info.getProfile().name();
            if (id == null || id.equals(self) || !real(id, name)) continue;
            listed.put(id, name);
            if (info.getGameMode() == GameType.SPECTATOR) spectating.put(id, name);
        }

        if (tab == null || now < graceUntil) {
            tab = listed;
        } else {
            List<UUID> gone = new ArrayList<>();
            for (UUID id : tab.keySet()) if (!listed.containsKey(id)) gone.add(id);
            List<UUID> came = new ArrayList<>();
            for (UUID id : listed.keySet()) if (!tab.containsKey(id)) came.add(id);
            if (gone.size() <= MASS && came.size() <= MASS * 2) {
                for (UUID id : gone) drops.add(new Change(id, tab.get(id), now));
                for (UUID id : came) arrivals.add(new Change(id, listed.get(id), now));
            }
            tab = listed;
        }
        settleDrops(conn, now);
        settleArrivals(now);
        checkSpectators(spectating, now);
        if (++ticks % 10 == 0) scanPlayers(now);
        suspects.values().removeIf(s -> s.reason == Reason.CONTAINER && now - s.since > CONTAINER_KEEP);
        alerted.values().removeIf(at -> now - at > REPEAT * 5);
    }

    /** A real account: an online (v4) or offline-mode (v3) id and a name a player could have. NPCs fail this. */
    private static boolean real(UUID id, String name) {
        int v = id.version();
        return (v == 4 || v == 3) && name != null && NAME.matcher(name).matches();
    }

    private void settleDrops(ClientPacketListener conn, long now) {
        for (Iterator<Change> it = drops.iterator(); it.hasNext(); ) {
            Change drop = it.next();
            if (now - drop.at < SETTLE) continue;
            it.remove();
            // Back already: the tab list just refreshed them.
            if (tab.containsKey(drop.id)) continue;
            suspects.remove(key(Reason.SPECTATOR, drop.id));
            boolean said = announced(drop.name, drop.at, LEAVE_WORDS);
            // Out of the tab list but the server still has them: nobody does that by logging off.
            boolean connected = conn.getPlayerInfo(drop.id) != null;
            if (!connected) {
                leaves++;
                if (said) announcedLeaves++;
            }
            if (said || !tabCheck.get()) continue;
            if (connected) {
                flag(drop.id, drop.name, Reason.VANISHED, now, drop.name + " vanished: gone from the tab list, but still connected");
                continue;
            }
            switch (leaveTrust()) {
                case YES -> flag(drop.id, drop.name, Reason.VANISHED, now, drop.name + " vanished: dropped off the tab list without a leave message");
                case UNKNOWN -> flag(drop.id, drop.name, Reason.UNSURE, now, drop.name
                    + " dropped off the tab list without a leave message. Could be vanish; no leave messages seen here yet to compare");
                case NO -> {
                    suspects.values().removeIf(s -> s.reason == Reason.UNSURE);
                    if (!toldNoLeaves) {
                        toldNoLeaves = true;
                        Notify.send("Anti Vanish", "This server doesn't announce leaves, so vanishing looks the same as logging off. "
                            + "The spectator, hidden player and chest checks still work", false, false, false);
                    }
                }
            }
        }
    }

    private void settleArrivals(long now) {
        for (Iterator<Change> it = arrivals.iterator(); it.hasNext(); ) {
            Change arrival = it.next();
            if (now - arrival.at < SETTLE) continue;
            it.remove();
            if (!tab.containsKey(arrival.id)) continue;
            boolean said = announced(arrival.name, arrival.at, JOIN_WORDS);
            joins++;
            if (said) announcedJoins++;
            Suspect was = suspects.remove(key(Reason.VANISHED, arrival.id));
            if (was == null) was = suspects.remove(key(Reason.UNSURE, arrival.id));
            if (!tabCheck.get()) continue;
            if (was != null) {
                Notify.send("Anti Vanish", arrival.name + " is back in the tab list" + (said ? " (rejoined)" : ", no join message: out of vanish"), false, false, false);
            } else if (!said && joinTrust() == Trust.YES) {
                Notify.send("Anti Vanish", arrival.name + " showed up without a join message. Probably just came out of vanish, and may have been watching",
                    false, false, false);
            }
        }
    }

    private Trust leaveTrust() {
        return trust(leaves, announcedLeaves);
    }

    private Trust joinTrust() {
        return trust(joins, announcedJoins);
    }

    /** Whether this server announces these: once it has, mostly, a missing message means something. */
    private static Trust trust(int seen, int announced) {
        if (announced >= 1 && announced * 2 >= seen) return Trust.YES;
        if (seen >= 4) return Trust.NO;
        return Trust.UNKNOWN;
    }

    /** Whether a line saying {@code name} came and went (by {@code words}) around {@code at}. */
    private boolean announced(String name, long at, String[] words) {
        String n = name.toLowerCase(Locale.ROOT);
        for (Heard heard : chat) {
            if (heard.at < at - BEFORE || heard.at > at + SETTLE + 500 || !mentions(heard.line, n)) continue;
            for (String word : words) if (heard.line.contains(word)) return true;
            // "[-] Name", "» Name": a line that's little more than the name itself.
            if (heard.line.replace(n, "").replaceAll("[^a-z]", "").length() <= 4) return true;
        }
        return false;
    }

    private static boolean mentions(String line, String name) {
        for (int i = line.indexOf(name); i >= 0; i = line.indexOf(name, i + 1)) {
            int end = i + name.length();
            if ((i == 0 || !wordChar(line.charAt(i - 1))) && (end >= line.length() || !wordChar(line.charAt(end)))) return true;
        }
        return false;
    }

    private static boolean wordChar(char c) {
        return c == '_' || Character.isLetterOrDigit(c);
    }

    private void checkSpectators(Map<UUID, String> spectating, long now) {
        suspects.values().removeIf(s -> s.reason == Reason.SPECTATOR && !spectating.containsKey(s.id));
        if (!spectators.get()) return;
        spectating.forEach((id, name) -> {
            if (!suspects.containsKey(key(Reason.SPECTATOR, id))) flag(id, name, Reason.SPECTATOR, now, name + " is in spectator mode");
        });
    }

    /** Players you can actually see in the world that the tab list says aren't there, or who are invisible. */
    private void scanPlayers(long now) {
        Set<UUID> present = new HashSet<>();
        for (AbstractClientPlayer p : mc.level.players()) {
            if (p == mc.player) continue;
            UUID id = p.getUUID();
            String name = p.getGameProfile().name();
            if (!real(id, name)) continue;
            present.add(id);
            Reason reason = null;
            if (!tab.containsKey(id)) {
                // NPCs aren't in the tab list either, but they stand still.
                Vec3 first = firstSeen.computeIfAbsent(id, k -> p.position());
                if (first.distanceTo(p.position()) >= 1) reason = Reason.HIDDEN;
            } else if (p.isInvisible()) {
                reason = Reason.INVISIBLE;
            }
            if (reason == null || !hidden.get()) continue;
            Suspect known = suspects.get(key(reason, id));
            if (known == null) {
                int dist = (int) Math.round(p.distanceTo(mc.player));
                String text = reason == Reason.HIDDEN
                    ? name + " is " + dist + "m from you but hidden from the tab list. Likely vanished and watching"
                    : name + " is invisible, " + dist + "m from you";
                known = flag(id, name, reason, now, text);
            }
            known.where = p.position();
            known.seen = now;
        }
        firstSeen.keySet().retainAll(present);
        suspects.values().removeIf(s -> (s.reason == Reason.HIDDEN || s.reason == Reason.INVISIBLE)
            && (!hidden.get() || now - s.seen > GONE_KEEP));
    }

    /** A container near you just opened. Yours, or anyone you can see beside it, is fine. */
    private void opened(BlockPos pos, String kind) {
        LocalPlayer me = mc.player;
        if (!containers.get() || me == null || mc.level == null) return;
        long now = System.currentTimeMillis();
        Vec3 c = Vec3.atCenterOf(pos);
        // Further out, a real player opening it might be past where the server shows players.
        if (me.position().distanceTo(c) > CONTAINER_RANGE) return;
        if ((me.containerMenu != me.inventoryMenu || now - ownContainerAt < 1500) && me.getEyePosition().distanceTo(c) < OPENER_REACH) return;
        // Someone in the tab list standing next to it explains it. Someone missing from the tab
        // list doesn't: that's the vanished player.
        for (Player p : mc.level.players()) {
            if (p != me && (tab == null || tab.containsKey(p.getUUID())) && p.getEyePosition().distanceTo(c) < OPENER_REACH) return;
        }
        // Copper golems sort items into chests.
        for (Entity e : mc.level.getEntities((Entity) null, new AABB(pos).inflate(3), e -> !(e instanceof Player))) {
            if (BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath().equals("copper_golem")) return;
        }
        String where = pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
        int dist = (int) Math.round(me.position().distanceTo(c));
        Suspect s = flag(null, capital(kind) + " at " + where, Reason.CONTAINER, now,
            "A " + kind + " at " + where + " opened with nobody there (" + dist + "m from you). Someone vanished is likely right beside it");
        s.block = pos;
        s.where = c;
    }

    private Suspect flag(UUID id, String name, Reason reason, long now, String message) {
        String key = id == null ? reason + ":" + name : key(reason, id);
        Suspect s = new Suspect(id, name, reason, now);
        suspects.remove(key);
        suspects.put(key, s);
        Long last = alerted.get(key);
        if (last == null || now - last > REPEAT) {
            alerted.put(key, now);
            // A hunch gets a chat line; something firmer gets your attention.
            boolean firm = reason != Reason.UNSURE && reason != Reason.INVISIBLE;
            Notify.send("Anti Vanish", message, firm && toast.get(), firm && sound.get(), firm && flash.get());
        }
        return s;
    }

    private static String key(Reason reason, UUID id) {
        return reason + ":" + id;
    }

    /** What it currently suspects, newest first. */
    List<Suspect> suspects() {
        List<Suspect> out = new ArrayList<>(suspects.values());
        out.sort((a, b) -> Long.compare(b.since, a.since));
        return out;
    }

    // ---- Drawing

    @Override
    public void onRender3D(Render3D render) {
        if (!markers.get()) return;
        float pulse = (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 180.0));
        for (Suspect s : suspects.values()) {
            int rgb = s.reason.rgb;
            if (s.block != null) {
                render.box(new AABB(s.block).inflate(0.02), (int) (40 + 50 * pulse) << 24 | rgb, 0xFF000000 | rgb, 2f, true);
            } else if (s.where != null && (s.reason == Reason.HIDDEN || s.reason == Reason.INVISIBLE)) {
                Entity e = entity(s.id);
                AABB box = e != null ? e.getBoundingBox().move(e.getPosition(render.partialTick()).subtract(e.position()))
                    : new AABB(s.where.x - 0.3, s.where.y, s.where.z - 0.3, s.where.x + 0.3, s.where.y + 1.8, s.where.z + 0.3);
                render.box(box, 0x30000000 | rgb, 0xFF000000 | rgb, 2f, true);
            }
        }
    }

    private Entity entity(UUID id) {
        if (id == null || mc.level == null) return null;
        for (AbstractClientPlayer p : mc.level.players()) if (p.getUUID().equals(id)) return p;
        return null;
    }

    @Override
    public void onRender2D(GuiGraphics g, float partialTick) {
        if (suspects.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (markers.get()) {
            int unit = Render2D.scale();
            Vec3 eye = mc.player.getEyePosition(partialTick);
            for (Suspect s : suspects.values()) {
                if (s.where == null) continue;
                Entity e = entity(s.id);
                Vec3 top = e != null ? e.getPosition(partialTick).add(0, e.getBbHeight() + 0.4, 0)
                    : s.block != null ? Vec3.atCenterOf(s.block).add(0, 0.9, 0) : s.where.add(0, 2.2, 0);
                double[] screen = Projection.toScreen(top);
                if (screen == null) continue;
                g.pose().pushMatrix();
                g.pose().translate(Math.round(screen[0]) / (float) unit, Math.round(screen[1]) / (float) unit);
                String title = s.reason == Reason.CONTAINER ? "Opened by someone unseen" : s.name;
                tag(g, title, Math.round(top.distanceTo(eye)) + "m", s.reason.rgb);
                g.pose().popMatrix();
            }
        }
        if (list.get()) drawList(g, now);
    }

    private static void tag(GuiGraphics g, String title, String detail, int rgb) {
        int pad = 3;
        int w = Text.width(title) + 6 + Text.width(detail) + pad * 2;
        int h = Text.CAP + pad * 2;
        int x = -w / 2;
        int y = -h;
        int s = Render2D.scale();
        int color = 0xFF000000 | rgb;
        Render2D.pixels(g);
        Render2D.rect(g, x * s, y * s, w * s, h * s, 3 * s, Theme.BORDER);
        Render2D.rect(g, x * s + 1, y * s + 1, w * s - 2, h * s - 2, 3 * s - 1, 0xE6101217);
        Render2D.rect(g, x * s + 3 * s, y * s + 1, w * s - 6 * s, Math.max(1, s / 2), 0, color);
        Render2D.end(g);
        Text.draw(g, title, x + pad, y + pad, Theme.TEXT);
        Text.draw(g, detail, x + pad + Text.width(title) + 6, y + pad, Theme.TEXT_FAINT);
    }

    /** A small panel down the left side: who, what it saw, and how long ago. */
    private void drawList(GuiGraphics g, long now) {
        List<Suspect> rows = suspects();
        int more = Math.max(0, rows.size() - MAX_ROWS);
        if (more > 0) rows = rows.subList(0, MAX_ROWS);
        String title = "Anti Vanish";
        String count = suspects.size() == 1 ? "1 sign" : suspects.size() + " signs";
        int line = Text.CAP + 5;
        int pad = 6;
        int nameW = 0;
        int restW = 0;
        for (Suspect s : rows) {
            nameW = Math.max(nameW, Text.width(StreamerMode.mask(s.name)));
            restW = Math.max(restW, Text.width(s.reason.label) + 8 + Text.width(age(now - s.since)));
        }
        int w = Math.max(Text.width(title) + 8 + Text.width(count), 10 + nameW + 8 + restW) + pad * 2;
        int h = pad * 2 + line * (rows.size() + 1) + (more > 0 ? line : 0) + 2;
        int x = 4;
        int y = Math.max(30, Hud.screenHeight() / 3);
        int s = Render2D.scale();
        int accent = 0xFFFF5B5B;
        Render2D.pixels(g);
        Render2D.glow(g, x * s, y * s, w * s, h * s, 3 * s, 6 * s, Theme.alpha(accent, 45 * Theme.glow()));
        Render2D.rect(g, x * s, y * s, w * s, h * s, 3 * s, 0xE6101217);
        Render2D.rect(g, x * s, y * s + 3 * s, Math.max(1, s), h * s - 6 * s, 0, accent);
        for (int i = 0; i < rows.size(); i++) {
            int dot = 4 * s;
            int cy = (y + pad + line * (i + 1) + 2) * s + (Text.CAP * s - dot) / 2;
            Render2D.rect(g, (x + pad) * s, cy, dot, dot, dot / 2, 0xFF000000 | rows.get(i).reason.rgb);
        }
        Render2D.end(g);

        Text.draw(g, title, x + pad, y + pad, Theme.TEXT);
        Text.draw(g, count, x + pad + Text.width(title) + 8, y + pad, Theme.TEXT_FAINT);
        for (int i = 0; i < rows.size(); i++) {
            Suspect r = rows.get(i);
            int ty = y + pad + line * (i + 1) + 2;
            int tx = x + pad + 10;
            Text.draw(g, StreamerMode.mask(r.name), tx, ty, Theme.TEXT);
            tx += nameW + 8;
            Text.draw(g, r.reason.label, tx, ty, 0xFF000000 | Theme.mix(0xFF000000 | r.reason.rgb, 0xFFFFFFFF, 0.2f));
            Text.draw(g, age(now - r.since), tx + Text.width(r.reason.label) + 8, ty, Theme.TEXT_FAINT);
        }
        if (more > 0) Text.draw(g, "+" + more + " more", x + pad + 10, y + pad + line * (rows.size() + 1) + 2, Theme.TEXT_FAINT);
    }

    private static String age(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + "s";
        if (s < 3600) return s / 60 + "m";
        return s / 3600 + "h";
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
