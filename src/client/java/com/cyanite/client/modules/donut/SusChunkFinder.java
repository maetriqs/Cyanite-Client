package com.cyanite.client.modules.donut;

import com.cyanite.client.gui.Theme;
import com.cyanite.client.render.Badge;
import com.cyanite.client.render.Projection;
import com.cyanite.client.render.Render3D;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.ChunkScanner;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Notify;
import com.cyanite.client.system.Simulation;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.SliderSetting;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.util.TriState;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BuddingAmethystBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.GrowingPlantHeadBlock;
import net.minecraft.world.level.block.MangrovePropaguleBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DripstoneThickness;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Chunks somebody has kept loaded for hours, and where they sit. A chunk only ticks while a player
 * is within the server's simulation distance of it (a square that many chunks out from theirs),
 * and some things in it grow only on those ticks:
 * <ul>
 * <li>Kelp. Worldgen only puts kelp where there's water over it, so its tops never reach the
 *     surface; each time it grows it gets a block taller, and an hour of ticking takes a good few
 *     of them up to it (none of 1,100 as generated; up to a third after an hour). Oceans are up
 *     top, where the server shows what's really there. Kelp's age says it too (worldgen leaves
 *     20 to 23, ticking takes it to 25), where the server sends it: Donut sends 0 for all of it.</li>
 * <li>Amethyst. Worldgen gives a bud to about a third of a geode's budding amethyst, never more
 *     than one each, at all four sizes (about a quarter full clusters). Ticking sprouts new buds
 *     on the empty faces (most of them within an hour) and grows them all, slowly: after hours
 *     nearly every bud is a full cluster. So a budding block with two buds or more has been
 *     ticked, and a geode with most of its budding blocks bare hasn't. (How much of it is grown
 *     says little in between: new buds sprout about as fast as old ones grow.) A bud the server
 *     hides still glows, and the light it sends gives it away: 1, 2, 4 or 5 by its size, nothing
 *     round it brighter, against the geode's lining.</li>
 * <li>Hanging mangrove propagules. Worldgen leaves them at all five ages; ticking grows every one
 *     to the last.</li>
 * <li>Dripstone. A stalactite grows only while water drips through it (a water source on top of
 *     the dripstone block it hangs from), up to seven long or till it meets the stalagmite under
 *     it. Worldgen very rarely leaves one like that (about a chunk in a thousand), never two.</li>
 * <li>Sugar cane and cactus. Worldgen makes sugar cane 2 to 4 tall (most of it 2) and cactus 1 to
 *     3 (most of it 1); ticking grows both to 3, a block every quarter of an hour or so. Sugar cane
 *     is never 1 tall unless someone's cut it, often a farm that's being ticked, so short cane
 *     only says "untouched" where none of it is cut. Cactus farms keep theirs short too: cactus
 *     only ever says "grown".</li>
 * <li>Crops, stems and nether wart, all fully grown: planted by someone, and ticked long after.
 *     (Young ones prove nothing: a farm harvesting itself keeps them young while it's ticked.)</li>
 * <li>Eyeblossoms. They open at nightfall and shut at dawn, but only on a tick, and worldgen
 *     leaves them shut. Open ones a few minutes into the night have been ticked since dusk; ones
 *     the wrong way for the time of day haven't been ticked since it last changed.</li>
 * </ul>
 * Each chunk where something's grown is marked at sea level.
 *
 * <p>Where they sit comes from the square: everything within the simulation distance of them grows
 * and nothing past it. Every grown chunk adds to all the chunks within that distance of it (the
 * places someone could have sat to grow it), and a chunk still as worldgen left it (a geode with
 * most of its budding amethyst bare, propagules mostly young, sugar cane mostly short and none of
 * it cut, eyeblossoms the wrong way: all change within the hour) rules out every chunk within that
 * distance of it, since anyone sitting there would have grown it too. The guess is the hottest
 * area nothing rules out, with
 * its middle marked and its spread shown: one grown chunk says little, a ring of them round one
 * spot pins it. Kelp and dripstone only add, never rule out: kelp often stops a block short of
 * the surface, and grown dripstone's too rare to say anything by its absence.
 *
 * <p>You tick chunks too: where you've been a while (five minutes all told within the simulation
 * distance), what grew might be your doing, so it doesn't count.
 *
 * <p>The simulation-distance guess, reading hidden amethyst by its light and most of the plant
 * signs follow the Anubis client (GPL-3.0); the code is Cyanite's own.
 */
