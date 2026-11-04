package com.cyanite.client.modules.donut;

import com.cyanite.client.gui.Theme;
import com.cyanite.client.render.Badge;
import com.cyanite.client.render.Projection;
import com.cyanite.client.render.Render3D;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.Notify;
import com.cyanite.client.system.Simulation;
import com.cyanite.client.system.WorldScan;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.SliderSetting;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Players you can't see, found by what ticks round them. Chunks only tick within the server's
 * simulation distance of a player: mobs move, turn and get hurt, crops grow, grass spreads,
 * leaves fall, eyeblossoms open and shut. Past your own simulation distance nothing of yours ticks
 * anything, but the server still shows you those chunks (out to your view distance). So whatever
 * ticks out there, in the ring between the two, is someone else's doing: someone within their
 * simulation distance of it, whether or not you can see them (on Donut, people sitting under the
 * made-up rock keep the surface over them ticking). And a mob out there that hasn't moved in a
 * minute and a half says nobody is within reach of it.
 *
 * <p>The guess works like Sus Chunk Finder's: every chunk where something ticked adds to all the
 * chunks within the simulation distance of it, every chunk with mobs standing still rules them
 * out, and what's left that's hottest is where they are, its spread outlined. Two more give a
 * player away outright: a mob hit by a player your client doesn't know, and an item picked up by
 * someone it doesn't know.
 *
 * <p>Things you'd mistake: anything near where you've just been (your own ticking), chunks only
 * just loaded, the moment after you teleport, mobs coming into view at the edge of how far the
 * server shows them (looks like a spawn, isn't), water and lava (they spill over from ticking
 * chunks next door), and changes under Y 32 (Donut swaps made-up rock for real and back as you
 * go up and down). Players you can see explain what ticks round them, so that's left out too.
 *
 * <p>The method is the Anubis client's (GPL-3.0, its "Player Bypass 2"); the code is Cyanite's own.
 */
public class HiddenPlayerFinder extends Module {
    /** Changes under this are Donut swapping its made-up rock in and out. */
    private static final int ABOVE_MADE_UP = 32;
    /** Just loaded, just near you, just teleported: give it this long. */
    private static final long SETTLE = 3000;
    /** A mob counts once in this long, however much it does. */
    private static final long MOB_EVERY = 5000;
    /** Just come into view: what it does at first is the server catching you up. */
    private static final long FRESH = 2000;
    /** How far inside the furthest a kind of mob's been seen it has to appear for it to be a spawn. */
    private static final double INSIDE_VIEW = 24;
    /** One chunk counts for no more than this, however busy (a farm's always going). */
    private static final int MAX_PER_CHUNK = 5;
    private static final int PIN_COLOR = 0xFFFF4DD2;

    private final BoolSetting mobs = add(new BoolSetting("Moving mobs", true));
    private final BoolSetting spawns = add(new BoolSetting("Spawns", true));
    private final BoolSetting blocks = add(new BoolSetting("Ticking blocks", true));
    private final BoolSetting hidden = add(new BoolSetting("Hidden hits & pickups", true));
    private final BoolSetting still = add(new BoolSetting("Rule out still mobs", true));
    private final SliderSetting stillFor = add(new SliderSetting("Still for", 90, 30, 300, 10).format(v -> (int) v + "s"));
    /** 0: what the server says its simulation distance is. */
    private final SliderSetting simulation = add(new SliderSetting("Simulation distance", 0, 0, 16, 1)
        .format(v -> v < 1 ? "Server's" : (int) v + " chunks"));
    private final SliderSetting margin = add(new SliderSetting("Margin", 1, 0, 4, 1).format(v -> (int) v + ((int) v == 1 ? " chunk" : " chunks")));
    private final SliderSetting memory = add(new SliderSetting("Memory", 60, 10, 600, 10).format(v -> (int) v + "s"));
    private final SliderSetting least = add(new SliderSetting("Least signs", 3, 1, 20, 1));
    private final BoolSetting labels = add(new BoolSetting("Labels", true));
    private final BoolSetting notify = add(new BoolSetting("Notify", true));
    private final ColorSetting color = add(new ColorSetting("Color", 0xFF6B4A));

    /** Something ticked: where, what, when. */
    private record Sign(long time, BlockPos pos, String what) {
    }

    /** Someone you can't see, given away: where, how, when. */
    private record Pin(long time, Vec3 pos, String what) {
    }

