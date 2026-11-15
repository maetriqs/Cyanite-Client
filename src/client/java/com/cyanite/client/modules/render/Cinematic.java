package com.cyanite.client.modules.render;

import com.cyanite.client.render.CinematicRenderer;
import com.cyanite.client.render.SpaceSky;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.Stealth;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.KeybindSetting;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.SliderSetting;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

/**
 * Cinematic: the game looks and moves more like film. Mouse turns and the camera ease instead of
 * snapping, the FOV glides, a key zooms in slowly, and the picture gets a grade of its own (motion
 * blur, vignette, saturation, contrast, brightness, warmth, background blur) with the HUD bits you
 * pick out of the way. Not a replay or camera-path tool: it changes how playing looks.
 *
 * <p>Nothing in Options is touched; every hook asks this module and falls back to vanilla, so
 * switching it off leaves your own settings exactly as they were. The picture eases out rather than
 * cutting, and changes to its sliders ease in the same way.
 *
 * <p>Hooks: MouseHandlerMixin eases mouse turns ({@link #easeTurn}); GameRendererMixin takes the
 * FOV ({@link #baseFov}, {@link #fov}), bobbing, the hand and the menu blur from here and calls
 * {@link #afterWorld} once the world's drawn; CameraMixin eases the camera over steps and jumps;
 * GuiMixin and Hud leave out what's hidden; ScreenMixin fades menu backgrounds in; MinecraftMixin
 * says when a menu opens; WeatherEffectRendererMixin widens the rain; LevelRendererMixin hands
 * over the block outline ({@link #aimOutline}, {@link #drawOutline}); SkyRendererMixin swaps in
 * the space sky ({@link #skyFrame}, {@link SpaceSky}) and the fog takes its colour. The picture
 * itself is {@link CinematicRenderer}.
 */
public class Cinematic extends Module {
    /** How long the picture takes to settle on a new setting, or to fade in and out, in seconds. */
    private static final double SETTLE = 0.25;
    /** How long the FOV takes to glide to a new value, in seconds. */
    private static final double FOV_GLIDE = 0.22;
    /** How fast the zoom eases, per second (by ratio, so 2x to 4x feels like 4x to 8x). */
    private static final double ZOOM_EASE = 5;
    /** How long a menu's backdrop takes to fade in, in seconds. */
    private static final double MENU_FADE = 0.22;
    /** The camera never trails your eyes by more than this many blocks. */
    private static final double CAMERA_LAG = 0.6;
    /** How far the background blur looks for something to focus on, in blocks. */
    private static final double FOCUS_RANGE = 96;
    /** The outline glides on from where it was if you looked off blocks for less than this, in nanoseconds. */
    private static final long OUTLINE_MEMORY = 250_000_000L;

