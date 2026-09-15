package shortestpath.dump;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.runelite.cache.ItemManager;
import net.runelite.cache.definitions.ItemDefinition;
import net.runelite.cache.fs.Store;
import net.runelite.cache.util.XteaKeyManager;
import org.junit.Assume;
import org.junit.Test;

/**
 * Dumps every item that looks like a Leagues V / Demonic Pacts teleport
 * source: Banker's Briefcase, relics, the four Demonic Pact relics, etc.
 *
 * For each match, prints:
 *   - id, name, examine, notedID
 *   - interfaceOptions  (right-click menu, slot 0..4)
 *   - subops[i]         (each top-level option's sub-menu, e.g. teleport list)
 *   - params            (int-keyed map; often holds cs2 script IDs + varbit gates)
 *   - countCo/countObj  (charge / stack metadata, rarely useful here)
 *
 * Run via:
 *   ./gradlew leagueTeleportItemDump \
 *     -PleagueTeleportItemCacheDir=$PWD/cache \
 *     -PleagueTeleportItemXteaPath=$PWD/keys.json
 *
 * Following the pattern set by {@link SailingAmenityVarbitDumperTest}, this
 * test does NOT attempt cs2 script disassembly — the bundled runelite-cache
 * ScriptLoader can't reliably decode current-rev opcodes. Subops + params are
 * usually enough to map menu indices to in-game destinations.
 */
public class LeagueTeleportItemDumperTest {

    // Name substrings that catch league/relic teleport-bearing items.
    // Lowercase, matched via {@link ItemDefinition#getName()}.
    private static final String[] NAME_NEEDLES = {
        "briefcase",            // Banker's Briefcase (30361)
        "evil eye",             // Evil Eye relic (33227)
        "map of alacrity",      // Map of Alacrity relic (33233)
        "clue contract",        // Clue Contract relic (33235)
        "fairy mushroom",       // Fairy Mushroom relic (25102) — League V
        "demonic pact",         // Demonic Pacts entry items
        "league tablet",
        "league relic",
        "relic fragment",
        "totem of legends",     // League III?
        "ring of nature",       // older
    };

    @Test
    public void dumpLeagueTeleportItems() throws Exception {
        Assume.assumeTrue(
            "Enable with -Dleague.teleport.item.dump=true and supply cacheDir/xteaPath",
            Boolean.getBoolean("league.teleport.item.dump"));

        String cacheDir = CacheUtils.requiredProperty("league.teleport.item.cacheDir");
        String xteaPath = CacheUtils.requiredProperty("league.teleport.item.xteaPath");

        XteaKeyManager xteaKeyManager = CacheUtils.loadXteaKeys(xteaPath);

        try (Store store = CacheUtils.openStore(cacheDir)) {
            ItemManager itemManager = new ItemManager(store);
            itemManager.load();

            // Bucket items by which needle they matched (some items match >1).
            Map<String, List<ItemDefinition>> matchesByNeedle = new LinkedHashMap<>();
            for (String n : NAME_NEEDLES) matchesByNeedle.put(n, new ArrayList<>());

            for (ItemDefinition item : itemManager.getItems()) {
                String name = item.getName();
                if (name == null) continue;
                String lower = name.toLowerCase(Locale.ROOT);
                for (String needle : NAME_NEEDLES) {
                    if (lower.contains(needle)) {
                        matchesByNeedle.get(needle).add(item);
                    }
                }
            }

            for (Map.Entry<String, List<ItemDefinition>> e : matchesByNeedle.entrySet()) {
                if (e.getValue().isEmpty()) continue;
                System.out.println();
                System.out.println("######## needle=\"" + e.getKey() + "\" matches=" + e.getValue().size() + " ########");
                for (ItemDefinition item : e.getValue()) {
                    printItem(item);
                }
            }
        }
    }

    private static void printItem(ItemDefinition item) {
        System.out.println();
        System.out.println("-- id=" + item.getId() + " name=\"" + item.getName() + "\" --");
        System.out.println("  examine:          " + item.getExamine());
        System.out.println("  notedID:          " + item.notedID);
        System.out.println("  placeholderId:    " + item.placeholderId);
        System.out.println("  boughtId:         " + item.boughtId);
        System.out.println("  interfaceOptions: " + Arrays.toString(item.getInterfaceOptions()));
        String[][] subops = item.getSubops();
        if (subops != null) {
            for (int i = 0; i < subops.length; i++) {
                if (subops[i] != null) {
                    System.out.println("  subops[" + i + "]:        " + Arrays.toString(subops[i]));
                }
            }
        }
        Map<Integer, Object> params = item.params;
        if (params != null && !params.isEmpty()) {
            List<Integer> keys = new ArrayList<>(params.keySet());
            keys.sort(Integer::compareTo);
            for (Integer k : keys) {
                System.out.println("  param[" + k + "] = " + params.get(k));
            }
        }
    }
}
