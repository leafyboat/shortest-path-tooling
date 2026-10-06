package shortestpath.benchmark.canonical;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import shortestpath.WorldPointUtil;

/** One route from the shared canonical corpus. */
public final class CanonicalRoute {
    private final String id;
    private final String name;
    private final int[] start;
    private final int[] target;
    private final boolean allowTransports;
    private final List<String> negativeProfiles;

    private CanonicalRoute(JsonObject json) {
        id = requiredString(json, "id");
        name = requiredString(json, "name");
        start = point(json, "start");
        target = point(json, "target");
        allowTransports = requiredBoolean(json, "allowTransports");
        negativeProfiles = json.has("negativeProfiles")
            ? strings(json, "negativeProfiles") : Collections.emptyList();
    }

    public static CanonicalRoute fromJson(JsonObject json) {
        return new CanonicalRoute(json);
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public int[] getStart() { return start.clone(); }
    public int[] getTarget() { return target.clone(); }
    public int getStartPacked() { return WorldPointUtil.packWorldPoint(start[0], start[1], start[2]); }
    public int getTargetPacked() { return WorldPointUtil.packWorldPoint(target[0], target[1], target[2]); }
    public boolean isAllowTransports() { return allowTransports; }
    public List<String> getNegativeProfiles() { return negativeProfiles; }

    private static int[] point(JsonObject json, String field) {
        JsonArray array = required(json, field).getAsJsonArray();
        if (array.size() != 3) {
            throw new IllegalArgumentException("canonical route " + json.get("id").getAsString()
                + " field " + field + " must contain exactly [x, y, plane]");
        }
        return new int[] {array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt()};
    }

    private static List<String> strings(JsonObject json, String field) {
        List<String> result = new ArrayList<>();
        for (var value : required(json, field).getAsJsonArray()) {
            result.add(value.getAsString());
        }
        return Collections.unmodifiableList(result);
    }

    private static String requiredString(JsonObject json, String field) {
        return required(json, field).getAsString();
    }

    private static boolean requiredBoolean(JsonObject json, String field) {
        return required(json, field).getAsBoolean();
    }

    private static com.google.gson.JsonElement required(JsonObject json, String field) {
        if (!json.has(field) || json.get(field).isJsonNull()) {
            throw new IllegalArgumentException("canonical route missing field " + field);
        }
        return json.get(field);
    }
}
