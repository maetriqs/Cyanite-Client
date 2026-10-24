package com.cyanite.client.modules.donut;

import com.cyanite.client.gui.Theme;
import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.mixin.BaseSpawnerAccessor;
import com.cyanite.client.modules.render.EspStyle;
import com.cyanite.client.render.Projection;
import com.cyanite.client.render.Render3D;
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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.SpawnData;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds mob spawners in the chunks you have loaded, reads which mob each one spawns (the same
 * data the game uses to draw the little spinning mob), boxes the ones you want and tells you
 * the moment a new one turns up. Read-only: it only looks at chunk data you've already got.
 */
public class SpawnerFinder extends Module {
    private static final int SCAN_INTERVAL = 10;
    private static final int PAD = 4;

    private final BoolSetting skeleton = add(new BoolSetting("Skeleton", true));
    private final BoolSetting zombie = add(new BoolSetting("Zombie", false));
    private final BoolSetting spider = add(new BoolSetting("Spider", false));
    private final BoolSetting caveSpider = add(new BoolSetting("Cave spider", false));
    private final BoolSetting blaze = add(new BoolSetting("Blaze", false));
    private final BoolSetting other = add(new BoolSetting("Other mobs", false));
    private final BoolSetting unknown = add(new BoolSetting("Unknown type", false));
    private final ColorSetting color = add(new ColorSetting("Color", 0xE8E8E8));
    private final SliderSetting range = add(new SliderSetting("Range", 256, 16, 512, 16).format(v -> (int) v + "m"));
    private final BoolSetting labels = add(new BoolSetting("Labels", true));
    private final BoolSetting toast = add(new BoolSetting("Toast", true));
    private final BoolSetting sound = add(new BoolSetting("Sound", true));
    private final BoolSetting flash = add(new BoolSetting("Flash taskbar", true));
    private final EspStyle style = new EspStyle(true);

    private final Set<BlockPos> announced = new HashSet<>();
    private List<Found> found = List.of();
    private ClientLevel level;
    private int ticks;

    private record Found(BlockPos pos, String mob, SpawnerBlockEntity entity) {
    }

    public SpawnerFinder() {
        super(Category.DONUT, "Spawner Finder", "Finds skeleton spawners and tells you");
        style.settings().forEach(this::add);
    }

    @Override
    public String getInfo() {
        return Integer.toString(found.size());
    }

    @Override
    protected void onEnable() {
        ticks = SCAN_INTERVAL;
    }

    @Override
    protected void onDisable() {
        found = List.of();
        announced.clear();
        level = null;
    }

    @Override
    public void onTick() {
        // A new world (or dimension) means new spawners, and old positions mean nothing there.
        if (mc.level != level) {
            level = mc.level;
            announced.clear();
            found = List.of();
        }
        if (++ticks < SCAN_INTERVAL) return;
        ticks = 0;
        found = scan();

        List<Found> fresh = new ArrayList<>();
        for (Found spawner : found) {
            if (announced.add(spawner.pos)) fresh.add(spawner);
        }
        if (!fresh.isEmpty()) announce(fresh);
    }

    private List<Found> scan() {
        List<Found> out = new ArrayList<>();
        ChunkPos center = mc.player.chunkPosition();
        int radius = mc.options.getEffectiveRenderDistance();

        for (int cx = center.x - radius; cx <= center.x + radius; cx++) {
            for (int cz = center.z - radius; cz <= center.z + radius; cz++) {
                if (!mc.level.hasChunk(cx, cz)) continue;
                LevelChunk chunk = mc.level.getChunk(cx, cz);
                for (BlockEntity entity : chunk.getBlockEntities().values()) {
                    if (!(entity instanceof SpawnerBlockEntity spawner)) continue;
                    EntityType<?> type = mobOf(spawner);
                    if (!wanted(type)) continue;
                    String mob = type == null ? "Unknown" : type.getDescription().getString();
                    out.add(new Found(spawner.getBlockPos(), mob, spawner));
                }
            }
        }
        out.sort(Comparator.comparingDouble(f -> f.pos.distToCenterSqr(mc.player.position())));
        return out;
    }

    /** The mob a spawner spawns, or null if the server didn't send it. */
    public static EntityType<?> mobOf(SpawnerBlockEntity spawner) {
        SpawnData data = ((BaseSpawnerAccessor) spawner.getSpawner()).cyanite$nextSpawnData();
        if (data == null) return null;
        Identifier id = Identifier.tryParse(data.entityToSpawn().getStringOr("id", ""));
        return id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
    }

