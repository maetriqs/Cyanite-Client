package com.cyanite.client.modules.movement;

import com.cyanite.client.modules.donut.AuctionBuyer;
import com.cyanite.client.modules.world.AreaMiner;
import com.cyanite.client.modules.world.AutoMine;
import com.cyanite.client.modules.world.BedrockBurrow;
import com.cyanite.client.modules.world.SchematicBuilder;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.HumanAim;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.Notify;
import com.cyanite.client.system.WorldScan;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.SliderSetting;
import com.cyanite.client.system.setting.StringSetting;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * An elytra autopilot: to the coordinates in Target, or straight on the way you're facing. It
 * jumps and opens the elytra, climbs on rockets to the cruise height (higher where the terrain
 * ahead calls for it), and fires the next rocket once the last has burnt out and the speed's
 * dropping. Every turn goes through {@link HumanAim}.
 *
 * <p>Near the target it pitches up to bleed the speed off, drops in a steep dive once it's over
 * the spot, pulls up about 50 blocks above the ground, and sinks the rest of the way nose high,
 * slower than the 10 blocks a second that would hurt on landing.
 *
 * <p>Out of rockets, it lands where it is; with Buy rockets from /ah on it buys more off the
 * auction house ({@link AuctionBuyer}, rockets without firework stars only: those hurt you) and
 * takes off again. Before take-off it buys when you're below Buy below. With the elytra down to
 * its last few uses it lands and stops, before the elytra shuts in the air.
 */
public class AutoElytra extends Module {
    private enum Phase { BUY, TAKEOFF, CRUISE, BRAKE, DESCEND }

    /** Why it's coming down. */
    private enum Landing { TARGET, ROCKETS, ELYTRA }

    private static final int STACK = 64;
    /** Closer than this on the ground and you're there already. */
    private static final double ARRIVED = 8;
    /** Above this over the ground it dives; below it, it pulls up and sinks the rest nose high. */
    private static final double DIVE_ABOVE = 50;
    /** Climbing on rockets, nose up this far; diving to lose height, nose down at most this far. */
    private static final double CLIMB_PITCH = -35;
    private static final double DESCEND_PITCH = 20;
    /** Nose high: the elytra all but stalls and sinks at 2 to 8 blocks a second. */
    private static final double FLARE_PITCH = -45;
    private static final double DIVE_PITCH = 80;
    /** Ticks after a rocket before its entity is sure to have shown up next to you. */
    private static final int ROCKET_SHOWS = 8;
    private static final int MAX_TAKEOFFS = 3;

    private final ModeSetting mode = add(new ModeSetting("Fly", "To coordinates", List.of("To coordinates", "Straight")));
    private final StringSetting target = add(new StringSetting("Target", "", 40).placeholder("X Z, e.g. 1500 -3200"));
    private final SliderSetting cruise = add(new SliderSetting("Cruise height", 200, 0, 320, 5).format(v -> v == 0 ? "Where you are" : "Y " + (int) v));
    private final SliderSetting clearance = add(new SliderSetting("Terrain clearance", 24, 8, 64, 4).format(v -> (int) v + " blocks"));
    private final SliderSetting rocketBelow = add(new SliderSetting("Rocket below", 26, 10, 33, 1).format(v -> (int) v + " blocks/s"));
    /** Uses the elytra has left when it lands: about a second each, and the landing takes some. */
    private final SliderSetting reserve = add(new SliderSetting("Land at durability", 30, 5, 120, 5).format(v -> (int) v + " left"));
    private final BoolSetting buy = add(new BoolSetting("Buy rockets from /ah", true));
    private final SliderSetting buyBelow = add(new SliderSetting("Buy below", 16, 1, 128, 1).format(v -> (int) v + (v == 1 ? " rocket" : " rockets")));
    private final SliderSetting buyStacks = add(new SliderSetting("Buy stacks", 1, 1, 9, 1).format(v -> (int) v + (v == 1 ? " stack" : " stacks")));
    private final SliderSetting maxStack = add(new SliderSetting("Max price a stack", 20_000, 64, 10_000_000, 1).logarithmic().format(AutoElytra::dollars));
    /** The most it spends on rockets in one run. */
    private final SliderSetting budget = add(new SliderSetting("Rocket budget", 200_000, 1_000, 100_000_000, 1).logarithmic().format(AutoElytra::dollars));
    /** Off to match DonutSMP's own "auction buy confirm" setting being off: a click on a listing buys it. */
    private final BoolSetting ahConfirm = add(new BoolSetting("AH confirm screen", false));
    private final SliderSetting turnSpeed = add(new SliderSetting("Turn speed", 1, 0.3, 3, 0.1).format(v -> String.format(Locale.ROOT, "%.1fx", v)));
    private final BoolSetting toast = add(new BoolSetting("Toast", true));
    private final BoolSetting sound = add(new BoolSetting("Sound", true));
    private final BoolSetting flash = add(new BoolSetting("Flash taskbar", true));

