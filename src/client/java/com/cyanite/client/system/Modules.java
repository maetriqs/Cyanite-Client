package com.cyanite.client.system;

import com.cyanite.client.modules.combat.AimAssist;
import com.cyanite.client.modules.combat.AntiBot;
import com.cyanite.client.modules.combat.AutoArmor;
import com.cyanite.client.modules.combat.AutoClicker;
import com.cyanite.client.modules.combat.AutoGap;
import com.cyanite.client.modules.combat.AutoLog;
import com.cyanite.client.modules.combat.AutoWeapon;
import com.cyanite.client.modules.combat.BowAimbot;
import com.cyanite.client.modules.combat.Hitboxes;
import com.cyanite.client.modules.combat.Surround;
import com.cyanite.client.modules.combat.AutoTotem;
import com.cyanite.client.modules.combat.Criticals;
import com.cyanite.client.modules.combat.KillAura;
import com.cyanite.client.modules.combat.TriggerBot;
import com.cyanite.client.modules.donut.AutoSell;
import com.cyanite.client.modules.donut.BaseAlert;
import com.cyanite.client.modules.donut.ChunkFinder;
import com.cyanite.client.modules.donut.BountyBalance;
import com.cyanite.client.modules.donut.FakePay;
import com.cyanite.client.modules.donut.DrillLogger;
import com.cyanite.client.modules.donut.DrillTester;
import com.cyanite.client.modules.donut.DepthAlert;
import com.cyanite.client.modules.donut.Relog;
import com.cyanite.client.modules.donut.ScoreboardSpoof;
import com.cyanite.client.modules.donut.SpawnerFinder;
import com.cyanite.client.modules.donut.StaffList;
import com.cyanite.client.modules.combat.Velocity;
import com.cyanite.client.modules.misc.AntiAFK;
import com.cyanite.client.modules.misc.AntiModCheck;
import com.cyanite.client.modules.misc.AntiVanish;
import com.cyanite.client.modules.misc.StealthMode;
import com.cyanite.client.modules.misc.StreamerMode;
import com.cyanite.client.modules.misc.DeathPosition;
import com.cyanite.client.modules.misc.DiscordPresence;
import com.cyanite.client.modules.misc.MessageLogger;
import com.cyanite.client.modules.misc.NameProtect;
import com.cyanite.client.modules.misc.Pets;
import com.cyanite.client.modules.movement.AutoWalk;
import com.cyanite.client.modules.movement.BoatFly;
import com.cyanite.client.modules.movement.Flight;
import com.cyanite.client.modules.movement.Jesus;
import com.cyanite.client.modules.movement.NoClip;
import com.cyanite.client.modules.movement.NoFall;
import com.cyanite.client.modules.movement.NoSlow;
import com.cyanite.client.modules.movement.SafeWalk;
import com.cyanite.client.modules.movement.Speed;
import com.cyanite.client.modules.movement.Spider;
import com.cyanite.client.modules.movement.Sprint;
import com.cyanite.client.modules.movement.Step;
import com.cyanite.client.modules.player.AutoEat;
import com.cyanite.client.modules.player.AutoFish;
import com.cyanite.client.modules.player.AutoRespawn;
import com.cyanite.client.modules.player.ChestStealer;
import com.cyanite.client.modules.player.FastPlace;
import com.cyanite.client.modules.player.InventoryMove;
import com.cyanite.client.modules.render.BlockESP;
import com.cyanite.client.modules.render.Breadcrumbs;
import com.cyanite.client.modules.render.ESP;
import com.cyanite.client.modules.render.Freecam;
import com.cyanite.client.modules.render.Zoom;
import com.cyanite.client.modules.render.Waypoints;
import com.cyanite.client.modules.render.Fullbright;
import com.cyanite.client.modules.render.Nametags;
import com.cyanite.client.modules.render.NoFog;
import com.cyanite.client.modules.render.NoHurtCam;
import com.cyanite.client.modules.render.Search;
import com.cyanite.client.modules.render.StorageESP;
import com.cyanite.client.modules.render.PearlESP;
import com.cyanite.client.modules.render.VillagerESP;
import com.cyanite.client.modules.render.Xray;
import com.cyanite.client.modules.world.AreaMiner;
import com.cyanite.client.modules.world.BedrockBurrow;
import com.cyanite.client.modules.world.ChestIndex;
import com.cyanite.client.modules.world.AutoMine;
import com.cyanite.client.modules.world.SchematicBuilder;
import com.cyanite.client.modules.world.AutoTool;
import com.cyanite.client.modules.world.FastBreak;
import com.cyanite.client.modules.world.FastMine;
import com.cyanite.client.modules.player.HotbarRefill;
import com.cyanite.client.modules.combat.PopCounter;
import com.cyanite.client.modules.world.Scaffold;
import com.cyanite.client.modules.world.Timer;
import com.cyanite.client.render.Render3D;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Central registry of every module, grouped by category. */
public final class Modules {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final List<Module> ALL = new ArrayList<>();
    private static final Map<Category, List<Module>> BY_CATEGORY = new EnumMap<>(Category.class);
    private static final Map<Class<? extends Module>, Module> BY_CLASS = new HashMap<>();

