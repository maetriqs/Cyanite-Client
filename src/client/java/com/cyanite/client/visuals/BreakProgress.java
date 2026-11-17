package com.cyanite.client.visuals;

import com.cyanite.client.mixin.MultiPlayerGameModeAccessor;
import com.cyanite.client.render.Render3D;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.awt.Color;
import java.util.List;

/**
 * The block you're mining fills with a glowing box as it breaks, shading from one colour to another, and gives a
 * little burst of light when it goes.
 */
public final class BreakProgress {
    public static final Group GROUP = new Group("break_progress", "Break Progress",
        "The block you're mining fills with a glowing box as it breaks, shading from one colour to another, with a burst when it goes.",
        "break mining mine progress block dig crack glow percent");
    static final ModeSetting STYLE = GROUP.add(new ModeSetting("Style", "Grow", List.of("Grow", "Fill up", "Shrink")));
    static final ColorSetting START = GROUP.add(new ColorSetting("Start color", 0xFF4D6A).full());
    static final ColorSetting DONE = GROUP.add(new ColorSetting("Done color", 0x4DFF9A).full());
    static final SliderSetting FILL = GROUP.add(new SliderSetting("Fill opacity", 0.3, 0, 0.8, 0.02).percent());
    static final BoolSetting GLOW = GROUP.add(new BoolSetting("Glow", true));
    static final BoolSetting BURST = GROUP.add(new BoolSetting("Burst when broken", true));
    static final BoolSetting PERCENT = GROUP.add(new BoolSetting("Percent", false));

    private static BlockPos pos;
    private static float progress;
    private static float before;

    private BreakProgress() {
    }

    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        MultiPlayerGameModeAccessor mode = (MultiPlayerGameModeAccessor) mc.gameMode;
        BlockPos now = mc.gameMode.isDestroying() ? mode.cyanite$getDestroyBlockPos() : null;
        // It went while we were on it far enough along to be the one that broke it.
        if (pos != null && !pos.equals(now) && progress > 0.6f && BURST.get() && mc.level.getBlockState(pos).isAir()) {
            Vec3 middle = Vec3.atCenterOf(pos);
            WorldFx.burst(middle, DONE.get(), 1.1, 0.45f);
            WorldFx.ring(middle.add(0, -0.45, 0), DONE.get(), 0.6, 2.2, 0.45f, 2f);
        }
        if (now == null) {
            pos = null;
            progress = before = 0;
            return;
        }
        if (!now.equals(pos)) {
            pos = now.immutable();
            progress = 0;
        }
        before = progress;
        progress = Math.min(1, mode.cyanite$getDestroyProgress());
    }

    static void render3D(Render3D render) {
        if (pos == null) return;
        Minecraft mc = Minecraft.getInstance();
        VoxelShape shape = mc.level.getBlockState(pos).getShape(mc.level, pos);
        if (shape.isEmpty()) return;
        float p = Math.max(0, Math.min(1, before + (progress - before) * render.partialTick()));
        AABB block = shape.bounds().move(pos);
        Vec3 c = block.getCenter();
        double sx = block.getXsize() / 2, sy = block.getYsize() / 2, sz = block.getZsize() / 2;
        AABB box = switch (STYLE.get()) {
            case "Fill up" -> new AABB(block.minX, block.minY, block.minZ, block.maxX, block.minY + block.getYsize() * Math.max(0.03, p), block.maxZ);
            case "Shrink" -> {
                double k = Math.max(0.04, 1 - p);
                yield new AABB(c.x - sx * k, c.y - sy * k, c.z - sz * k, c.x + sx * k, c.y + sy * k, c.z + sz * k);
            }
            default -> {
                double k = Math.max(0.04, p);
                yield new AABB(c.x - sx * k, c.y - sy * k, c.z - sz * k, c.x + sx * k, c.y + sy * k, c.z + sz * k);
            }
        };
        int rgb = color(p);
        boolean glow = GLOW.get();
        render.box(box.inflate(0.003), Visuals.argb(rgb, FILL.get()), Visuals.argb(rgb, 0.95), 2f, glow);
        if (glow) render.glow(c, 0.35 + 0.55 * p, Visuals.argb(rgb, 0.12 + 0.2 * p), true);
        if (PERCENT.get()) {
            String text = Math.round(p * 100) + "%";
            var font = mc.font;
            render.text(new Vec3(c.x, block.maxY + 0.35, c.z), WorldFx.facing(mc.gameRenderer.getMainCamera()), 0.022f,
                -font.width(text) / 2f, -font.lineHeight / 2f, text, 0xFF000000 | rgb, 0x60000000, true);
        }
    }

    /** Start color to Done color round the colour wheel the short way, so red to green goes by orange and yellow, not brown. */
    static int color(float p) {
        float[] a = Color.RGBtoHSB(START.get() >> 16 & 0xFF, START.get() >> 8 & 0xFF, START.get() & 0xFF, null);
        float[] b = Color.RGBtoHSB(DONE.get() >> 16 & 0xFF, DONE.get() >> 8 & 0xFF, DONE.get() & 0xFF, null);
        float turn = b[0] - a[0];
        if (turn > 0.5f) turn -= 1;
        if (turn < -0.5f) turn += 1;
        float hue = a[0] + turn * p;
        return Color.HSBtoRGB(hue - (float) Math.floor(hue), a[1] + (b[1] - a[1]) * p, a[2] + (b[2] - a[2]) * p) & 0xFFFFFF;
    }
}
