package shortestpath.dump;

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
 * One-off probe: dump every object placed inside a bounding box of world
 * tiles. Used to inspect what objects sit in a wall/area the collision map
 * marks blocked. Run with:
 *   ./gradlew test --tests shortestpath.dump.TileObjectProbeTest -Dtile.probe=true
 * Cache paths default to the repo's cache/ and keys.json.
 */
public class TileObjectProbeTest {
    @Test
    public void dumpObjectsInBox() throws Exception {
        Assume.assumeTrue("Enable with -Dtile.probe=true", Boolean.getBoolean("tile.probe"));
        String cacheDir = System.getProperty("tile.probe.cacheDir", "cache");
        String xteaPath = System.getProperty("tile.probe.xteaPath", "keys.json");

        // Boxes: Mage Arena compound (pocket, wall band, inner ring) and the
        // bank cave region the entrance lever teleports into.
        int[][] boxes = {
            {3085, 3135, 3935, 3970},
            {2510, 2570, 4680, 4740},
        };
        int plane = 0;

        XteaKeyManager xtea = CacheUtils.loadXteaKeys(xteaPath);
        try (Store store = CacheUtils.openStore(cacheDir)) {
            ObjectManager objectManager = new ObjectManager(store);
            objectManager.load();
            RegionLoader regionLoader = CacheUtils.loadRegions(store, xtea);
            regionLoader.calculateBounds();

            for (Region region : regionLoader.getRegions()) {
                for (Location loc : region.getLocations()) {
                    Position pos = loc.getPosition();
                    int x = pos.getX(), y = pos.getY(), z = pos.getZ();
                    if (z != plane) continue;
                    boolean inBox = false;
                    for (int[] b : boxes) {
                        if (x >= b[0] && x <= b[1] && y >= b[2] && y <= b[3]) { inBox = true; break; }
                    }
                    if (!inBox) continue;
                    ObjectDefinition def = objectManager.getObject(loc.getId());
                    String name = def != null ? def.getName() : "?";
                    System.out.printf("%d %d %d\tid=%d\ttype=%d\tname=%s%n",
                        x, y, z, loc.getId(), loc.getType(), name);
                }
            }
        }
    }
}
