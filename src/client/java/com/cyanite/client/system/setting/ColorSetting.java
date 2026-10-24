package com.cyanite.client.system.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/** An opaque RGB colour, stored as 0xRRGGBB. */
public class ColorSetting extends Setting<Integer> {
    public ColorSetting(String name, int rgb) {
        super(name, rgb & 0xFFFFFF);
    }

    public float hue() {
        return hsv(value)[0];
    }

    /** Changes the hue but keeps saturation and brightness, unless the colour is grey. */
    public void setHue(float hue) {
        float[] hsv = hsv(value);
        float s = hsv[1] < 0.1f ? 0.7f : hsv[1];
        float v = hsv[2] < 0.2f ? 1f : hsv[2];
        set(fromHsv(hue, s, v));
    }

    @Override
    protected Integer sanitize(Integer v) {
        return v & 0xFFFFFF;
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(String.format("#%06X", value));
    }

    @Override
    public void fromJson(JsonElement json) {
        if (!json.isJsonPrimitive()) return;
        String s = json.getAsString().replace("#", "");
        try {
            set(Integer.parseInt(s, 16));
        } catch (NumberFormatException ignored) {
        }
    }

    public static float[] hsv(int rgb) {
        float r = (rgb >> 16 & 0xFF) / 255f;
        float g = (rgb >> 8 & 0xFF) / 255f;
        float b = (rgb & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float d = max - min;

        float h;
        if (d == 0) h = 0;
        else if (max == r) h = ((g - b) / d) % 6;
        else if (max == g) h = (b - r) / d + 2;
        else h = (r - g) / d + 4;
        h /= 6;
        if (h < 0) h += 1;

        return new float[]{h, max == 0 ? 0 : d / max, max};
    }

    public static int fromHsv(float h, float s, float v) {
        float c = v * s;
        float hp = (h % 1f + 1f) % 1f * 6f;
        float x = c * (1 - Math.abs(hp % 2 - 1));
        float r, g, b;
        if (hp < 1) { r = c; g = x; b = 0; }
        else if (hp < 2) { r = x; g = c; b = 0; }
        else if (hp < 3) { r = 0; g = c; b = x; }
        else if (hp < 4) { r = 0; g = x; b = c; }
        else if (hp < 5) { r = x; g = 0; b = c; }
        else { r = c; g = 0; b = x; }
        float m = v - c;
        return Math.round((r + m) * 255) << 16 | Math.round((g + m) * 255) << 8 | Math.round((b + m) * 255);
    }
}
