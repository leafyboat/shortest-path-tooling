package shortestpath.benchmark.canonical;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Loads the generated account fixture directly from the corpus checkout. */
public final class CanonicalAccountProfileLoader {
    private CanonicalAccountProfileLoader() { }

    public static Map<String, CanonicalAccountProfile> load(Path path) throws IOException {
        return load(path, Set.copyOf(CanonicalCorpusLoader.PROFILES));
    }

    public static Map<String, CanonicalAccountProfile> load(Path path, Set<String> requestedProfiles)
            throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8))
            .getAsJsonObject();
        if (!root.has("formatVersion") || root.get("formatVersion").getAsInt() != 1) {
            throw new IllegalArgumentException("account fixture formatVersion must be 1: " + path);
        }
        if (!root.has("benchmarkNowMinutes")) {
            throw new IllegalArgumentException("account fixture has no benchmarkNowMinutes: " + path);
        }
        long benchmarkNowMinutes = root.get("benchmarkNowMinutes").getAsLong();
        JsonObject profiles = root.getAsJsonObject("profiles");
        if (profiles == null) {
            throw new IllegalArgumentException("account fixture has no profiles: " + path);
        }
        Set<String> expected = Set.copyOf(CanonicalCorpusLoader.PROFILES);
        if (requestedProfiles.isEmpty() || !expected.containsAll(requestedProfiles)
                || !profiles.keySet().containsAll(requestedProfiles)) {
            throw new IllegalArgumentException("unknown requested account profiles: " + requestedProfiles);
        }
        Map<String, CanonicalAccountProfile> result = new LinkedHashMap<>();
        for (String name : CanonicalCorpusLoader.PROFILES) {
            if (requestedProfiles.contains(name)) {
                result.put(name, CanonicalAccountProfile.fromJson(
                    profiles.getAsJsonObject(name), benchmarkNowMinutes));
            }
        }
        return Collections.unmodifiableMap(result);
    }
}
