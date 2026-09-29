package shortestpath.dump;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.Map;
import java.util.TreeMap;
import net.runelite.cache.IndexType;
import net.runelite.cache.definitions.SequenceDefinition;
import net.runelite.cache.definitions.loaders.SequenceLoader;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Storage;
import net.runelite.cache.fs.Store;
import net.runelite.cache.util.XteaKeyManager;
import org.junit.Assume;
import org.junit.Test;

/**
 * Dumps SequenceDefinition frameLengths sums per animation id from the
 * ANIMATIONS index — duration evidence for anim-bound transports
 * (teleport spells, rides, minigame teleports). Emitted sums are in
 * cache frame units; the TSV header records the unit so consumers
 * decide how to convert frames to game ticks.
 * Run:
 *   ./gradlew animDurationDump -PanimDurationCacheDir=$PWD/cache -PanimDurationXteaPath=$PWD/keys.json
 */
public class AnimDurationDumperTest {
    @Test
    public void dumpAnimDurations() throws Exception {
        Assume.assumeTrue("Enable with -Danim.duration.dump=true",
                Boolean.getBoolean("anim.duration.dump"));
        String cacheDir = System.getProperty("anim.duration.cacheDir", "cache");
        String xteaPath = System.getProperty("anim.duration.xteaPath", "keys.json");
        String outPath = System.getProperty("anim.duration.output", "build/anim-durations.tsv");

        XteaKeyManager xtea = CacheUtils.loadXteaKeys(xteaPath);
        try (Store store = CacheUtils.openStore(cacheDir)) {
            Storage storage = store.getStorage();
            Index animIndex = store.getIndex(IndexType.ANIMATIONS);
            SequenceLoader loader = new SequenceLoader();

            Map<Integer, Long> sums = new TreeMap<>();
            int skipped = 0;
            for (Archive archive : animIndex.getArchives()) {
                byte[] data;
                try { data = storage.loadArchive(archive); } catch (Exception e) { skipped++; continue; }
                if (data == null) { skipped++; continue; }
                byte[] dec;
                try { dec = archive.decompress(data); } catch (Exception e) { skipped++; continue; }
                if (dec == null) { skipped++; continue; }
                SequenceDefinition def;
                try { def = loader.load(archive.getArchiveId(), dec); } catch (Exception e) { skipped++; continue; }
                if (def == null) { skipped++; continue; }

                long sum = 0;
                if (def.frameLengths != null) {
                    for (int len : def.frameLengths) sum += len;
                }
                sums.put(archive.getArchiveId(), sum);
            }

            File outFile = new File(outPath);
            File parent = outFile.getParentFile();
            if (parent != null) parent.mkdirs();
            try (BufferedWriter w = new BufferedWriter(new FileWriter(outFile))) {
                w.write("# units=cache-frame-sums");
                w.newLine();
                for (Map.Entry<Integer, Long> e : sums.entrySet()) {
                    w.write(e.getKey() + "\t" + e.getValue());
                    w.newLine();
                }
            }
            System.out.println("anim durations: wrote " + sums.size() + " rows to "
                    + outFile.getAbsolutePath() + " (skipped " + skipped + " archives)");
        }
    }
}
