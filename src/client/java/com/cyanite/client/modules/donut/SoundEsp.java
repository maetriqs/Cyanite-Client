package com.cyanite.client.modules.donut;

import com.cyanite.client.gui.Theme;
import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.modules.misc.StreamerMode;
import com.cyanite.client.render.Projection;
import com.cyanite.client.render.Render3D;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.Notify;
import com.cyanite.client.system.Stealth;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.AbstractChestBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Marks where sounds come from under the ground. On Donut the server fakes every block below Y 16
 * (sometimes 32) while you're up top, so a base under the deepslate doesn't show, but what goes on in
 * it still reaches you as packets: a piston or a chest (64 blocks), a block broken (64), someone's pick
 * cracking a block (32), a mob, a door, footsteps (16, further for loud ones). Each is a ping at its
 * spot; repeats at one place merge into a hotspot with a count, coloured by what kind of thing it was,
 * that fades once it's gone quiet. Read-only: it only reads packets the server sends you anyway.
 */
public class SoundEsp extends Module {
    /** Most hotspots kept; the one heard longest ago makes room. */
    private static final int MAX_SPOTS = 256;
    /** Most labels drawn, nearest first. */
    private static final int MAX_LABELS = 40;
    /** The same thing twice this close together at one spot is one sound sent two ways (a piston's sound and its block event), or one drill swing. */
    private static final long SAME_MS = 150;
    /** How long the ring out of a fresh sound takes to spread and fade. */
    private static final long RIPPLE_MS = 1200;
    private static final long ALERT_GAP_MS = 15_000;
    private static final double BEAM = 24;
    private static final int PAD = 4;

    /** What a sound says is going on down there, and the colour it's marked in. */
    public enum Kind {
        PLAYERS("Mining & players", "Players color", 0x4FC8FF),
        REDSTONE("Redstone & pistons", "Redstone color", 0xFF5050),
        CONTAINERS("Containers", "Containers color", 0xFFB52E),
        MOBS("Mobs & spawners", "Mobs color", 0x7BE05A),
        OTHER("Other sounds", "Other color", 0xC28BFF);

        final String title;
        final String colorName;
        final int rgb;

        Kind(String title, String colorName, int rgb) {
            this.title = title;
            this.colorName = colorName;
            this.rgb = rgb;
        }
    }

    /** What made a sound: its kind, a name to show, and whether caves make it by themselves. */
    record Heard(Kind kind, String name, boolean natural) {
    }

    private static final Heard PISTON = new Heard(Kind.REDSTONE, "Piston", false);
    private static final Heard NOTE_BLOCK = new Heard(Kind.REDSTONE, "Note block", false);
    private static final Heard CHEST = new Heard(Kind.CONTAINERS, "Chest", false);
    private static final Heard ENDER_CHEST = new Heard(Kind.CONTAINERS, "Ender chest", false);
    private static final Heard SHULKER = new Heard(Kind.CONTAINERS, "Shulker box", false);
    private static final Heard SPAWNER = new Heard(Kind.MOBS, "Spawner", false);
    private static final Heard BELL = new Heard(Kind.OTHER, "Bell", false);
    private static final Heard MINING = new Heard(Kind.PLAYERS, "Mining", false);

    /** Blocks whose sounds are redstone at work. Buttons and pressure plates go by the end of their name. */
    private static final Set<String> REDSTONE = Set.of("piston", "dispenser", "lever", "comparator", "tripwire", "note_block", "crafter",
        "copper_bulb", "redstone_torch");
    /** Blocks (and workstation screens) you keep things in or work at, by the name their sounds use. */
    private static final Map<String, String> CONTAINERS = new HashMap<>();
    /** Blocks that make noise by themselves: drips, geodes, the deep dark, lava, trial chambers. */
    private static final Set<String> NATURAL_BLOCKS = Set.of("pointed_dripstone", "amethyst_block", "amethyst_cluster", "large_amethyst_bud",
        "medium_amethyst_bud", "small_amethyst_bud", "sculk", "sculk_sensor", "calibrated_sculk_sensor", "sculk_shrieker", "sculk_catalyst",
        "sculk_vein", "big_dripleaf", "spore_blossom", "lava", "water", "bubble_column", "fire", "trial_spawner", "vault");
    /** Mobs that live in caves anyway: their everyday noises mean nothing, only their getting hurt or dying might. */
    private static final Set<String> CAVE_MOBS = Set.of("bat", "zombie", "zombie_villager", "husk", "drowned", "skeleton", "stray", "bogged",
        "creeper", "spider", "cave_spider", "enderman", "slime", "witch", "silverfish", "axolotl", "glow_squid", "squid", "warden", "breeze",
        "tadpole", "parched");
    /** Entities only a player makes: things thrown, shot, placed or picked up. */
    private static final Set<String> PLAYER_ENTITIES = Set.of("item", "experience_orb", "experience_bottle", "arrow", "fishing_bobber",
        "ender_pearl", "ender_eye", "splash_potion", "lingering_potion", "snowball", "egg", "firework_rocket", "tnt", "leash_knot", "painting",
        "item_frame", "glow_item_frame", "armor_stand", "wind_charge", "boat");
    /** Level events (the server's "this just happened here") that are sounds. Broken blocks are read apart, for the block's name. */
    private static final Map<Integer, Heard> EVENTS = new HashMap<>();

