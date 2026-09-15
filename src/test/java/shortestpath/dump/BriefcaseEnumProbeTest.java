package shortestpath.dump;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.runelite.cache.ConfigType;
import net.runelite.cache.IndexType;
import net.runelite.cache.definitions.EnumDefinition;
import net.runelite.cache.definitions.loaders.EnumLoader;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.ArchiveFiles;
import net.runelite.cache.fs.FSFile;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Storage;
import net.runelite.cache.fs.Store;
import net.runelite.cache.util.XteaKeyManager;
import org.junit.Assume;
import org.junit.Test;

/**
 * Scans every {@code enum} in the cache and prints any whose string values
 * contain known Banker's Briefcase destination names. Used to locate the
 * authoritative cache-side list of briefcase destinations to cross-check
 * the wiki-derived rows in {@code seasonal_transports.tsv}.
 *
 * Run via:
 *   ./gradlew briefcaseEnumProbe \
 *     -PbriefcaseEnumCacheDir=$PWD/cache \
 *     -PbriefcaseEnumXteaPath=$PWD/keys.json
 */
public class BriefcaseEnumProbeTest {
    private static final String[] NEEDLES = {
        "mount quidamortem",
        "alchemical society",
        "aldarin",
        "fortis cothon",
        "dorgesh-kaan",
        "fossil island volcano",
        "giants' foundry",
        "temple of the eye",
        "ferox enclave",
        "lumbridge castle cellar",
        "edgeville",
        "draynor village",
        "varrock east",
        "varrock west",
        "wintertodt",
        "dwarven mine",
        "gwenith",
        "cooks' guild",
        "museum camp",
        "outside the volcanic mine",
    };

    // Distinctive briefcase-only strings — won't match common-named enums.
    private static final String[] SHARP_NEEDLES = {
        "fortis cothon",
        "alchemical society",
        "aldarin dock",
        "hallowed sepulchre",
        "blast mine",
        "fortis colosseum",
        "auburnvale",
        "outside the volcanic mine",
        "fossil island volcano",
        "temple of the eye",
    };

    @Test
    public void probeBriefcaseEnums() throws Exception {
        Assume.assumeTrue(
            "Enable with -Dbriefcase.enum.probe=true",
            Boolean.getBoolean("briefcase.enum.probe"));

        String cacheDir = CacheUtils.requiredProperty("briefcase.enum.cacheDir");
        String xteaPath = CacheUtils.requiredProperty("briefcase.enum.xteaPath");

        XteaKeyManager xteaKeyManager = CacheUtils.loadXteaKeys(xteaPath);

        try (Store store = CacheUtils.openStore(cacheDir)) {
            Storage storage = store.getStorage();
            Index index = store.getIndex(IndexType.CONFIGS);
            Archive archive = index.getArchive(ConfigType.ENUM.getId());
            byte[] archiveData = storage.loadArchive(archive);
            ArchiveFiles files = archive.getFiles(archiveData);

            EnumLoader loader = new EnumLoader();
            int scanned = 0;
            List<EnumDefinition> hits = new ArrayList<>();
            List<EnumDefinition> sharpHits = new ArrayList<>();
            for (FSFile f : files.getFiles()) {
                EnumDefinition def;
                try {
                    def = loader.load(f.getFileId(), f.getContents());
                } catch (Exception e) {
                    continue;
                }
                if (def == null) continue;
                scanned++;
                String[] svals = def.getStringVals();
                if (svals == null) continue;
                int matches = 0;
                int sharpMatches = 0;
                for (String s : svals) {
                    if (s == null) continue;
                    String lc = s.toLowerCase(Locale.ROOT);
                    for (String needle : NEEDLES) {
                        if (lc.contains(needle)) { matches++; break; }
                    }
                    for (String needle : SHARP_NEEDLES) {
                        if (lc.contains(needle)) { sharpMatches++; break; }
                    }
                }
                if (sharpMatches >= 1) {
                    sharpHits.add(def);
                } else if (matches >= 3) {
                    hits.add(def);
                }
            }

            System.out.println("Scanned " + scanned + " enums.");
            System.out.println("SHARP hits (>=1 briefcase-only string): "
                + sharpHits.size());
            for (EnumDefinition def : sharpHits) {
                printEnum(def);
            }
            System.out.println();
            System.out.println("Generic hits (>=3 common strings, no sharp): "
                + hits.size());
            for (EnumDefinition def : hits) {
                System.out.println("  enum id=" + def.getId()
                    + " size=" + def.getSize()
                    + " keyType=" + def.getKeyType()
                    + " valType=" + def.getValType());
            }

            // Also scan structs for briefcase-only strings in their params.
            scanStructs(store);
        }
    }

    private static void scanStructs(Store store) throws Exception {
        net.runelite.cache.StructManager sm = new net.runelite.cache.StructManager(store);
        sm.load();
        int total = sm.getStructs().size();
        int hits = 0;
        for (java.util.Map.Entry<Integer, net.runelite.cache.definitions.StructDefinition> e : sm.getStructs().entrySet()) {
            if (e.getValue() == null || e.getValue().getParams() == null) continue;
            boolean matched = false;
            for (Object v : e.getValue().getParams().values()) {
                if (!(v instanceof String)) continue;
                String lc = ((String) v).toLowerCase(Locale.ROOT);
                for (String needle : SHARP_NEEDLES) {
                    if (lc.contains(needle)) { matched = true; break; }
                }
                if (matched) break;
            }
            if (matched) {
                hits++;
                System.out.println("STRUCT id=" + e.getKey() + " params=" + e.getValue().getParams());
            }
        }
        System.out.println("Scanned " + total + " structs; " + hits + " contain a briefcase-only string.");
    }

    private static void printEnum(EnumDefinition def) {
        int[] keys = def.getKeys();
        String[] svals = def.getStringVals();
        int[] ivals = def.getIntVals();
        long[] lvals = def.getLongVals();
        int size = def.getSize();
        System.out.println();
        System.out.println("=== enum id=" + def.getId()
            + " size=" + size
            + " keyType=" + def.getKeyType()
            + " valType=" + def.getValType()
            + " strVals=" + (svals == null ? "null" : svals.length)
            + " intVals=" + (ivals == null ? "null" : ivals.length)
            + " longVals=" + (lvals == null ? "null" : lvals.length)
            + " ===");
        int n = keys == null ? 0 : keys.length;
        for (int i = 0; i < n; i++) {
            StringBuilder sb = new StringBuilder();
            sb.append("  key=").append(keys[i]);
            if (svals != null && i < svals.length && svals[i] != null) {
                sb.append("\tstr=\"").append(svals[i]).append("\"");
            }
            if (ivals != null && i < ivals.length) {
                sb.append("\tint=").append(ivals[i])
                    .append(" (0x").append(Integer.toHexString(ivals[i])).append(")");
                // Decode common packed-coord layouts so we can eyeball them.
                int v = ivals[i];
                int x14_y14_p2 = v & 0x3FFF;
                int yA = (v >> 14) & 0x3FFF;
                int pA = (v >> 28) & 0x3;
                sb.append(" coord?[x=").append(x14_y14_p2)
                    .append(",y=").append(yA)
                    .append(",p=").append(pA).append("]");
            }
            if (lvals != null && i < lvals.length) {
                sb.append("\tlong=").append(lvals[i]);
            }
            System.out.println(sb.toString());
        }
    }
}
