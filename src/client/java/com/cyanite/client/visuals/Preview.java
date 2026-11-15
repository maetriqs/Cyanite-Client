package com.cyanite.client.visuals;

import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.Setting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * The Visuals tab's live preview: a small picture of what a group does, drawn from its settings as they are
 * right now, so a slider shows its effect while it moves. Items go through the same hooks as in the world
 * (Item Colors, Glint, durability bars), so those show exactly what the game will draw.
 * Everything is in the caller's units; {@link #real()} converts from the game's own GUI units.
 */
public final class Preview {
    private static final int TEXT = 0xFFE9ECF1;
    private static final int DIM = 0xFF8A919D;
    private static final int FAINT = 0xFF4E5461;
    private static final int SKY_TOP = 0xFF5F86B8;
    private static final int SKY_BOTTOM = 0xFF9CB7D6;
    private static final int GROUND = 0xFF4A6E36;

    private static final ItemStack TOTEM = new ItemStack(Items.TOTEM_OF_UNDYING);
    private static final ItemStack SWORD = glinting(Items.NETHERITE_SWORD);
    private static final ItemStack SWORD_PLAIN = new ItemStack(Items.DIAMOND_SWORD);
    private static final ItemStack CHEST = glinting(Items.DIAMOND_CHESTPLATE);
    private static final List<ItemStack> ARMOR = List.of(worn(Items.DIAMOND_HELMET, 0.92f), worn(Items.NETHERITE_CHESTPLATE, 0.6f),
        worn(Items.DIAMOND_LEGGINGS, 0.33f), worn(Items.DIAMOND_BOOTS, 0.12f));

    private Preview() {
    }

