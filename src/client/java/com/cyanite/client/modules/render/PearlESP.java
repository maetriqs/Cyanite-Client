package com.cyanite.client.modules.render;

import com.cyanite.client.gui.Theme;
import com.cyanite.client.system.WorldScan;
import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.render.Projection;
import com.cyanite.client.render.Render3D;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Notify;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Ender pearls in flight, the path they'll take and where they'll land: where whoever threw it
 * is about to be. The flight is worked out with the same steps the game uses (a little gravity,
 * a little drag, then move), so the landing spot is where the pearl really comes down. Pearls
 * sitting still in a stasis chamber are marked with whose they are, and holding a pearl shows
 * where yours would land.
 */
public class PearlESP extends Module {
    private static final double GRAVITY = 0.03;
    private static final double AIR_DRAG = 0.99;
    private static final double WATER_DRAG = 0.8;
    private static final double THROW_SPEED = 1.5;
    private static final int MAX_TICKS = 400;
    private static final long STASIS_MS = 1500;
    private static final int PAD = 4;

    private final BoolSetting others = add(new BoolSetting("Other players' pearls", true));
    private final BoolSetting yours = add(new BoolSetting("Your thrown pearls", true));
    private final BoolSetting aim = add(new BoolSetting("Aim preview", true));
    private final BoolSetting path = add(new BoolSetting("Path", true));
    private final BoolSetting landing = add(new BoolSetting("Landing spot", true));
    private final BoolSetting stasis = add(new BoolSetting("Stasis pearls", true));
    private final BoolSetting labels = add(new BoolSetting("Labels", true));
    private final BoolSetting warn = add(new BoolSetting("Warn when one lands near you", false));
    private final SliderSetting warnRange = add(new SliderSetting("Warn range", 8, 2, 32, 1).format(v -> (int) v + "m"));
    private final ColorSetting color = add(new ColorSetting("Color", 0xB45CFF));
    private final ColorSetting aimColor = add(new ColorSetting("Aim color", 0x37D3FF));
    private final SliderSetting width = add(new SliderSetting("Line width", 2, 1, 5, 0.5));

    private final Map<UUID, Anchor> anchors = new HashMap<>();
    /** Each pearl's flight, worked out once a tick and shared by the path, the label and the warning. */
    private final Map<UUID, Flight> flights = new HashMap<>();
    private long flightsAt = Long.MIN_VALUE;
    /** The aim preview, worked out again only when where you're aiming from or at has moved. */
    private Flight aimed;
    private long aimedKey = Long.MIN_VALUE;
    private final Set<UUID> warned = new HashSet<>();
    private ClientLevel level;
    private int count;

    /** Where a pearl has been hanging around, and since when. */
    private record Anchor(Vec3 pos, long since) {
    }

    /** Where a pearl goes: every tick's position, where it stops, and how many ticks that takes. */
    public record Flight(List<Vec3> points, Vec3 end, BlockHitResult hit, int ticks) {
    }

    public PearlESP() {
        super(Category.RENDER, "Pearl ESP", "Shows thrown ender pearls and where they'll land");
    }

    @Override
    public String getInfo() {
        return count == 0 ? null : Integer.toString(count);
    }

    @Override
    protected void onDisable() {
        flights.clear();
        aimed = null;
        anchors.clear();
        warned.clear();
        count = 0;
    }

    @Override
    public void onTick() {
        if (mc.level != level) {
            level = mc.level;
            anchors.clear();
            warned.clear();
        }
        long now = System.currentTimeMillis();
        Set<UUID> seen = new HashSet<>();
        int n = 0;
        for (Entity entity : WorldScan.entities()) {
            if (!(entity instanceof ThrownEnderpearl pearl) || !wanted(pearl)) continue;
            n++;
            UUID id = pearl.getUUID();
            seen.add(id);
            // Bobbing in one spot of water or a bubble column for a while: a stasis chamber, not a throw.
            Anchor anchor = anchors.get(id);
            if (anchor == null || pearl.position().distanceTo(anchor.pos) > 1.2) anchors.put(id, new Anchor(pearl.position(), now));
            if (warn.get() && !isStasis(pearl, now) && !mine(pearl) && !warned.contains(id)) {
                Flight flight = flightOf(pearl);
                if (flight.end.distanceTo(mc.player.position()) <= warnRange.get()) {
                    warned.add(id);
                    Notify.send("Pearl landing near you", ownerName(pearl) + " lands " + Math.round(flight.end.distanceTo(mc.player.position()))
                        + "m away in " + seconds(flight.ticks), true, true, false);
                }
            }
        }
        anchors.keySet().retainAll(seen);
        warned.retainAll(seen);
        count = n;
    }

    private boolean wanted(ThrownEnderpearl pearl) {
        if (!pearl.isAlive()) return false;
        return mine(pearl) ? yours.get() : others.get();
    }

