package shortestpath.dump;

import net.runelite.cache.fs.Store;
import net.runelite.cache.region.Region;
import net.runelite.cache.region.RegionLoader;
import net.runelite.cache.util.XteaKeyManager;
import org.junit.Assume;
import org.junit.Test;

/**
 * One-off probe: dump per-tile settings (tileSetting flag, underlay, overlay)
 * for a bounding box of world tiles. Used to tell rock floor from open floor
 * when auditing standable flags in collision-map.zip. Run with:
 *   ./gradlew test --tests shortestpath.dump.TileSettingProbeTest -Dtile.probe=true \
 *     -Dtile.probe.box="x1 x2 y1 y2" -Dtile.probe.plane=0
 * Cache paths default to the repo's cache/ and keys.json.
 */
public class TileSettingProbeTest {
    @Test
    public void dumpTileSettings() throws Exception {
        Assume.assumeTrue("Enable with -Dtile.probe=true", Boolean.getBoolean("tile.probe"));
        String cacheDir = System.getProperty("tile.probe.cacheDir", "cache");
        String xteaPath = System.getProperty("tile.probe.xteaPath", "keys.json");
        String[] nums = System.getProperty("tile.probe.box", "2810 2870 3225 3260").trim().split("\\s+");
        int x1 = Integer.parseInt(nums[0]), x2 = Integer.parseInt(nums[1]);
        int y1 = Integer.parseInt(nums[2]), y2 = Integer.parseInt(nums[3]);
        int plane = Integer.getInteger("tile.probe.plane", 0);

        XteaKeyManager xtea = CacheUtils.loadXteaKeys(xteaPath);
        try (Store store = CacheUtils.openStore(cacheDir)) {
            RegionLoader regionLoader = CacheUtils.loadRegions(store, xtea);
            regionLoader.calculateBounds();
            for (Region region : regionLoader.getRegions()) {
                int baseX = region.getBaseX(), baseY = region.getBaseY();
                for (int ly = 0; ly < Region.Y; ly++) {
                    for (int lx = 0; lx < Region.X; lx++) {
                        int x = baseX + lx, y = baseY + ly;
                        if (x < x1 || x > x2 || y < y1 || y > y2) continue;
                        int setting = region.getTileSetting(plane, lx, ly);
                        int underlay = region.getUnderlayId(plane, lx, ly);
                        int overlay = region.getOverlayId(plane, lx, ly);
                        System.out.printf("%d %d %d\tsetting=%d\tunderlay=%d\toverlay=%d%n",
                            x, y, plane, setting, underlay, overlay);
                    }
                }
            }
        }
    }
}
