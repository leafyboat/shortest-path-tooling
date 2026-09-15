package shortestpath.dump;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.cache.IndexType;
import net.runelite.cache.definitions.ScriptDefinition;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Storage;
import net.runelite.cache.fs.Store;
import net.runelite.cache.definitions.loaders.ScriptLoader;
import net.runelite.cache.script.Opcodes;
import net.runelite.cache.util.XteaKeyManager;
import org.junit.Assume;
import org.junit.Test;

/**
 * Scans all cs2 scripts for those whose intOperands contain &gt;= 5 of the
 * briefcase param-id constants (661, 2074, 2082, 2090, 2098, 2106, 2114, 2122,
 * 2130, 2138). Such a script is almost certainly the briefcase dispatch.
 */
public class BriefcaseParamScriptScanTest {

    private static final int[] PARAM_IDS = {661, 2074, 2082, 2090, 2098, 2106, 2114, 2122, 2130, 2138};

    @Test
    public void scan() throws Exception {
        Assume.assumeTrue("Enable with -Dbriefcase.param.scan=true",
            Boolean.getBoolean("briefcase.param.scan"));

        String cacheDir = CacheUtils.requiredProperty("briefcase.param.cacheDir");
        String xteaPath = CacheUtils.requiredProperty("briefcase.param.xteaPath");
        String outPath = System.getProperty("briefcase.param.outPath", "build/briefcase-param-scripts.txt");

        XteaKeyManager xtea = CacheUtils.loadXteaKeys(xteaPath);

        Set<Integer> targets = new HashSet<>();
        for (int p : PARAM_IDS) targets.add(p);

        try (Store store = CacheUtils.openStore(cacheDir);
             PrintWriter out = new PrintWriter(outPath)) {
            Storage storage = store.getStorage();
            Index scriptIndex = store.getIndex(IndexType.CLIENTSCRIPT);
            ScriptLoader loader = new ScriptLoader();

            int scanned = 0;
            List<int[]> hits = new ArrayList<>(); // [scriptId, paramMatchCount]

            for (Archive archive : scriptIndex.getArchives()) {
                byte[] data;
                try { data = storage.loadArchive(archive); } catch (Exception e) { continue; }
                if (data == null) continue;
                byte[] dec;
                try { dec = archive.decompress(data); } catch (Exception e) { continue; }
                if (dec == null) continue;
                scanned++;
                ScriptDefinition def;
                try { def = loader.load(archive.getArchiveId(), dec); } catch (Exception e) { continue; }
                if (def == null) continue;

                int[] iops = def.getIntOperands();
                if (iops == null) continue;
                Set<Integer> matched = new HashSet<>();
                for (int v : iops) {
                    if (targets.contains(v)) matched.add(v);
                }
                if (matched.size() >= 5) {
                    hits.add(new int[]{archive.getArchiveId(), matched.size()});
                }
            }

            hits.sort((a, b) -> Integer.compare(b[1], a[1]));
            String summary = "Scanned " + scanned + " scripts; " + hits.size() + " with >=5 param-id hits";
            System.out.println(summary);
            out.println(summary);

            // Re-scan to dump matching ones.
            for (int[] h : hits) {
                int targetId = h[0];
                for (Archive archive : scriptIndex.getArchives()) {
                    if (archive.getArchiveId() != targetId) continue;
                    byte[] data = storage.loadArchive(archive);
                    if (data == null) continue;
                    byte[] dec = archive.decompress(data);
                    if (dec == null) continue;
                    ScriptDefinition def = loader.load(archive.getArchiveId(), dec);
                    if (def == null) continue;
                    dumpScript(out, def, targetId, h[1]);
                    break;
                }
            }
        }
    }

    private static void dumpScript(PrintWriter out, ScriptDefinition def, int id, int matchCount) {
        out.println();
        out.println("=================================================================");
        out.println("Script id=" + id + " paramMatches=" + matchCount);
        out.println("=================================================================");
        String[] sops = def.getStringOperands();
        int[] iops = def.getIntOperands();
        int[] ops = def.getInstructions();
        out.println("instructionCount=" + (ops == null ? 0 : ops.length));

        if (sops != null) {
            int cnt = 0;
            for (String s : sops) if (s != null) cnt++;
            out.println("-- string operands (" + cnt + " non-null) --");
            for (int i = 0; i < sops.length; i++) {
                if (sops[i] != null) out.println("  [" + i + "] " + sops[i]);
            }
        }
        if (iops != null) {
            out.println("-- candidate WorldPoint int operands --");
            for (int i = 0; i < iops.length; i++) {
                int v = iops[i];
                int z = (v >>> 28) & 0x3;
                int x = (v >>> 14) & 0x3FFF;
                int y = v & 0x3FFF;
                if (z < 4 && x >= 1024 && x <= 4096 && y >= 1024 && y <= 16383 && (v >>> 30) == 0) {
                    out.println("  iop[" + i + "] = " + v + "  -> WP(" + x + "," + y + "," + z + ")");
                }
            }
        }

        if (ops != null && ops.length <= 2000) {
            out.println("-- bytecode --");
            long[] lops = def.getLongOperands();
            for (int i = 0; i < ops.length; i++) {
                int op = ops[i];
                String operand;
                if (op == Opcodes.SCONST) {
                    operand = sops == null ? "" : "\"" + sops[i] + "\"";
                } else if (op == Opcodes.LCONST) {
                    operand = lops == null ? "" : String.valueOf(lops[i]);
                } else {
                    int v = iops == null ? 0 : iops[i];
                    operand = String.valueOf(v);
                    int z = (v >>> 28) & 0x3, x = (v >>> 14) & 0x3FFF, y = v & 0x3FFF;
                    if (z < 4 && x >= 1024 && x <= 4096 && y >= 1024 && y <= 16383 && (v >>> 30) == 0) {
                        operand += "  -> WP(" + x + "," + y + "," + z + ")";
                    }
                }
                out.println("  " + i + "\t" + op + "\t" + operand);
            }
        }
    }
}
