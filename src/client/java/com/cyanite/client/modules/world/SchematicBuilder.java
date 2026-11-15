package com.cyanite.client.modules.world;

import com.cyanite.client.gui.Theme;
import com.cyanite.client.litematica.Litematica;
import com.cyanite.client.modules.donut.AuctionBuyer;
import com.cyanite.client.modules.donut.SellMenu;
import com.cyanite.client.modules.world.area.Paths;
import com.cyanite.client.modules.world.area.Region;
import com.cyanite.client.modules.world.build.Placing;
import com.cyanite.client.modules.world.build.Routes;
import com.cyanite.client.modules.world.build.StandIns;
import com.cyanite.client.system.setting.ModeSetting;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import com.cyanite.client.render.Render3D;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.Disconnects;
import com.cyanite.client.system.HumanAim;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.Notify;
import com.cyanite.client.system.Rejoin;
import com.cyanite.client.system.Resume;
import com.cyanite.client.system.Steer;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.BaseTorchBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RedstoneWallTorchBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import it.unimi.dsi.fastutil.longs.LongArrayList;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/**
 * Schematic Builder: builds the schematic you've placed with Litematica, the way a player would.
 *
 * <p>It drives your player, not the camera: Freecam flies about while it builds, and it carries
 * on while the game's tabbed out (GameRendererMixin keeps the pause menu away).
 *
 * <p>It works from the bottom up, nearest first, one layer at a time (Layer by layer): nothing on
 * a layer is started until the one below is done, short of spots it's tried three times and
 * given up on. For each block it finds somewhere to stand that
 * can see a face to place it against, walks there (stepping up, dropping down), turns to look at
 * that face like a hand on a mouse, crouches, and places it, facing the way that gets stairs,
 * logs, slabs, furnaces and the like turned right (see {@link Placing}). Blocks in the way that
 * shouldn't be there are mined out first with the best tool you have.
 *
 * <p>It gets about the way a player does on a tall build (see {@link Routes}): up on a pillar of
 * junk blocks (jumping and putting one under itself), across a gap on a bridge of them (crouched
 * at the edge, putting them out to walk onto), and once it's up on what it's built it stays up
 * there, walking along the top, corner to corner too, crouched where a slip would hurt. Down again
 * is a pillar mined out from under itself. Where to stand and how to get there is worked out as
 * one route, cheapest first, so it never climbs down just to put up another pillar beside the
 * first. The junk blocks come out again once it's all built, the way down last. With Use
 * scaffolding, a climb of two blocks or more goes up as scaffolding instead, under one junk block
 * to stand on: put up from its foot (into its own feet first, then on top of the column from inside
 * it) while it climbs it, and dug out from under its feet on the way down (it breaks at a touch),
 * what it drops coming down with it and picked up at the bottom. A stack's bought off /ah as junk
 * blocks are. Throwaway blocks to build a floating block against only go in when nothing beside
 * it that's still to be built would do as well.
 *
 * <p>Dropped off the server (Rejoin after disconnect, see {@link Rejoin}): a reset connection or a
 * restart, it rejoins, and once it's back where it left with the world loaded round it and the
 * schematic where it was, it carries on. A dead session, a ban or a kick from staff stops it.
 *
 * <p>The blocks it needs are moved into your hotbar as it goes. What you haven't got, it buys off
 * the auction house with /ah first, under a price cap per item and a budget (see
 * {@link AuctionBuyer}): with Layer by layer, only what the layer it's on needs (Buy per layer).
 * When a layer's done, what it bought that the next won't use goes back with /sell, and at the
 * end all it bought that's left (Sell leftovers, see {@link SellMenu}). With that on it buys
 * whole stacks, rounding up (148 is three stacks), and the extra is what goes back. It never buys
 * more than fits with a slot to spare (bought into a full inventory, it'd land on the ground, paid
 * for all the same): a layer too big for your inventory gets a fair share of each block, and the
 * rest is bought once it's down to its last stack. Short of room, what it bought and won't need on
 * this layer goes back with /sell right then, not just at the end of it. Only what it bought this
 * time is ever sold, never below what you had of your own, and a stack of junk blocks is kept
 * while there's building left; down to its last few, it buys a stack more (dirt, or whatever junk
 * the build doesn't use). What costs more than the cap (or isn't listed at all) gets a stand-in
 * instead, cobblestone or whatever's picked, and the spot is marked so you know to come back to
 * it ({@link StandIns}); spots where a full block would spoil things (torches, panes, redstone)
 * are left empty and marked. Built again later with the real block to hand, a stand-in is
 * swapped out for it.
 *
 * <p>Water and lava go in by the bucket, against a block beside the spot, and only once they
 * can't run off: every way they'd flow is shut (or is meant to have them in it), and nothing near
 * is still to be built, so lava never sets fire to leaves still waiting for their water. Blocks
 * the schematic has waterlogged get a bucket of water on them once they're in. Buckets of water
 * and lava are bought off /ah like the blocks, so it never goes back down for water, and the empty
 * buckets they leave go back with /sell with the rest of the leftovers (never ones you had). Only
 * with buying off, or none to be had, are empty buckets filled at water nearby that fills itself
 * back up (two or more still water blocks side by side, outside the build).
 * What placing can't set is clicked: a repeater's delay, a comparator's mode, a gate, door or
 * trapdoor left open, a note block's note. Some things wait their turn so building doesn't set
 * the machine off: an observer goes in after what it watches, a piston only with no power by it,
 * and TNT and pulled levers last of all (TNT never where it'd be lit).
 */
public class SchematicBuilder extends Module implements Rejoin.Client {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final int PATH_BUDGET = 40_000;
    /** Eye height while crouched, which is how it places; and standing, which is how it clicks and soaks. */
    private static final double EYE = 1.27;
    private static final double STAND_EYE = 1.62;
    private static final int SCAN_EVERY = 6;
    private static final long PLACE_TIMEOUT = 3500;
    private static final long BREAK_TIMEOUT = 9000;
    private static final long BRIDGE_TIMEOUT = 5000;
    private static final long WALK_STUCK = 5000;
    private static final int ROUTE_COLOR = 0xFF4DD4FF;
    /** Reach held back when picking somewhere to stand, so where you really end up still reaches: to place, and to break. */
    private static final double PLAN_SLACK = 0.45;
    private static final double BREAK_SLACK = 0.2;
    /** Throwaway blocks for pillars. */
    /** Scaffolding to hand for ladders: under this many, a stack more's bought. */
    private static final int LADDER_LOW = 16;
    /**
     * The lowest pillar that goes up as a ladder: a shorter one's quicker jumped up block by block
     * (the first scaffolding, looking down at your feet, and the climb out onto the top cost more
     * than climbing saves).
     */
    private static final int LADDER_MIN = 4;
    private static final int LADDER_STACK = 64;
    private static final Set<Item> SCAFFOLD = Set.of(Items.DIRT, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.NETHERRACK,
        Items.STONE, Items.DEEPSLATE, Items.ANDESITE, Items.DIORITE, Items.GRANITE, Items.TUFF, Items.BLACKSTONE, Items.OAK_PLANKS,
        Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS);

    private final SliderSetting speed = add(new SliderSetting("Speed", 4, 1, 10, 0.5)
        .format(v -> String.format(Locale.ROOT, "%.1f blocks/s", v)));
    private final SliderSetting turnSpeed = add(new SliderSetting("Turn speed", 1.2, 0.3, 3, 0.1)
        .format(v -> String.format(Locale.ROOT, "%.1fx", v)));
    private final SliderSetting randomness = add(new SliderSetting("Randomness", 0.5, 0, 1, 0.05).percent());
    private final SliderSetting reach = add(new SliderSetting("Reach", 4.2, 3, 4.5, 0.1)
        .format(v -> String.format(Locale.ROOT, "%.1f", v)));
    /** One layer finished before the next is started, the way you'd lay courses of bricks. */
    private final BoolSetting layered = add(new BoolSetting("Layer by layer", true));
    private final BoolSetting breakWrong = add(new BoolSetting("Break wrong blocks", true));
    private final BoolSetting buildUp = add(new BoolSetting("Build up", true));
    /** Across gaps on a bridge of junk blocks, crouched at the edge, rather than only walking round. */
    private final BoolSetting bridge = add(new BoolSetting("Bridge", true));
    /**
     * Pillars of scaffolding (bought off /ah, a stack at a time) with one junk block on top to
     * stand on: quicker up, sunk down through in a moment, and taken down in one go from the foot.
     */
    private final BoolSetting useScaffolding = add(new BoolSetting("Use scaffolding", false));
    private final BoolSetting buy = add(new BoolSetting("Buy from /ah", true));
    /** Off to match DonutSMP's own "auction buy confirm" setting being off: a click on a listing buys it. */
    private final BoolSetting confirmScreen = add(new BoolSetting("AH confirm screen", false));
    private final SliderSetting maxEach = add(new SliderSetting("Max price each", 50, 1, 10_000_000, 1).logarithmic().format(SchematicBuilder::dollars));
    private final SliderSetting budget = add(new SliderSetting("Budget", 250_000, 1_000, 100_000_000_000.0, 1).logarithmic().format(SchematicBuilder::dollars));
    /** With Layer by layer: buys only what the layer it's on needs, so not much is left over. */
    private final BoolSetting buyPerLayer = add(new BoolSetting("Buy per layer", true));
    /**
     * Sells back with /sell what it bought and won't need: after each layer, and the rest at the
     * end. With it on, buying's in whole stacks (148 is three), since what's over goes back.
     */
    private final BoolSetting sellLeftovers = add(new BoolSetting("Sell leftovers", true));
    private final BoolSetting standIns = add(new BoolSetting("Stand-ins", true));
    private final ModeSetting standIn = add(new ModeSetting("Stand-in block", "Cobblestone",
        List.of("Cobblestone", "Stone", "Cobbled Deepslate", "Dirt", "Netherrack", "Oak Planks", "Whatever you have")));
    private final BoolSetting showStandIns = add(new BoolSetting("Show stand-ins", true));
    private final BoolSetting showPlan = add(new BoolSetting("Show plan", true));
    /** A dropped connection or a restart doesn't end the build: back on, and on with it from where it left. */
    private final Resume rejoin = new Resume(this);

    /** For tests and other modules: a plan to build instead of Litematica's selection. */
    private static Map<BlockPos, BlockState> given;
    private static String givenName;

    private final HumanAim aim = new HumanAim();
    private final Random random = new Random();
    private final AuctionBuyer buyer = new AuctionBuyer();
    private final SellMenu seller = new SellMenu();

    private LocalPlayer owner;
    private Map<BlockPos, BlockState> plan = Map.of();
    private String planName = "";
    /** The Litematica placement being built and where it was put, to tell after a rejoin that it hasn't been moved; null for a given plan. */
    private String builtKey;
    private BlockPos builtOrigin;
    private Region bounds;
    private List<BlockPos> pending = new ArrayList<>();
    private int skippedCount;
    /** Goes back round what it gave up on, from the bottom, this many times before stopping. */
    private int sweeps;
    /**
     * Built as far as it goes and its junk out as far as it'll come: what's left in is in for
     * good, so one last round of what waited on junk beside it coming out ({@link #waitedOnJunk}).
     */
    private boolean lastGo;
    private final Set<BlockPos> waitedOnJunk = new HashSet<>();
    /** How to place each block at each spot (the planned one, or a stand-in), worked out once. */
    private final Map<BlockPos, Map<BlockState, List<Placing.Way>>> ways = new HashMap<>();
    /** Stand-ins put in this time, and what they're in for. */
    private final Map<BlockPos, Block> stood = new HashMap<>();
    /** Spots given up on and left empty, marked. */
    private final Set<BlockPos> leftEmpty = new HashSet<>();
    private final Map<BlockPos, Long> passed = new HashMap<>();
    /** How many times each spot's been set aside: three, and it's given up on. */
    private final Map<BlockPos, Integer> tries = new HashMap<>();
    private final Set<Item> buyTried = new HashSet<>();
    /** Tried and none to be had: not tried again on the next layer. */
    private final Set<Item> cantBuy = new HashSet<>();
    /** Short of something there's no room to buy all of; null if not. Set by {@link #shortage}. */
    private Item cramped;
    /** When it may next /sell to make room. */
    private long roomSellAt;
    /** Stopped buying these for want of room, and how many slots were free then: tried again once more are. */
    private final Map<Item, Integer> noRoom = new HashMap<>();
    /** Told you there's no room for these. */
    private final Set<Item> toldFull = new HashSet<>();
    /** What it's bought this time, and sold back. */
    private final Map<Item, Integer> bought = new HashMap<>();
    private final Map<Item, Integer> soldBack = new HashMap<>();
    /** How many you had of your own when it first bought some: never sold. */
    private final Map<Item, Integer> own = new HashMap<>();
    /** Put up for sale and none taken: /sell doesn't buy it. */
    private final Set<Item> unsellable = new HashSet<>();
    private boolean soldAtEnd;
    private double earned;
    /** What the buyer's after right now. */
    private Item buyingItem = Items.AIR;
    /** How many it set out to buy: all of it got, and it can buy more later (it was only short of room). */
    private int buyingWant;
    private final LinkedHashSet<BlockPos> scaffold = new LinkedHashSet<>();
    /**
     * The scaffolding among them (Use scaffolding): ladders, a column under each pillar's top
     * block. Never stood on (crouched there, you'd sink); sunk down through, and taken down from
     * the foot, everything over it coming down with it.
     */
    private final Set<BlockPos> ladder = new HashSet<>();
    /** The pillar going up is a ladder (its scaffolding goes in first, then it climbs it), and when it last clicked. */
    private boolean pillarLadder;
    private long ladderClickAt;
    /** Picking up what a ladder taken down dropped, till then. */
    private long dropsUntil;
    /**
     * Throwaway blocks put in to build a floating block against (nothing beside it to place it
     * on), taken out again once the layer they were for is done; and ones it couldn't get to.
     */
    private final LinkedHashSet<BlockPos> props = new LinkedHashSet<>();
    /**
     * What each junk block or prop still in was put in for, until it's stood on or built against:
     * the ones that go again untouched were wasted, and the debug log counts them by reason.
     */
    private final Map<BlockPos, String> junkWhy = new HashMap<>();
    private final Map<String, Integer> junkWasted = new TreeMap<>();
    private final Map<BlockPos, String> propWhy = new HashMap<>();
    private String pillarWhy = "pillar";
    private int junkPut;
    private final Set<BlockPos> stuckProps = new HashSet<>();
    /** Junk blocks and props it went to break and couldn't, and how often: twice and they're left for now. */
    private final Map<BlockPos, Integer> breakFails = new HashMap<>();
    /** Why each junk block or prop being left in is left: out of reach, holding something up, or redstone it'd set off. */
    private final Map<BlockPos, String> leftWhy = new HashMap<>();
    private static final String OUT_OF_REACH = "out of reach";
    private static final String HOLDS_UP = "holding something up";
    private static final String REDSTONE = "taking it out would set off the redstone";
    /**
     * Clearing up has had its fresh look round from up top (and since the last of it went in: it
     * may start before, round what it gave up on, from somewhere it can't see much); and it's just
     * come down the pillar it left by.
     */
    private boolean lookedAgain;
    private boolean lookedBuilt;
    private boolean cameDown;
    private boolean clearingProps;
    private int placedCount;
    private int brokenCount;
    private double spent;
    private int scanIn;
    private long startedAt;
    private long nextActAt;

    private enum Phase { IDLE, WALK, PLACE, BREAK, PILLAR, BRIDGE, DESCEND, BUY, SELL, USE }

    /**
     * PLACE a block, BREAK one in the way, PROP a floating one with a throwaway block; POUR water
     * or lava from a bucket, SOAK a block with a bucket of water, CLICK a block to set it, FILL
     * empty buckets at a pool; DOWN: walk onto the top of a pillar it put up and mine it down;
     * SCOOP up the water it's standing in, shut in at the bottom of a hole, to climb out.
     */
    private enum Kind { PLACE, BREAK, PROP, POUR, SOAK, CLICK, FILL, DOWN, SCOOP }

    /** Water that fills itself back up when a bucket's taken, found when it's wanted; and pools that couldn't be got to. */
    private BlockPos pool;
    private long poolLookedAt;
    private long poolSeenAt;
    private final Map<BlockPos, Long> badPools = new HashMap<>();
    /** Between fills, the pool filling back in; between clicks, the server catching up. */
    private long useAfter;

    /** Something to do: place or break {@code target}, standing at {@code stand}; {@code cost} is what picked it. */
    private record Job(Kind kind, BlockPos target, Placing.Way way, BlockPos stand, double cost) {
    }

    /** The layer being built (Layer by layer): the lowest with anything left that hasn't been given up on. */
    private int layer = Integer.MIN_VALUE;
    private Phase phase = Phase.IDLE;
    private long phaseSince;
    private Job job;
    private List<BlockPos> path;
    /** How each spot of the path is got to: walked to, bridged to, pillared up to, dug down to. */
    private List<Routes.Move> moves;
    private int pathIndex;
    /** The pillar going up: the spot it's on, how high it's to go, and from what height. */
    private BlockPos pillarAt;
    private int pillarHeight;
    private int pillarBase;
    /** Pillar blocks placed so far, and when the last went in (to notice the server taking it back). */
    private int pillarPlaced;
    private long pillarPlacedAt;
    /**
     * Climbing up out of somewhere it can't walk back into, for good, clearing up: each block of
     * the pillar under the one it stands on comes out as it goes (leaning out over the edge to see
     * its side), so only the top one's left, for taking out from what it climbs out onto. And
     * which way it leans for the one it's at now.
     */
    private boolean pillarClear;
    private Direction pillarLean;
    /** A pillar put up or mined down on the way somewhere: on along the route after. */
    private boolean resumeRoute;
    /** How low a pillar's mined on a route (feet height) and how many blocks; MIN_VALUE: all the way down. */
    private int digTo = Integer.MIN_VALUE;
    private int digCount;
    /** Where it started: somewhere you can walk out to, so the pillar it comes down at the end is one you can leave from the foot of. */
    private BlockPos home;
    /** When it got to the end of the route, and began settling onto the middle of the spot. */
    private long arrivedAt;
    private boolean aimed;
    /** The block it's mining this tick, its tool in hand; null if none. Attack is held on it through the game's own path (see {@link #shouldAttack}). */
    private BlockPos digAt;
    private Input steering = Input.EMPTY;
    private boolean lookingAhead;
    private long lastMoveAt;
    /** Getting nearer the spot it's walking to next: which one, the nearest it's been to it, and when it last got nearer. */
    private int nodeFor = -1;
    private double nodeBest;
    private long nodeProgressAt;
    private long lastJumpAt;

    public enum State { IDLE, RUNNING, PAUSED }

    /** Paused from the tab: it stands still until resumed (a purchase or a sale under way finishes first). */
    private boolean paused;
    /** Time spent actually running, for the rate. */
    private long runMillis;
    private long lastTick;
    /** How the last run ended, until a new one starts; and why the last start didn't. */
    private String report;
    private String startError;
    /** The placement picked in the tab: built rather than Litematica's selected one. */
    private String chosenKey;
    private BlockPos chosenOrigin;
    /** What the tab's material list shows, worked out at most every second or so. */
    private List<Material> materials = List.of();
    private long materialsAt;
    private String materialsFor = "";

    /** One line of the material list: how many it takes, and how many you've got on you. */
    public record Material(Item item, int need, int have) {
    }

    public SchematicBuilder() {
        super(Category.WORLD, "Schematic Builder", "Builds your Litematica schematic like a player, lava, water and redstone too, buying each layer's blocks off /ah and selling leftovers");
        showStandIns.onChange(() -> StandIns.setShown(showStandIns.get()));
    }

    static String dollars(double v) {
        if (v >= 1e9) return String.format(Locale.ROOT, "$%sB", trim(v / 1e9));
        if (v >= 1e6) return String.format(Locale.ROOT, "$%sM", trim(v / 1e6));
        if (v >= 1e4) return String.format(Locale.ROOT, "$%sK", trim(v / 1e3));
        return String.format(Locale.ROOT, "$%,d", Math.round(v));
    }

    /** 2.5, 12, 120: up to two decimals, none if they'd be zeros. */
    private static String trim(double v) {
        String s = String.format(Locale.ROOT, "%.2f", v);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    /** Builds this instead of Litematica's selection the next time it's switched on. */
    public static void give(Map<BlockPos, BlockState> plan, String name) {
        given = plan;
        givenName = name;
    }

    // ---- What the tab uses

    public State state() {
        return !isEnabled() ? State.IDLE : paused ? State.PAUSED : State.RUNNING;
    }

    /** Starts, or resumes when paused. @return why it couldn't, or null. */
    public String start() {
        if (isEnabled()) {
            resume();
            return null;
        }
        startError = null;
        setEnabled(true);
        return startError;
    }

    public void pause() {
        if (!isEnabled() || paused) return;
        paused = true;
        steering = Input.EMPTY;
        aim.idle();
    }

    public void resume() {
        if (!isEnabled() || !paused) return;
        paused = false;
        job = null;
        resumeWalk = null;
        clearBehind = false;
        nextActAt = 0;
        lastTick = System.currentTimeMillis();
        if (phase != Phase.BUY && phase != Phase.SELL) go(Phase.IDLE);
    }

    public void stop() {
        if (!isEnabled()) return;
        report = "Stopped. " + summary();
        setEnabled(false);
    }

    /** How the last run ended, until a new one starts. */
    public String report() {
        return report;
    }

    /**
     * Whether to hold attack, the way a player holds left click to mine: the crosshair's on the
     * block it's mining. The game's own held attack does the mining (MinecraftMixin), as for Auto
     * Mine; mined from here instead, the game let go of it every tick and it started over.
     */
    public boolean shouldAttack() {
        return isEnabled() && digAt != null && mc.screen == null && mc.hitResult instanceof BlockHitResult hit
            && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(digAt);
    }

    /** What it's doing right now, in a few words. */
    public String status() {
        if (!isEnabled()) return "";
        if (paused) return phase == Phase.BUY || phase == Phase.SELL ? "Pausing once this is done" : "Paused";
        if (Rejoin.waiting(this)) return "Getting back on the server";
        Job j = job;
        BlockState want = j == null ? null : plan.get(j.target());
        return switch (phase) {
            case BUY -> "Buying " + AuctionBuyer.name(buyingItem) + " on /ah";
            case SELL -> "Selling what's left over with /sell";
            case WALK -> j == null ? "Walking over" : j.kind() == Kind.FILL ? "Walking to water"
                : j.kind() == Kind.DOWN ? "Walking back to a pillar to climb down"
                : j.kind() == Kind.BREAK && want == null ? "Walking over to take out a throwaway block" : "Walking over";
            case PLACE -> j != null && j.kind() == Kind.PROP ? "Propping up a floating block" : "Placing " + name(want);
            case BREAK -> j == null ? "" : want == null ? "Taking out a throwaway block" : "Breaking " + name(mc.level.getBlockState(j.target())) + " in the way";
            case PILLAR -> pillarLadder ? "Putting up scaffolding" : "Pillaring up";
            case BRIDGE -> "Bridging across";
            case DESCEND -> "Coming back down";
            case USE -> j == null ? "" : switch (j.kind()) {
                case POUR -> want != null && want.getFluidState().is(net.minecraft.tags.FluidTags.LAVA) ? "Pouring lava" : "Pouring water";
                case SOAK -> "Waterlogging " + name(want);
                case CLICK -> "Setting " + name(want);
                case SCOOP -> "Scooping up the water it's stuck in";
                default -> "Filling buckets with water";
            };
            default -> pending.isEmpty() ? "Finishing up" : "Working out what's next";
        };
    }

    private static String name(BlockState state) {
        return state == null ? "" : state.getBlock().getName().getString();
    }

    public String planName() {
        return planName;
    }

    /** Blocks to build in all (not counting running water and the like), and how many are done. */
    public int total() {
        return plan.size() - skippedCount;
    }

    public int built() {
        return Math.max(0, total() - pending.size());
    }

    public double progress() {
        return total() == 0 ? 0 : built() / (double) total();
    }

    /** The layer it's on and how many there are, counted from 1; {0, 0} when it isn't going layer by layer. */
    public int[] layer() {
        if (!layered.get() || bounds == null || layer == Integer.MIN_VALUE) return new int[] {0, 0};
        return new int[] {layer - bounds.minY() + 1, bounds.sizeY()};
    }

    public double spent() {
        return spent;
    }

    public double earned() {
        return earned;
    }

    public int standIns() {
        return stood.size();
    }

    public int leftEmptyCount() {
        return leftEmpty.size();
    }

    public long elapsedMillis() {
        return runMillis;
    }

    /** Blocks a minute, over the time spent actually running. */
    public double rate() {
        double minutes = runMillis / 60_000.0;
        return minutes < 0.05 ? 0 : placedCount / minutes;
    }

    /** Milliseconds until done at the current rate, or -1 if there's no rate yet. */
    public long etaMillis() {
        double rate = rate();
        return rate <= 0 ? -1 : (long) (pending.size() / rate * 60_000);
    }

    /** Builds this placement from now on rather than Litematica's selected one. */
    public void choose(Litematica.Placement p) {
        chosenKey = p.key();
        chosenOrigin = p.origin();
        materialsAt = 0;
    }

    /**
     * The placement it'll build: the one picked in the tab while it's still there, otherwise the
     * one selected in Litematica, otherwise the only one showing. Null if there's none to go by.
     */
    public Litematica.Placement chosen() {
        Litematica.Placement selected = null;
        List<Litematica.Placement> enabled = new ArrayList<>();
        for (Litematica.Placement p : Litematica.placements()) {
            if (p.key().equals(chosenKey) && p.origin().equals(chosenOrigin)) return p;
            if (p.selected()) selected = p;
            if (p.enabled()) enabled.add(p);
        }
        if (selected != null) return selected;
        return enabled.size() == 1 ? enabled.getFirst() : null;
    }

    /**
     * What the build takes that isn't in yet, most first, with how many you've got: what's left
     * while it's running, otherwise for the placement it'd build. Water counts a bucket for every
     * block of it and every block soaked with it.
     */
    public List<Material> materials() {
        long now = System.currentTimeMillis();
        Litematica.Placement p = isEnabled() ? null : chosen();
        String key = isEnabled() ? "running" : p == null ? "" : p.key() + p.origin();
        if (now - materialsAt < (isEnabled() ? 1000 : 3000) && key.equals(materialsFor)) return materials;
        materialsAt = now;
        materialsFor = key;
        Map<Item, Integer> need = new HashMap<>();
        if (isEnabled()) {
            for (BlockPos pos : pending) count(need, pos, plan.get(pos));
        } else if (p != null && mc.level != null) {
            Map<BlockPos, BlockState> states = Litematica.states(p.key(), p.origin(), 400_000);
            if (states != null) for (Map.Entry<BlockPos, BlockState> e : states.entrySet()) count(need, e.getKey(), e.getValue());
        }
        List<Material> out = new ArrayList<>();
        need.forEach((item, n) -> out.add(new Material(item, n, mc.player == null ? 0 : count(item))));
        out.sort(Comparator.comparingInt(Material::need).reversed().thenComparing(m -> AuctionBuyer.name(m.item())));
        materials = out;
        return out;
    }

    /** Adds what a spot still takes to the tally: nothing if it's done, its water if it's only waiting on that. */
    private void count(Map<Item, Integer> need, BlockPos pos, BlockState want) {
        if (Placing.skipped(want)) return;
        BlockState have = mc.level.hasChunkAt(pos) ? mc.level.getBlockState(pos) : null;
        if (have != null && Placing.matches(have, want)) {
            if (Placing.needsWater(have, want)) need.merge(Items.WATER_BUCKET, 1, Integer::sum);
            return;
        }
        boolean twice = want.getBlock() instanceof SlabBlock && want.getValue(SlabBlock.TYPE) == net.minecraft.world.level.block.state.properties.SlabType.DOUBLE
            && (have == null || have.getBlock() != want.getBlock());
        need.merge(Placing.item(want), twice ? 2 : 1, Integer::sum);
        if (Placing.wantsWater(want)) need.merge(Items.WATER_BUCKET, 1, Integer::sum);
    }

    @Override
    public String getInfo() {
        if (paused) return "Paused";
        if (plan.isEmpty()) return null;
        int total = plan.size() - skippedCount;
        String done = planName + " " + (total - pending.size()) + "/" + total;
        if (!layered.get() || bounds == null || layer == Integer.MIN_VALUE) return done;
        return done + ", layer " + (layer - bounds.minY() + 1) + "/" + bounds.sizeY();
    }

    /** How the builder wants the player to move this tick, or null when it isn't driving. */
    public Input steering() {
        if (!isEnabled() || owner == null || owner != mc.player) return null;
        // Never crouched on top of scaffolding, which you'd sink through; but going down a ladder.
        Input s = steering;
        if (s.shift() && phase != Phase.DESCEND && onScaffolding(mc.player)) s = new Input(s.forward(), s.backward(), s.left(), s.right(), s.jump(), false, s.sprint());
        return s;
    }

    /** Standing on top of scaffolding (anyone's). */
    private boolean onScaffolding(LocalPlayer player) {
        return mc.level.getBlockState(BlockPos.containing(player.getX(), player.getY() - 0.05, player.getZ())).is(Blocks.SCAFFOLDING);
    }

    @Override
    protected void onEnable() {
        owner = inGame() ? mc.player : null;
        if (owner == null || !loadPlan()) {
            setEnabled(false);
            return;
        }
        // Only one thing drives the player at a time.
        for (Module other : List.of(Modules.get(AreaMiner.class), Modules.get(AutoMine.class), Modules.get(BedrockBurrow.class))) {
            if (other != null && other.isEnabled()) other.setEnabled(false);
        }
        pending = new ArrayList<>();
        ways.clear();
        propWays.clear();
        stuckSteps.clear();
        stood.clear();
        leftEmpty.clear();
        StandIns.setShown(showStandIns.get());
        passed.clear();
        tries.clear();
        sweeps = 0;
        lastGo = false;
        waitedOnJunk.clear();
        lastHealth = 0;
        buyTried.clear();
        cantBuy.clear();
        bought.clear();
        soldBack.clear();
        own.clear();
        noRoom.clear();
        toldFull.clear();
        cramped = null;
        roomSellAt = 0;
        unsellable.clear();
        soldAtEnd = false;
        earned = 0;
        pool = null;
        poolLookedAt = 0;
        badPools.clear();
        layer = Integer.MIN_VALUE;
        scaffold.clear();
        ladder.clear();
        pillarLadder = false;
        dropsUntil = 0;
        ladderDug = false;
        props.clear();
        junkWhy.clear();
        junkWasted.clear();
        propWhy.clear();
        junkPut = 0;
        stuckProps.clear();
        breakFails.clear();
        leftWhy.clear();
        lookedAgain = false;
        lookedBuilt = false;
        cameDown = false;
        clearBehind = false;
        resumeWalk = null;
        wayWaits = 0;
        onGroundForGood = false;
        clearingProps = false;
        cleaning = false;
        cleanupBlocks = 0;
        junkAtStart.clear();
        cleared.clear();
        path = null;
        moves = null;
        resumeRoute = false;
        digTo = Integer.MIN_VALUE;
        home = owner.blockPosition();
        rejoin.clear();
        placedCount = 0;
        brokenCount = 0;
        spent = 0;
        scanIn = 0;
        paused = false;
        report = null;
        runMillis = 0;
        lastTick = System.currentTimeMillis();
        startedAt = System.currentTimeMillis();
        nextActAt = 0;
        job = null;
        go(Phase.IDLE);
        phaseTime.clear();
        Notify.send("Schematic Builder", "Building " + planName + ": " + (plan.size() - skippedCount) + " blocks", false, false, false);
    }

    @Override
    protected void onDisable() {
        paused = false;
        buyer.cancel();
        seller.cancel();
        steering = Input.EMPTY;
        aim.clear();
        job = null;
        given = null;
    }

    private boolean cantStart(String why) {
        startError = why;
        Notify.send("Schematic Builder", why, true, false, false);
        return false;
    }

    private boolean loadPlan() {
        Map<BlockPos, BlockState> states = null;
        String name = "";
        builtKey = null;
        builtOrigin = null;
        if (given != null) {
            states = given;
            name = givenName;
        } else if (!Litematica.installed()) {
            return cantStart("Needs Litematica, to place the schematic where you want it built");
        } else {
            Litematica.Placement chosen = chosen();
            if (chosen == null) return cantStart("Pick a schematic in the Schematic Builder tab, or select one in Litematica");
            states = Litematica.states(chosen.key(), chosen.origin(), 400_000);
            name = chosen.name();
            if (states == null) return cantStart("Couldn't read " + chosen.name() + " (too big?)");
            builtKey = chosen.key();
            builtOrigin = chosen.origin();
        }
        if (states.isEmpty()) return cantStart("That schematic has no blocks in it");
        plan = new LinkedHashMap<>(states);
        planName = name;
        skippedCount = 0;
        Region r = null;
        for (Map.Entry<BlockPos, BlockState> e : plan.entrySet()) {
            if (Placing.skipped(e.getValue())) skippedCount++;
            Region one = Region.of(e.getKey(), e.getKey());
            r = r == null ? one : r.union(one);
        }
        bounds = r;
        List<BlockPos> up = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockState> e : plan.entrySet()) {
            if (e.getValue().getBlock() instanceof ObserverBlock && e.getValue().getValue(ObserverBlock.FACING) == Direction.UP) up.add(e.getKey());
        }
        upObservers = up;
        return true;
    }

