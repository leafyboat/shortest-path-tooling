package shortestpath.dump;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.runelite.cache.ConfigType;
import net.runelite.cache.IndexType;
import net.runelite.cache.StructManager;
import net.runelite.cache.definitions.EnumDefinition;
import net.runelite.cache.definitions.ScriptDefinition;
import net.runelite.cache.definitions.StructDefinition;
import net.runelite.cache.definitions.loaders.EnumLoader;
import net.runelite.cache.definitions.loaders.ScriptLoader;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.ArchiveFiles;
import net.runelite.cache.fs.FSFile;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Storage;
import net.runelite.cache.fs.Store;
import net.runelite.cache.script.Opcodes;
import org.junit.Assume;
import org.junit.Test;

/**
 * One-off probe: locate the authoritative cache-side value-to-location
 * map for varbit 2187 (POH_HOUSE_LOCATION), to confirm or refute the
 * estate-agent menu order before remapping the committed house-teleport
 * rows. Looks for (a) enums mapping index to portal object ids,
 * (b) enums/structs listing the nine location names in order, and
 * (c) clientscripts that touch varbit 2187 alongside location strings.
 *
 * Run via:
 *   ./gradlew test --tests shortestpath.dump.PohHouseLocationProbeTest -Dpoh.loc.probe=true
 * Cache paths default to the repo's cache/ and keys.json.
 */
public class PohHouseLocationProbeTest {

    // House portal object ids as committed in teleportation_portals.tsv.
    private static final int[] PORTAL_OBJECT_IDS = {
        15477, 15478, 15479, 15480, 15481, 15482, 28822, 34947, 55353,
    };

    private static final String[] LOCATION_NAMES = {
        "rimmington", "taverley", "pollnivneach", "hosidius", "rellekka",
        "aldarin", "brimhaven", "yanille", "prifddinas",
    };

    private static final int HOUSE_LOCATION_VARBIT = 2187;

    @Test
    public void probePohHouseLocations() throws Exception {
        Assume.assumeTrue("Enable with -Dpoh.loc.probe=true",
                          Boolean.getBoolean("poh.loc.probe"));
        String cacheDir = System.getProperty("poh.loc.probe.cacheDir", "cache");
        try (Store store = CacheUtils.openStore(cacheDir)) {
            scanEnums(store);
            scanStructs(store);
            scanScripts(store);
        }
    }

    private static int countLocationNames(String[] svals) {
        if (svals == null) return 0;
        int hits = 0;
        for (String s : svals) {
            if (s == null) continue;
            String lc = s.toLowerCase(Locale.ROOT);
            for (String n : LOCATION_NAMES) {
                if (lc.contains(n)) { hits++; break; }
            }
        }
        return hits;
    }

    private static int countPortalIds(int[] ivals) {
        if (ivals == null) return 0;
        Set<Integer> ids = new HashSet<>();
        for (int id : PORTAL_OBJECT_IDS) ids.add(id);
        int hits = 0;
        for (int v : ivals) {
            if (ids.contains(v)) hits++;
        }
        return hits;
    }

    private static void scanEnums(Store store) throws Exception {
        Storage storage = store.getStorage();
        Index index = store.getIndex(IndexType.CONFIGS);
        Archive archive = index.getArchive(ConfigType.ENUM.getId());
        ArchiveFiles files = archive.getFiles(storage.loadArchive(archive));

        EnumLoader loader = new EnumLoader();
        int scanned = 0;
        for (FSFile f : files.getFiles()) {
            EnumDefinition def;
            try {
                def = loader.load(f.getFileId(), f.getContents());
            } catch (Exception e) {
                continue;
            }
            if (def == null) continue;
            scanned++;
            int nameHits = countLocationNames(def.getStringVals());
            int portalHits = countPortalIds(def.getIntVals());
            if (nameHits >= 3 || portalHits >= 2) {
                printEnum(def, nameHits, portalHits);
            }
        }
        System.out.println("Scanned " + scanned + " enums.");
    }