    private final HumanAim aim = new HumanAim();
    private final AuctionBuyer buyer = new AuctionBuyer();

    private LocalPlayer owner;
    private Phase phase = Phase.TAKEOFF;
    private int ticks;
    private int phaseSince;
    private Input steering;
    /** Where it's going (y unused), or null flying straight. */
    private Vec3 goal;
    /** Flying straight: the way. */
    private float heading;
    private double cruiseY;
    private Landing landing = Landing.TARGET;
    /** Where it's coming down. */
    private Vec3 landAt;
    private int landShiftedAt;
    private int startSlot = -1;
    private int firedAt = -1000;
    private int rocketsUsed;
    private int takeoffs;
    private double spent;
    /** Ticks since it last left the ground, and whether it jumped off it itself. */
    private int airTicks;
    private boolean jumped;
    private boolean jumpedLast;
    private boolean buyStarted;

    public AutoElytra() {
        super(Category.MOVEMENT, "Auto Elytra", "Flies your elytra to coordinates or straight ahead on rockets, lands by itself, and buys more rockets off /ah when you run out");
    }

    @Override
    protected boolean ticksEarly() {
        // It fires rockets: a use, which goes out before the tick's movement.
        return true;
    }

    private boolean toCoords() {
        return mode.get().equals("To coordinates");
    }

    @Override
    public String getInfo() {
        if (!isEnabled() || owner == null || owner != mc.player) return toCoords() ? null : "Straight";
        return switch (phase) {
            case BUY -> "Buying rockets";
            case TAKEOFF -> "Taking off";
            case BRAKE, DESCEND -> "Landing";
            case CRUISE -> goal == null ? "Straight" : distance(Math.hypot(goal.x - owner.getX(), goal.z - owner.getZ()));
        };
    }

    /** Your keys while it jumps off or stands at /ah; yours otherwise. */
    public Input steering() {
        return isEnabled() && owner != null && owner == mc.player ? steering : null;
    }

    @Override
    protected void onEnable() {
        owner = inGame() ? mc.player : null;
        steering = null;
        goal = null;
        ticks = 0;
        firedAt = -1000;
        rocketsUsed = 0;
        takeoffs = 0;
        spent = 0;
        airTicks = 0;
        jumped = false;
        jumpedLast = false;
        buyStarted = false;
        landing = Landing.TARGET;
        landAt = null;
        aim.clear();
        if (owner == null) {
            setEnabled(false);
            return;
        }
        startSlot = owner.getInventory().getSelectedSlot();
        if (mc.level.dimensionType().hasCeiling()) {
            stop("Auto Elytra can't start", "It only flies under an open sky, not under the Nether's roof");
            return;
        }
        if (toCoords()) {
            double[] xz = parse(target.get());
            if (xz == null) {
                stop("Auto Elytra can't start", "Put where to go in Target first: X and Z, like 1500 -3200");
                return;
            }
            goal = new Vec3(xz[0], 0, xz[1]);
            if (Math.hypot(goal.x - owner.getX(), goal.z - owner.getZ()) < ARRIVED && !owner.isFallFlying()) {
                stop("Auto Elytra", "You're there already");
                return;
            }
        } else {
            heading = owner.getYRot();
        }
        int left = elytraLeft(owner);
        if (left < 0) {
            stop("Auto Elytra can't start", "Wear an elytra first");
            return;
        }
        if (left <= reserve.get() && !owner.isFallFlying()) {
            stop("Auto Elytra can't start", "Your elytra's down to " + left + " uses, under Land at durability (" + reserve.display() + "): mend it first");
            return;
        }
        if (owner.getAbilities().flying) {
            stop("Auto Elytra can't start", "Creative flight's on: land first");
            return;
        }
        if (rockets(owner) == 0 && hasStarredRockets(owner) && !buy.get()) {
            stop("Auto Elytra can't start", "Your only rockets have firework stars, which hurt you when they go off");
            return;
        }
        // Only one thing steers the player at a time.
        AutoMine autoMine = Modules.get(AutoMine.class);
        if (autoMine != null && autoMine.isEnabled()) autoMine.setEnabled(false);
        AreaMiner areaMiner = Modules.get(AreaMiner.class);
        if (areaMiner != null && areaMiner.isEnabled()) areaMiner.stop();
        BedrockBurrow burrow = Modules.get(BedrockBurrow.class);
        if (burrow != null && burrow.isEnabled()) burrow.setEnabled(false);
        SchematicBuilder builder = Modules.get(SchematicBuilder.class);
        if (builder != null && builder.isEnabled()) builder.setEnabled(false);

        cruiseY = cruise.get() == 0 ? owner.getY() : cruise.get();
        if (owner.isFallFlying()) {
            enter(Phase.CRUISE);
        } else if (owner.onGround() && buy.get() && rockets(owner) < buyBelow.get()) {
            enter(Phase.BUY);
        } else if (rockets(owner) == 0) {
            stop("Auto Elytra can't start", "No rockets: put some in your inventory, or turn on Buy rockets from /ah");
        } else {
            enter(Phase.TAKEOFF);
        }
    }

