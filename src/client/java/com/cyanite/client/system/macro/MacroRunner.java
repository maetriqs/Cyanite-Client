package com.cyanite.client.system.macro;

import com.cyanite.client.gui.ClickGuiScreen;
import com.cyanite.client.modules.world.area.AreaPlan;
import com.cyanite.client.modules.world.area.Paths;
import com.cyanite.client.modules.world.area.Region;
import com.cyanite.client.system.HumanAim;
import com.cyanite.client.system.Keys;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.Notify;
import com.cyanite.client.system.Steer;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Runs one macro at a time, a step per tick at most. Movement goes through the same steering
 * hook Auto Mine uses (KeyboardInputMixin), looking through {@link HumanAim}, and mining through
 * vanilla's own held attack (MinecraftMixin), so a macro moves and clicks like a player does.
 * Steps that need the world pause while a menu is open; menu steps wait for one.
 */
public final class MacroRunner {
    private static final Minecraft mc = Minecraft.getInstance();
    private static final long LOOK_TIMEOUT = 3000;
    private static final long MINE_TIMEOUT = 20_000;
    private static final long USE_TIMEOUT = 5000;
    private static final long MENU_TIMEOUT = 4000;
    private static final long STUCK_MS = 1500;

    private final HumanAim aim = new HumanAim();

    private final java.util.Deque<Frame> frames = new java.util.ArrayDeque<>();
    private final Map<String, Double> vars = new java.util.HashMap<>();
    private final java.util.Random random = new java.util.Random();

    private Macro macro;
    private LocalPlayer owner;
    private MacroStep current;
    private MacroStep waiting;
    private long waitingSince;
    private int round;
    private long stepStart;
    private int stepTicks;
    private Input steering;
    private boolean holdAttack;
    private boolean holdingUse;
    private long nextSayAt;
    private String lastProblem;

    // The current step's own state.
    private Vec3 walkTarget;
    private Vec3 walkDir;
    private List<BlockPos> path;
    private int pathIndex;
    private BlockPos goal;
    private long deadline;
    private Vec3 lastPos;
    private long lastMoveAt;
    private int actedAt;
    private int menuSeenAt;
    private boolean looking;
    private volatile boolean chatMatched;
    private String chatNeedle;

    /** Where the runner is in one list of blocks: the macro itself, or the inside of a loop or if. */
    private static final class Frame {
        final List<MacroStep> list;
        final MacroStep owner;
        int index;
        int counter;

        Frame(List<MacroStep> list, MacroStep owner) {
            this.list = list;
            this.owner = owner;
        }
    }

    public boolean running() {
        return macro != null;
    }

    public Macro macro() {
        return macro;
    }

    /** The block running right now (an action, or a "wait until"), or null. */
    public MacroStep current() {
        return macro == null ? null : current != null ? current : waiting;
    }

    public int round() {
        return round;
    }

    /** The macro's variables, as they stand. */
    public Map<String, Double> variables() {
        return Collections.unmodifiableMap(vars);
    }

    /** Why the last run stopped early, until the next one starts. */
    public String lastProblem() {
        return lastProblem;
    }

    public void start(Macro next) {
        stop();
        if (mc.player == null || mc.level == null || next == null) return;
        macro = next;
        owner = mc.player;
        frames.clear();
        frames.push(new Frame(next.steps, null));
        vars.clear();
        round = 0;
        current = null;
        waiting = null;
        lastProblem = null;
        aim.speed = 1.2;
        aim.randomness = 0.35;
    }

    /** Stops wherever it's got to and lets go of everything it was holding. */
    public void stop() {
        macro = null;
        current = null;
        waiting = null;
        frames.clear();
        steering = null;
        holdAttack = false;
        if (holdingUse) {
            Keys.restore(mc.options.keyUse);
            holdingUse = false;
        }
        aim.clear();
        looking = false;
    }

    private void fail(String problem) {
        String name = macro == null ? "Macro" : macro.name;
        stop();
        lastProblem = problem;
        Notify.send("Macro stopped", name + ": " + problem, true, false, false);
    }

