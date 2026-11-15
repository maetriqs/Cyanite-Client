package com.cyanite.client.system;

import com.cyanite.client.mixin.DialogScreenAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.dialog.DialogScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.ItemBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads a server's /stats. DonutSMP shows it as a dialog ("Money: $ 1.2M", "Kills: 0", ...), other
 * servers usually as a chest menu with the numbers in the items' lore. It sends the command, waits
 * for whichever opens, reads every line and button, and shuts it without clicking anything. Call
 * {@link #tick} every client tick until it says it's done.
 */
public final class ServerStats {
    /** What the menu said, top to bottom: "dialog" or "menu", its title, its lines and its buttons. */
    public record Result(String kind, String title, List<String> lines, List<String> buttons) {
        /** The "Label: value" lines as label to value, in order. */
        public Map<String, String> values() {
            Map<String, String> out = new LinkedHashMap<>();
            for (String line : lines) {
                int colon = line.indexOf(':');
                if (colon <= 0 || colon == line.length() - 1) continue;
                String label = line.substring(0, colon).strip();
                String value = line.substring(colon + 1).strip();
                if (!label.isEmpty() && !value.isEmpty() && label.length() <= 40) out.putIfAbsent(label, value);
            }
            return out;
        }
    }

    private static final Pattern NOISE = Pattern.compile("§.");
    private static final Pattern SPACE = Pattern.compile("\\s+");
    /** A picture in the text (a player's head, a sprite): "[_name head]". */
    private static final Pattern PICTURE = Pattern.compile("^\\[[^\\]]*\\]$");
    /** Ticks a menu's items get to settle after the first one arrives. */
    private static final int SETTLE = 5;

    private final long deadline;
    private final int menuBefore;
    private final Screen screenBefore;
    private int filledFor = -1;
    private Result result;
    private String error;

    private ServerStats(Minecraft mc, long timeoutMs) {
        this.deadline = Util.getMillis() + timeoutMs;
        this.menuBefore = mc.player.containerMenu.containerId;
        this.screenBefore = mc.screen;
    }

    /** Sends {@code command} (no slash) and starts watching for what it opens. */
    public static ServerStats start(Minecraft mc, String command, long timeoutMs) {
        if (mc.player == null) throw new IllegalStateException("Not on a server");
        ServerStats read = new ServerStats(mc, timeoutMs);
        mc.player.connection.sendCommand(command);
        return read;
    }

    public boolean done() {
        return result != null || error != null;
    }

    /** The menu, once done; null if it failed ({@link #error}). */
    public Result result() {
        return result;
    }

    public String error() {
        return error;
    }

    /** True once it has an answer or gave up. */
    public boolean tick(Minecraft mc) {
        if (done()) return true;
        LocalPlayer player = mc.player;
        if (player == null) {
            error = "Left the server";
            return true;
        }
        Screen screen = mc.screen;
        boolean late = Util.getMillis() > deadline;
        if (screen instanceof DialogScreen<?> && screen != screenBefore) {
            result = read(((DialogScreenAccessor) screen).cyanite$dialog());
            // Not onClose: a dialog's own close can run an action on the server.
            mc.setScreen(null);
            return true;
        }
        if (screen instanceof AbstractContainerScreen<?> s && s.getMenu() != player.inventoryMenu
            && s.getMenu().containerId != menuBefore) {
            if (filledFor >= 0 || filled(s.getMenu())) filledFor++;
            if (filledFor >= SETTLE || late) {
                if (filledFor >= 0) result = read(s.getTitle(), s.getMenu());
                else error = "The stats menu stayed empty";
                player.closeContainer();
                return true;
            }
            return false;
        }
        if (late) error = "No stats menu opened";
        return late;
    }

    private static Result read(Dialog dialog) {
        List<String> lines = new ArrayList<>();
        for (DialogBody body : dialog.common().body()) {
            if (body instanceof PlainMessage message) {
                add(lines, message.contents());
            } else if (body instanceof ItemBody item) {
                add(lines, item.item().getHoverName());
                item.description().ifPresent(d -> add(lines, d.contents()));
            }
        }
        List<String> buttons = new ArrayList<>();
        if (dialog instanceof MultiActionDialog multi) {
            for (ActionButton button : multi.actions()) add(buttons, button.button().label());
        }
        return new Result("dialog", text(dialog.common().title()), lines, buttons);
    }

    private static Result read(Component title, AbstractContainerMenu menu) {
        List<String> lines = new ArrayList<>();
        for (Slot slot : menu.slots) {
            if (slot.container instanceof Inventory) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            add(lines, stack.getHoverName());
            ItemLore lore = stack.get(DataComponents.LORE);
            if (lore != null) for (Component line : lore.lines()) add(lines, line);
        }
        return new Result("menu", text(title), lines, List.of());
    }

    private static boolean filled(AbstractContainerMenu menu) {
        for (Slot slot : menu.slots) {
            if (!(slot.container instanceof Inventory) && !slot.getItem().isEmpty()) return true;
        }
        return false;
    }

    private static void add(List<String> lines, Component component) {
        String text = text(component);
        if (!text.isEmpty() && !PICTURE.matcher(text).matches()) lines.add(text);
    }

    private static String text(Component component) {
        return SPACE.matcher(NOISE.matcher(component.getString()).replaceAll("")).replaceAll(" ").strip();
    }
}
