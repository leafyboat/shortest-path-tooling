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
 * Generic probe: find CLIENTSCRIPTs that read or write a given varp or
 * varbit and print the surrounding instruction window. For SET ops the
 * value pushed immediately before the store is usually the stage/flag
 * written at that point — that is how quest-stage thresholds can be
 * recovered without an in-game read.
 *
 * Run:
 *   ./gradlew test --tests shortestpath.dump.VarAccessProbeTest \
 *     -Dtile.probe=true -Dtile.probe.vars=482,10670,4556
 */
public class VarAccessProbeTest {
    // Base CS2 opcodes (see net.runelite.cache.script.Opcodes)
    private static final int ICONST = 0;
    private static final int GET_VARP = 1;
    private static final int SET_VARP = 2;
    private static final int GET_VARBIT = 25;
    private static final int SET_VARBIT = 27;
    private static final int SCONST = 3;

    @Test
    public void scan() throws Exception {
        Assume.assumeTrue("Enable with -Dtile.probe=true", Boolean.getBoolean("tile.probe"));
        String cacheDir = System.getProperty("tile.probe.cacheDir", "cache");
        String xteaPath = System.getProperty("tile.probe.xteaPath", "keys.json");
        String varProp = System.getProperty("tile.probe.vars", "482,10670,4556");
        List<Integer> targets = new ArrayList<>();
        for (String s : varProp.split(",")) targets.add(Integer.parseInt(s.trim()));
        // tile.probe.scripts=113,9462 dumps those scripts in full instead of scanning
        String dumpProp = System.getProperty("tile.probe.scripts", "");
        List<Integer> dumpIds = new ArrayList<>();
        for (String s : dumpProp.split(",")) {
            if (!s.trim().isEmpty()) dumpIds.add(Integer.parseInt(s.trim()));
        }
        // tile.probe.strings="Mage Training Arena" lists script ids whose
        // string operands contain the text (case-insensitive substring)
        String strProp = System.getProperty("tile.probe.strings", "");
        // tile.probe.iops=44060123,43568683 lists scripts containing these
        // raw int operands (e.g. packed WorldPoint teleport destinations)
        String iopProp = System.getProperty("tile.probe.iops", "");
        List<Integer> iopTargets = new ArrayList<>();
        for (String s : iopProp.split(",")) {
            if (!s.trim().isEmpty()) iopTargets.add(Integer.parseInt(s.trim()));
        }

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

                int[] ops = def.getInstructions();
                int[] iops = def.getIntOperands();
                String[] sops = def.getStringOperands();
                if (ops == null || iops == null) continue;

                if (dumpIds.contains(archive.getArchiveId())) {
                    System.out.println("=== FULL DUMP script " + archive.getArchiveId()
                            + " (args int=" + def.getIntArgCount() + ") ===");
                    for (int j = 0; j < ops.length; j++) {
                        String operand = ops[j] == SCONST && sops != null && sops[j] != null
                                ? "\"" + sops[j] + "\"" : String.valueOf(iops[j]);
                        System.out.println("  " + j + " " + opName(ops[j]) + " " + operand);
                    }
                    continue;
                }
                if (!dumpIds.isEmpty()) continue;

                if (!strProp.isEmpty() && sops != null) {
                    boolean found = false;
                    for (String s : sops) {
                        if (s != null && s.toLowerCase().contains(strProp.toLowerCase())) {
                            found = true;
                            break;
                        }
                    }
                    if (found) {
                        System.out.println("=== string-hit script " + archive.getArchiveId()
                                + " for '" + strProp + "' ===");
                    }
                    continue;
                }
                if (!strProp.isEmpty()) continue;

                if (!iopTargets.isEmpty()) {
                    List<Integer> iopHits = new ArrayList<>();
                    for (int v : iops) {
                        if (iopTargets.contains(v)) iopHits.add(v);
                    }
                    if (!iopHits.isEmpty()) {
                        System.out.println("=== iop-hit script " + archive.getArchiveId()
                                + " hits=" + iopHits + " ===");
                    }
                    continue;
                }

                List<Integer> hits = new ArrayList<>();
                for (int i = 0; i < ops.length; i++) {
                    int op = ops[i];
                    if ((op == GET_VARP || op == SET_VARP || op == GET_VARBIT || op == SET_VARBIT)
                            && targets.contains(iops[i])) {
                        hits.add(i);
                    }
                }
                if (hits.isEmpty()) continue;

                System.out.println("=== script " + archive.getArchiveId() + " ===");
                for (int i : hits) {
                    int lo = Math.max(0, i - 4), hi = Math.min(ops.length - 1, i + 2);
                    for (int j = lo; j <= hi; j++) {
                        String name = opName(ops[j]);
                        String operand = ops[j] == SCONST && sops != null && sops[j] != null
                                ? "\"" + sops[j] + "\"" : String.valueOf(iops[j]);
                        System.out.println((j == i ? " >>> " : "     ")
                                + j + " " + name + " " + operand);
                    }
                    System.out.println();
                }
            }
            System.out.println("scanned " + scanned + " scripts");
        }
    }

    private static String opName(int op) {
        switch (op) {
            case ICONST: return "ICONST";
            case GET_VARP: return "GET_VARP";
            case SET_VARP: return "SET_VARP";
            case SCONST: return "SCONST";
            case GET_VARBIT: return "GET_VARBIT";
            case SET_VARBIT: return "SET_VARBIT";
            case 33: return "OPLONG"; // BRANCH family, name irrelevant
            default: return "op" + op;
        }
    }
}
