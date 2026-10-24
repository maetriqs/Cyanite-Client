package com.cyanite.client.system.setting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.List;

/** Installed mods, picked in a searchable list. Saved by mod id, like "voicechat". */
public class ModListSetting extends Setting<List<String>> {
    public ModListSetting(String name, String... defaults) {
        super(name, List.of(defaults));
    }

    public boolean contains(String modId) {
        return value.contains(modId);
    }

    /** Adds it if it's not there, takes it out if it is. */
    public void toggle(String modId) {
        List<String> next = new ArrayList<>(value);
        if (!next.remove(modId)) next.add(modId);
        set(List.copyOf(next));
    }

    public void clear() {
        set(List.of());
    }

    @Override
    public JsonElement toJson() {
        JsonArray array = new JsonArray();
        for (String id : value) array.add(id);
        return array;
    }

    @Override
    public void fromJson(JsonElement json) {
        if (!(json instanceof JsonArray array)) return;
        List<String> ids = new ArrayList<>();
        for (JsonElement element : array) {
            if (element.isJsonPrimitive() && !ids.contains(element.getAsString())) ids.add(element.getAsString());
        }
        set(List.copyOf(ids));
    }
}