    static {
        for (Category category : Category.values()) {
            BY_CATEGORY.put(category, new ArrayList<>());
        }
    }

    private Modules() {
    }

    public static void init() {
        Module.bindGame(net.minecraft.client.Minecraft.getInstance());
        // First, so a saved Stealth Mode is back on before anything it hides gets switched on.
        register(new StealthMode());
        register(new KillAura());
        register(new Velocity());
        register(new Criticals());
        register(new AutoTotem());
        register(new TriggerBot());
        register(new AutoArmor());
        register(new AimAssist());
        register(new AutoClicker());
        register(new Hitboxes());
        register(new AutoWeapon());
        register(new BowAimbot());
        register(new AutoGap());
        register(new AutoLog());
        register(new Surround());
        register(new AntiBot());

        register(new Sprint());
        register(new Flight());
        register(new BoatFly());
        register(new Speed());
        register(new NoFall());
        register(new Step());
        register(new AutoWalk());
        register(new Jesus());
        register(new Spider());
        register(new NoSlow());
        register(new SafeWalk());
        register(new NoClip());

        register(new Fullbright());
        register(new ESP());
        register(new VillagerESP());
        register(new PearlESP());
        register(new StorageESP());
        register(new Search());
        register(new BlockESP());
        register(new Xray());
        register(new Nametags());
        register(new Freecam());
        register(new Zoom());
        register(new Waypoints());
        register(new Breadcrumbs());
        register(new NoHurtCam());
        register(new NoFog());

        register(new FastPlace());
        register(new AutoRespawn());
        register(new AutoEat());
        register(new AutoFish());
        register(new ChestStealer());
        register(new InventoryMove());

        register(new AutoTool());
        register(new AutoMine());
        register(new SchematicBuilder());
        register(new AreaMiner());
        register(new BedrockBurrow());
        register(new ChestIndex());
        register(new FastBreak());
        register(new FastMine());
        register(new HotbarRefill());
        register(new PopCounter());
        register(new com.cyanite.client.modules.combat.AutoAnchor());
        register(new com.cyanite.client.modules.render.Tracers());
        register(new com.cyanite.client.modules.render.SmallTotem());
        register(new com.cyanite.client.modules.render.F5Camera());
        register(new com.cyanite.client.modules.render.MotionBlur());
        register(new Scaffold());
        register(new Timer());

        register(new AntiAFK());
        register(new MessageLogger());
        register(new NameProtect());
        register(new DiscordPresence());
        register(new DeathPosition());
        register(new AntiVanish());
        register(new AntiModCheck());
        register(new StreamerMode());
        register(new Pets());

        register(new ChunkFinder());
        register(new FakePay());
        register(new ScoreboardSpoof());
        register(new SpawnerFinder());
        register(new Relog());
        register(new DepthAlert());
        register(new BaseAlert());
        register(new AutoSell());
        register(new DrillLogger());
        register(new DrillTester());
        register(new BountyBalance());
        register(new StaffList());
    }

