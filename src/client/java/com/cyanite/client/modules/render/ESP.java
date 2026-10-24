package com.cyanite.client.modules.render;

import com.cyanite.client.render.Render3D;
import com.cyanite.client.system.WorldScan;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

/** Boxes around entities, drawn through walls with the client's own world renderer. */
public class ESP extends Module {
    private final BoolSetting players = add(new BoolSetting("Players", true));
    private final ColorSetting playerColor = add(new ColorSetting("Player color", 0xFF5A5F));
    private final BoolSetting hostiles = add(new BoolSetting("Hostiles", true));
    private final ColorSetting hostileColor = add(new ColorSetting("Hostile color", 0xFFA43A));
    private final BoolSetting passives = add(new BoolSetting("Passives", false));
    private final ColorSetting passiveColor = add(new ColorSetting("Passive color", 0x5BE07A));
    private final BoolSetting items = add(new BoolSetting("Items", false));
    private final ColorSetting itemColor = add(new ColorSetting("Item color", 0xE6E6E6));
    private final BoolSetting invisibles = add(new BoolSetting("Invisibles", true));
    private final EspStyle style = new EspStyle(false);

    public ESP() {
        super(Category.RENDER, "ESP", "Shows entities through walls");
        style.settings().forEach(this::add);
    }

    @Override
    public String getInfo() {
        return "Box";
    }

    @Override
    public void onRender3D(Render3D render) {
        float pt = render.partialTick();

        for (Entity entity : WorldScan.entities()) {
            Integer rgb = colorFor(entity);
            if (rgb == null) continue;

            // Boxes follow the entity's interpolated position so they don't lag behind at high frame rates.
            AABB box = entity.getBoundingBox().move(entity.getPosition(pt).subtract(entity.position()));
            style.draw(render, box, rgb);
        }
    }

    private Integer colorFor(Entity entity) {
        if (entity == mc.player && !mc.gameRenderer.getMainCamera().isDetached()) return null;
        if (entity.isInvisible() && !invisibles.get()) return null;
        if (!entity.isAlive()) return null;

        if (entity instanceof Player) return players.get() ? playerColor.get() : null;
        if (entity instanceof Enemy) return hostiles.get() ? hostileColor.get() : null;
        if (entity instanceof ItemEntity) return items.get() ? itemColor.get() : null;
        if (entity instanceof LivingEntity && !(entity instanceof ArmorStand)) return passives.get() ? passiveColor.get() : null;
        return null;
    }
}
