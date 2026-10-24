package com.cyanite.client.system.setting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;

/** Any blocks from the game, picked in a searchable list. Saved by id, like "minecraft:spawner". */
public class BlockListSetting extends Setting<List<Block>> {
    public BlockListSetting(String name, Block... defaults) {
        super(name, List.of(defaults));
    }

    public boolean contains(Block block) {
        return value.contains(block);
    }

    /** Adds it if it's not there, takes it out if it is. */
    public void toggle(Block block) {
        List<Block> next = new ArrayList<>(value);
        if (!next.remove(block)) next.add(block);
        set(List.copyOf(next));
    }

    public void clear() {
        set(List.of());
    }

    @Override
    public JsonElement toJson() {
        JsonArray array = new JsonArray();
        for (Block block : value) array.add(BuiltInRegistries.BLOCK.getKey(block).toString());
        return array;
    }

    @Override
    public void fromJson(JsonElement json) {
        if (!(json instanceof JsonArray array)) return;
        List<Block> blocks = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonPrimitive()) continue;
            Identifier id = Identifier.tryParse(element.getAsString());
            if (id == null) continue;
            BuiltInRegistries.BLOCK.getOptional(id).ifPresent(block -> {
                if (!blocks.contains(block)) blocks.add(block);
            });
        }
        set(List.copyOf(blocks));
    }
}
