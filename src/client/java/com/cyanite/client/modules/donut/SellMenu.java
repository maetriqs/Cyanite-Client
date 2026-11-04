package com.cyanite.client.modules.donut;

import com.cyanite.client.config.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Sells items to the server through DonutSMP's /sell menu, the way you would: /sell opens a chest
 * six rows high, what goes in its top five rows is sold when the green pane (bottom right) is
 * clicked or the menu's closed, and the bottom row is the menu's own and takes nothing. Items go
 * up with a shift-click, whole stacks only, never more stacks than the top five rows have room for
 * (your inventory always fits, so one go does it). It only ever touches stacks of the items it was
 * asked to sell, plain ones (nothing named or enchanted), up to the amount asked for.
 *
 * <p>What sold is what's gone from your inventory once the menu's shut: anything the server hands
 * back (an item it won't buy) counts as not sold. Every menu it sees goes in cyanite/sell-log.txt.
 */
public final class SellMenu {
    public enum Result { RUNNING, DONE, FAILED }

    private enum Stage { CLOSE_FIRST, SEND, WAIT_MENU, FILL, SELL, WAIT_SOLD, SETTLE }

    /** The top five rows of the menu: where items go. */
    private static final int SELL_SLOTS = 45;
    private static final long MENU_WAIT = 5000;
    private static final long SETTLE = 450;
    private static final long SOLD_WAIT = 3000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    /** Money the server said the sale made, while one's going. */
    private static volatile double earnedNow;
    private static volatile boolean listening;

    private final Minecraft mc = Minecraft.getInstance();
    private final Random random = new Random();
    private final Map<Item, Integer> left = new HashMap<>();
    private final Map<Item, Integer> before = new HashMap<>();
    private final Map<Item, Integer> offered = new HashMap<>();
    private final Map<Item, Integer> sold = new HashMap<>();
    private Stage stage;
    private long stageSince;
    private long nextAt;
    private int lastMenu = -1;
    private int menuId = -1;
    private String why = "";
    private double earned;
    private boolean active;

    /** Starts selling up to {@code amounts} of each item, in whole stacks. */
    public void start(Map<Item, Integer> amounts) {
        left.clear();
        left.putAll(amounts);
        before.clear();
        offered.clear();
        sold.clear();
        why = "";
        earned = 0;
        earnedNow = 0;
        listening = true;
        active = true;
        StringBuilder sb = new StringBuilder("Selling up to");
        amounts.forEach((item, n) -> sb.append(' ').append(n).append(' ').append(AuctionBuyer.name(item)).append(','));
        log(sb.substring(0, sb.length() - 1));
        go(Stage.CLOSE_FIRST);
    }

    public boolean active() {
        return active;
    }

    /** How many of each item went into the menu. */
    public Map<Item, Integer> offered() {
        return offered;
    }

    /** How many of each item it sold. */
    public Map<Item, Integer> sold() {
        return sold;
    }

    /** What the server said the sale made, NaN if it didn't say. */
    public double earned() {
        return earned;
    }

    /** Why it stopped short, for the message. */
    public String why() {
        return why;
    }

    public void cancel() {
        if (active) close();
        active = false;
        listening = false;
    }

    /** A message from the server: what a sale made, if it says. */
    public static void onServerMessage(String text) {
        if (!listening) return;
        String lower = text.toLowerCase(Locale.ROOT);
        if (!lower.contains("$") || !lower.contains("sold") && !lower.contains("sell") && !lower.contains("earn")) return;
        Double v = BountyBalance.money(text, "\u0000");
        if (v != null) earnedNow += v;
        log("Server said: " + text);
    }

    public Result tick() {
        if (!active || mc.player == null) return Result.FAILED;
        long now = System.currentTimeMillis();
        switch (stage) {
            case CLOSE_FIRST -> {
                if (mc.screen != null) mc.player.closeContainer();
                nextAt = now + 600 + random.nextInt(500);
                go(Stage.SEND);
            }
            case SEND -> {
                if (now < nextAt) return Result.RUNNING;
                lastMenu = mc.player.containerMenu.containerId;
                mc.player.connection.sendCommand("sell");
                log("> /sell");
                go(Stage.WAIT_MENU);
            }
            case WAIT_MENU -> {
                AbstractContainerMenu menu = menu();
                if (menu != null && menu.containerId != lastMenu) {
                    if (!isSellMenu(menu)) {
                        logMenu(menu);
                        return finish(Result.FAILED, "/sell opened a menu that isn't the sell menu");
                    }
                    menuId = menu.containerId;
                    for (Item item : left.keySet()) before.put(item, count(item));
                    nextAt = now + SETTLE;
                    go(Stage.FILL);
                } else if (now - stageSince > MENU_WAIT) {
                    log("No menu opened");
                    return finish(Result.FAILED, "/sell didn't open its menu");
                }
            }
            case FILL -> {
                if (now < nextAt) return Result.RUNNING;
                AbstractContainerMenu menu = menu();
                if (menu == null || menu.containerId != menuId) return finish(Result.FAILED, "the sell menu closed");
                Slot from = next(menu);
                if (from == null || free(menu) == 0) {
                    if (offered.isEmpty()) return finish(Result.DONE, "nothing in whole stacks to sell");
                    nextAt = now + 250 + random.nextInt(250);
                    go(Stage.SELL);
                    return Result.RUNNING;
                }
                ItemStack stack = from.getItem();
                Item item = stack.getItem();
                int n = stack.getCount();
                mc.gameMode.handleInventoryMouseClick(menu.containerId, from.index, 0, ClickType.QUICK_MOVE, mc.player);
                left.merge(item, -n, Integer::sum);
                offered.merge(item, n, Integer::sum);
                log("Shift-clicked slot " + from.index + ": " + n + " " + AuctionBuyer.name(item));
                // Shift-clicks one after another, at the pace of a hand.
                nextAt = now + 90 + random.nextInt(90);
            }
            case SELL -> {
                if (now < nextAt) return Result.RUNNING;
                AbstractContainerMenu menu = menu();
                if (menu == null || menu.containerId != menuId) {
                    // Gone already: closing sells it too.
                    go(Stage.WAIT_SOLD);
                    return Result.RUNNING;
                }
                logMenu(menu);
                Slot button = sellButton(menu);
                if (button != null) {
                    log("Clicking the sell button, slot " + button.index);
                    mc.gameMode.handleInventoryMouseClick(menu.containerId, button.index, 0, ClickType.PICKUP, mc.player);
                } else {
                    log("No sell button: closing the menu, which sells it");
                    mc.player.closeContainer();
                }
                go(Stage.WAIT_SOLD);
            }
            case WAIT_SOLD -> {
                AbstractContainerMenu menu = menu();
                boolean open = menu != null && menu.containerId == menuId;
                if (open && !emptied(menu) && now - stageSince < SOLD_WAIT) return Result.RUNNING;
                if (open) {
                    log(emptied(menu) ? "Sold: closing the menu" : "Still full after the click: closing the menu");
                    mc.player.closeContainer();
                }
                // A moment for anything the server won't buy to come back.
                nextAt = now + 600;
                go(Stage.SETTLE);
            }
            case SETTLE -> {
                if (now < nextAt) return Result.RUNNING;
                for (Map.Entry<Item, Integer> e : before.entrySet()) {
                    int gone = e.getValue() - count(e.getKey());
                    if (gone > 0) sold.put(e.getKey(), gone);
                }
                earned = earnedNow > 0 ? earnedNow : Double.NaN;
                log("Sold " + sold + (Double.isNaN(earned) ? "" : " for $" + earned));
                return finish(sold.isEmpty() ? Result.FAILED : Result.DONE, sold.isEmpty() ? "the server didn't take any of it" : "");
            }
        }
        return Result.RUNNING;
    }

    private Result finish(Result result, String reason) {
        why = reason;
        close();
        active = false;
        listening = false;
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

    /** Six rows of the server's own, called Sell or with the green button bottom right. */
    private boolean isSellMenu(AbstractContainerMenu menu) {
        List<Slot> top = top(menu);
        if (top.size() != 54) return false;
        String title = mc.screen == null ? "" : mc.screen.getTitle().getString().toLowerCase(Locale.ROOT);
        return title.contains("sell") || green(top.get(53).getItem());
    }

    private static List<Slot> top(AbstractContainerMenu menu) {
        return menu.slots.stream().filter(s -> !(s.container instanceof Inventory)).toList();
    }

    /** Empty spots left in the top five rows. */
    private static int free(AbstractContainerMenu menu) {
        List<Slot> top = top(menu);
        int n = 0;
        for (int i = 0; i < Math.min(SELL_SLOTS, top.size()); i++) if (top.get(i).getItem().isEmpty()) n++;
        return n;
    }

    /** Whether the top five rows are empty again: the server's taken what was in them. */
    private static boolean emptied(AbstractContainerMenu menu) {
        return free(menu) == Math.min(SELL_SLOTS, top(menu).size());
    }

    /**
     * The next stack of yours to put up: one asked for, plain, and no more than what's left to sell
     * of it; the biggest such, so a stack set aside to be sold goes before a smaller one beside it.
     */
    private Slot next(AbstractContainerMenu menu) {
        Slot best = null;
        for (Slot slot : menu.slots) {
            if (!(slot.container instanceof Inventory) || slot.getContainerSlot() >= 36) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || !stack.getComponentsPatch().isEmpty()) continue;
            Integer want = left.get(stack.getItem());
            if (want != null && stack.getCount() <= want && (best == null || stack.getCount() > best.getItem().getCount())) best = slot;
        }
        return best;
    }

    /** The green pane in the bottom row; null if there isn't one. */
    private static Slot sellButton(AbstractContainerMenu menu) {
        List<Slot> top = top(menu);
        for (int i = top.size() - 1; i >= SELL_SLOTS; i--) {
            if (green(top.get(i).getItem())) return top.get(i);
        }
        return null;
    }

    private static boolean green(ItemStack stack) {
        return stack.is(Items.LIME_STAINED_GLASS_PANE) || stack.is(Items.GREEN_STAINED_GLASS_PANE);
    }

    private int count(Item wanted) {
        Inventory inventory = mc.player.getInventory();
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(wanted)) n += stack.getCount();
        }
        return n;
    }

    // ---- The log

    private void logMenu(AbstractContainerMenu menu) {
        StringBuilder sb = new StringBuilder("Menu: \"" + (mc.screen == null ? "" : mc.screen.getTitle().getString()) + "\"");
        for (Slot slot : top(menu)) {
            if (slot.getItem().isEmpty()) continue;
            sb.append("\n    slot ").append(slot.index).append(": ").append(slot.getItem().getCount()).append("x ")
                .append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()))
                .append(" \"").append(slot.getItem().getHoverName().getString()).append('"');
        }
        log(sb.toString());
    }

    private static void log(String line) {
        Path file = Config.dir().resolve("sell-log.txt");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, "[" + LocalTime.now().format(TIME) + "] " + line + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // The log is only for checking against; selling goes on without it.
        }
    }
}
