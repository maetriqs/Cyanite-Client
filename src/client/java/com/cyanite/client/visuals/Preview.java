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
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The Visuals tab's live preview: a small picture of what a group does, drawn from its settings as they are
 * right now, so a slider shows its effect while it moves. Items go through the same hooks as in the world
 * (Item Colors, Glint, durability bars), so those show exactly what the game will draw. Every group has one;
 * the cards in the tab draw them small, without their labels.
 * Everything is in the caller's units; {@link #real()} converts from the game's own GUI units.
 */
public final class Preview {
    private static final int TEXT = 0xFFE9ECF1;
    private static final int DIM = 0xFF8A919D;
    private static final int FAINT = 0xFF4E5461;
    private static final int SKY_TOP = 0xFF5F86B8;
    private static final int SKY_BOTTOM = 0xFF9CB7D6;
    private static final int GROUND = 0xFF4A6E36;
    private static final int NIGHT_TOP = 0xFF070A16;
    private static final int NIGHT_BOTTOM = 0xFF1A2036;
    /** A splash potion of healing, as the game colours it. */
    private static final int HEALING = 0xF82423;

    private static final ItemStack TOTEM = new ItemStack(Items.TOTEM_OF_UNDYING);
    private static final ItemStack SWORD = glinting(Items.NETHERITE_SWORD);
    private static final ItemStack SWORD_PLAIN = new ItemStack(Items.DIAMOND_SWORD);
    private static final ItemStack NETHERITE_SWORD = new ItemStack(Items.NETHERITE_SWORD);
    private static final ItemStack AXE = new ItemStack(Items.NETHERITE_AXE);
    private static final ItemStack GAPPLE = new ItemStack(Items.GOLDEN_APPLE);
    private static final ItemStack PEARL = new ItemStack(Items.ENDER_PEARL);
    private static final ItemStack TNT = new ItemStack(Items.TNT);
    private static final ItemStack SPLASH = PotionContents.createItemStack(Items.SPLASH_POTION, Potions.STRONG_HEALING);
    private static final ItemStack CHEST = glinting(Items.DIAMOND_CHESTPLATE);
    private static final List<ItemStack> ARMOR = List.of(worn(Items.DIAMOND_HELMET, 0.92f), worn(Items.NETHERITE_CHESTPLATE, 0.6f),
        worn(Items.DIAMOND_LEGGINGS, 0.33f), worn(Items.DIAMOND_BOOTS, 0.12f));