    private boolean mine(ThrownEnderpearl pearl) {
        Entity owner = pearl.getOwner();
        return owner != null && owner == mc.player;
    }

    boolean isStasis(ThrownEnderpearl pearl, long now) {
        Anchor anchor = anchors.get(pearl.getUUID());
        return anchor != null && now - anchor.since > STASIS_MS;
    }

    private static String ownerName(ThrownEnderpearl pearl) {
        Entity owner = pearl.getOwner();
        if (owner instanceof Player player) return player.getName().getString();
        return owner == null ? "Someone" : owner.getName().getString();
    }

    private static String seconds(int ticks) {
        return String.format(java.util.Locale.ROOT, "%.1fs", ticks / 20.0);
    }

    /**
     * Flies a pearl forward from {@code pos} with velocity {@code motion}: gravity, then drag
     * (heavier in water), then move, stopping at the first block in the way, just as the game does.
     */
    public static Flight simulate(Vec3 pos, Vec3 motion, Entity source) {
        ClientLevel level = mc.level;
        List<Vec3> points = new ArrayList<>();
        points.add(pos);
        Vec3 p = pos;
        Vec3 v = motion;
        for (int tick = 1; tick <= MAX_TICKS; tick++) {
            v = v.add(0, -GRAVITY, 0);
            boolean water = level.getFluidState(BlockPos.containing(p)).is(FluidTags.WATER);
            v = v.scale(water ? WATER_DRAG : AIR_DRAG);
            Vec3 next = p.add(v);
            BlockHitResult hit = level.clip(new ClipContext(p, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, source));
            if (hit.getType() != HitResult.Type.MISS) {
                points.add(hit.getLocation());
                return new Flight(points, hit.getLocation(), hit, tick);
            }
            p = next;
            points.add(p);
            if (p.y < level.getMinY() - 16) break;
        }
        return new Flight(points, p, null, MAX_TICKS);
    }

    /** This pearl's flight from where it is this tick, worked out once a tick however often it's asked for. */
    private Flight flightOf(ThrownEnderpearl pearl) {
        long tick = mc.level.getGameTime();
        if (tick != flightsAt) {
            flights.clear();
            flightsAt = tick;
        }
        return flights.computeIfAbsent(pearl.getUUID(), id -> simulate(pearl.position(), pearl.getDeltaMovement(), pearl));
    }

    /** Where a pearl thrown right now would go: from just under your eyes, the way you're looking, plus how you're moving. */
    Flight aimFlight(float partialTick) {
        Player player = mc.player;
        float yaw = player.getViewYRot(partialTick);
        float pitch = player.getViewXRot(partialTick);
        double x = -Mth.sin(yaw * Mth.DEG_TO_RAD) * Mth.cos(pitch * Mth.DEG_TO_RAD);
        double y = -Mth.sin(pitch * Mth.DEG_TO_RAD);
        double z = Mth.cos(yaw * Mth.DEG_TO_RAD) * Mth.cos(pitch * Mth.DEG_TO_RAD);
        Vec3 motion = new Vec3(x, y, z).normalize().scale(THROW_SPEED);
        Vec3 known = player.getKnownMovement();
        motion = motion.add(known.x, player.onGround() ? 0 : known.y, known.z);
        Vec3 start = player.getPosition(partialTick).add(0, player.getEyeHeight() - 0.1, 0);
        // The same aim to a hair (a 64th of a block, a 20th of a degree) lands in the same place: no need to fly it again.
        long key = 31L * (31L * (31L * Math.round(start.x * 64) + Math.round(start.y * 64)) + Math.round(start.z * 64))
            + 1_000_003L * Math.round(yaw * 20) + 998_244_353L * Math.round(pitch * 20) + Math.round(motion.lengthSqr() * 4096);
        if (aimed == null || key != aimedKey) {
            aimed = simulate(start, motion, player);
            aimedKey = key;
        }
        return aimed;
    }

    private boolean holdingPearl() {
        return mc.player.getMainHandItem().is(Items.ENDER_PEARL) || mc.player.getOffhandItem().is(Items.ENDER_PEARL);
    }

    // ---- Drawing