    static {
        for (String[] c : new String[][] {
            {"chest", "Chest"}, {"copper_chest", "Chest"}, {"barrel", "Barrel"}, {"ender_chest", "Ender chest"}, {"shulker_box", "Shulker box"},
            {"chiseled_bookshelf", "Bookshelf"}, {"furnace", "Furnace"}, {"blastfurnace", "Blast furnace"}, {"smoker", "Smoker"},
            {"brewing_stand", "Brewing stand"}, {"anvil", "Anvil"}, {"grindstone", "Grindstone"}, {"smithing_table", "Smithing table"},
            {"enchantment_table", "Enchanting table"}, {"composter", "Composter"}, {"beacon", "Beacon"}, {"conduit", "Conduit"},
            {"respawn_anchor", "Respawn anchor"}, {"stonecutter", "Stonecutter"}, {"loom", "Loom"}, {"cartography_table", "Cartography table"},
            {"decorated_pot", "Decorated pot"}}) {
            CONTAINERS.put(c[0], c[1]);
        }

        event(Kind.REDSTONE, "Dispenser", false, LevelEvent.SOUND_DISPENSER_DISPENSE, LevelEvent.SOUND_DISPENSER_FAIL,
            LevelEvent.SOUND_DISPENSER_PROJECTILE_LAUNCH, LevelEvent.PARTICLES_SHOOT_SMOKE);
        event(Kind.REDSTONE, "Crafter", false, LevelEvent.SOUND_CRAFTER_CRAFT, LevelEvent.SOUND_CRAFTER_FAIL, LevelEvent.PARTICLES_SHOOT_WHITE_SMOKE);
        event(Kind.REDSTONE, "Redstone torch", false, LevelEvent.REDSTONE_TORCH_BURNOUT);
        event(Kind.CONTAINERS, "Anvil", false, LevelEvent.SOUND_ANVIL_BROKEN, LevelEvent.SOUND_ANVIL_USED, LevelEvent.SOUND_ANVIL_LAND);
        event(Kind.CONTAINERS, "Brewing stand", false, LevelEvent.SOUND_BREWING_STAND_BREW);
        event(Kind.CONTAINERS, "Grindstone", false, LevelEvent.SOUND_GRINDSTONE_USED);
        event(Kind.CONTAINERS, "Lectern", false, LevelEvent.SOUND_PAGE_TURN);
        event(Kind.CONTAINERS, "Smithing table", false, LevelEvent.SOUND_SMITHING_TABLE_USED);
        event(Kind.CONTAINERS, "Composter", false, LevelEvent.COMPOSTER_FILL);
        // A spawner only spawns with a player within 16 blocks of it, so one going off means someone's there.
        event(Kind.MOBS, "Spawner", false, LevelEvent.PARTICLES_MOBBLOCK_SPAWN);
        event(Kind.MOBS, "Zombie at a door", false, LevelEvent.SOUND_ZOMBIE_WOODEN_DOOR, LevelEvent.SOUND_ZOMBIE_IRON_DOOR,
            LevelEvent.SOUND_ZOMBIE_DOOR_CRASH);
        event(Kind.MOBS, "Mob converting", false, LevelEvent.SOUND_ZOMBIE_INFECTED, LevelEvent.SOUND_ZOMBIE_CONVERTED,
            LevelEvent.SOUND_ZOMBIE_TO_DROWNED, LevelEvent.SOUND_HUSK_TO_ZOMBIE, LevelEvent.SOUND_SKELETON_TO_STRAY);
        event(Kind.MOBS, "Fireball", false, LevelEvent.SOUND_GHAST_WARNING, LevelEvent.SOUND_GHAST_FIREBALL, LevelEvent.SOUND_BLAZE_FIREBALL);
        event(Kind.MOBS, "Bat", true, LevelEvent.SOUND_BAT_LIFTOFF);
        event(Kind.PLAYERS, "Bone meal", false, LevelEvent.PARTICLES_AND_SOUND_PLANT_GROWTH);
        event(Kind.PLAYERS, "Firework", false, LevelEvent.SOUND_FIREWORK_SHOOT);
        event(Kind.PLAYERS, "Potion", false, LevelEvent.PARTICLES_SPELL_POTION_SPLASH, LevelEvent.PARTICLES_INSTANT_POTION_SPLASH);
        event(Kind.PLAYERS, "Copper waxing", false, LevelEvent.PARTICLES_AND_SOUND_WAX_ON, LevelEvent.PARTICLES_WAX_OFF, LevelEvent.PARTICLES_SCRAPE);
        event(Kind.PLAYERS, "Brushing", false, LevelEvent.PARTICLES_AND_SOUND_BRUSH_BLOCK_COMPLETE);
        event(Kind.PLAYERS, "Mace smash", false, LevelEvent.PARTICLES_SMASH_ATTACK);
        event(Kind.PLAYERS, "Eye of ender", false, LevelEvent.PARTICLES_EYE_OF_ENDER_DEATH);
        event(Kind.OTHER, "Jukebox", false, LevelEvent.SOUND_PLAY_JUKEBOX_SONG, LevelEvent.SOUND_STOP_JUKEBOX_SONG);
        event(Kind.OTHER, "Fire put out", false, LevelEvent.SOUND_EXTINGUISH_FIRE);
        event(Kind.OTHER, "Cauldron drip", false, LevelEvent.SOUND_DRIP_LAVA_INTO_CAULDRON, LevelEvent.SOUND_DRIP_WATER_INTO_CAULDRON);
        event(Kind.OTHER, "End portal frame", false, LevelEvent.END_PORTAL_FRAME_FILL);
        event(Kind.OTHER, "Water evaporating", false, LevelEvent.PARTICLES_WATER_EVAPORATING);
        event(Kind.OTHER, "Lava fizz", true, LevelEvent.LAVA_FIZZ);
        event(Kind.OTHER, "Dripstone", true, LevelEvent.DRIPSTONE_DRIP, LevelEvent.SOUND_POINTED_DRIPSTONE_LAND);
        event(Kind.OTHER, "Sculk", true, LevelEvent.PARTICLES_SCULK_CHARGE, LevelEvent.PARTICLES_SCULK_SHRIEK);
        event(Kind.OTHER, "Trial chamber", true, LevelEvent.PARTICLES_TRIAL_SPAWNER_SPAWN, LevelEvent.PARTICLES_TRIAL_SPAWNER_SPAWN_MOB_AT,
            LevelEvent.PARTICLES_TRIAL_SPAWNER_DETECT_PLAYER, LevelEvent.ANIMATION_TRIAL_SPAWNER_EJECT_ITEM, LevelEvent.ANIMATION_VAULT_ACTIVATE,
            LevelEvent.ANIMATION_VAULT_DEACTIVATE, LevelEvent.ANIMATION_VAULT_EJECT_ITEM, LevelEvent.ANIMATION_SPAWN_COBWEB,
            LevelEvent.PARTICLES_TRIAL_SPAWNER_DETECT_PLAYER_OMINOUS, LevelEvent.PARTICLES_TRIAL_SPAWNER_BECOME_OMINOUS,
            LevelEvent.PARTICLES_TRIAL_SPAWNER_SPAWN_ITEM);
    }

