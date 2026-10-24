package com.cyanite.client.modules.donut;

import com.cyanite.client.gui.Theme;
import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.modules.world.area.Paths;
import com.cyanite.client.render.Render3D;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.Notify;
import com.cyanite.client.system.Steer;
import com.cyanite.client.system.setting.BoolSetting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Takes you through every way a drill can be swung, one check at a time, and writes down
 * exactly what it cut each time: into a wall head on, turned, at a diagonal, looking down at
 * it and up at it; straight down and straight up facing two ways and either side of a diagonal;
 * and down and up at an angle. Area Miner and Auto Mine swing at all of these, and a server's
 * drill can decide its cut from the face you hit or from where you look, so each one matters.
 *
 * <p>You dig a 3x3 tunnel into solid rock with the drill, and it picks a block in fresh rock
 * for each check, where nothing's been cut within two blocks, so the whole cut shows. It marks
 * the block, the face to hit and where to stand; walk close and it lines you up and holds the
 * exact angle, and it won't let the drill swing at that block until everything's right. Other
 * hits are fine and are logged as extras. The report, with what it all means, goes in
 * {@code cyanite/drill-log.txt}.
 */
public class DrillTester extends Module {
    private static final int FRESH = 2;
    private static final int STEADY_TICKS = 3;
    private static final int SEARCH_EVERY = 10;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    enum Kind { WALL, FLOOR, CEILING }

    /**
     * One way to swing. Walls: {@code wall} is which wall (-1 any, 0 the one you faced at the
     * start, 90 the one to its right), {@code lo..hi} how far off head-on to look, in degrees.
     * Floors and ceilings: {@code turn} is the way to face, in degrees right of where you faced at
     * the start, within {@code tol}; straight ones look exactly up or down. Pitch is in game terms,
     * positive looking down.
     */
    enum Check {
        WALL_AHEAD(Kind.WALL, "Into a wall, head on", false, 0, 0, 15, 0, 0, -20, 20),
        WALL_TURNED(Kind.WALL, "Into a wall, turned 90°", false, 90, 0, 15, 0, 0, -20, 20),
        WALL_DIAGONAL(Kind.WALL, "Into a wall, at a diagonal", false, -1, 30, 44, 0, 0, -25, 25),
        // Past 45 degrees the game says you face a different way from into the face: which one wins?
        WALL_FAR_SIDE(Kind.WALL, "Into a wall, far off to the side", false, -1, 52, 70, 0, 0, -25, 25),
        WALL_LOOKING_DOWN(Kind.WALL, "Into a wall, looking down at it", false, -1, 0, 20, 0, 0, 50, 78),
        WALL_LOOKING_UP(Kind.WALL, "Into a wall, looking up at it", false, -1, 0, 20, 0, 0, -78, -45),
        UP(Kind.CEILING, "Straight up", true, 0, 0, 0, 0, 1, -90, -89),
        UP_TURNED(Kind.CEILING, "Straight up, turned 90°", true, 0, 0, 0, 90, 1, -90, -89),
        UP_ANGLED(Kind.CEILING, "Up at an angle", false, 0, 0, 0, 0, 15, -72, -40),
        DOWN_ANGLED(Kind.FLOOR, "Down at an angle", false, 0, 0, 0, 0, 15, 40, 72),
        DOWN_ANGLED_DIAGONAL(Kind.FLOOR, "Down at an angle, diagonal", false, 0, 0, 0, 45, 12, 38, 72),
        DOWN(Kind.FLOOR, "Straight down", true, 0, 0, 0, 0, 1, 89, 90),
        DOWN_TURNED(Kind.FLOOR, "Straight down, turned 90°", true, 0, 0, 0, 90, 1, 89, 90),
        DOWN_40(Kind.FLOOR, "Straight down, turned 40°", true, 0, 0, 0, 40, 1, 89, 90),
        DOWN_50(Kind.FLOOR, "Straight down, turned 50°", true, 0, 0, 0, 50, 1, 89, 90);

        final Kind kind;
        final String label;
        final boolean straight;
        final int wall;
        final double lo;
        final double hi;
        final double turn;
        final double tol;
        final double pitchMin;
        final double pitchMax;

        Check(Kind kind, String label, boolean straight, int wall, double lo, double hi, double turn, double tol, double pitchMin, double pitchMax) {
            this.kind = kind;
            this.label = label;
            this.straight = straight;
            this.wall = wall;
            this.lo = lo;
            this.hi = hi;
            this.turn = turn;
            this.tol = tol;
            this.pitchMin = pitchMin;
            this.pitchMax = pitchMax;
        }
    }

    /** Where to stand (feet), what to hit, where to aim, and at what angle. */
    record Plan(BlockPos target, Direction face, Vec3 stand, Vec3 aim, float yaw, float pitch, BlockPos floor) {
    }

