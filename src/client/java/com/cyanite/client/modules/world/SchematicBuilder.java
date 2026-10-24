package com.cyanite.client.modules.world;

import com.cyanite.client.gui.Theme;
import com.cyanite.client.litematica.Litematica;
import com.cyanite.client.modules.donut.AuctionBuyer;
import com.cyanite.client.modules.world.area.Paths;
import com.cyanite.client.modules.world.area.Region;
import com.cyanite.client.modules.world.build.Placing;
import com.cyanite.client.modules.world.build.StandIns;
import com.cyanite.client.system.setting.ModeSetting;
import net.minecraft.world.level.block.Block;
import com.cyanite.client.render.Render3D;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.HumanAim;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.Notify;
import com.cyanite.client.system.Steer;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

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
 * shouldn't be there are mined out first with the best tool you have. What's too high to reach
 * from the ground or the build, it towers up to on a pillar of junk blocks, then mines the pillar
 * back down under itself before walking off.
 *
 * <p>The blocks it needs are moved into your hotbar as it goes. What you haven't got, it buys off
 * the auction house with /ah first, under a price cap per item and a budget (see
 * {@link AuctionBuyer}). What costs more than the cap (or isn't listed at all) gets a stand-in
 * instead, cobblestone or whatever's picked, and the spot is marked so you know to come back to
 * it ({@link StandIns}); spots where a full block would spoil things (torches, panes, redstone)
 * are left empty and marked. Built again later with the real block to hand, a stand-in is
 * swapped out for it.
 */
public class SchematicBuilder extends Module {
    private static final int PATH_BUDGET = 40_000;
    /** Eye height while crouched, which is how it places. */
    private static final double EYE = 1.27;
    private static final int SCAN_EVERY = 6;
    private static final int MAX_PILLAR = 10;
    private static final long PLACE_TIMEOUT = 3500;
    private static final long BREAK_TIMEOUT = 9000;
    private static final long WALK_STUCK = 5000;
    private static final int ROUTE_COLOR = 0xFF4DD4FF;
    /** Reach held back when picking somewhere to stand, so where you really end up still reaches. */
    private static final double PLAN_SLACK = 0.45;
    /** Throwaway blocks for pillars. */
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
    private final BoolSetting buy = add(new BoolSetting("Buy from /ah", true));
    /** Off to match DonutSMP's own "auction buy confirm" setting being off: a click on a listing buys it. */
    private final BoolSetting confirmScreen = add(new BoolSetting("AH confirm screen", false));
    private final SliderSetting maxEach = add(new SliderSetting("Max price each", 50, 1, 10_000_000, 1).logarithmic().format(SchematicBuilder::dollars));
    private final SliderSetting budget = add(new SliderSetting("Budget", 250_000, 1_000, 100_000_000_000.0, 1).logarithmic().format(SchematicBuilder::dollars));
    private final BoolSetting standIns = add(new BoolSetting("Stand-ins", true));
    private final ModeSetting standIn = add(new ModeSetting("Stand-in block", "Cobblestone",
        List.of("Cobblestone", "Stone", "Cobbled Deepslate", "Dirt", "Netherrack", "Oak Planks", "Whatever you have")));
    private final BoolSetting showStandIns = add(new BoolSetting("Show stand-ins", true));
    private final BoolSetting showPlan = add(new BoolSetting("Show plan", true));

    /** For tests and other modules: a plan to build instead of Litematica's selection. */
    private static Map<BlockPos, BlockState> given;
    private static String givenName;

    private final HumanAim aim = new HumanAim();
    private final Random random = new Random();
    private final AuctionBuyer buyer = new AuctionBuyer();

    private LocalPlayer owner;
    private Map<BlockPos, BlockState> plan = Map.of();
    private String planName = "";
    private Region bounds;
    private List<BlockPos> pending = new ArrayList<>();
    private int skippedCount;
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
    /** What the buyer's after right now. */
    private Item buyingItem = Items.AIR;
    private final LinkedHashSet<BlockPos> scaffold = new LinkedHashSet<>();
    private int placedCount;
    private int brokenCount;
    private double spent;
    private int scanIn;
    private long startedAt;
    private long nextActAt;

    private enum Phase { IDLE, WALK, PLACE, BREAK, PILLAR, DESCEND, BUY }

    private enum Kind { PLACE, BREAK, PILLAR }

    /** Something to do: place or break {@code target}, standing at {@code stand} (pillaring {@code height} up first). */
    private record Job(Kind kind, BlockPos target, Placing.Way way, BlockPos stand, int height, double cost) {
    }

    /** The layer being built (Layer by layer): the lowest with anything left that hasn't been given up on. */
    private int layer = Integer.MIN_VALUE;
    private Phase phase = Phase.IDLE;
    private long phaseSince;
    private Job job;
    private List<BlockPos> path;
    private int pathIndex;
    private int pillarBase;
    /** Pillar blocks placed so far, and when the last went in (to notice the server taking it back). */
    private int pillarPlaced;
    private long pillarPlacedAt;
    private boolean aimed;
    private Input steering = Input.EMPTY;
    private boolean lookingAhead;
    private Vec3 lastPos = Vec3.ZERO;
    private long lastMoveAt;
    private long lastJumpAt;

    public SchematicBuilder() {
        super(Category.WORLD, "Schematic Builder", "Builds your Litematica schematic like a player, buying blocks off /ah");
        showStandIns.onChange(() -> StandIns.setShown(showStandIns.get()));
    }

    private static String dollars(double v) {
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

    @Override
    public String getInfo() {
        if (plan.isEmpty()) return null;
        int total = plan.size() - skippedCount;
        String done = planName + " " + (total - pending.size()) + "/" + total;
        if (!layered.get() || bounds == null || layer == Integer.MIN_VALUE) return done;
        return done + ", layer " + (layer - bounds.minY() + 1) + "/" + bounds.sizeY();
    }

    /** How the builder wants the player to move this tick, or null when it isn't driving. */
    public Input steering() {
        return isEnabled() && owner != null && owner == mc.player ? steering : null;
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
        stood.clear();
        leftEmpty.clear();
        StandIns.setShown(showStandIns.get());
        passed.clear();
        tries.clear();
        buyTried.clear();
        scaffold.clear();
        placedCount = 0;
        brokenCount = 0;
        spent = 0;
        scanIn = 0;
        startedAt = System.currentTimeMillis();
        nextActAt = 0;
        job = null;
        go(Phase.IDLE);
        Notify.send("Schematic Builder", "Building " + planName + ": " + (plan.size() - skippedCount) + " blocks", false, false, false);
    }

    @Override
    protected void onDisable() {
        buyer.cancel();
        steering = Input.EMPTY;
        aim.clear();
        job = null;
        given = null;
    }

    private boolean loadPlan() {
        Map<BlockPos, BlockState> states = null;
        String name = "";
        if (given != null) {
            states = given;
            name = givenName;
        } else if (!Litematica.installed()) {
            Notify.send("Schematic Builder", "Needs Litematica, to place the schematic where you want it built", true, false, false);
            return false;
        } else {
            Litematica.Placement chosen = null;
            List<Litematica.Placement> enabled = new ArrayList<>();
            for (Litematica.Placement p : Litematica.placements()) {
                if (p.selected()) chosen = p;
                if (p.enabled()) enabled.add(p);
            }
            if (chosen == null && enabled.size() == 1) chosen = enabled.getFirst();
            if (chosen == null) {
                Notify.send("Schematic Builder", "Select a schematic placement in Litematica first", true, false, false);
                return false;
            }
            states = Litematica.states(chosen.key(), chosen.origin(), 400_000);
            name = chosen.name();
            if (states == null) {
                Notify.send("Schematic Builder", "Couldn't read " + chosen.name() + " (too big?)", true, false, false);
                return false;
            }
        }
        if (states.isEmpty()) {
            Notify.send("Schematic Builder", "That schematic has no blocks in it", true, false, false);
            return false;
        }
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
        return true;
    }

    @Override
    public void onFrame() {
        if (mc.isPaused()) {
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
        LocalPlayer player = mc.player;
        if (player != owner || player.isDeadOrDying()) {
            stop("You died or changed worlds");
            return;
        }
        if (mc.isPaused()) {
            steering = Input.EMPTY;
            return;
        }
        long now = System.currentTimeMillis();
        if (phase == Phase.BUY) {
            buying(now);
            return;
        }
        if (--scanIn <= 0) scan();
        switch (phase) {
            case IDLE -> decide(player, now);
            case WALK -> walk(player, now);
            case PLACE -> place(player, now);
            case BREAK -> dig(player, now);
            case PILLAR -> pillar(player, now);
            case DESCEND -> descend(player, now);
            default -> {
            }
        }
    }

    /** What's still to do: every planned spot that isn't right yet, lowest first, then nearest. */
    private void scan() {
        scanIn = SCAN_EVERY;
        List<BlockPos> left = new ArrayList<>();
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
            left.add(pos);
        }
        Vec3 at = mc.player.position();
        left.sort(Comparator.<BlockPos>comparingInt(p -> p.getY()).thenComparingDouble(p -> p.distToCenterSqr(at.x, at.y, at.z)));
        pending = left;
    }

    /** A planned spot as done as it'll get: the right block, a stand-in for it, or given up on and left empty. */
    private boolean settled(BlockPos pos) {
        BlockState have = mc.level.getBlockState(pos);
        if (Placing.matches(have, plan.get(pos))) return true;
        Block standing = stood.get(pos);
        return standing != null && have.is(standing) || leftEmpty.contains(pos);
    }

    /** No block to be had for it (none on you, and buying's off or came to nothing). */
    private boolean gaveUp(Item item) {
        return count(item) == 0 && (!buy.get() || buyTried.contains(item));
    }

    /** Whether a stand-in can go here: wanted on, the spot wants a full block, and a stand-in's to be had. */
    private boolean standInFits(BlockPos pos, BlockState want) {
        Item sub = standInItem();
        return standIns.get() && sub != null && want.isCollisionShapeFullBlock(mc.level, pos) && !gaveUp(sub);
    }

    private boolean cantFill(BlockPos pos, BlockState want) {
        return gaveUp(want.getBlock().asItem()) && !standInFits(pos, want);
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
        if (count(want.getBlock().asItem()) > 0) return want;
        if (!gaveUp(want.getBlock().asItem()) || !standInFits(pos, want)) return null;
        Item sub = standInItem();
        return count(sub) > 0 && sub instanceof BlockItem block ? block.getBlock().defaultBlockState() : null;
    }

    private void decide(LocalPlayer player, long now) {
        steering = Input.EMPTY;
        if (now < nextActAt || !player.onGround()) return;
        if (pending.isEmpty()) {
            if (onScaffold(player)) startDescend();
            else finish();
            return;
        }
        // What you've not got: bought first, the way you'd fetch your materials before starting.
        if (buy.get()) {
            Map.Entry<Item, Integer> short_ = shortage(player);
            if (short_ != null) {
                buyer.start(short_.getKey(), short_.getValue(), maxEach.get(), budget.get() - spent, confirmScreen.get());
                buyTried.add(short_.getKey());
                buyingItem = short_.getKey();
                go(Phase.BUY);
                return;
            }
        }
        layer = currentLayer();
        Job here = fromHere(player);
        if (here != null) {
            begin(here);
            return;
        }
        // Up a pillar with the next job just out of reach: build it higher rather than climb down and back up.
        if (onScaffold(player) && buildUp.get()) {
            Job higher = higher(player);
            if (higher != null) {
                job = higher;
                pillarBase = player.blockPosition().getY();
                pillarPlaced = 0;
                aimed = false;
                go(Phase.PILLAR);
                return;
            }
        }
        Paths paths = Paths.search(mc.level, player.blockPosition(), bounds.grow(8, 6), null, PATH_BUDGET);
        Job walk = walkTo(paths);
        if (walk == null && buildUp.get()) walk = tower(paths, player);
        if (walk != null) {
            // Come down off a pillar before walking anywhere.
            if (onScaffold(player)) {
                startDescend();
                return;
            }
            job = walk;
            path = paths.pathTo(walk.stand());
            pathIndex = 1;
            lookingAhead = false;
            lastMoveAt = now;
            if (path == null) {
                pass(walk.target(), 10_000);
                return;
            }
            go(Phase.WALK);
            return;
        }
        if (onScaffold(player)) {
            startDescend();
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
        // Every one tried three times over and none doable: that's as far as it goes. Until then,
        // wait for the ones set aside to come round again.
        if (setAside == 0 && pending.stream().allMatch(p -> tries.getOrDefault(p, 0) >= 3)) finish();
    }

    // ---- Picking the next job

    /** The lowest, nearest thing that can be done from right where you stand. */
    private Job fromHere(LocalPlayer player) {
        Vec3 eye = player.position().add(0, EYE, 0);
        int lowest = pending.getFirst().getY();
        Job best = null;
        for (BlockPos pos : pending) {
            if (!onLayer(pos) || passedOver(pos) || Vec3.atCenterOf(pos).distanceTo(eye) > reach.get() + 1.2) continue;
            Job j = jobAt(pos, player.blockPosition(), eye, reach.get());
            if (j == null) continue;
            double cost = (pos.getY() - lowest) * 10 + angleTo(player, j) * 0.04 + Vec3.atCenterOf(pos).distanceTo(eye);
            if (best == null || cost < best.cost()) best = new Job(j.kind(), j.target(), j.way(), j.stand(), 0, cost);
        }
        return best;
    }

    /** The cheapest walk to somewhere a low, near job can be done from. */
    private Job walkTo(Paths paths) {
        int lowest = pending.getFirst().getY();
        Job best = null;
        int looked = 0;
        for (BlockPos pos : pending) {
            if (!onLayer(pos) || passedOver(pos)) continue;
            if (++looked > 40 || best != null && pos.getY() > lowest + 2) break;
            for (int dy = -4; dy <= 1; dy++) {
                for (int dx = -4; dx <= 4; dx++) {
                    for (int dz = -4; dz <= 4; dz++) {
                        BlockPos stand = pos.offset(dx, dy, dz);
                        int d = paths.distance(stand);
                        if (d < 0) continue;
                        double cost = d + (pos.getY() - lowest) * 6 + (plan.containsKey(stand) && pending.contains(stand) ? 6 : 0);
                        if (best != null && cost >= best.cost()) continue;
                        Job j = jobAt(pos, stand, Vec3.atBottomCenterOf(stand).add(0, EYE, 0), reach.get() - PLAN_SLACK);
                        if (j != null) best = new Job(j.kind(), pos, j.way(), stand, 0, cost);
                    }
                }
            }
        }
        return best;
    }

    /**
     * Out of reach from anywhere it can walk: a pillar up beside it. A spot it can walk to with
     * room overhead, and the fewest blocks of pillar that bring the job into reach.
     */
    private Job tower(Paths paths, LocalPlayer player) {
        if (scaffoldSlot(player, false) < 0) return null;
        Job best = null;
        int looked = 0;
        for (BlockPos pos : pending) {
            if (!onLayer(pos) || passedOver(pos)) continue;
            if (++looked > 12) break;
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    for (int dy = -MAX_PILLAR - 2; dy <= 0; dy++) {
                        BlockPos base = pos.offset(dx, dy, dz);
                        int d = paths.distance(base);
                        if (d < 0) continue;
                        for (int h = 1; h <= MAX_PILLAR; h++) {
                            BlockPos top = base.above(h);
                            // Room for the pillar block, and for you on top of it; never in a planned spot.
                            BlockPos block = base.above(h - 1);
                            if (h > 1 && !Paths.passable(mc.level, block) || !Paths.passable(mc.level, top) || !Paths.passable(mc.level, top.above())
                                || plan.containsKey(block)) break;
                            if (top.equals(pos) || top.above().equals(pos)) break;
                            double cost = d + h * 4 + (pos.getY() - pending.getFirst().getY()) * 6;
                            if (best != null && cost >= best.cost()) break;
                            Job j = jobAt(pos, top, Vec3.atBottomCenterOf(top).add(0, EYE, 0), reach.get() - PLAN_SLACK);
                            if (j != null) {
                                best = new Job(Kind.PILLAR, pos, j.way(), base, h, cost);
                                break;
                            }
                        }
                    }
                }
            }
        }
        return best;
    }

    /** The fewest more blocks on the pillar you're standing on that bring a job into reach. */
    private Job higher(LocalPlayer player) {
        if (scaffoldSlot(player, false) < 0) return null;
        BlockPos base = player.blockPosition();
        int looked = 0;
        Job best = null;
        for (BlockPos pos : pending) {
            if (!onLayer(pos) || passedOver(pos)) continue;
            if (++looked > 12) break;
            for (int h = 1; h <= MAX_PILLAR; h++) {
                BlockPos top = base.above(h);
                if (!Paths.passable(mc.level, top) || !Paths.passable(mc.level, top.above()) || plan.containsKey(base.above(h - 1))
                    || top.equals(pos) || top.above().equals(pos)) break;
                if (best != null && h >= best.height()) break;
                Job j = jobAt(pos, top, Vec3.atBottomCenterOf(top).add(0, EYE, 0), reach.get() - PLAN_SLACK);
                if (j != null) {
                    best = new Job(Kind.PILLAR, pos, j.way(), base, h, h);
                    break;
                }
            }
        }
        return best;
    }

    /**
     * What doing the planned spot {@code pos} from {@code stand} (eye at {@code eye}, reaching
     * {@code reachLimit}) would take: a break if something wrong is in it, a placement otherwise.
     * Null if it can't be done from there.
     */
    private Job jobAt(BlockPos pos, BlockPos stand, Vec3 eye, double reachLimit) {
        BlockState want = plan.get(pos);
        BlockState have = mc.level.getBlockState(pos);
        if (inTheWay(pos, have, want)) {
            if (!breakWrong.get() || stand.below().equals(pos)) return null;
            Vec3 face = visibleFace(eye, pos, reachLimit);
            return face == null ? null : new Job(Kind.BREAK, pos, null, stand, 0, 0);
        }
        // Bottom up: nothing goes on top of a planned spot that isn't right yet, or that spot gets buried.
        BlockState below = plan.get(pos.below());
        if (below != null && !Placing.skipped(below) && !settled(pos.below())) return null;
        BlockState placing = toPlace(pos, want);
        if (placing == null) return null;
        Item item = placing.getBlock().asItem();
        // Never into the space you stand in.
        AABB body = new AABB(stand.getX() + 0.2, stand.getY(), stand.getZ() + 0.2, stand.getX() + 0.8, stand.getY() + 1.8, stand.getZ() + 0.8);
        if (body.intersects(new AABB(pos)) && !placing.getCollisionShape(mc.level, pos).isEmpty()) return null;
        for (Placing.Way way : waysFor(pos, placing, item)) {
            if (way.hit().distanceTo(eye) > reachLimit) continue;
            float[] rot = rotation(eye, way.hit());
            if (!way.fits(rot[0], rot[1]) || !visible(eye, way)) continue;
            return new Job(Kind.PLACE, pos, way, stand, 0, 0);
        }
        return null;
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
        for (Direction d : Direction.values()) ways.remove(pos.relative(d));
        scanIn = 0;
    }

    /** Whether looking at the way's spot from the eye really lands on that face of that block. */
    private boolean visible(Vec3 eye, Placing.Way way) {
        Vec3 into = way.hit().subtract(way.face().getStepX() * 0.02, way.face().getStepY() * 0.02, way.face().getStepZ() * 0.02);
        BlockHitResult hit = mc.level.clip(new ClipContext(eye, into, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(way.against()) && hit.getDirection() == way.face();
    }

    /** The middle of a face of {@code pos} the eye can see and reach, or null. */
    private Vec3 visibleFace(Vec3 eye, BlockPos pos, double reachLimit) {
        Vec3 best = null;
        double bestDist = Double.MAX_VALUE;
        for (Direction face : Direction.values()) {
            Vec3 point = Placing.facePoint(pos, face, 0.5);
            double dist = point.distanceTo(eye);
            if (dist > reachLimit || dist >= bestDist) continue;
            Vec3 into = point.subtract(face.getStepX() * 0.02, face.getStepY() * 0.02, face.getStepZ() * 0.02);
            BlockHitResult hit = mc.level.clip(new ClipContext(eye, into, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)) {
                best = point;
                bestDist = dist;
            }
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
        tries.merge(pos, 1, Integer::sum);
    }

    // ---- Doing it

    private void begin(Job j) {
        job = j;
        aimed = false;
        go(j.kind() == Kind.BREAK ? Phase.BREAK : Phase.PLACE);
    }

    private void walk(LocalPlayer player, long now) {
        if (path == null || pathIndex >= path.size()) {
            // Arrived: onto the middle of the spot first, where it was planned from.
            Vec3 middle = Vec3.atBottomCenterOf(job.stand());
            if (Math.hypot(middle.x - player.getX(), middle.z - player.getZ()) > 0.18 && now - phaseSince < WALK_STUCK) {
                steering = Steer.toward(player, middle, false, false);
                return;
            }
            steering = Input.EMPTY;
            if (!player.onGround()) return;
            if (job.kind() == Kind.PILLAR) {
                pillarBase = player.blockPosition().getY();
                pillarPlaced = 0;
                aimed = false;
                go(Phase.PILLAR);
                return;
            }
            // The job from where you really are; if it doesn't work after all, leave it a while.
            Job j = jobAt(job.target(), player.blockPosition(), player.position().add(0, EYE, 0), reach.get());
            if (j != null) {
                begin(j);
            } else {
                pass(job.target(), 10_000);
                go(Phase.IDLE);
            }
            return;
        }
        BlockPos node = path.get(pathIndex);
        Vec3 target = Vec3.atBottomCenterOf(node);
        double horizontal = Math.hypot(target.x - player.getX(), target.z - player.getZ());
        boolean up = node.getY() > player.getY() + 0.5;
        if (horizontal < 0.3 && (!up || player.getY() > node.getY() - 0.2)) {
            pathIndex++;
            lastMoveAt = now;
            return;
        }
        // Look on ahead on a long walk, at the job once it's close.
        int remaining = path.size() - 1 - pathIndex;
        Vec3 look = remaining > 2
            ? Vec3.atBottomCenterOf(path.get(Math.min(path.size() - 1, pathIndex + 2))).add(0, 1.5, 0)
            : job.way() != null ? job.way().hit() : Vec3.atCenterOf(job.target());
        if (!lookingAhead || remaining <= 2) aim.setTarget(look);
        else aim.track(look);
        lookingAhead = remaining > 2;

        if (player.position().distanceToSqr(lastPos) > 0.01) {
            lastPos = player.position();
            lastMoveAt = now;
        }
        if (now - lastMoveAt > WALK_STUCK) {
            pass(job.target(), 15_000);
            go(Phase.IDLE);
            return;
        }
        boolean stuck = now - lastMoveAt > 900;
        boolean jump = player.onGround() && (up && horizontal < 1.4 || stuck && now - lastJumpAt > 800);
        if (jump) lastJumpAt = now;
        steering = Steer.toward(player, target, jump, false);
    }

    private void place(LocalPlayer player, long now) {
        Placing.Way way = job.way();
        BlockState wanted = plan.get(job.target());
        BlockState placing = toPlace(job.target(), wanted);
        if (placing == null) {
            pass(job.target(), 20_000);
            go(Phase.IDLE);
            return;
        }
        Item item = placing.getBlock().asItem();
        // Crouched, so clicking a chest or a door places against it instead of opening it.
        steering = new Input(false, false, false, false, false, true, false);
        if (!aimed) {
            aim.setTarget(way.hit());
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
        if (settled(job.target())) {
            done();
            return;
        }
        if (now - phaseSince > PLACE_TIMEOUT) {
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
            placedCount++;
            if (placing != wanted) {
                stood.put(job.target().immutable(), placing.getBlock());
                StandIns.mark(job.target(), wanted.getBlock(), placing.getBlock());
            }
            changedAt(job.target());
            done();
        }
    }

    private void dig(LocalPlayer player, long now) {
        steering = Input.EMPTY;
        BlockPos pos = job.target();
        if (!inTheWay(pos, mc.level.getBlockState(pos), plan.get(pos))) {
            brokenCount++;
            changedAt(pos);
            done();
            return;
        }
        if (now - phaseSince > BREAK_TIMEOUT) {
            pass(pos, 20_000);
            go(Phase.IDLE);
            return;
        }
        int tool = toolFor(mc.level.getBlockState(pos));
        if (tool >= 0 && player.getInventory().getSelectedSlot() != tool) player.getInventory().setSelectedSlot(tool);
        Vec3 face = visibleFace(player.getEyePosition(), pos, reach.get());
        if (face == null) {
            pass(pos, 10_000);
            go(Phase.IDLE);
            return;
        }
        if (!aimed || aim.target() == null || aim.target().distanceTo(face) > 0.6) {
            aim.setTarget(face);
            aimed = true;
        }
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)
            && mc.gameMode.continueDestroyBlock(pos, hit.getDirection())) {
            player.swing(InteractionHand.MAIN_HAND);
        }
    }

    /** Jumps and puts a block under its feet at the top of each jump, the way a player towers up. */
    private void pillar(LocalPlayer player, long now) {
        // The last block gone again (the server didn't have it): put it in again.
        if (pillarPlaced > 0 && now - pillarPlacedAt > 600) {
            BlockPos last = new BlockPos(job.stand().getX(), pillarBase + pillarPlaced - 1, job.stand().getZ());
            if (!mc.level.getBlockState(last).isSolid()) {
                scaffold.remove(last);
                pillarPlaced--;
            }
        }
        if (pillarPlaced >= job.height() && player.onGround() && player.getY() >= pillarBase + job.height() - 0.01) {
            steering = Input.EMPTY;
            go(Phase.IDLE);
            return;
        }
        if (now - phaseSince > 4000L + job.height() * 1500L) {
            pass(job.target(), 20_000);
            steering = Input.EMPTY;
            go(Phase.IDLE);
            return;
        }
        int slot = scaffoldSlot(player, true);
        if (slot < 0) {
            buildUp.set(false);
            Notify.send("Schematic Builder", "Out of throwaway blocks for pillars", false, false, false);
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
        BlockPos cell = new BlockPos(job.stand().getX(), pillarBase + pillarPlaced, job.stand().getZ());
        // Stay over the pillar, and jump while there's more to build.
        Vec3 middle = Vec3.atBottomCenterOf(cell);
        Input drift = Math.hypot(middle.x - player.getX(), middle.z - player.getZ()) > 0.15
            ? Steer.toward(player, middle, false, false) : Input.EMPTY;
        boolean more = pillarPlaced < job.height();
        steering = new Input(drift.forward(), drift.backward(), drift.left(), drift.right(), more && player.onGround(), false, false);
        // Near the top of the jump, your feet well clear of the spot they just left: fill it. (Any lower
        // and the server, a moment behind, still has you in it and turns the block down.)
        if (!player.onGround() && player.getY() > cell.getY() + 1.12 && mc.level.getBlockState(cell).canBeReplaced()
            && mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
            && hit.getBlockPos().equals(cell.below()) && hit.getDirection() == Direction.UP
            && mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit).consumesAction()) {
            player.swing(InteractionHand.MAIN_HAND);
            scaffold.add(cell.immutable());
            pillarPlaced++;
            pillarPlacedAt = now;
            changedAt(cell);
        }
    }

    private void startDescend() {
        aimed = false;
        go(Phase.DESCEND);
    }

    /** Mines the pillar out from under its feet, a block at a time, back down to the ground. */
    private void descend(LocalPlayer player, long now) {
        steering = Input.EMPTY;
        BlockPos under = player.blockPosition().below();
        if (!player.onGround()) return;
        if (!scaffold.contains(under)) {
            scaffold.removeIf(p -> !mc.level.getBlockState(p).isSolid());
            go(Phase.IDLE);
            return;
        }
        if (mc.level.getBlockState(under).isAir()) {
            scaffold.remove(under);
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
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(under)
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
        phase = next;
        phaseSince = System.currentTimeMillis();
    }

    // ---- Materials

    /** The item it's shortest of for what's left, and how many more it needs; null if it's got everything it can buy. */
    private Map.Entry<Item, Integer> shortage(LocalPlayer player) {
        Map<Item, Integer> needed = new HashMap<>();
        for (BlockPos pos : pending) {
            BlockState want = plan.get(pos);
            BlockState have = mc.level.getBlockState(pos);
            int n = want.getBlock() instanceof SlabBlock && want.getValue(SlabBlock.TYPE) == net.minecraft.world.level.block.state.properties.SlabType.DOUBLE
                && have.getBlock() != want.getBlock() ? 2 : 1;
            Item item = want.getBlock().asItem();
            // Given up on: it's a stand-in that's wanted instead.
            if (gaveUp(item)) {
                if (standInFits(pos, want)) needed.merge(standInItem(), 1, Integer::sum);
                continue;
            }
            needed.merge(item, n, Integer::sum);
        }
        Map.Entry<Item, Integer> worst = null;
        for (Map.Entry<Item, Integer> e : needed.entrySet()) {
            if (buyTried.contains(e.getKey())) continue;
            int lacking = e.getValue() - count(e.getKey());
            if (lacking > 0 && (worst == null || lacking > worst.getValue())) worst = Map.entry(e.getKey(), lacking);
        }
        return worst;
    }

    private void buying(long now) {
        steering = Input.EMPTY;
        AuctionBuyer.Result r = buyer.tick();
        if (r == AuctionBuyer.Result.RUNNING) return;
        spent += buyer.spent();
        Item item = buyingItem;
        String what = AuctionBuyer.name(item);
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
        for (int i = 0; i < 9; i++) if (SCAFFOLD.contains(inventory.getItem(i).getItem())) return i;
        for (int i = 9; i < 36; i++) {
            if (SCAFFOLD.contains(inventory.getItem(i).getItem())) return bring ? bringUp(i) : i;
        }
        return -1;
    }

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
        for (BlockPos pos : pending.subList(0, Math.min(pending.size(), 30))) planned.add(plan.get(pos).getBlock().asItem());
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

    private void finish() {
        int left = pending.size();
        double seconds = (System.currentTimeMillis() - startedAt) / 1000.0;
        StringBuilder sb = new StringBuilder();
        sb.append("Placed ").append(placedCount).append(brokenCount > 0 ? ", broke " + brokenCount : "")
            .append(" in ").append(seconds < 60 ? Math.round(seconds) + "s" : (int) seconds / 60 + "m " + Math.round(seconds % 60) + "s");
        if (spent > 0) sb.append(", spent ").append(dollars(spent));
        if (left > 0) {
            Map<Item, Integer> lacking = new HashMap<>();
            for (BlockPos pos : pending) lacking.merge(plan.get(pos).getBlock().asItem(), 1, Integer::sum);
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
        if (skippedCount > 0) sb.append(". Skipped ").append(skippedCount).append(" (water, lava and the like)");
        Notify.send(left == 0 ? "Schematic built" : "Schematic Builder stopped", sb.toString(), true, true, true);
        setEnabled(false);
    }

    private void stop(String why) {
        Notify.send("Schematic Builder stopped", why, true, true, true);
        setEnabled(false);
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
