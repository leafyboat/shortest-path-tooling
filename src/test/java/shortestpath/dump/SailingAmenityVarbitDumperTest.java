package shortestpath.dump;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.junit.Assume;
import org.junit.Test;

/**
 * Finds the varbits that gate the Sailing-island construction-built bank
 * chests.
 *
 * The wiki's Bank chest (amenity) infobox names two object IDs:
 *   58662 — "Bank chest space" (unbuilt)
 *   58663 — "Bank chest" (built)
 *
 * Both definitions carry no varbit and are never placed in the landscape
 * directly. Each physical chest is a multi-loc parent object whose
 * {@code configChangeDest} transforms between 58662 and 58663 based on its
 * varbit. This dumper:
 *   1. finds every parent object whose transforms reference 58662/58663,
 *   2. prints that parent's varbit,
 *   3. looks up the parent's one and only world placement.
 *
 * Run with:
 *   ./gradlew sailingAmenityVarbitDump \
 *     -PsailingAmenityCacheDir=$PWD/cache \
 *     -PsailingAmenityXteaPath=$PWD/keys.json
 */
public class SailingAmenityVarbitDumperTest {
    private static final int[] SEED_IDS = {58662, 58663};
    // Sailors' Marker scenery object IDs (Port Roberts, Red Rock, Deepfin Point).
    // Interacting with a marker unlocks the Sailors' amulet teleport to that location.
    private static final int[] SAILORS_MARKER_IDS = {59985, 59986, 59988};

