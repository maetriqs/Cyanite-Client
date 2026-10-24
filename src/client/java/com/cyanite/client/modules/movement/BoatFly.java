package com.cyanite.client.modules.movement;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.SliderSetting;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.Vec3;

/**
 * Flies the boat you're steering. AbstractBoatMixin calls in right after the boat has read your
 * input, so this replaces the boat's motion for the tick before it moves. Space rises and the
 * sprint key sinks; sneak is left alone because it gets you out of the boat.
 *
 * <p>Noclip (red: servers won't have it): the boat stops bumping into blocks on your side, and
 * the dark in-a-block screen is hidden while you're in one. A 1.21 server puts back any move
 * into a block the boat wasn't already touching, so on a server you get pushed back out.
 */
public class BoatFly extends Module {
    @Override
    public boolean anticheatDownOnly() {
        return true;
    }

    private final SliderSetting speed = add(new SliderSetting("Speed", 20, 1, 80, 1).format(v -> Math.round(v) + " b/s"));
    private final SliderSetting vertical = add(new SliderSetting("Vertical speed", 10, 1, 40, 1).format(v -> Math.round(v) + " b/s"));
    private final SliderSetting fall = add(new SliderSetting("Fall speed", 0, 0, 10, 0.5).format(v -> v == 0 ? "Hover" : (v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v)) + " b/s"));
    private final BoolSetting camera = add(new BoolSetting("Steer with camera", true));
    private final BoolSetting antiKick = add(new BoolSetting("Anti kick", true));
    private final BoolSetting ignoreSetbacks = add(new BoolSetting("Ignore setbacks", false));
    private final BoolSetting noclip = add(new BoolSetting("Noclip", false));

    private int ticks;
    /** The boat that's had its block collisions turned off, to turn them back on. */
    private AbstractBoat clipped;

    public BoatFly() {
        super(Category.MOVEMENT, "Boat Fly", "Fly any boat you're steering");
        noclip.markRisky();
    }

    @Override
    public String getInfo() {
        return speed.display();
    }

    /**
     * Sets this tick's motion for a boat you control.
     * @return true when the boat was turned to face the camera, so its own A/D turning should be dropped.
     */
    public boolean fly(AbstractBoat boat) {
        if (!isEnabled() || mc.player == null || boat.getControllingPassenger() != mc.player) return false;
        ticks++;
        if (noclip.get()) {
            if (clipped != boat) release();
            // Before the boat moves this tick: no collisions, so it goes wherever it's sent.
            boat.noPhysics = true;
            clipped = boat;
        } else {
            release();
        }

        Input keys = mc.player.input.keyPresses;
        boolean steer = camera.get();
        float yaw = steer ? mc.player.getYRot() : boat.getYRot();
        if (steer) boat.setYRot(yaw);

        double forward = (keys.forward() ? 1 : 0) - (keys.backward() ? 1 : 0);
        // Without camera steering A and D turn the boat as usual, so they can't also strafe.
        double strafe = steer ? (keys.left() ? 1 : 0) - (keys.right() ? 1 : 0) : 0;
        double x = 0;
        double z = 0;
        if (forward != 0 || strafe != 0) {
            double perTick = speed.get() / 20;
            double length = Math.sqrt(forward * forward + strafe * strafe);
            double rad = Math.toRadians(yaw);
            forward /= length;
            strafe /= length;
            x = (-Math.sin(rad) * forward + Math.cos(rad) * strafe) * perTick;
            z = (Math.cos(rad) * forward + Math.sin(rad) * strafe) * perTick;
        }

        double y;
        if (mc.options.keyJump.isDown()) y = vertical.get() / 20;
        else if (mc.options.keySprint.isDown()) y = -vertical.get() / 20;
        else y = -fall.get() / 20;

        // Servers kick a vehicle that hangs in the air for four seconds; one small dip every two resets the count.
        if (antiKick.get() && ticks % 40 == 0) y = Math.min(y, 0) - 0.04;

        boat.setDeltaMovement(new Vec3(x, y, z));
        return steer;
    }

    /** Whether you're noclipping in a boat right now (the in-a-block screen is hidden). */
    public boolean noclipping() {
        return isEnabled() && noclip.get() && clipped != null && mc.player != null && mc.player.getVehicle() == clipped;
    }

    @Override
    public void onTick() {
        // Out of the boat, or Noclip switched off: it bumps into things again.
        if (clipped != null && (!noclip.get() || mc.player.getVehicle() != clipped)) release();
    }

    private void release() {
        if (clipped != null) clipped.noPhysics = false;
        clipped = null;
    }

    /** Whether the server's position corrections for your boat should be thrown away. */
    public boolean ignoresSetbacks() {
        return isEnabled() && ignoreSetbacks.get() && mc.player != null && mc.player.getRootVehicle() instanceof AbstractBoat;
    }

    @Override
    protected void onDisable() {
        ticks = 0;
        release();
    }
}
