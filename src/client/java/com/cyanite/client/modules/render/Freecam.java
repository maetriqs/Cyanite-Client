package com.cyanite.client.modules.render;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Culling;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * Detaches the camera and lets it fly freely while your player stays put.
 * CameraMixin places the camera, EntityMixin steers it with the mouse, and
 * KeyboardInputMixin keeps the movement keys from moving the player. While it's on, chunk
 * rendering stays centred on the player (LevelRendererMixin, SectionOcclusionGraphMixin,
 * SodiumCullingMixin) and fog reaches further (FogRendererMixin), so every loaded chunk shows.
 */
public class Freecam extends Module {
    private final SliderSetting speed = add(new SliderSetting("Speed", 1, 0.1, 4, 0.1).format(v -> String.format(Locale.ROOT, "%.1fx", v)));

    private Vec3 position;
    private Vec3 previous;
    private float yaw;
    private float pitch;

    public Freecam() {
        super(Category.RENDER, "Freecam", "Fly the camera around without moving");
    }

    public boolean isActive() {
        return isEnabled() && position != null;
    }

    @Override
    protected void onEnable() {
        if (!inGame()) return;
        position = previous = mc.gameRenderer.getMainCamera().position();
        yaw = mc.player.getYRot();
        pitch = mc.player.getXRot();
        // Occlusion culling guesses what the camera can see from inside the world; a camera flying
        // through walls breaks that guess and chunks blink out.
        Culling.hold(this);
    }

    @Override
    protected void onDisable() {
        position = previous = null;
        Culling.release(this);
    }

    /**
     * Where chunk rendering should be centred: your player, not the camera. Chunks are loaded
     * around the player, so centring on them keeps every loaded chunk drawable however far the
     * camera flies. Null when Freecam is off.
     */
    public static Vec3 renderCentre() {
        Freecam freecam = Modules.get(Freecam.class);
        if (freecam == null || !freecam.isActive() || mc.player == null) return null;
        return mc.player.getEyePosition();
    }

    /** How many chunks further fog and draw distance must reach, so loaded chunks near your player stay visible. */
    public static int extraChunks(Vec3 camera) {
        Vec3 centre = renderCentre();
        return centre == null ? 0 : (int) Math.ceil(centre.distanceTo(camera) / 16);
    }

    @Override
    public void onTick() {
        if (position == null) onEnable();
        previous = position;

        double forward = (mc.options.keyUp.isDown() ? 1 : 0) - (mc.options.keyDown.isDown() ? 1 : 0);
        double strafe = (mc.options.keyLeft.isDown() ? 1 : 0) - (mc.options.keyRight.isDown() ? 1 : 0);
        double vertical = (mc.options.keyJump.isDown() ? 1 : 0) - (mc.options.keyShift.isDown() ? 1 : 0);
        double step = 0.6 * speed.get() * (mc.options.keySprint.isDown() ? 2 : 1);

        double length = Math.sqrt(forward * forward + strafe * strafe);
        if (length > 0) {
            forward /= length;
            strafe /= length;
        }
        double rad = Math.toRadians(yaw);
        double x = (-Math.sin(rad) * forward + Math.cos(rad) * strafe) * step;
        double z = (Math.cos(rad) * forward + Math.sin(rad) * strafe) * step;
        position = position.add(x, vertical * step, z);
    }

    /** Mouse movement, in the same units Entity.turn receives. */
    public void turn(double yRot, double xRot) {
        yaw += (float) yRot * 0.15f;
        pitch = Mth.clamp(pitch + (float) xRot * 0.15f, -90f, 90f);
    }

    public Vec3 cameraPosition(float partialTick) {
        return previous.lerp(position, partialTick);
    }

    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }
}
