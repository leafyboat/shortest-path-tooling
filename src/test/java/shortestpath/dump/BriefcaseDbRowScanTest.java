package shortestpath.dump;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import net.runelite.cache.DBRowManager;
import net.runelite.cache.DBTableManager;
import net.runelite.cache.definitions.DBRowDefinition;
import net.runelite.cache.definitions.DBTableDefinition;
import net.runelite.cache.fs.Store;
import net.runelite.cache.util.ScriptVarType;
import org.junit.Assume;
import org.junit.Test;

/**
 * Scans every DBRow for column values that match a canonical destination-name list.
 * Groups hits by tableId so we see if a single DBTable holds the briefcase data.
 */
public class BriefcaseDbRowScanTest {

    @Test
    public void scan() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("briefcase.db.scan"));

        String cacheDir = CacheUtils.requiredProperty("briefcase.db.cacheDir");
        String namesFile = CacheUtils.requiredProperty("briefcase.db.namesFile");
        String outPath = System.getProperty("briefcase.db.outPath", "build/briefcase-dbrow.txt");

        Set<String> canonical = new HashSet<>();
        for (String line : Files.readAllLines(Paths.get(namesFile))) {
            String s = line.trim();
            if (!s.isEmpty()) canonical.add(s.toLowerCase(Locale.ROOT));
        }

        try (Store store = CacheUtils.openStore(cacheDir);
             PrintWriter out = new PrintWriter(outPath)) {
            DBTableManager tables = new DBTableManager(store);
            tables.load();
            DBRowManager rows = new DBRowManager(store);
            rows.load();

            // Group: tableId -> hitCount
            TreeMap<Integer, Integer> tableHits = new TreeMap<>();
            TreeMap<Integer, Integer> tableTotalRows = new TreeMap<>();
            TreeMap<Integer, Integer> tableSampleRow = new TreeMap<>();

            int hitRowCount = 0;
            int totalRowCount = 0;
            for (DBRowDefinition row : rows.getRows()) {
                totalRowCount++;
                int tid = row.getTableId();
                tableTotalRows.merge(tid, 1, Integer::sum);
                if (row.getColumnValues() == null) continue;
                boolean rowHit = false;
                for (Object[] colArr : row.getColumnValues()) {
                    if (colArr == null) continue;
                    for (Object v : colArr) {
                        if (v instanceof String) {
                            String s = ((String) v).toLowerCase(Locale.ROOT);
                            if (canonical.contains(s)) { rowHit = true; break; }
                        }
                    }
                    if (rowHit) break;
                }
                if (rowHit) {
                    hitRowCount++;
                    tableHits.merge(tid, 1, Integer::sum);
                    tableSampleRow.putIfAbsent(tid, row.getId());
                }
            }

            String summary = "Total DBRows=" + totalRowCount + " hitRows=" + hitRowCount;
            System.out.println(summary);
            out.println(summary);
            out.println();
            out.println("Per-table hits:");
            for (var e : tableHits.entrySet()) {
                int tid = e.getKey();
                out.println("  tableId=" + tid
                    + "\thits=" + e.getValue()
                    + "\ttotalRows=" + tableTotalRows.getOrDefault(tid, 0)
                    + "\tsampleRowId=" + tableSampleRow.get(tid));
                System.out.println("  tableId=" + tid
                    + " hits=" + e.getValue()
                    + " totalRows=" + tableTotalRows.getOrDefault(tid, 0));
            }

            // Dump every row from the top hit tables (>= 30 hits).
            for (var e : tableHits.entrySet()) {
                if (e.getValue() < 5) continue;
                int tid = e.getKey();
                DBTableDefinition tableDef = tables.get(tid);
                out.println();
                out.println("=== Table " + tid + " full dump (" + tableTotalRows.get(tid) + " rows) ===");
                if (tableDef != null && tableDef.getTypes() != null) {
                    for (int i = 0; i < tableDef.getTypes().length; i++) {
                        ScriptVarType[] cts = tableDef.getTypes()[i];
                        StringBuilder cb = new StringBuilder("  col[").append(i).append("] types=[");
                        if (cts != null) {
                            for (int j = 0; j < cts.length; j++) {
                                if (j > 0) cb.append(',');
                                cb.append(cts[j]);
                            }
                        }
                        cb.append("]");
                        out.println(cb);
                    }
                }
                for (DBRowDefinition row : rows.getRows()) {
                    if (row.getTableId() != tid) continue;
                    out.print("  row " + row.getId() + ":");
                    Object[][] cv = row.getColumnValues();
                    if (cv == null) { out.println(" (null)"); continue; }
                    out.println();
                    for (int i = 0; i < cv.length; i++) {
                        StringBuilder sb = new StringBuilder("    col[").append(i).append("] = ");
                        Object[] arr = cv[i];
                        if (arr == null) { sb.append("null"); }
                        else {
                            sb.append('[');
                            for (int j = 0; j < arr.length; j++) {
                                if (j > 0) sb.append(", ");
                                Object v = arr[j];
                                sb.append(v == null ? "null" : v.toString());
                                if (v instanceof Integer) {
                                    int vv = (Integer) v;
                                    int z = (vv >>> 28) & 0x3;
                                    int x = (vv >>> 14) & 0x3FFF;
                                    int y = vv & 0x3FFF;
                                    if (z < 4 && x >= 1024 && x <= 4096 && y >= 1024 && y <= 16383 && (vv >>> 30) == 0) {
                                        sb.append(" WP(").append(x).append(',').append(y).append(',').append(z).append(')');
                                    }
                                }
                            }
                            sb.append(']');
                        }
                        out.println(sb);
                    }
                }
            }
        }
    }
}
