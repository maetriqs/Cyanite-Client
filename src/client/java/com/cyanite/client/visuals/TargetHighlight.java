package com.cyanite.client.visuals;

import com.cyanite.client.render.Render3D;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** A box, brackets or a ring on whoever you last hit or are looking at, flashing when you land a hit. */
public final class TargetHighlight {
    public static final Group GROUP = new Group("target_highlight", "Target Highlight",
        "Marks whoever you last hit or are aiming at, and flashes when you hit them.",
        "target highlight box outline ring focus enemy aim");
    static final ModeSetting WHO = GROUP.add(new ModeSetting("Mark", "Both", List.of("Last hit", "Looking at", "Both")));
    static final ModeSetting STYLE = GROUP.add(new ModeSetting("Style", "Corners", List.of("Box", "Corners", "Outline", "Ring")));
    static final ColorSetting COLOR = GROUP.add(new ColorSetting("Color", 0x4DE1FF).full());
    static final ColorSetting HIT_COLOR = GROUP.add(new ColorSetting("On hit", 0xFF3B3B).full());
    static final SliderSetting KEEP = GROUP.add(new SliderSetting("Keep last hit for", 4, 1, 15, 0.5).format(Trails::seconds));
    static final BoolSetting PLAYERS = GROUP.add(new BoolSetting("Players only", false));

    private static final int RING_SEGMENTS = 32;

    private TargetHighlight() {
    }

    static void render3D(Render3D render) {
        Minecraft mc = Minecraft.getInstance();
        float partial = render.partialTick();
        Entity looked = WHO.is("Last hit") ? null : mc.crosshairPickEntity;
        Entity hit = WHO.is("Looking at") || !Combat.hitRecently(Combat.lastTarget, KEEP.get()) ? null : Combat.lastTarget;
        if (wanted(hit)) mark(render, hit, partial);
        if (looked != hit && wanted(looked)) mark(render, looked, partial);
    }

    private static boolean wanted(Entity e) {
        if (!(e instanceof LivingEntity living) || e.isRemoved() || living.isDeadOrDying()) return false;
        return !PLAYERS.get() || e instanceof Player;
    }

    private static void mark(Render3D render, Entity e, float partial) {
        // Fades from the hit colour back to the usual one over a third of a second after each of your hits.
        float since = Combat.lastTarget == e ? (System.nanoTime() - Combat.lastHitAt) / 1e9f : 1;
        int rgb = Visuals.lerp(0xFF000000 | HIT_COLOR.get(), 0xFF000000 | COLOR.get(), since / 0.35f) & 0xFFFFFF;
        float pulse = since < 0.35f ? 1 - since / 0.35f : 0;
        Vec3 at = e.getPosition(partial);
        AABB box = e.getBoundingBox().move(at.subtract(e.position())).inflate(0.05 + pulse * 0.08);
        float width = 2f + pulse * 1.5f;
        switch (STYLE.get()) {
            case "Box" -> render.box(box, Visuals.argb(rgb, 0.15 + pulse * 0.2), Visuals.argb(rgb, 0.95), width, true);
            case "Outline" -> render.box(box, 0, Visuals.argb(rgb, 0.95), width, true);
            case "Ring" -> {
                double radius = e.getBbWidth() * 0.9 + pulse * 0.25;
                double spin = System.nanoTime() / 1e9 * 1.5;
                double y = at.y + 0.05 + (Math.sin(System.nanoTime() / 1e9 * 2.5) * 0.5 + 0.5) * e.getBbHeight() * 0.9;
                Vec3 prev = null;
                for (int i = 0; i <= RING_SEGMENTS; i++) {
                    double a = spin + Math.PI * 2 * i / RING_SEGMENTS;
                    Vec3 p = new Vec3(at.x + Math.cos(a) * radius, y, at.z + Math.sin(a) * radius);
                    if (prev != null) render.line(prev, p, Visuals.argb(rgb, 0.95), width, true);
                    prev = p;
                }
            }
            default -> render.corners(box, Visuals.argb(rgb, 0.1 + pulse * 0.2), Visuals.argb(rgb, 0.1 + pulse * 0.2), Visuals.argb(rgb, 0.95), width, true);
        }
    }
}