    /** How the macro wants the player to move this tick, or null when it isn't moving them. */
    public Input steering() {
        return macro == null ? null : steering;
    }

    /** Whether vanilla should treat attack as held: while mining or holding the attack key. */
    public boolean shouldAttack() {
        return macro != null && holdAttack && mc.screen == null;
    }

    /** A chat line arrived; a "wait for chat" block may be waiting for it. */
    public void onChat(String line) {
        if (macro == null || current == null || current.type != MacroStep.Type.WAIT_CHAT) return;
        String plain = ChatFormatting.stripFormatting(line);
        if (plain == null) return;
        if (chatNeedle.isEmpty() || plain.toLowerCase(Locale.ROOT).contains(chatNeedle)) chatMatched = true;
    }

    public void frame() {
        if (macro == null || !looking || mc.screen != null) {
            aim.idle();
            return;
        }
        aim.frame();
    }

    /**
     * One tick of the script. An action that's under way gets its tick; otherwise control blocks
     * are worked through until the next action starts. A loop going round again always waits for
     * the next tick, the way Scratch does, so even an empty "forever" can't freeze the game.
     */
    public void tick() {
        if (macro == null) return;
        LocalPlayer player = mc.player;
        if (player == null || player != owner) {
            fail("You died or changed worlds");
            return;
        }
        long now = System.currentTimeMillis();
        if (current != null) {
            stepTicks++;
            if (run(current, player, now)) finishAction();
            return;
        }
        for (int budget = 0; budget < 200 && macro != null; budget++) {
            Frame f = frames.peek();
            if (f == null) {
                endRound();
                return;
            }
            if (f.index >= f.list.size()) {
                if (loopAgain(f)) return;
                frames.pop();
                continue;
            }
            MacroStep b = f.list.get(f.index);
            switch (b.type) {
                case REPEAT -> {
                    f.index++;
                    if (b.number("times") >= 1) frames.push(new Frame(b.body, b));
                }
                case FOREVER -> {
                    f.index++;
                    frames.push(new Frame(b.body, b));
                }
                case IF -> {
                    f.index++;
                    if (test(b.slots[0], player)) frames.push(new Frame(b.body, b));
                }
                case IF_ELSE -> {
                    f.index++;
                    frames.push(new Frame(test(b.slots[0], player) ? b.body : b.elseBody, b));
                }
                case REPEAT_UNTIL -> {
                    f.index++;
                    if (!test(b.slots[0], player)) frames.push(new Frame(b.body, b));
                }
                case WAIT_UNTIL -> {
                    if (waiting != b) {
                        waiting = b;
                        waitingSince = now;
                    }
                    if (test(b.slots[0], player)) {
                        waiting = null;
                        f.index++;
                        continue;
                    }
                    double timeout = b.number("timeout");
                    if (timeout > 0 && now - waitingSince > timeout * 1000) {
                        fail("Waited " + (long) timeout + "s and " + (b.slots[0] == null ? "no condition was set" : b.slots[0].summary() + " never came true"));
                    }
                    return;
                }
                case STOP -> {
                    stop();
                    return;
                }
                case SET_VAR -> {
                    vars.put(varName(b), b.number("value"));
                    f.index++;
                }
                case CHANGE_VAR -> {
                    vars.merge(varName(b), b.number("value"), Double::sum);
                    f.index++;
                }
                default -> {
                    if (b.type.isCondition()) {
                        // A condition on its own does nothing.
                        f.index++;
                        continue;
                    }
                    beginAction(b, player, now);
                    if (macro != null && current != null && run(current, player, now)) finishAction();
                    return;
                }
            }
        }
    }

