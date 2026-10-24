package com.cyanite.client.modules.world;

import com.cyanite.client.modules.combat.AntiBot;
import com.cyanite.client.modules.donut.DrillCut;
import com.cyanite.client.modules.player.AutoEat;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.HumanAim;
import com.cyanite.client.system.Keys;
import com.cyanite.client.system.Targets;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.Notify;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import com.cyanite.client.modules.donut.SpawnerFinder;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Digs a straight tunnel in the direction you're facing. It mines by holding attack through
 * vanilla (MinecraftMixin) while HumanAim walks the crosshair onto each block, so it only ever
 * breaks what the crosshair is really on. It stops and tells you about lava, drops and the rest
 * before it digs into them.
 *
 * <p>It keeps going while you're tabbed out: GameRendererMixin stops the game opening the pause
 * menu when the window loses focus, as long as Auto Mine is on.
 *
 * <p>Drill mode is for a tool that breaks a square around the block you hit (3x3 by default), on
 * the face you hit. It hits one block per slice, high enough that the square's bottom row is the
 * floor you walk on. For 3x3 that's the block at eye level, dead ahead every time, so it just
 * looks straight down the tunnel and holds still while it mines and walks.
 *
 * <p>It checks what the drill really cut, like Area Miner: the first hits that start with
 * everything still are watched, it tells you the shape, and if the drill cuts a different square
 * from the Drill size setting it fixes the setting (or switches to strip mining for a tool that
 * only breaks one block). It never swings the drill unless the game says you're facing straight
 * down the tunnel, since the drill lays its square across the way you face. It keeps going with a
 * menu open, stops for any hostile mob in sight, and draws the tunnel ahead.
 *
 * <p>Lava ahead doesn't stop it unless Go round obstacles is off. It turns off sideways just far enough
 * that the tunnel on from there stays a block clear of the lava, carries on the way it was going,
 * and once it's past, cuts back onto the line it started on. Every stretch gets the same lava check
 * before it's dug, so it never opens up a block next to lava. Bedrock, a block that won't break,
 * and a hole too big to fill or step down get gone round the same way.
 *
 * <p>Lava under the floor is no danger (lava never flows up), so a lava pit is just a hole: it's
 * filled from the bottom up when the bottom's in reach, and otherwise bridged, the way a player
 * does it: facing back, crouched at the very edge, a block placed against the front of the one
 * you stand on. It never steps down into lava.
 *
 * <p>Left running, it looks after itself. It fights hostile mobs that come at it (best weapon in
 * the hotbar, backing off from a creeper that's about to go), eats when it gets hungry, mends the
 * drill with Bottles o' Enchanting thrown at its feet from the off hand (or swaps in a spare when
 * there are none), and fills or bridges any hole in the floor. A full inventory doesn't stop it:
 * it digs on and what it breaks stays on the floor (Inventory full: Keep mining). Set to Drop junk,
 * it turns round and throws the junk (cobblestone, deepslate, tuff, gravel and the like) back down
 * the tunnel instead, keeping one stack of it for holes. If health gets low or another player
 * comes close, it leaves the server.
 */
public class AutoMine extends Module {
    private static final long STUCK_NANOS = 20_000_000_000L;
    private static final long BLOCKED_NANOS = 400_000_000L;
    private static final int TOOL_RESERVE = 10;
    private static final long DRILL_GRACE_NANOS = 700_000_000L;
    /** A hit is only watched if nothing else came out for this long before it started. */
    private static final long QUIET = 1000;
    private static final long CUT_SETTLE = 400;
    private static final long CUT_LONGEST = 2500;
    /** How many quiet hits to watch before trusting the drill's shape. */
    private static final int WATCH_HITS = 3;
    private static final int ROUTE_COLOR = 0xFFFF5D4A;
    /** Thrown out when the inventory fills: what a tunnel brings in by the stack. */
    private static final Set<Item> JUNK = Set.of(Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.STONE, Items.DEEPSLATE, Items.TUFF,
        Items.GRANITE, Items.DIORITE, Items.ANDESITE, Items.CALCITE, Items.DIRT, Items.COARSE_DIRT, Items.GRAVEL, Items.FLINT,
        Items.SAND, Items.RED_SAND, Items.SANDSTONE, Items.NETHERRACK, Items.BLACKSTONE, Items.BASALT, Items.SMOOTH_BASALT,
        Items.DRIPSTONE_BLOCK, Items.POINTED_DRIPSTONE);
    /** Junk that makes a good floor: full blocks that stay where they're put. */
    private static final Set<Item> FILL = Set.of(Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.STONE, Items.DEEPSLATE, Items.TUFF,
        Items.GRANITE, Items.DIORITE, Items.ANDESITE, Items.CALCITE, Items.DIRT, Items.NETHERRACK, Items.BLACKSTONE, Items.BASALT,
        Items.SMOOTH_BASALT, Items.DRIPSTONE_BLOCK);
    /** Never eaten: bad for you, or too good to waste on hunger. */
    private static final Set<Item> BAD_FOOD = Set.of(Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO, Items.PUFFERFISH,
        Items.CHORUS_FRUIT, Items.SUSPICIOUS_STEW, Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE);
    /** A mob that stays out of reach this long (a skeleton across a cave) gets left alone. */
    private static final long FIGHT_GIVE_UP = 8000;
    private static final long FILL_GIVE_UP = 4000;
    /** The longest gap it'll build a bridge across. */
    private static final int MAX_BRIDGE = 64;
    /** Bottles thrown in a row with the drill no better for it: it isn't taking. */
    private static final int REPAIR_DUD = 12;
    private static final int OFFHAND_BUTTON = 40;
    /** Eats until the hunger bar's this full. */
    private static final int FED = 18;

    /** The hotbar slot the drill was in when this started. */
    private int drillSlot = -1;
    private final ModeSetting mode = add(new ModeSetting("Mode", "Strip mine", List.of("Strip mine", "Drill")));
    private final SliderSetting drillSize = add(new SliderSetting("Drill size", 3, 3, 15, 2).format(v -> (int) v + "x" + (int) v));
    private final SliderSetting length = add(new SliderSetting("Length", 0, 0, 500, 10).format(v -> v == 0 ? "Endless" : (int) v + " blocks"));
    private final SliderSetting turnSpeed = add(new SliderSetting("Turn speed", 1, 0.3, 3, 0.1).format(v -> String.format(Locale.ROOT, "%.1fx", v)));
    private final SliderSetting randomness = add(new SliderSetting("Randomness", 0.5, 0, 1, 0.05).percent());
    private final SliderSetting lavaRange = add(new SliderSetting("Lava check", 3, 1, 6, 1).format(v -> (int) v + " ahead"));
    private final BoolSetting goAround = add(new BoolSetting("Go round obstacles", true));
    private final SliderSetting maxDetour = add(new SliderSetting("Max detour", 16, 4, 32, 1).format(v -> (int) v + " blocks"));
    private final BoolSetting stopWater = add(new BoolSetting("Stop at water", false));
    private final BoolSetting fillHoles = add(new BoolSetting("Fill holes", true));
    private final BoolSetting stopDrops = add(new BoolSetting("Stop at drops", true));
    private final ModeSetting whenFull = add(new ModeSetting("Inventory full", "Keep mining", List.of("Keep mining", "Drop junk", "Stop")));
    private final BoolSetting saveTool = add(new BoolSetting("Save tool", true));
    private final BoolSetting repairXp = add(new BoolSetting("Repair with XP", true));
    private final SliderSetting repairAt = add(new SliderSetting("Repair at", 0.2, 0.05, 0.9, 0.05).percent());
    private final BoolSetting eat = add(new BoolSetting("Eat", true));
    private final SliderSetting eatAt = add(new SliderSetting("Eat at", 14, 1, 19, 1).format(v -> "hunger " + (int) v));
    private final ModeSetting mobs = add(new ModeSetting("Mobs", "Fight", List.of("Fight", "Stop", "Ignore")));
    private final SliderSetting mobRange = add(new SliderSetting("Mob range", 10, 4, 32, 1).format(v -> (int) v + "m"));
    private final BoolSetting stopHurt = add(new BoolSetting("Stop on damage", false));
    private final SliderSetting leaveHealth = add(new SliderSetting("Leave at health", 6, 0, 19, 1).format(v -> v == 0 ? "Off" : (int) v + " HP"));
    private final SliderSetting leavePlayers = add(new SliderSetting("Leave near players", 32, 0, 128, 4).format(v -> v == 0 ? "Off" : (int) v + "m"));
    private final BoolSetting stopSkeletons = add(new BoolSetting("Stop near skeletons", false));
    private final SliderSetting skeletonRange = add(new SliderSetting("Skeleton range", 16, 4, 48, 2).format(v -> (int) v + "m"));
    private final BoolSetting stopSpawners = add(new BoolSetting("Stop at skeleton spawners", true));
    private final SliderSetting spawnerRange = add(new SliderSetting("Spawner range", 32, 8, 96, 4).format(v -> (int) v + "m"));
    private final BoolSetting showPath = add(new BoolSetting("Show path", true));
    private final BoolSetting toast = add(new BoolSetting("Toast", true));
    private final BoolSetting sound = add(new BoolSetting("Sound", true));
    private final BoolSetting flash = add(new BoolSetting("Flash taskbar", true));