    public static <T extends Module> T register(T module) {
        ALL.add(module);
        BY_CATEGORY.get(module.category).add(module);
        BY_CLASS.put(module.getClass(), module);
        return module;
    }

    @SuppressWarnings("unchecked")
    public static <T extends Module> T get(Class<T> type) {
        return (T) BY_CLASS.get(type);
    }

    public static List<Module> get(Category category) {
        return Collections.unmodifiableList(BY_CATEGORY.get(category));
    }

    public static List<Module> getAll() {
        return Collections.unmodifiableList(ALL);
    }

    public static int enabledCount() {
        int count = 0;
        for (Module module : ALL) {
            if (module.isEnabled()) count++;
        }
        return count;
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        WorldScan.next();

        for (Module module : ALL) {
            if (!module.isEnabled()) continue;
            // One broken module shouldn't take the rest down with it, or crash the game.
            long start = System.nanoTime();
            try {
                module.onTick();
            } catch (RuntimeException e) {
                LOGGER.error("{} threw while ticking, disabling it", module.name, e);
                module.setEnabled(false);
            }
            module.spentNanos += System.nanoTime() - start;
        }
    }

    // ---- What each module costs, for the Performance panel

    private static long windowStart;
    private static long lastFrame;
    private static long worstGap;
    private static int framesInWindow;
    private static int fps;
    private static float worstFrameMs;

    /** Frames last second. */
    public static int fps() {
        return fps;
    }

    /** The longest single frame last second, in ms: a stutter shows here even when the average is fine. */
    public static float worstFrameMs() {
        return worstFrameMs;
    }

    /** Once a frame: counts it, and once a second turns each module's time into its share. */
    private static void measure() {
        long now = System.nanoTime();
        if (lastFrame != 0) worstGap = Math.max(worstGap, now - lastFrame);
        lastFrame = now;
        framesInWindow++;
        if (windowStart == 0) windowStart = now;
        long elapsed = now - windowStart;
        if (elapsed < 1_000_000_000L) return;
        for (Module module : ALL) {
            module.load = module.spentNanos * 100f / elapsed;
            module.spentNanos = 0;
        }
        fps = Math.round(framesInWindow * 1e9f / elapsed);
        worstFrameMs = worstGap / 1e6f;
        framesInWindow = 0;
        worstGap = 0;
        windowStart = now;
    }

    public static void frame() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        WorldScan.next();
        measure();
        for (Module module : ALL) {
            if (!module.isEnabled()) continue;
            long start = System.nanoTime();
            try {
                module.onFrame();
            } catch (RuntimeException e) {
                LOGGER.error("{} threw during a frame, disabling it", module.name, e);
                module.setEnabled(false);
            }
            module.spentNanos += System.nanoTime() - start;
        }
    }

    public static void render3D(Render3D render) {
        for (Module module : ALL) {
            if (!module.isEnabled() && !module.rendersWhileDisabled()) continue;
            long start = System.nanoTime();
            try {
                module.onRender3D(render);
            } catch (RuntimeException e) {
                LOGGER.error("{} threw while rendering, disabling it", module.name, e);
                module.setEnabled(false);
            }
            module.spentNanos += System.nanoTime() - start;
        }
    }

    public static void render2D(GuiGraphics g, float partialTick) {
        for (Module module : ALL) {
            if (!module.isEnabled()) continue;
            long start = System.nanoTime();
            try {
                module.onRender2D(g, partialTick);
            } catch (RuntimeException e) {
                LOGGER.error("{} threw while drawing, disabling it", module.name, e);
                module.setEnabled(false);
            }
            module.spentNanos += System.nanoTime() - start;
        }
    }

    public static void onKey(int key) {
        for (Module module : ALL) {
            if (module.bind().isBound() && module.bind().get() == key) module.toggle();
            else if (module.isEnabled()) module.onKey(key);
        }
    }
}