    /** A guess at where someone is: the area, and what ticked round it. */
    private record Guess(Simulation.Area area, String why) {
        Vec3 center() {
            return new Vec3(area.cx() * 16 + 8, 0, area.cz() * 16 + 8);
        }
    }

    private final Long2ObjectOpenHashMap<List<Sign>> signs = new Long2ObjectOpenHashMap<>();
    /** Chunks with a mob standing still a while: when last seen like that. */
    private final Long2LongOpenHashMap stillAt = new Long2LongOpenHashMap();
    /** When each chunk was last within your own reach, and when it was loaded. */
    private final Long2LongOpenHashMap yoursAt = new Long2LongOpenHashMap();
    private final Long2LongOpenHashMap loadedAt = new Long2LongOpenHashMap();
    /** Mobs: when each came into view, last did anything, last counted. */
    private final Int2LongOpenHashMap seenAt = new Int2LongOpenHashMap();
    private final Int2LongOpenHashMap activeAt = new Int2LongOpenHashMap();
    private final Int2LongOpenHashMap countedAt = new Int2LongOpenHashMap();
    /** How far off each kind of mob has been seen: about how far the server shows them. */
    private final Map<MobCategory, Double> farthest = new EnumMap<>(MobCategory.class);
    private final List<Pin> pins = new ArrayList<>();
    private final LongOpenHashSet told = new LongOpenHashSet();
    private List<Guess> guesses = List.of();
    private Object level;
    private Vec3 lastPos;
    private long lastChunk = Long.MIN_VALUE;
    private long quietUntil;
    private boolean stale;
    private int tick;

    public HiddenPlayerFinder() {
        super(Category.DONUT, "Hidden Player Finder", "Finds players you can't see by what ticks round them, past your own simulation distance");
    }

    @Override
    protected void onEnable() {
        reset();
    }

    @Override
    public String getInfo() {
        return guesses.isEmpty() && pins.isEmpty() ? null : Integer.toString(guesses.size() + pins.size());
    }

    private void reset() {
        signs.clear();
        stillAt.clear();
        yoursAt.clear();
        loadedAt.clear();
        seenAt.clear();
        activeAt.clear();
        countedAt.clear();
        farthest.clear();
        pins.clear();
        told.clear();
        guesses = List.of();
        lastPos = null;
        lastChunk = Long.MIN_VALUE;
        quietUntil = System.currentTimeMillis() + SETTLE;
    }

    private static HiddenPlayerFinder on() {
        HiddenPlayerFinder f = Modules.get(HiddenPlayerFinder.class);
        return f != null && f.isEnabled() && inGame() ? f : null;
    }

    /** Your own reach: as far as your ticking goes, scheduled ticks a chunk further. */
    private int reach() {
        return Simulation.distance(simulation.get().intValue());
    }

    /** How far out the ring starts: past your reach, and a margin past that. */
    private int ringStart() {
        return reach() + 1 + margin.get().intValue();
    }

    // ---- What the server tells us (all on the game thread)

    public static void onChunk(int x, int z) {
        HiddenPlayerFinder f = on();
        if (f != null) f.loadedAt.put(ChunkPos.asLong(x, z), System.currentTimeMillis());
    }

    /** A mob moved, turned, or changed: it's being ticked. */
    public static void onActive(Entity entity) {
        HiddenPlayerFinder f = on();
        if (f == null || !(entity instanceof Mob mob) || mob.isNoAi() || mob.isPassenger()) return;
        long now = System.currentTimeMillis();
        f.activeAt.put(mob.getId(), now);
        if (!f.seenAt.containsKey(mob.getId())) f.seenAt.put(mob.getId(), now);
        double away = Math.hypot(mob.getX() - mc.player.getX(), mob.getZ() - mc.player.getZ());
        f.farthest.merge(mob.getType().getCategory(), away, Math::max);
        if (!f.mobs.get() || now - f.seenAt.get(mob.getId()) < FRESH || now - f.countedAt.get(mob.getId()) < MOB_EVERY) return;
        if (f.sign(mob.blockPosition(), "a " + name(mob) + " moving")) f.countedAt.put(mob.getId(), now);
    }

    public static void onAdded(Entity entity) {
        HiddenPlayerFinder f = on();
        if (f == null) return;
        long now = System.currentTimeMillis();
        f.seenAt.put(entity.getId(), now);
        f.activeAt.put(entity.getId(), now);
        if (f.spawns.get() && entity instanceof Mob mob && f.wellInView(mob)) f.sign(mob.blockPosition(), "a " + name(mob) + " spawning");
    }

