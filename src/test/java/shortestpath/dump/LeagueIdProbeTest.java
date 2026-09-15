package shortestpath.dump;

import java.util.List;
import net.runelite.cache.ItemManager;
import net.runelite.cache.fs.Store;
import net.runelite.cache.definitions.ItemDefinition;
import net.runelite.cache.util.XteaKeyManager;
import org.junit.Assume;
import org.junit.Test;

/**
 * One-off helper to surface league-related item IDs from the cache. Run via
 * the {@code leagueIdProbe} Gradle task, then copy the printed IDs into
 * {@code seasonal_transports.tsv} (no placeholder file).
 */
public class LeagueIdProbeTest {
    @Test
    public void probeLeagueIds() throws Exception {
        Assume.assumeTrue(
            "Enable with -Dleague.id.probe=true and supply -Dleague.id.cacheDir / -Dleague.id.xteaPath",
            Boolean.getBoolean("league.id.probe"));

        String cacheDir = CacheUtils.requiredProperty("league.id.cacheDir");
        String xteaPath = CacheUtils.requiredProperty("league.id.xteaPath");

        XteaKeyManager xteaKeyManager = CacheUtils.loadXteaKeys(xteaPath);

        try (Store store = CacheUtils.openStore(cacheDir)) {
            ItemManager itemManager = new ItemManager(store);
            itemManager.load();

            System.out.println("=== Banker's Briefcase candidates ===");
            List<ItemDefinition> briefcases = CacheUtils.findItemsByNameSubstring(
                itemManager, "banker's briefcase", "bankers briefcase", "banker briefcase");
            for (ItemDefinition item : briefcases) {
                System.out.println("  id=" + item.getId() + "\tname=\"" + item.getName() + "\"");
            }

            System.out.println();
            System.out.println("=== Relic candidates (Bank Heist / Heist) ===");
            List<ItemDefinition> relics = CacheUtils.findItemsByNameSubstring(
                itemManager, "bank heist", "heist");
            for (ItemDefinition item : relics) {
                System.out.println("  id=" + item.getId() + "\tname=\"" + item.getName() + "\"");
            }

            System.out.println();
            System.out.println("=== Evil Eye candidates ===");
            List<ItemDefinition> evilEye = CacheUtils.findItemsByNameSubstring(
                itemManager, "evil eye");
            for (ItemDefinition item : evilEye) {
                System.out.println("  id=" + item.getId() + "\tname=\"" + item.getName() + "\"");
            }

            System.out.println();
            System.out.println("=== Map of Alacrity candidates ===");
            List<ItemDefinition> mapOfAlacrity = CacheUtils.findItemsByNameSubstring(
                itemManager, "map of alacrity", "alacrity");
            for (ItemDefinition item : mapOfAlacrity) {
                System.out.println("  id=" + item.getId() + "\tname=\"" + item.getName() + "\"");
            }

            System.out.println();
            System.out.println("=== Clue Contract candidates ===");
            List<ItemDefinition> clueContract = CacheUtils.findItemsByNameSubstring(
                itemManager, "clue contract");
            for (ItemDefinition item : clueContract) {
                System.out.println("  id=" + item.getId() + "\tname=\"" + item.getName() + "\"");
            }

            System.out.println();
            System.out.println("=== Fairy Mushroom candidates ===");
            List<ItemDefinition> fairyMushroom = CacheUtils.findItemsByNameSubstring(
                itemManager, "fairy mushroom");
            for (ItemDefinition item : fairyMushroom) {
                System.out.println("  id=" + item.getId() + "\tname=\"" + item.getName() + "\"");
            }
        }
    }
}
