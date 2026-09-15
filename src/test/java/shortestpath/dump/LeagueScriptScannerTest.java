package shortestpath.dump;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.runelite.cache.IndexType;
import net.runelite.cache.definitions.ScriptDefinition;
import net.runelite.cache.definitions.loaders.ScriptLoader;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Storage;
import net.runelite.cache.fs.Store;
import net.runelite.cache.script.Opcodes;
import net.runelite.cache.util.XteaKeyManager;
import org.junit.Assume;
import org.junit.Test;

/**
 * Scans every CLIENTSCRIPT in the cache and prints the IDs of scripts
 * that look like league relic teleport dispatch — i.e., scripts whose
 * string operands contain briefcase-only destination names or whose int
 * operands reference relic item IDs (30361 briefcase, 33227 evil eye,
 * 33233 map of alacrity, 33235 clue contract, 25102 fairy mushroom).
 *
 * For each hit, dumps the full string-operand list + any int constants
 * that look like packed WorldPoints (z &lt;&lt; 28 | x &lt;&lt; 14 | y).
 *
 * Run via:
 *   ./gradlew leagueScriptScan \
 *     -PleagueScriptCacheDir=$PWD/cache \
 *     -PleagueScriptXteaPath=$PWD/keys.json
 *
 * Output: build/league-scripts.txt and console.
 */
public class LeagueScriptScannerTest {

    // Relic / teleport item IDs to flag in intOperands.
    private static final int[] RELIC_ITEM_IDS = {30361, 33227, 33233, 33235, 25102};

    // Briefcase-only destination names (lowercase) that strongly indicate
    // a relic teleport dispatch script.
    private static final String[] SHARP_NEEDLES = {
        "fortis cothon",
        "alchemical society",
        "aldarin",
        "hallowed sepulchre",
        "blast mine",
        "fortis colosseum",
        "auburnvale",
        "outside the volcanic mine",
        "fossil island volcano",
        "temple of the eye",
        "lumbridge castle",
        "ferox enclave",
        "draynor village",
        "dorgesh-kaan",
        "gwenith",
        "wintertodt",
    };

    @Test
    public void scanLeagueScripts() throws Exception {
        Assume.assumeTrue(
            "Enable with -Dleague.script.scan=true",
            Boolean.getBoolean("league.script.scan"));

        String cacheDir = CacheUtils.requiredProperty("league.script.cacheDir");
        String xteaPath = CacheUtils.requiredProperty("league.script.xteaPath");
        String outPath  = System.getProperty("league.script.outPath", "build/league-scripts.txt");

        XteaKeyManager xteaKeyManager = CacheUtils.loadXteaKeys(xteaPath);

        Set<Integer> relicIds = new HashSet<>();
        for (int id : RELIC_ITEM_IDS) relicIds.add(id);

        try (Store store = CacheUtils.openStore(cacheDir);
             PrintWriter out = new PrintWriter(outPath)) {
            Storage storage = store.getStorage();
            Index scriptIndex = store.getIndex(IndexType.CLIENTSCRIPT);

            ScriptLoader loader = new ScriptLoader(); // rev237=true by default → handles rev 237+
            int scanned = 0, sharpHits = 0, relicRefHits = 0;

            List<int[]> sharpHitList = new ArrayList<>();   // [scriptId, hitCount]
            List<int[]> relicHitList = new ArrayList<>();   // [scriptId, itemId]

            for (Archive archive : scriptIndex.getArchives()) {
                byte[] archiveData;
                try {
                    archiveData = storage.loadArchive(archive);
                } catch (Exception e) {
                    continue;
                }
                if (archiveData == null) continue;
                byte[] decompressed;
                try {
                    decompressed = archive.decompress(archiveData);
                } catch (Exception e) {
                    continue;
                }
                if (decompressed == null) continue;
                scanned++;

                ScriptDefinition def;
                try {
                    def = loader.load(archive.getArchiveId(), decompressed);
                } catch (Exception e) {
                    continue;
                }
                if (def == null) continue;

                String[] sops = def.getStringOperands();
                int[] iops = def.getIntOperands();
                int scriptId = archive.getArchiveId();

                int sharpHitCount = 0;
                if (sops != null) {
                    for (String s : sops) {
                        if (s == null) continue;
                        String low = s.toLowerCase(Locale.ROOT);
                        for (String n : SHARP_NEEDLES) {
                            if (low.contains(n)) { sharpHitCount++; break; }
                        }
                    }
                }

                int relicMatch = -1;
                if (iops != null) {
                    for (int v : iops) {
                        if (relicIds.contains(v)) { relicMatch = v; break; }
                    }
                }

                if (sharpHitCount > 0) {
                    sharpHits++;
                    sharpHitList.add(new int[]{scriptId, sharpHitCount});
                }
                if (relicMatch >= 0) {
                    relicRefHits++;
                    relicHitList.add(new int[]{scriptId, relicMatch});
                }

                // For high-signal scripts (>=2 sharp hits OR any relic ref), dump full contents.
                if (sharpHitCount >= 2 || relicMatch >= 0) {
                    dumpScript(out, def, archive.getArchiveId(), sharpHitCount, relicMatch);
                }
            }

            String summary = "\nScanned " + scanned + " scripts. sharpHits=" + sharpHits + " relicRefHits=" + relicRefHits;
            out.println(summary);
            System.out.println(summary);
            System.out.println("Sharp hits (scriptId, sharpHitCount):");
            for (int[] h : sharpHitList) {
                System.out.println("  " + h[0] + "\t" + h[1]);
            }
            System.out.println("Relic ref hits (scriptId, itemId):");
            for (int[] h : relicHitList) {
                System.out.println("  " + h[0] + "\t" + h[1]);
            }
        }
    }