    @Override
    public void onRender3D(Render3D render) {
        float pt = render.partialTick();
        long now = System.currentTimeMillis();
        int rgb = 0xFF000000 | color.get();
        float lineWidth = width.get().floatValue();

        for (Entity entity : WorldScan.entities()) {
            if (!(entity instanceof ThrownEnderpearl pearl) || !wanted(pearl)) continue;
            Vec3 at = pearl.getPosition(pt);
            AABB box = pearl.getBoundingBox().move(at.subtract(pearl.position())).inflate(0.1);
            if (isStasis(pearl, now)) {
                if (stasis.get()) render.box(box, 0x40000000 | color.get(), rgb, lineWidth, true);
                continue;
            }
            render.box(box, 0x50000000 | color.get(), rgb, lineWidth, true);
            drawFlight(render, flightOf(pearl), rgb, lineWidth, at);
        }

        if (aim.get() && mc.screen == null && holdingPearl() && !mc.player.getCooldowns().isOnCooldown(mc.player.getMainHandItem())) {
            drawFlight(render, aimFlight(pt), 0xFF000000 | aimColor.get(), Math.max(1, lineWidth - 0.5f), null);
        }
    }

    /** The path and landing spot; {@code from} is where the pearl's drawn this frame, between ticks, or null. */
    private void drawFlight(Render3D render, Flight flight, int rgb, float lineWidth, Vec3 from) {
        if (path.get()) {
            List<Vec3> points = flight.points;
            for (int i = 1; i < points.size(); i++) {
                Vec3 a = i == 1 && from != null ? from : points.get(i - 1);
                // Fades in along its length, so the near end doesn't cover the screen.
                float t = Math.min(1, i / 6f);
                int argb = Math.round(((rgb >>> 24) * (0.35f + 0.65f * t))) << 24 | (rgb & 0xFFFFFF);
                render.line(a, points.get(i), argb, lineWidth, i > 3);
            }
        }
        if (landing.get() && flight.hit != null) {
            BlockPos block = flight.hit.getBlockPos();
            render.box(new AABB(block).inflate(0.005), 0x30000000 | (rgb & 0xFFFFFF), rgb, lineWidth, true);
            // Where the thrower ends up: a small marker on the very spot.
            render.box(new AABB(flight.end, flight.end).inflate(0.15), 0x80000000 | (rgb & 0xFFFFFF), 0xFFFFFFFF, 1.5f, false);
        }
    }

    @Override
    public void onRender2D(GuiGraphics g, float partialTick) {
        if (!labels.get()) return;
        long now = System.currentTimeMillis();
        int unit = Render2D.scale();
        for (Entity entity : WorldScan.entities()) {
            if (!(entity instanceof ThrownEnderpearl pearl) || !wanted(pearl)) continue;
            boolean still = isStasis(pearl, now);
            if (still && !stasis.get()) continue;
            Vec3 at = pearl.getPosition(partialTick);
            String title = mine(pearl) ? "Your pearl" : ownerName(pearl) + "'s pearl";
            String detail;
            Vec3 anchor;
            if (still) {
                detail = "stasis";
                anchor = at.add(0, 0.5, 0);
            } else {
                Flight flight = flightOf(pearl);
                detail = flight.hit == null ? "falling" : "lands in " + seconds(flight.ticks);
                anchor = landing.get() && flight.hit != null ? flight.end.add(0, 0.6, 0) : at.add(0, 0.5, 0);
            }
            double[] screen = Projection.toScreen(anchor);
            if (screen == null) continue;
            String dist = Math.round(anchor.distanceTo(mc.player.getEyePosition(partialTick))) + "m";
            g.pose().pushMatrix();
            g.pose().translate(Math.round(screen[0]) / (float) unit, Math.round(screen[1]) / (float) unit);
            drawLabel(g, title, detail, dist);
            g.pose().popMatrix();
        }
    }

    private void drawLabel(GuiGraphics g, String title, String detail, String dist) {
        int w = Text.width(title) + 5 + Text.width(detail) + 6 + Text.width(dist) + PAD * 2;
        int h = Text.CAP + PAD * 2;
        int x = -w / 2;
        int y = -h;
        int s = Render2D.scale();
        int rgb = 0xFF000000 | color.get();
        int corner = Math.round(Math.min(Theme.radius(), 3.5f) * s);
        Render2D.pixels(g);
        Render2D.glow(g, x * s, y * s, w * s, h * s, corner, 6 * s, Theme.alpha(rgb, 35 * Theme.glow()));
        Render2D.rect(g, x * s, y * s, w * s, h * s, corner, Theme.BORDER);
        Render2D.rect(g, x * s + 1, y * s + 1, w * s - 2, h * s - 2, Math.max(0, corner - 1), 0xE6101217);
        Render2D.rect(g, x * s + corner, y * s + 1, w * s - 2 * corner, Math.max(1, s / 2), 0, rgb);
        Render2D.end(g);
        int tx = x + PAD;
        Text.draw(g, title, tx, y + PAD, Theme.TEXT);
        tx += Text.width(title) + 5;
        Text.draw(g, detail, tx, y + PAD, Theme.mix(rgb, 0xFFFFFFFF, 0.3f));
        tx += Text.width(detail) + 6;
        Text.draw(g, dist, tx, y + PAD, Theme.TEXT_FAINT);
    }
}
