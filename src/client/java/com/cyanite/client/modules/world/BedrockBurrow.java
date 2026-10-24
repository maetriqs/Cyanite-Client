package com.cyanite.client.modules.world;

import com.cyanite.client.modules.world.area.AreaPlan;
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
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.SliderSetting;
import com.cyanite.client.system.setting.StringSetting;
import net.minecraft.network.chat.Component;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Fallable;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Takes you down to bedrock and closes the way behind you, so nobody can follow the hole. It
 * starts by checking the column under you, block by block down to bedrock, from the chunk data
 * you already have: a cave, lava or water on the way means a hole someone could find, or worse.
 * If yours isn't clean it looks around for the nearest column that is and walks you there.
 *
 * <p>Then it digs straight down, one block at a time, with the same human aim and held attack
 * as Auto Mine, using a normal pickaxe (never the drill, the Shard Pickaxe, which cuts 3 wide). Every few
 * blocks it looks up and puts back what it dug out, highest first, so the shaft fills in above
 * you: the same block where you have it, the nearest thing to it where you don't (stone drops
 * cobblestone without Silk Touch). Sand and gravel would fall on your head, so those are swapped
 * for something that stays put. At the bottom you're sealed in on bedrock with room to start.
 *
 * <p>With the cave check on, the bottom isn't the end: once there it looks over a 30x30 square
 * round you, from bedrock up to Y -10, for caves, lava and water. The rock down there only shows
 * for real once you're near it (Donut hides what's under the deepslate from the surface), which
 * is why it digs first and looks after. Any caves and it does {@code /rtp} and digs down again
 * wherever that lands you; a clean square and you're disconnected, the reason saying "Spot
 * found!", ready to come back to a hidden base.
 *
 * <p>It keeps going with a menu open (your inventory, a chest, chat). The game stops handling
 * attack and use while a screen is up, so then it mines and places through the game mode itself.
 */
public class BedrockBurrow extends Module {
    private static final int SEAL_BATCH = 3;
    private static final long BLOCK_TIMEOUT = 15_000;
    private static final long PLACE_TIMEOUT = 4000;
    private static final long CENTER_TIMEOUT = 4000;
    private static final int COLUMNS_PER_TICK = 24;

    private enum Phase { SCOUT, WALK, DIG, SEAL, LAST, RTP }

    /** This many open blocks (air, water, lava) round the spot and it counts as caves. */
    private static final int CAVE_OPEN = 8;
    private static final int SCAN_COLUMNS_PER_TICK = 160;
    private static final long RTP_WAIT = 30_000;
    private static final long LANDED_SETTLE = 2_000;
    /** At the bottom, how long to let the server send the real rock round you before looking. */
    private static final long BOTTOM_SETTLE = 3_000;

    private final BoolSetting findSpot = add(new BoolSetting("Find a clean spot", true));
    private final SliderSetting radius = add(new SliderSetting("Search radius", 12, 2, 32, 1).format(v -> (int) v + " blocks"));
    private final BoolSetting seal = add(new BoolSetting("Seal behind you", true));
    private final ModeSetting fill = add(new ModeSetting("Fill with", "Similar blocks", List.of("Same blocks", "Similar blocks", "Anything solid")));
    private final ModeSetting stopAt = add(new ModeSetting("Stop at", "Bedrock", List.of("Bedrock", "Y level")));
    private final SliderSetting stopY = add(new SliderSetting("Stop Y", -50, -60, 300, 1).format(v -> "Y " + (int) v));
    private final SliderSetting lavaGap = add(new SliderSetting("Lava gap", 2, 1, 4, 1).format(v -> (int) v + (v == 1 ? " block" : " blocks")));
    private final BoolSetting stopHurt = add(new BoolSetting("Stop when hurt", true));
    private final BoolSetting caveCheck = add(new BoolSetting("Cave check", true));
    private final SliderSetting checkSize = add(new SliderSetting("Check area", 30, 10, 64, 2).format(v -> (int) v + "x" + (int) v));
    private final SliderSetting checkTop = add(new SliderSetting("Check up to", -10, -60, 64, 1).format(v -> "Y " + (int) v));
    private final StringSetting rtpCommand = add(new StringSetting("RTP command", "rtp"));
    private final SliderSetting maxRtps = add(new SliderSetting("Max RTPs", 10, 1, 50, 1).format(v -> (int) v + (v == 1 ? " try" : " tries")));
    private final BoolSetting disconnect = add(new BoolSetting("Disconnect when found", true));
    private final SliderSetting turnSpeed = add(new SliderSetting("Turn speed", 1.2, 0.3, 3, 0.1).format(v -> String.format(Locale.ROOT, "%.1fx", v)));
    private final BoolSetting toast = add(new BoolSetting("Toast", true));
    private final BoolSetting sound = add(new BoolSetting("Sound", true));
    private final BoolSetting flash = add(new BoolSetting("Flash taskbar", true));

    private final HumanAim aim = new HumanAim();
    /** Every block dug out of the shaft, with what it was. */
    private final Map<BlockPos, BlockState> dug = new LinkedHashMap<>();

