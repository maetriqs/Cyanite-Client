package com.cyanite.client.modules.world;

import com.cyanite.client.config.Config;
import com.cyanite.client.gui.ChestSearchScreen;
import com.cyanite.client.gui.Theme;
import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.render.Projection;
import com.cyanite.client.render.Render3D;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.KeybindSetting;
import com.cyanite.client.system.setting.SliderSetting;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Remembers what's in every chest you open, so you can ask where your diamonds are. Open a
 * chest, barrel, shulker box, hopper, dispenser or your ender chest and its contents are noted
 * (shulker boxes stored inside are looked into too); the next time you open it, the note's
 * brought up to date, and a container that's gone is forgotten. Press the search key, type
 * what you're after, and the chests with it light up, with how many each has.
 *
 * <p>Only menus you opened by right clicking a container count, so a server's shop or auction
 * menus never get mistaken for a chest. Kept per server and dimension in
 * {@code cyanite/chest-index.json}.
 */
public class ChestIndex extends Module {
    /** How long after you right click a container its menu can take to arrive. */
    private static final long USE_WINDOW = 2000;
    private static final int GOLD = 0xFFFFC94A;

    private final KeybindSetting searchKey = add(new KeybindSetting("Search key", GLFW.GLFW_KEY_K));
    private final BoolSetting shulkers = add(new BoolSetting("Look in shulkers", true));
    private final SliderSetting showFor = add(new SliderSetting("Light up for", 60, 10, 600, 10).format(v -> (int) v + "s"));

    /** One kind of item in a container, and how many. */
    public record Stored(String id, String name, int count, boolean inShulker) {
    }

    /** One container you've opened. */
    private static final class Box {
        BlockPos partner;
        String kind;
        long seen;
        List<Stored> items = new ArrayList<>();
    }

    /** Where some of a search's item is: a spot in the world, or your ender chest (no spot). */
    public record Spot(BlockPos pos, BlockPos partner, String kind, int count, boolean inShulker, double distance) {
    }

    /** One item that matched a search, with every place it is in this world. */
    public record Hit(String id, String name, int total, List<Spot> spots) {
        public ItemStack icon() {
            Item item = BuiltInRegistries.ITEM.getValue(Identifier.tryParse(id));
            return new ItemStack(item);
        }
    }

    private final Map<String, Map<Long, Box>> worlds = new HashMap<>();
    private final Map<String, List<Stored>> enderChests = new HashMap<>();
    private boolean loaded;
    private boolean dirty;
    private BlockPos aimed;
    private long useAt;
    private BlockPos open;
    private String openKind;
    private int ticks;
    private Hit shown;
    private long shownUntil;

    public ChestIndex() {
        super(Category.WORLD, "Chest Index", "Remembers what's in your chests; press the search key to find something");
    }

    @Override
    public String getInfo() {
        return Integer.toString(here().size());
    }

    @Override
    protected void onEnable() {
        load();
    }

    @Override
    protected void onDisable() {
        save();
        shown = null;
    }

    @Override
    public void onKey(int key) {
        if (searchKey.isBound() && key == searchKey.get() && inGame()) mc.setScreen(new ChestSearchScreen(this, key));
    }

    // ---- Noting what's in containers

    /** You right clicked a block (from the game mode itself): if it's a container, its menu is next. */
    public static void onUse(BlockPos pos) {
        ChestIndex self = com.cyanite.client.system.Modules.get(ChestIndex.class);
        if (self == null || !self.isEnabled() || mc.level == null || kind(mc.level.getBlockState(pos)) == null) return;
        self.aimed = pos.immutable();
        self.useAt = System.currentTimeMillis();
    }