    // Camera
    private final BoolSetting smoothCamera = add(new BoolSetting("Smooth camera", true));
    private final SliderSetting smoothness = add(new SliderSetting("Camera smoothness", 50, 0, 100, 5).format(Cinematic::percent));
    private final BoolSetting smoothFov = add(new BoolSetting("Smooth FOV", true));
    private final BoolSetting customFov = add(new BoolSetting("Custom FOV", false));
    private final SliderSetting fov = add(new SliderSetting("FOV", 70, 30, 110, 1).format(v -> (int) v + "°"));
    private final KeybindSetting zoomKey = add(new KeybindSetting("Zoom key", GLFW.GLFW_KEY_C));
    private final SliderSetting zoomStrength = add(new SliderSetting("Zoom strength", 3, 1.5, 10, 0.5).format(Cinematic::times));
    private final SliderSetting bobbing = add(new SliderSetting("Camera bobbing", 50, 0, 200, 5).format(Cinematic::percent));
    // Picture
    private final SliderSetting motionBlur = add(new SliderSetting("Motion blur", 30, 0, 100, 5).format(Cinematic::offOrPercent));
    private final SliderSetting vignette = add(new SliderSetting("Vignette", 40, 0, 100, 5).format(Cinematic::offOrPercent));
    private final SliderSetting saturation = add(new SliderSetting("Saturation", 110, 0, 200, 5).format(Cinematic::percent));
    private final SliderSetting contrast = add(new SliderSetting("Contrast", 110, 50, 150, 5).format(Cinematic::percent));
    private final SliderSetting brightness = add(new SliderSetting("Brightness", 100, 50, 150, 5).format(Cinematic::percent));
    private final SliderSetting temperature = add(new SliderSetting("Temperature", 0, -100, 100, 5).format(Cinematic::warmth));
    private final SliderSetting depthOfField = add(new SliderSetting("Depth of field", 0, 0, 100, 5).format(Cinematic::offOrPercent));
    private final BoolSetting weather = add(new BoolSetting("Enhanced weather", true));
    // Space sky
    private final BoolSetting spaceSky = add(new BoolSetting("Space sky", true));
    private final ModeSetting skyPicture = add(new ModeSetting("Sky picture", SpaceSky.Picture.GALAXY.label, SpaceSky.Picture.labels()));
    private final ColorSetting skyColor = add(new ColorSetting("Sky color", SpaceSky.PICTURE_COLOR));
    private final SliderSetting skyBrightness = add(new SliderSetting("Sky brightness", 100, 25, 200, 5).format(Cinematic::percent));
    private final BoolSetting stars = add(new BoolSetting("Stars", true));
    private final SliderSetting starDensity = add(new SliderSetting("Star density", 50, 5, 100, 5).format(Cinematic::percent));
    private final BoolSetting starFlicker = add(new BoolSetting("Star flicker", true));
    // Block outline
    private final BoolSetting outline = add(new BoolSetting("Block outline", true));
    private final ColorSetting outlineColor = add(new ColorSetting("Outline color", 0x4C9BFF));
    private final SliderSetting outlineOpacity = add(new SliderSetting("Outline opacity", 80, 10, 100, 5).format(Cinematic::percent));
    private final SliderSetting outlineGlide = add(new SliderSetting("Outline glide", 50, 0, 100, 5).format(Cinematic::offOrPercent));
    // Screen
    private final BoolSetting hideCrosshair = add(new BoolSetting("Hide crosshair", true));
    private final BoolSetting hideHand = add(new BoolSetting("Hide hand", false));
    private final BoolSetting hideHotbar = add(new BoolSetting("Hide hotbar", false));
    private final BoolSetting hideChat = add(new BoolSetting("Hide chat", false));
    private final BoolSetting hideEffects = add(new BoolSetting("Hide effects", false));
    private final BoolSetting hideScoreboard = add(new BoolSetting("Hide scoreboard", false));
    private final BoolSetting hideBossBars = add(new BoolSetting("Hide boss bars", false));
    private final BoolSetting hideClientHud = add(new BoolSetting("Hide Cyanite HUD", false));
    private final BoolSetting smoothMenus = add(new BoolSetting("Smooth menus", true));

    /** Mouse movement still to be turned, and this frame's share of it. */
    private double pendingYaw;
    private double pendingPitch;
    private double turnYaw;
    private double turnPitch;

    private long fovFrame;
    private double shownFov = -1;
    private double shownZoom = 1;

    private long cameraFrame;
    private double cameraY = Double.NaN;

    /** When the open menu first showed (-1: opened, not drawn yet; 0: none fading). */
    private long menuOpenedAt;

    /** Indexes into {@link #shown}. */
    private static final int SATURATION = 0, CONTRAST = 1, BRIGHTNESS = 2, TEMPERATURE = 3, VIGNETTE = 4, TRAIL = 5, DEPTH = 6, MOOD = 7;
    private static final double[] NEUTRAL = {1, 1, 1, 0, 0, 0, 0, 0};
    /** The picture on screen this frame, easing toward the settings (or back to plain when off). */
    private final double[] shown = NEUTRAL.clone();
    private final double[] wanted = new double[NEUTRAL.length];

