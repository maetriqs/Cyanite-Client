package com.cyanite.client.visuals;

import com.cyanite.client.system.setting.SliderSetting;

import java.util.Locale;

/**
 * The time of day as you see it: where the sun and moon are, the sky's colour and the daylight. Only your own
 * world's clock as the game draws it; the server's time, and anything it decides by it, stays the same.
 */
public final class TimeChanger {
    public static final Group GROUP = new Group("time_changer", "Time Changer",
        "Pick the time of day you see, like always noon, sunset or night. Only for you; the server's time stays the same.",
        "time day night sunset sunrise noon midnight clock sun moon light dark");
    static final SliderSetting TIME = GROUP.add(new SliderSetting("Time", 12, 0, 24, 0.25).format(TimeChanger::clock));

    private static final long DAY = 24000;

    private TimeChanger() {
    }

    /** The day time your world reports, {@code time} being the server's: the same day (so the moon's phase), at your hour. */
    public static long dayTime(long time) {
        if (!GROUP.on()) return time;
        return Math.floorDiv(time, DAY) * DAY + ticks(TIME.get());
    }

    /** The game's day starts at 6 in the morning, a thousand ticks an hour. */
    static long ticks(double hours) {
        return Math.round(((hours - 6 + 24) % 24) * 1000) % DAY;
    }

    static String clock(double hours) {
        int minutes = (int) Math.round(hours * 60) % (24 * 60);
        return String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
    }
}
