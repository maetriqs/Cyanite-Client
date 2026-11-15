package com.cyanite.client.visuals;

import com.cyanite.client.render.Render3D;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * Obsidian and bedrock near you washed in colours of yours, so the blocks you can crystal on stand out.
 * A small area round you is looked over a few times a second; only blocks with air above count, if you like.
 */
public final class BlockColors {
    public static final Group GROUP = new Group("block_colors", "Obsidian & Bedrock Colors",
        "Colours obsidian and bedrock near you, so crystal spots stand out.", "obsidian bedrock color crystal block place spot");
    static final ColorSetting OBSIDIAN = GROUP.add(new ColorSetting("Obsidian color", 0x9A4DFF).full());
    static final ColorSetting BEDROCK = GROUP.add(new ColorSetting("Bedrock color", 0x4DE1FF).full());
    static final BoolSetting CRYING = GROUP.add(new BoolSetting("Crying obsidian too", true));
    static final SliderSetting RANGE = GROUP.add(new SliderSetting("Range", 6, 2, 10, 1));
    static final BoolSetting EXPOSED = GROUP.add(new BoolSetting("Only with room for a crystal", true));
    static final ModeSetting STYLE = GROUP.add(new ModeSetting("Style", "Top face", List.of("Top face", "Box", "Outline")));
    static final SliderSetting OPACITY = GROUP.add(new SliderSetting("Opacity", 0.35, 0.05, 1, 0.05).percent());

    private static final int MAX = 700;
    private record Spot(BlockPos pos, int rgb) {
    }

    private static final List<Spot> spots = new ArrayList<>();
    private static int wait;

    private BlockColors() {
    }

    static void tick() {
        if (--wait > 0) return;
        wait = 5;
        spots.clear();
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        BlockPos center = mc.player.blockPosition();
        int r = RANGE.get().intValue();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos above = new BlockPos.MutableBlockPos();
        for (int dy = -r; dy <= r && spots.size() < MAX; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    pos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    Block block = level.getBlockState(pos).getBlock();
                    int rgb;
                    if (block == Blocks.OBSIDIAN || CRYING.get() && block == Blocks.CRYING_OBSIDIAN) rgb = OBSIDIAN.get();
                    else if (block == Blocks.BEDROCK) rgb = BEDROCK.get();
                    else continue;
                    if (EXPOSED.get()) {
                        above.set(pos.getX(), pos.getY() + 1, pos.getZ());
                        if (!level.getBlockState(above).isAir()) continue;
                    }
                    spots.add(new Spot(pos.immutable(), rgb));
                    if (spots.size() >= MAX) break;
                }
            }
        }
    }

    static void render3D(Render3D render) {
        double alpha = OPACITY.get();
        String style = STYLE.get();
        for (Spot spot : spots) {
            BlockPos p = spot.pos;
            if (style.equals("Top face")) {
                AABB top = new AABB(p.getX(), p.getY() + 1, p.getZ(), p.getX() + 1, p.getY() + 1.02, p.getZ() + 1);
                render.box(top, Visuals.argb(spot.rgb, alpha), Visuals.argb(spot.rgb, Math.min(1, alpha + 0.3)), 1.2f, false);
            } else {
                AABB box = new AABB(p).deflate(0.002);
                int fill = style.equals("Outline") ? 0 : Visuals.argb(spot.rgb, alpha * 0.6);
                render.box(box, fill, Visuals.argb(spot.rgb, Math.min(1, alpha + 0.3)), 1.4f, false);
            }
        }
    }
}
