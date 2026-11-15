package com.cyanite.client.visuals;

import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;

import java.util.ArrayList;
import java.util.List;

/**
 * Durability in your colours: the bar under every damaged item shades from your "worn" colour to your
 * "fresh" one, and a readout on the HUD lists your armour with how much is left, flashing when a piece is nearly gone.
 */
public final class ArmorDurability {
    public static final Group GROUP = new Group("armor_durability", "Armor Durability",
        "Durability bar colours, and a HUD readout of your armour that warns when a piece is low.",
        "armor armour durability damage bar indicator break warning");
    static final ColorSetting HIGH = GROUP.add(new ColorSetting("Fresh color", 0x4DFFA0).full());
    static final ColorSetting LOW = GROUP.add(new ColorSetting("Worn color", 0xFF3B3B).full());
    static final BoolSetting ALL_ITEMS = GROUP.add(new BoolSetting("Color every item's bar", true));
    static final BoolSetting READOUT = GROUP.add(new BoolSetting("HUD readout", true));
    static final ModeSetting POSITION = GROUP.add(new ModeSetting("Readout position", "Beside hotbar",
        List.of("Beside hotbar", "Left side", "Top left")));
    static final BoolSetting HELD = GROUP.add(new BoolSetting("Include held item", true));
    static final ModeSetting FORMAT = GROUP.add(new ModeSetting("Show", "Percent", List.of("Percent", "Uses left", "Bar")));
    static final SliderSetting WARN = GROUP.add(new SliderSetting("Warn below", 0.2, 0.05, 0.5, 0.05).percent());

    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private ArmorDurability() {
    }

    /** The durability bar's colour, in place of the game's green-to-red. */
    public static int barColor(ItemStack stack, int color) {
        if (!GROUP.on() || stack.getMaxDamage() <= 0) return color;
        if (!ALL_ITEMS.get() && !isArmor(stack)) return color;
        return Visuals.lerp(0xFF000000 | LOW.get(), 0xFF000000 | HIGH.get(), left(stack)) & 0xFFFFFF;
    }

    private static boolean isArmor(ItemStack stack) {
        Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
        return equippable != null && equippable.slot().getType() == EquipmentSlot.Type.HUMANOID_ARMOR;
    }

    /** How much of it is left, 0 to 1. */
    static float left(ItemStack stack) {
        int max = stack.getMaxDamage();
        return max <= 0 ? 1 : 1 - (float) stack.getDamageValue() / max;
    }

    static void renderHud(GuiGraphics g) {
        if (!READOUT.get()) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (mc.options.hideGui || player.isSpectator()) return;
        List<ItemStack> pieces = new ArrayList<>();
        for (EquipmentSlot slot : ARMOR) {
            ItemStack stack = player.getItemBySlot(slot);
            if (!stack.isEmpty() && stack.isDamageableItem()) pieces.add(stack);
        }
        ItemStack held = player.getMainHandItem();
        if (HELD.get() && held.isDamageableItem()) pieces.add(held);
        if (pieces.isEmpty()) return;

        int rowH = 17;
        int x;
        int y;
        switch (POSITION.get()) {
            case "Left side" -> {
                x = 4;
                y = g.guiHeight() / 2 - pieces.size() * rowH / 2;
            }
            case "Top left" -> {
                x = 4;
                y = 4;
            }
            default -> {
                // Right of the hotbar, bottom lined up with it.
                x = g.guiWidth() / 2 + 91 + 8;
                y = g.guiHeight() - pieces.size() * rowH - 2;
            }
        }
        draw(g, pieces, x, y, rowH);
    }

    /** The list at (x, y). Also the tab's preview. */
    static void draw(GuiGraphics g, List<ItemStack> pieces, int x, int y, int rowH) {
        Minecraft mc = Minecraft.getInstance();
        boolean blink = (System.currentTimeMillis() / 400) % 2 == 0;
        for (ItemStack stack : pieces) {
            float left = left(stack);
            int color = Visuals.lerp(0xFF000000 | LOW.get(), 0xFF000000 | HIGH.get(), left);
            boolean low = left <= WARN.get();
            if (low && blink) g.fill(x - 1, y - 1, x + 17, y + 17, Visuals.argb(LOW.get(), 0.45));
            g.renderItem(stack, x, y);
            int tx = x + 19;
            int ty = y + 4;
            switch (FORMAT.get()) {
                case "Uses left" -> g.drawString(mc.font, String.valueOf(stack.getMaxDamage() - stack.getDamageValue()), tx, ty, color, true);
                case "Bar" -> {
                    g.fill(tx, ty + 2, tx + 30, ty + 6, 0xA0000000);
                    g.fill(tx, ty + 2, tx + Math.max(1, Math.round(30 * left)), ty + 6, color);
                }
                default -> g.drawString(mc.font, Math.round(left * 100) + "%", tx, ty, color, true);
            }
            y += rowH;
        }
    }
}
