package com.cyanite.client.visuals;

import com.cyanite.client.render.Render3D;
import com.cyanite.client.system.WorldScan;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Numbers that jump off players and mobs when they lose health: how much went, in hearts or health points.
 * Read from the health the server already sends for everything near you; your crits show in their own colour.
 */
public final class DamageNumbers {
    public static final Group GROUP = new Group("damage_numbers", "Damage Indicators",
        "Numbers that pop off players and mobs showing how much health they just lost.",
        "damage indicator numbers hit health hearts popup");
    static final ColorSetting COLOR = GROUP.add(new ColorSetting("Color", 0xFF5555).full());
    static final ColorSetting CRIT_COLOR = GROUP.add(new ColorSetting("Your crits", 0xFFD54A).full());
    static final BoolSetting HEALING = GROUP.add(new BoolSetting("Show healing", true));
    static final ColorSetting HEAL_COLOR = GROUP.add(new ColorSetting("Healing color", 0x55FF7A).full());
    static final ModeSetting FORMAT = GROUP.add(new ModeSetting("Show as", "Hearts", List.of("Hearts", "Health")));
    static final SliderSetting SCALE = GROUP.add(new SliderSetting("Size", 1, 0.4, 2.5, 0.05).format(Particles::times));
    static final SliderSetting LENGTH = GROUP.add(new SliderSetting("Length", 1, 0.4, 2.5, 0.05).format(v -> String.format(Locale.ROOT, "%.1fs", v)));
    static final BoolSetting SELF = GROUP.add(new BoolSetting("On yourself too", false));
    static final BoolSetting THROUGH = GROUP.add(new BoolSetting("Through walls", false));

    private static final double RANGE = 32;
    private static final int MAX = 48;

    private record Pop(Entity entity, Vec3 at, String text, int color, float size, long start, double dx, double dz) {
    }

    /** Each living thing's health plus absorption, last tick. */
    private static final Map<Integer, Float> health = new HashMap<>();
    private static final List<Pop> pops = new ArrayList<>();
    private static Object level;

    private DamageNumbers() {
    }

    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (level != mc.level) {
            level = mc.level;
            health.clear();
            pops.clear();
        }
        Map<Integer, Float> seen = new HashMap<>();
        for (Entity e : WorldScan.entities()) {
            if (!(e instanceof LivingEntity living) || e.distanceToSqr(player) > RANGE * RANGE) continue;
            if (e == player && !SELF.get()) continue;
            float now = living.isDeadOrDying() ? 0 : living.getHealth() + living.getAbsorptionAmount();
            Float before = health.get(e.getId());
            seen.put(e.getId(), now);
            if (before == null) continue;
            float change = now - before;
            if (change <= -0.05f) add(living, -change, Combat.hitRecently(e, 0.6) && Combat.lastCrit ? CRIT_COLOR.get() : COLOR.get(), false);
            else if (change >= 0.5f && HEALING.get()) add(living, change, HEAL_COLOR.get(), true);
        }
        health.clear();
        health.putAll(seen);
        long now = System.nanoTime();
        float length = LENGTH.get().floatValue();
        pops.removeIf(p -> (now - p.start) / 1e9f > length);
    }

    private static void add(LivingEntity e, float amount, int rgb, boolean heal) {
        String text = FORMAT.is("Hearts")
            ? trim(amount / 2) + "❤"
            : trim(amount);
        if (heal) text = "+" + text;
        Vec3 at = e.position().add(0, e.getBbHeight() + 0.25, 0);
        double angle = Math.random() * Math.PI * 2;
        if (pops.size() >= MAX) pops.remove(0);
        pops.add(new Pop(e, at, text, 0xFF000000 | rgb, Math.min(1.6f, 0.9f + amount / 20f), System.nanoTime(),
            Math.cos(angle) * 0.35, Math.sin(angle) * 0.35));
    }

    private static String trim(float v) {
        String s = String.format(Locale.ROOT, "%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    static void render3D(Render3D render) {
        if (pops.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        Quaternionf facing = WorldFx.facing(mc.gameRenderer.getMainCamera());
        long now = System.nanoTime();
        float length = LENGTH.get().floatValue();
        float scale = SCALE.get().floatValue();
        boolean through = THROUGH.get();
        for (Pop p : pops) {
            float t = (now - p.start) / 1e9f / length;
            if (t >= 1) continue;
            // Up and out in a little arc, then hanging while it fades.
            float up = 1 - (1 - Math.min(1, t * 2.2f)) * (1 - Math.min(1, t * 2.2f));
            Vec3 at = p.at.add(p.dx * up, up * 0.55, p.dz * up);
            float grow = t < 0.12f ? 0.6f + t / 0.12f * 0.6f : 1.2f - Math.min(0.2f, (t - 0.12f));
            float alpha = t < 0.65f ? 1 : 1 - (t - 0.65f) / 0.35f;
            int color = Math.max(6, Math.round(255 * alpha)) << 24 | p.color & 0xFFFFFF;
            float size = 0.03f * scale * p.size * grow;
            render.text(at, facing, size, -mc.font.width(p.text) / 2f, -mc.font.lineHeight / 2f, p.text, color, 0, through);
        }
    }
}
