package shortestpath.dump;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.cache.EntityOpsDefinition;
import net.runelite.cache.ItemManager;
import net.runelite.cache.ObjectManager;
import net.runelite.cache.definitions.ItemDefinition;
import net.runelite.cache.definitions.ObjectDefinition;
import net.runelite.cache.fs.Store;
import net.runelite.cache.region.Location;
import net.runelite.cache.region.Position;
import net.runelite.cache.region.Region;
import net.runelite.cache.region.RegionLoader;
import net.runelite.cache.util.XteaKeyManager;

/**
 * Shared utility methods for cache-dump test classes.
 *
 * <p>Typical usage:
 * <pre>
 *   // After loading store / objectManager / regionLoader:
 *   List&lt;ObjectDefinition&gt; parents =
 *       CacheUtils.collectMultiLocParents(objectManager, 58658, 58659);
 *   Set&lt;Integer&gt; ids = CacheUtils.parentIdSet(parents);
 *   Map&lt;Integer, List&lt;Location&gt;&gt; locs =
 *       CacheUtils.collectLocationsByObjectId(regionLoader, ids);
 *   Map&lt;Long, Region&gt; tileToRegion = CacheUtils.buildTileToRegion(regionLoader);
 *
 *   int[] dep = CacheUtils.closestLandAdjacentTile(ox, oy, oz, sizeX, sizeY, tileToRegion);
 * </pre>
 */
public final class CacheUtils {
    private CacheUtils() {}

    // -------------------------------------------------------------------------
    // System properties
    // -------------------------------------------------------------------------

