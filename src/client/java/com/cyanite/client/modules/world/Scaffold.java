package com.cyanite.client.modules.world;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.BoolSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Places blocks under your feet as you walk. */
public class Scaffold extends Module {
    private static final Direction[] SUPPORTS = {Direction.DOWN, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    private final BoolSetting autoSwitch = add(new BoolSetting("Auto switch", true));
    private final BoolSetting switchBack = add(new BoolSetting("Switch back", true));
    private final BoolSetting swing = add(new BoolSetting("Swing", true));

    public Scaffold() {
        super(Category.WORLD, "Scaffold", "Builds a bridge under you");
    }

    @Override
    public void onTick() {
        if (mc.screen != null) return;

        BlockPos below = BlockPos.containing(mc.player.getX(), mc.player.getY() - 0.5, mc.player.getZ());
        if (!mc.level.getBlockState(below).canBeReplaced()) return;

        BlockHitResult hit = support(below);
        if (hit == null) return;

        int previous = mc.player.getInventory().getSelectedSlot();
        int slot = isPlaceable(mc.player.getMainHandItem()) ? previous : autoSwitch.get() ? findBlock() : -1;
        if (slot < 0) return;

        mc.player.getInventory().setSelectedSlot(slot);
        InteractionResult result = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        if (result.consumesAction() && swing.get()) mc.player.swing(InteractionHand.MAIN_HAND);
        if (switchBack.get()) mc.player.getInventory().setSelectedSlot(previous);
    }

    /** A click on the face of a solid neighbour that would put a block at {@code pos}. */
    private BlockHitResult support(BlockPos pos) {
        for (Direction direction : SUPPORTS) {
            BlockPos neighbour = pos.relative(direction);
            if (mc.level.getBlockState(neighbour).canBeReplaced()) continue;

            Direction face = direction.getOpposite();
            Vec3 point = Vec3.atCenterOf(neighbour).relative(face, 0.5);
            return new BlockHitResult(point, face, neighbour, false);
        }
        return null;
    }

    private int findBlock() {
        for (int i = 0; i < 9; i++) {
            if (isPlaceable(mc.player.getInventory().getItem(i))) return i;
        }
        return -1;
    }

    private boolean isPlaceable(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem item)) return false;
        // Only full blocks: slabs, torches and flowers make a poor floor.
        return item.getBlock().defaultBlockState().isCollisionShapeFullBlock(mc.level, BlockPos.ZERO);
    }
}
