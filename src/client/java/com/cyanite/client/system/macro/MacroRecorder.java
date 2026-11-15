package com.cyanite.client.system.macro;

import com.cyanite.client.CyaniteClientMod;
import com.cyanite.client.config.Config;
import com.cyanite.client.modules.world.area.Paths;
import com.cyanite.client.system.Macros;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.Notify;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.lwjgl.glfw.GLFW;

/**
 * Watches what you do and writes it into a macro as blocks, on the end of the script as you go.
 * Walking becomes "walk to" blocks along the way you went; breaking, placing and right clicking
 * blocks become mine, place and use blocks; chat you type, hotbar slots, menu clicks, dropping,
 * swapping hands, opening your inventory and module keybinds each get theirs; and the pauses in
 * between become waits. A click on something that isn't a block gets a "face" block first so it
 * lands the same way, and a teleport (a /home, a pearl) waits until you've arrived.
 *
 * Only your own input counts: vanilla's click handlers say when they're running (MinecraftMixin),
 * and the runner and the recorder never go at once. Time spent in menus that aren't chests and
 * the like (chat, the pause menu, Cyanite's) isn't counted as a pause.
 */
public final class MacroRecorder {
    private static final Minecraft mc = Minecraft.getInstance();
    /** Shorter pauses than this aren't worth a wait block. */
    private static final long MIN_PAUSE = 250;
    /** Standing still this long ends a walk, so it shows up in the script. */
    private static final long SETTLE_MS = 600;
    /** How far the way you walked may stray from a straight line before it gets another "walk to". */
    private static final double ROUTE_SLACK = 1.0;
    /** The longest single "walk to", so each route search stays small. */
    private static final double MAX_LEG = 32;
    /** Further than this in one tick isn't walking. */
    private static final double TELEPORT = 8;
    /** Holding right click longer than this is a hold, not a click: eating, drawing a bow. */
    private static final long HOLD_MIN = 300;
    private static final float LOOK_SLACK = 2;

    private Macro macro;
    private LocalPlayer owner;
    private int added;
    private int skipped;
    private Macro summaryFor;
    private String summary;

    // Set while vanilla's own click handlers run, so what they do is known to be your click.
    private boolean userAttack;
    private boolean userUse;
    private boolean usedThisClick;

    // The recording's clock: it stops while you're in chat or a menu that isn't part of the game.
    private long clock;
    private long lastTickAt;
    private Screen lastScreen;
    private long lastAt;

    private int slot;
    private boolean slotKnown;
    /** Where the replay will be facing; NaN when that isn't known. */
    private float lookYaw = Float.NaN;
    private float lookPitch;
    /** A walk or a block step will have turned the replay somewhere: the way you end up facing goes in at the end. */
    private boolean turned;

    // Walking.
    private BlockPos standing;
    private final List<BlockPos> route = new ArrayList<>();
    private Vec3 lastPos;
    private long stillAt;
    private long setOffAt;
    private long movedAt;

    // A block being mined, a block about to be placed, and an item held in use.
    private BlockPos miningPos;
    private long miningSince;
    private BlockPos placeAt;
    private BlockState placeBefore;
    private MacroStep using;
    private long usingSince;
    /** Keys down right now that went in as "hold down key" blocks, by key code. */
    private final Map<Integer, Held> held = new HashMap<>();

    private record Held(MacroStep step, long at) {
    }

    public boolean recording() {
        return macro != null;
    }

    /** The macro being recorded into, or null. */
    public Macro macro() {
        return macro;
    }

    /** Blocks added so far. */
    public int added() {
        return added;
    }

    /** How the last recording into this macro went, or null. */
    public String summary(Macro of) {
        return of == summaryFor ? summary : null;
    }

