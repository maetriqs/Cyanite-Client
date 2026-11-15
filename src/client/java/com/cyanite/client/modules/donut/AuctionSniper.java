package com.cyanite.client.modules.donut;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.Notify;
import com.cyanite.client.system.Stealth;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.SliderSetting;
import com.cyanite.client.system.setting.StringSetting;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Snipes DonutSMP's auction house while you have it open. A panel beside the /ah menu takes the most
 * you'll pay (and what to look for, if you like); started, it clicks the page's anvil to refresh it as
 * fast as the server answers, and clicks any listing at or under the price in the frame the page lands
 * (the game handles packets just before Cyanite's frame hook, so nothing waits for a tick).
 *
 * <p>One click is out at a time. A listing is only clicked on a page the server has answered since the
 * last click, so a page that shifted after a purchase, or a refresh still on its way, never turns a
 * click into buying something else. A clicked listing isn't clicked again until a refresh shows the
 * page afresh. Refreshing waits while there's something on the page to buy.
 *
 * <p>It stops when you click in the menu or close it, when the money, the budget or the room in your
 * inventory runs out, and when the server opens a menu it doesn't know. A purchase that closes the
 * menu opens /ah again; with Donut's confirm screen on, the confirm button is clicked as it opens.
 */
public class AuctionSniper extends Module {
    /** A purchase the server hasn't said yes or no to after this long: counted as gone through, unseen. */
    private static final long PURCHASE_WAIT = 3000;
    /** A listing's click hasn't been answered after this long: the page is taken as it is. */
    private static final long CLICK_WAIT = 1500;
    /** Fastest the anvil is ever clicked, whatever the delay. */
    private static final long MIN_GAP = 50;
    /** The menu gone this long (and not reopened) ends a run. */
    private static final long GONE_WAIT = 2500;
    private static final Pattern PRICE = Pattern.compile("^\\$?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kmb])?$");

    public final StringSetting maxPrice = add(new StringSetting("Max price", "", 16).placeholder("e.g. 150k"));
    public final StringSetting item = add(new StringSetting("Item", "", 64).placeholder("any item"));
    private final BoolSetting perItem = add(new BoolSetting("Price per item", false));
    private final StringSetting budget = add(new StringSetting("Budget", "", 16).placeholder("no limit"));
    private final SliderSetting delay = add(new SliderSetting("Refresh delay", 0.1, 0, 2, 0.05)
        .format(v -> v == 0 ? "none" : String.format(Locale.ROOT, "%.2fs", v)));

    private enum Kind { ANVIL, LISTING, CONFIRM }

    /** A click the server hasn't answered yet. */
    private record Out(Kind kind, int containerId, int slot, long at) {
    }

    /** A listing clicked, waiting for the items (bought), "already bought" (missed) or a refusal. */
    private static final class Purchase {
        final ItemStack stack;
        final Item item;
        final double price;
        final long at;
        int before;

        Purchase(ItemStack stack, double price, long at, int before) {
            this.stack = stack;
            this.item = stack.getItem();
            this.price = price;
            this.at = at;
            this.before = before;
        }
    }

    /** One bought, for the panel's list. */
    public record Bought(String what, double price) {
    }

    private boolean running;
    private String status = "";
    private boolean statusBad;
    private long statusAt;
    private int bought;
    private int missed;
    private double spent;
    /** Purchases that never said either way, their price kept against the budget all the same. */
    private double unsure;
    private int refreshes;
    private final Deque<Long> refreshTimes = new ArrayDeque<>();
    private final Deque<Bought> recent = new ArrayDeque<>();
    private double rtt = -1;

    // ---- The page and what's out
    private int page = -1;
    private Out out;
    private long nextRefresh;
    private long lastAnvil;
    /** Extra wait between refreshes after the server says to slow down; halves back over time. */
    private long backoff;
    private long backoffAt;
    private final List<Purchase> pending = new ArrayList<>();
    /** Listings clicked since the last refresh, by slot: not clicked again while they're still there. */
    private final Map<Integer, ItemStack> clicked = new HashMap<>();
    private long lastPurchaseClick;
    private long goneSince;
    private int reopens;

    // ---- What the packets brought since the last frame
    private boolean news;
    private boolean answered;
    private boolean wholePage;
    private boolean heldFrame;
    private static volatile String serverSaid;
    private static String lastSaid = "";
    private static long lastSaidAt;

    public AuctionSniper() {
        super(Category.DONUT, "Auction Sniper", "Buys /ah listings at or under your price, refreshing with the anvil");
    }

    public static AuctionSniper get() {
        return Modules.get(AuctionSniper.class);
    }

    @Override
    public boolean hiddenInStealth() {
        // Its panel shows the client in a server's menu.
        return true;
    }

    @Override
    public String getInfo() {
        return running ? "sniping" : null;
    }

    @Override
    protected void onDisable() {
        if (running) stop("Switched off", false);
    }

    // ---- What the panel asks

    public boolean running() {
        return running;
    }

    public String status() {
        return status;
    }

    public boolean statusBad() {
        return statusBad;
    }

    public int bought() {
        return bought;
    }

    public int missed() {
        return missed;
    }

    public double spent() {
        return spent;
    }

    /** The price is apiece rather than for the whole listing. */
    public boolean perItem() {
        return perItem.get();
    }

    public int refreshes() {
        return refreshes;
    }

    /** Refreshes over the last two seconds, per second. */
    public float refreshRate() {
        long now = System.currentTimeMillis();
        while (!refreshTimes.isEmpty() && now - refreshTimes.peekFirst() > 2000) refreshTimes.pollFirst();
        return refreshTimes.size() / 2f;
    }

    /** How long the server takes to answer a refresh, smoothed, in ms; negative before the first. */
    public double pageMs() {
        return rtt;
    }

    public List<Bought> recent() {
        return List.copyOf(recent);
    }

    /** The budget left, or NaN with none set. */
    public double budgetLeft() {
        double b = parse(budget.get());
        return Double.isNaN(b) ? Double.NaN : Math.max(0, b - spent - unsure - pendingCost());
    }

    /** The price typed in, or NaN if it isn't one. */
    public double maxPriceValue() {
        return parse(maxPrice.get());
    }

    /** Starts on the auction house menu that's open. */
    public void start() {
        if (running || !inGame()) return;
        if (!isEnabled()) {
            setEnabled(true);
            if (!isEnabled()) return;
        }
        double max = maxPriceValue();
        if (Double.isNaN(max)) {
            say("Type the most you'll pay first", true);
            return;
        }
        AbstractContainerMenu menu = auctionMenu();
        if (menu == null) {
            say("Open /ah first", true);
            return;
        }
        running = true;
        bought = 0;
        missed = 0;
        spent = 0;
        unsure = 0;
        refreshes = 0;
        refreshTimes.clear();
        recent.clear();
        pending.clear();
        clicked.clear();
        rtt = -1;
        out = null;
        backoff = 0;
        reopens = 0;
        goneSince = 0;
        page = menu.containerId;
        nextRefresh = 0;
        news = true;
        answered = false;
        wholePage = true;
        heldFrame = false;
        serverSaid = null;
        say("Watching for " + what() + " at $" + AuctionBuyer.money(max) + " or less", false);
        AuctionBuyer.log("Sniping " + what() + " at $" + AuctionBuyer.money(max) + (perItem.get() ? " each" : "") + " or less"
            + (Double.isNaN(parse(budget.get())) ? "" : ", budget $" + AuctionBuyer.money(parse(budget.get()))));
    }

    public void stop(String why, boolean bad) {
        if (!running) return;
        running = false;
        out = null;
        clicked.clear();
        say(why + (bought > 0 ? " (bought " + bought + ")" : ""), bad);
        AuctionBuyer.log("Sniper stopped: " + why + "; bought " + bought + " for $" + AuctionBuyer.money(spent) + ", missed " + missed
            + ", " + refreshes + " refreshes");
    }

    private void say(String text, boolean bad) {
        status = text;
        statusBad = bad;
        statusAt = System.currentTimeMillis();
    }

    private String what() {
        String filter = item.get().strip();
        return filter.isEmpty() ? "anything" : filter;
    }

    // ---- From the packet handlers (game thread) and chat

    /**
     * The server sent the open menu something: its whole contents ({@code slot} -1) or one slot. Only
     * noted here; the frame hook acts on it once every packet of this frame is in.
     */
    public static void heard(int containerId, int slot) {
        AuctionSniper self = get();
        if (self == null || !self.running || mc.player == null) return;
        if (containerId == 0 || containerId != mc.player.containerMenu.containerId) return;
        self.news = true;
        if (slot < 0) self.wholePage = true;
        Out o = self.out;
        if (o != null && (slot < 0 || slot == o.slot || containerId != o.containerId)) self.answered = true;
    }

    /** The cursor was set straight by the server: its answer to a click. */
    public static void heardCursor() {
        AuctionSniper self = get();
        if (self != null && self.running && self.out != null) self.answered = true;
    }

    public static void onServerMessage(String text) {
        AuctionSniper self = get();
        if (self == null || !self.running) return;
        long now = System.currentTimeMillis();
        // Donut says it in chat and the action bar both: once is enough.
        if (text.equals(lastSaid) && now - lastSaidAt < 250) return;
        lastSaid = text;
        lastSaidAt = now;
        serverSaid = text;
    }

    // ---- Every frame (after the frame's packets)

    @Override
    public void onFrame() {
        if (!running) return;
        if (Stealth.active()) {
            // Its panel's hidden: nothing runs out of sight.
            stop("Stealth Mode", false);
            return;
        }
        long now = System.currentTimeMillis();
        String said = serverSaid;
        serverSaid = null;
        if (said != null && answer(said, now)) return;
        settle(now);
        if (!running) return;

        AbstractContainerMenu menu = auctionMenu();
        if (menu == null) {
            elsewhere(now);
            news = false;
            wholePage = false;
            answered = false;
            return;
        }
        goneSince = 0;
        reopens = 0;
        if (menu.containerId != page) {
            // A new page (the anvil reopened the menu, or /ah did): everything on it is new.
            page = menu.containerId;
            clicked.clear();
            if (out != null) answered = true;
        }
        if (answered && out != null) {
            if (out.kind == Kind.ANVIL) {
                double took = now - out.at;
                rtt = rtt < 0 ? took : rtt * 0.7 + took * 0.3;
                refreshes++;
                refreshTimes.addLast(now);
                // Listings bought or missed since the last refresh are off this page now.
                clicked.clear();
            }
            out = null;
            nextRefresh = now + Math.round(delay.get() * 1000) + backoff;
        }
        // In-place updates can come a slot at a time: let a frame go by in case more of the page is coming.
        boolean partial = news && !wholePage;
        answered = false;
        wholePage = false;
        news = false;
        if (out != null) {
            if (now - out.at < (out.kind == Kind.ANVIL ? anvilWait() : CLICK_WAIT)) return;
            // No answer: the page is taken as it is.
            out = null;
            nextRefresh = now;
        }
        if (partial && !heldFrame) {
            heldFrame = true;
            return;
        }
        heldFrame = false;

        if (snipe(menu, now) || !running) return;
        if (now >= nextRefresh && now - lastAnvil >= MIN_GAP) refresh(menu, now);
    }

    /** A refresh not answered after this long is taken as one where nothing changed. */
    private long anvilWait() {
        return rtt < 0 ? 600 : Math.max(250, Math.round(rtt * 3));
    }

    /** Clicks the best listing on the page that's at or under the price, if any; true if it clicked one. */
    private boolean snipe(AbstractContainerMenu menu, long now) {
        double max = maxPriceValue();
        if (Double.isNaN(max)) {
            stop("No price to snipe at", true);
            return false;
        }
        double cap = parse(budget.get());
        double committed = spent + unsure + pendingCost();
        if (!Double.isNaN(cap) && committed >= cap) {
            if (pending.isEmpty()) stop("Budget used up", false);
            return false;
        }
        int listings = listingSlots(menu);
        Slot best = null;
        double bestCost = Double.MAX_VALUE;
        double bestPrice = 0;
        boolean noRoom = false;
        int free = freeSlots() - pending.size();
        for (int i = 0; i < listings; i++) {
            Slot slot = menu.slots.get(i);
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            ItemStack before = clicked.get(i);
            if (before != null && ItemStack.matches(before, stack)) continue;
            double[] p = AuctionBuyer.price(stack);
            if (p == null) continue;
            double cost = perItem.get() ? p[1] : p[0];
            if (cost > max || !wanted(stack)) continue;
            if (!Double.isNaN(cap) && committed + p[0] > cap) continue;
            if (free <= 0 && !merges(stack)) {
                noRoom = true;
                continue;
            }
            // The best deal first: the furthest under the price, apiece.
            if (p[1] < bestCost) {
                best = slot;
                bestCost = p[1];
                bestPrice = p[0];
            }
        }
        if (best == null) {
            if (noRoom && pending.isEmpty()) stop("Inventory full", true);
            return false;
        }
        ItemStack stack = best.getItem().copy();
        clicked.put(best.index, stack);
        pending.add(new Purchase(stack, bestPrice, now, count(stack.getItem())));
        lastPurchaseClick = now;
        click(menu, best.index, Kind.LISTING, now);
        say("Buying " + describe(stack) + " for $" + AuctionBuyer.money(bestPrice), false);
        AuctionBuyer.log("Sniping slot " + best.index + ": " + describe(stack) + " for $" + AuctionBuyer.money(bestPrice));
        return true;
    }

    private void refresh(AbstractContainerMenu menu, long now) {
        int anvil = -1;
        int size = containerSize(menu);
        for (int i = Math.max(0, size - 9); i < size; i++) {
            if (menu.slots.get(i).getItem().is(Items.ANVIL)) {
                anvil = i;
                break;
            }
        }
        if (anvil < 0) {
            // Nothing to refresh with: it keeps watching what the server sends.
            if (!status.startsWith("No anvil")) say("No anvil to refresh with: watching this page", false);
            nextRefresh = now + 1000;
            return;
        }
        lastAnvil = now;
        click(menu, anvil, Kind.ANVIL, now);
        // What just happened stays up a moment, then it's back to watching.
        if (pending.isEmpty() && now - statusAt > 2500 && !status.startsWith("Watching")) {
            say("Watching for " + what() + " at $" + AuctionBuyer.money(maxPriceValue()) + " or less", false);
        }
    }

    /** Clicks a slot the way a left click does, leaving the menu looking as it did: the server says what's really there. */
    private void click(AbstractContainerMenu menu, int slot, Kind kind, long now) {
        ItemStack shown = menu.slots.get(slot).getItem().copy();
        mc.gameMode.handleInventoryMouseClick(menu.containerId, slot, 0, ClickType.PICKUP, mc.player);
        // The click picked it up here; until the server answers, it stays where it was, and nothing's on the cursor.
        menu.slots.get(slot).set(shown);
        menu.setCarried(ItemStack.EMPTY);
        out = new Out(kind, menu.containerId, slot, now);
        answered = false;
    }

    /** Purchases resolved by what's turned up in the inventory, or by time. */
    private void settle(long now) {
        if (pending.isEmpty() || mc.player == null) return;
        for (int i = 0; i < pending.size(); i++) {
            Purchase p = pending.get(i);
            int have = count(p.item);
            if (have > p.before) {
                int got = Math.min(have - p.before, p.stack.getCount());
                for (int j = i + 1; j < pending.size(); j++) if (pending.get(j).item == p.item) pending.get(j).before += got;
                pending.remove(i--);
                done(p);
            } else if (now - p.at > PURCHASE_WAIT) {
                pending.remove(i--);
                unsure += p.price;
                AuctionBuyer.log("No word on " + describe(p.stack) + " for $" + AuctionBuyer.money(p.price));
            }
        }
        if (backoff > 0 && now - backoffAt > 3000) {
            backoff /= 2;
            backoffAt = now;
            if (backoff < 50) backoff = 0;
        }
    }

    private void done(Purchase p) {
        bought++;
        spent += p.price;
        String what = describe(p.stack);
        recent.addFirst(new Bought(what, p.price));
        while (recent.size() > 4) recent.pollLast();
        say("Bought " + what + " for $" + AuctionBuyer.money(p.price), false);
        AuctionBuyer.log("Sniped " + what + " for $" + AuctionBuyer.money(p.price));
        Notify.send("Auction Sniper", "Bought " + what + " for $" + AuctionBuyer.money(p.price), true, true, false);
    }

    /** A server message about a purchase; true if it ended the run. */
    private boolean answer(String said, long now) {
        String lower = said.toLowerCase(Locale.ROOT);
        if (lower.contains("already bought") || lower.contains("already been bought") || lower.contains("already sold")
            || lower.contains("already been sold") || lower.contains("no longer available") || lower.contains("no longer exists")) {
            if (!pending.isEmpty()) {
                Purchase p = pending.removeFirst();
                missed++;
                say("Missed " + describe(p.stack) + ": someone was quicker", false);
                AuctionBuyer.log("Missed " + describe(p.stack) + ": " + said);
            }
            if (out != null && out.kind == Kind.LISTING) answered = true;
            return false;
        }
        if (lower.contains("enough money") || lower.contains("can't afford") || lower.contains("cannot afford")
            || lower.contains("insufficient") || lower.contains("not enough")) {
            if (!pending.isEmpty()) pending.removeFirst();
            stop("Not enough money", true);
            AuctionBuyer.log("Server said: " + said);
            return true;
        }
        if (lower.contains("too fast") || lower.contains("slow down") || lower.contains("cooldown") || lower.contains("please wait")) {
            backoff = Math.min(2000, Math.max(250, backoff * 2));
            backoffAt = now;
            say("Server asked to slow down: refreshing every " + (Math.round(delay.get() * 1000) + backoff) + " ms", false);
            AuctionBuyer.log("Slowing down: " + said);
        }
        return false;
    }

    /**
     * The auction house isn't what's open: a confirm screen the purchase opened gets its button
     * clicked; a menu closed by a purchase is opened again; anything else ends the run.
     */
    private void elsewhere(long now) {
        boolean afterBuy = now - lastPurchaseClick < 2500;
        AbstractContainerMenu menu = serverMenu();
        if (menu != null) {
            if (!afterBuy) {
                stop("Another menu opened", true);
                return;
            }
            if (out != null && out.kind == Kind.CONFIRM && out.containerId == menu.containerId) {
                if (now - out.at > CLICK_WAIT) stop("The confirm screen stayed open", true);
                return;
            }
            Slot button = empty(menu) ? null : AuctionBuyer.confirmIn(menu);
            if (button != null) {
                AuctionBuyer.log("Confirming, slot " + button.index + ": " + button.getItem().getHoverName().getString());
                click(menu, button.index, Kind.CONFIRM, now);
                lastPurchaseClick = now;
            } else if (now - lastPurchaseClick > CLICK_WAIT) {
                stop("A menu opened with no confirm button", true);
            }
            return;
        }
        if (goneSince == 0) goneSince = now;
        // The purchase closed it (a moment's grace, in case the server is opening it again itself): back to the auction house.
        boolean asked = out != null && out.containerId == -1 && now - out.at < 1000;
        if (afterBuy && reopens < 3 && mc.screen == null && now - goneSince > 250 && !asked) {
            reopens++;
            out = new Out(Kind.ANVIL, -1, -1, now);
            lastPurchaseClick = now;
            mc.player.connection.sendCommand("ah");
            AuctionBuyer.log("> /ah (the menu closed after a purchase)");
            return;
        }
        if (now - goneSince > (afterBuy ? GONE_WAIT : 300)) stop("The auction house closed", false);
    }

    private static boolean empty(AbstractContainerMenu menu) {
        int size = containerSize(menu);
        for (int i = 0; i < size; i++) if (!menu.slots.get(i).getItem().isEmpty()) return false;
        return true;
    }

    // ---- The menu

    /** The open auction house page: a chest menu titled "Auction..." with the anvil in its bottom row. */
    public AbstractContainerMenu auctionMenu() {
        if (mc.player == null || !(mc.screen instanceof AbstractContainerScreen<?> screen) || !isAuctionTitle(screen.getTitle().getString())) return null;
        AbstractContainerMenu menu = screen.getMenu();
        if (menu != mc.player.containerMenu || menu == mc.player.inventoryMenu) return null;
        int size = containerSize(menu);
        if (size < 18) return null;
        // While the anvil's still there it's the page; a confirm screen titled the same has none.
        for (int i = size - 9; i < size; i++) if (menu.slots.get(i).getItem().is(Items.ANVIL)) return menu;
        return page == menu.containerId || !running ? menu : null;
    }

    public static boolean isAuctionTitle(String title) {
        return title.toLowerCase(Locale.ROOT).contains("auction");
    }

    /** The server's menu that's open, whatever it is. */
    private AbstractContainerMenu serverMenu() {
        if (mc.player == null || !(mc.screen instanceof AbstractContainerScreen<?> screen)) return null;
        AbstractContainerMenu menu = screen.getMenu();
        return menu == mc.player.inventoryMenu || menu != mc.player.containerMenu ? null : menu;
    }

    /** Slots of the menu's own (not the inventory below it). */
    private static int containerSize(AbstractContainerMenu menu) {
        int n = 0;
        for (Slot slot : menu.slots) {
            if (slot.container instanceof Inventory) break;
            n++;
        }
        return n;
    }

    /** The listings: every slot of the menu's own but its bottom row of buttons. */
    private static int listingSlots(AbstractContainerMenu menu) {
        return Math.max(0, containerSize(menu) - 9);
    }

    // ---- Listings

    private boolean wanted(ItemStack stack) {
        String filter = item.get().strip().toLowerCase(Locale.ROOT);
        if (filter.isEmpty()) return true;
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().replace('_', ' ');
        String name = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
        for (String term : filter.split(",")) {
            String t = term.strip();
            if (!t.isEmpty() && (name.contains(t) || id.contains(t.replace('_', ' ')))) return true;
        }
        return false;
    }

    private double pendingCost() {
        double sum = 0;
        for (Purchase p : pending) sum += p.price;
        return sum;
    }

    private static String describe(ItemStack stack) {
        String name = stack.getHoverName().getString();
        return stack.getCount() > 1 ? stack.getCount() + "x " + name : name;
    }

    /** "150k", "$1.5M", "2,500,000": a price; NaN for anything else (blank included). */
    public static double parse(String text) {
        String t = text.strip().toLowerCase(Locale.ROOT).replace(" ", "");
        Matcher m = PRICE.matcher(t);
        if (!m.matches()) return Double.NaN;
        double v;
        try {
            v = Double.parseDouble(m.group(1).replace(",", ""));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
        if (m.group(2) != null) {
            v *= switch (m.group(2)) {
                case "k" -> 1e3;
                case "m" -> 1e6;
                default -> 1e9;
            };
        }
        return v;
    }

    // ---- The inventory

    private int freeSlots() {
        Inventory inventory = mc.player.getInventory();
        int n = 0;
        for (int i = 0; i < 36; i++) if (inventory.getItem(i).isEmpty()) n++;
        return n;
    }

    /** Whether the listing would go onto a stack you already have (no free slot needed). */
    private boolean merges(ItemStack listing) {
        Inventory inventory = mc.player.getInventory();
        int room = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (ItemStack.isSameItemSameComponents(stack, listing)) room += stack.getMaxStackSize() - stack.getCount();
        }
        for (Purchase p : pending) if (ItemStack.isSameItemSameComponents(p.stack, listing)) room -= p.stack.getCount();
        return room >= listing.getCount();
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
}
