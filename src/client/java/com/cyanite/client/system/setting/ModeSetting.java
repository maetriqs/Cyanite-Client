package com.cyanite.client.system.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.List;

public class ModeSetting extends Setting<String> {
    public final List<String> modes;

    public ModeSetting(String name, String defaultValue, List<String> modes) {
        super(name, defaultValue);
        this.modes = List.copyOf(modes);
    }

    public void cycle(int direction) {
        int i = modes.indexOf(value);
        set(modes.get(Math.floorMod(i + direction, modes.size())));
    }

    public boolean is(String mode) {
        return value.equals(mode);
    }

    @Override
    protected String sanitize(String v) {
        return modes.contains(v) ? v : value;
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(value);
    }

    @Override
    public void fromJson(JsonElement json) {
        if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) set(json.getAsString());
    }
}