    // A cube's corners are numbered by their bits (x 1, y 2, z 4); its edges join corners one bit apart.
    private static final int[][] EDGES = {{0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
    private static final int[][] FACES = {{0, 2, 6, 4}, {1, 3, 7, 5}, {0, 1, 5, 4}, {2, 3, 7, 6}, {0, 1, 3, 2}, {4, 5, 7, 6}};
    /** Tilts a cube onto one corner, the way an end crystal's cubes stand. */
    private static final float ON_CORNER = 0.9553f;

    /** Drawing a card's thumbnail: no labels, they don't fit. */
    private static boolean small;

    private Preview() {
    }

    /** An item at (x, y), {@code size} across, in the caller's units: the tab's icons. */
    public static void icon(GuiGraphics g, ItemStack stack, float x, float y, float size) {
        item(g, stack, x, y, size);
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
        draw(g, group, x, y, w, h, alpha, false);
    }

    /** As {@link #draw}, {@code thumbnail} leaving out the labels for a card's small picture. */
    public static void draw(GuiGraphics g, Group group, int x, int y, int w, int h, float alpha, boolean thumbnail) {
        small = thumbnail;
        try {
            pick(g, group, x, y, w, h, alpha);
        } finally {
            small = false;
        }
    }

    private static void pick(GuiGraphics g, Group group, int x, int y, int w, int h, float alpha) {
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
        else if (group == Particles.GENERAL) particles(g, x, y, w, h, alpha);
        else if (group == Crystals.MODEL) crystalModel(g, x, y, w, h, alpha);
        else if (group == Crystals.ANIMATION) crystalAnimation(g, x, y, w, h, alpha);
        else if (group == Crystals.BEAM) crystalBeam(g, x, y, w, h, alpha);
        else if (group == Crystals.HIGHLIGHT) crystalHighlight(g, x, y, w, h, alpha);
        else if (group == BlockColors.GROUP) blockColors(g, x, y, w, h, alpha);
        else if (group == Particles.EXPLOSION) explosionParticles(g, x, y, w, h, alpha);
        else if (group == Explosions.GROUP) explosions(g, x, y, w, h, alpha);
        else if (group == Hands.SWORD_SWING) swing(g, x, y, w, h, alpha, Hands.SWORD, NETHERITE_SWORD);
        else if (group == Hands.AXE_SWING) swing(g, x, y, w, h, alpha, Hands.AXE, AXE);
        else if (group == Hands.SWORD_ATTACK) attack(g, x, y, w, h, alpha, Hands.SWORD_HIT, NETHERITE_SWORD);
        else if (group == Hands.AXE_ATTACK) attack(g, x, y, w, h, alpha, Hands.AXE_HIT, AXE);
        else if (group == Hands.GAPPLE) gapple(g, x, y, w, h, alpha);
        else if (group == HitParticles.SWORD) hitParticles(g, x, y, w, h, alpha, HitParticles.SWORD_BURST);
        else if (group == HitParticles.AXE) hitParticles(g, x, y, w, h, alpha, HitParticles.AXE_BURST);
        else if (group == HitParticles.UHC) hitParticles(g, x, y, w, h, alpha, HitParticles.UHC_BURST);
        else if (group == Particles.CRIT) crits(g, x, y, w, h, alpha);
        else if (group == ShieldBreak.GROUP) shieldBreak(g, x, y, w, h, alpha);
        else if (group == Particles.SPLASH) splash(g, x, y, w, h, alpha);
        else if (group == PotionImpact.GROUP) potionImpact(g, x, y, w, h, alpha);
        else if (group == ProjectileFx.GROUP) projectiles(g, x, y, w, h, alpha);
        else if (group == HurtCamera.GROUP) hurtCamera(g, x, y, w, h, alpha);
        else if (group == Glow.GROUP) glowPreview(g, x, y, w, h, alpha);
        else if (group == TimeChanger.GROUP) timeChanger(g, x, y, w, h, alpha);
        else if (group == SkyColors.GROUP) skyColors(g, x, y, w, h, alpha);
        else if (group == ItemGlow.GROUP) itemGlow(g, x, y, w, h, alpha);
        else if (group == BreakProgress.GROUP) breakProgress(g, x, y, w, h, alpha);
        else if (group == Halo.GROUP) halo(g, x, y, w, h, alpha);
        else if (group == Fireflies.GROUP) fireflies(g, x, y, w, h, alpha);
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
        if (!small) Text.draw(g, text, x, y, fade(DIM, alpha));
    }

    /** A label on a dark tag, for a bright sky behind it. */
    private static void tag(GuiGraphics g, String text, int x, int y, float alpha) {
        if (small) return;
        g.fill(x - 2, y - 2, x + Text.width(text) + 2, y + Text.CAP + 2, fade(0x99000000, alpha));
        Text.draw(g, text, x, y, fade(TEXT, alpha));
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
        // Three rows, each its colour's dot and its items on a glow of that colour, in the middle.
        int row = Math.min(30, (h - 8) / 3);
        float size = Math.min(22, row - 6);
        float step = size + 8;
        int fit = Math.max(1, (int) ((w - 34) / step));
        int most = 1;
        for (List<Item> items : lists) most = Math.max(most, Math.min(items.size(), fit));
        float left = x + Math.max(6, (w - 18 - most * step) / 2);
        float top = y + (h - 3 * row) / 2f;
        Polys glow = Polys.of(g, 0);
        for (int i = 0; i < 3; i++) {
            float cy = top + i * row + row / 2f;
            int rgb = colors[i].get();
            glow.disc(left + 4, cy, 4, 4, 14, Visuals.argb(rgb, alpha), Visuals.argb(rgb, alpha));
            int n = Math.min(lists.get(i).size(), fit);
            for (int k = 0; k < n; k++) {
                float r = size * 0.78f;
                glow.disc(left + 18 + k * step + size / 2, cy, r, r, 18, Visuals.argb(rgb, 0.55 * alpha), Visuals.argb(rgb, 0));
            }
        }
        glow.submit(g);
        for (int i = 0; i < 3; i++) {
            float cy = top + i * row + row / 2f;
            List<Item> items = lists.get(i);
            if (items.isEmpty()) {
                Text.draw(g, "No items", Math.round(left + 18), Math.round(cy - Text.CAP / 2f), fade(FAINT, alpha));
                continue;
            }
            for (int k = 0; k < Math.min(items.size(), fit); k++) item(g, new ItemStack(items.get(k)), left + 18 + k * step, cy - size / 2, size);
        }
    }

    private static void glint(GuiGraphics g, int x, int y, int w, int h, float alpha) {
        float size = Math.min(40, h - 20);
        item(g, SWORD, x + w * 0.3f - size / 2, y + (h - size) / 2f - 4, size);
        item(g, CHEST, x + w * 0.7f - size / 2, y + (h - size) / 2f - 4, size);
        if (small) return;
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
        // Soft light under the whole arc first, as in the world.
        if (SwordTrail.GLOW.get()) {
            float strength = glowStrength();
            float[] xs = new float[steps];
            float[] ys = new float[steps];
            float[] reach = new float[steps];
            int[] colors = new int[steps];
            int n = 0;
            for (; n < steps; n++) {
                float back = n / (float) steps;
                float at = head - back * span;
                if (at < 0) break;
                double angle = Math.PI * (0.95 - 0.8 * at);
                xs[n] = cx - (float) Math.cos(angle) * r;
                ys[n] = cy - (float) Math.sin(angle) * r;
                reach[n] = Math.max(3, width * 2.8f) * (1 - back * 0.6f);
                colors[n] = Visuals.argb(Visuals.lerp(from, to, back), alpha * fadeOut * 0.5f * strength * (1 - back * 0.7f));
            }
            Polys.of(g, 0).glowPath(xs, ys, reach, colors, n, false).submit(g);
        }
        // One batch per colour band, so the whole arc is a couple of dozen pieces for the menu, not a hundred.
        for (int band = bands - 1; band >= 0; band--) {
            float mid = (band + 0.5f) / bands;
            int c = Visuals.lerp(from, to, mid);
            Shapes core = Shapes.of(g, fade(c, alpha * fadeOut * (1 - mid * 0.6f)));
            for (int i = band * steps / bands; i < (band + 1) * steps / bands; i++) {
                float back = i / (float) steps;
                float at = head - back * span;
                if (at < 0) continue;
                double angle = Math.PI * (0.95 - 0.8 * at);
                float px = cx - (float) Math.cos(angle) * r;
                float py = cy - (float) Math.sin(angle) * r;
                float sz = Math.max(0.8f, width * (1 - back * 0.85f));
                core.rect(px - sz / 2, py - sz / 2, px + sz / 2, py + sz / 2);
            }
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
            // The world draws trails glowing, and a glow on the thing itself.
            Polys light = Polys.of(g, 0);
            float strength = glowStrength();
            float[] xs = new float[steps + 1];
            float[] ys = new float[steps + 1];
            float[] sizes = new float[steps + 1];
            float[] reach = new float[steps + 1];
            int[] core = new int[steps + 1];
            int[] shine = new int[steps + 1];
            for (int s = 0; s <= steps; s++) {
                float p = tail + (head - tail) * s / steps;
                xs[s] = x + 6 + p * (w - 12);
                // A shallow arc, the way a throw drops.
                ys[s] = lane + (float) (Math.pow(p - 0.35, 2) * 18) - 4;
                sizes[s] = Math.max(0.7f, width * 0.5f * (0.3f + 0.7f * s / steps));
                reach[s] = Math.max(2.5f, width * 1.8f);
                core[s] = Visuals.argb(colors[i], alpha * fadeOut * (0.35f + 0.65f * s / steps));
                shine[s] = Visuals.argb(colors[i], alpha * fadeOut * 0.45f * strength * s / steps);
            }
            if (head > tail) {
                light.glowPath(xs, ys, reach, shine, steps + 1, false);
                light.glow(xs[steps], ys[steps], Math.max(4, width * 2.6f), Visuals.argb(colors[i], alpha * fadeOut * 0.7f * strength));
                light.linePath(xs, ys, sizes, core, steps + 1, false);
            }
            light.submit(g);
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

    // ---- Shared by the previews below

    /** A steady 0 to 1 for particle {@code i}, so a burst looks the same on every loop. */
    private static float rand(int i, int salt) {
        int v = i * 0x9E3779B1 + salt * 0x85EBCA6B;
        v ^= v >>> 15;
        v *= 0x2C1B3C6D;
        v ^= v >>> 12;
        v *= 0x297A2D39;
        v ^= v >>> 15;
        return (v >>> 8) / (float) (1 << 24);
    }

    /** Fast at first, settling at 1. */
    private static float ease(float t) {
        t = 1 - Math.max(0, Math.min(1, t));
        return 1 - t * t * t;
    }

    private static float horizon(int y, int h) {
        return y + h * 3 / 5;
    }

    private static void night(GuiGraphics g, int x, int y, int w, int h, float alpha) {
        g.fillGradient(x, y, x + w, y + h, fade(NIGHT_TOP, alpha), fade(NIGHT_BOTTOM, alpha));
    }

    /** {@code argb} with its red, green and blue scaled by {@code light}. */
    private static int shade(int argb, float light) {
        int r = Math.min(255, Math.round((argb >> 16 & 0xFF) * light));
        int gr = Math.min(255, Math.round((argb >> 8 & 0xFF) * light));
        int b = Math.min(255, Math.round((argb & 0xFF) * light));
        return argb & 0xFF000000 | r << 16 | gr << 8 | b;
    }

    /** A flash over the whole picture. */
    private static void flash(GuiGraphics g, int x, int y, int w, int h, int rgb, float strength) {
        if (strength > 0.004f) g.fill(x, y, x + w, y + h, Visuals.argb(rgb, strength));
    }

    /** An isometric block whose top face is centred on (x, y), {@code hw} from its middle to a side corner. */
    private static void block(Polys p, float x, float y, float hw, float hh, int top, int left, int right) {
        p.color(top).quad(x, y - hw / 2, x + hw, y, x, y + hw / 2, x - hw, y);
        p.color(left).quad(x - hw, y, x, y + hw / 2, x, y + hw / 2 + hh, x - hw, y + hh);
        p.color(right).quad(x, y + hw / 2, x + hw, y, x + hw, y + hh, x, y + hw / 2 + hh);
    }

    /** The visible edges of {@link #block}. */
    private static void blockEdges(Polys p, float x, float y, float hw, float hh, float lw, boolean all) {
        p.line(x - hw, y, x, y - hw / 2, lw).line(x, y - hw / 2, x + hw, y, lw)
            .line(x + hw, y, x, y + hw / 2, lw).line(x, y + hw / 2, x - hw, y, lw);
        if (!all) return;
        p.line(x - hw, y, x - hw, y + hh, lw).line(x, y + hw / 2, x, y + hw / 2 + hh, lw).line(x + hw, y, x + hw, y + hh, lw)
            .line(x - hw, y + hh, x, y + hw / 2 + hh, lw).line(x, y + hw / 2 + hh, x + hw, y + hh, lw);
    }

    /** {@code stack} at (cx, cy) by its middle, turned {@code angle} radians and {@code size} across. */
    private static void spun(GuiGraphics g, ItemStack stack, float cx, float cy, float size, float angle) {
        g.pose().pushMatrix();
        g.pose().translate(cx, cy);
        g.pose().rotate(angle);
        item(g, stack, -size / 2, -size / 2, size);
        g.pose().popMatrix();
    }

    /**
     * A sword or axe in your right hand, first person: held by its handle at (px, py), its blade up and to the
     * left, turned a further {@code angle} radians and grown by {@code scale}.
     */
    private static void held(GuiGraphics g, ItemStack stack, float px, float py, float size, float angle, float scale) {
        g.pose().pushMatrix();
        g.pose().translate(px, py);
        g.pose().rotate((float) (-Math.PI / 2) + angle);
        g.pose().scale(scale);
        item(g, stack, -size * 0.16f, -size * 0.84f, size);
        g.pose().popMatrix();
    }

    // ---- End crystals

    /** A cube stood on one corner and spun round the upright by {@code spin}: each corner's x, y (up) and z (towards you), from -1.7 to 1.7. */
    private static float[][] cube(float spin) {
        float[][] out = new float[8][3];
        double r = Math.sqrt(0.5);
        double ct = Math.cos(ON_CORNER);
        double st = Math.sin(ON_CORNER);
        double cs = Math.cos(spin);
        double ss = Math.sin(spin);
        for (int i = 0; i < 8; i++) {
            double x = (i & 1) == 0 ? -1 : 1;
            double y = (i & 2) == 0 ? -1 : 1;
            double z = (i & 4) == 0 ? -1 : 1;
            // A quarter turn round the upright, then over onto the corner, so the long diagonal stands straight up...
            double x1 = (x + z) * r;
            double z1 = (z - x) * r;
            double x2 = x1 * ct - y * st;
            double y2 = x1 * st + y * ct;
            // ...and the spin.
            out[i][0] = (float) (x2 * cs + z1 * ss);
            out[i][1] = (float) y2;
            out[i][2] = (float) (z1 * cs - x2 * ss);
        }
        return out;
    }

    private static int glassColor() {
        return Crystals.TINT.get() ? Visuals.lerp(0xFFFFFFFF, 0xFF000000 | Crystals.COLOR.get(), 0.45f) : 0xFFF1E2FF;
    }

    private static int coreColor() {
        return Crystals.TINT.get() ? 0xFF000000 | Crystals.COLOR.get() : 0xFFD04BF0;
    }

    /** An end crystal floating at (cx, cy): two glass cubes round a solid core, {@code half} the outer cube's half-width. */
    private static void crystal(GuiGraphics g, float cx, float cy, float half, float spin, float alpha) {
        Polys p = Polys.of(g, 0);
        solidCube(p, cube(-spin * 1.3f + 0.7f), cx, cy, half * 0.42f, coreColor(), alpha);
        glassCube(p, cube(spin + 0.35f), cx, cy, half * 0.72f, glassColor(), alpha);
        glassCube(p, cube(spin), cx, cy, half, glassColor(), alpha);
        p.submit(g);
    }

    private static void solidCube(Polys p, float[][] c, float cx, float cy, float half, int rgb, float alpha) {
        for (int[] face : FACES) {
            float nx = 0;
            float ny = 0;
            float nz = 0;
            for (int i : face) {
                nx += c[i][0];
                ny += c[i][1];
                nz += c[i][2];
            }
            // Only the faces turned towards you; on a cube those never overlap.
            if (nz <= 0) continue;
            float light = 0.45f + 0.55f * Math.max(0, (-0.45f * nx + 0.75f * ny + 0.5f * nz) / 4);
            p.color(fade(shade(rgb, light), alpha));
            p.quad(cx + c[face[0]][0] * half, cy - c[face[0]][1] * half, cx + c[face[1]][0] * half, cy - c[face[1]][1] * half,
                cx + c[face[2]][0] * half, cy - c[face[2]][1] * half, cx + c[face[3]][0] * half, cy - c[face[3]][1] * half);
        }
    }

    private static void glassCube(Polys p, float[][] c, float cx, float cy, float half, int rgb, float alpha) {
        float lw = Math.max(0.7f, half * 0.07f);
        for (int[] edge : EDGES) {
            float[] a = c[edge[0]];
            float[] b = c[edge[1]];
            // The far edges fainter, so the cube reads as see-through glass.
            float depth = (a[2] + b[2]) / 2;
            p.color(fade(rgb, alpha * (depth < 0 ? 0.35f : 0.95f)));
            p.line(cx + a[0] * half, cy - a[1] * half, cx + b[0] * half, cy - b[1] * half, lw);
        }
    }

    /** The bedrock a crystal sits on, its top centred on (cx, top). */
    private static void bedrock(GuiGraphics g, float cx, float top, float hw, float alpha) {
        Polys p = Polys.of(g, 0);
        block(p, cx, top, hw, hw * 0.5f, fade(0xFF6E6E6E, alpha), fade(0xFF4C4C4C, alpha), fade(0xFF393939, alpha));
        p.color(fade(0xFF2E2E2E, alpha));
        for (int i = 0; i < 5; i++) {
            float sx = cx + (rand(i, 3) - 0.5f) * hw;
            float sy = top + (rand(i, 4) - 0.5f) * hw * 0.4f;
            p.rect(sx - hw * 0.08f, sy - hw * 0.04f, sx + hw * 0.08f, sy + hw * 0.04f);
        }
        p.submit(g);
    }

    private static void crystalModel(GuiGraphics g, int x, int y, int w, int h, float alpha) {
        sky(g, x, y, w, h, alpha);
        float unit = Math.min(w, h) * 0.2f;
        float whole = Crystals.SIZE.get().floatValue();
        float half = unit * whole * Crystals.CUBE_SCALE.get().floatValue();
        float cx = x + w / 2f;
        float floor = y + h * 0.84f;
        if (!Crystals.HIDE_BASE.get()) bedrock(g, cx, floor - unit * 0.55f * whole, unit * 0.95f * whole, alpha);
        float cy = floor - unit * 1.75f * whole + (float) Math.sin(seconds() * 2) * unit * 0.12f;
        crystal(g, cx, cy, half, seconds() * 1.05f, alpha * Crystals.OPACITY.get().floatValue());
        label(g, String.format(java.util.Locale.ROOT, "%.2fx", whole), x + 4, y + 4, alpha);
    }

    private static void crystalAnimation(GuiGraphics g, int x, int y, int w, int h, float alpha) {
        sky(g, x, y, w, h, alpha);
        float unit = Math.min(w, h) * 0.18f;
        float cx = x + w / 2f;
        float floor = y + h * 0.86f;
        bedrock(g, cx, floor - unit * 0.55f, unit * 0.95f, alpha);
        // The game's bounce: a sine squashed to sit low and spring up, 0 to 0.8 of a block.
        float t = seconds();
        float s = (float) Math.sin(t * 4 * Crystals.BOB_SPEED.get()) / 2 + 0.5f;
        float bob = (s * s + s) * 0.4f * Crystals.BOB_HEIGHT.get().floatValue() * unit * 1.3f;
        float cy = floor - unit * 1.7f - bob;
        crystal(g, cx, cy, unit, t * 1.05f * Crystals.SPIN.get().floatValue(), alpha);
    }

    private static void crystalBeam(GuiGraphics g, int x, int y, int w, int h, float alpha) {
        night(g, x, y, w, h, alpha);
        float unit = Math.min(w, h) * 0.16f;
        float floor = y + h * 0.88f;
        g.fill(x, Math.round(floor), x + w, y + h, fade(0xFF1D1B26, alpha));
        float cx = x + w * 0.64f;
        float cy = floor - unit * 1.8f + (float) Math.sin(seconds() * 2) * unit * 0.1f;
        int rgb = Crystals.BEAM_COLOR.get();
        float pulse = 0.8f + 0.2f * (float) Math.sin(seconds() * 6);
        Polys beam = Polys.of(g, 0);
        // The beam the game draws to whatever the crystal is linked to; and the one into the sky if you want it.
        float tx = x + w * 0.12f;
        float ty = y + h * 0.14f;
        beam.line(cx, cy, tx, ty, unit * 0.9f, Visuals.argb(rgb, 0.16 * alpha * pulse), Visuals.argb(rgb, 0.1 * alpha));
        beam.line(cx, cy, tx, ty, Math.max(1, unit * 0.28f), Visuals.argb(rgb, 0.95 * alpha), Visuals.argb(rgb, 0.8 * alpha));
        beam.line(cx, cy, tx, ty, Math.max(0.5f, unit * 0.09f), fade(0xFFFFFFFF, alpha * 0.8f), fade(0xFFFFFFFF, alpha * 0.5f));
        if (Crystals.SKY_BEAM.get()) {
            float top = cy - (cy - y) * Math.min(1, Crystals.SKY_LENGTH.get().floatValue() / 24f);
            beam.line(cx, cy, cx, top, unit * 0.9f, Visuals.argb(rgb, 0.18 * alpha * pulse), Visuals.argb(rgb, 0));
            beam.line(cx, cy, cx, top, Math.max(1, unit * 0.3f), Visuals.argb(rgb, alpha), Visuals.argb(rgb, 0.1 * alpha));
        }
        beam.submit(g);
        bedrock(g, cx, floor - unit * 0.55f, unit * 0.95f, alpha);
        crystal(g, cx, cy, unit, seconds() * 1.05f, alpha);
    }

    private static void crystalHighlight(GuiGraphics g, int x, int y, int w, int h, float alpha) {
        sky(g, x, y, w, h, alpha);
        int rgb = Crystals.HIGHLIGHT_COLOR.get();
        float unit = Math.min(w, h) * 0.15f;
        float[][] crystals = {{x + w * 0.72f, y + h * 0.66f, unit * 0.7f}, {x + w * 0.32f, y + h * 0.86f, unit}};
        for (int i = 0; i < 2; i++) {
            float cx = crystals[i][0];
            float floor = crystals[i][1];
            float u = crystals[i][2];
            boolean far = i == 0;
            bedrock(g, cx, floor - u * 0.55f, u * 0.95f, alpha);
            crystal(g, cx, floor - u * 1.75f, u, seconds() * 1.05f + i, alpha);
            float a = alpha * (far && Crystals.CLOSEST.get() ? 0.45f : 1);
            float l = cx - u * 1.5f;
            float r = cx + u * 1.5f;
            float top = floor - u * 3.4f;
            float bottom = floor + u * 0.1f;
            float lw = Math.max(0.8f, u * 0.09f);
            Polys box = Polys.of(g, Visuals.argb(rgb, a));
            switch (Crystals.STYLE.get()) {
                case "Corners" -> {
                    float c = (r - l) * 0.28f;
                    box.rect(l, top, l + c, top + lw).rect(l, top, l + lw, top + c).rect(r - c, top, r, top + lw).rect(r - lw, top, r, top + c)
                        .rect(l, bottom - lw, l + c, bottom).rect(l, bottom - c, l + lw, bottom)
                        .rect(r - c, bottom - lw, r, bottom).rect(r - lw, bottom - c, r, bottom);
                }
                case "Outline" -> box.rect(l, top, r, top + lw).rect(l, bottom - lw, r, bottom).rect(l, top, l + lw, bottom).rect(r - lw, top, r, bottom);
                default -> {
                    box.color(Visuals.argb(rgb, 0.18 * a)).rect(l, top, r, bottom);
                    box.color(Visuals.argb(rgb, a)).rect(l, top, r, top + lw).rect(l, bottom - lw, r, bottom).rect(l, top, l + lw, bottom)
                        .rect(r - lw, top, r, bottom);
                }
            }
            box.submit(g);
        }
    }

    // ---- Blocks and explosions

    private static void blockColors(GuiGraphics g, int x, int y, int w, int h, float alpha) {
        night(g, x, y, w, h, alpha);
        // A patch of floor: o obsidian, c crying obsidian, b bedrock, s stone. Index is row * 3 + column.
        String floor = "oobocbsbo";
        float hw = Math.min((w - 8) / 6f, (h - 8) / 4.1f);
        float hh = hw * 1.1f;
        float ox = x + w / 2f;
        float oy = y + (h - (3 * hw + hh)) / 2 + hw / 2;
        int obsidian = BlockColors.OBSIDIAN.get();
        int bedrock = BlockColors.BEDROCK.get();
        float opacity = BlockColors.OPACITY.get().floatValue();
        String style = BlockColors.STYLE.get();
        Polys blocks = Polys.of(g, 0);
        Polys marks = Polys.of(g, 0);
        for (int sum = 0; sum <= 4; sum++) {
            for (int gx = 0; gx < 3; gx++) {
                int gz = sum - gx;
                if (gz < 0 || gz > 2) continue;
                char kind = floor.charAt(gz * 3 + gx);
                float bx = ox + (gx - gz) * hw;
                float by = oy + (gx + gz) * hw / 2;
                switch (kind) {
                    case 'b' -> block(blocks, bx, by, hw, hh, fade(0xFF727272, alpha), fade(0xFF565656, alpha), fade(0xFF434343, alpha));
                    case 's' -> block(blocks, bx, by, hw, hh, fade(0xFF8D8D8D, alpha), fade(0xFF707070, alpha), fade(0xFF5C5C5C, alpha));
                    default -> block(blocks, bx, by, hw, hh, fade(0xFF221836, alpha), fade(0xFF181026, alpha), fade(0xFF110B1B, alpha));
                }
                if (kind == 'c') {
                    blocks.color(fade(0xFF9A45FF, alpha));
                    for (int i = 0; i < 3; i++) {
                        float sx = bx + (rand(i, 7) - 0.5f) * hw * 0.9f;
                        float sy = by + (rand(i, 8) - 0.5f) * hw * 0.35f;
                        blocks.rect(sx - hw * 0.07f, sy - hw * 0.04f, sx + hw * 0.07f, sy + hw * 0.04f);
                    }
                }
                boolean colored = kind == 'o' || kind == 'b' || kind == 'c' && BlockColors.CRYING.get();
                if (!colored) continue;
                int rgb = kind == 'b' ? bedrock : obsidian;
                float lw = Math.max(0.7f, hw * 0.06f);
                switch (style) {
                    case "Outline" -> blockEdges(marks.color(Visuals.argb(rgb, 0.95 * alpha)), bx, by, hw, hh, lw, true);
                    case "Box" -> {
                        int fill = Visuals.argb(rgb, opacity * 0.8 * alpha);
                        block(marks, bx, by, hw, hh, fill, fill, fill);
                        blockEdges(marks.color(Visuals.argb(rgb, Math.min(1, opacity + 0.45) * alpha)), bx, by, hw, hh, lw, true);
                    }
                    default -> {
                        marks.color(Visuals.argb(rgb, opacity * alpha)).quad(bx, by - hw / 2, bx + hw, by, bx, by + hw / 2, bx - hw, by);
                        blockEdges(marks.color(Visuals.argb(rgb, Math.min(1, opacity + 0.45) * alpha)), bx, by, hw, hh, lw, false);
                    }
                }
            }
        }
        blocks.submit(g);
        marks.submit(g);
    }

    private static void explosionParticles(GuiGraphics g, int x, int y, int w, int h, float alpha) {
        sky(g, x, y, w, h, alpha);
        float t = seconds() % 1.6f / 1.3f;
        float cx = x + w / 2f;
        float cy = horizon(y, h) + h * 0.12f;
        float reach = Math.min(w, h * 1.6f) * 0.3f;
        if (t >= 1) {
            tnt(g, cx, cy, reach * 0.55f);
            return;
        }
        float size = Particles.EXPLOSION_SIZE.get().floatValue();
        int rgb = Particles.EXPLOSION_COLOR.get();
        int count = Math.round(16 * Particles.EXPLOSION_DENSITY.get().floatValue());
        Polys puffs = Polys.of(g, 0);
        if (Particles.EXPLOSION_SMOKE.get()) {
            for (int i = 0; i < 7; i++) {
                float sx = cx + (rand(i, 21) - 0.5f) * reach * 1.4f;
                float sy = cy - t * h * 0.35f - rand(i, 22) * reach * 0.5f;
                float r = reach * (0.18f + 0.12f * rand(i, 23)) * (0.6f + t);
                int smoke = fade(0xFF5A5A5A, alpha * 0.45f * (1 - t));
                puffs.disc(sx, sy, r, r, 14, smoke, smoke & 0x00FFFFFF);
            }
        }
        for (int i = 0; i < count; i++) {
            double a = i * 2.39996;
            float d = (0.15f + 0.85f * rand(i, 11)) * (float) Math.sqrt(t) * reach;
            float px = cx + (float) Math.cos(a) * d;
            float py = cy + (float) Math.sin(a) * d * 0.7f;
            float r = reach * (0.09f + 0.07f * rand(i, 12)) * size * (1.15f - t * 0.5f);
            int c = fade(Visuals.lerp(0xFF000000 | rgb, 0xFFFFFFFF, 0.35f * rand(i, 13) * (1 - t)), alpha * (1 - t));
            puffs.disc(px, py, r, r, 12, c, fade(c, 0.35f));
        }
        puffs.submit(g);
    }

    private static void explosions(GuiGraphics g, int x, int y, int w, int h, float alpha) {
        sky(g, x, y, w, h, alpha);
        float length = Explosions.LENGTH.get().floatValue();
        float t = seconds() % (length + 0.4f) / length;
        int rgb = Explosions.COLOR.get();
        float size = Explosions.SIZE.get().floatValue();
        float cx = x + w / 2f;
        float ground = horizon(y, h) + h * 0.16f;
        float reach = Math.min(w * 0.5f, h * 0.9f) * 0.42f * size;
        if (t >= 1) {
            tnt(g, cx, ground - h * 0.08f, Math.min(h * 0.22f, 26));
            return;
        }
        float fade = 1 - t;
        Polys fx = Polys.of(g, 0);
        if (t < 0.35f) {
            float core = reach * 0.35f * ease(t / 0.35f);
            fx.disc(cx, ground - reach * 0.2f, core, core, 18, Visuals.argb(0xFFFFFF, 0.9 * (1 - t / 0.35f) * alpha),
                Visuals.argb(rgb, 0));
        }
        if (Explosions.RING.get()) {
            float rx = reach * 1.6f * ease(t);
            fx.color(Visuals.argb(rgb, fade * alpha)).ring(cx, ground, rx, rx * 0.26f, Math.max(0.8f, 3 * fade), 40);
        }
        if (Explosions.SHOCKWAVE.get()) {
            float r = reach * ease(t * 1.4f);
            fx.color(Visuals.argb(rgb, 0.8 * fade * alpha)).ring(cx, ground - reach * 0.35f, r, r, Math.max(0.7f, 2.2f * fade), 40);
        }
        fx.submit(g);
        if (Explosions.FLASH.get() && t < 0.18f) flash(g, x, y, w, h, 0xFFFFFF, (float) (Explosions.FLASH_STRENGTH.get() * (1 - t / 0.18f) * alpha));
    }

    /** Primed TNT where the next blast goes off, so the quiet part of the loop still shows something. */
    private static void tnt(GuiGraphics g, float cx, float cy, float size) {
        item(g, TNT, cx - size / 2, cy - size / 2, size);
    }

    // ---- Your hand

    /** The swing's progress, 0 to 1, once a loop; {@code seconds} long. */
    private static float swingProgress(float seconds) {
        float t = seconds() % Math.max(0.9f, seconds + 0.55f);
        return Math.min(1, t / seconds);
    }

    private static void swing(GuiGraphics g, int x, int y, int w, int h, float alpha, Hands.Swing swing, ItemStack stack) {
        sky(g, x, y, w, h, alpha);
        float p = swingProgress(0.32f / swing.speed.get().floatValue());
        float k = swing.strength.get().floatValue();
        float size = h * 0.62f * swing.size.get().floatValue();
        float px = x + w * 0.84f;
        float py = y + h * 1.02f;
        float sin = (float) Math.sin(p * Math.PI);
        float sq = (float) Math.sin(Math.sqrt(p) * Math.PI);
        float angle = 0;
        float dx = 0;
        float dy = 0;
        float scale = 1;
        double deg = Math.PI / 180;
        switch (swing.style.get()) {
            case "Vanilla" -> {
                angle = (float) (-50 * deg * sq * k);
                dx = -0.4f * sq * size * k;
                dy = -0.2f * (float) Math.sin(Math.sqrt(p) * Math.PI * 2) * size * k;
            }
            case "Smooth" -> {
                angle = (float) (-38 * deg * sin * k);
                dx = -0.25f * sin * size * k;
            }
            case "Slash" -> {
                angle = (float) (-70 * deg * sin * k);
                dx = -0.5f * sin * size * k;
                dy = 0.3f * size * k * sin * (p - 0.3f);
            }
            case "Stab" -> {
                angle = (float) (-8 * deg * sin * k);
                dx = -0.32f * sin * size * k;
                dy = -0.28f * sin * size * k;
                scale = 1 + 0.25f * sin * k;
            }
            case "Spin" -> angle = (float) (-Math.PI * 2 * ease(p));
            case "Low" -> {
                dy = 0.22f * size;
                angle = (float) (-22 * deg * sin * k);
                dx = -0.15f * sin * size * k;
            }
            default -> {
                // Chop: up, down hard, and back.
                double a = p < 0.3f ? -50 * (p / 0.3f) : p < 0.55f ? -50 + 85 * ((p - 0.3f) / 0.25f) : 35 * (1 - (p - 0.55f) / 0.45f);
                angle = (float) (a * deg * k);
                dy = angle > 0 ? 0.15f * size * angle / (float) (35 * deg) : 0;
            }
        }
        held(g, stack, px + dx, py + dy, size, angle, scale);
    }

    private static void attack(GuiGraphics g, int x, int y, int w, int h, float alpha, Hands.Attack attack, ItemStack stack) {
        sky(g, x, y, w, h, alpha);
        float length = attack.length.get().floatValue();
        float t = seconds() % Math.max(1.1f, length + 0.7f);
        float e = t < length ? (float) Math.sin(t / length * Math.PI) : 0;
        float u = Math.min(1.4f, h / 80f);
        person(g, x + w * 0.4f, horizon(y, h) + h * 0.22f, u, 0xFF0000, t < 0.25f ? 0.4f : 0, alpha);
        float lunge = attack.lunge.get().floatValue();
        float size = h * 0.62f;
        float angle = (float) Math.toRadians(attack.tilt.get()) * e;
        held(g, stack, x + w * 0.84f - lunge * size * 0.5f * e, y + h * 1.02f - lunge * size * 0.35f * e, size, angle, 1 + lunge * 0.25f * e);
    }

    private static void gapple(GuiGraphics g, int x, int y, int w, int h, float alpha) {
        sky(g, x, y, w, h, alpha);
        float t = seconds();
        float size = h * 0.42f * Hands.EAT_SIZE.get().floatValue();
        float cx = x + w * 0.62f;
        float cy = y + h * 0.62f;
        float angle = 0;
        switch (Hands.EAT_STYLE.get()) {
            case "Vanilla" -> cy += Math.abs((float) Math.cos(t * 12)) * h * 0.06f;
            case "Bob" -> cy += (float) Math.sin(t * 5) * h * 0.06f * Hands.BOB.get().floatValue();
            case "Spin" -> angle = t * 2.5f;
            case "Close" -> {
                size *= 1.3f;
                cx = x + w * 0.54f;
                cy = y + h * 0.56f;
            }
            default -> {
            }
        }
        // Crumbs falling as you eat.
        Shapes crumbs = Shapes.of(g, fade(0xFFE8B83A, alpha));
        for (int i = 0; i < 6; i++) {
            float q = (t * 1.3f + rand(i, 31)) % 1;
            float sx = cx + (rand(i, 32) - 0.5f) * size * 0.6f;
            float sy = cy + q * h * 0.35f;
            float s = Math.max(0.8f, size * 0.05f) * (1 - q);
            crumbs.rect(sx - s, sy - s, sx + s, sy + s);
        }
        crumbs.submit();
        spun(g, GAPPLE, cx, cy, size, angle);
    }

    // ---- Particles

    private static void particles(GuiGraphics g, int x, int y, int w, int h, float alpha) {
        sky(g, x, y, w, h, alpha);
        float t = seconds();
        int count = Math.round(40 * Particles.DENSITY.get().floatValue());
        float size = Particles.SIZE.get().floatValue() * Math.max(0.8f, h / 70f);
        float opacity = Particles.OPACITY.get().floatValue();
        Polys dots = Polys.of(g, 0);
        for (int i = 0; i < count; i++) {
            float q = (t * 0.45f + rand(i, 41)) % 1;
            float px = x + w / 2f + (rand(i, 42) - 0.5f) * w * 0.8f * (0.3f + q);
            float py = y + h - q * h * 0.95f;
            float s = size * (0.8f + 0.9f * rand(i, 43)) * (1 - q * 0.5f);
            int rgb = i % 3 == 0 ? 0xFFFFFFFF : i % 3 == 1 ? 0xFFCFE6FF : 0xFFFFF0C8;
            dots.color(fade(rgb, opacity * alpha * Math.min(1, (1 - q) * 2))).rect(px - s, py - s, px + s, py + s);
        }
        dots.submit(g);
        label(g, Math.round(Particles.DENSITY.get() * 100) + "% of particles", x + 4, y + 4, alpha);
    }

    /** A five by five pixel heart centred on (cx, cy). */
    private static final int[] HEART = {0b01010, 0b11111, 0b11111, 0b01110, 0b00100};

    private static void heart(Polys p, float cx, float cy, float px) {
        for (int row = 0; row < 5; row++) {
            for (int col = 0; col < 5; col++) {
                if ((HEART[row] >> (4 - col) & 1) == 0) continue;
                float x0 = cx + (col - 2.5f) * px;
                float y0 = cy + (row - 2.5f) * px;
                p.rect(x0, y0, x0 + px, y0 + px);
            }
        }
    }

    private static void star(Polys p, float cx, float cy, float s) {
        p.rect(cx - s, cy - s / 3, cx + s, cy + s / 3).rect(cx - s / 3, cy - s, cx + s / 3, cy - s / 3).rect(cx - s / 3, cy + s / 3, cx + s / 3, cy + s);
    }

    /** What a particle type looks like before any tint. */
    private static int natural(String type, int i) {
        return switch (type) {
            case "Crit" -> 0xFFF4E3B5;
            case "Magic" -> 0xFF63D8FF;
            case "Flame" -> i % 3 == 0 ? 0xFFFFE07A : 0xFFFF8A26;
            case "Soul flame" -> 0xFF4FE6FF;
            case "Hearts" -> 0xFFFF3D55;
            case "Sparks" -> 0xFFFFF6A0;
            case "Totem" -> i % 2 == 0 ? 0xFF8BE04E : 0xFFF2D94A;
            case "Damage" -> 0xFF8A1414;
            case "Glow" -> 0xFF7DFFCF;
            case "Cloud" -> 0xFFEDEDED;
            case "End rod" -> 0xFFFFFFFF;
            default -> i % 3 == 0 ? 0xFFFF5D8F : i % 3 == 1 ? 0xFF5DB8FF : 0xFFFFFFFF;
        };
    }

    private static void hitParticles(GuiGraphics g, int x, int y, int w, int h, float alpha, HitParticles.Burst burst) {
        sky(g, x, y, w, h, alpha);
        float t = seconds() % 1.05f;
        float q = Math.min(1, t / 0.85f);
        float u = Math.min(2, (h - 12) / 36f);
        float cx = x + w / 2f;
        float feet = y + h - 6;
        person(g, cx, feet, u, 0xFF0000, t < 0.2f ? 0.35f : 0, alpha);
        if (q >= 1) return;
        String type = burst.type.get();
        boolean tint = burst.tint.get();
        int tintRgb = 0xFF000000 | burst.color.get();
        int count = burst.amount.get().intValue();
        float reach = (float) (burst.spread.get() * 34 * u + 6 * u);
        float by = feet - 20 * u;
        float s = Math.max(0.8f, u * 1.1f);
        boolean rises = type.equals("Hearts") || type.equals("Glow") || type.equals("End rod") || type.equals("Totem");
        Polys p = Polys.of(g, 0);
        for (int i = 0; i < count; i++) {
            double a = rand(i, 51) * Math.PI * 2;
            float d = reach * (0.35f + 0.65f * rand(i, 52)) * ease(q);
            float px = cx + (float) Math.cos(a) * d;
            float py = by + (float) Math.sin(a) * d * 0.75f + (rises ? -q * 8 * u : q * q * 10 * u);
            int rgb = tint ? Visuals.lerp(tintRgb, 0xFFFFFFFF, 0.25f * rand(i, 53)) : natural(type, i);
            p.color(fade(rgb, alpha * (1 - q * q)));
            switch (type) {
                case "Hearts", "Damage" -> heart(p, px, py, s * 0.55f);
                case "Crit", "Magic", "Firework" -> star(p, px, py, s * 1.1f);
                case "Sparks" -> p.line(px, py, px - (float) Math.cos(a) * s * 3, py - (float) Math.sin(a) * s * 3, Math.max(0.6f, s * 0.5f));
                case "Glow", "Cloud", "End rod" -> {
                    float r = type.equals("Cloud") ? s * 2.2f : s * 1.2f;
                    p.disc(px, py, r, r, 10, fade(rgb, alpha * (1 - q)), fade(rgb, 0));
                }
                default -> p.rect(px - s * 0.8f, py - s * 0.8f, px + s * 0.8f, py + s * 0.8f);
            }
        }
        p.submit(g);
    }

    private static void crits(GuiGraphics g, int x, int y, int w, int h, float alpha) {
        sky(g, x, y, w, h, alpha);
        float t = seconds() % 1.1f;
        float q = Math.min(1, t / 0.8f);
        float u = Math.min(2, (h - 12) / 36f);
        float cx = x + w / 2f;
        float feet = y + h - 6;
        person(g, cx, feet, u, 0xFF0000, t < 0.2f ? 0.35f : 0, alpha);
        if (q >= 1) return;
        int count = Math.round(12 * Particles.CRIT_AMOUNT.get().floatValue());
        float s = Math.max(0.8f, u * 1.2f) * Particles.CRIT_SIZE.get().floatValue();
        int crit = 0xFF000000 | Particles.CRIT_COLOR.get();
        int magic = 0xFF000000 | Particles.MAGIC_COLOR.get();
        Polys p = Polys.of(g, 0);
        for (int i = 0; i < count; i++) {
            double a = rand(i, 61) * Math.PI * 2;
            float d = (10 + 14 * rand(i, 62)) * u * ease(q);
            float px = cx + (float) Math.cos(a) * d;
            float py = feet - 24 * u + (float) Math.sin(a) * d * 0.7f + q * q * 8 * u;
            p.color(fade(i % 3 == 2 ? magic : crit, alpha * (1 - q * q)));
            star(p, px, py, s);
        }
        p.submit(g);
    }

    private static void shieldBreak(GuiGraphics g, int x, int y, int w, int h, float alpha) {
        sky(g, x, y, w, h, alpha);
        float t = seconds() % 1.85f;
        float u = Math.min(2, (h - 14) / 38f);
        float cx = x + w * 0.45f;
        float feet = y + h - 6;
        boolean broken = t >= 0.6f;
        float q = broken ? Math.min(1, (t - 0.6f) / 1.1f) : 0;
        person(g, cx, feet, u, 0, 0, alpha);
        float sx0 = cx + 3 * u;
        float sx1 = cx + 12 * u;
        float sy0 = feet - 27 * u;
        float sy1 = feet - 9 * u;
        if (!broken) {
            Shapes.of(g, fade(0xFFA7A7A7, alpha)).rect(sx0, sy0, sx1, sy1).submit();
            Shapes.of(g, fade(0xFF8A5A2B, alpha)).rect(sx0 + u, sy0 + u, sx1 - u, sy1 - u).submit();
            return;
        }
        int rgb = ShieldBreak.COLOR.get();
        float scx = (sx0 + sx1) / 2;
        float scy = (sy0 + sy1) / 2;
        Polys fx = Polys.of(g, 0);
        if (ShieldBreak.RING.get()) {
            float rx = 22 * u * ease(q);
            fx.color(Visuals.argb(rgb, (1 - q) * alpha)).ring(cx, feet, rx, rx * 0.28f, Math.max(0.7f, 2 * u * (1 - q)), 36);
            float r = 15 * u * ease(q * 1.3f);
            fx.color(Visuals.argb(rgb, 0.75 * (1 - q) * alpha)).ring(cx, feet - 16 * u, r * 0.55f, r, Math.max(0.6f, 1.5f * u * (1 - q)), 36);
        }
        if (ShieldBreak.BURST.get()) {
            int count = Math.min(50, ShieldBreak.AMOUNT.get().intValue());
            for (int i = 0; i < count; i++) {
                double a = -Math.PI * (0.05 + 0.9 * rand(i, 71)) + (rand(i, 72) - 0.5) * 0.6;
                float v = (12 + 20 * rand(i, 73)) * u;
                float px = scx + (float) Math.cos(a) * v * q;
                float py = scy + (float) Math.sin(a) * v * q + q * q * 22 * u;
                float s = Math.max(0.7f, u * (0.6f + 0.8f * rand(i, 74))) * (1 - q * 0.6f);
                int c = i % 3 == 0 ? 0xFF8A5A2B : 0xFF000000 | rgb;
                fx.color(fade(c, alpha * (1 - q))).rect(px - s, py - s, px + s, py + s);
            }
        }
        fx.submit(g);
        if (ShieldBreak.WORD.get()) {
            String word = "Shield broken";
            int ty = Math.round(feet - 34 * u - Text.CAP - q * 6 * u);
            Text.draw(g, word, Math.round(cx - Text.width(word) / 2f), ty, Visuals.argb(rgb, (1 - q * q) * alpha));
        }
        if (ShieldBreak.FLASH.get() && q < 0.22f) flash(g, x, y, w, h, rgb, 0.3f * (1 - q / 0.22f) * alpha);
    }

    // ---- Potions and thrown things

    private static void splash(GuiGraphics g, int x, int y, int w, int h, float alpha) {
        sky(g, x, y, w, h, alpha);
        float t = seconds() % 2.05f;
        float ix = x + w * 0.56f;
        float iy = horizon(y, h) + h * 0.2f;
        float size = Math.max(8, h * 0.2f);
        if (t < 0.55f) {
            float f = t / 0.55f;
            float px = x + w * 0.1f + (ix - x - w * 0.1f) * f;
            float py = y + h * 0.15f + (iy - y - h * 0.15f) * f - (float) Math.sin(f * Math.PI) * h * 0.25f;
            spun(g, SPLASH, px, py, size, f * 6);
            return;
        }
        float q = Math.min(1, (t - 0.55f) / 1.4f);
        boolean own = Particles.SPLASH_OWN_COLOR.get();
        int rgb = own ? HEALING : Particles.SPLASH_COLOR.get();
        int count = Math.round(44 * Particles.SPLASH_DENSITY.get().floatValue());
        float s = Math.max(0.7f, h / 70f) * Particles.SPLASH_SIZE.get().floatValue();
        float opacity = Particles.SPLASH_OPACITY.get().floatValue();
        float reach = Math.min(w * 0.45f, h * 0.9f);
        Polys p = Polys.of(g, 0);
        for (int i = 0; i < count; i++) {
            double a = rand(i, 81) * Math.PI * 2;
            float d = reach * (0.2f + 0.8f * rand(i, 82)) * ease(q);
            float px = ix + (float) Math.cos(a) * d;
            float py = iy + (float) Math.sin(a) * d * 0.3f - (float) Math.sin(q * Math.PI) * h * 0.12f * rand(i, 83);
            int c = Visuals.lerp(0xFF000000 | rgb, 0xFFFFFFFF, 0.35f * rand(i, 84));
            p.color(fade(c, opacity * alpha * (1 - q * q))).rect(px - s, py - s, px + s, py + s);
        }
        p.submit(g);
    }

    private static void potionImpact(GuiGraphics g, int x, int y, int w, int h, float alpha) {
        sky(g, x, y, w, h, alpha);
        float length = PotionImpact.LENGTH.get().floatValue();
        float t = seconds() % (length + 0.4f) / length;
        float cx = x + w / 2f;
        float ground = horizon(y, h) + h * 0.2f;
        if (t >= 1) {
            // The next potion on its way down.
            float f = (t - 1) * length / 0.4f;
            spun(g, SPLASH, cx, y + h * 0.08f + (ground - y - h * 0.08f) * f, Math.max(8, h * 0.18f), f * 4);
            return;
        }
        int rgb = PotionImpact.OWN_COLOR.get() ? HEALING : PotionImpact.COLOR.get();
        float reach = Math.min(w * 0.4f, h * 0.75f) * PotionImpact.SIZE.get().floatValue();
        float fade = 1 - t;
        Polys fx = Polys.of(g, 0);
        if (PotionImpact.RING.get()) {
            float rx = reach * ease(t);
            fx.color(Visuals.argb(rgb, fade * alpha)).ring(cx, ground, rx, rx * 0.28f, Math.max(0.8f, 2.4f * fade), 40);
        }
        if (PotionImpact.DOME.get()) {
            for (int i = 0; i < 4; i++) {
                float f = i / 3.6f;
                float up = f * reach * 0.85f * ease(t);
                float r = reach * 0.8f * (float) Math.sqrt(1 - f * f) * ease(t);
                fx.color(Visuals.argb(rgb, fade * (1 - i * 0.18f) * alpha)).ring(cx, ground - up, r, r * 0.28f, Math.max(0.6f, 1.6f * fade), 32);
            }
            float r = reach * 0.6f * ease(t * 0.9f);
            fx.color(Visuals.argb(rgb, 0.6 * fade * alpha)).ring(cx, ground - reach * 0.32f, r, r, Math.max(0.6f, 1.6f * fade), 36);
        }
        if (PotionImpact.TINT_SELF.get() && !small && t < 0.4f) {
            // The tint at your screen's edges when one lands on you.
            float a = 0.45f * (1 - t / 0.4f) * alpha;
            int edge = Visuals.argb(rgb, a);
            int clear = Visuals.argb(rgb, 0);
            float band = Math.min(w, h) * 0.22f;
            fx.quad(x, y, edge, x, y + h, edge, x + band, y + h, clear, x + band, y, clear);
            fx.quad(x + w - band, y, clear, x + w - band, y + h, clear, x + w, y + h, edge, x + w, y, edge);
            fx.quad(x, y, edge, x, y + band, clear, x + w, y + band, clear, x + w, y, edge);
            fx.quad(x, y + h - band, clear, x, y + h, edge, x + w, y + h, edge, x + w, y + h - band, clear);
        }
        fx.submit(g);
    }

    private static void projectiles(GuiGraphics g, int x, int y, int w, int h, float alpha) {
        sky(g, x, y, w, h, alpha);
        float t = seconds() % 2.2f;
        float f = Math.min(1, t / 1.4f);
        float sx = x + w * 0.1f;
        float sy = y + h * 0.66f;
        float ex = x + w * 0.84f;
        float ey = horizon(y, h) + h * 0.24f;
        float apex = h * 0.5f;
        int rgb = ProjectileFx.COLOR.get();
        int land = ProjectileFx.LANDING_COLOR.get();
        float size = Math.max(7, h * 0.13f);
        Polys fx = Polys.of(g, 0);
        if (ProjectileFx.LANDING.get() || ProjectileFx.ALL_LANDINGS.get()) {
            // Where it's going, dotted ahead of it, and the marker where it comes down.
            for (int i = 0; i <= 24; i++) {
                float k = i / 24f;
                if (k < f) continue;
                float px = sx + (ex - sx) * k;
                float py = sy + (ey - sy) * k - (float) Math.sin(k * Math.PI) * apex;
                float d = Math.max(0.6f, h / 110f);
                fx.color(Visuals.argb(land, 0.55 * alpha)).rect(px - d, py - d, px + d, py + d);
            }
            float pulse = 0.75f + 0.25f * (float) Math.sin(seconds() * 6);
            float r = size * 0.9f;
            fx.disc(ex, ey, r, r * 0.3f, 24, Visuals.argb(land, 0.4 * alpha * pulse), Visuals.argb(land, 0.1 * alpha));
            fx.color(Visuals.argb(land, alpha)).ring(ex, ey, r, r * 0.3f, Math.max(0.7f, h / 90f), 28);
            fx.line(ex, ey, ex, ey - size * 2.2f, Math.max(0.7f, h / 100f), Visuals.argb(land, 0.9 * alpha), Visuals.argb(land, 0));
        }
        float px = sx + (ex - sx) * f;
        float py = sy + (ey - sy) * f - (float) Math.sin(f * Math.PI) * apex;
        if (f < 1 && ProjectileFx.BOXES.get()) {
            float b = size * 0.7f;
            float lw = Math.max(0.7f, h / 100f);
            fx.color(Visuals.argb(rgb, 0.2 * alpha)).rect(px - b, py - b, px + b, py + b);
            fx.color(Visuals.argb(rgb, alpha)).rect(px - b, py - b, px + b, py - b + lw).rect(px - b, py + b - lw, px + b, py + b)
                .rect(px - b, py - b, px - b + lw, py + b).rect(px + b - lw, py - b, px + b, py + b);
        }
        fx.submit(g);
        if (f < 1) item(g, PEARL, px - size / 2, py - size / 2, size);
    }

    private static void hurtCamera(GuiGraphics g, int x, int y, int w, int h, float alpha) {
        float t = seconds() % 1.4f;
        // The game's hurt bob runs half a second; f is how much of it is left.
        float f = Math.max(0, 1 - t / 0.5f);
        float k = HurtCamera.INTENSITY.get().floatValue();
        float angle = 0;
        float dx = 0;
        float dy = 0;
        if (HurtCamera.SHAKE.get() && f > 0) {
            switch (HurtCamera.STYLE.get()) {
                case "Bounce" -> dy = (float) Math.sin(f * Math.PI) * h * 0.08f * k;
                case "Shake" -> {
                    dx = (rand((int) (t * 40), 91) - 0.5f) * h * 0.12f * k * f;
                    dy = (rand((int) (t * 40), 92) - 0.5f) * h * 0.12f * k * f;
                }
                default -> angle = (float) Math.toRadians(-Math.sin(Math.pow(f, 4) * Math.PI) * 14 * k);
            }
        }
        float cx = x + w / 2f;
        float cy = y + h / 2f;
        g.pose().pushMatrix();
        g.pose().translate(cx + dx, cy + dy);
        g.pose().rotate(angle);
        g.pose().scale(1.3f);
        g.pose().translate(-cx, -cy);
        sky(g, x, y, w, h, alpha);
        // Something to see it move against: a block ahead and the hills behind.
        float horizon = horizon(y, h);
        Shapes.of(g, fade(0xFF3E5E2E, alpha)).rect(x, horizon - h * 0.06f, x + w * 0.3f, horizon).rect(x + w * 0.55f, horizon - h * 0.09f, x + w, horizon).submit();
        float bs = h * 0.22f;
        Shapes.of(g, fade(0xFF7C7C7C, alpha)).rect(cx - bs / 2, horizon - bs * 0.2f, cx + bs / 2, horizon + bs * 0.8f).submit();
        Shapes.of(g, fade(0xFF8E8E8E, alpha)).rect(cx - bs / 2, horizon - bs * 0.2f, cx + bs / 2, horizon - bs * 0.05f).submit();
        g.pose().popMatrix();
        // The crosshair and hotbar stay put; only the world moves.
        Shapes.of(g, fade(0xFFFFFFFF, alpha)).rect(cx - 3, cy - 0.5f, cx + 3, cy + 0.5f).rect(cx - 0.5f, cy - 3, cx + 0.5f, cy + 3).submit();
        float hw = Math.min(w * 0.6f, h * 1.1f);
        Shapes.of(g, fade(0xC0101217, alpha)).rect(cx - hw / 2, y + h - h * 0.12f, cx + hw / 2, y + h - 2).submit();
        if (!HurtCamera.SHAKE.get()) label(g, "No shake", x + 4, y + 4, alpha);
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

    // ---- World

    /** How strong glows are in the pictures: the Glow group's strength, as in the world. */
    private static float glowStrength() {
        return Math.min(2.5f, Glow.strength());
    }

    /** {@code n} of the same width, for {@link Polys#glowPath} and {@link Polys#linePath}. */
    private static float[] same(int n, float value) {
        float[] out = new float[n];
        Arrays.fill(out, value);
        return out;
    }

    /** {@code n} of the same colour. */
    private static int[] same(int n, int argb) {
        int[] out = new int[n];
        Arrays.fill(out, argb);
        return out;
    }

    private static void glowPreview(GuiGraphics g, int x, int y, int w, int h, float alpha) {
        night(g, x, y, w, h, alpha);
        float t = seconds();
        float u = Math.min(w, h) / 60f;
        float strength = glowStrength();
        float size = Glow.size();
        boolean cores = Glow.cores();
        Polys light = Polys.of(g, 0);
        Polys lines = Polys.of(g, 0);
        // A neon wave, a ring on the ground and a row of lights, all as bright and as wide as set.
        int wave = 0x4DE1FF;
        int ring = 0xFF4DD2;
        int lamp = 0xFFD84D;
        int steps = 31;
        float[] xs = new float[steps];
        float[] ys = new float[steps];
        float[] reach = new float[steps];
        for (int i = 0; i < steps; i++) {
            float q = i / (float) (steps - 1);
            xs[i] = x + w * (0.08f + 0.84f * q);
            ys[i] = y + h * 0.32f + (float) Math.sin(q * 7 + t * 2.4f) * h * 0.11f;
            // Rounded off at the ends, not cut square.
            reach[i] = 4.5f * u * size * (float) Math.sqrt(Math.min(1, (Math.min(i, steps - 1 - i) + 0.4f) / 3));
        }
        light.glowPath(xs, ys, reach, same(steps, Visuals.argb(wave, 0.55 * strength * alpha)), steps, false);
        lines.linePath(xs, ys, same(steps, 1.5f * u), same(steps, Visuals.argb(wave, alpha)), steps, false);
        if (cores) lines.linePath(xs, ys, same(steps, 0.6f * u), same(steps, Polys.whiter(Visuals.argb(wave, alpha))), steps, false);
        float rcx = x + w * 0.3f;
        float rcy = y + h * 0.74f;
        float rx = w * (0.15f + 0.015f * (float) Math.sin(t * 3));
        float ry = rx * 0.32f;
        int segments = 36;
        float[] rxs = new float[segments];
        float[] rys = new float[segments];
        for (int i = 0; i < segments; i++) {
            double a = Math.PI * 2 * i / segments;
            rxs[i] = rcx + (float) Math.cos(a) * rx;
            rys[i] = rcy + (float) Math.sin(a) * ry;
        }
        light.glowPath(rxs, rys, same(segments, 4.5f * u * size), same(segments, Visuals.argb(ring, 0.55 * strength * alpha)), segments, true);
        lines.color(Visuals.argb(ring, alpha)).ring(rcx, rcy, rx, ry, 1.5f * u, segments);
        if (cores) lines.color(Polys.whiter(Visuals.argb(ring, alpha))).ring(rcx, rcy, rx, ry, 0.6f * u, segments);
        for (int i = 0; i < 3; i++) {
            float pulse = 0.75f + 0.25f * (float) Math.sin(t * 3 + i * 2);
            light.glow(x + w * (0.62f + 0.12f * i), rcy, 7 * u * size * pulse, Visuals.argb(lamp, 0.8 * strength * alpha * pulse));
        }
        light.submit(g);
        lines.submit(g);
        label(g, "Strength " + Particles.times(Glow.strength()), x + 4, y + 4, alpha);
    }

    private static void timeChanger(GuiGraphics g, int x, int y, int w, int h, float alpha) {
        float hours = TimeChanger.TIME.get().floatValue();
        float t = seconds();
        // The sun is up at noon, down at midnight and on the horizon at six, as in the game.
        double angle = (hours - 6) / 24 * Math.PI * 2;
        float height = (float) Math.sin(angle);
        float day = Math.max(0, Math.min(1, height * 3 + 0.35f));
        float dusk = Math.max(0, 1 - Math.abs(height) * 4);
        int top = Visuals.lerp(NIGHT_TOP, SKY_TOP, day);
        int bottom = Visuals.lerp(Visuals.lerp(NIGHT_BOTTOM, SKY_BOTTOM, day), 0xFFFF8A4D, dusk * 0.75f);
        int horizon = y + h * 3 / 4;
        g.fillGradient(x, y, x + w, horizon, fade(top, alpha), fade(bottom, alpha));
        Polys sky = Polys.of(g, 0);
        float night = 1 - day;
        if (night > 0.05f) {
            float s = Math.max(0.7f, h / 100f);
            for (int i = 0; i < 26; i++) {
                float sx = x + rand(i, 71) * w;
                float sy = y + rand(i, 72) * (horizon - y) * 0.85f;
                float twinkle = 0.55f + 0.45f * (float) Math.sin(t * 2.3f + i * 1.3f);
                sky.color(fade(0xFFFFFFFF, alpha * night * twinkle * 0.85f)).rect(sx - s / 2, sy - s / 2, sx + s / 2, sy + s / 2);
            }
        }
        // The sun and moon opposite each other on their arc, square the way the game draws them.
        float cx = x + w / 2f;
        float r = Math.min(w * 0.42f, (horizon - y) * 0.85f);
        float s = Math.max(3, h / 14f);
        float sunX = cx - (float) Math.cos(angle) * r;
        float sunY = horizon - height * r;
        float moonX = cx + (float) Math.cos(angle) * r;
        float moonY = horizon + height * r;
        if (sunY < horizon + s) {
            sky.glow(sunX, sunY, s * 3.4f, Visuals.argb(0xFFD27A, 0.55 * alpha));
            sky.color(fade(0xFFFFF2B0, alpha)).rect(sunX - s, sunY - s, sunX + s, sunY + s);
        }
        if (moonY < horizon + s) {
            sky.glow(moonX, moonY, s * 2.6f, Visuals.argb(0xBFD4FF, 0.35 * alpha));
            sky.color(fade(0xFFE6ECF5, alpha)).rect(moonX - s * 0.8f, moonY - s * 0.8f, moonX + s * 0.8f, moonY + s * 0.8f);
        }
        sky.submit(g);
        // Hills in front, lit by the daylight.
        Polys land = Polys.of(g, fade(shade(GROUND, 0.22f + 0.78f * day), alpha));
        int n = 12;
        for (int i = 0; i < n; i++) {
            float x0 = x + w * i / (float) n;
            float x1 = x + w * (i + 1) / (float) n;
            float y0 = horizon - (float) (Math.sin(i * 0.9) * 0.5 + 0.5) * h * 0.08f;
            float y1 = horizon - (float) (Math.sin((i + 1) * 0.9) * 0.5 + 0.5) * h * 0.08f;
            land.quad(x0, y0, x1, y1, x1, y + h, x0, y + h);
        }
        land.submit(g);
        tag(g, TimeChanger.clock(hours), x + 5, y + 5, alpha);
    }

    private static void skyColors(GuiGraphics g, int x, int y, int w, int h, float alpha) {
        float t = seconds();
        int horizon = y + h * 3 / 4;
        // The game's colours left of the line, yours right of it; the line sweeps across.
        float split = x + w * (0.5f + 0.38f * (float) Math.sin(t * 0.9f));
        int sx = Math.round(split);
        int white = 0xFFFFFFFF;
        int top = SkyColors.SKY.get() ? 0xFF000000 | SkyColors.tint(SKY_TOP, SkyColors.SKY_COLOR.get()) : SKY_TOP;
        int fog = SkyColors.FOG.get() ? 0xFF000000 | SkyColors.tint(SKY_BOTTOM, SkyColors.FOG_COLOR.get()) : SKY_BOTTOM;
        int clouds = SkyColors.CLOUDS.get() ? 0xFF000000 | SkyColors.tint(white, SkyColors.CLOUD_COLOR.get()) : white;
        if (sx > x) g.fillGradient(x, y, sx, horizon, fade(SKY_TOP, alpha), fade(SKY_BOTTOM, alpha));
        if (sx < x + w) g.fillGradient(sx, y, x + w, horizon, fade(top, alpha), fade(fog, alpha));
        g.fill(x, horizon, x + w, y + h, fade(GROUND, alpha));
        Polys p = Polys.of(g, 0);
        for (int i = 0; i < 5; i++) {
            float cw = w * (0.18f + 0.1f * rand(i, 81));
            float cxp = x - cw + (rand(i, 82) * (w + cw) + t * w * 0.04f * (1 + rand(i, 83))) % (w + cw);
            float cy = y + h * (0.1f + 0.38f * rand(i, 84));
            float ch = Math.max(2, h * 0.06f);
            cloud(p, cxp, cy, cxp + cw, cy + ch, split, white, clouds, alpha);
            cloud(p, cxp + cw * 0.2f, cy - ch * 0.6f, cxp + cw * 0.7f, cy, split, white, clouds, alpha);
        }
        p.color(fade(0xFFFFFFFF, alpha * 0.85f)).rect(split - 0.5f, y, split + 0.5f, horizon);
        p.submit(g);
        tag(g, "Game", x + 5, y + 5, alpha);
        tag(g, "Yours", x + w - 5 - Text.width("Yours"), y + 5, alpha);
    }

    /** A block of cloud, cut at the line so each side has its own colour. */
    private static void cloud(Polys p, float x0, float y0, float x1, float y1, float split, int game, int yours, float alpha) {
        if (x0 < split) p.color(fade(game, alpha * 0.9f)).rect(x0, y0, Math.min(x1, split), y1);
        if (x1 > split) p.color(fade(yours, alpha * 0.9f)).rect(Math.max(x0, split), y0, x1, y1);
    }

    private static final List<ItemStack> DROPS = List.of(new ItemStack(Items.COBBLESTONE), new ItemStack(Items.GOLDEN_APPLE),
        new ItemStack(Items.DIAMOND), new ItemStack(Items.ENCHANTED_GOLDEN_APPLE));

    private static void itemGlow(GuiGraphics g, int x, int y, int w, int h, float alpha) {
        night(g, x, y, w, h, alpha);
        float floor = y + h * 0.84f;
        g.fill(x, Math.round(floor), x + w, y + h, fade(0xFF1B261D, alpha));
        float t = seconds();
        float size = Math.min(w / 7f, h / 4f);
        float strength = glowStrength();
        String beams = ItemGlow.BEAMS.get();
        float beamTop = floor - (floor - y) * Math.min(1, ItemGlow.BEAM_HEIGHT.get().floatValue() / 8f);
        Polys light = Polys.of(g, 0);
        float[] at = new float[DROPS.size() * 2];
        for (int i = 0; i < DROPS.size(); i++) {
            int tier = ItemGlow.tier(DROPS.get(i));
            int rgb = ItemGlow.COLORS.is("One color") ? ItemGlow.COLOR.get() : ItemGlow.RARITY[tier];
            float cx = x + w * (0.15f + 0.233f * i);
            float cy = floor - size * 0.6f + (float) Math.sin(t * 2.2f + i * 1.7f) * size * 0.08f;
            at[i * 2] = cx;
            at[i * 2 + 1] = cy;
            if (beams.equals("All") || beams.equals("Good items") && tier > 0) {
                light.glowLine(cx, floor, cx, beamTop, size * 0.4f, Visuals.argb(rgb, 0.5 * strength * alpha), Visuals.argb(rgb, 0));
                light.glowLine(cx, floor, cx, beamTop, size * 0.12f, Visuals.argb(rgb, 0.9 * alpha), Visuals.argb(rgb, 0));
            }
            if (ItemGlow.LIGHT.get()) {
                light.glow(cx, floor - size * 0.15f, size * (0.85f + 0.15f * tier), Visuals.argb(rgb, (0.32 + 0.08 * tier) * 1.3 * strength * alpha));
            }
            if (ItemGlow.OUTLINE.get()) light.glow(cx, cy, size * 0.72f, Visuals.argb(rgb, 0.55 * alpha));
        }
        light.submit(g);
        for (int i = 0; i < DROPS.size(); i++) {
            item(g, DROPS.get(i), at[i * 2] - size / 2, at[i * 2 + 1] - size / 2, size);
        }
        if (ItemGlow.NAMES.get() && !small) {
            String name = DROPS.get(2).getHoverName().getString();
            Text.draw(g, name, Math.round(at[4] - Text.width(name) / 2f), Math.round(at[5] - size * 0.6f - Text.CAP - 3), fade(TEXT, alpha));
        }
    }

    private static void breakProgress(GuiGraphics g, int x, int y, int w, int h, float alpha) {
        sky(g, x, y, w, h, alpha);
        float loop = seconds() % 2.4f;
        float mining = 1.7f;
        float p = Math.min(1, loop / mining);
        boolean gone = loop > mining;
        float hw = Math.min(w * 0.2f, h * 0.27f);
        float hh = hw;
        float bx = x + w / 2f;
        float by = y + h * 0.5f - hh / 2;
        float middle = by + hh / 2;
        int rgb = BreakProgress.color(p);
        float strength = glowStrength();
        Polys poly = Polys.of(g, 0);
        if (!gone) {
            block(poly, bx, by, hw, hh, fade(0xFF8E8E8E, alpha), fade(0xFF686868, alpha), fade(0xFF7A7A7A, alpha));
            // The game's cracks, more of them as it goes.
            poly.color(fade(0xFF2E2E2E, alpha * 0.8f));
            for (int i = 0; i < Math.round(p * 7); i++) {
                float cx = bx + (rand(i, 61) - 0.5f) * hw * 1.2f;
                float cy = by + hw * 0.1f + rand(i, 62) * hh;
                poly.line(cx, cy, cx + (rand(i, 63) - 0.5f) * hw * 0.5f, cy + rand(i, 64) * hh * 0.3f, Math.max(0.7f, hw / 22));
            }
            float bhw = hw;
            float bhh = hh;
            float bty;
            switch (BreakProgress.STYLE.get()) {
                case "Fill up" -> {
                    bhh = hh * Math.max(0.04f, p);
                    bty = by + hh - bhh;
                }
                case "Shrink" -> {
                    float k = Math.max(0.06f, 1 - p);
                    bhw = hw * k;
                    bhh = hh * k;
                    bty = middle - bhh / 2;
                }
                default -> {
                    float k = Math.max(0.06f, p);
                    bhw = hw * k;
                    bhh = hh * k;
                    bty = middle - bhh / 2;
                }
            }
            double fill = BreakProgress.FILL.get() * alpha;
            block(poly, bx, bty, bhw, bhh, Visuals.argb(rgb, fill * 1.15), Visuals.argb(rgb, fill * 0.8), Visuals.argb(rgb, fill));
            if (BreakProgress.GLOW.get()) {
                blockGlow(poly, bx, bty, bhw, bhh, Math.max(3, hw * 0.16f), Visuals.argb(rgb, 0.55 * strength * alpha));
                poly.glow(bx, middle, hw * (0.6f + 0.6f * p), Visuals.argb(rgb, (0.15 + 0.3 * p) * strength * alpha));
            }
            blockEdges(poly.color(Visuals.argb(rgb, 0.95 * alpha)), bx, bty, bhw, bhh, Math.max(1, hw / 18), true);
        } else if (BreakProgress.BURST.get()) {
            float q = (loop - mining) / (2.4f - mining);
            int done = BreakProgress.DONE.get();
            poly.glow(bx, middle, hw * (1.1f + 1.3f * q), Visuals.argb(done, (1 - q) * (1 - q) * 0.9 * strength * alpha));
            poly.color(Visuals.argb(done, (1 - q) * alpha))
                .ring(bx, by + hw / 2 + hh, hw * (0.6f + 1.4f * q), hw * (0.3f + 0.7f * q), Math.max(1, hw / 14), 32);
        }
        poly.submit(g);
        if (BreakProgress.PERCENT.get() && !gone) label(g, Math.round(p * 100) + "%", x + 4, y + 4, alpha);
    }

    /** Glow along the visible edges of {@link #block}. */
    private static void blockGlow(Polys p, float x, float y, float hw, float hh, float reach, int argb) {
        float[][] edges = {{x - hw, y, x, y - hw / 2}, {x, y - hw / 2, x + hw, y}, {x + hw, y, x, y + hw / 2}, {x, y + hw / 2, x - hw, y},
            {x - hw, y, x - hw, y + hh}, {x, y + hw / 2, x, y + hw / 2 + hh}, {x + hw, y, x + hw, y + hh},
            {x - hw, y + hh, x, y + hw / 2 + hh}, {x, y + hw / 2 + hh, x + hw, y + hh}};
        for (float[] e : edges) p.glowLine(e[0], e[1], e[2], e[3], reach, argb, argb);
    }

    private static void halo(GuiGraphics g, int x, int y, int w, int h, float alpha) {
        g.fillGradient(x, y, x + w, y + h, fade(0xFF15122A, alpha), fade(0xFF3B2B4D, alpha));
        float floor = y + h * 0.92f;
        g.fill(x, Math.round(floor), x + w, y + h, fade(0xFF251E33, alpha));
        float u = h / 50f;
        float t = seconds();
        boolean others = Halo.OTHERS.get();
        float cx = x + w * (others ? 0.36f : 0.5f);
        float ox = x + w * 0.76f;
        float ou = u * 0.72f;
        person(g, cx, floor, u, 0, 0, alpha);
        if (others) person(g, ox, floor - u * 4, ou, 0x8A5A3C, 0.6f, alpha);
        Polys light = Polys.of(g, 0);
        Polys core = Polys.of(g, 0);
        halo(light, core, cx, floor - 32 * u, u, t, alpha);
        if (others) halo(light, core, ox, floor - u * 4 - 32 * ou, ou, t + 1.3f, alpha * 0.9f);
        light.submit(g);
        core.submit(g);
    }

    /** A halo or hat over a {@link #person}'s head, its top at (cx, head). */
    private static void halo(Polys light, Polys core, float cx, float head, float u, float t, float alpha) {
        float size = Halo.SIZE.get().floatValue();
        float strength = glowStrength();
        int segments = 32;
        boolean hat = Halo.STYLE.is("Hat");
        float rx = (hat ? 11 : 6) * u * size;
        float ry = rx * 0.3f;
        float cy = hat ? head - u : head - (Halo.HEIGHT.get().floatValue() * 16 + 3) * u + (float) Math.sin(t * 2.5f) * 0.6f * u;
        float tipY = cy - 7 * u * size;
        float[] xs = new float[segments];
        float[] ys = new float[segments];
        int[] glow = new int[segments];
        int[] bright = new int[segments];
        for (int i = 0; i < segments; i++) {
            double a = t * 0.8 + Math.PI * 2 * i / segments;
            xs[i] = cx + (float) Math.cos(a) * rx;
            ys[i] = cy + (float) Math.sin(a) * ry;
            int rgb = Halo.color(i, segments);
            glow[i] = Visuals.argb(rgb, 0.5 * strength * alpha);
            bright[i] = Polys.whiter(Visuals.argb(rgb, alpha));
            if (hat && i % (segments / 8) == 0) haloPiece(light, core, xs[i], ys[i], cx, tipY, rgb, u * size * 0.7f, strength, alpha);
        }
        light.glowPath(xs, ys, same(segments, 2.8f * u * size), glow, segments, true);
        core.linePath(xs, ys, same(segments, Math.max(0.8f, 0.9f * u * size)), bright, segments, true);
        if (hat) light.glow(cx, tipY, 3 * u * size, Visuals.argb(Halo.color(0, 1), 0.6 * strength * alpha));
    }

    private static void haloPiece(Polys light, Polys core, float ax, float ay, float bx, float by, int rgb, float u, float strength, float alpha) {
        int glow = Visuals.argb(rgb, 0.5 * strength * alpha);
        light.glowLine(ax, ay, bx, by, 2.8f * u, glow, glow);
        int c = Polys.whiter(Visuals.argb(rgb, alpha));
        core.line(ax, ay, bx, by, Math.max(0.8f, 0.9f * u), c, c);
    }

    private static void fireflies(GuiGraphics g, int x, int y, int w, int h, float alpha) {
        night(g, x, y, w, h, alpha);
        float floor = y + h * 0.86f;
        Polys grass = Polys.of(g, fade(0xFF0D1810, alpha)).rect(x, floor, x + w, y + h);
        for (int i = 0; i < w / 3; i++) {
            float gx = x + i * 3 + rand(i, 51) * 2;
            float gh = h * (0.03f + 0.06f * rand(i, 52));
            grass.tri(gx - 1.2f, floor + 0.5f, gx + 1.2f, floor + 0.5f, gx + (rand(i, 53) - 0.5f) * 2, floor - gh);
        }
        grass.submit(g);
        float t = seconds();
        int n = Math.round(Math.min(40, 6 + Fireflies.AMOUNT.get().floatValue() * 0.35f));
        float size = Fireflies.SIZE.get().floatValue() * Math.max(2.5f, h / 28f);
        float spread = 0.35f + 0.6f * Fireflies.SPREAD.get().floatValue() / 24f;
        int rgb = Fireflies.COLOR.get();
        float strength = glowStrength();
        Polys lights = Polys.of(g, 0);
        for (int i = 0; i < n; i++) {
            float fx = x + w * (0.5f + (rand(i, 91) - 0.5f) * spread) + (float) Math.sin(t * (0.3f + rand(i, 92) * 0.5f) + i) * w * 0.05f;
            float fy = y + h * (0.15f + 0.62f * rand(i, 93)) + (float) Math.cos(t * (0.4f + rand(i, 94) * 0.5f) + i * 2) * h * 0.05f;
            float blink = (float) Math.sin(t * (1.2f + rand(i, 95) * 1.6f) + rand(i, 96) * 100);
            float lit = 0.25f + 0.75f * Math.max(0, blink * 0.8f + 0.4f);
            lights.glow(fx, fy, size, Visuals.argb(rgb, Math.min(1, 0.95 * lit * strength) * alpha));
        }
        lights.submit(g);
    }
}
