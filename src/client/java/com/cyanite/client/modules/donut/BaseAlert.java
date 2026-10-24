package com.cyanite.client.modules.donut;

import com.cyanite.client.gui.Theme;
import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.render.Projection;
import com.cyanite.client.modules.misc.StreamerMode;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Notify;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrushableBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.SculkCatalystBlockEntity;
import net.minecraft.world.level.block.entity.SculkShriekerBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Watches the storage in your loaded chunks, groups what sits close together, and tells you when
 * a group looks like somebody's base rather than a structure the world made.
 *
 * <p>Every container adds to its group's score. Things only players make (shulker boxes, ender
 * chests, double chests, hoppers) count for a lot. Things worldgen also places (single chests,
 * barrels, furnaces, dispensers) count for a little, and for nothing next to a sign of a natural
 * structure: a spawner, trial chamber, ancient city, village bell or suspicious sand/gravel.
 * Chest minecarts are entities, not blocks, so mineshafts never count at all.
 */
public class BaseAlert extends Module {
    private static final int SCAN_INTERVAL = 20;
    private static final double LINK = 12;
    private static final double STRUCTURE_REACH = 12;
    private static final double SAME_BASE = 48;
    private static final int MAX_CONTAINERS = 4000;

    enum Kind {
        SHULKER("Shulker box", 4, false),
        ENDER("Ender chest", 3, false),
        DOUBLE("Double chest", 2, false),
        HOPPER("Hopper", 2, false),
        CHEST("Chest", 1, true),
        BARREL("Barrel", 1, true),
        CRAFTER("Crafter", 1, true),
        FURNACE("Furnace", 0.5, true),
        DISPENSER("Dispenser", 0.5, true);

        final String label;
        final double weight;
        /** Worldgen places these too, so they count for nothing beside a natural structure. */
        final boolean natural;

        Kind(String label, double weight, boolean natural) {
            this.label = label;
            this.weight = weight;
            this.natural = natural;
        }
    }

    private record Container(BlockPos pos, Kind kind) {
    }

    private record Base(Vec3 center, double score, Map<Kind, Integer> counts, boolean nearStructure) {
    }

    private final SliderSetting threshold = add(new SliderSetting("Threshold", 8, 3, 30, 1).format(v -> (int) v + " points"));
    private final BoolSetting markers = add(new BoolSetting("Markers", true));
    private final ColorSetting color = add(new ColorSetting("Color", 0x6BE675));
    private final BoolSetting toast = add(new BoolSetting("Toast", true));
    private final BoolSetting sound = add(new BoolSetting("Sound", true));
    private final BoolSetting flash = add(new BoolSetting("Flash taskbar", true));

    private final List<Vec3> announced = new ArrayList<>();
    private List<Base> bases = List.of();
    private ClientLevel level;
    private int ticks;

    public BaseAlert() {
        super(Category.DONUT, "Base Alert", "Tells you when storage nearby looks like a base");
    }

    @Override
    public String getInfo() {
        return Integer.toString(bases.size());
    }

    @Override
    protected void onEnable() {
        ticks = SCAN_INTERVAL;
    }

    @Override
    protected void onDisable() {
        bases = List.of();
        announced.clear();
        level = null;
    }

    @Override
    public void onTick() {
        if (mc.level != level) {
            level = mc.level;
            announced.clear();
            bases = List.of();
        }
        if (++ticks < SCAN_INTERVAL) return;
        ticks = 0;

        List<Container> containers = new ArrayList<>();
        List<BlockPos> structures = new ArrayList<>();
        collect(containers, structures);

        List<Base> found = new ArrayList<>();
        for (List<Container> group : cluster(containers)) {
            Base base = score(group, structures);
            if (base.score >= threshold.get()) found.add(base);
        }
        found.sort(Comparator.comparingDouble((Base b) -> b.center.distanceToSqr(mc.player.position())));
        bases = found;

        for (Base base : found) {
            boolean known = false;
            for (Vec3 seen : announced) {
                if (seen.distanceTo(base.center) < SAME_BASE) {
                    known = true;
                    break;
                }
            }
            if (known) continue;
            announced.add(base.center);
            // The popup only fits a line, so it gets where; chat gets what's there too.
            Notify.send("Possible base", where(base), toast.get(), sound.get(), flash.get());
            Notify.send("Contents", summary(base), false, false, false);
        }
    }