    private final HumanAim aim = new HumanAim();
    private final Random random = new Random();

    private LocalPlayer owner;
    /** The way the tunnel goes overall. */
    private Direction heading;
    /** The way the stretch being dug goes: the heading, or sideways while going round lava. */
    private Direction axis;
    private BlockPos start;
    /** A block on the centre line of the stretch being dug. */
    private BlockPos line;
    /** How long a sideways stretch is before it turns back onto the heading. */
    private int legLength;
    private BlockPos target;
    private long targetSince;
    private long blockedSince;
    private boolean lookingAhead;
    private boolean lookingStraight;
    private int straightY;
    private long offLineSince;
    private boolean targetCleared;
    private Input steering = Input.EMPTY;
    private final java.util.Set<BlockPos> reportedSpawners = new java.util.HashSet<>();
    private float lastHealth;

    // Watching what the drill cuts.
    private BlockPos beforeAt;
    private boolean[] before;
    private long lastBreakAt;
    private BlockPos watching;
    private boolean[] watchBefore;
    private Direction watchAxis;
    private long watchSince;
    private long watchChange;
    private int watchCount;
    private int watched;
    private boolean toldShape;
    private long startedAt;
    private int advancedNow;

    // Looking after itself.
    /** Doing something other than mining (eating, fighting, throwing out junk, filling a hole). */
    private boolean busy;
    private String doing;
    private int eatSlot = -1;
    private boolean toldNoFood;
    private boolean toldFull;
    private boolean dumping;
    private long dumpNext;
    private Entity fighting;
    private long fightSince;
    private final Set<Integer> ignoredMobs = new HashSet<>();
    private BlockPos filling;
    private long fillSince;
    private final Set<BlockPos> unfillable = new HashSet<>();
    /** Blocks that wouldn't break: gone round like bedrock. */
    private final Set<BlockPos> avoid = new HashSet<>();
    private BlockPos fillFace;
    private boolean bridging;
    /** Where it's looking while bridging: 0 not set, 1 down past its feet, 2 at the edge's face. */
    private int bridgeAim;
    private int bridged;
    private boolean repairing;
    /** Where whatever the off hand held went while it holds the bottles, to swap it back after. */
    private int offhandWas = -1;
    private long repairNext;
    private int repairDamage;
    private int idleThrows;
    private long noRepairUntil;

    public AutoMine() {
        super(Category.WORLD, "Auto Mine", "Digs a tunnel on its own: goes round lava, fights, eats and makes room");
    }

    @Override
    public String getInfo() {
        String kind = mode.is("Drill") ? "Drill" : "Strip";
        if (axis == null) return kind;
        String at = kind + " " + (length.get() > 0 ? advancedNow + "/" + length.get().intValue() : advancedNow + "m");
        return doing != null ? at + " - " + doing : at;
    }

    @Override
    protected void onEnable() {
        // A profile can switch this on from the title screen; it should only ever start in a world.
        owner = inGame() ? mc.player : null;
        // Both would steer the player at once.
        AreaMiner areaMiner = Modules.get(AreaMiner.class);
        if (owner != null && areaMiner != null && areaMiner.isEnabled()) areaMiner.stop();
        BedrockBurrow burrow = Modules.get(BedrockBurrow.class);
        if (owner != null && burrow != null && burrow.isEnabled()) burrow.setEnabled(false);
        axis = null;
        target = null;
        lookingAhead = false;
        lookingStraight = false;
        drillSlot = owner != null ? owner.getInventory().getSelectedSlot() : -1;
        beforeAt = null;
        before = null;
        watching = null;
        watched = 0;
        toldShape = false;
        lastBreakAt = 0;
        advancedNow = 0;
        startedAt = System.currentTimeMillis();
        busy = false;
        doing = null;
        eatSlot = -1;
        toldNoFood = false;
        toldFull = false;
        dumping = false;
        fighting = null;
        ignoredMobs.clear();
        filling = null;
        unfillable.clear();
        avoid.clear();
        fillFace = null;
        bridging = false;
        bridged = 0;
        repairing = false;
        offhandWas = -1;
        noRepairUntil = 0;
    }

    @Override
    protected void onDisable() {
        if (eatSlot >= 0 && inGame()) stopEating(mc.player);
        if (repairing && inGame()) endRepair(mc.player);
        repairing = false;
        bridging = false;
        eatSlot = -1;
        doing = null;
        if (owner != null && owner == mc.player && drillSlot >= 0) owner.getInventory().setSelectedSlot(drillSlot);
        target = null;
        aim.clear();
        steering = Input.EMPTY;
    }

    /**
     * How Auto Mine wants the player to move this tick, or null when it isn't driving. Given to the
     * player straight from KeyboardInputMixin rather than by holding movement keys, so your own keys
     * stay free: with Freecam on they fly the camera while your player keeps mining.
     */
    public Input steering() {
        return isEnabled() && owner != null && owner == mc.player ? steering : null;
    }

    @Override
    public void onFrame() {
        if (mc.isPaused()) {
            aim.idle();
            return;
        }
        aim.speed = turnSpeed.get();
        // Locked straight ahead means locked: no tremor either.
        aim.randomness = lookingStraight ? 0 : randomness.get();
        aim.frame();
    }

    /** Whether vanilla should treat attack as held this tick: only with the crosshair on the planned block. */
    public boolean shouldAttack() {
        return mc.screen == null && wantsToMine();
    }

    /** The crosshair's on the planned block, with the right tool, facing the right way. */
    private boolean wantsToMine() {
        if (!isEnabled() || target == null || mc.player == null) return false;
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return false;
        if (!hit.getBlockPos().equals(target)) return false;
        // Not until the right tool's in hand: the drill for the face, a plain one for a block at your feet.
        int slot = slotFor(target);
        if (slot >= 0 && mc.player.getInventory().getSelectedSlot() != slot) return false;
        // The drill lays its square across the way you face: only ever straight down the tunnel.
        if (drill() && mc.player.getInventory().getSelectedSlot() == drillSlot && mc.player.getDirection() != axis) return false;
        // The drill cuts its square on the face you hit, so it has to be the right one.
        return !drill() || hit.getDirection() == faceFor(target);
    }

