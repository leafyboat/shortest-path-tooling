package shortestpath.dump;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.cache.ObjectManager;
import net.runelite.cache.definitions.ObjectDefinition;
import net.runelite.cache.fs.Store;
import net.runelite.cache.region.Location;
import net.runelite.cache.region.Position;
import net.runelite.cache.region.Region;
import net.runelite.cache.region.RegionLoader;
import net.runelite.cache.util.XteaKeyManager;
import org.junit.Assume;
import org.junit.Test;

/**
 * Compares every committed destination row against a freshly downloaded
 * OSRS cache and reports destinations that have no interaction-capable
 * object near their tile — the deterministic half of "is this
 * destination still in the game" that the committed-data walkability
 * check cannot see (a deleted anvil leaves the floor walkable).
 *
 * <p>Destinations carry no object anchor, so matching is by menu
 * action: a row is live when an object exposing one of the category's
 * interaction options exists within {@link #RADIUS} tiles of the
 * destination tile (same plane, Chebyshev distance to the object's
 * footprint, multi-loc children resolved — e.g. construction spaces
 * whose built state is the bank chest).
 *
 * <p>Files without an object-anchored category (the apothecary is an
 * NPC shop) land in the report's {@code uncovered} bucket.
 *
 * <p>Three finding classes are by-design unverifiable and are curated
 * into {@code src/test/resources/destination_drift_exceptions.tsv}
 * (prop {@code destination.drift.exceptions}, {@code X Y Z<TAB>reason}
 * rows): NPC-serviced destinations (the banker is an NPC, not an
 * object — Piscatoris, Jatizso, Ourania/Eniola), instanced interiors
 * the cache stores at other coordinates (Clan Hall, Giants' Foundry),
 * and server-spawned objects that never appear in map placements
 * (Grand Exchange / Canifis booths — only {@code name=null} floor
 * objects occupy those tiles in the cache).
 *
 * <p>The scan is <b>advisory by construction</b>: findings populate the
 * report and stdout summary but never fail the test — XTEA lag and
 * legitimate object churn make nonzero diffs expected.
 *
 * Run via:
 *   ./gradlew destinationDrift \
 *     -PdestinationDriftCacheDir=$PWD/cache \
 *     -PdestinationDriftXteaPath=$PWD/keys.json
 *
 * Output: build/destination-drift.txt and console.
 */
public class DestinationDriftTest {

    /** Max Chebyshev distance from a destination tile to the nearest
     * matching object footprint. Stand tiles sit adjacent (distance 1);
     * 3 leaves headroom for large altars and booth counters. */
    private static final int RADIUS = 3;

    /** destinations/-relative TSV path -> menu actions that mark the
     * row's object as live. Any one action on the object (or on a
     * multi-loc child state) counts. */
    private static final Map<String, String[]> CATEGORIES = new LinkedHashMap<>();
    static {
        CATEGORIES.put("game_features/bank.tsv",
            new String[]{"Bank", "Use-quickly", "Collect", "Deposit"});
        CATEGORIES.put("game_features/altar.tsv",
            new String[]{"Pray-at", "Pray", "Offer", "Venerate", "Worship"});
        CATEGORIES.put("training/anvil.tsv", new String[]{"Smith"});
    }

    private static final class DestRow {
        int line;
        int x, y, z;
        String info;
    }

    private static final class Placement {
        int x, y, z, sizeX, sizeY;
    }