    /** What the crosshair was on, and everything around it, as of one tick. */
    private record Look(BlockPos pos, Direction face, float yaw, float pitch, Direction facing, String item,
                        boolean[] solid, Check check) {
    }

    private record Result(Check check, DrillCut cut, Look look) {
    }

    private final BoolSetting lineUp = add(new BoolSetting("Line up for me", true));
    private final BoolSetting guard = add(new BoolSetting("Hold back wrong swings", true));
    private final BoolSetting toast = add(new BoolSetting("Toast", true));
    private final BoolSetting sound = add(new BoolSetting("Sound", true));

    private final Map<Check, Result> results = new LinkedHashMap<>();
    private final List<DrillCut> extras = new ArrayList<>();
    private String item;
    private Direction start;
    private float startYaw;
    private Check check;
    private Plan plan;
    private Input steering;
    private int ticks;
    private int readyTicks;
    private float lastYaw;
    private float lastPitch;
    private long lastFrame;
    private Look last;
    private Look watching;
    private long brokeAt;
    private long lastChange;
    private int lastCount;
    private BlockPos digHere;
    /** After a check's hit, no more swings until you let go: holding on would cut again into the measurement. */
    private boolean letGo;
    private String notReady = "";
    private boolean lining;

    public DrillTester() {
        super(Category.DONUT, "Drill Tester", "Walks you through every angle of swinging your drill and logs what it cuts");
    }

    @Override
    public String getInfo() {
        return check == null ? null : results.size() + "/" + Check.values().length;
    }

    // ---- What the mixins ask

    /** The way it's moving you onto the spot, or null. */
    public Input steering() {
        return isEnabled() && plan != null ? steering : null;
    }

    /**
     * True when a swing now would spoil the test: on the test block before it's lined up, or
     * straight after a check's hit, while the cut is still being measured and until you let go
     * (holding on would carry on into the block behind, and that would count as part of the cut).
     */
    public static boolean blocksAttack() {
        DrillTester self = Modules.get(DrillTester.class);
        if (self == null || !self.isEnabled() || !self.guard.get()) return false;
        if (self.watching != null || self.letGo) return true;
        if (self.plan == null) return false;
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return false;
        return hit.getBlockPos().equals(self.plan.target) && self.readyTicks < STEADY_TICKS;
    }

    // ---- Start and stop

    @Override
    protected void onEnable() {
        results.clear();
        extras.clear();
        plan = null;
        steering = null;
        last = null;
        watching = null;
        readyTicks = 0;
        check = null;
        if (!inGame()) return;
        if (mc.player.getMainHandItem().isEmpty()) {
            Notify.send("Drill Tester", "Hold the drill you want to test, then turn this on", true, false, false);
            setEnabled(false);
            return;
        }
        item = mc.player.getMainHandItem().getHoverName().getString();
        start = mc.player.getDirection();
        startYaw = start.toYRot();
        check = Check.values()[0];
        String where = mc.getCurrentServer() != null ? mc.getCurrentServer().ip : "singleplayer";
        write(String.format(Locale.ROOT, "%n==== Drill Tester: \"%s\" on %s, %s, started facing %s ====%n",
            item, where, LocalDateTime.now().format(WHEN), start.getName()));
        Notify.send("Drill Tester", "Testing your " + item + ". Stand deep in solid rock facing the way to tunnel, and follow the steps at the top left",
            false, false, false);
    }

    @Override
    protected void onDisable() {
        steering = null;
        if (inGame() && watching != null) finish(watching, DrillCut.snapshot(mc.level, watching.pos));
        watching = null;
        if (item != null && (!results.isEmpty() || !extras.isEmpty())) report();
        item = null;
        check = null;
        plan = null;
    }

    // ---- Every tick