public class SusChunkFinder extends Module {
    /** How long a stalactite grows to, at most. */
    private static final int GROWN_LENGTH = 7;
    /** Worldgen's oldest kelp. */
    private static final int KELP_WORLDGEN_MAX = 23;
    private static final int MAX_SPOTS = 32;
    private static final int OWN_SECONDS = 300;
    /** Propagules grown no further than this share are as worldgen left them: no one's sat near. */
    private static final double NATURAL_SHARE = 0.4;
    /**
     * A geode with at least this share of its budding amethyst bare is as worldgen left it (about
     * two thirds are). An hour's ticking leaves a budding block with one open face bare about one
     * time in six: at this share, a small geode near someone still looks untouched well under one
     * time in a hundred.
     */
    private static final double BARE_SHARE = 0.6;
    /** Budding amethyst with two buds or more, this many in a chunk: ticked, as worldgen never leaves one like that. */
    private static final int LEAST_SPROUTED = 2;
    /** Of the square round a guess, at least this much looked at, or it's a guess about chunks never seen. */
    private static final double LEAST_LOOKED = 1 / 3.0;
    /** Sugar cane, cactus, crops: at least this many in a chunk to say anything. */
    private static final int LEAST_PLANTS = 8;
    /** Of those, this share grown all the way: ticked. */
    private static final double PLANTS_GROWN = 0.9;
    /** Eyeblossoms: at least this many, all one way. */
    private static final int LEAST_EYEBLOSSOMS = 3;
    /** When eyeblossoms open and shut, in the day's ticks (as the overworld's timeline has it). */
    private static final int EYEBLOSSOMS_OPEN = 12600;
    private static final int EYEBLOSSOMS_SHUT = 23401;
    /** How long after that before a ticked eyeblossom has surely changed too (a few minutes). */
    private static final int EYEBLOSSOMS_SETTLE = 4000;
    private static final List<Block> BUDS = List.of(Blocks.SMALL_AMETHYST_BUD, Blocks.MEDIUM_AMETHYST_BUD, Blocks.LARGE_AMETHYST_BUD,
        Blocks.AMETHYST_CLUSTER);

    private final BoolSetting kelp = add(new BoolSetting("Grown kelp", true));
    private final SliderSetting leastKelp = add(new SliderSetting("Least grown kelp", 2, 1, 20, 1));
    private final BoolSetting kelpAge = add(new BoolSetting("Kelp age", true));
    private final BoolSetting amethyst = add(new BoolSetting("Grown amethyst", true));
    private final BoolSetting amethystLight = add(new BoolSetting("Amethyst by its light", true));
    private final SliderSetting leastBuds = add(new SliderSetting("Least buds", 8, 3, 40, 1).format(v -> (int) v + " buds"));
    private final SliderSetting grownShare = add(new SliderSetting("Fully grown share", 0.8, 0.5, 1, 0.05).percent());
    private final BoolSetting propagules = add(new BoolSetting("Grown mangrove propagules", true));
    private final BoolSetting dripstone = add(new BoolSetting("Grown dripstone", true));
    private final SliderSetting leastDrip = add(new SliderSetting("Least grown dripstone", 2, 1, 10, 1));
    private final BoolSetting caneCactus = add(new BoolSetting("Grown cane & cactus", true));
    private final BoolSetting crops = add(new BoolSetting("Grown crops", true));
    private final BoolSetting eyeblossoms = add(new BoolSetting("Eyeblossoms", true));
    private final BoolSetting guess = add(new BoolSetting("Guess where they sit", true));
    private final SliderSetting leastGrown = add(new SliderSetting("Least grown chunks", 2, 1, 10, 1)
        .format(v -> (int) v + ((int) v == 1 ? " chunk" : " chunks")));
    /** 0: what the server says its simulation distance is. */
    private final SliderSetting simulation = add(new SliderSetting("Simulation distance", 0, 0, 16, 1)
        .format(v -> v < 1 ? "Server's" : (int) v + " chunks"));
    private final BoolSetting skipOwn = add(new BoolSetting("Skip where you've been", true));
    private final SliderSetting radius = add(new SliderSetting("Radius", 12, 2, 32, 1).format(v -> (int) v + " chunks"));
    private final BoolSetting markSpots = add(new BoolSetting("Mark what grew", true));
    private final BoolSetting labels = add(new BoolSetting("Labels", true));
    private final BoolSetting notify = add(new BoolSetting("Notify", true));
    private final ColorSetting color = add(new ColorSetting("Color", 0xC77DFF));