    /** Before they go: a mob despawning or dying out there was ticked. */
    public static void onRemoving(IntList ids) {
        HiddenPlayerFinder f = on();
        if (f == null) return;
        long now = System.currentTimeMillis();
        for (int i = 0; i < ids.size(); i++) {
            int id = ids.getInt(i);
            Entity e = mc.level.getEntity(id);
            if (f.spawns.get() && e instanceof Mob mob && now - f.seenAt.getOrDefault(id, now) >= FRESH && f.wellInView(mob)) {
                f.sign(mob.blockPosition(), "a " + name(mob) + " despawning or dying");
            }
            f.seenAt.remove(id);
            f.activeAt.remove(id);
            f.countedAt.remove(id);
        }
    }

    public static void onDamage(ClientboundDamageEventPacket packet) {
        HiddenPlayerFinder f = on();
        if (f == null) return;
        Entity victim = mc.level.getEntity(packet.entityId());
        if (victim == null) return;
        int cause = packet.sourceCauseId();
        if (f.hidden.get() && packet.sourceType().is(DamageTypes.PLAYER_ATTACK) && cause >= 0 && cause != mc.player.getId()
            && mc.level.getEntity(cause) == null) {
            f.pin(victim.position(), "a " + name(victim) + " hit by a player you can't see");
        }
        if (victim instanceof Mob) onActive(victim);
    }

    /** Before the item's gone: who picked it up. */
    public static void onTakeItem(int itemId, int collectorId) {
        HiddenPlayerFinder f = on();
        if (f == null || !f.hidden.get() || collectorId == mc.player.getId()) return;
        Entity item = mc.level.getEntity(itemId);
        if (item == null || mc.level.getEntity(collectorId) != null) return;
        String what = item instanceof ItemEntity stack ? stack.getItem().getHoverName().getString() : name(item);
        f.pin(item.position(), what + " picked up by someone you can't see");
    }

    /** Before the change goes in: what it was. */
    public static void onBlockChanging(BlockPos pos, BlockState now) {
        HiddenPlayerFinder f = on();
        if (f == null || !f.blocks.get() || pos.getY() < ABOVE_MADE_UP) return;
        BlockState was = mc.level.getBlockState(pos);
        if (was == now || was.getBlock() instanceof LiquidBlock || now.getBlock() instanceof LiquidBlock) return;
        String what = was.getBlock() == now.getBlock()
            ? was.getBlock().getName().getString().toLowerCase() + " growing"
            : was.getBlock().getName().getString().toLowerCase() + " turning to " + now.getBlock().getName().getString().toLowerCase();
        f.sign(pos.immutable(), what);
    }

    // ---- Counting

    /** Counts what ticked at {@code pos} if it's out in the ring, nothing explains it, and it's had time to settle. @return whether it counted. */
    private boolean sign(BlockPos pos, String what) {
        long now = System.currentTimeMillis();
        long chunk = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        if (now < quietUntil || !inRing(chunk, now, SETTLE)) return false;
        List<Sign> list = signs.computeIfAbsent(chunk, k -> new ArrayList<>());
        list.add(new Sign(now, pos, what));
        if (list.size() > 64) list.removeFirst();
        stale = true;
        return true;
    }

    private void pin(Vec3 pos, String what) {
        long now = System.currentTimeMillis();
        for (Pin p : pins) if (p.pos().distanceTo(pos) < 8 && now - p.time() < 10_000) return;
        pins.add(new Pin(now, pos, what));
        if (notify.get()) Notify.send("Hidden player", (int) pos.x + ", " + (int) pos.z + " (" + Math.round(pos.distanceTo(mc.player.position())) + "m): " + what, true, true, false);
    }

    /**
     * Out past your reach (and the margin), not near where you've been in the last {@code settle},
     * loaded that long, and not near a player you can see.
     */
    private boolean inRing(long chunk, long now, long settle) {
        long here = mc.player.chunkPosition().toLong();
        if (Simulation.apart(chunk, here) < ringStart()) return false;
        if (now - yoursAt.getOrDefault(chunk, Long.MIN_VALUE / 2) < settle) return false;
        if (now - loadedAt.getOrDefault(chunk, Long.MIN_VALUE / 2) < SETTLE) return false;
        int reach = reach();
        for (Player p : mc.level.players()) {
            if (p != mc.player && Simulation.apart(chunk, p.chunkPosition().toLong()) <= reach + 1) return false;
        }
        return true;
    }

