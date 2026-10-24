package com.cyanite.client.system.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/** A short line of text, typed straight into the settings list. */
public class StringSetting extends Setting<String> {
    public final int maxLength;
    private String placeholder = "";

    public StringSetting(String name, String defaultValue) {
        this(name, defaultValue, 32);
    }

    public StringSetting(String name, String defaultValue, int maxLength) {
        super(name, defaultValue);
        this.maxLength = maxLength;
    }

    /** Greyed-out text shown while the value is empty. */
    public StringSetting placeholder(String placeholder) {
        this.placeholder = placeholder;
        return this;
    }

    public String placeholder() {
        return placeholder;
    }

    public boolean isBlank() {
        return value.isBlank();
    }

    @Override
    protected String sanitize(String v) {
        String clean = v.replace("\n", "").replace("\r", "");
        return clean.length() > maxLength ? clean.substring(0, maxLength) : clean;
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