    /**
     * Reads a required system property, throwing {@link IllegalStateException}
     * if it is absent or empty.
     */
    public static String requiredProperty(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("Missing required system property -D" + key + "=...");
        }
        return value;
    }

    // -------------------------------------------------------------------------
    // Cache bootstrap
    // -------------------------------------------------------------------------

    /**
     * Opens the OSRS cache {@link Store} rooted at {@code cacheDir} and loads
     * its index data. The caller keeps ownership: use the returned store in a
     * try-with-resources block so it is closed when the dump finishes.
     */
    public static Store openStore(String cacheDir) throws IOException {
        Store store = new Store(new File(cacheDir));
        store.load();
        return store;
    }

    /**
     * Loads the XTEA decryption keys from {@code xteaPath} (a keys.json file)
     * into a fresh {@link XteaKeyManager}.
     */
    public static XteaKeyManager loadXteaKeys(String xteaPath) throws IOException {
        XteaKeyManager xteaKeyManager = new XteaKeyManager();
        try (FileInputStream fin = new FileInputStream(xteaPath)) {
            xteaKeyManager.loadKeys(fin);
        }
        return xteaKeyManager;
    }

    /**
     * Creates a {@link RegionLoader} for the given store and loads every map
     * region (decrypting with {@code xtea} where keys are available). Callers
     * that need region bounds call {@link RegionLoader#calculateBounds()} on
     * the returned loader themselves — it is a per-caller decision, not part
     * of this bootstrap.
     */
    public static RegionLoader loadRegions(Store store, XteaKeyManager xtea) throws IOException {
        RegionLoader regionLoader = new RegionLoader(store, xtea);
        regionLoader.loadRegions();
        return regionLoader;
    }

    // -------------------------------------------------------------------------
    // Region / tile queries
    // -------------------------------------------------------------------------

    /**
     * Builds a world-coordinate → {@link Region} lookup map. The key is
     * {@code ((long) worldX << 16) | worldY}. Covers every tile in every loaded
     * region; tiles outside all loaded regions map to {@code null}.
     */
    public static Map<Long, Region> buildTileToRegion(RegionLoader regionLoader) {
        Map<Long, Region> map = new HashMap<>();
        for (Region region : regionLoader.getRegions()) {
            int baseX = region.getBaseX(), baseY = region.getBaseY();
            for (int lx = 0; lx < 64; lx++) {
                for (int ly = 0; ly < 64; ly++) {
                    map.put(((long)(baseX + lx) << 16) | (baseY + ly), region);
                }
            }
        }
        return map;
    }

    /**
     * Returns the {@link Region} that contains world tile {@code (x, y)}, or
     * {@code null} if no loaded region covers that tile.
     */
    public static Region regionForTile(Map<Long, Region> tileToRegion, int x, int y) {
        return tileToRegion.get(((long) x << 16) | y);
    }

    /**
     * Returns {@code true} if world tile {@code (x, y, plane)} is bare ground
     * (no floor overlay texture). A tile's overlay ID is 0 on plain land and
     * non-zero wherever a floor texture is applied — most importantly on sailing
     * water, which carries a water-texture overlay but is <em>not</em> blocked in
     * the collision map (ships travel over it). Using the overlay ID is therefore
     * more reliable than the collision BLOCKED flag for land/water detection in
     * sailing areas.
     */
    public static boolean isLandTile(Map<Long, Region> tileToRegion, int x, int y, int plane) {
        Region r = regionForTile(tileToRegion, x, y);
        if (r == null) return false;
        return r.getOverlayId(plane, x - r.getBaseX(), y - r.getBaseY()) == 0;
    }

    // -------------------------------------------------------------------------
    // Object footprint helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the effective footprint width (X) of an object at the given
     * orientation. Orientations 1 and 3 rotate the object 90°/270°, which
     * swaps its X and Y extents in world space.
     */
    public static int effectiveSizeX(ObjectDefinition def, int orientation) {
        return (orientation == 1 || orientation == 3) ? def.getSizeY() : def.getSizeX();
    }

    /**
     * Returns the effective footprint height (Y) of an object at the given
     * orientation. Orientations 1 and 3 rotate the object 90°/270°, which
     * swaps its X and Y extents in world space.
     */
    public static int effectiveSizeY(ObjectDefinition def, int orientation) {
        return (orientation == 1 || orientation == 3) ? def.getSizeX() : def.getSizeY();
    }

    /**
     * Returns all tiles immediately outside the perimeter of the given footprint
     * (one tile wide on each of the four faces). Each entry is
     * {@code [worldX, worldY]}.
     *
     * @param ox    south-west corner X (world coordinates)
     * @param oy    south-west corner Y (world coordinates)
     * @param sizeX footprint width after accounting for orientation
     * @param sizeY footprint height after accounting for orientation
     */
    public static int[][] footprintPerimeter(int ox, int oy, int sizeX, int sizeY) {
        int[][] result = new int[sizeX * 2 + sizeY * 2][2];
        int n = 0;
        for (int dy = 0; dy < sizeY; dy++) {
            result[n++] = new int[]{ox - 1,     oy + dy};  // west
            result[n++] = new int[]{ox + sizeX, oy + dy};  // east
        }
        for (int dx = 0; dx < sizeX; dx++) {
            result[n++] = new int[]{ox + dx, oy - 1};      // south
            result[n++] = new int[]{ox + dx, oy + sizeY};  // north
        }
        return result;
    }

    /**
     * Finds the land tile (see {@link #isLandTile}) adjacent to the given
     * footprint that is closest to the footprint's geometric centre. Returns
     * {@code [worldX, worldY]} of the best tile, or {@code null} if no adjacent
     * land tile exists in the loaded regions.
     *
     * <p>This is the canonical way to compute the "departure tile" — the tile a
     * player stands on when interacting with a dockside object such as a rowboat.
     *
     * @param ox    south-west corner X (world coordinates)
     * @param oy    south-west corner Y (world coordinates)
     * @param oz    plane
     * @param sizeX footprint width after accounting for orientation
     * @param sizeY footprint height after accounting for orientation
     */
    public static int[] closestLandAdjacentTile(
            int ox, int oy, int oz, int sizeX, int sizeY,
            Map<Long, Region> tileToRegion) {
        double centerX = ox + sizeX / 2.0;
        double centerY = oy + sizeY / 2.0;
        int bestX = -1, bestY = -1;
        double bestDist = Double.MAX_VALUE;
        for (int[] c : footprintPerimeter(ox, oy, sizeX, sizeY)) {
            if (!isLandTile(tileToRegion, c[0], c[1], oz)) continue;
            double ddx = c[0] - centerX, ddy = c[1] - centerY;
            double dist = ddx * ddx + ddy * ddy;
            if (dist < bestDist) { bestDist = dist; bestX = c[0]; bestY = c[1]; }
        }
        return bestX == -1 ? null : new int[]{bestX, bestY};
    }

    // -------------------------------------------------------------------------
    // Object / location collection
    // -------------------------------------------------------------------------

    /**
     * Finds all {@link ObjectDefinition}s whose {@code configChangeDest} array
     * references any of the given {@code seedIds}. These are the "multi-loc
     * parent" objects that transform into the seeds based on a varbit/varp value.
     * The result is sorted ascending by object ID.
     */
    public static List<ObjectDefinition> collectMultiLocParents(
            ObjectManager objectManager, int... seedIds) {
        Set<Integer> seedSet = new HashSet<>();
        for (int id : seedIds) seedSet.add(id);
        List<ObjectDefinition> parents = new ArrayList<>();
        for (ObjectDefinition def : objectManager.getObjects()) {
            int[] dest = def.getConfigChangeDest();
            if (dest == null) continue;
            for (int d : dest) {
                if (seedSet.contains(d)) { parents.add(def); break; }
            }
        }
        parents.sort((a, b) -> Integer.compare(a.getId(), b.getId()));
        return parents;
    }

    /**
     * Convenience method: returns a {@link Set} of the IDs of the given
     * definitions, suitable for passing to
     * {@link #collectLocationsByObjectId(RegionLoader, Set)}.
     */
    public static Set<Integer> parentIdSet(List<ObjectDefinition> parents) {
        Set<Integer> ids = new HashSet<>();
        for (ObjectDefinition p : parents) ids.add(p.getId());
        return ids;
    }

    /**
     * Collects all world {@link Location}s for each object ID in {@code ids},
     * grouped by object ID. Preserves encounter order within each group.
     */
    public static Map<Integer, List<Location>> collectLocationsByObjectId(
            RegionLoader regionLoader, Set<Integer> ids) {
        Map<Integer, List<Location>> result = new HashMap<>();
        for (Region region : regionLoader.getRegions()) {
            for (Location loc : region.getLocations()) {
                if (!ids.contains(loc.getId())) continue;
                if (!result.containsKey(loc.getId())) {
                    result.put(loc.getId(), new ArrayList<>());
                }
                result.get(loc.getId()).add(loc);
            }
        }
        return result;
    }

    /**
     * For each object ID in {@code ids}, records the <em>first</em> world
     * placement found as {@code [worldX, worldY, plane]}. Convenient for
     * multi-loc parent objects that have exactly one placement (e.g. one
     * island chest). Objects with no placement are absent from the result.
     */
    public static Map<Integer, int[]> collectFirstPlacementByObjectId(
            RegionLoader regionLoader, Set<Integer> ids) {
        Map<Integer, int[]> result = new HashMap<>();
        for (Region region : regionLoader.getRegions()) {
            for (Location loc : region.getLocations()) {
                if (!ids.contains(loc.getId())) continue;
                if (result.containsKey(loc.getId())) continue; // keep first only
                Position pos = loc.getPosition();
                result.put(loc.getId(), new int[]{pos.getX(), pos.getY(), pos.getZ()});
            }
        }
        return result;
    }

    /**
     * Collects all world {@link Location}s whose object ID is in {@code ids}
     * as a flat list. Unlike {@link #collectLocationsByObjectId}, this does not
     * group by object ID — useful when iterating all placements regardless of
     * which object they belong to (e.g. printing every marker placement).
     */
    public static List<Location> collectLocations(RegionLoader regionLoader, Set<Integer> ids) {
        List<Location> result = new ArrayList<>();
        for (Region region : regionLoader.getRegions()) {
            for (Location loc : region.getLocations()) {
                if (ids.contains(loc.getId())) result.add(loc);
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Object queries
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if the given object definition has at least one menu
     * action whose text matches any of the provided {@code actions}
     * (case-insensitive). Useful for filtering decorative objects that share a
     * name with interactive ones but lack the relevant menu option.
     */
    public static boolean hasAction(ObjectDefinition def, String... actions) {
        EntityOpsDefinition ops = def.getOps();
        if (ops == null || ops.ops == null) return false;
        for (EntityOpsDefinition.Op op : ops.ops) {
            if (op == null || op.text == null) continue;
            for (String action : actions) {
                if (op.text.equalsIgnoreCase(action)) return true;
            }
        }
        return false;
    }

    /**
     * Returns all {@link ObjectDefinition}s that use {@code varbitId} to control
     * their state (i.e. {@code getVarbitID() == varbitId}). Useful when you know
     * the varbit that gates a feature and want to find all objects it controls
     * without already knowing their IDs. The result is sorted ascending by object ID.
     */
    public static List<ObjectDefinition> findObjectsByVarbit(
            ObjectManager objectManager, int varbitId) {
        List<ObjectDefinition> result = new ArrayList<>();
        for (ObjectDefinition def : objectManager.getObjects()) {
            if (def.getVarbitID() == varbitId) result.add(def);
        }
        result.sort((a, b) -> Integer.compare(a.getId(), b.getId()));
        return result;
    }

    // -------------------------------------------------------------------------
    // Item queries
    // -------------------------------------------------------------------------

    /**
     * Returns all items whose name contains any of the given {@code substrings}
     * (case-insensitive). The result is sorted ascending by item ID.
     *
     * <p>Note: the caller must have already called {@link ItemManager#load()}
     * before invoking this method.
     */
    public static List<ItemDefinition> findItemsByNameSubstring(
            ItemManager itemManager, String... substrings) {
        List<ItemDefinition> result = new ArrayList<>();
        for (ItemDefinition item : itemManager.getItems()) {
            String name = item.getName();
            if (name == null) continue;
            String lower = name.toLowerCase();
            for (String sub : substrings) {
                if (lower.contains(sub.toLowerCase())) { result.add(item); break; }
            }
        }
        result.sort((a, b) -> Integer.compare(a.getId(), b.getId()));
        return result;
    }
}
