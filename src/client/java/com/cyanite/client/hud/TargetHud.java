package com.cyanite.client.hud;

import com.cyanite.client.gui.Theme;
import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.modules.combat.PopCounter;
import com.cyanite.client.system.WorldScan;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;

/**
 * Who you're fighting: the player you last hit (for 15 seconds), else the one you're aiming at,
 * else the nearest. Their health and absorption, totem pops, distance, and their armor and what
 * they hold, each with how much durability it has left.
 */
public class TargetHud extends HudElement {
    private static final int PAD = 5;
    private static final int W = 170;
    private static final int BAR = 4;
    private static final int ITEM = 14;
    private static final int STEP = 26;
    private static final EquipmentSlot[] SLOTS = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND
    };

    private static Entity lastHit;
    private static long lastHitAt;

    private final BoolSetting playersOnly = add(new BoolSetting("Players only", true));
    private final SliderSetting range = add(new SliderSetting("Range", 8, 3, 32, 1).format(v -> (int) (double) v + " blocks"));

    private LivingEntity target;

    public TargetHud() {
        super("Target HUD", true, Horizontal.CENTER, Vertical.CENTER, 0, 40);
    }

    /** You hit something (from the attack hook). */
    public static void onAttack(Entity entity) {
        lastHit = entity;
        lastHitAt = System.currentTimeMillis();
    }

    @Override
    public void update(float dt, boolean editing) {
        target = pick();
        if (target == null && editing) target = Minecraft.getInstance().player;
        width = target == null ? 0 : W;
        height = target == null ? 0 : PAD * 2 + Text.CAP + 4 + BAR + 5 + Text.CAP + 6 + ITEM + 2 + Text.CAP;
    }

    private LivingEntity pick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return null;
        if (lastHit instanceof LivingEntity hit && hit.isAlive() && !hit.isRemoved() && hit.level() == mc.level
            && System.currentTimeMillis() - lastHitAt < 15_000 && hit.distanceTo(mc.player) < 32 && allowed(hit)) {
            return hit;
        }
        if (mc.crosshairPickEntity instanceof LivingEntity aimed && aimed != mc.player && aimed.isAlive() && allowed(aimed)) return aimed;
        LivingEntity nearest = null;
        double best = range.get() * range.get();
        for (Entity e : WorldScan.entities()) {
            if (!(e instanceof Player p) || p == mc.player || !p.isAlive() || p.isSpectator()) continue;
            double d = p.distanceToSqr(mc.player);
            if (d < best) {
                best = d;
                nearest = p;
            }
        }
        return nearest;
    }

    private boolean allowed(LivingEntity e) {
        return !playersOnly.get() || e instanceof Player;
    }

    @Override
    public void draw(GuiGraphics g, float alpha, boolean editing) {
        if (target == null) return;
        Minecraft mc = Minecraft.getInstance();
        int s = Render2D.scale();
        int accent = Theme.accent();
        int r = Math.round(Theme.radius() * s);

        // The same chip as the other panels.
        Render2D.pixels(g);
        Render2D.glow(g, x * s, y * s + s, width * s, height * s, r, 7 * s, Theme.fade(Theme.SHADOW, alpha * 0.8f));
        Render2D.glow(g, x * s, y * s, width * s, height * s, r, 7 * s, Theme.alpha(accent, 35 * Theme.glow() * alpha));
        Render2D.rect(g, x * s, y * s, width * s, height * s, r, Theme.fade(Theme.BORDER, alpha));
        Render2D.rect(g, x * s + 1, y * s + 1, width * s - 2, height * s - 2, Math.max(0, r - 1), Theme.fade(0xE8101217, alpha));
        Render2D.end(g);

        // Name and distance, then health, absorption and pops.
        int tx = x + PAD;
        int ty = y + PAD;
        String name = target instanceof Player p ? p.getGameProfile().name() : target.getName().getString();
        String dist = mc.player == target ? "you" : String.format(Locale.ROOT, "%.1fm", target.distanceTo(mc.player));
        Text.draw(g, name, tx, ty, Theme.fade(Theme.accentText(), alpha), false);
        Text.draw(g, dist, x + width - PAD - Text.width(dist), ty, Theme.fade(Theme.TEXT_FAINT, alpha), false);
        ty += Text.CAP + 4;

        float hp = target.getHealth();
        float max = Math.max(1, target.getMaxHealth());
        float absorb = target.getAbsorptionAmount();
        int barW = width - PAD * 2;
        Render2D.pixels(g);
        Render2D.rect(g, tx * s, ty * s, barW * s, BAR * s, BAR * s / 2, Theme.fade(0x40FFFFFF, alpha));
        int fill = Math.round(barW * Math.min(1, hp / max));
        Render2D.rect(g, tx * s, ty * s, fill * s, BAR * s, BAR * s / 2, Theme.fade(healthColor(hp / max), alpha));
        if (absorb > 0) {
            int gold = Math.round(barW * Math.min(1, absorb / max));
            Render2D.rect(g, tx * s, ty * s, gold * s, Math.max(1, BAR * s / 2), 0, Theme.fade(0xFFF5C542, alpha));
        }
        Render2D.end(g);
        ty += BAR + 5;

        // Health (and absorption) on the left, pops on the right.
        String hpText = (hp == Math.floor(hp) ? Integer.toString((int) hp) : String.format(Locale.ROOT, "%.1f", hp))
            + (absorb > 0 ? " +" + Math.round(absorb) : "") + " hp";
        Text.draw(g, hpText, tx, ty, Theme.fade(healthColor(hp / max), alpha), false);
        if (target instanceof Player p && PopCounter.pops(p) > 0) {
            int n = PopCounter.pops(p);
            String popText = n + (n == 1 ? " pop" : " pops");
            Text.draw(g, popText, x + width - PAD - Text.width(popText), ty, Theme.fade(0xFFF5C542, alpha), false);
        }
        ty += Text.CAP + 6;

        // Armor and what they hold, each with the durability it has left.
        int ix = tx + (STEP - ITEM) / 2 - 2;
        for (EquipmentSlot slot : SLOTS) {
            ItemStack stack = target.getItemBySlot(slot);
            if (!stack.isEmpty()) {
                g.pose().pushMatrix();
                g.pose().translate(ix, ty);
                g.pose().scale(ITEM / 16f);
                g.renderItem(stack, 0, 0);
                g.renderItemDecorations(mc.font, stack, 0, 0);
                g.pose().popMatrix();
                if (stack.isDamageableItem()) {
                    int pct = Math.round(100f * (stack.getMaxDamage() - stack.getDamageValue()) / stack.getMaxDamage());
                    String label = pct + "%";
                    Text.draw(g, label, ix + ITEM / 2 - Text.width(label) / 2, ty + ITEM + 2, Theme.fade(healthColor(pct / 100f), alpha), false);
                }
            }
            ix += STEP;
        }
    }

    /** Red through yellow to green. */
    static int healthColor(float fraction) {
        int red = 0xFFFF5A5F;
        int yellow = 0xFFF5C542;
        int green = 0xFF5BE07A;
        return fraction > 0.5f ? Theme.mix(yellow, green, (fraction - 0.5f) * 2) : Theme.mix(red, yellow, Math.max(0, fraction) * 2);
    }
}