    private void collect(List<Container> containers, List<BlockPos> structures) {
        ChunkPos center = mc.player.chunkPosition();
        int radius = mc.options.getEffectiveRenderDistance();
        for (int cx = center.x - radius; cx <= center.x + radius; cx++) {
            for (int cz = center.z - radius; cz <= center.z + radius; cz++) {
                if (!mc.level.hasChunk(cx, cz)) continue;
                LevelChunk chunk = mc.level.getChunk(cx, cz);
                for (BlockEntity entity : chunk.getBlockEntities().values()) {
                    if (isStructureSign(entity)) {
                        structures.add(entity.getBlockPos());
                        continue;
                    }
                    Kind kind = kindOf(entity);
                    if (kind != null && containers.size() < MAX_CONTAINERS) containers.add(new Container(entity.getBlockPos(), kind));
                }
            }
        }
    }

    private static boolean isStructureSign(BlockEntity entity) {
        return entity instanceof SpawnerBlockEntity || entity instanceof TrialSpawnerBlockEntity || entity instanceof VaultBlockEntity
            || entity instanceof SculkShriekerBlockEntity || entity instanceof SculkCatalystBlockEntity
            || entity instanceof BellBlockEntity || entity instanceof BrushableBlockEntity;
    }

    private static Kind kindOf(BlockEntity entity) {
        if (entity instanceof ShulkerBoxBlockEntity) return Kind.SHULKER;
        if (entity instanceof EnderChestBlockEntity) return Kind.ENDER;
        if (entity instanceof ChestBlockEntity) {
            var state = entity.getBlockState();
            if (!(state.getBlock() instanceof ChestBlock) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return Kind.CHEST;
            // Count the pair once, from its left half.
            return state.getValue(ChestBlock.TYPE) == ChestType.LEFT ? Kind.DOUBLE : null;
        }
        if (entity instanceof BarrelBlockEntity) return Kind.BARREL;
        if (entity instanceof HopperBlockEntity) return Kind.HOPPER;
        if (entity instanceof CrafterBlockEntity) return Kind.CRAFTER;
        if (entity instanceof AbstractFurnaceBlockEntity) return Kind.FURNACE;
        if (entity instanceof DispenserBlockEntity) return Kind.DISPENSER;
        return null;
    }

    /** Groups containers that sit within a short hop of each other, chaining through neighbours. */
    private static List<List<Container>> cluster(List<Container> containers) {
        int n = containers.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        double linkSq = LINK * LINK;
        for (int i = 0; i < n; i++) {
            BlockPos a = containers.get(i).pos;
            for (int j = i + 1; j < n; j++) {
                if (a.distSqr(containers.get(j).pos) <= linkSq) parent[find(parent, i)] = find(parent, j);
            }
        }
        Map<Integer, List<Container>> groups = new java.util.HashMap<>();
        for (int i = 0; i < n; i++) groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(containers.get(i));
        return new ArrayList<>(groups.values());
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static Base score(List<Container> group, List<BlockPos> structures) {
        double x = 0, y = 0, z = 0;
        for (Container c : group) {
            x += c.pos.getX() + 0.5;
            y += c.pos.getY() + 0.5;
            z += c.pos.getZ() + 0.5;
        }
        Vec3 center = new Vec3(x / group.size(), y / group.size(), z / group.size());

        boolean nearStructure = false;
        for (BlockPos sign : structures) {
            for (Container c : group) {
                if (c.pos.distSqr(sign) <= STRUCTURE_REACH * STRUCTURE_REACH) {
                    nearStructure = true;
                    break;
                }
            }
            if (nearStructure) break;
        }

        Map<Kind, Integer> counts = new EnumMap<>(Kind.class);
        double score = 0;
        for (Container c : group) {
            counts.merge(c.kind, 1, Integer::sum);
            if (!(nearStructure && c.kind.natural)) score += c.kind.weight;
        }
        return new Base(center, score, counts, nearStructure);
    }

    private String where(Base base) {
        BlockPos p = BlockPos.containing(base.center);
        return p.getX() + ", " + p.getY() + ", " + p.getZ() + " (" + Math.round(base.center.distanceTo(mc.player.position())) + "m)";
    }

    private static String summary(Base base) {
        List<String> parts = new ArrayList<>();
        base.counts.forEach((kind, n) -> parts.add(n + " " + kind.label.toLowerCase(java.util.Locale.ROOT) + (n == 1 ? "" : kind == Kind.SHULKER ? "es" : "s")));
        return String.join(", ", parts);
    }

    @Override
    public void onRender2D(GuiGraphics g, float partialTick) {
        if (!markers.get()) return;
        Vec3 eye = mc.player.getEyePosition(partialTick);
        int unit = Render2D.scale();
        for (int i = bases.size() - 1; i >= 0; i--) {
            Base base = bases.get(i);
            double[] screen = Projection.toScreen(base.center);
            if (screen == null) continue;
            g.pose().pushMatrix();
            g.pose().translate(Math.round(screen[0]) / (float) unit, Math.round(screen[1]) / (float) unit);
            drawMarker(g, base, base.center.distanceTo(eye));
            g.pose().popMatrix();
        }
    }

    private void drawMarker(GuiGraphics g, Base base, double distance) {
        int s = Render2D.scale();
        int rgb = 0xFF000000 | color.get();
        int r = 9 * s;
        int ring = Math.max(2, Math.round(1.5f * s));

        Render2D.pixels(g);
        Render2D.glow(g, -r, -r, 2 * r, 2 * r, r, 6 * s, Theme.alpha(rgb, 70 * Theme.glow()));
        Render2D.rect(g, -r, -r, 2 * r, 2 * r, r, rgb);
        Render2D.rect(g, -r + ring, -r + ring, 2 * (r - ring), 2 * (r - ring), r - ring, 0xF0101217);
        Render2D.end(g);
        String score = Integer.toString((int) Math.round(base.score));
        Text.draw(g, score, -Text.width(score) / 2, -Text.CAP / 2, rgb);

        BlockPos p = BlockPos.containing(base.center);
        String title = "Possible base";
        String dist = Math.round(distance) + "m";
        String coords = StreamerMode.mask(p.getX() + ", " + p.getY() + ", " + p.getZ());
        List<String> lines = new ArrayList<>();
        base.counts.forEach((kind, n) -> lines.add(kind.label + "  ×" + n));

        int pad = 4;
        int line = Text.CAP + 4;
        int width = Math.max(Text.width(title) + 6 + Text.width(dist), Text.width(coords));
        for (String l : lines) width = Math.max(width, Text.width(l));
        int w = width + pad * 2;
        int h = pad * 2 + line * (2 + lines.size()) - 4;
        int x = -w / 2;
        int y = 14;
        int corner = Math.round(Math.min(Theme.radius(), 3.5f) * s);

        Render2D.pixels(g);
        g.fill(-Math.max(1, s / 2), r, Math.max(1, s / 2), y * s, Theme.alpha(rgb, 150));
        Render2D.glow(g, x * s, y * s, w * s, h * s, corner, 6 * s, Theme.alpha(rgb, 30 * Theme.glow()));
        Render2D.rect(g, x * s, y * s, w * s, h * s, corner, Theme.BORDER);
        Render2D.rect(g, x * s + 1, y * s + 1, w * s - 2, h * s - 2, Math.max(0, corner - 1), 0xE6101217);
        Render2D.end(g);

        Text.draw(g, title, x + pad, y + pad, Theme.TEXT);
        Text.draw(g, dist, x + pad + Text.width(title) + 6, y + pad, Theme.TEXT_FAINT);
        Text.draw(g, coords, x + pad, y + pad + line, Theme.TEXT_DIM);
        for (int i = 0; i < lines.size(); i++) Text.draw(g, lines.get(i), x + pad, y + pad + line * (2 + i), Theme.TEXT_DIM);
    }
}
