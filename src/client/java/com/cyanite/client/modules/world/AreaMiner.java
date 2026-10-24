package com.cyanite.client.modules.world;

import com.cyanite.client.gui.Theme;
import com.cyanite.client.litematica.Litematica;
import com.cyanite.client.modules.world.area.AreaPlan;
import com.cyanite.client.modules.world.area.AreaSelection;
import com.cyanite.client.modules.world.area.AreaTarget;
import com.cyanite.client.modules.world.area.DrillModel;
import com.cyanite.client.modules.world.area.Paths;
import com.cyanite.client.modules.world.area.Region;
import com.cyanite.client.render.Render3D;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.HumanAim;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.Notify;
import com.cyanite.client.system.Steer;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.SliderSetting;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Digs out a whole area with a drill that breaks a square around the block you hit, on the
 * face you hit. Pick the area with two corners or a Litematica placement, press Start, and it
 * works through it layer by layer, lane by lane (see {@link AreaPlan}), on a fixed route: down
 * one lane, across, back up the next, like mowing a lawn. It finishes each slice of a lane before
 * the next, so it never wanders off and leaves holes to come back for. Down a lane it works the
 * way you'd tunnel by hand: up close to the face, holding left click, the next slice under the
 * crosshair as soon as the last one goes, stepping forward as the face moves away.
 *
 * <p>Every hit is checked before it's made: the drill's whole square must land on blocks
 * inside the area, so nothing outside it is ever cut, and none of those blocks may sit near
 * lava. Hits are chosen from where you can actually stand and see the face, using the same
 * human aim and held attack as Auto Mine, and you walk there with real movement (see
 * {@link Paths}). Empty pockets cost nothing: a slice with nothing left in it is skipped
 * without a swing. Lava is dug around from whichever side is still safe; whatever stays too
 * close to it is left and counted as skipped. Blocks the drill can't take without cutting
 * outside the area go to another tool from your hotbar, if you have one.
 */
public class AreaMiner extends Module {
    private static final long MAX_VOLUME = 4_000_000;
    private static final double MAX_START_DISTANCE = 96;
    private static final int PATH_BUDGET = 40_000;
    private static final long CHOOSE_BUDGET = 10_000_000L;
    private static final long SETTLE_NANOS = 850_000_000L;
    private static final long MINE_TIMEOUT = 20_000_000_000L;
    private static final long OFF_TARGET_TIMEOUT = 2_500_000_000L;
    private static final long WALK_STUCK = 5_000_000_000L;
    private static final long EMPTY_CONFIRM = 1_500_000_000L;
    private static final long PREVIEW_REFRESH = 1_000_000_000L;
    private static final int TOOL_RESERVE = 10;
    private static final double MIN_FACING = 0.35;
    /**
     * Into a wall, never more than this far off head on (degrees), so the way the game says you
     * face is the way into the face. The drill lays its square across the way you face; past 45
     * degrees that would be a different square from the one planned on the face.
     */
    private static final double MAX_SIDE_ANGLE = 40;
    /** How far from the face (eye to face) it likes to swing from, walking up to it when further. */
    private static final double STAND_NEAR = 2.2;
    /** Down a lane, further than this from the face and it steps forward while it mines. */
    private static final double CLOSE = 2.0;
    /** How much the hit straight down the lane, the way the route runs, is preferred over any other. */
    private static final double ROUTE_BONUS = 20;
    /** Hitting the top of a block cuts a shape the drill may not share with its sides: only when the side won't do. */
    private static final double DOWN_PENALTY = 2;
    private static final long BLOCKED_RETRY = 20_000_000_000L;
    private static final long LEARN_SETTLE = 1_500_000_000L;
    /** A hit is only learned from if nothing else came out for this long before it started. */
    private static final long QUIET_BEFORE = 1_000_000_000L;
    private static final int ROUTE_COLOR = 0xFFFF5D4A;
    private static final int MAX_GIVEN_UP_DRAWN = 400;

    public enum State { IDLE, RUNNING, PAUSED }

    private enum Phase { CHOOSE, WALK, MINE, SETTLE }

    private record Job(int rank, BlockPos center, Direction.Axis axis, Direction face, Vec3 aim, BlockPos stand,
                       boolean edge, int slot, int gain) {
    }

    private record Stand(BlockPos cell, int dist, Vec3 aim, double reach) {
    }

    private final SliderSetting drillSize = add(new SliderSetting("Drill size", 3, 3, 9, 2).format(v -> (int) v + "x" + (int) v));
    private final SliderSetting turnSpeed = add(new SliderSetting("Turn speed", 1, 0.3, 3, 0.1).format(v -> String.format(Locale.ROOT, "%.1fx", v)));
    private final SliderSetting randomness = add(new SliderSetting("Randomness", 0.5, 0, 1, 0.05).percent());
    private final SliderSetting lavaGap = add(new SliderSetting("Lava gap", 2, 1, 4, 1).format(v -> (int) v + (v == 1 ? " block" : " blocks")));
    private final BoolSetting avoidWater = add(new BoolSetting("Avoid water", false));
    private final ModeSetting edgeBlocks = add(new ModeSetting("Edge blocks", "Other tool", List.of("Other tool", "Skip")));
    private final BoolSetting pauseHurt = add(new BoolSetting("Pause when hurt", true));
    private final BoolSetting pauseFull = add(new BoolSetting("Pause when full", true));
    private final BoolSetting saveTool = add(new BoolSetting("Save tool", true));
    private final BoolSetting pauseMobs = add(new BoolSetting("Pause at mobs", true));
    private final SliderSetting mobRange = add(new SliderSetting("Mob range", 10, 4, 32, 1).format(v -> (int) v + "m"));
    private final ModeSetting outline = add(new ModeSetting("Outline", "Always", List.of("Always", "While mining", "Off")));
    private final ColorSetting outlineColor = add(new ColorSetting("Outline color", 0x37D3FF));
    private final BoolSetting showPath = add(new BoolSetting("Show path", true));
    private final BoolSetting toast = add(new BoolSetting("Toast", true));
    private final BoolSetting sound = add(new BoolSetting("Sound", true));
    private final BoolSetting flash = add(new BoolSetting("Flash taskbar", true));

    private final HumanAim aim = new HumanAim();
    private final Random random = new Random();
    private final AreaSelection selection = AreaSelection.load();

    private State state = State.IDLE;
    private Phase phase = Phase.CHOOSE;
    private LocalPlayer owner;
    private AreaPlan plan;
    private Job job;
    private List<BlockPos> path;
    private int pathIndex;
    private Input steering = Input.EMPTY;
    private String status = "";
    private String report;
    private String startError;

    private int drillSlot = -1;
    private ItemStack drillStack = ItemStack.EMPTY;
    private long slotReadyAt;
    private float lastHealth;

    private long phaseSince;
    private long offTargetSince;
    private long settleSince;
    private long emptySince;
    private boolean lookingAhead;
    private Vec3 lastPos;
    private long lastMoveAt;
    private long lastJumpAt;
    private int hits;
    private int retries;
    private static final int MAX_RETRIES = 3;
    private int edgeHits;
    private long runNanos;
    private long lastTick;
    private long lastLavaNotice;
    private BlockPos lastLavaNoticeAt;

    // One "choose" can take a few ticks on a big area; this is where it picks up.
    private boolean scanning;
    private DrillModel model = new DrillModel(3);
    private final Long2ObjectOpenHashMap<LongSet> mays = new Long2ObjectOpenHashMap<>();
    private boolean[] before;
    private Direction brokeFacing = Direction.NORTH;
    private long lastChangeAt;
    private int settleCount;
    private int steadyTicks;
    private boolean cleaning;
    private int cleanupStage;
    private int cleanupCursor;
    private int scanCursor;
    private Paths paths;
    private Job scanBest;
    private double scanBestCost;
    private boolean scanLava;
    private final Long2IntOpenHashMap evals = new Long2IntOpenHashMap();
    private final Long2ByteOpenHashMap lavaNear = new Long2ByteOpenHashMap();
    private final Int2LongOpenHashMap bannedCells = new Int2LongOpenHashMap();
    private final Long2LongOpenHashMap bannedHits = new Long2LongOpenHashMap();
    private byte[] failStreak = new byte[0];
    /** The cell each job was for, in order. */
    private final it.unimi.dsi.fastutil.ints.IntArrayList jobRanks = new it.unimi.dsi.fastutil.ints.IntArrayList();
    private int settleStartCount;
    /** A look ahead from {@link #fromHere}: it mustn't pass anything over on what it sees mid-cut. */
    private boolean peeking;
    /** When the last block came out, and when the current hit's "before" was taken. */
    private long lastBreakAt;
    private long quietBefore;
    /** First hits of each kind waited on for the drill's cut; after a few with nothing extra, it stops waiting. */
    private final int[] learnTries = new int[DrillModel.Kind.values().length];
    /** Whether you've been told what the drill cuts, per kind of hit. */
    private final boolean[] toldShape = new boolean[DrillModel.Kind.values().length];

    private AreaTarget preview;
    private String previewError;
    private long previewAt;
    private int previewStamp = -1;
    private int selectionStamp;