    @Override
    public void onFrame() {
        if (mc.isPaused() || paused) {
            aim.idle();
            return;
        }
        aim.speed = turnSpeed.get();
        aim.randomness = randomness.get();
        aim.frame();
    }

    // ---- The loop

    @Override
    public void onTick() {
        // Set again below by whatever's mining this tick.
        digAt = null;
        LocalPlayer player = mc.player;
        if (player != owner && Rejoin.waiting(this)) {
            rejoined(player);
            return;
        }
        if (player != owner || player.isDeadOrDying()) {
            stop("You died or changed worlds");
            return;
        }
        rejoin.saw(player);
        hurt(player);
        if (mc.isPaused()) {
            steering = Input.EMPTY;
            return;
        }
        long now = System.currentTimeMillis();
        if (!paused) runMillis += Math.min(1000, now - lastTick);
        lastTick = now;
        if (phase == Phase.BUY) {
            buying(now);
            return;
        }
        if (phase == Phase.SELL) {
            selling(now);
            return;
        }
        if (paused) {
            steering = Input.EMPTY;
            return;
        }
        stoodOnJunk(player);
        // Fire where you stand (lit by lava near it, or spread from a fire close by): off it at
        // once, not after the job in hand. Where it goes next it can't be, standing in fire.
        if ((phase == Phase.PLACE || phase == Phase.BREAK || phase == Phase.USE)
            && (burning(player.blockPosition()) || burning(player.blockPosition().above()))) {
            LOGGER.debug("Schematic Builder: fire where it stands at {}, off it ({})", player.blockPosition(), job);
            go(Phase.IDLE);
        }
        if (--scanIn <= 0) scan();
        switch (phase) {
            case IDLE -> decide(player, now);
            case WALK -> walk(player, now);
            case PLACE -> place(player, now);
            case BREAK -> dig(player, now);
            case PILLAR -> pillar(player, now);
            case BRIDGE -> bridge(player, now);
            case DESCEND -> descend(player, now);
            case USE -> use(player, now);
            default -> {
            }
        }
    }

    /** Health last tick, and how far it had fallen by then: for the debug log when it's hurt. */
    private float lastHealth;
    private double lastFall;

    /** Says in the debug log when you're hurt, and how: a fall, a burn, or neither. */
    private void hurt(LocalPlayer player) {
        float health = player.getHealth();
        if (health < lastHealth) {
            LOGGER.debug("Schematic Builder: hurt {} at {} ({}{}{}, fell {} blocks), job {}", lastHealth - health, player.position(), phase,
                player.isInLava() ? ", in lava" : "", player.isOnFire() ? ", on fire" : "", String.format(Locale.ROOT, "%.1f", lastFall), job);
        }
        lastHealth = health;
        lastFall = player.fallDistance;
    }

