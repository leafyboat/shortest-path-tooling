package shortestpath.dump;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import net.runelite.cache.IndexType;
import net.runelite.cache.WorldMapManager;
import net.runelite.cache.definitions.MapSquareDefinition;
import net.runelite.cache.definitions.WorldMapCompositeDefinition;
import net.runelite.cache.definitions.ZoneDefinition;
import net.runelite.cache.definitions.loaders.WorldMapCompositeLoader;
import net.runelite.cache.definitions.loaders.WorldMapLoader;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.FSFile;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Store;
import org.junit.Assume;
import org.junit.Test;

/**
 * Generates {@code world-map-layout.tsv}, consumed by the plugin's
 * {@code shortestpath.WorldMapLayout}: every area a world map draws away
 * from its real position, or shows above the ground floor.
 *
 * <p>
 * The cache's world map composite places each 64x64 map square and 8x8 zone
 * of the world, over a range of planes, at a display position on one of the
 * world maps. Most of the world is drawn in place on the ground floor; the
 * rest is written out, merged into rectangles per map, lowest plane, number
 * of planes and offset. The Troll Stronghold map, for example, draws the
 * stronghold's first floor 64 tiles east and 32 tiles north of where it is.
 * </p>
 *
 * <p>
 * Run with:
 * </p>
 * <pre>
 *   ./gradlew worldMapLayoutDump -PworldMapLayoutCacheDir=$PWD/cache
 * </pre>
 * <p>
 * Then copy the output over the plugin resource:
 * </p>
 * <pre>
 *   cp build/world-map-layout/world-map-layout.tsv \
 *     ../shortest-path/src/main/resources/world-map-layout.tsv
 * </pre>
 */
public class WorldMapLayoutDumperTest
{
    @Test
    public void dumpWorldMapLayout() throws Exception
    {
        Assume.assumeTrue(
            "Enable with -Dworld.map.layout.dump=true and supply -Dworld.map.layout.cacheDir / "
                + "-Dworld.map.layout.output",
            Boolean.getBoolean("world.map.layout.dump"));

        String cacheDir = CacheUtils.requiredProperty("world.map.layout.cacheDir");
        String outputPath = CacheUtils.requiredProperty("world.map.layout.output");

        List<Area> areas = new ArrayList<>();
        try (Store store = CacheUtils.openStore(cacheDir))
        {
            Index index = store.getIndex(IndexType.WORLDMAP);
            Map<Integer, String> names = new HashMap<>();
            for (FSFile file : files(store, index, WorldMapManager.DETAILS_ID))
            {
                names.put(file.getFileId(), new WorldMapLoader().load(file.getContents(), file.getFileId()).getName());
            }
            Map<Long, Long> offsetByRealZone = new HashMap<>();
            for (FSFile file : files(store, index, WorldMapManager.COMPOSITEMAP_ID))
            {
                WorldMapCompositeDefinition composite = new WorldMapCompositeLoader()
                    .configureForRevision(index.getRevision())
                    .load(file.getContents());
                String map = names.get(file.getFileId());
                Collection<Zone> zones = drawnZones(composite);
                warnIfDrawnTwice(map, zones, offsetByRealZone);
                areas.addAll(relocatedAreas(map, zones));
            }
        }
        areas.sort(Comparator.<Area, String>comparing(a -> a.map)
            .thenComparingInt(a -> a.plane)
            .thenComparingInt(a -> a.y)
            .thenComparingInt(a -> a.x)
            .thenComparingInt(a -> a.planes)
            .thenComparingInt(a -> a.dx)
            .thenComparingInt(a -> a.dy));

        writeOutput(Path.of(outputPath), areas);
        System.out.println("Wrote " + areas.size() + " world map areas to " + outputPath);
    }

    private static Collection<FSFile> files(Store store, Index index, int archiveId) throws IOException
    {
        Archive archive = index.getArchive(archiveId);
        return archive.getFiles(store.getStorage().loadArchive(archive)).getFiles();
    }

