package com.cyanite.client.gui;

public final class Theme {
    public static final int BORDER       = 0xFF252932;
    public static final int BODY         = 0xFF101217;
    public static final int HEADER       = 0xFF161920;
    public static final int HEADER_HOVER = 0xFF1C2029;
    public static final int FIELD        = 0xFF0B0D11;
    public static final int TRACK        = 0xFF2A2F3A;

    public static final int TEXT         = 0xFFE9ECF1;
    public static final int TEXT_DIM     = 0xFF8A919D;
    public static final int TEXT_FAINT   = 0xFF4E5461;
    public static final int DANGER       = 0xFFFF5D62;

    public static final int SHADOW       = 0xB0000000;
    public static final int TINT_TOP     = 0x30000000;
    public static final int TINT_BOTTOM  = 0x9A04070C;

    private Theme() {
    }

    public static int accent() {
        return 0xFF000000 | GuiSettings.ACCENT.get();
    }

    /** The accent lifted towards white, readable as text on the dark panels. */
    public static int accentText() {
        return mix(accent(), 0xFFFFFFFF, 0.55f);
    }

    public static float glow() {
        return GuiSettings.GLOW.get().floatValue();
    }

    public static float radius() {
        return GuiSettings.RADIUS.get().floatValue();
    }

    /** Scales the alpha of {@code color} by {@code mul}. */
    public static int fade(int color, float mul) {
        int a = Math.round((color >>> 24) * Math.max(0, Math.min(1, mul)));
        return (a << 24) | (color & 0xFFFFFF);
    }

    /** RGB of {@code color} with a 0-255 alpha. */
    public static int alpha(int color, float alpha) {
        int a = Math.round(Math.max(0, Math.min(255, alpha)));
        return (a << 24) | (color & 0xFFFFFF);
    }

    public static int mix(int from, int to, float t) {
        t = Math.max(0, Math.min(1, t));
        int a = lerp(from >>> 24, to >>> 24, t);
        int r = lerp(from >> 16 & 0xFF, to >> 16 & 0xFF, t);
        int g = lerp(from >> 8 & 0xFF, to >> 8 & 0xFF, t);
        int b = lerp(from & 0xFF, to & 0xFF, t);
        return a << 24 | r << 16 | g << 8 | b;
    }

    private static int lerp(int a, int b, float t) {
        return Math.round(a + (b - a) * t);
    }
}