    @Test
    public void scanDestinations() throws Exception {
        Assume.assumeTrue(
            "Enable with -Ddestination.drift.scan=true",
            Boolean.getBoolean("destination.drift.scan"));

        String cacheDir = CacheUtils.requiredProperty("destination.drift.cacheDir");
        String xteaPath = CacheUtils.requiredProperty("destination.drift.xteaPath");
        String tsvDir = CacheUtils.requiredProperty("destination.drift.tsvDir");
        String outPath = System.getProperty("destination.drift.outPath",
            "build/destination-drift.txt");
        java.util.Set<String> exceptions =
            loadExceptions(System.getProperty("destination.drift.exceptions"));

        Path destinationsDir = Paths.get(tsvDir, "destinations");
        if (!Files.isDirectory(destinationsDir)) {
            throw new IllegalStateException(
                "Destination TSV directory not readable: " + destinationsDir);
        }

        XteaKeyManager xteaKeyManager = CacheUtils.loadXteaKeys(xteaPath);

        Path outFile = Paths.get(outPath);
        if (outFile.getParent() != null) {
            Files.createDirectories(outFile.getParent());
        }

        List<String> missing = new ArrayList<>();
        List<String> uncovered = new ArrayList<>();
        int scanned = 0;
        int excepted = 0;

        try (Store store = CacheUtils.openStore(cacheDir);
             PrintWriter out = new PrintWriter(outFile.toFile())) {

            ObjectManager objectManager = new ObjectManager(store);
            objectManager.load();
            RegionLoader regionLoader = CacheUtils.loadRegions(store, xteaKeyManager);
            regionLoader.calculateBounds();

            for (Map.Entry<String, String[]> cat : CATEGORIES.entrySet()) {
                Path file = destinationsDir.resolve(cat.getKey());
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                List<DestRow> rows = readRows(file);
                List<Placement> placements =
                    collectPlacements(regionLoader, objectManager, cat.getValue());
                for (DestRow row : rows) {
                    scanned++;
                    if (nearest(placements, row) > RADIUS) {
                        if (exceptions.contains(
                                row.x + " " + row.y + " " + row.z)) {
                            excepted++;
                            continue;
                        }
                        missing.add(cat.getKey() + ":" + row.line + "\t"
                            + row.x + " " + row.y + " " + row.z
                            + (row.info.isEmpty() ? "" : "\t" + row.info)
                            + "\tno object with one of "
                            + String.join("/", cat.getValue())
                            + " within " + RADIUS + " tiles");
                    }
                }
            }

            // Files without an object-anchored category still surface —
            // an unverifiable row class is a finding, not silent coverage.
            try (java.util.stream.Stream<Path> stream =
                     Files.walk(destinationsDir)) {
                stream.filter(p -> p.toString().endsWith(".tsv"))
                    .sorted()
                    .forEach(p -> {
                        String rel = destinationsDir.relativize(p).toString();
                        if (!CATEGORIES.containsKey(rel)) {
                            uncovered.add(rel);
                        }
                    });
            }

            String summary = "destinations scanned=" + scanned
                + " missing-object=" + missing.size()
                + " excepted=" + excepted
                + " uncovered-files=" + uncovered.size();

            out.println("=== missing-object (" + missing.size() + ") ===");
            for (String s : missing) {
                out.println(s);
            }
            out.println();
            out.println("=== uncovered (" + uncovered.size() + ") ===");
            for (String s : uncovered) {
                out.println(s + " — no object-anchored category configured");
            }
            out.println();
            out.println(summary);

            System.out.println();
            System.out.println(summary);
            for (String s : missing) {
                System.out.println(s);
            }
            System.out.println("report -> " + outPath);
        }
    }

    /** Chebyshev distance from the destination tile to the nearest
     * placement footprint on the same plane; Integer.MAX_VALUE when no
     * placement shares the plane. */
    private static int nearest(List<Placement> placements, DestRow row) {
        int best = Integer.MAX_VALUE;
        for (Placement p : placements) {
            if (p.z != row.z) {
                continue;
            }
            int dx = Math.max(0, Math.max(p.x - row.x, row.x - (p.x + p.sizeX - 1)));
            int dy = Math.max(0, Math.max(p.y - row.y, row.y - (p.y + p.sizeY - 1)));
            best = Math.min(best, Math.max(dx, dy));
        }
        return best;
    }

