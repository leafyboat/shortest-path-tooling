package shortestpath.dump;

import java.util.ArrayList;
import java.util.List;
import net.runelite.cache.IndexType;
import net.runelite.cache.definitions.ScriptDefinition;
import net.runelite.cache.definitions.loaders.ScriptLoader;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Storage;
import net.runelite.cache.fs.Store;
import net.runelite.cache.util.XteaKeyManager;
import org.junit.Assume;
import org.junit.Test;

/**
 * One-off probe: find CLIENTSCRIPTs referencing the Mage Arena lever object
 * IDs (9706/9707) and dump their operands — the teleport destination tiles
 * should appear among the int operands (raw coords or packed WorldPoints).
 * Run:
 *   ./gradlew test --tests shortestpath.dump.LeverScriptProbeTest -Dtile.probe=true
 */
public class LeverScriptProbeTest {
    private static final int[] LEVER_IDS = {9706, 9707};

    @Test
    public void findLeverScripts() throws Exception {
        Assume.assumeTrue("Enable with -Dtile.probe=true", Boolean.getBoolean("tile.probe"));
        String cacheDir = System.getProperty("tile.probe.cacheDir", "cache");
        String xteaPath = System.getProperty("tile.probe.xteaPath", "keys.json");

        XteaKeyManager xtea = CacheUtils.loadXteaKeys(xteaPath);
        try (Store store = CacheUtils.openStore(cacheDir)) {
            Storage storage = store.getStorage();
            Index scriptIndex = store.getIndex(IndexType.CLIENTSCRIPT);
            ScriptLoader loader = new ScriptLoader();

            int scanned = 0;
            for (Archive archive : scriptIndex.getArchives()) {
                byte[] data;
                try { data = storage.loadArchive(archive); } catch (Exception e) { continue; }
                if (data == null) continue;
                byte[] dec;
                try { dec = archive.decompress(data); } catch (Exception e) { continue; }
                if (dec == null) continue;
                ScriptDefinition def;
                try { def = loader.load(archive.getArchiveId(), dec); } catch (Exception e) { continue; }
                if (def == null) continue;
                scanned++;

                int[] iops = def.getIntOperands();
                if (iops == null) continue;
                List<Integer> hits = new ArrayList<>();
                for (int v : iops) {
                    for (int id : LEVER_IDS) {
                        if (v == id) hits.add(v);
                    }
                }
                if (hits.isEmpty()) continue;

                System.out.println("=== script " + archive.getArchiveId() + " lever hits=" + hits + " ===");
                String[] sops = def.getStringOperands();
                if (sops != null) {
                    for (String s : sops) {
                        if (s != null && !s.isEmpty()) System.out.println("  sop: " + s);
                    }
                }
                for (int v : iops) {
                    // Packed WorldPoint: z << 28 | x << 14 | y
                    int z = (v >>> 28) & 0xF;
                    int x = (v >>> 14) & 0x3FFF;
                    int y = v & 0x3FFF;
                    String packed = "";
                    if (x >= 3000 && x <= 3200 && y >= 3800 && y <= 4800 && z <= 3) {
                        packed = "  <-- packed coord (" + x + "," + y + "," + z + ")";
                    }
                    System.out.println("  iop: " + v + packed);
                }
            }
            System.out.println("scanned " + scanned + " scripts");
        }
    }
}
