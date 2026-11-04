package com.cyanite.client.modules.render;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.client.CameraType;
import net.minecraft.util.Mth;

/**
 * The third-person camera (F5): on its key it goes to third person and back, further out than
 * vanilla's 4 blocks and through walls instead of bumping into them. With Free look the mouse
 * swings the camera round you while your player keeps facing the same way, so WASD still walks
 * the way it's facing.
 */
public class F5Camera extends Module {
    private final SliderSetting distance = add(new SliderSetting("Distance", 8, 1, 30, 0.5).format(v -> v + " blocks"));
    private final BoolSetting throughWalls = add(new BoolSetting("Through walls", true));
    private final BoolSetting switchView = add(new BoolSetting("Switch to third person", true));
    private final BoolSetting freeLook = add(new BoolSetting("Free look", true));

    /** The view to go back to when this is turned off, if turning it on is what left first person. */
    private CameraType before;
    /** Free look is going: yaw and pitch are the camera's own, not the player's. */
    private boolean looking;
    private float yaw;
    private float pitch;

    public F5Camera() {
        super(Category.RENDER, "F5 Camera", "Switches to third person and lets you look round without turning your player, further out and through walls");
    }

    @Override
    public String getInfo() {
        return distance.display();
    }

    @Override
    protected void onEnable() {
        before = null;
        if (inGame() && switchView.get() && mc.options.getCameraType().isFirstPerson()) {
            before = mc.options.getCameraType();
            setView(CameraType.THIRD_PERSON_BACK);
        }
    }

    @Override
    protected void onDisable() {
        looking = false;
        if (before != null && inGame()) setView(before);
        before = null;
    }

    /** As pressing F5 does it. */
    private static void setView(CameraType view) {
        boolean wasFirst = mc.options.getCameraType().isFirstPerson();
        mc.options.setCameraType(view);
        if (wasFirst != view.isFirstPerson()) mc.gameRenderer.checkEntityPostEffect(view.isFirstPerson() ? mc.getCameraEntity() : null);
        mc.levelRenderer.needsUpdate();
    }

    /** The camera distance to use in place of vanilla's 4 blocks (times the player's scale). */
    public float distance(float vanilla) {
        return isEnabled() ? vanilla / 4f * distance.get().floatValue() : vanilla;
    }

    public boolean clips() {
        return isEnabled() && throughWalls.get();
    }

    /**
     * Whether the mouse is swinging the camera round you rather than turning your player: on, in
     * third person, looking from your own player, and not in Freecam. It starts looking the way
     * you face.
     */
    public boolean freeLooking() {
        Freecam freecam = Modules.get(Freecam.class);
        boolean now = isEnabled() && freeLook.get() && inGame()
            && !mc.options.getCameraType().isFirstPerson()
            && mc.getCameraEntity() == mc.player
            && (freecam == null || !freecam.isActive());
        if (now && !looking) {
            yaw = mc.player.getYRot();
            pitch = mc.player.getXRot();
        }
        looking = now;
        return now;
    }

    /** Mouse movement, in the same units Entity.turn receives. */
    public void turn(double yRot, double xRot) {
        yaw += (float) yRot * 0.15f;
        pitch = Mth.clamp(pitch + (float) xRot * 0.15f, -90f, 90f);
    }

    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }
}
