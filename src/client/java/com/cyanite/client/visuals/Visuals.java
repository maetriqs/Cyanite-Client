package com.cyanite.client.visuals;

import com.cyanite.client.config.Config;
import com.cyanite.client.render.Render3D;
import com.cyanite.client.system.Stealth;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.Setting;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Visuals tab: cosmetic, client-side changes to how the game looks, in groups under the PvP styles
 * they're for. Every value lives in a visual profile (General, Crystal PvP, Sword PvP, ...) that can follow
 * what you're holding. Saved to {@code cyanite/visuals.json}, apart from the module config.
 */
public final class Visuals {
    static final Logger LOGGER = LogUtils.getLogger();
    public static final String GENERAL = "General";
    public static final List<String> PROFILES = List.of(GENERAL, "Crystal PvP", "Sword PvP", "Axe PvP", "Pot PvP", "UHC / SMP");

    public record Section(String name, String blurb, List<Group> groups) {
    }

    public static final List<Section> SECTIONS = new ArrayList<>();
    /** Every group once, in the order they first show. */
    public static final List<Group> GROUPS = new ArrayList<>();

    /** Picks the profile for what's in your main hand: swords Sword PvP, crystals and obsidian Crystal PvP, and so on. */
    public static final BoolSetting AUTO = new BoolSetting("Switch by held item", false);
    /** With Auto: holding anything else goes back to General (otherwise the last PvP profile stays). */
    public static final BoolSetting FALLBACK = new BoolSetting("Back to General", false);

    private static final Map<String, JsonObject> saved = new LinkedHashMap<>();
    private static String active = GENERAL;
    private static boolean loaded;
    private static boolean dirty;
    private static long changedAt;
    /** Goes up on every change, so features that bake something (textures) know to redo it. */
    private static int version;

    private Visuals() {
    }

