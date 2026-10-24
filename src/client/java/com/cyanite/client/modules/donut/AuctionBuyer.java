package com.cyanite.client.modules.donut;

import com.cyanite.client.config.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Buys an item off DonutSMP's auction house, the way you would: /ah and the item's name (with
 * "stack" on the end when it wants 64 or more, which lists only full stacks), the cheapest listing
 * of the right item under the price cap clicked, the confirm button clicked, and a check that it
 * actually turned up in your inventory. It searches again until it has enough, runs out of
 * affordable listings, or hits its budget.
 *
 * <p>With the server's confirm screen turned off (DonutSMP lets you), a click on the listing buys
 * it, and nothing else is ever clicked: it only waits for the items to arrive. It closes the menu
 * and searches afresh before every purchase, so it never clicks a listing that's just moved.
 *
 * <p>Every menu it sees, and every click, goes in cyanite/ah-log.txt, so the way a server lays
 * its auction house out can be checked against what this expects.
 */
public final class AuctionBuyer {
    public enum Result { RUNNING, DONE, NONE, FAILED }

    private enum Stage { CLOSE_FIRST, SEND, WAIT_MENU, PICK, CONFIRM, WAIT_ITEM }

    private static final long MENU_WAIT = 5000;
    private static final long SETTLE = 450;
    private static final long ITEM_WAIT = 4500;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    /** A "not enough money" or the like from the server while buying. */
    private static volatile String refusal;

    private final Minecraft mc = Minecraft.getInstance();
    private Item item;
    private int want;
    private double maxEach;
    private double budget;
    private double spent;
    private int got;
    private Stage stage;
    private long stageSince;
    private int before;
    private double price;
    private int lastMenu = -1;
    private String why = "";
    /** The cheapest apiece any listing of the item went for, cap or no cap (NaN if none was seen). */
    private double cheapest = Double.NaN;
    private boolean active;
    private boolean confirm;
    private long sendAfter;
    private final java.util.Random random = new java.util.Random();

    /**
     * Starts buying {@code count} of {@code item}, at no more than {@code maxEach} apiece and
     * {@code budget} in all. {@code confirm}: the server asks to confirm each purchase in a menu.
     */
    public void start(Item item, int count, double maxEach, double budget, boolean confirm) {
        this.confirm = confirm;
        this.item = item;
        this.want = count;
        this.maxEach = maxEach;
        this.budget = budget;
        this.spent = 0;
        this.got = 0;
        this.why = "";
        this.cheapest = Double.NaN;
        refusal = null;
        active = true;
        go(Stage.CLOSE_FIRST);
        log("Buying " + count + " " + name(item) + " (max $" + money(maxEach) + " each, budget $" + money(budget) + ", "
            + (confirm ? "confirm screen" : "no confirm screen") + ")");
    }

    public boolean active() {
        return active;
    }

    public double spent() {
        return spent;
    }

    public int got() {
        return got;
    }

    /** The cheapest apiece it saw listed, over the cap or not; NaN if there were none. */
    public double cheapestEach() {
        return cheapest;
    }

    /** Why it stopped short, for the message. */
    public String why() {
        return why;
    }

    public void cancel() {
        if (active) close();
        active = false;
    }