    public AreaMiner() {
        super(Category.WORLD, "Area Miner", "Digs out a whole selected area with your drill");
        evals.defaultReturnValue(Integer.MIN_VALUE);
        lavaNear.defaultReturnValue((byte) -1);
    }

    // ---- What the GUI uses

    public AreaSelection selection() {
        return selection;
    }

    /** Call after changing the selection, so it's saved and the outline updates. */
    public void selectionChanged() {
        if (selection.source == AreaSelection.Source.COORDS && inGame()) selection.world = worldKey();
        selection.save();
        selectionStamp++;
    }

    public State state() {
        return isEnabled() ? state : State.IDLE;
    }

    public String status() {
        return status;
    }

    /** How the last run ended, until a new one starts. */
    public String report() {
        return report;
    }

    public boolean counting() {
        return plan != null && !plan.counted();
    }

    public double progress() {
        return plan == null ? 0 : plan.progress();
    }

    public long mined() {
        return plan == null ? 0 : plan.mined();
    }

    public long left() {
        return plan == null ? 0 : plan.left();
    }

    public long skipped() {
        return plan == null ? 0 : plan.skipped();
    }

    public int hits() {
        return hits;
    }

    /** What the drill has been seen to break: "Learning" until it has, then like "3×3 side, 2×3 down". */
    public String drillShape() {
        String side = model.describe(DrillModel.Kind.SIDE);
        String down = model.describe(DrillModel.Kind.UP);
        if (side == null && down == null) return "Learning";
        StringBuilder sb = new StringBuilder();
        if (side != null) sb.append(side).append(" side");
        if (down != null) sb.append(sb.isEmpty() ? "" : ", ").append(down).append(" down");
        return sb.toString();
    }

    /** Of those, single blocks taken with your other tool. */
    public int edgeHits() {
        return edgeHits;
    }

    public long elapsedMillis() {
        return runNanos / 1_000_000;
    }

    /** Blocks a minute, over the time spent actually running. */
    public double rate() {
        double minutes = runNanos / 60e9;
        return minutes < 0.05 ? 0 : mined() / minutes;
    }

    /** Milliseconds until done at the current rate, or -1 if there's no rate yet. */
    public long etaMillis() {
        double rate = rate();
        return rate <= 0 ? -1 : (long) (left() / rate * 60_000);
    }

    /** The selected area as boxes (no schematic mask), for the outline and the GUI. Null if it isn't set up. */
    public AreaTarget preview() {
        long now = System.nanoTime();
        boolean litematica = selection.source == AreaSelection.Source.LITEMATICA;
        if (previewStamp != selectionStamp || (litematica && now - previewAt > PREVIEW_REFRESH)) {
            previewStamp = selectionStamp;
            previewAt = now;
            previewError = null;
            preview = resolve(false);
        }
        return preview;
    }

    /** Why there's no preview, or null. */
    public String previewError() {
        preview();
        return previewError;
    }

    /** Starts, or resumes when paused. @return why it couldn't, or null. */
    public String start() {
        if (isEnabled() && state == State.PAUSED) {
            resume();
            return null;
        }
        if (isEnabled()) return null;
        startError = null;
        setEnabled(true);
        return startError;
    }

    public void pause() {
        pause("Paused");
    }

    public void resume() {
        if (!isEnabled() || state != State.PAUSED || mc.player == null) return;
        state = State.RUNNING;
        status = "Resuming";
        lastHealth = mc.player.getHealth();
        resetPhase();
        aim.idle();
    }

    public void stop() {
        if (!isEnabled()) return;
        report = "Stopped" + summary();
        setEnabled(false);
    }

    /** Server (or singleplayer world) plus dimension, the way Death Position tells worlds apart. */
    private static String worldKey() {
        String where;
        if (mc.getCurrentServer() != null) where = mc.getCurrentServer().ip;
        else if (mc.getSingleplayerServer() != null) where = "singleplayer:" + mc.getSingleplayerServer().getWorldData().getLevelName();
        else where = "unknown";
        return where + "|" + mc.level.dimension().identifier();
    }

    // ---- Hooks for the mixins

    /** How Area Miner wants the player to move this tick, or null when it isn't driving. */
    public Input steering() {
        return isEnabled() && owner != null && owner == mc.player ? steering : null;
    }

    /** Whether vanilla should treat attack as held: only with the crosshair on the planned face. */
    public boolean shouldAttack() {
        if (!isEnabled() || state != State.RUNNING || phase != Phase.MINE || job == null) return false;
        if (mc.screen != null || mc.player == null || System.nanoTime() < slotReadyAt) return false;
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return false;
        if (!job.edge && job.face.getAxis().isHorizontal() && mc.player.getDirection() != job.face.getOpposite()) return false;
        return steadyTicks >= 2 && hit.getBlockPos().equals(job.center) && hit.getDirection() == job.face;
    }

    /** True while it picks the tools itself, so Auto Tool keeps its hands off. */
    public boolean managesTools() {
        return isEnabled() && state == State.RUNNING;
    }

    @Override
    public String getInfo() {
        if (state == State.PAUSED) return "Paused";
        if (plan == null) return null;
        if (!plan.counted()) return "Scanning";
        return Math.round(plan.progress() * 100) + "%";
    }

    @Override
    public boolean rendersWhileDisabled() {
        return outline.is("Always");
    }

    // ---- Start and stop

    @Override
    protected void onEnable() {
        owner = null;
        if (!inGame()) return;
        BedrockBurrow burrow = Modules.get(BedrockBurrow.class);
        if (burrow != null && burrow.isEnabled()) burrow.setEnabled(false);
        String error = begin();
        if (error != null) {
            startError = error;
            Notify.send("Area Miner", error, toast.get(), false, false);
            plan = null;
            setEnabled(false);
        }
    }

    @Override
    protected void onDisable() {
        if (inGame() && drillSlot >= 0 && owner == mc.player) mc.player.getInventory().setSelectedSlot(drillSlot);
        state = State.IDLE;
        steering = Input.EMPTY;
        aim.clear();
        job = null;
        path = null;
        scanning = false;
        paths = null;
        owner = null;
        if (report == null && plan != null) report = "Stopped" + summary();
        plan = null;
    }

    private String begin() {
        LocalPlayer player = mc.player;
        AreaTarget target = resolve(true);
        if (target == null) return previewError == null ? "Pick an area first" : previewError;
        if (target.bounds.distanceTo(player.getX(), player.getY(), player.getZ()) > MAX_START_DISTANCE) {
            return "You're too far from the area: get within " + (int) MAX_START_DISTANCE + " blocks of it";
        }

        AutoMine autoMine = Modules.get(AutoMine.class);
        if (autoMine != null && autoMine.isEnabled()) autoMine.setEnabled(false);

        int size = drillSize.get().intValue();
        plan = new AreaPlan(target, size, player.getX(), player.getY(), player.getZ());
        // Learned afresh each run, starting from the safe assumptions, in case the drill changed.
        model = new DrillModel(size);
        mays.clear();
        failStreak = new byte[plan.cells];
        bannedCells.clear();
        bannedHits.clear();
        jobRanks.clear();
        java.util.Arrays.fill(learnTries, 0);
        java.util.Arrays.fill(toldShape, false);
        owner = player;
        state = State.RUNNING;
        report = null;
        status = "Scanning the area";
        hits = 0;
        retries = 0;
        edgeHits = 0;
        runNanos = 0;
        lastTick = 0;
        lastHealth = player.getHealth();
        lastLavaNotice = 0;
        lastLavaNoticeAt = null;
        drillSlot = player.getInventory().getSelectedSlot();
        drillStack = player.getMainHandItem().copy();
        resetPhase();
        if (drillStack.isEmpty()) status = "Hold your drill: an empty hand won't cut squares";
        return null;
    }

    /** Turns the selection into blocks to dig. With {@code exact}, a schematic shape lists its blocks. */
    private AreaTarget resolve(boolean exact) {
        previewError = null;
        if (selection.source == AreaSelection.Source.COORDS) {
            if (selection.pos1 == null || selection.pos2 == null) {
                previewError = selection.pos1 == null ? "Set corner 1" : "Set corner 2";
                return null;
            }
            Region region = Region.of(selection.pos1, selection.pos2);
            if (region.volume() > MAX_VOLUME) {
                previewError = String.format(Locale.ROOT, "Too big: %,d blocks (most is %,d)", region.volume(), MAX_VOLUME);
                return null;
            }
            return new AreaTarget(List.of(region), null, "Corners");
        }

        if (!Litematica.installed()) {
            previewError = "Litematica isn't installed";
            return null;
        }
        if (selection.placementKey == null) {
            previewError = "Pick a schematic";
            return null;
        }
        Litematica.Placement placement = findPlacement();
        if (placement == null) {
            previewError = "Can't find " + (selection.placementName == null ? "that schematic" : selection.placementName) + " in Litematica";
            return null;
        }
        long volume = 0;
        for (Region r : placement.boxes()) volume += r.volume();
        if (volume > MAX_VOLUME) {
            previewError = String.format(Locale.ROOT, "Too big: %,d blocks (most is %,d)", volume, MAX_VOLUME);
            return null;
        }
        LongSet mask = null;
        if (exact && selection.shape == AreaSelection.Shape.BLOCKS) {
            mask = Litematica.blocks(placement.key(), placement.origin(), MAX_VOLUME);
            if (mask == null) {
                previewError = "Couldn't read the schematic's blocks";
                return null;
            }
            if (mask.isEmpty()) {
                previewError = "That schematic has no blocks";
                return null;
            }
        }
        return new AreaTarget(placement.boxes(), mask, placement.name());
    }

