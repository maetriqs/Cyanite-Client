package com.cyanite.client.modules.combat;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Notify;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Walls in your feet with blast-proof blocks (obsidian and friends) so explosions can't reach you,
 * and keeps the wall up if something breaks it. Works off-centre too: it covers every block your
 * feet overlap. Turns off when you move away.
 */
public class Surround extends Module {
    private static final Direction[] SUPPORTS = {Direction.DOWN, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.UP};
    private static final float BLAST_PROOF = 600;

    private final SliderSetting perTick = add(new SliderSetting("Blocks per tick", 2, 1, 4, 1));
    private final BoolSetting offWhenMoving = add(new BoolSetting("Off when you move", true));
    private final BoolSetting switchBack = add(new BoolSetting("Switch back", true));
    private final BoolSetting swing = add(new BoolSetting("Swing", true));

    private Set<BlockPos> footprint;

    public Surround() {
        super(Category.COMBAT, "Surround", "Walls your feet in with obsidian");
    }

    @Override
    protected void onEnable() {
        footprint = null;
    }

    @Override
    public void onTick() {
        if (mc.screen != null) return;

        Set<BlockPos> now = footprint();
        if (footprint == null) footprint = now;
        if (offWhenMoving.get() && !now.equals(footprint)) {
            setEnabled(false);
            return;
        }

        List<BlockPos> wall = new ArrayList<>();
        for (BlockPos column : now) {
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos pos = column.relative(side);
                if (!now.contains(pos)) wall.add(pos);
            }
        }

        int placed = 0;
        for (BlockPos pos : wall) {
            if (placed >= perTick.get()) break;
            if (!mc.level.getBlockState(pos).canBeReplaced() || occupied(pos)) continue;
            BlockHitResult hit = support(pos);
            if (hit == null) continue;

            int slot = findBlock();
            if (slot < 0) {
                Notify.send("Surround", "No obsidian (or other blast-proof block) in your hotbar", true, false, false);
                setEnabled(false);
                return;
            }
            int previous = mc.player.getInventory().getSelectedSlot();
            mc.player.getInventory().setSelectedSlot(slot);
            InteractionResult result = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
            if (result.consumesAction()) {
                placed++;
                if (swing.get()) mc.player.swing(InteractionHand.MAIN_HAND);
            }
            if (switchBack.get()) mc.player.getInventory().setSelectedSlot(previous);
        }
    }

    /** Every block column your hitbox stands in, at foot level. */
    private Set<BlockPos> footprint() {
        AABB box = mc.player.getBoundingBox();
        int y = Mth.floor(mc.player.getY() + 0.2);
        Set<BlockPos> out = new LinkedHashSet<>();
        for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX - 1e-4); x++) {
            for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ - 1e-4); z++) {
                out.add(new BlockPos(x, y, z));
            }
        }
        return out;
    }

    /** Something other than a dropped item standing where the block would go. */
    private boolean occupied(BlockPos pos) {
        return !mc.level.getEntities((net.minecraft.world.entity.Entity) null, new AABB(pos), e -> !(e instanceof ItemEntity) && !e.isSpectator()).isEmpty();
    }

    private BlockHitResult support(BlockPos pos) {
        for (Direction direction : SUPPORTS) {
            BlockPos neighbour = pos.relative(direction);
            if (mc.level.getBlockState(neighbour).canBeReplaced()) continue;
            Direction face = direction.getOpposite();
            return new BlockHitResult(Vec3.atCenterOf(neighbour).relative(face, 0.5), face, neighbour, false);
        }
        return null;
    }

    private int findBlock() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!(stack.getItem() instanceof BlockItem item)) continue;
            Block block = item.getBlock();
            if (block.getExplosionResistance() >= BLAST_PROOF && block.defaultBlockState().isCollisionShapeFullBlock(mc.level, BlockPos.ZERO)) return i;
        }
        return -1;
    }
}