    @Override
    public void onTick() {
        LocalPlayer player = mc.player;
        long now = System.currentTimeMillis();
        ticks++;
        boolean menuOpen = mc.screen instanceof AbstractContainerScreen<?> && player.containerMenu != player.inventoryMenu;
        if (!menuOpen) {
            if (open != null) {
                open = null;
                save();
            }
            if (ticks % 40 == 0) forgetGone();
            if (shown != null && now > shownUntil) shown = null;
            return;
        }
        if (open == null) {
            if (aimed == null || now - useAt > USE_WINDOW) return;
            open = aimed;
            openKind = kind(mc.level.getBlockState(open));
            if (openKind == null) {
                open = null;
                return;
            }
        }
        // Contents land a tick or so after the menu opens; keep the note current while it's open.
        List<Stored> items = read(player.containerMenu, player);
        if (openKind.equals("ender chest")) {
            enderChests.put(server(), items);
        } else {
            Box box = here().computeIfAbsent(open.asLong(), k -> new Box());
            box.kind = openKind;
            box.items = items;
            box.seen = now;
            box.partner = partner(open, mc.level.getBlockState(open));
        }
        dirty = true;
    }

    /** The kind of container this is, or null for anything else. */
    private static String kind(BlockState state) {
        var block = state.getBlock();
        if (block instanceof EnderChestBlock) return "ender chest";
        if (block instanceof ChestBlock) return state.getValue(ChestBlock.TYPE) == ChestType.SINGLE ? "chest" : "double chest";
        if (block instanceof BarrelBlock) return "barrel";
        if (block instanceof ShulkerBoxBlock) return "shulker box";
        if (block instanceof HopperBlock) return "hopper";
        if (block instanceof DispenserBlock) return "dispenser";
        return null;
    }