    /** All placements of objects exposing one of {@code actions},
     * including multi-loc parents whose transformed child does. */
    private static List<Placement> collectPlacements(
            RegionLoader regionLoader, ObjectManager objectManager,
            String[] actions) {
        List<Placement> result = new ArrayList<>();
        for (Region region : regionLoader.getRegions()) {
            for (Location loc : region.getLocations()) {
                ObjectDefinition def = objectManager.getObject(loc.getId());
                if (def == null || !offers(objectManager, def, actions)) {
                    continue;
                }
                Position pos = loc.getPosition();
                Placement p = new Placement();
                p.x = pos.getX();
                p.y = pos.getY();
                p.z = pos.getZ();
                p.sizeX = CacheUtils.effectiveSizeX(def, loc.getOrientation());
                p.sizeY = CacheUtils.effectiveSizeY(def, loc.getOrientation());
                result.add(p);
            }
        }
        return result;
    }

    /** True when the definition itself, or any multi-loc child state it
     * transforms into, exposes one of {@code actions}. */
    private static boolean offers(ObjectManager objectManager,
                                  ObjectDefinition def, String[] actions) {
        if (CacheUtils.hasAction(def, actions)) {
            return true;
        }
        int[] children = def.getConfigChangeDest();
        if (children == null) {
            return false;
        }
        for (int child : children) {
            ObjectDefinition c = objectManager.getObject(child);
            if (c != null && CacheUtils.hasAction(c, actions)) {
                return true;
            }
        }
        return false;
    }

    /** Loads the curated suppression list: {@code X Y Z<TAB>reason}
     * rows keyed by the coordinate string. A malformed non-comment row
     * aborts the scan — a suppression that might be a typo must not
     * silently pass. */
    private static java.util.Set<String> loadExceptions(String path)
            throws IOException {
        java.util.Set<String> keys = new java.util.HashSet<>();
        if (path == null) {
            return keys;
        }
        Path file = Paths.get(path);
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException(
                "destination.drift.exceptions not readable: " + file);
        }
        List<String> lines = Files.readAllLines(file);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\t", -1);
            if (parts.length != 2 || parts[0].trim().split("\\s+").length != 3
                    || parts[1].trim().isEmpty()) {
                throw new IllegalStateException(
                    file + ":" + (i + 1) + ": malformed exceptions row "
                    + "(want \"X Y Z<TAB>reason\")");
            }
            keys.add(parts[0].trim());
        }
        return keys;
    }

    /** Reads a destination TSV's {@code Destination}/{@code Info}
     * columns, skipping comments and non-concrete cells. */
    private static List<DestRow> readRows(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file);
        List<DestRow> rows = new ArrayList<>();
        if (lines.isEmpty()) {
            return rows;
        }
        String header = lines.get(0);
        if (header.startsWith("#")) {
            header = header.substring(1).trim();
        }
        String[] cols = header.split("\t", -1);
        int destCol = -1, infoCol = -1;
        for (int i = 0; i < cols.length; i++) {
            if (cols[i].trim().equals("Destination")) {
                destCol = i;
            } else if (cols[i].trim().equals("Info")) {
                infoCol = i;
            }
        }
        if (destCol < 0) {
            return rows;
        }
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] fields = line.split("\t", -1);
            if (fields.length <= destCol) {
                continue;
            }
            String cell = fields[destCol].trim();
            String[] parts = cell.split("\\s+");
            if (parts.length != 3) {
                continue; // wildcards/permutations — nothing concrete to check
            }
            DestRow row = new DestRow();
            try {
                row.x = Integer.parseInt(parts[0]);
                row.y = Integer.parseInt(parts[1]);
                row.z = Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {
                continue;
            }
            row.line = i + 1;
            row.info = infoCol >= 0 && fields.length > infoCol
                ? fields[infoCol].trim() : "";
            rows.add(row);
        }
        return rows;
    }
}