    /**
     * What's still to do: every planned spot that isn't right yet, lowest first, then nearest. TNT
     * and levers to pull wait off the list until everything else is done (or given up on for good).
     */
    private void scan() {
        scanIn = SCAN_EVERY;
        // Junk blocks mined out (or never taken by the server) are gone from the list too.
        scaffold.removeIf(p -> mc.level.getBlockState(p).canBeReplaced());
        ladder.removeIf(p -> !mc.level.getBlockState(p).is(Blocks.SCAFFOLDING));
        goneJunk(false);
        List<BlockPos> left = new ArrayList<>();
        List<BlockPos> last = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockState> e : plan.entrySet()) {
            BlockPos pos = e.getKey();
            BlockState want = e.getValue();
            if (Placing.skipped(want) || !mc.level.hasChunkAt(pos) || settled(pos)) continue;
            // No block for it and no stand-in that would do: left empty, and marked.
            if (cantFill(pos, want)) {
                leftEmpty.add(pos);
                StandIns.mark(pos, want.getBlock(), null);
                continue;
            }
            (lastOfAll(pos, want) ? last : left).add(pos);
        }
        // Only once everything else is in, or given up on for good (the last time round): never
        // while there's still building near them that could set them off.
        if (left.isEmpty() || sweeps >= 2 && left.stream().allMatch(p -> tries.getOrDefault(p, 0) >= 3)) left.addAll(last);
        Set<Item> items = new HashSet<>();
        for (BlockPos pos : left) items.add(Placing.item(plan.get(pos)));
        stillPlanned = items;
        Vec3 at = mc.player.position();
        left.sort(Comparator.<BlockPos>comparingInt(p -> p.getY()).thenComparingDouble(p -> p.distToCenterSqr(at.x, at.y, at.z)));
        pending = left;
    }

    /** TNT, and a lever to pull: what could set the machine off goes in once all of it's built. */
    private boolean lastOfAll(BlockPos pos, BlockState want) {
        if (want.getBlock() instanceof TntBlock) return true;
        return want.getBlock() instanceof LeverBlock && Placing.clicks(mc.level.getBlockState(pos), want) > 0;
    }

    /**
     * A planned spot as done as it'll get: the right block (waterlogged and clicked as it should
     * be), a stand-in for it, or given up on and left empty.
     */
    private boolean settled(BlockPos pos) {
        BlockState have = mc.level.getBlockState(pos);
        BlockState want = plan.get(pos);
        if (Placing.matches(have, want)) return !Placing.needsWater(have, want) && Placing.clicks(have, want) == 0;
        Block standing = stood.get(pos);
        return standing != null && have.is(standing) || leftEmpty.contains(pos);
    }

    /** No block to be had for it (none on you, and buying's off or came to nothing). */
    private boolean gaveUp(Item item) {
        // Water's to be had for an empty bucket at a pool nearby, when it isn't bought.
        if (item == Items.WATER_BUCKET && fromPool() && count(Items.BUCKET) > 0 && findPool() != null) return false;
        return count(item) == 0 && (!buy.get() || buyTried.contains(item));
    }

    /**
     * Whether water's fetched from a pool in empty buckets: only when it isn't bought off /ah
     * (buying's off, or none was to be had). Bought, it never has to go back down for more.
     */
    private boolean fromPool() {
        return !buy.get() || cantBuy.contains(Items.WATER_BUCKET);
    }

    /** Whether a stand-in can go here: wanted on, the spot wants a full block, and a stand-in's to be had. */
    private boolean standInFits(BlockPos pos, BlockState want) {
        Item sub = standInItem();
        return standIns.get() && sub != null && want.isCollisionShapeFullBlock(mc.level, pos) && !gaveUp(sub);
    }

    private boolean cantFill(BlockPos pos, BlockState want) {
        // In, and only short of its water or its clicks: not something to leave empty.
        if (Placing.matches(mc.level.getBlockState(pos), want)) return false;
        return gaveUp(Placing.item(want)) && !standInFits(pos, want);
    }

    /** The block to put in for one that can't be had: the one picked, or the throwaway you've most of. */
    private Item standInItem() {
        return switch (standIn.get()) {
            case "Stone" -> Items.STONE;
            case "Cobbled Deepslate" -> Items.COBBLED_DEEPSLATE;
            case "Dirt" -> Items.DIRT;
            case "Netherrack" -> Items.NETHERRACK;
            case "Oak Planks" -> Items.OAK_PLANKS;
            case "Whatever you have" -> {
                Item most = Items.COBBLESTONE;
                int best = 0;
                for (Item item : SCAFFOLD) {
                    int n = count(item);
                    if (n > best) {
                        best = n;
                        most = item;
                    }
                }
                yield most;
            }
            default -> Items.COBBLESTONE;
        };
    }

    /** What goes in at {@code pos} now: the planned block, or a stand-in for one that can't be had. Null if neither. */
    private BlockState toPlace(BlockPos pos, BlockState want) {
        if (count(Placing.item(want)) > 0) return want;
        if (!gaveUp(Placing.item(want)) || !standInFits(pos, want)) return null;
        Item sub = standInItem();
        return count(sub) > 0 && sub instanceof BlockItem block ? block.getBlock().defaultBlockState() : null;
    }

    private void decide(LocalPlayer player, long now) {
        steering = Input.EMPTY;
        if (now < nextActAt || !player.onGround()) return;
        if (pending.isEmpty()) {
            lookRound(player);
            if (takeOutProp(player, now)) return;
            if (takeOutScaffold(player, now)) return;
            wrapUp();
            return;
        }
        int next = currentLayer();
        boolean layerDone = layered.get() && layer != Integer.MIN_VALUE && next != Integer.MIN_VALUE && next > layer;
        layer = next;
        if (layerDone) {
            // A new layer: what wasn't to be had gets another go only if some turned up last time.
            buyTried.retainAll(cantBuy);
            noRoom.clear();
            clearingProps = true;
            if (sell(false)) return;
        }
        // What the layer just done was built against comes out before going on.
        if (clearingProps) {
            if (takeOutProp(player, now)) return;
            clearingProps = false;
        }
        // What you've not got: bought first, the way you'd fetch your materials before starting.
        if (buy.get()) {
            Map.Entry<Item, Integer> short_ = shortage(player);
            // No room for all it's short of: what it bought that this layer doesn't want goes back first.
            if (cramped != null && now >= roomSellAt) {
                roomSellAt = now + 15_000;
                if (sell(false)) return;
            }
            if (short_ == null && waitingForRoom() && toldFull.add(cramped)) {
                Notify.send("Schematic Builder", "No room to buy " + AuctionBuyer.name(cramped)
                    + ": your inventory's full. Make some room and it'll buy them", false, false, false);
            }
            if (short_ != null) {
                own.putIfAbsent(short_.getKey(), count(short_.getKey()));
                // A bucket of lava or water bought leaves an empty one to sell back: the empties you had are yours.
                if (filled(short_.getKey())) own.putIfAbsent(Items.BUCKET, count(Items.BUCKET));
                // Whole stacks when what's over goes back with /sell: 148 is three stacks, the rest sold.
                boolean stacks = sellLeftovers.get() && short_.getKey().getDefaultMaxStackSize() > 1;
                buyer.start(short_.getKey(), short_.getValue(), maxEach.get(), budget.get() - spent, confirmScreen.get(), stacks);
                buyTried.add(short_.getKey());
                buyingItem = short_.getKey();
                buyingWant = short_.getValue();
                go(Phase.BUY);
                return;
            }
        }
        // Each bucket poured leaves an empty in its own slot: stacked up again, or they fill the inventory.
        if (stackBuckets()) {
            nextActAt = now + 150;
            return;
        }
        // Water wanted and none to hand: off to fill the empty buckets first.
        boolean thirsty = waterTrip();
        if (thirsty) {
            Job fill = fillJob(player, null);
            if (fill != null) {
                begin(fill);
                return;
            }
        }
        Job here = fromHere(player);
        if (here != null) {
            begin(here);
            return;
        }
        // Where to go and how: along what's built, bridging and pillaring where it must, as one route.
        Routes routes = routes(player, true);
        lastRoutes = routes;
        Job walk = null;
        if (thirsty) {
            walk = fillJob(player, routes);
            // No way there: another pool, if there is one, next time.
            if (walk == null && pool != null) {
                for (BlockPos p : BlockPos.betweenClosed(pool.offset(-3, -1, -3), pool.offset(3, 1, 3))) badPools.put(p.immutable(), now + 20_000);
                pool = null;
            }
        }
        if (walk == null) walk = nextJob(routes);
        if (walk != null) {
            if (!follow(walk, routes, now)) {
                LOGGER.debug("Schematic Builder: no way to {} {} after all", walk.kind(), walk.stand());
                pass(walk.target(), 10_000);
                nextActAt = now + 250;
            }
            return;
        }
        String nothing = "nothing doable on layer " + layer + " from " + player.blockPosition().toShortString() + ": " + routes.order().size()
            + " places to stand looked at, " + triedJobs + " spots tried from them, " + lookFits + " placements the look suits, " + inSight + " of them in sight";
        // Once each time it changes: it's looked again every moment while it waits.
        if (!nothing.equals(lastNothing)) LOGGER.debug("Schematic Builder: {}", nothing);
        lastNothing = nothing;
        // Walled in (the build's gone up round it, too high to step out of): up a block where it
        // stands, a junk one, even in a spot of the build, then out over the top. What's wanted
        // there goes in later, the junk mined out of its way first. Shut in under a junk block of
        // its own (or one beside its head), that comes out first. Nothing's set aside for either:
        // it's being shut in that's in the way, not the spots.
        BlockPos feet = player.blockPosition();
        if (routes.order().size() <= 6) {
            // Standing in water at the bottom of the hole: a jump there's only a swim, never high
            // enough to put a block under you. The water's scooped up first (it goes back in
            // later, a spot of the build like any other), then it's a hole to pillar out of.
            if (player.getFluidHeight(FluidTags.WATER) > player.getFluidJumpThreshold()) {
                if (isStillWater(feet) && count(Items.BUCKET) > 0) {
                    LOGGER.debug("Schematic Builder: shut in at {} standing in water, scooping it up", feet);
                    begin(new Job(Kind.SCOOP, feet.immutable(), null, feet.immutable(), 0));
                } else {
                    stop("shut in standing in water, with no way to climb out (no empty bucket to scoop it up with)");
                }
                return;
            }
            if (scaffoldSlot(player, false) >= 0 && Paths.passable(mc.level, feet.above(2))) {
                LOGGER.debug("Schematic Builder: walled in at {}, climbing out", feet);
                job = new Job(Kind.PLACE, feet.immutable(), null, feet.immutable(), 0);
                // Not mined straight back out from under it (it's in a spot of the build): it's
                // the way out, till it's walked off it.
                passed.put(feet.immutable(), now + 30_000);
                pillarWhy = "pillar, walled in";
                startPillar(feet, 1, false);
                return;
            }
            BlockPos lid = ownBlockRound(feet);
            if (lid != null) {
                LOGGER.debug("Schematic Builder: shut in at {}, taking out {}", feet, lid);
                // Not put straight back: a prop over its head, put in from the bottom of a hole to
                // turn a block it then couldn't see, went in and out for 40 minutes. With it left
                // alone, it climbs out instead.
                pass(lid, 60_000);
                begin(new Job(Kind.BREAK, lid, null, feet.immutable(), 0));
                return;
            }
        }
        // Waiting on room to buy what's wanted: nothing's set aside (three times and it'd be left
        // empty), it waits till there's room.
        if (waitingForRoom()) {
            nextActAt = now + 1000;
            go(Phase.IDLE);
            return;
        }
        // Nothing doable among the nearest few: set them aside a while and look further on (in a
        // moment: working out every route again each tick would lag a big build).
        nextActAt = now + 400;
        int setAside = 0;
        for (BlockPos pos : pending) {
            if (!onLayer(pos) || passedOver(pos)) continue;
            pass(pos, 20_000);
            if (++setAside >= 40) break;
        }
        if (setAside > 0) return;
        // Every one tried three times over and none doable: that's as far as it goes.
        if (pending.stream().allMatch(p -> tries.getOrDefault(p, 0) >= 3)) {
            // Once more round what it gave up on, from the bottom: what it was waiting on (water,
            // the blocks round it) may be there now.
            if (sweeps < 2) {
                sweeps++;
                tries.clear();
                passed.clear();
                layer = Integer.MIN_VALUE;
            } else {
                // As far as it goes: its throwaway and junk blocks come out all the same.
                lookRound(player);
                if (takeOutProp(player, now) || takeOutScaffold(player, now)) return;
                // What's still in is in for good: once more round what was waiting on it to come out.
                if (!lastGo && pending.stream().anyMatch(waitedOnJunk::contains)) {
                    lastGo = true;
                    LOGGER.debug("Schematic Builder: junk left in for good ({}), once more round what waited on it", leftWhy.keySet());
                    tries.clear();
                    passed.clear();
                    layer = Integer.MIN_VALUE;
                    return;
                }
                wrapUp();
            }
            return;
        }
        // Until then, the layer's all set aside and none of it doable: they come round again in a
        // moment rather than the full while, as nothing changes while it stands waiting (the full
        // while is for getting on with the rest of the layer meanwhile).
        long soon = now + IDLE_RETRY;
        for (BlockPos pos : pending) if (onLayer(pos)) passed.computeIfPresent(pos, (p, until) -> Math.min(until, soon));
    }

    /** How soon what's set aside comes round again when there's nothing else to do meanwhile. */
    private static final long IDLE_RETRY = 2500;

    /**
     * A junk block of its own shutting it in where it stands, to take out: over its head first, then
     * beside its head, then beside its feet. A throwaway block holding something up stays. Null if none.
     */
    private BlockPos ownBlockRound(BlockPos feet) {
        List<BlockPos> round = new ArrayList<>(List.of(feet.above(2)));
        for (Direction d : Direction.Plane.HORIZONTAL) round.add(feet.above().relative(d));
        for (Direction d : Direction.Plane.HORIZONTAL) round.add(feet.relative(d));
        for (BlockPos b : round) {
            if ((scaffold.contains(b) || props.contains(b) && removable(b)) && !mc.level.getBlockState(b).canBeReplaced()) return b.immutable();
        }
        return null;
    }

    // ---- Picking the next job

    /** The lowest, nearest thing that can be done from right where you stand. */
    private Job fromHere(LocalPlayer player) {
        // Somewhere it shouldn't stand (in the layer's way, say, or where fire could start): off
        // somewhere better first.
        if (!rules.mayStand(player.blockPosition()) || catchesFire(player.blockPosition())) return null;
        Vec3 eye = player.position().add(0, EYE, 0);
        int lowest = pending.getFirst().getY();
        Job best = null;
        for (BlockPos pos : pending) {
            if (!onLayer(pos) || passedOver(pos) || Vec3.atCenterOf(pos).distanceTo(eye) > reach.get() + 1.2) continue;
            Job j = jobAt(pos, player.blockPosition(), eye, reach.get(), true);
            if (j == null) continue;
            double cost = (pos.getY() - lowest) * 10 + angleTo(player, j) * 0.04 + Vec3.atCenterOf(pos).distanceTo(eye)
                + (j.kind() == Kind.PROP ? PROP_COST : 0);
            if (best == null || cost < best.cost()) best = new Job(j.kind(), j.target(), j.way(), j.stand(), cost);
        }
        return best;
    }

    /** Spots of the layer a route's looked at for at once: the nearest this many. */
    private static final int ROUTE_SPOTS = 60;
    /** Lines of sight checked, at most, in looking for the next job, before it goes with the best so far. */
    private static final int ROUTE_SIGHTS = 24_000;
    /** Lines of sight checked since the count was last started. */
    private int sights;
    /** For the debug log, when there's nothing to do: spots tried from places to stand, placements the look suits, and of those, ones in sight. */
    private int triedJobs;
    private int lookFits;
    private int inSight;
    /** What each spot of the layer in reach from a place takes off its cost, for up to COVER_CAP of them (tenths of a step). */
    private static final int COVER_BONUS = 4;
    private static final int COVER_CAP = 12;
    /** Standing in a spot of the layer still to do: in its way till it moves on. */
    private static final int IN_THE_WAY = 40;
    /**
     * Standing where fire could start: only if nowhere ten steps nearer will do, and only for the
     * one job (it doesn't stay on to do what else is in reach from there).
     */
    private static final int FIRE_RISK = 100;
    /** Placing from somewhere it's only just in sight: a step from where it stops and it mightn't be. */
    private static final int JUST_IN_SIGHT = 30;

    /**
     * The ways round from where you stand: with {@code build}, putting junk blocks down near the
     * build to stand on (bridging, pillaring) as well as walking.
     */
    private Routes routes(LocalPlayer player, boolean build) {
        return routes(player, player.blockPosition(), build, rules, Set.of());
    }

    /** As {@link #routes(LocalPlayer, boolean)}, from {@code feet}, by {@code with}, as if the blocks {@code gone} were out. */
    private Routes routes(LocalPlayer player, BlockPos feet, boolean build, Routes.Rules with, Set<BlockPos> gone) {
        Region area = bounds.grow(8, 6);
        if (pool != null) area = area.union(Region.of(pool, pool).grow(5, 4));
        boolean bridges = build && bridge.get();
        boolean pillars = build && buildUp.get();
        // Back on the ground for good, clearing up: no higher than a short pillar it can dig back down.
        int top = onGroundForGood ? Math.min(bounds.maxY() + 2, home.getY() + GROUND_REACH) : bounds.maxY() + 2;
        Region building = (bridges || pillars) && scaffoldSlot(player, false) >= 0
            ? new Region(bounds.minX() - 2, bounds.minY() - 1, bounds.minZ() - 2, bounds.maxX() + 2, top, bounds.maxZ() + 2)
            : null;
        return Routes.search(mc.level, feet, area, building, bridges, pillars, with, PATH_BUDGET, gone);
    }

    /** {@link #rules}, but with {@code fill} saying where a junk block may go. */
    private Routes.Rules filling(java.util.function.Predicate<BlockPos> fill) {
        return new Routes.Rules() {
            @Override
            public boolean mayStand(BlockPos feet) {
                return rules.mayStand(feet);
            }

            @Override
            public boolean mayFill(BlockPos cell) {
                return fill.test(cell);
            }

            @Override
            public boolean mayDig(BlockPos cell) {
                return rules.mayDig(cell);
            }

            @Override
            public boolean mayStep(BlockPos from, BlockPos to) {
                return rules.mayStep(from, to);
            }

            @Override
            public int fillCost(BlockPos cell) {
                return rules.fillCost(cell);
            }

            @Override
            public boolean ladder(BlockPos cell) {
                return rules.ladder(cell);
            }
        };
    }

    /**
     * Come down at the end of clearing up: from then on it builds no higher than this over the
     * ground where it started (enough to reach the middle of a pillar), not back up the build.
     */
    private boolean onGroundForGood;
    private static final int GROUND_REACH = 4;

    /**
     * The best thing to go and do: of the layer's nearest spots, the one that's cheapest to get to
     * somewhere to do it from (along what's built first, bridging and pillaring where it must),
     * less a little for somewhere that reaches a lot of the layer, so it isn't back and forth.
     * Places to stand are looked at cheapest first, so it stops once none further on could win.
     */
    private Job nextJob(Routes routes) {
        List<BlockPos> spots = new ArrayList<>();
        for (BlockPos pos : pending) {
            if (!onLayer(pos) || passedOver(pos)) continue;
            spots.add(pos);
            if (spots.size() >= ROUTE_SPOTS) break;
        }
        if (spots.isEmpty()) return null;
        int lowest = pending.getFirst().getY();
        double limit = reach.get() - PLAN_SLACK;
        double near = (limit + 0.9) * (limit + 0.9);
        double[] centres = new double[spots.size() * 3];
        for (int i = 0; i < spots.size(); i++) {
            centres[i * 3] = spots.get(i).getX() + 0.5;
            centres[i * 3 + 1] = spots.get(i).getY() + 0.5;
            centres[i * 3 + 2] = spots.get(i).getZ() + 0.5;
        }
        int[] inReach = new int[spots.size()];
        Job best = null;
        sights = 0;
        triedJobs = 0;
        lookFits = 0;
        inSight = 0;
        LongArrayList order = routes.order();
        for (int n = 0; n < order.size(); n++) {
            BlockPos stand = BlockPos.of(order.getLong(n));
            int cost = routes.distance(stand);
            if (best != null && cost - COVER_CAP * COVER_BONUS >= best.cost()) break;
            double ex = stand.getX() + 0.5;
            double ey = stand.getY() + EYE;
            double ez = stand.getZ() + 0.5;
            int count = 0;
            for (int i = 0; i < spots.size(); i++) {
                double dx = centres[i * 3] - ex;
                double dy = centres[i * 3 + 1] - ey;
                double dz = centres[i * 3 + 2] - ez;
                if (dx * dx + dy * dy + dz * dz <= near) inReach[count++] = i;
            }
            // Never from in a fire (where you stand now is looked at, whatever the rules for routes).
            if (count == 0 || burning(stand) || burning(stand.above())) continue;
            double base = cost - Math.min(count, COVER_CAP) * COVER_BONUS + (stillToDo(stand) ? IN_THE_WAY : 0)
                + (catchesFire(stand) ? FIRE_RISK : 0);
            Vec3 eye = new Vec3(ex, ey, ez);
            for (int k = 0; k < count; k++) {
                BlockPos spot = spots.get(inReach[k]);
                double score = base + (spot.getY() - lowest) * 60;
                if (best != null && score >= best.cost()) continue;
                if (sights > ROUTE_SIGHTS) return best;
                triedJobs++;
                Job j = jobAt(spot, stand, eye, limit);
                if (j == null) continue;
                // A prop keeps its own cell as the target; it costs more than a real block.
                if (j.kind() == Kind.PROP) score += PROP_COST * Routes.STEP;
                score += j.cost();
                if (best != null && score >= best.cost()) continue;
                best = new Job(j.kind(), j.kind() == Kind.PROP ? j.target() : spot, j.way(), stand, score);
            }
        }
        return best;
    }

    /** Sets off on the route to where {@code j} is done from. @return false if there's no way there after all. */
    private boolean follow(Job j, Routes routes, long now) {
        List<BlockPos> route = routes.pathTo(j.stand());
        if (route == null) return false;
        List<Routes.Move> how = routes.moves(route);
        // Not enough junk blocks for the bridges and pillars on the way.
        int junk = Routes.blocks(how);
        if (junk > scaffoldCount()) return false;
        if (junk > 0) LOGGER.debug("Schematic Builder: {} junk blocks on the way to {} for {} {}: {}", junk, j.stand(), j.kind(), j.target(), how);
        job = j;
        path = route;
        moves = how;
        pathIndex = 1;
        resumeRoute = false;
        clearBehind = false;
        resumeWalk = null;
        lookingAhead = false;
        lastMoveAt = now;
        arrivedAt = 0;
        go(Phase.WALK);
        return true;
    }

    /** What the build allows of the routes round it. */
    private final Routes.Rules rules = new Routes.Rules() {
        @Override
        public boolean mayStand(BlockPos feet) {
            // Not with your head in a spot of the layer still to do: you'd be in its way, and once
            // the blocks round it were in, down a hole two deep with no way out. Feet are all
            // right: what goes in round them only comes up to your knees, and you step up onto it.
            if (stillToDo(feet.above())) return false;
            // Never in the build's own water, poured or still to pour: it goes in from beside, and
            // at the bottom of a hole (as a farm's water often is), once the blocks round it are in
            // there's no getting out, as there's no jumping or pillaring up out of water.
            BlockState planned = plan.get(feet);
            if (planned != null && Placing.fluid(planned) && planned.getFluidState().is(FluidTags.WATER)) return false;
            // Never on top of a ladder: crouched there to place, you'd sink.
            if (isLadder(feet.below())) return false;
            // Never in fire.
            if (burning(feet) || burning(feet.above())) return false;
            // Never in tripwire or on a pressure plate, which would set the machine off.
            return !trigger(feet) && !trigger(feet.above());
        }

        @Override
        public boolean ladder(BlockPos cell) {
            return isLadder(cell);
        }

        /**
         * Junk blocks to walk on: where a throwaway block may go, and not beside a spot still to do
         * that's turned by which way you look placing it, where it could close off the one way to
         * look at it right (the gap beside a piston on a wall, that it's placed looking up through).
         */
        @Override
        public boolean mayFill(BlockPos cell) {
            return mayPut(cell) && !besideTurned(cell) && !(cleaning && cleared.contains(cell));
        }

        /** Just under such a gap, only if there's no other way: that's where it's looked up through from. */
        @Override
        public int fillCost(BlockPos cell) {
            return underGap(cell) ? UNDER_GAP : 0;
        }

        @Override
        public boolean mayDig(BlockPos cell) {
            return scaffold.contains(cell) && mc.level.getBlockState(cell).isSolid() && quietOut(cell);
        }

        @Override
        public boolean mayStep(BlockPos from, BlockPos to) {
            Long until = stuckSteps.get(List.of(from, to));
            return until == null || until < System.currentTimeMillis();
        }
    };

    /** Steps it got stuck on lately, from and to, and till when they're not tried again. */
    private final Map<List<BlockPos>, Long> stuckSteps = new HashMap<>();

    /** Whether a throwaway block may go in {@code cell}: not a spot of the build, empty, and nothing it'd set off or hold in only for now. */
    private boolean mayPut(BlockPos cell) {
        if (plan.containsKey(cell)) return false;
        BlockState state = mc.level.getBlockState(cell);
        return state.canBeReplaced() && state.getFluidState().isEmpty() && !watched(cell) && !byFluid(cell) && !besideWatchedFiring(cell);
    }

    /**
     * One or two under an empty cell beside a spot still to do that's turned by your look: where
     * you stand to look up through that gap at a piston on top of a wall. Lower down's all right
     * (that's the floor to stand on), but a block here leaves nowhere low enough to look up from.
     * Not ruled out (the way down to look up from often goes through here), only dear.
     */
    private static final int UNDER_GAP = 200;

    private boolean underGap(BlockPos cell) {
        for (int k = 1; k <= 2; k++) {
            BlockPos gap = cell.above(k);
            if (!plan.containsKey(gap) && besideTurned(gap)) return true;
        }
        return false;
    }

    /** Beside a spot still to do that's turned by which way you look placing it: a piston, an observer, a dropper. */
    private boolean besideTurned(BlockPos cell) {
        return besideTurned(cell, null);
    }

    /** As {@link #besideTurned(BlockPos)}, but for one other than {@code except} (the one a prop's for). */
    private boolean besideTurned(BlockPos cell, BlockPos except) {
        for (Direction d : Direction.values()) {
            BlockPos next = cell.relative(d);
            BlockState planned = plan.get(next);
            if (planned != null && !next.equals(except) && planned.hasProperty(BlockStateProperties.FACING) && !Placing.skipped(planned) && !settled(next)) return true;
        }
        return false;
    }

    /**
     * What doing the planned spot {@code pos} from {@code stand} (eye at {@code eye}, reaching
     * {@code reachLimit}) would take: a break if something wrong is in it, a placement otherwise.
     * Null if it can't be done from there.
     */
    private Job jobAt(BlockPos pos, BlockPos stand, Vec3 eye, double reachLimit) {
        return jobAt(pos, stand, eye, reachLimit, false);
    }

    /**
     * As {@link #jobAt(BlockPos, BlockPos, Vec3, double)}; {@code steady}: a placement that a look
     * a hair either side of still lands, if there's one, over one only just in sight (from where
     * you really are, where a grazing look tips onto the block beside).
     */
    private Job jobAt(BlockPos pos, BlockPos stand, Vec3 eye, double reachLimit, boolean steady) {
        BlockState want = plan.get(pos);
        BlockState have = mc.level.getBlockState(pos);
        if (inTheWay(pos, have, want)) {
            if (!breakWrong.get() || stand.below().equals(pos)) return null;
            Vec3 face = visibleFace(eye, pos, reachLimit);
            return face == null ? null : new Job(Kind.BREAK, pos, null, stand, 0);
        }
        // Bottom up: nothing goes on top of a planned spot that isn't right yet, or that spot gets
        // buried. Except TNT (and a lever to pull), which goes in last of all, from the side; and
        // lava or water over more of the same, which buries nothing (what it runs down into gets
        // its own bucket after), so one that's held up doesn't hold up all of it over it.
        BlockState below = plan.get(pos.below());
        if (below != null && !Placing.skipped(below) && !settled(pos.below()) && !lastOfAll(pos.below(), below)
            && !(Placing.fluid(want) && Placing.fluid(below) && below.getFluidState().getType().isSame(want.getFluidState().getType()))) return null;
        // In already, only short of its water or a click or two: done to it standing (crouched,
        // a bucket of water would go beside it and a click would place against it).
        if (Placing.matches(have, want)) return finishing(pos, have, want, stand, eye.add(0, STAND_EYE - EYE, 0), reachLimit);
        if (!ready(pos, want)) return null;
        if (Placing.fluid(want)) return pour(pos, want, stand, eye, reachLimit);
        BlockState placing = toPlace(pos, want);
        if (placing == null) return null;
        Item item = placing.getBlock().asItem();
        // Never into the space you stand in.
        AABB body = new AABB(stand.getX() + 0.2, stand.getY(), stand.getZ() + 0.2, stand.getX() + 0.8, stand.getY() + 1.8, stand.getZ() + 0.8);
        if (body.intersects(new AABB(pos)) && !placing.getCollisionShape(mc.level, pos).isEmpty()) return null;
        // Nothing that burns beside where you stand, if lava's near enough to light the air you're in.
        if (placing.ignitedByLava() && (pos.distManhattan(stand) == 1 && firesReach(stand) || pos.distManhattan(stand.above()) == 1 && firesReach(stand.above()))) return null;
        List<Placing.Way> all = waysFor(pos, placing, item);
        Placing.Way seen = null;
        for (Placing.Way way : all) {
            if (way.hit().distanceTo(eye) > reachLimit) continue;
            float[] rot = rotation(eye, way.hit());
            if (!(steady ? way.fits(rot[0], rot[1]) : way.fitsWell(rot[0], rot[1]))) continue;
            lookFits++;
            if (!visible(eye, way)) continue;
            inSight++;
            // In sight from a hair either side as well (planned from the middle of the spot, which is
            // only about where it'll stop), or with a look a hair either side landing (from where it
            // really is): best. Failing that, one only just in sight will do, costing a little more,
            // as some placements are only ever just in sight (a piston facing down on a wall, seen
            // past the top edge of the wall).
            if (steady ? steadilyVisible(eye, way) : visibleBeside(eye, way)) return new Job(Kind.PLACE, pos, way, stand, 0);
            if (seen == null) seen = way;
        }
        if (seen != null) return new Job(Kind.PLACE, pos, seen, stand, steady ? 0 : JUST_IN_SIGHT);
        // Floating, with nothing beside it to place it against: a throwaway block first, to build
        // it on. Only for blocks that stand on their own once it's taken out again (a fence gate
        // or glass, say, but not a torch or redstone).
        if (all.isEmpty() && standsAlone(placing) && !hasSupport(pos) && !comingBeside(pos)) {
            BlockPos cell = propCell(pos);
            if (cell != null) {
                propWhy.put(cell, "prop under a floating block");
                return propAt(cell, stand, eye, reachLimit);
            }
        }
        // Nothing there to place it against that comes out right from here: a throwaway block
        // beside it first, where a look from here at it would, for a block turned by which way
        // you look placing it (a piston, an observer, a dropper).
        if (placing.hasProperty(BlockStateProperties.FACING) && standsAlone(placing)) {
            Job prop = propToLookFrom(pos, placing, item, stand, eye, reachLimit, steady);
            if (prop != null) return prop;
        }
        return null;
    }

    /**
     * A spot that isn't done yet, of the layer being built or one under it (given up on for now,
     * it gets another go): somewhere you'd be in the way, standing.
     */
    private boolean stillToDo(BlockPos pos) {
        BlockState want = plan.get(pos);
        if (want == null || Placing.skipped(want) || settled(pos)) return false;
        return !layered.get() || layer == Integer.MIN_VALUE || pos.getY() <= layer;
    }

    /** Tripwire or a pressure plate here, or to go here: walked into, it sets the machine off. */
    private boolean trigger(BlockPos pos) {
        BlockState planned = plan.get(pos);
        return planned != null && tripping(planned) || tripping(mc.level.getBlockState(pos));
    }

    private static boolean tripping(BlockState state) {
        return state.getBlock() instanceof TripWireBlock || state.getBlock() instanceof BasePressurePlateBlock;
    }

    /** Whether an observer (in, or to go in) watches this cell: a block put in or taken out there would set it off. */
    private boolean watched(BlockPos cell) {
        for (Direction d : Direction.values()) {
            BlockPos at = cell.relative(d);
            for (BlockState o : new BlockState[] {plan.get(at), mc.level.getBlockState(at)}) {
                if (o != null && o.getBlock() instanceof ObserverBlock && o.getValue(ObserverBlock.FACING) == d.getOpposite()) return true;
            }
        }
        return false;
    }

    /** Whether an observer that's in watches this cell. */
    private boolean seen(BlockPos cell) {
        for (Direction d : Direction.values()) {
            BlockState o = mc.level.getBlockState(cell.relative(d));
            if (o.getBlock() instanceof ObserverBlock && o.getValue(ObserverBlock.FACING) == d.getOpposite()) return true;
        }
        return false;
    }

    /** A dropper, dispenser or piston: turned over by the power round it, quasi-connectivity and all. */
    private static boolean fires(BlockState state) {
        return state.getBlock() instanceof DispenserBlock || state.getBlock() instanceof PistonBaseBlock;
    }

    /**
     * Beside a dropper or piston that an observer (in, or to go in) watches: a junk block put in or
     * taken out here can turn it over, and so set off the observer and what that powers.
     */
    private boolean besideWatchedFiring(BlockPos cell) {
        for (Direction d : Direction.values()) {
            BlockPos at = cell.relative(d);
            BlockState planned = plan.get(at);
            if ((planned != null && fires(planned) || fires(mc.level.getBlockState(at))) && watched(at)) return true;
        }
        return false;
    }

    /** Next to water or lava, or a spot for some: a junk block there would hold it in only till it came out again. */
    private boolean byFluid(BlockPos cell) {
        for (Direction d : Direction.values()) {
            BlockPos next = cell.relative(d);
            BlockState planned = plan.get(next);
            if (planned != null && Placing.fluid(planned) || mc.level.getBlockState(next).getBlock() instanceof LiquidBlock) return true;
        }
        return false;
    }

    /** Junk blocks: down to this few, a stack more's bought; and a stack's kept back from selling while there's building left. */
    private static final int JUNK_LOW = 16;
    private static final int JUNK_STACK = 64;

    /** The junk blocks to buy more of: the kind the build doesn't use that you've most of; dirt, failing that. */
    private Item junkItem() {
        Item most = null;
        int best = 0;
        for (Item item : SCAFFOLD) {
            int n = count(item);
            if (!stillPlanned.contains(item) && n > best) {
                best = n;
                most = item;
            }
        }
        if (most != null) return most;
        return stillPlanned.contains(Items.DIRT) ? Items.COBBLESTONE : Items.DIRT;
    }

    /** Junk blocks to hand, for pillars and bridges. */
    private int scaffoldCount() {
        Inventory inventory = mc.player.getInventory();
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (SCAFFOLD.contains(stack.getItem())) n += stack.getCount();
        }
        return n;
    }

    // ---- Clearing up

    /**
     * The junk blocks it stood on, taken out once it's all built. Everything but the pillar it'll
     * come down by (and the way onto it) goes first, from wherever reaches it: the highest first,
     * far ends before near ones, and never one that's its only way on foot to others (steps down
     * a wall come out from the bottom up, from the step above) or to that pillar. Then that pillar,
     * walked onto and mined down from the top, so it comes down with it; or with no such pillar, a
     * way down of its own, each junk step taken out behind it as it goes. From the ground, what was
     * out of reach up there gets another look. Nothing's taken out where it'd set the machine off.
     * @return whether it's on it.
     */
    private boolean takeOutScaffold(LocalPlayer player, long now) {
        scaffold.removeIf(p -> {
            if (!mc.level.getBlockState(p).canBeReplaced()) return false;
            if (cleaning) cleared.add(p);
            return true;
        });
        List<BlockPos> left = new ArrayList<>();
        boolean setAside = false;
        for (BlockPos p : scaffold) {
            if (stuckProps.contains(p)) continue;
            if (passedOver(p)) setAside = true;
            else if (quietOut(p)) left.add(p);
            else leave(p, REDSTONE);
        }
        // All out, or only what it mustn't touch; nothing set aside for a moment, nothing to come down for.
        if (left.isEmpty() && !setAside && !leftWhy.containsValue(OUT_OF_REACH)) return false;
        // Getting at them may take a bridge or a stair of its own (which comes out in turn), but
        // only so many, so it can't go on putting blocks in to take blocks out.
        cleaning = true;
        Routes routes = routes(player, mayBuildClearing());
        List<BlockPos> exit = exitPillar(routes);
        List<BlockPos> first = new ArrayList<>(left);
        first.removeAll(exit);
        if (!exit.isEmpty()) {
            // The way onto it stays till it's been used: unless it's up out of somewhere on a
            // pillar of its own anyway (the bottom of a shaft in the build). Then the junk blocks
            // it'd climb over come out first, and it climbs out on a pillar of its own that comes
            // out under it as it goes: a stair left down there, nothing could get at again.
            List<BlockPos> way = routes.pathTo(exit.getFirst().above());
            if (way != null && !routes.moves(way).contains(Routes.Move.PILLAR)) for (BlockPos node : way) first.remove(node.below());
        }
        // A pillar comes out from the top down: taken from the foot first, the rest of it's left
        // hanging out of reach.
        // A ladder with its top block out comes down from anywhere along it, everything over that
        // with it: the lowest it can get at first.
        first.removeIf(b -> scaffold.contains(b.above()) && !stuckProps.contains(b.above()) && !(isLadder(b) && bareLadder(b)));
        if (!first.isEmpty()) {
            first.sort(Comparator.comparingInt((BlockPos b) -> -ladderTop(b).getY()).thenComparingInt(b -> isLadder(b) ? b.getY() : 0)
                .thenComparingInt(this::anchors).thenComparingInt(this::scaffoldBeside));
            if (takeOut(player, now, first, routes, true, exit.isEmpty() ? null : exit.getFirst())) return true;
        }
        // What's set aside is its way to others still in: back to it in a moment, once they're out.
        boolean waiting = setAside;
        for (BlockPos p : first) waiting |= passedOver(p);
        if (waiting) {
            nextActAt = now + 500;
            return true;
        }
        if (!exit.isEmpty()) {
            BlockPos top = exit.getFirst();
            if (player.onGround() && player.blockPosition().below().equals(top)) {
                startDescend();
                return true;
            }
            if (follow(new Job(Kind.DOWN, top, null, top.above(), 0), routes, now)) {
                LOGGER.debug("Schematic Builder: coming down by the pillar at {} ({} high)", top, exit.size());
                return true;
            }
            for (BlockPos b : exit) leave(b, OUT_OF_REACH);
            return false;
        }
        // No pillar to come down by, and junk left it can't get at from up here: down a way of its own.
        return leftWhy.containsValue(OUT_OF_REACH) && goDown(player, now, routes);
    }

    /**
     * A fresh look round at what it left in, before clearing up: once the building's done (it was
     * out of reach from where it was building), again once the last of it's in if it started
     * before (round what it gave up on, from wherever it was then, with no blocks of its own to
     * get there), and again from the ground once it's come down.
     */
    private void lookRound(LocalPlayer player) {
        boolean fresh = !lookedAgain || pending.isEmpty() && !lookedBuilt;
        if (!fresh && !cameDown) return;
        if (fresh) junkAtStart.addAll(scaffold);
        LOGGER.debug("Schematic Builder: clearing up {} junk blocks and {} props from {}{}: {}", scaffold.size(), props.size(), player.blockPosition(),
            fresh ? lookedAgain ? ", all built now" : "" : ", come down", scaffold);
        lookAgain(fresh);
        lookedAgain = true;
        lookedBuilt |= pending.isEmpty();
        cameDown = false;
    }

    /** Most junk blocks looked at each time for whether taking them out cuts it off from the rest: two route searches each. */
    private static final int WAY_CHECKS = 6;

    /**
     * Whether taking out {@code j}'s junk block, from where {@code j} stands, leaves a way on foot
     * from there to every other junk block of {@code others} it could walk onto before, and to the
     * pillar it leaves by ({@code exitTop}): so it never cuts itself off from the rest, or from the
     * way down. Only ones it can break count: one nothing reaches holds nothing up. Nor one near it
     * left where nowhere it can still walk to reaches it. On foot means there and back: the top
     * step of a stair down a wall taken out first, the lower ones can still be dropped onto, but
     * there's no getting back up off them, so it had to put new blocks in to get at each old one.
     */
    private boolean keepsWays(Job j, BlockPos exitTop, Set<BlockPos> others) {
        Region area = bounds.grow(8, 6);
        Routes before = Routes.searchTwoWay(mc.level, j.stand(), area, rules, PATH_BUDGET, Set.of());
        Routes after = Routes.searchTwoWay(mc.level, j.stand(), area, rules, PATH_BUDGET, Set.of(j.target()));
        if (exitTop != null && before.distance(exitTop.above()) >= 0 && after.distance(exitTop.above()) < 0) return false;
        for (BlockPos b : others) {
            if (b.equals(j.target())) continue;
            if (before.distance(b.above()) >= 0 && after.distance(b.above()) < 0) return false;
            if (b.distChessboard(j.target()) <= STRAND_NEAR && !breakableFrom(after, b, j.target()) && breakableFrom(before, b, null)) return false;
        }
        return true;
    }

    /**
     * Whether, with {@code j}'s junk block out, there's a way from where {@code j} stands back down
     * to the ground (where you can walk off to where it started), putting junk blocks in where it
     * still may: so it never takes out its last way out of somewhere. Along the top of a shaft in
     * the build, bridging on over it and taking out the block behind it each time, it ended up on
     * the last one, in a corner, with nowhere round it left that it might put one back. And with
     * the junk blocks it'll have on it by then, going over by {@code routes}: it once spent its
     * last ones getting somewhere to take one out from. Only while there's a way out from where it
     * is now: with none, there's none to keep.
     */
    private boolean wayOutAfter(LocalPlayer player, Job j, Routes routes) {
        Region area = bounds.grow(8, 6);
        Set<BlockPos> gone = Set.of(j.target());
        Routes fromHome = Routes.search(mc.level, home, area, null, false, false, rules, PATH_BUDGET, gone);
        if (fromHome.distance(j.stand()) >= 0) return true;
        List<BlockPos> there = routes.pathTo(j.stand());
        int left = scaffoldCount() - (there == null ? 0 : Routes.blocks(routes.moves(there)));
        Routes.Rules after = filling(c -> !c.equals(j.target()) && rules.mayFill(c));
        if (downWith(routes(player, j.stand(), true, after, gone), fromHome, left)) return true;
        Routes homeNow = Routes.search(mc.level, home, area, null, false, false, rules, PATH_BUDGET);
        return homeNow.distance(player.blockPosition()) < 0 && !downWith(routes(player, true), homeNow, scaffoldCount());
    }

    /**
     * Whether {@code routes} gets down to the ground ({@code fromHome} gets to), the nearest of it
     * as {@link #goDown} goes, with no more than {@code blocks} junk blocks put in on the way.
     */
    private static boolean downWith(Routes routes, Routes fromHome, int blocks) {
        LongArrayList order = routes.order();
        for (int n = 0; n < order.size(); n++) {
            BlockPos at = BlockPos.of(order.getLong(n));
            if (fromHome.distance(at) < 0) continue;
            List<BlockPos> way = routes.pathTo(at);
            return way != null && Routes.blocks(routes.moves(way)) <= blocks;
        }
        return false;
    }

    /** How near a junk block's taking out may leave another out of reach: the steps of the same stair or bridge. */
    private static final int STRAND_NEAR = 5;

    /** Whether somewhere {@code routes} gets to on foot (not on top of it, nor of {@code gone}) reaches {@code b} to break it. */
    private boolean breakableFrom(Routes routes, BlockPos b, BlockPos gone) {
        double limit = reach.get() - BREAK_SLACK;
        for (int dy = -4; dy <= 1; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos stand = b.offset(dx, dy, dz);
                    if (routes.distance(stand) < 0 || stand.below().equals(b) || stand.below().equals(gone)) continue;
                    Vec3 eye = Vec3.atBottomCenterOf(stand).add(0, EYE, 0);
                    if (eye.distanceTo(Vec3.atCenterOf(b)) > limit + 0.9) continue;
                    if (visibleFace(eye, b, limit) != null) return true;
                }
            }
        }
        return false;
    }

    /**
     * Down to the ground (where you can walk off to where it started) a way of its own: along
     * what's there and down steps against a wall, each junk block it steps off taken out behind
     * it as it goes, so no way down's left standing. @return whether it's on its way.
     */
    private boolean goDown(LocalPlayer player, long now, Routes routes) {
        Routes fromHome = Routes.search(mc.level, home, bounds.grow(8, 6), null, false, false, rules, PATH_BUDGET);
        if (fromHome.distance(player.blockPosition()) >= 0) return false;
        // Steps of its own whatever it's put in already: they come out behind it as it goes.
        if (!mayBuildClearing()) routes = routes(player, true);
        if (downBy(routes, fromHome, now)) return true;
        // None but where junk blocks have come out already: back in there, this once, rather than
        // left up there. They come out again behind it.
        Routes refill = routes(player, player.blockPosition(), true,
            filling(c -> rules.mayFill(c) || cleared.contains(c) && mayPut(c) && !besideTurned(c)), Set.of());
        if (downBy(refill, fromHome, now)) return true;
        LOGGER.debug("Schematic Builder: no way down from {} to the ground", player.blockPosition());
        return false;
    }

    /** Sets off down {@code routes} to the nearest spot of the ground ({@code fromHome} gets to), if it can. */
    private boolean downBy(Routes routes, Routes fromHome, long now) {
        LongArrayList order = routes.order();
        for (int n = 0; n < order.size(); n++) {
            BlockPos ground = BlockPos.of(order.getLong(n));
            if (fromHome.distance(ground) < 0) continue;
            if (!follow(new Job(Kind.DOWN, ground, null, ground, 0), routes, now)) return false;
            LOGGER.debug("Schematic Builder: no pillar to come down by, going down to {} by {}", ground, moves);
            clearBehind = true;
            return true;
        }
        return false;
    }

    /** On the way down for good: each junk block it steps off comes out behind it. The route it's on while one does. */
    private boolean clearBehind;
    private Job resumeWalk;

    /** Leaves a junk block or prop in for now, and why. */
    private void leave(BlockPos b, String why) {
        if (!why.equals(leftWhy.get(b))) LOGGER.debug("Schematic Builder: leaving {} in: {}", b, why);
        stuckProps.add(b.immutable());
        leftWhy.put(b.immutable(), why);
    }

    /** Lets what was left in come round again: everything ({@code all}), or what was out of reach. */
    private void lookAgain(boolean all) {
        leftWhy.entrySet().removeIf(e -> {
            if (!all && !e.getValue().equals(OUT_OF_REACH)) return false;
            stuckProps.remove(e.getKey());
            return true;
        });
        if (all) {
            stuckProps.clear();
            breakFails.clear();
        }
    }

    /**
     * The pillar it comes down by at the end: of the pillar tops it can get onto (the one it's on
     * among them), the nearest that you can walk away from the foot of; failing that, the nearest.
     * Top block first; empty if there's none.
     */
    private List<BlockPos> exitPillar(Routes routes) {
        Routes fromHome = null;
        BlockPos top = null;
        double best = Double.MAX_VALUE;
        for (BlockPos b : scaffold) {
            if (scaffold.contains(b.above()) || !safeDig(b)) continue;
            int d = routes.distance(b.above());
            if (d < 0) continue;
            BlockPos foot = b;
            while (scaffold.contains(foot.below())) foot = foot.below();
            if (fromHome == null) fromHome = Routes.search(mc.level, home, bounds.grow(8, 6), null, false, false, rules, PATH_BUDGET);
            double cost = d + (walksOut(foot, fromHome) ? 0 : 100_000);
            if (cost < best) {
                best = cost;
                top = b;
            }
        }
        if (top == null) return List.of();
        List<BlockPos> column = new ArrayList<>();
        for (BlockPos b = top; scaffold.contains(b); b = b.below()) column.add(b);
        return column;
    }

    /** Whether, standing where the foot of a pillar is now, you could walk off to where it started. */
    private static boolean walksOut(BlockPos foot, Routes fromHome) {
        for (Direction d : Direction.Plane.HORIZONTAL) if (fromHome.distance(foot.relative(d)) >= 0) return true;
        return false;
    }

    /**
     * Junk blocks it may put in while clearing up, to get at the rest (which come out in turn), and
     * one more for every two of the ones it had down when it started that are out by then: so a big
     * build's clearing up doesn't stop halfway, but every block it puts in has to get two out. With
     * two in for every one out (as it was), each block it stood on to take one out was another to
     * take out, and clearing up the top of a farm put in 120 to take 81 out.
     */
    private static final int CLEANUP_BLOCKS = 32;
    private static final int CLEANUP_OUT_PER_BLOCK = 2;
    /** Clearing up its junk blocks, and how many more it's put in doing it. */
    private boolean cleaning;
    private int cleanupBlocks;
    /** The junk blocks it had down when it started clearing up. */
    private final Set<BlockPos> junkAtStart = new HashSet<>();

    /**
     * Clearing up, whether the way to where {@code j} is done from is worth the junk blocks it puts
     * in: no bridge, or fewer bridge blocks than the ones it takes out from there. One put in to
     * take one out (a step to stand on beside the step it's taking out) never gets anywhere: that
     * one's to come out next, from another step put in beside it, and so on round. A pillar's
     * free: it's mined out from under it on the way down.
     */
    private boolean worthBuilding(Job j, Routes routes, Set<BlockPos> breakable) {
        List<BlockPos> way = routes.pathTo(j.stand());
        int in = way == null ? 0 : (int) routes.moves(way).stream().filter(m -> m == Routes.Move.BRIDGE).count();
        if (in == 0) return true;
        Vec3 eye = Vec3.atBottomCenterOf(j.stand()).add(0, EYE, 0);
        int out = 0;
        for (BlockPos b : breakable) {
            if (!b.equals(j.stand().below()) && visibleFace(eye, b, reach.get() - BREAK_SLACK) != null && ++out > in) return true;
        }
        LOGGER.debug("Schematic Builder: not putting {} junk blocks in to take {} out from {}", in, out, j.stand());
        return false;
    }

    /** Whether it may still put junk blocks in, clearing up, to get at the rest. */
    private boolean mayBuildClearing() {
        int out = 0;
        for (BlockPos p : junkAtStart) if (mc.level.getBlockState(p).canBeReplaced()) out++;
        return cleanupBlocks < CLEANUP_BLOCKS + out / CLEANUP_OUT_PER_BLOCK;
    }
    /**
     * Where junk blocks were taken out while clearing up: none goes back in there, or it goes round
     * and round, putting back the pillar it's just taken out to get at the next one.
     */
    private final Set<BlockPos> cleared = new HashSet<>();

    /** Solid blocks of the build (or the ground) a junk block's against, under it and round it: what a bridge's near end hangs on. */
    private int anchors(BlockPos b) {
        int n = 0;
        for (Direction d : Direction.values()) {
            BlockPos next = b.relative(d);
            if (d != Direction.UP && !scaffold.contains(next) && !mc.level.getBlockState(next).getCollisionShape(mc.level, next).isEmpty()) n++;
        }
        return n;
    }

    private int scaffoldBeside(BlockPos b) {
        int n = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) if (scaffold.contains(b.relative(d))) n++;
        return n;
    }

    // ---- Props: throwaway blocks to build floating ones against

    /** Whether anything's beside a cell to place a block against. */
    private boolean hasSupport(BlockPos cell) {
        for (Direction d : Direction.values()) {
            BlockPos next = cell.relative(d);
            BlockState s = mc.level.getBlockState(next);
            if (!s.isAir() && !s.canBeReplaced() && !s.getShape(mc.level, next).isEmpty()) return true;
        }
        return false;
    }

    /**
     * Whether blocks of the build beside {@code pos}, on its level, are still to go in, one of them
     * (or one further along a row of them) with something to place it against already: they go in
     * first, and then {@code pos} goes in against them, so a throwaway block for it now would come
     * out again unused. Not past one it's had trouble with (set aside, or tried before): then the
     * throwaway block it is.
     */
    private boolean comingBeside(BlockPos pos) {
        Set<BlockPos> seen = new HashSet<>();
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        seen.add(pos);
        queue.add(pos);
        while (!queue.isEmpty() && seen.size() < 48) {
            BlockPos at = queue.poll();
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos next = at.relative(d);
                if (!seen.add(next) || !comesInFirst(next)) continue;
                if (hasSupport(next)) return true;
                queue.add(next);
            }
        }
        return false;
    }

    /** A block of the build still to go in, whole (one to place against), and nothing holding it up: none it's had trouble with. */
    private boolean comesInFirst(BlockPos pos) {
        BlockState want = plan.get(pos);
        if (want == null || Placing.fluid(want) || settled(pos) || passedOver(pos) || tries.getOrDefault(pos, 0) > 0) return false;
        if (!want.isCollisionShapeFullBlock(mc.level, pos)) return false;
        BlockState under = plan.get(pos.below());
        return under == null || Placing.skipped(under) || settled(pos.below());
    }

    /**
     * Where a throwaway block goes so a floating block has something to be placed against: the
     * open cell beside it (under it first) that has something to place against itself; failing
     * that, the first step of the shortest way out to one, up to three cells long. Only cells the
     * schematic leaves empty, and none beside a piston or the like still to do: that's the gap it's
     * placed through, and a prop boxed in there by what's built round it later can't be got out.
     */
    private BlockPos propCell(BlockPos pos) {
        Map<BlockPos, Integer> depth = new HashMap<>();
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        depth.put(pos, 0);
        queue.add(pos);
        while (!queue.isEmpty()) {
            BlockPos at = queue.poll();
            int d = depth.get(at);
            for (Direction dir : PROP_ORDER) {
                BlockPos next = at.relative(dir);
                if (depth.containsKey(next) || plan.containsKey(next) || props.contains(next) || passedOver(next) || besideTurned(next, pos)) continue;
                BlockState s = mc.level.getBlockState(next);
                if (!s.canBeReplaced() || s.getFluidState().isSource()) continue;
                depth.put(next, d + 1);
                if (hasSupport(next)) return next;
                if (d + 1 < 3) queue.add(next);
            }
        }
        return null;
    }

    private static final Direction[] PROP_ORDER = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
    /** How much more a throwaway block costs than putting in a real one: real ones first. */
    private static final double PROP_COST = 8;

    /** Ways of placing a block against a throwaway block that isn't in yet, beside it: by spot, then by the throwaway block's cell. */
    private final Map<BlockPos, Map<BlockPos, List<Placing.Way>>> propWays = new HashMap<>();

    /**
     * A throwaway block beside {@code pos}, from {@code stand}, that it could then be placed
     * against from there, turned right by the look: the throwaway block's job, or null.
     */
    private Job propToLookFrom(BlockPos pos, BlockState placing, Item item, BlockPos stand, Vec3 eye, double reachLimit, boolean steady) {
        // A block of the build that's to go in beside it anyway and would do as well: that first.
        for (Direction d : Direction.values()) {
            BlockPos cell = pos.relative(d);
            if (!comesInFirst(cell) || !hasSupport(cell)) continue;
            List<Placing.Way> all = propWays.computeIfAbsent(pos, p -> new HashMap<>())
                .computeIfAbsent(cell, c -> Placing.waysAgainst(mc.level, mc.player, pos, placing, new ItemStack(item), c));
            for (Placing.Way way : all) {
                float[] rot = rotation(eye, way.hit());
                if (way.hit().distanceTo(eye) <= reachLimit && way.fits(rot[0], rot[1])) return null;
            }
        }
        for (Direction d : Direction.values()) {
            BlockPos cell = pos.relative(d);
            if (props.contains(cell) || passedOver(cell) || !mayPut(cell) || !hasSupport(cell)) continue;
            List<Placing.Way> all = propWays.computeIfAbsent(pos, p -> new HashMap<>())
                .computeIfAbsent(cell, c -> Placing.waysAgainst(mc.level, mc.player, pos, placing, new ItemStack(item), c));
            for (Placing.Way way : all) {
                if (way.hit().distanceTo(eye) > reachLimit) continue;
                float[] rot = rotation(eye, way.hit());
                if (!(steady ? way.fits(rot[0], rot[1]) : way.fitsWell(rot[0], rot[1])) || !clearTo(eye, way.hit())) continue;
                if (!steady) {
                    double dx = way.hit().x - eye.x;
                    double dz = way.hit().z - eye.z;
                    double len = Math.max(1e-3, Math.sqrt(dx * dx + dz * dz));
                    if (!clearTo(eye.add(-dz / len * 0.15, 0, dx / len * 0.15), way.hit()) || !clearTo(eye.add(dz / len * 0.15, 0, -dx / len * 0.15), way.hit())) continue;
                }
                Job prop = propAt(cell, stand, eye, reachLimit);
                if (prop != null) {
                    propWhy.put(cell, "prop to turn " + placing.getBlock().getName().getString());
                    return prop;
                }
            }
        }
        return null;
    }

    /** Nothing in the way of a look from the eye to just short of {@code point}. */
    private boolean clearTo(Vec3 eye, Vec3 point) {
        sights++;
        Vec3 dir = point.subtract(eye);
        double len = dir.length();
        if (len < 0.1) return true;
        Vec3 end = eye.add(dir.scale((len - 0.05) / len));
        return mc.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player)).getType() == HitResult.Type.MISS;
    }

    /** A throwaway block into {@code cell}, from {@code stand}; null if it can't be done from there. */
    private Job propAt(BlockPos cell, BlockPos stand, Vec3 eye, double reachLimit) {
        BlockState state = propState();
        if (state == null || !mc.level.getBlockState(cell).canBeReplaced()) return null;
        AABB body = new AABB(stand.getX() + 0.2, stand.getY(), stand.getZ() + 0.2, stand.getX() + 0.8, stand.getY() + 1.8, stand.getZ() + 0.8);
        if (body.intersects(new AABB(cell))) return null;
        for (Placing.Way way : waysFor(cell, state, state.getBlock().asItem())) {
            if (way.hit().distanceTo(eye) > reachLimit) continue;
            float[] rot = rotation(eye, way.hit());
            if (!way.fits(rot[0], rot[1]) || !visible(eye, way)) continue;
            return new Job(Kind.PROP, cell, way, stand, 0);
        }
        return null;
    }

    /** The throwaway block props are made of: whatever pillar blocks you've most of. Null if none. */
    private BlockState propState() {
        int slot = scaffoldSlot(mc.player, false);
        if (slot < 0 || !(mc.player.getInventory().getItem(slot).getItem() instanceof BlockItem block)) return null;
        return block.getBlock().defaultBlockState();
    }

    /**
     * Whether a block stays put with nothing round it at all, as a whole block, a fence gate or a
     * slab does and a torch, a door or redstone doesn't (tried where it'd have nothing round it:
     * up at the top of the world). Never sand and the like.
     */
    private boolean standsAlone(BlockState state) {
        if (state.getBlock() instanceof net.minecraft.world.level.block.FallingBlock) return false;
        BlockPos sky = new BlockPos(mc.player.getBlockX(), mc.level.getMaxY() - 2, mc.player.getBlockZ());
        return mc.level.getBlockState(sky).isAir() && mc.level.getBlockState(sky.below()).isAir() && state.canSurvive(mc.level, sky);
    }

    /**
     * Whether a prop can come out with everything left standing: nothing next to it hangs on it
     * (sits on top of it, or is fixed to its side or underneath) or is sand and the like on top.
     */
    private boolean removable(BlockPos prop) {
        for (Direction d : Direction.values()) {
            BlockPos next = prop.relative(d);
            BlockState planned = plan.get(next);
            if (planned == null || !settled(next)) continue;
            if (d == Direction.UP && planned.getBlock() instanceof net.minecraft.world.level.block.FallingBlock) return false;
            if (hangsOn(planned, d)) return false;
        }
        return true;
    }

    /**
     * Whether {@code state}, beside a block ({@code d} the way from that block to it), needs it to
     * stay up: it sits on it, or is fixed to that side of it (a wall torch, a hook, a ladder, a
     * button), or hangs under it. Redstone, repeaters, a torch standing up and the like beside it
     * sit on what's under them instead. Whatever it can't tell, it counts as needing it.
     */
    private boolean hangsOn(BlockState state, Direction d) {
        if (standsAlone(state)) return false;
        Block block = state.getBlock();
        if (block instanceof FaceAttachedHorizontalDirectionalBlock) {
            return switch (state.getValue(FaceAttachedHorizontalDirectionalBlock.FACE)) {
                case FLOOR -> d == Direction.UP;
                case CEILING -> d == Direction.DOWN;
                case WALL -> state.getValue(FaceAttachedHorizontalDirectionalBlock.FACING) == d;
            };
        }
        boolean wall = block instanceof WallTorchBlock || block instanceof RedstoneWallTorchBlock || block instanceof TripWireHookBlock
            || block instanceof LadderBlock;
        // Fixed to the block behind it, facing away from it.
        if (wall) return state.getValue(BlockStateProperties.HORIZONTAL_FACING) == d;
        if (block instanceof DiodeBlock || block instanceof RedStoneWireBlock || block instanceof TripWireBlock || block instanceof BaseTorchBlock) {
            return d == Direction.UP;
        }
        return true;
    }

    /**
     * Whether taking a block out of {@code cell} leaves the machine be: no observer (that's in)
     * watches the cell, and nothing beside it that the change would set off (a dropper or piston
     * an observer watches; or a piston, dropper or TNT with power to it that it hasn't acted on
     * yet, which the update makes it act on). One still to go in sees nothing yet.
     */
    private boolean quietOut(BlockPos cell) {
        if (seen(cell)) return false;
        for (Direction d : Direction.values()) {
            BlockPos at = cell.relative(d);
            BlockState s = mc.level.getBlockState(at);
            boolean tnt = s.getBlock() instanceof TntBlock;
            if (!tnt && !fires(s)) continue;
            if (seen(at)) return false;
            boolean powered = mc.level.hasNeighborSignal(at) || !tnt && mc.level.hasNeighborSignal(at.above());
            boolean acted = tnt ? false : s.getBlock() instanceof PistonBaseBlock ? s.getValue(PistonBaseBlock.EXTENDED) : s.getValue(DispenserBlock.TRIGGERED);
            if (powered != acted) return false;
        }
        return true;
    }

    /** Takes out a prop that's done its job, walking over to it if need be. @return whether it's gone to. */
    private boolean takeOutProp(LocalPlayer player, long now) {
        props.removeIf(p -> mc.level.getBlockState(p).canBeReplaced());
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos p : props) {
            if (stuckProps.contains(p) || passedOver(p) || !removable(p)) continue;
            if (quietOut(p)) out.add(p);
            else leave(p, REDSTONE);
        }
        if (out.isEmpty()) return false;
        // Clearing up once it's all built, it may put a bridge or a stair in to get at one, as for its junk blocks.
        if (pending.isEmpty()) cleaning = true;
        return takeOut(player, now, out, pending.isEmpty() ? routes(player, mayBuildClearing()) : null, false, null);
    }

    /**
     * Breaks one of {@code out} (blocks the schematic doesn't have), walking to it if need be: the
     * first of them it can get at, unless a later one's a good deal nearer. With {@code keepWays}
     * (its junk blocks), not one it'd cut itself off from the rest by taking out (see {@link
     * #keepsWays}); those wait a while. @return whether it's on it.
     */
    private boolean takeOut(LocalPlayer player, long now, List<BlockPos> out, Routes given, boolean keepWays, BlockPos exitTop) {
        if (out.isEmpty()) return false;
        Routes routes = given != null ? given : routes(player, false);
        // Where to break each from, if anywhere: the nearest place to stand that sees it. The order
        // they're in counts for only a little (making sure it can't cut itself off is keepsWays'
        // job), so it takes what's near before going over for what's next in the order.
        List<Job> options = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) {
            BlockPos p = out.get(i);
            Job best = null;
            for (int dy = -4; dy <= 1; dy++) {
                for (int dx = -4; dx <= 4; dx++) {
                    for (int dz = -4; dz <= 4; dz++) {
                        BlockPos stand = p.offset(dx, dy, dz);
                        int d = routes.distance(stand);
                        double cost = d + i * (keepWays ? 2 : 15);
                        if (d < 0 || best != null && cost >= best.cost() || stand.below().equals(p)) continue;
                        // Breaking only wants the block in reach, not a face to aim at just so.
                        if (visibleFace(Vec3.atBottomCenterOf(stand).add(0, EYE, 0), p, reach.get() - BREAK_SLACK) != null) {
                            best = new Job(Kind.BREAK, p, null, stand, cost);
                        }
                    }
                }
            }
            if (best != null) options.add(best);
        }
        if (options.isEmpty()) {
            // Nowhere to reach them from here: left in for now (said so at the end, if still in).
            for (BlockPos p : out) leave(p, OUT_OF_REACH);
            return false;
        }
        options.sort(Comparator.comparingDouble(Job::cost));
        Set<BlockPos> breakable = new HashSet<>();
        for (Job j : options) breakable.add(j.target());
        int checks = 0;
        boolean kept = false;
        for (Job j : options) {
            if (keepWays) {
                if (checks++ >= WAY_CHECKS) break;
                String way = !keepsWays(j, exitTop, breakable) ? "the way to others" : !wayOutAfter(player, j, routes) ? "its way out" : null;
                if (way != null) {
                    LOGGER.debug("Schematic Builder: keeping {} in for now: {}", j.target(), way);
                    // Its way to others for now: after them.
                    passed.put(j.target(), now + 8000);
                    kept = true;
                    continue;
                }
            }
            if (cleaning && !worthBuilding(j, routes, breakable)) {
                passed.put(j.target(), now + 8000);
                kept = true;
                continue;
            }
            if (follow(j, routes, now)) {
                LOGGER.debug("Schematic Builder: taking out {} from {} (cost {}, {} it can get at)", j.target(), j.stand(), j.cost(), options.size());
                wayWaits = 0;
                return true;
            }
            leave(j.target(), OUT_OF_REACH);
        }
        // Every one it could take out the way to another, round after round: they can't all stay,
        // so they're left for a look from the ground.
        if (kept && ++wayWaits > 3) {
            for (Job j : options) if (passedOver(j.target())) leave(j.target(), OUT_OF_REACH);
            wayWaits = 0;
        }
        return false;
    }

    /** Rounds in a row that everything it could take out was the way to something else. */
    private int wayWaits;

    /** The water it's waiting on, or the clicks that set it, from {@code stand} (standing eye at {@code eye}). */
    private Job finishing(BlockPos pos, BlockState have, BlockState want, BlockPos stand, Vec3 eye, double reachLimit) {
        if (Placing.needsWater(have, want)) {
            if (count(Items.WATER_BUCKET) == 0 || !holdsWater(pos, have)) return null;
            Placing.Way face = faceOf(eye, pos, reachLimit, null);
            return face == null ? null : new Job(Kind.SOAK, pos, face, stand, 0);
        }
        if (Placing.clicks(have, want) > 0) {
            // Opened facing back into it, a gate swings round to face you.
            Direction back = have.getBlock() instanceof FenceGateBlock && !have.getValue(FenceGateBlock.OPEN)
                ? have.getValue(FenceGateBlock.FACING).getOpposite() : null;
            Placing.Way face = faceOf(eye, pos, reachLimit, back);
            return face == null ? null : new Job(Kind.CLICK, pos, face, stand, 0);
        }
        return null;
    }

    /** A bucket of water or lava into {@code pos}, against a block beside it the eye can see. */
    private Job pour(BlockPos pos, BlockState want, BlockPos stand, Vec3 eye, double reachLimit) {
        if (count(Placing.item(want)) == 0) return null;
        // Never into the space you stand in.
        AABB body = new AABB(stand.getX() + 0.2, stand.getY(), stand.getZ() + 0.2, stand.getX() + 0.8, stand.getY() + 1.8, stand.getZ() + 0.8);
        if (body.intersects(new AABB(pos))) return null;
        // Nor lava right beside you.
        if (want.getFluidState().is(FluidTags.LAVA) && (stand.distManhattan(pos) <= 1 || stand.above().distManhattan(pos) <= 1)) return null;
        Placing.Way best = null;
        for (Direction d : Direction.values()) {
            BlockPos against = pos.relative(d);
            BlockState support = mc.level.getBlockState(against);
            if (support.isAir() || support.canBeReplaced() || support.getShape(mc.level, against).isEmpty()) continue;
            Direction face = d.getOpposite();
            // Up a wall as well as its middle: into a hole from above, only the top of a wall's in sight.
            for (double h : face.getAxis().isVertical() ? new double[] {0.5} : new double[] {0.5, 0.85, 0.15}) {
                Vec3 hit = Placing.shapePoint(mc.level, against, support, face, h);
                if (hit.distanceTo(eye) > reachLimit || best != null && hit.distanceTo(eye) >= best.hit().distanceTo(eye)) continue;
                Placing.Way way = new Placing.Way(against, face, hit, null, null);
                if (visible(eye, way)) {
                    best = way;
                    break;
                }
            }
        }
        return best == null ? null : new Job(Kind.POUR, pos, best, stand, 0);
    }

    /**
     * Whether a spot's turn has come, so building doesn't set the machine off: an observer after
     * what it watches (unless that sits on top of it), a piston only with no power by it, TNT
     * only where nothing would light it, water and lava only once they can't run off, and
     * nothing that running water would wash out while it's running there.
     */
    private boolean ready(BlockPos pos, BlockState want) {
        Block block = want.getBlock();
        if (block instanceof ObserverBlock) {
            Direction facing = want.getValue(ObserverBlock.FACING);
            BlockPos at = pos.relative(facing);
            BlockState watched = plan.get(at);
            if (facing != Direction.UP && watched != null && !Placing.skipped(watched) && !settled(at)) return false;
            // Watching a dropper or a piston, which turn over with the power round them (a dropper
            // under a torch flips "triggered" the next time anything beside it changes): what's
            // planned beside it goes in first, and the throwaway blocks there come out, or that
            // change sets the observer off, and with it whatever it powers. One it's left in (out of
            // reach, say: boxed in down inside the machine) doesn't hold it up, or the build closes
            // over the observer while it waits; once the observer's in, that one stays in for good.
            if (watched != null && fires(watched)) {
                for (Direction d : Direction.values()) {
                    BlockPos next = at.relative(d);
                    BlockState beside = plan.get(next);
                    if (props.contains(next) || scaffold.contains(next)) {
                        if (leftWhy.containsKey(next)) continue;
                        waitedOnJunk.add(pos);
                        return false;
                    }
                    if (next.equals(pos) || beside == null || Placing.skipped(beside) || settled(next)) continue;
                    if (tries.getOrDefault(next, 0) < 3 && !leftEmpty.contains(next)) return false;
                }
            }
        }
        // Something that burns, near lava: the blocks of its layer beside it go in first, so there's
        // no air left beside it for lava to light (a fire there burns it, and you, if you're in it).
        if (want.ignitedByLava()) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos next = pos.relative(d);
                BlockState beside = plan.get(next);
                if (beside == null || Placing.skipped(beside) || beside.ignitedByLava() || Placing.fluid(beside) || settled(next)) continue;
                if (tries.getOrDefault(next, 0) < 3 && !leftEmpty.contains(next) && firesReach(next)) return false;
            }
        }
        if (block instanceof PistonBaseBlock && (mc.level.hasNeighborSignal(pos) || mc.level.hasNeighborSignal(pos.above()) || primed(pos))) return false;
        if (block instanceof TntBlock && mc.level.hasNeighborSignal(pos)) return false;
        // A plain block waits for an observer, piston or dropper beside it on the same layer, while
        // that has something to go against: it's set by which way you look placing it, and the
        // plain block in first can take away the only place to look from.
        if (!want.hasProperty(BlockStateProperties.FACING) && !Placing.fluid(want)) {
            for (Direction d : Direction.values()) {
                BlockPos next = pos.relative(d);
                BlockState aimed = plan.get(next);
                if (aimed == null || !aimed.hasProperty(BlockStateProperties.FACING) || next.getY() != pos.getY() || settled(next)
                    || tries.getOrDefault(next, 0) >= 3 || leftEmpty.contains(next)) continue;
                if (!waysFor(next, aimed, aimed.getBlock().asItem()).isEmpty()) return false;
            }
        }
        // Water or lava running through the spot washes string, redstone and the like straight out again.
        if (!Placing.fluid(want) && !mc.level.getFluidState(pos).isEmpty() && !want.isCollisionShapeFullBlock(mc.level, pos)
            && !(block instanceof LiquidBlockContainer)) return false;
        return !Placing.fluid(want) || fluidReady(pos, want);
    }

    private static final Direction[] FLOWS = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

    /** Observers in the plan facing up: they go in before what's on top of them (it sits on them), so they fire when that goes in. */
    private List<BlockPos> upObservers = List.of();

    /**
     * Whether a piston here could be set off by building still to come: an observer facing up
     * near it that's yet to see the block over it go in. That fires it, and through whatever it
     * powers, maybe this piston, pushing what's beside it out of place; so the piston waits.
     */
    private boolean primed(BlockPos pos) {
        for (BlockPos o : upObservers) {
            if (Math.abs(o.getX() - pos.getX()) > 3 || Math.abs(o.getY() - pos.getY()) > 3 || Math.abs(o.getZ() - pos.getZ()) > 3) continue;
            BlockPos watched = o.above();
            BlockState w = plan.get(watched);
            if (w != null && !Placing.skipped(w) && !settled(watched) && tries.getOrDefault(watched, 0) < 3 && !leftEmpty.contains(watched)) return true;
        }
        return false;
    }

    /**
     * Water or lava goes in once it can't run off: down and all round, every way it would flow is
     * shut or is meant to have it in (past the schematic's edge it's left to find its own way).
     * Lava also waits while anything that burns is still to be finished beside or under it:
     * leaves still waiting on their water would catch.
     */
    private boolean fluidReady(BlockPos pos, BlockState want) {
        Fluid type = want.getFluidState().getType();
        if (!contained(pos, type)) return false;
        if (type.isSame(Fluids.LAVA)) {
            for (BlockPos p : pending) {
                // Its own layer and the one under it: the one over it isn't started till this one's done.
                if (Math.abs(p.getX() - pos.getX()) > 2 || Math.abs(p.getZ() - pos.getZ()) > 2 || p.getY() > pos.getY() || p.getY() < pos.getY() - 1) continue;
                if (plan.get(p).ignitedByLava()) return false;
            }
        }
        return true;
    }

    /**
     * Whether {@code type} put in at {@code pos} would stay put, or only run where the schematic
     * has it: every way it would flow from there is shut, and so is every way out of the blocks of
     * it the schematic has it running into (past the schematic's edge it's left to find its own way).
     */
    private boolean contained(BlockPos pos, Fluid type) {
        Set<BlockPos> seen = new HashSet<>();
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        seen.add(pos);
        queue.add(pos);
        while (!queue.isEmpty()) {
            BlockPos at = queue.poll();
            for (Direction d : FLOWS) {
                BlockPos next = at.relative(d);
                if (!bounds.contains(next)) continue;
                BlockState planned = plan.get(next);
                if (planned != null && planned.getBlock() instanceof LiquidBlock && planned.getFluidState().getType().isSame(type)) {
                    // Where it's meant to run: what holds it in there has to be in too.
                    if (seen.add(next) && seen.size() < 512) queue.add(next);
                    continue;
                }
                if (props.contains(next) || scaffold.contains(next) || planned != null && !settled(next)) return false;
                if (!stopsFlow(mc.level.getBlockState(next))) return false;
            }
        }
        return true;
    }

    /** Water put in a block stays in it if it's a whole block; otherwise it could run out, as from a bucket of it there. */
    private boolean holdsWater(BlockPos pos, BlockState have) {
        return Block.isShapeFullBlock(have.getCollisionShape(mc.level, pos)) || contained(pos, Fluids.WATER);
    }

    /** Whether running water or lava can't get into a block, the way the game decides it. */
    private static boolean stopsFlow(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof LiquidBlockContainer) return true;
        if (block instanceof LiquidBlock) return false;
        return state.blocksMotion() || block instanceof DoorBlock || state.is(BlockTags.SIGNS) || state.is(net.minecraft.world.level.block.Blocks.LADDER)
            || state.is(net.minecraft.world.level.block.Blocks.SUGAR_CANE) || state.is(net.minecraft.world.level.block.Blocks.BUBBLE_COLUMN);
    }

    /** A block in a planned spot that has to come out first: solid, breakable, and not what's wanted. */
    private boolean inTheWay(BlockPos pos, BlockState have, BlockState want) {
        if (have.isAir() || have.canBeReplaced() || Placing.matches(have, want)) return false;
        // A stand-in put in this time stays: there's nothing better to hand.
        Block standing = stood.get(pos);
        if (standing != null && have.is(standing)) return false;
        // Half a double slab is the start of it, not in the way.
        if (have.getBlock() == want.getBlock() && have.getBlock() instanceof SlabBlock) return false;
        return have.getDestroySpeed(mc.level, BlockPos.ZERO) >= 0;
    }

    private List<Placing.Way> waysFor(BlockPos pos, BlockState want, Item item) {
        return ways.computeIfAbsent(pos, p -> new HashMap<>()).computeIfAbsent(want, w -> Placing.ways(mc.level, mc.player, pos, w, new ItemStack(item)));
    }

    /** Forgets how to place anything next to a spot that just changed: what it can be placed against has. */
    private void changedAt(BlockPos pos) {
        ways.remove(pos);
        propWays.remove(pos);
        for (Direction d : Direction.values()) {
            ways.remove(pos.relative(d));
            propWays.remove(pos.relative(d));
        }
        scanIn = 0;
    }

    /** Whether a look a little to each side of the way's spot, on its face, lands there too. */
    private boolean steadilyVisible(Vec3 eye, Placing.Way way) {
        Direction face = way.face();
        Direction.Axis[] across = switch (face.getAxis()) {
            case X -> new Direction.Axis[] {Direction.Axis.Y, Direction.Axis.Z};
            case Y -> new Direction.Axis[] {Direction.Axis.X, Direction.Axis.Z};
            case Z -> new Direction.Axis[] {Direction.Axis.X, Direction.Axis.Y};
        };
        for (Direction.Axis axis : across) {
            for (double off : new double[] {-0.12, 0.12}) {
                Vec3 shifted = way.hit().add(axis == Direction.Axis.X ? off : 0, axis == Direction.Axis.Y ? off : 0, axis == Direction.Axis.Z ? off : 0);
                if (!visible(eye, new Placing.Way(way.against(), face, shifted, null, null))) return false;
            }
        }
        return true;
    }

    /** Whether the way's spot is in sight from 0.15 either side of the eye, across the line of sight. */
    private boolean visibleBeside(Vec3 eye, Placing.Way way) {
        double dx = way.hit().x - eye.x;
        double dz = way.hit().z - eye.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        double px = len < 1e-3 ? 0.15 : -dz / len * 0.15;
        double pz = len < 1e-3 ? 0 : dx / len * 0.15;
        return visible(eye.add(px, 0, pz), way) && visible(eye.add(-px, 0, -pz), way);
    }

    /** What a look from the eye at the way's spot lands on instead, for the debug log. */
    private String seenInstead(Vec3 eye, Placing.Way way) {
        Vec3 into = way.hit().subtract(way.face().getStepX() * 0.02, way.face().getStepY() * 0.02, way.face().getStepZ() * 0.02);
        BlockHitResult hit = mc.level.clip(new ClipContext(eye, into, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos().toShortString() + " " + hit.getDirection() : "nothing";
    }

    /** Points across a face, each way, that {@link #steadyPoint} looks over. */
    private static final int STEADY_GRID = 9;

    /**
     * Where on the way's face to look from {@code eye}: of a grid over the face, a point the look
     * lands on (and, placing {@code placing}, one that puts it in at {@code target} the right way
     * round, turned as that look turns you) with the most such points round it, so a look a hair
     * off still does. A look that only just clears an edge (up past the top of a wall, say) is one
     * a hair off lands on the edge instead. The way's own point if there's none.
     */
    private Vec3 steadyPoint(Vec3 eye, Placing.Way way, BlockState placing, Item item, BlockPos target) {
        BlockPos against = way.against();
        Direction face = way.face();
        net.minecraft.world.phys.shapes.VoxelShape shape = mc.level.getBlockState(against).getShape(mc.level, against);
        AABB box = shape.isEmpty() ? new AABB(0, 0, 0, 1, 1, 1) : shape.bounds();
        Direction.Axis across = face.getAxis() == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
        Direction.Axis up = face.getAxis().isVertical() ? Direction.Axis.Z : Direction.Axis.Y;
        double plane = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? box.max(face.getAxis()) : box.min(face.getAxis());
        int n = STEADY_GRID;
        boolean[][] good = new boolean[n][n];
        Vec3[][] points = new Vec3[n][n];
        BlockState want = placing == null ? null : Placing.nextStep(placing, mc.level.getBlockState(target));
        ItemStack stack = item == null ? ItemStack.EMPTY : new ItemStack(item);
        LocalPlayer player = mc.player;
        float yaw = player.getYRot();
        float pitch = player.getXRot();
        float yawO = player.yRotO;
        float pitchO = player.xRotO;
        try {
            for (int i = 0; i < n; i++) {
                for (int k = 0; k < n; k++) {
                    double[] at = new double[3];
                    at[face.getAxis().ordinal()] = plane;
                    at[across.ordinal()] = box.min(across) + (box.max(across) - box.min(across)) * (i + 1) / (n + 1);
                    at[up.ordinal()] = box.min(up) + (box.max(up) - box.min(up)) * (k + 1) / (n + 1);
                    Vec3 p = new Vec3(against.getX() + at[0], against.getY() + at[1], against.getZ() + at[2]);
                    points[i][k] = p;
                    if (p.distanceTo(eye) > reach.get() || !visible(eye, new Placing.Way(against, face, p, null, null))) continue;
                    if (want != null) {
                        // Turned as the look would turn you, for what's set by which way you face or look.
                        float[] rot = rotation(eye, p);
                        player.setYRot(rot[0]);
                        player.setXRot(rot[1]);
                        player.yRotO = rot[0];
                        player.xRotO = rot[1];
                        BlockState got = Placing.result(mc.level, player, new BlockHitResult(p, face, against, false), stack, target);
                        if (got == null || !Placing.matches(got, want)) continue;
                    }
                    good[i][k] = true;
                }
            }
        } finally {
            player.setYRot(yaw);
            player.setXRot(pitch);
            player.yRotO = yawO;
            player.xRotO = pitchO;
        }
        Vec3 best = way.hit();
        int bestScore = 0;
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < n; k++) {
                if (!good[i][k]) continue;
                int score = 0;
                for (int di = -1; di <= 1; di++) {
                    for (int dk = -1; dk <= 1; dk++) {
                        int a = i + di;
                        int b = k + dk;
                        if (a >= 0 && a < n && b >= 0 && b < n && good[a][b]) score++;
                    }
                }
                // As steady as the best so far: the one nearer where it was planned to look.
                if (score > bestScore || score == bestScore && points[i][k].distanceToSqr(way.hit()) < best.distanceToSqr(way.hit())) {
                    best = points[i][k];
                    bestScore = score;
                }
            }
        }
        return best;
    }

    /** Whether looking at the way's spot from the eye really lands on that face of that block. */
    private boolean visible(Vec3 eye, Placing.Way way) {
        sights++;
        Vec3 into = way.hit().subtract(way.face().getStepX() * 0.02, way.face().getStepY() * 0.02, way.face().getStepZ() * 0.02);
        BlockHitResult hit = mc.level.clip(new ClipContext(eye, into, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(way.against()) && hit.getDirection() == way.face();
    }

    /** The middle of a face of {@code pos} the eye can see and reach, or null. */
    private Vec3 visibleFace(Vec3 eye, BlockPos pos, double reachLimit) {
        Placing.Way face = faceOf(eye, pos, reachLimit, null);
        return face == null ? null : face.hit();
    }

    /**
     * The nearest face of the block at {@code pos} the eye can see and reach, on the block's real
     * shape; none that'd have you facing {@code notFacing} while you look at it. Null if none.
     */
    private Placing.Way faceOf(Vec3 eye, BlockPos pos, double reachLimit, Direction notFacing) {
        BlockState state = mc.level.getBlockState(pos);
        Placing.Way best = null;
        double bestDist = Double.MAX_VALUE;
        boolean bestSteady = false;
        for (Direction face : Direction.values()) {
            Vec3 point = Placing.shapePoint(mc.level, pos, state, face, 0.5);
            double dist = point.distanceTo(eye);
            if (dist > reachLimit) continue;
            if (notFacing != null && Direction.fromYRot(rotation(eye, point)[0]) == notFacing) continue;
            Vec3 into = point.subtract(face.getStepX() * 0.02, face.getStepY() * 0.02, face.getStepZ() * 0.02);
            BlockHitResult hit = mc.level.clip(new ClipContext(eye, into, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos)) continue;
            // A face a look a hair either side of still lands on beats a nearer one only just in sight
            // (the edge of a repeater, say, where a grazing look tips onto the one beside it).
            Placing.Way way = new Placing.Way(pos, face, point, null, null);
            boolean steady = steadilyVisible(eye, way);
            if (best != null && (bestSteady && !steady || bestSteady == steady && dist >= bestDist)) continue;
            best = way;
            bestDist = dist;
            bestSteady = steady;
        }
        return best;
    }

    private static float[] rotation(Vec3 eye, Vec3 point) {
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        return new float[] {Mth.wrapDegrees(yaw), pitch};
    }

    private double angleTo(LocalPlayer player, Job j) {
        Vec3 point = j.way() != null ? j.way().hit() : Vec3.atCenterOf(j.target());
        float[] rot = rotation(player.getEyePosition(), point);
        return Math.abs(Mth.wrapDegrees(rot[0] - player.getYRot())) + Math.abs(rot[1] - player.getXRot());
    }

    /**
     * The lowest layer with anything left on it that hasn't been tried three times over. Spots set
     * aside for a moment still count: they get their next try before the layer above is started.
     */
    private int currentLayer() {
        for (BlockPos pos : pending) {
            if (tries.getOrDefault(pos, 0) < 3) return pos.getY();
        }
        return Integer.MIN_VALUE;
    }

    /** Whether a spot's on the layer being built (always, with Layer by layer off). */
    private boolean onLayer(BlockPos pos) {
        return !layered.get() || layer == Integer.MIN_VALUE || pos.getY() == layer;
    }

    private boolean passedOver(BlockPos pos) {
        Long until = passed.get(pos);
        return until != null && System.currentTimeMillis() < until;
    }

    /** Leaves a spot alone for a while: it couldn't be done, maybe later it can. */
    private void pass(BlockPos pos, long ms) {
        passed.put(pos, System.currentTimeMillis() + ms);
        int n = tries.merge(pos, 1, Integer::sum);
        BlockState want = plan.get(pos);
        if (n == 3 && want != null) {
            LOGGER.debug("Schematic Builder: giving up on {} {} for now: ready {}, {} ways to place it", pos, want, ready(pos, want),
                Placing.fluid(want) ? -1 : waysFor(pos, want, want.getBlock().asItem()).size());
            explain(pos);
        }
    }

    /** The routes worked out last, for {@link #explain}; and the last "nothing doable" put in the debug log. */
    private Routes lastRoutes;
    private String lastNothing;

    /**
     * For the debug log, when a spot's given up on: what's round it, and for every place to stand
     * near enough to reach it, what was wrong with it (no way there and why, or the job from there
     * and why not: out of reach, the look not right for it, something else in sight first).
     */
    private void explain(BlockPos pos) {
        Routes routes = lastRoutes;
        if (!LOGGER.isDebugEnabled() || routes == null || plan.get(pos) == null) return;
        double limit = reach.get() - PLAN_SLACK;
        Map<String, Integer> tally = new java.util.TreeMap<>();
        List<BlockPos> near = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-4, -4, -4), pos.offset(4, 3, 4))) near.add(p.immutable());
        near.sort(Comparator.comparingDouble(p -> p.distSqr(pos)));
        List<String> reached = new ArrayList<>();
        for (BlockPos stand : near) {
            Vec3 eye = Vec3.atBottomCenterOf(stand).add(0, EYE, 0);
            if (Vec3.atCenterOf(pos).distanceTo(eye) > limit + 0.9 || !Paths.passable(mc.level, stand) || !Paths.passable(mc.level, stand.above())) continue;
            String why;
            if (routes.distance(stand) < 0) {
                // Nowhere to stand in mid-air that no route builds a floor under: not worth a mention.
                if (mc.level.getBlockState(stand.below()).getCollisionShape(mc.level, stand.below()).isEmpty()) continue;
                why = !rules.mayStand(stand) ? (stillToDo(stand.above()) ? "no route: head in the layer" : "no route: a trigger")
                    : !Paths.standable(mc.level, stand) ? "no route: by lava or a bad floor" : "no route";
            } else {
                why = whyNotFrom(pos, stand, eye, limit);
                if (reached.size() < 10) reached.add(stand.toShortString() + " (" + routes.distance(stand) + ") " + why);
                why = why.replaceAll(":.*", "");
            }
            tally.merge(why, 1, Integer::sum);
        }
        StringBuilder round = new StringBuilder();
        for (Direction d : Direction.values()) {
            BlockState s = mc.level.getBlockState(pos.relative(d));
            round.append(d.getName()).append(' ').append(s.isAir() ? "air" : net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath())
                .append(scaffold.contains(pos.relative(d)) ? " (junk)" : "").append(", ");
        }
        LOGGER.debug("Schematic Builder: why not {} (routes from {}): round it {}places to stand {}; reached, nearest first: {}",
            pos, routes.start, round, tally, reached);
    }

    /** For {@link #explain}: why the spot can't be done from a place to stand a route gets to. */
    private String whyNotFrom(BlockPos pos, BlockPos stand, Vec3 eye, double limit) {
        Job j = jobAt(pos, stand, eye, limit);
        if (j != null) return "would do: " + j.kind() + (j.way() == null ? "" : " via " + j.way().against().toShortString() + " " + j.way().face());
        BlockState want = plan.get(pos);
        BlockState below = plan.get(pos.below());
        if (below != null && !Placing.skipped(below) && !settled(pos.below()) && !lastOfAll(pos.below(), below)) return "what's under it isn't in";
        if (Placing.matches(mc.level.getBlockState(pos), want)) return "other: only its water or clicks left";
        if (!ready(pos, want)) return "not ready";
        if (Placing.fluid(want)) {
            if (count(Placing.item(want)) == 0) return "no bucket of it";
            if (want.getFluidState().is(FluidTags.LAVA) && (stand.distManhattan(pos) <= 1 || stand.above().distManhattan(pos) <= 1)) return "lava beside you";
            int far = 0, hidden = 0;
            for (Direction d : Direction.values()) {
                BlockPos against = pos.relative(d);
                BlockState support = mc.level.getBlockState(against);
                if (support.isAir() || support.canBeReplaced() || support.getShape(mc.level, against).isEmpty()) continue;
                Vec3 hit = Placing.shapePoint(mc.level, against, support, d.getOpposite(), 0.5);
                if (hit.distanceTo(eye) > limit) far++;
                else if (!visible(eye, new Placing.Way(against, d.getOpposite(), hit, null, null))) hidden++;
            }
            return (hidden > 0 ? "hidden" : "far") + ": pour, far " + far + ", hidden " + hidden;
        }
        BlockState placing = toPlace(pos, want);
        if (placing == null) return "nothing to place";
        int far = 0, look = 0, hidden = 0;
        String sees = "";
        for (Placing.Way way : waysFor(pos, placing, placing.getBlock().asItem())) {
            float[] rot = rotation(eye, way.hit());
            if (way.hit().distanceTo(eye) > limit) far++;
            else if (!way.fitsWell(rot[0], rot[1])) look++;
            else if (!visible(eye, way)) {
                hidden++;
                if (sees.isEmpty()) sees = ", " + way.against().toShortString() + " " + way.face() + " sees " + seenInstead(eye, way);
            }
        }
        return (hidden > 0 ? "hidden" : look > 0 ? "look" : far > 0 ? "far" : "no ways") + ": far " + far + ", look " + look + ", hidden " + hidden + sees;
    }

    // ---- Doing it

    private void begin(Job j) {
        job = j;
        aimed = false;
        useAfter = 0;
        go(switch (j.kind()) {
            case BREAK -> Phase.BREAK;
            case PLACE, PROP -> Phase.PLACE;
            default -> Phase.USE;
        });
    }

    private void walk(LocalPlayer player, long now) {
        if (path == null || pathIndex >= path.size()) {
            // Arrived: onto the middle of the spot first, where it was planned from; the last bit
            // crouched, slow enough to stop right on it.
            if (arrivedAt == 0) arrivedAt = now;
            Vec3 middle = Vec3.atBottomCenterOf(job.stand());
            double off = Math.hypot(middle.x - player.getX(), middle.z - player.getZ());
            if (off > 0.08 && now - arrivedAt < 2500) {
                steering = Steer.toward(player, middle, false, off < 0.45 || exposed(job.stand()));
                return;
            }
            steering = Input.EMPTY;
            if (!player.onGround()) return;
            if (Math.abs(player.getY() - job.stand().getY()) > 0.6) {
                LOGGER.debug("Schematic Builder: got to {} at the wrong height ({})", job.stand(), player.position());
                pass(job.target(), 3000);
                nextActAt = now + 250;
                go(Phase.IDLE);
                return;
            }
            // The job from where you really are; if it doesn't work after all, leave it a while.
            if (job.kind() == Kind.DOWN) {
                // Down a way of its own: on the ground now, what was out of reach gets another look.
                if (clearBehind) {
                    clearBehind = false;
                    cameDown = true;
                    onGroundForGood = true;
                }
                if (onScaffold(player)) startDescend();
                else go(Phase.IDLE);
                return;
            }
            Vec3 eye = player.position().add(0, EYE, 0);
            Job j = job.kind() == Kind.FILL ? fillJob(player, null)
                : job.kind() == Kind.PROP ? propAt(job.target(), player.blockPosition(), eye, reach.get())
                : !plan.containsKey(job.target()) ? (visibleFace(eye, job.target(), reach.get()) == null ? null
                    : new Job(Kind.BREAK, job.target(), null, player.blockPosition(), 0))
                : jobAt(job.target(), player.blockPosition(), eye, reach.get(), true);
            if (j == null && job.kind() == Kind.FILL) {
                go(Phase.IDLE);
                nextActAt = now + 1000;
                return;
            }
            if (j != null) {
                begin(j);
            } else {
                Placing.Way w = job.way();
                LOGGER.debug("Schematic Builder: couldn't do {} {} from {} after all (planned from {} via {} {} at {}: {} away, looks at {}; ready {}, have {})",
                    job.kind(), job.target(), player.position(), job.stand(), w == null ? null : w.against(), w == null ? null : w.face(),
                    w == null ? null : w.hit(), w == null ? -1 : w.hit().distanceTo(eye), w == null ? "-" : seenInstead(eye, w),
                    plan.containsKey(job.target()) && ready(job.target(), plan.get(job.target())), mc.level.getBlockState(job.target()));
                pass(job.target(), 10_000);
                nextActAt = now + 250;
                go(Phase.IDLE);
            }
            return;
        }
        BlockPos node = path.get(pathIndex);
        BlockPos from = path.get(pathIndex - 1);
        Routes.Move move = moves.get(pathIndex);
        // A pillar to put up or mine down on the way: all of it in one go.
        if (move == Routes.Move.PILLAR || move == Routes.Move.DIG) {
            steering = Input.EMPTY;
            if (!player.onGround()) return;
            int n = 1;
            while (pathIndex + n < path.size() && moves.get(pathIndex + n) == move) n++;
            if (move == Routes.Move.PILLAR) {
                pillarWhy = "pillar" + (cleaning ? ", clearing up" : " for " + job.kind());
                startPillar(from, n, true);
                pillarClear = climbsOut(from, n);
                if (pillarClear) LOGGER.debug("Schematic Builder: climbing out from {} for good, {} high, taking the pillar out under it", from, n);
                // A ladder of scaffolding, its top block junk, where it'd save time: not one that comes out under it as it climbs.
                pillarLadder = !pillarClear && ladderFits(player, from, n);
            } else {
                // Down to the last spot of the dig (a ladder's sunk through all the way in one).
                startDig(path.get(pathIndex + n - 1).getY(), n);
            }
            return;
        }
        // A block to put out to walk onto, unless it's there already.
        if (move == Routes.Move.BRIDGE && mc.level.getBlockState(node.below()).canBeReplaced()) {
            steering = Input.EMPTY;
            if (!player.onGround()) return;
            aimed = false;
            go(Phase.BRIDGE);
            return;
        }
        Vec3 target = Vec3.atBottomCenterOf(node);
        double horizontal = Math.hypot(target.x - player.getX(), target.z - player.getZ());
        boolean up = node.getY() > player.getY() + 0.5;
        // A spot lower down isn't got to till you've dropped onto it: right beside it, still up on
        // the edge (as at the end of a step down), you're not.
        boolean above = node.getY() < player.getY() - 0.5;
        if (horizontal < 0.3 && (!up || player.getY() > node.getY() - 0.2) && !above) {
            pathIndex++;
            lastMoveAt = now;
            // On the way down for good: the junk block it's just stepped off comes out behind it.
            BlockPos behind = from.below();
            if (clearBehind && player.onGround() && scaffold.contains(behind) && !behind.equals(node.below()) && quietOut(behind)) {
                resumeWalk = job;
                job = new Job(Kind.BREAK, behind.immutable(), null, node.immutable(), 0);
                aimed = false;
                go(Phase.BREAK);
            }
            return;
        }
        // Look on ahead on a long walk, at the job once it's close; and back the way it came when
        // a bridge is next, so it's turned round already when it gets to the edge.
        int remaining = path.size() - 1 - pathIndex;
        boolean bridgingNext = pathIndex + 1 < path.size() && moves.get(pathIndex + 1) == Routes.Move.BRIDGE;
        Vec3 look = bridgingNext ? backLook(node, path.get(pathIndex + 1))
            : remaining > 2 ? Vec3.atBottomCenterOf(path.get(Math.min(path.size() - 1, pathIndex + 2))).add(0, 1.5, 0)
            : job.way() != null ? job.way().hit() : Vec3.atCenterOf(job.target());
        boolean ahead = remaining > 2 && !bridgingNext;
        // Corner to corner, or crouched along an edge: looking the way it's going, so the keys
        // walk it in a straight line and not up to a 45-degree slant off it.
        boolean diagonal = node.getX() != from.getX() && node.getZ() != from.getZ();
        boolean careful = !bridgingNext && (diagonal || node.getY() >= from.getY() && (exposed(from) || exposed(node)));
        if (careful) {
            look = target.add((node.getX() - from.getX()) * 2.0, 1.4, (node.getZ() - from.getZ()) * 2.0);
            ahead = false;
        }
        if (!lookingAhead || !ahead) lookAt(look);
        else aim.track(look);
        lookingAhead = ahead;

        // Stuck is not getting any nearer the next spot (jumping on the spot under a ceiling, say,
        // moves you but gets you nowhere), not merely standing still. Counted afresh for each spot,
        // and from setting off again (a new route, or on after a bridge or a pillar): a count left
        // over from the last walk would have it stuck the moment it starts.
        if (nodeFor != pathIndex || lastMoveAt > nodeProgressAt) {
            nodeFor = pathIndex;
            nodeBest = Double.MAX_VALUE;
            nodeProgressAt = now;
        }
        double away = player.position().distanceTo(target);
        if (away < nodeBest - 0.1) {
            nodeBest = away;
            nodeProgressAt = now;
        }
        if (now - nodeProgressAt > WALK_STUCK) {
            LOGGER.debug("Schematic Builder: stuck walking to {} at {} (next {} by {}, from {}; route {}; on the ground {}, crouching {}, keys {}, there {} on {}, head {})",
                job.stand(), player.position(), node, move, from, moves, player.onGround(), player.isCrouching(), steering,
                mc.level.getBlockState(node), mc.level.getBlockState(node.below()), mc.level.getBlockState(node.above()));
            // That step's no good for now: the next route goes another way.
            stuckSteps.put(List.of(from.immutable(), node.immutable()), now + 30_000);
            pass(job.target(), 15_000);
            nextActAt = now + 250;
            go(Phase.IDLE);
            return;
        }
        // Crouched where a slip would hurt, as crouching won't walk you off an edge; not when it
        // means to drop down.
        boolean sneak = node.getY() >= from.getY() && (exposed(from) || exposed(node));
        // A hop to get unstuck, but never crouched at an edge: in the air crouching holds nothing,
        // and a hop there with a key down goes over it. Nor going down more than a step: a hop off
        // the top of a three-block drop is a fall of over four, and that hurts.
        boolean stuck = now - nodeProgressAt > 900;
        boolean hop = stuck && !sneak && node.getY() >= from.getY() - 1 && now - lastJumpAt > 800;
        boolean jump = player.onGround() && (up && horizontal < 1.4 || hop);
        if (jump) lastJumpAt = now;
        steering = Steer.toward(player, target, jump, sneak);
    }

    /** Looks at {@code point}: a fresh turn if it's somewhere new, otherwise just keeping on it. */
    private void lookAt(Vec3 point) {
        Vec3 at = aim.target();
        if (at == null || at.distanceToSqr(point) > 0.25) aim.setTarget(point);
        else aim.track(point);
    }

    /**
     * Somewhere a slip would hurt: lava, or a drop of two blocks or more, just beside it (corners
     * too). Crouched there, it can't walk off.
     */
    private boolean exposed(BlockPos feet) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPos n = feet.offset(dx, 0, dz);
                // Lava under a block beside you is no danger: you can't step in there.
                if (lavaIn(n) || Paths.passable(mc.level, n) && lavaIn(n.below())) return true;
                if (Paths.passable(mc.level, n) && Paths.passable(mc.level, n.below()) && Paths.passable(mc.level, n.below(2))) return true;
            }
        }
        return false;
    }

    private boolean lavaIn(BlockPos pos) {
        return mc.level.getFluidState(pos).is(FluidTags.LAVA);
    }

    private boolean burning(BlockPos pos) {
        return mc.level.getBlockState(pos).getBlock() instanceof BaseFireBlock;
    }

    /** Whether you'd catch fire standing with your feet in {@code feet}: fire there, or it could start there. */
    private boolean catchesFire(BlockPos feet) {
        return lightable(feet) || lightable(feet.above());
    }

    /** An empty cell that's on fire, or that fire could start in: something lava lights beside it, in fire's reach. */
    private boolean lightable(BlockPos cell) {
        BlockState state = mc.level.getBlockState(cell);
        if (state.getBlock() instanceof BaseFireBlock) return true;
        if (!state.isAir()) return false;
        for (Direction d : Direction.values()) {
            if (mc.level.getBlockState(cell.relative(d)).ignitedByLava()) return firesReach(cell);
        }
        return false;
    }

    /**
     * Whether fire could get to {@code cell}, were there something that burns beside it: lava now
     * and then lights the air a block over it (or a block either way), or two over (two either
     * way); a fire spreads to a block either way of it, from one under it to four over.
     */
    private boolean firesReach(BlockPos cell) {
        for (int up = 1; up <= 2; up++) {
            for (int dx = -up; dx <= up; dx++) {
                for (int dz = -up; dz <= up; dz++) {
                    if (lavaIn(cell.offset(dx, -up, dz))) return true;
                }
            }
        }
        for (BlockPos p : BlockPos.betweenClosed(cell.offset(-1, -4, -1), cell.offset(1, 1, 1))) {
            if (burning(p)) return true;
        }
        return false;
    }

    /** Where to look making for the edge to bridge from {@code from} to {@code to}: down at the back of the block underfoot. */
    private static Vec3 backLook(BlockPos from, BlockPos to) {
        int dx = Integer.signum(to.getX() - from.getX());
        int dz = Integer.signum(to.getZ() - from.getZ());
        return new Vec3(from.getX() + 0.5 - dx * 0.45, from.getY(), from.getZ() + 0.5 - dz * 0.45);
    }

    /**
     * Puts a throwaway block out beside the one you stand on, to walk onto, the way a player
     * bridges: crouched (so there's no falling off), turned back the way it came, out over the
     * edge as far as crouching lets it go, then a look down at the side of the block underfoot.
     * Or a step lower, against the side of the block under that, to drop onto: a stair down.
     */
    private void bridge(LocalPlayer player, long now) {
        BlockPos from = path.get(pathIndex - 1);
        BlockPos to = path.get(pathIndex);
        BlockPos cell = to.below();
        if (!mc.level.getBlockState(cell).canBeReplaced()) {
            // In (or something else is): on along the route.
            lastMoveAt = now;
            go(Phase.WALK);
            return;
        }
        if (now - phaseSince > BRIDGE_TIMEOUT) {
            LOGGER.debug("Schematic Builder: gave up bridging to {}: crouching {}, at {}, looking at {}", cell, player.isCrouching(), player.position(),
                mc.hitResult instanceof BlockHitResult b ? b.getBlockPos() + " " + b.getDirection() : mc.hitResult);
            pass(job.target(), 15_000);
            steering = Input.EMPTY;
            go(Phase.IDLE);
            return;
        }
        int slot = scaffoldSlot(player, true);
        if (slot < 0) {
            pass(job.target(), 20_000);
            steering = Input.EMPTY;
            go(Phase.IDLE);
            return;
        }
        if (player.getInventory().getSelectedSlot() != slot) player.getInventory().setSelectedSlot(slot);
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        // Out level with the block underfoot, or a step down (stairs down a wall): against the side
        // of the block under that.
        BlockPos floor = to.getY() < from.getY() ? from.below(2) : from.below();
        // How far out past the middle of the block underfoot: crouching stops you about 0.3 over the edge.
        double out = (player.getX() - (from.getX() + 0.5)) * dx + (player.getZ() - (from.getZ() + 0.5)) * dz;
        boolean there = out > 0.65;
        steering = Steer.toward(player, Vec3.atBottomCenterOf(from).add(dx * 0.9, 0, dz * 0.9), false, true);
        if (there && !aimed) {
            bridgeAim = bridgeAim(player, cell, floor, slot);
            aimed = true;
        }
        lookAt(there ? bridgeAim : backLook(from, to));
        if (!there || !player.isCrouching() || !player.onGround()) return;
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
        if (Placing.result(mc.level, player, hit, player.getMainHandItem(), cell) == null) return;
        if (mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit).consumesAction()) {
            player.swing(InteractionHand.MAIN_HAND);
            scaffold.add(cell.immutable());
            putJunk(cell, (to.getY() < from.getY() ? "stair" : "bridge") + (cleaning ? ", clearing up" : " for " + job.kind()));
            usedJunk(hit.getBlockPos());
            if (cleaning) cleanupBlocks++;
            changedAt(cell);
        }
    }

    /** Where to look from the edge to put the junk block out. */
    private Vec3 bridgeAim;

    /**
     * Where to look, crouched at the edge, to put a junk block in {@code cell}: the steadiest spot on
     * a face round it in sight, the side of {@code floor} (the block underfoot, or the one under that
     * for a step down) first, failing that another (the wall beside it, going on down a wall).
     */
    private Vec3 bridgeAim(LocalPlayer player, BlockPos cell, BlockPos floor, int slot) {
        Vec3 eye = player.position().add(0, EYE, 0);
        Placing.Way pick = null;
        BlockState junk = null;
        if (player.getInventory().getItem(slot).getItem() instanceof BlockItem block) {
            junk = block.getBlock().defaultBlockState();
            for (Placing.Way way : waysFor(cell, junk, block)) {
                if (way.hit().distanceTo(eye) > reach.get() || !visible(eye, way)) continue;
                if (pick == null || way.against().equals(floor)) pick = way;
                if (way.against().equals(floor)) break;
            }
        }
        if (pick == null) return Placing.shapePoint(mc.level, floor, mc.level.getBlockState(floor), Direction.getApproximateNearest(cell.getX() - floor.getX(), 0, cell.getZ() - floor.getZ()), 0.5);
        return steadyPoint(eye, pick, junk, junk.getBlock().asItem(), cell);
    }

    private void place(LocalPlayer player, long now) {
        Placing.Way way = job.way();
        boolean prop = job.kind() == Kind.PROP;
        BlockState wanted = prop ? propState() : plan.get(job.target());
        BlockState placing = prop ? wanted : toPlace(job.target(), wanted);
        if (placing == null) {
            pass(job.target(), 20_000);
            go(Phase.IDLE);
            return;
        }
        Item item = placing.getBlock().asItem();
        // Crouched, so clicking a chest or a door places against it instead of opening it.
        steering = new Input(false, false, false, false, false, true, false);
        // Stopped a hair into the spot (short of the middle of where it stands), the block can't go in
        // where you are: edged back out of it first, crouched.
        net.minecraft.world.phys.shapes.VoxelShape solid = placing.getCollisionShape(mc.level, job.target());
        BlockPos at = job.target();
        if (!solid.isEmpty() && player.getBoundingBox().intersects(solid.bounds().move(at.getX(), at.getY(), at.getZ()))) {
            Vec3 middle = Vec3.atCenterOf(at);
            steering = Steer.toward(player, player.position().add(player.getX() - middle.x, 0, player.getZ() - middle.z), false, true);
        }
        if (!aimed) {
            // At the steadiest spot on that face from where it really is, crouched.
            aim.setTarget(steadyPoint(player.position().add(0, EYE, 0), way, placing, item, job.target()));
            aimed = true;
        }
        int slot = hotbar(item);
        if (slot < 0) {
            pass(job.target(), 20_000);
            go(Phase.IDLE);
            return;
        }
        if (player.getInventory().getSelectedSlot() != slot) {
            player.getInventory().setSelectedSlot(slot);
            return;
        }
        if (prop ? !mc.level.getBlockState(job.target()).canBeReplaced() : settled(job.target())) {
            done();
            return;
        }
        if (now - phaseSince > PLACE_TIMEOUT) {
            LOGGER.debug("Schematic Builder: gave up placing at {}: crouching {}, holding {}, looking at {} (which would place {}), at {}, aiming at {}",
                job.target(), player.isCrouching(), player.getMainHandItem(),
                mc.hitResult instanceof BlockHitResult b ? b.getBlockPos() + " " + b.getDirection() : mc.hitResult,
                mc.hitResult instanceof BlockHitResult b ? Placing.result(mc.level, player, b, player.getMainHandItem(), job.target()) : null,
                player.position(), aim.target());
            pass(job.target(), 15_000);
            go(Phase.IDLE);
            return;
        }
        // A moment crouched first, so the server knows before the click.
        if (now - phaseSince < 120 || !player.isCrouching()) return;
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
        // Whatever the crosshair's on, as long as clicking it (turned as you are) puts the right block
        // in the right place: a face next to the planned one does as well.
        BlockState want = Placing.nextStep(placing, mc.level.getBlockState(job.target()));
        BlockState got = Placing.result(mc.level, player, hit, player.getMainHandItem(), job.target());
        if (got == null || !Placing.matches(got, want)) return;
        if (mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit).consumesAction()) {
            player.swing(InteractionHand.MAIN_HAND);
            usedJunk(hit.getBlockPos());
            if (prop) {
                props.add(job.target().immutable());
                putJunk(job.target(), propWhy.getOrDefault(job.target(), "prop"));
            } else {
                placedCount++;
            }
            if (!prop && placing != wanted) {
                stood.put(job.target().immutable(), placing.getBlock());
                StandIns.mark(job.target(), wanted.getBlock(), placing.getBlock());
            }
            changedAt(job.target());
            done();
        }
    }

    /**
     * A bucket or a click: water or lava poured against a block beside the spot (crouched, so it
     * goes in the spot and not into a waterloggable block it's poured against), a bucket of water
     * on a block to soak it, a click to set it, or empty buckets filled at the pool, one after
     * another while there's call for more.
     */
    private void use(LocalPlayer player, long now) {
        Job j = job;
        BlockPos pos = j.target();
        BlockState want = plan.get(pos);
        BlockState have = mc.level.getBlockState(pos);
        boolean done = switch (j.kind()) {
            case POUR -> Placing.matches(have, want);
            case SOAK -> !Placing.needsWater(have, want);
            case CLICK -> Placing.clicks(have, want) == 0;
            case SCOOP -> !isStillWater(pos);
            default -> !moreWater();
        };
        if (done) {
            if (j.kind() == Kind.POUR) placedCount++;
            if (j.kind() != Kind.FILL) changedAt(pos);
            done();
            return;
        }
        if (now - phaseSince > PLACE_TIMEOUT) {
            LOGGER.debug("Schematic Builder: gave up on {} at {}: have {}, crouching {}, holding {}, looking at {}", j.kind(), pos, have,
                player.isCrouching(), player.getMainHandItem(), mc.hitResult instanceof BlockHitResult b ? b.getBlockPos() + " " + b.getDirection() : mc.hitResult);
            if (j.kind() != Kind.FILL) pass(pos, 15_000);
            steering = Input.EMPTY;
            go(Phase.IDLE);
            return;
        }
        Item hold = switch (j.kind()) {
            case POUR -> Placing.item(want);
            case SOAK -> Items.WATER_BUCKET;
            case FILL, SCOOP -> Items.BUCKET;
            default -> null;
        };
        if (hold != null) {
            int slot = hotbar(hold);
            if (slot < 0) {
                if (j.kind() != Kind.FILL) pass(pos, 20_000);
                go(Phase.IDLE);
                return;
            }
            if (player.getInventory().getSelectedSlot() != slot) {
                player.getInventory().setSelectedSlot(slot);
                return;
            }
        }
        boolean crouch = j.kind() == Kind.POUR;
        steering = new Input(false, false, false, false, false, crouch, false);
        if (!aimed) {
            // Poured or soaked at the steadiest spot on the face; the pool's taken at the top of it, and
            // a click's at the middle of the face picked (a gate's swing goes by which way you face).
            // The water underfoot is scooped looking straight down (a hair ahead, so it doesn't spin round).
            double yaw = Math.toRadians(player.getYRot());
            aim.setTarget(j.kind() == Kind.SCOOP ? player.position().add(-Math.sin(yaw) * 0.05, -1, Math.cos(yaw) * 0.05)
                : j.kind() == Kind.POUR || j.kind() == Kind.SOAK
                ? steadyPoint(player.position().add(0, crouch ? EYE : STAND_EYE, 0), j.way(), null, null, pos) : j.way().hit());
            aimed = true;
        }
        // Crouched (or stood back up) a moment first, so the server knows before the click.
        if (now - phaseSince < 150 || player.isCrouching() != crouch || now < useAfter) return;
        boolean onTarget;
        if (j.kind() == Kind.FILL || j.kind() == Kind.SCOOP) {
            Vec3 eye = player.getEyePosition();
            BlockHitResult hit = mc.level.clip(new ClipContext(eye, eye.add(player.getViewVector(1).scale(reach.get())),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, player));
            onTarget = hit.getType() == HitResult.Type.BLOCK && (j.kind() == Kind.SCOOP ? hit.getBlockPos().equals(pos)
                : isPool(hit.getBlockPos()) && !plan.containsKey(hit.getBlockPos()));
        } else if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            onTarget = false;
        } else if (j.kind() == Kind.POUR) {
            onTarget = hit.getBlockPos().equals(j.way().against()) && hit.getDirection() == j.way().face();
        } else {
            onTarget = hit.getBlockPos().equals(pos);
            // Not opening a gate while facing back into it, or it swings round to face you.
            if (j.kind() == Kind.CLICK && have.getBlock() instanceof FenceGateBlock && !have.getValue(FenceGateBlock.OPEN)
                && player.getDirection() == have.getValue(FenceGateBlock.FACING).getOpposite()) onTarget = false;
        }
        if (!onTarget) return;
        InteractionResult result = j.kind() == Kind.CLICK
            ? mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, (BlockHitResult) mc.hitResult)
            : mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        if (!result.consumesAction()) return;
        player.swing(InteractionHand.MAIN_HAND);
        // The next fill once the pool's filled back in; the next click once the server's caught up.
        useAfter = now + (j.kind() == Kind.FILL ? 400 : 250);
        if (j.kind() == Kind.FILL || j.kind() == Kind.CLICK) phaseSince = now;
    }

    // ---- Water

    /** Whether to go and fill buckets: water isn't bought, what's left wants some, you've none, and there are empties and a pool to fill them at. */
    private boolean waterTrip() {
        if (!fromPool() || count(Items.WATER_BUCKET) > 0 || count(Items.BUCKET) == 0 || freeSlots() == 0 && count(Items.BUCKET) > 1) return false;
        return waterWanted() > 0 && findPool() != null;
    }

    /** Filling at the pool: another bucket while what's left wants more, and there's an empty and room for it. */
    private boolean moreWater() {
        int empties = count(Items.BUCKET);
        return empties > 0 && (freeSlots() > 0 || empties == 1) && count(Items.WATER_BUCKET) < waterWanted();
    }

    /** Buckets of water what's left wants (on the layer it's on, with Layer by layer). */
    private int waterWanted() {
        return needed(layered.get()).getOrDefault(Items.WATER_BUCKET, 0);
    }

    /**
     * Puts one lone empty bucket onto another stack of them, the way you'd tidy your inventory
     * (one pick up, one put down). @return whether it moved one.
     */
    private boolean stackBuckets() {
        Inventory inventory = mc.player.getInventory();
        // Onto the biggest stack that isn't full, from the smallest other one.
        int target = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(Items.BUCKET) && stack.getCount() < stack.getMaxStackSize()
                && (target < 0 || stack.getCount() > inventory.getItem(target).getCount())) target = i;
        }
        int single = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (i != target && stack.is(Items.BUCKET) && stack.getCount() < stack.getMaxStackSize()
                && (single < 0 || stack.getCount() < inventory.getItem(single).getCount())) single = i;
        }
        if (target < 0 || single < 0 || mc.screen != null || !mc.player.containerMenu.getCarried().isEmpty()) return false;
        int from = -1;
        int to = -1;
        for (Slot slot : mc.player.containerMenu.slots) {
            if (slot.container != inventory) continue;
            if (slot.getContainerSlot() == single) from = slot.index;
            if (slot.getContainerSlot() == target) to = slot.index;
        }
        if (from < 0 || to < 0) return false;
        int id = mc.player.containerMenu.containerId;
        mc.gameMode.handleInventoryMouseClick(id, from, 0, ClickType.PICKUP, mc.player);
        mc.gameMode.handleInventoryMouseClick(id, to, 0, ClickType.PICKUP, mc.player);
        // More than the stack takes: what's left goes back where it came from.
        if (!mc.player.containerMenu.getCarried().isEmpty()) mc.gameMode.handleInventoryMouseClick(id, from, 0, ClickType.PICKUP, mc.player);
        return true;
    }

    /** A bucket of lava or water: poured, it leaves an empty one. */
    private static boolean filled(Item item) {
        return item == Items.LAVA_BUCKET || item == Items.WATER_BUCKET;
    }

    /**
     * Makes a stack of exactly {@code n} of {@code item}, so /sell (whole stacks only) can take just
     * those: off the smallest stack bigger than that, the way you'd split one by hand (picked up,
     * then one at a time onto an empty slot, or back where it was if that's fewer clicks, and the
     * rest put down). Nothing if there's a stack of {@code n} already, or no empty slot.
     */
    private void splitOff(Item item, int n) {
        Inventory inventory = mc.player.getInventory();
        if (mc.screen != null || !mc.player.containerMenu.getCarried().isEmpty()) return;
        int from = -1;
        int to = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() && (to < 0 || to < 9 && i >= 9)) to = i;
            if (!stack.is(item) || !stack.getComponentsPatch().isEmpty()) continue;
            if (stack.getCount() == n) return;
            if (stack.getCount() > n && (from < 0 || stack.getCount() < inventory.getItem(from).getCount())) from = i;
        }
        if (from < 0 || to < 0) return;
        int fromSlot = -1;
        int toSlot = -1;
        for (Slot slot : mc.player.containerMenu.slots) {
            if (slot.container != inventory) continue;
            if (slot.getContainerSlot() == from) fromSlot = slot.index;
            if (slot.getContainerSlot() == to) toSlot = slot.index;
        }
        if (fromSlot < 0 || toSlot < 0) return;
        int id = mc.player.containerMenu.containerId;
        int rest = inventory.getItem(from).getCount() - n;
        mc.gameMode.handleInventoryMouseClick(id, fromSlot, 0, ClickType.PICKUP, mc.player);
        // Right clicks put one down each.
        int one = n <= rest ? toSlot : fromSlot;
        for (int k = Math.min(n, rest); k > 0; k--) mc.gameMode.handleInventoryMouseClick(id, one, 1, ClickType.PICKUP, mc.player);
        mc.gameMode.handleInventoryMouseClick(id, n <= rest ? fromSlot : toSlot, 0, ClickType.PICKUP, mc.player);
    }

    private int freeSlots() {
        Inventory inventory = mc.player.getInventory();
        int n = 0;
        for (int i = 0; i < 36; i++) if (inventory.getItem(i).isEmpty()) n++;
        return n;
    }

    /**
     * Water that fills itself back up when a bucket's taken from it, nearest you first: still
     * water with two more beside it and something under it to hold it, outside the build. Looked
     * for at most every few seconds; null if there's none near.
     */
    private BlockPos findPool() {
        long now = System.currentTimeMillis();
        if (pool != null && badPools.getOrDefault(pool, 0L) < now) {
            // Each bucket filled takes a block of it out for a moment, until it fills back in.
            if (isPool(pool)) poolSeenAt = now;
            if (now - poolSeenAt < 5000) return pool;
        }
        pool = null;
        if (now - poolLookedAt < 2000) return null;
        poolLookedAt = now;
        BlockPos at = mc.player.blockPosition();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        double best = Double.MAX_VALUE;
        for (int dy = -8; dy <= 8; dy++) {
            for (int dx = -24; dx <= 24; dx++) {
                for (int dz = -24; dz <= 24; dz++) {
                    double d = dx * dx + dy * dy + dz * dz;
                    if (d >= best) continue;
                    p.set(at.getX() + dx, at.getY() + dy, at.getZ() + dz);
                    if (!isPool(p) || plan.containsKey(p) || badPools.getOrDefault(p, 0L) >= now) continue;
                    best = d;
                    pool = p.immutable();
                    poolSeenAt = now;
                }
            }
        }
        return pool;
    }

    private boolean isPool(BlockPos p) {
        if (!mc.level.hasChunkAt(p) || !isStillWater(p)) return false;
        int beside = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) if (isStillWater(p.relative(d))) beside++;
        BlockState below = mc.level.getBlockState(p.below());
        return beside >= 2 && (below.isSolid() || isStillWater(p.below()));
    }

    private boolean isStillWater(BlockPos p) {
        BlockState state = mc.level.getBlockState(p);
        return state.getBlock() instanceof LiquidBlock && state.getFluidState().isSourceOfType(Fluids.WATER);
    }

    /**
     * Filling buckets at the pool: from right where you are if it's in reach (with no {@code
     * paths}), or from the nearest spot you can walk to that reaches it.
     */
    private Job fillJob(LocalPlayer player, Routes paths) {
        BlockPos p = findPool();
        if (p == null) return null;
        if (paths == null) {
            Placing.Way way = poolWay(player.getEyePosition(), p, reach.get());
            return way == null ? null : new Job(Kind.FILL, way.against(), way, player.blockPosition(), 0);
        }
        Job best = null;
        for (int dy = -3; dy <= 2; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos stand = p.offset(dx, dy, dz);
                    int d = paths.distance(stand);
                    if (d < 0 || best != null && d >= best.cost()) continue;
                    Placing.Way way = poolWay(Vec3.atBottomCenterOf(stand).add(0, STAND_EYE, 0), p, reach.get() - PLAN_SLACK);
                    if (way != null) best = new Job(Kind.FILL, way.against(), way, stand, d);
                }
            }
        }
        return best;
    }

    /** Where to look to take water out of the pool from {@code eye}: the top of a block of it in reach and in sight. */
    private Placing.Way poolWay(Vec3 eye, BlockPos p, double limit) {
        List<BlockPos> cells = new ArrayList<>(List.of(p));
        for (Direction d : Direction.Plane.HORIZONTAL) if (isPool(p.relative(d))) cells.add(p.relative(d));
        for (BlockPos cell : cells) {
            Vec3 point = new Vec3(cell.getX() + 0.5, cell.getY() + 0.8, cell.getZ() + 0.5);
            if (point.distanceTo(eye) > limit) continue;
            Vec3 past = point.add(point.subtract(eye).normalize().scale(0.1));
            BlockHitResult hit = mc.level.clip(new ClipContext(eye, past, ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, mc.player));
            if (hit.getType() == HitResult.Type.BLOCK && isPool(hit.getBlockPos()) && !plan.containsKey(hit.getBlockPos())) {
                return new Placing.Way(hit.getBlockPos().immutable(), hit.getDirection(), point, null, null);
            }
        }
        return null;
    }

    /**
     * Done with a junk step taken out behind it on the way down (or one it couldn't, left in): on
     * down the route. @return whether it was one of those.
     */
    private boolean onDown(long now) {
        if (resumeWalk == null) return false;
        if (!mc.level.getBlockState(job.target()).canBeReplaced()) leave(job.target(), OUT_OF_REACH);
        job = resumeWalk;
        resumeWalk = null;
        lastMoveAt = now;
        go(Phase.WALK);
        return true;
    }

    private void dig(LocalPlayer player, long now) {
        steering = Input.EMPTY;
        BlockPos pos = job.target();
        boolean planned = plan.containsKey(pos);
        if (planned ? !inTheWay(pos, mc.level.getBlockState(pos), plan.get(pos)) : mc.level.getBlockState(pos).canBeReplaced()) {
            if (planned) brokenCount++;
            else {
                props.remove(pos);
                if (scaffold.remove(pos) && cleaning) cleared.add(pos.immutable());
            }
            changedAt(pos);
            if (onDown(now)) return;
            done();
            return;
        }
        if (now - phaseSince > BREAK_TIMEOUT) {
            LOGGER.debug("Schematic Builder: gave up breaking {} at {}: crouching {}, looking at {}, at {}, aiming at {}",
                name(mc.level.getBlockState(pos)), pos, player.isCrouching(),
                mc.hitResult instanceof BlockHitResult b ? b.getBlockPos() + " " + b.getDirection() : mc.hitResult,
                player.position(), aim.target());
            if (onDown(now)) return;
            // One of its own it can't get at, twice: left till the clearing up at the end. Set
            // aside a while and no more, four such would take turns for good, the build waiting.
            if (!planned && breakFails.merge(pos.immutable(), 1, Integer::sum) >= 2) leave(pos, OUT_OF_REACH);
            pass(pos, 20_000);
            go(Phase.IDLE);
            return;
        }
        // Scaffolding breaks at a touch: in hand, its outline is the whole block, what the crosshair's on.
        int tool = mc.level.getBlockState(pos).is(Blocks.SCAFFOLDING) ? hotbar(Items.SCAFFOLDING) : toolFor(mc.level.getBlockState(pos));
        if (tool >= 0 && player.getInventory().getSelectedSlot() != tool) player.getInventory().setSelectedSlot(tool);
        Vec3 face = visibleFace(player.getEyePosition(), pos, reach.get());
        if (face == null) {
            if (onDown(now)) return;
            // Not from where it's got to after all (it's off the middle of the spot it planned
            // from): one of its own, twice over, is left too, or it's back for it round and round.
            if (!planned && breakFails.merge(pos.immutable(), 1, Integer::sum) >= 2) leave(pos, OUT_OF_REACH);
            pass(pos, 10_000);
            go(Phase.IDLE);
            return;
        }
        if (!aimed || aim.target() == null || aim.target().distanceTo(face) > 0.6) {
            aim.setTarget(face);
            aimed = true;
        }
        digAt = pos.immutable();
        // With a menu open the game won't hold attack for us, so mine the way it would have.
        if (mc.screen != null && mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)
            && mc.gameMode.continueDestroyBlock(pos, hit.getDirection())) {
            player.swing(InteractionHand.MAIN_HAND);
        }
    }

    /** Jumps and puts a block under its feet at the top of each jump, the way a player towers up. */
    private void pillar(LocalPlayer player, long now) {
        // The last block gone again (the server didn't have it): put it in again.
        if (pillarPlaced > 0 && now - pillarPlacedAt > 600) {
            BlockPos last = new BlockPos(pillarAt.getX(), pillarBase + pillarPlaced - 1, pillarAt.getZ());
            if (!ladder.contains(last) && !mc.level.getBlockState(last).isSolid()) {
                scaffold.remove(last);
                pillarPlaced--;
            }
        }
        if (pillarPlaced >= pillarHeight && player.onGround() && player.getY() >= pillarBase + pillarHeight - 0.01) {
            if (pillarClear && clearUnder(player)) return;
            steering = Input.EMPTY;
            if (resumeRoute) {
                resumeRoute = false;
                pathIndex += pillarHeight;
                lastMoveAt = now;
                go(Phase.WALK);
            } else {
                go(Phase.IDLE);
            }
            return;
        }
        if (now - phaseSince > 4000L + pillarHeight * (pillarClear ? 6000L : 1500L)) {
            pass(job.target(), 20_000);
            steering = Input.EMPTY;
            // A ladder it didn't get up: back down it, taking it down.
            if (pillarPlaced == 0 && isLadder(pillarAt)) {
                LOGGER.debug("Schematic Builder: gave up the ladder at {}, taking it down", pillarAt);
                pillarLadder = false;
                resumeRoute = false;
                ladderAbort = true;
                startDescend();
                return;
            }
            go(Phase.IDLE);
            return;
        }
        if (pillarLadder && ladderUp(player, now)) return;
        // Up on the last one put in: the one under it out first.
        if (pillarClear && pillarPlaced >= 2 && player.onGround() && player.getY() >= pillarBase + pillarPlaced - 0.01 && clearUnder(player)) return;
        int slot = scaffoldSlot(player, true);
        if (slot < 0) {
            Notify.send("Schematic Builder", "Out of throwaway blocks for pillars and bridges", false, false, false);
            pass(job.target(), 20_000);
            go(Phase.IDLE);
            return;
        }
        if (player.getInventory().getSelectedSlot() != slot) player.getInventory().setSelectedSlot(slot);
        if (!aimed) {
            // Straight down, a hair ahead so it doesn't spin round.
            aim.setTarget(player.position().add(-Math.sin(Math.toRadians(player.getYRot())) * 0.05, -3, Math.cos(Math.toRadians(player.getYRot())) * 0.05));
            aimed = true;
        }
        if (player.getXRot() < 80) {
            steering = Input.EMPTY;
            return;
        }
        BlockPos cell = new BlockPos(pillarAt.getX(), pillarBase + pillarPlaced, pillarAt.getZ());
        // Stay over the pillar, and jump while there's more to build.
        Vec3 middle = Vec3.atBottomCenterOf(cell);
        Input drift = Math.hypot(middle.x - player.getX(), middle.z - player.getZ()) > 0.15
            ? Steer.toward(player, middle, false, false) : Input.EMPTY;
        boolean more = pillarPlaced < pillarHeight;
        steering = new Input(drift.forward(), drift.backward(), drift.left(), drift.right(), more && player.onGround(), false, false);
        // Near the top of the jump, your feet well clear of the spot they just left: fill it. (Any lower
        // and the server, a moment behind, still has you in it and turns the block down.)
        if (!player.onGround() && player.getY() > cell.getY() + 1.12 && mc.level.getBlockState(cell).canBeReplaced()
            && mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
            && hit.getBlockPos().equals(cell.below()) && hit.getDirection() == Direction.UP
            && mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit).consumesAction()) {
            player.swing(InteractionHand.MAIN_HAND);
            scaffold.add(cell.immutable());
            putJunk(cell, pillarWhy);
            usedJunk(hit.getBlockPos());
            if (cleaning) cleanupBlocks++;
            pillarPlaced++;
            pillarPlacedAt = now;
            changedAt(cell);
        }
    }

    /** Up {@code height} blocks on a pillar at {@code at}; {@code resume}: then on along the route. */
    private void startPillar(BlockPos at, int height, boolean resume) {
        pillarAt = at;
        pillarHeight = height;
        pillarBase = at.getY();
        pillarPlaced = 0;
        pillarClear = false;
        pillarLadder = false;
        pillarLean = null;
        resumeRoute = resume;
        aimed = false;
        go(Phase.PILLAR);
    }

    // ---- Ladders: pillars of scaffolding (Use scaffolding)

    /** Taking a ladder down from its foot, and given up on one it was putting up (back down it, then on). */
    private boolean takingDown;
    private boolean ladderAbort;
    /** Which way it's looking while it puts a ladder up: down (at the floor, or the first one) or out level from inside it. */
    private boolean ladderLookDown;
    /** Which way it looks out of a ladder, kept as it climbs (the aim follows the eye up, or it ends up looking down). */
    private double ladderYaw;
    /** Scaffolding dug out on the way down, what it dropped still to pick up once off it. */
    private boolean ladderDug;
    private static final long DROPS_WAIT = 2500;

    /** Scaffolding of its own, there now. */
    private boolean isLadder(BlockPos cell) {
        return ladder.contains(cell) && mc.level.getBlockState(cell).is(Blocks.SCAFFOLDING);
    }

    /**
     * Whether the {@code n}-high pillar from {@code from} goes up as a ladder: Use scaffolding on,
     * {@link #LADDER_MIN} high or more, scaffolding enough to hand, and stood square on a floor it stands on.
     */
    private boolean ladderFits(LocalPlayer player, BlockPos from, int n) {
        if (!useScaffolding.get() || n < LADDER_MIN || stillPlanned.contains(Items.SCAFFOLDING) || count(Items.SCAFFOLDING) < n - 1) return false;
        if (Math.abs(player.getY() - from.getY()) > 0.05) return false;
        BlockPos floor = from.below();
        if (!mc.level.getBlockState(floor).isFaceSturdy(mc.level, floor, Direction.UP)) return false;
        for (int k = 0; k < n - 1; k++) {
            BlockState state = mc.level.getBlockState(from.above(k));
            if (!state.canBeReplaced() || !state.getFluidState().isEmpty() && !state.getFluidState().isSourceOfType(net.minecraft.world.level.material.Fluids.WATER)) return false;
        }
        return true;
    }

    /**
     * Puts a ladder up under a pillar's top block, from its foot: the first scaffolding into your
     * own feet (looking down at the floor), the next on top of it (crouched, looking down at it),
     * then the rest on top of the column, clicking out level from inside it while it climbs. Then
     * out of the top and onto it; the junk block to stand on goes on as for any pillar. @return
     * whether it's still at it.
     */
    private boolean ladderUp(LocalPlayer player, long now) {
        int want = pillarHeight - 1;
        BlockPos base = pillarAt;
        int in = 0;
        while (in < want && mc.level.getBlockState(base.above(in)).is(Blocks.SCAFFOLDING)) {
            BlockPos cell = base.above(in).immutable();
            if (ladder.add(cell)) {
                scaffold.add(cell);
                if (cleaning) cleanupBlocks++;
                changedAt(cell);
            }
            in++;
        }
        Vec3 middle = Vec3.atBottomCenterOf(base);
        Input drift = Math.hypot(middle.x - player.getX(), middle.z - player.getZ()) > 0.15 ? Steer.toward(player, middle, false, false) : Input.EMPTY;
        if (in < want) {
            int slot = hotbar(Items.SCAFFOLDING);
            if (slot < 0) {
                pillarLadder = false;
                aimed = false;
                if (in == 0) return false;
                // Run out halfway: back down, taking it down, and another go from there.
                ladderAbort = true;
                resumeRoute = false;
                startDescend();
                return true;
            }
            if (player.getInventory().getSelectedSlot() != slot) player.getInventory().setSelectedSlot(slot);
            boolean eyeIn = mc.level.getBlockState(BlockPos.containing(player.getEyePosition())).is(Blocks.SCAFFOLDING);
            boolean down = in == 0 || !eyeIn;
            // On top of the first from outside it: crouched, or the next goes out sideways.
            boolean crouch = in > 0 && !eyeIn;
            if (!aimed || ladderLookDown != down) {
                ladderYaw = Math.toRadians(player.getYRot());
                double yaw = ladderYaw;
                aim.setTarget(down ? player.position().add(-Math.sin(yaw) * 0.05, -3, Math.cos(yaw) * 0.05)
                    : player.getEyePosition().add(-Math.sin(yaw) * 3, -0.8, Math.cos(yaw) * 3));
                aimed = true;
                ladderLookDown = down;
            } else if (!down) {
                aim.track(player.getEyePosition().add(-Math.sin(ladderYaw) * 3, -0.8, Math.cos(ladderYaw) * 3));
            }
            // Up it as it goes up: climbing, jump held, while the top's well over your head, clicking
            // on top of it on the way (let go of jump, it still rises a bit).
            boolean climb = !down && player.getEyeY() + 0.7 < base.getY() + in;
            steering = new Input(drift.forward(), drift.backward(), drift.left(), drift.right(), climb, crouch, false);
            if (down && !player.onGround() || player.isShiftKeyDown() != crouch || now - ladderClickAt < 140 + random.nextInt(120)) return true;
            if (down ? player.getXRot() < 80 : Math.abs(player.getXRot()) > 40) return true;
            if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return true;
            BlockPos at = hit.getBlockPos();
            boolean right = in == 0 ? at.equals(base.below()) && hit.getDirection() == Direction.UP
                : down ? at.equals(base) && hit.getDirection() == Direction.UP
                : hit.isInside() && at.getX() == base.getX() && at.getZ() == base.getZ() && at.getY() >= base.getY() && at.getY() < base.getY() + in
                    && hit.getDirection().getAxis().isHorizontal();
            if (right && mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit).consumesAction()) {
                player.swing(InteractionHand.MAIN_HAND);
                ladderClickAt = now;
            }
            return true;
        }
        // All in: up it, jump held over the middle, let go once out of the top to land on it.
        if (!player.onGround() || player.getY() < base.getY() + want - 0.01) {
            boolean out = player.getY() >= base.getY() + want;
            steering = new Input(drift.forward(), drift.backward(), drift.left(), drift.right(), !out, false, false);
            return true;
        }
        LOGGER.debug("Schematic Builder: up the ladder at {}, {} high, putting the top block on", base, want);
        pillarLadder = false;
        pillarPlaced = want;
        pillarPlacedAt = now;
        aimed = false;
        steering = Input.EMPTY;
        return false;
    }

    /**
     * In a ladder's foot (one given up on halfway up, sunk down through), come down it: the
     * scaffolding at your feet broken, looking down, and all of it over that comes down too; what
     * it drops is picked up.
     * @return whether it's at it.
     */
    private boolean takeDownLadder(LocalPlayer player, long now) {
        BlockPos feet = player.blockPosition();
        if (!isLadder(feet)) {
            if (!takingDown) return false;
            takingDown = false;
            dropsUntil = now + DROPS_WAIT;
            return true;
        }
        if (!takingDown) LOGGER.debug("Schematic Builder: taking down the ladder at {}", feet);
        takingDown = true;
        if (now - phaseSince > 30_000) {
            takingDown = false;
            go(Phase.IDLE);
            return true;
        }
        // Out of hand, scaffolding's outline is its frame: looking down from in the column, the
        // crosshair's through the one at your head, on the one at your feet. With it in hand (the
        // whole hotbar of it) it's on the one at your head, and that goes first.
        Inventory inventory = player.getInventory();
        if (inventory.getSelectedItem().is(Items.SCAFFOLDING)) {
            for (int i = 0; i < 9; i++) {
                if (!inventory.getItem(i).is(Items.SCAFFOLDING)) {
                    inventory.setSelectedSlot(i);
                    break;
                }
            }
        }
        double yaw = Math.toRadians(player.getYRot());
        Vec3 point = Vec3.atCenterOf(feet).add(-Math.sin(yaw) * 0.05, 0.5, Math.cos(yaw) * 0.05);
        if (aim.target() == null || aim.target().distanceTo(point) > 0.5) aim.setTarget(point);
        digAt = mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && isLadder(hit.getBlockPos())
            && hit.getBlockPos().getX() == feet.getX() && hit.getBlockPos().getZ() == feet.getZ() ? hit.getBlockPos().immutable() : feet.immutable();
        return true;
    }

    /**
     * Scaffolding a ladder taken down dropped, lying near: walked over to crouched (so never off an
     * edge) till it's picked up, or still falling, till the wait's over. @return whether it's at it.
     */
    private boolean collectDrops(LocalPlayer player, long now) {
        if (now >= dropsUntil) return false;
        net.minecraft.world.entity.item.ItemEntity nearest = null;
        double best = Double.MAX_VALUE;
        boolean falling = false;
        for (net.minecraft.world.entity.Entity e : com.cyanite.client.system.WorldScan.entities()) {
            if (!(e instanceof net.minecraft.world.entity.item.ItemEntity item) || !item.getItem().is(Items.SCAFFOLDING)) continue;
            double dx = item.getX() - player.getX();
            double dz = item.getZ() - player.getZ();
            double dy = item.getY() - player.getY();
            double flat = dx * dx + dz * dz;
            if (flat > 6.25 || dy < -0.6 || dy > 48) continue;
            if (dy > 0.6 || !item.onGround()) {
                falling = true;
                continue;
            }
            if (flat < best) {
                best = flat;
                nearest = item;
            }
        }
        // The first drops take a moment to show up.
        if (nearest == null && !falling) return now - (dropsUntil - DROPS_WAIT) < 500;
        steering = nearest == null ? Input.EMPTY : Steer.toward(player, nearest.position(), false, true);
        return true;
    }

    /** Whether nothing of its own sits on the ladder over {@code b} (its top block's out): taken out there, all of it over that comes down with it. */
    private boolean bareLadder(BlockPos b) {
        BlockPos top = ladderTop(b);
        return !scaffold.contains(top.above()) && !props.contains(top.above());
    }

    /** The top of the ladder {@code b}'s in; {@code b} itself if it's no ladder. */
    private BlockPos ladderTop(BlockPos b) {
        if (!isLadder(b)) return b;
        BlockPos top = b;
        while (isLadder(top.above())) top = top.above();
        return top;
    }

    /**
     * Whether the {@code n}-high pillar from {@code from}, next on the route, is the way up out of
     * somewhere it couldn't walk back into from where it started (the bottom of a shaft in the
     * build), onto something of the build's, clearing up: it's coming up out of there for good, so
     * the pillar comes out under it as it goes (a pillar left down there, nothing could reach).
     */
    private boolean climbsOut(BlockPos from, int n) {
        int next = pathIndex + n;
        if (!cleaning || next > path.size()) return false;
        if (next < path.size()) {
            // On along the route, walking off the top onto the build.
            if (moves.get(next) != Routes.Move.WALK || !buildFloor(path.get(next).below())) return false;
        } else if (!walksOff(path.get(next - 1))) {
            // The top's where it's going: only with the build beside it to walk off onto after.
            return false;
        }
        Routes fromHome = Routes.search(mc.level, home, bounds.grow(8, 6), null, false, false, rules, PATH_BUDGET);
        return fromHome.distance(from) < 0;
    }

    /** Something of the build's to stand on at {@code floor}, not a junk block or a prop. */
    private boolean buildFloor(BlockPos floor) {
        return !scaffold.contains(floor) && !props.contains(floor) && !mc.level.getBlockState(floor).getCollisionShape(mc.level, floor).isEmpty();
    }

    /** Whether there's a step beside {@code feet}, level or one up, onto the build, to walk off onto. */
    private boolean walksOff(BlockPos feet) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            for (int up = 0; up <= 1; up++) {
                BlockPos at = feet.relative(d).above(up);
                if (buildFloor(at.below()) && rules.mayStand(at) && Paths.clearAir(mc.level, at) && Paths.clearAir(mc.level, at.above())
                    && (up == 0 || Paths.clearAir(mc.level, feet.above(2)))) return true;
            }
        }
        return false;
    }

    /**
     * Takes out the block of the pillar under the one it's on, crouched out over the edge to see
     * its side; then back over the middle to go on. @return whether it's still at it.
     */
    private boolean clearUnder(LocalPlayer player) {
        BlockPos on = new BlockPos(pillarAt.getX(), pillarBase + pillarPlaced - 1, pillarAt.getZ());
        BlockPos under = on.below();
        Vec3 middle = Vec3.atBottomCenterOf(on.above());
        if (under.getY() >= pillarBase && scaffold.contains(under) && !stuckProps.contains(under)
            && !mc.level.getBlockState(under).canBeReplaced() && quietOut(under)) {
            if (pillarLean == null) pillarLean = leanFor(under);
            Vec3 face = null;
            if (pillarLean != null) {
                Direction d = pillarLean;
                steering = Steer.toward(player, middle.add(d.getStepX() * 0.9, 0, d.getStepZ() * 0.9), false, true);
                double out = (player.getX() - middle.x) * d.getStepX() + (player.getZ() - middle.z) * d.getStepZ();
                if (out < 0.6 || !player.isCrouching()) return true;
                face = visibleFace(player.getEyePosition(), under, reach.get());
            }
            if (face != null) {
                int tool = toolFor(mc.level.getBlockState(under));
                if (tool >= 0 && player.getInventory().getSelectedSlot() != tool) player.getInventory().setSelectedSlot(tool);
                if (aim.target() == null || aim.target().distanceTo(face) > 0.3) aim.setTarget(face);
                digAt = under.immutable();
                return true;
            }
            LOGGER.debug("Schematic Builder: can't get at {} under the pillar it's climbing out on (leaning {})", under, pillarLean);
            leave(under, OUT_OF_REACH);
        } else if (mc.level.getBlockState(under).canBeReplaced() && scaffold.remove(under)) {
            cleared.add(under.immutable());
            changedAt(under);
        }
        // Back over the middle before going on up, looking down again.
        if (pillarLean != null) aimed = false;
        pillarLean = null;
        if (Math.hypot(middle.x - player.getX(), middle.z - player.getZ()) > 0.15) {
            steering = Steer.toward(player, middle, false, true);
            return true;
        }
        return false;
    }

    /** Which way to lean out from over {@code under} to see its side: open beside it from there on up past your head, and nothing to set off. */
    private Direction leanFor(BlockPos under) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos side = under.relative(d);
            boolean open = rules.mayStand(side.above(2));
            for (int k = 0; k <= 3 && open; k++) open = Paths.clearAir(mc.level, side.above(k));
            if (open) return d;
        }
        return null;
    }

    /** Down the pillar underfoot, mining it out from under itself, all the way. */
    private void startDescend() {
        digTo = Integer.MIN_VALUE;
        aimed = false;
        go(Phase.DESCEND);
    }

    /** Down {@code count} blocks of pillar on a route, till the feet are at {@code toY}; then on along it. */
    private void startDig(int toY, int count) {
        digTo = toY;
        digCount = count;
        aimed = false;
        go(Phase.DESCEND);
    }

    /** Whether mining out {@code block} from under you lands you somewhere safe: a floor at most a couple of blocks under it, and no lava. */
    private boolean safeDig(BlockPos block) {
        for (int k = 1; k <= 3; k++) {
            BlockPos at = block.below(k);
            // Down a ladder of its own: its foot's where it put it up from.
            if (isLadder(at)) return true;
            BlockState state = mc.level.getBlockState(at);
            if (state.getFluidState().is(FluidTags.LAVA) || state.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)) return false;
            if (!state.getCollisionShape(mc.level, at).isEmpty()) return true;
        }
        return false;
    }

    /** Mines the pillar out from under its feet, a block at a time: back down to the ground, or as far as the route goes. */
    private void descend(LocalPlayer player, long now) {
        steering = Input.EMPTY;
        if (dropsUntil > 0) {
            if (collectDrops(player, now)) return;
            dropsUntil = 0;
        }
        BlockPos feet = player.blockPosition();
        BlockPos under = feet.below();
        // In a ladder (one given up on halfway up): crouched, sinking through it to its foot. On
        // top of one, it's dug out from under your feet like any junk block (it breaks at a touch),
        // what it drops coming down with you.
        if (isLadder(feet) && (!player.onGround() || isLadder(under))) {
            if (now - phaseSince > 30_000) {
                go(Phase.IDLE);
                return;
            }
            steering = new Input(false, false, false, false, false, true, false);
            return;
        }
        if (!player.onGround()) return;
        // At its foot, in it: taken down before going on.
        if (takeDownLadder(player, now)) return;
        if (ladderAbort) {
            ladderAbort = false;
            go(Phase.IDLE);
            return;
        }
        boolean bottom = digTo != Integer.MIN_VALUE && feet.getY() <= digTo;
        if (ladderDug && (bottom || !isLadder(under))) {
            // Off the scaffolding it dug down through: what that dropped picked up first.
            ladderDug = false;
            dropsUntil = now + DROPS_WAIT;
            return;
        }
        if (bottom) {
            // As far down as the route goes: on along it.
            digTo = Integer.MIN_VALUE;
            pathIndex += digCount;
            lastMoveAt = now;
            go(Phase.WALK);
            return;
        }
        if (!scaffold.contains(under) || !safeDig(under) || !quietOut(under)) {
            scaffold.removeIf(p -> !mc.level.getBlockState(p).isSolid() && !isLadder(p));
            // Down the pillar it left by: what was out of reach from up there gets another look.
            // On the ground for good only where it can walk off to where it started from (not at
            // the bottom of a shaft in the build, say, with the way out still to put up).
            if (cleaning && digTo == Integer.MIN_VALUE) {
                boolean out = Routes.search(mc.level, home, bounds.grow(8, 6), null, false, false, rules, PATH_BUDGET)
                    .distance(player.blockPosition()) >= 0;
                LOGGER.debug("Schematic Builder: down {} at {}", out ? "for good" : "the pillar, but not out", player.blockPosition());
                cameDown = true;
                onGroundForGood = out;
            }
            digTo = Integer.MIN_VALUE;
            go(Phase.IDLE);
            return;
        }
        if (mc.level.getBlockState(under).isAir()) {
            if (scaffold.remove(under) && cleaning) cleared.add(under.immutable());
            return;
        }
        if (now - phaseSince > 20_000) {
            go(Phase.IDLE);
            return;
        }
        int tool = toolFor(mc.level.getBlockState(under));
        if (tool >= 0 && player.getInventory().getSelectedSlot() != tool) player.getInventory().setSelectedSlot(tool);
        Vec3 top = Vec3.atCenterOf(under).add(0, 0.5, 0);
        if (!aimed || aim.target() == null || aim.target().distanceTo(top) > 0.3) {
            aim.setTarget(top.add(-Math.sin(Math.toRadians(player.getYRot())) * 0.05, 0, Math.cos(Math.toRadians(player.getYRot())) * 0.05));
            aimed = true;
        }
        digAt = under.immutable();
        if (isLadder(under)) ladderDug = true;
        // With a menu open the game won't hold attack for us, so mine the way it would have.
        if (mc.screen != null && mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(under)
            && mc.gameMode.continueDestroyBlock(under, hit.getDirection())) {
            player.swing(InteractionHand.MAIN_HAND);
        }
    }

    private boolean onScaffold(LocalPlayer player) {
        return player.onGround() && scaffold.contains(player.blockPosition().below());
    }

    /** One job done: a pause before the next, the way a person's hand moves on. */
    private void done() {
        double gap = 1000 / speed.get();
        nextActAt = System.currentTimeMillis() + (long) (gap * (0.7 + random.nextDouble() * 0.6));
        steering = Input.EMPTY;
        go(Phase.IDLE);
    }

    private void go(Phase next) {
        long now = System.currentTimeMillis();
        if (phaseSince > 0) phaseTime.merge(phase, now - phaseSince, Long::sum);
        phase = next;
        phaseSince = now;
    }

    /** Where the time's gone, by what it was doing: for working out what's slow. */
    private final Map<Phase, Long> phaseTime = new java.util.EnumMap<>(Phase.class);

    // ---- Materials

    /** The item it's shortest of for what's left, and how many more it needs; null if it's got everything it can buy. */
    private Map.Entry<Item, Integer> shortage(LocalPlayer player) {
        Map<Item, Integer> needed = needed(buyPerLayer.get());
        // Water's bought by the bucket like lava. Fetched from a pool instead (none to be had), only
        // empty buckets are bought, if you've none to fill.
        Integer water = needed.get(Items.WATER_BUCKET);
        if (water != null && fromPool() && findPool() != null) {
            needed.remove(Items.WATER_BUCKET);
            int lacking = water - count(Items.WATER_BUCKET);
            if (lacking > 0 && count(Items.BUCKET) == 0) needed.put(Items.BUCKET, Math.min(lacking, 16));
        }
        // Junk blocks for pillars and bridges: a stack more when it's down to its last few, with
        // building left (stuck at the foot of a tall build without any, it can't get back up).
        if ((buildUp.get() || bridge.get()) && !pending.isEmpty() && scaffoldCount() < JUNK_LOW) {
            Item junk = junkItem();
            needed.merge(junk, count(junk) + JUNK_STACK, Math::max);
        }
        // Scaffolding for ladders, the same way.
        if (useScaffolding.get() && buildUp.get() && !pending.isEmpty() && !stillPlanned.contains(Items.SCAFFOLDING) && count(Items.SCAFFOLDING) < LADDER_LOW) {
            needed.merge(Items.SCAFFOLDING, count(Items.SCAFFOLDING) + LADDER_STACK, Math::max);
        }
        int free = freeSlots();
        cramped = null;
        Map<Item, Integer> lacking = new HashMap<>();
        for (Map.Entry<Item, Integer> e : needed.entrySet()) {
            Item item = e.getKey();
            if (buyTried.contains(item)) continue;
            int n = e.getValue() - count(item);
            if (n <= 0) continue;
            // Stopped for want of room: again once there's more of it.
            Integer was = noRoom.get(item);
            if (was != null && free <= was) {
                if (cramped == null || count(item) == 0) cramped = item;
                continue;
            }
            noRoom.remove(item);
            lacking.put(item, n);
        }
        // No more than fits with a slot to spare: bought into a full inventory it'd land on the
        // ground, paid for all the same. Not room for the lot, the slots are shared out: what
        // needs fewest gets all it needs, the rest split evenly.
        int pool = Math.max(0, free - 1);
        for (Item item : lacking.keySet()) pool += Math.ceilDiv(count(item), item.getDefaultMaxStackSize());
        List<Item> order = new ArrayList<>(lacking.keySet());
        order.sort(Comparator.comparingInt(item -> Math.ceilDiv(count(item) + lacking.get(item), item.getDefaultMaxStackSize())));
        Map.Entry<Item, Integer> worst = null;
        boolean worstHasNone = false;
        for (int i = 0; i < order.size(); i++) {
            Item item = order.get(i);
            int stack = item.getDefaultMaxStackSize();
            int have = count(item);
            // A slot at least while there's one, so the few-of what needs fewest isn't shared down to none.
            int even = Math.max(0, pool) / (order.size() - i);
            if (even == 0 && pool > 0) even = 1;
            int share = Math.min(Math.ceilDiv(have + lacking.get(item), stack), even);
            pool -= Math.max(share, Math.ceilDiv(have, stack));
            int n = Math.min(lacking.get(item), Math.min(Math.max(0, share * stack - have), roomFor(item)));
            if (n < lacking.get(item)) {
                if (cramped == null || have == 0) cramped = item;
                // The rest once it's down to its last stack: off to /ah for every slot built would crawl.
                if (have >= stack) continue;
                // Whole stacks, so what comes in stacks leaves the spare slot be.
                n -= n % stack;
            }
            if (n <= 0) continue;
            // What you've none of at all first, then the most lacking.
            boolean none = have == 0;
            if (worst == null || none && !worstHasNone || none == worstHasNone && n > worst.getValue()) {
                worst = Map.entry(item, n);
                worstHasNone = none;
            }
        }
        return worst;
    }

    /** Short of something it's none of, the inventory too full to buy it. */
    private boolean waitingForRoom() {
        return buy.get() && cramped != null && count(cramped) == 0 && freeSlots() <= 1;
    }

    /** How many more of {@code item} fit in the inventory with a slot to spare. */
    private int roomFor(Item item) {
        Inventory inventory = mc.player.getInventory();
        int empty = 0;
        int topUp = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) empty++;
            else if (stack.is(item) && stack.getComponentsPatch().isEmpty()) topUp += Math.max(0, stack.getMaxStackSize() - stack.getCount());
        }
        return Math.max(0, empty - 1) * item.getDefaultMaxStackSize() + topUp;
    }

    /** How many of each item what's left takes: just the layer it's on, with {@code layerOnly} (and Layer by layer). */
    private Map<Item, Integer> needed(boolean layerOnly) {
        Map<Item, Integer> needed = new HashMap<>();
        // Layer by layer, buckets wait till the layer's blocks are in: then all its lava's bought at once.
        boolean blocksLeft = false;
        if (layered.get()) {
            for (BlockPos pos : pending) {
                BlockState want = plan.get(pos);
                if (onLayer(pos) && !Placing.fluid(want) && !Placing.matches(mc.level.getBlockState(pos), want)) {
                    blocksLeft = true;
                    break;
                }
            }
        }
        for (BlockPos pos : pending) {
            if (layerOnly && !onLayer(pos)) continue;
            BlockState want = plan.get(pos);
            BlockState have = mc.level.getBlockState(pos);
            // In already: only its water, if it's still to be soaked.
            if (Placing.matches(have, want)) {
                if (Placing.needsWater(have, want)) needed.merge(Items.WATER_BUCKET, 1, Integer::sum);
                continue;
            }
            int n = want.getBlock() instanceof SlabBlock && want.getValue(SlabBlock.TYPE) == net.minecraft.world.level.block.state.properties.SlabType.DOUBLE
                && have.getBlock() != want.getBlock() ? 2 : 1;
            Item item = Placing.item(want);
            // A bucket only once there's somewhere to pour it, shut in on all sides: they don't
            // stack, and lava waiting on the blocks round it would just fill up the inventory.
            if (Placing.fluid(want) && (blocksLeft && onLayer(pos) || !contained(pos, want.getFluidState().getType()))) continue;
            // Given up on: it's a stand-in that's wanted instead.
            if (gaveUp(item)) {
                if (standInFits(pos, want)) needed.merge(standInItem(), 1, Integer::sum);
                continue;
            }
            needed.merge(item, n, Integer::sum);
            // And a bucket of water on it once it's in, fetched along with the rest.
            if (Placing.wantsWater(want)) needed.merge(Items.WATER_BUCKET, 1, Integer::sum);
        }
        return needed;
    }

    private void buying(long now) {
        steering = Input.EMPTY;
        AuctionBuyer.Result r = buyer.tick();
        if (r == AuctionBuyer.Result.RUNNING) return;
        spent += buyer.spent();
        Item item = buyingItem;
        if (buyer.got() > 0) bought.merge(item, buyer.got(), Integer::sum);
        // And the empty buckets they'll leave, once poured: those go back with /sell too.
        if (buyer.got() > 0 && filled(item)) bought.merge(Items.BUCKET, buyer.got(), Integer::sum);
        String what = AuctionBuyer.name(item);
        if (buyer.got() > 0) toldFull.remove(item);
        // Stopped for want of room: not one it can't buy. The rest once there's room.
        if (buyer.full()) {
            buyTried.remove(item);
            noRoom.put(item, freeSlots());
            Notify.send("Schematic Builder", (buyer.got() > 0 ? "Bought " + buyer.got() + " " + what + " for " + dollars(buyer.spent()) + "; no room for more"
                : "No room for " + what) + " yet, the rest once some's built", false, false, false);
            nextActAt = now + 600;
            go(Phase.IDLE);
            return;
        }
        // Got all it went for (only as many as there was room for, say): more can be bought once there's room.
        if (buyer.got() >= buyingWant) buyTried.remove(item);
        else cantBuy.add(item);
        // What it costs, said either way, so you know why it did what it did.
        double cheapest = buyer.cheapestEach();
        String price = !Double.isNaN(cheapest) ? "cheapest is " + dollars(cheapest) + " each, your max is " + dollars(maxEach.get())
            : buyer.why().isEmpty() || buyer.why().startsWith("no ") ? "none listed" : buyer.why();
        // What happens where it was wanted instead: a stand-in, or (for anything but a full block) nothing.
        boolean full = item instanceof BlockItem b && b.getBlock().defaultBlockState().isCollisionShapeFullBlock(mc.level, BlockPos.ZERO);
        String instead = item == standInItem() ? "" : standIns.get() && full
            ? ". " + AuctionBuyer.name(standInItem()) + " goes in its place, marked" : ". Left empty, marked";
        if (buyer.got() > 0) {
            Notify.send("Schematic Builder", "Bought " + buyer.got() + " " + what + " for " + dollars(buyer.spent())
                + (buyer.why().isEmpty() ? "" : " (no more: " + price + ")" + instead), false, false, false);
        } else {
            Notify.send("Schematic Builder", "Not buying " + what + ": " + price + instead, false, false, false);
        }
        nextActAt = now + 600;
        go(Phase.IDLE);
    }

    /**
     * Sells back what it bought and won't need: at the {@code end} all of it, otherwise what the
     * layer it's on doesn't take. Never below what you had of your own. @return whether it started.
     */
    private boolean sell(boolean end) {
        if (!sellLeftovers.get() || bought.isEmpty()) return false;
        // What's still to be built with: just this layer's, unless it all got bought at once
        // (selling the next layers' only to buy them again would throw money away).
        Map<Item, Integer> keep = end ? Map.of() : needed(buyPerLayer.get());
        if (!end) {
            // And what's wanted below, set aside for now: it gets another go, and selling it only
            // to buy it all again would throw money away.
            keep = new HashMap<>(keep);
            for (BlockPos pos : pending) {
                if (layer == Integer.MIN_VALUE || pos.getY() >= layer) continue;
                BlockState want = plan.get(pos);
                if (!Placing.matches(mc.level.getBlockState(pos), want)) keep.merge(Placing.item(want), 1, Integer::sum);
            }
        }
        Map<Item, Integer> sell = new HashMap<>();
        for (Map.Entry<Item, Integer> e : bought.entrySet()) {
            Item item = e.getKey();
            // Full buckets are kept for pouring; the empties go back, unless water's fetched in them.
            if (unsellable.contains(item) || filled(item) || item == Items.BUCKET && fromPool() && !end) continue;
            int hold = keep.getOrDefault(item, 0);
            // Junk blocks to tower up and bridge on: a stack stays while there's building left.
            if (!end && (buildUp.get() || bridge.get()) && SCAFFOLD.contains(item)) hold += JUNK_STACK;
            if (!end && useScaffolding.get() && item == Items.SCAFFOLDING) hold += LADDER_STACK;
            int n = Math.min(count(item) - Math.max(hold, own.getOrDefault(item, 0)), e.getValue() - soldBack.getOrDefault(item, 0));
            if (n > 0) sell.put(item, n);
        }
        // The empties all go on one stack, yours too: the ones to sell come off it onto a stack of their own.
        Integer empties = sell.get(Items.BUCKET);
        if (empties != null) splitOff(Items.BUCKET, empties);
        // It only puts up whole stacks: no stack that small, nothing to open the menu for.
        Inventory inventory = mc.player.getInventory();
        boolean any = false;
        for (int i = 0; i < 36 && !any; i++) {
            ItemStack stack = inventory.getItem(i);
            Integer n = sell.get(stack.getItem());
            any = !stack.isEmpty() && n != null && stack.getCount() <= n && stack.getComponentsPatch().isEmpty();
        }
        if (!any) return false;
        seller.start(sell);
        go(Phase.SELL);
        return true;
    }

    private void selling(long now) {
        steering = Input.EMPTY;
        SellMenu.Result r = seller.tick();
        if (r == SellMenu.Result.RUNNING) return;
        Map<Item, Integer> sold = seller.sold();
        sold.forEach((item, n) -> soldBack.merge(item, n, Integer::sum));
        // Put up and none of it taken: /sell doesn't buy it, so it isn't put up again.
        for (Item item : seller.offered().keySet()) if (!sold.containsKey(item)) unsellable.add(item);
        if (!Double.isNaN(seller.earned())) earned += seller.earned();
        if (!sold.isEmpty()) {
            List<String> parts = new ArrayList<>();
            sold.forEach((item, n) -> parts.add(n + " " + AuctionBuyer.name(item)));
            Notify.send("Schematic Builder", "Sold back " + String.join(", ", parts) + " with /sell"
                + (Double.isNaN(seller.earned()) ? "" : " for " + dollars(seller.earned())), false, false, false);
        } else if (r == SellMenu.Result.FAILED) {
            Notify.send("Schematic Builder", "Couldn't sell the leftovers: " + seller.why(), false, false, false);
        }
        nextActAt = now + 600;
        go(Phase.IDLE);
    }

    private int count(Item item) {
        Inventory inventory = mc.player.getInventory();
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(item)) n += stack.getCount();
        }
        return n;
    }

    /** The hotbar slot with {@code item}, swapping it up from the inventory if it's down there. -1 if you've none. */
    private int hotbar(Item item) {
        Inventory inventory = mc.player.getInventory();
        for (int i = 0; i < 9; i++) if (inventory.getItem(i).is(item)) return i;
        for (int i = 9; i < 36; i++) if (inventory.getItem(i).is(item)) return bringUp(i);
        return -1;
    }

    /** A hotbar slot with throwaway blocks for a pillar, brought up if need be (only when {@code bring}). -1 if there are none. */
    private int scaffoldSlot(LocalPlayer player, boolean bring) {
        Inventory inventory = player.getInventory();
        // Junk the build doesn't use first (dirt rather than the cobblestone it's building with), the hotbar first.
        for (boolean spare : new boolean[] {true, false}) {
            for (int i = 0; i < 36; i++) {
                Item item = inventory.getItem(i).getItem();
                if (!SCAFFOLD.contains(item) || spare && stillPlanned.contains(item)) continue;
                return i < 9 || !bring ? i : bringUp(i);
            }
        }
        return -1;
    }

    /** What's still to go in, as items (worked out with each scan): pillars and props are built of something else when there is something else. */
    private Set<Item> stillPlanned = Set.of();

    /** The best thing you have for breaking {@code state} quickly, brought to the hotbar. -1 for your hand. */
    private int toolFor(BlockState state) {
        Inventory inventory = mc.player.getInventory();
        int best = -1;
        float bestSpeed = 1;
        for (int i = 0; i < 36; i++) {
            float s = inventory.getItem(i).getDestroySpeed(state);
            if (s > bestSpeed) {
                best = i;
                bestSpeed = s;
            }
        }
        return best < 9 ? best : bringUp(best);
    }

    /** Swaps a stack from the inventory into a hotbar slot, keeping tools and weapons where they are. */
    private int bringUp(int from) {
        Inventory inventory = mc.player.getInventory();
        Set<Item> planned = new HashSet<>();
        for (BlockPos pos : pending.subList(0, Math.min(pending.size(), 30))) planned.add(Placing.item(plan.get(pos)));
        int into = -1;
        for (int s = 8; s >= 0 && into < 0; s--) if (inventory.getItem(s).isEmpty()) into = s;
        for (int s = 8; s >= 0 && into < 0; s--) {
            ItemStack stack = inventory.getItem(s);
            if (stack.getItem() instanceof BlockItem && !planned.contains(stack.getItem())) into = s;
        }
        for (int s = 8; s >= 0 && into < 0; s--) if (!inventory.getItem(s).isDamageableItem()) into = s;
        if (into < 0) into = 8;
        for (Slot slot : mc.player.containerMenu.slots) {
            if (slot.container == inventory && slot.getContainerSlot() == from) {
                mc.gameMode.handleInventoryMouseClick(mc.player.containerMenu.containerId, slot.index, into, ClickType.SWAP, mc.player);
                return into;
            }
        }
        return -1;
    }

    // ---- Finishing

    /** As done as it'll get: the leftovers sold back first (once), then the summary. */
    private void wrapUp() {
        if (!soldAtEnd) {
            soldAtEnd = true;
            if (sell(true)) return;
        }
        finish();
    }

    private void finish() {
        String summary = summary();
        report = (pending.isEmpty() ? "Built. " : "Stopped. ") + summary;
        Notify.send(pending.isEmpty() ? "Schematic built" : "Schematic Builder stopped", summary, true, true, true);
        setEnabled(false);
    }

    /** What it did: placed, broke, spent and sold back, what's left undone and why. */
    private String summary() {
        int left = pending.size();
        double seconds = (System.currentTimeMillis() - startedAt) / 1000.0;
        StringBuilder sb = new StringBuilder();
        sb.append("Placed ").append(placedCount).append(brokenCount > 0 ? ", broke " + brokenCount : "")
            // Rounded first, or 419.6 s comes out as "6m 60s".
            .append(" in ").append(Math.round(seconds) < 60 ? Math.round(seconds) + "s" : Math.round(seconds) / 60 + "m " + Math.round(seconds) % 60 + "s");
        if (spent > 0) sb.append(", spent ").append(dollars(spent));
        int soldItems = soldBack.values().stream().mapToInt(Integer::intValue).sum();
        if (soldItems > 0) sb.append(", sold back ").append(soldItems).append(earned > 0 ? " for " + dollars(earned) : "");
        if (left > 0) {
            Map<Item, Integer> lacking = new HashMap<>();
            for (BlockPos pos : pending) lacking.merge(Placing.item(plan.get(pos)), 1, Integer::sum);
            List<String> parts = new ArrayList<>();
            lacking.entrySet().stream().sorted(Map.Entry.<Item, Integer>comparingByValue().reversed()).limit(5)
                .forEach(e -> parts.add(e.getValue() + " " + AuctionBuyer.name(e.getKey())));
            sb.append(". ").append(left).append(" left undone: ").append(String.join(", ", parts));
        }
        if (!stood.isEmpty()) {
            Map<Block, Integer> byWanted = new HashMap<>();
            for (BlockPos pos : stood.keySet()) byWanted.merge(plan.get(pos).getBlock(), 1, Integer::sum);
            List<String> parts = new ArrayList<>();
            byWanted.forEach((b, n) -> parts.add(n + " " + b.getName().getString()));
            sb.append(". Stand-ins for ").append(String.join(", ", parts)).append(" (marked orange)");
        }
        if (!leftEmpty.isEmpty()) sb.append(". ").append(leftEmpty.size()).append(" left empty (marked red)");
        // Junk blocks and props still in, by why.
        props.removeIf(p -> mc.level.getBlockState(p).canBeReplaced());
        scaffold.removeIf(p -> mc.level.getBlockState(p).canBeReplaced());
        Map<String, List<BlockPos>> leftIn = new LinkedHashMap<>();
        for (BlockPos p : props) leftIn.computeIfAbsent(leftWhy.getOrDefault(p, removable(p) ? OUT_OF_REACH : HOLDS_UP), k -> new ArrayList<>()).add(p);
        for (BlockPos p : scaffold) leftIn.computeIfAbsent(leftWhy.getOrDefault(p, OUT_OF_REACH), k -> new ArrayList<>()).add(p);
        if (!leftIn.isEmpty()) {
            List<String> parts = new ArrayList<>();
            leftIn.forEach((why, at) -> parts.add(at.size() + " " + why));
            int n = props.size() + scaffold.size();
            sb.append(". ").append(n).append(" junk block").append(n == 1 ? "" : "s").append(" left in (").append(String.join(", ", parts)).append(")");
            LOGGER.debug("Schematic Builder: junk left in: {}", leftIn);
        }
        if (skippedCount > 0) sb.append(". Skipped ").append(skippedCount).append(" (running water and lava, which come from their source)");
        goneJunk(true);
        if (junkPut > 0) LOGGER.debug("Schematic Builder: junk blocks: {} put in, {} never stood on or built against: {}", junkPut,
            junkWasted.values().stream().mapToInt(Integer::intValue).sum(), junkWasted);
        return sb.toString();
    }

    // ---- Wasted junk (debug log only)

    /** A junk block or prop just put in, and what for. */
    private void putJunk(BlockPos pos, String why) {
        junkPut++;
        junkWhy.put(pos.immutable(), why);
    }

    /** A junk block or prop stood on or built against: it was worth putting in. */
    private void usedJunk(BlockPos pos) {
        junkWhy.remove(pos);
    }

    /** Junk blocks under your feet count as used. */
    private void stoodOnJunk(LocalPlayer player) {
        if (junkWhy.isEmpty() || !player.onGround()) return;
        AABB box = player.getBoundingBox();
        int y = Mth.floor(box.minY - 0.05);
        for (int x = Mth.floor(box.minX + 0.01); x <= Mth.floor(box.maxX - 0.01); x++) {
            for (int z = Mth.floor(box.minZ + 0.01); z <= Mth.floor(box.maxZ - 0.01); z++) junkWhy.remove(new BlockPos(x, y, z));
        }
    }

    /** Junk gone again without ever being used (or, at the end, all still unused) counts as wasted. */
    private void goneJunk(boolean all) {
        junkWhy.entrySet().removeIf(e -> {
            if (!all && !mc.level.getBlockState(e.getKey()).canBeReplaced()) return false;
            junkWasted.merge(e.getValue(), 1, Integer::sum);
            LOGGER.debug("Schematic Builder: wasted junk block at {} ({})", e.getKey(), e.getValue());
            return true;
        });
    }

    private void stop(String why) {
        report = "Stopped: " + why;
        Notify.send("Schematic Builder stopped", why, true, true, true);
        setEnabled(false);
    }

    // ---- Back after a dropped connection (Rejoin)

    @Override
    public boolean wantsRejoin() {
        return rejoin.on() && owner != null && !paused && !plan.isEmpty();
    }

    @Override
    public boolean rejoinUnknown() {
        return rejoin.unknown();
    }

    @Override
    public int rejoinTries() {
        return rejoin.tries();
    }

    @Override
    public long rejoinDelay() {
        return rejoin.delay();
    }

    @Override
    public void rejoinEnded(String why) {
        stop(why);
    }

    /**
     * Back on the server after a rejoin: on with the build once it's where it left, the world's
     * loaded round it and the schematic's still where it was in Litematica.
     */
    private void rejoined(LocalPlayer player) {
        steering = Input.EMPTY;
        digAt = null;
        // Hands off the camera till it carries on: what it was looking at may be nowhere near (a lobby on the way in).
        aim.clear();
        switch (rejoin.back(player)) {
            case HERE -> {
                String moved = placementMoved();
                if (moved != null) stop(moved);
                else carryOn(player);
            }
            case LOST -> stop(rejoin.lostWhy(player));
            default -> {
            }
        }
    }

    /** Why the build can't go on: Litematica has its placement somewhere else now. Null if it hasn't moved (or isn't there to tell). */
    private String placementMoved() {
        if (builtKey == null || !Litematica.installed()) return null;
        boolean elsewhere = false;
        for (Litematica.Placement p : Litematica.placements()) {
            if (!p.key().equals(builtKey)) continue;
            if (p.origin().equals(builtOrigin)) return null;
            elsewhere = true;
        }
        return elsewhere ? "Back on the server, but " + planName + " has been moved in Litematica" : null;
    }

    /**
     * Picks up with the new player. The plan, what's built and the junk blocks it put in stay as
     * they were; what was half done (a walk, a pillar, a purchase) is worked out again from here,
     * and the time it was gone doesn't count.
     */
    private void carryOn(LocalPlayer player) {
        String why = Rejoin.why();
        Rejoin.back();
        long now = System.currentTimeMillis();
        startedAt += rejoin.gone(now);
        // A purchase cut off: what it got before the drop is counted, and the rest can be bought again.
        if (phase == Phase.BUY) {
            spent += buyer.spent();
            if (buyer.got() > 0) bought.merge(buyingItem, buyer.got(), Integer::sum);
            if (buyer.got() > 0 && filled(buyingItem)) bought.merge(Items.BUCKET, buyer.got(), Integer::sum);
            buyTried.remove(buyingItem);
        }
        buyer.cancel();
        seller.cancel();
        owner = player;
        job = null;
        path = null;
        moves = null;
        resumeRoute = false;
        resumeWalk = null;
        clearBehind = false;
        pillarClear = false;
        pillarLadder = false;
        takingDown = false;
        ladderAbort = false;
        dropsUntil = 0;
        ladderDug = false;
        digTo = Integer.MIN_VALUE;
        nodeFor = -1;
        arrivedAt = 0;
        aimed = false;
        lastMoveAt = now;
        nodeProgressAt = now;
        lastTick = now;
        nextActAt = now + 500;
        scanIn = 0;
        steering = Input.EMPTY;
        aim.clear();
        go(Phase.IDLE);
        LOGGER.debug("Schematic Builder: rejoined at {} after: {}", player.blockPosition(), why);
        Notify.send("Schematic Builder back", "Rejoined after: " + Disconnects.brief(why, 56), true, false, false);
    }

    // ---- Drawing

    @Override
    public void onRender3D(Render3D render) {
        if (!showPlan.get() || pending == null) return;
        int accent = Theme.accent();
        int shown = 0;
        for (BlockPos pos : pending) {
            if (++shown > 120) break;
            boolean now = onLayer(pos);
            render.box(new AABB(pos).deflate(0.04), Theme.alpha(accent, now ? 22 : 8), Theme.alpha(accent, now ? 130 : 45), 1f, false);
        }
        for (BlockPos pos : scaffold) render.box(new AABB(pos).deflate(0.02), 0, Theme.alpha(0xFFFFA94D, 160), 1.2f, false);
        for (BlockPos pos : props) render.box(new AABB(pos).deflate(0.02), 0, Theme.alpha(0xFFFFA94D, 160), 1.2f, false);
        if (job != null) {
            render.box(new AABB(job.target()).inflate(0.01), Theme.alpha(accent, 40), accent, 2f, true);
            if (phase == Phase.WALK && path != null) {
                for (int i = Math.max(1, pathIndex); i < path.size(); i++) {
                    render.line(Vec3.atBottomCenterOf(path.get(i - 1)).add(0, 0.05, 0), Vec3.atBottomCenterOf(path.get(i)).add(0, 0.05, 0),
                        ROUTE_COLOR, 2f, true);
                }
            }
        }
    }
}