    /**
     * Every 8x8 zone the map draws, keyed by where it is drawn. A zone
     * overrides a map square drawn at the same place, as the client does.
     */
    private static Collection<Zone> drawnZones(WorldMapCompositeDefinition composite)
    {
        Map<Long, Zone> drawn = new HashMap<>();
        for (MapSquareDefinition square : composite.getMapSquareDefinitions())
        {
            for (int zx = 0; zx < 8; zx++)
            {
                for (int zy = 0; zy < 8; zy++)
                {
                    Zone zone = new Zone(square.getSourceSquareX() * 8 + zx, square.getSourceSquareZ() * 8 + zy,
                        square.getDisplaySquareX() * 8 + zx, square.getDisplaySquareZ() * 8 + zy,
                        square.getMinLevel(), square.getLevels());
                    drawn.put(zone.displayKey(), zone);
                }
            }
        }
        for (ZoneDefinition definition : composite.getZoneDefinitions())
        {
            Zone zone = new Zone(definition.getSourceSquareX() * 8 + definition.getSourceZoneX(),
                definition.getSourceSquareZ() * 8 + definition.getSourceZoneZ(),
                definition.getDisplaySquareX() * 8 + definition.getDisplayZoneX(),
                definition.getDisplaySquareZ() * 8 + definition.getDisplayZoneZ(),
                definition.getMinLevel(), definition.getLevels());
            drawn.put(zone.displayKey(), zone);
        }
        return drawn.values();
    }

    /**
     * The plugin converts a world point to a map position without knowing
     * which map is open, which holds while every real zone and plane is drawn
     * at one offset across all maps.
     */
    private static void warnIfDrawnTwice(String map, Collection<Zone> zones, Map<Long, Long> offsetByRealZone)
    {
        for (Zone zone : zones)
        {
            long offset = ((long) zone.offsetX() << 32) | (zone.offsetY() & 0xffffffffL);
            for (int plane = zone.minLevel; plane < zone.minLevel + zone.levels; plane++)
            {
                long realZone = ((long) zone.sourceX << 40) | ((long) zone.sourceY << 8) | plane;
                Long previous = offsetByRealZone.putIfAbsent(realZone, offset);
                if (previous != null && previous != offset)
                {
                    System.err.println("WARNING: " + map + " draws zone (" + zone.sourceX * 8 + ", "
                        + zone.sourceY * 8 + ", " + plane + ") at a second offset");
                }
            }
        }
    }

    /**
     * The zones not drawn in place on the ground floor, merged into
     * rectangles per lowest plane, number of planes and offset.
     */
    private static List<Area> relocatedAreas(String map, Collection<Zone> zones)
    {
        Map<List<Integer>, TreeSet<Long>> groups = new HashMap<>();
        for (Zone zone : zones)
        {
            if (zone.offsetX() != 0 || zone.offsetY() != 0 || zone.minLevel != 0)
            {
                groups.computeIfAbsent(List.of(zone.minLevel, zone.levels, zone.offsetX(), zone.offsetY()),
                    k -> new TreeSet<>()).add(((long) zone.sourceY << 32) | zone.sourceX);
            }
        }
        List<Area> areas = new ArrayList<>();
        groups.forEach((group, sourceZones) ->
        {
            for (int[] r : rectangles(sourceZones))
            {
                areas.add(new Area(map, r[0] * 8, r[1] * 8, group.get(0), r[2] * 8, r[3] * 8, group.get(1),
                    group.get(2), group.get(3)));
            }
        });
        return areas;
    }