    /** The outline on screen, as a box in the world (min x y z, max x y z) gliding toward the block you look at. */
    private final double[] outlineBox = new double[6];
    private final double[] outlineGoal = new double[6];
    private boolean outlineShown;
    private long outlineFrame;
    private long outlineLostAt;
    private long pictureFrame;
    private double focus = 8;

    /** How much of the space sky shows (0 the game's sky, 1 all space), easing in and out. */
    private double skyShown;
    private long skyFrame;

    public Cinematic() {
        super(Category.RENDER, "Cinematic", "Film look: smooth camera and zoom, motion blur, vignette, color grading, blur, a starry space sky, a gliding block outline, HUD out of the way");
    }

    private static String percent(double v) {
        return Math.round(v) + "%";
    }

    private static String offOrPercent(double v) {
        return v <= 0 ? "Off" : Math.round(v) + "%";
    }

    private static String times(double v) {
        return (v == Math.rint(v) ? Integer.toString((int) v) : String.format(Locale.ROOT, "%.1f", v)) + "x";
    }

    private static String warmth(double v) {
        return v == 0 ? "Neutral" : (v > 0 ? "Warm " : "Cool ") + Math.round(Math.abs(v));
    }

    @Override
    protected void onDisable() {
        pendingYaw = pendingPitch = 0;
        cameraY = Double.NaN;
        outlineShown = false;
        shownZoom = 1;
    }

    private static Cinematic on() {
        Cinematic cinematic = Modules.get(Cinematic.class);
        return cinematic != null && cinematic.isEnabled() ? cinematic : null;
    }

    private static double seconds(long since, long now) {
        return since == 0 ? 0 : Math.min((now - since) / 1e9, 0.1);
    }

    // ---- Camera

    /** Whether mouse turns go through {@link #easeTurn} this frame. */
    public static boolean easesTurns() {
        Cinematic c = on();
        return c != null && c.smoothCamera.get() && c.smoothness.get() > 0;
    }

    /**
     * Takes this frame's mouse movement and hands back a share of everything not yet turned, so a
     * flick glides to a stop instead of snapping. Nothing is lost: it all arrives, just eased.
     * Read the result from {@link #turnYaw()} and {@link #turnPitch()}.
     */
    public static void easeTurn(double yaw, double pitch, double seconds) {
        Cinematic c = Modules.get(Cinematic.class);
        double s = c.smoothness.get() / 100;
        // Squared, so the low end of the slider has room for a light touch.
        double lag = 0.4 * s * s;
        c.pendingYaw += yaw;
        c.pendingPitch += pitch;
        double share = seconds <= 0 ? 0 : 1 - Math.exp(-seconds / lag);
        c.turnYaw = c.pendingYaw * share;
        c.turnPitch = c.pendingPitch * share;
        c.pendingYaw -= c.turnYaw;
        c.pendingPitch -= c.turnPitch;
        if (Math.abs(c.pendingYaw) < 1e-4) c.pendingYaw = 0;
        if (Math.abs(c.pendingPitch) < 1e-4) c.pendingPitch = 0;
    }

    public static double turnYaw() {
        return Modules.get(Cinematic.class).turnYaw;
    }

    public static double turnPitch() {
        return Modules.get(Cinematic.class).turnPitch;
    }

