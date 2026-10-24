package com.cyanite.client.modules.render;

import com.cyanite.client.gui.Theme;
import com.cyanite.client.system.WorldScan;
import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.render.Projection;
import com.cyanite.client.render.Render3D;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Notify;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Villagers through walls, coloured by profession, with what each one is and how far it's
 * levelled. Zombie villagers too (they can be cured), and optionally wandering traders and iron
 * golems. A crowd of villagers out in the wild is usually someone's trading hall.
 */
public class VillagerESP extends Module {
    private static final int PAD = 4;
    private static final int SCAN_INTERVAL = 10;
    private static final String[] LEVELS = {"Novice", "Apprentice", "Journeyman", "Expert", "Master"};

    /** Profession colours, chosen to tell the useful ones apart at a glance. Librarians stand out in white. */
    private static final Map<ResourceKey<VillagerProfession>, Integer> COLORS = Map.ofEntries(
        Map.entry(VillagerProfession.LIBRARIAN, 0xF4F4F4),
        Map.entry(VillagerProfession.ARMORER, 0x9AA6B2),
        Map.entry(VillagerProfession.BUTCHER, 0xE35D5D),
        Map.entry(VillagerProfession.CARTOGRAPHER, 0xE9D27A),
        Map.entry(VillagerProfession.CLERIC, 0xB46CE6),
        Map.entry(VillagerProfession.FARMER, 0xA2D65E),
        Map.entry(VillagerProfession.FISHERMAN, 0x4FB6EA),
        Map.entry(VillagerProfession.FLETCHER, 0xCFA66B),
        Map.entry(VillagerProfession.LEATHERWORKER, 0xB9784B),
        Map.entry(VillagerProfession.MASON, 0xC4BEAE),
        Map.entry(VillagerProfession.SHEPHERD, 0xF2E3B8),
        Map.entry(VillagerProfession.TOOLSMITH, 0x7FAED1),
        Map.entry(VillagerProfession.WEAPONSMITH, 0xFF8F45),
        Map.entry(VillagerProfession.NITWIT, 0x6E9A5C),
        Map.entry(VillagerProfession.NONE, 0x8C919B));

    private final BoolSetting villagers = add(new BoolSetting("Villagers", true));
    private final ModeSetting show = add(new ModeSetting("Show", "All", List.of("All", "Employed", "Librarians", "Master level")));
    private final BoolSetting babies = add(new BoolSetting("Babies", true));
    private final BoolSetting zombies = add(new BoolSetting("Zombie villagers", true));
    private final BoolSetting traders = add(new BoolSetting("Wandering traders", false));
    private final BoolSetting golems = add(new BoolSetting("Iron golems", false));
    private final ModeSetting coloring = add(new ModeSetting("Color", "By profession", List.of("By profession", "One color")));
    private final ColorSetting color = add(new ColorSetting("Villager color", 0x5BE07A));
    private final SliderSetting range = add(new SliderSetting("Range", 128, 16, 512, 16).format(v -> (int) v + "m"));
    private final BoolSetting labels = add(new BoolSetting("Labels", true));
    private final SliderSetting labelRange = add(new SliderSetting("Label range", 48, 8, 256, 8).format(v -> (int) v + "m"));
    private final BoolSetting alert = add(new BoolSetting("Alert when found", false));
    private final BoolSetting toast = add(new BoolSetting("Toast", true));
    private final BoolSetting sound = add(new BoolSetting("Sound", true));
    private final EspStyle style = new EspStyle(false);

    private final Set<UUID> announced = new HashSet<>();
    private ClientLevel level;
    private int ticks;
    private int count;

    private record Shown(Entity entity, int rgb, String name, String detail) {
    }

    public VillagerESP() {
        super(Category.RENDER, "Villager ESP", "Shows villagers through walls, by profession and level");
        style.settings().forEach(this::add);
    }

    @Override
    public String getInfo() {
        return Integer.toString(count);
    }

    @Override
    protected void onDisable() {
        announced.clear();
        level = null;
        count = 0;
    }

    @Override
    public void onTick() {
        if (mc.level != level) {
            level = mc.level;
            announced.clear();
        }
        if (++ticks < SCAN_INTERVAL) return;
        ticks = 0;
        List<Shown> now = shown(1);
        count = now.size();
        if (!alert.get()) {
            for (Shown s : now) announced.add(s.entity.getUUID());
            return;
        }
        List<Shown> fresh = new ArrayList<>();
        for (Shown s : now) {
            if (announced.add(s.entity.getUUID())) fresh.add(s);
        }
        if (!fresh.isEmpty()) announce(fresh);
    }

    private void announce(List<Shown> fresh) {
        fresh.sort(Comparator.comparingDouble(s -> s.entity.distanceToSqr(mc.player)));
        Shown nearest = fresh.getFirst();
        BlockPos p = nearest.entity.blockPosition();
        String where = p.getX() + ", " + p.getY() + ", " + p.getZ() + " (" + Math.round(nearest.entity.distanceTo(mc.player)) + "m)";
        String title = fresh.size() == 1 ? nearest.name + " found" : fresh.size() + " villagers found";
        Notify.send(title, (fresh.size() == 1 ? "" : "Nearest " + nearest.name.toLowerCase(Locale.ROOT) + " at ") + where,
            toast.get(), sound.get(), false);
    }

