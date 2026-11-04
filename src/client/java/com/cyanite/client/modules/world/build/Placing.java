package com.cyanite.client.modules.world.build;

import com.cyanite.client.mixin.BlockItemInvoker;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.DaylightDetectorBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * How to place a block so it comes out the way a schematic wants it: which neighbour to click,
 * which face of it, where on that face, and which way to be facing and looking while you do.
 *
 * <p>Worked out with the game's own placement rules: the block's state for placement is asked
 * for every face it could be clicked on, high and low on that face, facing each of the four
 * ways and looking up, level and down. So stairs, slabs, logs, furnaces, observers, torches and
 * the rest come out right without a rule for each. The player's turn is borrowed for the asking
 * and put straight back; nothing is sent.
 */
public final class Placing {
    /** Looking up, level or down, which is all a block's placement can tell apart. */
    public enum Look {
        UP(-70), LEVEL(0), DOWN(70);

        final float pitch;

        Look(float pitch) {
            this.pitch = pitch;
        }
    }

    /**
     * One way to place it: click {@code face} of {@code against} at {@code hit}, with the game
     * reading you as facing {@code facing} and looking {@code look} (null for either: any will do).
     */
    public record Way(BlockPos against, Direction face, Vec3 hit, Direction facing, Look look) {
        /** Whether looking at {@code hit} from here, at this turn, places it right. */
        public boolean fits(float yaw, float pitch) {
            return fits(yaw, pitch, 40, 1.05);
        }

        /**
         * As {@link #fits}, with room to spare: for planning from the middle of somewhere to stand,
         * which is only about where you'll be looking from when you get there.
         */
        public boolean fitsWell(float yaw, float pitch) {
            return fits(yaw, pitch, 30, 1.2);
        }

        /**
         * Whether a look that fits could ever land on the face: not a look up at a face that faces
         * up (or down at one facing down), nor one facing the same way as the face, from behind it.
         */
        public boolean plausible() {
            if (look == Look.UP && face == Direction.UP || look == Look.DOWN && face == Direction.DOWN) return false;
            return facing == null || face != facing;
        }

        private boolean fits(float yaw, float pitch, float turn, double margin) {
            if (facing != null && Math.abs(Mth.wrapDegrees(yaw - facing.toYRot())) > turn) return false;
            if (look == null) return true;
            return switch (look) {
                case UP -> nearest(Direction.UP, yaw, pitch, margin);
                case DOWN -> nearest(Direction.DOWN, yaw, pitch, margin);
                // Level, the way it faces: that the nearest of the six to the look, not up or down.
                case LEVEL -> facing != null ? nearest(facing, yaw, pitch, margin) : Math.abs(pitch) <= (margin > 1.1 ? 28 : 36);
            };
        }
    }

