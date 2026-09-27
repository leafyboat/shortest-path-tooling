package shortestpath.dump;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;
import net.runelite.cache.DBRowManager;
import net.runelite.cache.EntityOpsDefinition;
import net.runelite.cache.ObjectManager;
import net.runelite.cache.definitions.ObjectDefinition;
import net.runelite.cache.definitions.DBRowDefinition;
import net.runelite.cache.fs.Store;
import net.runelite.cache.region.Location;
import net.runelite.cache.region.Position;
import net.runelite.cache.region.Region;
import net.runelite.cache.region.RegionLoader;
import net.runelite.cache.util.ScriptVarType;
import net.runelite.cache.util.XteaKeyManager;
import org.junit.Assume;
import org.junit.Test;

/**
 * Probes how sailing water tiles are represented in the OSRS cache, to judge
 * whether a "sailable" mask can be generated alongside collision-map.zip.
 *
 * Questions answered:
 *   1. Which (tileSetting, overlayId, underlayId) signature marks water?
 *      - Land vs water on the collision map is ambiguous today: the dumper only
 *        records wall flags, and water tiles end up fully blocked.
 *   2. Do the DBTable SailingSea centre coords land in regions classified as
 *      ocean? (sanity check for the classifier)
 *   3. Do ocean tiles carry locations (rapids, kelp, hazards as objects)?
 *
 * Run with:
 *   ./gradlew sailingWaterProbe \
 *     -PsailingWaterCacheDir=$PWD/cache \
 *     -PsailingWaterXteaPath=$PWD/keys.json
 */
public class SailingWaterProbeTest {
    // DBTableID.SailingSea.Row.SAILING_SEA_ARDENT_OCEAN — used to locate the table.
    private static final int SAILING_SEA_ROW_SEED = 9281;