    @Override
    public void onTick() {
        LocalPlayer player = mc.player;
        if (owner == null) {
            setEnabled(false);
            return;
        }
        if (owner != player) {
            stop("Stopped", "You died or changed worlds");
            return;
        }
        if (axis == null) {
            axis = player.getDirection();
            heading = axis;
            start = player.blockPosition();
            line = start;
            legLength = 0;
            lastHealth = player.getHealth();
        }
        // Only a paused singleplayer world stops it; with any other menu open it carries on.
        if (mc.isPaused()) {
            steering = Input.EMPTY;
            return;
        }

        if (leave(player)) return;
        String hazard = hazard(player);
        if (hazard != null) {
            stop("Stopped", hazard);
            return;
        }
        if (lookAfter(player)) return;

        BlockPos base = base(player);
        // The end of a sideways stretch round lava: on along the heading from here.
        if (sideways() && legLeft(base) <= 0) {
            turn(base, heading, 0);
            base = base(player);
        }
        int advanced = (base.getX() - start.getX()) * heading.getStepX() + (base.getZ() - start.getZ()) * heading.getStepZ();
        advancedNow = Math.max(0, advanced);
        if (length.get() > 0 && advanced >= length.get()) {
            double seconds = Math.max(1, (System.currentTimeMillis() - startedAt) / 1000.0);
            stop("Finished", String.format(Locale.ROOT, "Dug %d blocks in %s (%.1f a second)", advanced, time(seconds), advanced / seconds));
            return;
        }

        BlockPos lava = lavaAhead(base);
        if (lava != null) {
            if (!goAround.get()) {
                stop("Stopped", "Lava " + ahead(base, lava) + " at " + coords(lava));
                return;
            }
            if (!detour(base, lava, "Lava")) {
                stop("Stopped", "Lava " + ahead(base, lava) + " at " + coords(lava) + ", with no way round within " + maxDetour.get().intValue() + " blocks");
                return;
            }
            base = base(player);
        } else if (goAround.get() && !sideways() && offset(base) != 0) {
            // Past it: back onto the line you started on once the way there is clear.
            int offset = offset(base);
            Direction back = offset > 0 ? heading.getCounterClockWise() : heading.getClockWise();
            if (clearWay(base, back, Math.abs(offset))) {
                turn(base, back, Math.abs(offset));
                base = base(player);
            }
        }

        // A hole just filled (or left behind): back to the drill.
        if (filling != null && (solid(filling) || !filling.equals(at(base, 1, 0, -1)))) {
            filling = null;
            backToWork(player);
        }

        BlockPos next = null;
        boolean walk = false;
        List<BlockPos> first = needed(base, 1);
        if (!first.isEmpty() && bridging) endBridge(player);
        if (bridging && bridge(player, base)) return;
        if (!first.isEmpty()) {
            next = pick(first);
        } else if (solid(at(base, 1, 0, -1))) {
            walk = true;
            // Nothing past the end of a sideways stretch: the turn comes first.
            if (!sideways() || legLeft(base) >= 2) next = pick(needed(base, 2));
        } else if (fillHoles.get() && fill(player, at(base, 1, 0, -1))) {
            return;
        } else if (fillHoles.get() && bridge(player, base)) {
            return;
        } else if (safeDrop(base)) {
            // Three blocks or less does no harm: step down and carry on from there. From a cramped
            // start it's the drill's own square, cut a few blocks below your feet.
            walk = true;
        } else if (goAround.get() && detour(base, at(base, 1, 0, -1), "A drop")) {
            return;
        } else if (stopDrops.get()) {
            stop("Stopped", "Drop ahead at " + coords(at(base, 1, 0, -1)) + (bridged >= MAX_BRIDGE ? ", too wide to bridge" : ""));
            return;
        } else {
            walk = true;
        }

        // The block you hit breaks on your screen at once, but the rest of the drill's square only
        // vanishes when the server says so a moment later. Give it that moment rather than glancing
        // down at a block that's about to go anyway; only a real leftover gets aimed at.
        if (straight() && next != null && next.getY() != base.getY() + 1) {
            long now = System.nanoTime();
            if (offLineSince == 0) offLineSince = now;
            if (now - offLineSince < DRILL_GRACE_NANOS) next = null;
        } else {
            offLineSince = 0;
        }

        if (next != null && mc.level.getBlockState(next).getDestroySpeed(mc.level, next) < 0) {
            if (goAround.get() && detour(base, next, mc.level.getBlockState(next).getBlock().getName().getString())) return;
            stop("Stopped", "Unbreakable block at " + coords(next));
            return;
        }

        // Gravel and sand: never walk under them while they're still coming down. Wait for them to
        // land, mine them again, and repeat until the column above is empty.
        if (fallingAhead(base)) walk = false;
        boolean backOff = fallingOverhead(player);

        if (target != null && mc.level.getBlockState(target).isAir()) targetCleared = true;
        watchCut(player);
        retarget(player, base, next);
        int slot = target == null ? -1 : slotFor(target);
        if (slot >= 0 && player.getInventory().getSelectedSlot() != slot) player.getInventory().setSelectedSlot(slot);
        if (unstick(player)) return;
        move(player, walk && !backOff, backOff);
        // With a menu open the game won't hold attack for us, so mine the way it would have.
        if (mc.screen != null && wantsToMine() && mc.hitResult instanceof BlockHitResult hit
            && mc.gameMode.continueDestroyBlock(hit.getBlockPos(), hit.getDirection())) {
            player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        }
    }

    /**
     * Checks what the drill really cut, from the first few hits that started with everything
     * still: a hit straight after another one might pick up the last cut still landing. The block
     * hit goes at once, the rest when the server says so, so it waits for the cube round it to
     * go quiet before comparing.
     */
    private void watchCut(LocalPlayer player) {
        if (!drill()) return;
        long now = System.currentTimeMillis();
        if (watching != null) {
            boolean[] after = com.cyanite.client.modules.donut.DrillCut.snapshot(mc.level, watching);
            int count = com.cyanite.client.modules.donut.DrillCut.count(after);
            if (count != watchCount) {
                watchCount = count;
                watchChange = now;
            }
            if (now - watchChange > CUT_SETTLE || now - watchSince > CUT_LONGEST) {
                learn(new DrillCut(watchAxis, watchBefore, after));
                watching = null;
            }
        }
        // The block being hit is gone: that's a cut to watch, if it started quiet.
        if (beforeAt != null && mc.level.getBlockState(beforeAt).isAir()) {
            if (before != null && watching == null && watched < WATCH_HITS) {
                watching = beforeAt;
                watchBefore = before;
                watchAxis = axis;
                watchSince = now;
                watchChange = now;
                watchCount = com.cyanite.client.modules.donut.DrillCut.count(com.cyanite.client.modules.donut.DrillCut.snapshot(mc.level, watching));
            }
            lastBreakAt = now;
            beforeAt = null;
            before = null;
        }
        // Keep a picture of round the block being hit, up to the moment it goes.
        if (target != null && player.getInventory().getSelectedSlot() == drillSlot && !mc.level.getBlockState(target).isAir()) {
            if (!target.equals(beforeAt)) beforeAt = target.immutable();
            before = now - lastBreakAt >= QUIET ? com.cyanite.client.modules.donut.DrillCut.snapshot(mc.level, beforeAt) : null;
        }
    }

    /** What one watched hit says about the drill, and what to do about it. */
    private void learn(com.cyanite.client.modules.donut.DrillCut cut) {
        watched++;
        int across = cut.rMax - cut.rMin + 1;
        int tall = cut.uMax - cut.uMin + 1;
        if (cut.size() <= 1) {
            // Only the block itself: a plain tool. Drill mode would leave a 1-wide shaft.
            mode.set("Strip mine");
            Notify.send("Auto Mine", "That tool only breaks one block, so it's strip mining instead", false, false, false);
            return;
        }
        if (!toldShape) {
            toldShape = true;
            Notify.send("Auto Mine", "Your drill cuts " + across + "x" + tall + " into a wall", false, false, false);
        }
        // A square of another size: dig the tunnel that size.
        if (across == tall && across % 2 == 1 && across >= 3 && across != drillSize.get().intValue()) {
            drillSize.set((double) across);
            lookingStraight = false;
            Notify.send("Auto Mine", "Drill size set to " + across + "x" + across + " to match what it cuts", false, false, false);
        }
    }

    private static String time(double seconds) {
        long s = Math.round(seconds);
        return s < 60 ? s + "s" : s / 60 + "m " + s % 60 + "s";
    }

    /** Anything falling into the next couple of slices of the tunnel, from however high up. */
    private boolean fallingAhead(BlockPos base) {
        int half = drill() ? half() : 0;
        int top = drill() ? half * 2 : 1;
        BlockPos a = at(base, 0, -half, 0);
        BlockPos b = at(base, 2, half, top + 24);
        return !mc.level.getEntitiesOfClass(FallingBlockEntity.class, new AABB(a).minmax(new AABB(b))).isEmpty();
    }

    /** Something falling straight down at you: step back into the dug tunnel until it lands. */
    private boolean fallingOverhead(LocalPlayer player) {
        AABB column = player.getBoundingBox().inflate(0.15, 0, 0.15).expandTowards(0, 24, 0);
        for (FallingBlockEntity falling : mc.level.getEntitiesOfClass(FallingBlockEntity.class, column)) {
            if (falling.getY() > player.getY() + 1) return true;
        }
        return false;
    }

    private void retarget(LocalPlayer player, BlockPos base, BlockPos next) {
        long now = System.nanoTime();
        // 3x3 drill: every hit is the eye-level block straight ahead, which a level look down the
        // tunnel is already on. Turn to that once and stay there.
        if (straight() && (next == null || next.getY() == base.getY() + 1)) {
            if (next != null && (!next.equals(target) || targetCleared)) {
                targetSince = now;
                blockedSince = 0;
                targetCleared = false;
            }
            target = next;
            lookingAhead = false;
            if (!lookingStraight || straightY != base.getY()) {
                Vec3 far = Vec3.atBottomCenterOf(at(base, 10_000, 0, 0)).add(0, player.getEyeHeight(), 0);
                aim.setTarget(far);
                lookingStraight = true;
                straightY = base.getY();
            }
            return;
        }
        lookingStraight = false;
        if (next == null) {
            target = null;
            if (!lookingAhead) {
                // Nothing to dig yet: look on down the tunnel, the way you would while walking.
                Vec3 ahead = Vec3.atBottomCenterOf(at(base, 12, 0, 0)).add(0, player.getEyeHeight(), 0);
                aim.setTarget(ahead);
                lookingAhead = true;
            }
            return;
        }
        // The same spot again only counts as new once it was emptied in between: gravel refilling it.
        if (next.equals(target) && !targetCleared) return;
        targetCleared = false;
        target = next;
        targetSince = now;
        blockedSince = 0;
        lookingAhead = false;
        aim.setTarget(facePoint(next));
    }