    /** Well inside how far the server shows this kind of mob: a mob appearing or going there really did. */
    private boolean wellInView(Mob mob) {
        if (mob.isNoAi()) return false;
        double far = farthest.getOrDefault(mob.getType().getCategory(), 0.0);
        double away = Math.hypot(mob.getX() - mc.player.getX(), mob.getZ() - mc.player.getZ());
        return far >= INSIDE_VIEW * 2 && away <= far - INSIDE_VIEW;
    }

    private static String name(Entity e) {
        return e.getType().getDescription().getString().toLowerCase();
    }

    @Override
    public void onTick() {
        if (mc.level != level) {
            level = mc.level;
            reset();
        }
        long now = System.currentTimeMillis();
        Vec3 at = mc.player.position();
        // A jump (teleport, respawn): what comes in for a moment after is the server catching up.
        if (lastPos != null && at.distanceToSqr(lastPos) > 64) quietUntil = now + SETTLE;
        lastPos = at;
        long here = mc.player.chunkPosition().toLong();
        if (here != lastChunk || ++tick % 10 == 0) {
            lastChunk = here;
            int r = reach() + 1;
            int cx = ChunkPos.getX(here), cz = ChunkPos.getZ(here);
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) yoursAt.put(ChunkPos.asLong(cx + dx, cz + dz), now);
            }
        }
        if (tick % 20 != 0) return;

        // Mobs out there that haven't moved in a good while: nobody's ticking them.
        long hold = stillFor.get().longValue() * 1000;
        if (still.get()) {
            for (Entity e : WorldScan.entities()) {
                if (!(e instanceof Mob mob) || mob.isNoAi() || mob.isPassenger()) continue;
                long seen = seenAt.getOrDefault(mob.getId(), 0);
                if (seen == 0) {
                    seenAt.put(mob.getId(), now);
                    activeAt.put(mob.getId(), now);
                    continue;
                }
                if (now - seen < hold || now - activeAt.get(mob.getId()) < hold) continue;
                long chunk = ChunkPos.asLong(mob.getBlockX() >> 4, mob.getBlockZ() >> 4);
                if (inRing(chunk, now, hold)) {
                    if (stillAt.put(chunk, now) == 0) stale = true;
                }
            }
        }

        // Forget what's older than the memory.
        long keep = memory.get().longValue() * 1000;
        for (Iterator<Long2ObjectOpenHashMap.Entry<List<Sign>>> it = signs.long2ObjectEntrySet().fastIterator(); it.hasNext(); ) {
            List<Sign> list = it.next().getValue();
            if (list.removeIf(s -> now - s.time() > keep)) stale = true;
            if (list.isEmpty()) it.remove();
        }
        if (stillAt.long2LongEntrySet().removeIf(e -> now - e.getLongValue() > keep)) stale = true;
        if (pins.removeIf(p -> now - p.time() > keep * 5)) stale = true;
        if (stale) {
            stale = false;
            guesses = guess();
        }
    }

    /** Where they are: hottest where things ticked, not where mobs stand still (see {@link Simulation#hottest}). */
    private List<Guess> guess() {
        int reach = reach();
        Long2IntOpenHashMap heat = new Long2IntOpenHashMap();
        LongOpenHashSet ruledOut = new LongOpenHashSet();
        for (Long2ObjectOpenHashMap.Entry<List<Sign>> e : signs.long2ObjectEntrySet()) {
            Simulation.spread(heat, e.getLongKey(), reach, Math.min(MAX_PER_CHUNK, e.getValue().size()));
        }
        for (long chunk : stillAt.keySet()) Simulation.spread(ruledOut, chunk, reach);
        List<Guess> out = new ArrayList<>();
        for (Simulation.Area a : Simulation.hottest(heat, ruledOut, least.get().intValue())) {
            // What ticked within reach of it, the most-seen first.
            Map<String, Integer> kinds = new java.util.HashMap<>();
            long at = ChunkPos.asLong(a.cx(), a.cz());
            for (Long2ObjectOpenHashMap.Entry<List<Sign>> e : signs.long2ObjectEntrySet()) {
                if (Simulation.apart(e.getLongKey(), at) > reach) continue;
                for (Sign s : e.getValue()) kinds.merge(s.what(), 1, Integer::sum);
            }
            List<String> parts = new ArrayList<>();
            kinds.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(3)
                .forEach(k -> parts.add(k.getValue() > 1 ? k.getKey() + " (" + k.getValue() + ")" : k.getKey()));
            Guess g = new Guess(a, String.join(", ", parts));
            out.add(g);
            if (notify.get() && !near(told, at, 2)) {
                told.add(at);
                Vec3 c = g.center();
                Notify.send("Hidden player", "About " + (int) c.x + ", " + (int) c.z + " (" + Math.round(Math.hypot(c.x - mc.player.getX(), c.z - mc.player.getZ()))
                    + "m): " + g.why(), true, true, false);
            }
        }
        return out;
    }

    private static boolean near(LongOpenHashSet chunks, long chunk, int within) {
        for (long c : chunks) if (Simulation.apart(c, chunk) <= within) return true;
        return false;
    }

    // ---- Drawing

    @Override
    public void onRender3D(Render3D render) {
        int rgb = 0xFF000000 | color.get();
        long now = System.currentTimeMillis();
        long keep = memory.get().longValue() * 1000;
        // What ticked: where, fading as it gets older.
        for (List<Sign> list : signs.values()) {
            for (Sign s : list) {
                float left = 1 - (float) (now - s.time()) / keep;
                render.box(new AABB(s.pos()).inflate(0.05), Theme.alpha(rgb, 40 * left), Theme.alpha(rgb, 200 * left), 1.2f, true);
            }
        }
        for (Pin p : pins) {
            render.box(new AABB(p.pos(), p.pos()).inflate(0.6, 1, 0.6), Theme.alpha(PIN_COLOR, 50), Theme.alpha(PIN_COLOR, 230), 2f, true);
        }
        double y = mc.player.getY();
        for (Guess g : guesses) {
            Simulation.Area a = g.area();
            if (a.spread() > 1) {
                render.box(new AABB(a.minX() * 16, y - 0.5, a.minZ() * 16, (a.maxX() + 1) * 16, y - 0.1, (a.maxZ() + 1) * 16), 0, Theme.alpha(rgb, 160), 1.6f, true);
            }
            Vec3 c = g.center();
            for (int i = 0; i < 16; i++) {
                float fade = 1 - i / 16f;
                Vec3 from = new Vec3(c.x, y - 40 + i * 6, c.z);
                render.line(from, from.add(0, 6, 0), Theme.alpha(rgb, 235 * fade * fade), 2.6f, true);
            }
        }
    }

    @Override
    public void onRender2D(GuiGraphics g, float partialTick) {
        if (!labels.get()) return;
        var window = mc.getWindow();
        double midX = window.getWidth() / 2.0;
        double midY = window.getHeight() / 2.0;
        double focus = 90 * window.getHeight() / 1080.0;
        double y = mc.player.getY();
        Object focused = null;
        double focusedOff = focus;
        List<Object[]> shown = new ArrayList<>();
        List<Object> all = new ArrayList<>(guesses);
        all.addAll(pins);
        for (Object o : all) {
            Vec3 c = o instanceof Guess gs ? gs.center().add(0, y + 4, 0) : ((Pin) o).pos().add(0, 1.5, 0);
            double[] screen = Projection.toScreen(c);
            if (screen == null) continue;
            double off = Math.hypot(screen[0] - midX, screen[1] - midY);
            if (off < focusedOff) {
                focused = o;
                focusedOff = off;
            }
            shown.add(new Object[] {o, screen, c});
        }
        for (Object[] o : shown) {
            if (o[0] instanceof Guess gs) {
                Simulation.Area a = gs.area();
                String why = gs.why() + (a.spread() > 1 ? "; somewhere in the " + (a.maxX() - a.minX() + 1) + " by " + (a.maxZ() - a.minZ() + 1) + " outlined" : "");
                Badge.draw(g, "Someone's about here", (Vec3) o[2], why, 0xFF000000 | color.get(), (double[]) o[1], o[0] == focused);
            } else {
                Pin p = (Pin) o[0];
                long ago = (System.currentTimeMillis() - p.time()) / 1000;
                Badge.draw(g, "Hidden player", (Vec3) o[2], p.what() + ", " + ago + "s ago", PIN_COLOR, (double[]) o[1], o[0] == focused);
            }
        }
    }
}
