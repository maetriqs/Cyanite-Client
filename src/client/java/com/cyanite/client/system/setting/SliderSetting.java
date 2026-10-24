package com.cyanite.client.system.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.function.DoubleFunction;

public class SliderSetting extends Setting<Double> {
    public final double min;
    public final double max;
    public final double step;
    private DoubleFunction<String> format = SliderSetting::trim;
    /** Each stretch of the track covers ten times the last, for ranges that run from ones to billions. */
    private boolean logarithmic;

    public SliderSetting(String name, double defaultValue, double min, double max, double step) {
        super(name, defaultValue);
        this.min = min;
        this.max = max;
        this.step = step;
    }

    public SliderSetting format(DoubleFunction<String> format) {
        this.format = format;
        return this;
    }

    /**
     * Spaces the track out by powers of ten, so a slider from $1 to $10M is as easy to set at $40
     * as at $4M. Values snap to two significant figures instead of to the step. Needs {@code min > 0}.
     */
    public SliderSetting logarithmic() {
        logarithmic = true;
        value = sanitize(value);
        return this;
    }

    public SliderSetting percent() {
        return format(v -> Math.round(v * 100) + "%");
    }

    public String display() {
        return format.apply(value);
    }

    /** Position of the value along the track, 0 to 1. */
    public double fraction() {
        if (logarithmic) return Math.log(value / min) / Math.log(max / min);
        return (value - min) / (max - min);
    }

    public void setFraction(double fraction) {
        double f = Math.max(0, Math.min(1, fraction));
        set(logarithmic ? min * Math.pow(max / min, f) : min + f * (max - min));
    }

    @Override
    protected Double sanitize(Double v) {
        if (logarithmic) {
            double clamped = Math.max(min, Math.min(max, v));
            // Two significant figures: $4,500, $120K, $2.5M.
            double unit = Math.pow(10, Math.floor(Math.log10(clamped)) - 1);
            return Math.max(min, Math.min(max, Math.round(clamped / unit) * unit));
        }
        double snapped = step > 0 ? Math.round((v - min) / step) * step + min : v;
        // Kill float noise like 0.30000000000000004 so saved configs stay readable.
        snapped = Math.round(snapped * 1e6) / 1e6;
        return Math.max(min, Math.min(max, snapped));
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(value);
    }

    @Override
    public void fromJson(JsonElement json) {
        if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber()) set(json.getAsDouble());
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.format(java.util.Locale.ROOT, "%.2f", v).replaceAll("0+$", "");
    }
}
