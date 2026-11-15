package com.cyanite.client.visuals;

import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Moves and resizes the game's own HUD: the hotbar with the bars over it, the boss bars and the scoreboard;
 * and recolours the XP level. Each piece is drawn by the game as usual, only shifted and scaled.
 */
public final class CustomHud {
    public static final Group GROUP = new Group("custom_hud", "Custom HUD",
        "Position, size and colour of the hotbar, health and hunger, boss bars, scoreboard and XP level.",
        "hud hotbar position size scale move scoreboard boss bar xp level color health hunger");
    static final SliderSetting HOTBAR_SCALE = GROUP.add(new SliderSetting("Hotbar scale", 1, 0.5, 1.5, 0.05).format(Particles::times));
    static final SliderSetting HOTBAR_X = GROUP.add(new SliderSetting("Hotbar X", 0, -300, 300, 1));
    static final SliderSetting HOTBAR_Y = GROUP.add(new SliderSetting("Hotbar Y", 0, -200, 0, 1));
    static final SliderSetting BOSS_SCALE = GROUP.add(new SliderSetting("Boss bar scale", 1, 0.5, 1.5, 0.05).format(Particles::times));
    static final SliderSetting BOSS_Y = GROUP.add(new SliderSetting("Boss bar Y", 0, 0, 150, 1));
    static final SliderSetting SCORE_SCALE = GROUP.add(new SliderSetting("Scoreboard scale", 1, 0.5, 1.5, 0.05).format(Particles::times));
    static final SliderSetting SCORE_Y = GROUP.add(new SliderSetting("Scoreboard Y", 0, -150, 150, 1));
    static final BoolSetting XP_RECOLOR = GROUP.add(new BoolSetting("Recolor XP level", false));
    static final ColorSetting XP_COLOR = GROUP.add(new ColorSetting("XP level color", 0x80FF20).full());

    /** Everything that sits on or over the hotbar, moved together. */
    private static final List<Identifier> HOTBAR = List.of(VanillaHudElements.HOTBAR, VanillaHudElements.ARMOR_BAR, VanillaHudElements.HEALTH_BAR,
        VanillaHudElements.FOOD_BAR, VanillaHudElements.AIR_BAR, VanillaHudElements.MOUNT_HEALTH, VanillaHudElements.INFO_BAR,
        VanillaHudElements.EXPERIENCE_LEVEL, VanillaHudElements.HELD_ITEM_TOOLTIP);

    private enum Pivot { BOTTOM, TOP, RIGHT }

    private CustomHud() {
    }

    static void register() {
        for (Identifier id : HOTBAR) {
            HudElementRegistry.replaceElement(id, original -> moved(id == VanillaHudElements.EXPERIENCE_LEVEL ? xpLevel(original) : original,
                Pivot.BOTTOM, HOTBAR_SCALE, HOTBAR_X, HOTBAR_Y));
        }
        HudElementRegistry.replaceElement(VanillaHudElements.BOSS_BAR, original -> moved(original, Pivot.TOP, BOSS_SCALE, null, BOSS_Y));
        HudElementRegistry.replaceElement(VanillaHudElements.SCOREBOARD, original -> moved(original, Pivot.RIGHT, SCORE_SCALE, null, SCORE_Y));
    }

    /** {@code element}, drawn shifted and scaled about its {@code pivot} when this is on. */
    private static HudElement moved(HudElement element, Pivot pivot, SliderSetting scale, SliderSetting x, SliderSetting y) {
        return (g, delta) -> {
            if (!GROUP.on()) {
                element.render(g, delta);
                return;
            }
            float s = scale.get().floatValue();
            float dx = x == null ? 0 : x.get().floatValue();
            float dy = y.get().floatValue();
            if (s == 1 && dx == 0 && dy == 0) {
                element.render(g, delta);
                return;
            }
            float px = pivot == Pivot.RIGHT ? g.guiWidth() : g.guiWidth() / 2f;
            float py = switch (pivot) {
                case BOTTOM -> g.guiHeight();
                case TOP -> 0;
                case RIGHT -> g.guiHeight() / 2f;
            };
            g.pose().pushMatrix();
            g.pose().translate(px + dx, py + dy);
            g.pose().scale(s);
            g.pose().translate(-px, -py);
            element.render(g, delta);
            g.pose().popMatrix();
        };
    }

    /** The XP level number, in a colour of yours, drawn the way the game draws it. */
    private static HudElement xpLevel(HudElement original) {
        return (g, delta) -> {
            Minecraft mc = Minecraft.getInstance();
            if (!GROUP.on() || !XP_RECOLOR.get() || mc.player == null) {
                original.render(g, delta);
                return;
            }
            Component text = Component.translatable("gui.experience.level", mc.player.experienceLevel);
            int x = (g.guiWidth() - mc.font.width(text)) / 2;
            int y = g.guiHeight() - 24 - 9 - 2;
            g.drawString(mc.font, text, x + 1, y, 0xFF000000, false);
            g.drawString(mc.font, text, x - 1, y, 0xFF000000, false);
            g.drawString(mc.font, text, x, y + 1, 0xFF000000, false);
            g.drawString(mc.font, text, x, y - 1, 0xFF000000, false);
            g.drawString(mc.font, text, x, y, 0xFF000000 | XP_COLOR.get(), false);
        };
    }
}