    @Test
    public void dumpSailingAmenityVarbits() throws Exception {
        Assume.assumeTrue(
            "Enable with -Dsailing.amenity.dump=true and supply -Dsailing.amenity.cacheDir / -Dsailing.amenity.xteaPath",
            Boolean.getBoolean("sailing.amenity.dump"));

        String cacheDir = CacheUtils.requiredProperty("sailing.amenity.cacheDir");
        String xteaPath = CacheUtils.requiredProperty("sailing.amenity.xteaPath");

        XteaKeyManager xteaKeyManager = CacheUtils.loadXteaKeys(xteaPath);

        try (Store store = CacheUtils.openStore(cacheDir)) {
            ObjectManager objectManager = new ObjectManager(store);
            objectManager.load();

            // 1. Parent multi-loc objects that transform into the seed IDs.
            List<ObjectDefinition> parents = CacheUtils.collectMultiLocParents(objectManager, SEED_IDS);

            // 2. World placements of those parents (one per island).
            RegionLoader regionLoader = CacheUtils.loadRegions(store, xteaKeyManager);
            regionLoader.calculateBounds();

            Map<Integer, int[]> placement =
                CacheUtils.collectFirstPlacementByObjectId(regionLoader, CacheUtils.parentIdSet(parents));

            // 3. Report.
            System.out.println("id\tvarbitId\tvarpId\ttransforms\tx\ty\tplane");
            for (ObjectDefinition p : parents) {
                int[] pos = placement.get(p.getId());
                String coord = pos == null ? "?\t?\t?" : pos[0] + "\t" + pos[1] + "\t" + pos[2];
                System.out.println(p.getId()
                    + "\t" + p.getVarbitID()
                    + "\t" + p.getVarpID()
                    + "\t" + Arrays.toString(p.getConfigChangeDest())
                    + "\t" + coord);
            }

            // 4. Rowboat dump. Same pattern as bank chests but seeded on the rowboat
            //    object IDs: 58658 = "Rowboat space" (unbuilt), 58659 = "Rowboat" (built).
            //    Uses tile settings (bit 0 = BLOCKED/water) to identify which face of each
            //    rowboat footprint is accessible, giving the exact player departure tile.
            System.out.println();
            System.out.println("=== Rowboats ===");

            int[] rowboatSeedIds = {58658, 58659};
            List<ObjectDefinition> rowboatParents = CacheUtils.collectMultiLocParents(objectManager, rowboatSeedIds);
            Set<Integer> rowboatParentIds = CacheUtils.parentIdSet(rowboatParents);

            // Collect all Location objects (preserving orientation) per parent ID.
            Map<Integer, List<Location>> rowboatLocations =
                CacheUtils.collectLocationsByObjectId(regionLoader, rowboatParentIds);

            Map<Long, Region> tileToRegion = CacheUtils.buildTileToRegion(regionLoader);

            // The departure tile is the land (overlayId == 0) adjacent tile closest to the
            // object's geometric center: centerX = objX + sizeX/2, centerY = objY + sizeY/2.
            System.out.println("id\tvarbitId\ttransforms\tobjX\tobjY\tplane\tori\tsizeX\tsizeY\tdeparture");
            for (ObjectDefinition p : rowboatParents) {
                List<Location> locs = rowboatLocations.containsKey(p.getId())
                    ? rowboatLocations.get(p.getId()) : new ArrayList<>();
                for (Location loc : locs) {
                    Position pos = loc.getPosition();
                    int ori = loc.getOrientation();
                    int sizeX = CacheUtils.effectiveSizeX(p, ori);
                    int sizeY = CacheUtils.effectiveSizeY(p, ori);
                    int ox = pos.getX(), oy = pos.getY(), oz = pos.getZ();
                    int[] dep = CacheUtils.closestLandAdjacentTile(ox, oy, oz, sizeX, sizeY, tileToRegion);
                    String departure = dep == null ? "?" : dep[0] + "," + dep[1] + "," + oz;
                    System.out.println(p.getId()
                        + "\t" + p.getVarbitID()
                        + "\t" + Arrays.toString(p.getConfigChangeDest())
                        + "\t" + ox + "\t" + oy + "\t" + oz
                        + "\t" + ori + "\t" + sizeX + "\t" + sizeY
                        + "\t" + departure);
                }
            }
            System.out.println();

            // 5. Sailors' Marker dump. Print each marker's own varbit/varp, any
            //    multi-loc parent that transforms into it, and every world placement.
            System.out.println();
            System.out.println("=== Sailors' Markers ===");
            System.out.println("id\tname\tvarbitId\tvarpId\ttransforms");
            Set<Integer> markerSet = new HashSet<>();
            for (int id : SAILORS_MARKER_IDS) markerSet.add(id);
            // Direct marker definitions (carry their own varbit/varp):
            for (ObjectDefinition def : objectManager.getObjects()) {
                if (!markerSet.contains(def.getId())) continue;
                System.out.println(def.getId()
                    + "\t" + def.getName()
                    + "\t" + def.getVarbitID()
                    + "\t" + def.getVarpID()
                    + "\t" + Arrays.toString(def.getConfigChangeDest()));
            }
            // Multi-loc parents that transform into a marker:
            List<ObjectDefinition> markerParents =
                CacheUtils.collectMultiLocParents(objectManager, SAILORS_MARKER_IDS);
            System.out.println();
            System.out.println("=== Sailors' Marker parents (multi-loc) ===");
            System.out.println("id\tname\tvarbitId\tvarpId\ttransforms");
            for (ObjectDefinition p : markerParents) {
                System.out.println(p.getId()
                    + "\t" + p.getName()
                    + "\t" + p.getVarbitID()
                    + "\t" + p.getVarpID()
                    + "\t" + Arrays.toString(p.getConfigChangeDest()));
            }
            System.out.println();
            System.out.println("=== Sailors' Marker placements ===");
            System.out.println("id\tx\ty\tplane");
            Set<Integer> markerPlusParentIds = new HashSet<>(markerSet);
            for (ObjectDefinition p : markerParents) markerPlusParentIds.add(p.getId());
            for (Location loc : CacheUtils.collectLocations(regionLoader, markerPlusParentIds)) {
                Position pos = loc.getPosition();
                System.out.println(loc.getId() + "\t" + pos.getX() + "\t" + pos.getY() + "\t" + pos.getZ());
            }

            // 5. Sailors' amulet item dump. Walk every item named "Sailors' amulet..." and print
            //    inventoryActions (interfaceOptions), subops, params, and countObj/countCo chains.
            //    Teleport-gating varbits on OSRS items are typically stored in the item's params map
            //    (int-keyed) or implied by the subops that a cs2 script toggles.
            System.out.println();
            System.out.println("=== Sailors' amulet items ===");
            ItemManager itemManager = new ItemManager(store);
            itemManager.load();
            List<ItemDefinition> matches =
                CacheUtils.findItemsByNameSubstring(itemManager, "sailors' amulet", "sailor's amulet");
            for (ItemDefinition item : matches) {
                System.out.println();
                System.out.println("-- id=" + item.getId() + " name=\"" + item.getName() + "\" --");
                System.out.println("  examine:          " + item.getExamine());
                System.out.println("  notedID:          " + item.notedID);
                System.out.println("  placeholderId:    " + item.placeholderId);
                System.out.println("  boughtId:         " + item.boughtId);
                System.out.println("  countCo:          " + Arrays.toString(item.getCountCo()));
                System.out.println("  countObj:         " + Arrays.toString(item.getCountObj()));
                System.out.println("  interfaceOptions: " + Arrays.toString(item.getInterfaceOptions()));
                String[][] subops = item.getSubops();
                if (subops != null) {
                    for (int i = 0; i < subops.length; i++) {
                        System.out.println("  subops[" + i + "]:        " + Arrays.toString(subops[i]));
                    }
                }
                Map<Integer, Object> params = item.params;
                if (params != null && !params.isEmpty()) {
                    // Sort params by key for readability.
                    List<Integer> keys = new ArrayList<>(params.keySet());
                    keys.sort(Integer::compareTo);
                    for (Integer k : keys) {
                        System.out.println("  param[" + k + "] = " + params.get(k));
                    }
                }
            }

            // Note: the cs2 script referenced by param[2257] on item 32399 handles the Teleport
            // submenu gating, but the bundled runelite-cache 1.12.24 ScriptLoader can't
            // reliably decode current-rev scripts, so we don't extract the unlock varbit here.
            // The subop indices (0=Pandemonium, 1=Port Roberts, 4=Deepfin Point) and the marker
            // coordinates from section 4 are enough for a contributor to identify the varbit
            // in-game via RuneLite's varbit inspector.
        }
    }

}