    /**
     * Whether {@code dir} is the nearest of the six directions to a look at {@code yaw} and
     * {@code pitch}, the way the game turns a piston, an observer or a dropper to it: its share of
     * the look at least {@code margin} times any other's.
     */
    static boolean nearest(Direction dir, float yaw, float pitch, double margin) {
        double p = Math.toRadians(pitch);
        double y = Math.toRadians(yaw);
        double[] look = {-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p)};
        int axis = dir.getAxis().ordinal();
        double share = look[axis] * dir.getAxisDirection().getStep();
        if (share <= 0) return false;
        for (int i = 0; i < 3; i++) if (i != axis && Math.abs(look[i]) * margin >= share) return false;
        return true;
    }

    /** What placing can't decide, or what the blocks round about decide instead: never held against a block. */
    private static final Set<String> IGNORED = Set.of("waterlogged", "powered", "lit", "distance", "persistent", "age", "delay",
        "mode", "note", "instrument", "snowy", "triggered", "enabled", "extended", "open", "occupied", "has_book", "hinge",
        "shape", "north", "south", "east", "west", "up", "down", "attached", "in_wall", "bottom", "power", "locked", "unstable",
        "drag", "hatch", "eggs", "pickles", "candles", "layers", "level", "charges", "honey_level", "bites", "berries", "stage",
        "moisture", "short", "conditional", "has_bottle_0", "has_bottle_1", "has_bottle_2", "has_record", "inverted",
        "signal_fire", "tilt", "thickness", "cracked", "sculk_sensor_phase", "bloom", "can_summon", "shrieking", "flower_amount",
        "segment_amount", "crafting", "trial_spawner_state", "ominous", "vault_state", "natural", "tip", "rotation", "dusted",
        "slot_0_occupied", "slot_1_occupied", "slot_2_occupied", "slot_3_occupied", "slot_4_occupied", "slot_5_occupied");

    /**
     * Heights up a side face to click at: near the top and the bottom as well, as looking up past
     * an edge into a gap (a piston facing down on a wall), the top of the face beyond is a lot
     * further clear of the edge than its middle is.
     */
    private static final double[] HIGH_LOW = {0.25, 0.75, 0.9, 0.1};
    private static final double[] MIDDLE = {0.5};
    /** How far off the middle of a top or bottom face the other points to click on it are. */
    private static final double OFF_MIDDLE = 0.3;

    private Placing() {
    }

    /**
     * Whether a schematic block is left alone: flowing water and lava (the source they run from
     * is placed, by the bucket), the halves that come with the other half (a door's top, a bed's
     * head), piston heads, and anything with no item to place it with.
     */
    public static boolean skipped(BlockState want) {
        Block block = want.getBlock();
        if (block instanceof LiquidBlock) return !fluid(want);
        if (block instanceof PistonHeadBlock || block instanceof MovingPistonBlock) return true;
        if (block == Blocks.FIRE || block == Blocks.SOUL_FIRE || block == Blocks.NETHER_PORTAL || block == Blocks.END_PORTAL
            || block == Blocks.BUBBLE_COLUMN || block == Blocks.END_GATEWAY) return true;
        if (want.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && want.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return true;
        if (block instanceof BedBlock && want.getValue(BedBlock.PART) == BedPart.HEAD) return true;
        return !(block.asItem() instanceof BlockItem);
    }

    /** A source of water or lava, which goes in with a bucket. */
    public static boolean fluid(BlockState want) {
        if (!(want.getBlock() instanceof LiquidBlock)) return false;
        FluidState f = want.getFluidState();
        return f.isSource() && (f.is(FluidTags.WATER) || f.is(FluidTags.LAVA));
    }

    /** What puts it in: a bucket of it for water or lava, otherwise the block's own item. */
    public static Item item(BlockState want) {
        if (want.getBlock() instanceof LiquidBlock) {
            if (!fluid(want)) return Items.AIR;
            return want.getFluidState().is(FluidTags.WATER) ? Items.WATER_BUCKET : Items.LAVA_BUCKET;
        }
        return want.getBlock().asItem();
    }

    /** Wanted waterlogged: a bucket of water goes on it once it's in. */
    public static boolean wantsWater(BlockState want) {
        return want.hasProperty(BlockStateProperties.WATERLOGGED) && want.getValue(BlockStateProperties.WATERLOGGED);
    }

    /** In, but still waiting on its water. */
    public static boolean needsWater(BlockState have, BlockState want) {
        return wantsWater(want) && have.hasProperty(BlockStateProperties.WATERLOGGED) && !have.getValue(BlockStateProperties.WATERLOGGED);
    }

    /**
     * How many clicks on it, once it's in, set what placing can't: a repeater's delay, a
     * comparator's mode, a gate, door or trapdoor left open, a lever pulled, a note block's note,
     * a daylight sensor turned round. 0 if none (or if it's not the same block).
     */
    public static int clicks(BlockState have, BlockState want) {
        Block block = want.getBlock();
        if (have.getBlock() != block) return 0;
        if (block instanceof RepeaterBlock) return Math.floorMod(want.getValue(RepeaterBlock.DELAY) - have.getValue(RepeaterBlock.DELAY), 4);
        if (block instanceof NoteBlock) return Math.floorMod(want.getValue(NoteBlock.NOTE) - have.getValue(NoteBlock.NOTE), 25);
        Property<?> toggled = block instanceof ComparatorBlock ? ComparatorBlock.MODE
            : block instanceof DaylightDetectorBlock ? DaylightDetectorBlock.INVERTED
            : block instanceof LeverBlock ? LeverBlock.POWERED
            : openByHand(want) ? BlockStateProperties.OPEN : null;
        return toggled != null && !have.getValue(toggled).equals(want.getValue(toggled)) ? 1 : 0;
    }

    /** A gate, or a door or trapdoor a hand can open (not iron). */
    private static boolean openByHand(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof FenceGateBlock) return true;
        if (block instanceof DoorBlock door) return door.type().canOpenByHand();
        return block instanceof TrapDoorBlock && block != Blocks.IRON_TRAPDOOR;
    }

    /** Close enough: the same block, and the same in everything placing decides. */
    public static boolean matches(BlockState have, BlockState want) {
        if (have.getBlock() != want.getBlock()) return false;
        // Water or lava: a source where a source is wanted.
        if (want.getBlock() instanceof LiquidBlock) return have.getFluidState().isSource() == want.getFluidState().isSource();
        for (Property<?> property : want.getProperties()) {
            if (IGNORED.contains(property.getName()) || !have.hasProperty(property)) continue;
            if (!have.getValue(property).equals(want.getValue(property))) return false;
        }
        return true;
    }

    /**
     * What the next placement at a spot should make: the block itself, except a double slab,
     * which starts as a bottom one.
     */
    public static BlockState nextStep(BlockState want, BlockState have) {
        if (want.getBlock() instanceof SlabBlock && want.getValue(SlabBlock.TYPE) == SlabType.DOUBLE && have.getBlock() != want.getBlock()) {
            return want.setValue(SlabBlock.TYPE, SlabType.BOTTOM);
        }
        return want;
    }

    /** Every way of placing {@code want} at {@code target} with what's round it now. */
    public static List<Way> ways(Level level, LocalPlayer player, BlockPos target, BlockState want, ItemStack stack) {
        List<Way> out = new ArrayList<>();
        if (!(stack.getItem() instanceof BlockItem item)) return out;
        BlockState here = level.getBlockState(target);
        BlockState step = nextStep(want, here);

        float yaw = player.getYRot();
        float pitch = player.getXRot();
        float yawO = player.yRotO;
        float pitchO = player.xRotO;
        try {
            List<BlockPos> supports = new ArrayList<>();
            for (Direction d : Direction.values()) supports.add(target.relative(d));
            // A single slab already there of the same kind: click the slab itself to double it.
            if (here.getBlock() == want.getBlock() && here.getBlock() instanceof SlabBlock) supports.add(target);

            for (BlockPos against : supports) {
                BlockState support = level.getBlockState(against);
                boolean self = against.equals(target);
                if (!self && (support.isAir() || support.canBeReplaced() || support.getShape(level, against).isEmpty())) continue;
                for (Direction face : self ? slabFaces(here) : List.of(faceToward(against, target))) {
                    double[] heights = face.getAxis().isVertical() ? MIDDLE : HIGH_LOW;
                    for (double h : heights) {
                        Vec3 hit = self ? slabHit(target, here, face) : shapePoint(level, against, support, face, h);
                        sample(out, level, player, item, stack, target, step, against, face, hit, against);
                    }
                    // The top or bottom of a whole block: off the middle as well, toward each side.
                    // Its middle can be only just hidden past the corner of a block beside the look
                    // (a piston on a ledge, seen over the step it's on), and a hair to one side clear.
                    if (!self && face.getAxis().isVertical() && Block.isShapeFullBlock(support.getShape(level, against))) {
                        Vec3 middle = facePoint(against, face, 0.5);
                        for (Direction side : Direction.Plane.HORIZONTAL) {
                            Vec3 hit = middle.add(side.getStepX() * OFF_MIDDLE, 0, side.getStepZ() * OFF_MIDDLE);
                            sample(out, level, player, item, stack, target, step, against, face, hit, against);
                        }
                    }
                }
            }
        } finally {
            player.setYRot(yaw);
            player.setXRot(pitch);
            player.yRotO = yawO;
            player.xRotO = pitchO;
        }
        out.removeIf(w -> !w.plausible());
        return out;
    }

    /**
     * Every way of placing {@code want} at {@code target} against a whole block at {@code against}
     * that isn't there yet: a throwaway block to put in first, for a block turned only by which
     * way you look (a piston, an observer, a dropper), when nothing that's there will do.
     */
    public static List<Way> waysAgainst(Level level, LocalPlayer player, BlockPos target, BlockState want, ItemStack stack, BlockPos against) {
        List<Way> out = new ArrayList<>();
        if (!(stack.getItem() instanceof BlockItem item)) return out;
        BlockState step = nextStep(want, level.getBlockState(target));
        Direction face = faceToward(against, target);
        float yaw = player.getYRot();
        float pitch = player.getXRot();
        float yawO = player.yRotO;
        float pitchO = player.xRotO;
        try {
            // Asked of a click on the spot itself (empty, so it's what gets filled): which way the
            // block turns comes from the look alone.
            for (double h : face.getAxis().isVertical() ? MIDDLE : HIGH_LOW) {
                sample(out, level, player, item, stack, target, step, against, face, facePoint(against, face, h), target);
            }
        } finally {
            player.setYRot(yaw);
            player.setXRot(pitch);
            player.yRotO = yawO;
            player.xRotO = pitchO;
        }
        out.removeIf(w -> !w.plausible());
        return out;
    }

    /**
     * The ways of placing {@code step} at {@code target} with a click on {@code face} at {@code
     * hit} (made on {@code clickAt}): facing each of the four ways and looking up, level and down.
     */
    private static void sample(List<Way> out, Level level, LocalPlayer player, BlockItem item, ItemStack stack, BlockPos target, BlockState step,
                               BlockPos against, Direction face, Vec3 hit, BlockPos clickAt) {
        net.minecraft.world.phys.BlockHitResult click = new net.minecraft.world.phys.BlockHitResult(hit, face, clickAt, false);
        boolean[][] good = new boolean[4][Look.values().length];
        int count = 0;
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            for (Look look : Look.values()) {
                turn(player, facing.toYRot(), look.pitch);
                BlockPlaceContext ctx = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, click);
                if (!ctx.getClickedPos().equals(target) || !ctx.canPlace()) continue;
                BlockState got = item instanceof StandingAndWallBlockItem
                    ? ((BlockItemInvoker) item).cyanite$placementState(ctx)
                    : item.getBlock().getStateForPlacement(ctx);
                if (got == null || !got.canSurvive(level, target) || !matches(got, step)) continue;
                good[facing.get2DDataValue()][look.ordinal()] = true;
                count++;
            }
        }
        if (count == 0) return;
        if (count == 4 * Look.values().length) {
            out.add(new Way(against, face, hit, null, null));
            return;
        }
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            boolean[] row = good[facing.get2DDataValue()];
            boolean all = row[0] && row[1] && row[2];
            if (all) {
                out.add(new Way(against, face, hit, facing, null));
            } else {
                for (Look look : Look.values()) if (row[look.ordinal()]) out.add(new Way(against, face, hit, facing, look));
            }
        }
        // The same for any facing at a given look: say so once.
        for (Look look : Look.values()) {
            boolean every = true;
            for (Direction facing : Direction.Plane.HORIZONTAL) every &= good[facing.get2DDataValue()][look.ordinal()];
            if (every) {
                out.removeIf(w -> w.against().equals(against) && w.face() == face && w.hit().equals(hit) && w.look() == look);
                out.add(new Way(against, face, hit, null, look));
            }
        }
    }

    /**
     * What clicking {@code hit} with {@code stack} would place at {@code where}, turned as you are
     * right now, or null if the click wouldn't place anything there.
     */
    public static BlockState result(Level level, LocalPlayer player, net.minecraft.world.phys.BlockHitResult hit, ItemStack stack, BlockPos where) {
        if (!(stack.getItem() instanceof BlockItem item)) return null;
        BlockPlaceContext ctx = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, hit);
        if (!ctx.getClickedPos().equals(where) || !ctx.canPlace()) return null;
        BlockState got = item instanceof StandingAndWallBlockItem
            ? ((BlockItemInvoker) item).cyanite$placementState(ctx)
            : item.getBlock().getStateForPlacement(ctx);
        return got != null && got.canSurvive(level, where) ? got : null;
    }

    private static void turn(LocalPlayer player, float yaw, float pitch) {
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.yRotO = yaw;
        player.xRotO = pitch;
    }

    /** The face of {@code against} that looks onto {@code target}. */
    private static Direction faceToward(BlockPos against, BlockPos target) {
        for (Direction d : Direction.values()) if (against.relative(d).equals(target)) return d;
        return Direction.UP;
    }

    /** A point on {@code face} of the block at {@code pos}, {@code height} of the way up it (for the sides). */
    public static Vec3 facePoint(BlockPos pos, Direction face, double height) {
        double x = pos.getX() + 0.5 + face.getStepX() * 0.5;
        double z = pos.getZ() + 0.5 + face.getStepZ() * 0.5;
        double y = face == Direction.UP ? pos.getY() + 1 : face == Direction.DOWN ? pos.getY() : pos.getY() + height;
        return new Vec3(x, y, z);
    }

    /**
     * A point on {@code face} of the block that's really there, {@code height} of the way up its
     * side: on a whole block the same as {@link #facePoint}; on anything smaller (a hook, a slab,
     * a repeater, a fence) on the part of it that sticks out furthest that way, so a look at the
     * point lands on the block rather than past it.
     */
    public static Vec3 shapePoint(BlockGetter level, BlockPos pos, BlockState state, Direction face, double height) {
        VoxelShape shape = state.getShape(level, pos);
        if (shape.isEmpty() || Block.isShapeFullBlock(shape)) return facePoint(pos, face, height);
        AABB best = null;
        double furthest = -Double.MAX_VALUE;
        for (AABB box : shape.toAabbs()) {
            double reach = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? box.max(face.getAxis()) : -box.min(face.getAxis());
            if (reach > furthest + 1e-6 || reach > furthest - 1e-6 && best != null && volume(box) > volume(best)) {
                furthest = reach;
                best = box;
            }
        }
        double x = (best.minX + best.maxX) / 2;
        double y = face.getAxis().isVertical() ? (best.minY + best.maxY) / 2 : best.minY + (best.maxY - best.minY) * height;
        double z = (best.minZ + best.maxZ) / 2;
        switch (face) {
            case UP -> y = best.maxY;
            case DOWN -> y = best.minY;
            case EAST -> x = best.maxX;
            case WEST -> x = best.minX;
            case SOUTH -> z = best.maxZ;
            case NORTH -> z = best.minZ;
        }
        return new Vec3(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
    }

    private static double volume(AABB box) {
        return box.getXsize() * box.getYsize() * box.getZsize();
    }

    /** The open face of a single slab: its top if it sits at the bottom, its underside if at the top. */
    private static List<Direction> slabFaces(BlockState slab) {
        if (!(slab.getBlock() instanceof SlabBlock) || slab.getValue(SlabBlock.TYPE) == SlabType.DOUBLE) return List.of();
        return List.of(slab.getValue(SlabBlock.TYPE) == SlabType.BOTTOM ? Direction.UP : Direction.DOWN);
    }

    private static Vec3 slabHit(BlockPos pos, BlockState slab, Direction face) {
        return new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }
}