    /**
     * Covers the zones, sorted by row then column, with {x, y, width, height}
     * rectangles in zones: runs along each row, stacked upward while the run
     * below starts at the same column with the same width.
     */
    private static List<int[]> rectangles(TreeSet<Long> zones)
    {
        List<int[]> rectangles = new ArrayList<>();
        Map<Long, int[]> topByRun = new HashMap<>();
        int[] run = null;
        for (long zone : zones)
        {
            int x = (int) zone;
            int y = (int) (zone >>> 32);
            if (run != null && run[1] == y && run[0] + run[2] == x)
            {
                run[2]++;
                continue;
            }
            stack(run, topByRun, rectangles);
            run = new int[]{x, y, 1, 1};
        }
        stack(run, topByRun, rectangles);
        return rectangles;
    }

    private static void stack(int[] run, Map<Long, int[]> topByRun, List<int[]> rectangles)
    {
        if (run == null)
        {
            return;
        }
        long key = ((long) run[0] << 32) | run[2];
        int[] below = topByRun.get(key);
        if (below != null && below[1] + below[3] == run[1])
        {
            below[3]++;
        }
        else
        {
            topByRun.put(key, run);
            rectangles.add(run);
        }
    }

    private static void writeOutput(Path outputPath, List<Area> areas) throws IOException
    {
        Path parent = outputPath.toAbsolutePath().getParent();
        if (parent != null)
        {
            Files.createDirectories(parent);
        }
        try (BufferedWriter w = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8))
        {
            // The column header must be the first line. Banner-comment lines
            // below it carry five trailing tabs to keep the six-column shape.
            w.write("# World map\tOrigin\tWidth\tHeight\tPlanes\tOffset\n");
            w.write("# Areas the game's world maps draw away from their real position, or show above the ground floor.\t\t\t\t\t\n");
            w.write("# Origin is the real area's south-west tile and lowest plane; Planes counts the planes drawn from it;\t\t\t\t\t\n");
            w.write("# Offset is how far east and north of the real area the map draws it.\t\t\t\t\t\n");
            w.write("#\t\t\t\t\t\n");
            w.write("# Auto-generated by WorldMapLayoutDumperTest in the shortest-path-tooling repo.\t\t\t\t\t\n");
            w.write("# Do not edit by hand; the Extract Collision Map workflow regenerates it, or run:\t\t\t\t\t\n");
            w.write("#   ./gradlew worldMapLayoutDump -PworldMapLayoutCacheDir=$PWD/cache\t\t\t\t\t\n");
            w.write("#\t\t\t\t\t\n");
            for (Area a : areas)
            {
                w.write(a.map + "\t" + a.x + " " + a.y + " " + a.plane + "\t" + a.width + "\t" + a.height + "\t"
                    + a.planes + "\t" + a.dx + " " + a.dy + "\n");
            }
        }
    }

    /** An 8x8 zone of the world, in zone coordinates, and where a map draws it. */
    private static final class Zone
    {
        final int sourceX;
        final int sourceY;
        final int displayX;
        final int displayY;
        final int minLevel;
        final int levels;

        Zone(int sourceX, int sourceY, int displayX, int displayY, int minLevel, int levels)
        {
            this.sourceX = sourceX;
            this.sourceY = sourceY;
            this.displayX = displayX;
            this.displayY = displayY;
            this.minLevel = minLevel;
            this.levels = levels;
        }

        long displayKey()
        {
            return ((long) displayX << 32) | displayY;
        }

        int offsetX()
        {
            return (displayX - sourceX) * 8;
        }

        int offsetY()
        {
            return (displayY - sourceY) * 8;
        }
    }

    /** One output row, in tiles. */
    private static final class Area
    {
        final String map;
        final int x;
        final int y;
        final int plane;
        final int width;
        final int height;
        final int planes;
        final int dx;
        final int dy;

        Area(String map, int x, int y, int plane, int width, int height, int planes, int dx, int dy)
        {
            this.map = map;
            this.x = x;
            this.y = y;
            this.plane = plane;
            this.width = width;
            this.height = height;
            this.planes = planes;
            this.dx = dx;
            this.dy = dy;
        }
    }
}
