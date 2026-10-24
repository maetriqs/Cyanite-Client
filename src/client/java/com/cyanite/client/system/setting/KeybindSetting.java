package com.cyanite.client.system.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.platform.InputConstants;

/** A keyboard key, stored as its GLFW key code. {@link #NONE} means unbound. */
public class KeybindSetting extends Setting<Integer> {
    public static final int NONE = -1;

    public KeybindSetting(String name) {
        super(name, NONE);
    }

    public KeybindSetting(String name, int defaultKey) {
        super(name, defaultKey);
    }

    public boolean isBound() {
        return value != NONE;
    }

    public String display() {
        if (value == NONE) return "None";
        return InputConstants.Type.KEYSYM.getOrCreate(value).getDisplayName().getString();
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(value);
    }

    @Override
    public void fromJson(JsonElement json) {
        if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber()) set(json.getAsInt());
    }
}