    @Override
    public void onTick() {
        LocalPlayer player = mc.player;
        long now = System.currentTimeMillis();
        ticks++;

        // A hit that's landed: give the rest of the cut a moment, then see what went.
        if (watching != null) {
            boolean[] after = DrillCut.snapshot(mc.level, watching.pos);
            int count = DrillCut.count(after);
            if (count != lastCount) {
                lastCount = count;
                lastChange = now;
            }
            if (now - lastChange > DrillLogger.QUIET || now - brokeAt > DrillLogger.LONGEST) {
                finish(watching, after);
                watching = null;
                if (check == null) {
                    setEnabled(false);
                    return;
                }
            }
        }
        if (letGo && !mc.options.keyAttack.isDown()) letGo = false;
        if (watching == null && last != null && mc.level.getBlockState(last.pos).isAir()) {
            watching = last;
            letGo = last.check != null;
            brokeAt = now;
            lastChange = now;
            lastCount = DrillCut.count(DrillCut.snapshot(mc.level, last.pos));
            plan = null;
        }
        if (check == null) return;

        if (plan != null && ticks % 5 == 0 && !stillGood(plan)) plan = null;
        if (plan == null && watching == null && ticks % SEARCH_EVERY == 0) {
            plan = find(check);
            digHere = plan == null ? ahead(player) : null;
        }
        lineUpAndAim(player);

        // What's under the crosshair now, so the next tick can tell if it broke.
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = hit.getBlockPos().immutable();
            Check aimedAt = plan != null && pos.equals(plan.target) && hit.getDirection() == plan.face && readyTicks >= STEADY_TICKS ? check : null;
            last = new Look(pos, hit.getDirection(), player.getYRot(), player.getXRot(), player.getDirection(),
                player.getMainHandItem().getHoverName().getString(), DrillCut.snapshot(mc.level, pos), aimedAt);
        } else {
            last = null;
        }
    }

    /** Steps you onto the spot and holds the angle, and counts how long it's all been right. */
    private void lineUpAndAim(LocalPlayer player) {
        steering = null;
        if (plan == null) {
            readyTicks = 0;
            return;
        }
        double off = Math.hypot(plan.stand.x - player.getX(), plan.stand.z - player.getZ());
        boolean level = Math.abs(player.getY() - plan.stand.y) < 0.1 && player.onGround();
        double tolerance = check.straight && check.kind == Kind.FLOOR ? 0.07 : 0.2;
        // Starts moving you once you're off the spot, and keeps on until you're well onto it, so it
        // never hovers at the edge still sneaking.
        if (off > tolerance) lining = true;
        else if (off <= tolerance * 0.6) lining = false;
        if (lineUp.get() && level && off < 2.2 && lining) {
            // Sneaking for the last little bit: slow enough to stop where it should.
            Input to = Steer.toward(player, plan.stand, false, false);
            steering = new Input(to.forward(), to.backward(), to.left(), to.right(), false, true, false);
        }
        boolean placed = level && off <= tolerance && player.getPose() == Pose.STANDING;
        float yaw = player.getYRot();
        float pitch = player.getXRot();
        boolean steady = Math.abs(Mth.wrapDegrees(yaw - lastYaw)) < 0.3 && Math.abs(pitch - lastPitch) < 0.3;
        lastYaw = yaw;
        lastPitch = pitch;
        boolean onFace = mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
            && hit.getBlockPos().equals(plan.target) && hit.getDirection() == plan.face;
        boolean holding = player.getMainHandItem().getHoverName().getString().equals(item);
        boolean angle = fits(check, plan.face, yaw, pitch);
        boolean right = placed && onFace && holding && steady && angle;
        readyTicks = right ? readyTicks + 1 : 0;
        notReady = right ? "" : !level ? "not on the spot's level" : off > tolerance ? "not on the spot" : player.getPose() != Pose.STANDING ? "not standing"
            : !onFace ? "crosshair not on the face" : !holding ? "not holding the drill" : !steady ? "still turning" : "angle out of range";
    }

    @Override
    public void onFrame() {
        long now = System.nanoTime();
        double dt = lastFrame == 0 ? 0 : Math.min((now - lastFrame) / 1e9, 0.05);
        lastFrame = now;
        LocalPlayer player = mc.player;
        if (plan == null || !lineUp.get() || player == null || mc.isPaused() || dt == 0) return;
        if (Math.hypot(plan.stand.x - player.getX(), plan.stand.z - player.getZ()) > 2.2) return;
        float wantYaw = plan.yaw;
        float wantPitch = plan.pitch;
        if (!check.straight) {
            // From where your eye really is, so the crosshair lands on the planned spot.
            float[] a = angles(player.getEyePosition(), plan.aim);
            wantYaw = a[0];
            wantPitch = a[1];
        }
        float dy = Mth.wrapDegrees(wantYaw - player.getYRot());
        float dp = wantPitch - player.getXRot();
        float step = (float) Math.min(1, dt * 14);
        player.setYRot(Math.abs(dy) < 0.05f ? wantYaw : player.getYRot() + dy * step);
        player.setXRot(Mth.clamp(Math.abs(dp) < 0.05f ? wantPitch : player.getXRot() + dp * step, -90, 90));
    }

    // ---- Finding a spot for a check

    private Plan find(Check c) {
        LocalPlayer player = mc.player;
        BlockPos feet = player.blockPosition();
        List<BlockPos> targets = new ArrayList<>();
        for (int dx = -6; dx <= 6; dx++) {
            for (int dy = -4; dy <= 5; dy++) {
                for (int dz = -6; dz <= 6; dz++) targets.add(feet.offset(dx, dy, dz));
            }
        }
        Vec3 eye = player.getEyePosition();
        targets.sort((a, b) -> Double.compare(Vec3.atCenterOf(a).distanceToSqr(eye), Vec3.atCenterOf(b).distanceToSqr(eye)));
        Plan best = null;
        double bestCost = Double.MAX_VALUE;
        int examined = 0;
        for (BlockPos t : targets) {
            if (!solid(t)) continue;
            for (Direction face : faces(c)) {
                if (!Paths.passable(mc.level, t.relative(face)) || !fresh(t, face)) continue;
                Plan p = standFor(c, t, face);
                if (p != null) {
                    double cost = p.stand.distanceTo(player.position());
                    if (cost < bestCost) {
                        bestCost = cost;
                        best = p;
                    }
                }
                // Nearest first, so after this many the rest are only further away.
                if (++examined >= 60) return best;
            }
        }
        return best;
    }

    private Direction[] faces(Check c) {
        return switch (c.kind) {
            case FLOOR -> new Direction[] {Direction.UP};
            case CEILING -> new Direction[] {Direction.DOWN};
            case WALL -> c.wall == 0 ? new Direction[] {start.getOpposite()}
                : c.wall == 90 ? new Direction[] {start.getClockWise().getOpposite()}
                : new Direction[] {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
        };
    }

    /** Nothing cut near it: every block within two of it, on its side of the face, is still solid rock. */
    private boolean fresh(BlockPos t, Direction face) {
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        for (int dx = -FRESH; dx <= FRESH; dx++) {
            for (int dy = -FRESH; dy <= FRESH; dy++) {
                for (int dz = -FRESH; dz <= FRESH; dz++) {
                    if (dx * face.getStepX() + dy * face.getStepY() + dz * face.getStepZ() > 0) continue;
                    q.set(t.getX() + dx, t.getY() + dy, t.getZ() + dz);
                    if (!solid(q)) return false;
                }
            }
        }
        return true;
    }

    private boolean solid(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        return !state.isAir() && state.getFluidState().isEmpty() && !state.hasBlockEntity()
            && state.isCollisionShapeFullBlock(mc.level, pos) && state.getDestroySpeed(mc.level, pos) >= 0;
    }

    /** Where to stand for this check on this face, or null if nowhere works. */
    private Plan standFor(Check c, BlockPos t, Direction face) {
        double reach = mc.player.blockInteractionRange() - 0.3;
        float yawFor = Mth.wrapDegrees((float) (startYaw + c.turn));
        if (c.straight && c.kind == Kind.FLOOR) {
            // Straight down hits the block under your eye. Stand on the block behind it, right at
            // its edge, with your eye just over the test block: the cut goes from under your
            // eye, and you're still standing on the block behind when it does.
            Direction d = Direction.fromYRot(yawFor);
            BlockPos behind = t.relative(d.getOpposite());
            if (!solid(behind)) return null;
            for (BlockPos open : new BlockPos[] {t.above(), t.above(2), behind.above(), behind.above(2)}) {
                if (!Paths.passable(mc.level, open)) return null;
            }
            Vec3 stand = new Vec3(t.getX() + 0.5 - d.getStepX() * 0.35, t.getY() + 1, t.getZ() + 0.5 - d.getStepZ() * 0.35);
            return new Plan(t, face, stand, Vec3.atCenterOf(t).add(0, 0.5, 0), yawFor, 90, behind);
        }
        if (c.straight) {
            // Straight up: right under it, as close as you can stand.
            for (int k = 2; k <= 5; k++) {
                BlockPos feet = t.below(k);
                if (!Paths.standable(mc.level, feet)) continue;
                boolean clear = true;
                for (int y = feet.getY() + 2; y < t.getY(); y++) clear &= Paths.passable(mc.level, new BlockPos(t.getX(), y, t.getZ()));
                if (!clear) continue;
                double eyeY = feet.getY() + eyeHeight();
                if (t.getY() - eyeY > reach) return null;
                return new Plan(t, face, Vec3.atBottomCenterOf(feet), Vec3.atCenterOf(t).add(0, -0.5, 0), yawFor, -90, feet.below());
            }
            return null;
        }
        Plan best = null;
        double bestCost = Double.MAX_VALUE;
        Vec3 aim = Vec3.atCenterOf(t).add(face.getStepX() * 0.45, face.getStepY() * 0.45, face.getStepZ() * 0.45);
        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -4; dy <= 2; dy++) {
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos feet = t.offset(dx, dy, dz);
                    Vec3 stand = Vec3.atBottomCenterOf(feet);
                    Vec3 eye = stand.add(0, eyeHeight(), 0);
                    if (eye.distanceTo(aim) > reach) continue;
                    if (feet.below().equals(t) || !Paths.standable(mc.level, feet)) continue;
                    float[] a = angles(eye, aim);
                    if (!fits(c, face, a[0], a[1])) continue;
                    BlockHitResult hit = mc.level.clip(new ClipContext(eye, aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
                    if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(t) || hit.getDirection() != face) continue;
                    double cost = stand.distanceTo(mc.player.position()) + 0.05 * offCentre(c, face, a[0], a[1]);
                    if (cost < bestCost) {
                        bestCost = cost;
                        best = new Plan(t, face, stand, aim, a[0], a[1], feet.below());
                    }
                }
            }
        }
        return best;
    }

    private boolean stillGood(Plan p) {
        if (!solid(p.target) || !Paths.passable(mc.level, p.target.relative(p.face)) || !fresh(p.target, p.face)) return false;
        return solid(p.floor);
    }

    /** Whether looking this way at this face is what the check wants. */
    private boolean fits(Check c, Direction face, float yaw, float pitch) {
        if (pitch < c.pitchMin || pitch > c.pitchMax) return false;
        if (c.kind == Kind.WALL) {
            double off = Math.abs(Mth.wrapDegrees(yaw - face.getOpposite().toYRot()));
            return off >= c.lo && off <= c.hi;
        }
        return Math.abs(Mth.wrapDegrees(yaw - (startYaw + c.turn))) <= c.tol;
    }

    /** How far from the middle of what the check wants, in degrees: closer is better. */
    private double offCentre(Check c, Direction face, float yaw, float pitch) {
        double p = Math.abs(pitch - (c.pitchMin + c.pitchMax) / 2);
        if (c.kind == Kind.WALL) return p + Math.abs(Math.abs(Mth.wrapDegrees(yaw - face.getOpposite().toYRot())) - (c.lo + c.hi) / 2);
        return p + Math.abs(Mth.wrapDegrees(yaw - (startYaw + c.turn)));
    }

    private static float[] angles(Vec3 eye, Vec3 point) {
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90;
        float pitch = (float) -(Mth.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * Mth.RAD_TO_DEG);
        return new float[] {Mth.wrapDegrees(yaw), pitch};
    }

    private static double eyeHeight() {
        return mc.player.getDimensions(Pose.STANDING).eyeHeight();
    }

    /** The rock straight ahead the way you started, at eye level: dig there to make room. */
    private BlockPos ahead(LocalPlayer player) {
        Vec3 eye = player.getEyePosition();
        for (int k = 1; k <= 5; k++) {
            BlockPos q = BlockPos.containing(eye.x + start.getStepX() * k, eye.y, eye.z + start.getStepZ() * k);
            if (solid(q)) return q;
        }
        return null;
    }

    // ---- What a hit took

    private void finish(Look hit, boolean[] after) {
        DrillCut cut = new DrillCut(DrillCut.forward(hit.face, hit.facing), hit.solid, after);
        if (cut.size() > 1 && hit.item.equals(item)) DrillCut.rememberDrill(item);
        if (hit.check != null && hit.check == check && hit.item.equals(item)) {
            results.put(check, new Result(check, cut, hit));
            int n = results.size();
            Notify.send("Drill Tester", n + "/" + Check.values().length + " " + check.label + ": " + cut.size() + " blocks, " + cut.shape(),
                false, false, false);
            check = next();
            plan = null;
            if (check == null) {
                Notify.send("Drill Tester done", "Every check is in. Saved to cyanite/drill-log.txt", toast.get(), sound.get(), false);
            }
        } else if (hit.item.equals(item)) {
            extras.add(cut);
        }
    }

    private Check next() {
        for (Check c : Check.values()) if (!results.containsKey(c)) return c;
        return null;
    }

    // ---- The report

    private void report() {
        StringBuilder sb = new StringBuilder();
        for (Check c : Check.values()) {
            Result r = results.get(c);
            sb.append(String.format(Locale.ROOT, "[%d/%d] %s: ", c.ordinal() + 1, Check.values().length, c.label));
            if (r == null) {
                sb.append("not done\n");
                continue;
            }
            Look l = r.look;
            sb.append(String.format(Locale.ROOT, "%d blocks, %s%s%n", r.cut.size(), r.cut.shape(), r.cut.size() > 1 ? ", runs along " + r.cut.acrossAxis() : ""));
            sb.append(String.format(Locale.ROOT, "  hit the %s face of %d %d %d, facing %s (yaw %.1f, pitch %.1f)%n",
                l.face.getName(), l.pos.getX(), l.pos.getY(), l.pos.getZ(), l.facing.getName(), Mth.wrapDegrees(l.yaw), l.pitch));
            sb.append(r.cut.picture("  "));
            sb.append("  world offsets (dx dy dz): ").append(r.cut.offsets()).append('\n');
        }
        if (!extras.isEmpty()) sb.append(extras.size()).append(" other hits (tunnelling) not counted\n");
        sb.append("---- What it found ----\n");
        for (String line : findings()) sb.append(line).append('\n');
        write(sb.toString());
    }

    /** Plain-language conclusions from the checks that were done. */
    private List<String> findings() {
        List<String> out = new ArrayList<>();
        List<Result> walls = doneOf(Check.WALL_AHEAD, Check.WALL_TURNED, Check.WALL_DIAGONAL, Check.WALL_LOOKING_DOWN, Check.WALL_LOOKING_UP);
        if (!walls.isEmpty()) {
            List<Result> odd = odd(walls);
            out.add("Into a wall: " + union(walls) + (odd.isEmpty() ? ", the same every way you swung (" + walls.size() + " checks)." : "."));
            for (Result r : odd) out.add("  " + r.check.label + " DOESN'T match: " + union(List.of(r)) + ".");
        }
        Result side = results.get(Check.WALL_FAR_SIDE);
        if (side != null) {
            Direction face = side.look.face;
            Direction facing = side.look.facing;
            boolean onFace = side.cut.gone.stream().allMatch(g -> g.dx() * face.getStepX() + g.dz() * face.getStepZ() == 0);
            boolean acrossYou = side.cut.gone.stream().allMatch(g -> g.dx() * facing.getStepX() + g.dz() * facing.getStepZ() == 0);
            String what = String.format(Locale.ROOT, "  Far off to the side (you hit the %s face, the game said you faced %s): ", face.getName(), facing.getName());
            if (onFace && !acrossYou) out.add(what + "the square lay flat on the face. It goes by the face you hit.");
            else if (acrossYou && !onFace) out.add(what + "the square stood across the way you faced, not on the face. It goes by where you face.");
            else out.add(what + "can't tell from this one.");
        }
        out.addAll(vertical("Into a floor (mining down)", Check.DOWN, Check.DOWN_TURNED, Check.DOWN_40, Check.DOWN_50,
            Check.DOWN, Check.DOWN_TURNED, Check.DOWN_40, Check.DOWN_50, Check.DOWN_ANGLED, Check.DOWN_ANGLED_DIAGONAL));
        out.addAll(vertical("Into a ceiling (mining up)", Check.UP, Check.UP_TURNED, null, null, Check.UP, Check.UP_TURNED, Check.UP_ANGLED));
        out.add("(Only blocks that were there before a hit can be seen going: a cut that reaches somewhere already open shows less.)");
        return out;
    }

    private List<String> vertical(String name, Check one, Check turned, Check forty, Check fifty, Check... family) {
        List<String> out = new ArrayList<>();
        List<Result> all = doneOf(family);
        if (all.isEmpty()) return out;
        List<Result> odd = odd(all);
        out.add(name + ": " + union(all) + (odd.isEmpty() ? ", the same every way you swung (" + all.size() + " checks)." : "."));
        Result a = results.get(one);
        Result b = turned == null ? null : results.get(turned);
        if (a != null && b != null) {
            String axisA = a.cut.acrossAxis();
            String axisB = b.cut.acrossAxis();
            boolean followsA = axisA.equals(across(a.look.facing));
            boolean followsB = axisB.equals(across(b.look.facing));
            if (followsA && followsB) {
                out.add("  It turns with you: facing " + a.look.facing.getName() + " it ran along " + axisA + ", facing " + b.look.facing.getName()
                    + " along " + axisB + ".");
            } else if (axisA.equals(axisB)) {
                out.add("  It does NOT turn with you: always along " + axisA + ", facing " + a.look.facing.getName() + " or " + b.look.facing.getName() + ".");
            } else {
                out.add("  Unclear: facing " + a.look.facing.getName() + " it ran along " + axisA + ", facing " + b.look.facing.getName() + " along " + axisB + ".");
            }
        }
        for (Check c : new Check[] {forty, fifty}) {
            Result r = c == null ? null : results.get(c);
            if (r == null) continue;
            String axis = r.cut.acrossAxis();
            out.add(String.format(Locale.ROOT, "  %s (yaw %.1f, the game says facing %s): ran along %s, %s.", r.check.label, Mth.wrapDegrees(r.look.yaw),
                r.look.facing.getName(), axis, axis.equals(across(r.look.facing)) ? "the way the game's facing says" : "NOT the way the game's facing says"));
        }
        for (Result r : odd) out.add("  " + r.check.label + " DOESN'T match: " + union(List.of(r)) + ".");
        return out;
    }

    private List<Result> doneOf(Check... checks) {
        List<Result> out = new ArrayList<>();
        for (Check c : checks) {
            Result r = results.get(c);
            if (r != null && !out.contains(r)) out.add(r);
        }
        return out;
    }

    /**
     * The cut as seen from where you faced: for every spot near the block you hit that had a block
     * in it before, whether it went. Keyed by (forward, right, up) from the way you faced, so hits
     * facing different ways line up.
     */
    private static Map<Integer, Boolean> seen(Result r) {
        Direction forward = r.look.facing;
        Direction right = forward.getClockWise();
        Map<Integer, Boolean> out = new java.util.HashMap<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    int i = DrillCut.index(dx, dy, dz);
                    boolean hit = dx == 0 && dy == 0 && dz == 0;
                    if (!hit && !r.cut.before[i]) continue;
                    int f = dx * forward.getStepX() + dz * forward.getStepZ();
                    int rr = dx * right.getStepX() + dz * right.getStepZ();
                    out.put(key(f, rr, dy), hit || !r.cut.after[i]);
                }
            }
        }
        return out;
    }

    private static int key(int f, int r, int u) {
        return ((f + 4) * 9 + (r + 4)) * 9 + (u + 4);
    }

    /**
     * The hits that disagree with the rest about a spot both could see: one where it went in the
     * others and stayed in this one, or the other way round. A spot one couldn't see isn't held
     * against it.
     */
    private static List<Result> odd(List<Result> all) {
        Map<Integer, int[]> votes = new java.util.HashMap<>();
        List<Map<Integer, Boolean>> views = new ArrayList<>();
        for (Result r : all) {
            Map<Integer, Boolean> v = seen(r);
            views.add(v);
            v.forEach((k, went) -> votes.computeIfAbsent(k, x -> new int[2])[went ? 0 : 1]++);
        }
        List<Result> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            for (Map.Entry<Integer, Boolean> e : views.get(i).entrySet()) {
                int[] n = votes.get(e.getKey());
                if (n[0] == n[1]) continue;
                if (e.getValue() != n[0] > n[1]) {
                    out.add(all.get(i));
                    break;
                }
            }
        }
        return out;
    }

    /** Everything any of these hits was seen to cut, as a shape: "9 blocks, 3 across, 3 tall, upright across the way you face". */
    private static String union(List<Result> rs) {
        java.util.Set<Integer> cut = new java.util.HashSet<>();
        boolean partly = false;
        for (Result r : rs) {
            seen(r).forEach((k, went) -> {
                if (went) cut.add(k);
            });
        }
        for (Result r : rs) {
            Map<Integer, Boolean> v = seen(r);
            for (int k : cut) partly |= !v.containsKey(k);
        }
        int fMin = 9, fMax = -9, rMin = 9, rMax = -9, uMin = 9, uMax = -9;
        for (int k : cut) {
            int u = k % 9 - 4;
            int r = (k / 9) % 9 - 4;
            int f = k / 81 - 4;
            fMin = Math.min(fMin, f);
            fMax = Math.max(fMax, f);
            rMin = Math.min(rMin, r);
            rMax = Math.max(rMax, r);
            uMin = Math.min(uMin, u);
            uMax = Math.max(uMax, u);
        }
        String shape = cut.size() + " blocks, " + (rMax - rMin + 1) + " across, " + (uMax - uMin + 1) + " tall, " + (fMax - fMin + 1) + " deep";
        if (fMin == 0 && fMax == 0 && uMax > uMin) shape += ", upright across the way you face";
        else if (uMin == 0 && uMax == 0) shape += ", flat";
        if (partly && rs.size() > 1) shape += " (some hits only showed part of it: the rest was already open)";
        return shape;
    }

    /** The world axis that runs across you, facing this way. */
    private static String across(Direction facing) {
        return facing.getClockWise().getAxis() == Direction.Axis.X ? "X" : "Z";
    }

    private void write(String text) {
        try {
            DrillCut.log(text);
        } catch (IOException e) {
            Notify.send("Drill Tester", "Couldn't write the log: " + e.getMessage(), false, false, false);
        }
    }

    // ---- Drawing

    @Override
    public void onRender3D(Render3D render) {
        if (plan != null) {
            Plan p = plan;
            render.box(new AABB(p.target).inflate(0.01), 0, 0xFFFFD84A, 2f, true);
            // The face to hit, as a thin bright slab on it.
            AABB face = new AABB(p.target);
            Direction f = p.face;
            face = switch (f) {
                case UP -> new AABB(face.minX, face.maxY - 0.02, face.minZ, face.maxX, face.maxY + 0.02, face.maxZ);
                case DOWN -> new AABB(face.minX, face.minY - 0.02, face.minZ, face.maxX, face.minY + 0.02, face.maxZ);
                case NORTH -> new AABB(face.minX, face.minY, face.minZ - 0.02, face.maxX, face.maxY, face.minZ + 0.02);
                case SOUTH -> new AABB(face.minX, face.minY, face.maxZ - 0.02, face.maxX, face.maxY, face.maxZ + 0.02);
                case WEST -> new AABB(face.minX - 0.02, face.minY, face.minZ, face.minX + 0.02, face.maxY, face.maxZ);
                case EAST -> new AABB(face.maxX - 0.02, face.minY, face.minZ, face.maxX + 0.02, face.maxY, face.maxZ);
            };
            render.box(face, 0x70FFD84A, 0xFFFFD84A, 1.5f, true);
            // Where to stand: a square under your feet, and a post so you can find it.
            Vec3 s = p.stand;
            boolean ready = readyTicks >= STEADY_TICKS;
            int green = ready ? 0xFF5BE07A : 0xFF3FB861;
            render.box(new AABB(s.x - 0.3, s.y, s.z - 0.3, s.x + 0.3, s.y + 0.04, s.z + 0.3), 0x605BE07A, green, 2f, true);
            render.line(s, s.add(0, 2.2, 0), green, 1.5f, true);
        }
        if (plan == null && digHere != null) render.box(new AABB(digHere).inflate(0.01), 0x304AA8FF, 0xFF4AA8FF, 2f, true);
    }

    @Override
    public void onRender2D(GuiGraphics g, float partialTick) {
        if (check == null && results.isEmpty()) return;
        LocalPlayer player = mc.player;
        String title = "Drill Tester  ·  " + item + "  ·  " + results.size() + "/" + Check.values().length;
        String step = check == null ? "All done" : "Next: " + check.label;
        String hint;
        int hintColor = Theme.TEXT_DIM;
        boolean holding = player.getMainHandItem().getHoverName().getString().equals(item);
        if (check == null) {
            hint = "Saved to cyanite/drill-log.txt. Turn this off when you're ready";
        } else if (!holding) {
            hint = "Hold your " + item;
            hintColor = 0xFFFFB347;
        } else if (watching != null || letGo) {
            hint = watching != null ? "Measuring what it cut..." : "Got it. Let go of left click";
        } else if (plan == null) {
            hint = switch (check.kind) {
                case WALL -> "No fresh wall in reach: drill your tunnel on (the blue block), a few blocks";
                case FLOOR -> "No fresh floor in reach: drill your 3x3 tunnel on (the blue block) a few blocks";
                case CEILING -> "No fresh ceiling in reach: drill your 3x3 tunnel on (the blue block) a few blocks";
            };
        } else if (readyTicks >= STEADY_TICKS) {
            hint = "Ready: hold left click";
            hintColor = 0xFF5BE07A;
        } else {
            double off = Math.hypot(plan.stand.x - player.getX(), plan.stand.z - player.getZ());
            hint = off > 2.2 ? String.format(Locale.ROOT, "Walk to the green post (%.0fm), then hit the yellow face", off)
                : lineUp.get() ? "Lining you up..." : "Stand on the green square and aim at the yellow face";
        }
        String angles = "";
        if (check != null && plan != null) {
            float yaw = player.getYRot();
            float pitch = player.getXRot();
            if (check.kind == Kind.WALL) {
                double off = Math.abs(Mth.wrapDegrees(yaw - plan.face.getOpposite().toYRot()));
                angles = String.format(Locale.ROOT, "%.0f° off head-on (want %.0f-%.0f)  ·  pitch %.0f° (want %.0f to %.0f)",
                    off, check.lo, check.hi, pitch, check.pitchMin, check.pitchMax);
            } else {
                double off = Mth.wrapDegrees(yaw - (startYaw + check.turn));
                angles = String.format(Locale.ROOT, "facing %.1f° from where it wants (±%.0f)  ·  pitch %.1f°", off, check.tol, pitch);
            }
        }

        int line = Text.CAP + 5;
        int pad = 6;
        int w = Math.max(Math.max(Text.width(title), Text.width(step)), Math.max(Text.width(hint), Text.width(angles))) + pad * 2;
        int lines = angles.isEmpty() ? 3 : 4;
        int h = pad * 2 + line * lines - 5;
        // Top left, under the watermark: popups take the top right, and the middle is where you're looking.
        int x = 6;
        int y = 24;
        int s = Render2D.scale();
        int accent = Theme.accent();
        Render2D.pixels(g);
        Render2D.glow(g, x * s, y * s, w * s, h * s, 3 * s, 6 * s, Theme.alpha(accent, 50 * Theme.glow()));
        Render2D.rect(g, x * s, y * s, w * s, h * s, 3 * s, 0xE6101217);
        Render2D.rect(g, x * s + 3 * s, (y + h) * s - Math.max(1, s / 2), w * s - 6 * s, Math.max(1, s / 2), 0, Theme.alpha(accent, 220));
        Render2D.end(g);
        Text.draw(g, title, x + pad, y + pad, Theme.TEXT_FAINT);
        Text.draw(g, step, x + pad, y + pad + line, Theme.accentText());
        Text.draw(g, hint, x + pad, y + pad + line * 2, hintColor);
        if (!angles.isEmpty()) Text.draw(g, angles, x + pad, y + pad + line * 3, Theme.TEXT_FAINT);

        // The checklist, under it.
        int ly = y + h + 8;
        for (Check c : Check.values()) {
            boolean done = results.containsKey(c);
            String mark = done ? "• " : c == check ? "> " : "· ";
            int color = done ? 0xFF5BE07A : c == check ? Theme.accentText() : Theme.TEXT_FAINT;
            Text.draw(g, mark + c.label, 6, ly, color);
            ly += Text.CAP + 3;
        }
    }

    /** For tests: the current plan. */
    Plan plan() {
        return plan;
    }

    boolean ready() {
        return readyTicks >= STEADY_TICKS;
    }

    String notReady() {
        return notReady;
    }

    Check current() {
        return check;
    }

    int done() {
        return results.size();
    }
}
