package com.cyanite.client.modules.world.area;

import com.cyanite.client.config.Config;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** What Area Miner should dig, kept in {@code cyanite/area-miner.json} between sessions. */
public final class AreaSelection {
    private static final Logger LOGGER = LogUtils.getLogger();

    public enum Source { COORDS, LITEMATICA }

    /** Litematica only: the whole box of every sub-region, or just where the schematic has blocks. */
    public enum Shape { BOX, BLOCKS }

    public Source source = Source.COORDS;
    public BlockPos pos1;
    public BlockPos pos2;
    public String placementKey;
    public String placementName;
    public BlockPos placementOrigin;
    public Shape shape = Shape.BOX;
    /** Server and dimension the corners were picked in, so their outline only shows there. */
    public String world;

    private static Path file() {
        return Config.dir().resolve("area-miner.json");
    }

    public static AreaSelection load() {
        AreaSelection s = new AreaSelection();
        Path file = file();
        if (!Files.exists(file)) return s;
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (json.has("source")) s.source = "litematica".equals(json.get("source").getAsString()) ? Source.LITEMATICA : Source.COORDS;
            s.pos1 = pos(json.get("pos1"));
            s.pos2 = pos(json.get("pos2"));
            if (json.get("placement") instanceof JsonObject p) {
                s.placementKey = p.has("key") ? p.get("key").getAsString() : null;
                s.placementName = p.has("name") ? p.get("name").getAsString() : null;
                s.placementOrigin = pos(p.get("origin"));
            }
            if (json.has("shape")) s.shape = "blocks".equals(json.get("shape").getAsString()) ? Shape.BLOCKS : Shape.BOX;
            if (json.has("world")) s.world = json.get("world").getAsString();
        } catch (Exception e) {
            LOGGER.error("Couldn't read {}", file, e);
        }
        return s;
    }

    public void save() {
        JsonObject json = new JsonObject();
        json.addProperty("source", source == Source.LITEMATICA ? "litematica" : "coords");
        if (pos1 != null) json.add("pos1", json(pos1));
        if (pos2 != null) json.add("pos2", json(pos2));
        if (placementKey != null) {
            JsonObject p = new JsonObject();
            p.addProperty("key", placementKey);
            if (placementName != null) p.addProperty("name", placementName);
            if (placementOrigin != null) p.add("origin", json(placementOrigin));
            json.add("placement", p);
        }
        json.addProperty("shape", shape == Shape.BLOCKS ? "blocks" : "box");
        if (world != null) json.addProperty("world", world);
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), new GsonBuilder().setPrettyPrinting().create().toJson(json));
        } catch (IOException e) {
            LOGGER.error("Couldn't write {}", file(), e);
        }
    }

    private static JsonArray json(BlockPos pos) {
        JsonArray a = new JsonArray();
        a.add(pos.getX());
        a.add(pos.getY());
        a.add(pos.getZ());
        return a;
    }

    private static BlockPos pos(JsonElement e) {
        if (!(e instanceof JsonArray a) || a.size() != 3) return null;
        return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
    }

    /** Reads "x y z", "x, y, z" or "x,y,z". Null if it isn't three whole numbers. */
    public static BlockPos parse(String text) {
        String[] parts = text.trim().split("[\\s,]+");
        if (parts.length != 3) return null;
        try {
            return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String format(BlockPos pos) {
        return pos == null ? "" : pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