    private static void event(Kind kind, String name, boolean natural, int... types) {
        Heard heard = new Heard(kind, name, natural);
        for (int type : types) EVENTS.put(type, heard);
    }

    private final SliderSetting belowY = add(new SliderSetting("Below Y", 0, -64, 128, 1).format(v -> "Y " + (int) (double) v));
    private final SliderSetting range = add(new SliderSetting("Range", 128, 16, 256, 8).format(v -> (int) (double) v + "m"));
    private final SliderSetting fade = add(new SliderSetting("Fade after", 90, 10, 600, 5).format(SoundEsp::duration));
    private final SliderSetting mergeWithin = add(new SliderSetting("Merge within", 3, 1, 8, 0.5)
        .format(v -> (v % 1 == 0 ? Integer.toString((int) (double) v) : Double.toString(v)) + " blocks"));
    private final BoolSetting[] show = kindSwitches();
    private final BoolSetting ignoreCaves = add(new BoolSetting("Ignore cave noise", true));
    private final SliderSetting nearMe = add(new SliderSetting("Ignore near me", 8, 0, 32, 1).format(v -> v == 0 ? "Off" : (int) (double) v + " blocks"));
    private final SliderSetting nearPlayers = add(new SliderSetting("Ignore near players", 4, 0, 16, 1)
        .format(v -> v == 0 ? "Off" : (int) (double) v + " blocks"));
    private final BoolSetting labels = add(new BoolSetting("Labels", true));
    private final BoolSetting beam = add(new BoolSetting("Beam", true));
    private final BoolSetting tracers = add(new BoolSetting("Tracers", false));
    private final BoolSetting alert = add(new BoolSetting("Alert", false));
    private final ColorSetting[] colors = kindColors();