    /**
     * The first-person camera, eased up and down over steps, jumps and landings so they don't jolt.
     * Only height: sideways it stays on your eyes, so what's under the crosshair is what you'd hit.
     * Left alone while flying, gliding, swimming or riding, and never eased into a block.
     * @return where to put the camera, or null to leave it
     */
    public static Vec3 easeCamera(Entity entity, Vec3 eyes, boolean detached) {
        Cinematic c = on();
        long now = System.nanoTime();
        if (c == null || detached || entity != mc.player || !c.smoothCamera.get() || c.smoothness.get() <= 0
            || mc.player.isFallFlying() || mc.player.isPassenger() || mc.player.isInWater() || mc.player.isInLava()
            || mc.player.getAbilities().flying || mc.player.isSleeping()) {
            if (c != null) c.cameraY = Double.NaN;
            return null;
        }
        double dt = seconds(c.cameraFrame, now);
        c.cameraFrame = now;
        double s = c.smoothness.get() / 100;
        double lag = 0.02 + 0.13 * s * s;
        double reach = CAMERA_LAG * Math.max(0.25, s);
        if (Double.isNaN(c.cameraY) || Math.abs(eyes.y - c.cameraY) > 2) {
            c.cameraY = eyes.y;
        } else if (dt > 0) {
            c.cameraY += (eyes.y - c.cameraY) * (1 - Math.exp(-dt / lag));
            c.cameraY = Math.max(eyes.y - reach, Math.min(eyes.y + reach, c.cameraY));
        }
        if (c.cameraY != eyes.y && !(open(eyes.x, c.cameraY - 0.1, eyes.z) && open(eyes.x, c.cameraY + 0.1, eyes.z))) {
            c.cameraY = eyes.y;
        }
        return c.cameraY == eyes.y ? null : new Vec3(eyes.x, c.cameraY, eyes.z);
    }

    /** Whether nothing solid fills that spot, so the camera can sit there without seeing through a block. */
    private static boolean open(double x, double y, double z) {
        BlockPos pos = BlockPos.containing(x, y, z);
        return mc.level.getBlockState(pos).getCollisionShape(mc.level, pos).isEmpty();
    }

    /** The FOV in place of the one in Options, when Custom FOV is on. */
    public static int baseFov(int fov) {
        Cinematic c = on();
        return c != null && c.customFov.get() ? c.fov.get().intValue() : fov;
    }

    /** The world's FOV this frame: zoomed while the key's held, and gliding to any change when Smooth FOV is on. */
    public static float fov(float fov) {
        Cinematic c = Modules.get(Cinematic.class);
        if (c == null) return fov;
        long now = System.nanoTime();
        double dt = seconds(c.fovFrame, now);
        c.fovFrame = now;
        double target = fov / c.zoom(dt);
        if (!c.isEnabled() || !c.smoothFov.get() || c.shownFov <= 0) {
            c.shownFov = target;
        } else if (dt > 0) {
            c.shownFov += (target - c.shownFov) * (1 - Math.exp(-dt / FOV_GLIDE));
            if (Math.abs(c.shownFov - target) < 0.01) c.shownFov = target;
        }
        return (float) c.shownFov;
    }

    /** Eases the zoom toward the strength while the key's held, and back to none. */
    private double zoom(double dt) {
        boolean held = isEnabled() && mc.screen == null && mc.player != null && zoomKey.isBound()
            && !Zoom.handles(zoomKey.get()) && InputConstants.isKeyDown(mc.getWindow(), zoomKey.get());
        double target = held ? zoomStrength.get() : 1;
        if (!isEnabled()) {
            shownZoom = 1;
        } else if (dt > 0) {
            double step = 1 - Math.exp(-dt * ZOOM_EASE);
            shownZoom = Math.exp(Math.log(shownZoom) + (Math.log(target) - Math.log(shownZoom)) * step);
            if (Math.abs(shownZoom - target) < 0.002) shownZoom = target;
        }
        return shownZoom;
    }

    /** Mouse turns slow down with the zoom, so aiming stays steady. */
    public static double turnScale() {
        Cinematic c = on();
        return c == null || c.shownZoom <= 1 ? 1 : 1 / c.shownZoom;
    }

    /** Whether the view bobs as you walk; Camera bobbing above 0 turns it on even if Options has it off. */
    public static boolean bobs(boolean vanilla) {
        Cinematic c = on();
        return c == null ? vanilla : c.bobbing.get() > 0;
    }

    /** How far the view bobs, scaled by Camera bobbing (100% is vanilla's). */
    public static float bob(float bob) {
        Cinematic c = on();
        return c == null ? bob : (float) (bob * c.bobbing.get() / 100);
    }

