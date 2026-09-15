package shortestpath.dump;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import net.runelite.cache.fs.Store;
import net.runelite.cache.region.Region;
import net.runelite.cache.region.RegionLoader;
import net.runelite.cache.util.XteaKeyManager;
import org.junit.Assume;
import org.junit.Test;

/**
 * Generates the regionId -> LeagueRegion mapping consumed by
 * {@code shortestpath.leagues.LeagueRegionChecker}.
 *
 * <p>
 * Reads the curated bounding-box source-of-truth at
 * {@code src/test/resources/leagues_regions.tsv}, walks every loaded map
 * region (64x64 chunk) in the supplied OSRS cache, and assigns each
 * region id to the league region whose bounding boxes it falls inside.
 * On overlap the box with greater intersection area wins; when multiple
 * boxes tie, the earliest row in the source file wins. Anything
 * uncovered defaults to NEUTRAL and is omitted from the output (the
 * runtime checker already returns NEUTRAL for unmapped regions).
 * </p>
 *
 * <p>
 * Run with:
 * </p>
 * <pre>
 *   ./gradlew leagueRegionDump \
 *     -PleagueRegionsCacheDir=$PWD/cache \
 *     -PleagueRegionsXteaPath=$PWD/keys.json
 * </pre>
 * <p>
 * Then copy the output over the plugin resource:
 * </p>
 * <pre>
 *   cp build/league-regions/regions.tsv \
 *     ../shortest-path/src/main/resources/leagues/regions.tsv
 * </pre>
 */
public class LeagueRegionDumperTest
{
    private static final String SOURCE_RESOURCE = "/leagues_regions.tsv";
    private static final int REGION_SIZE = 64;

    @Test
    public void dumpLeagueRegions() throws Exception
    {
        Assume.assumeTrue(
            "Enable with -Dleague.regions.dump=true and supply -Dleague.regions.cacheDir / "
                + "-Dleague.regions.xteaPath / -Dleague.regions.output",
            Boolean.getBoolean("league.regions.dump"));

        String cacheDir = CacheUtils.requiredProperty("league.regions.cacheDir");
        String xteaPath = CacheUtils.requiredProperty("league.regions.xteaPath");
        String outputPath = CacheUtils.requiredProperty("league.regions.output");

        List<BoundingBox> boxes = loadSourceBoxes();
        if (boxes.isEmpty())
        {
            throw new IllegalStateException("No bounding boxes loaded from " + SOURCE_RESOURCE);
        }

        XteaKeyManager xteaKeyManager = CacheUtils.loadXteaKeys(xteaPath);

        // Collect candidate region IDs from two sources:
        //   1. The cache's loaded regions (covers everything that has unencrypted content).
        //   2. Every region that intersects any source bbox (covers underground / instance
        //      regions that the cache may not return without xtea keys).
        // Combining the two lets the dumper run usefully even on a keys.json containing
        // only validated keys, while still mapping uncached underground areas covered by
        // the source-of-truth bboxes.
        Set<Integer> candidateRegionIds = new HashSet<>();
        try (Store store = CacheUtils.openStore(cacheDir))
        {
            RegionLoader regionLoader = CacheUtils.loadRegions(store, xteaKeyManager);

            for (Region region : regionLoader.getRegions())
            {
                candidateRegionIds.add(region.getRegionID());
            }
        }
        addRegionIdsCoveredByBoxes(candidateRegionIds, boxes);

        TreeMap<Integer, String> assignments = new TreeMap<>();
        for (int regionId : candidateRegionIds)
        {
            int chunkX = (regionId >> 8) & 0xff;
            int chunkY = regionId & 0xff;
            int xMin = chunkX * REGION_SIZE;
            int yMin = chunkY * REGION_SIZE;
            int xMax = xMin + REGION_SIZE - 1;
            int yMax = yMin + REGION_SIZE - 1;

            String leagueRegion = classify(boxes, xMin, yMin, xMax, yMax);
            if (leagueRegion != null)
            {
                assignments.put(regionId, leagueRegion);
            }
        }

        writeOutput(Path.of(outputPath), assignments);
        System.out.println("Wrote " + assignments.size() + " region assignments to " + outputPath);
    }