    /** Handles the crosshair landing on something else, and gives up on a block that never breaks. */
    private boolean unstick(LocalPlayer player) {
        if (target == null) return false;
        long now = System.nanoTime();

        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
            && !hit.getBlockPos().equals(target) && aim.error() < 2) {
            if (blockedSince == 0) blockedSince = now;
            // Something we'd dig anyway is in the way (gravel that fell, say): take it first.
            if (now - blockedSince > BLOCKED_NANOS && planned(hit.getBlockPos())) {
                target = hit.getBlockPos();
                targetSince = now;
                blockedSince = 0;
            }
        } else {
            blockedSince = 0;
        }

        if (now - targetSince > STUCK_NANOS) {
            BlockPos stuck = target.immutable();
            String name = mc.level.getBlockState(stuck).getBlock().getName().getString();
            avoid.add(stuck);
            if (goAround.get() && detour(base(player), stuck, name + " that won't break,")) return true;
            stop("Stopped", "Couldn't break " + name + " at " + coords(stuck));
            return true;
        }
        return false;
    }

    private void move(LocalPlayer player, boolean walk, boolean backOff) {
        // Not until you face the way this stretch goes: just after a turn you'd walk on the old way.
        if (Math.abs(Mth.wrapDegrees(player.getYRot() - axis.toYRot())) > 45) walk = false;
        boolean[] drift = walk ? drift(player) : new boolean[2];
        steering = new Input(walk, backOff, drift[0], drift[1], false, false, false);
    }

    /** Left and right to drift back toward the middle of the tunnel; nothing holds you in a drill-wide one. */
    private boolean[] drift(LocalPlayer player) {
        Direction side = axis.getClockWise();
        double off = (player.getX() - (line.getX() + 0.5)) * side.getStepX() + (player.getZ() - (line.getZ() + 0.5)) * side.getStepZ();
        if (Math.abs(off) <= 0.15) return new boolean[2];
        double yaw = Math.toRadians(player.getYRot());
        // Which way "left" points right now, and whether that's toward the middle.
        double toward = -Math.signum(off) * (Math.cos(yaw) * side.getStepX() + Math.sin(yaw) * side.getStepZ());
        return new boolean[] {toward > 0.4, toward < -0.4};
    }

    /** Whether there's solid ground no more than three blocks under the next slice: a drop that does no damage. */
    private boolean safeDrop(BlockPos base) {
        for (int down = 1; down <= 4; down++) {
            BlockPos pos = at(base, 1, 0, -down);
            // Never down into lava, or water hiding what's under it.
            if (!mc.level.getFluidState(pos).isEmpty()) return false;
            if (solid(pos)) return true;
        }
        return false;
    }

    private String hazard(LocalPlayer player) {
        float health = player.getHealth();
        boolean hurt = health < lastHealth;
        lastHealth = health;
        if (stopHurt.get() && hurt) return "You took damage";
        if (whenFull.is("Stop") && player.getInventory().getFreeSlot() < 0) return "Inventory full";

        String skeleton = skeletons(player);
        if (skeleton != null) return skeleton;

        if (mobs.is("Stop")) {
            Entity mob = threat(player);
            if (mob != null) return mob.getName().getString() + " " + Math.round(mob.distanceTo(player)) + "m away";
        }

        if (stopWater.get()) {
            BlockPos base = base(player);
            BlockPos water = fluidIn(base, axis, 0, lavaRange.get().intValue(), FluidTags.WATER);
            if (water != null) return "Water " + ahead(base, water) + " at " + coords(water);
        }
        return null;
    }

    /** Lava near anything this stretch is about to open up, or null. */
    private BlockPos lavaAhead(BlockPos base) {
        int range = lavaRange.get().intValue();
        // A sideways stretch only goes so far: what's past its end is the next stretch's to check.
        if (sideways()) return fluidIn(base, axis, 1, Math.min(range, legLeft(base) + reach()), FluidTags.LAVA);
        return fluidIn(base, axis, 0, range, FluidTags.LAVA);
    }

    /**
     * The nearest {@code fluid} in slices {@code from} to {@code to} of a tunnel going {@code dir}
     * from {@code base}, taken one block wider all round than the tunnel so what's behind a wall
     * counts too.
     */
    private BlockPos fluidIn(BlockPos base, Direction dir, int from, int to, TagKey<Fluid> fluid) {
        int half = drill() ? half() : 0;
        // Nothing below the lowest row it digs: lava never flows up. A hole down into it is a hole.
        int bottom = drill() ? Math.min(0, 1 - half) : 0;
        int top = drill() ? half * 2 + 1 : 2;
        Direction side = dir.getClockWise();
        for (int k = from; k <= to; k++) {
            for (int l = -half - 1; l <= half + 1; l++) {
                for (int y = bottom; y <= top; y++) {
                    BlockPos pos = base.relative(dir, k).relative(side, l).above(y);
                    FluidState state = mc.level.getFluidState(pos);
                    if (state.is(fluid)) return pos;
                }
            }
        }
        return null;
    }

    /**
     * Finds the shortest way round lava ahead: a sideways stretch, then on along the heading from
     * its end. Both get the check the lava check will give them once they're being dug, so a way
     * found here is never turned back from straight away. Partway along a sideways stretch, going
     * on along the heading from where you are counts too.
     * @return false when there's no way round within Max detour
     */
    private boolean detour(BlockPos base, BlockPos lava, String what) {
        int range = lavaRange.get().intValue();
        if (sideways() && fluidIn(base, heading, 0, range, FluidTags.LAVA) == null && passable(base, heading, 1, range)) {
            turn(base, heading, 0);
            return true;
        }
        Direction right = heading.getClockWise();
        int offset = offset(base);
        int lavaSide = (lava.getX() - base.getX()) * right.getStepX() + (lava.getZ() - base.getZ()) * right.getStepZ();
        // Back toward the line you started on when that's as short, otherwise away from the lava.
        boolean leftFirst = offset != 0 ? offset > 0 : lavaSide > 0;
        Direction[] sides = leftFirst ? new Direction[] {right.getOpposite(), right} : new Direction[] {right, right.getOpposite()};
        for (int s = 1; s <= maxDetour.get().intValue(); s++) {
            for (Direction side : sides) {
                if (!clearWay(base, side, s)) continue;
                Notify.send("Auto Mine", what + " " + ahead(base, lava) + " at " + coords(lava) + ", going round it "
                    + s + (s == 1 ? " block" : " blocks") + " to the " + (side == right ? "right" : "left"), false, false, false);
                turn(base, side, s);
                return true;
            }
        }
        return false;
    }

    /**
     * Whether going {@code s} blocks {@code side} from {@code base}, then on along the heading, keeps
     * clear of lava, has floor to walk on, and has nothing in the way that won't break.
     */
    private boolean clearWay(BlockPos base, Direction side, int s) {
        int range = lavaRange.get().intValue();
        BlockPos corner = base.relative(side, s);
        return fluidIn(base, side, 1, s + reach(), FluidTags.LAVA) == null && fluidIn(corner, heading, 0, range, FluidTags.LAVA) == null
            && passable(base, side, 1, s) && passable(corner, heading, 1, range);
    }

    /** Slices {@code from} to {@code to} going {@code dir}: floor under the middle, and nothing that can't be dug. */
    private boolean passable(BlockPos base, Direction dir, int from, int to) {
        int half = drill() ? half() : 0;
        int top = drill() ? half * 2 : 1;
        Direction side = dir.getClockWise();
        for (int k = from; k <= to; k++) {
            BlockPos middle = base.relative(dir, k);
            if (!solid(middle.below())) return false;
            for (int l = -half; l <= half; l++) {
                for (int y = 0; y <= top; y++) {
                    BlockPos pos = middle.relative(side, l).above(y);
                    if (avoid.contains(pos) || mc.level.getBlockState(pos).getDestroySpeed(mc.level, pos) < 0) return false;
                }
            }
        }
        return true;
    }

    /** Low on health, or another player close: out of the server before anything happens. */
    private boolean leave(LocalPlayer player) {
        String why = null;
        float hp = player.getHealth() + player.getAbsorptionAmount();
        if (leaveHealth.get() > 0 && hp <= leaveHealth.get()) why = String.format(Locale.ROOT, "Health dropped to %.1f", hp);
        double range = leavePlayers.get();
        if (why == null && range > 0) {
            for (Player other : mc.level.players()) {
                if (other == player || other.isSpectator() || AntiBot.isBot(other)) continue;
                double distance = other.distanceTo(player);
                if (distance <= range) {
                    why = other.getName().getString() + " came within " + Math.round(distance) + "m";
                    break;
                }
            }
        }
        if (why == null) return false;
        // Leaving singleplayer this way would strand the world mid-save: just stop there.
        if (mc.isLocalServer() || mc.getConnection() == null) {
            stop("Stopped", why);
            return true;
        }
        Notify.send("Auto Mine left", why, toast.get(), sound.get(), flash.get());
        setEnabled(false);
        mc.getConnection().getConnection().disconnect(com.cyanite.client.system.Stealth.disconnectReason("Cyanite Auto Mine\n\n" + why));
        return true;
    }

    /**
     * Everything that keeps it going on its own: fighting what comes at it, eating, swapping in a
     * spare tool, and throwing out junk when the inventory fills.
     * @return true while one of those has it busy, so there's no mining this tick
     */
    private boolean lookAfter(LocalPlayer player) {
        String now = null;
        if (mobs.is("Fight") && fight(player)) {
            if (eatSlot >= 0) stopEating(player);
            if (repairing) endRepair(player);
            dumping = false;
            now = "fighting";
        } else if (eat(player)) {
            now = "eating";
        } else if (repair(player)) {
            now = "repairing";
        } else if (wornOut(player)) {
            return true;
        } else if (dump(player)) {
            now = "dropping junk";
        }
        doing = now;
        if (now != null) {
            busy = true;
            target = null;
            filling = null;
            bridgeAim = 0;
            offLineSince = 0;
            return true;
        }
        if (busy && filling == null && !bridging) backToWork(player);
        return false;
    }

    /** Done with whatever it was: the drill back in hand and the eyes back on the tunnel. */
    private void backToWork(LocalPlayer player) {
        busy = false;
        doing = null;
        if (drillSlot >= 0) player.getInventory().setSelectedSlot(drillSlot);
        lookingStraight = false;
        lookingAhead = false;
        target = null;
        targetSince = System.nanoTime();
        blockedSince = 0;
    }

    /** A hostile in sight: turn on it and hit it once it's in reach, with the best weapon in the hotbar. */
    private boolean fight(LocalPlayer player) {
        Entity mob = threat(player);
        if (mob == null) {
            fighting = null;
            return false;
        }
        long now = System.currentTimeMillis();
        Vec3 point = Targets.chest(mob, 1);
        if (mob != fighting) {
            fighting = mob;
            fightSince = now;
            lookingStraight = false;
            aim.setTarget(point);
        } else {
            aim.track(point);
        }
        boolean inReach = Targets.reach(mob) <= player.entityInteractionRange() - 0.2;
        if (inReach) {
            fightSince = now;
        } else if (now - fightSince > FIGHT_GIVE_UP) {
            // It keeps its distance (a skeleton across a cave, say): leave it be.
            ignoredMobs.add(mob.getId());
            fighting = null;
            return false;
        }
        // A creeper about to go off: back away down the tunnel, hitting it if it follows.
        boolean back = mob instanceof Creeper creeper && creeper.getSwelling(1) > 0 && mob.distanceTo(player) < 5;
        steering = new Input(false, back, false, false, false, false, false);
        int weapon = weapon(player);
        if (weapon >= 0 && player.getInventory().getSelectedSlot() != weapon) {
            // The game reads a weapon's damage a tick after you pick it up: swing from next tick.
            player.getInventory().setSelectedSlot(weapon);
            return true;
        }
        if (inReach && mc.crosshairPickEntity == mob && player.getAttackStrengthScale(0.5f) >= 0.95f) {
            mc.gameMode.attack(player, mob);
            player.swing(InteractionHand.MAIN_HAND);
        }
        return true;
    }

    /** The nearest hostile mob in sight within Mob range. Endermen only once they're angry: looking at one starts it. */
    private Entity threat(LocalPlayer player) {
        double range = mobRange.get();
        Entity best = null;
        double bestDistance = range;
        for (Entity entity : mc.level.getEntities(player, player.getBoundingBox().inflate(range),
            e -> e instanceof Enemy && e instanceof LivingEntity living && living.isAlive())) {
            if (ignoredMobs.contains(entity.getId()) || entity instanceof EnderMan man && !man.isCreepy()) continue;
            double distance = entity.distanceTo(player);
            if (distance > bestDistance || !player.hasLineOfSight(entity)) continue;
            best = entity;
            bestDistance = distance;
        }
        return best;
    }

    /** The hotbar slot that hits hardest, or -1 if nothing beats a fist. */
    private int weapon(LocalPlayer player) {
        int best = -1;
        double bestDamage = 1;
        for (int slot = 0; slot < 9; slot++) {
            double damage = player.getInventory().getItem(slot).getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY)
                .compute(Attributes.ATTACK_DAMAGE, 1, EquipmentSlot.MAINHAND);
            if (damage > bestDamage) {
                best = slot;
                bestDamage = damage;
            }
        }
        return best;
    }

    /** Hungry: eats the most filling food it has, bringing some up from the inventory if the hotbar has none. */
    private boolean eat(LocalPlayer player) {
        // With Auto Eat on, that does the eating: just hold still while it does.
        AutoEat autoEat = Modules.get(AutoEat.class);
        if (autoEat != null && autoEat.isEnabled()) return autoEat.isEating();
        if (!eat.get()) return false;
        Inventory inventory = player.getInventory();
        int food = player.getFoodData().getFoodLevel();
        if (eatSlot >= 0) {
            if (food >= FED || !edible(inventory.getItem(eatSlot))) {
                stopEating(player);
                return false;
            }
            if (inventory.getSelectedSlot() != eatSlot) inventory.setSelectedSlot(eatSlot);
            steering = Input.EMPTY;
            // Held use keeps it going; starting it straight off means no clicking the block ahead.
            if (mc.screen == null) mc.options.keyUse.setDown(true);
            if (!player.isUsingItem()) mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
            return true;
        }
        if (food > eatAt.get()) {
            toldNoFood = false;
            return false;
        }
        int slot = foodSlot(player);
        if (slot < 0) {
            if (!toldNoFood) {
                toldNoFood = true;
                Notify.send("Auto Mine", "Out of food", toast.get(), sound.get(), flash.get());
            }
            return false;
        }
        eatSlot = slot;
        inventory.setSelectedSlot(slot);
        steering = Input.EMPTY;
        return true;
    }

    private void stopEating(LocalPlayer player) {
        Keys.restore(mc.options.keyUse);
        if (player.isUsingItem()) mc.gameMode.releaseUsingItem(player);
        eatSlot = -1;
    }

    /** The hotbar slot of the most filling food, the best from the inventory brought up if the hotbar has none. -1 if there's none. */
    private int foodSlot(LocalPlayer player) {
        Inventory inventory = player.getInventory();
        int best = -1;
        int bestScore = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!edible(stack)) continue;
            int score = stack.get(DataComponents.FOOD).nutrition() + (slot < 9 ? 100 : 0);
            if (score > bestScore) {
                best = slot;
                bestScore = score;
            }
        }
        return best < 9 ? best : bringUp(best);
    }

    private static boolean edible(ItemStack stack) {
        return stack.has(DataComponents.FOOD) && !BAD_FOOD.contains(stack.getItem());
    }

    /** The tool's nearly worn out: swaps in a spare from the inventory, or stops. @return true if it did either */
    private boolean wornOut(LocalPlayer player) {
        if (!saveTool.get() || drillSlot < 0) return false;
        Inventory inventory = player.getInventory();
        ItemStack tool = inventory.getItem(drillSlot);
        if (!tool.isDamageableItem() || tool.getMaxDamage() - tool.getDamageValue() > TOOL_RESERVE) return false;
        int spare = spareFor(tool);
        int menuSlot = spare < 0 ? -1 : menuSlot(spare);
        if (menuSlot < 0) {
            stop("Stopped", "Tool almost broken, and no spare");
            return true;
        }
        mc.gameMode.handleInventoryMouseClick(player.containerMenu.containerId, menuSlot, drillSlot, ClickType.SWAP, player);
        Notify.send("Auto Mine", "Tool almost broken: swapped in a spare " + inventory.getItem(drillSlot).getHoverName().getString(), false, false, false);
        // Another tool might cut differently: check it again.
        watched = 0;
        toldShape = false;
        watching = null;
        before = null;
        busy = true;
        return true;
    }

    /** The best spare for a worn tool: a drill for a drill, the same tool, or anything that mines stone at a pinch. */
    private int spareFor(ItemStack worn) {
        Inventory inventory = mc.player.getInventory();
        BlockState stone = Blocks.STONE.defaultBlockState();
        boolean wasDrill = DrillCut.isDrill(worn.getHoverName().getString());
        int best = -1;
        int bestScore = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (slot == drillSlot || stack.isEmpty() || !stack.isCorrectToolForDrops(stone)) continue;
            if (stack.isDamageableItem() && stack.getMaxDamage() - stack.getDamageValue() <= TOOL_RESERVE * 3) continue;
            int score = 1;
            if (stack.getItem() == worn.getItem()) score += 2;
            if (wasDrill && DrillCut.isDrill(stack.getHoverName().getString())) score += 4;
            if (score > bestScore) {
                best = slot;
                bestScore = score;
            }
        }
        return best;
    }

    /**
     * The inventory's full: turns round and throws the junk back down the tunnel, so walking on
     * never picks it up again. One stack of floor blocks stays, for holes. Not until there's open
     * tunnel behind, though: thrown at a wall it lands at your feet and comes straight back, so
     * until then it digs on, leaving what it breaks on the floor.
     */
    private boolean dump(LocalPlayer player) {
        Inventory inventory = player.getInventory();
        long now = System.currentTimeMillis();
        if (!dumping) {
            if (!whenFull.is("Drop junk") || inventory.getFreeSlot() >= 0) {
                toldFull = false;
                return false;
            }
            if (junk(inventory) < 0) {
                // Nothing worth throwing out: dig on, and what it breaks stays on the floor.
                if (!toldFull) {
                    toldFull = true;
                    Notify.send("Auto Mine", "Inventory full of things worth keeping: digging on, new drops stay on the floor", false, false, false);
                }
                return false;
            }
            if (!roomBehind(base(player))) return false;
            dumping = true;
            dumpNext = now + 250;
            lookingStraight = false;
            // A touch above level throws it furthest.
            aim.setTarget(player.getEyePosition().add(-axis.getStepX() * 8, 0.3, -axis.getStepZ() * 8));
        }
        steering = Input.EMPTY;
        if (now < dumpNext || aim.error() > 10) return true;
        int slot = junk(inventory);
        int menuSlot = slot < 0 ? -1 : menuSlot(slot);
        if (menuSlot < 0) {
            dumping = false;
            return false;
        }
        mc.gameMode.handleInventoryMouseClick(player.containerMenu.containerId, menuSlot, 1, ClickType.THROW, player);
        dumpNext = now + 120 + random.nextInt(180);
        return true;
    }

    /** A few blocks of open tunnel behind, for junk to land in well away from you. */
    private boolean roomBehind(BlockPos base) {
        for (int k = 1; k <= 3; k++) {
            if (solid(at(base, -k, 0, 0)) || solid(at(base, -k, 0, 1))) return false;
        }
        return true;
    }

    /** A junk stack to throw out, or -1. The biggest stack of floor blocks is kept back for holes. */
    private int junk(Inventory inventory) {
        int keep = -1;
        if (fillHoles.get()) {
            for (int slot = 0; slot < 36; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (slot != drillSlot && FILL.contains(stack.getItem()) && (keep < 0 || stack.getCount() > inventory.getItem(keep).getCount())) keep = slot;
            }
        }
        for (int slot = 0; slot < 36; slot++) {
            if (slot != keep && slot != drillSlot && JUNK.contains(inventory.getItem(slot).getItem())) return slot;
        }
        return -1;
    }

    /**
     * A hole in the floor just ahead: puts a block in it, clicked onto a face beside it that can
     * really be seen from where you stand (its bottom, its sides or its far wall).
     * @return true while it's at it; false when it can't, so the hole gets stepped down or gone round
     */
    private boolean fill(LocalPlayer player, BlockPos hole) {
        if (unfillable.contains(hole) || !mc.level.getBlockState(hole).canBeReplaced()) return false;
        int slot = fillSlot(player);
        BlockHitResult face = slot < 0 ? null : support(player, hole);
        if (face == null) {
            unfillable.add(hole.immutable());
            return false;
        }
        long now = System.currentTimeMillis();
        if (!hole.equals(filling) || !face.getBlockPos().equals(fillFace)) {
            filling = hole.immutable();
            fillFace = face.getBlockPos();
            fillSince = now;
            lookingStraight = false;
            lookingAhead = false;
            aim.setTarget(face.getLocation());
        }
        if (now - fillSince > FILL_GIVE_UP) {
            unfillable.add(filling);
            filling = null;
            backToWork(player);
            return false;
        }
        busy = true;
        doing = "filling a hole";
        target = null;
        steering = Input.EMPTY;
        Inventory inventory = player.getInventory();
        if (inventory.getSelectedSlot() != slot) {
            inventory.setSelectedSlot(slot);
            return true;
        }
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
            && hit.getBlockPos().equals(face.getBlockPos()) && hit.getDirection() == face.getDirection()
            && mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit).consumesAction()) {
            player.swing(InteractionHand.MAIN_HAND);
        }
        return true;
    }

    /**
     * A gap in the floor too wide or deep to fill: builds across it the way a player would. Facing
     * back the way it came, crouched so it can't fall, it backs out to the very edge and places a
     * block against the front of the one it stands on, then backs onto that one, until there's
     * floor again. @return true while it's at it
     */
    private boolean bridge(LocalPlayer player, BlockPos base) {
        // The last solid floor along the way, and the gap past it.
        BlockPos edge = at(base, 0, 0, -1);
        if (!solid(edge)) return endBridge(player);
        BlockPos gap = at(base, 1, 0, -1);
        if (solid(gap)) {
            // Only while bridging: on to the far end of what's been built.
            if (!bridging || solid(at(base, 2, 0, -1))) return endBridge(player);
            edge = gap;
            gap = at(base, 2, 0, -1);
        }
        if (!mc.level.getBlockState(gap).canBeReplaced() || bridged >= MAX_BRIDGE) return endBridge(player);
        int slot = fillSlot(player);
        if (slot < 0) return endBridge(player);
        if (!bridging) {
            bridging = true;
            bridgeAim = 0;
            lookingStraight = false;
            lookingAhead = false;
        }
        busy = true;
        doing = "bridging";
        target = null;
        Inventory inventory = player.getInventory();
        if (inventory.getSelectedSlot() != slot) inventory.setSelectedSlot(slot);
        // How far past the edge's front face you are.
        double past = (player.getX() - (edge.getX() + 0.5)) * axis.getStepX() + (player.getZ() - (edge.getZ() + 0.5)) * axis.getStepZ() - 0.5;
        Vec3 face = Vec3.atCenterOf(edge).add(axis.getStepX() * 0.5, -0.2, axis.getStepZ() * 0.5);
        // Turned round looking down past your feet until you're over the edge, then at its face.
        int want = past > 0.12 ? 2 : past <= 0.05 || bridgeAim == 0 ? 1 : bridgeAim;
        if (want != bridgeAim) {
            bridgeAim = want;
            aim.setTarget(want == 2 ? face : player.getEyePosition().add(-axis.getStepX() * 0.4, -3, -axis.getStepZ() * 0.4));
        } else if (want == 2) {
            aim.track(face);
        }
        // Backing up (you face the way you came), crouched so the edge holds you.
        boolean facingBack = Math.abs(Mth.wrapDegrees(player.getYRot() - axis.getOpposite().toYRot())) < 35;
        boolean[] drift = facingBack ? drift(player) : new boolean[2];
        steering = new Input(false, facingBack, drift[0], drift[1], false, true, false);
        if (bridgeAim == 2 && mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
            && hit.getBlockPos().equals(edge) && hit.getDirection() == axis
            && mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit).consumesAction()) {
            player.swing(InteractionHand.MAIN_HAND);
            bridged++;
        }
        return true;
    }

    /** Off the bridge: back to digging. Always false, for {@link #bridge}. */
    private boolean endBridge(LocalPlayer player) {
        if (bridging) {
            bridging = false;
            bridgeAim = 0;
            steering = Input.EMPTY;
            backToWork(player);
        }
        if (bridged < MAX_BRIDGE) bridged = 0;
        return false;
    }

    /**
     * The drill's getting worn: mends it with Bottles o' Enchanting thrown at your feet from the off
     * hand, the drill still in your main hand so Mending puts the experience into it. It goes until
     * the drill's mended or the bottles run out, then puts back whatever the off hand held.
     */
    private boolean repair(LocalPlayer player) {
        Inventory inventory = player.getInventory();
        long now = System.currentTimeMillis();
        ItemStack tool = drillSlot >= 0 ? inventory.getItem(drillSlot) : ItemStack.EMPTY;
        if (!repairing) {
            if (!repairXp.get() || now < noRepairUntil || !tool.isDamageableItem() || tool.getDamageValue() == 0) return false;
            if (!EnchantmentHelper.has(tool, EnchantmentEffectComponents.REPAIR_WITH_XP)) return false;
            if (1 - (double) tool.getDamageValue() / tool.getMaxDamage() > repairAt.get()) return false;
            if (!player.getOffhandItem().is(Items.EXPERIENCE_BOTTLE) && bottles(inventory) < 0) return false;
            repairing = true;
            offhandWas = -1;
            repairDamage = tool.getDamageValue();
            idleThrows = 0;
            repairNext = now + 250;
            lookingStraight = false;
            lookingAhead = false;
            // Straight down at your feet, so every orb lands on you.
            aim.setTarget(player.getEyePosition().add(axis.getStepX() * 0.05, -3, axis.getStepZ() * 0.05));
        }
        steering = Input.EMPTY;
        if (tool.getDamageValue() < repairDamage) {
            repairDamage = tool.getDamageValue();
            idleThrows = 0;
        }
        boolean mended = !tool.isDamageableItem() || tool.getDamageValue() == 0;
        if (idleThrows >= REPAIR_DUD) {
            // Bottles going in and the drill no better: something's off. Leave it a while.
            noRepairUntil = now + 120_000;
            Notify.send("Auto Mine", "Bottles aren't mending the drill, so it's carrying on", false, false, false);
            mended = true;
        }
        if (!mended && !player.getOffhandItem().is(Items.EXPERIENCE_BOTTLE)) {
            int slot = bottles(inventory);
            int menuSlot = slot < 0 ? -1 : menuSlot(slot);
            if (menuSlot < 0) {
                mended = true;
            } else {
                // Whatever the off hand held lands where the bottles were: remember it, to put it back.
                if (offhandWas < 0 && !player.getOffhandItem().isEmpty()) offhandWas = slot;
                mc.gameMode.handleInventoryMouseClick(player.containerMenu.containerId, menuSlot, OFFHAND_BUTTON, ClickType.SWAP, player);
                repairNext = Math.max(repairNext, now + 150);
                return true;
            }
        }
        if (mended) {
            endRepair(player);
            return false;
        }
        if (inventory.getSelectedSlot() != drillSlot) {
            inventory.setSelectedSlot(drillSlot);
            return true;
        }
        if (now < repairNext || aim.error() > 6) return true;
        if (mc.gameMode.useItem(player, InteractionHand.OFF_HAND).consumesAction()) {
            player.swing(InteractionHand.OFF_HAND);
            idleThrows++;
        }
        repairNext = now + 200 + random.nextInt(120);
        return true;
    }

    /** Done mending: whatever the off hand held goes back there. */
    private void endRepair(LocalPlayer player) {
        repairing = false;
        if (offhandWas >= 0) {
            int menuSlot = menuSlot(offhandWas);
            if (menuSlot >= 0) mc.gameMode.handleInventoryMouseClick(player.containerMenu.containerId, menuSlot, OFFHAND_BUTTON, ClickType.SWAP, player);
        }
        offhandWas = -1;
    }

    /** An inventory slot with Bottles o' Enchanting in it, or -1. */
    private static int bottles(Inventory inventory) {
        for (int slot = 0; slot < 36; slot++) {
            if (inventory.getItem(slot).is(Items.EXPERIENCE_BOTTLE)) return slot;
        }
        return -1;
    }

    /** True while the bottles for mending are in the off hand, so Auto Totem leaves it be. */
    public boolean usesOffhand() {
        return isEnabled() && repairing;
    }

    /** A hotbar slot holding floor blocks, brought up from the inventory if need be. -1 if there are none. */
    private int fillSlot(LocalPlayer player) {
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < 9; slot++) {
            if (slot != drillSlot && FILL.contains(inventory.getItem(slot).getItem())) return slot;
        }
        for (int slot = 9; slot < 36; slot++) {
            if (FILL.contains(inventory.getItem(slot).getItem())) return bringUp(slot);
        }
        return -1;
    }

    /**
     * Where to click to fill the hole: a face beside it, or failing that one beside a cell further
     * down, so a deep hole (lava and all: the crosshair sees through it) fills from the bottom up.
     */
    private BlockHitResult support(LocalPlayer player, BlockPos hole) {
        for (int depth = 0; depth < 4; depth++) {
            BlockPos cell = hole.below(depth);
            if (depth > 0 && !mc.level.getBlockState(cell).canBeReplaced()) return null;
            BlockHitResult face = faceBeside(player, cell);
            if (face != null) return face;
        }
        return null;
    }

    /** A face beside {@code hole} that can be seen and reached from here, to click a block in against. */
    private BlockHitResult faceBeside(LocalPlayer player, BlockPos hole) {
        Vec3 eye = player.getEyePosition();
        double reach = player.blockInteractionRange() - 0.3;
        for (BlockPos against : List.of(hole.below(), hole.relative(axis.getClockWise()), hole.relative(axis.getCounterClockWise()), hole.relative(axis))) {
            if (!solid(against)) continue;
            Direction face = Direction.UP;
            for (Direction d : Direction.values()) {
                if (against.relative(d).equals(hole)) face = d;
            }
            Vec3 middle = Vec3.atCenterOf(against).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            if (middle.distanceTo(eye) > reach) continue;
            Vec3 into = Vec3.atCenterOf(against).add(face.getStepX() * 0.45, face.getStepY() * 0.45, face.getStepZ() * 0.45);
            BlockHitResult hit = mc.level.clip(new ClipContext(eye, into, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(against) && hit.getDirection() == face) {
                return new BlockHitResult(middle, face, against, false);
            }
        }
        return null;
    }

    /** Swaps a stack from the inventory into the hotbar, never into the drill's slot. @return the hotbar slot, or -1 */
    private int bringUp(int slot) {
        Inventory inventory = mc.player.getInventory();
        int into = -1;
        for (int s = 8; s >= 0 && into < 0; s--) {
            if (s != drillSlot && inventory.getItem(s).isEmpty()) into = s;
        }
        for (int s = 8; s >= 0 && into < 0; s--) {
            if (s != drillSlot && JUNK.contains(inventory.getItem(s).getItem())) into = s;
        }
        for (int s = 8; s >= 0 && into < 0; s--) {
            if (s != drillSlot && !inventory.getItem(s).isDamageableItem()) into = s;
        }
        if (into < 0) into = drillSlot == 8 ? 7 : 8;
        int menuSlot = menuSlot(slot);
        if (menuSlot < 0) return -1;
        mc.gameMode.handleInventoryMouseClick(mc.player.containerMenu.containerId, menuSlot, into, ClickType.SWAP, mc.player);
        return into;
    }

    /** Where an inventory slot sits in whatever menu is open: each numbers your slots its own way. -1 if it isn't there. */
    private int menuSlot(int slot) {
        Inventory inventory = mc.player.getInventory();
        for (Slot s : mc.player.containerMenu.slots) {
            if (s.container == inventory && s.getContainerSlot() == slot) return s.index;
        }
        return -1;
    }

    /** Starts a new straight stretch at {@code base}: {@code dir} for {@code length} blocks, or on along the heading. */
    private void turn(BlockPos base, Direction dir, int length) {
        bridging = false;
        bridgeAim = 0;
        axis = dir;
        line = base.immutable();
        legLength = length;
        target = null;
        lookingAhead = false;
        lookingStraight = false;
        offLineSince = 0;
        blockedSince = 0;
        targetSince = System.nanoTime();
    }

    /** On a sideways stretch round lava. */
    private boolean sideways() {
        return axis != heading;
    }

    /** How much of this sideways stretch is still to go. */
    private int legLeft(BlockPos base) {
        return legLength - ((base.getX() - line.getX()) * axis.getStepX() + (base.getZ() - line.getZ()) * axis.getStepZ());
    }

    /** How far right of the line you started on (left is negative). */
    private int offset(BlockPos base) {
        Direction right = heading.getClockWise();
        return (base.getX() - start.getX()) * right.getStepX() + (base.getZ() - start.getZ()) * right.getStepZ();
    }

    /** From the tunnel's centre line to the edge of what the lava check looks at. */
    private int reach() {
        return (drill() ? half() : 0) + 1;
    }

    /** "right beside you" or "3 blocks ahead", along the stretch being dug. */
    private String ahead(BlockPos base, BlockPos pos) {
        int k = (pos.getX() - base.getX()) * axis.getStepX() + (pos.getZ() - base.getZ()) * axis.getStepZ();
        return k <= 0 ? "right beside you" : k + (k == 1 ? " block" : " blocks") + " ahead";
    }

    /**
     * A skeleton close by, or a skeleton spawner within range. On a spawner hunt that's what you
     * were digging for; otherwise it's a fight you'd rather not have while tabbed out. A spawner
     * is reported once, so turning Auto Mine back on carries on past it.
     */
    private String skeletons(LocalPlayer player) {
        if (stopSkeletons.get()) {
            double range = skeletonRange.get();
            for (AbstractSkeleton mob : mc.level.getEntitiesOfClass(AbstractSkeleton.class, player.getBoundingBox().inflate(range),
                m -> m.isAlive() && m.distanceTo(player) <= range)) {
                return mob.getName().getString() + " " + Math.round(mob.distanceTo(player)) + "m away at " + coords(mob.blockPosition());
            }
        }
        if (stopSpawners.get() && player.tickCount % 10 == 0) {
            double range = spawnerRange.get();
            int chunks = (int) Math.ceil(range / 16);
            ChunkPos centre = player.chunkPosition();
            for (int cx = centre.x - chunks; cx <= centre.x + chunks; cx++) {
                for (int cz = centre.z - chunks; cz <= centre.z + chunks; cz++) {
                    if (!mc.level.hasChunk(cx, cz)) continue;
                    for (BlockEntity entity : mc.level.getChunk(cx, cz).getBlockEntities().values()) {
                        if (!(entity instanceof SpawnerBlockEntity spawner) || SpawnerFinder.mobOf(spawner) != EntityType.SKELETON) continue;
                        BlockPos pos = spawner.getBlockPos();
                        double distance = Math.sqrt(pos.distToCenterSqr(player.position()));
                        if (distance > range || !reportedSpawners.add(pos.immutable())) continue;
                        return "Skeleton spawner " + Math.round(distance) + "m away at " + coords(pos);
                    }
                }
            }
        }
        return null;
    }

    /** What still has to come out of slice {@code k}, in the order it should be dug. */
    private List<BlockPos> needed(BlockPos base, int k) {
        List<BlockPos> out = new ArrayList<>();
        if (drill()) {
            // Highest first: the very top is the ideal drill hit, lower ones are fallbacks.
            for (int y = half(); y >= 0; y--) {
                BlockPos pos = at(base, k, 0, y);
                if (solid(pos)) out.add(pos);
            }
        } else {
            BlockPos head = at(base, k, 0, 1);
            BlockPos feet = at(base, k, 0, 0);
            if (solid(head)) out.add(head);
            if (solid(feet)) out.add(feet);
        }
        return out;
    }

    /** The first block in the list the crosshair can actually reach, or the first one if none can. */
    private BlockPos pick(List<BlockPos> blocks) {
        if (blocks.isEmpty()) return null;
        Vec3 eye = mc.player.getEyePosition();
        double reach = mc.player.blockInteractionRange();
        for (BlockPos pos : blocks) {
            // Aim a little way into the block: a ray that stops exactly on the face can miss it.
            Direction f = faceFor(pos);
            Vec3 face = Vec3.atCenterOf(pos).add(f.getStepX() * 0.4, f.getStepY() * 0.4, f.getStepZ() * 0.4);
            if (face.distanceTo(eye) > reach) continue;
            BlockHitResult hit = mc.level.clip(new ClipContext(eye, face, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)) return pos;
        }
        return blocks.getFirst();
    }

    /** A random spot on the face looking down the tunnel, kept off the edges. */
    private Vec3 facePoint(BlockPos pos) {
        double spread = 0.1 + 0.25 * randomness.get();
        double u = Math.clamp(random.nextGaussian() * spread, -0.35, 0.35);
        double v = Math.clamp(random.nextGaussian() * spread, -0.35, 0.35);
        Direction side = axis.getClockWise();
        if (faceFor(pos) == Direction.UP) {
            // Looking down onto a block on the floor: a random spot on its top.
            return Vec3.atCenterOf(pos).add(0, 0.5, 0)
                .add(side.getStepX() * u, 0, side.getStepZ() * u)
                .add(-axis.getStepX() * Math.abs(v), 0, -axis.getStepZ() * Math.abs(v));
        }
        return Vec3.atCenterOf(pos)
            .add(-axis.getStepX() * 0.5, 0, -axis.getStepZ() * 0.5)
            .add(side.getStepX() * u, v, side.getStepZ() * u);
    }

    /** Which face to hit: the one looking down the tunnel. */
    private Direction faceFor(BlockPos pos) {
        return axis.getOpposite();
    }

    /**
     * A block left at your feet (gravel that landed after the square was cut): the drill's square
     * is centred on what you break, so it would take a row out of the floor with it. Those get a
     * plain tool. @return the hotbar slot to use for {@code pos}, or -1 to keep what's held.
     */
    private int slotFor(BlockPos pos) {
        if (!drill() || drillSlot < 0 || mc.player == null) return -1;
        if (pos.getY() != base(mc.player).getY()) return drillSlot;
        net.minecraft.world.entity.player.Inventory inventory = mc.player.getInventory();
        net.minecraft.world.level.block.state.BlockState state = mc.level.getBlockState(pos);
        int best = -1;
        float bestSpeed = 1;
        for (int slot = 0; slot < 9; slot++) {
            net.minecraft.world.item.ItemStack stack = inventory.getItem(slot);
            if (slot == drillSlot || stack.isEmpty() || com.cyanite.client.modules.donut.DrillCut.isDrill(stack.getHoverName().getString())) continue;
            float speed = stack.getDestroySpeed(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                best = slot;
            }
        }
        return best < 0 ? -1 : best;
    }

    /** True while it picks between the drill and a plain tool itself, so Auto Tool keeps its hands off. */
    public boolean managesTools() {
        return isEnabled() && drill() && drillSlot >= 0;
    }

    /** Whether a block is somewhere this tunnel digs through, in the next couple of slices. */
    private boolean planned(BlockPos pos) {
        BlockPos base = base(mc.player);
        int half = drill() ? half() : 0;
        int top = drill() ? half : 1;
        for (int k = 1; k <= 2; k++) {
            for (int l = -half; l <= half; l++) {
                for (int y = 0; y <= top; y++) {
                    if (at(base, k, l, y).equals(pos)) return solid(pos);
                }
            }
        }
        return false;
    }

    private void stop(String title, String reason) {
        Notify.send("Auto Mine " + title.toLowerCase(Locale.ROOT), reason, toast.get(), sound.get(), flash.get());
        setEnabled(false);
    }

    /** The tunnel's centre line at the player's spot along it. */
    private BlockPos base(LocalPlayer player) {
        BlockPos feet = player.blockPosition();
        BlockPos base = axis.getAxis() == Direction.Axis.X
            ? new BlockPos(feet.getX(), feet.getY(), line.getZ())
            : new BlockPos(line.getX(), feet.getY(), feet.getZ());
        // Crouched out past the edge while bridging: still standing on the block behind.
        if (bridging && !solid(base.below()) && solid(base.relative(axis, -1).below())) return base.relative(axis, -1);
        return base;
    }

    /** {@code k} blocks ahead, {@code side} to the right, {@code up} above the floor you stand on. */
    private BlockPos at(BlockPos base, int k, int side, int up) {
        return base.relative(axis, k).relative(axis.getClockWise(), side).above(up);
    }

    private boolean solid(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        return !state.isAir() && !state.getCollisionShape(mc.level, pos).isEmpty();
    }

    private boolean drill() {
        return mode.is("Drill");
    }

    private boolean straight() {
        return drill() && half() == 1;
    }

    private int half() {
        return (drillSize.get().intValue() - 1) / 2;
    }

    private static String coords(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    /**
     * The tunnel ahead: its outline for the next stretch, a line along its floor to the end (or a
     * way on, when it's endless), and the block being hit. Round lava, the sideways stretch and the
     * way on from its end. Same colours as Area Miner's route.
     */
    @Override
    public void onRender3D(com.cyanite.client.render.Render3D render) {
        if (!showPath.get() || axis == null || mc.player == null) return;
        BlockPos base = base(mc.player);
        int left = length.get() > 0 ? Math.max(0, length.get().intValue() - advancedNow) : 24;
        BlockPos from = base;
        if (sideways()) {
            int rest = Math.max(0, legLeft(base));
            stretch(render, base, axis, rest, rest);
            from = base.relative(axis, rest);
        }
        stretch(render, from, heading, Math.min(left, 24), left);
        if (target != null) render.box(new AABB(target).inflate(0.01), 0x28FFFFFF, 0xC0FFFFFF, 1.5f, false);
    }

    /** One straight stretch: its outline {@code shown} blocks on from {@code from}, and its floor line {@code to} blocks on. */
    private void stretch(com.cyanite.client.render.Render3D render, BlockPos from, Direction dir, int shown, int to) {
        if (to <= 0) return;
        int half = drill() ? half() : 0;
        int top = drill() ? half * 2 : 1;
        Direction side = dir.getClockWise();
        if (shown > 0) {
            BlockPos a = from.relative(dir, 1).relative(side, -half);
            BlockPos b = from.relative(dir, shown).relative(side, half).above(top);
            render.box(new AABB(a).minmax(new AABB(b)), 0, com.cyanite.client.gui.Theme.fade(ROUTE_COLOR, 0.35f), 1.2f, false);
        }
        Vec3 a = Vec3.atBottomCenterOf(from).add(0, 0.06, 0);
        Vec3 b = Vec3.atBottomCenterOf(from.relative(dir, to)).add(0, 0.06, 0);
        render.line(a, b, ROUTE_COLOR, 3f, true);
    }
}
