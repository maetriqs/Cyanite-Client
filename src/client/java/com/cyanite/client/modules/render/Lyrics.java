package com.cyanite.client.modules.render;

import com.cyanite.client.music.LrcLib;
import com.cyanite.client.music.Spotify;
import com.cyanite.client.render.Render3D;
import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Notify;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ColorSetting;
import com.cyanite.client.system.setting.ModeSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.client.Camera;
import net.minecraft.client.gui.Font;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import java.text.Bidi;
import java.util.List;
import java.util.Locale;

/** The words of the song playing in Spotify, hanging in front of you and in time with it. */
public class Lyrics extends Module {
    // Blocks per font pixel at size 1, the same as a name tag.
    private static final float PIXEL = 0.025f;
    private static final float CURRENT = 1.3f;
    private static final float AROUND = 0.8f;
    private static final float HEADER = 0.7f;
    // Font pixels between the rows, at size 1.
    private static final float GAP = 15;
    // Widest a line may be before it's shrunk to fit, in font pixels at size 1.
    private static final float MAX_WIDTH = 300;
    private static final long SLIDE = 260;
    // How long after switching on it says it's waiting, when Spotify isn't playing anything.
    private static final long HINT = 6000;
    // Seconds it takes to swing round to where you're looking, when following smoothly.
    private static final float LAG = 0.18f;
    private static final int BACKGROUND = 0x66000000;

    private final ModeSetting follow = add(new ModeSetting("Follow", "Smooth", List.of("Smooth", "Locked")));
    private final SliderSetting distance = add(new SliderSetting("Distance", 4, 2, 12, 0.5).format(v -> String.format(Locale.ROOT, "%.1f blocks", v)));
    private final SliderSetting height = add(new SliderSetting("Height", -1, -3, 3, 0.1).format(v -> (v > 0 ? "+" : "") + String.format(Locale.ROOT, "%.1f", v) + " blocks"));
    private final SliderSetting size = add(new SliderSetting("Size", 1, 0.5, 3, 0.1).format(v -> String.format(Locale.ROOT, "%.1fx", v)));
    private final BoolSetting throughWalls = add(new BoolSetting("Through walls", true));
    private final BoolSetting songName = add(new BoolSetting("Song name", true));
    private final BoolSetting around = add(new BoolSetting("Lines around", true));
    private final BoolSetting background = add(new BoolSetting("Background", true));
    private final ColorSetting color = add(new ColorSetting("Color", 0x6FD3FF));
    private final SliderSetting sync = add(new SliderSetting("Sync", 0, -3, 3, 0.1).format(v -> (v > 0 ? "+" : "") + String.format(Locale.ROOT, "%.1f", v) + " s"));

    private float yaw;
    private float pitch;
    private long lastFrame;
    private long enabledAt;
    private String song;
    private int line = -2;
    private long lineChangedAt;

    public Lyrics() {
        super(Category.RENDER, "Lyrics", "Shows the words of the song playing in Spotify, floating in front of you in time with the music");
    }

    @Override
    protected void onEnable() {
        enabledAt = System.currentTimeMillis();
        lastFrame = 0;
        song = null;
        line = -2;
        if (Spotify.supported()) Spotify.start();
        else Notify.send("Lyrics", "Only works on Windows for now: it reads Spotify through Windows' media controls.", true, false, false);
    }

    @Override
    protected void onDisable() {
        Spotify.stop();
    }

    @Override
    public String getInfo() {
        if (!Spotify.supported()) return "Windows only";
        if (Spotify.problem() != null) return "Can't read Spotify";
        Spotify.State s = Spotify.state();
        if (s == null) return "No Spotify";
        return s.playing() ? "Playing" : "Paused";
    }

    @Override
    public void onRender3D(Render3D render) {
        Panel panel = place(render);
        long now = System.currentTimeMillis();
        Spotify.State s = Spotify.state();
        if (s == null) {
            String why = Spotify.problem() != null ? "Can't read Spotify: " + Spotify.problem()
                : Spotify.supported() ? "Waiting for Spotify to play something" : null;
            if (why != null && now - enabledAt < HINT) panel.row(why, 0, CURRENT * 0.7f, 0xB0FFFFFF);
            return;
        }

        Spotify.Track track = s.track();
        if (songName.get()) {
            String name = "♪ " + track.title() + (track.artist().isEmpty() ? "" : " · " + track.artist()) + (s.playing() ? "" : "  (paused)");
            panel.row(name, -2 * GAP, HEADER, 0xA0FFFFFF);
        }

        LrcLib.Words words = LrcLib.words(track);
        String status = switch (words.status()) {
            case LOOKING -> "Looking the words up…";
            case INSTRUMENTAL -> "♪ Instrumental ♪";
            case UNSYNCED -> "Only unsynced words for this one";
            case MISSING -> "No words found for this one";
            case FAILED -> "Couldn't reach the lyrics site";
            case FOUND -> null;
        };
        if (status != null) {
            panel.row(status, 0, CURRENT * 0.7f, 0xC0FFFFFF);
            return;
        }

        List<LrcLib.Line> lines = words.lines();
        long at = s.positionNow() + Math.round(sync.get() * 1000);
        int i = words.lineAt(at);
        String key = track.key();
        if (!key.equals(song) || i != line) {
            // A new song starts in place; a new line slides up into it.
            lineChangedAt = key.equals(song) ? now : 0;
            song = key;
            line = i;
        }
        float t = Math.min(1f, (now - lineChangedAt) / (float) SLIDE);
        float eased = 1 - (1 - t) * (1 - t) * (1 - t);
        float slide = (1 - eased) * GAP;

        if (around.get() && i >= 1) panel.row(shown(lines.get(i - 1).text()), -GAP + slide, AROUND, alpha(0x70, eased));
        if (i >= 0) {
            LrcLib.Line current = lines.get(i);
            long next = i + 1 < lines.size() ? lines.get(i + 1).at() : current.at() + 4000;
            panel.sung(shown(current.text()), slide, at - current.at(), next - current.at(), 0.55f + 0.45f * eased);
        } else {
            panel.row("♪", slide, CURRENT, 0x90FFFFFF);
        }
        if (around.get() && i + 1 < lines.size()) panel.row(shown(lines.get(i + 1).text()), GAP + slide, AROUND, alpha(0x90, eased));
    }