    /** Everything to draw this frame, nearest last so its label lands on top. */
    private List<Shown> shown(float partialTick) {
        List<Shown> out = new ArrayList<>();
        double maxSq = range.get() * range.get();
        for (Entity entity : WorldScan.entities()) {
            if (!entity.isAlive() || entity.distanceToSqr(mc.player) > maxSq) continue;
            Shown s = describe(entity);
            if (s != null) out.add(s);
        }
        out.sort(Comparator.comparingDouble(s -> -s.entity.distanceToSqr(mc.player)));
        return out;
    }

    private Shown describe(Entity entity) {
        if (entity instanceof Villager villager) {
            if (!villagers.get() || villager.isBaby() && !babies.get()) return null;
            VillagerData data = villager.getVillagerData();
            Holder<VillagerProfession> profession = data.profession();
            boolean employed = !profession.is(VillagerProfession.NONE) && !profession.is(VillagerProfession.NITWIT);
            switch (show.get()) {
                case "Employed" -> {
                    if (!employed) return null;
                }
                case "Librarians" -> {
                    if (!profession.is(VillagerProfession.LIBRARIAN)) return null;
                }
                case "Master level" -> {
                    if (data.level() < 5) return null;
                }
                default -> {
                }
            }
            String name = villager.isBaby() ? "Baby villager" : professionName(profession);
            String detail = employed && !villager.isBaby() ? LEVELS[Math.max(1, Math.min(5, data.level())) - 1] : null;
            return new Shown(entity, rgbFor(profession), name, detail);
        }
        if (entity instanceof ZombieVillager zombie) {
            if (!zombies.get()) return null;
            String detail = zombie.isConverting() ? "curing" : professionName(zombie.getVillagerData().profession()).toLowerCase(Locale.ROOT);
            return new Shown(entity, 0x3FBF5F, "Zombie villager", detail);
        }
        if (entity instanceof WanderingTrader) {
            return traders.get() ? new Shown(entity, 0x4D86FF, "Wandering trader", null) : null;
        }
        if (entity instanceof IronGolem golem) {
            // Built ones are usually guarding a base; village ones spawned there.
            return golems.get() ? new Shown(entity, 0xD9D3C4, "Iron golem", golem.isPlayerCreated() ? "built" : null) : null;
        }
        return null;
    }

    private int rgbFor(Holder<VillagerProfession> profession) {
        if (coloring.is("One color")) return color.get();
        for (Map.Entry<ResourceKey<VillagerProfession>, Integer> e : COLORS.entrySet()) {
            if (profession.is(e.getKey())) return e.getValue();
        }
        return color.get();
    }

    private static String professionName(Holder<VillagerProfession> profession) {
        if (profession.is(VillagerProfession.NONE)) return "Unemployed";
        String name = profession.value().name().getString();
        return name.isEmpty() ? "Villager" : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    @Override
    public void onRender3D(Render3D render) {
        float pt = render.partialTick();
        for (Shown s : shown(pt)) {
            // Boxes follow the entity's interpolated position so they don't lag behind at high frame rates.
            AABB box = s.entity.getBoundingBox().move(s.entity.getPosition(pt).subtract(s.entity.position()));
            style.draw(render, box, s.rgb);
        }
    }

    @Override
    public void onRender2D(GuiGraphics g, float partialTick) {
        if (!labels.get()) return;
        Vec3 eye = mc.player.getEyePosition(partialTick);
        double maxSq = labelRange.get() * labelRange.get();
        int unit = Render2D.scale();
        for (Shown s : shown(partialTick)) {
            Vec3 top = s.entity.getPosition(partialTick).add(0, s.entity.getBbHeight() + 0.35, 0);
            if (top.distanceToSqr(eye) > maxSq) continue;
            double[] screen = Projection.toScreen(top);
            if (screen == null) continue;
            g.pose().pushMatrix();
            g.pose().translate(Math.round(screen[0]) / (float) unit, Math.round(screen[1]) / (float) unit);
            drawLabel(g, s, top.distanceTo(eye));
            g.pose().popMatrix();
        }
    }

    private void drawLabel(GuiGraphics g, Shown shown, double distance) {
        String name = shown.name;
        String detail = shown.detail == null ? "" : shown.detail;
        String dist = Math.round(distance) + "m";
        int w = Text.width(name) + (detail.isEmpty() ? 0 : 5 + Text.width(detail)) + 6 + Text.width(dist) + PAD * 2;
        int h = Text.CAP + PAD * 2;
        int x = -w / 2;
        int y = -h;
        int s = Render2D.scale();
        int rgb = 0xFF000000 | shown.rgb;
        int corner = Math.round(Math.min(Theme.radius(), 3.5f) * s);

        Render2D.pixels(g);
        Render2D.glow(g, x * s, y * s, w * s, h * s, corner, 6 * s, Theme.alpha(rgb, 35 * Theme.glow()));
        Render2D.rect(g, x * s, y * s, w * s, h * s, corner, Theme.BORDER);
        Render2D.rect(g, x * s + 1, y * s + 1, w * s - 2, h * s - 2, Math.max(0, corner - 1), 0xE6101217);
        // A thin bar in the villager's colour along the top ties the label to its box.
        Render2D.rect(g, x * s + corner, y * s + 1, w * s - 2 * corner, Math.max(1, s / 2), 0, rgb);
        Render2D.end(g);

        int tx = x + PAD;
        Text.draw(g, name, tx, y + PAD, Theme.TEXT);
        tx += Text.width(name) + 5;
        if (!detail.isEmpty()) {
            Text.draw(g, detail, tx, y + PAD, Theme.mix(rgb, 0xFFFFFFFF, 0.25f));
            tx += Text.width(detail) + 6;
        } else {
            tx += 1;
        }
        Text.draw(g, dist, tx, y + PAD, Theme.TEXT_FAINT);
    }
}
