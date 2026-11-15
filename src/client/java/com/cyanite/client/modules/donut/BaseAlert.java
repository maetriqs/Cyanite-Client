package com.cyanite.client.modules.donut;

import com.cyanite.client.config.Config;
import com.cyanite.client.gui.Theme;
import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.hud.BasePicture;
import com.cyanite.client.render.CameraShot;
import com.cyanite.client.render.Projection;
import com.cyanite.client.modules.misc.StreamerMode;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.Depths;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Notify;
import com.cyanite.client.system.Webhook;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.SliderSetting;
import com.cyanite.client.system.setting.StringSetting;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrushableBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.SculkCatalystBlockEntity;
import net.minecraft.world.level.block.entity.SculkShriekerBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Watches the storage in your loaded chunks, groups what sits close together, and tells you when
 * a group looks like somebody's base rather than a structure the world made.
 *
 * <p>Every container adds to its group's score. Things only players make (shulker boxes, ender
 * chests, double chests, hoppers) count for a lot. Things worldgen also places (single chests,
 * barrels, furnaces, dispensers) count for a little, and for nothing next to a sign of a natural
 * structure: a spawner, trial chamber, ancient city, village bell or suspicious sand/gravel.
 * Chest minecarts are entities, not blocks, so mineshafts never count at all.
 *
 * <p>A new base also gets its picture taken, as if from Freecam: a camera placed where every
 * container's in frame and nothing's in the way (CameraShot). Inside solid rock is best: from in
 * there the rock isn't drawn, so the picture's a cutaway of the base. It shows on the HUD (Base
 * Picture) and goes in the screenshots folder.
 *
 * <p>With Discord on, every find goes to your webhook too: what's there, where, how deep, how far
 * and the picture. Bases found close together in time (as close as the alert's ding allows) go out
 * as one message, once their pictures are in. The webhook is a secret setting, like Base Guard's.
 */
public class BaseAlert extends Module {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int SCAN_INTERVAL = 20;
    private static final double LINK = 12;
    private static final double STRUCTURE_REACH = 12;
    private static final double SAME_BASE = 48;
    private static final int MAX_CONTAINERS = 4000;
    /** The picture's vertical field of view, in degrees. */
    private static final float PICTURE_FOV = 70;
    /** How many containers a camera spot is judged by (spread over the base). */
    private static final int PICTURE_TARGETS = 24;
    /** Camera angles tried, best first: how far it looks down, and how far round from your side of the base. */
    private static final float[] PICTURE_PITCHES = {50, 35, 65, 80, 20};
    private static final float[] PICTURE_TURNS = {0, 45, -45, 90, -90, 135, -135, 180};
    private static final double[] PICTURE_DISTANCES = {1, 1.4};
    private static final String FOOTER = "Cyanite Base Alert";
    /** Discord takes at most this many embeds in a message. */
    private static final int MAX_EMBEDS = 10;
    /** Pictures go to Discord at most this wide, and at most this much of them in one message (it takes 10 MB). */
    private static final int UPLOAD_WIDTH = 1280;
    private static final int MAX_UPLOAD = 8_000_000;
    /** A burst goes out this long after its first find, whatever's still to come. */
    private static final long BURST_LIMIT_MS = 30_000;
    /** A picture still not in this long after the camera last had something to do isn't coming (it dropped it). */
    private static final long PICTURE_GIVE_UP_MS = 3_000;
    private static final String[] COMPASS = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};

    enum Kind {
        SHULKER("Shulker box", 4, false),
        ENDER("Ender chest", 3, false),
        DOUBLE("Double chest", 2, false),
        HOPPER("Hopper", 2, false),
        CHEST("Chest", 1, true),
        BARREL("Barrel", 1, true),
        CRAFTER("Crafter", 1, true),
        FURNACE("Furnace", 0.5, true),
        DISPENSER("Dispenser", 0.5, true);

        final String label;
        final double weight;
        /** Worldgen places these too, so they count for nothing beside a natural structure. */
        final boolean natural;

        Kind(String label, double weight, boolean natural) {
            this.label = label;
            this.weight = weight;
            this.natural = natural;
        }
    }

    private record Container(BlockPos pos, Kind kind) {
    }

    private record Base(Vec3 center, double score, Map<Kind, Integer> counts, boolean nearStructure, List<BlockPos> containers) {
    }

    /** A find on its way to Discord, and its picture once that's in. Only touched on the game's thread. */
    private static final class Sighting {
        final JsonObject embed;
        final String summary;
        final long distance;
        /** The camera's been asked for its picture, which isn't in yet. */
        boolean waiting;
        /** The picture's in and being made a PNG. */
        boolean encoding;
        byte[] png;

        Sighting(JsonObject embed, String summary, long distance) {
            this.embed = embed;
            this.summary = summary;
            this.distance = distance;
        }
    }

    private final SliderSetting threshold = add(new SliderSetting("Threshold", 8, 3, 30, 1).format(v -> (int) v + " points"));
    private final BoolSetting markers = add(new BoolSetting("Markers", true));
    private final ColorSetting color = add(new ColorSetting("Color", 0x6BE675));
    private final BoolSetting toast = add(new BoolSetting("Toast", true));
    private final BoolSetting sound = add(new BoolSetting("Sound", true));
    private final BoolSetting flash = add(new BoolSetting("Flash taskbar", true));
    private final BoolSetting picture = add(new BoolSetting("Picture", true));
    private final BoolSetting savePicture = add(new BoolSetting("Save picture", true));
    private final BoolSetting discord = add(new BoolSetting("Discord", false));
    private final StringSetting webhook = add(new StringSetting("Webhook", "", 200).placeholder("paste a Discord webhook URL").secret());
    private final BoolSetting sendCoords = add(new BoolSetting("Send coords", true));
    private final BoolSetting pingEveryone = add(new BoolSetting("Ping everyone", false));
    private final BoolSetting test = add(new BoolSetting("Send test message", false));

    private final List<Vec3> announced = new ArrayList<>();
    private List<Base> bases = List.of();
    private ClientLevel level;
    private int ticks;

    /** The finds of the burst going on now, for one Discord message. */
    private final List<Sighting> burst = new ArrayList<>();
    private long burstStarted;
    private long burstLast;
    private long cameraBusyAt;
    private boolean toldNoWebhook;

    public BaseAlert() {
        super(Category.DONUT, "Base Alert", "Tells you when storage nearby looks like a base, shows you a picture of it and can post it to Discord");
        test.onChange(() -> {
            if (!test.get()) return;
            test.set(false);
            String url = webhook.get().strip();
            if (!Webhook.valid(url)) {
                Notify.send("Base Alert", "Paste a Discord webhook URL into Webhook first", true, false, false);
                return;
            }
            JsonObject embed = Webhook.embed("Base Alert test", "If you can see this, Base Alert can post its finds here.", color.get(), List.of(), FOOTER);
            Webhook.post("Base Alert", url, Webhook.message("Base Alert", "", Webhook.noPings(), List.of(embed)), List.of(),
                "Test message sent. Check the channel.");
        });
    }

    @Override
    public String getInfo() {
        return Integer.toString(bases.size());
    }

    @Override
    protected void onEnable() {
        ticks = SCAN_INTERVAL;
        toldNoWebhook = false;
    }

    @Override
    protected void onDisable() {
        // What's been found still goes, without the pictures not in yet.
        post();
        bases = List.of();
        announced.clear();
        level = null;
    }

    @Override
    public void onTick() {
        if (mc.level != level) {
            // The camera drops its pictures when the world changes.
            post();
            level = mc.level;
            announced.clear();
            bases = List.of();
        }
        postWhenReady();
        if (++ticks < SCAN_INTERVAL) return;
        ticks = 0;

        List<Container> containers = new ArrayList<>();
        List<BlockPos> structures = new ArrayList<>();
        collect(containers, structures);

        List<Base> found = new ArrayList<>();
        for (List<Container> group : cluster(containers)) {
            Base base = score(group, structures);
            if (base.score >= threshold.get()) found.add(base);
        }
        found.sort(Comparator.comparingDouble((Base b) -> b.center.distanceToSqr(mc.player.position())));
        bases = found;

        for (Base base : found) {
            boolean known = false;
            for (Vec3 seen : announced) {
                if (seen.distanceTo(base.center) < SAME_BASE) {
                    known = true;
                    break;
                }
            }
            if (known) continue;
            announced.add(base.center);
            // The popup only fits a line, so it gets where; chat gets what's there too.
            Notify.send("Possible base", where(base), toast.get(), sound.get(), flash.get());
            Notify.send("Contents", summary(base), false, false, false);
            Sighting sighting = posting() ? sighting(base) : null;
            if (picture.get()) photograph(base, sighting);
        }
    }

    private void collect(List<Container> containers, List<BlockPos> structures) {
        ChunkPos center = mc.player.chunkPosition();
        int radius = mc.options.getEffectiveRenderDistance();
        for (int cx = center.x - radius; cx <= center.x + radius; cx++) {
            for (int cz = center.z - radius; cz <= center.z + radius; cz++) {
                if (!mc.level.hasChunk(cx, cz)) continue;
                LevelChunk chunk = mc.level.getChunk(cx, cz);
                for (BlockEntity entity : chunk.getBlockEntities().values()) {
                    if (isStructureSign(entity)) {
                        structures.add(entity.getBlockPos());
                        continue;
                    }
                    Kind kind = kindOf(entity);
                    if (kind != null && containers.size() < MAX_CONTAINERS) containers.add(new Container(entity.getBlockPos(), kind));
                }
            }
        }
    }

    private static boolean isStructureSign(BlockEntity entity) {
        return entity instanceof SpawnerBlockEntity || entity instanceof TrialSpawnerBlockEntity || entity instanceof VaultBlockEntity
            || entity instanceof SculkShriekerBlockEntity || entity instanceof SculkCatalystBlockEntity
            || entity instanceof BellBlockEntity || entity instanceof BrushableBlockEntity;
    }

    private static Kind kindOf(BlockEntity entity) {
        if (entity instanceof ShulkerBoxBlockEntity) return Kind.SHULKER;
        if (entity instanceof EnderChestBlockEntity) return Kind.ENDER;
        if (entity instanceof ChestBlockEntity) {
            var state = entity.getBlockState();
            if (!(state.getBlock() instanceof ChestBlock) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return Kind.CHEST;
            // Count the pair once, from its left half.
            return state.getValue(ChestBlock.TYPE) == ChestType.LEFT ? Kind.DOUBLE : null;
        }
        if (entity instanceof BarrelBlockEntity) return Kind.BARREL;
        if (entity instanceof HopperBlockEntity) return Kind.HOPPER;
        if (entity instanceof CrafterBlockEntity) return Kind.CRAFTER;
        if (entity instanceof AbstractFurnaceBlockEntity) return Kind.FURNACE;
        if (entity instanceof DispenserBlockEntity) return Kind.DISPENSER;
        return null;
    }

    /** Groups containers that sit within a short hop of each other, chaining through neighbours. */
    private static List<List<Container>> cluster(List<Container> containers) {
        int n = containers.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        double linkSq = LINK * LINK;
        for (int i = 0; i < n; i++) {
            BlockPos a = containers.get(i).pos;
            for (int j = i + 1; j < n; j++) {
                if (a.distSqr(containers.get(j).pos) <= linkSq) parent[find(parent, i)] = find(parent, j);
            }
        }
        Map<Integer, List<Container>> groups = new java.util.HashMap<>();
        for (int i = 0; i < n; i++) groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(containers.get(i));
        return new ArrayList<>(groups.values());
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static Base score(List<Container> group, List<BlockPos> structures) {
        double x = 0, y = 0, z = 0;
        for (Container c : group) {
            x += c.pos.getX() + 0.5;
            y += c.pos.getY() + 0.5;
            z += c.pos.getZ() + 0.5;
        }
        Vec3 center = new Vec3(x / group.size(), y / group.size(), z / group.size());

        boolean nearStructure = false;
        for (BlockPos sign : structures) {
            for (Container c : group) {
                if (c.pos.distSqr(sign) <= STRUCTURE_REACH * STRUCTURE_REACH) {
                    nearStructure = true;
                    break;
                }
            }
            if (nearStructure) break;
        }

        Map<Kind, Integer> counts = new EnumMap<>(Kind.class);
        List<BlockPos> positions = new ArrayList<>(group.size());
        double score = 0;
        for (Container c : group) {
            counts.merge(c.kind, 1, Integer::sum);
            positions.add(c.pos);
            if (!(nearStructure && c.kind.natural)) score += c.kind.weight;
        }
        return new Base(center, score, counts, nearStructure, positions);
    }

    /** {@code sighting}, if given, gets a copy of the picture for Discord. */
    private void photograph(Base base, Sighting sighting) {
        CameraShot.View view = frame(base.containers);
        if (view == null) return;
        String contents = summary(base);
        Path file = savePicture.get()
            ? mc.gameDirectory.toPath().resolve("screenshots").resolve("base-alert_" + new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss").format(new Date()) + ".png")
            : null;
        if (sighting != null) {
            sighting.waiting = true;
            cameraBusyAt = System.currentTimeMillis();
        }
        CameraShot.take(view, base.containers, image -> {
            if (sighting != null) keep(sighting, image);
            BasePicture.show(image, base.center, contents, file);
        });
    }

    /**
     * Where to put the picture's camera: at the angle that sees the most containers past whatever's in
     * the way, just far enough back that all of them (and a block and a half round them) fit in frame.
     * Tried looking down from your side of the base first, then round it, steeper and shallower, then
     * from further back.
     */
    private CameraShot.View frame(List<BlockPos> containers) {
        AABB box = new AABB(containers.get(0));
        for (BlockPos p : containers) box = box.minmax(new AABB(p));
        // A block and a half round them, so what they stand in shows too.
        box = box.inflate(1.5);
        Vec3 center = box.getCenter();
        double aspect = mc.getWindow().getWidth() / (double) Math.max(1, mc.getWindow().getHeight());
        double up = Math.tan(Math.toRadians(PICTURE_FOV / 2));
        double across = up * aspect;

        List<BlockPos> targets = new ArrayList<>();
        int step = Math.max(1, (containers.size() + PICTURE_TARGETS - 1) / PICTURE_TARGETS);
        for (int i = 0; i < containers.size(); i += step) targets.add(containers.get(i));

        Vec3 toBase = center.subtract(mc.player.getEyePosition());
        float facing = (float) Math.toDegrees(Math.atan2(-toBase.x, toBase.z));
        CameraShot.View best = null;
        int bestSeen = -1;
        for (double further : PICTURE_DISTANCES) {
            for (float pitch : PICTURE_PITCHES) {
                for (float turn : PICTURE_TURNS) {
                    float yaw = facing + turn;
                    Vec3 forward = Vec3.directionFromRotation(pitch, yaw);
                    Vec3 eye = center.subtract(forward.scale(fit(box, forward, up, across) * further));
                    if (!usable(eye)) continue;
                    int seen = 0;
                    for (BlockPos target : targets) if (clear(eye, target)) seen++;
                    if (seen > bestSeen) {
                        bestSeen = seen;
                        best = new CameraShot.View(eye, yaw, pitch, PICTURE_FOV);
                        if (seen == targets.size()) return best;
                    }
                }
            }
        }
        return best;
    }

    /**
     * How far back from the middle of {@code box} a camera looking along {@code forward} has to be for
     * every corner of it to be in frame. {@code up} and {@code across} are the tangents of half the
     * field of view each way.
     */
    private static double fit(AABB box, Vec3 forward, double up, double across) {
        Vec3 right = forward.cross(new Vec3(0, 1, 0)).normalize();
        Vec3 top = right.cross(forward);
        Vec3 center = box.getCenter();
        double distance = 0;
        for (int i = 0; i < 8; i++) {
            Vec3 corner = new Vec3((i & 1) == 0 ? box.minX : box.maxX, (i & 2) == 0 ? box.minY : box.maxY, (i & 4) == 0 ? box.minZ : box.maxZ)
                .subtract(center);
            double needed = Math.max(Math.abs(corner.dot(right)) / across, Math.abs(corner.dot(top)) / up) - corner.dot(forward);
            distance = Math.max(distance, needed);
        }
        return Math.max(2, distance * 1.05);
    }

    /** A camera can go here: its chunk is loaded and it's not in water, lava or powder snow (they fog the picture). */
    private boolean usable(Vec3 eye) {
        BlockPos pos = BlockPos.containing(eye);
        if (!mc.level.hasChunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()))) return false;
        return mc.level.getFluidState(pos).isEmpty() && !mc.level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.POWDER_SNOW);
    }

    /**
     * Whether a camera at {@code eye} would see the container at {@code target}. The game only draws
     * a block's face where it meets something see-through, and only from the front, so solid rock
     * is invisible from inside: the line may run through rock, then open space, but anything solid
     * after open space shows a face to the camera and hides what's behind. A solid container (a
     * barrel, say) shows by its open faces, so one turned to the camera has to be in sight.
     */
    private boolean clear(Vec3 eye, BlockPos target) {
        Vec3 middle = Vec3.atCenterOf(target);
        if (!solid(target)) return inSight(eye, middle);
        for (Direction side : Direction.values()) {
            if (solid(target.relative(side))) continue;
            // Just off the face, in the open block beside it.
            Vec3 face = middle.add(side.getStepX() * 0.55, side.getStepY() * 0.55, side.getStepZ() * 0.55);
            boolean turnedToCamera = (eye.x - face.x) * side.getStepX() + (eye.y - face.y) * side.getStepY() + (eye.z - face.z) * side.getStepZ() > 0;
            if (turnedToCamera && inSight(eye, face)) return true;
        }
        return false;
    }

    /** Whether the line from {@code eye} gets to {@code to} without meeting the face of anything solid. */
    private boolean inSight(Vec3 eye, Vec3 to) {
        int steps = (int) Math.ceil(eye.distanceTo(to) * 4);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos last = BlockPos.containing(eye);
        boolean lastSolid = solid(last);
        for (int i = 1; i <= steps; i++) {
            Vec3 at = eye.lerp(to, i / (double) steps);
            pos.set(at.x, at.y, at.z);
            if (pos.equals(last)) continue;
            boolean solid = solid(pos);
            if (solid && !lastSolid) return false;
            lastSolid = solid;
            last = pos.immutable();
        }
        return true;
    }

    private boolean solid(BlockPos pos) {
        return mc.level.getBlockState(pos).isSolidRender();
    }

    // ---- Discord

    /** Discord's on with a webhook that looks right. Says so once if it's on without one. */
    private boolean posting() {
        if (!discord.get()) return false;
        if (Webhook.valid(webhook.get().strip())) return true;
        if (!toldNoWebhook) {
            toldNoWebhook = true;
            Notify.send("Base Alert", webhook.isBlank() ? "Discord's on, but there's no webhook to post to" : "That webhook URL doesn't look right", true, false, false);
        }
        return false;
    }

    /** Puts {@code base} in the burst going on now (or starts one), with what's known of it now. */
    private Sighting sighting(Base base) {
        long now = System.currentTimeMillis();
        if (burst.size() >= MAX_EMBEDS) post();
        if (burst.isEmpty()) burstStarted = now;
        burstLast = now;

        BlockPos p = BlockPos.containing(base.center);
        long distance = Math.round(base.center.distanceTo(mc.player.position()));
        StringBuilder found = new StringBuilder();
        base.counts.forEach((kind, n) -> found.append("**").append(n).append("** ").append(name(kind, n)).append('\n'));
        if (base.nearStructure) found.append("By a spawner or structure, so chests, barrels, furnaces and dispensers there don't count");
        List<String[]> fields = new ArrayList<>();
        fields.add(new String[] {"Found", found.toString().strip()});
        fields.add(new String[] {"Score", Math.round(base.score) + " points (alerts from " + threshold.get().intValue() + ")"});
        if (sendCoords.get()) fields.add(new String[] {"Coordinates", StreamerMode.mask(p.getX() + ", " + p.getY() + ", " + p.getZ())});
        fields.add(new String[] {"Depth", depth(p)});
        fields.add(new String[] {"Distance", distance + "m " + bearing(base.center)});
        fields.add(new String[] {"Dimension", dimension()});
        Sighting sighting = new Sighting(Webhook.embed("Possible base", null, color.get(), fields, FOOTER), summary(base), distance);
        burst.add(sighting);
        return sighting;
    }

    /**
     * How deep it is. The bases that matter are under the deepslate, below Y 0. Where the server's
     * hiding what's underground (Depths), that's said too: there may be more below.
     */
    private String depth(BlockPos p) {
        int y = p.getY();
        String line = "Y " + y;
        if (mc.level.dimension().equals(Level.OVERWORLD)) line += y < 0 ? ", " + -y + " down in the deepslate" : ", above the deepslate";
        int floor = Depths.floorAt(mc.level, p.getX(), p.getZ());
        if (floor > 0) line += "\nThe server's hiding what's under Y " + floor + " there";
        return line;
    }

    /** Which way {@code to} is from you: "north-east of you, 40 below". */
    private String bearing(Vec3 to) {
        Vec3 me = mc.player.position();
        double dx = to.x - me.x, dz = to.z - me.z;
        long down = Math.round(me.y - to.y);
        if (dx * dx + dz * dz < 16) return down >= 0 ? "below you" : "above you";
        // North is -Z, east +X.
        String way = COMPASS[Math.floorMod(Math.round((float) Math.toDegrees(Math.atan2(dx, -dz)) / 45), 8)] + " of you";
        return Math.abs(down) < 4 ? way : way + ", " + Math.abs(down) + (down > 0 ? " below" : " above");
    }

    private String dimension() {
        String id = mc.level.dimension().identifier().getPath();
        return switch (id) {
            case "overworld" -> "Overworld";
            case "the_nether" -> "Nether";
            case "the_end" -> "End";
            default -> id;
        };
    }

    /** Copies the picture for the message (if it hasn't gone already) and makes it a PNG off the game's thread. */
    private void keep(Sighting sighting, NativeImage image) {
        sighting.waiting = false;
        if (!burst.contains(sighting)) return;
        sighting.encoding = true;
        NativeImage copy = new NativeImage(image.format(), image.getWidth(), image.getHeight(), false);
        copy.copyFrom(image);
        Thread worker = new Thread(() -> {
            byte[] png = png(copy);
            mc.execute(() -> {
                sighting.png = png;
                sighting.encoding = false;
            });
        }, "Cyanite Base Alert");
        worker.setDaemon(true);
        worker.start();
    }

    /** {@code image} (closed after) as a PNG at most {@link #UPLOAD_WIDTH} across, or null if that failed. */
    private static byte[] png(NativeImage image) {
        Path file = Config.dir().resolve("base-alert-" + UUID.randomUUID() + ".png");
        NativeImage out = image;
        try (image) {
            if (image.getWidth() > UPLOAD_WIDTH) {
                int height = Math.max(1, Math.round(image.getHeight() * UPLOAD_WIDTH / (float) image.getWidth()));
                out = new NativeImage(image.format(), UPLOAD_WIDTH, height, false);
                image.resizeSubRectTo(0, 0, image.getWidth(), image.getHeight(), out);
            }
            Files.createDirectories(file.getParent());
            out.writeToFile(file);
            return Files.readAllBytes(file);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Base Alert: couldn't make the picture for Discord", e);
            return null;
        } finally {
            if (out != image) out.close();
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                LOGGER.warn("Base Alert: couldn't delete {}", file, e);
            }
        }
    }

    /** Sends the burst once it's over and its pictures are in, or once it's gone on long enough. */
    private void postWhenReady() {
        if (burst.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (CameraShot.busy()) cameraBusyAt = now;
        boolean ready = now - burstLast >= Notify.DING_GAP_MS;
        for (Sighting s : burst) {
            // The camera drops what it's asked for when it's full or Stealth Mode comes on.
            if (s.waiting && now - cameraBusyAt > PICTURE_GIVE_UP_MS) s.waiting = false;
            if (s.waiting || s.encoding) ready = false;
        }
        if (ready || now - burstStarted >= BURST_LIMIT_MS) post();
    }

    /** Sends the burst as one message, with the pictures that are in (as many as Discord takes). */
    private void post() {
        if (burst.isEmpty()) return;
        List<Sighting> sightings = new ArrayList<>(burst);
        burst.clear();
        String url = webhook.get().strip();
        if (!discord.get() || !Webhook.valid(url)) return;

        List<JsonObject> embeds = new ArrayList<>();
        List<Webhook.File> files = new ArrayList<>();
        long bytes = 0;
        long nearest = Long.MAX_VALUE;
        for (int i = 0; i < sightings.size(); i++) {
            Sighting s = sightings.get(i);
            nearest = Math.min(nearest, s.distance);
            if (sightings.size() > 1) s.embed.addProperty("title", "Possible base " + (i + 1) + " of " + sightings.size());
            if (s.png != null && bytes + s.png.length <= MAX_UPLOAD) {
                String name = "base-" + (i + 1) + ".png";
                JsonObject image = new JsonObject();
                image.addProperty("url", "attachment://" + name);
                s.embed.add("image", image);
                files.add(new Webhook.File(name, s.png));
                bytes += s.png.length;
            }
            embeds.add(s.embed);
        }
        String content = sightings.size() == 1
            ? "Possible base " + nearest + "m away: " + sightings.get(0).summary
            : sightings.size() + " possible bases, the nearest " + nearest + "m away";
        JsonObject allowed = Webhook.noPings();
        if (pingEveryone.get()) {
            content = "@everyone " + content;
            allowed.getAsJsonArray("parse").add("everyone");
        }
        Webhook.post("Base Alert", url, Webhook.message("Base Alert", content, allowed, embeds), files, null);
    }

    private String where(Base base) {
        BlockPos p = BlockPos.containing(base.center);
        return p.getX() + ", " + p.getY() + ", " + p.getZ() + " (" + Math.round(base.center.distanceTo(mc.player.position())) + "m)";
    }

    private static String summary(Base base) {
        List<String> parts = new ArrayList<>();
        base.counts.forEach((kind, n) -> parts.add(n + " " + name(kind, n)));
        return String.join(", ", parts);
    }

    /** What {@code n} of {@code kind} are called: "shulker box", "shulker boxes". */
    private static String name(Kind kind, int n) {
        return kind.label.toLowerCase(Locale.ROOT) + (n == 1 ? "" : kind == Kind.SHULKER ? "es" : "s");
    }

    @Override
    public void onRender2D(GuiGraphics g, float partialTick) {
        if (!markers.get()) return;
        Vec3 eye = mc.player.getEyePosition(partialTick);
        int unit = Render2D.scale();
        for (int i = bases.size() - 1; i >= 0; i--) {
            Base base = bases.get(i);
            double[] screen = Projection.toScreen(base.center);
            if (screen == null) continue;
            g.pose().pushMatrix();
            g.pose().translate(Math.round(screen[0]) / (float) unit, Math.round(screen[1]) / (float) unit);
            drawMarker(g, base, base.center.distanceTo(eye));
            g.pose().popMatrix();
        }
    }

    private void drawMarker(GuiGraphics g, Base base, double distance) {
        int s = Render2D.scale();
        int rgb = 0xFF000000 | color.get();
        int r = 9 * s;
        int ring = Math.max(2, Math.round(1.5f * s));

        Render2D.pixels(g);
        Render2D.glow(g, -r, -r, 2 * r, 2 * r, r, 6 * s, Theme.alpha(rgb, 70 * Theme.glow()));
        Render2D.rect(g, -r, -r, 2 * r, 2 * r, r, rgb);
        Render2D.rect(g, -r + ring, -r + ring, 2 * (r - ring), 2 * (r - ring), r - ring, 0xF0101217);
        Render2D.end(g);
        String score = Integer.toString((int) Math.round(base.score));
        Text.draw(g, score, -Text.width(score) / 2, -Text.CAP / 2, rgb);

        BlockPos p = BlockPos.containing(base.center);
        String title = "Possible base";
        String dist = Math.round(distance) + "m";
        String coords = StreamerMode.mask(p.getX() + ", " + p.getY() + ", " + p.getZ());
        List<String> lines = new ArrayList<>();
        base.counts.forEach((kind, n) -> lines.add(kind.label + "  ×" + n));

        int pad = 4;
        int line = Text.CAP + 4;
        int width = Math.max(Text.width(title) + 6 + Text.width(dist), Text.width(coords));
        for (String l : lines) width = Math.max(width, Text.width(l));
        int w = width + pad * 2;
        int h = pad * 2 + line * (2 + lines.size()) - 4;
        int x = -w / 2;
        int y = 14;
        int corner = Math.round(Math.min(Theme.radius(), 3.5f) * s);

        Render2D.pixels(g);
        g.fill(-Math.max(1, s / 2), r, Math.max(1, s / 2), y * s, Theme.alpha(rgb, 150));
        Render2D.glow(g, x * s, y * s, w * s, h * s, corner, 6 * s, Theme.alpha(rgb, 30 * Theme.glow()));
        Render2D.rect(g, x * s, y * s, w * s, h * s, corner, Theme.BORDER);
        Render2D.rect(g, x * s + 1, y * s + 1, w * s - 2, h * s - 2, Math.max(0, corner - 1), 0xE6101217);
        Render2D.end(g);

        Text.draw(g, title, x + pad, y + pad, Theme.TEXT);
        Text.draw(g, dist, x + pad + Text.width(title) + 6, y + pad, Theme.TEXT_FAINT);
        Text.draw(g, coords, x + pad, y + pad + line, Theme.TEXT_DIM);
        for (int i = 0; i < lines.size(); i++) Text.draw(g, lines.get(i), x + pad, y + pad + line * (2 + i), Theme.TEXT_DIM);
    }
}
