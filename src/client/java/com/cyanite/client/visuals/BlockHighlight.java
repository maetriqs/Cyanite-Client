package com.cyanite.client.visuals;

import com.cyanite.client.render.Render3D;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.VoxelShape;

/** The outline round the block you're looking at: its colour, opacity and width, and a fill if you like. */
public final class BlockHighlight {
    public static final Group GROUP = new Group("block_highlight", "Block Highlight",
        "Colour, opacity and width of the outline on the block you look at, with an optional fill.",
        "block outline highlight selection box fill");
    static final ColorSetting COLOR = GROUP.add(new ColorSetting("Color", 0x4DE1FF).full());
    static final SliderSetting OPACITY = GROUP.add(new SliderSetting("Opacity", 0.9, 0.05, 1, 0.05).percent());
    static final SliderSetting WIDTH = GROUP.add(new SliderSetting("Width", 1.5, 0.5, 4, 0.1).format(Particles::times));
    static final BoolSetting FILL = GROUP.add(new BoolSetting("Fill", true));
    static final SliderSetting FILL_OPACITY = GROUP.add(new SliderSetting("Fill opacity", 0.18, 0.02, 0.8, 0.02).percent());

    private BlockHighlight() {
    }

    /** The outline's ARGB in place of the game's. */
    public static int color(int color) {
        return GROUP.on() ? Visuals.argb(COLOR.get(), OPACITY.get()) : color;
    }

    public static float width(float width) {
        return GROUP.on() ? width * WIDTH.get().floatValue() : width;
    }

    static void render3D(Render3D render) {
        if (!FILL.get()) return;
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
        BlockPos pos = hit.getBlockPos();
        VoxelShape shape = mc.level.getBlockState(pos).getShape(mc.level, pos);
        if (shape.isEmpty()) return;
        AABB box = shape.bounds().move(pos).inflate(0.002);
        render.box(box, Visuals.argb(COLOR.get(), FILL_OPACITY.get()), 0, 1, false);
    }
}
