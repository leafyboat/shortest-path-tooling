package shortestpath.dump;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.cache.ObjectManager;
import net.runelite.cache.definitions.ObjectDefinition;
import net.runelite.cache.fs.Store;
import net.runelite.cache.region.RegionLoader;
import net.runelite.cache.util.XteaKeyManager;
import org.junit.Assume;
import org.junit.Test;
import shortestpath.transport.parser.TransportRecord;

/**
 * Compares every committed transport row's {@code menuOption menuTarget objectID}
 * anchor against a freshly downloaded OSRS cache and reports anchors whose
 * object no longer exists or no longer exposes the named menu action.
 *
 * <p>Transports anchor to game objects by object id + menu option; a weekly
 * game update can move, rename, or remove an object while the TSV row still
 * looks syntactically fine. Only a live-cache comparison catches that drift.
 *
 * <p>The scan is <b>advisory by construction</b>: findings populate a grouped
 * triage report and the stdout summary but never fail the test — post-update
 * XTEA key lag and legitimate object churn make nonzero diffs expected. Only
 * genuine infrastructure problems (missing properties, unreadable cache or
 * TSV root) surface as test errors.
 *
 * <p>Anchors whose trailing token is not a parseable object id land in the
 * report's {@code unparseable} bucket rather than crashing the scan; the
 * trailing-integer convention is documented but unenforced.
 *
 * Run via:
 *   ./gradlew transportAnchorDrift \
 *     -PtransportDriftCacheDir=$PWD/cache \
 *     -PtransportDriftXteaPath=$PWD/keys.json
 *
 * Output: build/transport-drift.txt and console.
 */
public class TransportAnchorDriftTest {

    private static final class Anchor {
        String file;
        int line;
        String cell;        // raw anchor cell text
        String menuOption;
        int objectId = -1;
        String parseError;  // non-null when the cell failed to parse
    }

    @Test
    public void scanTransportAnchors() throws Exception {
        Assume.assumeTrue(
            "Enable with -Dtransport.drift.scan=true",
            Boolean.getBoolean("transport.drift.scan"));

        String cacheDir = CacheUtils.requiredProperty("transport.drift.cacheDir");
        String xteaPath = CacheUtils.requiredProperty("transport.drift.xteaPath");
        String tsvDir = CacheUtils.requiredProperty("transport.drift.tsvDir");
        String outPath = System.getProperty("transport.drift.outPath", "build/transport-drift.txt");

        Path transportsDir = Paths.get(tsvDir, "transports");
        if (!Files.isDirectory(transportsDir)) {
            throw new IllegalStateException("Transport TSV directory not readable: " + transportsDir);
        }

        List<Anchor> anchors = readAnchors(transportsDir);

        XteaKeyManager xteaKeyManager = CacheUtils.loadXteaKeys(xteaPath);

        Path outFile = Paths.get(outPath);
        if (outFile.getParent() != null) {
            Files.createDirectories(outFile.getParent());
        }

        try (Store store = CacheUtils.openStore(cacheDir);
             PrintWriter out = new PrintWriter(outFile.toFile())) {

            ObjectManager objectManager = new ObjectManager(store);
            objectManager.load();

            RegionLoader regionLoader = CacheUtils.loadRegions(store, xteaKeyManager);
            regionLoader.calculateBounds();

            List<String> missingObject = new ArrayList<>();
            List<String> missingAction = new ArrayList<>();
            List<String> unparseable = new ArrayList<>();
            List<Anchor> actionFindings = new ArrayList<>();
            Map<Anchor, List<Integer>> actionFindingIds = new HashMap<>();
            Set<Integer> placementIds = new HashSet<>();
            int scanned = 0;

            for (Anchor a : anchors) {
                if (a.parseError != null) {
                    unparseable.add(a.file + ":" + a.line + "\t" + a.cell + "\t" + a.parseError);
                    continue;
                }
                scanned++;

                ObjectDefinition def = objectManager.getObject(a.objectId);
                if (def == null) {
                    missingObject.add(a.file + ":" + a.line + "\t" + a.cell
                        + "\tobject " + a.objectId + " absent from cache");
                    continue;
                }

                if (CacheUtils.hasAction(def, a.menuOption)) {
                    continue;
                }

                // Multi-loc parent resolution: the TSV objectID may be the child
                // a placed parent object transforms into via varbit/varp.
                List<ObjectDefinition> parents =
                    CacheUtils.collectMultiLocParents(objectManager, a.objectId);
                boolean parentHasAction = false;
                for (ObjectDefinition p : parents) {
                    if (CacheUtils.hasAction(p, a.menuOption)) {
                        parentHasAction = true;
                        break;
                    }
                }
                if (parentHasAction) {
                    continue;
                }

                List<Integer> ids = new ArrayList<>();
                ids.add(def.getId());
                for (ObjectDefinition p : parents) {
                    ids.add(p.getId());
                }
                actionFindings.add(a);
                actionFindingIds.put(a, ids);
                placementIds.addAll(ids);
            }

            // One region sweep annotates findings with first world placements.
            Map<Integer, int[]> placement =
                CacheUtils.collectFirstPlacementByObjectId(regionLoader, placementIds);

            for (Anchor a : actionFindings) {
                StringBuilder detail = new StringBuilder();
                detail.append("object ").append(a.objectId)
                    .append(" lacks menu option \"").append(a.menuOption).append("\"");
                String where = null;
                for (int id : actionFindingIds.get(a)) {
                    int[] pos = placement.get(id);
                    if (pos != null) {
                        where = id + " at " + pos[0] + "," + pos[1] + "," + pos[2];
                        break;
                    }
                }
                detail.append(where == null ? " — no world placement" : " — " + where);
                missingAction.add(a.file + ":" + a.line + "\t" + a.cell + "\t" + detail);
            }

            String summary = "anchors scanned=" + scanned
                + " missing-object=" + missingObject.size()
                + " missing-action=" + missingAction.size()
                + " unparseable=" + unparseable.size();

            out.println("=== missing-object (" + missingObject.size() + ") ===");
            for (String s : missingObject) {
                out.println(s);
            }
            out.println();
            out.println("=== missing-action (" + missingAction.size() + ") ===");
            for (String s : missingAction) {
                out.println(s);
            }
            out.println();
            out.println("=== unparseable (" + unparseable.size() + ") ===");
            for (String s : unparseable) {
                out.println(s);
            }
            out.println();
            out.println(summary);

            System.out.println();
            System.out.println(summary);
            System.out.println("report -> " + outPath);
        }
    }