    public Litematica.Placement findPlacement() {
        Litematica.Placement match = null;
        for (Litematica.Placement p : Litematica.placements()) {
            if (!p.key().equals(selection.placementKey)) continue;
            if (p.origin().equals(selection.placementOrigin)) return p;
            if (match == null) match = p;
        }
        return match;
    }

    private void pause(String reason) {
        if (!isEnabled() || state != State.RUNNING) return;
        state = State.PAUSED;
        status = reason;
        steering = Input.EMPTY;
        aim.clear();
        resetPhase();
        notify("paused", reason);
    }

    private void finish(String headline) {
        report = headline + summary();
        notify("finished", report);
        setEnabled(false);
    }

    private String summary() {
        if (plan == null) return "";
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, ": %,d blocks mined in %,d hits", plan.mined(), hits));
        if (edgeHits > 0) sb.append(String.format(Locale.ROOT, " (%,d with your other tool)", edgeHits));
        if (plan.skipped() > 0) sb.append(String.format(Locale.ROOT, ", %,d skipped", plan.skipped()));
        return sb.toString();
    }

    private void notify(String what, String body) {
        Notify.send("Area Miner " + what, body, toast.get(), sound.get(), flash.get());
    }

    private void resetPhase() {
        phase = Phase.CHOOSE;
        phaseSince = System.nanoTime();
        job = null;
        path = null;
        scanning = false;
        cleaning = false;
        emptySince = 0;
        offTargetSince = 0;
        lookingAhead = false;
        steering = Input.EMPTY;
    }

    // ---- Every tick

    @Override
    public void onFrame() {
        if (state != State.RUNNING || mc.screen != null) {
            aim.idle();
            return;
        }
        aim.speed = turnSpeed.get();
        aim.randomness = randomness.get();
        aim.frame();
    }

    @Override
    public void onTick() {
        LocalPlayer player = mc.player;
        if (owner == null) {
            setEnabled(false);
            return;
        }
        if (owner != player) {
            report = "Stopped: you died or changed worlds" + summary();
            notify("stopped", "You died or changed worlds");
            setEnabled(false);
            return;
        }

        long now = System.nanoTime();
        if (state == State.RUNNING && lastTick != 0) runNanos += Math.min(now - lastTick, 200_000_000L);
        lastTick = now;
        plan.sweep(mc.level, 1_500_000L);

        if (state != State.RUNNING) {
            steering = Input.EMPTY;
            lastHealth = player.getHealth();
            return;
        }
        if (mc.screen != null) {
            steering = Input.EMPTY;
            return;
        }

        String hazard = hazard(player);
        if (hazard != null) {
            pause(hazard);
            return;
        }

        switch (phase) {
            case CHOOSE -> tickChoose(player, now);
            case WALK -> tickWalk(player, now);
            case MINE -> tickMine(player, now);
            case SETTLE -> tickSettle(now);
        }
    }

    private void enter(Phase next) {
        phase = next;
        phaseSince = System.nanoTime();
        offTargetSince = 0;
    }

    // ---- Choosing the next hit

    private void tickChoose(LocalPlayer player, long now) {
        steering = Input.EMPTY;
        // Mid-fall or with gravel still coming down, the world's about to change: wait for it.
        if (!player.onGround() && !player.isInWater()) return;
        if (fallingNear(player)) {
            if (fallingOverhead(player)) steering = away(player);
            return;
        }

        Job next = null;
        if (cleaning) {
            // Leftovers get their own ticks, so a slow scan can never starve them.
            next = continueCleanup(now + CHOOSE_BUDGET);
            if (next == null) {
                if (cleanupStage < 2) return;
                cleaning = false;
                nothingToDo(player, now);
                return;
            }
            cleaning = false;
        } else {
            if (!scanning) beginScan(player);
            if (!continueScan(now + CHOOSE_BUDGET)) return;
            scanning = false;
            next = scanBest;
            if (next == null) {
                cleaning = true;
                cleanupStage = 0;
                cleanupCursor = plan.nextPending(0);
                return;
            }
        }
        emptySince = 0;
        start(next, player);
    }

    private void start(Job next, LocalPlayer player) {
        job = next;
        if (jobRanks.size() < 100_000) jobRanks.add(next.rank);
        path = paths.pathTo(next.stand);
        pathIndex = 1;
        lookingAhead = false;
        lastPos = player.position();
        lastMoveAt = System.nanoTime();
        status = describe(next);
        if (doableFromHere(player)) beginMining();
        else enter(Phase.WALK);
    }

    private String describe(Job j) {
        AreaPlan.Lattice l = plan.lattice(j.rank);
        int layers = plan.layers(j.rank);
        String where = layers > 1 ? "Layer " + (plan.layer(j.rank) + 1) + " of " + layers + ", " : "";
        where += "lane " + (plan.laneOrder(j.rank) + 1) + " of " + plan.lanes(j.rank);
        if (j.edge) return where + ": edge block with your other tool";
        if (j.axis == Direction.Axis.Y) return where + ": cutting down";
        return where + (j.axis == l.along ? ": down the lane" : ": opening the lane");
    }

    /** The cell each job so far was for, in order. */
    it.unimi.dsi.fastutil.ints.IntList jobRanks() {
        return jobRanks;
    }

    AreaPlan plan() {
        return plan;
    }

    private void beginScan(LocalPlayer player) {
        Region bounds = plan.target.bounds.grow(16, 8).union(new Region(
            player.getBlockX() - 6, player.getBlockY() - 6, player.getBlockZ() - 6,
            player.getBlockX() + 6, player.getBlockY() + 6, player.getBlockZ() + 6));
        paths = Paths.search(mc.level, player.blockPosition(), bounds, plan.target, PATH_BUDGET);
        scanning = true;
        scanCursor = plan.nextPending(0);
        scanBest = null;
        scanBestCost = Double.MAX_VALUE;
        evals.clear();
        lavaNear.clear();
        long now = System.nanoTime();
        bannedHits.long2LongEntrySet().removeIf(e -> e.getLongValue() < now);
        bannedCells.int2LongEntrySet().removeIf(e -> e.getLongValue() < now);
    }

    /**
     * Follows the route: the first cell along it with anything left is the next job, and only
     * hits that dig that cell are looked at. If none of the lane-shaped hits can, anything else
     * that clears it comes next (any safe drill hit, then your other tool), so the route never
     * leaves a hole behind it. A cell nothing can touch right now is passed over and tried again
     * later. @return true once it's found the job or looked everywhere.
     */
    private boolean continueScan(long deadline) {
        int n = 0;
        while (scanCursor >= 0 && scanBest == null) {
            int rank = scanCursor;
            scanCursor = plan.nextPending(rank + 1);
            if (bannedCells.containsKey(rank)) continue;
            if (plan.recount(mc.level, rank) > 0) {
                consider(rank);
                if (scanBest == null) scanBest = rescue(rank, 0);
                if (scanBest == null && edgeBlocks.is("Other tool")) scanBest = rescue(rank, 1);
                if (scanBest == null && !plan.isGivenUp(rank) && !peeking) bannedCells.put(rank, System.nanoTime() + BLOCKED_RETRY);
            }
            if ((++n & 7) == 0 && System.nanoTime() > deadline) return false;
        }
        return true;
    }

    /** Every hit that would take blocks from this cell while staying lined up with the lanes and layers. */
    private void consider(int rank) {
        AreaPlan.Lattice l = plan.lattice(rank);
        int a = plan.along(rank);
        int c = plan.lane(rank);
        int b = plan.base(rank);
        int h = plan.half;
        Job before = scanBest;
        scanLava = false;

        // Straight down the lane, facing the way the route runs: the square is exactly this cell.
        candidate(rank, l.pos(a, c, b + h), l.along, back(rank), ROUTE_BONUS, 0.3);
        // From the side, out of the lane next door: opening a lane at its start.
        for (int lane = c - h; lane <= c + h; lane++) {
            for (int a2 = a - h; a2 <= a + h; a2++) candidate(rank, l.pos(a2, lane, b + h), l.across, null, 0, 0);
        }
        // From above (or below): going down into a new layer, or a thin last row.
        for (int y = b; y < b + plan.size; y++) {
            for (int a2 = a - h; a2 <= a + h; a2++) candidate(rank, l.pos(a2, c, y), Direction.Axis.Y, null, 0, -DOWN_PENALTY);
        }

        if (scanBest != before) {
            failStreak[rank] = 0;
        } else if (scanLava && failStreak[rank] < 100) {
            // Nothing safe here because of lava. Once every block left in it sits by lava, leave it.
            failStreak[rank]++;
            if (allNearLava(rank)) skipForLava(rank);
        }
    }

    /** The face a cell's straight-down-the-lane hit lands on: the one looking back along the route. */
    private Direction back(int rank) {
        return Direction.fromAxisAndDirection(plan.lattice(rank).along,
            plan.travel(rank) > 0 ? Direction.AxisDirection.NEGATIVE : Direction.AxisDirection.POSITIVE);
    }

    /** Whether this is a straight-down-the-lane hit, the kind it holds on through. */
    private boolean downTheLane(Job j) {
        return !j.edge && j.axis == plan.lattice(j.rank).along && j.face == back(j.rank);
    }

    /** A hit on either face of {@code center} across {@code axis}; {@code preferred} gets {@code bonus}, any other face {@code otherBonus}. */
    private void candidate(int rank, BlockPos center, Direction.Axis axis, Direction preferred, double bonus, double otherBonus) {
        Direction pos = Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE);
        for (Direction face : new Direction[] {pos, pos.getOpposite()}) {
            // Cheapest test first: most blocks deep in the rock have no open face to hit at all.
            if (!open(center.relative(face))) continue;
            int gain = eval(center, face);
            if (gain == -2) scanLava = true;
            if (gain <= 0) continue;
            double base = rank - 0.6 * gain - (preferred == null || face == preferred ? bonus : otherBonus);
            if (base >= scanBestCost || bannedHits.containsKey(hitKey(center, face))) continue;
            Stand stand = stand(center, face, mayBreak(center, face), true);
            if (stand == null) continue;
            double cost = base + 2.0 * stand.dist + 0.05 * stand.reach;
            if (cost < scanBestCost) {
                scanBestCost = cost;
                scanBest = new Job(rank, center, axis, face, stand.aim, stand.cell, false, -1, gain);
            }
        }
    }

    /**
     * What a drill hit on {@code face} of {@code center} would take: how many area blocks it
     * breaks, 0 if the block isn't one, -1 if anything it might break lies outside the area (or
     * in an unloaded chunk), -2 if it would open up near lava.
     */
    private int eval(BlockPos center, Direction face) {
        long key = hitKey(center, face);
        int cached = evals.get(key);
        if (cached != Integer.MIN_VALUE) return cached;

        int result = 0;
        if (plan.target.contains(center) && AreaPlan.diggable(mc.level, center, mc.level.getBlockState(center))) {
            BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
            for (long p : mayBreak(center, face)) {
                q.set(p);
                if (!mc.level.hasChunkAt(q)) {
                    result = -1;
                    break;
                }
                BlockState state = mc.level.getBlockState(q);
                if (!AreaPlan.diggable(mc.level, q, state)) continue;
                if (!plan.target.contains(q)) {
                    // Grass and torches past the edge don't count; anything solid does.
                    if (state.getCollisionShape(mc.level, q).isEmpty()) continue;
                    result = -1;
                    break;
                }
                if (nearLava(q)) {
                    result = -2;
                    break;
                }
            }
            if (result == 0) result = gain(center, face);
        }
        evals.put(key, result);
        return result;
    }

    /** Area blocks the hit should break, counted the least favourable way you could be facing. */
    private int gain(BlockPos center, Direction face) {
        int least = Integer.MAX_VALUE;
        for (Direction facing : facings(face)) {
            int n = 0;
            for (BlockPos q : willBreak(center, face, facing)) {
                if (plan.target.contains(q) && AreaPlan.diggable(mc.level, q, mc.level.getBlockState(q))) n++;
            }
            least = Math.min(least, n);
        }
        return Math.max(1, least);
    }

    /** Which ways you could be facing for this hit: straight at a side face, or any way for top and bottom. */
    private static Direction[] facings(Direction face) {
        if (face.getAxis().isHorizontal()) return new Direction[] {face.getOpposite()};
        return new Direction[] {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    }

    /** Everywhere the drill might break on this hit, whichever way you face (see {@link DrillModel}). */
    private LongSet mayBreak(BlockPos center, Direction face) {
        long key = hitKey(center, face);
        LongSet cached = mays.get(key);
        if (cached != null) return cached;
        LongSet out = new LongOpenHashSet();
        DrillModel.Kind kind = DrillModel.kind(face);
        int r = model.radius();
        for (Direction facing : facings(face)) {
            Direction forward = DrillModel.forward(face, facing);
            for (int f = -r; f <= r; f++) {
                for (int s = -r; s <= r; s++) {
                    for (int u = -r; u <= r; u++) {
                        if (model.mayBreak(kind, f, s, u)) out.add(DrillModel.at(center, forward, f, s, u).asLong());
                    }
                }
            }
        }
        mays.put(key, out);
        return out;
    }

    /** What the drill should break on this hit, facing {@code facing}. */
    private List<BlockPos> willBreak(BlockPos center, Direction face, Direction facing) {
        List<BlockPos> out = new ArrayList<>();
        DrillModel.Kind kind = DrillModel.kind(face);
        Direction forward = DrillModel.forward(face, facing);
        int r = model.radius();
        for (int f = -r; f <= r; f++) {
            for (int s = -r; s <= r; s++) {
                for (int u = -r; u <= r; u++) {
                    if (model.willBreak(kind, f, s, u)) out.add(DrillModel.at(center, forward, f, s, u));
                }
            }
        }
        return out;
    }

    /** Position {@code (i, j)} of the drill's square around {@code center}, flat across {@code axis}. */
    private static void square(BlockPos c, Direction.Axis axis, int i, int j, BlockPos.MutableBlockPos out) {
        switch (axis) {
            case X -> out.set(c.getX(), c.getY() + i, c.getZ() + j);
            case Y -> out.set(c.getX() + i, c.getY(), c.getZ() + j);
            case Z -> out.set(c.getX() + i, c.getY() + j, c.getZ());
        }
    }

    /** Lava (or water, if avoided) within the lava gap of {@code pos}: digging it out would let it in. */
    private boolean nearLava(BlockPos pos) {
        long key = pos.asLong();
        byte cached = lavaNear.get(key);
        if (cached >= 0) return cached == 1;
        int gap = lavaGap.get().intValue();
        boolean water = avoidWater.get();
        boolean found = false;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        search:
        for (int dx = -gap; dx <= gap; dx++) {
            int ry = gap - Math.abs(dx);
            for (int dy = -ry; dy <= ry; dy++) {
                int rz = ry - Math.abs(dy);
                for (int dz = -rz; dz <= rz; dz++) {
                    m.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    FluidState fluid = mc.level.getFluidState(m);
                    if (fluid.is(FluidTags.LAVA) || water && fluid.is(FluidTags.WATER)) {
                        found = true;
                        break search;
                    }
                }
            }
        }
        lavaNear.put(key, (byte) (found ? 1 : 0));
        return found;
    }

    private boolean allNearLava(int rank) {
        for (BlockPos pos : plan.owned(rank)) {
            if (AreaPlan.diggable(mc.level, pos, mc.level.getBlockState(pos)) && !nearLava(pos)) return false;
        }
        return true;
    }

    private void skipForLava(int rank) {
        plan.giveUp(rank);
        BlockPos at = BlockPos.containing(plan.box(rank).getCenter());
        long now = System.nanoTime();
        boolean sameSpot = lastLavaNoticeAt != null && lastLavaNoticeAt.closerThan(at, 12);
        if (!sameSpot || now - lastLavaNotice > 60_000_000_000L) {
            lastLavaNotice = now;
            lastLavaNoticeAt = at;
            Notify.send("Area Miner", "Lava near " + coords(at) + ", digging around it", false, false, false);
        }
    }

    /** Nothing in front of the face: the crosshair can reach it. */
    private boolean open(BlockPos pos) {
        if (!mc.level.hasChunkAt(pos)) return false;
        BlockState state = mc.level.getBlockState(pos);
        if (state.getFluidState().is(FluidTags.LAVA)) return false;
        return state.getShape(mc.level, pos).isEmpty();
    }

    /**
     * The cheapest place to stand that can hit {@code face} of {@code center}, and where to aim
     * from there. {@code breaks} is everything the hit might take, so you never stand on it unless
     * the drop is a single block.
     *
     * <p>A {@code drill} hit on the top of a block is only made standing level with it, beside
     * the hole it opens. The drill cuts deeper than one block going down, so cutting down into a
     * pit from its rim would leave a shaft too deep to drop into safely right where you walk.
     */
    private Stand stand(BlockPos center, Direction face, LongSet breaks, boolean drill) {
        double reach = mc.player.blockInteractionRange() - 0.3;
        double eye = eyeHeight();
        Vec3 faceCentre = Vec3.atCenterOf(center).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        Vec3[] points = facePoints(center, face);
        int r = (int) Math.ceil(reach);
        int fx = Mth.floor(faceCentre.x);
        int fz = Mth.floor(faceCentre.z);
        int yLo = Mth.floor(faceCentre.y - eye - reach);
        int yHi = Mth.floor(faceCentre.y - eye + reach);

        List<long[]> options = new ArrayList<>();
        for (int x = fx - r; x <= fx + r; x++) {
            for (int z = fz - r; z <= fz + r; z++) {
                for (int y = yLo; y <= yHi; y++) {
                    if (drill && face == Direction.UP && y != center.getY() + 1) continue;
                    int dist = paths.distance(x, y, z);
                    if (dist < 0) continue;
                    double ex = x + 0.5 - faceCentre.x;
                    double ey = y + eye - faceCentre.y;
                    double ez = z + 0.5 - faceCentre.z;
                    // Has to be out in front of the face, close enough to reach it, and not looking at it edge-on.
                    double front = ex * face.getStepX() + ey * face.getStepY() + ez * face.getStepZ();
                    if (front < 0.3) continue;
                    double d2 = ex * ex + ey * ey + ez * ez;
                    if (d2 > reach * reach) continue;
                    double square = front / Math.sqrt(d2);
                    if (square < MIN_FACING) continue;
                    if (face.getAxis().isHorizontal()) {
                        double across = Math.sqrt(Math.max(0, ex * ex + ez * ez - front * front));
                        if (Math.toDegrees(Math.atan2(across, front)) > MAX_SIDE_ANGLE) continue;
                    }
                    double far = Math.max(0, Math.sqrt(d2) - STAND_NEAR);
                    options.add(new long[] {(long) dist * 1000 + (long) (far * 1400 + Math.sqrt(d2) * 60 + (1 - square) * 300), BlockPos.asLong(x, y, z)});
                }
            }
        }
        options.sort((p, q) -> Long.compare(p[0], q[0]));

        int tried = 0;
        for (long[] option : options) {
            if (++tried > 40) break;
            BlockPos cell = BlockPos.of(option[1]);
            // Standing on the square is fine when you only drop one block into it.
            BlockPos floor = cell.below();
            if (breaks.contains(floor.asLong()) && !(solid(floor.below()) && !breaks.contains(floor.below().asLong()))) continue;
            Vec3 eyePos = new Vec3(cell.getX() + 0.5, cell.getY() + eye, cell.getZ() + 0.5);
            for (Vec3 point : points) {
                if (eyePos.distanceTo(point) > reach) continue;
                if (sees(eyePos, point, center, face)) {
                    return new Stand(cell, (int) (option[0] / 1000), point, eyePos.distanceTo(point));
                }
            }
        }
        return null;
    }

    /** Where to aim on a face: its middle first, then towards its corners. A little way in, so the ray lands. */
    private Vec3[] facePoints(BlockPos center, Direction face) {
        Vec3 mid = Vec3.atCenterOf(center).add(face.getStepX() * 0.4, face.getStepY() * 0.4, face.getStepZ() * 0.4);
        Direction.Axis[] plane = switch (face.getAxis()) {
            case X -> new Direction.Axis[] {Direction.Axis.Y, Direction.Axis.Z};
            case Y -> new Direction.Axis[] {Direction.Axis.X, Direction.Axis.Z};
            case Z -> new Direction.Axis[] {Direction.Axis.X, Direction.Axis.Y};
        };
        Vec3 u = unit(plane[0]).scale(0.28);
        Vec3 v = unit(plane[1]).scale(0.28);
        return new Vec3[] {mid, mid.add(u).add(v), mid.add(u).subtract(v), mid.subtract(u).add(v), mid.subtract(u).subtract(v)};
    }

    private static Vec3 unit(Direction.Axis axis) {
        return switch (axis) {
            case X -> new Vec3(1, 0, 0);
            case Y -> new Vec3(0, 1, 0);
            case Z -> new Vec3(0, 0, 1);
        };
    }

    private boolean sees(Vec3 eye, Vec3 point, BlockPos center, Direction face) {
        BlockHitResult hit = mc.level.clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(center) && hit.getDirection() == face;
    }

    private boolean solid(BlockPos pos) {
        return !mc.level.getBlockState(pos).getCollisionShape(mc.level, pos).isEmpty();
    }

    private static double eyeHeight() {
        return mc.player.getDimensions(Pose.STANDING).eyeHeight();
    }

    private static long hitKey(BlockPos center, Direction face) {
        return center.asLong() * 6 + face.ordinal();
    }

    /**
     * The last sweep, once the route has nothing left it can do: any cell with blocks left gets
     * another go at {@link #rescue}, stage 0 for every cell, then stage 1. Works through cells a
     * few at a time; stage 2 means it looked everywhere and found nothing.
     * @return the job for the first cell along the route that can be helped, or null for now.
     */
    private Job continueCleanup(long deadline) {
        while (cleanupStage < 2) {
            if (cleanupStage == 1 && !edgeBlocks.is("Other tool")) {
                cleanupStage = 2;
                break;
            }
            if (cleanupCursor < 0) {
                cleanupStage++;
                cleanupCursor = plan.nextPending(0);
                continue;
            }
            int rank = cleanupCursor;
            cleanupCursor = plan.nextPending(rank + 1);
            if (bannedCells.containsKey(rank) || plan.count(rank) <= 0) continue;
            Job best = rescue(rank, cleanupStage);
            if (best != null) return best;
            if (System.nanoTime() > deadline) return null;
        }
        return null;
    }

    /**
     * Something that takes a block from this cell when no lane-shaped hit can: any safe drill hit
     * at all ({@code stage} 0), or failing that one block with a tool that isn't the drill
     * (stage 1), so no square cuts past the edge. Null if there's nothing.
     */
    private Job rescue(int rank, int stage) {
        int h = plan.half;
        BlockPos.MutableBlockPos centre = new BlockPos.MutableBlockPos();
        Job best = null;
        double bestCost = Double.MAX_VALUE;
        for (BlockPos q : plan.owned(rank)) {
            BlockState state = mc.level.getBlockState(q);
            if (!AreaPlan.diggable(mc.level, q, state) || nearLava(q)) continue;
            if (stage == 0) {
                for (Direction.Axis axis : Direction.Axis.values()) {
                    for (int i = -h; i <= h; i++) {
                        for (int j = -h; j <= h; j++) {
                            square(q, axis, -i, -j, centre);
                            BlockPos center = centre.immutable();
                            Direction pos = Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE);
                            for (Direction face : new Direction[] {pos, pos.getOpposite()}) {
                                if (!open(center.relative(face)) || bannedHits.containsKey(hitKey(center, face))) continue;
                                int gain = eval(center, face);
                                if (gain <= 0) continue;
                                Stand stand = stand(center, face, mayBreak(center, face), true);
                                if (stand == null) continue;
                                double cost = -gain + 2.0 * stand.dist + (axis == Direction.Axis.Y ? DOWN_PENALTY : 0);
                                if (cost < bestCost) {
                                    bestCost = cost;
                                    best = new Job(rank, center, axis, face, stand.aim, stand.cell, false, -1, gain);
                                }
                            }
                        }
                    }
                }
            } else {
                int slot = edgeTool(state);
                if (slot < 0) continue;
                for (Direction face : Direction.values()) {
                    if (!open(q.relative(face)) || bannedHits.containsKey(hitKey(q, face))) continue;
                    Stand stand = stand(q, face, LongSets.singleton(q.asLong()), false);
                    if (stand == null) continue;
                    double cost = 2.0 * stand.dist;
                    if (cost < bestCost) {
                        bestCost = cost;
                        best = new Job(rank, q.immutable(), face.getAxis(), face, stand.aim, stand.cell, true, slot, 1);
                    }
                }
            }
        }
        return best;
    }

    /** Leaves this spot alone for a while; {@code why} is just for reading the code. */
    private void ban(long until, String why) {
        bannedHits.put(hitKey(job.center, job.face), until);
    }

    /** The best hotbar tool for {@code state} that isn't the drill, or -1. */
    private int edgeTool(BlockState state) {
        Inventory inventory = mc.player.getInventory();
        int best = -1;
        float bestSpeed = 1.5f;
        for (int slot = 0; slot < 9; slot++) {
            if (slot == drillSlot) continue;
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || isDrill(stack)) continue;
            if (stack.isDamageableItem() && stack.getMaxDamage() - stack.getDamageValue() <= TOOL_RESERVE) continue;
            float speed = stack.getDestroySpeed(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                best = slot;
            }
        }
        return best;
    }

    /** The drill, or another one like it: same item, same name. Never used for single blocks. */
    private boolean isDrill(ItemStack stack) {
        return ItemStack.isSameItem(stack, drillStack) && stack.getHoverName().getString().equals(drillStack.getHoverName().getString());
    }

    /** No hit anywhere. Make sure it stays that way for a moment, then wrap up. */
    private void nothingToDo(LocalPlayer player, long now) {
        if (emptySince == 0) {
            emptySince = now;
            status = "Checking for anything left";
            return;
        }
        if (now - emptySince < EMPTY_CONFIRM || !plan.counted()) return;
        emptySince = 0;

        // Spots that failed once get another go before anything is called out of reach.
        if ((!bannedHits.isEmpty() || !bannedCells.isEmpty()) && retries < MAX_RETRIES) {
            retries++;
            bannedHits.clear();
            bannedCells.clear();
            status = "Trying the tricky spots again";
            return;
        }

        if (plan.unknown() > 0) {
            pause("Part of the area isn't loaded: get closer, then Resume");
            return;
        }
        long left = 0;
        for (int rank = plan.nextPending(0); rank >= 0; rank = plan.nextPending(rank + 1)) {
            if (plan.recount(mc.level, rank) > 0) left++;
        }
        if (left > 0 && plan.target.bounds.distanceTo(player.getX(), player.getY(), player.getZ()) > 8) {
            pause("Can't find a way into the area from here: get next to it, then Resume");
            return;
        }
        boolean lava = false;
        for (int rank = plan.nextPending(0); rank >= 0; rank = plan.nextPending(rank + 1)) {
            lava |= allNearLava(rank);
            plan.giveUp(rank);
        }
        finish(plan.skipped() == 0 ? "Done" : lava ? "Done, around lava" : "Done, some blocks out of reach");
    }

    // ---- Walking

    private void tickWalk(LocalPlayer player, long now) {
        if (!stillValid()) {
            resetPhase();
            return;
        }
        if (player.onGround() && doableFromHere(player)) {
            steering = Input.EMPTY;
            beginMining();
            return;
        }
        if (path == null) {
            resetPhase();
            return;
        }
        if (pathIndex >= path.size()) {
            // At the spot: shuffle onto its middle, where the hit was planned from.
            Vec3 middle = Vec3.atBottomCenterOf(job.stand);
            double off = Math.hypot(middle.x - player.getX(), middle.z - player.getZ());
            // Still dropping down onto it doesn't count as being there.
            boolean landed = player.onGround() && Math.abs(player.getY() - job.stand.getY()) < 0.6;
            if (landed && off < 0.2 || now - phaseSince > WALK_STUCK) {
                // Right there and still can't hit it: something changed. Try something else for now.
                ban(now + 15_000_000_000L, "at stand, can't hit");
                resetPhase();
                return;
            }
            aimAt(job.aim);
            steering = walk(player, middle, false, false);
            return;
        }

        BlockPos node = path.get(pathIndex);
        Vec3 target = Vec3.atBottomCenterOf(node);
        double dx = target.x - player.getX();
        double dz = target.z - player.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        boolean up = node.getY() > player.getY() + 0.5;
        if (horizontal < 0.3 && (!up || player.getY() > node.getY() - 0.2)) {
            pathIndex++;
            lastMoveAt = now;
            if (pathIndex >= path.size()) {
                steering = Input.EMPTY;
                phaseSince = now;
                return;
            }
            node = path.get(pathIndex);
            target = Vec3.atBottomCenterOf(node);
            up = node.getY() > player.getY() + 0.5;
        }

        // Look where you're going on a long walk, at the block once it's close.
        int remaining = path.size() - 1 - pathIndex;
        if (remaining > 3) {
            BlockPos ahead = path.get(Math.min(path.size() - 1, pathIndex + 2));
            Vec3 look = Vec3.atBottomCenterOf(ahead).add(0, eyeHeight(), 0);
            if (!lookingAhead) {
                aim.setTarget(look);
                lookingAhead = true;
            } else {
                aim.track(look);
            }
        } else {
            aimAt(job.aim);
        }

        if (player.position().distanceToSqr(lastPos) > 0.01) {
            lastPos = player.position();
            lastMoveAt = now;
        }
        boolean stuck = now - lastMoveAt > 1_000_000_000L;
        if (now - lastMoveAt > WALK_STUCK) {
            ban(now + 20_000_000_000L, "walk stuck");
            resetPhase();
            return;
        }
        boolean jump = player.onGround() && (up && horizontal < 1.4 || stuck && now - lastJumpAt > 800_000_000L);
        if (jump) lastJumpAt = now;
        boolean down = node.getY() < player.getY() - 0.5;
        steering = walk(player, target, jump, !down && nearDrop(player.blockPosition()));
    }

    private void aimAt(Vec3 point) {
        if (lookingAhead || aim.target() == null || !aim.target().equals(point)) aim.setTarget(point);
        lookingAhead = false;
    }

    /** Keys for walking towards {@code target} whichever way you're facing: forward, back, strafe, or a diagonal. */
    private static Input walk(LocalPlayer player, Vec3 target, boolean jump, boolean sneak) {
        return Steer.toward(player, target, jump, sneak);
    }

    /** A drop of more than three blocks right next to you: sneak so a stumble can't take you over it. */
    private boolean nearDrop(BlockPos feet) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = feet.relative(d);
            if (!Paths.passable(mc.level, n)) continue;
            boolean deep = true;
            for (int k = 1; k <= 4; k++) {
                if (!Paths.passable(mc.level, n.below(k))) {
                    deep = false;
                    break;
                }
            }
            if (deep) return true;
        }
        return false;
    }

    /** Whether the job still makes sense: its block is still there and its square still safe. */
    private boolean stillValid() {
        if (job == null) return false;
        if (!AreaPlan.diggable(mc.level, job.center, mc.level.getBlockState(job.center))) return false;
        if (!open(job.center.relative(job.face))) return false;
        if (job.edge) return true;
        evals.remove(hitKey(job.center, job.face));
        lavaNear.clear();
        return eval(job.center, job.face) > 0;
    }

    /** Whether the face can be hit from right where you are; points the aim at the spot that works. */
    private boolean doableFromHere(LocalPlayer player) {
        Vec3 eye = player.getEyePosition();
        double reach = player.blockInteractionRange() - 0.15;
        Vec3 faceCentre = Vec3.atCenterOf(job.center).add(job.face.getStepX() * 0.5, job.face.getStepY() * 0.5, job.face.getStepZ() * 0.5);
        if (facing(eye, faceCentre, job.face) < MIN_FACING) return false;
        if (!job.edge && job.face.getAxis().isHorizontal()) {
            // Into a wall, only from where you'd face into it: aimed at its squarest spot from here.
            Vec3 square = squarest(eye, job.center, job.face);
            if (sideAngle(eye, square, job.face) > MAX_SIDE_ANGLE) return false;
            if (eye.distanceTo(square) > reach || !sees(eye, square, job.center, job.face)) return false;
            job = new Job(job.rank, job.center, job.axis, job.face, square, job.stand, job.edge, job.slot, job.gain);
            return true;
        }
        if (eye.distanceTo(job.aim) <= reach && sees(eye, job.aim, job.center, job.face)) return true;
        for (Vec3 point : facePoints(job.center, job.face)) {
            if (eye.distanceTo(point) <= reach && sees(eye, point, job.center, job.face)) {
                job = new Job(job.rank, job.center, job.axis, job.face, point, job.stand, job.edge, job.slot, job.gain);
                return true;
            }
        }
        return false;
    }

    /**
     * The spot on a wall face nearest straight ahead of {@code eye}: looking at it you face as
     * squarely into the wall as you can from there. Kept off the edges, at the face's mid height.
     */
    private static Vec3 squarest(Vec3 eye, BlockPos center, Direction face) {
        Vec3 mid = Vec3.atCenterOf(center).add(face.getStepX() * 0.4, 0, face.getStepZ() * 0.4);
        if (face.getAxis() == Direction.Axis.X) return mid.add(0, 0, Math.clamp(eye.z - mid.z, -0.3, 0.3));
        return mid.add(Math.clamp(eye.x - mid.x, -0.3, 0.3), 0, 0);
    }

    /** How far off head on (degrees, sideways only) looking from {@code eye} at {@code point} on a wall face is. */
    private static double sideAngle(Vec3 eye, Vec3 point, Direction face) {
        double dx = point.x - eye.x;
        double dz = point.z - eye.z;
        double into = -(dx * face.getStepX() + dz * face.getStepZ());
        double across = Math.abs(dx * face.getStepZ() - dz * face.getStepX());
        return Math.toDegrees(Math.atan2(across, into));
    }

    /**
     * How squarely you look at a face, 1 head-on down to 0 edge-on. Hit at a shallow angle, one
     * mouse count moves the crosshair a long way across the face and onto the next block.
     */
    private static double facing(Vec3 eye, Vec3 faceCentre, Direction face) {
        Vec3 rel = eye.subtract(faceCentre);
        double len = rel.length();
        if (len < 1e-6) return 0;
        return (rel.x * face.getStepX() + rel.y * face.getStepY() + rel.z * face.getStepZ()) / len;
    }

    // ---- Mining

    private void beginMining() {
        enter(Phase.MINE);
        steadyTicks = 0;
        before = cube();
        quietBefore = System.nanoTime();
        steering = Input.EMPTY;
        lookingAhead = false;
        // A random spot on the face, the way a hand never lands twice in the same place.
        double spread = 0.05 + 0.15 * randomness.get();
        Vec3 mid = Vec3.atCenterOf(job.center).add(job.face.getStepX() * 0.4, job.face.getStepY() * 0.4, job.face.getStepZ() * 0.4);
        Vec3 offset = job.aim.subtract(mid).add(facePlaneJitter(job.face, spread));
        // Kept well inside the face: near its edge the crosshair slips onto the next block.
        Vec3 point = mid.add(Math.clamp(offset.x, -0.3, 0.3), Math.clamp(offset.y, -0.3, 0.3), Math.clamp(offset.z, -0.3, 0.3));
        Vec3 eye = mc.player.getEyePosition();
        if (!job.edge && job.face.getAxis().isHorizontal() && sideAngle(eye, point, job.face) > MAX_SIDE_ANGLE - 3) {
            // Too far along the wall would turn you to face another way: slide back to its squarest spot.
            Vec3 square = squarest(eye, job.center, job.face);
            point = new Vec3(square.x, point.y, square.z);
        }
        if (sees(mc.player.getEyePosition(), point, job.center, job.face)) {
            job = new Job(job.rank, job.center, job.axis, job.face, point, job.stand, job.edge, job.slot, job.gain);
        }
        aim.setTarget(job.aim);
    }

    private Vec3 facePlaneJitter(Direction face, double spread) {
        double u = Math.clamp(random.nextGaussian() * spread, -0.25, 0.25);
        double v = Math.clamp(random.nextGaussian() * spread, -0.25, 0.25);
        return switch (face.getAxis()) {
            case X -> new Vec3(0, u, v);
            case Y -> new Vec3(u, 0, v);
            case Z -> new Vec3(u, v, 0);
        };
    }

    private void tickMine(LocalPlayer player, long now) {
        steering = Input.EMPTY;
        if (!AreaPlan.diggable(mc.level, job.center, mc.level.getBlockState(job.center))) {
            hits++;
            retries = 0;
            if (job.edge) edgeHits++;
            Job next = carryOn(player);
            if (next == null) next = fromHere(player);
            if (next != null) {
                // Still holding: the next hit's block is right there from where you stand. Its
                // cut isn't watched: the last one may still be landing round it.
                Vec3 was = job.aim;
                job = next;
                before = null;
                lastBreakAt = now;
                if (jobRanks.size() < 100_000) jobRanks.add(next.rank);
                status = describe(next);
                phaseSince = now;
                offTargetSince = 0;
                // A small turn just carries on; a bigger one is a fresh move of the hand.
                if (next.aim.distanceTo(was) > 0.6 && !downTheLane(next)) {
                    aim.setTarget(next.aim);
                    steadyTicks = 0;
                } else {
                    aim.track(next.aim);
                }
                return;
            }
            brokeFacing = player.getDirection();
            // Only a hit that started with everything still is worth learning from.
            if (quietBefore - lastBreakAt < QUIET_BEFORE) before = null;
            lastBreakAt = now;
            settleSince = now;
            lastChangeAt = now;
            settleCount = cubeCount();
            settleStartCount = settleCount;
            enter(Phase.SETTLE);
            return;
        }
        if (fallingNear(player)) {
            if (fallingOverhead(player)) steering = away(player);
            return;
        }

        Inventory inventory = player.getInventory();
        int want = job.edge ? job.slot : drillSlot;
        if (want >= 0 && inventory.getSelectedSlot() != want) {
            inventory.setSelectedSlot(want);
            slotReadyAt = now + 120_000_000L;
        }

        boolean onTarget = mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
            && hit.getBlockPos().equals(job.center) && hit.getDirection() == job.face;
        // The server works out which face you hit from the aim it last heard about, a tick
        // behind. Two ticks steady on the face before swinging, so it sees the same face we do.
        steadyTicks = onTarget ? steadyTicks + 1 : 0;
        if (!onTarget && aim.error() < 1.5) {
            if (offTargetSince == 0) offTargetSince = now;
            // Aimed right but the crosshair lands on something else: that spot won't work.
            if (now - offTargetSince > OFF_TARGET_TIMEOUT) {
                ban(now + 30_000_000_000L, "crosshair off target");
                resetPhase();
            }
            return;
        }
        offTargetSince = 0;
        if (downTheLane(job)) steering = closeIn(player);
        if (now - phaseSince > MINE_TIMEOUT) {
            Notify.send("Area Miner", "Couldn't break " + mc.level.getBlockState(job.center).getBlock().getName().getString()
                + " at " + coords(job.center) + ", coming back to it later", false, false, false);
            bannedCells.put(job.rank, now + 90_000_000_000L);
            resetPhase();
        }
    }

    /**
     * Straight on to the next slice down the lane, without letting go, if that's the next thing on
     * the route anyway and it's safe from right here. Not until the drill's cut into a wall has
     * been seen a couple of times: those hits pause to watch what goes. Null to stop and look.
     */
    private Job carryOn(LocalPlayer player) {
        if (!downTheLane(job) || model.samples(DrillModel.Kind.SIDE) < 2) return null;
        int rank = job.rank + 1;
        if (rank >= plan.cells || plan.lattice(rank) != plan.lattice(job.rank) || plan.layer(rank) != plan.layer(job.rank)
            || plan.lane(rank) != plan.lane(job.rank) || bannedCells.containsKey(rank) || plan.isGivenUp(rank)) return null;
        if (plan.recount(mc.level, rank) <= 0) return null;
        AreaPlan.Lattice l = plan.lattice(rank);
        Direction face = back(rank);
        BlockPos center = l.pos(plan.along(rank), plan.lane(rank), plan.base(rank) + plan.half);
        if (!AreaPlan.diggable(mc.level, center, mc.level.getBlockState(center)) || !open(center.relative(face))) return null;
        evals.remove(hitKey(center, face));
        int gain = eval(center, face);
        if (gain <= 0) return null;
        Vec3 eye = player.getEyePosition();
        Vec3 point = squarest(eye, center, face);
        if (sideAngle(eye, point, face) > MAX_SIDE_ANGLE || eye.distanceTo(point) > player.blockInteractionRange() - 0.3
            || !sees(eye, point, center, face)) return null;
        return new Job(rank, center, l.along, face, point, player.blockPosition(), false, -1, gain);
    }

    /**
     * Any next hit on the route that can be made from right where you stand, found straight
     * away rather than after stopping to look: turning into the next lane, or going down into a
     * layer. Not when it's a block the cut that just landed is about to take (the server can be a
     * moment behind), and not until the drill's cut for this kind of hit has been seen.
     */
    private Job fromHere(LocalPlayer player) {
        if (job.edge || model.samples(DrillModel.Kind.SIDE) < 2 || model.samples(DrillModel.kind(job.face)) < 2) return null;
        List<BlockPos> cut = willBreak(job.center, job.face, player.getDirection());
        Job done = job;
        peeking = true;
        beginScan(player);
        boolean finished = continueScan(System.nanoTime() + CHOOSE_BUDGET * 4);
        scanning = false;
        peeking = false;
        Job next = finished ? scanBest : null;
        if (next == null || next.edge || cut.contains(next.center)) return null;
        job = next;
        boolean here = doableFromHere(player);
        Job aimed = job;
        job = done;
        return here ? aimed : null;
    }

    /**
     * Down a lane: a step forward along the lane's middle while mining, once the face has moved
     * more than {@link #CLOSE} away. Never onto anything the hit might take.
     */
    private Input closeIn(LocalPlayer player) {
        if (!player.onGround() || player.getEyePosition().distanceTo(job.aim) <= CLOSE) return Input.EMPTY;
        AreaPlan.Lattice l = plan.lattice(job.rank);
        Direction ahead = back(job.rank).getOpposite();
        double along = l.along == Direction.Axis.X ? player.getX() : player.getZ();
        double target = along + ahead.getAxisDirection().getStep();
        // Not past the cell behind the face.
        double limit = plan.along(job.rank) + 0.5 - ahead.getAxisDirection().getStep() * 1.0;
        if (ahead.getAxisDirection().getStep() > 0 ? target > limit : target < limit) target = limit;
        double across = plan.lane(job.rank) + 0.5;
        Vec3 spot = l.along == Direction.Axis.X ? new Vec3(target, player.getY(), across) : new Vec3(across, player.getY(), target);
        BlockPos cell = BlockPos.containing(spot);
        if (!Paths.standable(mc.level, cell) || mayBreak(job.center, job.face).contains(cell.below().asLong())) return Input.EMPTY;
        if (Math.hypot(spot.x - player.getX(), spot.z - player.getZ()) < 0.2) return Input.EMPTY;
        return walk(player, spot, false, false);
    }

    /**
     * The block you hit goes at once; the rest of the drill's square only goes when the server
     * says so, a moment later. Give it that moment before deciding what's left.
     */
    private void tickSettle(long now) {
        steadyTicks = 0;
        steering = Input.EMPTY;
        boolean settled = job.edge;
        if (!settled) {
            settled = true;
            for (BlockPos q : willBreak(job.center, job.face, brokeFacing)) {
                if (plan.target.contains(q) && AreaPlan.diggable(mc.level, q, mc.level.getBlockState(q))) {
                    settled = false;
                    break;
                }
            }
        }
        int count = cubeCount();
        if (count != settleCount) {
            settleCount = count;
            lastChangeAt = now;
        }
        // Whatever the drill was going to break has gone quiet for a moment: that's its lot.
        boolean quiet = now - lastChangeAt > 250_000_000L;
        DrillModel.Kind kind = DrillModel.kind(job.face);
        if (!job.edge && model.samples(kind) == 0 && learnTries[kind.ordinal()] < 4) {
            // First hit of this kind: it doesn't know what the drill does here yet, so it can't tell
            // "done" from "the server hasn't answered". Wait for the rest of the cut to show up.
            if (count >= settleStartCount && now - settleSince < LEARN_SETTLE) return;
            if (!quiet && now - settleSince < LEARN_SETTLE) return;
        } else if (!settled && !quiet && now - settleSince < SETTLE_NANOS) {
            return;
        }
        if (!job.edge && before != null) {
            if (model.samples(kind) == 0) learnTries[kind.ordinal()]++;
            int took = model.observe(kind, DrillModel.forward(job.face, brokeFacing), before, cube());
            mays.clear();
            evals.clear();
            String shape = model.describe(kind);
            // Only once it's seen a whole cut: a hit next to open air shows just part of the shape.
            if (!toldShape[kind.ordinal()] && shape != null && (took >= 5 || model.samples(kind) >= 3)) {
                toldShape[kind.ordinal()] = true;
                String way = kind == DrillModel.Kind.SIDE ? "into a wall" : kind == DrillModel.Kind.UP ? "going down" : "going up";
                Notify.send("Area Miner", "Your drill cuts " + shape + " " + way + ". Planning around that", false, false, false);
            }
        }
        before = null;
        resetPhase();
    }

    /** Where there's something to dig in the cube around the job's block, for watching what the drill took. */
    private boolean[] cube() {
        int r = model.radius();
        boolean[] out = new boolean[model.cubeSize()];
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    q.set(job.center.getX() + dx, job.center.getY() + dy, job.center.getZ() + dz);
                    out[model.cell(dx, dy, dz)] = AreaPlan.diggable(mc.level, q, mc.level.getBlockState(q));
                }
            }
        }
        return out;
    }

    private int cubeCount() {
        int n = 0;
        for (boolean b : cube()) {
            if (b) n++;
        }
        return n;
    }

    // ---- Hazards

    private String hazard(LocalPlayer player) {
        float health = player.getHealth();
        boolean hurt = health < lastHealth;
        lastHealth = health;
        if (pauseHurt.get() && hurt) return "You took damage";
        if (pauseFull.get() && player.getInventory().getFreeSlot() < 0) return "Inventory full";

        if (drillSlot >= 0 && !drillStack.isEmpty()) {
            ItemStack drill = player.getInventory().getItem(drillSlot);
            if (!ItemStack.isSameItem(drill, drillStack)) return "Your drill isn't in hotbar slot " + (drillSlot + 1) + " anymore";
            if (saveTool.get() && drill.isDamageableItem() && drill.getMaxDamage() - drill.getDamageValue() <= TOOL_RESERVE) {
                return "Drill almost broken";
            }
        }

        BlockPos feet = player.blockPosition();
        for (BlockPos p : new BlockPos[] {feet, feet.above()}) {
            if (mc.level.getFluidState(p).is(FluidTags.LAVA)) return "Lava right on you at " + coords(p);
            for (Direction d : Direction.values()) {
                if (mc.level.getFluidState(p.relative(d)).is(FluidTags.LAVA)) return "Lava right next to you at " + coords(p.relative(d));
            }
        }

        if (pauseMobs.get()) {
            double range = mobRange.get();
            for (Entity mob : mc.level.getEntities(player, player.getBoundingBox().inflate(range),
                e -> e instanceof Enemy && e.isAlive() && e.distanceTo(player) <= range && player.hasLineOfSight(e))) {
                return mob.getName().getString() + " " + Math.round(mob.distanceTo(player)) + "m away";
            }
        }
        return null;
    }

    private boolean fallingNear(LocalPlayer player) {
        AABB around = player.getBoundingBox().inflate(plan.size + 1, 0, plan.size + 1).expandTowards(0, 24, 0);
        return !mc.level.getEntitiesOfClass(FallingBlockEntity.class, around).isEmpty();
    }

    private boolean fallingOverhead(LocalPlayer player) {
        AABB column = player.getBoundingBox().inflate(0.2, 0, 0.2).expandTowards(0, 24, 0);
        for (FallingBlockEntity falling : mc.level.getEntitiesOfClass(FallingBlockEntity.class, column)) {
            if (falling.getY() > player.getY() + 1) return true;
        }
        return false;
    }

    /** Back away from whatever's falling on you: towards where you came from, or away from the face. */
    private Input away(LocalPlayer player) {
        Vec3 from;
        if (path != null && pathIndex > 1) {
            from = Vec3.atBottomCenterOf(path.get(pathIndex - 2));
        } else if (job != null) {
            from = player.position().add(job.face.getStepX(), 0, job.face.getStepZ());
        } else {
            from = player.position().subtract(player.getLookAngle().multiply(1, 0, 1));
        }
        return walk(player, from, false, false);
    }

    private static String coords(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    /**
     * The route through the layer being dug, along the floor: each lane end to end and across to
     * the next, the way it snakes. Lanes already done are faint, the one it's on is bright. Warm,
     * so it never reads as part of the area's outline.
     */
    private void drawRoute(Render3D render) {
        int current = job != null ? job.rank : plan.nextPending(0);
        if (current < 0) return;
        AreaPlan.Lattice l = plan.lattice(current);
        int start = plan.layerStart(current);
        int len = plan.laneLength(current);
        int lanes = plan.lanes(current);
        double y = plan.base(current) + 0.06;
        int done = Theme.fade(ROUTE_COLOR, 0.3f);
        int active = ROUTE_COLOR;
        int ahead = Theme.fade(ROUTE_COLOR, 0.65f);
        Vec3 previous = null;
        for (int n = 0; n < lanes; n++) {
            int first = start + n * len;
            int last = first + len - 1;
            Vec3 from = floorPoint(l, plan.along(first), plan.lane(first), y);
            Vec3 to = floorPoint(l, plan.along(last), plan.lane(last), y);
            int color = last < current ? done : first <= current ? active : ahead;
            if (previous != null) render.line(previous, from, first <= current ? color : ahead, 2f, true);
            render.line(from, to, color, first <= current && last >= current ? 4f : 2f, true);
            previous = to;
        }
    }

    private static Vec3 floorPoint(AreaPlan.Lattice l, int a, int lane, double y) {
        BlockPos p = l.pos(a, lane, 0);
        return new Vec3(p.getX() + 0.5, y, p.getZ() + 0.5);
    }

    // ---- Drawing

    @Override
    public void onRender3D(Render3D render) {
        boolean running = isEnabled() && plan != null;
        if (outline.is("Off") || (!running && !outline.is("Always"))) return;
        // Corners are just numbers: picked on one server, they mean nothing on another, or in the Nether.
        boolean elsewhere = !running && selection.source == AreaSelection.Source.COORDS
            && selection.world != null && !selection.world.equals(worldKey());
        if (elsewhere) return;
        AreaTarget target = running ? plan.target : preview();
        int rgb = outlineColor.get();
        int line = 0xFF000000 | rgb;

        if (target != null) {
            Vec3 cam = render.camera();
            for (Region r : target.regions) {
                if (r.distanceTo(cam.x, cam.y, cam.z) > 512) continue;
                // Edges only: a filled box would tint the whole screen once you're standing inside it.
                render.box(r.aabb(), 0, line, 2f, true);
            }
        }
        if (!running) {
            // Corners while you're picking them, so you can see where each one is.
            if (selection.source == AreaSelection.Source.COORDS) {
                if (selection.pos1 != null) render.box(new AABB(selection.pos1).inflate(0.02), 0x405BE07A, 0xFF5BE07A, 2f, false);
                if (selection.pos2 != null) render.box(new AABB(selection.pos2).inflate(0.02), 0x40FFB347, 0xFFFFB347, 2f, false);
            }
            return;
        }

        int drawn = 0;
        for (int rank = plan.nextGivenUp(0); rank >= 0 && drawn < MAX_GIVEN_UP_DRAWN; rank = plan.nextGivenUp(rank + 1)) {
            if (plan.count(rank) <= 0) continue;
            render.box(plan.box(rank), 0x20FF5D62, 0xC0FF5D62, 1.2f, false);
            drawn++;
        }

        if (showPath.get()) drawRoute(render);

        Job j = job;
        if (j != null) {
            BlockPos a = j.center;
            AABB square = new AABB(a);
            if (!j.edge) {
                for (BlockPos q : willBreak(a, j.face, mc.player.getDirection())) square = square.minmax(new AABB(q));
            }
            render.box(square, 0x28FFFFFF, 0xC0FFFFFF, 1.5f, false);
            render.box(new AABB(a).inflate(0.01), 0, line, 2.5f, true);
            if (showPath.get() && path != null && phase == Phase.WALK) {
                for (int i = Math.max(1, pathIndex); i < path.size(); i++) {
                    Vec3 from = Vec3.atBottomCenterOf(path.get(i - 1)).add(0, 0.05, 0);
                    Vec3 to = Vec3.atBottomCenterOf(path.get(i)).add(0, 0.05, 0);
                    render.line(from, to, Theme.fade(line, 0.7f), 1.5f, false);
                }
            }
        }
    }
}