    /** At the end of a loop's blocks: go round again? Going round waits for the next tick. */
    private boolean loopAgain(Frame f) {
        if (f.owner == null) return false;
        LocalPlayer player = mc.player;
        switch (f.owner.type) {
            case REPEAT -> {
                f.counter++;
                if (f.counter < (int) f.owner.number("times")) {
                    f.index = 0;
                    return true;
                }
                return false;
            }
            case FOREVER -> {
                f.index = 0;
                return true;
            }
            case REPEAT_UNTIL -> {
                if (!test(f.owner.slots[0], player)) {
                    f.index = 0;
                    return true;
                }
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    private void endRound() {
        round++;
        if (macro.repeat == 0 || round < macro.repeat) {
            frames.clear();
            frames.push(new Frame(macro.steps, null));
        } else {
            stop();
        }
    }

    private void beginAction(MacroStep b, LocalPlayer player, long now) {
        current = b;
        stepStart = now;
        stepTicks = 1;
        steering = null;
        holdAttack = false;
        begin(b, player, now);
    }

    private void finishAction() {
        if (macro == null) return;
        steering = null;
        holdAttack = false;
        if (holdingUse) {
            Keys.restore(mc.options.keyUse);
            holdingUse = false;
        }
        looking = false;
        aim.clear();
        current = null;
        Frame f = frames.peek();
        if (f != null) f.index++;
    }

    private static String varName(MacroStep b) {
        String name = b.text("var").strip();
        return name.isEmpty() ? "count" : name;
    }

    /** "{name}" in a message becomes the variable's value. */
    private String fillIn(String text) {
        if (vars.isEmpty() || text.indexOf('{') < 0) return text;
        String out = text;
        for (Map.Entry<String, Double> e : vars.entrySet()) {
            double v = e.getValue();
            String shown = v == Math.rint(v) ? Long.toString((long) v) : String.format(Locale.ROOT, "%.2f", v);
            out = out.replace("{" + e.getKey() + "}", shown);
        }
        return out;
    }

    // ---- Conditions

    /** Whether a condition block is true right now. An empty slot is false. */
    private boolean test(MacroStep c, LocalPlayer player) {
        if (c == null || player == null) return false;
        return switch (c.type) {
            case AND -> test(c.slots[0], player) && test(c.slots[1], player);
            case OR -> test(c.slots[0], player) || test(c.slots[1], player);
            case NOT -> !test(c.slots[0], player);
            case CHANCE -> random.nextDouble() * 100 < c.number("percent");
            case COMPARE_VAR -> compare(vars.getOrDefault(varName(c), 0.0), c.text("op"), c.number("value"));
            case HEALTH -> compare(player.getHealth(), c.text("op"), c.number("amount"));
            case HUNGER -> compare(player.getFoodData().getFoodLevel(), c.text("op"), c.number("amount"));
            case Y_LEVEL -> compare(player.getY(), c.text("op"), c.number("amount"));
            case INVENTORY_FULL -> player.getInventory().getFreeSlot() < 0;
            case HOLDING -> matchesItem(player.getMainHandItem(), c.text("item"));
            case HAS_ITEM -> countItems(player, c.text("item")) >= c.number("count");
            case BLOCK_IS -> c.pos("pos") != null && matchesBlock(mc.level.getBlockState(c.pos("pos")), c.text("block"));
            case LOOKING_AT -> mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                && matchesBlock(mc.level.getBlockState(hit.getBlockPos()), c.text("block"));
            case MENU_OPEN -> mc.screen instanceof AbstractContainerScreen<?>;
            case MENU_TITLE -> mc.screen != null && mc.screen.getTitle().getString().toLowerCase(Locale.ROOT)
                .contains(c.text("text").strip().toLowerCase(Locale.ROOT));
            case CHAT_SAID -> com.cyanite.client.system.Macros.chatSaid(c.text("text"), (long) (c.number("seconds") * 1000));
            case PLAYER_NEAR -> !mc.level.getEntitiesOfClass(net.minecraft.world.entity.player.Player.class,
                player.getBoundingBox().inflate(c.number("range")), p -> p != player && p.distanceTo(player) <= c.number("range")).isEmpty();
            case MOB_NEAR -> !mc.level.getEntities(player, player.getBoundingBox().inflate(c.number("range")),
                e -> e instanceof net.minecraft.world.entity.monster.Enemy && e.isAlive() && e.distanceTo(player) <= c.number("range")).isEmpty();
            case ON_GROUND -> player.onGround();
            case IN_WATER -> player.isInWater();
            case MODULE_ON -> {
                Module module = module(c.text("module"));
                yield module != null && module.isEnabled();
            }
            default -> false;
        };
    }

    private static boolean compare(double a, String op, double b) {
        return switch (op) {
            case "<" -> a < b;
            case "<=" -> a <= b;
            case "=" -> Math.abs(a - b) < 1e-9;
            case ">=" -> a >= b;
            case ">" -> a > b;
            case "!=" -> Math.abs(a - b) >= 1e-9;
            default -> false;
        };
    }

    /**
     * By id or by name. A whole item id means just that item ("diamond" isn't a diamond pickaxe);
     * anything else matches as part of a name ("pickaxe" is any pickaxe). Blank is anything held.
     */
    private static boolean matchesItem(net.minecraft.world.item.ItemStack stack, String query) {
        if (stack.isEmpty()) return false;
        String q = query.strip().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (q.isEmpty()) return true;
        net.minecraft.resources.Identifier key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        net.minecraft.resources.Identifier exact = net.minecraft.resources.Identifier.tryParse(q);
        if (exact != null && net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(exact)) return key.equals(exact);
        return key.getPath().contains(q) || stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(query.strip().toLowerCase(Locale.ROOT));
    }

    private static int countItems(LocalPlayer player, String query) {
        int n = 0;
        net.minecraft.world.entity.player.Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            net.minecraft.world.item.ItemStack stack = inventory.getItem(i);
            if (matchesItem(stack, query)) n += stack.getCount();
        }
        return n;
    }

    /** Same rule as items: a whole block id means just that block ("stone" isn't redstone ore). */
    private static boolean matchesBlock(net.minecraft.world.level.block.state.BlockState state, String query) {
        String q = query.strip().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (q.isEmpty()) return !state.isAir();
        net.minecraft.resources.Identifier key = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
        net.minecraft.resources.Identifier exact = net.minecraft.resources.Identifier.tryParse(q);
        if (exact != null && net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(exact)) return key.equals(exact);
        return key.getPath().contains(q) || state.getBlock().getName().getString().toLowerCase(Locale.ROOT).contains(query.strip().toLowerCase(Locale.ROOT));
    }

    // ---- Steps

    private void begin(MacroStep step, LocalPlayer player, long now) {
        actedAt = -1;
        menuSeenAt = -1;
        lastPos = player.position();
        lastMoveAt = now;
        switch (step.type) {
            case WALK -> {
                double yaw = Math.toRadians(player.getYRot());
                Vec3 forward = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
                Vec3 left = new Vec3(Math.cos(yaw), 0, Math.sin(yaw));
                walkDir = switch (step.text("dir")) {
                    case "Back" -> forward.scale(-1);
                    case "Left" -> left;
                    case "Right" -> left.scale(-1);
                    default -> forward;
                };
                walkTarget = player.position().add(walkDir.scale(step.number("blocks")));
            }
            case WALK_TO -> planWalk(step, player, now);
            case LOOK -> lookAt(direction(step.number("yaw"), step.number("pitch"), player));
            case TURN -> {
                double degrees = step.number("degrees") * (step.text("dir").equals("Left") ? -1 : 1);
                lookAt(direction(player.getYRot() + degrees, player.getXRot(), player));
            }
            case LOOK_AT, MINE, USE_ON -> {
                BlockPos pos = step.pos("pos");
                if (pos == null) {
                    fail("\"" + step.summary() + "\" has no block set");
                    return;
                }
                lookAt(visiblePoint(pos, player));
            }
            case WAIT_CHAT -> {
                chatNeedle = step.text("text").strip().toLowerCase(Locale.ROOT);
                chatMatched = false;
            }
            default -> {
            }
        }
    }

    /** @return true once the step is done. */
    private boolean run(MacroStep step, LocalPlayer player, long now) {
        boolean screen = mc.screen != null;
        long elapsed = now - stepStart;
        switch (step.type) {
            case SAY -> {
                if (now < nextSayAt) return false;
                ClientPacketListener connection = mc.getConnection();
                if (connection == null) {
                    fail("Not connected");
                    return false;
                }
                String text = fillIn(step.text("text")).strip();
                if (text.startsWith("/")) connection.sendCommand(text.substring(1));
                else if (!text.isEmpty()) connection.sendChat(text);
                nextSayAt = now + com.cyanite.client.system.Macros.gapMs();
                return true;
            }
            case WAIT -> {
                return elapsed >= step.number("seconds") * 1000;
            }
            case WAIT_CHAT -> {
                if (chatMatched) return true;
                if (elapsed > step.number("timeout") * 1000) {
                    fail("No chat line with \"" + step.text("text") + "\" after " + (long) step.number("timeout") + "s");
                }
                return false;
            }
            case WALK -> {
                if (screen) return pause();
                double left = walkTarget.subtract(player.position()).dot(walkDir);
                // Letting go of the key you still slide about 1.2 ticks' worth, so stop that much short.
                double speed = player.getDeltaMovement().horizontalDistance();
                if (left <= 0.05 + speed * 1.2) return true;
                if (stuck(player, now)) return true;
                steering = Steer.toward(player, walkTarget, jumpNeeded(player), false);
                return false;
            }
            case WALK_TO -> {
                if (screen) return pause();
                return walkTo(player, now);
            }
            case JUMP -> {
                if (screen) return pause();
                if (actedAt < 0) {
                    if (player.onGround()) {
                        steering = new Input(false, false, false, false, true, false, false);
                        actedAt = stepTicks;
                    } else if (elapsed > 1000) {
                        return true;
                    }
                    return false;
                }
                steering = null;
                return stepTicks - actedAt >= 3;
            }
            case HOLD -> {
                if (screen) return pause();
                if (elapsed >= step.number("seconds") * 1000) return true;
                String key = step.text("key");
                switch (key) {
                    case "Attack" -> holdAttack = true;
                    case "Use" -> {
                        mc.options.keyUse.setDown(true);
                        holdingUse = true;
                    }
                    default -> steering = new Input(key.equals("Forward"), key.equals("Back"), key.equals("Left"), key.equals("Right"),
                        key.equals("Jump"), key.equals("Sneak"), key.equals("Sprint"));
                }
                return false;
            }
            case LOOK, TURN, LOOK_AT -> {
                if (screen) return false;
                return aim.error() < 0.6 || elapsed > LOOK_TIMEOUT;
            }
            case ATTACK -> {
                return click(mc.options.keyAttack, screen);
            }
            case USE -> {
                return click(mc.options.keyUse, screen);
            }
            case MINE -> {
                if (screen) return pause();
                BlockPos pos = step.pos("pos");
                if (!AreaPlan.diggable(mc.level, pos, mc.level.getBlockState(pos))) return true;
                if (!inReach(player, pos)) {
                    fail("Too far to mine " + coords(pos));
                    return false;
                }
                holdAttack = onBlock(pos) && aim.error() < 3;
                if (elapsed > 1500 && !onBlock(pos) && aim.error() < 1) lookAt(visiblePoint(pos, player));
                if (elapsed > MINE_TIMEOUT) fail("Couldn't break the block at " + coords(pos));
                return false;
            }
            case USE_ON -> {
                if (screen && actedAt < 0) return pause();
                BlockPos pos = step.pos("pos");
                if (actedAt >= 0) return stepTicks - actedAt >= 3;
                if (!inReach(player, pos)) {
                    fail("Too far to use " + coords(pos));
                    return false;
                }
                if (onBlock(pos) && aim.error() < 3) {
                    KeyMapping.click(KeyBindingHelper.getBoundKeyOf(mc.options.keyUse));
                    actedAt = stepTicks;
                    looking = false;
                    return false;
                }
                if (elapsed > USE_TIMEOUT) fail("Couldn't get the crosshair on " + coords(pos));
                return false;
            }
            case SLOT -> {
                player.getInventory().setSelectedSlot((int) step.number("slot") - 1);
                return true;
            }
            case DROP -> {
                player.drop(!step.text("amount").equals("One"));
                return true;
            }
            case CLICK_SLOT -> {
                return clickSlot(step, player, elapsed);
            }
            case CLOSE -> {
                if (mc.screen instanceof AbstractContainerScreen<?>) player.closeContainer();
                else if (mc.screen != null && !(mc.screen instanceof ClickGuiScreen) && !(mc.screen instanceof com.cyanite.client.gui.BetaGuiScreen)) mc.setScreen(null);
                return true;
            }
            case MODULE -> {
                Module module = module(step.text("module"));
                if (module == null) {
                    fail("No module called \"" + step.text("module") + "\"");
                    return false;
                }
                switch (step.text("state")) {
                    case "On" -> module.setEnabled(true);
                    case "Off" -> module.setEnabled(false);
                    default -> module.toggle();
                }
                return true;
            }
        }
        return true;
    }

    /** Stands still while a menu is open; the step carries on once it closes. */
    private boolean pause() {
        steering = Input.EMPTY;
        holdAttack = false;
        return false;
    }

    private boolean click(KeyMapping key, boolean screen) {
        if (actedAt < 0) {
            if (screen) return false;
            KeyMapping.click(KeyBindingHelper.getBoundKeyOf(key));
            actedAt = stepTicks;
            return false;
        }
        return stepTicks - actedAt >= 2;
    }

    private boolean clickSlot(MacroStep step, LocalPlayer player, long elapsed) {
        if (actedAt >= 0) return stepTicks - actedAt >= 3;
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) {
            if (elapsed > MENU_TIMEOUT) fail("No menu opened to click slot " + (int) step.number("slot") + " in");
            return false;
        }
        // Give the server a moment to fill the menu in before clicking.
        if (menuSeenAt < 0) menuSeenAt = stepTicks;
        if (stepTicks - menuSeenAt < 3) return false;
        AbstractContainerMenu menu = screen.getMenu();
        int slot = (int) step.number("slot");
        if (slot >= menu.slots.size()) {
            fail("This menu has no slot " + slot + " (it has " + menu.slots.size() + ")");
            return false;
        }
        String button = step.text("button");
        ClickType type = button.equals("Shift") ? ClickType.QUICK_MOVE : ClickType.PICKUP;
        mc.gameMode.handleInventoryMouseClick(menu.containerId, slot, button.equals("Right") ? 1 : 0, type, player);
        actedAt = stepTicks;
        return false;
    }

    // ---- Walking

    private void planWalk(MacroStep step, LocalPlayer player, long now) {
        BlockPos pos = step.pos("pos");
        if (pos == null) {
            fail("\"" + step.summary() + "\" has no block set");
            return;
        }
        // Picked with "Look" it's the floor block; stand on top of it.
        goal = !Paths.standable(mc.level, pos) && Paths.standable(mc.level, pos.above()) ? pos.above() : pos;
        path = null;
        pathIndex = 1;
        BlockPos feet = player.blockPosition();
        Region around = Region.of(feet, goal).grow(12, 6);
        if (around.sizeX() <= 200 && around.sizeZ() <= 200) {
            Paths paths = Paths.search(mc.level, feet, around, null, 60_000);
            BlockPos reachable = paths.distance(goal) >= 0 ? goal : nearestReachable(paths, goal);
            if (reachable != null) {
                path = paths.pathTo(reachable);
                goal = reachable;
            }
        }
        double length = path != null ? path.size() : Math.sqrt(feet.distSqr(goal));
        deadline = now + 5000 + (long) (length * 600);
    }

    private static BlockPos nearestReachable(Paths paths, BlockPos goal) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(goal.offset(-2, -2, -2), goal.offset(2, 2, 2))) {
            if (paths.distance(p) < 0) continue;
            double d = p.distSqr(goal);
            if (d < bestDist) {
                bestDist = d;
                best = p.immutable();
            }
        }
        return best;
    }

    private boolean walkTo(LocalPlayer player, long now) {
        if (now > deadline) {
            fail("Couldn't reach " + coords(goal));
            return false;
        }
        Vec3 end = Vec3.atBottomCenterOf(goal);
        if (path == null) {
            // Too far for a route, or none found: head straight there, hopping over what's in the way.
            if (horizontal(player, end) < 0.4 && Math.abs(player.getY() - goal.getY()) < 1.5) return true;
            steering = Steer.toward(player, end, jumpNeeded(player), false);
            faceToward(end.add(0, player.getEyeHeight(), 0));
            return false;
        }
        if (pathIndex >= path.size()) {
            if (horizontal(player, end) < 0.35) return true;
            steering = Steer.toward(player, end, false, false);
            return false;
        }
        BlockPos node = path.get(pathIndex);
        Vec3 target = Vec3.atBottomCenterOf(node);
        boolean up = node.getY() > player.getY() + 0.5;
        if (horizontal(player, target) < 0.3 && (!up || player.getY() > node.getY() - 0.2)) {
            pathIndex++;
            return false;
        }
        BlockPos ahead = path.get(Math.min(path.size() - 1, pathIndex + 2));
        faceToward(Vec3.atBottomCenterOf(ahead).add(0, player.getEyeHeight(), 0));
        boolean jump = player.onGround() && (up && horizontal(player, target) < 1.4 || jumpNeeded(player));
        steering = Steer.toward(player, target, jump, false);
        return false;
    }

    /** Looks where it's walking, like a person does, unless it's right there. */
    private void faceToward(Vec3 point) {
        if (horizontal(mc.player, point) < 1.5) return;
        if (!looking) {
            aim.setTarget(point);
            looking = true;
        } else {
            aim.track(point);
        }
    }

    private boolean stuck(LocalPlayer player, long now) {
        if (player.position().distanceToSqr(lastPos) > 0.0025) {
            lastPos = player.position();
            lastMoveAt = now;
        }
        return now - lastMoveAt > STUCK_MS;
    }

    /** Walking into a one-block step: hop it, the way auto-jump would. */
    private static boolean jumpNeeded(LocalPlayer player) {
        return player.horizontalCollision && player.onGround();
    }

    private static double horizontal(LocalPlayer player, Vec3 point) {
        return Math.hypot(point.x - player.getX(), point.z - player.getZ());
    }

    // ---- Looking

    private void lookAt(Vec3 point) {
        aim.setTarget(point);
        looking = true;
    }

    private static Vec3 direction(double yawDeg, double pitchDeg, LocalPlayer player) {
        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(Math.max(-90, Math.min(90, pitchDeg)));
        Vec3 dir = new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
        return player.getEyePosition().add(dir.scale(64));
    }

    /** A spot on the block the eye can actually see: the face turned most towards you first. */
    private static Vec3 visiblePoint(BlockPos pos, LocalPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 centre = Vec3.atCenterOf(pos);
        Direction[] faces = Direction.values();
        Arrays.sort(faces, Comparator.comparingDouble(d -> -(d.getStepX() * (eye.x - centre.x) + d.getStepY() * (eye.y - centre.y)
            + d.getStepZ() * (eye.z - centre.z))));
        for (Direction face : faces) {
            Vec3 point = centre.add(face.getStepX() * 0.4, face.getStepY() * 0.4, face.getStepZ() * 0.4);
            BlockHitResult hit = mc.level.clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)) return point;
        }
        return centre;
    }

    private static boolean onBlock(BlockPos pos) {
        return mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos);
    }

    private static boolean inReach(LocalPlayer player, BlockPos pos) {
        return player.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) <= player.blockInteractionRange() + 0.8;
    }

    private static Module module(String name) {
        String wanted = name.strip();
        for (Module module : Modules.getAll()) {
            if (module.name.equalsIgnoreCase(wanted)) return module;
        }
        return null;
    }

    private static String coords(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }
}
