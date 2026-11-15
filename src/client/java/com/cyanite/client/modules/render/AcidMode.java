package com.cyanite.client.modules.render;

import com.cyanite.client.hud.Hud;
import com.cyanite.client.hud.HudElement;
import com.cyanite.client.render.AcidRenderer;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.MusicLoop;
import com.cyanite.client.system.Notify;
import com.cyanite.client.system.Stealth;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.SliderSetting;
import com.cyanite.client.system.setting.StringSetting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.ARGB;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Acid Mode: everything in a smooth, fast rainbow. The world's picture drifts through every hue,
 * items (in your hand, on the ground, in the hotbar and menus) shimmer in a rainbow of their own,
 * the FOV opens up and breathes, the view sways, the client's menus and HUD cycle colours, a song
 * of your choosing loops in the background and the Subway Surfers HUD element comes on.
 *
 * <p>Nothing is changed for good: the FOV in Options, the menu accent and the HUD's own switches
 * are only read past while it's on, so switching it off (or Stealth Mode) leaves them exactly as
 * they were. It eases in and out like Cinematic, and Stealth Mode cuts it at once.
 *
 * <p>Hooks: GameRendererMixin takes the FOV ({@link #fov}) and the sway ({@link #sway}) from here and
 * calls {@link #afterWorld} once the world's drawn (the picture is {@link AcidRenderer});
 * ItemRendererMixin ({@link #drawRainbowItem}), GuiRendererMixin ({@link #guiItemColor}) and
 * GuiHotbarMixin ({@link #hotbarColor}) colour the items and hotbar; Theme ({@link #accent}) and
 * ModuleList ({@link #hudColor}) the client's own look; Hud saves the Subway Surfers element as it
 * was before this switched it on ({@link #switchedOn}).
 */
public class AcidMode extends Module {
    /** How long it takes to fade in or out, in seconds. */
    private static final double SETTLE = 0.6;
    /** Turns of the hue wheel per second at 100% colour speed. */
    private static final double HUE_SPEED = 0.25;
    /** The FOV never goes past this, however it's pushed: wider bends the picture into a fishbowl. */
    private static final double FOV_LIMIT = 140;
    /** How much the FOV breathes in and out, and how fast (per second). */
    private static final double PULSE = 7;
    private static final double PULSE_RATE = 0.55;
    /** How far the view sways side to side, in degrees. */
    private static final double SWAY = 3;
    /** The HUD element it turns on, looked up by name: it may not be in this build. */
    private static final String SUBWAY = "Subway Surfers";
    /** The song played when Music file is left blank, inside the game's cyanite folder; without it, any song there. */
    private static final String DEFAULT_SONG = "acid.mp3";
    /** What counts as a song when looking through the cyanite folder. */
    private static final List<String> SONG_TYPES = List.of(".mp3", ".ogg", ".wav", ".flac", ".m4a", ".opus");

    private final SliderSetting intensity = add(new SliderSetting("Intensity", 100, 10, 100, 5).format(AcidMode::percent));
    private final SliderSetting colorSpeed = add(new SliderSetting("Color speed", 100, 25, 300, 5).format(AcidMode::percent));
    private final BoolSetting world = add(new BoolSetting("Rainbow world", true));
    private final BoolSetting wobble = add(new BoolSetting("Wobble", true));
    private final BoolSetting items = add(new BoolSetting("Rainbow items", true));
    private final BoolSetting ui = add(new BoolSetting("Rainbow menus and HUD", true));
    private final SliderSetting fovBoost = add(new SliderSetting("FOV boost", 25, 0, 50, 1).format(v -> v <= 0 ? "Off" : "+" + (int) v + "°"));
    private final BoolSetting fovPulse = add(new BoolSetting("FOV pulse", true));
    private final BoolSetting sway = add(new BoolSetting("Sway", true));
    private final BoolSetting music = add(new BoolSetting("Music", true));
    private final StringSetting musicFile = add(new StringSetting("Music file", "", 260).placeholder("any song in the cyanite folder"));
    private final SliderSetting volume = add(new SliderSetting("Music volume", 60, 0, 100, 5).format(AcidMode::percent));
    private final BoolSetting subway = add(new BoolSetting(SUBWAY, true));

    /** Where it's fading to, when that started, and from what level. */
    private boolean target;
    private long toggledAt;
    private double fromLevel;

    /** The hue wheel's turn, moved on by however long it's been since it was last asked, at the colour speed then. */
    private double turns;
    private long clockAt;
    /** Seconds since it was switched on, for the wobble, sway and pulse. */
    private long startedAt;

    private MusicLoop song;
    /** The song that's playing or failed, and whether its problem has been said: not said again until something changes. */
    private Path songPath;
    private boolean told;

    /** Item draws that went through {@link #drawRainbowItem}, for tests (Sodium has its own item path to get past). */
    private static int itemsDrawn;

    /** The Subway Surfers element while this has switched it on (and so switches it off again). */
    private HudElement subwayOn;
    /** Whether it's been looked at since this came on: on the first tick in a world, once the config's all loaded. */
    private boolean subwayChecked;

    public AcidMode() {
        super(Category.RENDER, "Acid Mode", "Everything in a smooth, fast rainbow: the world, items, menus and HUD cycle colours, the FOV opens up and breathes, the view sways and a song of yours loops");
        musicFile.onChange(this::restartMusic);
        music.onChange(this::restartMusic);
        subway.onChange(() -> {
            if (subway.get()) subwayChecked = false;
            else restoreSubway();
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> stopMusic());
    }

    private static String percent(double v) {
        return Math.round(v) + "%";
    }

    private static AcidMode get() {
        return Modules.get(AcidMode.class);
    }

    @Override
    protected void onEnable() {
        retarget(true);
        startedAt = System.nanoTime();
        subwayChecked = false;
        restartMusic();
    }

    @Override
    protected void onDisable() {
        retarget(false);
        stopMusic();
        restoreSubway();
    }

    @Override
    public void onTick() {
        if (!subwayChecked) {
            subwayChecked = true;
            switchSubway();
        }
        if (song == null) return;
        song.setVolume((float) (volume.get() / 100 * mc.options.getSoundSourceVolume(SoundSource.MASTER)));
        String problem = song.problem();
        if (problem != null && !told) {
            told = true;
            Notify.send(name, "No music: " + problem + ". Put an mp3 in .minecraft/cyanite, or set Music file.", false, false, false);
        }
    }

    // ---- Fading and the clock

    private void retarget(boolean on) {
        fromLevel = level();
        target = on;
        toggledAt = System.nanoTime();
    }

    /** How much of it shows right now, 0 to 1, easing in and out; 0 straight away under Stealth Mode. */
    private double level() {
        if (Stealth.active()) return 0;
        double goal = target ? 1 : 0;
        if (toggledAt == 0) return goal;
        double t = (System.nanoTime() - toggledAt) / 1e9 / SETTLE;
        if (t >= 1) return goal;
        double eased = t * t * (3 - 2 * t);
        return fromLevel + (goal - fromLevel) * eased;
    }

    /** {@link #level()} scaled by Intensity: how strong each effect is. */
    private double strength() {
        return level() * intensity.get() / 100;
    }

    private double turns() {
        long now = System.nanoTime();
        if (clockAt != 0) turns += Math.min((now - clockAt) / 1e9, 0.25) * HUE_SPEED * colorSpeed.get() / 100;
        clockAt = now;
        // Whole turns change nothing, and dropping them keeps the fraction exact.
        if (turns > 1000) turns -= Math.floor(turns);
        return turns;
    }

    private double seconds() {
        // Wraps after an hour, so a float still has the detail the waves need.
        return (System.nanoTime() - startedAt) / 1e9 % 3600;
    }

    /** A rainbow colour at {@code hue} turns, as RGB. */
    private static int hueColor(double hue, float saturation) {
        return ColorSetting.fromHsv((float) (hue - Math.floor(hue)), saturation, 1f);
    }

    // ---- The picture

    /** Right after the world (and hand) are drawn, and after Cinematic's picture: the rainbow over it. Every frame, so it can fade out. */
    public static void afterWorld() {
        AcidMode a = get();
        if (a == null) return;
        double s = a.world.get() ? a.strength() : 0;
        if (s <= 0 || mc.level == null) {
            AcidRenderer.release();
            return;
        }
        float shake = a.wobble.get() ? 1 : 0;
        AcidRenderer.draw((float) (a.turns() % 1), (float) a.seconds(), (float) s, 0.85f, shake, shake, 0.6f);
    }

    // ---- Camera

    /** The world's FOV, opened up by FOV boost and breathing in and out. */
    public static float fov(float fov) {
        AcidMode a = get();
        if (a == null) return fov;
        double s = a.strength();
        if (s <= 0) return fov;
        double boost = a.fovBoost.get();
        if (a.fovPulse.get()) boost += PULSE * Math.sin(a.seconds() * Math.PI * 2 * PULSE_RATE);
        return (float) Math.max(fov, Math.min(FOV_LIMIT, fov + boost * s));
    }

    /** Rolls the view gently side to side, after the hurt shake (the world's projection only, never where you aim). */
    public static void sway(PoseStack poseStack) {
        AcidMode a = get();
        if (a == null || !a.sway.get()) return;
        double s = a.strength();
        if (s <= 0) return;
        double t = a.seconds();
        double roll = SWAY * s * (Math.sin(t * 0.9) * 0.75 + Math.sin(t * 2.3 + 1) * 0.25);
        poseStack.mulPose(Axis.ZP.rotationDegrees((float) roll));
    }

    // ---- Items

    /** Whether items in the world are drawn in the rainbow ({@link #drawRainbowItem}). */
    public static boolean rainbowItems() {
        AcidMode a = get();
        return a != null && a.items.get() && a.strength() > 0;
    }

    /**
     * Draws an item's quads as the game would, but with a rainbow running across it: each corner
     * takes its hue from where it sits on the item, so it shimmers as the hues move, and items
     * side by side differ. Tinted parts (leather, potions) keep their own colour under it.
     */
    public static void drawRainbowItem(PoseStack poseStack, VertexConsumer consumer, List<BakedQuad> quads, int[] tints, int light, int overlay) {
        AcidMode a = get();
        itemsDrawn++;
        float amount = (float) a.strength();
        PoseStack.Pose pose = poseStack.last();
        Matrix4f matrix = pose.pose();
        double base = a.turns() + (matrix.m30() + matrix.m31() * 0.7 + matrix.m32()) * 0.08;
        Vector3f position = new Vector3f();
        Vector3f normal = new Vector3f();
        for (BakedQuad quad : quads) {
            int tint = quad.isTinted() && quad.tintIndex() < tints.length ? tints[quad.tintIndex()] : -1;
            pose.transformNormal(quad.direction().getUnitVec3f(), normal);
            int lightCoords = LightTexture.lightCoordsWithEmission(light, quad.lightEmission());
            for (int i = 0; i < 4; i++) {
                Vector3fc corner = quad.position(i);
                // A short stretch of the wheel across the item: opposite hues at the corners would blend to grey.
                int rainbow = 0xFF000000 | hueColor(base + (corner.x() + corner.y()) * 0.18, 0.7f);
                int color = ARGB.multiply(tint, ARGB.srgbLerp(amount, -1, rainbow));
                matrix.transformPosition(corner, position);
                long uv = quad.packedUV(i);
                consumer.addVertex(position.x(), position.y(), position.z(), color, UVPair.unpackU(uv), UVPair.unpackV(uv),
                    overlay, lightCoords, normal.x(), normal.y(), normal.z());
            }
        }
    }

    public static int itemsDrawn() {
        return itemsDrawn;
    }

    /** An item in the hotbar or a menu, tinted by where it is on screen, so the rainbow runs along the hotbar. */
    public static int guiItemColor(int color, int x, int y) {
        AcidMode a = get();
        if (a == null || !a.items.get()) return color;
        double s = a.strength();
        if (s <= 0) return color;
        int rainbow = 0xFF000000 | hueColor(a.turns() + x * 0.01 + y * 0.004, 0.6f);
        return ARGB.multiply(color, ARGB.srgbLerp((float) s, -1, rainbow));
    }

    /** The hotbar's colour (and its selection box's), or -1 to leave it be. */
    public static int hotbarColor(int x) {
        AcidMode a = get();
        if (a == null || !a.ui.get()) return -1;
        double s = a.strength();
        if (s <= 0) return -1;
        return ARGB.srgbLerp((float) s, -1, 0xFF000000 | hueColor(a.turns() + x * 0.004, 0.5f));
    }

    // ---- The client's look

    /** The menu and HUD accent: cycling through the rainbow while it's on, the one from settings otherwise. */
    public static int accent(int accent) {
        AcidMode a = get();
        if (a == null || !a.ui.get()) return accent;
        double s = a.level();
        if (s <= 0) return accent;
        return ARGB.srgbLerp((float) s, accent, 0xFF000000 | hueColor(a.turns(), 0.7f));
    }

    /** The arraylist's colour for its {@code index}th line, or 0 to leave it to the arraylist's own setting. */
    public static int hudColor(int index) {
        AcidMode a = get();
        if (a == null || !a.ui.get() || a.level() <= 0) return 0;
        return 0xFF000000 | hueColor(a.turns() + index * 0.06, 0.6f);
    }

    // ---- Subway Surfers

    /** Switches the Subway Surfers element on, if this build has it and it's off, remembering to switch it off again. */
    private void switchSubway() {
        if (!isEnabled() || !subway.get() || subwayOn != null) return;
        HudElement element = Hud.ELEMENTS.stream().filter(e -> e.name.equals(SUBWAY)).findFirst().orElse(null);
        if (element == null || element.isEnabled()) return;
        element.setEnabled(true);
        subwayOn = element;
    }

    /** Puts the Subway Surfers element back off, if this switched it on. */
    private void restoreSubway() {
        if (subwayOn != null && subwayOn.isEnabled()) subwayOn.setEnabled(false);
        subwayOn = null;
    }

    /** Whether this HUD element is on only because Acid Mode switched it on: it's saved as off, so the config never keeps it. */
    public static boolean switchedOn(HudElement element) {
        AcidMode a = get();
        return a != null && a.subwayOn == element;
    }

    // ---- Music

    /**
     * Music file as a path: blank is acid.mp3 in the game's cyanite folder, or failing that the first song there
     * by name; a bare name is looked for there too.
     */
    private Path songPath() {
        String typed = musicFile.get().strip();
        // Windows' "Copy as path" puts quotes round it.
        if (typed.length() >= 2 && typed.startsWith("\"") && typed.endsWith("\"")) typed = typed.substring(1, typed.length() - 1);
        Path folder = mc.gameDirectory.toPath().resolve("cyanite");
        if (typed.isEmpty()) return anySong(folder);
        try {
            Path path = Path.of(typed);
            return path.isAbsolute() ? path : folder.resolve(path);
        } catch (InvalidPathException e) {
            return anySong(folder);
        }
    }

    /** acid.mp3 if it's there, otherwise the first song in the folder by name (still acid.mp3 if there's none, to say so). */
    private static Path anySong(Path folder) {
        Path named = folder.resolve(DEFAULT_SONG);
        if (Files.isRegularFile(named)) return named;
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(Files::isRegularFile).filter(AcidMode::isSong)
                .min(Comparator.comparing(f -> f.getFileName().toString().toLowerCase(Locale.ROOT))).orElse(named);
        } catch (IOException e) {
            return named;
        }
    }

    private static boolean isSong(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return SONG_TYPES.stream().anyMatch(name::endsWith);
    }

    private void restartMusic() {
        stopMusic();
        if (!isEnabled() || !music.get()) return;
        Path path = songPath();
        if (!path.equals(songPath)) told = false;
        songPath = path;
        song = new MusicLoop(path, 0).start();
    }

    private void stopMusic() {
        if (song == null) return;
        song.stop();
        song = null;
    }

    /** Whether the song is playing right now, for tests. */
    public boolean musicPlaying() {
        return song != null && song.isPlaying();
    }

    @Override
    public String getInfo() {
        return intensity.get() >= 100 ? null : String.format(Locale.ROOT, "%d%%", Math.round(intensity.get()));
    }
}
