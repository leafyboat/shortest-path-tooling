package shortestpath.dump;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.runelite.cache.StructManager;
import net.runelite.cache.definitions.StructDefinition;
import net.runelite.cache.fs.Store;
import org.junit.Assume;
import org.junit.Test;

/**
 * Scans every StructDefinition for params that contain:
 *   (a) a String value that matches the canonical briefcase destination list, AND
 *   (b) an Integer value that decodes as a valid OSRS packed WorldPoint
 *       (z 0..3, x 1024..4096, y 1024..16383).
 * Such structs are very likely briefcase-destination records.
 */
public class BriefcaseStructHuntTest {

    @Test
    public void hunt() throws Exception {
        Assume.assumeTrue(
            "Enable with -Dbriefcase.struct.hunt=true",
            Boolean.getBoolean("briefcase.struct.hunt"));

        String cacheDir = CacheUtils.requiredProperty("briefcase.struct.cacheDir");
        String namesFile = CacheUtils.requiredProperty("briefcase.struct.namesFile");
        String outPath = System.getProperty("briefcase.struct.outPath", "build/briefcase-struct-hunt.txt");

        Set<String> canonical = new HashSet<>();
        for (String line : Files.readAllLines(Paths.get(namesFile))) {
            String s = line.trim();
            if (!s.isEmpty()) canonical.add(s.toLowerCase(Locale.ROOT));
        }

        try (Store store = CacheUtils.openStore(cacheDir);
             PrintWriter out = new PrintWriter(outPath)) {
            StructManager sm = new StructManager(store);
            sm.load();
            Map<Integer, StructDefinition> all = sm.getStructs();

            List<int[]> hits = new ArrayList<>();
            TreeMap<Integer, String> names = new TreeMap<>();
            TreeMap<Integer, Integer> coords = new TreeMap<>();

            for (Map.Entry<Integer, StructDefinition> e : all.entrySet()) {
                StructDefinition sd = e.getValue();
                if (sd.getParams() == null) continue;
                String hitName = null;
                Integer hitCoord = null;
                for (Map.Entry<Integer, Object> p : sd.getParams().entrySet()) {
                    Object v = p.getValue();
                    if (v instanceof String) {
                        String s = ((String) v).toLowerCase(Locale.ROOT);
                        if (canonical.contains(s)) hitName = (String) v;
                    } else if (v instanceof Integer) {
                        int vv = (Integer) v;
                        int z = (vv >>> 28) & 0x3;
                        int x = (vv >>> 14) & 0x3FFF;
                        int y = vv & 0x3FFF;
                        if (z < 4 && x >= 1024 && x <= 4096 && y >= 1024 && y <= 16383 && (vv >>> 30) == 0) {
                            hitCoord = vv;
                        }
                    }
                }
                if (hitName != null && hitCoord != null) {
                    hits.add(new int[]{e.getKey()});
                    names.put(e.getKey(), hitName);
                    coords.put(e.getKey(), hitCoord);
                }
            }

            out.println("Struct hits with name+coord: " + hits.size());
            System.out.println("Struct hits with name+coord: " + hits.size());

            for (int[] h : hits) {
                int id = h[0];
                StructDefinition sd = all.get(id);
                int c = coords.get(id);
                int z = (c >>> 28) & 0x3;
                int x = (c >>> 14) & 0x3FFF;
                int y = c & 0x3FFF;
                out.println();
                out.println("--- struct " + id + " name=\"" + names.get(id)
                    + "\" coord WP(" + x + "," + y + "," + z + ") ---");
                TreeMap<Integer, Object> sorted = new TreeMap<>(sd.getParams());
                for (Map.Entry<Integer, Object> p : sorted.entrySet()) {
                    Object v = p.getValue();
                    String vs = v == null ? "null" : v.toString();
                    if (vs.length() > 160) vs = vs.substring(0, 157) + "...";
                    StringBuilder line = new StringBuilder("  [")
                        .append(p.getKey()).append("] = ").append(vs);
                    if (v instanceof Integer) {
                        int vv = (Integer) v;
                        int zz = (vv >>> 28) & 0x3;
                        int xx = (vv >>> 14) & 0x3FFF;
                        int yy = vv & 0x3FFF;
                        if (zz < 4 && xx >= 1024 && xx <= 4096 && yy >= 1024 && yy <= 16383 && (vv >>> 30) == 0) {
                            line.append("  -> WP(").append(xx).append(',').append(yy).append(',').append(zz).append(')');
                        }
                    }
                    out.println(line);
                }
            }
        }
    }
}
