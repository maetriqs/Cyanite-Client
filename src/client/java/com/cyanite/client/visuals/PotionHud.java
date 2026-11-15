package com.cyanite.client.visuals;

import com.cyanite.client.modules.render.Cinematic;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.SliderSetting;
import com.google.common.collect.Ordering;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectUtil;

import java.util.ArrayList;
import java.util.List;

/** Your potion effects in the corner: their size, which way they line up, where, and whether names and timers show. */
public final class PotionHud {
    public static final Group GROUP = new Group("potion_hud", "Potion Effects",
        "Size and layout of your effect icons, with names and time left.", "potion effects status icons hud timer scale layout");
    static final SliderSetting SCALE = GROUP.add(new SliderSetting("Scale", 1, 0.5, 2.5, 0.05).format(Particles::times));
    static final ModeSetting LAYOUT = GROUP.add(new ModeSetting("Layout", "Column", List.of("Column", "Row", "Two rows")));
    static final ModeSetting CORNER = GROUP.add(new ModeSetting("Corner", "Top right", List.of("Top right", "Top left", "Bottom right", "Bottom left")));
    static final BoolSetting NAMES = GROUP.add(new BoolSetting("Names", true));
    static final BoolSetting TIMERS = GROUP.add(new BoolSetting("Time left", true));
    static final BoolSetting BACKGROUND = GROUP.add(new BoolSetting("Icon background", true));
    static final BoolSetting BLINK = GROUP.add(new BoolSetting("Blink when ending", true));
    static final ColorSetting TEXT = GROUP.add(new ColorSetting("Text color", 0xFFFFFF).full());
    static final ColorSetting TIME = GROUP.add(new ColorSetting("Time color", 0x9FB4C8).full());

    private static final Identifier BACK = Identifier.withDefaultNamespace("hud/effect_background");
    private static final Identifier BACK_AMBIENT = Identifier.withDefaultNamespace("hud/effect_background_ambient");
    /** One icon and the gap after it, in the game's GUI pixels before scaling. */
    private static final int CELL = 25;

    private PotionHud() {
    }

    static void register() {
        HudElementRegistry.replaceElement(VanillaHudElements.STATUS_EFFECTS, original -> (g, delta) -> {
            Minecraft mc = Minecraft.getInstance();
            if (!GROUP.on() || mc.player == null) {
                original.render(g, delta);
                return;
            }
            if (Cinematic.hidesEffects() || mc.screen != null && mc.screen.showsActiveEffects()) return;
            List<MobEffectInstance> effects = new ArrayList<>();
            for (MobEffectInstance effect : Ordering.natural().reverse().sortedCopy(mc.player.getActiveEffects())) {
                if (effect.showIcon()) effects.add(effect);
            }
            if (effects.isEmpty()) return;
            float scale = SCALE.get().floatValue();
            int w = Math.round(g.guiWidth() / scale);
            int h = Math.round(g.guiHeight() / scale);
            g.pose().pushMatrix();
            g.pose().scale(scale);
            draw(g, effects, w, h);
            g.pose().popMatrix();
        });
    }

    /** Lays the effects out in a {@code w} by {@code h} area (already scaled). Also the tab's preview. */
    static void draw(GuiGraphics g, List<MobEffectInstance> effects, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        boolean names = NAMES.get();
        boolean timers = TIMERS.get();
        boolean text = names || timers;
        boolean right = CORNER.get().endsWith("right");
        boolean bottom = CORNER.get().startsWith("Bottom");
        float tickRate = mc.level != null ? mc.level.tickRateManager().tickrate() : 20;

        // The width of the widest label, so a column lines its icons up.
        int labelW = 0;
        List<Component[]> labels = new ArrayList<>();
        for (MobEffectInstance effect : effects) {
            Component name = names ? name(effect) : Component.empty();
            Component time = timers ? MobEffectUtil.formatDuration(effect, 1, tickRate) : Component.empty();
            labels.add(new Component[]{name, time});
            labelW = Math.max(labelW, Math.max(font.width(name), font.width(time)));
        }
        int itemW = CELL + (text ? labelW + 4 : 0);
        String layout = LAYOUT.get();

        int beneficial = 0;
        int harmful = 0;
        for (int i = 0; i < effects.size(); i++) {
            MobEffectInstance effect = effects.get(i);
            int col;
            int row;
            switch (layout) {
                case "Row" -> {
                    col = i;
                    row = 0;
                }
                case "Two rows" -> {
                    if (effect.getEffect().value().isBeneficial()) {
                        col = beneficial++;
                        row = 0;
                    } else {
                        col = harmful++;
                        row = 1;
                    }
                }
                default -> {
                    col = 0;
                    row = i;
                }
            }
            int x = right ? w - 2 - (col + 1) * itemW : 2 + col * itemW;
            int y = bottom ? h - 2 - (row + 1) * CELL : 2 + row * (CELL + 1);
            if (right && mc.isDemo() && !bottom) y += 15;
            int iconX = right && text ? x + labelW + 4 : x;
            int textX = right && text ? x : x + CELL;

            float alpha = 1;
            if (BLINK.get() && !effect.isAmbient() && effect.endsWithin(200)) {
                int m = effect.getDuration();
                int n = 10 - m / 20;
                alpha = Mth.clamp(Mth.clamp(m / 10.0F / 5.0F * 0.5F, 0, 0.5F) + Mth.cos(m * (float) Math.PI / 5.0F) * Mth.clamp(n / 10.0F * 0.25F, 0, 0.25F), 0, 1);
            }
            if (BACKGROUND.get()) g.blitSprite(RenderPipelines.GUI_TEXTURED, effect.isAmbient() ? BACK_AMBIENT : BACK, iconX, y, 24, 24);
            g.blitSprite(RenderPipelines.GUI_TEXTURED, Gui.getMobEffectSprite(effect.getEffect()), iconX + 3, y + 3, 18, 18, ARGB.white(alpha));
            if (!text) continue;
            Component[] label = labels.get(i);
            int ink = 0xFF000000 | TEXT.get();
            int timeInk = 0xFF000000 | TIME.get();
            if (names && timers) {
                g.drawString(font, label[0], align(font, label[0], textX, labelW, right), y + 3, ink, true);
                g.drawString(font, label[1], align(font, label[1], textX, labelW, right), y + 13, timeInk, true);
            } else {
                Component only = names ? label[0] : label[1];
                g.drawString(font, only, align(font, only, textX, labelW, right), y + 8, names ? ink : timeInk, true);
            }
        }
    }

    private static int align(Font font, Component text, int x, int width, boolean right) {
        return right ? x + width - font.width(text) : x + 2;
    }

    private static Component name(MobEffectInstance effect) {
        MutableComponent name = effect.getEffect().value().getDisplayName().copy();
        if (effect.getAmplifier() > 0 && effect.getAmplifier() < 10) {
            name.append(" ").append(Component.translatable("enchantment.level." + (effect.getAmplifier() + 1)));
        }
        return name;
    }
}
