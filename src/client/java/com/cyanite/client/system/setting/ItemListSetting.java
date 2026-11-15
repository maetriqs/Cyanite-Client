package com.cyanite.client.system.setting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/** Any items from the game, picked in a searchable list. Saved by id, like "minecraft:totem_of_undying". */
public class ItemListSetting extends Setting<List<Item>> {
    public ItemListSetting(String name, Item... defaults) {
        super(name, List.of(defaults));
    }

    public boolean contains(Item item) {
        return value.contains(item);
    }

    /** Adds it if it's not there, takes it out if it is. */
    public void toggle(Item item) {
        List<Item> next = new ArrayList<>(value);
        if (!next.remove(item)) next.add(item);
        set(List.copyOf(next));
    }

    public void clear() {
        set(List.of());
    }

    @Override
    protected JsonElement write(List<Item> value) {
        JsonArray array = new JsonArray();
        for (Item item : value) array.add(BuiltInRegistries.ITEM.getKey(item).toString());
        return array;
    }

    @Override
    public void fromJson(JsonElement json) {
        if (!(json instanceof JsonArray array)) return;
        List<Item> items = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonPrimitive()) continue;
            Identifier id = Identifier.tryParse(element.getAsString());
            if (id == null) continue;
            BuiltInRegistries.ITEM.getOptional(id).ifPresent(item -> {
                if (item != Items.AIR && !items.contains(item)) items.add(item);
            });
        }
        set(List.copyOf(items));
    }
}