    private final List<Spot> spots = new ArrayList<>();
    private ClientLevel level;
    private long lastAlert;

    /** One place sounds came from, and everything heard there. */
    private static final class Spot {
        /** The middle of everything heard here. */
        double x, y, z;
        int hits;
        long last;
        Heard latest;
        /** Each thing heard here, by name, and how often. */
        final Map<String, Tally> heard = new LinkedHashMap<>();

        Spot(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        void add(Heard h, double hx, double hy, double hz, long now) {
            if (latest != null && latest.name().equals(h.name()) && now - last < SAME_MS) {
                last = now;
                return;
            }
            hits++;
            x += (hx - x) / hits;
            y += (hy - y) / hits;
            z += (hz - z) / hits;
            Tally t = heard.get(h.name());
            if (t == null && heard.size() < 8) heard.put(h.name(), t = new Tally(h));
            if (t != null) {
                t.count++;
                t.last = now;
            }
            latest = h;
            last = now;
        }

        /** What was heard here most (the latest of equals): the label's title and the colour. */
        Tally main() {
            Tally best = null;
            for (Tally t : heard.values()) {
                if (best == null || t.count > best.count || t.count == best.count && t.last > best.last) best = t;
            }
            return best;
        }

        double half() {
            return 0.5 + 0.08 * Math.min(10, hits - 1);
        }

        double distSq(double px, double py, double pz) {
            double dx = x - px, dy = y - py, dz = z - pz;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    private static final class Tally {
        final Heard heard;
        int count;
        long last;

        Tally(Heard heard) {
            this.heard = heard;
        }
    }

    public SoundEsp() {
        super(Category.DONUT, "Sound ESP", "Marks where sounds come from underground, to find hidden bases");
    }

    private BoolSetting[] kindSwitches() {
        BoolSetting[] out = new BoolSetting[Kind.values().length];
        for (Kind k : Kind.values()) out[k.ordinal()] = add(new BoolSetting(k.title, true));
        return out;
    }

    private ColorSetting[] kindColors() {
        ColorSetting[] out = new ColorSetting[Kind.values().length];
        for (Kind k : Kind.values()) out[k.ordinal()] = add(new ColorSetting(k.colorName, k.rgb));
        return out;
    }

    @Override
    public String getInfo() {
        return Integer.toString(spots.size());
    }

    @Override
    protected void onDisable() {
        spots.clear();
        level = null;
    }

    @Override
    public void onTick() {
        // Another world or dimension: what was heard was somewhere else.
        if (mc.level != level) {
            level = mc.level;
            spots.clear();
        }
        long now = System.currentTimeMillis();
        long keep = Math.round(fade.get() * 1000);
        spots.removeIf(s -> now - s.last > keep);
    }

    // ---- Hearing (packet hooks in ClientPacketListenerMixin, on the game thread)

    private static SoundEsp listening() {
        SoundEsp self = Modules.get(SoundEsp.class);
        return self != null && self.isEnabled() && mc.player != null && mc.level != null ? self : null;
    }

    /** A sound played at a spot (to 1/8 of a block). */
    public static void onSound(Holder<SoundEvent> sound, SoundSource source, double x, double y, double z) {
        SoundEsp self = listening();
        if (self != null && self.wanted(x, y, z)) self.record(classify(sound.value().location(), source), x, y, z);
    }

    /** A sound played by an entity: where it is. */
    public static void onEntitySound(Holder<SoundEvent> sound, SoundSource source, int entityId) {
        SoundEsp self = listening();
        if (self == null) return;
        Entity e = mc.level.getEntity(entityId);
        if (e == null || e == mc.player || !self.wanted(e.getX(), e.getY(), e.getZ())) return;
        self.record(classify(sound.value().location(), source), e.getX(), e.getY(), e.getZ());
    }

    /** A level event: a block broken, a dispenser, an anvil, a spawner going off. Global ones (a wither spawning) say nothing of where. */
    public static void onLevelEvent(int type, BlockPos pos, int data, boolean global) {
        SoundEsp self = listening();
        if (self == null || global) return;
        double x = pos.getX() + 0.5, y = pos.getY() + 0.5, z = pos.getZ() + 0.5;
        if (!self.wanted(x, y, z)) return;
        if (type == LevelEvent.PARTICLES_DESTROY_BLOCK) {
            // Names the real block, even where the server shows you rock.
            BlockState state = Block.stateById(data);
            self.record(new Heard(Kind.PLAYERS, state.isAir() ? "Block mined" : state.getBlock().getName().getString() + " mined", false), x, y, z);
            return;
        }
        Heard heard = EVENTS.get(type);
        if (heard != null) self.record(heard, x, y, z);
    }

    /** A block event: a chest opening or closing, a piston, a note block, a bell, a spawner's timer starting over. */
    public static void onBlockEvent(BlockPos pos, Block block, int b0) {
        SoundEsp self = listening();
        if (self == null) return;
        Heard heard = block == Blocks.PISTON || block == Blocks.STICKY_PISTON ? PISTON
            : block == Blocks.NOTE_BLOCK ? NOTE_BLOCK
            : block == Blocks.BELL ? BELL
            : b0 != 1 ? null
            : block instanceof EnderChestBlock ? ENDER_CHEST
            : block instanceof AbstractChestBlock<?> ? CHEST
            : block instanceof ShulkerBoxBlock ? SHULKER
            : block == Blocks.SPAWNER ? SPAWNER : null;
        double x = pos.getX() + 0.5, y = pos.getY() + 0.5, z = pos.getZ() + 0.5;
        if (heard != null && self.wanted(x, y, z)) self.record(heard, x, y, z);
    }

    /** Someone else's pick starting on a block (the cracks; the server sends them 32 blocks out). Later stages are the same dig. */
    public static void onCracking(BlockPos pos, int progress) {
        SoundEsp self = listening();
        if (self == null || progress != 0) return;
        double x = pos.getX() + 0.5, y = pos.getY() + 0.5, z = pos.getZ() + 0.5;
        if (self.wanted(x, y, z)) self.record(MINING, x, y, z);
    }

    /** Low enough, in range, and nobody's you can see: you, or a player on screen, making it. */
    private boolean wanted(double x, double y, double z) {
        if (y >= belowY.get()) return false;
        double d = mc.player.distanceToSqr(x, y, z);
        double far = range.get(), me = nearMe.get(), them = nearPlayers.get();
        if (d > far * far || d < me * me) return false;
        if (them > 0) {
            for (AbstractClientPlayer p : mc.level.players()) {
                if (p != mc.player && p.distanceToSqr(x, y, z) < them * them) return false;
            }
        }
        return true;
    }

    private void record(Heard heard, double x, double y, double z) {
        if (heard == null || !show[heard.kind().ordinal()].get() || heard.natural() && ignoreCaves.get()) return;
        long now = System.currentTimeMillis();
        Spot spot = null;
        double best = mergeWithin.get() * mergeWithin.get();
        for (Spot s : spots) {
            double d = s.distSq(x, y, z);
            if (d <= best) {
                best = d;
                spot = s;
            }
        }
        boolean fresh = spot == null;
        if (fresh) {
            if (spots.size() >= MAX_SPOTS) spots.remove(spots.stream().min(Comparator.comparingLong(s -> s.last)).orElseThrow());
            spots.add(spot = new Spot(x, y, z));
        }
        spot.add(heard, x, y, z, now);
        if (fresh && alert.get() && now - lastAlert >= ALERT_GAP_MS) {
            lastAlert = now;
            Notify.send("Sound below Y " + (int) (double) belowY.get(), heard.name() + " at " + where(spot) + " (" + distance(spot) + "m)", true, false, false);
        }
    }

    // ---- What a sound is

    /** Sorts a sound by its name ({@code block.chest.open}) and who made it (players, mobs, blocks). Null for ones that say nothing. */
    static Heard classify(Identifier id, SoundSource source) {
        if (source == SoundSource.MUSIC || source == SoundSource.WEATHER || source == SoundSource.UI || source == SoundSource.VOICE) return null;
        String path = id.getPath();
        if (!id.getNamespace().equals("minecraft")) return new Heard(Kind.OTHER, pretty(path.substring(path.lastIndexOf('.') + 1)), false);
        String[] p = path.split("\\.");
        String group = p[0];
        String noun = p.length > 1 ? p[1] : p[0];
        String verb = p.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(p, 2, p.length)) : "";
        boolean player = source == SoundSource.PLAYERS;

        switch (group) {
            case "block" -> {
                switch (verb) {
                    case "break" -> {
                        return new Heard(Kind.PLAYERS, pretty(noun) + " broken", false);
                    }
                    case "place" -> {
                        return new Heard(Kind.PLAYERS, pretty(noun) + " placed", false);
                    }
                    case "hit" -> {
                        return MINING;
                    }
                    // Mobs walk and land too; the sound's source says whose feet they were.
                    case "step", "fall" -> {
                        String what = verb.equals("step") ? "Footsteps" : "Fall";
                        return player ? new Heard(Kind.PLAYERS, what, false)
                            : new Heard(Kind.MOBS, "Mob " + what.toLowerCase(Locale.ROOT), source == SoundSource.HOSTILE || source == SoundSource.AMBIENT);
                    }
                    default -> {
                    }
                }
                if (NATURAL_BLOCKS.contains(noun)) return new Heard(Kind.OTHER, pretty(noun), true);
                if (noun.endsWith("_trapdoor")) return new Heard(Kind.PLAYERS, "Trapdoor", false);
                if (noun.endsWith("_door")) return new Heard(Kind.PLAYERS, "Door", false);
                if (noun.endsWith("fence_gate")) return new Heard(Kind.PLAYERS, "Fence gate", false);
                if (noun.endsWith("_button")) return new Heard(Kind.REDSTONE, "Button", false);
                if (noun.endsWith("_pressure_plate")) return new Heard(Kind.REDSTONE, "Pressure plate", false);
                if (REDSTONE.contains(noun)) return noun.equals("piston") ? PISTON : new Heard(Kind.REDSTONE, pretty(noun), false);
                String container = CONTAINERS.get(noun.startsWith("copper_chest") ? "copper_chest" : noun);
                if (container != null) return new Heard(Kind.CONTAINERS, container, false);
                if (noun.equals("portal")) return new Heard(Kind.OTHER, "Nether portal", false);
                return new Heard(Kind.OTHER, pretty(noun), false);
            }
            case "entity" -> {
                if (noun.equals("player")) return new Heard(Kind.PLAYERS, "Player " + verb, false);
                if (noun.equals("generic")) {
                    return verb.equals("explode") ? new Heard(Kind.OTHER, "Explosion", false) : new Heard(Kind.PLAYERS, pretty(verb), false);
                }
                if (PLAYER_ENTITIES.contains(noun)) return new Heard(Kind.PLAYERS, pretty(noun), false);
                if (noun.endsWith("minecart")) return new Heard(Kind.REDSTONE, "Minecart", false);
                boolean hurt = verb.startsWith("hurt"), death = verb.startsWith("death");
                String name = pretty(noun) + (hurt ? " hurt" : death ? " died" : verb.equals("step") ? " steps" : "");
                return new Heard(Kind.MOBS, name, CAVE_MOBS.contains(noun) && !hurt && !death);
            }
            case "item", "enchant" -> {
                return new Heard(Kind.PLAYERS, noun.equals("flintandsteel") ? "Flint and steel" : pretty(noun), false);
            }
            case "ui" -> {
                String container = CONTAINERS.get(noun);
                return container == null ? null : new Heard(Kind.CONTAINERS, container, false);
            }
            case "music_disc" -> {
                return new Heard(Kind.OTHER, "Jukebox", false);
            }
            case "ambient" -> {
                return new Heard(Kind.OTHER, pretty(noun), true);
            }
            case "music", "weather", "particle", "intentionally_empty" -> {
                return null;
            }
            default -> {
                return new Heard(Kind.OTHER, pretty(noun), false);
            }
        }
    }

    private static String pretty(String id) {
        String s = id.replace('_', ' ');
        if (s.equals("tnt")) return "TNT";
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String duration(double seconds) {
        int s = (int) seconds;
        return s < 60 ? s + "s" : s % 60 == 0 ? s / 60 + " min" : s / 60 + " min " + s % 60 + "s";
    }

    private static String ago(long ms) {
        long s = ms / 1000;
        return s < 1 ? "now" : s < 60 ? s + "s ago" : s / 60 + "m ago";
    }

    private static String where(Spot s) {
        return StreamerMode.mask(Math.round(s.x) + ", " + Math.round(s.y) + ", " + Math.round(s.z));
    }

    private static long distance(Spot s) {
        return Math.round(Math.sqrt(mc.player.distanceToSqr(s.x, s.y, s.z)));
    }

    // ---- Drawing

    /** How much of its time a spot has left, 1 when just heard to 0 when it goes. */
    private float left(Spot s, long now) {
        return Math.max(0, 1 - (now - s.last) / (float) (fade.get() * 1000));
    }

    private int rgb(Spot s) {
        return colors[s.main().heard.kind().ordinal()].get();
    }

    @Override
    public void onRender3D(Render3D render) {
        if (Stealth.active() || spots.isEmpty()) return;
        long now = System.currentTimeMillis();
        double far = range.get() * range.get();
        for (Spot s : spots) {
            if (mc.player.distanceToSqr(s.x, s.y, s.z) > far) continue;
            // Square root: it stays easy to see for most of its time, then goes.
            float a = (float) Math.sqrt(left(s, now));
            if (a <= 0) continue;
            int rgb = rgb(s);
            double half = s.half();
            AABB box = new AABB(s.x - half, s.y - half, s.z - half, s.x + half, s.y + half, s.z + half);
            render.box(box, Theme.alpha(rgb, 55 * a), Theme.alpha(rgb, 12 * a), Theme.alpha(rgb, 235 * a), 1.8f, true);

            // A ring spreading out of a sound just heard, so a fresh one catches the eye.
            long since = now - s.last;
            if (since < RIPPLE_MS) {
                float t = since / (float) RIPPLE_MS;
                render.box(box.inflate(0.2 + 1.6 * t), 0, Theme.alpha(rgb, 220 * (1 - t)), 1.4f, false);
            }
            if (beam.get()) {
                Vec3 base = new Vec3(s.x, s.y + half, s.z);
                render.line(base, base.add(0, BEAM, 0), Theme.alpha(rgb, 200 * a), Theme.alpha(rgb, 0), 2f, true);
            }
            if (tracers.get()) render.tracer(new Vec3(s.x, s.y, s.z), Theme.alpha(rgb, 200 * a), 1.5f, true);
        }
    }

    @Override
    public void onRender2D(GuiGraphics g, float partialTick) {
        if (!labels.get() || Stealth.active() || spots.isEmpty()) return;
        long now = System.currentTimeMillis();
        double far = range.get() * range.get();
        Vec3 eye = mc.player.getEyePosition(partialTick);
        List<Spot> near = new ArrayList<>(spots);
        near.removeIf(s -> mc.player.distanceToSqr(s.x, s.y, s.z) > far);
        near.sort(Comparator.comparingDouble(s -> s.distSq(eye.x, eye.y, eye.z)));
        if (near.size() > MAX_LABELS) near = near.subList(0, MAX_LABELS);

        // The one nearest the crosshair gets the long label: where it is, and what else was heard there.
        var window = mc.getWindow();
        double midX = window.getWidth() / 2.0, midY = window.getHeight() / 2.0;
        double focusOff = 90 * window.getHeight() / 1080.0;
        double[][] screen = new double[near.size()][];
        Spot focused = null;
        for (int i = 0; i < near.size(); i++) {
            Spot s = near.get(i);
            screen[i] = Projection.toScreen(new Vec3(s.x, s.y + s.half() + 0.2, s.z));
            if (screen[i] == null) continue;
            double off = Math.hypot(screen[i][0] - midX, screen[i][1] - midY);
            if (off < focusOff) {
                focusOff = off;
                focused = s;
            }
        }
        // Nearest first: each keeps its place, and one that would cover a nearer one goes up above it.
        List<Label> placed = new ArrayList<>();
        for (int i = 0; i < near.size(); i++) {
            if (screen[i] == null) continue;
            Label l = layout(near.get(i), screen[i], now, near.get(i) == focused);
            for (int tries = 0; tries < placed.size(); tries++) {
                Label hit = null;
                for (Label p : placed) {
                    if (p.covers(l)) {
                        hit = p;
                        break;
                    }
                }
                if (hit == null) break;
                l = l.at(hit.y - l.h - 2);
            }
            placed.add(l);
        }
        // Far ones first, so nearer labels sit on top where they still touch.
        for (int i = placed.size() - 1; i >= 0; i--) draw(g, placed.get(i), now);
    }

    /** A label laid out on screen, in HUD units. */
    private record Label(Spot spot, Tally main, String title, String age, List<String> lines, int x, int y, int w, int h, boolean focused) {
        boolean covers(Label o) {
            return x < o.x + o.w && o.x < x + w && y < o.y + o.h && o.y < y + h;
        }

        Label at(int newY) {
            return new Label(spot, main, title, age, lines, x, newY, w, h, focused);
        }
    }

    private Label layout(Spot s, double[] screen, long now, boolean focused) {
        Tally main = s.main();
        String title = main.heard.name() + (main.count > 1 ? " x" + main.count : "");
        String age = ago(now - s.last);
        List<String> lines = new ArrayList<>(2);
        if (focused) {
            lines.add(where(s) + "  " + distance(s) + "m");
            StringBuilder also = new StringBuilder();
            for (Tally t : s.heard.values()) {
                if (t == main) continue;
                also.append(also.isEmpty() ? "also " : ", ").append(t.heard.name().toLowerCase(Locale.ROOT));
                if (t.count > 1) also.append(" x").append(t.count);
            }
            if (!also.isEmpty()) lines.add(also.toString());
        }

        int line = Text.CAP + 4;
        int w = Text.width(title) + 6 + Text.width(age);
        for (String l : lines) w = Math.max(w, Text.width(l));
        w += PAD * 2;
        int h = PAD * 2 + Text.CAP + 1 + lines.size() * line;
        int scale = Render2D.scale();
        int x = (int) Math.round(screen[0] / scale) - w / 2;
        int y = (int) Math.round(screen[1] / scale) - h - 4;
        return new Label(s, main, title, age, lines, x, y, w, h, focused);
    }

    private void draw(GuiGraphics g, Label l, long now) {
        int rgb = 0xFF000000 | colors[l.main.heard.kind().ordinal()].get();
        // Never quite clear while it's up: faint text is still text.
        float a = l.focused ? 1 : 0.25f + 0.75f * (float) Math.sqrt(left(l.spot, now));
        int x = l.x, y = l.y, w = l.w, h = l.h, line = Text.CAP + 4;
        boolean focused = l.focused;
        int scale = Render2D.scale();

        Render2D.pixels(g);
        Render2D.glow(g, x * scale, y * scale, w * scale, h * scale, 3 * scale, 6 * scale, Theme.alpha(rgb, (focused ? 90 : 45) * Theme.glow() * a));
        Render2D.rect(g, x * scale, y * scale, w * scale, h * scale, 3 * scale, Theme.fade(Theme.BORDER, a));
        Render2D.rect(g, x * scale + 1, y * scale + 1, w * scale - 2, h * scale - 2, 3 * scale - 1, Theme.fade(0xE6101217, a));
        // A bar in the marker's colour along the top ties the label to its box.
        Render2D.rect(g, x * scale + 3 * scale, y * scale + 1, w * scale - 6 * scale, Math.max(1, scale / 2), 0, Theme.fade(rgb, a));
        Render2D.end(g);

        Text.draw(g, l.title, x + PAD, y + PAD + 1, Theme.fade(rgb, a));
        Text.draw(g, l.age, x + PAD + Text.width(l.title) + 6, y + PAD + 1, Theme.fade(Theme.TEXT_DIM, a));
        int ly = y + PAD + 1 + line;
        for (String text : l.lines) {
            Text.draw(g, text, x + PAD, ly, Theme.TEXT_DIM);
            ly += line;
        }
    }
}
