package shortestpath.dump;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.runelite.cache.ConfigType;
import net.runelite.cache.IndexType;
import net.runelite.cache.StructManager;
import net.runelite.cache.definitions.EnumDefinition;
import net.runelite.cache.definitions.StructDefinition;
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

public class BriefcaseDestOverlapScanTest {

    @Test
    public void scanOverlap() throws Exception {
        Assume.assumeTrue(
            "Enable with -Dbriefcase.dest.scan=true",
            Boolean.getBoolean("briefcase.dest.scan"));

        String cacheDir = CacheUtils.requiredProperty("briefcase.dest.cacheDir");
        String xteaPath = CacheUtils.requiredProperty("briefcase.dest.xteaPath");
        String namesFile = CacheUtils.requiredProperty("briefcase.dest.namesFile");
        String outPath = System.getProperty("briefcase.dest.outPath", "build/briefcase-dest-overlap.txt");

        XteaKeyManager xteaKeyManager = CacheUtils.loadXteaKeys(xteaPath);

        Set<String> canonical = new HashSet<>();
        for (String line : Files.readAllLines(Paths.get(namesFile))) {
            String s = line.trim();
            if (!s.isEmpty()) canonical.add(s.toLowerCase(Locale.ROOT));
        }
        System.out.println("Canonical dest set size: " + canonical.size());

        try (Store store = CacheUtils.openStore(cacheDir);
             PrintWriter out = new PrintWriter(outPath)) {
            Storage storage = store.getStorage();
            Index configs = store.getIndex(IndexType.CONFIGS);
            Archive enumArchive = configs.getArchive(ConfigType.ENUM.getId());
            ArchiveFiles enumFiles = enumArchive.getFiles(storage.loadArchive(enumArchive));

            EnumLoader enumLoader = new EnumLoader();

            int enumsScanned = 0, enumHits = 0;
            List<int[]> hits = new ArrayList<>();
            TreeMap<Integer, EnumDefinition> enumIdToDef = new TreeMap<>();

            for (FSFile f : enumFiles.getFiles()) {
                enumsScanned++;
                EnumDefinition def;
                try {
                    def = enumLoader.load(f.getFileId(), f.getContents());
                } catch (Exception e) {
                    continue;
                }
                if (def == null) continue;

                String[] strVals = def.getStringVals();
                if (strVals == null || strVals.length == 0) continue;

                int overlap = 0;
                for (String v : strVals) {
                    if (v == null) continue;
                    if (canonical.contains(v.toLowerCase(Locale.ROOT))) overlap++;
                }
                if (overlap >= 10) {
                    enumHits++;
                    hits.add(new int[]{f.getFileId(), overlap});
                    enumIdToDef.put(f.getFileId(), def);
                }
            }

            String summary = "Scanned " + enumsScanned + " enums; " + enumHits + " with overlap>=10";
            System.out.println(summary);
            out.println(summary);

            hits.sort((a, b) -> Integer.compare(b[1], a[1]));
            for (int[] h : hits) {
                int id = h[0], ov = h[1];
                EnumDefinition def = enumIdToDef.get(id);
                int[] keys = def.getKeys();
                String[] strVals = def.getStringVals();
                int[] intVals = def.getIntVals();
                int size = def.getSize();
                System.out.println("enum id=" + id + " size=" + size + " overlap=" + ov);

                out.println();
                out.println("=== enum id=" + id
                    + " keyType=" + def.getKeyType()
                    + " valType=" + def.getValType()
                    + " size=" + size + " overlap=" + ov + " ===");
                if (keys != null) {
                    for (int i = 0; i < keys.length; i++) {
                        String s = (strVals != null && i < strVals.length) ? strVals[i] : null;
                        Integer iv = (intVals != null && i < intVals.length) ? intVals[i] : null;
                        StringBuilder line = new StringBuilder("  key=").append(keys[i]);
                        if (s != null) line.append("\tstr=\"").append(s).append("\"");
                        if (iv != null) line.append("\tint=").append(iv);
                        out.println(line);
                    }
                }
            }

            StructManager structManager = new StructManager(store);
            structManager.load();

            Set<Integer> hitIds = new HashSet<>();
            for (int[] h : hits) hitIds.add(h[0]);

            out.println();
            out.println("=== related enums (same key set, within +/-50 of a hit id) ===");
            for (FSFile f : enumFiles.getFiles()) {
                EnumDefinition def;
                try {
                    def = enumLoader.load(f.getFileId(), f.getContents());
                } catch (Exception e) { continue; }
                if (def == null) continue;
                if (hitIds.contains(f.getFileId())) continue;
                int[] kk = def.getKeys();
                if (kk == null || kk.length < 5) continue;

                for (int[] h : hits) {
                    EnumDefinition hd = enumIdToDef.get(h[0]);
                    int[] hk = hd.getKeys();
                    if (hk == null || hk.length != kk.length) continue;
                    boolean same = true;
                    for (int i = 0; i < hk.length; i++) {
                        if (hk[i] != kk[i]) { same = false; break; }
                    }
                    if (!same) continue;
                    if (Math.abs(f.getFileId() - h[0]) > 50) continue;
                    out.println();
                    out.println("--- enum id=" + f.getFileId()
                        + " keyType=" + def.getKeyType()
                        + " valType=" + def.getValType()
                        + " size=" + def.getSize() + " (parallel to " + h[0] + ") ---");
                    int[] keys = def.getKeys();
                    String[] sv = def.getStringVals();
                    int[] iv = def.getIntVals();
                    boolean structVal = def.getValType() != null
                        && "STRUCT".equals(def.getValType().toString());
                    for (int i = 0; i < keys.length; i++) {
                        StringBuilder line = new StringBuilder("    key=").append(keys[i]);
                        if (sv != null && i < sv.length && sv[i] != null) {
                            line.append("\tstr=\"").append(sv[i]).append("\"");
                        }
                        if (iv != null && i < iv.length) {
                            int vv = iv[i];
                            line.append("\tint=").append(vv);
                            appendWP(line, vv);
                            if (structVal) {
                                StructDefinition sd = structManager.getStruct(vv);
                                if (sd != null && sd.getParams() != null) {
                                    line.append("\n      struct ").append(vv).append(" params:");
                                    TreeMap<Integer, Object> sorted = new TreeMap<>(sd.getParams());
                                    for (Map.Entry<Integer, Object> p : sorted.entrySet()) {
                                        Object pv = p.getValue();
                                        String pvs = pv == null ? "null" : pv.toString();
                                        if (pvs.length() > 120) pvs = pvs.substring(0, 117) + "...";
                                        line.append("\n        [").append(p.getKey()).append("]=").append(pvs);
                                        if (pv instanceof Integer) {
                                            appendWP(line, (Integer) pv);
                                        }
                                    }
                                }
                            }
                        }
                        out.println(line);
                    }
                    break;
                }
            }
        }
    }

    private static void appendWP(StringBuilder sb, int v) {
        int z = (v >>> 28) & 0x3;
        int x = (v >>> 14) & 0x3FFF;
        int y = v & 0x3FFF;
        if (z < 4 && x >= 1024 && x <= 4096 && y >= 1024 && y <= 16383 && (v >>> 30) == 0) {
            sb.append("  -> WP(").append(x).append(',').append(y).append(',').append(z).append(')');
        }
    }
}