    /** The other half of a double chest, or null. */
    private static BlockPos partner(BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof ChestBlock) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return null;
        Direction to = ChestBlock.getConnectedDirection(state);
        return pos.relative(to).immutable();
    }

    private List<Stored> read(AbstractContainerMenu menu, LocalPlayer player) {
        Map<String, Stored> merged = new LinkedHashMap<>();
        for (Slot slot : menu.slots) {
            if (slot.container == player.getInventory()) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            add(merged, stack, false);
            ItemContainerContents inside = stack.get(DataComponents.CONTAINER);
            if (shulkers.get() && inside != null) {
                for (ItemStack in : inside.nonEmptyItems()) add(merged, in, true);
            }
        }
        return new ArrayList<>(merged.values());
    }

    private static void add(Map<String, Stored> merged, ItemStack stack, boolean inShulker) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        String name = stack.getHoverName().getString();
        String key = id + "|" + name + "|" + inShulker;
        Stored old = merged.get(key);
        merged.put(key, new Stored(id, name, (old == null ? 0 : old.count) + stack.getCount(), inShulker));
    }

    /** Containers near you that aren't there any more: broken or moved. */
    private void forgetGone() {
        Vec3 me = mc.player.position();
        here().entrySet().removeIf(e -> {
            BlockPos pos = BlockPos.of(e.getKey());
            if (!mc.level.hasChunkAt(pos) || Vec3.atCenterOf(pos).distanceTo(me) > 64) return false;
            boolean gone = kind(mc.level.getBlockState(pos)) == null;
            if (gone) dirty = true;
            return gone;
        });
    }

    // ---- Searching

    /** Every item matching {@code query}, most of it first, each with where it is (nearest first). */
    public List<Hit> search(String query) {
        String q = query.strip().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return List.of();
        // "diamonds" should find diamond.
        String single = q.endsWith("es") && q.length() > 3 ? q.substring(0, q.length() - 2) : q.endsWith("s") && q.length() > 2 ? q.substring(0, q.length() - 1) : q;
        Vec3 me = mc.player.position();
        Map<String, Hit> hits = new LinkedHashMap<>();
        Map<String, List<Spot>> spots = new HashMap<>();
        Map<String, Integer> totals = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        Map<String, String> ids = new HashMap<>();
        java.util.function.BiConsumer<Stored, Spot> note = (s, spot) -> {
            String key = s.id + "|" + s.name;
            spots.computeIfAbsent(key, k -> new ArrayList<>()).add(spot);
            totals.merge(key, s.count, Integer::sum);
            names.put(key, s.name);
            ids.put(key, s.id);
        };
        for (Map.Entry<Long, Box> e : here().entrySet()) {
            BlockPos pos = BlockPos.of(e.getKey());
            Box box = e.getValue();
            for (Stored s : box.items) {
                if (!matches(s, q, single)) continue;
                note.accept(s, new Spot(pos, box.partner, box.kind, s.count, s.inShulker, Vec3.atCenterOf(pos).distanceTo(me)));
            }
        }
        for (Stored s : enderChests.getOrDefault(server(), List.of())) {
            if (matches(s, q, single)) note.accept(s, new Spot(null, null, "ender chest", s.count, s.inShulker, 0));
        }
        for (String key : spots.keySet()) {
            List<Spot> list = spots.get(key);
            // The same chest can hold an item loose and in a shulker: one row each is fine, nearest first.
            list.sort(Comparator.comparingDouble(Spot::distance));
            hits.put(key, new Hit(ids.get(key), names.get(key), totals.get(key), list));
        }
        List<Hit> out = new ArrayList<>(hits.values());
        out.sort(Comparator.comparingInt(Hit::total).reversed());
        return out;
    }

    private static boolean matches(Stored s, String q, String single) {
        String name = s.name.toLowerCase(Locale.ROOT);
        String id = s.id.substring(s.id.indexOf(':') + 1);
        String under = q.replace(' ', '_');
        String singleUnder = single.replace(' ', '_');
        return name.contains(q) || name.contains(single) || id.contains(under) || id.contains(singleUnder);
    }

    /** Lights up where this item is, for a while. */
    public void show(Hit hit) {
        shown = hit;
        shownUntil = System.currentTimeMillis() + (long) (showFor.get() * 1000);
    }

    public void clearShown() {
        shown = null;
    }

    public Hit shown() {
        return shown;
    }

    /** Whether any container you've opened in this world is within {@code radius} of x, z. */
    public boolean anyNear(int x, int z, int radius) {
        for (long key : here().keySet()) {
            BlockPos p = BlockPos.of(key);
            if (Math.hypot(p.getX() - x, p.getZ() - z) <= radius) return true;
        }
        return false;
    }

    /** How many containers it knows about in this world. */
    public int indexed() {
        return here().size() + (enderChests.containsKey(server()) ? 1 : 0);
    }

    // ---- Drawing what's lit up

    @Override
    public void onRender3D(Render3D render) {
        if (shown == null) return;
        float pulse = (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 220.0));
        for (Spot spot : shown.spots) {
            if (spot.pos == null) continue;
            AABB box = new AABB(spot.pos);
            if (spot.partner != null) box = box.minmax(new AABB(spot.partner));
            render.box(box.inflate(0.02), (int) (30 + 40 * pulse) << 24 | (GOLD & 0xFFFFFF), GOLD, 2f, true);
        }
        Spot nearest = shown.spots.stream().filter(s -> s.pos != null).findFirst().orElse(null);
        if (nearest != null) render.tracer(Vec3.atCenterOf(nearest.pos), Theme.fade(GOLD, 0.7f), 1.5f, true);
    }

    @Override
    public void onRender2D(GuiGraphics g, float partialTick) {
        if (shown == null) return;
        int unit = Render2D.scale();
        Vec3 eye = mc.player.getEyePosition(partialTick);
        for (Spot spot : shown.spots) {
            if (spot.pos == null) continue;
            Vec3 top = Vec3.atCenterOf(spot.pos).add(0, 0.9, 0);
            double[] screen = Projection.toScreen(top);
            if (screen == null) continue;
            String label = "x" + spot.count + (spot.inShulker ? " in shulker" : "");
            String dist = Math.round(top.distanceTo(eye)) + "m";
            g.pose().pushMatrix();
            g.pose().translate(Math.round(screen[0]) / (float) unit, Math.round(screen[1]) / (float) unit);
            int w = Text.width(label) + 6 + Text.width(dist) + 8;
            int h = Text.CAP + 6;
            int x = -w / 2;
            int s = Render2D.scale();
            Render2D.pixels(g);
            Render2D.rect(g, x * s, -h * s, w * s, h * s, 3 * s, 0xE6101217);
            Render2D.rect(g, x * s + 3 * s, -h * s + 1, w * s - 6 * s, Math.max(1, s / 2), 0, GOLD);
            Render2D.end(g);
            Text.draw(g, label, x + 4, -h + 3, GOLD);
            Text.draw(g, dist, x + 4 + Text.width(label) + 6, -h + 3, Theme.TEXT_FAINT);
            g.pose().popMatrix();
        }
    }

    // ---- Keeping it

    private Map<Long, Box> here() {
        if (!loaded) load();
        return worlds.computeIfAbsent(worldKey(), k -> new HashMap<>());
    }

    private static String server() {
        if (mc.getCurrentServer() != null) return mc.getCurrentServer().ip.toLowerCase(Locale.ROOT);
        if (mc.getSingleplayerServer() != null) return "singleplayer:" + mc.getSingleplayerServer().getWorldData().getLevelName();
        return "unknown";
    }

    private static String worldKey() {
        return server() + "|" + (mc.level == null ? "?" : mc.level.dimension().identifier());
    }

    private static Path file() {
        return Config.dir().resolve("chest-index.json");
    }

    private void load() {
        loaded = true;
        worlds.clear();
        enderChests.clear();
        try {
            if (!Files.exists(file())) return;
            JsonObject root = new Gson().fromJson(Files.readString(file(), StandardCharsets.UTF_8), JsonObject.class);
            if (root == null) return;
            if (root.get("worlds") instanceof JsonObject ws) {
                for (Map.Entry<String, JsonElement> w : ws.entrySet()) {
                    Map<Long, Box> boxes = new HashMap<>();
                    for (Map.Entry<String, JsonElement> b : w.getValue().getAsJsonObject().entrySet()) {
                        JsonObject o = b.getValue().getAsJsonObject();
                        Box box = new Box();
                        box.kind = o.get("kind").getAsString();
                        box.seen = o.get("seen").getAsLong();
                        if (o.has("partner")) box.partner = BlockPos.of(o.get("partner").getAsLong());
                        box.items = readItems(o.getAsJsonArray("items"));
                        boxes.put(Long.parseLong(b.getKey()), box);
                    }
                    worlds.put(w.getKey(), boxes);
                }
            }
            if (root.get("ender") instanceof JsonObject ender) {
                for (Map.Entry<String, JsonElement> e : ender.entrySet()) enderChests.put(e.getKey(), readItems(e.getValue().getAsJsonArray()));
            }
        } catch (Exception e) {
            // A damaged file starts the index fresh rather than stopping it.
        }
    }

    private static List<Stored> readItems(JsonArray array) {
        List<Stored> out = new ArrayList<>();
        for (JsonElement el : array) {
            JsonArray a = el.getAsJsonArray();
            out.add(new Stored(a.get(0).getAsString(), a.get(1).getAsString(), a.get(2).getAsInt(), a.get(3).getAsBoolean()));
        }
        return out;
    }

    private static JsonArray writeItems(List<Stored> items) {
        JsonArray out = new JsonArray();
        for (Stored s : items) {
            JsonArray a = new JsonArray();
            a.add(s.id);
            a.add(s.name);
            a.add(s.count);
            a.add(s.inShulker);
            out.add(a);
        }
        return out;
    }

    private void save() {
        if (!dirty || !loaded) return;
        dirty = false;
        JsonObject root = new JsonObject();
        JsonObject ws = new JsonObject();
        worlds.forEach((key, boxes) -> {
            if (boxes.isEmpty()) return;
            JsonObject w = new JsonObject();
            boxes.forEach((pos, box) -> {
                JsonObject o = new JsonObject();
                o.addProperty("kind", box.kind);
                o.addProperty("seen", box.seen);
                if (box.partner != null) o.addProperty("partner", box.partner.asLong());
                o.add("items", writeItems(box.items));
                w.add(Long.toString(pos), o);
            });
            ws.add(key, w);
        });
        root.add("worlds", ws);
        JsonObject ender = new JsonObject();
        enderChests.forEach((key, items) -> ender.add(key, writeItems(items)));
        root.add("ender", ender);
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), new GsonBuilder().create().toJson(root), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // Tried again on the next change.
            dirty = true;
        }
    }
}