    /**
     * Reads every {@code *.tsv} under {@code transportsDir} and extracts the
     * {@code menuOption menuTarget objectID} anchor from each data row.
     * Files without the anchor column contribute nothing; rows with an empty
     * anchor cell carry no object anchor and are skipped.
     */
    private static List<Anchor> readAnchors(Path transportsDir) throws IOException {
        List<Anchor> anchors = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(transportsDir, "*.tsv")) {
            for (Path p : stream) {
                files.add(p);
            }
        }
        files.sort(null);

        for (Path file : files) {
            List<String> lines = Files.readAllLines(file);
            if (lines.isEmpty()) {
                continue;
            }
            String header = lines.get(0);
            if (header.startsWith("# ")) {
                header = header.substring(2);
            } else if (header.startsWith("#")) {
                header = header.substring(1);
            }
            String[] cols = header.split("\t", -1);
            int objectInfoCol = -1;
            for (int i = 0; i < cols.length; i++) {
                if (cols[i].trim().equals(TransportRecord.Fields.OBJECT_INFO)) {
                    objectInfoCol = i;
                    break;
                }
            }
            if (objectInfoCol < 0) {
                continue; // file has no anchor column
            }

            String name = file.getFileName().toString();
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.isEmpty() || line.trim().isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] fields = line.split("\t", -1);
                if (fields.length <= objectInfoCol) {
                    Anchor a = new Anchor();
                    a.file = name;
                    a.line = i + 1;
                    a.cell = "";
                    a.parseError = "row has fewer columns than the anchor column index";
                    anchors.add(a);
                    continue;
                }
                String cell = fields[objectInfoCol].trim();
                if (cell.isEmpty()) {
                    continue; // no object anchor on this row
                }
                Anchor a = new Anchor();
                a.file = name;
                a.line = i + 1;
                a.cell = cell;
                parseAnchor(a);
                anchors.add(a);
            }
        }
        return anchors;
    }

    /**
     * Splits an anchor cell into menu option + object id. The convention is
     * {@code <menuOption> <menuTarget...> <objectID>}: the first whitespace-
     * separated token is the menu option and the trailing token is the object
     * id. Cells that don't fit land in the report's unparseable bucket.
     */
    private static void parseAnchor(Anchor a) {
        String[] tokens = a.cell.split("\\s+");
        if (tokens.length < 2) {
            a.parseError = "anchor cell has no trailing object id token";
            return;
        }
        try {
            a.objectId = Integer.parseInt(tokens[tokens.length - 1]);
        } catch (NumberFormatException e) {
            a.parseError = "trailing token \"" + tokens[tokens.length - 1]
                + "\" is not an object id";
            return;
        }
        a.menuOption = tokens[0];
    }
}
