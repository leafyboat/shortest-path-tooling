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
import java.util.Set;
import java.util.TreeMap;
import net.runelite.cache.fs.Store;
import net.runelite.cache.region.Region;
import net.runelite.cache.region.RegionLoader;
import net.runelite.cache.util.XteaKeyManager;
import org.junit.Assume;
import org.junit.Test;

/**
 * Generates the regionId -&gt; F2P/MEMBERS mapping consumed by
 * {@code shortestpath.f2p.F2pRegionChecker}.
 *
 * <p>
 * Reads the curated bounding-box source-of-truth at
 * {@code src/test/resources/f2p_regions.tsv}, walks every surface-level map
 * region (chunkY &lt; 100, i.e. world y &lt; 6400) loaded from the supplied OSRS
 * cache, and tags each chunk F2P if it overlaps any bounding box, or MEMBERS
 * otherwise. Underground chunks (chunkY &ge; 100) are omitted so they resolve
 * to UNKNOWN at runtime, which {@code F2pRegionChecker} treats as permissive
 * (F2P-accessible). This lets F2P routes pass through the Edgeville Dungeon,
 * Varrock Sewers, Dwarven Mine, etc. without needing explicit underground
 * bounding boxes.
 * </p>
 *
 * <p>
 * Run with:
 * </p>
 * <pre>
 *   collision-map-update/download-latest-cache.sh   # produces ./cache and ./keys.json
 *   sed -i '' 's/mapsquare/region/g; s/key/keys/g' keys.json
 *   ./gradlew f2pRegionDump \
 *     -Pf2pRegionsCacheDir=$PWD/cache \
 *     -Pf2pRegionsXteaPath=$PWD/keys.json
 * </pre>
 * <p>
 * Then copy the output over the plugin resource:
 * </p>
 * <pre>
 *   cp build/f2p-regions/regions.tsv \
 *     shortest-path/src/main/resources/f2p/regions.tsv
 * </pre>
 */
public class F2pRegionDumperTest
{
    private static final String SOURCE_RESOURCE = "/f2p_regions.tsv";
    private static final int REGION_SIZE = 64;

    /**
     * chunkY threshold: chunks with chunkY &ge; this value are underground
     * (world y &ge; 6400) and are omitted from the output.
     */
    private static final int UNDERGROUND_CHUNK_Y = 100;

    @Test
    public void dumpF2pRegions() throws Exception
    {
        Assume.assumeTrue(
            "Enable with -Df2p.regions.dump=true and supply -Df2p.regions.cacheDir / "
                + "-Df2p.regions.xteaPath / -Df2p.regions.output",
            Boolean.getBoolean("f2p.regions.dump"));

        String cacheDir = CacheUtils.requiredProperty("f2p.regions.cacheDir");
        String xteaPath = CacheUtils.requiredProperty("f2p.regions.xteaPath");
        String outputPath = CacheUtils.requiredProperty("f2p.regions.output");

        List<BoundingBox> f2pBoxes = loadF2pBoxes();
        if (f2pBoxes.isEmpty())
        {
            throw new IllegalStateException("No F2P bounding boxes loaded from " + SOURCE_RESOURCE);
        }

        XteaKeyManager xteaKeyManager = CacheUtils.loadXteaKeys(xteaPath);

        // Collect surface region IDs from two sources:
        //   1. The cache's loaded surface regions (needed to emit MEMBERS for
        //      non-F2P surface chunks — without this, members surface areas would
        //      silently default to UNKNOWN / permissive at runtime).
        //   2. Every surface chunk covered by an F2P bbox (ensures F2P areas are
        //      tagged even if the cache cannot decrypt those regions without xtea).
        Set<Integer> candidateRegionIds = new HashSet<>();
        try (Store store = CacheUtils.openStore(cacheDir))
        {
            RegionLoader regionLoader = CacheUtils.loadRegions(store, xteaKeyManager);

            for (Region region : regionLoader.getRegions())
            {
                int rid = region.getRegionID();
                if ((rid & 0xff) < UNDERGROUND_CHUNK_Y)
                {
                    candidateRegionIds.add(rid);
                }
            }
        }
        addRegionIdsFromBoxes(candidateRegionIds, f2pBoxes);

        TreeMap<Integer, String> assignments = new TreeMap<>();
        for (int regionId : candidateRegionIds)
        {
            int chunkX = (regionId >> 8) & 0xff;
            int chunkY = regionId & 0xff;
            int xMin = chunkX * REGION_SIZE;
            int yMin = chunkY * REGION_SIZE;
            int xMax = xMin + REGION_SIZE - 1;
            int yMax = yMin + REGION_SIZE - 1;

            boolean isF2p = overlapsAny(f2pBoxes, xMin, yMin, xMax, yMax);
            assignments.put(regionId, isF2p ? "F2P" : "MEMBERS");
        }

        writeOutput(Path.of(outputPath), assignments);
        long f2pCount = assignments.values().stream().filter("F2P"::equals).count();
        System.out.println("Wrote " + assignments.size() + " region assignments to " + outputPath
            + " (" + f2pCount + " F2P, " + (assignments.size() - f2pCount) + " MEMBERS)");
    }