    @Override
    protected void onDisable() {
        if (buyer.active()) buyer.cancel();
        steering = null;
        aim.clear();
        // Still gliding (switched off in the air), the rockets stay in your hand for you to carry on.
        if (inGame() && owner == mc.player && startSlot >= 0 && !owner.isFallFlying()) mc.player.getInventory().setSelectedSlot(startSlot);
        owner = null;
    }

    private void enter(Phase next) {
        phase = next;
        phaseSince = ticks;
        if (next == Phase.TAKEOFF) {
            airTicks = 0;
            jumped = false;
        }
        if (next == Phase.BUY) buyStarted = false;
    }

    private int inPhase() {
        return ticks - phaseSince;
    }

    private void stop(String title, String body) {
        Notify.send(title, body, toast.get(), sound.get(), flash.get());
        setEnabled(false);
    }

    @Override
    public void onFrame() {
        if (mc.isPaused()) {
            aim.idle();
            return;
        }
        aim.speed = turnSpeed.get();
        aim.randomness = 0.3;
        aim.frame();
    }

    @Override
    public void onTick() {
        LocalPlayer player = mc.player;
        if (owner == null || player != owner) {
            // A new player: you died, went through a portal or the connection dropped.
            if (buyer.active()) buyer.cancel();
            owner = null;
            setEnabled(false);
            return;
        }
        if (player.isDeadOrDying()) {
            stop("Auto Elytra stopped", "You died");
            return;
        }
        ticks++;
        steering = null;
        boolean jumpWas = jumpedLast;
        jumpedLast = false;
        switch (phase) {
            case BUY -> buying(player);
            case TAKEOFF -> takeOff(player, jumpWas);
            case CRUISE -> cruise(player, jumpWas);
            case BRAKE, DESCEND -> land(player, jumpWas);
        }
    }

    // ---- Taking off

    /**
     * Turns the way it's going with the nose up, jumps, and jumps again once past the top of the
     * jump to open the elytra (eight ticks on: a second press sooner is creative's double jump),
     * then the first rocket.
     */
    private void takeOff(LocalPlayer player, boolean jumpWas) {
        steering = Input.EMPTY;
        if (player.isFallFlying()) {
            takeoffs++;
            if (!fire(player)) {
                landNow(player, Landing.ROCKETS);
                return;
            }
            enter(Phase.CRUISE);
            return;
        }
        if (player.isInWater() || player.isInLava()) {
            stop("Auto Elytra stopped", "It can't take off from " + (player.isInLava() ? "lava" : "water"));
            return;
        }
        if (takeoffs >= MAX_TAKEOFFS) {
            stop("Auto Elytra stopped", "It came down " + takeoffs + " times without getting anywhere: something's in the way");
            return;
        }
        // On the ground, rockets from the inventory go to the hotbar now rather than in the air.
        if (rocketHand(player, player.onGround()) == null) {
            if (buy.get() && player.onGround()) {
                enter(Phase.BUY);
            } else {
                stop("Auto Elytra stopped", "Out of rockets");
            }
            return;
        }
        aimAt(player, headingYaw(player), CLIMB_PITCH);
        if (player.onGround()) {
            airTicks = 0;
            // Facing about the right way first; it gives up waiting after two seconds.
            if (aim.error() > 8 && inPhase() < 40) return;
            if (inPhase() > 200) {
                stop("Auto Elytra stopped", "It couldn't get off the ground");
                return;
            }
            if (!jumpWas) {
                press(true);
                jumped = true;
            }
            return;
        }
        airTicks++;
        // Already falling when it started: open straight away.
        int wait = jumped ? 8 : 1;
        if (airTicks >= wait && !jumpWas) press(true);
    }

