package com.cyanite.client.modules.world.build;

import com.cyanite.client.mixin.BlockItemInvoker;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;

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

        boolean fits(float pitch) {
            return switch (this) {
                case UP -> pitch <= -50;
                case DOWN -> pitch >= 50;
                case LEVEL -> Math.abs(pitch) <= 30;
            };
        }
    }

    /**
     * One way to place it: click {@code face} of {@code against} at {@code hit}, with the game
     * reading you as facing {@code facing} and looking {@code look} (null for either: any will do).
     */
    public record Way(BlockPos against, Direction face, Vec3 hit, Direction facing, Look look) {
        /** Whether looking at {@code hit} from here, at this turn, places it right. */
        public boolean fits(float yaw, float pitch) {
            if (facing != null && Math.abs(Mth.wrapDegrees(yaw - facing.toYRot())) > 38) return false;
            return look == null || look.fits(pitch);
        }
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

    private static final double[] HIGH_LOW = {0.25, 0.75};
    private static final double[] MIDDLE = {0.5};

    private Placing() {
    }

    /**
     * Whether a schematic block is left alone: fluids, the halves that come with the other half
     * (a door's top, a bed's head), piston heads, and anything with no item to place it with.
     */
    public static boolean skipped(BlockState want) {
        Block block = want.getBlock();
        if (block instanceof LiquidBlock || block instanceof PistonHeadBlock || block instanceof MovingPistonBlock) return true;
        if (block == Blocks.FIRE || block == Blocks.SOUL_FIRE || block == Blocks.NETHER_PORTAL || block == Blocks.END_PORTAL
            || block == Blocks.BUBBLE_COLUMN || block == Blocks.END_GATEWAY) return true;
        if (want.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && want.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return true;
        if (block instanceof BedBlock && want.getValue(BedBlock.PART) == BedPart.HEAD) return true;
        return !(block.asItem() instanceof BlockItem);
    }

    /** Close enough: the same block, and the same in everything placing decides. */
    public static boolean matches(BlockState have, BlockState want) {
        if (have.getBlock() != want.getBlock()) return false;
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
                        Vec3 hit = self ? slabHit(target, here, face) : facePoint(against, face, h);
                        net.minecraft.world.phys.BlockHitResult click = new net.minecraft.world.phys.BlockHitResult(hit, face, against, false);
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
                        if (count == 0) continue;
                        if (count == 4 * Look.values().length) {
                            out.add(new Way(against, face, hit, null, null));
                            continue;
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
                }
            }
        } finally {
            player.setYRot(yaw);
            player.setXRot(pitch);
            player.yRotO = yawO;
            player.xRotO = pitchO;
        }
        return out;
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

    /** The open face of a single slab: its top if it sits at the bottom, its underside if at the top. */
    private static List<Direction> slabFaces(BlockState slab) {
        if (!(slab.getBlock() instanceof SlabBlock) || slab.getValue(SlabBlock.TYPE) == SlabType.DOUBLE) return List.of();
        return List.of(slab.getValue(SlabBlock.TYPE) == SlabType.BOTTOM ? Direction.UP : Direction.DOWN);
    }

    private static Vec3 slabHit(BlockPos pos, BlockState slab, Direction face) {
        return new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }
}
