package com.cyanite.client.system;

import net.minecraft.util.Mth;

import java.util.concurrent.ThreadLocalRandom;

/**
 * When a person's next click comes at a set clicking speed. Not a steady beat with a little jitter
 * (anticheats look for exactly that: too even, too narrow a spread, the same rhythm over and over)
 * but spread the way real clicking is: skewed round the mean with a tail of slow ones, a speed that
 * wanders over a few seconds, and now and then a pause or two clicks so close they land in one tick.
 */
public final class ClickTimer {
    private long next;
    private double speed = -1;

    /** How many clicks are due by now (0, 1 or now and then 2) at a speed between {@code low} and {@code high} CPS. */
    public int due(double low, double high) {
        high = Math.max(low, high);
        long now = System.currentTimeMillis();
        // After a break, nothing to click at for a while, the first click comes straight away.
        if (now - next > 500) next = now;
        int clicks = 0;
        while (now >= next && clicks < 2) {
            clicks++;
            next += interval(low, high);
        }
        if (now >= next) next = now + interval(low, high);
        return clicks;
    }

    private long interval(double low, double high) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (speed < 0) speed = random.nextDouble(low, high + 1e-9);
        speed = Mth.clamp(speed + random.nextGaussian() * (high - low + 1) * 0.08, low, high);
        double mean = 1000 / speed;
        double roll = random.nextDouble();
        if (roll < 0.03) return Math.round(mean * random.nextDouble(2, 3.5));
        if (roll < 0.09) return Math.round(mean * random.nextDouble(0.3, 0.55));
        double spread = 0.22;
        return Math.max(20, Math.round(mean * Math.exp(random.nextGaussian() * spread - spread * spread / 2)));
    }
}