    private static void addRegionIdsFromBoxes(Set<Integer> out, List<BoundingBox> boxes)
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

    private static boolean overlapsAny(List<BoundingBox> boxes, int rxMin, int ryMin, int rxMax, int ryMax)
    {
        for (BoundingBox box : boxes)
        {
            int ox = Math.max(0, Math.min(rxMax, box.xMax) - Math.max(rxMin, box.xMin) + 1);
            int oy = Math.max(0, Math.min(ryMax, box.yMax) - Math.max(ryMin, box.yMin) + 1);
            if (ox > 0 && oy > 0)
            {
                return true;
            }
        }
        return false;
    }

    private static List<BoundingBox> loadF2pBoxes() throws IOException
    {
        try (InputStream in = F2pRegionDumperTest.class.getResourceAsStream(SOURCE_RESOURCE))
        {
            if (in == null)
            {
                throw new IllegalStateException("Missing source resource " + SOURCE_RESOURCE);
            }
            byte[] bytes = in.readAllBytes();
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
                String tag = parts[0].toUpperCase(Locale.ROOT);
                if (!"F2P".equals(tag))
                {
                    System.err.println("Skipping non-F2P row " + lineNumber + " (tag=" + tag + ")");
                    continue;
                }
                try
                {
                    int xMin = Integer.parseInt(parts[1]);
                    int xMax = Integer.parseInt(parts[2]);
                    int yMin = Integer.parseInt(parts[3]);
                    int yMax = Integer.parseInt(parts[4]);
                    if (xMin > xMax || yMin > yMax)
                    {
                        System.err.println("Skipping inverted box on row " + lineNumber);
                        continue;
                    }
                    boxes.add(new BoundingBox(xMin, xMax, yMin, yMax));
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
            w.write("# regionId\tF2pTag\n");
            w.write("# OSRS map region id -> F2P|MEMBERS surface classification\t\n");
            w.write("#\t\n");
            w.write("# Auto-generated by F2pRegionDumperTest from f2p_regions.tsv\t\n");
            w.write("# in the shortest-path-tooling repo. Do not edit by hand; regenerate with:\t\n");
            w.write("#   ./gradlew f2pRegionDump \\\t\n");
            w.write("#     -Pf2pRegionsCacheDir=$PWD/cache \\\t\n");
            w.write("#     -Pf2pRegionsXteaPath=$PWD/keys.json\t\n");
            w.write("#\t\n");
            w.write("# Underground chunks (chunkY >= 100) are omitted; they resolve to UNKNOWN\t\n");
            w.write("# at runtime and are treated as permissive (F2P-accessible).\t\n");
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
        final int xMin;
        final int xMax;
        final int yMin;
        final int yMax;

        BoundingBox(int xMin, int xMax, int yMin, int yMax)
        {
            this.xMin = xMin;
            this.xMax = xMax;
            this.yMin = yMin;
            this.yMax = yMax;
        }
    }
}