    private Phase phase = Phase.SCOUT;
    private LocalPlayer owner;
    /** Where your feet go at the top of the shaft. */
    private BlockPos column;
    private Input steering;
    private boolean attacking;
    private long phaseSince;
    private float lastHealth;
    private int placed;
    private int skipped;
    private boolean warnedNoBlocks;
    private int startSlot = -1;

    // Scouting, a few columns a tick.
    private List<BlockPos> candidates;
    private int candidateIndex;
    private String firstProblem;

    // Walking to the spot.
    private List<BlockPos> path;
    private int pathIndex;

    // Digging.
    private BlockPos digging;
    private Vec3 digPoint;
    private long digSince;

    // Sealing.
    private BlockPos sealing;
    private long sealSince;
    private BlockPos clickPos;
    private Direction clickFace;
    private Vec3 clickPoint;
    private int clickedAt = -1;
    private int ticks;

    // The cave check, a slice of the area a tick.
    private AreaScan scan;
    // /rtp: how many so far, and waiting for the teleport.
    private int rtps;
    private boolean rtpSent;
    private int rtpAttempts;
    private long rtpAt;
    private Vec3 rtpFrom;
    private Object rtpLevel;
    private long landedAt;
    private boolean toldMenu;

    /** Open blocks round a spot, counted a few columns at a time. */
    private static final class AreaScan {
        final BlockPos centre;
        final int yLo;
        final int yHi;
        final List<int[]> columns = new ArrayList<>();
        int next;
        int open;
        boolean lava;
        boolean water;
        BlockPos nearest;
        double nearestDistance = Double.MAX_VALUE;
        long startedAt = System.currentTimeMillis();

        /** A {@code size} by {@code size} square centred on {@code centre}, your own shaft left out. */
        AreaScan(BlockPos centre, int yLo, int yHi, int size) {
            this.centre = centre;
            this.yLo = yLo;
            this.yHi = yHi;
            int lo = -(size / 2);
            int hi = lo + size - 1;
            for (int dx = lo; dx <= hi; dx++) {
                for (int dz = lo; dz <= hi; dz++) {
                    if (dx != 0 || dz != 0) columns.add(new int[] {dx, dz});
                }
            }
        }
    }

    public BedrockBurrow() {
        super(Category.WORLD, "Bedrock Burrow", "Digs down to bedrock and seals the shaft behind you");
    }

    @Override
    public String getInfo() {
        if (!isEnabled() || mc.player == null) return null;
        return switch (phase) {
            case SCOUT -> "Scouting";
            case LAST -> "Cave check";
            case RTP -> "RTP " + rtps + "/" + maxRtps.get().intValue();
            case WALK -> "Walking";
            case SEAL -> "Sealing";
            case DIG -> "Y " + mc.player.getBlockY();
        };
    }

    /** How it wants the player to move, or null when it isn't driving. */
    public Input steering() {
        return isEnabled() && owner != null && owner == mc.player ? steering : null;
    }

    public boolean shouldAttack() {
        return isEnabled() && attacking && mc.screen == null;
    }

    /** True while it picks the tools itself, so Auto Tool keeps its hands off. */
    public boolean managesTools() {
        return isEnabled() && owner != null;
    }

    @Override
    protected void onEnable() {
        owner = inGame() ? mc.player : null;
        dug.clear();
        placed = 0;
        skipped = 0;
        warnedNoBlocks = false;
        steering = Input.EMPTY;
        attacking = false;
        sealing = null;
        candidates = null;
        column = null;
        digging = null;
        scan = null;
        rtps = 0;
        aim.clear();
        if (owner == null) return;
        // Only one thing steers the player at a time.
        AutoMine autoMine = Modules.get(AutoMine.class);
        if (autoMine != null && autoMine.isEnabled()) autoMine.setEnabled(false);
        AreaMiner areaMiner = Modules.get(AreaMiner.class);
        if (areaMiner != null && areaMiner.isEnabled()) areaMiner.stop();
        lastHealth = owner.getHealth();
        startSlot = owner.getInventory().getSelectedSlot();
        if (pickaxe(Blocks.STONE.defaultBlockState()) < 0) {
            stop("Bedrock Burrow can't start", "Put a normal pickaxe in your hotbar. Not the drill (Shard Pickaxe): it cuts 3 wide");
            return;
        }
        enter(Phase.SCOUT);
    }

    @Override
    protected void onDisable() {
        steering = null;
        attacking = false;
        aim.clear();
        if (inGame() && owner == mc.player && startSlot >= 0) mc.player.getInventory().setSelectedSlot(startSlot);
        owner = null;
    }

    private void enter(Phase next) {
        phase = next;
        phaseSince = System.currentTimeMillis();
    }

    private void stop(String title, String body) {
        Notify.send(title, body, toast.get(), sound.get(), flash.get());
        setEnabled(false);
    }