    /**
     * What grew in a chunk: kelp tops and how many are past what worldgen leaves or up at the
     * surface; amethyst buds and full clusters, and how many of those were only seen by their
     * light; budding amethyst open to the geode, how many of those are bare and how many carry
     * two buds or more; hanging propagules and fully grown ones; grown dripstone; sugar cane
     * columns (3 tall or more, 2 tall with room over them, cut down to 1); cactus columns and the
     * ones 3 tall; crops (and stems and nether wart) and the fully grown ones; eyeblossoms open
     * and shut; and where.
     */
    private record Grown(int kelpTops, int kelpGrown, int kelpSurface, int buds, int clusters, int byLight, int budding, int bare,
                         int sprouted, int hanging, int hangingGrown, int dripstone, int caneTall, int caneShort, int caneCut,
                         int cactus, int cactusTall, int crops, int cropsGrown, int eyesOpen, int eyesShut, List<BlockPos> spots) {
    }

    /** A chunk as looked at, for the guess: whether something grew in it, and whether it's still as generated. */
    private record Seen(boolean grown, boolean untouched) {
    }

    /** A chunk marked: where, why, and what grew in it. */
    private static final class Sus {
        final int cx;
        final int cz;
        String reasons;
        List<BlockPos> spots;

        Sus(int cx, int cz, String reasons, List<BlockPos> spots) {
            this.cx = cx;
            this.cz = cz;
            this.reasons = reasons;
            this.spots = spots;
        }

        Vec3 center() {
            return new Vec3(cx * 16 + 8, 0, cz * 16 + 8);
        }
    }

    /**
     * A guess at where someone sits: the middle chunk, how many grown chunks it's from, how far
     * across the spots it could be are, and those spots' corners (in chunks).
     */
    private record Sitter(int cx, int cz, int grown, int reach, int minX, int minZ, int maxX, int maxZ) {
        Vec3 center() {
            return new Vec3(cx * 16 + 8, 0, cz * 16 + 8);
        }
    }

    /** Marked chunks by world (server and dimension), for this session. */
    private final Map<String, Map<Long, Sus>> marked = new HashMap<>();
    /**
     * Chunks with something to say about where someone sits, by world. Kept once seen: on Donut a
     * chunk goes back to made-up rock under Y 16 when you go up, and what grew down there still did.
     */
    private final Map<String, Map<Long, Seen>> seen = new HashMap<>();
    /** Every chunk looked at, by world. */
    private final Map<String, LongOpenHashSet> lookedAt = new HashMap<>();
    /** How long you've been in each chunk, by world, in seconds. */
    private final Map<String, Map<Long, Integer>> own = new HashMap<>();
    private List<Sitter> sitters = List.of();
    private boolean sittersStale;
    private int sittersFor;
    private int tick;

    public SusChunkFinder() {
        super(Category.DONUT, "Sus Chunk Finder", "Highlights chunks someone kept loaded for hours: grown kelp, amethyst, propagules and dripstone");
        ChunkScanner.register(new ChunkScanner.Pass<Grown>() {
            @Override
            public boolean active() {
                return isEnabled();
            }

            @Override
            public boolean wants(int cx, int cz) {
                var player = mc.player;
                if (player == null) return false;
                ChunkPos at = player.chunkPosition();
                return Math.max(Math.abs(cx - at.x), Math.abs(cz - at.z)) <= radius.get().intValue();
            }

            @Override
            public Grown scan(LevelChunk chunk) {
                return SusChunkFinder.this.scan(chunk);
            }

            @Override
            public void accept(long key, Grown grown) {
                judge(key, grown);
            }
        });
    }

    @Override
    protected void onEnable() {
        ChunkScanner.rescanAll();
    }

    @Override
    public String getInfo() {
        if (!inGame()) return null;
        return Integer.toString(marked.getOrDefault(where(), Map.of()).size());
    }

    @Override
    public void onTick() {
        // Where you are, a second at a time: your own ticking grows things too.
        if (++tick % 20 == 0) own.computeIfAbsent(where(), k -> new HashMap<>()).merge(mc.player.chunkPosition().toLong(), 1, Integer::sum);
        if (tick % 20 == 0 && (sittersStale || sittersFor != simulationDistance())) {
            sittersStale = false;
            sittersFor = simulationDistance();
            sitters = guess.get() ? sitters(seen.getOrDefault(where(), Map.of()), lookedAt.getOrDefault(where(), new LongOpenHashSet()),
                sittersFor, leastGrown.get().intValue()) : List.of();
        }
    }

    /** How far out from a player chunks tick: as set, or as the server says. */
    private int simulationDistance() {
        return Simulation.distance(simulation.get().intValue());
    }

