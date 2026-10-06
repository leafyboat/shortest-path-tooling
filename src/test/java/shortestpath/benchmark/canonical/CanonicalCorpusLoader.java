package shortestpath.benchmark.canonical;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Loads the implementation-neutral route fixtures without copying them. */
public final class CanonicalCorpusLoader {
    public static final List<String> PROFILES = List.of("early", "mid", "end", "maxed");

    private CanonicalCorpusLoader() { }

    public static List<CanonicalRoute> loadRoutes(Path path) throws IOException {
        var root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonArray();
        List<CanonicalRoute> routes = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (var element : root) {
            CanonicalRoute route = CanonicalRoute.fromJson(element.getAsJsonObject());
            if (!ids.add(route.getId())) {
                throw new IllegalArgumentException("duplicate canonical route id: " + route.getId());
            }
            routes.add(route);
        }
        return Collections.unmodifiableList(routes);
    }
}