    private void press(boolean jump) {
        steering = new Input(false, false, false, false, jump, false, false);
        jumpedLast = jump;
    }

    // ---- Cruising

    private void cruise(LocalPlayer player, boolean jumpWas) {
        if (!player.isFallFlying()) {
            notFlying(player, jumpWas);
            return;
        }
        int left = elytraLeft(player);
        if (left <= reserve.get()) {
            landNow(player, Landing.ELYTRA);
            return;
        }
        // Ten seconds up and going: whatever brought it down before is behind it.
        if (inPhase() > 200) takeoffs = 0;
        Vec3 v = player.getDeltaMovement();
        double speed = v.horizontalDistance();
        double yaw = headingYaw(player);
        if (goal != null) {
            double d = Math.hypot(goal.x - player.getX(), goal.z - player.getZ());
            // Braking nose high takes about 30 ticks' worth of the speed it's at.
            if (d < speed * 30 + 10) {
                startLanding(Landing.TARGET, goal);
                return;
            }
        }
        double rad = Math.toRadians(yaw);
        double hx = -Math.sin(rad);
        double hz = Math.cos(rad);
        double wanted = Math.max(cruiseY, terrainAhead(player, hx, hz, 128) + clearance.get());
        double below = wanted - player.getY();
        double pitch = Mth.clamp(-below * 1.5, CLIMB_PITCH, DESCEND_PITCH);
        // Ground close ahead at about its height: nose up and a rocket, now.
        boolean wall = terrainAhead(player, hx, hz, 32) > player.getY() - 3;
        if (wall) pitch = -40;
        aimAt(player, yaw, pitch);
        if (!boosting(player) && (speed < rocketBelow.get() / 20 || below > 8 || wall)) {
            if (!fire(player)) landNow(player, Landing.ROCKETS);
        }
    }

    /** Gliding stopped mid-flight: down on the ground, in water, or the elytra shut in the air. */
    private void notFlying(LocalPlayer player, boolean jumpWas) {
        if (player.isInWater() || player.isInLava()) {
            stop("Auto Elytra stopped", "It came down in " + (player.isInLava() ? "lava" : "water"));
            return;
        }
        if (player.onGround()) {
            if (goal != null && Math.hypot(goal.x - player.getX(), goal.z - player.getZ()) < ARRIVED * 2) {
                landing = Landing.TARGET;
                landed(player);
                return;
            }
            // Clipped something on the way: up again.
            enter(Phase.TAKEOFF);
            return;
        }
        // In the air with the elytra shut: open it again.
        steering = Input.EMPTY;
        if (!jumpWas && elytraLeft(player) > 0) press(true);
    }