    /** A message from the server: one that says the purchase can't happen stops it. */
    public static void onServerMessage(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("enough money") || lower.contains("can't afford") || lower.contains("cannot afford")
            || lower.contains("insufficient") || lower.contains("not enough") || lower.contains("no longer available")) {
            refusal = text;
        }
    }

    public Result tick() {
        if (!active || mc.player == null) return Result.FAILED;
        long now = System.currentTimeMillis();
        String refused = refusal;
        if (refused != null) {
            refusal = null;
            log("Server said: " + refused);
            return finish(got > 0 ? Result.DONE : Result.FAILED, "the server said \"" + refused + "\"");
        }
        switch (stage) {
            case CLOSE_FIRST -> {
                if (mc.screen != null) mc.player.closeContainer();
                // A moment between searches, the way you'd type the next one.
                sendAfter = now + 700 + random.nextInt(600);
                go(Stage.SEND);
            }
            case SEND -> {
                if (now < sendAfter) return Result.RUNNING;
                String query = name(item) + (want - got >= 64 ? " stack" : "");
                lastMenu = mc.player.containerMenu.containerId;
                mc.player.connection.sendCommand("ah " + query);
                log("> /ah " + query);
                go(Stage.WAIT_MENU);
            }
            case WAIT_MENU -> {
                if (menu() != null) {
                    go(Stage.PICK);
                } else if (now - stageSince > MENU_WAIT) {
                    log("No menu opened");
                    return finish(got > 0 ? Result.DONE : Result.FAILED, "/ah didn't open a menu");
                }
            }
            case PICK -> {
                // The menu's contents come a moment after it opens.
                if (now - stageSince < SETTLE) return Result.RUNNING;
                AbstractContainerMenu menu = menu();
                if (menu == null) return finish(got > 0 ? Result.DONE : Result.FAILED, "the menu closed");
                logMenu(menu, "results");
                Slot best = null;
                double bestEach = Double.MAX_VALUE;
                double bestPrice = 0;
                for (Slot slot : menu.slots) {
                    if (slot.container instanceof Inventory) continue;
                    ItemStack stack = slot.getItem();
                    if (!stack.is(item)) continue;
                    double[] p = price(stack);
                    if (p == null) continue;
                    if (Double.isNaN(cheapest) || p[1] < cheapest) cheapest = p[1];
                    if (p[1] > maxEach || spent + p[0] > budget) continue;
                    if (p[1] < bestEach) {
                        best = slot;
                        bestEach = p[1];
                        bestPrice = p[0];
                    }
                }
                if (best == null) {
                    log("Nothing affordable listed");
                    return finish(got > 0 ? Result.DONE : Result.NONE, "no " + name(item) + " listed for $" + money(maxEach) + " each or less");
                }
                before = count(item);
                price = bestPrice;
                lastMenu = menu.containerId;
                log("Clicking slot " + best.index + ": " + best.getItem().getCount() + "x for $" + money(bestPrice));
                mc.gameMode.handleInventoryMouseClick(menu.containerId, best.index, 0, ClickType.PICKUP, mc.player);
                // No confirm screen: that click bought it. Nothing more gets clicked; just wait for it.
                go(confirm ? Stage.CONFIRM : Stage.WAIT_ITEM);
            }
            case CONFIRM -> {
                if (count(item) > before) return bought();
                if (now - stageSince < SETTLE) return Result.RUNNING;
                AbstractContainerMenu menu = menu();
                Slot confirm = menu == null ? null : confirmButton(menu);
                if (confirm != null) {
                    logMenu(menu, "confirm");
                    log("Clicking confirm, slot " + confirm.index + ": " + confirm.getItem().getHoverName().getString());
                    mc.gameMode.handleInventoryMouseClick(menu.containerId, confirm.index, 0, ClickType.PICKUP, mc.player);
                    go(Stage.WAIT_ITEM);
                } else if (now - stageSince > 2500) {
                    // No button anywhere: the click may have bought it outright.
                    go(Stage.WAIT_ITEM);
                }
            }
            case WAIT_ITEM -> {
                if (count(item) > before) return bought();
                if (now - stageSince > ITEM_WAIT) {
                    log("Nothing turned up");
                    return finish(got > 0 ? Result.DONE : Result.FAILED, "the purchase didn't come through");
                }
            }
        }
        return Result.RUNNING;
    }

    private Result bought() {
        int arrived = count(item) - before;
        got += arrived;
        spent += price;
        log("Got " + arrived + " (" + got + "/" + want + "), spent $" + money(spent));
        if (got >= want) return finish(Result.DONE, "");
        if (spent >= budget) return finish(Result.DONE, "the budget's used up");
        go(Stage.CLOSE_FIRST);
        return Result.RUNNING;
    }

    private Result finish(Result result, String reason) {
        why = reason;
        close();
        active = false;
        return result;
    }

    private void close() {
        if (mc.player != null && mc.screen != null) mc.player.closeContainer();
    }

    private void go(Stage next) {
        stage = next;
        stageSince = System.currentTimeMillis();
    }

    /** The server's menu that's open, if there is one. */
    private AbstractContainerMenu menu() {
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) return null;
        AbstractContainerMenu menu = screen.getMenu();
        return menu == mc.player.inventoryMenu ? null : menu;
    }

    /**
     * What a listing costs: {total, each}. A line with "each" or "per" in it is the price apiece;
     * otherwise a line with "price" in it (or failing that, the first with a $) is for the lot.
     */
    static double[] price(ItemStack stack) {
        List<String> lines = lines(stack);
        Double total = null;
        Double each = null;
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (!lower.contains("$") && !lower.contains("price") && !lower.contains("cost")) continue;
            Double v = BountyBalance.money(line, "\u0000");
            if (v == null) continue;
            if (lower.contains("each") || lower.contains(" per ") || lower.contains("/ea")) {
                if (each == null) each = v;
            } else if (lower.contains("price") || lower.contains("cost")) {
                if (total == null) total = v;
            } else if (total == null && lower.contains("$")) {
                total = v;
            }
        }
        int count = Math.max(1, stack.getCount());
        if (total == null && each == null) return null;
        if (total == null) total = each * count;
        if (each == null) each = total / count;
        return new double[] {total, each};
    }

    /** The confirm button of a confirm menu: by its name, or failing that a green pane or block. */
    private Slot confirmButton(AbstractContainerMenu menu) {
        // Only in a menu the click opened: the listing menu's own buttons (buy orders and the like) are never pressed.
        if (menu.containerId == lastMenu) return null;
        Slot green = null;
        for (Slot slot : menu.slots) {
            if (slot.container instanceof Inventory) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            String name = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
            if (name.contains("confirm") || name.contains("purchase") || name.contains("buy") || name.equals("yes") || name.contains("accept")) {
                return slot;
            }
            if (green == null && (stack.is(Items.LIME_STAINED_GLASS_PANE) || stack.is(Items.GREEN_STAINED_GLASS_PANE)
                || stack.is(Items.LIME_STAINED_GLASS) || stack.is(Items.LIME_CONCRETE) || stack.is(Items.LIME_WOOL)
                || stack.is(Items.EMERALD_BLOCK) || stack.is(Items.LIME_DYE))) {
                green = slot;
            }
        }
        return green;
    }

    private int count(Item wanted) {
        Inventory inventory = mc.player.getInventory();
        int n = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(wanted)) n += stack.getCount();
        }
        return n;
    }

    private static List<String> lines(ItemStack stack) {
        List<String> out = new ArrayList<>();
        out.add(stack.getHoverName().getString());
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore != null) for (Component line : lore.lines()) out.add(line.getString());
        return out;
    }

    /** The item's name as the auction house search wants it. */
    public static String name(Item item) {
        return item.getDefaultInstance().getHoverName().getString();
    }

    private static String money(double v) {
        if (v >= 1e6) return String.format(Locale.ROOT, "%.2fM", v / 1e6);
        if (v >= 1e3) return String.format(Locale.ROOT, "%.1fK", v / 1e3);
        return String.format(Locale.ROOT, "%.0f", v);
    }

    // ---- The log

    private void logMenu(AbstractContainerMenu menu, String what) {
        StringBuilder sb = new StringBuilder("Menu (" + what + "): \"" + (mc.screen == null ? "" : mc.screen.getTitle().getString()) + "\"");
        for (Slot slot : menu.slots) {
            if (slot.container instanceof Inventory || slot.getItem().isEmpty()) continue;
            sb.append("\n    slot ").append(slot.index).append(": ").append(slot.getItem().getCount()).append("x ")
                .append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()));
            for (String line : lines(slot.getItem())) sb.append("\n        ").append(line);
        }
        log(sb.toString());
    }

    private static void log(String line) {
        Path file = Config.dir().resolve("ah-log.txt");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, "[" + LocalTime.now().format(TIME) + "] " + line + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // The log is only for checking against; buying goes on without it.
        }
    }
}
