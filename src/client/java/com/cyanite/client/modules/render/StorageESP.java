package com.cyanite.client.modules.render;

import com.cyanite.client.render.Render3D;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/** Boxes around containers, drawn through walls. */
public class StorageESP extends Module {
    private static final int SCAN_INTERVAL = 10;

    private final BoolSetting chests = add(new BoolSetting("Chests", true));
    private final ColorSetting chestColor = add(new ColorSetting("Chest color", 0xF5C542));
    private final BoolSetting enderChests = add(new BoolSetting("Ender chests", true));
    private final ColorSetting enderColor = add(new ColorSetting("Ender color", 0xA35CFF));
    private final BoolSetting shulkers = add(new BoolSetting("Shulkers", true));
    private final BoolSetting dyeColors = add(new BoolSetting("Dye colors", true));
    private final ColorSetting shulkerColor = add(new ColorSetting("Shulker color", 0xFF6BD3));
    private final BoolSetting barrels = add(new BoolSetting("Barrels", true));
    private final ColorSetting barrelColor = add(new ColorSetting("Barrel color", 0xA8743F));
    private final BoolSetting hoppers = add(new BoolSetting("Hoppers", false));
    private final ColorSetting hopperColor = add(new ColorSetting("Hopper color", 0x9AA3AF));
    private final BoolSetting furnaces = add(new BoolSetting("Furnaces", false));
    private final ColorSetting furnaceColor = add(new ColorSetting("Furnace color", 0xD9D9D9));
    private final BoolSetting dispensers = add(new BoolSetting("Dispensers", false));
    private final ColorSetting dispenserColor = add(new ColorSetting("Dispenser color", 0x6FB7D6));
    private final SliderSetting range = add(new SliderSetting("Range", 128, 16, 512, 16).format(v -> (int) v + "m"));
    private final EspStyle style = new EspStyle(false);

    private List<Target> targets = List.of();
    private int ticks;

    private record Target(AABB box, int rgb, BlockEntity entity) {
    }

    public StorageESP() {
        super(Category.RENDER, "Storage ESP", "Shows containers through walls");
        style.settings().forEach(this::add);
    }

    @Override
    public String getInfo() {
        return Integer.toString(targets.size());
    }

    @Override
    protected void onEnable() {
        ticks = SCAN_INTERVAL;
    }

    @Override
    protected void onDisable() {
        targets = List.of();
    }

    @Override
    public void onTick() {
        // Walking every loaded chunk is too much for every frame, so scan twice a second and draw the result.
        if (++ticks < SCAN_INTERVAL) return;
        ticks = 0;
        targets = scan();
    }

    @Override
    public void onRender3D(Render3D render) {
        double maxSq = range.get() * range.get();
        for (Target target : targets) {
            if (target.entity.isRemoved()) continue;
            if (target.box.getCenter().distanceToSqr(render.camera()) > maxSq) continue;
            style.draw(render, target.box, target.rgb);
        }
    }

    private List<Target> scan() {
        List<Target> found = new ArrayList<>();
        ChunkPos center = mc.player.chunkPosition();
        int radius = mc.options.getEffectiveRenderDistance();

        for (int cx = center.x - radius; cx <= center.x + radius; cx++) {
            for (int cz = center.z - radius; cz <= center.z + radius; cz++) {
                if (!mc.level.hasChunk(cx, cz)) continue;
                LevelChunk chunk = mc.level.getChunk(cx, cz);

                for (BlockEntity entity : chunk.getBlockEntities().values()) {
                    Integer rgb = colorFor(entity);
                    if (rgb == null) continue;
                    AABB box = boxFor(entity);
                    if (box != null) found.add(new Target(box, rgb, entity));
                }
            }
        }
        return found;
    }

    private Integer colorFor(BlockEntity entity) {
        if (entity instanceof ChestBlockEntity) return chests.get() ? chestColor.get() : null;
        if (entity instanceof EnderChestBlockEntity) return enderChests.get() ? enderColor.get() : null;
        if (entity instanceof BarrelBlockEntity) return barrels.get() ? barrelColor.get() : null;
        if (entity instanceof HopperBlockEntity) return hoppers.get() ? hopperColor.get() : null;
        if (entity instanceof AbstractFurnaceBlockEntity) return furnaces.get() ? furnaceColor.get() : null;
        if (entity instanceof DispenserBlockEntity) return dispensers.get() ? dispenserColor.get() : null;
        if (entity instanceof ShulkerBoxBlockEntity shulker) {
            if (!shulkers.get()) return null;
            if (dyeColors.get() && shulker.getColor() != null) return shulker.getColor().getTextureDiffuseColor() & 0xFFFFFF;
            return shulkerColor.get();
        }
        return null;
    }

    /** The block's own shape, with both halves of a double chest drawn as one box. */
    private AABB boxFor(BlockEntity entity) {
        BlockPos pos = entity.getBlockPos();
        BlockState state = entity.getBlockState();
        AABB box = bounds(state, pos);

        if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
            // Let only one half draw the pair.
            if (pos.asLong() > other.asLong()) return null;
            box = box.minmax(bounds(mc.level.getBlockState(other), other));
        }
        return box;
    }

    private AABB bounds(BlockState state, BlockPos pos) {
        VoxelShape shape = state.getShape(mc.level, pos);
        return shape.isEmpty() ? new AABB(pos) : shape.bounds().move(pos);
    }
}