    // ---- Looking at a chunk

    private Grown scan(LevelChunk chunk) {
        int kelpTops = 0, kelpGrown = 0, kelpSurface = 0, buds = 0, clusters = 0, byLight = 0, budding = 0, bare = 0, sprouted = 0;
        int hanging = 0, hangingGrown = 0, grownDrip = 0;
        int caneTall = 0, caneShort = 0, caneCut = 0, cactus = 0, cactusTall = 0, crops = 0, cropsGrown = 0, eyesOpen = 0, eyesShut = 0;
        List<BlockPos> spots = new ArrayList<>();
        LevelChunkSection[] sections = chunk.getSections();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int x0 = chunk.getPos().getMinBlockX();
        int z0 = chunk.getPos().getMinBlockZ();
        boolean light = amethystLight.get();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section.hasOnlyAir()) continue;
            boolean growing = section.maybeHas(SusChunkFinder::grows);
            boolean lining = section.maybeHas(SusChunkFinder::lining);
            if (!growing && !lining) continue;
            int baseY = chunk.getMinY() + i * 16;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (lining && state.is(Blocks.BUDDING_AMETHYST)) {
                            // What's on its faces: buds it grew, or room for one.
                            int on = 0, room = 0;
                            for (Direction d : Direction.values()) {
                                BlockState next = chunk.getLevel().getBlockState(p.set(x0 + x + d.getStepX(), baseY + y + d.getStepY(), z0 + z + d.getStepZ()));
                                if (BUDS.contains(next.getBlock()) && next.getValue(BlockStateProperties.FACING) == d) on++;
                                else if (BuddingAmethystBlock.canClusterGrowAtState(next)) room++;
                            }
                            if (on + room > 0) {
                                budding++;
                                if (on == 0) bare++;
                                if (on >= 2) sprouted++;
                            }
                            continue;
                        }
                        if (lining && light && state.getLightEmission() == 0) {
                            // Something glowing that isn't what's shown: a bud the server hides.
                            int size = hiddenBud(chunk.getLevel(), p, x0 + x, baseY + y, z0 + z);
                            if (size > 0) {
                                buds++;
                                byLight++;
                                if (size == 5) {
                                    clusters++;
                                    if (spots.size() < MAX_SPOTS) spots.add(new BlockPos(x0 + x, baseY + y, z0 + z));
                                }
                                continue;
                            }
                        }
                        if (!growing || !grows(state)) continue;
                        Block block = state.getBlock();
                        boolean grown = false;
                        if (block == Blocks.KELP) {
                            // Counted, not marked one by one: there's kelp everywhere, the chunk says enough.
                            kelpTops++;
                            if (state.getValue(GrowingPlantHeadBlock.AGE) > KELP_WORLDGEN_MAX) kelpGrown++;
                            // Grown right up to the surface: no water over it.
                            if (chunk.getFluidState(p.set(x0 + x, baseY + y + 1, z0 + z)).isEmpty()) kelpSurface++;
                        } else if (BUDS.contains(block)) {
                            buds++;
                            grown = block == Blocks.AMETHYST_CLUSTER;
                            if (grown) clusters++;
                        } else if (block == Blocks.MANGROVE_PROPAGULE) {
                            if (!state.getValue(MangrovePropaguleBlock.HANGING)) continue;
                            hanging++;
                            grown = state.getValue(MangrovePropaguleBlock.AGE) == MangrovePropaguleBlock.MAX_AGE;
                            if (grown) hangingGrown++;
                        } else if (block == Blocks.SUGAR_CANE || block == Blocks.CACTUS) {
                            // One column, from its bottom block: how tall, and whether there's room to grow.
                            if (chunk.getBlockState(p.set(x0 + x, baseY + y - 1, z0 + z)).is(block)) continue;
                            int tall = 1;
                            while (tall < 5 && chunk.getBlockState(p.set(x0 + x, baseY + y + tall, z0 + z)).is(block)) tall++;
                            boolean room = chunk.getBlockState(p.set(x0 + x, baseY + y + tall, z0 + z)).isAir();
                            if (block == Blocks.CACTUS) {
                                cactus++;
                                if (tall >= 3) cactusTall++;
                            } else if (tall >= 3) {
                                caneTall++;
                            } else if (tall == 2 && room) {
                                caneShort++;
                            } else if (tall == 1) {
                                caneCut++;
                            }
                        } else if (block instanceof CropBlock crop) {
                            crops++;
                            if (crop.isMaxAge(state)) cropsGrown++;
                        } else if (block == Blocks.NETHER_WART) {
                            crops++;
                            if (state.getValue(NetherWartBlock.AGE) == NetherWartBlock.MAX_AGE) cropsGrown++;
                        } else if (block instanceof StemBlock) {
                            crops++;
                        } else if (block instanceof AttachedStemBlock) {
                            // Only ever there once its pumpkin or melon has grown.
                            crops++;
                            cropsGrown++;
                        } else if (block == Blocks.OPEN_EYEBLOSSOM) {
                            eyesOpen++;
                        } else if (block == Blocks.CLOSED_EYEBLOSSOM) {
                            eyesShut++;
                        } else if (grownStalactite(chunk, p, x0 + x, baseY + y, z0 + z)) {
                            grownDrip++;
                            grown = true;
                        }
                        if (grown && spots.size() < MAX_SPOTS) spots.add(new BlockPos(x0 + x, baseY + y, z0 + z));
                    }
                }
            }
        }
        return new Grown(kelpTops, kelpGrown, kelpSurface, buds, clusters, byLight, budding, bare, sprouted, hanging, hangingGrown, grownDrip,
            caneTall, caneShort, caneCut, cactus, cactusTall, crops, cropsGrown, eyesOpen, eyesShut, spots);
    }

    private static boolean grows(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.KELP || block == Blocks.POINTED_DRIPSTONE || block == Blocks.MANGROVE_PROPAGULE || BUDS.contains(block)
            || block == Blocks.SUGAR_CANE || block == Blocks.CACTUS || block instanceof CropBlock || block == Blocks.NETHER_WART
            || block instanceof StemBlock || block instanceof AttachedStemBlock || block == Blocks.OPEN_EYEBLOSSOM || block == Blocks.CLOSED_EYEBLOSSOM;
    }

    /**
     * Whether eyeblossoms at (x, z) should be open now, as the world's clock has it; null if they
     * changed too lately for one that's ticked to have surely changed too, or the world doesn't
     * open or shut them.
     */
    private Boolean eyeblossomsOpen(int x, int z) {
        long time = Math.floorMod(mc.level.getDayTime(), 24000L);
        long since = time >= EYEBLOSSOMS_OPEN && time < EYEBLOSSOMS_SHUT ? time - EYEBLOSSOMS_OPEN : Math.floorMod(time - EYEBLOSSOMS_SHUT, 24000L);
        if (since < EYEBLOSSOMS_SETTLE) return null;
        TriState open = mc.level.environmentAttributes().getValue(EnvironmentAttributes.EYEBLOSSOM_OPEN, new BlockPos(x, mc.level.getSeaLevel(), z));
        if (open != TriState.DEFAULT) return open == TriState.TRUE;
        // The client isn't sent the day's timeline: by the clock, then, where there's a day to go by.
        if (mc.level.dimension() != Level.OVERWORLD || mc.level.dimensionType().hasFixedTime()) return null;
        return time >= EYEBLOSSOMS_OPEN && time < EYEBLOSSOMS_SHUT;
    }

    /** A geode's lining: what buds grow out of, and what's round them. */
    private static boolean lining(BlockState state) {
        return state.is(Blocks.BUDDING_AMETHYST) || state.is(Blocks.AMETHYST_BLOCK);
    }

    /**
     * The light a hidden bud leaves at (x, y, z): 1, 2, 4 or 5 by its size (5 is a full cluster),
     * with nothing round it brighter (light only spreading always has the brighter spot it came
     * from next to it; buds side by side are as bright as each other), and up against the lining.
     * 0 if there's no bud there to see.
     */
    private static int hiddenBud(Level level, BlockPos.MutableBlockPos p, int x, int y, int z) {
        int value = level.getBrightness(LightLayer.BLOCK, p.set(x, y, z));
        if (value != 1 && value != 2 && value != 4 && value != 5) return 0;
        boolean against = false;
        for (Direction d : Direction.values()) {
            p.set(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ());
            if (level.getBrightness(LightLayer.BLOCK, p) > value) return 0;
            against |= lining(level.getBlockState(p));
        }
        return against ? value : 0;
    }

    /**
     * Whether (x, y, z) is the top of a stalactite that's done growing: hanging from a dripstone
     * block with a water source on it (the only way one grows), and seven long or met by the
     * stalagmite under it.
     */
    private static boolean grownStalactite(LevelChunk chunk, BlockPos.MutableBlockPos p, int x, int y, int z) {
        BlockState top = chunk.getBlockState(p.set(x, y, z));
        if (!hanging(top) || hanging(chunk.getBlockState(p.set(x, y + 1, z)))) return false;
        if (!chunk.getBlockState(p.set(x, y + 1, z)).is(Blocks.DRIPSTONE_BLOCK)) return false;
        FluidState water = chunk.getFluidState(p.set(x, y + 2, z));
        if (!water.is(Fluids.WATER) || !water.isSource()) return false;
        int length = 0;
        BlockState tip = top;
        for (int at = y; at > y - 16; at--) {
            BlockState s = chunk.getBlockState(p.set(x, at, z));
            if (!hanging(s)) break;
            tip = s;
            length++;
        }
        return length >= GROWN_LENGTH || tip.getValue(PointedDripstoneBlock.THICKNESS) == DripstoneThickness.TIP_MERGE;
    }

    private static boolean hanging(BlockState state) {
        return state.is(Blocks.POINTED_DRIPSTONE) && state.getValue(PointedDripstoneBlock.TIP_DIRECTION) == Direction.DOWN;
    }

    // ---- Marking

    private void judge(long key, Grown g) {
        if (!inGame()) return;
        boolean byAge = kelpAge.get() && g.kelpGrown() >= leastKelp.get() && g.kelpGrown() * 2 >= g.kelpTops();
        boolean bySurface = g.kelpSurface() >= leastKelp.get();
        boolean kelpGrown = kelp.get() && (byAge || bySurface);
        boolean enoughBuds = g.buds() >= leastBuds.get();
        boolean amethystGrown = amethyst.get() && enoughBuds && g.clusters() >= g.buds() * grownShare.get();
        boolean sprouted = amethyst.get() && g.sprouted() >= LEAST_SPROUTED;
        boolean enoughHanging = g.hanging() >= leastBuds.get();
        boolean propagulesGrown = propagules.get() && enoughHanging && g.hangingGrown() >= g.hanging() * grownShare.get();
        boolean dripGrown = dripstone.get() && g.dripstone() >= leastDrip.get();
        int cane = g.caneTall() + g.caneShort() + g.caneCut();
        boolean caneGrown = caneCactus.get() && cane >= LEAST_PLANTS && g.caneTall() >= cane * PLANTS_GROWN;
        boolean cactusGrown = caneCactus.get() && g.cactus() >= LEAST_PLANTS && g.cactusTall() >= g.cactus() * PLANTS_GROWN;
        boolean cropsGrown = crops.get() && g.crops() >= LEAST_PLANTS && g.cropsGrown() >= g.crops() * PLANTS_GROWN;
        Boolean open = eyeblossoms.get() && g.eyesOpen() + g.eyesShut() > 0 ? eyeblossomsOpen(ChunkPos.getX(key) * 16 + 8, ChunkPos.getZ(key) * 16 + 8) : null;
        int eyesRight = open == null ? 0 : open ? g.eyesOpen() : g.eyesShut();
        int eyesWrong = open == null ? 0 : open ? g.eyesShut() : g.eyesOpen();
        // Open at night: ticked since dusk (worldgen leaves them shut, so shut ones by day prove nothing).
        boolean eyesTicked = open == Boolean.TRUE && eyesRight >= LEAST_EYEBLOSSOMS && eyesWrong == 0;
        boolean mine = yours(key);

        // For the guess: whether something grew (not counted where it could be your doing), and whether it's as generated.
        boolean grown = !mine && (kelpGrown || amethystGrown || sprouted || propagulesGrown || dripGrown || caneGrown || cactusGrown
            || cropsGrown || eyesTicked);
        boolean untouched = amethyst.get() && g.budding() >= leastBuds.get() && g.bare() >= g.budding() * BARE_SHARE
            || propagules.get() && enoughHanging && g.hangingGrown() <= g.hanging() * NATURAL_SHARE
            || caneCactus.get() && g.caneCut() == 0 && cane >= LEAST_PLANTS && g.caneShort() >= cane * 0.5
            || eyesWrong >= LEAST_EYEBLOSSOMS && eyesRight == 0;
        if (lookedAt.computeIfAbsent(where(), k -> new LongOpenHashSet()).add(key)) sittersStale = true;
        if (grown || untouched) {
            Seen now = new Seen(grown, untouched);
            if (!Objects.equals(seen.computeIfAbsent(where(), k -> new HashMap<>()).put(key, now), now)) sittersStale = true;
        }

        // What grew where you've been a while may be your own doing.
        if (skipOwn.get() && mine) return;
        List<String> why = new ArrayList<>();
        if (kelpGrown) why.add(bySurface ? g.kelpSurface() + " kelp grown up to the surface" : g.kelpGrown() + " of " + g.kelpTops() + " kelp grown");
        if (amethystGrown) {
            why.add(g.clusters() + " of " + g.buds() + " amethyst buds fully grown" + (g.byLight() > 0 ? " (" + g.byLight() + " seen by their light)" : ""));
        }
        if (sprouted) why.add(g.sprouted() + " budding amethyst with new buds sprouted on (worldgen leaves one at most)");
        if (propagulesGrown) why.add(g.hangingGrown() + " of " + g.hanging() + " mangrove propagules fully grown");
        if (dripGrown) why.add(g.dripstone() == 1 ? "a fully grown dripstone" : g.dripstone() + " fully grown dripstones");
        if (caneGrown) why.add(g.caneTall() + " of " + cane + " sugar cane grown full height");
        if (cactusGrown) why.add(g.cactusTall() + " of " + g.cactus() + " cactus grown full height");
        if (cropsGrown) why.add(g.cropsGrown() + " of " + g.crops() + " crops fully grown");
        if (eyesTicked) why.add(eyesRight + " eyeblossoms opened since dusk");
        if (why.isEmpty()) return;
        Map<Long, Sus> here = marked.computeIfAbsent(where(), k -> new HashMap<>());
        String reasons = String.join(", ", why);
        Sus s = here.get(key);
        if (s != null) {
            s.reasons = reasons;
            s.spots = g.spots();
            return;
        }
        s = new Sus(ChunkPos.getX(key), ChunkPos.getZ(key), reasons, g.spots());
        here.put(key, s);
        if (notify.get()) {
            long dist = Math.round(Math.hypot(s.cx * 16 + 8 - mc.player.getX(), s.cz * 16 + 8 - mc.player.getZ()));
            Notify.send("Sus chunk", (s.cx * 16 + 8) + ", " + (s.cz * 16 + 8) + " (" + dist + "m): " + reasons, true, true, false);
        }
    }

    /** Whether you've spent long enough near this chunk for what grew in it to be your own doing. */
    private boolean yours(long key) {
        if (!skipOwn.get()) return false;
        int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
        int reach = simulationDistance();
        int seconds = 0;
        for (Map.Entry<Long, Integer> e : own.getOrDefault(where(), Map.of()).entrySet()) {
            if (Math.max(Math.abs(ChunkPos.getX(e.getKey()) - cx), Math.abs(ChunkPos.getZ(e.getKey()) - cz)) <= reach) seconds += e.getValue();
            if (seconds >= OWN_SECONDS) return true;
        }
        return false;
    }

    // ---- Where they sit

    /**
     * Where someone could sit to have grown what grew: every chunk within {@code reach} of a
     * grown one gets its count, none within {@code reach} of one still as generated counts (see
     * {@link Simulation#hottest}), and only where enough of the square round it has been looked at.
     */
    private static List<Sitter> sitters(Map<Long, Seen> seen, LongOpenHashSet lookedAt, int reach, int least) {
        Long2IntOpenHashMap heat = new Long2IntOpenHashMap();
        LongOpenHashSet ruledOut = new LongOpenHashSet();
        for (Map.Entry<Long, Seen> e : seen.entrySet()) {
            if (e.getValue().grown()) Simulation.spread(heat, e.getKey(), reach, 1);
            if (e.getValue().untouched()) Simulation.spread(ruledOut, e.getKey(), reach);
        }
        List<Sitter> out = new ArrayList<>();
        for (Simulation.Area a : Simulation.hottest(heat, ruledOut, least)) {
            if (looked(lookedAt, a.cx(), a.cz(), reach) < LEAST_LOOKED) continue;
            out.add(new Sitter(a.cx(), a.cz(), a.heat(), a.spread(), a.minX(), a.minZ(), a.maxX(), a.maxZ()));
        }
        return out;
    }

    /** How much of the square {@code reach} out from a chunk has been looked at. */
    private static double looked(LongOpenHashSet lookedAt, int cx, int cz, int reach) {
        int side = reach * 2 + 1;
        int n = 0;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                if (lookedAt.contains(ChunkPos.asLong(cx + dx, cz + dz))) n++;
            }
        }
        return (double) n / (side * side);
    }

    // ---- Drawing

    @Override
    public void onRender3D(Render3D render) {
        Map<Long, Sus> here = marked.get(where());
        int rgb = 0xFF000000 | color.get();
        double sea = mc.level.getSeaLevel();
        if (here != null) {
            for (Sus s : here.values()) {
                Vec3 c = s.center();
                if (Math.hypot(c.x - render.camera().x, c.z - render.camera().z) > 1024) continue;
                // The chunk, flat at sea level.
                render.box(new AABB(s.cx * 16, sea, s.cz * 16, s.cx * 16 + 16, sea + 0.2, s.cz * 16 + 16),
                    Theme.alpha(rgb, 46), Theme.alpha(rgb, 220), 2f, true);
                if (markSpots.get()) {
                    for (BlockPos spot : s.spots) render.box(new AABB(spot).inflate(0.02), Theme.alpha(rgb, 40), Theme.alpha(rgb, 200), 1.2f, false);
                }
            }
        }
        // Where they probably sit: a beam up from sea level, and the chunks it could be.
        for (Sitter s : sitters) {
            Vec3 c = s.center();
            if (Math.hypot(c.x - render.camera().x, c.z - render.camera().z) > 1024) continue;
            if (s.reach() > 1) {
                render.box(new AABB(s.minX() * 16, sea, s.minZ() * 16, (s.maxX() + 1) * 16, sea + 0.4, (s.maxZ() + 1) * 16),
                    0, Theme.alpha(0xFFFFD43B, 160), 1.6f, true);
            }
            render.box(new AABB(s.cx() * 16 + 5, sea, s.cz() * 16 + 5, s.cx() * 16 + 11, sea + 0.3, s.cz() * 16 + 11),
                Theme.alpha(0xFFFFD43B, 70), Theme.alpha(0xFFFFD43B, 230), 2f, true);
            for (int i = 0; i < 16; i++) {
                float fade = 1 - i / 16f;
                Vec3 from = c.add(0, sea + i * 6, 0);
                render.line(from, from.add(0, 6, 0), Theme.alpha(0xFFFFD43B, 235 * fade * fade), 2.6f, true);
            }
        }
    }

    @Override
    public void onRender2D(GuiGraphics g, float partialTick) {
        if (!labels.get()) return;
        var window = mc.getWindow();
        double midX = window.getWidth() / 2.0;
        double midY = window.getHeight() / 2.0;
        double focus = 90 * window.getHeight() / 1080.0;
        double sea = mc.level.getSeaLevel();
        Object focused = null;
        double focusedOff = focus;
        List<Object[]> shown = new ArrayList<>();
        List<Object> all = new ArrayList<>(marked.getOrDefault(where(), Map.of()).values());
        all.addAll(sitters);
        for (Object o : all) {
            Vec3 c = o instanceof Sus s ? s.center() : ((Sitter) o).center();
            double[] screen = Projection.toScreen(c.add(0, sea + (o instanceof Sitter ? 4 : 1), 0));
            if (screen == null) continue;
            double off = Math.hypot(screen[0] - midX, screen[1] - midY);
            if (off < focusedOff) {
                focused = o;
                focusedOff = off;
            }
            shown.add(new Object[] {o, screen});
        }
        for (Object[] o : shown) {
            if (o[0] instanceof Sus s) {
                // In a sitter's square the one label says it all; a chunk's own shows when you look at it.
                if (o[0] != focused && insideSitter(s)) continue;
                Badge.draw(g, "Sus chunk", s.center(), s.reasons, 0xFF000000 | color.get(), (double[]) o[1], o[0] == focused);
            } else {
                Sitter s = (Sitter) o[0];
                String why = s.grown() + (s.grown() == 1 ? " grown chunk" : " grown chunks") + " within " + sittersFor + " chunks"
                    + (s.reach() > 1 ? ", somewhere in the " + (s.maxX() - s.minX() + 1) + " by " + (s.maxZ() - s.minZ() + 1) + " outlined" : "");
                Badge.draw(g, "Someone sits about here", s.center(), why, 0xFFFFD43B, (double[]) o[1], o[0] == focused);
            }
        }
    }

    private boolean insideSitter(Sus s) {
        for (Sitter t : sitters) if (Math.max(Math.abs(s.cx - t.cx()), Math.abs(s.cz - t.cz())) <= sittersFor) return true;
        return false;
    }

    /** The server and the dimension: marks from one world don't show in another. */
    private String where() {
        String server;
        if (mc.getCurrentServer() != null) server = mc.getCurrentServer().ip;
        // By its save folder: two worlds can share a name, never a folder.
        else if (mc.getSingleplayerServer() != null) {
            server = "singleplayer:" + mc.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize().getFileName();
        }
        else server = "unknown";
        return server + "|" + (mc.level == null ? "" : mc.level.dimension().identifier().toString());
    }
}