    private static ItemStack glinting(Item item) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return stack;
    }

    private static ItemStack worn(Item item, float left) {
        ItemStack stack = new ItemStack(item);
        stack.setDamageValue(Math.round(stack.getMaxDamage() * (1 - left)));
        return stack;
    }

    /** What the button under the preview says, or null for none. */
    public static String action(Group group) {
        if (group == TotemPop.GROUP) return Minecraft.getInstance().level != null ? "Play a pop" : null;
        return null;
    }

    public static void act(Group group) {
        Minecraft mc = Minecraft.getInstance();
        if (group == TotemPop.GROUP && mc.level != null) mc.gameRenderer.displayItemActivation(new ItemStack(Items.TOTEM_OF_UNDYING));
    }

    /** One of the game's GUI units, in the caller's: things drawn at real size. */
    private static float real() {
        float f = Minecraft.getInstance().getWindow().getGuiScale() / (float) Render2D.scale();
        return Math.max(0.6f, Math.min(2.5f, f));
    }

    private static float seconds() {
        return (System.nanoTime() % 3_600_000_000_000L) / 1e9f;
    }

    /** Draws the preview of {@code group} inside (x, y, w, h); the caller clips to it and draws the frame. */
    public static void draw(GuiGraphics g, Group group, int x, int y, int w, int h, float alpha) {
        if (group == Crosshair.GROUP) crosshair(g, x, y, w, h, alpha);
        else if (group == TotemPop.GROUP) totem(g, x, y, w, h, alpha);
        else if (group == ItemColors.GROUP) itemColors(g, x, y, w, h, alpha);
        else if (group == Glint.GROUP) glint(g, x, y, w, h, alpha);
        else if (group == ArmorDurability.GROUP) armor(g, x, y, w, h, alpha);
        else if (group == DamageTint.GROUP) damageTint(g, x, y, w, h);
        else if (group == PotionHud.GROUP) potions(g, x, y, w, h);
        else if (group == HealthBars.GROUP) healthBars(g, x, y, w, h);
        else if (group == HitColor.GROUP) hitColor(g, x, y, w, h, alpha);
        else if (group == LowFire.GROUP) lowFire(g, x, y, w, h, alpha);
        else if (group == DamageNumbers.GROUP) damageNumbers(g, x, y, w, h, alpha);
        else if (group == BlockHighlight.GROUP) blockHighlight(g, x, y, w, h, alpha);
        else if (group == SwordTrail.GROUP) swordTrail(g, x, y, w, h, alpha);
        else if (group == Trails.PROJECTILES) trails(g, x, y, w, h, alpha, Trails.PEARL.get(), Trails.OTHER.get(), 0xF02BFF,
            Trails.WIDTH.get().floatValue(), Trails.LENGTH.get().floatValue(), "Pearl", "Snowball", Trails.POTION_OWN.get() ? "Potion" : null);
        else if (group == Trails.ARROWS) trails(g, x, y, w, h, alpha, Trails.ARROW.get(), Trails.CRIT.get(), Trails.TRIDENT.get(),
            Trails.ARROW_WIDTH.get().floatValue(), Trails.ARROW_LENGTH.get().floatValue(), "Arrow", "Crit", "Trident");
        else if (group == TargetHighlight.GROUP) target(g, x, y, w, h, alpha);
        else if (group == CustomHud.GROUP) hud(g, x, y, w, h, alpha);
        else if (group == Weather.GROUP) weather(g, x, y, w, h, alpha);
        else settings(g, group, x, y, w, h, alpha);
    }

    // ---- Pieces

    private static void sky(GuiGraphics g, int x, int y, int w, int h, float alpha) {
        int horizon = y + h * 3 / 5;
        g.fillGradient(x, y, x + w, horizon, Visuals.argb(SKY_TOP, alpha), Visuals.argb(SKY_BOTTOM, alpha));
        g.fill(x, horizon, x + w, y + h, Visuals.argb(GROUND, alpha));
    }

    private static int fade(int argb, float alpha) {
        return ((Math.round((argb >>> 24) * alpha)) << 24) | (argb & 0xFFFFFF);
    }

    private static void label(GuiGraphics g, String text, int x, int y, float alpha) {
        Text.draw(g, text, x, y, fade(DIM, alpha));
    }

    private static void item(GuiGraphics g, ItemStack stack, float x, float y, float size) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(size / 16f);
        g.renderItem(stack, 0, 0);
        g.pose().popMatrix();
    }

    /** A blocky person standing at (cx, feet), {@code u} units to a model pixel, its skin {@code tint} mixed in by {@code t}. */
    private static void person(GuiGraphics g, float cx, float feet, float u, int tint, float t, float alpha) {
        int skin = fade(Visuals.lerp(0xFF4F8A3A, 0xFF000000 | tint, t), alpha);
        int shirt = fade(Visuals.lerp(0xFF2E8C96, 0xFF000000 | tint, t), alpha);
        int legs = fade(Visuals.lerp(0xFF3D3F9A, 0xFF000000 | tint, t), alpha);
        Shapes.of(g, legs).rect(cx - 4 * u, feet - 12 * u, cx + 4 * u, feet).submit();
        Shapes.of(g, shirt).rect(cx - 4 * u, feet - 24 * u, cx + 4 * u, feet - 12 * u).submit();
        Shapes.of(g, skin)
            .rect(cx - 8 * u, feet - 24 * u, cx - 4 * u, feet - 12 * u)
            .rect(cx + 4 * u, feet - 24 * u, cx + 8 * u, feet - 12 * u)
            .rect(cx - 4 * u, feet - 32 * u, cx + 4 * u, feet - 24 * u)
            .submit();
    }

    // ---- One per group

    private static void crosshair(GuiGraphics g, int x, int y, int w, int h, float alpha) {
        sky(g, x, y, w, h, alpha);
        int s = Render2D.scale();
        float k = Minecraft.getInstance().getWindow().getHeight() / 1080f;
        g.pose().pushMatrix();
        g.pose().scale(1f / s);
        Crosshair.draw(g, (x + w / 2f) * s, (y + h / 2f) * s, k, Visuals.argb(Crosshair.COLOR.get(), Crosshair.OPACITY.get() * alpha), 0);
        g.pose().popMatrix();
        label(g, Crosshair.SHAPE.get() + ", actual size", x + 4, y + h - Text.CAP - 4, alpha);
    }

    private static void totem(GuiGraphics g, int x, int y, int w, int h, float alpha) {
        // The pop on a loop, as the chosen animation moves it; the held one at its size beside it.
        float length = TotemPop.LENGTH.get().floatValue();
        float t = (seconds() % (length + 0.6f)) / length;
        float size = 30 * TotemPop.SIZE.get().floatValue();
        float cx = x + w * 0.38f;
        float cy = y + h * 0.5f;
        if (t < 1 && !TotemPop.STYLE.is("Off")) {
            float grow = Math.min(1, t * 5);
            float spin = 0;
            float dy = 0;
            float dx = 0;
            switch (TotemPop.STYLE.get()) {
                case "Pop" -> grow = t < 0.15f ? t / 0.15f * 1.3f : 1.3f - 0.3f * Math.min(1, (t - 0.15f) * 4);
                case "Spin" -> spin = t * (float) Math.PI * 4 * TotemPop.SPIN.get().floatValue();
                case "Slide" -> dx = (1 - Math.min(1, t * 3)) * -w * 0.4f;
                case "Float" -> dy = -t * h * 0.25f;
                default -> spin = (float) Math.sin(t * Math.PI * 2) * 0.35f * TotemPop.SPIN.get().floatValue();
            }
            float shrink = t > 0.8f ? 1 - (t - 0.8f) / 0.2f : 1;
            float sz = size * grow * shrink;
            g.pose().pushMatrix();
            g.pose().translate(cx + dx, cy + dy);
            g.pose().rotate(spin);
            item(g, TOTEM, -sz / 2, -sz / 2, sz);
            g.pose().popMatrix();
        }
        float held = 16 * TotemPop.HELD.get().floatValue();
        item(g, TOTEM, x + w * 0.8f - held / 2, y + h * 0.62f - held / 2, held);
        label(g, "Pop", x + 4, y + 4, alpha);
        label(g, "Held", Math.round(x + w * 0.8f - Text.width("Held") / 2f), y + 4, alpha);
    }

    private static void itemColors(GuiGraphics g, int x, int y, int w, int h, float alpha) {
        ColorSetting[] colors = {ItemColors.COLOR_1, ItemColors.COLOR_2, ItemColors.COLOR_3};
        List<List<Item>> lists = List.of(ItemColors.ITEMS_1.get(), ItemColors.ITEMS_2.get(), ItemColors.ITEMS_3.get());
        int row = Math.min(24, (h - 8) / 3);
        for (int i = 0; i < 3; i++) {
            int ry = y + 4 + i * row;
            int rgb = colors[i].get();
            g.fill(x + 6, ry + 3, x + 16, ry + 13, Visuals.argb(rgb, alpha));
            List<Item> items = lists.get(i);
            if (items.isEmpty()) {
                Text.draw(g, "No items", x + 24, ry + 5, fade(FAINT, alpha));
                continue;
            }
            int ix = x + 24;
            for (Item item : items) {
                if (ix + 16 > x + w - 4) break;
                item(g, new ItemStack(item), ix, ry, 16);
                ix += 20;
            }
        }
    }

    private static void glint(GuiGraphics g, int x, int y, int w, int h, float alpha) {
        float size = Math.min(40, h - 20);
        item(g, SWORD, x + w * 0.3f - size / 2, y + (h - size) / 2f - 4, size);
        item(g, CHEST, x + w * 0.7f - size / 2, y + (h - size) / 2f - 4, size);
        int c = Glint.previewColor();
        g.fill(x + 4, y + h - 10, x + 12, y + h - 4, c | 0xFF000000);
        label(g, "Glint color", x + 15, y + h - 11, alpha);
    }

    private static void armor(GuiGraphics g, int x, int y, int w, int h, float alpha) {
        int rowH = 17;
        float f = Math.min(real(), (h - 8) / (float) (ARMOR.size() * rowH));
        Minecraft mc = Minecraft.getInstance();
        g.pose().pushMatrix();
        g.pose().translate(x + 6, y + 4);
        g.pose().scale(f);
        ArmorDurability.draw(g, ARMOR, 0, 0, rowH);
        int ry = 0;
        for (ItemStack stack : ARMOR) {
            g.renderItemDecorations(mc.font, stack, 0, ry);
            ry += rowH;
        }
        g.pose().popMatrix();
    }

    private static void damageTint(GuiGraphics g, int x, int y, int w, int h) {
        sky(g, x, y, w, h, 1);
        float seconds = 0.5f * DamageTint.LENGTH.get().floatValue();
        float t = (seconds() % (seconds + 0.7f)) / seconds;
        float strength = t < 1 ? (1 - t) * (1 - t) : 0;
        if (strength > 0.003f) DamageTint.draw(g, x, y, w, h, strength * DamageTint.INTENSITY.get().floatValue());
    }

    private static void potions(GuiGraphics g, int x, int y, int w, int h) {
        List<MobEffectInstance> effects = new ArrayList<>();
        effects.add(new MobEffectInstance(MobEffects.STRENGTH, 1800, 1));
        effects.add(new MobEffectInstance(MobEffects.SPEED, 900, 1));
        effects.add(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 6000, 0));
        effects.add(new MobEffectInstance(MobEffects.POISON, 300, 0));
        // Four rows fit the box at a Scale of 1; the slider grows or shrinks them from there.
        float f = Math.min(real(), (h - 4) / 106f) * PotionHud.SCALE.get().floatValue();
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(f);
        PotionHud.draw(g, effects, Math.round(w / f), Math.round(h / f));
        g.pose().popMatrix();
    }

    private static void healthBars(GuiGraphics g, int x, int y, int w, int h) {
        float k = real() * HealthBars.SIZE.get().floatValue();
        float cx = x + w / 2f - 8 * k;
        float step = Math.min(h / 3.2f, 16 * k);
        float top = y + h / 2f - step;
        HealthBars.draw(g, cx, top, k, 20, 20, 0);
        HealthBars.draw(g, cx, top + step, k, 13, 20, 6);
        HealthBars.draw(g, cx, top + step * 2, k, 4, 20, 0);
    }

    private static void hitColor(GuiGraphics g, int x, int y, int w, int h, float alpha) {
        boolean hurt = seconds() % 1.2f < 0.5f;
        float u = Math.min(2, (h - 16) / 34f);
        float feet = y + h - 12;
        person(g, x + w * 0.28f, feet, u, 0xFF0000, hurt ? 0.3f : 0, alpha);
        person(g, x + w * 0.72f, feet, u, HitColor.COLOR.get(), hurt ? HitColor.INTENSITY.get().floatValue() : 0, alpha);
        label(g, "Vanilla", Math.round(x + w * 0.28f - Text.width("Vanilla") / 2f), y + h - 9, alpha);
        label(g, "Yours", Math.round(x + w * 0.72f - Text.width("Yours") / 2f), y + h - 9, alpha);
        if (HitColor.FLASH.get() && hurt && seconds() % 1.2f < 0.15f) {
            g.fill(x, y, x + w, y + h, Visuals.argb(HitColor.FLASH_COLOR.get(), HitColor.FLASH_STRENGTH.get() * alpha));
        }
    }

    private static void lowFire(GuiGraphics g, int x, int y, int w, int h, float alpha) {
        sky(g, x, y, w, h, alpha);
        // The game's flames reach 60% up the screen; each step of Height lowers them from there.
        float vanilla = 0.6f;
        float cover = vanilla - (float) (1 - LowFire.HEIGHT.get()) * 0.25f;
        float opacity = LowFire.OPACITY.get().floatValue();
        int dashY = Math.round(y + h * (1 - vanilla));
        for (int dx = x; dx < x + w; dx += 6) g.fill(dx, dashY, Math.min(dx + 3, x + w), dashY + 1, fade(0xA0FFFFFF, alpha));
        float t = seconds();
        int tongues = 9;
        int bands = 8;
        float tw = w / (float) tongues;
        // Tapered tongues, orange at the bottom to pale yellow at the tips, with a brighter core; one batch per band and layer.
        for (int layer = 0; layer < 2; layer++) {
            boolean core = layer == 1;
            for (int band = 0; band < bands; band++) {
                float k = band / (float) bands;
                int color = core ? Visuals.lerp(0xFFFFB030, 0xFFFFF6C0, k) : Visuals.lerp(0xFFFF4A0A, 0xFFFFC040, k);
                Shapes slice = Shapes.of(g, fade(color, opacity * alpha * (core ? 0.9f - 0.5f * k : 0.95f - 0.45f * k)));
                for (int i = 0; i < tongues; i++) {
                    float flick = 0.75f + 0.25f * (float) Math.sin(t * 7 + i * 1.7f);
                    float height = h * cover * flick * (core ? 0.6f : 1);
                    float cxT = x + (i + 0.5f) * tw + (float) Math.sin(t * 5 + i) * tw * 0.08f * k;
                    float half = tw * (core ? 0.3f : 0.62f) * (1 - k * 0.8f);
                    float y0 = y + h - height * (band + 1) / bands;
                    float y1 = y + h - height * band / bands;
                    slice.rect(Math.max(x, cxT - half), y0, Math.min(x + w, cxT + half), y1);
                }
                slice.submit();
            }
        }
        label(g, "Game's height", x + 4, dashY - Text.CAP - 3, alpha);
    }

    private static void damageNumbers(GuiGraphics g, int x, int y, int w, int h, float alpha) {
        Minecraft mc = Minecraft.getInstance();
        boolean hearts = DamageNumbers.FORMAT.is("Hearts");
        String[] text = hearts ? new String[]{"-2.5❤", "-4❤", "+1❤"} : new String[]{"-5", "-8", "+2"};
        int[] color = {DamageNumbers.COLOR.get(), DamageNumbers.CRIT_COLOR.get(), DamageNumbers.HEAL_COLOR.get()};
        int count = DamageNumbers.HEALING.get() ? 3 : 2;
        float length = DamageNumbers.LENGTH.get().floatValue();
        float k = real() * DamageNumbers.SCALE.get().floatValue();
        for (int i = 0; i < count; i++) {
            float t = ((seconds() + i * 0.37f) % (length + 0.3f)) / length;
            if (t >= 1) continue;
            float px = x + w * (0.25f + 0.25f * i);
            float py = y + h * 0.7f - t * h * 0.4f;
            float a = t > 0.7f ? (1 - t) / 0.3f : 1;
            g.pose().pushMatrix();
            g.pose().translate(px, py);
            g.pose().scale(k);
            g.drawString(mc.font, text[i], -mc.font.width(text[i]) / 2, 0, Visuals.argb(color[i], a * alpha), true);
            g.pose().popMatrix();
        }
    }

    private static void blockHighlight(GuiGraphics g, int x, int y, int w, int h, float alpha) {
        int size = Math.min(h - 16, 56);
        int bx = x + (w - size) / 2;
        int by = y + (h - size) / 2;
        // A stone face: a few shades so the outline has something to stand out from.
        g.fill(bx, by, bx + size, by + size, fade(0xFF7C7C7C, alpha));
        int cell = size / 8;
        for (int i = 0; i < 64; i++) {
            if ((i * 37 + i / 8 * 11) % 5 != 0) continue;
            int cx = bx + (i % 8) * cell;
            int cy = by + (i / 8) * cell;
            g.fill(cx, cy, cx + cell, cy + cell, fade(0xFF686868, alpha));
        }
        int rgb = BlockHighlight.COLOR.get();
        if (BlockHighlight.FILL.get()) g.fill(bx, by, bx + size, by + size, Visuals.argb(rgb, BlockHighlight.FILL_OPACITY.get() * alpha));
        float t = Math.max(0.6f, BlockHighlight.WIDTH.get().floatValue() * 0.8f);
        int line = Visuals.argb(rgb, BlockHighlight.OPACITY.get() * alpha);
        Shapes.of(g, line)
            .rect(bx - t / 2, by - t / 2, bx + size + t / 2, by + t / 2)
            .rect(bx - t / 2, by + size - t / 2, bx + size + t / 2, by + size + t / 2)
            .rect(bx - t / 2, by + t / 2, bx + t / 2, by + size - t / 2)
            .rect(bx + size - t / 2, by + t / 2, bx + size + t / 2, by + size - t / 2)
            .submit();
    }

    private static void swordTrail(GuiGraphics g, int x, int y, int w, int h, float alpha) {
        // A swing across from right to left that stays a moment and fades, the trail thinning and turning to its end colour.
        float t = seconds() % 1.3f;
        float head = Math.min(1, t / 0.35f);
        float fadeOut = t < 0.75f ? 1 : Math.max(0, 1 - (t - 0.75f) / 0.45f);
        float span = Math.min(1, SwordTrail.LENGTH.get().floatValue() / 0.35f);
        float width = Math.max(1.5f, SwordTrail.WIDTH.get().floatValue() * 0.9f);
        float cx = x + w / 2f;
        float cy = y + h * 0.95f;
        float r = Math.min(w * 0.42f, h * 0.8f);
        int steps = 64;
        int bands = 12;
        int from = 0xFF000000 | SwordTrail.COLOR.get();
        int to = 0xFF000000 | SwordTrail.END_COLOR.get();
        // One batch per colour band, so the whole arc is a couple of dozen pieces for the menu, not a hundred.
        for (int band = bands - 1; band >= 0; band--) {
            float mid = (band + 0.5f) / bands;
            int c = Visuals.lerp(from, to, mid);
            Shapes glow = SwordTrail.GLOW.get() ? Shapes.of(g, fade(c, alpha * fadeOut * 0.12f * (1 - mid))) : null;
            Shapes core = Shapes.of(g, fade(c, alpha * fadeOut * (1 - mid * 0.6f)));
            for (int i = band * steps / bands; i < (band + 1) * steps / bands; i++) {
                float back = i / (float) steps;
                float at = head - back * span;
                if (at < 0) continue;
                double angle = Math.PI * (0.95 - 0.8 * at);
                float px = cx - (float) Math.cos(angle) * r;
                float py = cy - (float) Math.sin(angle) * r;
                float sz = Math.max(0.8f, width * (1 - back * 0.85f));
                if (glow != null) glow.rect(px - sz * 1.1f, py - sz * 1.1f, px + sz * 1.1f, py + sz * 1.1f);
                core.rect(px - sz / 2, py - sz / 2, px + sz / 2, py + sz / 2);
            }
            if (glow != null) glow.submit();
            core.submit();
        }
        item(g, SWORD_PLAIN, cx - (float) Math.cos(Math.PI * (0.95 - 0.8 * head)) * r - 8,
            cy - (float) Math.sin(Math.PI * (0.95 - 0.8 * head)) * r - 8, 16);
    }

    private static void trails(GuiGraphics g, int x, int y, int w, int h, float alpha, int a, int b, int c, float width, float length,
                               String na, String nb, String nc) {
        int[] colors = {a, b, c};
        String[] names = {na, nb, nc};
        float t = seconds() % 2.2f / 1.6f;
        for (int i = 0; i < 3; i++) {
            if (names[i] == null) continue;
            float lane = y + 12 + i * (h - 16) / 3f;
            float head = Math.min(1, t);
            float tail = Math.max(0, head - length / 3);
            float fadeOut = t > 1 ? Math.max(0, 1 - (t - 1) * 1.7f) : 1;
            int steps = 30;
            Shapes line = Shapes.of(g, Visuals.argb(colors[i], alpha * fadeOut));
            for (int s = 0; s < steps; s++) {
                float p = tail + (head - tail) * s / steps;
                float px = x + 6 + p * (w - 12);
                // A shallow arc, the way a throw drops.
                float py = lane + (float) (Math.pow(p - 0.35, 2) * 18) - 4;
                float sz = Math.max(0.6f, width * 0.5f * (0.3f + 0.7f * s / steps));
                line.rect(px - sz / 2, py - sz / 2, px + sz / 2, py + sz / 2);
            }
            line.submit();
            label(g, names[i], x + 4, Math.round(lane) - Text.CAP - 2, alpha);
        }
    }

    private static void target(GuiGraphics g, int x, int y, int w, int h, float alpha) {
        sky(g, x, y, w, h, alpha);
        float u = Math.min(2, (h - 16) / 36f);
        float cx = x + w / 2f;
        float feet = y + h - 8;
        person(g, cx, feet, u, 0, 0, alpha);
        float pulse = Math.max(0, 1 - seconds() % 1.6f / 0.6f);
        int color = Visuals.lerp(0xFF000000 | TargetHighlight.COLOR.get(), 0xFF000000 | TargetHighlight.HIT_COLOR.get(), pulse);
        int line = fade(color, alpha);
        float l = cx - 10 * u;
        float r = cx + 10 * u;
        float top = feet - 34 * u;
        float th = 1;
        switch (TargetHighlight.STYLE.get()) {
            case "Box" -> {
                g.fill(Math.round(l), Math.round(top), Math.round(r), Math.round(feet), Visuals.argb(color & 0xFFFFFF, 0.25 * alpha));
                outline(g, line, l, top, r, feet, th);
            }
            case "Corners" -> {
                float c = 5 * u;
                Shapes.of(g, line)
                    .rect(l, top, l + c, top + th).rect(l, top, l + th, top + c)
                    .rect(r - c, top, r, top + th).rect(r - th, top, r, top + c)
                    .rect(l, feet - th, l + c, feet).rect(l, feet - c, l + th, feet)
                    .rect(r - c, feet - th, r, feet).rect(r - th, feet - c, r, feet)
                    .submit();
            }
            case "Ring" -> {
                Shapes ring = Shapes.of(g, line);
                float rx = 11 * u;
                float ry = 3 * u;
                for (int i = 0; i < 40; i++) {
                    double a = Math.PI * 2 * i / 40;
                    float px = cx + (float) Math.cos(a) * rx;
                    float py = feet + (float) Math.sin(a) * ry - 1;
                    ring.rect(px - 0.6f, py - 0.6f, px + 0.6f, py + 0.6f);
                }
                ring.submit();
            }
            default -> outline(g, line, cx - 9 * u, top + 2 * u, cx + 9 * u, feet, th);
        }
    }

    private static void outline(GuiGraphics g, int color, float l, float t, float r, float b, float th) {
        Shapes.of(g, color).rect(l, t, r, t + th).rect(l, b - th, r, b).rect(l, t, l + th, b).rect(r - th, t, r, b).submit();
    }

    private static void hud(GuiGraphics g, int x, int y, int w, int h, float alpha) {
        // The screen in miniature: where the hotbar, boss bar and scoreboard end up.
        Minecraft mc = Minecraft.getInstance();
        int gw = mc.getWindow().getGuiScaledWidth();
        int gh = mc.getWindow().getGuiScaledHeight();
        float m = Math.min(w / (float) gw, h / (float) gh);
        float ox = x + (w - gw * m) / 2;
        float oy = y + (h - gh * m) / 2;
        sky(g, Math.round(ox), Math.round(oy), Math.round(gw * m), Math.round(gh * m), alpha);
        int box = fade(0xC0101217, alpha);
        float hs = CustomHud.HOTBAR_SCALE.get().floatValue();
        float hx = gw / 2f + CustomHud.HOTBAR_X.get().floatValue();
        float hy = gh + CustomHud.HOTBAR_Y.get().floatValue();
        Shapes.of(g, box).rect(ox + (hx - 91 * hs) * m, oy + (hy - 22 * hs) * m, ox + (hx + 91 * hs) * m, oy + hy * m).submit();
        Shapes.of(g, fade(0xFFE9ECF1, alpha)).rect(ox + (hx - 91 * hs) * m, oy + (hy - 22 * hs) * m, ox + (hx - 69 * hs) * m, oy + hy * m).submit();
        float bs = CustomHud.BOSS_SCALE.get().floatValue();
        float by = 12 + CustomHud.BOSS_Y.get().floatValue();
        Shapes.of(g, fade(0xFFE04DFF, alpha)).rect(ox + (gw / 2f - 91 * bs) * m, oy + by * m, ox + (gw / 2f + 91 * bs) * m, oy + (by + 5 * bs) * m).submit();
        float ss = CustomHud.SCORE_SCALE.get().floatValue();
        float sy = gh / 2f + CustomHud.SCORE_Y.get().floatValue();
        Shapes.of(g, box).rect(ox + (gw - 70 * ss) * m, oy + (sy - 40 * ss) * m, ox + gw * m, oy + (sy + 40 * ss) * m).submit();
        int xp = CustomHud.XP_RECOLOR.get() ? CustomHud.XP_COLOR.get() : 0x80FF20;
        float xs = Math.max(1, 3 * m * hs);
        Shapes.of(g, fade(0xFF000000 | xp, alpha)).rect(ox + hx * m - xs, oy + (hy - 30 * hs) * m - xs, ox + hx * m + xs, oy + (hy - 30 * hs) * m + xs).submit();
    }

    private static void weather(GuiGraphics g, int x, int y, int w, int h, float alpha) {
        g.fillGradient(x, y, x + w, y + h, fade(0xFF3A4656, alpha), fade(0xFF6A7686, alpha));
        boolean rain = Weather.RAIN.get();
        boolean snow = Weather.SNOW.get();
        float t = seconds();
        float opacity = Weather.OPACITY.get().floatValue();
        Shapes drops = Shapes.of(g, fade(0xC08FB4E8, alpha * opacity));
        Shapes flakes = Shapes.of(g, fade(0xF0FFFFFF, alpha * opacity));
        for (int i = 0; i < 40; i++) {
            float px = x + (i * 53 % 97) / 97f * w;
            boolean flake = i % 2 == 1;
            if (flake ? !snow : !rain) continue;
            float speed = flake ? 0.25f : 1.4f;
            float py = y + ((i * 31 % 89) / 89f + t * speed) % 1 * h;
            if (flake) flakes.rect(px - 0.8f, py - 0.8f, px + 0.8f, py + 0.8f);
            else drops.rect(px - 0.4f, py - 3, px + 0.4f, py + 3);
        }
        drops.submit();
        flakes.submit();
        label(g, (rain ? "Rain on" : "Rain off") + ", " + (snow ? "snow on" : "snow off"), x + 4, y + h - Text.CAP - 4, alpha);
    }

    /** Everything else: its colours as swatches and its other values, read straight from the settings. */
    private static void settings(GuiGraphics g, Group group, int x, int y, int w, int h, float alpha) {
        int sy = y + 4;
        int sx = x + 4;
        for (Setting<?> setting : group.settings) {
            if (!(setting instanceof ColorSetting color)) continue;
            String name = setting.name;
            int sw = 10 + 3 + Text.width(name) + 8;
            if (sx + sw > x + w - 2) {
                sx = x + 4;
                sy += 13;
            }
            if (sy + 10 > y + h) return;
            g.fill(sx, sy, sx + 10, sy + 10, Visuals.argb(color.get(), alpha));
            Text.draw(g, name, sx + 13, sy + (10 - Text.CAP) / 2, fade(DIM, alpha));
            sx += sw;
        }
        if (sx != x + 4) sy += 15;
        for (Setting<?> setting : group.settings) {
            String value;
            if (setting instanceof SliderSetting slider) value = slider.display();
            else if (setting instanceof ModeSetting mode) value = mode.get();
            else if (setting instanceof BoolSetting bool) value = bool.get() ? "on" : "off";
            else continue;
            if (sy + Text.CAP > y + h - 2) return;
            Text.draw(g, setting.name, x + 4, sy, fade(DIM, alpha));
            Text.draw(g, value, x + w - 4 - Text.width(value), sy, fade(TEXT, alpha));
            sy += Text.CAP + 4;
        }
    }
}