    @Test
    public void probeWaterSignatures() throws Exception {
        Assume.assumeTrue(
            "Enable with -Dsailing.water.probe=true and supply -Dsailing.water.cacheDir / -Dsailing.water.xteaPath",
            Boolean.getBoolean("sailing.water.probe"));

        String cacheDir = CacheUtils.requiredProperty("sailing.water.cacheDir");
        String xteaPath = CacheUtils.requiredProperty("sailing.water.xteaPath");

        try (Store store = CacheUtils.openStore(cacheDir)) {
            XteaKeyManager xtea = CacheUtils.loadXteaKeys(xteaPath);
            RegionLoader regionLoader = CacheUtils.loadRegions(store, xtea);
            regionLoader.calculateBounds();

            // ---- Pass 1: classify every region's plane-0 tiles. ----
            // tileSetting bit 0 is the nomove flag (water, rooftops). Water is
            // expected to also carry a floor overlay (isLandTile uses overlay==0).
            Map<Integer, Integer> tileSettingHistogram = new TreeMap<>();
            List<int[]> oceanCandidates = new ArrayList<>(); // regionId, waterishCount, openCount
            Map<Integer, Integer> waterOverlayIds = new TreeMap<>();   // overlayId -> count, in blocked+overlay tiles
            Map<Integer, Integer> waterUnderlayIds = new TreeMap<>();

            for (Region region : regionLoader.getRegions()) {
                int blockedOverlay = 0, openNoOverlay = 0, other = 0;
                for (int lx = 0; lx < Region.X; lx++) {
                    for (int ly = 0; ly < Region.Y; ly++) {
                        int ts = region.getTileSetting(0, lx, ly) & 0xFF;
                        int ov = region.getOverlayId(0, lx, ly);
                        int un = region.getUnderlayId(0, lx, ly);
                        tileSettingHistogram.merge(ts, 1, Integer::sum);
                        boolean blocked = (ts & 1) != 0;
                        if (blocked && ov != 0) {
                            blockedOverlay++;
                            waterOverlayIds.merge(ov, 1, Integer::sum);
                            waterUnderlayIds.merge(un, 1, Integer::sum);
                        } else if (!blocked && ov == 0) {
                            openNoOverlay++;
                        } else {
                            other++;
                        }
                    }
                }
                if (blockedOverlay > 3000) { // > ~73% of the 4096 plane-0 tiles
                    oceanCandidates.add(new int[]{region.getRegionID(), blockedOverlay, openNoOverlay, other});
                }
            }

            System.out.println("=== tileSetting byte histogram (all regions, plane 0) ===");
            for (Map.Entry<Integer, Integer> e : tileSettingHistogram.entrySet()) {
                System.out.println("tileSetting=" + e.getKey() + " count=" + e.getValue());
            }

            System.out.println();
            System.out.println("=== ocean-candidate regions (blocked+overlay > 3000/4096) ===");
            System.out.println("regionId\tregionXY\tblockedOv\topenNoOv\tother");
            for (int[] c : oceanCandidates) {
                int rx = c[0] >> 8, ry = c[0] & 0xFF;
                System.out.println(c[0] + "\t" + rx + "_" + ry + "\t" + c[1] + "\t" + c[2] + "\t" + c[3]);
            }
            System.out.println("total ocean candidates: " + oceanCandidates.size());

            System.out.println();
            System.out.println("=== top overlay ids on blocked+overlay tiles (water suspects) ===");
            waterOverlayIds.entrySet().stream()
                .sorted(Map.Entry.<Integer, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(25)
                .forEach(e -> System.out.println("overlay=" + e.getKey() + " tiles=" + e.getValue()));
            System.out.println();
            System.out.println("=== top underlay ids on blocked+overlay tiles ===");
            waterUnderlayIds.entrySet().stream()
                .sorted(Map.Entry.<Integer, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(15)
                .forEach(e -> System.out.println("underlay=" + e.getKey() + " tiles=" + e.getValue()));

            // ---- Pass 2: SailingSea DBTable centres -> region check. ----
            System.out.println();
            System.out.println("=== SailingSea table ===");
            DBRowManager rows = new DBRowManager(store);
            rows.load();
            DBRowDefinition seed = null;
            for (DBRowDefinition r : rows.getRows()) {
                if (r.getId() == SAILING_SEA_ROW_SEED) { seed = r; break; }
            }
            Map<Long, Region> tileToRegion = CacheUtils.buildTileToRegion(regionLoader);
            if (seed == null) {
                System.out.println("seed row " + SAILING_SEA_ROW_SEED + " not found");
            } else {
                int tableId = seed.getTableId();
                System.out.println("tableId=" + tableId);
                for (DBRowDefinition r : rows.getRows()) {
                    if (r.getTableId() != tableId || r.getColumnValues() == null) continue;
                    String name = null;
                    Integer coord = null;
                    for (int i = 0; i < r.getColumnValues().length; i++) {
                        Object[] vals = r.getColumnValues()[i];
                        ScriptVarType[] types = r.getColumnTypes() != null && i < r.getColumnTypes().length
                            ? r.getColumnTypes()[i] : null;
                        if (vals == null || vals.length == 0 || vals[0] == null) continue;
                        if (name == null && vals[0] instanceof String && !((String) vals[0]).isEmpty()) {
                            name = (String) vals[0];
                        }
                        if (coord == null && vals[0] instanceof Integer
                            && types != null && containsType(types, ScriptVarType.COORDGRID)) {
                            coord = (Integer) vals[0];
                        }
                    }
                    String where = "?";
                    if (coord != null) {
                        int z = (coord >>> 28) & 0x3, x = (coord >>> 14) & 0x3FFF, y = coord & 0x3FFF;
                        Region r2 = CacheUtils.regionForTile(tileToRegion, x, y);
                        where = "WP(" + x + "," + y + "," + z + ") region="
                            + (r2 == null ? "none" : String.valueOf(r2.getRegionID()));
                        if (r2 != null) {
                            int lx = x - r2.getBaseX(), ly = y - r2.getBaseY();
                            where += " ts=" + (r2.getTileSetting(0, lx, ly) & 0xFF)
                                + " ov=" + r2.getOverlayId(0, lx, ly)
                                + " un=" + r2.getUnderlayId(0, lx, ly);
                        }
                    }
                    System.out.println("  row=" + r.getId() + " name=" + name + " " + where);
                }
            }

            // ---- Pass 3: locations inside ocean regions (hazards?). ----
            // Ocean candidates above used the (wrong) blocked+overlay guess; instead
            // gather objects from regions that host SailingSea centre tiles.
            System.out.println();
            System.out.println("=== locations inside SailingSea-centre regions' neighbourhood (plane 0) ===");
            Set<Integer> seaRegions = new HashSet<>();
            for (DBRowDefinition r : rows.getRows()) {
                if (r.getColumnValues() == null) continue;
                for (int i = 0; i < r.getColumnValues().length; i++) {
                    Object[] vals = r.getColumnValues()[i];
                    ScriptVarType[] types = r.getColumnTypes() != null && i < r.getColumnTypes().length
                        ? r.getColumnTypes()[i] : null;
                    if (vals == null || vals.length == 0 || !(vals[0] instanceof Integer)) continue;
                    if (types == null || !containsType(types, ScriptVarType.COORDGRID)) continue;
                    int c = (Integer) vals[0];
                    int x = (c >>> 14) & 0x3FFF, y = c & 0x3FFF;
                    Region rr = CacheUtils.regionForTile(tileToRegion, x, y);
                    if (rr != null) {
                        // centre region + 8 neighbours: seas span multiple regions
                        int rx = rr.getRegionX(), ry = rr.getRegionY();
                        for (int dx = -1; dx <= 1; dx++) {
                            for (int dy = -1; dy <= 1; dy++) {
                                Region n = regionLoader.findRegionForRegionCoordinates(rx + dx, ry + dy);
                                if (n != null) seaRegions.add(n.getRegionID());
                            }
                        }
                    }
                }
            }
            System.out.println("sea regions in scope: " + seaRegions.size());
            ObjectManager objectManager = new ObjectManager(store);
            objectManager.load();
            Map<Integer, Integer> objectIds = new TreeMap<>();
            for (Region region : regionLoader.getRegions()) {
                if (!seaRegions.contains(region.getRegionID())) continue;
                for (Location loc : region.getLocations()) {
                    Position p = loc.getPosition();
                    if (p.getZ() == 0) objectIds.merge(loc.getId(), 1, Integer::sum);
                }
            }
            System.out.println("object\tname\tplacements\tinteractType\tsizeX\tsizeY");
            objectIds.entrySet().stream()
                .sorted(Map.Entry.<Integer, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(40)
                .forEach(e -> {
                    ObjectDefinition def = null;
                    try { def = objectManager.getObject(e.getKey()); } catch (Exception ignored) {}
                    System.out.println(e.getKey() + "\t" + (def == null ? "?" : def.getName())
                        + "\t" + e.getValue()
                        + "\t" + (def == null ? "?" : def.getInteractType())
                        + "\t" + (def == null ? "?" : def.getSizeX())
                        + "\t" + (def == null ? "?" : def.getSizeY()));
                });

            // ---- Pass 3b: SailingCustomisationLocAngles — the discrete facings
            // boat locs render at. Column COL_ANGLES holds the angle list; if
            // boats snap to N headings this table should reveal it. ----
            System.out.println();
            System.out.println("=== SailingCustomisationLocAngles (row 8391 seed) ===");
            dumpTable(rows, 8391);
            System.out.println();
            System.out.println("=== SailingBoatFacilityStats: steering rows (8203-8223 seed 8203) ===");
            dumpTableRange(rows, 8203, 8203, 8223);
            System.out.println();
            System.out.println("=== SailingBoat table (boat size, seed 8110) ===");
            dumpTable(rows, 8110);

            // ---- Pass 4: detail for a couple of regions. ----
            detailRegion(regionLoader, tileToRegion, "Port Sarim dock area", 3041, 3194);
            detailRegion(regionLoader, tileToRegion, "sea south of Port Sarim", 3041, 3100);
            detailRegion(regionLoader, tileToRegion, "deep ocean west of Tirannwn", 1700, 3100);

            // ---- Pass 5: SailingDock + SailingSeaHazard + full SailingSea rows. ----
            System.out.println();
            System.out.println("=== SailingDock table (seed 8587) ===");
            dumpTable(rows, 8587);
            System.out.println();
            System.out.println("=== SailingSeaHazard table (seed 9270) ===");
            dumpTable(rows, 9270);
            System.out.println();
            System.out.println("=== SailingShoal table (seed 8553) ===");
            dumpTable(rows, 8553);
            System.out.println();
            System.out.println("=== SailingSea full rows (seed 9281) ===");
            dumpTable(rows, 9281);

            // ---- Pass 6: shallow vs deep water overlays. ----
            // For every water-overlay tile in sea regions, measure Chebyshev
            // distance to the nearest non-water tile within radius 4. Overlays
            // that only ever appear beside land are shallows/draft-limited.
            System.out.println();
            System.out.println("=== water overlays by shore distance (sea regions, plane 0) ===");
            Map<Integer, int[]> overlayShore = new TreeMap<>(); // overlay -> [shore(<=2), mid(3-4), deep(>4)]
            for (Region region : regionLoader.getRegions()) {
                if (!seaRegions.contains(region.getRegionID())) continue;
                for (int lx = 0; lx < Region.X; lx++) {
                    for (int ly = 0; ly < Region.Y; ly++) {
                        int ov = region.getOverlayId(0, lx, ly);
                        if (ov == 0 || (region.getTileSetting(0, lx, ly) & 1) != 0) continue;
                        int dist = shoreDistance(region, lx, ly, 4);
                        int[] bands = overlayShore.computeIfAbsent(ov, k -> new int[3]);
                        bands[dist <= 2 ? 0 : dist <= 4 ? 1 : 2]++;
                    }
                }
            }
            System.out.println("overlay\tshore(<=2)\tmid(3-4)\tdeep(>4)");
            overlayShore.entrySet().stream()
                .sorted(Map.Entry.<Integer, int[]>comparingByValue(
                    (a, b) -> Integer.compare(b[0] + b[1] + b[2], a[0] + a[1] + a[2])))
                .forEach(e -> System.out.println(e.getKey() + "\t" + e.getValue()[0]
                    + "\t" + e.getValue()[1] + "\t" + e.getValue()[2]));

            // Underlays under the confirmed deep-water overlays — if water has a
            // stable underlay id set it separates water from land-decor overlays.
            Set<Integer> waterOvs = new HashSet<>(List.of(
                442, 445, 448, 451, 454, 469, 547, 577, 583, 607, 517, 532, 562,
                571, 574, 580, 595, 604, 487, 475, 484, 505, 514, 520, 529, 526,
                544, 565, 568, 523, 541, 535, 508, 601, 619, 622, 625, 613, 598,
                616, 579, 559, 567, 610, 463, 478, 323, 466));
            Map<Integer, Integer> underlayOnWaterOv = new TreeMap<>();
            Map<Integer, Integer> underlayOnOtherOv = new TreeMap<>();
            for (Region region : regionLoader.getRegions()) {
                if (!seaRegions.contains(region.getRegionID())) continue;
                for (int lx = 0; lx < Region.X; lx++) {
                    for (int ly = 0; ly < Region.Y; ly++) {
                        int ov = region.getOverlayId(0, lx, ly);
                        int un = region.getUnderlayId(0, lx, ly);
                        int ts = region.getTileSetting(0, lx, ly) & 0xFF;
                        if (ov == 0 || (ts & 1) != 0) continue;
                        (waterOvs.contains(ov) ? underlayOnWaterOv : underlayOnOtherOv)
                            .merge(un, 1, Integer::sum);
                    }
                }
            }
            System.out.println();
            System.out.println("=== underlays under water overlays vs other overlays ===");
            System.out.println("water-underlays:");
            underlayOnWaterOv.entrySet().stream()
                .sorted(Map.Entry.<Integer, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(15)
                .forEach(e -> System.out.println("  un=" + e.getKey() + " tiles=" + e.getValue()));
            System.out.println("other-overlay underlays:");
            underlayOnOtherOv.entrySet().stream()
                .sorted(Map.Entry.<Integer, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(15)
                .forEach(e -> System.out.println("  un=" + e.getKey() + " tiles=" + e.getValue()));

            // ---- Pass 6b: dock/mooring objects. SailingDock rows carry no
            // coordinates, so find the world objects that mark a dock. ----
            System.out.println();
            System.out.println("=== object defs named mooring/dock/gangplank/buoy ===");
            java.util.Collection<ObjectDefinition> allObjects = objectManager.getObjects();
            for (ObjectDefinition def : allObjects) {
                String n = def.getName();
                if (n == null || n.equals("null")) continue;
                String ln = n.toLowerCase();
                if (ln.contains("mooring") || ln.contains("gangplank") || ln.contains("dock")
                    || ln.contains("buoy")) {
                    StringBuilder ops = new StringBuilder();
                    if (def.getOps() != null && def.getOps().getOps() != null) {
                        for (EntityOpsDefinition.Op op : def.getOps().getOps()) {
                            if (op != null && op.text != null) ops.append('[').append(op.text).append(']');
                        }
                    }
                    System.out.println("  id=" + def.getId() + " name=" + n
                        + " interactType=" + def.getInteractType()
                        + " size=" + def.getSizeX() + "x" + def.getSizeY()
                        + " ops=" + ops);
                }
            }

            // ---- Pass 6c: where are the dock markers placed? ----
            // Dock buoy 59769-59828 (op=Dock), mooring points 59833/59834
            // (Board/Disembark), escape moorings 58444/58495, sabotaged mooring
            // 59368, board/disembark gangplanks 59720/59721/59831, buoys
            // 59236/60149/60461. Their placements are the actual dock waypoints.
            System.out.println();
            System.out.println("=== dock/mooring object placements (all regions) ===");
            Set<Integer> dockIds = new HashSet<>();
            for (int i = 59769; i <= 59828; i++) dockIds.add(i);
            dockIds.addAll(List.of(59833, 59834, 58444, 58495, 59368, 59720, 59721,
                59831, 59236, 60149, 60461));
            int dockBuoyCount = 0;
            for (Region region : regionLoader.getRegions()) {
                for (Location loc : region.getLocations()) {
                    if (!dockIds.contains(loc.getId())) continue;
                    Position p = loc.getPosition();
                    int lx = p.getX() - region.getBaseX(), ly = p.getY() - region.getBaseY();
                    String sig = "?";
                    if (lx >= 0 && lx < Region.X && ly >= 0 && ly < Region.Y) {
                        sig = "ts=" + (region.getTileSetting(p.getZ(), lx, ly) & 0xFF)
                            + " ov=" + region.getOverlayId(p.getZ(), lx, ly)
                            + " un=" + region.getUnderlayId(p.getZ(), lx, ly);
                    }
                    if (loc.getId() >= 59769 && loc.getId() <= 59828) dockBuoyCount++;
                    System.out.println("  obj=" + loc.getId() + " @WP(" + p.getX() + ","
                        + p.getY() + "," + p.getZ() + ") type=" + loc.getType()
                        + " orient=" + loc.getOrientation() + " " + sig);
                }
            }
            System.out.println("  dock buoys (op=Dock) found: " + dockBuoyCount);

            // ---- Pass 7: obstacles ON water tiles. ----
            // Only locations whose own tile carries a water overlay — the real
            // candidate set for boat-blocking hazards (rapids, rocks, kelp, ice).
            System.out.println();
            System.out.println("=== objects placed on water-overlay tiles (sea regions, plane 0) ===");
            Map<Integer, int[]> waterObjects = new TreeMap<>(); // objectId -> [count, interactType, type, sizeX, sizeY, blockingMask]
            Map<Integer, Map<Integer, Integer>> objectOverlays = new HashMap<>();
            for (Region region : regionLoader.getRegions()) {
                if (!seaRegions.contains(region.getRegionID())) continue;
                for (Location loc : region.getLocations()) {
                    Position p = loc.getPosition();
                    if (p.getZ() != 0) continue;
                    int lx = p.getX() - region.getBaseX(), ly = p.getY() - region.getBaseY();
                    if (lx < 0 || lx >= Region.X || ly < 0 || ly >= Region.Y) continue;
                    int ov = region.getOverlayId(0, lx, ly);
                    if (ov == 0 || (region.getTileSetting(0, lx, ly) & 1) != 0) continue;
                    ObjectDefinition def = null;
                    try { def = objectManager.getObject(loc.getId()); } catch (Exception ignored) {}
                    int[] rec = waterObjects.computeIfAbsent(loc.getId(), k -> new int[6]);
                    rec[0]++;
                    rec[1] = def == null ? -1 : def.getInteractType();
                    rec[2] = loc.getType();
                    rec[3] = def == null ? -1 : def.getSizeX();
                    rec[4] = def == null ? -1 : def.getSizeY();
                    rec[5] = def == null ? -1 : def.getBlockingMask();
                    objectOverlays.computeIfAbsent(loc.getId(), k -> new TreeMap<>())
                        .merge(ov, 1, Integer::sum);
                }
            }
            System.out.println("object\tinteractType\tlocType\tsizeX\tsizeY\tblockMask\tplacements\ttopOverlays");
            waterObjects.entrySet().stream()
                .sorted(Map.Entry.<Integer, int[]>comparingByValue(
                    (a, b) -> Integer.compare(b[0], a[0])))
                .limit(50)
                .forEach(e -> {
                    int[] rec = e.getValue();
                    String topOv = objectOverlays.get(e.getKey()).entrySet().stream()
                        .sorted(Map.Entry.<Integer, Integer>comparingByValue(Comparator.reverseOrder()))
                        .limit(3)
                        .map(en -> en.getKey() + "x" + en.getValue())
                        .collect(java.util.stream.Collectors.joining(","));
                    System.out.println(e.getKey() + "\t" + rec[1] + "\t" + rec[2] + "\t" + rec[3]
                        + "\t" + rec[4] + "\t" + rec[5] + "\t" + rec[0] + "\t" + topOv);
                });
        }
    }

    /** Chebyshev distance to the nearest non-water tile (no overlay or blocked), capped at max. */
    private static int shoreDistance(Region region, int lx, int ly, int max) {
        for (int d = 1; d <= max; d++) {
            for (int dx = -d; dx <= d; dx++) {
                for (int dy = -d; dy <= d; dy++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != d) continue;
                    int x = lx + dx, y = ly + dy;
                    if (x < 0 || x >= Region.X || y < 0 || y >= Region.Y) continue;
                    int ov = region.getOverlayId(0, x, y);
                    int ts = region.getTileSetting(0, x, y) & 0xFF;
                    if (ov == 0 || (ts & 1) != 0) return d;
                }
            }
        }
        return max + 1;
    }

    private static Integer tableIdOf(DBRowManager rows, int seedRowId) {
        for (DBRowDefinition r : rows.getRows()) {
            if (r.getId() == seedRowId) return r.getTableId();
        }
        return null;
    }

    private static void dumpTable(DBRowManager rows, int seedRowId) {
        Integer tableId = tableIdOf(rows, seedRowId);
        if (tableId == null) { System.out.println("seed row " + seedRowId + " not found"); return; }
        System.out.println("tableId=" + tableId);
        for (DBRowDefinition r : rows.getRows()) {
            if (r.getTableId() != tableId) continue;
            printRow(r);
        }
    }

    private static void dumpTableRange(DBRowManager rows, int seedRowId, int lo, int hi) {
        Integer tableId = tableIdOf(rows, seedRowId);
        if (tableId == null) { System.out.println("seed row " + seedRowId + " not found"); return; }
        System.out.println("tableId=" + tableId);
        for (DBRowDefinition r : rows.getRows()) {
            if (r.getTableId() != tableId || r.getId() < lo || r.getId() > hi) continue;
            printRow(r);
        }
    }

    private static void printRow(DBRowDefinition r) {
        StringBuilder sb = new StringBuilder("  row=" + r.getId());
        Object[][] cols = r.getColumnValues();
        ScriptVarType[][] types = r.getColumnTypes();
        if (cols == null) { System.out.println(sb + " <no values>"); return; }
        for (int i = 0; i < cols.length; i++) {
            Object[] vals = cols[i];
            if (vals == null || vals.length == 0) continue;
            sb.append(" | c").append(i);
            if (types != null && i < types.length && types[i] != null && types[i].length > 0 && types[i][0] != null) {
                sb.append('(').append(types[i][0].name()).append(')');
            }
            sb.append('=');
            for (int j = 0; j < vals.length; j++) {
                if (j > 0) sb.append(',');
                Object v = vals[j];
                if (v instanceof Integer && types != null && i < types.length
                    && types[i] != null && containsType(types[i], ScriptVarType.COORDGRID)) {
                    int c = (Integer) v;
                    sb.append("WP(").append((c >>> 14) & 0x3FFF).append(',')
                        .append(c & 0x3FFF).append(',').append((c >>> 28) & 0x3).append(')');
                } else {
                    sb.append(v);
                }
            }
        }
        System.out.println(sb);
    }

    private static boolean containsType(ScriptVarType[] types, ScriptVarType want) {
        for (ScriptVarType t : types) if (t == want) return true;
        return false;
    }

    private static void detailRegion(RegionLoader regionLoader, Map<Long, Region> tileToRegion,
                                     String label, int x, int y) {
        Region r = CacheUtils.regionForTile(tileToRegion, x, y);
        System.out.println();
        System.out.println("=== detail: " + label + " @ (" + x + "," + y + ") -> region "
            + (r == null ? "none" : r.getRegionID()) + " ===");
        if (r == null) return;
        Map<String, Integer> sig = new TreeMap<>();
        for (int lx = 0; lx < Region.X; lx++) {
            for (int ly = 0; ly < Region.Y; ly++) {
                int ts = r.getTileSetting(0, lx, ly) & 0xFF;
                int ov = r.getOverlayId(0, lx, ly);
                int un = r.getUnderlayId(0, lx, ly);
                sig.merge("ts=" + ts + " ov=" + ov + " un=" + un, 1, Integer::sum);
            }
        }
        sig.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
            .limit(15)
            .forEach(e -> System.out.println("  " + e.getKey() + " -> " + e.getValue()));
        long locs = r.getLocations().stream().filter(l -> l.getPosition().getZ() == 0).count();
        System.out.println("  plane-0 locations: " + locs);
    }
}