    private boolean wanted(EntityType<?> type) {
        if (type == null) return unknown.get();
        if (type == EntityType.SKELETON) return skeleton.get();
        if (type == EntityType.ZOMBIE) return zombie.get();
        if (type == EntityType.SPIDER) return spider.get();
        if (type == EntityType.CAVE_SPIDER) return caveSpider.get();
        if (type == EntityType.BLAZE) return blaze.get();
        return other.get();
    }

    private void announce(List<Found> fresh) {
        Found nearest = fresh.getFirst();
        String title = fresh.size() == 1
            ? nearest.mob + " spawner found"
            : fresh.size() + " spawners found";
        Notify.send(title, describe(nearest), toast.get(), sound.get(), flash.get());
        // Every one goes in chat, so none get lost behind the single popup.
        for (int i = 1; i < fresh.size(); i++) {
            Notify.send(fresh.get(i).mob + " spawner", describe(fresh.get(i)), false, false, false);
        }
    }

    private String describe(Found spawner) {
        BlockPos p = spawner.pos;
        return p.getX() + ", " + p.getY() + ", " + p.getZ() + " (" + Math.round(Math.sqrt(p.distToCenterSqr(mc.player.position()))) + "m)";
    }

    @Override
    public void onRender3D(Render3D render) {
        double maxSq = range.get() * range.get();
        for (Found spawner : found) {
            if (spawner.entity.isRemoved()) continue;
            AABB box = new AABB(spawner.pos);
            if (box.getCenter().distanceToSqr(render.camera()) > maxSq) continue;
            style.draw(render, box, color.get());
        }
    }

    @Override
    public void onRender2D(GuiGraphics g, float partialTick) {
        if (!labels.get()) return;
        Vec3 eye = mc.player.getEyePosition(partialTick);
        double maxSq = range.get() * range.get();
        int unit = Render2D.scale();

        // Far ones first, so nearer labels sit on top.
        for (int i = found.size() - 1; i >= 0; i--) {
            Found spawner = found.get(i);
            if (spawner.entity.isRemoved()) continue;
            Vec3 top = Vec3.atCenterOf(spawner.pos).add(0, 0.85, 0);
            if (top.distanceToSqr(eye) > maxSq) continue;
            double[] screen = Projection.toScreen(top);
            if (screen == null) continue;

            g.pose().pushMatrix();
            g.pose().translate(Math.round(screen[0]) / (float) unit, Math.round(screen[1]) / (float) unit);
            drawLabel(g, spawner, top.distanceTo(eye));
            g.pose().popMatrix();
        }
    }

    private void drawLabel(GuiGraphics g, Found spawner, double distance) {
        String name = spawner.mob + " spawner";
        String dist = Math.round(distance) + "m";
        String coords = StreamerMode.mask(spawner.pos.getX() + ", " + spawner.pos.getY() + ", " + spawner.pos.getZ());
        int line = Text.CAP + 4;
        int w = Math.max(Text.width(name) + 6 + Text.width(dist), Text.width(coords)) + PAD * 2;
        int h = PAD * 2 + line * 2 - 4;
        int x = -w / 2;
        int y = -h;
        int s = Render2D.scale();
        int rgb = 0xFF000000 | color.get();
        int corner = Math.round(Math.min(Theme.radius(), 3.5f) * s);

        Render2D.pixels(g);
        Render2D.glow(g, x * s, y * s, w * s, h * s, corner, 6 * s, Theme.alpha(rgb, 35 * Theme.glow()));
        Render2D.rect(g, x * s, y * s, w * s, h * s, corner, Theme.BORDER);
        Render2D.rect(g, x * s + 1, y * s + 1, w * s - 2, h * s - 2, Math.max(0, corner - 1), 0xE6101217);
        // A thin bar in the ESP colour along the top ties the label to its box.
        Render2D.rect(g, x * s + corner, y * s + 1, w * s - 2 * corner, Math.max(1, s / 2), 0, rgb);
        Render2D.end(g);

        Text.draw(g, name, x + PAD, y + PAD, Theme.TEXT);
        Text.draw(g, dist, x + PAD + Text.width(name) + 6, y + PAD, Theme.TEXT_FAINT);
        Text.draw(g, coords, x + PAD, y + PAD + line, Theme.TEXT_DIM);
    }
}
