package com.cyanite.client.gui;

public final class Anim {
    private Anim() {
    }

    public static boolean enabled() {
        return GuiSettings.ANIMATIONS.get();
    }

    /** Frame-rate independent ease towards a target. Higher speed settles faster. */
    public static float approach(float value, float target, float dt, float speed) {
        if (!enabled()) return target;
        return value + (target - value) * (1f - (float) Math.exp(-speed * dt));
    }

    public static float easeOut(float t) {
        t = 1f - clamp(t);
        return 1f - t * t * t;
    }

    public static float clamp(float v) {
        return v < 0 ? 0 : Math.min(v, 1);
    }
}
