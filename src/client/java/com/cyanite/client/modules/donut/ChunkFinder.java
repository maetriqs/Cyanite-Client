package com.cyanite.client.modules.donut;

import com.cyanite.client.config.Config;
import com.cyanite.client.system.WorldScan;
import com.cyanite.client.gui.Theme;
import com.cyanite.client.gui.render.Render2D;
import com.cyanite.client.gui.render.Text;
import com.cyanite.client.modules.misc.Pets;
import com.cyanite.client.modules.misc.StreamerMode;
import com.cyanite.client.modules.render.Waypoints;
import com.cyanite.client.modules.world.ChestIndex;
import com.cyanite.client.render.Projection;
import com.cyanite.client.render.Render3D;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.ChunkScanner;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.Notify;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.SliderSetting;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.axolotl.Axolotl;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.GrowingPlantHeadBlock;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Chunk Finder: on DonutSMP a base worth going to is under the deepslate, and the server hides
 * everything below Y 0 from anyone above it. This finds the chunks with something under them,
 * two ways. It only reads what the server sends anyway; it sends nothing.
 *
 * <p>Leaks: what gets through the hiding. The server still has to tell your client things about
 * what's down there, and hiding plugins often miss some of it:
 * <ul>
 * <li>Block entities. A chunk arrives with a list of its chests, furnaces, signs, beds and so on.
 *     If that isn't filtered, the client gets them at spots it's been told are solid stone, and
 *     quietly throws them away. This reads them first.</li>
 * <li>Block changes. When someone places or breaks a block down there near you, the update can
 *     carry the real block, and so can the "block broken" particles.</li>
 * <li>Lighting. If it's the real lighting, a torch glows at exactly 14 inside "solid" rock, and a
 *     lantern, glowstone or sea lantern makes a single bright point (lava makes a sheet of them).</li>
 * <li>Air. If the fill keeps the air, a box-shaped room shows in the rock.</li>
 * <li>Entities, sounds and events: item frames, villagers, minecarts; chests, furnaces, pistons,
 *     dispensers, jukeboxes, anvils; another player standing still down there.</li>
 * </ul>
 * The first time it sees each kind of leak it says so, and Leak report sums them up.
 *
 * <p>Clues from above, looked for down to where the real world starts: Y 0, or the top of the
 * made-up layers where a server sends some (Donut fills everything under Y 16 with plain deepslate
 * while you're up top, so its ways down end at Y 16 as far as you can see).
 *
 * <p>A base worth finding hides its way in, so what counts is hiding: a sealed
 * shaft (a column of cobble, cobbled deepslate or other fill straight down through natural rock to
 * the depths, where someone dug down and filled it in behind them), a capped shaft (open down to
 * the depths but closed over at the top), a patched seafloor (gravel and sand can't be put back, so
 * a way in under the sea is plugged with stone, dirt or cobble that sits in the gravel, over a hole
 * dug down to the depths that's left open or filled back in), deepslate on its side patching a
 * hole just above the depths (worldgen only stands it up straight), and kelp grown past what
 * worldgen leaves, which only happens where someone stays for hours.
 *
 * <p>Digging in the open counts the other way. A shaft open down to the depths, tunnels near them, torches
 * and ladders: someone mined there, and anything under it is gone already or never was. Those
 * chunks are skipped (Skip dug-out chunks), unless someone's down there right now (sounds, blocks
 * changing, a player staying), since a base that's in use hasn't been raided.
 *
 * <p>Ways in, watched. Players are sent to you only while they're up among the real layers, so
 * another player near you who drops out of sight right over the depths (still online, not out of
 * range) has gone down a way in, and one who appears there has come up one. And a way in already
 * found (a capped or sealed shaft, a patched seafloor) is watched: its lid opened, closed or swapped
 * since means somebody's using it.
 *
 * <p>Checked chunks. Go down among the made-up layers and the server sends the real chunks round
 * you; each one that comes through real is marked checked (with a base or without), and kept, so
 * you can see where you've been and how far round you it shows.
 *
 * <p>Each chunk's clues add up to a score; on Strict a chunk needs a strong clue, or two kinds.
 * Natural structures are left out: spawners, sculk and trial chambers nearby make chests and
 * rooms not count; strongholds, mineshafts and dungeons do the same for shafts, tunnels and fill.
 */
public class ChunkFinder extends Module {
    private static final int BAND = 24;
    private static final int SHAFT_MIN = 10;
    private static final int TUNNEL_MIN = 14;
    /** A sealed shaft: this many layers of fill or more, starting this close to Y 0. */
    private static final int SEAL_MIN = 8;
    private static final int SEAL_BOTTOM = 3;
    private static final int SEAL_TOP = 72;
    /** Deepslate on its side only means a patched hole this close to Y 0; higher up it's someone building. */
    private static final int PATCH_TOP = 12;
    /** Layers of a way down under the seabed that may be wide: the sand and gravel it starts in, a cave it crosses. */
    private static final int WIDE_MAX = 8;
    private static final double STRUCTURE_REACH = 24;
    private static final double NATURAL_REACH = 16;
    private static final long HEARD_FOR = 20 * 60_000;
    /** A player below Y 0 this long, within a few blocks of one spot, is home. */
    private static final long STAY_MS = 45_000;
    /** How near another player has to be for their going down (or coming up) to count: well inside the range players are sent in. */
    private static final double VANISH_REACH = 40;
    /** How far over the depths they can be when they go: right at the bottom of the way down. */
    private static final int VANISH_ABOVE = 3;
    /** How far they have to have come down, lately, for going out of sight to be going down (not teleporting home from a cave). */
    private static final double VANISH_DROP = 1.5;
    private static final long TRAIL_MS = 3_000;
    private static final long VANISH_FOR = 6 * 3_600_000L;
    /** Everyone appears at once in a world you've just come into, or after a teleport of your own; someone who's just joined appears out of nowhere too. */
    private static final long SETTLE_MS = 5_000;
    /** A way in used this long ago still counts. */
    private static final long USED_FOR = 7 * 86_400_000L;
    private static final int WATCH_MAX = 16;
    /** Checked chunks drawn this far round you. */
    private static final int CHECKED_DRAWN = 16;
    private static final int CHECKED_COLOR = 0xFF51CF66;
    private static final int MAX_PER_CLUE = 64;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    enum Clue {
        CONTAINER("hidden container or workstation|hidden containers and workstations", 30, true, 1, 0xFFFF4D4D),
        USED("way in used|ways in used", 30, true, 1, 0xFFFF6B9D, false, true),
        VANISHED("player seen going down or coming up|players seen going down or coming up", 24, true, 1, 0xFFFF8787, false, true),
        REVEALED("block seen below|blocks seen below", 26, true, 1, 0xFFFF6B9D, false, true),
        TORCHLIGHT("torch glowing below|torches glowing below", 24, true, 1, 0xFFFFB347),
        KEPT("thing kept below Y 0|things kept below Y 0", 24, true, 1, 0xFFFF6B6B),
        SEALED("sealed shaft to the depths|sealed shafts to the depths", 26, true, 1, 0xFF4DD4FF),
        CAPPED("capped shaft to the depths|capped shafts to the depths", 24, true, 1, 0xFF4DD4FF),
        SEAFLOOR("patched seafloor|patched seafloor", 24, true, 1, 0xFF3BC9DB),
        STAYING("player staying below Y 0|players staying below Y 0", 22, true, 1, 0xFFFF8787, false, true),
        LAMP("lamp glowing below|lamps glowing below", 18, true, 2, 0xFFFFE066),
        HEARD("sound from below|sounds from below", 18, true, 1, 0xFFE879F9, false, true),
        ROTATED("patched hole at the depths|patched holes at the depths", 10, false, 1, 0xFF9B8CFF),
        ROOM("room below Y 0|rooms below Y 0", 14, false, 1, 0xFF74C0FC),
        CHESTS("hidden chest|hidden chests", 12, false, 2, 0xFFFFA94D),
        TUNNEL("tunnel near the depths|tunnels near the depths", 0, false, 1, 0xFF8A8F98, true, false),
        ANIMALS("animal below Y 0|animals below Y 0", 10, false, 3, 0xFFFFD43B),
        KELP("grown kelp|grown kelp", 8, false, 4, 0xFF63E6BE),
        // Digging in the open: counts against a chunk, not for it.
        SHAFT("open shaft to the depths|open shafts to the depths", 0, false, 1, 0xFF8A8F98, true, false),
        PLACED("torch or ladder near the depths|torches and ladders near the depths", 0, false, 1, 0xFF8A8F98, true, false);

        /** "one|many". */
        final String label;
        final double weight;
        /** Enough on its own, on Strict, when there's enough of it. */
        final boolean strong;
        /** Less than this in one chunk says nothing. */
        final double least;
        final int color;
        /** Someone dug here in the open: the base was raided, or there never was one. */
        final boolean dug;
        /** Someone's down there right now: a base in use, dug out or not. */
        final boolean live;

        Clue(String label, double weight, boolean strong, double least, int color) {
            this(label, weight, strong, least, color, false, false);
        }

        Clue(String label, double weight, boolean strong, double least, int color, boolean dug, boolean live) {
            this.label = label;
            this.weight = weight;
            this.strong = strong;
            this.least = least;
            this.color = color;
            this.dug = dug;
            this.live = live;
        }
    }

    /** What the server lets through from below Y 0, as told the first time it's seen. */
    enum Leak {
        BLOCK_ENTITIES("block entities (chests, furnaces, signs, beds)"),
        CHANGES("real blocks in block changes"),
        LIGHT("real lighting"),
        AIR("air pockets (caves and rooms)"),
        EVENTS("sounds and events"),
        ENTITIES("entities"),
        PLAYERS("players");

        final String what;

        Leak(String what) {
            this.what = what;
        }
    }

    /** One clue: where, what, what exactly (a furnace, a chest sound), and its outline. */
    private record Hit(BlockPos pos, Clue clue, String detail, AABB box) {
        Hit(BlockPos pos, Clue clue) {
            this(pos, clue, null, new AABB(pos));
        }

        Hit(BlockPos pos, Clue clue, String detail) {
            this(pos, clue, detail, new AABB(pos));
        }
    }

    /**
     * One look at a chunk: its clues, the natural structures in it, whether its depths are hidden,
     * and whether air or light came through from them (leaks, if they're hidden).
     */
    private record Scan(List<Hit> hits, List<BlockPos> structures, boolean hidden, boolean air, boolean lit, long at, int floor) {
        /** Its depths came through as they are: nothing made up. */
        boolean real() {
            return floor == 0 && !hidden;
        }
    }

    /** Another player in sight: who, where, whether they're dying (then they go from sight, but not down), and how high they've been lately. */
    private static final class Seen {
        String name;
        Vec3 pos;
        boolean dying;
        /** {when, y}, the last few seconds of it. */
        final Deque<double[]> trail = new ArrayDeque<>();

        double highest() {
            double top = pos.y;
            for (double[] t : trail) top = Math.max(top, t[1]);
            return top;
        }
    }

    /** Someone going down a way in, or coming up one. */
    private record Went(BlockPos pos, String what, long at) {
    }

    /** A block that closes a way in: which kind of way, and what it was last seen as. */
    private static final class Watch {
        final String way;
        String state;

        Watch(String way, String state) {
            this.way = way;
            this.state = state;
        }
    }

    /** A sound or event from below Y 0. */
    private record Heard(BlockPos pos, String what, long at) {
    }

    /** A player seen below Y 0: where they were first, and since when. */
    private record Stay(String name, BlockPos at, long since) {
    }

    /** A chunk marked: where, how sure, why, and (while it's loaded) its clues. */
    private static final class Found {
        final String where;
        final int cx;
        final int cz;
        double score;
        String reasons;
        List<Hit> hits = List.of();
        boolean live;
        /** Its ways in, watched for being opened, closed or swapped. */
        final Map<BlockPos, Watch> watched = new LinkedHashMap<>();
        /** The last time one was, and how. */
        long usedAt;
        String used;

        Found(String where, int cx, int cz, double score, String reasons) {
            this.where = where;
            this.cx = cx;
            this.cz = cz;
            this.score = score;
            this.reasons = reasons;
        }

        Vec3 center() {
            return new Vec3(cx * 16 + 8, 0, cz * 16 + 8);
        }
    }

    /** Natural structures that have ladders, torches, rails or tunnels of their own. */
    private static final Set<Block> STRUCTURE = Set.of(Blocks.COBWEB, Blocks.MOSSY_COBBLESTONE, Blocks.STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS,
        Blocks.CRACKED_STONE_BRICKS, Blocks.CHISELED_STONE_BRICKS, Blocks.INFESTED_STONE, Blocks.INFESTED_STONE_BRICKS,
        Blocks.INFESTED_MOSSY_STONE_BRICKS, Blocks.INFESTED_CRACKED_STONE_BRICKS, Blocks.INFESTED_DEEPSLATE, Blocks.END_PORTAL_FRAME,
        Blocks.RAIL, Blocks.OAK_FENCE, Blocks.DARK_OAK_FENCE, Blocks.SPRUCE_FENCE, Blocks.REINFORCED_DEEPSLATE, Blocks.TRIAL_SPAWNER,
        Blocks.VAULT, Blocks.SCULK_CATALYST, Blocks.SCULK_SHRIEKER, Blocks.SUSPICIOUS_GRAVEL);
    /** What a miner puts up on the way down: lights and ladders. */
    private static final Set<Block> PLACED = Set.of(Blocks.LADDER, Blocks.SCAFFOLDING, Blocks.TORCH, Blocks.WALL_TORCH, Blocks.SOUL_TORCH,
        Blocks.SOUL_WALL_TORCH, Blocks.LANTERN, Blocks.SOUL_LANTERN);
    /** What a hole gets filled back in with: what digging drops, and the dirt and gravel on hand. */
    private static final Set<Block> FILL = Set.of(Blocks.COBBLESTONE, Blocks.COBBLED_DEEPSLATE, Blocks.NETHERRACK, Blocks.DIRT,
        Blocks.COARSE_DIRT, Blocks.GRAVEL, Blocks.ANDESITE, Blocks.DIORITE, Blocks.GRANITE, Blocks.TUFF);
    /** What the bottom of the sea is made of. */
    private static final Set<Block> SEABED = Set.of(Blocks.GRAVEL, Blocks.SAND, Blocks.RED_SAND, Blocks.CLAY, Blocks.SUSPICIOUS_SAND,
        Blocks.SUSPICIOUS_GRAVEL, Blocks.MUD);
    /** What a hole in it gets plugged with, since gravel and sand won't stay put. */
    private static final Set<Block> PLUG = Set.of(Blocks.STONE, Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.COBBLESTONE, Blocks.COBBLED_DEEPSLATE,
        Blocks.DEEPSLATE, Blocks.NETHERRACK, Blocks.ANDESITE, Blocks.DIORITE, Blocks.GRANITE, Blocks.TUFF, Blocks.SMOOTH_STONE,
        Blocks.STONE_BRICKS, Blocks.OBSIDIAN, Blocks.END_STONE, Blocks.BLACKSTONE);
    /** Fill nothing natural puts in a column: one of these in it, at least a few times, and it was placed. */
    private static final Set<Block> PLAYER_FILL = Set.of(Blocks.COBBLESTONE, Blocks.COBBLED_DEEPSLATE, Blocks.NETHERRACK);
    /** Block entities nothing natural makes below Y 0. */
    private static final Set<String> PLAYER_BLOCK_ENTITIES = Set.of("furnace", "blast_furnace", "smoker", "hopper", "dropper", "beacon",
        "shulker_box", "ender_chest", "enchanting_table", "brewing_stand", "barrel", "sign", "hanging_sign", "banner", "bed", "skull",
        "lectern", "jukebox", "crafter", "comparator", "daylight_detector", "conduit", "beehive", "bell", "command_block");
    /** Block entities that mean a dungeon, a mineshaft, the deep dark or a trial chamber: chests round them are theirs. */
    private static final Set<String> NATURAL_BLOCK_ENTITIES = Set.of("mob_spawner", "trial_spawner", "vault", "sculk_sensor",
        "calibrated_sculk_sensor", "sculk_shrieker", "sculk_catalyst", "decorated_pot", "brushable_block");
    /** Sounds that mean somebody's down there, by the start of their name. */
    private static final Map<String, String> SOUNDS = new LinkedHashMap<>();
    /** Level events (the server's "this just happened here") that mean the same. */
    private static final Map<Integer, String> EVENTS = new HashMap<>();

    static {
        for (String[] s : new String[][] {
            {"block.chest.", "chest"}, {"block.barrel.", "barrel"}, {"block.ender_chest.", "ender chest"}, {"block.shulker_box.", "shulker box"},
            {"block.piston.", "piston"}, {"block.dispenser.", "dispenser"}, {"block.note_block.", "note block"}, {"block.anvil.", "anvil"},
            {"block.smithing_table.", "smithing table"}, {"block.grindstone.", "grindstone"}, {"block.furnace.", "furnace"},
            {"block.blastfurnace.", "blast furnace"}, {"block.smoker.", "smoker"}, {"block.composter.", "composter"}, {"block.beacon.", "beacon"},
            {"block.respawn_anchor.", "respawn anchor"}, {"block.portal.", "nether portal"}, {"block.lever.", "lever"},
            {"block.wooden_door.", "door"}, {"block.iron_door.", "door"}, {"block.wooden_trapdoor.", "trapdoor"},
            {"block.iron_trapdoor.", "trapdoor"}, {"block.fence_gate.", "fence gate"}, {"block.wooden_button.", "button"},
            {"block.stone_button.", "button"}, {"block.crafter.", "crafter"}, {"block.enchantment_table.", "enchanting table"},
            {"block.brewing_stand.", "brewing stand"}, {"block.bell.", "bell"}, {"entity.villager.", "villager"},
            {"entity.iron_golem.", "iron golem"}, {"entity.item_frame.", "item frame"}, {"entity.minecart.", "minecart"},
            {"entity.cow.", "animals"}, {"entity.pig.", "animals"}, {"entity.sheep.", "animals"}, {"entity.chicken.", "animals"}}) {
            SOUNDS.put(s[0], s[1]);
        }
        for (int e : new int[] {LevelEvent.SOUND_DISPENSER_DISPENSE, LevelEvent.SOUND_DISPENSER_FAIL, LevelEvent.SOUND_DISPENSER_PROJECTILE_LAUNCH,
            LevelEvent.PARTICLES_SHOOT_SMOKE}) EVENTS.put(e, "dispenser");
        EVENTS.put(LevelEvent.SOUND_PLAY_JUKEBOX_SONG, "jukebox");
        EVENTS.put(LevelEvent.SOUND_STOP_JUKEBOX_SONG, "jukebox");
        EVENTS.put(LevelEvent.SOUND_ANVIL_USED, "anvil");
        EVENTS.put(LevelEvent.SOUND_ANVIL_LAND, "anvil");
        EVENTS.put(LevelEvent.SOUND_ANVIL_BROKEN, "anvil");
        EVENTS.put(LevelEvent.SOUND_BREWING_STAND_BREW, "brewing stand");
        EVENTS.put(LevelEvent.SOUND_GRINDSTONE_USED, "grindstone");
        EVENTS.put(LevelEvent.SOUND_PAGE_TURN, "lectern");
        EVENTS.put(LevelEvent.SOUND_SMITHING_TABLE_USED, "smithing table");
        EVENTS.put(LevelEvent.SOUND_CRAFTER_CRAFT, "crafter");
        EVENTS.put(LevelEvent.SOUND_CRAFTER_FAIL, "crafter");
        EVENTS.put(LevelEvent.PARTICLES_SHOOT_WHITE_SMOKE, "crafter");
        EVENTS.put(LevelEvent.COMPOSTER_FILL, "composter");
        EVENTS.put(LevelEvent.PARTICLES_AND_SOUND_PLANT_GROWTH, "bone meal");
        EVENTS.put(LevelEvent.REDSTONE_TORCH_BURNOUT, "redstone");
        EVENTS.put(LevelEvent.PARTICLES_AND_SOUND_WAX_ON, "copper waxing");
        EVENTS.put(LevelEvent.PARTICLES_WAX_OFF, "copper waxing");
    }

    private final ModeSetting strictness = add(new ModeSetting("Strictness", "Strict", List.of("Strict", "Normal", "Loose")));
    private final SliderSetting radius = add(new SliderSetting("Radius", 12, 2, 32, 1).format(v -> (int) v + " chunks"));
    private final SliderSetting spawnRadius = add(new SliderSetting("Skip near spawn", 500, 0, 5000, 100)
        .format(v -> v == 0 ? "Off" : (int) (double) v + " blocks"));
    private final BoolSetting skipMine = add(new BoolSetting("Skip my stuff", true));
    private final BoolSetting blockEntities = add(new BoolSetting("Hidden block entities", true));
    private final BoolSetting changes = add(new BoolSetting("Block changes", true));
    private final BoolSetting light = add(new BoolSetting("Light from below", true));
    private final BoolSetting rooms = add(new BoolSetting("Rooms below Y 0", true));
    private final BoolSetting kept = add(new BoolSetting("Things below Y 0", true));
    private final BoolSetting staying = add(new BoolSetting("Players staying", true));
    private final BoolSetting heard = add(new BoolSetting("Sounds from below", true));
    private final BoolSetting vanish = add(new BoolSetting("Players going down", true));
    private final BoolSetting watch = add(new BoolSetting("Watch ways in", true));
    private final BoolSetting checked = add(new BoolSetting("Checked chunks", true));
    private final BoolSetting sealedShafts = add(new BoolSetting("Sealed shafts", true));
    private final BoolSetting seafloor = add(new BoolSetting("Patched seafloor", true));
    private final BoolSetting rotated = add(new BoolSetting("Patched holes", true));
    /** Open shafts, tunnels, torches and ladders near Y 0: raided or empty, so skipped. */
    private final BoolSetting skipDug = add(new BoolSetting("Skip dug-out chunks", true));
    private final BoolSetting kelp = add(new BoolSetting("Grown kelp", true));
    private final BoolSetting markClues = add(new BoolSetting("Mark clues", true));
    private final BoolSetting beams = add(new BoolSetting("Beams", true));
    private final BoolSetting labels = add(new BoolSetting("Labels", true));
    private final BoolSetting notify = add(new BoolSetting("Notify", true));
    private final BoolSetting waypoints = add(new BoolSetting("Add waypoints", true));
    private final BoolSetting remember = add(new BoolSetting("Remember chunks", true));
    private final BoolSetting logFile = add(new BoolSetting("Save to file", true));
    private final BoolSetting report = add(new BoolSetting("Leak report", false));
    private final BoolSetting forget = add(new BoolSetting("Forget chunks here", false));
    private final ColorSetting color = add(new ColorSetting("Color", 0xFF5D62));

    private final Map<Long, Scan> scans = new HashMap<>();
    /** What leaked out of each chunk's packets: its block entities and the real blocks seen changing. */
    private final Map<Long, Map<BlockPos, Hit>> leaked = new HashMap<>();
    /** Natural block entities (spawners, sculk, trial chambers): chests near them don't count. */
    private final Map<Long, List<BlockPos>> natural = new HashMap<>();
    /** Trial chambers' spawners and vaults: their copper bulbs light up like lamps. */
    private final Map<Long, List<BlockPos>> trials = new HashMap<>();
    private final Deque<Heard> sounds = new ArrayDeque<>();
    private final Map<UUID, Stay> stays = new HashMap<>();
    /** Each kind of leak seen, by the chunks it was seen in. Kept for the whole session. */
    private final Map<Leak, Set<Long>> leaks = new EnumMap<>(Leak.class);
    /** Leaks from chunks not looked at yet: whether they're leaks depends on whether the chunk came through hidden. */
    private final Map<Long, Set<Leak>> unconfirmed = new HashMap<>();
    private final List<Found> found = new ArrayList<>();
    /** Other players last tick, and when each turned up in the tab list. */
    private final Map<UUID, Seen> others = new HashMap<>();
    private final Map<UUID, Long> listed = new HashMap<>();
    private final Deque<Went> went = new ArrayDeque<>();
    private Vec3 lastMe;
    private long settledAt;
    /** Chunks seen real, by world: when first. */
    private final Map<String, Map<Long, Long>> checkedAt = new HashMap<>();
    private boolean checkedDirty;
    /** Made-up depths seen here: only then does a real chunk mean you've checked it. */
    private boolean hiding;
    private int shownRadius = -1;
    private int pendingRadius = -1;
    private long pendingSince;
    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
    private boolean loaded;
    private ClientLevel lastLevel;
    private int tick;

    public ChunkFinder() {
        super(Category.DONUT, "Chunk Finder", "Finds chunks with a base hidden below Y 0, through what the server leaks");
        forget.onChange(() -> {
            if (!forget.get()) return;
            if (inGame()) {
                String where = where();
                found.removeIf(f -> f.where.equals(where));
                save();
                if (checkedAt.remove(where) != null) {
                    checkedDirty = true;
                    saveChecked();
                }
                Notify.send("Chunk Finder", "Forgot the chunks found here", false, false, false);
            }
            forget.set(false);
        });
        report.onChange(() -> {
            if (!report.get()) return;
            if (inGame()) Notify.send("Chunk Finder", leakReport(), false, false, false);
            report.set(false);
        });
        // Looking at a chunk takes a while (the shafts and tunnels most of all), so it's done on the
        // shared chunk scanner's thread, once when the chunk arrives and again when it changes.
        ChunkScanner.register(new ChunkScanner.Pass<Scan>() {
            @Override
            public boolean active() {
                return isEnabled();
            }

            @Override
            public boolean wants(int cx, int cz) {
                var player = mc.player;
                if (player == null) return false;
                ChunkPos at = player.chunkPosition();
                return Math.max(Math.abs(cx - at.x), Math.abs(cz - at.z)) <= radius.get().intValue() + 1;
            }

            @Override
            public Scan scan(LevelChunk chunk) {
                return ChunkFinder.this.scan(chunk, System.currentTimeMillis());
            }

            @Override
            public void accept(long key, Scan scan) {
                looked(key, scan);
            }
        });
    }

    @Override
    protected void onEnable() {
        settledAt = System.currentTimeMillis();
        others.clear();
        ChunkScanner.rescanAll();
    }

    /** A chunk's been looked at (game thread). */
    private void looked(long key, Scan scan) {
        if (!isEnabled()) return;
        scans.put(key, scan);
        if (!hiding && (scan.floor() > 0 || scan.hidden())) {
            // The first made-up chunk: the real ones already in were checked too.
            hiding = true;
            for (Map.Entry<Long, Scan> e : scans.entrySet()) markChecked(e.getKey(), e.getValue());
        }
        markChecked(key, scan);
        if (scan.hidden() && hiddenView()) {
            if (scan.air()) confirmed(Leak.AIR, key);
            if (scan.lit()) confirmed(Leak.LIGHT, key);
        }
        Set<Leak> waiting = unconfirmed.remove(key);
        if (waiting != null && scan.hidden()) for (Leak l : waiting) confirmed(l, key);
    }

    /** A chunk that came through real, where the server makes things up: you've seen what's really down there. */
    private void markChecked(long key, Scan scan) {
        if (!checked.get() || !hiding || !scan.real() || mc.level.dimension() != Level.OVERWORLD) return;
        if (checkedAt.computeIfAbsent(where(), k -> new HashMap<>()).putIfAbsent(key, System.currentTimeMillis()) == null) checkedDirty = true;
    }

    @Override
    public String getInfo() {
        if (!inGame()) return null;
        String where = where();
        return Long.toString(found.stream().filter(f -> f.where.equals(where)).count());
    }

    @Override
    protected void onDisable() {
        // What came in with the chunks, and what was heard, stays: it won't come again.
        // A different world is noticed on the next tick and clears it.
        scans.clear();
        stays.clear();
        others.clear();
        saveChecked();
    }

    // ---- Leaks

    /** True above Y 0 on a server that hides what's below: anything real from down there is a leak. */
    private boolean hiddenView() {
        return mc.player != null && mc.player.getY() >= 0;
    }

    /**
     * Something real from below Y 0 in this chunk, seen from above it. It's a leak if the chunk's
     * depths came through hidden, which is known once the chunk's been looked at.
     */
    private void leak(Leak leak, long chunk) {
        if (!hiddenView()) return;
        Scan scan = scans.get(chunk);
        if (scan == null) unconfirmed.computeIfAbsent(chunk, k -> EnumSet.noneOf(Leak.class)).add(leak);
        else if (scan.hidden()) confirmed(leak, chunk);
    }

    private void confirmed(Leak leak, long chunk) {
        Set<Long> chunks = leaks.computeIfAbsent(leak, k -> new HashSet<>());
        if (chunks.add(chunk) && chunks.size() == 1 && notify.get()) {
            Notify.send("Chunk Finder", "Leak: the server hides the blocks below Y 0 but still sends " + leak.what, false, false, false);
        }
    }

    private String leakReport() {
        List<String> parts = new ArrayList<>();
        for (Leak leak : Leak.values()) {
            int n = leaks.getOrDefault(leak, Set.of()).size();
            parts.add(leak.what + ": " + (n == 0 ? "none seen" : "in " + n + (n == 1 ? " chunk" : " chunks")));
        }
        long hidden = scans.values().stream().filter(Scan::hidden).count();
        String head = hidden == 0
            ? "No chunks round here have hidden depths (the server isn't hiding them, or you're below Y 0)."
            : "Leaks from below Y 0 this session; " + hidden + " chunks round you have hidden depths.";
        return head + " " + String.join("; ", parts);
    }

    // ---- Scanning the chunks round you

    @Override
    public void onTick() {
        load();
        if (mc.level != lastLevel) {
            lastLevel = mc.level;
            scans.clear();
            sounds.clear();
            stays.clear();
            leaked.clear();
            natural.clear();
            trials.clear();
            unconfirmed.clear();
            others.clear();
            went.clear();
            hiding = checkedAt.containsKey(where());
            shownRadius = -1;
            settledAt = System.currentTimeMillis();
        }
        watchPlayers(System.currentTimeMillis());
        ChunkPos center = mc.player.chunkPosition();
        int r = radius.get().intValue() + 1;
        // A chunk's look lasts as long as the chunk's loaded; ones come into range get asked for.
        scans.keySet().removeIf(key -> !mc.level.hasChunk(ChunkPos.getX(key), ChunkPos.getZ(key)));
        if (tick % 20 == 0) {
            for (int cx = center.x - r; cx <= center.x + r; cx++) {
                for (int cz = center.z - r; cz <= center.z + r; cz++) {
                    long key = ChunkPos.asLong(cx, cz);
                    if (!scans.containsKey(key) && mc.level.hasChunk(cx, cz)) ChunkScanner.request(key);
                }
            }
        }
        leaked.keySet().removeIf(key -> far(key, center, r + 4));
        natural.keySet().removeIf(key -> far(key, center, r + 4));
        trials.keySet().removeIf(key -> far(key, center, r + 4));
        unconfirmed.keySet().removeIf(key -> far(key, center, r + 4));

        if (++tick % 20 == 0) {
            judge();
            reveal(System.currentTimeMillis());
        }
        if (tick % 600 == 0) saveChecked();
    }

    // ---- Players going down, and coming up

    /**
     * Who's in sight now against last tick. Someone gone who's still online and wasn't far off, last
     * seen just over the depths, went down; someone new there, who didn't just join, came up.
     */
    private void watchPlayers(long now) {
        if (mc.getConnection() == null) return;
        Set<UUID> tab = new HashSet<>();
        for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
            tab.add(info.getProfile().id());
            listed.putIfAbsent(info.getProfile().id(), now);
        }
        listed.keySet().retainAll(tab);
        // A teleport of your own: everyone round you comes and goes at once.
        Vec3 me = mc.player.position();
        if (lastMe != null && me.distanceToSqr(lastMe) > 32 * 32) settledAt = now;
        lastMe = me;
        Map<UUID, Seen> current = new HashMap<>();
        for (AbstractClientPlayer p : mc.level.players()) {
            if (p == mc.player) continue;
            Seen s = others.getOrDefault(p.getUUID(), new Seen());
            s.name = p.getGameProfile().name();
            s.pos = p.position();
            s.dying = p.isDeadOrDying();
            s.trail.addLast(new double[] {now, p.getY()});
            while (!s.trail.isEmpty() && now - s.trail.peekFirst()[0] > TRAIL_MS) s.trail.removeFirst();
            current.put(p.getUUID(), s);
        }
        if (vanish.get() && now - settledAt >= SETTLE_MS) {
            for (Map.Entry<UUID, Seen> e : others.entrySet()) {
                if (current.containsKey(e.getKey()) || !tab.contains(e.getKey()) || e.getValue().dying) continue;
                went(e.getValue(), true, now);
            }
            for (Map.Entry<UUID, Seen> e : current.entrySet()) {
                Long since = listed.get(e.getKey());
                if (others.containsKey(e.getKey()) || since == null || now - since < SETTLE_MS) continue;
                went(e.getValue(), false, now);
            }
        }
        others.clear();
        others.putAll(current);
    }

    private void went(Seen s, boolean down, long now) {
        if (Math.hypot(s.pos.x - mc.player.getX(), s.pos.z - mc.player.getZ()) > VANISH_REACH) return;
        BlockPos at = BlockPos.containing(s.pos);
        LevelChunk chunk = mc.level.getChunkSource().getChunk(at.getX() >> 4, at.getZ() >> 4, false);
        if (chunk == null) return;
        // Only where the depths are made up (or players wouldn't vanish), and only right over them.
        int floor = floor(chunk);
        if (floor == 0 || at.getY() > floor + VANISH_ABOVE) return;
        // Going down is coming down to it (falling, climbing, digging), not standing about in a cave and teleporting home.
        if (down && at.getY() >= floor && s.highest() - s.pos.y < VANISH_DROP) return;
        went.addLast(new Went(at.immutable(), s.name + (down ? " went down" : " came up"), now));
        while (went.size() > 256) went.removeFirst();
    }

    // ---- Checked chunks

    /** Down among the made-up layers: how far round you the real chunks come in, once it's settled. */
    private void reveal(long now) {
        ChunkPos c = mc.player.chunkPosition();
        Scan here = scans.get(c.toLong());
        if (!checked.get() || !hiding || here == null || !here.real()) {
            shownRadius = -1;
            pendingRadius = -1;
            return;
        }
        int r = 0;
        for (int d = 1; d <= radius.get().intValue(); d++) {
            boolean all = true;
            for (int dx = -d; dx <= d && all; dx++) {
                for (int dz = -d; dz <= d && all; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != d) continue;
                    Scan s = scans.get(ChunkPos.asLong(c.x + dx, c.z + dz));
                    all = s != null && s.real();
                }
            }
            if (!all) break;
            r = d;
        }
        if (r != pendingRadius) {
            pendingRadius = r;
            pendingSince = now;
        } else if (r != shownRadius && now - pendingSince >= 3000) {
            shownRadius = r;
            int n = checkedAt.getOrDefault(where(), Map.of()).size();
            if (notify.get()) {
                Notify.send("Chunk Finder", "Real chunks " + r + " round you (" + (2 * r + 1) + "x" + (2 * r + 1) + "); " + n
                    + " checked here so far", false, false, false);
            }
        }
    }

    private static boolean far(long key, ChunkPos center, int r) {
        return Math.max(Math.abs(ChunkPos.getX(key) - center.x), Math.abs(ChunkPos.getZ(key) - center.z)) > r;
    }

    private Scan scan(LevelChunk chunk, long now) {
        List<Hit> hits = new ArrayList<>();
        List<BlockPos> structures = new ArrayList<>();
        if (mc.level.dimension() != Level.OVERWORLD) return new Scan(hits, structures, false, false, false, now, 0);
        int x0 = chunk.getPos().getMinBlockX();
        int z0 = chunk.getPos().getMinBlockZ();
        LevelChunkSection[] sections = chunk.getSections();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        Map<Clue, Integer> counts = new EnumMap<>(Clue.class);
        boolean[] hidden = depths(chunk);
        int floor = floor(chunk);

        // Above the hidden zone: deepslate patching a hole at Y 0, a miner's torches and ladders, grown kelp up at the sea.
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            int baseY = chunk.getMinY() + i * 16;
            if (baseY + 16 <= floor || baseY >= floor + 96 || section.hasOnlyAir() || !section.maybeHas(ChunkFinder::interesting)) continue;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (!interesting(state)) continue;
                        pos.set(x0 + x, baseY + y, z0 + z);
                        Block block = state.getBlock();
                        if (STRUCTURE.contains(block)) {
                            if (structures.size() < MAX_PER_CLUE * 4) structures.add(pos.immutable());
                        } else if (block == Blocks.DEEPSLATE) {
                            if (rotated.get() && baseY + y >= floor && baseY + y < floor + PATCH_TOP) add(hits, counts, new Hit(pos.immutable(), Clue.ROTATED));
                        } else if (block == Blocks.KELP) {
                            if (kelp.get()) add(hits, counts, new Hit(pos.immutable(), Clue.KELP));
                        } else if (PLACED.contains(block) && baseY + y >= floor && baseY + y < floor + BAND) {
                            if (skipDug.get()) add(hits, counts, new Hit(pos.immutable(), Clue.PLACED));
                        }
                    }
                }
            }
        }
        if (skipDug.get() || sealedShafts.get()) shafts(chunk, floor, hits, counts);
        if (skipDug.get()) tunnels(chunk, floor, hits, counts);
        if (sealedShafts.get()) sealed(chunk, floor, hits, counts);
        if (seafloor.get()) seafloor(chunk, floor, hits, counts);
        // Ladders and torches inside a capped shaft are its way down, not a miner's.
        List<AABB> capped = new ArrayList<>();
        for (Hit h : hits) if (h.clue() == Clue.CAPPED) capped.add(h.box().inflate(1));
        if (!capped.isEmpty()) hits.removeIf(h -> h.clue() == Clue.PLACED && capped.stream().anyMatch(b -> b.contains(Vec3.atCenterOf(h.pos()))));
        boolean lit = light.get() && glow(chunk, hits, counts);
        if (rooms.get()) rooms(chunk, hits, counts);
        return new Scan(hits, structures, hidden[0], hidden[0] && hidden[1], lit, now, floor);
    }

    private static void add(List<Hit> hits, Map<Clue, Integer> counts, Hit hit) {
        if (counts.merge(hit.clue(), 1, Integer::sum) <= MAX_PER_CLUE) hits.add(hit);
    }

    private static boolean interesting(BlockState state) {
        Block block = state.getBlock();
        // Worldgen only ever stands deepslate up straight; on its side, a player put it there.
        if (block == Blocks.DEEPSLATE) return state.getValue(RotatedPillarBlock.AXIS) != Direction.Axis.Y;
        // Worldgen leaves kelp at age 20 to 23: past that, it's grown since, while someone kept the area loaded.
        if (block == Blocks.KELP) return state.getValue(GrowingPlantHeadBlock.AGE) >= 24;
        return STRUCTURE.contains(block) || PLACED.contains(block);
    }

    /**
     * {hidden, air}: whether the layers below Y 0 came through as stone, deepslate and bedrock
     * (never true of the real thing, which has ores, tuff and gravel by the hundred; a handful of
     * other blocks is what leaked through or got built), and whether air was kept in them.
     */
    private static boolean[] depths(LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        int deep = 0;
        boolean air = false;
        for (int i = 0; i < sections.length; i++) {
            int baseY = chunk.getMinY() + i * 16;
            if (baseY >= 0) break;
            LevelChunkSection section = sections[i];
            if (section.hasOnlyAir()) continue;
            int[] other = {0};
            boolean[] hasAir = {false};
            section.getStates().count((st, n) -> {
                if (n <= 0) return;
                if (st.isAir()) hasAir[0] = true;
                else if (!st.is(Blocks.STONE) && !st.is(Blocks.DEEPSLATE) && !st.is(Blocks.BEDROCK)) other[0] += n;
            });
            if (other[0] > 24) return new boolean[] {false, false};
            air |= hasAir[0];
            deep++;
        }
        return new boolean[] {deep >= 2, air};
    }

    /**
     * Where the real world starts in this chunk, as far as you can see: the top of the made-up
     * layers a server sends in place of what's down there (nothing but deepslate, and the bedrock
     * floor), or Y 0 where nothing's made up. Donut's go up to Y 16 while you're up top; go down
     * among them and the real ones come in, and this drops back to Y 0.
     */
    private static int floor(LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        int top = chunk.getMinY();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section.hasOnlyAir()) break;
            int[] other = {0};
            section.getStates().count((st, n) -> {
                if (n > 0 && !st.is(Blocks.DEEPSLATE) && !st.is(Blocks.BEDROCK)) other[0] += n;
            });
            if (other[0] > 0) break;
            top = chunk.getMinY() + (i + 1) * 16;
        }
        return Math.max(0, top);
    }

    // ---- Sealed shafts

    /**
     * Sealed shafts: someone dug down to the hidden layers and filled the hole back in behind them,
     * the way you hide a way in (Bedrock Burrow does it too). A straight column of fill from Y 0
     * up through natural rock: one or two blocks wide at every layer, never the wide blob worldgen
     * makes of dirt and gravel, and with cobblestone, cobbled deepslate or netherrack in it, which
     * worldgen never puts in a column.
     */
    private static void sealed(LevelChunk chunk, int floor, List<Hit> hits, Map<Clue, Integer> counts) {
        int x0 = chunk.getPos().getMinBlockX();
        int z0 = chunk.getPos().getMinBlockZ();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int bottom = -1;
                int run = 0;
                int placed = 0;
                for (int y = floor; y <= floor + SEAL_TOP; y++) {
                    Block here = y < floor + SEAL_TOP ? fillAt(chunk, x, y, z) : null;
                    if (here != null) {
                        if (run == 0) bottom = y;
                        run++;
                        if (PLAYER_FILL.contains(here)) placed++;
                        continue;
                    }
                    if (run >= SEAL_MIN && bottom <= floor + SEAL_BOTTOM && placed >= 3) {
                        AABB box = new AABB(x0 + x, bottom, z0 + z, x0 + x + 1, bottom + run, z0 + z + 1);
                        add(hits, counts, new Hit(new BlockPos(x0 + x, bottom, z0 + z), Clue.SEALED, null, box));
                    }
                    // Only a column that starts at the bottom leads anywhere.
                    if (run == 0 && y > floor + SEAL_BOTTOM) break;
                    if (run > 0 && bottom <= floor + SEAL_BOTTOM) break;
                    run = 0;
                    placed = 0;
                }
            }
        }
    }

    /**
     * Patched seafloor: gravel and sand can't be put back (they fall), so a way down under the sea
     * gets plugged with whatever's to hand, and the plug sits in the gravel: stone, dirt or cobble on
     * a seabed that's otherwise gravel, sand or clay. One to four blocks of it, level with the seabed
     * all round it, rare on this bit of seabed, and over a way down to Y 0. Real seabeds are full of
     * small dirt and stone patches, but they sit on dirt and rock; a plug sits on the hole it hides.
     */
    private void seafloor(LevelChunk chunk, int depths, List<Hit> hits, Map<Clue, Integer> counts) {
        int x0 = chunk.getPos().getMinBlockX();
        int z0 = chunk.getPos().getMinBlockZ();
        int[][] floorY = new int[16][16];
        Block[][] floor = new Block[16][16];
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                floorY[x][z] = Integer.MIN_VALUE;
                int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                // The sea: water at the top, then down through it (seagrass and kelp hold water too).
                if (chunk.getFluidState(p.set(x, top, z)).isEmpty()) continue;
                int y = top;
                while (y > 0 && !chunk.getFluidState(p.set(x, y, z)).isEmpty()) y--;
                if (y <= 0) continue;
                floorY[x][z] = y;
                floor[x][z] = chunk.getBlockState(p.set(x, y, z)).getBlock();
            }
        }
        Map<Block, Integer> common = new HashMap<>();
        for (Block[] row : floor) for (Block b : row) if (b != null) common.merge(b, 1, Integer::sum);
        // Each patch of plug blocks on the seabed as a whole: four blocks or fewer, ringed by seabed.
        boolean[][] done = new boolean[16][16];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                if (done[x][z] || floor[x][z] == null || !PLUG.contains(floor[x][z])) continue;
                List<int[]> patch = new ArrayList<>();
                Deque<int[]> queue = new ArrayDeque<>();
                queue.add(new int[] {x, z});
                done[x][z] = true;
                while (!queue.isEmpty() && patch.size() <= 4) {
                    int[] c = queue.poll();
                    patch.add(c);
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            int nx = c[0] + dx, nz = c[1] + dz;
                            if (nx < 0 || nx > 15 || nz < 0 || nz > 15 || done[nx][nz]) continue;
                            if (floor[nx][nz] != null && PLUG.contains(floor[nx][nz])) {
                                done[nx][nz] = true;
                                queue.add(new int[] {nx, nz});
                            }
                        }
                    }
                }
                if (patch.size() > 4 || !queue.isEmpty()) continue;
                // A floor that's dirt or stone all over (a river, a lake, bare rock) isn't patched.
                if (patch.stream().anyMatch(c -> common.get(floor[c[0]][c[1]]) > 8)) continue;
                int seabed = 0;
                int other = 0;
                for (int[] c : patch) {
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            int nx = c[0] + dx, nz = c[1] + dz;
                            if (nx < 0 || nx > 15 || nz < 0 || nz > 15) continue;
                            Block n = floor[nx][nz];
                            if (n != null && PLUG.contains(n)) continue;
                            // Seabed only counts level with the plug; a drop beside it is a hole, not a patch.
                            if (n != null && SEABED.contains(n) && Math.abs(floorY[nx][nz] - floorY[c[0]][c[1]]) <= 1) seabed++;
                            else other++;
                        }
                    }
                }
                if (seabed >= 5 && seabed >= other * 3 && wayDown(patch, floorY, x0, z0, depths)) {
                    AABB box = null;
                    for (int[] c : patch) {
                        AABB b = new AABB(new BlockPos(x0 + c[0], floorY[c[0]][c[1]], z0 + c[1]));
                        box = box == null ? b : box.minmax(b);
                    }
                    int[] c = patch.get(0);
                    add(hits, counts, new Hit(new BlockPos(x0 + c[0], floorY[c[0]][c[1]], z0 + c[1]), Clue.SEAFLOOR, floor[c[0]][c[1]].getName().getString(), box));
                }
            }
        }
    }

    /**
     * A way down under a seabed patch (chunk-local cells): from just under it to Y 3, nothing but water,
     * air and what a hole gets filled back in with, never the rock round it, and walled in for all but
     * a few layers (the sand and gravel it starts in, a cave it crosses). Under a natural patch there's
     * rock within a few blocks.
     */
    private boolean wayDown(List<int[]> patch, int[][] floorY, int x0, int z0, int floor) {
        Set<Long> foot = new HashSet<>();
        for (int[] c : patch) foot.add(BlockPos.asLong(x0 + c[0], 0, z0 + c[1]));
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int[] c : patch) {
            int x = x0 + c[0];
            int z = z0 + c[1];
            int layers = 0;
            int wide = 0;
            boolean down = true;
            for (int y = floorY[c[0]][c[1]] - 1; y >= floor + SEAL_BOTTOM && down; y--) {
                boolean open = open(x, y, z);
                Block block = mc.level.getBlockState(p.set(x, y, z)).getBlock();
                if (!open && !FILL.contains(block) && !SEABED.contains(block)) {
                    down = false;
                    continue;
                }
                layers++;
                if (!open) continue;
                int around = 0;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if ((dx != 0 || dz != 0) && !foot.contains(BlockPos.asLong(x + dx, 0, z + dz)) && open(x + dx, y, z + dz)) around++;
                    }
                }
                if (around > 3) wide++;
            }
            if (down && layers >= SEAL_MIN && wide <= WIDE_MAX) return true;
        }
        return false;
    }

    /** The fill block at a spot (chunk-local x and z) if it's in a narrow column; null otherwise. */
    private static Block fillAt(LevelChunk chunk, int x, int y, int z) {
        Block block = chunk.getBlockState(new BlockPos(x, y, z)).getBlock();
        boolean sideways = block == Blocks.DEEPSLATE
            && chunk.getBlockState(new BlockPos(x, y, z)).getValue(RotatedPillarBlock.AXIS) != Direction.Axis.Y;
        if (!FILL.contains(block) && !sideways) return null;
        // One or two wide: three or fewer fill blocks in the ring round it. A blob has more.
        int around = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0 || x + dx < 0 || x + dx > 15 || z + dz < 0 || z + dz > 15) continue;
                Block n = chunk.getBlockState(new BlockPos(x + dx, y, z + dz)).getBlock();
                if (FILL.contains(n)) around++;
            }
        }
        return around <= 3 ? (sideways ? Blocks.COBBLED_DEEPSLATE : block) : null;
    }

    // ---- Open shafts and tunnels (dug out)

    /** Nothing in the way: air, water, a ladder, scaffolding, a torch, a vine. */
    private boolean open(int x, int y, int z) {
        probe.set(x, y, z);
        BlockState state = mc.level.getBlockState(probe);
        return state.isAir() || state.is(Blocks.LADDER) || state.is(Blocks.SCAFFOLDING) || state.getCollisionShape(mc.level, probe).isEmpty();
    }

    private boolean air(int x, int y, int z) {
        probe.set(x, y, z);
        return mc.level.getBlockState(probe).isAir();
    }

    /**
     * Shafts down to the hidden layers: from each open cell at Y 0 to 3 with rock (or the hidden
     * zone) under it, the patch of open cells round it at that height, if it's small (up to 3x3) and
     * walled in. Then up, as long as exactly that patch stays open and its walls stay solid.
     */
    private void shafts(LevelChunk chunk, int floor, List<Hit> hits, Map<Clue, Integer> counts) {
        int x0 = chunk.getPos().getMinBlockX();
        int z0 = chunk.getPos().getMinBlockZ();
        Set<Long> seen = new HashSet<>();
        for (int x = x0; x < x0 + 16; x++) {
            for (int z = z0; z < z0 + 16; z++) {
                for (int y = floor; y <= floor + 3; y++) {
                    if (!open(x, y, z)) continue;
                    if (y > floor && open(x, y - 1, z)) break;
                    Set<Long> foot = footprint(x, y, z);
                    if (foot == null || !seen.add(foot.stream().min(Long::compare).orElse(0L))) break;
                    Rise rise = rise(foot, y);
                    int height = rise.height();
                    // Open at the top: someone mined it. Closed over: someone's hiding the way down.
                    boolean want = rise.capped() ? sealedShafts.get() : skipDug.get();
                    if (height >= SHAFT_MIN && want) {
                        AABB box = null;
                        for (long cell : foot) {
                            AABB b = new AABB(BlockPos.getX(cell), y, BlockPos.getZ(cell), BlockPos.getX(cell) + 1, y + height, BlockPos.getZ(cell) + 1);
                            box = box == null ? b : box.minmax(b);
                        }
                        add(hits, counts, new Hit(new BlockPos(x, y, z), rise.capped() ? Clue.CAPPED : Clue.SHAFT, null, box));
                    }
                    break;
                }
            }
        }
    }

    /** The open cells joined to (x, z) at height y, if they're walled in within two blocks and nine or fewer. */
    private Set<Long> footprint(int x, int y, int z) {
        Set<Long> cells = new LinkedHashSet<>();
        Deque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[] {x, z});
        cells.add(BlockPos.asLong(x, 0, z));
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            for (Direction d : Direction.Plane.HORIZONTAL) {
                int nx = c[0] + d.getStepX();
                int nz = c[1] + d.getStepZ();
                if (!open(nx, y, nz) || cells.contains(BlockPos.asLong(nx, 0, nz))) continue;
                if (Math.abs(nx - x) > 2 || Math.abs(nz - z) > 2 || cells.size() >= 9) return null;
                cells.add(BlockPos.asLong(nx, 0, nz));
                queue.add(new int[] {nx, nz});
            }
        }
        return cells;
    }

    /** How many layers up from y the footprint stays open with solid walls all round it. */
    /** How far a shaft goes up, and whether it ends under a lid (closed right over) rather than opening out. */
    private record Rise(int height, boolean capped) {
    }

    private Rise rise(Set<Long> foot, int y) {
        int height = 0;
        for (int at = y; at < y + 96; at++) {
            for (long cell : foot) {
                int cx = BlockPos.getX(cell);
                int cz = BlockPos.getZ(cell);
                if (!open(cx, at, cz)) {
                    // A lid only if it closes the whole shaft at once; a step or a ledge isn't one.
                    boolean lid = true;
                    for (long c : foot) lid &= !open(BlockPos.getX(c), at, BlockPos.getZ(c));
                    return new Rise(height, lid);
                }
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    int nx = cx + d.getStepX();
                    int nz = cz + d.getStepZ();
                    // Opens out sideways: into the open air, the sea, a cave or a tunnel.
                    if (!foot.contains(BlockPos.asLong(nx, 0, nz)) && open(nx, at, nz)) return new Rise(height, false);
                }
            }
            height++;
        }
        return new Rise(height, false);
    }

    /** A tunnel's cross-section at one step along it: the floor height, how tall, and where its walls are. */
    private record Slice(int y, int height, int from, int to) {
    }

    /**
     * Straight tunnels just above the hidden layers: a box-shaped corridor, 1 to 3 wide and 2 or 3
     * high with rock all round, the same for 14 blocks or more along X or Z.
     */
    private void tunnels(LevelChunk chunk, int floor, List<Hit> hits, Map<Clue, Integer> counts) {
        int x0 = chunk.getPos().getMinBlockX();
        int z0 = chunk.getPos().getMinBlockZ();
        Set<String> seen = new HashSet<>();
        for (boolean alongX : new boolean[] {true, false}) {
            for (int y = floor + 1; y < floor + BAND; y++) {
                for (int x = x0; x < x0 + 16; x++) {
                    for (int z = z0; z < z0 + 16; z++) {
                        Slice s = slice(x, y, z, alongX);
                        if (s == null) continue;
                        // Only from where it starts, so each tunnel is counted once.
                        if (s.equals(slice(alongX ? x - 1 : x, y, alongX ? z : z - 1, alongX))) continue;
                        // Each cell across a wide tunnel gives the same slice: one tunnel, not three.
                        if (!seen.add(alongX + ":" + (alongX ? x : z) + ":" + s)) continue;
                        int length = 1;
                        while (length < 64 && s.equals(slice(alongX ? x + length : x, y, alongX ? z : z + length, alongX))) length++;
                        if (length < TUNNEL_MIN) continue;
                        AABB box = alongX
                            ? new AABB(x, s.y(), s.from(), x + length, s.y() + s.height(), s.to() + 1)
                            : new AABB(s.from(), s.y(), z, s.to() + 1, s.y() + s.height(), z + length);
                        add(hits, counts, new Hit(new BlockPos(x, y, z), Clue.TUNNEL, null, box));
                    }
                }
            }
        }
    }

    /** The corridor through (x, y, z) across the way it runs, or null if it isn't a clean box there. */
    private Slice slice(int x, int y, int z, boolean alongX) {
        int height = openHeight(x, y, z);
        if (height < 2 || height > 3) return null;
        int from = alongX ? z : x;
        int to = from;
        // Out to each side: the same shape, until a wall of rock all the way up.
        for (int dir = -1; dir <= 1; dir += 2) {
            for (int step = 1; ; step++) {
                int ox = alongX ? x : x + dir * step;
                int oz = alongX ? z + dir * step : z;
                int h = openHeight(ox, y, oz);
                if (h == height) {
                    if (dir < 0) from--;
                    else to++;
                    if (to - from + 1 > 3) return null;
                    continue;
                }
                for (int up = 0; up < height; up++) if (open(ox, y + up, oz)) return null;
                break;
            }
        }
        return new Slice(y, height, from, to);
    }

    /** How tall the open space at (x, y, z) is with rock under it and over it, or 0. */
    private int openHeight(int x, int y, int z) {
        if (!open(x, y, z) || open(x, y - 1, z)) return 0;
        int h = 1;
        while (h < 4 && open(x, y + h, z)) h++;
        return h < 4 ? h : 0;
    }

    // ---- Light from below

    /**
     * The hidden layers' lighting, if the server sends the real thing. A torch glows at exactly 14,
     * so a 14 with no 15 beside it is a torch (glow berries do too, so not in lush caves). A 15 with
     * no other 15 beside it is one bright block: a lantern, glowstone, a sea lantern; lava is a
     * sheet of 15s and doesn't count.
     */
    /** Marks the lamps and torches; true when there's any light at all below Y 0. */
    private boolean glow(LevelChunk chunk, List<Hit> hits, Map<Clue, Integer> counts) {
        boolean any = false;
        var engine = mc.level.getLightEngine().getLayerListener(LightLayer.BLOCK);
        ChunkPos cp = chunk.getPos();
        int bottom = chunk.getMinY() >> 4;
        for (int sy = -1; sy >= bottom; sy--) {
            DataLayer layer = engine.getDataLayerData(SectionPos.of(cp.x, sy, cp.z));
            if (layer == null || layer.isEmpty()) continue;
            boolean lit = false;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int v = layer.get(x, y, z);
                        if (v > 0) lit = true;
                        if (v < 14) continue;
                        BlockPos p = new BlockPos(cp.getMinBlockX() + x, sy * 16 + y, cp.getMinBlockZ() + z);
                        if (besideAtLeast(engine, layer, x, y, z, cp.x, sy, cp.z, 15)) continue;
                        if (v == 14) {
                            if (!mc.level.getBiome(p).is(Biomes.LUSH_CAVES)) add(hits, counts, new Hit(p, Clue.TORCHLIGHT));
                        } else {
                            add(hits, counts, new Hit(p, Clue.LAMP));
                        }
                    }
                }
            }
            any |= lit;
        }
        return any;
    }

    /**
     * Whether a neighbour is at least this bright: in the same section from its layer, across the
     * edge from the next section's (lava just across an edge lights a cell to 14 too: no torch).
     * Read the way that's safe off the game's thread: from the finished light, never the one being worked on.
     */
    private static boolean besideAtLeast(LayerLightEventListener engine, DataLayer layer, int x, int y, int z, int sx, int sy, int sz, int level) {
        for (Direction d : Direction.values()) {
            int nx = x + d.getStepX();
            int ny = y + d.getStepY();
            int nz = z + d.getStepZ();
            DataLayer in = layer;
            if (nx < 0 || ny < 0 || nz < 0 || nx > 15 || ny > 15 || nz > 15) {
                in = engine.getDataLayerData(SectionPos.of(sx + Math.floorDiv(nx, 16), sy + Math.floorDiv(ny, 16), sz + Math.floorDiv(nz, 16)));
                nx &= 15;
                ny &= 15;
                nz &= 15;
            }
            if (in != null && in.get(nx, ny, nz) >= level) return true;
        }
        return false;
    }

    // ---- Rooms below Y 0

    /**
     * Box-shaped rooms in the layers below Y 0, if the server keeps the air: at least 3 by 3 and 2
     * to 8 high, with a flat floor, a flat ceiling and straight walls. Caves are never boxes.
     */
    private void rooms(LevelChunk chunk, List<Hit> hits, Map<Clue, Integer> counts) {
        int x0 = chunk.getPos().getMinBlockX();
        int z0 = chunk.getPos().getMinBlockZ();
        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            int baseY = chunk.getMinY() + i * 16;
            if (baseY >= 0) break;
            if (section.hasOnlyAir() || !section.maybeHas(BlockState::isAir)) continue;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (!section.getBlockState(x, y, z).isAir()) continue;
                        AABB box = room(x0 + x, baseY + y, z0 + z);
                        if (box != null) add(hits, counts, new Hit(new BlockPos(x0 + x, baseY + y, z0 + z), Clue.ROOM, null, box));
                    }
                }
            }
        }
    }

    /** The box room whose low corner (smallest x, y, z) is here, or null. */
    private AABB room(int x, int y, int z) {
        // A corner: rock under it and on its low x and z sides.
        if (air(x, y - 1, z) || air(x - 1, y, z) || air(x, y, z - 1)) return null;
        int w = 0;
        while (w < 32 && air(x + w, y, z) && !air(x + w, y - 1, z) && !air(x + w, y, z - 1)) w++;
        if (w < 3 || air(x + w, y, z)) return null;
        int d = 1;
        rows:
        while (d < 32) {
            for (int i = 0; i < w; i++) if (!air(x + i, y, z + d) || air(x + i, y - 1, z + d)) break rows;
            if (air(x - 1, y, z + d) || air(x + w, y, z + d)) return null;
            d++;
        }
        if (d < 3) return null;
        for (int i = 0; i < w; i++) if (air(x + i, y, z + d)) return null;
        int h = 1;
        layers:
        while (h <= 8) {
            int ly = y + h;
            if (ly >= 0) return null;
            for (int i = 0; i < w; i++) for (int j = 0; j < d; j++) if (!air(x + i, ly, z + j)) break layers;
            h++;
        }
        if (h < 2 || h > 8) return null;
        // Flat ceiling and straight walls all the way up.
        for (int i = 0; i < w; i++) for (int j = 0; j < d; j++) if (air(x + i, y + h, z + j)) return null;
        for (int k = 1; k < h; k++) {
            for (int i = 0; i < w; i++) if (air(x + i, y + k, z - 1) || air(x + i, y + k, z + d)) return null;
            for (int j = 0; j < d; j++) if (air(x - 1, y + k, z + j) || air(x + w, y + k, z + j)) return null;
        }
        return new AABB(x, y, z, x + w, y + h, z + d);
    }

    // ---- What comes in from below: block entities, block changes, events, sounds

    /** A block entity in a chunk as it arrives, before the client drops any at spots it's been told are rock. */
    public static void onChunkBlockEntity(BlockPos pos, BlockEntityType<?> type) {
        ChunkFinder self = Modules.get(ChunkFinder.class);
        if (self == null || !self.isEnabled() || pos.getY() >= 0) return;
        self.blockEntity(pos, type);
    }

    /** A block entity update (a sign written, a beacon lit). */
    public static void onBlockEntity(BlockPos pos, BlockEntityType<?> type) {
        onChunkBlockEntity(pos, type);
    }

    private void blockEntity(BlockPos pos, BlockEntityType<?> type) {
        String id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type).getPath();
        long key = ChunkPos.asLong(pos);
        leak(Leak.BLOCK_ENTITIES, key);
        if (NATURAL_BLOCK_ENTITIES.contains(id)) {
            natural.computeIfAbsent(key, k -> new ArrayList<>()).add(pos.immutable());
            if (id.equals("trial_spawner") || id.equals("vault")) trials.computeIfAbsent(key, k -> new ArrayList<>()).add(pos.immutable());
            return;
        }
        if (!blockEntities.get()) return;
        Clue clue = PLAYER_BLOCK_ENTITIES.contains(id) ? Clue.CONTAINER : id.equals("chest") || id.equals("trapped_chest") ? Clue.CHESTS : null;
        if (clue == null) return;
        Map<BlockPos, Hit> hits = leaked.computeIfAbsent(key, k -> new LinkedHashMap<>());
        if (hits.size() < MAX_PER_CLUE * 2) hits.put(pos.immutable(), new Hit(pos.immutable(), clue, id.equals("chest") ? null : id.replace('_', ' ')));
    }

    /** A block changing below Y 0 (one update, or one of a batch). */
    public static void onBlockChanged(BlockPos pos, BlockState state) {
        ChunkFinder self = Modules.get(ChunkFinder.class);
        if (self == null || !self.isEnabled() || pos.getY() >= 0 || self.mine(pos)) return;
        self.revealed(pos, state);
    }

    /** Right by you: your own placing and breaking, not someone's base. */
    private boolean mine(BlockPos pos) {
        return mc.player != null && mc.player.blockPosition().distSqr(pos) < 64;
    }

    /** Whether to bother passing a batch of block changes in. */
    public static boolean listening() {
        ChunkFinder self = Modules.get(ChunkFinder.class);
        return self != null && self.isEnabled();
    }

    private void revealed(BlockPos pos, BlockState state) {
        if (state.isAir() || state.is(Blocks.STONE) || state.is(Blocks.DEEPSLATE) || state.is(Blocks.BEDROCK)) return;
        leak(Leak.CHANGES, ChunkPos.asLong(pos));
        if (!changes.get() || !playerBlock(state)) return;
        Map<BlockPos, Hit> hits = leaked.computeIfAbsent(ChunkPos.asLong(pos), k -> new LinkedHashMap<>());
        if (hits.size() < MAX_PER_CLUE * 2) hits.put(pos.immutable(), new Hit(pos.immutable(), Clue.REVEALED, name(state.getBlock())));
    }

    /** Blocks a player puts in a base and worldgen doesn't below Y 0. Torches and chests aren't enough: miners place torches, dungeons have chests. */
    private static boolean playerBlock(BlockState state) {
        Block block = state.getBlock();
        if (state.hasBlockEntity()) {
            BlockEntityType<?> type = BuiltInRegistries.BLOCK_ENTITY_TYPE.stream()
                .filter(t -> t.isValid(state)).findFirst().orElse(null);
            return type != null && PLAYER_BLOCK_ENTITIES.contains(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type).getPath());
        }
        String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
        return block == Blocks.CRAFTING_TABLE || block == Blocks.CARTOGRAPHY_TABLE || block == Blocks.FLETCHING_TABLE || block == Blocks.LOOM
            || block == Blocks.STONECUTTER || block == Blocks.GRINDSTONE || block == Blocks.SMITHING_TABLE || block == Blocks.ANVIL
            || block == Blocks.LANTERN || block == Blocks.SEA_LANTERN || block == Blocks.GLOWSTONE || block == Blocks.SHROOMLIGHT
            || block == Blocks.IRON_BLOCK || block == Blocks.DIAMOND_BLOCK || block == Blocks.NETHERITE_BLOCK || block == Blocks.EMERALD_BLOCK
            || block == Blocks.GOLD_BLOCK || block == Blocks.HAY_BLOCK || block == Blocks.BOOKSHELF || block == Blocks.FARMLAND
            || state.is(BlockTags.WOOL) || state.is(BlockTags.WOOL_CARPETS) || state.is(BlockTags.TERRACOTTA) || state.is(BlockTags.BEDS)
            || path.endsWith("_concrete") || path.endsWith("stained_glass") || path.equals("glass") || path.endsWith("froglight")
            || path.startsWith("quartz") || path.startsWith("smooth_quartz");
    }

    private static String name(Block block) {
        return block.getName().getString().toLowerCase(Locale.ROOT);
    }

    /** A level event: a dispenser firing, a jukebox, an anvil; or the particles of a block broken, which name the real block. */
    public static void onLevelEvent(int type, BlockPos pos, int data) {
        ChunkFinder self = Modules.get(ChunkFinder.class);
        if (self == null || !self.isEnabled() || pos.getY() >= 0) return;
        if (type == LevelEvent.PARTICLES_DESTROY_BLOCK) {
            if (!self.mine(pos)) self.revealed(pos, Block.stateById(data));
            return;
        }
        String what = EVENTS.get(type);
        if (what != null && self.heard.get()) self.hear(pos, what);
    }

    /** A block event (a chest opening, a piston). */
    public static void onBlockEvent(BlockPos pos, Block block) {
        ChunkFinder self = Modules.get(ChunkFinder.class);
        if (self == null || !self.isEnabled() || !self.heard.get() || pos.getY() >= 0) return;
        String what = block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST ? "chest"
            : block == Blocks.ENDER_CHEST ? "ender chest"
            : block instanceof ShulkerBoxBlock ? "shulker box"
            : block == Blocks.PISTON || block == Blocks.STICKY_PISTON ? "piston"
            : block == Blocks.NOTE_BLOCK ? "note block"
            : block == Blocks.BELL ? "bell" : null;
        if (what != null) self.hear(pos, what);
    }

    /** A sound. */
    public static void onSound(Holder<SoundEvent> sound, double x, double y, double z) {
        ChunkFinder self = Modules.get(ChunkFinder.class);
        if (self == null || !self.isEnabled() || !self.heard.get() || y >= 0) return;
        String path = sound.value().location().getPath();
        for (Map.Entry<String, String> s : SOUNDS.entrySet()) {
            if (path.startsWith(s.getKey())) {
                self.hear(BlockPos.containing(x, y, z), s.getValue());
                return;
            }
        }
    }

    private void hear(BlockPos pos, String what) {
        leak(Leak.EVENTS, ChunkPos.asLong(pos));
        sounds.addLast(new Heard(pos.immutable(), what, System.currentTimeMillis()));
        while (sounds.size() > 512) sounds.removeFirst();
    }

    // ---- Things kept below, and players staying

    private List<Hit> keptBelow() {
        List<Hit> out = new ArrayList<>();
        if (mc.level.dimension() != Level.OVERWORLD) return out;
        for (Entity e : WorldScan.entities()) {
            if (e.getY() >= 0 || e instanceof Player || Pets.isPet(e)) continue;
            leak(Leak.ENTITIES, ChunkPos.asLong(e.blockPosition()));
            String what = null;
            Clue clue = Clue.KEPT;
            if (e instanceof ItemFrame) what = "item frame";
            else if (e instanceof ArmorStand) what = "armor stand";
            else if (e instanceof Painting) what = "painting";
            // Mineshafts have chest minecarts of their own; any other kind, someone brought.
            else if (e instanceof AbstractMinecart && !(e instanceof MinecartChest)) what = "minecart";
            else if (e instanceof Villager) what = "villager";
            else if (e instanceof IronGolem) what = "iron golem";
            else if (e instanceof Animal && !(e instanceof Axolotl)) {
                what = "animal";
                clue = Clue.ANIMALS;
            }
            if (what != null && kept.get()) out.add(new Hit(e.blockPosition(), clue, what, e.getBoundingBox()));
        }
        return out;
    }

    /** Other players who've stayed near one spot below Y 0 for a while: at home, or at their farm. */
    private List<Hit> stayingBelow(long now) {
        List<Hit> out = new ArrayList<>();
        Set<UUID> present = new HashSet<>();
        for (AbstractClientPlayer p : mc.level.players()) {
            if (p == mc.player || p.getY() >= 0) continue;
            leak(Leak.PLAYERS, ChunkPos.asLong(p.blockPosition()));
            present.add(p.getUUID());
            Stay stay = stays.get(p.getUUID());
            if (stay == null || stay.at().distSqr(p.blockPosition()) > 100) {
                stays.put(p.getUUID(), new Stay(p.getGameProfile().name(), p.blockPosition(), now));
            } else if (now - stay.since() >= STAY_MS && staying.get()) {
                out.add(new Hit(stay.at(), Clue.STAYING, stay.name(), p.getBoundingBox()));
            }
        }
        stays.keySet().retainAll(present);
        return out;
    }

    // ---- Judging each chunk

    private void judge() {
        String where = where();
        ChunkPos center = mc.player.chunkPosition();
        int r = radius.get().intValue();
        long now = System.currentTimeMillis();
        while (!sounds.isEmpty() && now - sounds.peekFirst().at() > HEARD_FOR) sounds.removeFirst();
        while (!went.isEmpty() && now - went.peekFirst().at() > VANISH_FOR) went.removeFirst();
        if (watch.get()) for (Found f : found) if (f.where.equals(where)) checkWays(f, now);

        StructureMarks structures = new StructureMarks(STRUCTURE_REACH);
        for (Scan s : scans.values()) s.structures().forEach(structures::add);
        StructureMarks naturals = new StructureMarks(NATURAL_REACH);
        for (List<BlockPos> list : natural.values()) list.forEach(naturals::add);
        StructureMarks chambers = new StructureMarks(STRUCTURE_REACH);
        for (List<BlockPos> list : trials.values()) list.forEach(chambers::add);

        Map<Long, List<Hit>> byChunk = new HashMap<>();
        for (Map.Entry<Long, Scan> e : scans.entrySet()) {
            for (Hit h : e.getValue().hits()) {
                // A stronghold's ladders and torches, a mineshaft's tunnels: theirs, not a player's.
                if ((h.clue() == Clue.PLACED || h.clue() == Clue.TUNNEL || h.clue() == Clue.SHAFT || h.clue() == Clue.SEALED
                    || h.clue() == Clue.CAPPED || h.clue() == Clue.SEAFLOOR) && structures.near(h.pos())) continue;
                // A dungeon's room, a trial chamber's halls; a trial chamber's copper bulbs. Not lamps by a
                // spawner: a spawner with a farm round it is a base.
                if (h.clue() == Clue.ROOM && naturals.near(h.pos()) || h.clue() == Clue.LAMP && chambers.near(h.pos())) continue;
                byChunk.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(h);
            }
        }
        for (Map.Entry<Long, Map<BlockPos, Hit>> e : leaked.entrySet()) {
            for (Hit h : e.getValue().values()) {
                // A dungeon's chests, a trial chamber's, an ancient city's.
                if (h.clue() == Clue.CHESTS && naturals.near(h.pos())) continue;
                byChunk.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(h);
            }
        }
        for (Hit h : keptBelow()) byChunk.computeIfAbsent(ChunkPos.asLong(h.pos()), k -> new ArrayList<>()).add(h);
        for (Hit h : stayingBelow(now)) byChunk.computeIfAbsent(ChunkPos.asLong(h.pos()), k -> new ArrayList<>()).add(h);
        for (Heard h : sounds) byChunk.computeIfAbsent(ChunkPos.asLong(h.pos()), k -> new ArrayList<>()).add(new Hit(h.pos(), Clue.HEARD, h.what()));
        for (Went w : went) {
            byChunk.computeIfAbsent(ChunkPos.asLong(w.pos()), k -> new ArrayList<>())
                .add(new Hit(w.pos(), Clue.VANISHED, w.what(), new AABB(w.pos()).expandTowards(0, 1, 0)));
        }
        for (Found f : found) {
            if (!f.where.equals(where) || f.used == null || now - f.usedAt > USED_FOR) continue;
            BlockPos at = f.watched.isEmpty() ? BlockPos.containing(f.center()) : f.watched.keySet().iterator().next();
            byChunk.computeIfAbsent(ChunkPos.asLong(f.cx, f.cz), k -> new ArrayList<>()).add(new Hit(at, Clue.USED, f.used));
        }

        double spawn = spawnRadius.get();
        ChestIndex chests = Modules.get(ChestIndex.class);
        for (Found f : found) if (f.where.equals(where)) f.live = false;
        for (Map.Entry<Long, List<Hit>> e : byChunk.entrySet()) {
            int cx = ChunkPos.getX(e.getKey());
            int cz = ChunkPos.getZ(e.getKey());
            if (Math.max(Math.abs(cx - center.x), Math.abs(cz - center.z)) > r) continue;
            int bx = cx * 16 + 8;
            int bz = cz * 16 + 8;
            if (spawn > 0 && Math.hypot(bx, bz) < spawn) continue;
            if (skipMine.get() && (chests != null && chests.anyNear(bx, bz, 40) || StreamerMode.ownBaseNear(bx, bz, 64))) continue;
            Judged j = score(e.getValue());
            if (!j.flag()) continue;
            Found f = match(where, cx, cz);
            if (f == null) {
                f = new Found(where, cx, cz, j.score(), j.reasons());
                found.add(f);
                announce(f);
            } else {
                f.score = j.score();
                f.reasons = j.reasons();
            }
            f.hits = e.getValue();
            f.live = true;
            if (watch.get()) watchWays(f);
        }
        for (Found f : found) if (f.where.equals(where) && !f.live) f.hits = List.of();
        if (!remember.get()) found.removeIf(f -> !f.live);
    }

    private record Judged(boolean flag, double score, String reasons) {
    }

    // ---- Ways in, watched

    /** The blocks that close a found chunk's ways in, as they are now: the lid over a capped shaft, the top of a sealed one, a seafloor plug. */
    private void watchWays(Found f) {
        if (!f.watched.isEmpty()) return;
        for (Hit h : f.hits) {
            AABB b = h.box();
            int x0 = Mth.floor(b.minX), x1 = Mth.floor(b.maxX), z0 = Mth.floor(b.minZ), z1 = Mth.floor(b.maxZ);
            List<BlockPos> spots = new ArrayList<>();
            String way;
            if (h.clue() == Clue.CAPPED) {
                way = "capped shaft";
                for (int x = x0; x < x1; x++) for (int z = z0; z < z1; z++) spots.add(new BlockPos(x, Mth.floor(b.maxY), z));
            } else if (h.clue() == Clue.SEALED) {
                way = "sealed shaft";
                int top = Mth.floor(b.maxY) - 1;
                spots.add(new BlockPos(x0, top, z0));
                spots.add(new BlockPos(x0, top - 1, z0));
            } else if (h.clue() == Clue.SEAFLOOR) {
                way = "patched seafloor";
                for (int x = x0; x < x1; x++) {
                    for (int z = z0; z < z1; z++) {
                        BlockPos at = new BlockPos(x, Mth.floor(b.minY), z);
                        if (PLUG.contains(mc.level.getBlockState(at).getBlock())) spots.add(at);
                    }
                }
            } else {
                continue;
            }
            for (BlockPos at : spots) {
                if (f.watched.size() < WATCH_MAX) f.watched.put(at, new Watch(way, id(mc.level.getBlockState(at))));
            }
        }
        if (!f.watched.isEmpty()) save();
    }

    /** Whether a found chunk's ways in are as they were. Opened, closed or swapped by someone else: they're using it. */
    private void checkWays(Found f, long now) {
        if (f.watched.isEmpty() || !mc.level.hasChunk(f.cx, f.cz)) return;
        LevelChunk chunk = mc.level.getChunkSource().getChunk(f.cx, f.cz, false);
        if (chunk == null) return;
        int floor = floor(chunk);
        boolean changed = false;
        for (Map.Entry<BlockPos, Watch> e : f.watched.entrySet()) {
            BlockPos at = e.getKey();
            // Down among the made-up layers it can't be seen as it is.
            if (at.getY() < floor) continue;
            Watch w = e.getValue();
            String is = id(mc.level.getBlockState(at));
            if (kind(is).equals(kind(w.state))) continue;
            String was = w.state;
            w.state = is;
            changed = true;
            // Your own doing (you went to look).
            if (mc.player.blockPosition().distSqr(at) < 64) continue;
            f.usedAt = now;
            f.used = w.way + " " + change(kind(was), kind(is), was, is);
            if (notify.get()) {
                long dist = Math.round(Math.hypot(f.cx * 16 + 8 - mc.player.getX(), f.cz * 16 + 8 - mc.player.getZ()));
                Notify.send("Way in used", (f.cx * 16 + 8) + ", " + (f.cz * 16 + 8) + " (" + dist + "m): the " + f.used
                    + ", somebody's using it", true, true, true);
            }
        }
        if (changed) save();
    }

    private static String id(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
    }

    /** Grass coming and going on a dirt lid, air of one kind or another: no change. */
    private static String kind(String id) {
        return switch (id) {
            case "grass_block", "podzol", "mycelium", "coarse_dirt", "rooted_dirt", "dirt_path" -> "dirt";
            case "cave_air", "void_air" -> "air";
            default -> id;
        };
    }

    /** "opened, cobblestone gone", "closed with dirt", "swapped cobblestone for dirt". */
    private static String change(String was, String is, String wasId, String isId) {
        boolean open = is.equals("air") || is.equals("water");
        boolean shut = was.equals("air") || was.equals("water");
        if (open && !shut) return "opened, " + wasId.replace('_', ' ') + " gone";
        if (shut && !open) return "closed with " + isId.replace('_', ' ');
        return "swapped " + wasId.replace('_', ' ') + " for " + isId.replace('_', ' ');
    }

    /**
     * A chunk's score: each kind of clue its weight, more for plenty of it (two and a half times at
     * most), and more again for each different kind, since a lived-in chunk shows several.
     */
    private Judged score(List<Hit> hits) {
        Map<Clue, Integer> amounts = new EnumMap<>(Clue.class);
        Map<Clue, Set<String>> details = new EnumMap<>(Clue.class);
        for (Hit h : hits) {
            amounts.merge(h.clue(), 1, Integer::sum);
            if (h.detail() != null) details.computeIfAbsent(h.clue(), k -> new LinkedHashSet<>()).add(h.detail());
        }
        boolean dug = false;
        boolean live = false;
        for (Clue c : amounts.keySet()) {
            dug |= c.dug;
            live |= c.live;
        }
        // Dug out in the open: whatever was under it is gone, or never was. Unless someone's there now.
        if (dug && skipDug.get() && !live) return new Judged(false, 0, "dug out");
        double score = 0;
        int kinds = 0;
        boolean strong = false;
        List<Clue> counted = new ArrayList<>();
        for (Map.Entry<Clue, Integer> a : amounts.entrySet()) {
            Clue clue = a.getKey();
            int amount = a.getValue();
            if (clue.dug || amount < clue.least) continue;
            score += clue.weight * Math.min(2.5, 1 + Math.log(amount) / Math.log(2) * 0.35);
            kinds++;
            if (clue.strong) strong = true;
            counted.add(clue);
        }
        score += 5 * Math.max(0, kinds - 1);
        double least = switch (strictness.get()) {
            case "Loose" -> 8;
            case "Normal" -> 14;
            default -> 24;
        };
        boolean flag = score >= least && (!strictness.is("Strict") || strong || kinds >= 2);
        counted.sort(Comparator.comparingDouble((Clue c) -> -c.weight * amounts.get(c)));
        List<String> parts = new ArrayList<>();
        for (Clue c : counted) {
            String part = howMany(c, amounts.get(c));
            Set<String> d = details.get(c);
            if (d != null && c != Clue.ANIMALS) part += " (" + String.join(", ", d.stream().limit(5).toList()) + (d.size() > 5 ? "..." : "") + ")";
            parts.add(part);
        }
        if (dug) parts.add("dug out above, but someone's down there now");
        return new Judged(flag, score, String.join(", ", parts));
    }

    /** "4 rotated deepslate", "a shaft into the hidden layers". */
    private static String howMany(Clue clue, int amount) {
        String[] forms = clue.label.split("\\|");
        return amount == 1 ? (clue == Clue.KELP ? forms[0] : "a " + forms[0]) : amount + " " + forms[1];
    }

    private Found match(String where, int cx, int cz) {
        for (Found f : found) if (f.where.equals(where) && f.cx == cx && f.cz == cz) return f;
        return null;
    }

    /** A new chunk: told about, made a waypoint, written down. */
    private void announce(Found f) {
        String coords = (f.cx * 16 + 8) + ", " + (f.cz * 16 + 8);
        long dist = Math.round(Math.hypot(f.cx * 16 + 8 - mc.player.getX(), f.cz * 16 + 8 - mc.player.getZ()));
        if (notify.get()) {
            Notify.send("Chunk found (score " + Math.round(f.score) + ")", coords + " (" + dist + "m), base below Y 0? " + f.reasons, true, true, true);
        }
        if (waypoints.get()) {
            Waypoints w = Modules.get(Waypoints.class);
            if (w != null) w.addAt("Chunk " + Math.round(f.score), new BlockPos(f.cx * 16 + 8, 0, f.cz * 16 + 8), color.get());
        }
        if (logFile.get()) {
            String server = mc.getCurrentServer() != null ? mc.getCurrentServer().ip : "singleplayer";
            String line = String.format(Locale.ROOT, "%s  %s  chunk %d, %d  (%s)  score %d  %s%n", LocalDateTime.now().format(STAMP), server,
                f.cx, f.cz, coords, Math.round(f.score), f.reasons);
            try {
                Path file = Config.dir().resolve("chunk-finds.txt");
                Files.createDirectories(file.getParent());
                Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {
                // Found all the same.
            }
        }
        save();
    }

    /** Marks by where they are, so "any near here?" stays quick. */
    private static final class StructureMarks {
        private final double reach;
        private final int cell;
        private final Map<Long, List<BlockPos>> cells = new HashMap<>();

        StructureMarks(double reach) {
            this.reach = reach;
            this.cell = (int) Math.ceil(reach);
        }

        void add(BlockPos pos) {
            cells.computeIfAbsent(key(Math.floorDiv(pos.getX(), cell), Math.floorDiv(pos.getY(), cell), Math.floorDiv(pos.getZ(), cell)),
                k -> new ArrayList<>()).add(pos);
        }

        boolean near(BlockPos pos) {
            double r2 = reach * reach;
            int gx = Math.floorDiv(pos.getX(), cell);
            int gy = Math.floorDiv(pos.getY(), cell);
            int gz = Math.floorDiv(pos.getZ(), cell);
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                List<BlockPos> in = cells.get(key(gx + dx, gy + dy, gz + dz));
                if (in == null) continue;
                for (BlockPos m : in) if (m.distSqr(pos) <= r2) return true;
            }
            return false;
        }

        private static long key(int x, int y, int z) {
            return ((long) x & 0x1FFFFF) << 42 | ((long) y & 0x1FFFFF) << 21 | ((long) z & 0x1FFFFF);
        }
    }

    // ---- Drawing

    @Override
    public void onRender3D(Render3D render) {
        String where = where();
        int rgb = 0xFF000000 | color.get();
        Map<Long, Long> seen = checked.get() ? checkedAt.get(where) : null;
        if (seen != null && !seen.isEmpty()) {
            // Where you've seen the real depths: flat and faint at Y 0, and not over a chunk that's marked.
            Set<Long> marked = new HashSet<>();
            for (Found f : found) if (f.where.equals(where)) marked.add(ChunkPos.asLong(f.cx, f.cz));
            int ccx = Mth.floor(render.camera().x) >> 4;
            int ccz = Mth.floor(render.camera().z) >> 4;
            int fill = Theme.alpha(CHECKED_COLOR, 22);
            int edge = Theme.alpha(CHECKED_COLOR, 90);
            for (long key : seen.keySet()) {
                int cx = ChunkPos.getX(key);
                int cz = ChunkPos.getZ(key);
                if (Math.max(Math.abs(cx - ccx), Math.abs(cz - ccz)) > CHECKED_DRAWN || marked.contains(key)) continue;
                render.box(new AABB(cx * 16 + 0.6, -0.02, cz * 16 + 0.6, cx * 16 + 15.4, 0.15, cz * 16 + 15.4), fill, edge, 1f, false);
            }
        }
        for (Found f : found) {
            if (!f.where.equals(where)) continue;
            Vec3 c = f.center();
            if (Math.hypot(c.x - render.camera().x, c.z - render.camera().z) > 1024) continue;
            // The chunk itself, flat at Y 0 where the hidden layers start: the base would be underneath.
            int x0 = f.cx * 16;
            int z0 = f.cz * 16;
            float sure = (float) Math.min(1, f.score / 60);
            render.box(new AABB(x0, -0.05, z0, x0 + 16, 0.6, z0 + 16), Theme.alpha(rgb, 26 + 40 * sure), Theme.alpha(rgb, 160 + 80 * sure), 2f, true);
            if (beams.get()) {
                for (int i = 0; i < 16; i++) {
                    float fade = 1 - i / 16f;
                    Vec3 from = c.add(0, i * 6, 0);
                    render.line(from, from.add(0, 6, 0), Theme.alpha(rgb, 235 * fade * fade), 2.6f, true);
                }
            }
            if (markClues.get() && f.live) {
                int marked = 0;
                for (Hit h : f.hits) {
                    if (marked++ >= MAX_PER_CLUE * 2) break;
                    render.box(h.box().inflate(0.02), Theme.alpha(h.clue().color, 36), Theme.alpha(h.clue().color, 210), 1.3f, false);
                }
            }
        }
    }

    @Override
    public void onRender2D(GuiGraphics g, float partialTick) {
        if (!labels.get()) return;
        String where = where();
        var window = mc.getWindow();
        double midX = window.getWidth() / 2.0;
        double midY = window.getHeight() / 2.0;
        double focus = 90 * window.getHeight() / 1080.0;
        Found focused = null;
        double focusedOff = focus;
        List<Object[]> shown = new ArrayList<>();
        for (Found f : found) {
            if (!f.where.equals(where)) continue;
            double[] screen = Projection.toScreen(f.center().add(0, 1, 0));
            if (screen == null) continue;
            double off = Math.hypot(screen[0] - midX, screen[1] - midY);
            if (off < focusedOff) {
                focused = f;
                focusedOff = off;
            }
            shown.add(new Object[] {f, screen});
        }
        for (Object[] s : shown) badge(g, (Found) s[0], (double[]) s[1], s[0] == focused);
    }

    /** "Chunk 47 · 312m", and when looked at, where and why. */
    private void badge(GuiGraphics g, Found f, double[] screen, boolean focused) {
        int s = Render2D.scale();
        int rgb = 0xFF000000 | color.get();
        String title = "Chunk " + Math.round(f.score);
        Vec3 c = f.center();
        String dist = Math.round(Math.hypot(c.x - mc.player.getX(), c.z - mc.player.getZ())) + "m";
        List<String> lines = new ArrayList<>();
        if (focused) {
            lines.add(StreamerMode.mask(Math.round(c.x) + ", " + Math.round(c.z)) + "  below Y 0" + (f.live ? "" : "  (remembered)"));
            lines.addAll(wrap(f.reasons, 200));
        }
        int pad = 4;
        int line = Text.CAP + 4;
        int w = Text.width(title) + 6 + Text.width(dist);
        for (String l : lines) w = Math.max(w, Text.width(l));
        w += pad * 2;
        int h = pad * 2 + Text.CAP + 1 + lines.size() * line;
        int x = (int) Math.round(screen[0] / s) - w / 2;
        int y = (int) Math.round(screen[1] / s) - h - 6;
        float a = focused ? 1 : 0.85f;
        Render2D.pixels(g);
        Render2D.glow(g, x * s, y * s, w * s, h * s, 3 * s, 6 * s, Theme.alpha(rgb, (focused ? 90 : 50) * Theme.glow()));
        Render2D.rect(g, x * s, y * s, w * s, h * s, 3 * s, Theme.fade(Theme.BORDER, a));
        Render2D.rect(g, x * s + 1, y * s + 1, w * s - 2, h * s - 2, 3 * s - 1, Theme.fade(0xE6101217, a));
        Render2D.rect(g, x * s + 3 * s, y * s + 1, w * s - 6 * s, Math.max(1, s / 2), 0, rgb);
        Render2D.end(g);
        Text.draw(g, title, x + pad, y + pad + 1, Theme.fade(rgb, a));
        Text.draw(g, dist, x + pad + Text.width(title) + 6, y + pad + 1, Theme.TEXT_FAINT);
        int ly = y + pad + 1 + line;
        for (String l : lines) {
            Text.draw(g, l, x + pad, ly, Theme.TEXT_DIM);
            ly += line;
        }
    }

    private static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (!line.isEmpty() && Text.width(line + " " + word) > width) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (!line.isEmpty()) line.append(' ');
            line.append(word);
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out.size() > 5 ? out.subList(0, 5) : out;
    }

    // ---- Remembering

    private String where() {
        String server;
        if (mc.getCurrentServer() != null) server = mc.getCurrentServer().ip;
        else if (mc.getSingleplayerServer() != null) server = "singleplayer:" + mc.getSingleplayerServer().getWorldData().getLevelName();
        else server = "unknown";
        return server + "|" + (mc.level == null ? "" : mc.level.dimension().identifier().toString());
    }

    private Path file() {
        return Config.dir().resolve("chunks-found.json");
    }

    private void load() {
        if (loaded) return;
        loaded = true;
        try {
            JsonArray saved = Files.exists(file()) ? JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8)).getAsJsonArray() : new JsonArray();
            for (JsonElement e : saved) {
                JsonObject o = e.getAsJsonObject();
                Found f = new Found(o.get("where").getAsString(), o.get("cx").getAsInt(), o.get("cz").getAsInt(),
                    o.get("score").getAsDouble(), o.get("reasons").getAsString());
                if (o.has("watch")) {
                    for (JsonElement w : o.getAsJsonArray("watch")) {
                        JsonArray a = w.getAsJsonArray();
                        f.watched.put(new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()),
                            new Watch(a.get(3).getAsString(), a.get(4).getAsString()));
                    }
                }
                if (o.has("used")) {
                    f.used = o.get("used").getAsString();
                    f.usedAt = o.get("usedAt").getAsLong();
                }
                found.add(f);
            }
        } catch (IOException | RuntimeException ignored) {
            // A broken file: start fresh rather than lose the game.
        }
        try {
            if (!Files.exists(checkedFile())) return;
            JsonObject root = JsonParser.parseString(Files.readString(checkedFile(), StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> world : root.entrySet()) {
                Map<Long, Long> chunks = checkedAt.computeIfAbsent(world.getKey(), k -> new HashMap<>());
                for (JsonElement c : world.getValue().getAsJsonArray()) {
                    JsonArray a = c.getAsJsonArray();
                    chunks.put(ChunkPos.asLong(a.get(0).getAsInt(), a.get(1).getAsInt()), a.get(2).getAsLong());
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Start the checking over.
        }
    }

    private Path checkedFile() {
        return Config.dir().resolve("chunks-checked.json");
    }

    private void saveChecked() {
        if (!checkedDirty) return;
        checkedDirty = false;
        JsonObject root = new JsonObject();
        for (Map.Entry<String, Map<Long, Long>> world : checkedAt.entrySet()) {
            JsonArray chunks = new JsonArray();
            for (Map.Entry<Long, Long> c : world.getValue().entrySet()) {
                JsonArray a = new JsonArray();
                a.add(ChunkPos.getX(c.getKey()));
                a.add(ChunkPos.getZ(c.getKey()));
                a.add(c.getValue());
                chunks.add(a);
            }
            root.add(world.getKey(), chunks);
        }
        try {
            Files.createDirectories(checkedFile().getParent());
            Files.writeString(checkedFile(), root.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // Still checked for this session.
        }
    }

    private void save() {
        if (!remember.get()) return;
        JsonArray array = new JsonArray();
        for (Found f : found) {
            JsonObject o = new JsonObject();
            o.addProperty("where", f.where);
            o.addProperty("cx", f.cx);
            o.addProperty("cz", f.cz);
            o.addProperty("score", Math.round(f.score));
            o.addProperty("reasons", f.reasons);
            if (!f.watched.isEmpty()) {
                JsonArray w = new JsonArray();
                for (Map.Entry<BlockPos, Watch> e : f.watched.entrySet()) {
                    JsonArray a = new JsonArray();
                    a.add(e.getKey().getX());
                    a.add(e.getKey().getY());
                    a.add(e.getKey().getZ());
                    a.add(e.getValue().way);
                    a.add(e.getValue().state);
                    w.add(a);
                }
                o.add("watch", w);
            }
            if (f.used != null) {
                o.addProperty("used", f.used);
                o.addProperty("usedAt", f.usedAt);
            }
            array.add(o);
        }
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), array.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // Still found for this session.
        }
    }
}