    public static void init() {
        section("General Visuals", "How everything looks, whatever you're doing.",
            TotemPop.GROUP, ItemColors.GROUP, LowFire.GROUP, Crosshair.GROUP, BlockHighlight.GROUP, Glint.GROUP,
            DamageTint.GROUP, PotionHud.GROUP, Weather.GROUP, Particles.GENERAL, CustomHud.GROUP, ArmorDurability.GROUP);
        section("Crystal PvP", "End crystals, anchors of obsidian and bedrock, explosions and pops.",
            Crystals.MODEL, Crystals.ANIMATION, BlockColors.GROUP, Particles.EXPLOSION, Crystals.BEAM, TotemPop.GROUP,
            Crystals.HIGHLIGHT, Explosions.GROUP);
        section("Sword PvP", "Swings, trails and hits with a sword in hand.",
            Hands.SWORD_SWING, SwordTrail.GROUP, HitParticles.SWORD, Particles.CRIT, HitColor.GROUP, DamageNumbers.GROUP, Hands.SWORD_ATTACK);
        section("Axe PvP", "Axe swings, shield breaks and hits.",
            Hands.AXE_SWING, ShieldBreak.GROUP, HitParticles.AXE, DamageNumbers.GROUP, Hands.AXE_ATTACK);
        section("Pot PvP", "Splash potions, what's left of everyone's health, and your effects.",
            Particles.SPLASH, Trails.PROJECTILES, PotionHud.GROUP, HealthBars.GROUP, PotionImpact.GROUP);
        section("UHC / SMP", "Bows, golden apples and long fights.",
            Trails.ARROWS, HitParticles.UHC, Hands.GAPPLE, ArmorDurability.GROUP, DamageNumbers.GROUP, ProjectileFx.GROUP);
        section("General PvP", "For every kind of fight.",
            HitColor.GROUP, HurtCamera.GROUP, TargetHighlight.GROUP, Crosshair.GROUP, TotemPop.GROUP, Glint.GROUP,
            Particles.GENERAL, Trails.PROJECTILES);

        for (Group group : GROUPS) {
            group.enabled.onChange(Visuals::changed);
            for (Setting<?> setting : group.settings) setting.onChange(Visuals::changed);
        }
        AUTO.onChange(Visuals::changed);
        FALLBACK.onChange(Visuals::changed);
        load();

        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> save());
        HudElementRegistry.attachElementBefore(VanillaHudElements.CROSSHAIR, Identifier.fromNamespaceAndPath("cyanite_client", "visuals_world"),
            Visuals::renderWorldOverlay);
        CustomHud.register();
        PotionHud.register();
    }

    private static void section(String name, String blurb, Group... groups) {
        for (Group group : groups) {
            group.sections++;
            if (!GROUPS.contains(group)) GROUPS.add(group);
        }
        SECTIONS.add(new Section(name, blurb, List.of(groups)));
    }

    /** False while Stealth holds visuals off. */
    public static boolean live() {
        return !Stealth.active();
    }

    public static int version() {
        return version;
    }

    private static void changed() {
        version++;
        if (!loaded) return;
        dirty = true;
        changedAt = System.currentTimeMillis();
    }

    // ---- Profiles

    public static String active() {
        return active;
    }

    public static void switchTo(String profile) {
        if (profile.equals(active) || !PROFILES.contains(profile)) return;
        saved.put(active, snapshot());
        active = profile;
        apply(saved.get(profile));
        changed();
    }

    /** Copies what's set now into every other profile. */
    public static void copyToAll() {
        JsonObject now = snapshot();
        for (String profile : PROFILES) saved.put(profile, now.deepCopy());
        changed();
    }

    /** Puts this profile's every value back to its default. */
    public static void resetActive() {
        apply(null);
        saved.put(active, snapshot());
        changed();
    }

    /** Which profile Auto picks for what's in hand; null to stay where it is. */
    private static String profileFor(Weapon kind) {
        if (kind == null) return FALLBACK.get() ? GENERAL : null;
        return switch (kind) {
            case CRYSTAL -> "Crystal PvP";
            case SWORD -> "Sword PvP";
            case AXE -> "Axe PvP";
            case POT -> "Pot PvP";
            case UHC -> "UHC / SMP";
        };
    }

    private static JsonObject snapshot() {
        JsonObject json = new JsonObject();
        for (Group group : GROUPS) {
            JsonObject g = new JsonObject();
            g.add(group.enabled.name, group.enabled.toJson());
            for (Setting<?> setting : group.settings) g.add(setting.name, setting.toJson());
            json.add(group.id, g);
        }
        return json;
    }

    /** Defaults first, then whatever {@code json} has; null for all defaults. */
    private static void apply(JsonObject json) {
        for (Group group : GROUPS) {
            group.enabled.reset();
            for (Setting<?> setting : group.settings) setting.reset();
            if (json == null || !(json.get(group.id) instanceof JsonObject g)) continue;
            read(group.enabled, g);
            for (Setting<?> setting : group.settings) read(setting, g);
        }
    }

    private static void read(Setting<?> setting, JsonObject from) {
        JsonElement value = from.get(setting.name);
        if (value != null) setting.fromJson(value);
    }

    // ---- Saving

    private static Path file() {
        return Config.dir().resolve("visuals.json");
    }

    private static void load() {
        Path file = file();
        if (Files.isRegularFile(file)) {
            try {
                JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                if (root.get("profiles") instanceof JsonObject profiles) {
                    for (String profile : PROFILES) {
                        if (profiles.get(profile) instanceof JsonObject p) saved.put(profile, p);
                    }
                }
                if (root.has("active") && PROFILES.contains(root.get("active").getAsString())) active = root.get("active").getAsString();
                if (root.has("auto")) AUTO.fromJson(root.get("auto"));
                if (root.has("fallback")) FALLBACK.fromJson(root.get("fallback"));
            } catch (Exception e) {
                LOGGER.warn("Couldn't read {}, starting Visuals from defaults", file, e);
            }
        }
        apply(saved.get(active));
        loaded = true;
    }

    public static void save() {
        if (!loaded) return;
        saved.put(active, snapshot());
        JsonObject root = new JsonObject();
        root.addProperty("active", active);
        root.add("auto", AUTO.toJson());
        root.add("fallback", FALLBACK.toJson());
        JsonObject profiles = new JsonObject();
        for (String profile : PROFILES) {
            JsonObject p = saved.get(profile);
            if (p != null) profiles.add(profile, p);
        }
        root.add("profiles", profiles);
        Config.write(file(), root);
        dirty = false;
    }

    // ---- Hooks

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (dirty && System.currentTimeMillis() - changedAt > 1000) save();
        Glint.tick();
        HitColor.tick();
        if (mc.player == null || mc.level == null) {
            WorldFx.clear();
            return;
        }
        if (AUTO.get()) {
            String profile = profileFor(Weapon.of(mc.player.getMainHandItem()));
            if (profile != null) switchTo(profile);
        }
        run(DamageNumbers.GROUP, DamageNumbers::tick);
        run(Trails.PROJECTILES, () -> Trails.tick(false));
        run(Trails.ARROWS, () -> Trails.tick(true));
        run(BlockColors.GROUP, BlockColors::tick);
        run(SwordTrail.GROUP, SwordTrail::tick);
        Hands.tick();
        WorldFx.tick();
    }

    /** Called from {@link Render3D#render}, after the modules have queued theirs. */
    public static void render3D(Render3D render) {
        if (!live()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        run(BlockHighlight.GROUP, () -> BlockHighlight.render3D(render));
        run(BlockColors.GROUP, () -> BlockColors.render3D(render));
        run(Crystals.HIGHLIGHT, () -> Crystals.highlight(render));
        run(SwordTrail.GROUP, () -> SwordTrail.render3D(render));
        run(Trails.PROJECTILES, () -> Trails.render3D(render, false));
        run(Trails.ARROWS, () -> Trails.render3D(render, true));
        run(ProjectileFx.GROUP, () -> ProjectileFx.render3D(render));
        run(TargetHighlight.GROUP, () -> TargetHighlight.render3D(render));
        run(DamageNumbers.GROUP, () -> DamageNumbers.render3D(render));
        WorldFx.render3D(render);
    }

    /** Under the HUD: health bars over heads, the hurt tint, the armor readout. */
    private static void renderWorldOverlay(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!live() || mc.player == null) return;
        float partial = delta.getGameTimeDeltaPartialTick(true);
        run(HealthBars.GROUP, () -> HealthBars.render(g, partial));
        run(DamageTint.GROUP, () -> DamageTint.render(g, partial));
        run(ArmorDurability.GROUP, () -> ArmorDurability.renderHud(g));
        WorldFx.render2D(g);
    }

    /** Runs a group's hook if it's on; one that throws is logged and switched off, like a module. */
    public static void run(Group group, Runnable hook) {
        if (!group.on()) return;
        try {
            hook.run();
        } catch (RuntimeException e) {
            failed(group, e);
        }
    }

    /** Like {@link #run}, for a hook that answers whether it took over from the game: false when off or when it threw. */
    public static boolean test(Group group, java.util.function.BooleanSupplier hook) {
        if (!group.on()) return false;
        try {
            return hook.getAsBoolean();
        } catch (RuntimeException e) {
            failed(group, e);
            return false;
        }
    }

    private static void failed(Group group, RuntimeException e) {
        LOGGER.error("Visuals: {} threw, switching it off", group.name, e);
        group.enabled.set(false);
    }

    // ---- Small helpers shared by the features

    /** {@code rgb} with an alpha from 0 to 1. */
    public static int argb(int rgb, double alpha) {
        int a = (int) Math.round(Math.max(0, Math.min(1, alpha)) * 255);
        return a << 24 | rgb & 0xFFFFFF;
    }

    /** Channel by channel product of two ARGB colours. */
    public static int multiply(int a, int b) {
        int al = (a >>> 24) * (b >>> 24) / 255;
        int r = (a >> 16 & 0xFF) * (b >> 16 & 0xFF) / 255;
        int gr = (a >> 8 & 0xFF) * (b >> 8 & 0xFF) / 255;
        int bl = (a & 0xFF) * (b & 0xFF) / 255;
        return al << 24 | r << 16 | gr << 8 | bl;
    }

    /** Straight blend from {@code a} to {@code b}, every channel. */
    public static int lerp(int a, int b, float t) {
        t = Math.max(0, Math.min(1, t));
        int al = Math.round((a >>> 24) + ((b >>> 24) - (a >>> 24)) * t);
        int r = Math.round((a >> 16 & 0xFF) + ((b >> 16 & 0xFF) - (a >> 16 & 0xFF)) * t);
        int gr = Math.round((a >> 8 & 0xFF) + ((b >> 8 & 0xFF) - (a >> 8 & 0xFF)) * t);
        int bl = Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return al << 24 | r << 16 | gr << 8 | bl;
    }
}
