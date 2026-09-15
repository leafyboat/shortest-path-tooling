package shortestpath.dump;

import java.io.PrintWriter;
import net.runelite.cache.DBTableManager;
import net.runelite.cache.DBRowManager;
import net.runelite.cache.definitions.DBTableDefinition;
import net.runelite.cache.definitions.DBRowDefinition;
import net.runelite.cache.fs.Store;
import net.runelite.cache.util.ScriptVarType;
import org.junit.Assume;
import org.junit.Test;

/**
 * Enumerates DBTables matching the Varlamore-style schema:
 *   [INTEGER, STRING, COORDGRID, MODEL, INTEGER, INTEGER, BOOLEAN]
 * Briefcase region tables likely follow this template.
 */
public class BriefcaseTeleportTablesTest {

    @Test
    public void scan() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("briefcase.tt.scan"));
        String cacheDir = CacheUtils.requiredProperty("briefcase.tt.cacheDir");
        String outPath = System.getProperty("briefcase.tt.outPath", "build/briefcase-teleport-tables.txt");

        try (Store store = CacheUtils.openStore(cacheDir);
             PrintWriter out = new PrintWriter(outPath)) {
            DBTableManager tables = new DBTableManager(store);
            tables.load();
            DBRowManager rows = new DBRowManager(store);
            rows.load();

            // Pattern: COORDGRID column in any position, STRING column in any position.
            for (DBTableDefinition t : tables.getTables()) {
                if (t.getTypes() == null) continue;
                int coordCol = -1, stringCol = -1;
                for (int i = 0; i < t.getTypes().length; i++) {
                    ScriptVarType[] ct = t.getTypes()[i];
                    if (ct == null) continue;
                    for (ScriptVarType v : ct) {
                        if (v == ScriptVarType.COORDGRID && coordCol < 0) coordCol = i;
                        if (v == ScriptVarType.STRING && stringCol < 0) stringCol = i;
                    }
                }
                if (coordCol < 0 || stringCol < 0) continue;
                int rowCount = 0;
                for (DBRowDefinition r : rows.getRows()) if (r.getTableId() == t.getId()) rowCount++;
                if (rowCount < 5) continue;
                out.println("table=" + t.getId()
                    + " rows=" + rowCount
                    + " coordCol=" + coordCol
                    + " stringCol=" + stringCol
                    + " cols=" + t.getTypes().length);
                // Print first 5 rows.
                int shown = 0;
                for (DBRowDefinition r : rows.getRows()) {
                    if (r.getTableId() != t.getId()) continue;
                    if (r.getColumnValues() == null) continue;
                    Object[] sArr = r.getColumnValues()[stringCol];
                    Object[] cArr = r.getColumnValues()[coordCol];
                    String s = (sArr != null && sArr.length > 0 && sArr[0] != null) ? sArr[0].toString() : "?";
                    String c = "?";
                    if (cArr != null && cArr.length > 0 && cArr[0] instanceof Integer) {
                        int v = (Integer) cArr[0];
                        int z = (v >>> 28) & 0x3, x = (v >>> 14) & 0x3FFF, y = v & 0x3FFF;
                        c = "WP(" + x + "," + y + "," + z + ")";
                    }
                    out.println("  row " + r.getId() + " -> " + s + " " + c);
                    if (++shown >= 6) break;
                }
            }
        }
    }
}
