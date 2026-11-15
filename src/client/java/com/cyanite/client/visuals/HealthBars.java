package com.cyanite.client.visuals;

import com.cyanite.client.render.Projection;
import com.cyanite.client.system.WorldScan;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** A health bar over the heads of players (and mobs, if you like), with absorption on top and the number beside it. */
public final class HealthBars {
    public static final Group GROUP = new Group("health_bars", "Health Indicators",
        "A bar over everyone's head showing how much health and absorption they have left.",
        "health indicator bar hearts hp absorption over head nametag");
    static final ModeSetting WHO = GROUP.add(new ModeSetting("Show for", "Players", List.of("Players", "Players and mobs")));
    static final ColorSetting HIGH = GROUP.add(new ColorSetting("Full color", 0x4DFF7A).full());
    static final ColorSetting LOW = GROUP.add(new ColorSetting("Low color", 0xFF3B3B).full());
    static final ColorSetting ABSORPTION = GROUP.add(new ColorSetting("Absorption color", 0xFFD54A).full());
    static final BoolSetting NUMBER = GROUP.add(new BoolSetting("Number", true));
    static final ModeSetting FORMAT = GROUP.add(new ModeSetting("Number as", "Hearts", List.of("Hearts", "Health", "Percent")));
    static final SliderSetting SIZE = GROUP.add(new SliderSetting("Size", 1, 0.5, 2, 0.05).format(Particles::times));
    static final SliderSetting RANGE = GROUP.add(new SliderSetting("Range", 32, 8, 64, 1));

    private static final int MAX = 24;

    private HealthBars() {
    }

    static void render(GuiGraphics g, float partial) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) return;
        double range = RANGE.get();
        boolean mobs = WHO.is("Players and mobs");
        List<LivingEntity> near = new ArrayList<>();
        for (Entity e : WorldScan.entities()) {
            if (!(e instanceof LivingEntity living) || e == mc.player || e instanceof ArmorStand || living.isDeadOrDying()) continue;
            if (!mobs && !(e instanceof Player)) continue;
            if (e.isInvisibleTo(mc.player) || e.distanceToSqr(mc.player) > range * range) continue;
            near.add(living);
        }
        if (near.isEmpty()) return;
        near.sort(Comparator.comparingDouble(e -> e.distanceToSqr(mc.player)));
        if (near.size() > MAX) near = near.subList(0, MAX);

        double gui = mc.getWindow().getGuiScale();
        float size = SIZE.get().floatValue();
        // Furthest first, so the nearest bars are drawn on top.
        for (int i = near.size() - 1; i >= 0; i--) {
            LivingEntity e = near.get(i);
            Vec3 above = e.getPosition(partial).add(0, e.getBbHeight() + (e instanceof Player || e.shouldShowName() ? 0.75 : 0.35), 0);
            double[] at = Projection.toScreen(above);
            if (at == null) continue;
            double dist = Math.sqrt(e.distanceToSqr(mc.player));
            float k = (float) Math.max(0.55, Math.min(1, 10 / Math.max(1, dist))) * size;
            draw(g, (float) (at[0] / gui), (float) (at[1] / gui), k, e.getHealth(), e.getMaxHealth(), e.getAbsorptionAmount());
        }
    }

    /** One bar centred on x, its bottom edge at y. Also the tab's preview. */
    static void draw(GuiGraphics g, float x, float y, float k, float health, float max, float absorption) {
        Minecraft mc = Minecraft.getInstance();
        float w = 40 * k;
        float h = 4 * k;
        float left = x - w / 2;
        float top = y - h;
        float frac = max <= 0 ? 0 : Math.max(0, Math.min(1, health / max));
        int color = Visuals.lerp(0xFF000000 | LOW.get(), 0xFF000000 | HIGH.get(), frac);
        Shapes.of(g, 0xB0000000).rect(left - 1, top - 1, left + w + 1, y + 1).submit();
        Shapes.of(g, color).rect(left, top, left + w * frac, y).submit();
        if (absorption > 0) {
            float abs = Math.min(1, absorption / Math.max(1, max));
            Shapes.of(g, 0xFF000000 | ABSORPTION.get()).rect(left, top, left + w * abs, top + Math.max(1, h * 0.4f)).submit();
        }
        if (!NUMBER.get()) return;
        float total = health + absorption;
        String text = switch (FORMAT.get()) {
            case "Health" -> String.valueOf((int) Math.ceil(total));
            case "Percent" -> Math.round(frac * 100) + "%";
            default -> trim(total / 2) + "❤";
        };
        g.pose().pushMatrix();
        g.pose().translate(left + w + 3 * k, top + h / 2);
        g.pose().scale(Math.max(0.5f, 0.75f * k));
        g.drawString(mc.font, text, 0, -mc.font.lineHeight / 2 + 1, absorption > 0 ? 0xFF000000 | ABSORPTION.get() : color, true);
        g.pose().popMatrix();
    }

    private static String trim(float v) {
        String s = String.format(Locale.ROOT, "%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }
}