    private static void printEnum(EnumDefinition def, int nameHits, int portalHits) {
        System.out.println();
        System.out.println("=== enum id=" + def.getId()
            + " size=" + def.getSize()
            + " keyType=" + def.getKeyType()
            + " valType=" + def.getValType()
            + " nameHits=" + nameHits + " portalHits=" + portalHits + " ===");
        int[] keys = def.getKeys();
        String[] svals = def.getStringVals();
        int[] ivals = def.getIntVals();
        if (keys == null) return;
        for (int i = 0; i < keys.length; i++) {
            StringBuilder sb = new StringBuilder();
            sb.append("  key=").append(keys[i]);
            if (svals != null && i < svals.length && svals[i] != null) {
                sb.append("\tstr=\"").append(svals[i]).append("\"");
            }
            if (ivals != null && i < ivals.length) {
                sb.append("\tint=").append(ivals[i])
                    .append(" (0x").append(Integer.toHexString(ivals[i])).append(")");
            }
            System.out.println(sb);
        }
    }

    private static void scanStructs(Store store) throws Exception {
        StructManager sm = new StructManager(store);
        sm.load();
        int scanned = 0;
        for (Map.Entry<Integer, StructDefinition> e : sm.getStructs().entrySet()) {
            StructDefinition def = e.getValue();
            if (def == null || def.getParams() == null) continue;
            scanned++;
            int nameHits = 0;
            int portalHits = 0;
            for (Object v : def.getParams().values()) {
                if (v instanceof String) {
                    String lc = ((String) v).toLowerCase(Locale.ROOT);
                    for (String n : LOCATION_NAMES) {
                        if (lc.contains(n)) { nameHits++; break; }
                    }
                } else if (v instanceof Integer) {
                    for (int id : PORTAL_OBJECT_IDS) {
                        if ((Integer) v == id) { portalHits++; break; }
                    }
                }
            }
            if (nameHits >= 2 || portalHits >= 2) {
                System.out.println("STRUCT id=" + e.getKey()
                    + " nameHits=" + nameHits + " portalHits=" + portalHits
                    + " params=" + def.getParams());
            }
        }
        System.out.println("Scanned " + scanned + " structs.");
    }

    private static void scanScripts(Store store) throws Exception {
        Storage storage = store.getStorage();
        Index scriptIndex = store.getIndex(IndexType.CLIENTSCRIPT);
        ScriptLoader loader = new ScriptLoader();
        int scanned = 0;
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

            int[] iops = def.getIntOperands();
            if (iops == null) continue;
            boolean hasVarbit = false;
            boolean hasEnum252 = false;
            for (int v : iops) {
                if (v == HOUSE_LOCATION_VARBIT) hasVarbit = true;
                if (v == 252) hasEnum252 = true;
            }
            if (!hasVarbit && !hasEnum252) continue;

            String[] sops = def.getStringOperands();
            int nameHits = countLocationNames(sops);
            int[] ops = def.getInstructions();
            System.out.println();
            System.out.println("SCRIPT id=" + archive.getArchiveId()
                + " instructions=" + (ops == null ? 0 : ops.length)
                + " nameHits=" + nameHits
                + " varbit2187=" + hasVarbit + " enum252=" + hasEnum252);
            if (sops != null) {
                for (int i = 0; i < sops.length; i++) {
                    if (sops[i] != null && !sops[i].isEmpty()) {
                        System.out.println("  sop[" + i + "] = \"" + sops[i] + "\"");
                    }
                }
            }
            // Dump full bytecode for scripts that also carry location
            // names or are short enough to eyeball.
            if (nameHits >= 2 || archive.getArchiveId() == 7778
                || (ops != null && ops.length <= 400)) {
                for (int i = 0; ops != null && i < ops.length; i++) {
                    int op = ops[i];
                    String operand;
                    if (op == Opcodes.SCONST) {
                        operand = sops == null ? "" : "\"" + sops[i] + "\"";
                    } else {
                        operand = String.valueOf(iops[i]);
                    }
                    System.out.println("  " + i + "\t" + op + "\t" + operand);
                }
            }
        }
        System.out.println("Scanned " + scanned + " scripts for varbit "
            + HOUSE_LOCATION_VARBIT + ".");
    }
}