    private static void addRegionIdsCoveredByBoxes(Set<Integer> out, List<BoundingBox> boxes)
    {
        for (BoundingBox box : boxes)
        {
            int chunkXMin = box.xMin / REGION_SIZE;
            int chunkXMax = box.xMax / REGION_SIZE;
            int chunkYMin = box.yMin / REGION_SIZE;
            int chunkYMax = box.yMax / REGION_SIZE;
            for (int cx = chunkXMin; cx <= chunkXMax; cx++)
            {
                for (int cy = chunkYMin; cy <= chunkYMax; cy++)
                {
                    out.add((cx << 8) | cy);
                }
            }
        }
    }

    private static String classify(List<BoundingBox> boxes, int rxMin, int ryMin, int rxMax, int ryMax)
    {
        // Score each candidate by overlap area; earliest box wins on ties so
        // operators can lock in priority by row order in the source file.
        BoundingBox best = null;
        int bestArea = 0;
        for (BoundingBox box : boxes)
        {
            int ox = Math.max(0, Math.min(rxMax, box.xMax) - Math.max(rxMin, box.xMin) + 1);
            int oy = Math.max(0, Math.min(ryMax, box.yMax) - Math.max(ryMin, box.yMin) + 1);
            int area = ox * oy;
            if (area > bestArea)
            {
                bestArea = area;
                best = box;
            }
        }
        return best == null ? null : best.region;
    }

    private static List<BoundingBox> loadSourceBoxes() throws IOException
    {
        try (InputStream in = LeagueRegionDumperTest.class.getResourceAsStream(SOURCE_RESOURCE))
        {
            if (in == null)
            {
                throw new IllegalStateException("Missing source resource " + SOURCE_RESOURCE);
            }
            byte[] bytes = Objects.requireNonNull(in).readAllBytes();
            String body = new String(bytes, StandardCharsets.UTF_8);
            List<BoundingBox> boxes = new ArrayList<>();
            int lineNumber = 0;
            for (String rawLine : body.split("\\R"))
            {
                lineNumber++;
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#"))
                {
                    continue;
                }
                String[] parts = line.split("\\s+");
                if (parts.length != 5)
                {
                    System.err.println("Skipping malformed source row " + lineNumber + ": '" + rawLine + "'");
                    continue;
                }
                try
                {
                    String region = parts[0].toUpperCase(Locale.ROOT);
                    int xMin = Integer.parseInt(parts[1]);
                    int xMax = Integer.parseInt(parts[2]);
                    int yMin = Integer.parseInt(parts[3]);
                    int yMax = Integer.parseInt(parts[4]);
                    if (xMin > xMax || yMin > yMax)
                    {
                        System.err.println("Skipping inverted box on row " + lineNumber);
                        continue;
                    }
                    boxes.add(new BoundingBox(region, xMin, xMax, yMin, yMax));
                }
                catch (NumberFormatException e)
                {
                    System.err.println("Skipping non-numeric box on row " + lineNumber + ": " + rawLine);
                }
            }
            return boxes;
        }
    }

    private static void writeOutput(Path outputPath, Map<Integer, String> assignments) throws IOException
    {
        Path parent = outputPath.toAbsolutePath().getParent();
        if (parent != null)
        {
            Files.createDirectories(parent);
        }
        try (BufferedWriter w = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8))
        {
            // The column header must be the first line so scripts/check_tsv.py
            // recognises it. Banner-comment lines below it carry a trailing
            // tab to keep the two-column shape consistent.
            w.write("# regionId\tLeagueRegion\n");
            w.write("# Demonic Pacts League: OSRS map region id -> league region\t\n");
            w.write("#\t\n");
            w.write("# Auto-generated by LeagueRegionDumperTest from leagues_regions.tsv\t\n");
            w.write("# in the shortest-path-tooling repo. Do not edit by hand; regenerate with:\t\n");
            w.write("#   ./gradlew leagueRegionDump \\\t\n");
            w.write("#     -PleagueRegionsCacheDir=$PWD/cache \\\t\n");
            w.write("#     -PleagueRegionsXteaPath=$PWD/keys.json\t\n");
            w.write("#\t\n");
            for (Map.Entry<Integer, String> e : assignments.entrySet())
            {
                w.write(e.getKey() + "\t" + e.getValue());
                w.newLine();
            }
        }
    }

    private static final class BoundingBox
    {
        final String region;
        final int xMin;
        final int xMax;
        final int yMin;
        final int yMax;

        BoundingBox(String region, int xMin, int xMax, int yMin, int yMax)
        {
            this.region = region;
            this.xMin = xMin;
            this.xMax = xMax;
            this.yMin = yMin;
            this.yMax = yMax;
        }
    }
}
