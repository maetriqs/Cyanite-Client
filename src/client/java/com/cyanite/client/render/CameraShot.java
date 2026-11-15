package com.cyanite.client.render;

import com.cyanite.client.mixin.CameraAccessor;
import com.cyanite.client.mixin.GameRendererAccessor;
import com.cyanite.client.modules.render.Cinematic;
import com.cyanite.client.system.Culling;
import com.cyanite.client.system.Stealth;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Window;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * A picture of the world from a camera of its own, like Freecam's, taken without your view moving:
 * just before the game draws the world for a frame, it's drawn once more from that camera, copied
 * off, and then your view is drawn over it as usual, so you never see it.
 *
 * <p>The world is drawn from the shot's camera at least twice. The first time only gets the chunk
 * sections it can see built (ones you've never looked at may not be); once the sections the picture is
 * of are built as their blocks are now (SectionBuilds) and faded in, the next is the picture (or after
 * a few seconds regardless). Occlusion
 * culling is off from the request to the picture, as it works out what's hidden from where you are,
 * and vanilla looks for sections to draw over the world's whole height, not just your render distance
 * up and down (SectionOcclusionGraphMixin, SectionTreeMixin). While a shot is drawn ({@link #taking()})
 * the light is full bright and there's no hand, block outline, view bobbing, screen overlay or Render3D
 * shapes, and EntityCulling (if installed) hides nothing. Chunk rendering stays centred on your player,
 * as with Freecam.
 */
public final class CameraShot {
    private static final Logger LOGGER = LoggerFactory.getLogger("Cyanite");
    private static final Minecraft mc = Minecraft.getInstance();

    /** Before the first draw: long enough for the world to drop occlusion culling. */
    private static final long SETTLE_NANOS = 250_000_000L;
    private static final int SETTLE_FRAMES = 3;
    /** How long to wait for the picture's sections to be built before taking it as it is. */
    private static final long BUILD_LIMIT_NANOS = 6_000_000_000L;
    /** While waiting, the sections are asked for again from the shot's camera this often. */
    private static final long ASK_AGAIN_NANOS = 500_000_000L;
    private static final int MAX_QUEUED = 4;
    private static final int MAX_SECTIONS = 64;

    /** Where the camera sits and looks, and its vertical field of view in degrees. */
    public record View(Vec3 position, float yaw, float pitch, float fov) {
    }

    private record Request(View view, List<BlockPos> sections, Consumer<NativeImage> done) {
    }

    private static final ArrayDeque<Request> QUEUE = new ArrayDeque<>();
    private static Request current;
    /** From the first request to the last picture. Read off the render thread, by the section search. */
    private static volatile boolean widened;
    private static long since;
    private static int frames;
    private static long firstDraw;
    private static long lastAsked;
    private static long builtAt;
    private static boolean waited;
    private static ClientLevel level;

    /** The view being drawn right now, or null. */
    private static View drawing;
    /** Asks for the light map to be worked out again on the next draw (it otherwise changes once a tick). */
    private static boolean relight;
    /** Asks vanilla's chunk renderer to pick the visible sections again on the next draw. */
    private static boolean refreshSections;

    private static boolean cullingLooked;
    private static Field cullingSwitch;

    private CameraShot() {
    }

    /**
     * Takes a picture from {@code view} over the next second or two, once the chunk sections holding
     * {@code show} are built. {@code done} gets it on the render thread and must close it. Nothing comes
     * back if the world changes first, Stealth Mode comes on, or an Iris shader pack is drawing the
     * world (it can't be drawn twice in a frame).
     */
    public static void take(View view, Collection<BlockPos> show, Consumer<NativeImage> done) {
        if (QUEUE.size() >= MAX_QUEUED) return;
        // One block from each section.
        LongSet seen = new LongOpenHashSet();
        List<BlockPos> sections = new ArrayList<>();
        for (BlockPos p : show) {
            if (sections.size() < MAX_SECTIONS && seen.add(SectionPos.asLong(p))) sections.add(p.immutable());
        }
        QUEUE.add(new Request(view, sections, done));
        widened = true;
    }

    /** Whether the world is being drawn for a shot right now. */
    public static boolean taking() {
        return drawing != null;
    }

    /** Whether a shot is asked for or under way. */
    public static boolean busy() {
        return current != null || !QUEUE.isEmpty();
    }

    /** Whether vanilla's section tree should reach the world's full height: a shot is asked for or under way. */
    public static boolean widened() {
        return widened;
    }

    /** The shot's field of view while one is drawn. */
    public static float fov() {
        return drawing == null ? 70 : drawing.fov();
    }

    public static boolean takeRelight() {
        boolean was = relight;
        relight = false;
        return was;
    }

    public static boolean takeRefreshSections() {
        boolean was = refreshSections;
        refreshSections = false;
        return was;
    }

    /** From GameRendererMixin, just before the game draws the world for this frame. */
    public static void beforeWorld(GameRenderer renderer, DeltaTracker delta) {
        if (current == null) {
            if (QUEUE.isEmpty()) return;
            start(QUEUE.poll());
        }
        if (mc.level != level || mc.player == null || Stealth.active() || CinematicRenderer.shaderPackOn()) {
            cancel();
            return;
        }
        frames++;
        long now = System.nanoTime();
        if (firstDraw == 0) {
            if (frames < SETTLE_FRAMES || now - since < SETTLE_NANOS) return;
            if (!draw(renderer, delta, current.view(), null)) return;
            firstDraw = lastAsked = now;
            return;
        }
        boolean built = built(current.sections());
        if (!built && now - firstDraw < BUILD_LIMIT_NANOS) {
            waited = true;
            if (now - lastAsked >= ASK_AGAIN_NANOS && draw(renderer, delta, current.view(), null)) lastAsked = now;
            return;
        }
        // Sections built a moment ago fade in: let them finish.
        if (built && waited) {
            if (builtAt == 0) builtAt = now;
            if (now - builtAt < (long) (mc.options.chunkSectionFadeInTime().get() * 1e9)) return;
        }
        if (draw(renderer, delta, current.view(), current.done())) finish();
    }

    private static boolean built(List<BlockPos> sections) {
        for (BlockPos p : sections) if (!SectionBuilds.current(p)) return false;
        return true;
    }

    private static void start(Request request) {
        current = request;
        widened = true;
        frames = 0;
        since = System.nanoTime();
        firstDraw = lastAsked = builtAt = 0;
        waited = false;
        level = mc.level;
        Culling.hold(CameraShot.class);
        // Vanilla only works out what's visible again when told to.
        mc.levelRenderer.needsUpdate();
    }

    private static void finish() {
        current = null;
        level = null;
        widened = !QUEUE.isEmpty();
        Culling.release(CameraShot.class);
        mc.levelRenderer.needsUpdate();
    }

    private static void cancel() {
        QUEUE.clear();
        if (current != null) finish();
        widened = false;
    }

    /**
     * Draws the world from {@code view}'s camera, then puts the camera back. Hands a copy to {@code done}
     * if given. False if it failed, which drops every shot.
     */
    private static boolean draw(GameRenderer renderer, DeltaTracker delta, View view, Consumer<NativeImage> done) {
        Camera camera = renderer.getMainCamera();
        Vec3 position = camera.position();
        float yaw = camera.yRot();
        float pitch = camera.xRot();
        boolean detached = camera.isDetached();
        Boolean culled = entityCulling(false);
        drawing = view;
        try {
            place(renderer, camera, view.position(), view.yaw(), view.pitch(), true, delta);
            renderer.renderLevel(delta);
            if (done != null) Screenshot.takeScreenshot(mc.getMainRenderTarget(), done);
            return true;
        } catch (RuntimeException e) {
            LOGGER.error("Couldn't draw the world from the picture's camera", e);
            cancel();
            return false;
        } finally {
            drawing = null;
            // The world's fog settings sit in a buffer the graphics card may not have read yet: your
            // view's go in the next one.
            ((GameRendererAccessor) renderer).cyanite$fogRenderer().endFrame();
            place(renderer, camera, position, yaw, pitch, detached, delta);
            entityCulling(culled);
        }
    }

    private static void place(GameRenderer renderer, Camera camera, Vec3 position, float yaw, float pitch, boolean detached, DeltaTracker delta) {
        CameraAccessor access = (CameraAccessor) camera;
        access.cyanite$setRotation(yaw, pitch);
        access.cyanite$setPosition(position);
        access.cyanite$setDetached(detached);
        relight = true;
        refreshSections = true;
        // Chunks are drawn relative to the camera's block, which the game hands the graphics card once a frame.
        Window window = mc.getWindow();
        ((GameRendererAccessor) renderer).cyanite$globalSettings().update(window.getWidth(), window.getHeight(),
            mc.options.glintStrength().get(), mc.level.getGameTime(), delta,
            Cinematic.menuBlur(mc.options.getMenuBackgroundBlurriness()), camera,
            mc.options.textureFiltering().get() == TextureFilteringMethod.RGSS);
    }

    /**
     * Sets EntityCulling's on/off switch, if it's installed: it hides chests and the like it thinks you
     * can't see from where you stand. Returns what it was, or null without EntityCulling (or for null).
     */
    private static Boolean entityCulling(Boolean on) {
        if (!cullingLooked) {
            cullingLooked = true;
            try {
                Field f = Class.forName("dev.tr7zw.entityculling.versionless.EntityCullingVersionlessBase").getField("enabled");
                if (Modifier.isStatic(f.getModifiers()) && f.getType() == boolean.class) cullingSwitch = f;
            } catch (ReflectiveOperationException | LinkageError absent) {
                cullingSwitch = null;
            }
        }
        if (cullingSwitch == null || on == null) return null;
        try {
            boolean was = cullingSwitch.getBoolean(null);
            cullingSwitch.setBoolean(null, on);
            return was;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("EntityCulling's switch couldn't be set", e);
        }
    }
}