    @Override
    public void onFrame() {
        if (mc.isPaused()) {
            aim.idle();
            return;
        }
        aim.speed = turnSpeed.get();
        aim.randomness = 0.3;
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
            // An /rtp to another world gives you a new player: that's expected.
            if (phase == Phase.RTP && player != null) {
                owner = player;
                lastHealth = player.getHealth();
            } else {
                stop("Bedrock Burrow stopped", "You died or changed worlds");
                return;
            }
        }
        ticks++;
        float health = player.getHealth();
        if (stopHurt.get() && health < lastHealth && phase != Phase.RTP) {
            stop("Bedrock Burrow stopped", "You took damage at " + coords(player.blockPosition()));
            return;
        }
        lastHealth = health;
        // Only a paused singleplayer world stops it; any other menu it works straight through.
        if (mc.isPaused()) {
            steering = Input.EMPTY;
            attacking = false;
            return;
        }
        switch (phase) {
            case SCOUT -> scout(player);
            case WALK -> walk(player);
            case DIG -> dig(player);
            case SEAL -> seal(player);
            case LAST -> areaCheck(player);
            case RTP -> rtp(player);
        }
    }

    // ---- Finding a clean column

    private void scout(LocalPlayer player) {
        steering = Input.EMPTY;
        if (!player.onGround()) return;
        if (candidates == null) {
            BlockPos feet = player.blockPosition();
            String problem = check(feet.below());
            if (problem == null) {
                startDigging(feet);
                return;
            }
            if (!findSpot.get()) {
                stop("Bedrock Burrow stopped", "This column isn't safe: " + problem);
                return;
            }
            firstProblem = problem;
            candidates = spiral(feet, radius.get().intValue());
            candidateIndex = 1;
        }
        int level = player.getBlockY() - 1;
        for (int n = 0; n < COLUMNS_PER_TICK && candidateIndex < candidates.size(); n++, candidateIndex++) {
            BlockPos c = candidates.get(candidateIndex);
            if (!mc.level.hasChunkAt(c)) continue;
            int top = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c.getX(), c.getZ()) - 1;
            // Somewhere you can walk to: about the same height, and real ground on top.
            if (Math.abs(top - level) > 4) continue;
            BlockPos ground = new BlockPos(c.getX(), top, c.getZ());
            BlockState state = mc.level.getBlockState(ground);
            if (!state.isCollisionShapeFullBlock(mc.level, ground) || !state.getFluidState().isEmpty()) continue;
            if (!Paths.standable(mc.level, ground.above())) continue;
            if (check(ground) != null) continue;
            planWalk(player, ground.above());
            return;
        }
        if (candidateIndex >= candidates.size()) {
            moveOn("No clean column within " + radius.get().intValue() + " blocks. Yours has " + firstProblem);
        }
    }

    /** Columns around {@code centre}, nearest first, starting with its own. */
    private static List<BlockPos> spiral(BlockPos centre, int radius) {
        List<BlockPos> out = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz <= radius * radius) out.add(new BlockPos(centre.getX() + dx, centre.getY(), centre.getZ() + dz));
            }
        }
        out.sort((a, b) -> Double.compare(a.distSqr(centre), b.distSqr(centre)));
        return out;
    }

    /**
     * Whether a shaft from {@code top} straight down to the bottom is clean: solid the whole way,
     * no lava or water near it, nothing anyone built. @return what's wrong, or null if it's fine.
     */
    String check(BlockPos top) {
        int bottom = bottomY();
        for (int y = top.getY(); y >= mc.level.getMinY(); y--) {
            BlockPos p = new BlockPos(top.getX(), y, top.getZ());
            if (!mc.level.hasChunkAt(p)) return "chunks that aren't loaded";
            BlockState state = mc.level.getBlockState(p);
            if (isBottom(state, y, bottom)) return null;
            if (state.hasBlockEntity()) return "a " + state.getBlock().getName().getString() + " at Y " + y;
            if (!state.getFluidState().isEmpty()) return (state.getFluidState().is(FluidTags.LAVA) ? "lava" : "water") + " at Y " + y;
            if (!AreaPlan.diggable(mc.level, p, state) || !state.isCollisionShapeFullBlock(mc.level, p)) return "a cave at Y " + y;
            String near = fluidNear(p);
            if (near != null) return near + " next to it at Y " + y;
        }
        return "no bottom";
    }

    private int bottomY() {
        return stopAt.is("Y level") ? stopY.get().intValue() : Integer.MIN_VALUE;
    }

    /** The block you stop on: bedrock, or anything under the chosen Y. */
    private static boolean isBottom(BlockState state, int y, int bottom) {
        return state.is(Blocks.BEDROCK) || y < bottom;
    }

    /** Lava (or water) close enough to the shaft that digging past it could let it in. */
    private String fluidNear(BlockPos p) {
        int gap = lavaGap.get().intValue();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -gap; dx <= gap; dx++) {
            for (int dz = -gap; dz <= gap; dz++) {
                int far = Math.abs(dx) + Math.abs(dz);
                if (far > gap) continue;
                for (int dy = -1; dy <= 0; dy++) {
                    m.set(p.getX() + dx, p.getY() + dy, p.getZ() + dz);
                    FluidState fluid = mc.level.getFluidState(m);
                    if (fluid.is(FluidTags.LAVA)) return "lava";
                    // Water only matters right beside the shaft, where it would pour in.
                    if (fluid.is(FluidTags.WATER) && far <= 1) return "water";
                }
            }
        }
        return null;
    }

    // ---- Walking there

    private void planWalk(LocalPlayer player, BlockPos goal) {
        BlockPos feet = player.blockPosition();
        Region around = Region.of(feet, goal).grow(8, 4);
        Paths paths = Paths.search(mc.level, feet, around, null, 20_000);
        path = paths.pathTo(goal);
        if (path == null) {
            stop("Bedrock Burrow stopped", "Found a clean spot at " + coords(goal.below()) + " but can't walk there. Stand on it and turn this on again");
            return;
        }
        pathIndex = 1;
        column = goal;
        Notify.send("Bedrock Burrow", "Your column has " + firstProblem + ". Clean spot at " + coords(goal.below()) + ", walking there", false, false, false);
        enter(Phase.WALK);
    }

    private void walk(LocalPlayer player) {
        if (System.currentTimeMillis() - phaseSince > 4000 + path.size() * 700L) {
            stop("Bedrock Burrow stopped", "Couldn't get to the spot at " + coords(column.below()));
            return;
        }
        if (pathIndex >= path.size()) {
            steering = Input.EMPTY;
            if (player.onGround() && player.blockPosition().equals(column)) startDigging(column);
            else steering = Steer.toward(player, Vec3.atBottomCenterOf(column), false, false);
            return;
        }
        BlockPos node = path.get(pathIndex);
        Vec3 target = Vec3.atBottomCenterOf(node);
        boolean up = node.getY() > player.getY() + 0.5;
        double horizontal = Math.hypot(target.x - player.getX(), target.z - player.getZ());
        if (horizontal < 0.35 && (!up || player.getY() > node.getY() - 0.2)) {
            pathIndex++;
            return;
        }
        boolean jump = player.onGround() && (up && horizontal < 1.4 || player.horizontalCollision);
        steering = Steer.toward(player, target, jump, false);
        Vec3 look = Vec3.atBottomCenterOf(path.get(Math.min(path.size() - 1, pathIndex + 2))).add(0, player.getEyeHeight(), 0);
        if (aim.target() == null) aim.setTarget(look);
        else aim.track(look);
    }

    private void startDigging(BlockPos feet) {
        column = feet;
        steering = Input.EMPTY;
        aim.clear();
        digging = null;
        Notify.send("Bedrock Burrow", "Digging down at " + coords(feet.below()), false, false, false);
        enter(Phase.DIG);
    }

    // ---- Digging

    private void dig(LocalPlayer player) {
        attacking = false;
        if (!player.onGround()) {
            steering = Input.EMPTY;
            return;
        }
        double off = Math.hypot(player.getX() - (column.getX() + 0.5), player.getZ() - (column.getZ() + 0.5));
        if (dug.isEmpty()) {
            // Still on top: stand in the middle of the block first, or you'd hang off the edge
            // of the hole instead of dropping into it.
            if (off > 0.15) {
                if (System.currentTimeMillis() - phaseSince > CENTER_TIMEOUT) {
                    stop("Bedrock Burrow stopped", "Couldn't get to the middle of " + coords(column.below()));
                    return;
                }
                steering = center(player, true);
                return;
            }
        } else if (off > 0.6 || player.getBlockY() > column.getY()) {
            stop("Bedrock Burrow stopped", "You left the shaft");
            return;
        }
        steering = center(player, false);
        BlockPos feet = player.blockPosition();
        BlockPos below = feet.below();
        BlockState state = mc.level.getBlockState(below);

        if (atBottom(player)) {
            if (seal.get() && fillable(player) != null) {
                enter(Phase.SEAL);
                return;
            }
            finish(player);
            return;
        }
        if (seal.get() && sealDue(player)) {
            enter(Phase.SEAL);
            return;
        }

        // Look before you dig: anything the scout couldn't see from up top.
        if (state.hasBlockEntity()) {
            moveOn("A " + state.getBlock().getName().getString() + " right under you at " + coords(below));
            return;
        }
        String near = fluidNear(below);
        if (near != null) {
            moveOn(capital(near) + " next to the block under you at " + coords(below));
            return;
        }
        if (!AreaPlan.diggable(mc.level, below, state)) {
            // Already open under you (something fell, or it was never there): drop into it.
            return;
        }
        if (Paths.passable(mc.level, feet.below(2))) {
            moveOn("Open space under the next block at " + coords(feet.below(2)) + ", a cave or someone's tunnel");
            return;
        }

        if (!below.equals(digging)) {
            digging = below.immutable();
            digPoint = lean(player, Vec3.atCenterOf(below).add(0, 0.45, 0), 0.2);
            digSince = System.currentTimeMillis();
            dug.putIfAbsent(digging, state);
        }
        if (System.currentTimeMillis() - digSince > BLOCK_TIMEOUT) {
            stop("Bedrock Burrow stopped", "Couldn't break the " + state.getBlock().getName().getString() + " at " + coords(below));
            return;
        }
        int tool = pickaxe(state);
        if (tool >= 0 && tool != player.getInventory().getSelectedSlot()) {
            player.getInventory().setSelectedSlot(tool);
            return;
        }
        if (aim.target() == null || !aim.target().equals(digPoint)) aim.setTarget(digPoint);
        attacking = mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(below);
        // With a menu open the game won't hold attack for us, so dig the way it would have.
        if (attacking && mc.screen != null && mc.hitResult instanceof BlockHitResult hit
            && mc.gameMode.continueDestroyBlock(hit.getBlockPos(), hit.getDirection())) {
            player.swing(InteractionHand.MAIN_HAND);
        }
    }

    /**
     * {@code point} nudged a little the way you're already facing. Straight up or down has no
     * direction of its own, and aiming at it dead on would swing your view round for nothing.
     */
    private static Vec3 lean(LocalPlayer player, Vec3 point, double amount) {
        double yaw = Math.toRadians(player.getYRot());
        return point.add(-Math.sin(yaw) * amount, 0, Math.cos(yaw) * amount);
    }

    /** Keeps you in the middle of the shaft, so you drop straight down it. */
    private Input center(LocalPlayer player, boolean careful) {
        Vec3 middle = new Vec3(column.getX() + 0.5, player.getY(), column.getZ() + 0.5);
        if (Math.hypot(middle.x - player.getX(), middle.z - player.getZ()) < 0.1) return Input.EMPTY;
        Input to = Steer.toward(player, middle, false, false);
        // Sneaking on top: slow enough not to overshoot, and it won't walk you off anything.
        return new Input(to.forward(), to.backward(), to.left(), to.right(), false, careful, false);
    }

    /**
     * The fastest pickaxe-ish tool in the hotbar for this block, or -1 if there's none. Skips the
     * drill (it mines a 2x3 hole) and anything about to break.
     */
    private int pickaxe(BlockState state) {
        Inventory inventory = mc.player.getInventory();
        BlockState stone = Blocks.STONE.defaultBlockState();
        int best = -1;
        float bestSpeed = 0;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || isDrill(stack)) continue;
            if (stack.isDamageableItem() && stack.getMaxDamage() - stack.getDamageValue() <= 2) continue;
            if (stack.getDestroySpeed(stone) <= 1) continue;
            float speed = stack.getDestroySpeed(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                best = slot;
            }
        }
        return best;
    }

    /** A drill: by its name (Donut's Shard and Amethyst Pickaxes), or one Drill Logger or Tester saw cut more than a block. */
    static boolean isDrill(ItemStack stack) {
        return com.cyanite.client.modules.donut.DrillCut.isDrill(stack.getHoverName().getString());
    }

    private boolean atBottom(LocalPlayer player) {
        BlockPos below = player.blockPosition().below();
        return isBottom(mc.level.getBlockState(below), below.getY(), bottomY());
    }

    // ---- Sealing the shaft

    /** The highest dug-out spot above your head that's still open, or null. */
    private BlockPos fillable(LocalPlayer player) {
        int head = player.getBlockY() + 1;
        BlockPos best = null;
        for (BlockPos pos : dug.keySet()) {
            if (pos.getY() <= head || !mc.level.getBlockState(pos).canBeReplaced()) continue;
            if (best == null || pos.getY() > best.getY()) best = pos;
        }
        return best;
    }

    /** Time to look up and fill in: a few are open, or the top one is about to go out of reach. */
    private boolean sealDue(LocalPlayer player) {
        int open = 0;
        BlockPos top = null;
        int head = player.getBlockY() + 1;
        for (BlockPos pos : dug.keySet()) {
            if (pos.getY() <= head || !mc.level.getBlockState(pos).canBeReplaced()) continue;
            open++;
            if (top == null || pos.getY() > top.getY()) top = pos;
        }
        if (open == 0) return false;
        // One more block down and the eye drops with you.
        double after = Vec3.atCenterOf(top).distanceTo(player.getEyePosition().subtract(0, 1, 0));
        return open >= SEAL_BATCH || after > player.blockInteractionRange() - 0.3;
    }

    private void seal(LocalPlayer player) {
        steering = center(player, false);
        attacking = false;
        // The last click landed: that one's done (and no longer open, so it won't come up again).
        if (sealing != null && clickedAt >= 0 && !mc.level.getBlockState(sealing).canBeReplaced()) {
            placed++;
            dug.remove(sealing);
            sealing = null;
            clickedAt = -1;
        }
        BlockPos target = fillable(player);
        if (target == null) {
            sealing = null;
            if (atBottom(player)) finish(player);
            else enter(Phase.DIG);
            return;
        }
        if (!target.equals(sealing)) {
            sealing = target;
            sealSince = System.currentTimeMillis();
            clickedAt = -1;
            if (!reachable(player, target) || !pickFace(player, target)) {
                giveUp(target);
                return;
            }
        }
        if (clickedAt >= 0) {
            // Give the server a moment to answer before clicking again.
            if (ticks - clickedAt < 8) return;
            clickedAt = -1;
        }
        if (System.currentTimeMillis() - sealSince > PLACE_TIMEOUT) {
            giveUp(target);
            return;
        }
        int slot = blockFor(dug.get(target));
        if (slot < 0) {
            if (!warnedNoBlocks) {
                warnedNoBlocks = true;
                Notify.send("Bedrock Burrow", "Out of blocks to fill the shaft with. It stays open from " + coords(target) + " down", false, false, false);
            }
            giveUp(target);
            return;
        }
        Inventory inventory = player.getInventory();
        if (inventory.getSelectedSlot() != slot) {
            inventory.setSelectedSlot(slot);
            return;
        }
        if (aim.target() == null || !aim.target().equals(clickPoint)) aim.setTarget(clickPoint);
        boolean onFace = mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
            && hit.getBlockPos().equals(clickPos) && hit.getDirection() == clickFace;
        if (onFace && aim.error() < 4) {
            if (mc.screen == null) {
                KeyMapping.click(KeyBindingHelper.getBoundKeyOf(mc.options.keyUse));
            } else if (mc.hitResult instanceof BlockHitResult hit
                && mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit).consumesAction()) {
                // A menu's open, so right click isn't being handled: place it the way it would have.
                player.swing(InteractionHand.MAIN_HAND);
            }
            clickedAt = ticks;
        }
    }

    /** Leaves this one open and gets on with the rest. */
    private void giveUp(BlockPos target) {
        skipped++;
        dug.remove(target);
        sealing = null;
    }

    private static boolean reachable(LocalPlayer player, BlockPos target) {
        return player.getEyePosition().distanceTo(Vec3.atCenterOf(target)) <= player.blockInteractionRange() - 0.2;
    }

    /**
     * What to click so a block lands in {@code target}: the underside of the block above it if
     * that's solid, else the inside of one of its walls, whichever the eye can see.
     */
    private boolean pickFace(LocalPlayer player, BlockPos target) {
        Vec3 eye = player.getEyePosition();
        for (Direction side : List.of(Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)) {
            BlockPos support = target.relative(side);
            if (mc.level.getBlockState(support).canBeReplaced()) continue;
            Direction face = side.getOpposite();
            // Aim just inside the face, so the ray can't fall short of it.
            Vec3 point = Vec3.atCenterOf(support).add(face.getStepX() * 0.47, face.getStepY() * 0.47, face.getStepZ() * 0.47);
            if (face == Direction.DOWN) point = lean(player, point, 0.2);
            BlockHitResult hit = mc.level.clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(support) && hit.getDirection() == face) {
                clickPos = support;
                clickFace = face;
                clickPoint = point;
                return true;
            }
        }
        return false;
    }

    /**
     * The hotbar slot holding something to put back in place of {@code original}, moving it up
     * from the inventory first if need be. -1 if there's nothing fit to use.
     */
    private int blockFor(BlockState original) {
        Inventory inventory = mc.player.getInventory();
        for (Item item : choices(original)) {
            for (int slot = 0; slot < 9; slot++) {
                if (inventory.getItem(slot).is(item)) return slot;
            }
            for (int slot = 9; slot < 36; slot++) {
                if (!inventory.getItem(slot).is(item)) continue;
                int moved = bringUp(slot);
                if (moved >= 0) return moved;
            }
        }
        if (fill.is("Anything solid")) {
            for (int slot = 0; slot < 36; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (!(stack.getItem() instanceof BlockItem blockItem) || !fitsShaft(blockItem.getBlock())) continue;
                if (slot < 9) return slot;
                int moved = bringUp(slot);
                if (moved >= 0) return moved;
            }
        }
        return -1;
    }

    /** Swaps an inventory stack into the hotbar, into a slot that won't cost you your pickaxe. -1 if it can't. */
    private int bringUp(int slot) {
        Inventory inventory = mc.player.getInventory();
        int tool = pickaxe(Blocks.STONE.defaultBlockState());
        int into = -1;
        for (int s = 8; s >= 0 && into < 0; s--) {
            if (s != tool && inventory.getItem(s).isEmpty()) into = s;
        }
        for (int s = 8; s >= 0 && into < 0; s--) {
            if (s != tool && inventory.getItem(s).getItem() instanceof BlockItem) into = s;
        }
        for (int s = 8; s >= 0 && into < 0; s--) {
            if (s != tool && !inventory.getItem(s).isDamageableItem()) into = s;
        }
        if (into < 0) into = tool == 8 ? 7 : 8;
        // Through whatever menu is open (your inventory, or a chest you're looking in): the
        // server only takes clicks for the one that's open, and each numbers your slots its own way.
        AbstractContainerMenu menu = mc.player.containerMenu;
        for (Slot s : menu.slots) {
            if (s.container == inventory && s.getContainerSlot() == slot) {
                mc.gameMode.handleInventoryMouseClick(menu.containerId, s.index, into, ClickType.SWAP, mc.player);
                return into;
            }
        }
        return -1;
    }

    /** A plain full block that stays where it's put: no sand, no chests, no glass panes. */
    static boolean fitsShaft(Block block) {
        BlockState state = block.defaultBlockState();
        return !(block instanceof Fallable) && !state.hasBlockEntity() && state.isCollisionShapeFullBlock(mc.level, BlockPos.ZERO)
            && state.getFluidState().isEmpty();
    }

    /** What could stand in for {@code original}, best first. */
    List<Item> choices(BlockState original) {
        List<Item> out = new ArrayList<>();
        Block b = original.getBlock();
        Item exact = b.asItem();
        if (exact instanceof BlockItem blockItem && fitsShaft(blockItem.getBlock())) out.add(exact);
        if (fill.is("Same blocks")) return out;
        String id = BuiltInRegistries.BLOCK.getKey(b).getPath();
        if (b == Blocks.GRASS_BLOCK || b == Blocks.PODZOL || b == Blocks.MYCELIUM || b == Blocks.DIRT_PATH || b == Blocks.FARMLAND
            || b == Blocks.ROOTED_DIRT || b == Blocks.COARSE_DIRT || b == Blocks.MUD || b == Blocks.DIRT) {
            out.addAll(List.of(Items.DIRT, Items.COARSE_DIRT, Items.GRASS_BLOCK, Items.ROOTED_DIRT));
        } else if (id.contains("deepslate") || b == Blocks.TUFF || b == Blocks.CALCITE) {
            out.addAll(List.of(Items.COBBLED_DEEPSLATE, Items.DEEPSLATE, Items.TUFF, Items.COBBLESTONE, Items.STONE));
        } else if (id.contains("sand") || b == Blocks.GRAVEL) {
            out.addAll(List.of(Items.SANDSTONE, Items.DIRT, Items.COBBLESTONE));
        } else if (b == Blocks.NETHERRACK || id.contains("nether")) {
            out.addAll(List.of(Items.NETHERRACK, Items.COBBLESTONE));
        }
        // Anything stony passes for stone, ores and the rest of the underground.
        out.addAll(List.of(Items.COBBLESTONE, Items.STONE, Items.ANDESITE, Items.DIORITE, Items.GRANITE, Items.TUFF,
            Items.COBBLED_DEEPSLATE, Items.DEEPSLATE, Items.DIRT));
        return out;
    }

    private void finish(LocalPlayer player) {
        if (caveCheck.get() && phase != Phase.LAST) {
            // The cave check: the real rock round you only shows once you're down here.
            steering = Input.EMPTY;
            attacking = false;
            BlockPos feet = player.blockPosition();
            scan = new AreaScan(feet, feet.getY() - 1, Math.max(feet.getY() + 1, checkTop.get().intValue()), checkSize.get().intValue());
            enter(Phase.LAST);
            return;
        }
        // The pocket is a base now: Streamer Mode's chat guard keeps its coordinates in.
        com.cyanite.client.modules.misc.StreamerMode.protect(player.blockPosition());
        String sealed = !seal.get() ? "" : skipped == 0 ? ". Shaft sealed above you (" + placed + " blocks)"
            : ". " + placed + " blocks put back, " + skipped + " left open";
        stop("Bedrock Burrow done", "You're at " + coords(player.blockPosition()) + sealed);
    }

    // ---- Checking the area for caves, and /rtp

    /**
     * At the bottom: counts open blocks in the square round you, a slice a tick, once the server's
     * had a moment to send the real rock. None to speak of: "Spot found!". Caves: /rtp.
     */
    private void areaCheck(LocalPlayer player) {
        steering = Input.EMPTY;
        attacking = false;
        if (System.currentTimeMillis() - phaseSince < BOTTOM_SETTLE) return;
        AreaScan a = scan;
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        for (int n = 0; n < SCAN_COLUMNS_PER_TICK && a.next < a.columns.size(); n++) {
            int[] c = a.columns.get(a.next);
            int x = a.centre.getX() + c[0];
            int z = a.centre.getZ() + c[1];
            q.set(x, a.yLo, z);
            if (!mc.level.hasChunkAt(q)) {
                // Not loaded yet: wait for it a while, then go on without it.
                if (System.currentTimeMillis() - a.startedAt < 10_000) return;
                a.next++;
                continue;
            }
            a.next++;
            for (int y = a.yLo; y <= a.yHi; y++) {
                q.set(x, y, z);
                BlockState state = mc.level.getBlockState(q);
                FluidState fluid = state.getFluidState();
                if (!state.isAir() && fluid.isEmpty()) continue;
                a.open++;
                if (fluid.is(FluidTags.LAVA)) a.lava = true;
                else if (!fluid.isEmpty()) a.water = true;
                double d = Math.sqrt(c[0] * c[0] + c[1] * c[1] + (y - a.centre.getY()) * (double) (y - a.centre.getY()));
                if (d < a.nearestDistance) {
                    a.nearestDistance = d;
                    a.nearest = q.immutable();
                }
            }
        }
        if (a.next < a.columns.size()) return;
        scan = null;
        if (a.open < CAVE_OPEN) {
            found(player);
            return;
        }
        String what = a.lava ? "lava" : a.water ? "water" : "caves";
        String where = a.nearest == null ? "" : ", nearest " + Math.round(a.nearestDistance) + " blocks away at Y " + a.nearest.getY();
        if (rtps >= maxRtps.get().intValue()) {
            stop("Bedrock Burrow stopped", "Still " + what + " here after " + rtps + " RTPs" + where);
            return;
        }
        Notify.send("Bedrock Burrow", capital(what) + " round here (" + a.open + " open blocks" + where + "). Trying /" + command(), false, false, false);
        startRtp();
    }

    /** No caves anywhere round the bottom: done, and off the server so the spot stays yours. */
    private void found(LocalPlayer player) {
        BlockPos at = player.blockPosition();
        com.cyanite.client.modules.misc.StreamerMode.protect(at);
        String sealed = !seal.get() ? "" : skipped == 0 ? " Shaft sealed above you (" + placed + " blocks)." : " " + placed + " blocks put back, " + skipped + " left open.";
        String area = checkSize.get().intValue() + "x" + checkSize.get().intValue();
        String reason = "Spot found!\n\n" + com.cyanite.client.modules.misc.StreamerMode.mask(coords(at)) + ", no caves in the " + area
            + " round it up to Y " + checkTop.get().intValue() + "." + sealed;
        if (disconnect.get() && mc.getConnection() != null) {
            setEnabled(false);
            mc.getConnection().getConnection().disconnect(com.cyanite.client.system.Stealth.disconnectReason(reason));
            return;
        }
        stop("Bedrock Burrow: spot found!", coords(at) + ", no caves in the " + area + " round it up to Y " + checkTop.get().intValue() + "." + sealed);
    }

    /** Something wrong with this spot: with the cave check on, /rtp and try somewhere else; otherwise stop. */
    private void moveOn(String why) {
        attacking = false;
        steering = Input.EMPTY;
        if (!caveCheck.get()) {
            stop("Bedrock Burrow stopped", why);
            return;
        }
        if (rtps >= maxRtps.get().intValue()) {
            stop("Bedrock Burrow stopped", why + ". That was the last of " + rtps + " RTPs");
            return;
        }
        Notify.send("Bedrock Burrow", why + ". Trying /" + command(), false, false, false);
        startRtp();
    }

    private String command() {
        String c = rtpCommand.get().strip();
        return c.startsWith("/") ? c.substring(1) : c;
    }

    private void startRtp() {
        rtps++;
        rtpSent = false;
        rtpAttempts = 0;
        landedAt = 0;
        toldMenu = false;
        enter(Phase.RTP);
    }

    /** Sends /rtp, waits to be somewhere else with the ground under you and the chunks round you in, then starts over. */
    private void rtp(LocalPlayer player) {
        steering = Input.EMPTY;
        attacking = false;
        long now = System.currentTimeMillis();
        if (!rtpSent) {
            // A moment's pause first: straight after another command a server may say "slow down".
            if (now - phaseSince < 1000 || mc.getConnection() == null) return;
            mc.getConnection().sendCommand(command());
            rtpSent = true;
            rtpAttempts++;
            rtpAt = now;
            rtpFrom = player.position();
            rtpLevel = mc.level;
            return;
        }
        if (mc.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> && !toldMenu) {
            toldMenu = true;
            Notify.send("Bedrock Burrow", "/" + command() + " opened a menu: pick where to go and it'll carry on from there", true, true, false);
        }
        boolean moved = mc.level != rtpLevel || player.position().distanceTo(rtpFrom) > 64;
        if (!moved) {
            if (now - rtpAt < RTP_WAIT) return;
            if (rtpAttempts >= 3) {
                stop("Bedrock Burrow stopped", "/" + command() + " didn't move you after 3 tries");
                return;
            }
            rtpSent = false;
            phaseSince = now;
            return;
        }
        int half = checkSize.get().intValue() / 2;
        if (!player.onGround() || !mc.level.hasChunksAt(player.blockPosition().offset(-half, 0, -half), player.blockPosition().offset(half, 0, half))) {
            landedAt = 0;
            return;
        }
        if (landedAt == 0) landedAt = now;
        if (now - landedAt < LANDED_SETTLE) return;
        // Somewhere new: start over from the top.
        dug.clear();
        placed = 0;
        skipped = 0;
        column = null;
        candidates = null;
        digging = null;
        sealing = null;
        lastHealth = player.getHealth();
        Notify.send("Bedrock Burrow", "Landed at " + coords(player.blockPosition()) + ". Digging down again", false, false, false);
        enter(Phase.SCOUT);
    }

    // ---- Drawing

    @Override
    public void onRender3D(Render3D render) {
        if (column == null) return;
        if (phase == Phase.WALK) {
            BlockPos ground = column.below();
            render.box(new AABB(ground), 0x302EC4B6, 0xFF2EC4B6, 2f, true);
            Vec3 top = Vec3.atCenterOf(ground);
            render.line(top, new Vec3(top.x, mc.level.getMinY(), top.z), 0x902EC4B6, 1.5f, true);
        }
        if (phase == Phase.SEAL && sealing != null) render.box(new AABB(sealing).deflate(0.05), 0x30FFFFFF, 0xC0FFFFFF, 1.5f, false);
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    private static String coords(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }
}
