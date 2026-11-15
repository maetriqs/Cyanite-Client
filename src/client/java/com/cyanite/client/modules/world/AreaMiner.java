package com.cyanite.client.modules.world;

import com.cyanite.client.gui.Theme;
import com.cyanite.client.litematica.Litematica;
import com.cyanite.client.modules.donut.AuctionBuyer;
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
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
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
 * without a swing. Lava and water in and next to the area are filled with blocks from your
 * inventory as they come in reach: the source blocks, every one in reach before it digs on (what
 * flows from them dries up after), and the blocks that land inside the area are mined out with
 * the rest (the ones outside stay, as a wall). With no blocks to fill with, it buys
 * a stack of sand off /ah. With clearing off, or nothing to fill with, lava is dug around from
 * whichever side is still safe; whatever stays too close to it is left and counted as skipped. Blocks the drill can't take without cutting
 * outside the area go to another tool from your hotbar, if you have one.
 *
 * <p>If the floor opens into a cave and you fall in, it builds back up: it walks back if it can,
 * and otherwise jumps and puts blocks under itself (stone, cobble, dirt and the like from your
 * inventory) until it's back on the floor it fell from, then carries on.
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
    /** Building back up: this long without gaining a block and it tries somewhere else. */
    private static final long CLIMB_STUCK = 6_000_000_000L;
    private static final int CLIMB_TRIES = 3;
    /** Damage this soon after landing from a fall is the fall's. */
    private static final long FALL_DAMAGE_WINDOW = 1_500_000_000L;
    /** fillerSlot(): one's on its way into the hotbar; there are none; the hotbar's all tools. */
    private static final int FILLER_MOVING = -1;
    private static final int FILLER_NONE = -2;
    private static final int FILLER_NO_ROOM = -3;
    /** Lava or water filled this many times in one spot keeps coming back: left alone, the lava gap kept round it. */
    private static final int MAX_FILLS = 8;
    /** A fill the crosshair can't get onto in this long is passed over for a while. */
    private static final long FILL_TIMEOUT = 2_500_000_000L;
    private static final long FILL_PASS = 15_000_000_000L;
    private static final int SAND_STACK = 64;
    /** A go at buying sand that came to nothing isn't tried again for five minutes. */
    private static final long BUY_RETRY = 300_000;
    /** Longest walk over to somewhere it can fill lava or water from. */
    private static final long FILL_WALK = 10_000_000_000L;
    /** Longest it waits for filled lava and water to drain away before calling what's behind it out of reach. */
    private static final long DRAIN_WAIT = 12_000_000_000L;
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

    private enum Phase { CHOOSE, WALK, MINE, SETTLE, CLIMB }

    private record Job(int rank, BlockPos center, Direction.Axis axis, Direction face, Vec3 aim, BlockPos stand,
                       boolean edge, int slot, int gain) {
    }

    private record Stand(BlockPos cell, int dist, Vec3 aim, double reach) {
    }

    /** Lava or water to fill: the block it goes in, the block it's put against, and where on that to aim. */
    private record Fill(BlockPos cell, BlockPos against, Direction face, Vec3 aim, double cost) {
    }

    private final SliderSetting drillSize = add(new SliderSetting("Drill size", 3, 3, 9, 2).format(v -> (int) v + "x" + (int) v));
    private final SliderSetting turnSpeed = add(new SliderSetting("Turn speed", 1, 0.3, 3, 0.1).format(v -> String.format(Locale.ROOT, "%.1fx", v)));
    private final SliderSetting randomness = add(new SliderSetting("Randomness", 0.5, 0, 1, 0.05).percent());
    private final SliderSetting lavaGap = add(new SliderSetting("Lava gap", 2, 1, 4, 1).format(v -> (int) v + (v == 1 ? " block" : " blocks")));
    private final BoolSetting avoidWater = add(new BoolSetting("Avoid water", false));
    private final BoolSetting clearLava = add(new BoolSetting("Clear lava", true));
    private final BoolSetting clearWater = add(new BoolSetting("Clear water", true));
    private final BoolSetting buySand = add(new BoolSetting("Buy sand from /ah", true));
    private final SliderSetting sandMax = add(new SliderSetting("Sand max price a stack", 5_000, 64, 1_000_000, 1).logarithmic().format(SchematicBuilder::dollars));
    /** Off to match DonutSMP's own "auction buy confirm" setting being off: a click on a listing buys it. */
    private final BoolSetting ahConfirm = add(new BoolSetting("AH confirm screen", false));
    private final BoolSetting buildUp = add(new BoolSetting("Build back up", true));
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
    private final AuctionBuyer buyer = new AuctionBuyer();

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
    /** Lava or water being filled right now, the slot it's filled from, and since when. */
    private Fill fill;
    private int fillWith;
    private long fillSince;
    private long fillRest;
    /** Times each spot's been filled, spots passed over till a time, and spots given up on (the lava gap's kept round those). */
    private final Long2IntOpenHashMap fills = new Long2IntOpenHashMap();
    private final Long2LongOpenHashMap fillPassed = new Long2LongOpenHashMap();
    private final LongOpenHashSet stubborn = new LongOpenHashSet();
    /** Whether lava and water get filled rather than dug round: on, with blocks to fill with or sand to buy. */
    private boolean fillLava;
    private boolean fillWater;
    private int filled;
    private long noBuyUntil;
    private long drainSince;
    /** Lava (water) it's filling that's out in the open, not filled yet: nothing more by it is dug till it is. */
    private boolean openLava;
    private boolean openWater;
    /** Blocks on you to fill with, as of this tick. Without, lava's not dug up to till there are. */
    private boolean stocked;
    /** Lava or water by something to dig and nothing on you to fill it with: buy some first. */
    private boolean wantFiller;
    private long nextOpenLook;
    /** Walking over to fill {@link #fillGoal} from the end of {@link #fillPath}. */
    private List<BlockPos> fillPath;
    private int fillPathAt;
    private BlockPos fillGoal;
    private long fillWalkSince;
    private long nextFillLook;

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

    // Falls, and building back up after one.
    /** Your feet's block Y when you were last on the ground, and whether you've left it since. */
    private int groundY = Integer.MIN_VALUE;
    private boolean airborne;
    /** The floor to get back up to after a fall the route didn't plan; MIN_VALUE when there's none. */
    private int climbTo = Integer.MIN_VALUE;
    /** When the last fall long enough to hurt landed. */
    private long landedAt;
    /** Where it's building up, the hotbar slot it builds with, and the floor under this jump. */
    private BlockPos column;
    private int fillSlot = -1;
    private int jumpBase;
    private boolean placedThisJump;
    private Vec3 placeAim;
    private long climbProgressAt;
    private int climbFails;
    private int built;

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
        // Wherever you are now is the ground; a fall it was building up from still stands.
        groundY = Integer.MIN_VALUE;
        airborne = false;
        climbFails = 0;
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
        buyer.cancel();
        fill = null;
        fillPath = null;
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
        groundY = Integer.MIN_VALUE;
        airborne = false;
        climbTo = Integer.MIN_VALUE;
        landedAt = 0;
        climbFails = 0;
        built = 0;
        fill = null;
        fillPath = null;
        nextFillLook = 0;
        fillRest = 0;
        fills.clear();
        fillPassed.clear();
        stubborn.clear();
        fillLava = false;
        fillWater = false;
        filled = 0;
        noBuyUntil = 0;
        drainSince = 0;
        openLava = false;
        stocked = false;
        wantFiller = false;
        openWater = false;
        nextOpenLook = 0;
        buyer.cancel();
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
        fill = null;
        fillPath = null;
        buyer.cancel();
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
        if (filled > 0) sb.append(String.format(Locale.ROOT, ", %,d lava and water blocks filled", filled));
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
        // Buying sand: its /ah menus are open while it's at it.
        if (buyer.active()) {
            steering = Input.EMPTY;
            tickBuy();
            return;
        }
        if (mc.screen != null) {
            steering = Input.EMPTY;
            return;
        }

        if (trackFall(player, now)) return;
        if (escapeLava(player)) return;
        String hazard = hazard(player);
        if (hazard != null) {
            pause(hazard);
            return;
        }
        if (phase != Phase.CLIMB && tickFill(player, now)) return;

        switch (phase) {
            case CHOOSE -> tickChoose(player, now);
            case WALK -> tickWalk(player, now);
            case MINE -> tickMine(player, now);
            case SETTLE -> tickSettle(now);
            case CLIMB -> tickClimb(player, now);
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
        // Fell somewhere it didn't mean to: back up to that floor before anything else.
        if (climbTo != Integer.MIN_VALUE) {
            if (player.getBlockY() >= climbTo) {
                climbTo = Integer.MIN_VALUE;
            } else {
                startClimb(player, now);
                return;
            }
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
        drainSince = 0;
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
            // Not while it's only waiting its turn behind lava or water that's open and being filled.
            if (allNearLava(rank) && !(fillLava && (openLava || !stocked)) && !(fillWater && (openWater || !stocked))) skipForLava(rank);
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
        // Lava (and water) that gets filled isn't dug round, except where filling it gave up.
        for (long s : stubborn) {
            if (BlockPos.of(s).distManhattan(pos) <= gap) {
                lavaNear.put(key, (byte) 1);
                return true;
            }
        }
        if (held(pos)) {
            lavaNear.put(key, (byte) 1);
            return true;
        }
        // Being filled, it's dug up to and into, one lot at a time: while some is open and not
        // filled yet, nothing more by it is dug.
        boolean lava = !fillLava || openLava || !stocked;
        boolean water = avoidWater.get() && !fillWater || fillWater && (openWater || !stocked);
        if (!lava && !water) {
            lavaNear.put(key, (byte) 0);
            return false;
        }
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
                    if (lava && fluid.is(FluidTags.LAVA) || water && fluid.is(FluidTags.WATER)) {
                        // Being filled, what's out in the open already doesn't count: digging up
                        // to it lets nothing new out, and it may be the only way to get in reach.
                        // The gap's for lava you'd leave be: what's filled is only kept from being
                        // opened up while something else is, and only by what's right beside it.
                        boolean filled = stocked && (fluid.is(FluidTags.LAVA) ? fillLava : fillWater);
                        if (filled && (exposed(m, fluid) || Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > 1)) continue;
                        found = true;
                        break search;
                    }
                }
            }
        }
        lavaNear.put(key, (byte) (found ? 1 : 0));
        if (found && !stocked && (fillLava || fillWater)) wantFiller = true;
        return found;
    }

    /** Lava or water still running, or a source with air (or what runs from it) beside it. */
    private boolean exposed(BlockPos pos, FluidState fluid) {
        if (!fluid.isSource()) return true;
        for (Direction d : Direction.values()) {
            BlockPos q = pos.relative(d);
            if (!mc.level.getFluidState(q).isSource() && mc.level.getBlockState(q).getCollisionShape(mc.level, q).isEmpty()) return true;
        }
        return false;
    }

    private boolean allNearLava(int rank) {
        for (BlockPos pos : plan.owned(rank)) {
            // A plug that's only held for now doesn't count: it's dug once what's behind it drains.
            if (AreaPlan.diggable(mc.level, pos, mc.level.getBlockState(pos)) && (!nearLava(pos) || held(pos))) return false;
        }
        return true;
    }

    /**
     * A block it filled lava or water with that what it held back still runs beside: the source
     * feeding that is out of sight or reach, and dug out, it'd only come straight back. Left till
     * it drains.
     */
    private boolean held(BlockPos pos) {
        if (!fills.containsKey(pos.asLong())) return false;
        for (Direction d : Direction.values()) {
            FluidState fluid = mc.level.getFluidState(pos.relative(d));
            if (!fluid.isEmpty() && !fluid.isSource()) return true;
        }
        return false;
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

    // ---- Filling lava and water

    /**
     * Fills lava and water in and next to the area from where you stand, a block at a time: lava
     * right beside you first, then the source blocks, nearest first, every one in reach before it
     * digs on. Only sources: a block in what's flowing would hide the source behind it, and the
     * flow dries up once its source is gone. A pool fills from where there's something to put a
     * block against, the bottom row and the near side first, each block in giving the next one
     * its face. Over more lava or water, sand or gravel if you've any: it drops to the bottom.
     * What lands inside the area is mined out later with the rest; what's outside stays as a wall.
     * Out of blocks, it buys a stack of sand off /ah. @return true while it's at it (the job's
     * dropped and chosen afresh after, with the lava filled)
     */
    private boolean tickFill(LocalPlayer player, long now) {
        boolean blocks = fillerCount(player) > 0 || buySand.get() && System.currentTimeMillis() >= noBuyUntil;
        boolean lava = clearLava.get() && blocks;
        boolean water = clearWater.get() && blocks;
        if (lava != fillLava || water != fillWater) {
            // Dug round again (or not) from the next look.
            fillLava = lava;
            fillWater = water;
            lavaNear.clear();
            evals.clear();
        }
        if (!lava && !water) {
            fill = null;
            return false;
        }
        boolean nowStocked = fillerCount(player) > 0;
        if (nowStocked != stocked) {
            stocked = nowStocked;
            wantFiller = false;
            lavaNear.clear();
            evals.clear();
        }
        // Bought before it's dug into, not after: by the time a stack's come it's run a long way.
        if (!stocked && wantFiller) return buySand(player);
        lookForOpen(player, now);
        if (now < fillRest) {
            steering = Input.EMPTY;
            return true;
        }
        if (fill != null && mc.level.getFluidState(fill.cell()).isEmpty()) fill = null;
        // Lava about to run onto you comes first, even halfway through putting a block somewhere
        // else: a block in front of it, or where there's nothing to put one against, a step back.
        BlockPos feet = player.blockPosition();
        // Lava a block or two off is a second or more away (it runs a block every second and a
        // half): filling the sources it comes from goes on, and it's only stepped back from if
        // there's none in reach.
        if (fillLava && threatened(player, 3)) {
            boolean close = threatened(player, 1);
            FluidState at = fill == null ? null : mc.level.getFluidState(fill.cell());
            boolean plugging = at != null && at.is(FluidTags.LAVA) && threat(feet, fill.cell(), at);
            if (close && !plugging) fill = null;
            if (close ? !plugging && !pluggable(player) : fill == null && findFill(player) == null) {
                fillPath = null;
                if (stepBack(player)) return true;
            }
        }
        if (fill == null) {
            if (!player.onGround() && !player.isInWater()) return fillPath != null && walkToFill(player, now);
            Fill next = findFill(player);
            if (next == null) return walkToFill(player, now);
            fillPath = null;
            int slot = fillerSlot(player, !mc.level.getFluidState(next.cell().below()).isEmpty());
            if (slot == FILLER_MOVING) return true;
            if (slot == FILLER_NO_ROOM) {
                pause("No hotbar room for blocks to fill lava with: free a slot, then Resume");
                return true;
            }
            if (slot == FILLER_NONE) return buySand(player);
            fill = new Fill(next.cell(), next.against(), next.face(), next.aim().add(facePlaneJitter(next.face(), 0.1)), next.cost());
            fillWith = slot;
            fillSince = now;
            if (phase != Phase.CHOOSE) resetPhase();
            status = "Filling " + (mc.level.getFluidState(next.cell()).is(FluidTags.LAVA) ? "lava" : "water") + " at " + coords(next.cell());
        }
        steering = Input.EMPTY;
        Inventory inventory = player.getInventory();
        if (!filler(inventory.getItem(fillWith))) {
            // That stack ran out: the next.
            fill = null;
            return true;
        }
        if (now - fillSince > FILL_TIMEOUT) {
            fillPassed.put(fill.cell().asLong(), now + FILL_PASS);
            fill = null;
            return true;
        }
        if (inventory.getSelectedSlot() != fillWith) {
            inventory.setSelectedSlot(fillWith);
            slotReadyAt = now + 120_000_000L;
        }
        aimAt(fill.aim());
        if (now < slotReadyAt) return true;
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return true;
        // Any face beside the cell will do: at an angle the crosshair can land on the next one over.
        BlockState clicked = mc.level.getBlockState(hit.getBlockPos());
        if (!hit.getBlockPos().relative(hit.getDirection()).equals(fill.cell()) || clicked.hasBlockEntity()
            || !clicked.isCollisionShapeFullBlock(mc.level, hit.getBlockPos())) return true;
        if (mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit).consumesAction()) {
            player.swing(InteractionHand.MAIN_HAND);
            filled++;
            if (fills.addTo(fill.cell().asLong(), 1) + 1 >= MAX_FILLS) giveUpFilling(fill.cell());
            fill = null;
            // A hit looked at while this was lava would take the new block out with the rest.
            lavaNear.clear();
            evals.clear();
            fillRest = now + 150_000_000L + (long) (random.nextDouble() * 120_000_000L);
        }
        return true;
    }

    /** The lava or water to fill next from where you stand, or null if there's none in reach that a block can go into. */
    private Fill findFill(LocalPlayer player) {
        Vec3 eye = player.getEyePosition();
        double reach = player.blockInteractionRange() - 0.25;
        int r = (int) Math.ceil(reach);
        int gap = lavaGap.get().intValue();
        Region near = plan.target.bounds.grow(gap, gap);
        BlockPos feet = player.blockPosition();
        AABB body = player.getBoundingBox();
        long now = System.nanoTime();
        Fill best = null;
        double bestScore = Double.MAX_VALUE;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    m.set(feet.getX() + dx, feet.getY() + 1 + dy, feet.getZ() + dz);
                    FluidState fluid = mc.level.getFluidState(m);
                    if (fluid.isEmpty()) continue;
                    boolean lava = fluid.is(FluidTags.LAVA);
                    if (lava ? !fillLava : !(fillWater && fluid.is(FluidTags.WATER))) continue;
                    // Sources in the area or within the lava gap of it; what flows only when it's lava
                    // about to run onto you, or, behind every source in reach, where it comes out of one.
                    boolean touching = lava && threat(feet, m, fluid);
                    boolean seal = !touching && !fluid.isSource() && near.contains(m) && besideSource(m, lava);
                    // Last of all, lava that's run into the area: filled, it's ground to walk in on
                    // over what's still running, up to sources out of reach from here.
                    boolean flow = lava && !touching && !seal && !fluid.isSource() && plan.target.contains(m);
                    if (!touching && !seal && !flow && !(fluid.isSource() && near.contains(m))) continue;
                    long key = m.asLong();
                    if (stubborn.contains(key) || fillPassed.get(key) > now) continue;
                    if (!mc.level.getBlockState(m).canBeReplaced() || body.intersects(new AABB(m))) continue;
                    // A spot filled before (water that came back once it was mined out) after the
                    // ones behind it, or it'd be filled and mined over and over. Otherwise the one
                    // with the squarest face to put it against: the bottom row first, against the
                    // floor, and each block in gives the one above it a face.
                    // Where it comes out of a source last: shut off, what ran out drains away and the
                    // way back up to the source is clear again (it's mined open again from close up).
                    double score = (touching ? -100 : seal ? 50 : flow ? 80 : 0) + (lava ? 0 : 10) + 3 * fills.get(key);
                    if (score >= bestScore) continue;
                    Fill f = placement(eye, reach, m);
                    if (f == null || score + f.cost() >= bestScore) continue;
                    best = f;
                    bestScore = score + f.cost();
                }
            }
        }
        return best;
    }

    /** Lava or water running out of a source of its own kind beside it or right above it. */
    private boolean besideSource(BlockPos pos, boolean lava) {
        for (Direction d : Direction.values()) {
            if (d == Direction.DOWN) continue;
            FluidState next = mc.level.getFluidState(pos.relative(d));
            if (next.isSource() && next.is(FluidTags.LAVA) == lava && !stubborn.contains(pos.relative(d).asLong())) return true;
        }
        return false;
    }

    /**
     * Lava that runs onto you next: beside your feet or head with flow left in it (lava at the end
     * of its run, 2 deep, goes no further), or right over your head. Plugging any other would only
     * hide the source feeding it, and it dries up once that's filled.
     */
    private static boolean threat(BlockPos feet, BlockPos q, FluidState fluid) {
        int dx = q.getX() - feet.getX();
        int dy = q.getY() - feet.getY();
        int dz = q.getZ() - feet.getZ();
        if (dx == 0 && dz == 0) return dy == 2;
        return Math.abs(dx) + Math.abs(dz) == 1 && (dy == 0 || dy == 1) && fluid.getAmount() > 2;
    }

    /**
     * Whether any lava or water it's filling, in or by the area and near you, is out in the open
     * (air or what's running from it beside it) and not filled yet, or still running. Looked at
     * twice a second.
     */
    private void lookForOpen(LocalPlayer player, long now) {
        if (now < nextOpenLook) return;
        nextOpenLook = now + 500_000_000L;
        int gap = lavaGap.get().intValue();
        Region near = plan.target.bounds.grow(gap, gap);
        BlockPos feet = player.blockPosition();
        boolean lava = false;
        boolean water = false;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        for (int x = Math.max(near.minX(), feet.getX() - 16); x <= Math.min(near.maxX(), feet.getX() + 16); x++) {
            for (int y = Math.max(near.minY(), feet.getY() - 8); y <= Math.min(near.maxY(), feet.getY() + 8); y++) {
                for (int z = Math.max(near.minZ(), feet.getZ() - 16); z <= Math.min(near.maxZ(), feet.getZ() + 16); z++) {
                    FluidState fluid = mc.level.getFluidState(m.set(x, y, z));
                    if (fluid.isEmpty() || stubborn.contains(m.asLong())) continue;
                    boolean isLava = fluid.is(FluidTags.LAVA);
                    if (isLava ? !fillLava || lava : !fillWater || water || !fluid.is(FluidTags.WATER)) continue;
                    // Still running (from what was just filled, or what isn't yet): wait for it.
                    if (!fluid.isSource()) {
                        if (isLava) lava = true;
                        else water = true;
                        continue;
                    }
                    for (Direction d : Direction.values()) {
                        n.setWithOffset(m, d);
                        FluidState beside = mc.level.getFluidState(n);
                        if (beside.isSource() || !mc.level.getBlockState(n).getCollisionShape(mc.level, n).isEmpty()) continue;
                        if (isLava) lava = true;
                        else water = true;
                        break;
                    }
                }
            }
        }
        if (lava != openLava || water != openWater) {
            openLava = lava;
            openWater = water;
            lavaNear.clear();
            evals.clear();
        }
    }

    /**
     * Lava that can run onto someone standing at {@code feet} from where it is now: followed out
     * through open cells at foot and head height, three blocks at most, lava that's {@code d}
     * blocks along gets there if it has more than {@code 2d} in it (it loses 2 a block; what drops
     * onto a cell starts over at 8). A block to put in front of it waits for it to be right beside
     * you; this sees it coming a block or two sooner, as lava next to it can fill up and run on in
     * the same moment.
     */
    private boolean threatened(LocalPlayer player, int ahead) {
        // Every cell you're over, not just the one under your middle: the last block of a run of
        // lava burns as well as any, and standing off-centre your side's in the next one along.
        AABB box = player.getBoundingBox();
        int y = player.blockPosition().getY();
        for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX); x++) {
            for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ); z++) {
                if (threatened(new BlockPos(x, y, z), ahead)) return true;
            }
        }
        return false;
    }

    private boolean threatened(BlockPos feet) {
        return threatened(feet, 3);
    }

    private boolean threatened(BlockPos feet, int ahead) {
        if (mc.level.getFluidState(feet.above(2)).is(FluidTags.LAVA)) return true;
        for (int dy = 0; dy <= 1; dy++) {
            BlockPos start = feet.above(dy);
            if (mc.level.getFluidState(start).is(FluidTags.LAVA)) return true;
            LongOpenHashSet seen = new LongOpenHashSet();
            List<BlockPos> ring = new ArrayList<>();
            ring.add(start);
            seen.add(start.asLong());
            for (int d = 1; d <= ahead && !ring.isEmpty(); d++) {
                List<BlockPos> next = new ArrayList<>();
                for (BlockPos at : ring) {
                    for (Direction dir : Direction.Plane.HORIZONTAL) {
                        BlockPos q = at.relative(dir);
                        if (!seen.add(q.asLong())) continue;
                        FluidState fluid = mc.level.getFluidState(q);
                        int amount = fluid.is(FluidTags.LAVA) ? fluid.getAmount() : 0;
                        if (mc.level.getFluidState(q.above()).is(FluidTags.LAVA)) amount = 8;
                        if (amount > 2 * d) return true;
                        if (amount == 0 && mc.level.getBlockState(q).getCollisionShape(mc.level, q).isEmpty()) next.add(q);
                    }
                }
                ring = next;
            }
        }
        return false;
    }

    /** Lava about to run onto you that a block can go in front of from where you stand. */
    private boolean pluggable(LocalPlayer player) {
        BlockPos feet = player.blockPosition();
        Vec3 eye = player.getEyePosition();
        double reach = player.blockInteractionRange() - 0.25;
        AABB body = player.getBoundingBox();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dy = 0; dy <= 2; dy++) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                m.setWithOffset(feet, dy == 2 ? 0 : d.getStepX(), dy, dy == 2 ? 0 : d.getStepZ());
                FluidState fluid = mc.level.getFluidState(m);
                if (!fluid.is(FluidTags.LAVA) || !threat(feet, m, fluid) || body.intersects(new AABB(m))) continue;
                if (mc.level.getBlockState(m).canBeReplaced() && placement(eye, reach, m) != null) return true;
            }
        }
        return false;
    }

    /**
     * Lava about to run onto you with nothing to put a block against: a step to the nearest spot
     * beside you it isn't about to reach, away from it (it runs a block every second and a half;
     * a step takes a quarter of one). @return true while stepping
     */
    private boolean stepBack(LocalPlayer player) {
        BlockPos feet = player.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos c = feet.offset(dx, dy, dz);
                    // The middle of the one you're in will do, if it's only your side over the next one.
                    if (!Paths.passable(mc.level, c) || !Paths.passable(mc.level, c.above())) continue;
                    if (mc.level.getBlockState(c.below()).getCollisionShape(mc.level, c.below()).isEmpty() || threatened(c)) continue;
                    if (!mc.level.getFluidState(c).isEmpty() || !mc.level.getFluidState(c.above()).isEmpty()) continue;
                    // Not through a wall: a step to a spot two off needs the one between clear too.
                    BlockPos mid = feet.offset(Integer.signum(dx), 0, Integer.signum(dz));
                    if ((Math.abs(dx) == 2 || Math.abs(dz) == 2) && (!Paths.passable(mc.level, mid) || !Paths.passable(mc.level, mid.above()))) continue;
                    double dist = c.distSqr(feet);
                    if (dist < bestDist) {
                        best = c;
                        bestDist = dist;
                    }
                }
            }
        }
        if (best == null) return false;
        steering = walk(player, Vec3.atBottomCenterOf(best), best.getY() > feet.getY() && player.onGround(), false);
        status = "Stepping back from lava";
        return true;
    }

    /**
     * Caught in lava anyway: a step out onto the nearest spot beside you with nothing burning in it,
     * before anything else (stopping there would only keep you in it). @return true while it's at it
     */
    private boolean escapeLava(LocalPlayer player) {
        BlockPos feet = player.blockPosition();
        if (!player.isInLava() && !mc.level.getFluidState(feet).is(FluidTags.LAVA) && !mc.level.getFluidState(feet.above()).is(FluidTags.LAVA)) return false;
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos c = feet.offset(dx, dy, dz);
                    if (c.equals(feet) || !Paths.passable(mc.level, c) || !Paths.passable(mc.level, c.above())) continue;
                    if (mc.level.getBlockState(c.below()).getCollisionShape(mc.level, c.below()).isEmpty()) continue;
                    double dist = c.distSqr(feet);
                    if (dist < bestDist) {
                        best = c;
                        bestDist = dist;
                    }
                }
            }
        }
        if (best == null) return false;
        fill = null;
        fillPath = null;
        status = "Getting out of the lava";
        steering = walk(player, Vec3.atBottomCenterOf(best), best.getY() > feet.getY() && player.onGround(), false);
        return true;
    }

    /** Lava or water still running near you, that's being filled: it drains away in a few seconds once its sources are. */
    private boolean draining(LocalPlayer player) {
        BlockPos feet = player.blockPosition();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -8; dx <= 8; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -8; dz <= 8; dz++) {
                    FluidState fluid = mc.level.getFluidState(m.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz));
                    if (fluid.isEmpty() || fluid.isSource()) continue;
                    if (fillLava && fluid.is(FluidTags.LAVA) || fillWater && fluid.is(FluidTags.WATER)) return true;
                }
            }
        }
        return false;
    }

    /**
     * How to put a block into {@code cell} from {@code eye}: against the nearest plain full block
     * beside it whose face toward it you can see (the crosshair goes through lava and water), or null.
     */
    private Fill placement(Vec3 eye, double reach, BlockPos cell) {
        Fill best = null;
        for (Direction d : Direction.values()) {
            BlockPos against = cell.relative(d);
            BlockState state = mc.level.getBlockState(against);
            if (state.hasBlockEntity() || !state.isCollisionShapeFullBlock(mc.level, against)) continue;
            Direction face = d.getOpposite();
            // The nearest part of the face (off its edges): more reach than its middle.
            Vec3 mid = Vec3.atCenterOf(cell).add(d.getStepX() * 0.5, d.getStepY() * 0.5, d.getStepZ() * 0.5);
            Vec3 point = new Vec3(
                d.getAxis() == Direction.Axis.X ? mid.x : Math.clamp(eye.x, mid.x - 0.25, mid.x + 0.25),
                d.getAxis() == Direction.Axis.Y ? mid.y : Math.clamp(eye.y, mid.y - 0.25, mid.y + 0.25),
                d.getAxis() == Direction.Axis.Z ? mid.z : Math.clamp(eye.z, mid.z - 0.25, mid.z + 0.25));
            Vec3 toEye = eye.subtract(point);
            if (toEye.x * face.getStepX() + toEye.y * face.getStepY() + toEye.z * face.getStepZ() <= 0.05) continue;
            double dist = toEye.length();
            // Seen edge-on, the crosshair slips onto the block beside it.
            double square = (toEye.x * face.getStepX() + toEye.y * face.getStepY() + toEye.z * face.getStepZ()) / dist;
            double cost = dist + 2 * (1 - square);
            if (dist > reach || square < 0.1 || best != null && cost >= best.cost()) continue;
            Vec3 inside = point.add(d.getStepX() * 0.05, d.getStepY() * 0.05, d.getStepZ() * 0.05);
            BlockHitResult hit = mc.level.clip(new ClipContext(eye, inside, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(against) || hit.getDirection() != face) continue;
            best = new Fill(cell.immutable(), against.immutable(), face, point, cost);
        }
        return best;
    }

    /**
     * Lava or water in or by the area that it can't fill from where it stands: a walk over to the
     * nearest spot it can, safe to stand on (never next to lava), before it digs on. Looked for
     * once a second at most. @return true while it's walking
     */
    private boolean walkToFill(LocalPlayer player, long now) {
        if (fillPath == null) {
            if (now < nextFillLook) return false;
            nextFillLook = now + 1_000_000_000L;
            if (!findFillSpot(player)) return false;
            // Filling comes before digging on: what it's at is dropped and chosen afresh after.
            if (phase != Phase.CHOOSE) resetPhase();
            fillPathAt = 1;
            fillWalkSince = now;
            status = "Going over to fill " + (mc.level.getFluidState(fillGoal).is(FluidTags.LAVA) ? "lava" : "water") + " at " + coords(fillGoal);
        }
        steering = Input.EMPTY;
        // Gone already, or there and still not in reach: something changed. Something else first.
        if (mc.level.getFluidState(fillGoal).isEmpty() || fillPathAt >= fillPath.size() || now - fillWalkSince > FILL_WALK) {
            if (!mc.level.getFluidState(fillGoal).isEmpty()) fillPassed.put(fillGoal.asLong(), now + FILL_PASS);
            fillPath = null;
            return false;
        }
        BlockPos node = fillPath.get(fillPathAt);
        Vec3 target = Vec3.atBottomCenterOf(node);
        double horizontal = Math.hypot(target.x - player.getX(), target.z - player.getZ());
        boolean up = node.getY() > player.getY() + 0.5;
        if (horizontal < 0.3 && (!up || player.getY() > node.getY() - 0.2)) {
            fillPathAt++;
            return true;
        }
        aimAt(Vec3.atCenterOf(fillGoal));
        steering = walk(player, target, player.onGround() && up && horizontal < 1.4, false);
        return true;
    }

    /** Where to walk to fill lava or water from: sets {@link #fillPath} and {@link #fillGoal}. @return false if there's nowhere */
    private boolean findFillSpot(LocalPlayer player) {
        double reach = player.blockInteractionRange() - 0.25;
        int gap = lavaGap.get().intValue();
        Region near = plan.target.bounds.grow(gap, gap);
        BlockPos feet = player.blockPosition();
        long now = System.nanoTime();
        List<BlockPos> sources = new ArrayList<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = Math.max(near.minX(), feet.getX() - 24); x <= Math.min(near.maxX(), feet.getX() + 24); x++) {
            for (int y = Math.max(near.minY(), feet.getY() - 12); y <= Math.min(near.maxY(), feet.getY() + 12); y++) {
                for (int z = Math.max(near.minZ(), feet.getZ() - 24); z <= Math.min(near.maxZ(), feet.getZ() + 24); z++) {
                    FluidState fluid = mc.level.getFluidState(m.set(x, y, z));
                    if (fluid.isEmpty()) continue;
                    if (fluid.is(FluidTags.LAVA) ? !fillLava : !(fillWater && fluid.is(FluidTags.WATER))) continue;
                    // Sources, and where one runs out (shutting that off drains what ran).
                    if (!fluid.isSource() && !besideSource(m, fluid.is(FluidTags.LAVA))) continue;
                    long key = m.asLong();
                    if (stubborn.contains(key) || fillPassed.get(key) > now || !mc.level.getBlockState(m).canBeReplaced()) continue;
                    sources.add(m.immutable());
                }
            }
        }
        if (sources.isEmpty()) return false;
        sources.sort(java.util.Comparator.comparingDouble(q -> q.distSqr(feet)));
        if (sources.size() > 24) sources = sources.subList(0, 24);

        Region around = near.grow(6, 4).union(new Region(feet.getX() - 6, feet.getY() - 6, feet.getZ() - 6, feet.getX() + 6, feet.getY() + 6, feet.getZ() + 6));
        Paths ways = Paths.search(mc.level, feet, around, plan.target, 6_000);
        double eye = eyeHeight();
        BlockPos bestSpot = null;
        BlockPos bestGoal = null;
        double bestCost = Double.MAX_VALUE;
        for (long packed : ways.spots()) {
            BlockPos spot = BlockPos.of(packed);
            double walkCost = ways.distance(spot);
            if (walkCost >= bestCost) continue;
            Vec3 eyeAt = new Vec3(spot.getX() + 0.5, spot.getY() + eye, spot.getZ() + 0.5);
            for (BlockPos source : sources) {
                if (Vec3.atCenterOf(source).distanceToSqr(eyeAt) > (reach + 0.9) * (reach + 0.9)) continue;
                Fill f = placement(eyeAt, reach - 0.2, source);
                if (f == null || walkCost + f.cost() >= bestCost) continue;
                bestSpot = spot;
                bestGoal = source;
                bestCost = walkCost + f.cost();
            }
        }
        if (bestSpot == null || bestSpot.equals(feet)) return false;
        fillPath = ways.pathTo(bestSpot);
        fillGoal = bestGoal;
        return fillPath != null && fillPath.size() > 1;
    }

    /** Filled too often and still coming back: left alone, dug round like before. */
    private void giveUpFilling(BlockPos cell) {
        stubborn.add(cell.asLong());
        lavaNear.clear();
        evals.clear();
        Notify.send("Area Miner", "Lava or water keeps coming back at " + coords(cell) + ", digging round it", false, false, false);
    }

    /** Filler blocks in the whole inventory, the drill's slot aside. */
    private int fillerCount(LocalPlayer player) {
        Inventory inventory = player.getInventory();
        int n = 0;
        for (int i = 0; i < 36; i++) {
            if (i != drillSlot && filler(inventory.getItem(i))) n += inventory.getItem(i).getCount();
        }
        return n;
    }

    /**
     * Lava or water to fill and no blocks to fill it with: a stack of sand off /ah ({@link
     * AuctionBuyer}), the cheapest under your price. A go that comes to nothing isn't tried again
     * for five minutes, and lava's dug round meanwhile. @return true while it's buying
     */
    private boolean buySand(LocalPlayer player) {
        if (!buySand.get() || System.currentTimeMillis() < noBuyUntil) return false;
        if (player.getInventory().getFreeSlot() < 0) {
            Notify.send("Area Miner", "Not buying sand: no room for it. Digging round lava instead", false, false, false);
            noBuyUntil = System.currentTimeMillis() + BUY_RETRY;
            return false;
        }
        buyer.start(Items.SAND, SAND_STACK, sandMax.get() / SAND_STACK, sandMax.get(), ahConfirm.get(), true);
        resetPhase();
        status = "Buying sand off /ah to fill lava with";
        return true;
    }

    private void tickBuy() {
        if (buyer.tick() == AuctionBuyer.Result.RUNNING) return;
        String what = AuctionBuyer.name(Items.SAND);
        if (buyer.got() > 0) {
            Notify.send("Area Miner", "Bought " + buyer.got() + " " + what + " for " + SchematicBuilder.dollars(buyer.spent()) + " to fill lava with",
                false, false, false);
        } else {
            double cheapest = buyer.cheapestEach();
            String why = buyer.full() ? "no room for it" : !Double.isNaN(cheapest)
                ? "cheapest is " + SchematicBuilder.dollars(cheapest * SAND_STACK) + " a stack, your max is " + SchematicBuilder.dollars(sandMax.get())
                : buyer.why().isEmpty() || buyer.why().startsWith("no ") ? "none listed" : buyer.why();
            Notify.send("Area Miner", "Not buying " + what + ": " + why + ". Digging round lava instead", false, false, false);
            noBuyUntil = System.currentTimeMillis() + BUY_RETRY;
        }
        resetPhase();
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

        // Lava or water just filled still runs in what's dug out for a few seconds, and where it
        // runs is no place to stand yet: wait for it to drain before calling anything out of reach.
        if ((drainSince == 0 || now - drainSince < DRAIN_WAIT) && (fillLava || fillWater) && draining(player)) {
            if (drainSince == 0) drainSince = now;
            status = "Waiting for lava and water to drain";
            return;
        }

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

    // ---- Building back up after a fall

    /**
     * Watches for falls the route didn't plan: landing two or more blocks below where you last
     * stood, anywhere but a spot on the path being walked (a planned drop is at most three
     * blocks, onto the path). After one it builds back up to that floor before going on, so a
     * cave opening under the drill can't strand it, or leave the layer above half dug.
     * @return true if it paused instead.
     */
    private boolean trackFall(LocalPlayer player, long now) {
        if (!player.onGround()) {
            airborne = true;
            return false;
        }
        int y = player.getBlockY();
        int from = groundY;
        boolean landed = airborne;
        airborne = false;
        groundY = y;
        if (!landed || from == Integer.MIN_VALUE || from - y < 2) return false;
        if (from - y > 3) landedAt = now;
        if (phase == Phase.CLIMB || plannedLanding(player.blockPosition())) return false;
        if (!buildUp.get()) {
            pause("Fell " + (from - y) + " blocks: get back up to Y " + from + ", then Resume");
            return true;
        }
        climbTo = Math.max(climbTo, from);
        climbFails = 0;
        status = "Fell " + (from - y) + " blocks: getting back up";
        resetPhase();
        return false;
    }

    /** Landed where the route meant to drop to: a spot on the path being walked. */
    private boolean plannedLanding(BlockPos feet) {
        if (phase != Phase.WALK || path == null) return false;
        for (int i = Math.max(0, pathIndex - 2); i < path.size(); i++) {
            BlockPos p = path.get(i);
            if (p.getY() == feet.getY() && Math.abs(p.getX() - feet.getX()) <= 1 && Math.abs(p.getZ() - feet.getZ()) <= 1) return true;
        }
        return false;
    }

    /** Picks where to build up from, walking back instead if there's a way, and gets the blocks into the hotbar. */
    private void startClimb(LocalPlayer player, long now) {
        if (player.isInWater()) {
            pause("Fell into water: get out, then Resume");
            return;
        }
        BlockPos feet = player.blockPosition();
        Region around = new Region(feet.getX() - 10, feet.getY() - 3, feet.getZ() - 10, feet.getX() + 10, climbTo + 2, feet.getZ() + 10);
        Paths reach = Paths.search(mc.level, feet, around, plan.target, 6_000);
        BlockPos best = null;
        double bestCost = Double.MAX_VALUE;
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        for (long key : reach.spots()) {
            BlockPos spot = BlockPos.of(key);
            if (spot.getY() >= climbTo) {
                // There's a way to walk back up: no blocks needed, the usual route takes it from here.
                climbTo = Integer.MIN_VALUE;
                status = "Walking back up";
                return;
            }
            BlockPos floor = spot.below();
            if (!mc.level.getBlockState(floor).isCollisionShapeFullBlock(mc.level, floor)) continue;
            boolean clear = true;
            for (int y = spot.getY(); y <= climbTo + 1 && clear; y++) clear = Paths.clearAir(mc.level, q.set(spot.getX(), y, spot.getZ()));
            if (!clear) continue;
            // Every block built is a jump, so a higher start is worth a few steps.
            double cost = reach.distance(spot) + 4.0 * (climbTo - spot.getY());
            if (cost < bestCost) {
                bestCost = cost;
                best = spot;
            }
        }
        if (best == null) {
            pause("Fell to Y " + feet.getY() + " with no clear way to build up to Y " + climbTo + ": get out, then Resume");
            return;
        }

        int slot = fillerSlot(player);
        if (slot == FILLER_MOVING) return;
        if (slot == FILLER_NONE) {
            pause("Fell to Y " + feet.getY() + " and there are no blocks to build back up with: get out, then Resume");
            return;
        }
        if (slot == FILLER_NO_ROOM) {
            pause("No hotbar room for blocks to build back up with: free a slot, then Resume");
            return;
        }
        column = best;
        fillSlot = slot;
        path = reach.pathTo(best);
        pathIndex = 1;
        placeAim = null;
        placedThisJump = true;
        climbProgressAt = now;
        status = "Building back up to Y " + climbTo;
        enter(Phase.CLIMB);
    }

    private void tickClimb(LocalPlayer player, long now) {
        BlockPos feet = player.blockPosition();
        if (player.onGround() && feet.getY() >= climbTo) {
            climbTo = Integer.MIN_VALUE;
            column = null;
            status = "Back up";
            resetPhase();
            return;
        }
        if (now - climbProgressAt > CLIMB_STUCK) {
            if (++climbFails >= CLIMB_TRIES) {
                pause("Couldn't build back up to Y " + climbTo + ": get out, then Resume");
                return;
            }
            resetPhase();
            return;
        }
        Inventory inventory = player.getInventory();
        if (!filler(inventory.getItem(fillSlot))) {
            // That stack ran out: find the next.
            resetPhase();
            return;
        }

        if (feet.getX() != column.getX() || feet.getZ() != column.getZ()) {
            // Not on the spot (yet, or knocked off it): walk there.
            if (!player.onGround()) {
                steering = Input.EMPTY;
                return;
            }
            if (path == null || pathIndex >= path.size()) {
                steering = walk(player, Vec3.atBottomCenterOf(column.atY(feet.getY())), false, false);
                return;
            }
            BlockPos node = path.get(pathIndex);
            Vec3 target = Vec3.atBottomCenterOf(node);
            double horizontal = Math.hypot(target.x - player.getX(), target.z - player.getZ());
            boolean up = node.getY() > player.getY() + 0.5;
            if (horizontal < 0.3 && (!up || player.getY() > node.getY() - 0.2)) {
                pathIndex++;
                climbProgressAt = now;
                return;
            }
            aimAt(target.add(0, eyeHeight(), 0));
            steering = walk(player, target, player.onGround() && up && horizontal < 1.4, nearDrop(feet));
            return;
        }

        if (inventory.getSelectedSlot() != fillSlot) {
            inventory.setSelectedSlot(fillSlot);
            slotReadyAt = now + 120_000_000L;
        }
        if (player.onGround()) {
            jumpBase = feet.getY();
            if (placeAim == null || placedThisJump) {
                // Down at the block you're on, just ahead of its middle the way you already face, so
                // only your pitch moves (straight down, the way you face would swing about), and a
                // little to one side or the other like a hand would be.
                Vec3 ahead = Vec3.directionFromRotation(0, player.getYRot());
                double side = (random.nextDouble() - 0.5) * 0.2;
                placeAim = new Vec3(column.getX() + 0.5 + ahead.x * 0.2 - ahead.z * side, jumpBase,
                    column.getZ() + 0.5 + ahead.z * 0.2 + ahead.x * side);
                placedThisJump = false;
            }
            aimAt(placeAim);
            Vec3 middle = Vec3.atBottomCenterOf(feet);
            if (Math.hypot(middle.x - player.getX(), middle.z - player.getZ()) > 0.15) {
                // Onto the middle first, so the jump goes straight up.
                steering = walk(player, middle, false, false);
                return;
            }
            if (!Paths.clearAir(mc.level, feet.above(2))) {
                // Something overhead after all: somewhere else.
                climbFails++;
                resetPhase();
                return;
            }
            boolean ready = now >= slotReadyAt && aim.error() < 4;
            steering = new Input(false, false, false, false, ready, false, false);
            return;
        }

        // In the air: once your feet are above where the block goes, put it under you.
        steering = Input.EMPTY;
        aimAt(placeAim);
        if (placedThisJump || player.getY() < jumpBase + 1.05) return;
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
        if (!hit.getBlockPos().equals(column.atY(jumpBase - 1)) || hit.getDirection() != Direction.UP) return;
        if (mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit).consumesAction()) {
            player.swing(InteractionHand.MAIN_HAND);
            placedThisJump = true;
            built++;
            climbProgressAt = now;
            status = "Building back up: Y " + (jumpBase + 1) + " of " + climbTo;
        }
    }

    /**
     * A hotbar slot holding blocks to build up with, or why there isn't one. Blocks in the main
     * inventory are swapped into an empty slot (or one with no tool in it) first; never the drill's.
     */
    private int fillerSlot(LocalPlayer player) {
        return fillerSlot(player, false);
    }

    /** As {@link #fillerSlot(LocalPlayer)}; with {@code falling}, sand or gravel if there's any (it drops to the bottom of a pool). */
    private int fillerSlot(LocalPlayer player, boolean falling) {
        Inventory inventory = player.getInventory();
        if (falling) {
            boolean any = false;
            for (int i = 0; i < 36 && !any; i++) any = i != drillSlot && falls(inventory.getItem(i));
            falling = any;
        }
        int best = -1;
        for (int i = 0; i < 9; i++) {
            if (i == drillSlot || !filler(inventory.getItem(i)) || falling && !falls(inventory.getItem(i))) continue;
            if (best < 0 || inventory.getItem(i).getCount() > inventory.getItem(best).getCount()) best = i;
        }
        if (best >= 0) return best;

        int from = -1;
        for (int j = 9; j < 36; j++) {
            if (!filler(inventory.getItem(j)) || falling && !falls(inventory.getItem(j))) continue;
            if (from < 0 || inventory.getItem(j).getCount() > inventory.getItem(from).getCount()) from = j;
        }
        if (from < 0) return FILLER_NONE;
        int into = -1;
        for (int i = 0; i < 9 && into < 0; i++) {
            if (i != drillSlot && inventory.getItem(i).isEmpty()) into = i;
        }
        for (int i = 8; i >= 0 && into < 0; i--) {
            if (i != drillSlot && !inventory.getItem(i).isDamageableItem()) into = i;
        }
        if (into < 0) return FILLER_NO_ROOM;
        if (player.containerMenu != player.inventoryMenu) return FILLER_MOVING;
        mc.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, from, into, ClickType.SWAP, player);
        return FILLER_MOVING;
    }

    /** Plain full blocks nobody minds losing: stone and its kinds, cobble, netherrack, dirt, sand and gravel. */
    private static boolean filler(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem item)) return false;
        BlockState state = item.getBlock().defaultBlockState();
        boolean plain = state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.BASE_STONE_NETHER) || state.is(BlockTags.DIRT)
            || state.is(Blocks.COBBLESTONE) || state.is(Blocks.COBBLED_DEEPSLATE) || state.is(Blocks.MOSSY_COBBLESTONE)
            || state.is(Blocks.END_STONE) || state.is(Blocks.CALCITE) || state.is(Blocks.SAND) || state.is(Blocks.RED_SAND) || state.is(Blocks.GRAVEL);
        return plain && state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    /** Filler that drops when there's nothing under it: sand and gravel. */
    private static boolean falls(ItemStack stack) {
        return filler(stack) && ((BlockItem) stack.getItem()).getBlock() instanceof FallingBlock;
    }

    /** Blocks put down building back up after falls, this run. */
    public int built() {
        return built;
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
        // Hurt by a fall it's about to build back up from is no reason to stop, unless it left you low.
        boolean fallHurt = buildUp.get() && System.nanoTime() - landedAt < FALL_DAMAGE_WINDOW && health > 6;
        if (pauseHurt.get() && hurt && !fallHurt) return "You took damage";
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
                BlockPos q = p.relative(d);
                // Lava beside you, with lava being filled: plugged, or stepped back from, instead.
                if (mc.level.getFluidState(q).is(FluidTags.LAVA) && !fillLava) {
                    return "Lava right next to you at " + coords(q);
                }
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
        double y = plan.floor(current) + 0.06;
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
