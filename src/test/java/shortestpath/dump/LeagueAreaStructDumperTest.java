package shortestpath.dump;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.runelite.cache.StructManager;
import net.runelite.cache.definitions.StructDefinition;
import net.runelite.cache.fs.Store;
import net.runelite.cache.util.XteaKeyManager;
import org.junit.Assume;
import org.junit.Test;

/**
 * Scans every {@link StructDefinition} in the cache and prints any struct
 * whose param-value strings look like a league area / region declaration.
 *
 * Heuristic: param 2020 holds the long-form "area covers..." description
 * (confirmed for structs 5982-5986 = Morytania, Kharidian Desert, Kebos
 * &amp; Kourend, Varlamore). Also flags param values containing any of:
 *   "area covers", "leagues vi", "demonic pacts", "banker's briefcase",
 *   region name lists, etc.
 *
 * Run via:
 *   ./gradlew leagueAreaStructDump \
 *     -PleagueAreaStructCacheDir=$PWD/cache \
 *     -PleagueAreaStructXteaPath=$PWD/keys.json
 */
public class LeagueAreaStructDumperTest {

    private static final String[] NEEDLES_LOWER = {
        "area covers",
        "demonic pacts",
        "banker's briefcase",
        "bankers briefcase",
        "evil eye",
        "map of alacrity",
        "clue contract",
        "fairy mushroom",
        "league relic",
    };

    @Test
    public void dumpLeagueAreaStructs() throws Exception {
        Assume.assumeTrue(
            "Enable with -Dleague.area.struct.dump=true",
            Boolean.getBoolean("league.area.struct.dump"));

        String cacheDir = CacheUtils.requiredProperty("league.area.struct.cacheDir");
        String xteaPath = CacheUtils.requiredProperty("league.area.struct.xteaPath");

        XteaKeyManager xteaKeyManager = CacheUtils.loadXteaKeys(xteaPath);

        try (Store store = CacheUtils.openStore(cacheDir)) {
            StructManager structManager = new StructManager(store);
            structManager.load();

            int scanned = 0, hits = 0;
            List<StructDefinition> hitDefs = new ArrayList<>();

            for (StructDefinition def : structManager.getStructs().values()) {
                scanned++;
                Map<Integer, Object> params = def.getParams();
                if (params == null || params.isEmpty()) continue;
                boolean match = false;
                for (Object v : params.values()) {
                    if (v instanceof String) {
                        String s = ((String) v).toLowerCase();
                        for (String n : NEEDLES_LOWER) {
                            if (s.contains(n)) { match = true; break; }
                        }
                        if (match) break;
                    }
                }
                if (match) {
                    hits++;
                    hitDefs.add(def);
                }
            }

            System.out.println();
            System.out.println("Scanned " + scanned + " structs, " + hits + " league/region matches");
            System.out.println();

            // Group by leading param key (gives a rough "kind" of struct).
            Map<Integer, List<StructDefinition>> byLeadingKey = new TreeMap<>();
            for (StructDefinition d : hitDefs) {
                int leadKey = d.getParams().keySet().iterator().next();
                byLeadingKey.computeIfAbsent(leadKey, k -> new ArrayList<>()).add(d);
            }

            for (Map.Entry<Integer, List<StructDefinition>> e : byLeadingKey.entrySet()) {
                System.out.println("=== leadingParamKey=" + e.getKey() + " count=" + e.getValue().size() + " ===");
                for (StructDefinition d : e.getValue()) {
                    System.out.println("  struct id=" + d.getId());
                    Map<Integer, Object> sorted = new TreeMap<>(d.getParams());
                    for (Map.Entry<Integer, Object> p : sorted.entrySet()) {
                        Object v = p.getValue();
                        String vs = v == null ? "null" : v.toString();
                        if (vs.length() > 2000) vs = vs.substring(0, 1997) + "...";
                        System.out.println("    [" + p.getKey() + "] = " + vs);
                    }
                }
                System.out.println();
            }
        }
    }
}
