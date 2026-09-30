package shortestpath.dump;

import net.runelite.cache.EntityOpsDefinition;
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
        // bank cave region the entrance lever teleports into. Override with
        // -Dtile.probe.boxes="x1 x2 y1 y2;x1 x2 y1 y2" (x1..x2, y1..y2).
        String boxSpec = System.getProperty("tile.probe.boxes");
        int[][] boxes;
        if (boxSpec != null && !boxSpec.isEmpty()) {
            String[] parts = boxSpec.split(";");
            boxes = new int[parts.length][4];
            for (int i = 0; i < parts.length; i++) {
                String[] nums = parts[i].trim().split("\\s+");
                for (int j = 0; j < 4; j++) {
                    boxes[i][j] = Integer.parseInt(nums[j]);
                }
            }
        } else {
            boxes = new int[][]{
                {3085, 3135, 3935, 3970},
                {2510, 2570, 4680, 4740},
            };
        }
        int plane = Integer.getInteger("tile.probe.plane", 0);

        XteaKeyManager xtea = CacheUtils.loadXteaKeys(xteaPath);
        try (Store store = CacheUtils.openStore(cacheDir)) {
            ObjectManager objectManager = new ObjectManager(store);
            objectManager.load();

            String defIds = System.getProperty("tile.probe.defids", "");
            if (!defIds.isEmpty()) {
                for (String s : defIds.split(",")) {
                    ObjectDefinition d = objectManager.getObject(Integer.parseInt(s.trim()));
                    if (d == null) { System.out.println("def " + s + " = null"); continue; }
                    StringBuilder ops = new StringBuilder();
                    if (d.getOps() != null && d.getOps().ops != null) {
                        for (EntityOpsDefinition.Op op : d.getOps().ops) {
                            if (op != null && op.text != null) ops.append(' ').append(op.text);
                        }
                    }
                    System.out.printf("def %d\tname=%s\tinteract=%d\twallOrDoor=%d\tsize=%dx%d\tvarbit=%d\tvarp=%d\tblockingMask=%d\tops=%s%n",
                        d.getId(), d.getName(), d.getInteractType(), d.getWallOrDoor(),
                        d.getSizeX(), d.getSizeY(), d.getVarbitID(), d.getVarpID(),
                        d.getBlockingMask(), ops);
                }
            }

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
                    String idFilter = System.getProperty("tile.probe.ids", "");
                    if (!idFilter.isEmpty()) {
                        boolean idMatch = false;
                        for (String s : idFilter.split(",")) {
                            if (loc.getId() == Integer.parseInt(s.trim())) { idMatch = true; break; }
                        }
                        if (!idMatch) continue;
                    }
                    ObjectDefinition def = objectManager.getObject(loc.getId());
                    String name = def != null ? def.getName() : "?";
                    StringBuilder extra = new StringBuilder();
                    if (def != null) {
                        extra.append("\tinteract=").append(def.getInteractType())
                            .append(" wallOrDoor=").append(def.getWallOrDoor())
                            .append(" size=").append(def.getSizeX()).append("x").append(def.getSizeY());
                        if (def.getVarbitID() != -1 || def.getVarpID() != -1) {
                            extra.append("\tvarbit=").append(def.getVarbitID())
                                .append(" varp=").append(def.getVarpID());
                        }
                        if (def.getConfigChangeDest() != null) {
                            extra.append("\tchildren=")
                                .append(java.util.Arrays.toString(
                                    def.getConfigChangeDest()));
                            for (int c : def.getConfigChangeDest()) {
                                ObjectDefinition cd = objectManager.getObject(c);
                                if (cd != null) {
                                    extra.append(" [").append(c).append(":").append(cd.getName()).append(":");
                                    if (cd.getOps() != null && cd.getOps().ops != null) {
                                        for (EntityOpsDefinition.Op op : cd.getOps().ops) {
                                            if (op != null && op.text != null) {
                                                extra.append(' ').append(op.text);
                                            }
                                        }
                                    }
                                    extra.append(']');
                                }
                            }
                        }
                        if (def.getOps() != null && def.getOps().ops != null) {
                            extra.append("\tops=");
                            for (EntityOpsDefinition.Op op : def.getOps().ops) {
                                if (op != null && op.text != null) {
                                    extra.append(' ').append(op.text);
                                }
                            }
                        }
                    }
                    System.out.printf("%d %d %d\tid=%d\ttype=%d\torient=%d\tname=%s%s%n",
                        x, y, z, loc.getId(), loc.getType(), loc.getOrientation(), name, extra);
                }
            }
        }
    }
}
