package com.cyanite.client.modules.render;

import com.cyanite.client.gui.Theme;
import com.cyanite.client.system.WorldScan;
import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.hud.Hud;
import com.cyanite.client.render.Projection;
import com.cyanite.client.system.Category;
import com.cyanite.client.modules.combat.PopCounter;
import com.cyanite.client.modules.donut.BountyBalance;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Replaces vanilla name tags with readable ones that show through walls. Drawn in HUD units. */
public class Nametags extends Module {
    private static final EquipmentSlot[] ITEM_ORDER = {
        EquipmentSlot.MAINHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST,
        EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND
    };
    private static final int HEIGHT = 13;
    private static final int PAD = 4;
    private static final int GAP = 4;
    private static final float ITEM_SCALE = 0.75f;

    private final BoolSetting players = add(new BoolSetting("Players", true));
    private final BoolSetting hostiles = add(new BoolSetting("Hostiles", false));
    private final BoolSetting passives = add(new BoolSetting("Passives", false));
    private final BoolSetting health = add(new BoolSetting("Health", true));
    private final BoolSetting distance = add(new BoolSetting("Distance", true));
    private final BoolSetting ping = add(new BoolSetting("Ping", false));
    private final BoolSetting pops = add(new BoolSetting("Pops", true));
    private final BoolSetting items = add(new BoolSetting("Items", true));
    private final BoolSetting durability = add(new BoolSetting("Durability", true));
    private final BoolSetting background = add(new BoolSetting("Background", true));
    private final SliderSetting scale = add(new SliderSetting("Scale", 1, 0.5, 2, 0.05).format(v -> String.format(Locale.ROOT, "%.2fx", v)));

    /** One tag's words and colours, and how wide they are: worked out once a tick, drawn every frame. */
    private record Tag(List<String> parts, List<Integer> colors, int textWidth) {
    }

    private final java.util.Map<Integer, Tag> tags = new java.util.HashMap<>();
    private long tagsAt = Long.MIN_VALUE;

    public Nametags() {
        super(Category.RENDER, "Nametags", "Clearer name tags with health, distance and gear");
    }

    /** Whether this entity gets one of our tags (and so loses the vanilla one). */
    public boolean tags(Entity entity) {
        if (!isEnabled() || entity == mc.player || !(entity instanceof LivingEntity) || entity instanceof ArmorStand) return false;
        if (entity instanceof Player player) return players.get() && !player.isSpectator();
        if (entity instanceof Enemy) return hostiles.get();
        return passives.get();
    }

    @Override
    public void onRender2D(GuiGraphics g, float partialTick) {
        List<LivingEntity> targets = new ArrayList<>();
        for (Entity entity : WorldScan.entities()) {
            if (tags(entity)) targets.add((LivingEntity) entity);
        }
        // Far ones first, so nearer tags sit on top.
        targets.sort(Comparator.comparingDouble((LivingEntity e) -> e.distanceToSqr(mc.player)).reversed());

        int unit = Render2D.scale();
        for (LivingEntity entity : targets) {
            Vec3 head = entity.getPosition(partialTick).add(0, entity.getBbHeight() + 0.45, 0);
            double[] screen = Projection.toScreen(head);
            if (screen == null) continue;

            g.pose().pushMatrix();
            g.pose().translate(Math.round(screen[0]) / (float) unit, Math.round(screen[1]) / (float) unit);
            g.pose().scale(scale.get().floatValue());
            drawTag(g, entity);
            g.pose().popMatrix();
        }
    }