    /** The highest ground (or water, or leaves) along the way for {@code reach} blocks, in loaded chunks. */
    private double terrainAhead(LocalPlayer player, double hx, double hz, int reach) {
        Level level = player.level();
        double top = level.getMinY();
        for (int s = 0; s <= reach; s += s < 64 ? 4 : 8) {
            int x = Mth.floor(player.getX() + hx * s);
            int z = Mth.floor(player.getZ() + hz * s);
            if (!level.hasChunk(x >> 4, z >> 4)) continue;
            top = Math.max(top, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z));
        }
        return top;
    }

    // ---- Rockets

    /** A rocket without stars: one with them goes off in your hand and hurts. */
    public static boolean usable(ItemStack stack) {
        if (!stack.is(Items.FIREWORK_ROCKET)) return false;
        Fireworks fireworks = stack.get(DataComponents.FIREWORKS);
        return fireworks == null || fireworks.explosions().isEmpty();
    }

    /** Usable rockets in your inventory and off hand. */
    private static int rockets(LocalPlayer player) {
        Inventory inventory = player.getInventory();
        int n = 0;
        for (int i = 0; i < 36; i++) if (usable(inventory.getItem(i))) n += inventory.getItem(i).getCount();
        if (usable(player.getOffhandItem())) n += player.getOffhandItem().getCount();
        return n;
    }

    private static boolean hasStarredRockets(LocalPlayer player) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < 36; i++) if (inventory.getItem(i).is(Items.FIREWORK_ROCKET)) return true;
        return player.getOffhandItem().is(Items.FIREWORK_ROCKET);
    }

    /**
     * The hand to fire a rocket from: the off hand if it holds them, else the hotbar slot with
     * them, picked. {@code fetch}: none in the hotbar, a stack's swapped in from the inventory
     * (into an empty slot, or the one you're on). Null for no rockets.
     */
    private InteractionHand rocketHand(LocalPlayer player, boolean fetch) {
        if (usable(player.getOffhandItem())) return InteractionHand.OFF_HAND;
        Inventory inventory = player.getInventory();
        if (usable(inventory.getSelectedItem())) return InteractionHand.MAIN_HAND;
        int slot = -1;
        for (int i = 0; i < 9 && slot < 0; i++) if (usable(inventory.getItem(i))) slot = i;
        if (slot < 0) {
            int from = -1;
            for (int i = 9; i < 36 && from < 0; i++) if (usable(inventory.getItem(i))) from = i;
            if (from < 0) return null;
            if (!fetch) return InteractionHand.MAIN_HAND;
            // Not with a chest or the like open: the click would go to it.
            if (player.containerMenu != player.inventoryMenu) return null;
            int to = inventory.getSelectedSlot();
            for (int i = 0; i < 9; i++) {
                if (inventory.getItem(i).isEmpty()) {
                    to = i;
                    break;
                }
            }
            // The inventory menu numbers the main inventory 9-35 as the inventory does; a swap's button is the hotbar slot.
            mc.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, from, to, ClickType.SWAP, player);
            slot = to;
        }
        inventory.setSelectedSlot(slot);
        return InteractionHand.MAIN_HAND;
    }

    /** Fires a rocket, the way right click would. @return false if there are none */
    private boolean fire(LocalPlayer player) {
        // A menu of yours in the way (it would take the click): later.
        if (mc.screen instanceof AbstractContainerScreen<?>) return true;
        InteractionHand hand = rocketHand(player, true);
        if (hand == null || !usable(player.getItemInHand(hand))) return false;
        if (mc.gameMode.useItem(player, hand) instanceof InteractionResult.Success success
            && success.swingSource() == InteractionResult.SwingSource.CLIENT) {
            player.swing(hand);
        }
        firedAt = ticks;
        rocketsUsed++;
        return true;
    }

    /** A rocket still pushing: its entity rides along right next to you. */
    private boolean boosting(LocalPlayer player) {
        if (ticks - firedAt < ROCKET_SHOWS) return true;
        for (Entity entity : WorldScan.entities()) {
            if (entity instanceof FireworkRocketEntity && entity.distanceToSqr(player) < 9) return true;
        }
        return false;
    }

    // ---- Landing

    /** Down here, wherever here is: braking takes it a little further on. */
    private void landNow(LocalPlayer player, Landing why) {
        Vec3 v = player.getDeltaMovement();
        double ahead = v.horizontalDistance() * 30;
        double rad = Math.toRadians(player.getYRot());
        startLanding(why, new Vec3(player.getX() - Math.sin(rad) * ahead, 0, player.getZ() + Math.cos(rad) * ahead));
    }

    private void startLanding(Landing why, Vec3 spot) {
        landing = why;
        landAt = spot;
        landShiftedAt = ticks;
        if (why == Landing.ROCKETS) {
            Notify.send("Auto Elytra", buy.get() ? "Out of rockets: landing to buy more" : "Out of rockets: landing", false, false, false);
        } else if (why == Landing.ELYTRA) {
            Notify.send("Auto Elytra", "Your elytra's nearly broken (" + elytraLeft(owner) + " uses left): landing", false, false, false);
        }
        enter(Phase.BRAKE);
    }

    /**
     * Nose high until the speed's gone (BRAKE), then over the spot: a dive while it's high, a
     * pull-up from 50 blocks over the ground, and nose high the rest of the way down, sinking
     * slower than the 0.5 blocks a tick that would hurt.
     */
    private void land(LocalPlayer player, boolean jumpWas) {
        if (!player.isFallFlying()) {
            if (player.onGround() || player.isInWater() || player.isInLava()) {
                landed(player);
            } else {
                steering = Input.EMPTY;
                if (!jumpWas && elytraLeft(player) > 0) press(true);
            }
            return;
        }
        Vec3 v = player.getDeltaMovement();
        double ground = groundBelow(player);
        double above = Double.isNaN(ground) ? 1000 : player.getY() - ground;
        avoidLiquid(player, above);
        double dx = landAt.x - player.getX();
        double dz = landAt.z - player.getZ();
        double d = Math.hypot(dx, dz);
        // Right over it, the way it's facing is as good as any (no whipping round as it passes).
        double yaw = d > 3 ? yawTo(dx, dz) : player.getYRot();
        double pitch;
        if (phase == Phase.BRAKE) {
            pitch = FLARE_PITCH;
            if (v.horizontalDistance() < 0.6 || inPhase() > 100) enter(Phase.DESCEND);
        } else if (above > DIVE_ABOVE && d < 16) {
            pitch = DIVE_PITCH;
        } else if (v.y < -0.6 || above > DIVE_ABOVE || d < 20 || above < 8) {
            pitch = FLARE_PITCH;
        } else {
            // Low, and still some way off: glide over to it.
            pitch = 0;
        }
        aimAt(player, yaw, pitch);
    }

    /** Coming down on water or lava where the spot wasn't the target itself: further on, while there's height to. */
    private void avoidLiquid(LocalPlayer player, double above) {
        if (above < 20 || ticks - landShiftedAt < 20) return;
        boolean lava = liquidAt(player.level(), landAt, true);
        if (!lava && (landing == Landing.TARGET || !liquidAt(player.level(), landAt, false))) return;
        double rad = Math.toRadians(player.getYRot());
        landAt = landAt.add(-Math.sin(rad) * 32, 0, Math.cos(rad) * 32);
        landShiftedAt = ticks;
    }

    /** Whether the top of the column at {@code spot} is lava (or any liquid, {@code lavaOnly} false); false if not loaded. */
    private static boolean liquidAt(Level level, Vec3 spot, boolean lavaOnly) {
        int x = Mth.floor(spot.x);
        int z = Mth.floor(spot.z);
        if (!level.hasChunk(x >> 4, z >> 4)) return false;
        BlockPos top = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1, z);
        var fluid = level.getFluidState(top);
        return lavaOnly ? fluid.is(net.minecraft.tags.FluidTags.LAVA) : !fluid.isEmpty();
    }

    /** The top of the ground under you and a block ahead; NaN where the chunk isn't loaded. */
    private static double groundBelow(LocalPlayer player) {
        Level level = player.level();
        Vec3 v = player.getDeltaMovement();
        double top = Double.NaN;
        for (int i = 0; i <= 1; i++) {
            int x = Mth.floor(player.getX() + v.x * i * 3);
            int z = Mth.floor(player.getZ() + v.z * i * 3);
            if (!level.hasChunk(x >> 4, z >> 4)) continue;
            double h = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            top = Double.isNaN(top) ? h : Math.max(top, h);
        }
        return top;
    }

    /** Touched down: done, or (out of rockets) off to /ah and up again. */
    private void landed(LocalPlayer player) {
        aim.clear();
        switch (landing) {
            case TARGET -> {
                double off = goal == null ? 0 : Math.hypot(goal.x - player.getX(), goal.z - player.getZ());
                stop("Auto Elytra", String.format(Locale.ROOT, "Landed %.0f blocks from %d %d (%d rockets used)",
                    off, Math.round(goal.x), Math.round(goal.z), rocketsUsed));
            }
            case ELYTRA -> stop("Auto Elytra stopped", "Landed: your elytra's down to " + elytraLeft(player) + " uses. Mend it, then turn this on again");
            case ROCKETS -> {
                if (rockets(player) > 0) {
                    // Some turned up (picked up, or given): on we go.
                    enter(Phase.TAKEOFF);
                } else if (buy.get() && !player.isInWater() && !player.isInLava()) {
                    enter(Phase.BUY);
                } else {
                    stop("Auto Elytra stopped", "Landed out of rockets" + (buy.get() ? " in water" : ": turn on Buy rockets from /ah to have it buy more"));
                }
            }
        }
    }

    // ---- Buying

    /** A stack or few of rockets off /ah, the cheapest under your price, then up again. */
    private void buying(LocalPlayer player) {
        steering = Input.EMPTY;
        if (!buyStarted) {
            // Still settling after the landing.
            if (!player.onGround() && !player.isInWater()) return;
            double left = budget.get() - spent;
            if (left < 1) {
                afterBuying(player, "the rocket budget's spent");
                return;
            }
            buyStarted = true;
            buyer.start(Items.FIREWORK_ROCKET, (int) Math.round(buyStacks.get()) * STACK, maxStack.get() / STACK, left,
                ahConfirm.get(), true, AutoElytra::usable);
            return;
        }
        AuctionBuyer.Result result = buyer.tick();
        if (result == AuctionBuyer.Result.RUNNING) return;
        spent += buyer.spent();
        double cheapest = buyer.cheapestEach();
        String why = buyer.full() ? "no room for them" : !Double.isNaN(cheapest) && cheapest * STACK > maxStack.get()
            ? "cheapest is " + dollars(cheapest * STACK) + " a stack, your max is " + maxStack.display()
            : buyer.why().isEmpty() || buyer.why().startsWith("no ") ? "none listed" : buyer.why();
        if (buyer.got() > 0) {
            Notify.send("Auto Elytra", "Bought " + buyer.got() + " rockets for " + dollars(buyer.spent())
                + (buyer.why().isEmpty() ? "" : " (no more: " + why + ")"), false, false, false);
            afterBuying(player, null);
        } else {
            afterBuying(player, why);
        }
    }

    private void afterBuying(LocalPlayer player, String whyNot) {
        int have = rockets(player);
        if (have == 0) {
            stop("Auto Elytra stopped", "No rockets, and none bought: " + whyNot);
            return;
        }
        if (whyNot != null) Notify.send("Auto Elytra", "Not buying rockets: " + whyNot + ". Going on with the " + have + " you have", false, false, false);
        enter(Phase.TAKEOFF);
    }

    // ---- Aim and odds and ends

    private double headingYaw(LocalPlayer player) {
        if (goal == null) return heading;
        return yawTo(goal.x - player.getX(), goal.z - player.getZ());
    }

    private static double yawTo(double dx, double dz) {
        return Math.toDegrees(Math.atan2(dz, dx)) - 90;
    }

    /** Points the crosshair along {@code yaw}/{@code pitch}, through HumanAim. */
    private void aimAt(LocalPlayer player, double yaw, double pitch) {
        Vec3 point = player.getEyePosition().add(Vec3.directionFromRotation((float) pitch, (float) yaw).scale(512));
        if (aim.target() == null) aim.setTarget(point);
        else aim.track(point);
    }

    /** Uses the worn elytra has before it shuts (it won't open on its last), or -1 with none worn. */
    private static int elytraLeft(LocalPlayer player) {
        ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
        if (!chest.has(DataComponents.GLIDER)) return -1;
        if (!chest.isDamageableItem()) return Integer.MAX_VALUE;
        return chest.getMaxDamage() - chest.getDamageValue() - 1;
    }

    /** "X Z", or "X Y Z" as F3 shows it (the Y's left out: it lands on whatever's there). Null if it isn't that. */
    static double[] parse(String text) {
        if (text == null) return null;
        String[] parts = text.trim().split("[\\s,]+");
        List<Double> numbers = new ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            try {
                numbers.add(Double.parseDouble(part));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        double x;
        double z;
        if (numbers.size() == 2) {
            x = numbers.get(0);
            z = numbers.get(1);
        } else if (numbers.size() == 3) {
            x = numbers.get(0);
            z = numbers.get(2);
        } else {
            return null;
        }
        if (!Double.isFinite(x) || !Double.isFinite(z) || Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000) return null;
        return new double[] {x, z};
    }

    private static String distance(double blocks) {
        if (blocks >= 10_000) return String.format(Locale.ROOT, "%.0fK", blocks / 1000);
        if (blocks >= 1000) return String.format(Locale.ROOT, "%.1fK", blocks / 1000);
        return (int) blocks + "m";
    }

    static String dollars(double v) {
        if (v >= 1e9) return String.format(Locale.ROOT, "$%sB", trim(v / 1e9));
        if (v >= 1e6) return String.format(Locale.ROOT, "$%sM", trim(v / 1e6));
        if (v >= 1e4) return String.format(Locale.ROOT, "$%sK", trim(v / 1e3));
        return String.format(Locale.ROOT, "$%,d", Math.round(v));
    }

    private static String trim(double v) {
        String s = String.format(Locale.ROOT, "%.2f", v);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }
}
