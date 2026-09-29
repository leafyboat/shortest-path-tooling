package shortestpath.dump;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.Map;
import java.util.TreeMap;
import net.runelite.cache.ConfigType;
import net.runelite.cache.IndexType;
import net.runelite.cache.definitions.SequenceDefinition;
import net.runelite.cache.definitions.loaders.SequenceLoader;
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
 * Dumps SequenceDefinition frameLengths sums per animation id — duration
 * evidence for anim-bound transports (teleport spells, rides, minigame
 * teleports). Sequence definitions are packed as files in the CONFIGS
 * index's SEQUENCE archive; the file id is the animation id. Emitted
 * sums are in cache frame units; the TSV header records the unit so
 * consumers decide how to convert frames to game ticks.
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
            Index configIndex = store.getIndex(IndexType.CONFIGS);
            Archive seqArchive = configIndex.getArchive(ConfigType.SEQUENCE.getId());
            if (seqArchive == null) {
                throw new IllegalStateException("SEQUENCE config archive not found in cache");
            }

            byte[] archiveData = storage.loadArchive(seqArchive);
            if (archiveData == null) {
                throw new IllegalStateException("SEQUENCE config archive failed to load");
            }
            ArchiveFiles files = seqArchive.getFiles(archiveData);

            SequenceLoader loader = new SequenceLoader()
                    .configureForRevision(seqArchive.getRevision());

            Map<Integer, Long> sums = new TreeMap<>();
            int skipped = 0;
            for (FSFile file : files.getFiles()) {
                SequenceDefinition def;
                try { def = loader.load(file.getFileId(), file.getContents()); } catch (Exception e) { skipped++; continue; }
                if (def == null) { skipped++; continue; }

                long sum = 0;
                if (def.frameLengths != null) {
                    for (int len : def.frameLengths) sum += len;
                }
                sums.put(file.getFileId(), sum);
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
                    + outFile.getAbsolutePath() + " (skipped " + skipped + " defs)");
        }
    }
}