    private static void dumpScript(PrintWriter out, ScriptDefinition def, int id, int sharpCount, int relicId) {
        out.println();
        out.println("=================================================================");
        out.println("Script id=" + id + " sharpHits=" + sharpCount + " relicRef=" + (relicId < 0 ? "-" : String.valueOf(relicId)));
        out.println("=================================================================");

        String[] sops = def.getStringOperands();
        int[] iops = def.getIntOperands();
        long[] lops = def.getLongOperands();
        int[] ops = def.getInstructions();

        out.println("instructionCount=" + (ops == null ? 0 : ops.length));

        // String operands.
        if (sops != null) {
            List<String> strings = new ArrayList<>();
            for (int i = 0; i < sops.length; i++) {
                if (sops[i] != null) strings.add(i + ": " + sops[i]);
            }
            out.println("-- string operands (" + strings.size() + ") --");
            for (String s : strings) out.println("  " + s);
        }

        // Potential WorldPoints: z<<28 | x<<14 | y, with z in 0..3, x in 1024..3968, y in 2048..16383.
        if (iops != null) {
            out.println("-- candidate WorldPoint int operands --");
            for (int i = 0; i < iops.length; i++) {
                int v = iops[i];
                int z = (v >>> 28) & 0x3;
                int x = (v >>> 14) & 0x3FFF;
                int y = v & 0x3FFF;
                if (z < 4 && x >= 1024 && x <= 4096 && y >= 1024 && y <= 16383 && (v >>> 30) == 0) {
                    out.println("  iop[" + i + "] = 0x" + Integer.toHexString(v) + "  -> (" + x + "," + y + "," + z + ")");
                }
            }
        }

        // Also print the full bytecode briefly (opcode, operand) for short scripts.
        if (ops != null && ops.length <= 600) {
            out.println("-- bytecode --");
            for (int i = 0; i < ops.length; i++) {
                int op = ops[i];
                String operand;
                if (op == Opcodes.SCONST) {
                    operand = sops == null ? "" : "\"" + sops[i] + "\"";
                } else if (op == Opcodes.LCONST) {
                    operand = lops == null ? "" : String.valueOf(lops[i]);
                } else {
                    operand = String.valueOf(iops == null ? 0 : iops[i]);
                }
                out.println("  " + i + "\t" + op + "\t" + operand);
            }
        }
    }
}