    public void start(Macro into) {
        stop();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || into == null) return;
        macro = into;
        owner = player;
        added = 0;
        skipped = 0;
        summaryFor = null;
        summary = null;
        clock = 0;
        lastTickAt = System.currentTimeMillis();
        lastScreen = mc.screen;
        lastAt = 0;
        slot = player.getInventory().getSelectedSlot();
        slotKnown = false;
        lookYaw = Float.NaN;
        turned = false;
        standing = standBlock(player);
        route.clear();
        lastPos = player.position();
        stillAt = setOffAt = movedAt = 0;
        miningPos = null;
        placeAt = null;
        using = null;
    }

    /** Ends the recording: the last walk, and which way you ended up facing, go in too. */
    public void stop() {
        if (macro == null) return;
        LocalPlayer player = mc.player;
        if (player != null && player == owner) {
            finishUse(now());
            flushWalk();
            if (macro != null && (turned || !Float.isNaN(lookYaw))) look(player);
        }
        end(null);
    }

    /** Ends it without a word: the macro's gone, or its list is being replaced. */
    public void cancel() {
        held.clear();
        macro = null;
        owner = null;
        route.clear();
        using = null;
    }

    private void end(String problem) {
        Macro into = macro;
        if (into == null) return;
        cancel();
        summaryFor = into;
        summary = (problem == null ? "" : problem + ". ") + "Recorded " + added + (added == 1 ? " block" : " blocks")
            + (skipped == 0 ? "" : ", skipped " + skipped + " menu " + (skipped == 1 ? "click" : "clicks") + " macros can't do");
        Notify.send(problem == null ? "Macro recorded" : "Recording stopped", into.name + ": " + summary, true, false, false);
        Config.saveSoon();
    }

    // ---- Every tick

    public void tick() {
        if (macro == null) return;
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            flushWalk();
            end("You left the world");
            return;
        }
        if (player.isDeadOrDying()) {
            flushWalk();
            end("You died");
            return;
        }
        long wall = System.currentTimeMillis();
        if (!offClock(lastScreen) && !offClock(mc.screen)) clock += Math.max(0, Math.min(wall - lastTickAt, 1000));
        lastTickAt = wall;
        long t = clock;
        if (player != owner) {
            // Through a portal or a /warp to another world: a teleport, as far as the macro goes.
            owner = player;
            teleported(player, t);
            if (macro == null) return;
        }

        int selected = player.getInventory().getSelectedSlot();
        if (selected != slot) {
            slot = selected;
            slotChanged();
        }
        if (mc.screen instanceof InventoryScreen && !(lastScreen instanceof InventoryScreen)) add(new MacroStep(MacroStep.Type.OPEN_INVENTORY), t);
        lastScreen = mc.screen;
        if (using != null && !player.isUsingItem()) finishUse(t);
        if (macro != null) walk(player, t);
    }

    /** Chat, the pause menu and Cyanite's own menus aren't part of the macro; chests and other menus are. */
    private static boolean offClock(Screen screen) {
        return screen != null && !(screen instanceof AbstractContainerScreen<?>);
    }

    /** The recording's clock right now, between ticks too. */
    private long now() {
        if (offClock(mc.screen)) return clock;
        return clock + Math.max(0, Math.min(System.currentTimeMillis() - lastTickAt, 1000));
    }

    // ---- Walking

    private void walk(LocalPlayer player, long t) {
        Vec3 pos = player.position();
        if (pos.distanceTo(lastPos) > TELEPORT) {
            teleported(player, t);
            return;
        }
        boolean moving = Math.abs(pos.x - lastPos.x) > 0.003 || Math.abs(pos.z - lastPos.z) > 0.003 || Math.abs(pos.y - lastPos.y) > 0.01;
        lastPos = pos;
        if (moving) movedAt = t;
        else if (route.isEmpty()) stillAt = t;
        if (player.onGround() || player.isInWater() || player.onClimbable()) {
            BlockPos here = standBlock(player);
            BlockPos last = route.isEmpty() ? standing : route.getLast();
            if (!here.equals(last)) {
                if (route.isEmpty()) setOffAt = stillAt;
                route.add(here);
            }
        }
        if (!route.isEmpty() && !moving && t - movedAt > SETTLE_MS) flushWalk();
    }

    /** The walk since the last block, as "walk to" blocks along the way you went. */
    private void flushWalk() {
        if (macro == null || route.isEmpty()) return;
        List<BlockPos> points = new ArrayList<>(route.size() + 1);
        points.add(standing);
        points.addAll(route);
        route.clear();
        BlockPos end = points.getLast();
        // Off and back to the same block with nothing done on the way: no walk at all.
        if (end.equals(standing)) return;
        standing = end;
        pause(setOffAt);
        for (BlockPos p : waypoints(points)) {
            MacroStep step = new MacroStep(MacroStep.Type.WALK_TO);
            step.set("pos", p);
            append(step);
            if (macro == null) return;
        }
        lastAt = Math.max(lastAt, movedAt);
        lookYaw = Float.NaN;
        turned = true;
    }

    /** A /home, a pearl or a portal: wait until you're there before carrying on. */
    private void teleported(LocalPlayer player, long t) {
        flushWalk();
        if (macro == null) return;
        standing = standBlock(player);
        route.clear();
        lastPos = player.position();
        MacroStep near = new MacroStep(MacroStep.Type.NEAR);
        near.set("pos", standing);
        near.set("range", 4.0);
        MacroStep wait = new MacroStep(MacroStep.Type.WAIT_UNTIL);
        wait.slots[0] = near;
        wait.set("timeout", 30.0);
        append(wait);
        lastAt = stillAt = movedAt = t;
        lookYaw = Float.NaN;
        turned = true;
    }

    /**
     * The corners of the way you walked, so the route search follows it rather than finding its
     * own; long straight stretches still get a point now and then. The start isn't included.
     */
    static List<BlockPos> waypoints(List<BlockPos> points) {
        int n = points.size();
        boolean[] keep = new boolean[n];
        keep[0] = true;
        keep[n - 1] = true;
        simplify(points, 0, n - 1, keep);
        List<BlockPos> out = new ArrayList<>();
        int from = 0;
        for (int i = 1; i < n; i++) {
            if (!keep[i]) continue;
            int last = from;
            for (int j = from + 1; j < i; j++) {
                if (distance(points.get(last), points.get(j + 1)) > MAX_LEG) {
                    out.add(points.get(j));
                    last = j;
                }
            }
            out.add(points.get(i));
            from = i;
        }
        return out;
    }

    /** Douglas-Peucker: keeps the points the line between two kept points strays furthest from. */
    private static void simplify(List<BlockPos> points, int a, int b, boolean[] keep) {
        if (b - a < 2) return;
        double worst = 0;
        int at = -1;
        for (int i = a + 1; i < b; i++) {
            double d = offLine(points.get(i), points.get(a), points.get(b));
            if (d > worst) {
                worst = d;
                at = i;
            }
        }
        if (worst <= ROUTE_SLACK) return;
        keep[at] = true;
        simplify(points, a, at, keep);
        simplify(points, at, b, keep);
    }

    private static double offLine(BlockPos p, BlockPos a, BlockPos b) {
        Vec3 ab = Vec3.atLowerCornerOf(b.subtract(a));
        Vec3 ap = Vec3.atLowerCornerOf(p.subtract(a));
        double len = ab.lengthSqr();
        if (len == 0) return ap.length();
        double t = Mth.clamp(ap.dot(ab) / len, 0, 1);
        return ap.subtract(ab.scale(t)).length();
    }

    private static double distance(BlockPos a, BlockPos b) {
        return Math.sqrt(a.distSqr(b));
    }

    /** The block you're standing in, the way "walk to" reads it. */
    private static BlockPos standBlock(LocalPlayer player) {
        BlockPos feet = player.blockPosition();
        if (!Paths.standable(mc.level, feet) && Paths.standable(mc.level, feet.above())) return feet.above();
        return feet;
    }

    // ---- Adding blocks

    /** Something you did that began at {@code from}: the walk that led to it and the pause before it go in first. */
    private void add(MacroStep step, long from) {
        if (macro == null) return;
        flushWalk();
        pause(from);
        if (holdsItem(step.type) && !slotKnown) appendSlot();
        if (facing(step.type) && mc.player != null) look(mc.player);
        append(step);
        lastAt = Math.max(lastAt, now());
        switch (step.type) {
            case MINE, PLACE, USE_ON -> {
                lookYaw = Float.NaN;
                turned = true;
            }
            default -> {
            }
        }
    }

    /** Blocks whose result depends on what's in your hand: the slot goes in before the first one. */
    private static boolean holdsItem(MacroStep.Type type) {
        return switch (type) {
            case MINE, PLACE, USE, ATTACK, DROP, SWAP_HANDS, HOLD -> true;
            default -> false;
        };
    }

    /** Clicks on air or a mob, which land wherever you're facing. */
    private static boolean facing(MacroStep.Type type) {
        return type == MacroStep.Type.ATTACK || type == MacroStep.Type.USE;
    }

    private void pause(long from) {
        if (added == 0) return;
        long gap = from - lastAt;
        if (gap < MIN_PAUSE) return;
        MacroStep wait = new MacroStep(MacroStep.Type.WAIT);
        wait.set("seconds", Math.round(gap / 50.0) * 0.05);
        append(wait);
    }

    /** A "face" block, unless the replay will be facing that way already. */
    private void look(LocalPlayer player) {
        if (!Float.isNaN(lookYaw) && !turnedFrom(player, lookYaw, lookPitch, LOOK_SLACK)) return;
        MacroStep look = new MacroStep(MacroStep.Type.LOOK);
        look.set("yaw", (double) Mth.wrapDegrees(player.getYRot()));
        look.set("pitch", (double) player.getXRot());
        append(look);
        lookYaw = (float) look.number("yaw");
        lookPitch = (float) look.number("pitch");
        turned = false;
    }

    private static boolean turnedFrom(LocalPlayer player, float yaw, float pitch, float slack) {
        return Math.abs(Mth.wrapDegrees(player.getYRot() - yaw)) > slack || Math.abs(player.getXRot() - pitch) > slack;
    }

    private void slotChanged() {
        // A run of scrolling is one block: only the slot you end on matters.
        if (!macro.steps.isEmpty() && macro.steps.getLast().type == MacroStep.Type.SLOT && slotKnown) {
            macro.steps.getLast().set("slot", (double) (slot + 1));
            return;
        }
        appendSlot();
    }

    private void appendSlot() {
        MacroStep step = new MacroStep(MacroStep.Type.SLOT);
        step.set("slot", (double) (slot + 1));
        append(step);
        slotKnown = true;
    }

    private void append(MacroStep step) {
        if (macro == null) return;
        if (macro.size() + step.size() > Macro.MAX_BLOCKS) {
            end("The macro is full (" + Macro.MAX_BLOCKS + " blocks)");
            return;
        }
        macro.steps.add(step);
        added += step.size();
    }

    /** An item you were holding right click on has stopped: long enough, and the click becomes a hold. */
    private void finishUse(long t) {
        MacroStep click = using;
        using = null;
        if (click == null || macro == null || t - usingSince < HOLD_MIN) return;
        int i = macro.steps.lastIndexOf(click);
        if (i < 0) return;
        MacroStep hold = new MacroStep(MacroStep.Type.HOLD);
        hold.set("key", "Use");
        hold.set("seconds", Math.round((t - usingSince) / 50.0) * 0.05);
        macro.steps.set(i, hold);
        lastAt = Math.max(lastAt, t);
    }

    // ---- What happened, from the mixins

    /** Vanilla's left click handler is running: an entity hit now is your click. */
    public void userAttack(boolean running) {
        userAttack = running;
    }

    /** Vanilla's right click handler is running: what gets used now is your click. Once per click. */
    public void userUse(boolean running) {
        userUse = running;
        if (running) usedThisClick = false;
    }

    /** Mining counts when it's your click, or your attack key held down. */
    private boolean yourMining() {
        return macro != null && (userAttack || mc.options.keyAttack.isDown());
    }

    public void startedMining(BlockPos pos) {
        if (!yourMining()) return;
        miningPos = pos.immutable();
        miningSince = now();
    }

    public void broke(BlockPos pos) {
        if (!yourMining()) return;
        long from = pos.equals(miningPos) ? miningSince : now();
        miningPos = null;
        MacroStep step = new MacroStep(MacroStep.Type.MINE);
        step.set("pos", pos);
        add(step, from);
    }

    /** Before a right click on a block: what's in the spot a block would go, to see afterwards whether one did. */
    public void beforeUseOn(BlockHitResult hit) {
        if (macro == null || !userUse || mc.level == null) return;
        BlockPos clicked = hit.getBlockPos();
        placeAt = mc.level.getBlockState(clicked).canBeReplaced() ? clicked : clicked.relative(hit.getDirection());
        placeBefore = mc.level.getBlockState(placeAt);
    }

    public void usedOn(BlockHitResult hit, InteractionResult result) {
        BlockPos at = placeAt;
        placeAt = null;
        if (macro == null || !userUse || usedThisClick || at == null || result == null || !result.consumesAction()) return;
        usedThisClick = true;
        BlockState after = mc.level.getBlockState(at);
        MacroStep step;
        if (placeBefore.canBeReplaced() && !after.equals(placeBefore) && !after.isAir()) {
            step = new MacroStep(MacroStep.Type.PLACE);
            step.set("pos", at);
            if (!hit.getBlockPos().equals(at)) step.set("side", sideName(hit.getDirection().getOpposite()));
        } else {
            step = new MacroStep(MacroStep.Type.USE_ON);
            step.set("pos", hit.getBlockPos());
        }
        add(step, now());
    }

    /** Which neighbour a placed block went against, the way "place block" names it. */
    private static String sideName(Direction toSupport) {
        return switch (toSupport) {
            case DOWN -> "Below";
            case UP -> "Above";
            case NORTH -> "North";
            case SOUTH -> "South";
            case WEST -> "West";
            case EAST -> "East";
        };
    }

    /** Right click with an item in the air (or on a block that had nothing to do with it). */
    public void usedItem(InteractionResult result) {
        if (macro == null || !userUse || usedThisClick || result == null || !result.consumesAction()) return;
        usedThisClick = true;
        MacroStep step = new MacroStep(MacroStep.Type.USE);
        add(step, now());
        if (mc.player != null && mc.player.isUsingItem()) {
            using = step;
            usingSince = now();
        }
    }

    /** Right click on a mob, a villager, an item frame. */
    public void usedEntity(InteractionResult result) {
        if (macro == null || !userUse || usedThisClick || result == null || !result.consumesAction()) return;
        usedThisClick = true;
        add(new MacroStep(MacroStep.Type.USE), now());
    }

    public void attacked() {
        if (macro == null || !userAttack) return;
        add(new MacroStep(MacroStep.Type.ATTACK), now());
    }

    /** A click on a slot of the menu you have open. Drags, double clicks and Q on a slot have no block. */
    public void clicked(int containerId, int slotId, int button, ClickType type) {
        if (macro == null) return;
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen) || screen instanceof CreativeModeInventoryScreen
            || screen.getMenu().containerId != containerId) return;
        MacroStep step = null;
        if (slotId >= 0 && slotId <= 89) {
            switch (type) {
                case PICKUP -> {
                    step = new MacroStep(MacroStep.Type.CLICK_SLOT);
                    step.set("button", button == 1 ? "Right" : "Left");
                }
                case QUICK_MOVE -> {
                    step = new MacroStep(MacroStep.Type.CLICK_SLOT);
                    step.set("button", "Shift");
                }
                case SWAP -> {
                    if (button >= 0 && button <= 8 || button == 40) {
                        step = new MacroStep(MacroStep.Type.MENU_SWAP);
                        step.set("hotbar", MacroStep.HOTBAR_KEYS[button == 40 ? 9 : button]);
                    }
                }
                default -> {
                }
            }
        }
        if (step == null) {
            skipped++;
            return;
        }
        step.set("slot", (double) slotId);
        add(step, now());
    }

    /** You closed the menu yourself; the server closing it doesn't come through here. */
    public void closedMenu() {
        if (macro == null || !(mc.screen instanceof AbstractContainerScreen<?>) || mc.screen instanceof CreativeModeInventoryScreen) return;
        add(new MacroStep(MacroStep.Type.CLOSE), now());
    }

    public void dropped(boolean wholeStack) {
        if (macro == null || mc.screen != null) return;
        MacroStep step = new MacroStep(MacroStep.Type.DROP);
        step.set("amount", wholeStack ? "Whole stack" : "One");
        add(step, now());
    }

    /**
     * A key pressed in game. F is "swap hands"; any key whose job no other block does goes in as
     * "hold down key", which becomes "press key" if you let go of it straight away.
     */
    public void keyPressed(int key) {
        if (macro == null || key == macro.key) return;
        if (bound(mc.options.keySwapOffhand, key)) {
            add(new MacroStep(MacroStep.Type.SWAP_HANDS), now());
            return;
        }
        if (!keyBlock(key)) return;
        MacroStep step = new MacroStep(MacroStep.Type.KEY_DOWN);
        step.set("key", MacroStep.keyName(InputConstants.Type.KEYSYM.getOrCreate(key)));
        long t = now();
        add(step, t);
        if (macro != null) held.put(key, new Held(step, t));
    }

    /** A key let go of, menu open or not. */
    public void keyReleased(int key) {
        Held down = held.remove(key);
        if (macro == null || down == null) return;
        int i = macro.steps.lastIndexOf(down.step());
        if (i < 0) return;
        long t = now();
        if (i == macro.steps.size() - 1 && t - down.at() < HOLD_MIN) {
            MacroStep tap = new MacroStep(MacroStep.Type.KEY_PRESS);
            tap.set("key", down.step().text("key"));
            macro.steps.set(i, tap);
            return;
        }
        MacroStep up = new MacroStep(MacroStep.Type.KEY_UP);
        up.set("key", down.step().text("key"));
        add(up, t);
    }

    /** Whether a key goes in as a key block: not one another block covers, or that isn't part of playing. */
    private static boolean keyBlock(int key) {
        if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_F1 || key == GLFW.GLFW_KEY_F3 || key == GLFW.GLFW_KEY_F11) return false;
        Options o = mc.options;
        // Walking, jumping, slots, chat, the inventory, dropping and clicks have their own blocks.
        for (KeyMapping m : new KeyMapping[] {o.keyUp, o.keyDown, o.keyLeft, o.keyRight, o.keyJump, o.keyInventory, o.keyDrop,
            o.keyChat, o.keyCommand, o.keySwapOffhand, o.keyAttack, o.keyUse, CyaniteClientMod.clickGuiKey()}) {
            if (bound(m, key)) return false;
        }
        for (KeyMapping m : o.keyHotbarSlots) if (bound(m, key)) return false;
        for (Module m : Modules.getAll()) if (m.bind().isBound() && m.bind().get() == key) return false;
        for (Macro m : Macros.list()) if (m.key == key) return false;
        return true;
    }

    private static boolean bound(KeyMapping mapping, int key) {
        InputConstants.Key bound = KeyBindingHelper.getBoundKeyOf(mapping);
        return bound.getType() == InputConstants.Type.KEYSYM && bound.getValue() == key;
    }

    /** A module's keybind switched it. */
    public void moduleKey(Module module) {
        // The key that stops recording isn't part of it, even when a module shares it.
        if (macro == null || module.bind().get() == macro.key) return;
        MacroStep step = new MacroStep(MacroStep.Type.MODULE);
        step.set("module", module.name);
        step.set("state", module.isWanted() ? "On" : "Off");
        add(step, now());
    }

    /** Chat or a command you typed. */
    public void typed(String text) {
        if (macro == null || text == null || text.isBlank()) return;
        MacroStep step = new MacroStep(MacroStep.Type.SAY);
        step.set("text", text.strip());
        add(step, now());
    }
}