    // ---- Screen

    public static boolean hidesHand() {
        Cinematic c = on();
        return c != null && c.hideHand.get();
    }

    public static boolean hidesCrosshair() {
        Cinematic c = on();
        return c != null && c.hideCrosshair.get();
    }

    public static boolean hidesHotbar() {
        Cinematic c = on();
        return c != null && c.hideHotbar.get();
    }

    public static boolean hidesChat() {
        Cinematic c = on();
        return c != null && c.hideChat.get();
    }

    public static boolean hidesEffects() {
        Cinematic c = on();
        return c != null && c.hideEffects.get();
    }

    public static boolean hidesScoreboard() {
        Cinematic c = on();
        return c != null && c.hideScoreboard.get();
    }

    public static boolean hidesBossBars() {
        Cinematic c = on();
        return c != null && c.hideBossBars.get();
    }

    public static boolean hidesClientHud() {
        Cinematic c = on();
        return c != null && c.hideClientHud.get();
    }

    /** A menu is about to replace {@code current} (null: none open); one opening over the game fades its backdrop in. */
    public static void screenChanging(Screen current, Screen next) {
        Cinematic c = Modules.get(Cinematic.class);
        if (c == null || next == null) return;
        // The clock starts on the first frame it's drawn: some menus (the creative inventory) take a
        // while to build, and the fade would be over before anyone saw it.
        if (current == null) c.menuOpenedAt = -1;
        // A glide still under way shouldn't carry on once you're back from the menu.
        c.pendingYaw = c.pendingPitch = 0;
    }

    /** How far a menu's backdrop has faded in, 0 to 1 (1 when Smooth menus is off). */
    public static float menuProgress() {
        Cinematic c = on();
        if (c == null || !c.smoothMenus.get() || c.menuOpenedAt == 0 || mc.level == null) return 1;
        if (c.menuOpenedAt < 0) c.menuOpenedAt = System.nanoTime();
        double t = (System.nanoTime() - c.menuOpenedAt) / 1e9 / MENU_FADE;
        if (t >= 1) return 1;
        double eased = 1 - Math.pow(1 - Math.max(0, t), 3);
        return (float) eased;
    }

    /** The menu blur, growing in with the backdrop. */
    public static int menuBlur(int blur) {
        float progress = menuProgress();
        return progress >= 1 ? blur : Math.round(blur * progress);
    }

    /** Rain and snow drawn out to 15 blocks instead of the 10 Options allows at most. */
    public static int weatherRadius(int radius) {
        Cinematic c = on();
        return c != null && c.weather.get() ? Math.max(radius, 15) : radius;
    }

    // ---- Space sky

    /**
     * Once a frame, from SkyRendererMixin as the game sets up its sky: eases the space sky in or out
     * and hands {@link SpaceSky} this frame's look. Only the overworld has it; under an Iris shader
     * pack the pack draws its own sky.
     */
    public static void skyFrame(boolean overworld, float starAngle, float rainBrightness) {
        Cinematic c = Modules.get(Cinematic.class);
        if (c == null) return;
        long now = System.nanoTime();
        double dt = seconds(c.skyFrame, now);
        c.skyFrame = now;
        boolean wanted = c.isEnabled() && c.spaceSky.get() && !CinematicRenderer.shaderPackOn();
        if (!overworld || Stealth.active()) {
            c.skyShown = 0;
        } else {
            c.skyShown += ((wanted ? 1 : 0) - c.skyShown) * (dt <= 0 ? 0 : 1 - Math.exp(-dt / SETTLE));
            if (Math.abs(c.skyShown - (wanted ? 1 : 0)) < 1e-3) c.skyShown = wanted ? 1 : 0;
        }
        SpaceSky.frame((float) c.skyShown, SpaceSky.Picture.of(c.skyPicture.get()), c.skyColor.get(),
            (float) (c.skyBrightness.get() / 100), c.stars.get() ? (float) (c.starDensity.get() / 50) : 0,
            c.starFlicker.get(), starAngle, rainBrightness);
    }

