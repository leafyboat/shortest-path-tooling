package shortestpath.dashboard;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import shortestpath.Util;

public class PathfinderDashboardAssetWriter {
    private static final String[] ASSETS = {
        "/reachability-dashboard/index.html",
        "/reachability-dashboard/app.js",
        "/reachability-dashboard/profiler.js",
        "/reachability-dashboard/collision-overlay.js",
        "/reachability-dashboard/sailing.js",
        "/reachability-dashboard/styles.css"
    };

    /**
     * Plugin-bundled resources that the seasonal dashboard view needs to
     * fetch at runtime. The map key is the classpath resource path; the
     * value is the file name written under the dashboard output root.
     * These are best-effort: a missing resource is logged and skipped so
     * the rest of the dashboard still publishes.
     */
    private static final String[][] OPTIONAL_PLUGIN_ASSETS = {
        {"/leagues/regions.tsv", "regions.tsv"},
        {"/f2p/regions.tsv", "f2p_regions.tsv"},
        {"/collision-map.zip", "collision-map.zip"}
    };

    public void writeAssets(Path outputDirectory) throws IOException {
        Files.createDirectories(outputDirectory);
        for (String asset : ASSETS) {
            try (InputStream in = PathfinderDashboardAssetWriter.class.getResourceAsStream(asset)) {
                if (in == null) {
                    throw new IOException("Missing asset resource: " + asset);
                }
                Path destination = outputDirectory.resolve(asset.substring(asset.lastIndexOf('/') + 1));
                Files.write(destination, Util.readAllBytes(in));
            }
        }
        for (String[] entry : OPTIONAL_PLUGIN_ASSETS) {
            String resource = entry[0];
            String filename = entry[1];
            try (InputStream in = PathfinderDashboardAssetWriter.class.getResourceAsStream(resource)) {
                if (in == null) {
                    System.err.println("[dashboard] Optional asset missing: " + resource);
                    continue;
                }
                Files.write(outputDirectory.resolve(filename), Util.readAllBytes(in));
            }
        }
    }
}