    private void drawTag(GuiGraphics g, LivingEntity entity) {
        long tick = mc.level.getGameTime();
        if (tick != tagsAt) {
            tags.clear();
            tagsAt = tick;
        }
        Tag tag = tags.computeIfAbsent(entity.getId(), id -> tagFor(entity));
        List<String> parts = tag.parts();
        List<Integer> colors = tag.colors();
        int textWidth = tag.textWidth();

        int w = textWidth + PAD * 2;
        int x = -w / 2;
        int y = -HEIGHT;
        int s = Render2D.scale();

        if (background.get()) {
            int r = Math.round(Math.min(Theme.radius(), 3.5f) * s);
            Render2D.pixels(g);
            Render2D.glow(g, x * s, y * s, w * s, HEIGHT * s, r, 6 * s, Theme.alpha(Theme.accent(), 35 * Theme.glow()));
            Render2D.rect(g, x * s, y * s, w * s, HEIGHT * s, r, Theme.BORDER);
            Render2D.rect(g, x * s + 1, y * s + 1, w * s - 2, HEIGHT * s - 2, Math.max(0, r - 1), 0xE6101217);
            Render2D.end(g);
        }

        boolean shadow = !background.get() && Hud.SHADOW.get();
        int cursor = x + PAD;
        for (int i = 0; i < parts.size(); i++) {
            Text.draw(g, parts.get(i), cursor, y + (HEIGHT - Text.CAP) / 2, colors.get(i), shadow);
            cursor += Text.width(parts.get(i)) + GAP;
        }

        if (items.get()) drawItems(g, entity, y - 2);
    }

    private Tag tagFor(LivingEntity entity) {
        List<String> parts = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();

        parts.add(entity.getName().getString());
        colors.add(entity instanceof Player ? Theme.TEXT : Theme.TEXT_DIM);
        BountyBalance worth = Modules.get(BountyBalance.class);
        if (worth != null && worth.isEnabled() && entity instanceof Player player) worth.addTo(player.getGameProfile().name(), parts, colors);

        if (health.get()) {
            float hp = entity.getHealth() + entity.getAbsorptionAmount();
            parts.add(hp == Math.floor(hp) ? Integer.toString((int) hp) : String.format(Locale.ROOT, "%.1f", hp));
            colors.add(healthColor(entity.getHealth() / Math.max(1, entity.getMaxHealth())));
        }
        if (distance.get()) {
            parts.add(Math.round(entity.distanceTo(mc.player)) + "m");
            colors.add(Theme.TEXT_DIM);
        }
        if (ping.get() && entity instanceof Player player && mc.getConnection() != null) {
            PlayerInfo info = mc.getConnection().getPlayerInfo(player.getUUID());
            if (info != null) {
                parts.add(info.getLatency() + "ms");
                colors.add(Theme.TEXT_FAINT);
            }
        }
        if (pops.get() && entity instanceof Player player && PopCounter.pops(player) > 0) {
            int n = PopCounter.pops(player);
            parts.add(n + (n == 1 ? " pop" : " pops"));
            colors.add(0xFFF5C542);
        }

        int textWidth = 0;
        for (String part : parts) textWidth += Text.width(part);
        textWidth += GAP * (parts.size() - 1);
        return new Tag(parts, colors, textWidth);
    }

    private void drawItems(GuiGraphics g, LivingEntity entity, int bottom) {
        List<ItemStack> stacks = new ArrayList<>();
        for (EquipmentSlot slot : ITEM_ORDER) {
            ItemStack stack = entity.getItemBySlot(slot);
            if (!stack.isEmpty()) stacks.add(stack);
        }
        if (stacks.isEmpty()) return;

        int size = 16;
        // Spread out when each piece has its durability over it, so the numbers don't run together.
        int step = durability.get() ? 26 : 17;
        float width = stacks.size() * step * ITEM_SCALE;

        g.pose().pushMatrix();
        g.pose().translate(-width / 2, bottom - size * ITEM_SCALE);
        g.pose().scale(ITEM_SCALE);
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack stack = stacks.get(i);
            g.renderItem(stack, i * step, 0);
            g.renderItemDecorations(mc.font, stack, i * step, 0);
            // How much is left of each piece, over it: what a crystal fight turns on.
            if (durability.get() && stack.isDamageableItem()) {
                int pct = Math.round(100f * (stack.getMaxDamage() - stack.getDamageValue()) / stack.getMaxDamage());
                String label = Integer.toString(pct);
                Text.draw(g, label, i * step + size / 2 - Text.width(label) / 2, -Text.CAP - 2, healthColor(pct / 100f), true);
            }
        }
        g.pose().popMatrix();
    }

    private static int healthColor(float fraction) {
        int red = 0xFFFF5A5F;
        int yellow = 0xFFF5C542;
        int green = 0xFF5BE07A;
        return fraction > 0.5f
            ? Theme.mix(yellow, green, (fraction - 0.5f) * 2)
            : Theme.mix(red, yellow, fraction * 2);
    }
}