    // ---- Block outline

    /** Once a frame, with the block the game is about to outline (null: none): moves the outline's box toward it. */
    public static void aimOutline(BlockPos pos, VoxelShape shape) {
        Cinematic c = Modules.get(Cinematic.class);
        if (c == null) return;
        long now = System.nanoTime();
        double dt = seconds(c.outlineFrame, now);
        c.outlineFrame = now;
        if (pos == null || shape == null || shape.isEmpty() || !c.isEnabled() || !c.outline.get()) {
            if (c.outlineShown) c.outlineLostAt = now;
            c.outlineShown = false;
            return;
        }
        AABB local = shape.bounds();
        double[] goal = c.outlineGoal;
        goal[0] = pos.getX() + local.minX;
        goal[1] = pos.getY() + local.minY;
        goal[2] = pos.getZ() + local.minZ;
        goal[3] = pos.getX() + local.maxX;
        goal[4] = pos.getY() + local.maxY;
        goal[5] = pos.getZ() + local.maxZ;

        double[] box = c.outlineBox;
        double s = c.outlineGlide.get() / 100;
        // From nothing, from a while ago or from far off it starts on the block; otherwise it glides over.
        boolean fresh = !c.outlineShown && now - c.outlineLostAt > OUTLINE_MEMORY;
        double far = Math.abs(goal[0] - box[0]) + Math.abs(goal[1] - box[1]) + Math.abs(goal[2] - box[2]);
        if (fresh || s <= 0 || far > 12) {
            System.arraycopy(goal, 0, box, 0, 6);
        } else if (dt > 0) {
            double step = 1 - Math.exp(-dt / (0.02 + 0.12 * s));
            for (int i = 0; i < 6; i++) {
                box[i] += (goal[i] - box[i]) * step;
                if (Math.abs(goal[i] - box[i]) < 1e-3) box[i] = goal[i];
            }
        }
        c.outlineShown = true;
    }

    /** Whether Cinematic draws the block outline (in its color, gliding) instead of the game. */
    public static boolean drawsOutline() {
        Cinematic c = on();
        return c != null && c.outline.get();
    }

    /** The outline's color: Outline color at Outline opacity, or the game's when Cinematic isn't drawing it. */
    public static int outlineColor(int vanilla) {
        Cinematic c = on();
        if (c == null || !c.outline.get()) return vanilla;
        return ARGB.color((int) Math.round(c.outlineOpacity.get() * 2.55), c.outlineColor.get());
    }

    /**
     * Draws the block's own outline, stretched and moved to fit the gliding box, so it slides from
     * block to block and lands as exactly the shape the game would draw.
     * @return false if there's no box yet, and the game should draw it
     */
    public static boolean drawOutline(PoseStack poseStack, VertexConsumer lines, double camX, double camY, double camZ,
                                      VoxelShape shape, int color, float width) {
        Cinematic c = Modules.get(Cinematic.class);
        if (c == null || !c.outlineShown || shape.isEmpty()) return false;
        double[] box = c.outlineBox;
        AABB local = shape.bounds();
        PoseStack.Pose pose = poseStack.last();
        shape.forAllEdges((x1, y1, z1, x2, y2, z2) -> {
            float ax = (float) (fit(x1, local.minX, local.maxX, box[0], box[3]) - camX);
            float ay = (float) (fit(y1, local.minY, local.maxY, box[1], box[4]) - camY);
            float az = (float) (fit(z1, local.minZ, local.maxZ, box[2], box[5]) - camZ);
            float bx = (float) (fit(x2, local.minX, local.maxX, box[0], box[3]) - camX);
            float by = (float) (fit(y2, local.minY, local.maxY, box[1], box[4]) - camY);
            float bz = (float) (fit(z2, local.minZ, local.maxZ, box[2], box[5]) - camZ);
            Vector3f normal = new Vector3f(bx - ax, by - ay, bz - az);
            if (normal.lengthSquared() <= 0) return;
            normal.normalize();
            lines.addVertex(pose, ax, ay, az).setColor(color).setNormal(pose, normal).setLineWidth(width);
            lines.addVertex(pose, bx, by, bz).setColor(color).setNormal(pose, normal).setLineWidth(width);
        });
        return true;
    }

