package shortestpath.benchmark.canonical;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Assume;

/**
 * The canonical corpus checkout used by tests: -PcorpusDir, default ../shortest-path-corpus.
 * Tests that need it are skipped when it is not checked out.
 */
final class TestCorpus {
    private TestCorpus() { }

    static Path dir() {
        Path dir = Path.of(System.getProperty("benchmark.corpusDir", "../shortest-path-corpus"));
        Assume.assumeTrue("canonical corpus not checked out at " + dir, Files.isDirectory(dir));
        return dir;
    }
}