    /** An empty line is a break in the singing. */
    private static String shown(String text) {
        return text.isEmpty() ? "♪" : text;
    }

    private static int alpha(int full, float f) {
        return Math.max(4, Math.round(full * f)) << 24 | 0xFFFFFF;
    }

    /** Where the words hang this frame: straight ahead of the camera, or catching up with it. */
    private Panel place(Render3D render) {
        Camera cam = mc.gameRenderer.getMainCamera();
        long nanos = System.nanoTime();
        if (follow.is("Locked") || lastFrame == 0) {
            yaw = cam.yRot();
            pitch = cam.xRot();
        } else {
            float dt = Math.min(0.25f, (nanos - lastFrame) / 1e9f);
            float k = 1 - (float) Math.exp(-dt / LAG);
            yaw += Mth.wrapDegrees(cam.yRot() - yaw) * k;
            pitch += (cam.xRot() - pitch) * k;
        }
        lastFrame = nanos;

        Quaternionf facing = new Quaternionf().rotationYXZ((float) Math.PI - yaw * Mth.DEG_TO_RAD, -pitch * Mth.DEG_TO_RAD, 0);
        Vec3 at = render.camera().add(Vec3.directionFromRotation(pitch, yaw).scale(distance.get()));
        float scale = size.get().floatValue();
        // Height is in blocks; rows are laid out in font pixels from the middle of the panel.
        return new Panel(render, at, facing, scale, (float) (-height.get() / (PIXEL * scale)));
    }

    private final class Panel {
        private final Render3D render;
        private final Vec3 at;
        private final Quaternionf facing;
        private final float scale;
        private final float lift;
        private final Font font = mc.font;

        Panel(Render3D render, Vec3 at, Quaternionf facing, float scale, float lift) {
            this.render = render;
            this.at = at;
            this.facing = facing;
            this.scale = scale;
            this.lift = lift;
        }

        /** One centred row, {@code y} font pixels below the middle at size 1, {@code rowScale} times the size. */
        void row(String text, float y, float rowScale, int argb) {
            float s = fit(text, rowScale);
            float w = font.width(text);
            draw(text, -w / 2, y, s, argb, true);
        }

        /** The line being sung: filled in with the colour from the left as it goes, over the time it's sung in. */
        void sung(String text, float y, long into, long length, float fade) {
            float s = fit(text, CURRENT);
            float w = font.width(text);
            int base = alpha(0xFF, fade);
            draw(text, -w / 2, y, s, base, true);

            int cps = text.codePointCount(0, text.length());
            long fill = Math.max(1, Math.min(length, 500 + 85L * cps));
            float f = Mth.clamp(into / (float) fill, 0, 1);
            int sung = Math.max(4, Math.round(0xFF * fade)) << 24 | color.get();
            // Splitting right-to-left text would scramble it: it's filled all at once.
            if (Bidi.requiresBidi(text.toCharArray(), 0, text.length())) f = f > 0.5f ? 1 : 0;
            int count = Math.round(f * cps);
            if (count <= 0) return;
            String part = text.substring(0, text.offsetByCodePoints(0, count));
            draw(part, -w / 2, y, s, sung, false);
        }

        /** Shrinks a row that would be too wide. */
        private float fit(String text, float rowScale) {
            float w = font.width(text) * rowScale;
            return w > MAX_WIDTH ? rowScale * MAX_WIDTH / w : rowScale;
        }

        private void draw(String text, float x, float y, float rowScale, int argb, boolean withBackground) {
            float px = PIXEL * scale * rowScale;
            // y is the row's middle in size-1 pixels; the font draws from the top of the text.
            float top = (y + lift) / rowScale - font.lineHeight / 2f;
            int back = withBackground && background.get() ? BACKGROUND : 0;
            render.text(at, facing, px, x, top, text, argb, back, throughWalls.get());
        }
    }
}