    /** Where {@code v}, between {@code from0} and {@code from1}, falls between {@code to0} and {@code to1}. */
    private static double fit(double v, double from0, double from1, double to0, double to1) {
        double size = from1 - from0;
        return size < 1e-6 ? to0 + (v - from0) : to0 + (v - from0) / size * (to1 - to0);
    }

    // ---- Picture

    /** Right after the world (and hand) are drawn, before the HUD: grades the picture. Every frame, on or off, so it can fade out. */
    public static void afterWorld() {
        Cinematic c = Modules.get(Cinematic.class);
        if (c != null) c.picture();
    }

    private void picture() {
        long now = System.nanoTime();
        double dt = seconds(pictureFrame, now);
        pictureFrame = now;

        boolean on = isEnabled() && mc.level != null;
        if (on) {
            wanted[SATURATION] = saturation.get() / 100;
            wanted[CONTRAST] = contrast.get() / 100;
            wanted[BRIGHTNESS] = brightness.get() / 100;
            wanted[TEMPERATURE] = temperature.get() / 100;
            wanted[VIGNETTE] = vignette.get() / 100;
            wanted[TRAIL] = motionBlur.get() / 100;
            wanted[DEPTH] = CinematicRenderer.shaderPackOn() ? 0 : depthOfField.get() / 100;
            // Rain greys and cools the picture a little; a thunderstorm more.
            wanted[MOOD] = weather.get() ? Math.min(1, mc.level.getRainLevel(1) * 0.6 + mc.level.getThunderLevel(1) * 0.4) : 0;
        } else {
            System.arraycopy(NEUTRAL, 0, wanted, 0, NEUTRAL.length);
        }

        // Stealth Mode wants it gone now, not in a quarter second.
        double step = Stealth.active() || dt <= 0 && !on ? 1 : dt <= 0 ? 0 : 1 - Math.exp(-dt / SETTLE);
        boolean plain = true;
        for (int i = 0; i < shown.length; i++) {
            shown[i] += (wanted[i] - shown[i]) * step;
            if (Math.abs(shown[i] - wanted[i]) < 1e-3) shown[i] = wanted[i];
            if (shown[i] != NEUTRAL[i]) plain = false;
        }
        if (plain || mc.level == null) {
            CinematicRenderer.release();
            return;
        }

        if (shown[DEPTH] > 0) focus(dt);
        // Motion blur keeps this share of the frames before, set per 60th of a second so it trails
        // as long at 30 fps as at 240.
        double keep = Math.min(0.92, shown[TRAIL] * 0.9);
        double trail = keep <= 0 || dt <= 0 ? 0 : Math.pow(keep, dt * 60);
        CinematicRenderer.draw((float) shown[SATURATION], (float) shown[CONTRAST], (float) shown[BRIGHTNESS],
            (float) shown[TEMPERATURE], (float) shown[VIGNETTE], (float) trail, (float) shown[DEPTH], (float) focus,
            (float) shown[MOOD]);
    }

    /** What the camera's looking at, and how far: the background blur keeps that sharp, easing as you look around. */
    private void focus(double dt) {
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 from = camera.position();
        Vec3 to = from.add(new Vec3(camera.forwardVector()).scale(FOCUS_RANGE));
        HitResult hit = mc.level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()));
        double distance = hit.getType() == HitResult.Type.MISS ? FOCUS_RANGE : hit.getLocation().distanceTo(from);
        if (mc.hitResult instanceof EntityHitResult entity) distance = Math.min(distance, entity.getLocation().distanceTo(from));
        focus = dt <= 0 ? distance : focus + (distance - focus) * (1 - Math.exp(-dt / 0.3));
    }
}
