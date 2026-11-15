package com.cyanite.client.config;

import com.cyanite.client.modules.render.Waypoints;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Reads Xaero's Minimap waypoints for {@link Imports}. They're in {@code xaero/minimap/} (older versions:
 * {@code XaeroWaypoints/}), a folder per server ({@code Multiplayer_<address>}) or world (its save folder),
 * then one per dimension ({@code dim%0}, {@code dim%-1}, {@code dim%1}), holding text files with a
 * {@code waypoint:name:initials:x:y:z:color:disabled:type:...} line each.
 */
public final class XaeroImport {
    private static final String MULTIPLAYER = "Multiplayer_";

    private XaeroImport() {
    }

    static List<Path> roots() {
        Path game = FabricLoader.getInstance().getGameDir();
        return List.of(game.resolve("xaero").resolve("minimap"), game.resolve("XaeroWaypoints"));
    }

    /** Xaero's waypoints by the server or world Cyanite keeps them under; deathpoints and Realms left out. */
    static Map<String, List<Waypoints.Waypoint>> waypoints() {
        Map<String, List<Waypoints.Waypoint>> out = new LinkedHashMap<>();
        List<String> addresses = null;
        Map<String, String> worlds = null;
        for (Path root : roots()) {
            for (Path world : list(root)) {
                if (!Files.isDirectory(world)) continue;
                String name = world.getFileName().toString();
                if (name.startsWith("Realms_") || name.equals("backup")) continue;
                if (addresses == null) {
                    addresses = Imports.serverAddresses();
                    worlds = Imports.worlds();
                }
                String place = name.startsWith(MULTIPLAYER)
                    ? Imports.placeFor(name.substring(MULTIPLAYER.length()), addresses, Map.of())
                    : Imports.worldFor(name, worlds);
                if (place == null) continue;
                for (Path dim : list(world)) {
                    String dimension = dimension(dim.getFileName().toString());
                    if (dimension == null || !Files.isDirectory(dim)) continue;
                    for (Path file : list(dim)) {
                        if (!file.getFileName().toString().endsWith(".txt") || !Files.isRegularFile(file)) continue;
                        List<Waypoints.Waypoint> wps = read(file, dimension);
                        if (!wps.isEmpty()) out.computeIfAbsent(place, p -> new ArrayList<>()).addAll(wps);
                    }
                }
            }
        }
        return out;
    }

    /** "dim%0" as the overworld, "dim%-1" the nether, "dim%1" the end, "dim%mod$place" as mod:place; null for anything else. */
    static String dimension(String folder) {
        if (!folder.startsWith("dim%")) return null;
        String id = folder.substring(4);
        return switch (id) {
            case "0" -> "minecraft:overworld";
            case "-1" -> "minecraft:the_nether";
            case "1" -> "minecraft:the_end";
            default -> id.contains("$") ? id.replaceFirst("\\$", ":") : null;
        };
    }

    private static List<Waypoints.Waypoint> read(Path file, String dimension) {
        List<Waypoints.Waypoint> wps = new ArrayList<>();
        try {
            if (Files.size(file) > Imports.MAX_FILE) throw new IOException("too big");
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                Waypoints.Waypoint wp = parse(line, dimension);
                if (wp != null) wps.add(wp);
            }
        } catch (IOException | RuntimeException e) {
            // Their file, not our bug: a warning, not an error.
            Imports.LOGGER.warn("Couldn't read Xaero's {}: {}", file.getFileName(), e.toString());
        }
        return wps;
    }

    /** One "waypoint:..." line, or null for any other line, a deathpoint, or one that doesn't parse. */
    static Waypoints.Waypoint parse(String line, String dimension) {
        if (!line.startsWith("waypoint:")) return null;
        String[] f = line.split(":", -1);
        if (f.length < 9) return null;
        // A colon in a name is written as "§§".
        String name = f[1].replace("§§", ":").strip();
        // Type 1 is a deathpoint; older ones are named by translation key.
        if (f[8].strip().equals("1") || name.startsWith("gui.xaero_deathpoint")) return null;
        try {
            int x = Integer.parseInt(f[3].strip());
            // "~" is a waypoint without a height.
            int y = f[4].strip().equals("~") ? 64 : Integer.parseInt(f[4].strip());
            int z = Integer.parseInt(f[5].strip());
            ChatFormatting format = ChatFormatting.getById(Integer.parseInt(f[6].strip()));
            Integer rgb = format == null ? null : format.getColor();
            int color = rgb == null ? Waypoints.PALETTE[0] : rgb;
            boolean hidden = Boolean.parseBoolean(f[7].strip());
            return new Waypoints.Waypoint(name.isEmpty() ? f[2].strip() : name, x, y, z, dimension, color, hidden);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<Path> list(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.sorted().toList();
        } catch (IOException e) {
            Imports.LOGGER.warn("Couldn't list Xaero's {}: {}", dir.getFileName(), e.toString());
            return List.of();
        }
    }
}
