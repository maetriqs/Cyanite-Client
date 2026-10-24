package com.cyanite.client.modules.render;

import com.cyanite.client.gui.Theme;
import com.cyanite.client.render.Render3D;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.WorldScan;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Lines from the middle of your screen to players (and whatever else you pick), coloured by how close they are. */
public class Tracers extends Module {
    private final BoolSetting players = add(new BoolSetting("Players", true));
    private final BoolSetting hostiles = add(new BoolSetting("Hostiles", false));
    private final BoolSetting passives = add(new BoolSetting("Passives", false));
    private final BoolSetting items = add(new BoolSetting("Items", false));
    private final ModeSetting colorMode = add(new ModeSetting("Color", "Distance", List.of("Distance", "Custom")));
    private final ColorSetting color = add(new ColorSetting("Custom color", 0x5DADFF));
    private final SliderSetting range = add(new SliderSetting("Range", 128, 16, 256, 8).format(v -> (int) (double) v + " blocks"));
    private final SliderSetting width = add(new SliderSetting("Width", 1.5, 1, 4, 0.5));
    private final BoolSetting glow = add(new BoolSetting("Glow", true));

    public Tracers() {
        super(Category.RENDER, "Tracers", "Lines to players and mobs, red when they're close");
    }

    @Override
    public void onRender3D(Render3D render) {
        float pt = render.partialTick();
        double max = range.get();
        for (Entity e : WorldScan.entities()) {
            if (e == mc.player || !e.isAlive() || !wanted(e)) continue;
            double d = e.distanceTo(mc.player);
            if (d > max) continue;
            Vec3 at = e.getPosition(pt).add(0, e.getBbHeight() / 2, 0);
            int rgb = colorMode.is("Custom") ? 0xFF000000 | color.get() : byDistance(d);
            render.tracer(at, rgb, width.get().floatValue(), glow.get());
        }
    }

    private boolean wanted(Entity e) {
        if (e instanceof Player p) return players.get() && !p.isSpectator();
        if (e instanceof ItemEntity) return items.get();
        if (e instanceof Enemy) return hostiles.get();
        return e instanceof LivingEntity && !(e instanceof ArmorStand) && passives.get();
    }

    /** Red up close, through yellow, to green 60 blocks out. */
    private static int byDistance(double d) {
        float t = (float) Math.min(1, d / 60);
        return t < 0.5f ? Theme.mix(0xFFFF4D57, 0xFFF5C542, t * 2) : Theme.mix(0xFFF5C542, 0xFF5BE07A, (t - 0.5f) * 2);
    }
}
