package shortestpath.benchmark.canonical;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class BenchmarkMainTest {
    private static Path CORPUS;

    @BeforeClass
    public static void requireCorpus() {
        CORPUS = TestCorpus.dir();
    }

    @Test
    public void rejectsUnknownRoute() throws Exception {
        Path manifest = manifest("unknown-route", "early", 1, 100000000L);
        expectFailure(manifest);
    }

    @Test
    public void rejectsUnknownProfile() throws Exception {
        CanonicalRoute route = firstRoute();
        Path manifest = manifest(route.getId(), "unknown", 1, 100000000L);
        expectFailure(manifest);
    }

    @Test
    public void rejectsDuplicateCase() throws Exception {
        CanonicalRoute route = firstRoute();
        boolean expectedReachable = expectedReachable(route, "early");
        Path manifest = writeManifest(List.of(caseJson(route, "early", 0, expectedReachable),
            caseJson(route, "early", 0, expectedReachable)), 1, false, 100000000L, false);
        expectFailure(manifest);
    }

    @Test
    public void rejectsUnsupportedProtocolVersion() throws Exception {
        CanonicalRoute route = firstRoute();
        Path manifest = manifest(route.getId(), "early", 1, 100000000L);
        JsonObject json = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
        json.addProperty("format_version", 99);
        Files.writeString(manifest, new Gson().toJson(json), StandardCharsets.UTF_8);
        expectFailure(manifest);
    }

    @Test
    public void executesOnlyExplicitCasesAndReportsJvmMetadata() throws Exception {
        CanonicalRoute route = firstRoute();
        boolean expectedReachable = expectedReachable(route, "early");
        Path manifest = writeManifest(List.of(caseJson(route, "early", 0, expectedReachable),
            caseJson(route, "early", 1, expectedReachable)), 2, false, 123456789L, true);
        Path output = Files.createTempFile("benchmark-main-result", ".json");
        Path progress = Files.createTempFile("benchmark-main-progress", ".jsonl");
        System.setProperty("benchmark.runId", "java-test-run");
        System.setProperty("benchmark.progressFile", progress.toString());
        try {
            BenchmarkMain.run(manifest, CORPUS, output);
        } finally {
            System.clearProperty("benchmark.runId");
            System.clearProperty("benchmark.progressFile");
        }
        JsonObject result = JsonParser.parseString(Files.readString(output)).getAsJsonObject();
        Assert.assertEquals(1, result.get("protocol_version").getAsInt());
        Assert.assertEquals(2, result.getAsJsonArray("observations").size());
        Assert.assertTrue(result.getAsJsonObject("execution_metadata")
            .getAsJsonObject("runtime").get("java_version").getAsString().length() > 0);
        Assert.assertEquals(123456789L, result.getAsJsonObject("execution_metadata")
            .get("synthetic_benchmark_time").getAsLong());
        Assert.assertEquals(0, result.getAsJsonArray("observations").get(0)
            .getAsJsonObject().get("repetition").getAsInt());
        Assert.assertEquals(1, result.getAsJsonArray("observations").get(1)
            .getAsJsonObject().get("repetition").getAsInt());
        List<String> events = Files.readAllLines(progress);
        Assert.assertEquals(5, events.size());
        Assert.assertEquals("running", JsonParser.parseString(events.get(0)).getAsJsonObject()
            .get("phase").getAsString());
        Assert.assertEquals("case-complete", JsonParser.parseString(events.get(2)).getAsJsonObject()
            .get("type").getAsString());
    }

    @Test
    public void acceptsExactProjectWithExactAlgorithm() throws Exception {
        Path manifest = manifest(firstRoute().getId(), "early", 1, 100000000L);
        JsonObject json = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
        json.addProperty("project", "shortest-path-exact");
        json.getAsJsonObject("policy").getAsJsonObject("adapter_args")
            .addProperty("algorithm", "exact");
        Files.writeString(manifest, new Gson().toJson(json), StandardCharsets.UTF_8);
        BenchmarkMain.Plan plan = BenchmarkMain.loadPlan(manifest, CORPUS);
        Assert.assertEquals("shortest-path-exact", plan.project);
        Assert.assertEquals("exact", plan.algorithm);
    }

    @Test
    public void exactSessionDefaultsToColdAndIsValidated() throws Exception {
        Assert.assertEquals("cold", BenchmarkMain.loadPlan(exactManifest(null, false), CORPUS).exactSession);
        Assert.assertEquals("account", BenchmarkMain.loadPlan(exactManifest("account", false), CORPUS).exactSession);
        Assert.assertEquals("target", BenchmarkMain.loadPlan(exactManifest("target", false), CORPUS).exactSession);
        expectFailure(exactManifest("warm", false));
        Path legacy = manifest(firstRoute().getId(), "early", 1, 100000000L);
        JsonObject json = JsonParser.parseString(Files.readString(legacy)).getAsJsonObject();
        json.getAsJsonObject("policy").getAsJsonObject("adapter_args").addProperty("exact_session", "target");
        Files.writeString(legacy, new Gson().toJson(json), StandardCharsets.UTF_8);
        expectFailure(legacy);
    }

    @Test
    public void exactSessionModesReuseTheStagesTheyDescribe() throws Exception {
        JsonObject cold = onlyObservation(exactManifest("cold", true));
        JsonObject account = onlyObservation(exactManifest("account", true));
        JsonObject target = onlyObservation(exactManifest("target", true));

        Assert.assertFalse(cold.get("graph_reused").getAsBoolean());
        Assert.assertFalse(cold.get("target_reused").getAsBoolean());
        Assert.assertTrue(cold.get("reverse_search_ns").getAsLong() > 0);
        Assert.assertTrue(account.get("graph_reused").getAsBoolean());
        Assert.assertFalse(account.get("target_reused").getAsBoolean());
        Assert.assertTrue(account.get("reverse_search_ns").getAsLong() > 0);
        Assert.assertTrue(target.get("graph_reused").getAsBoolean());
        Assert.assertTrue(target.get("target_reused").getAsBoolean());
        Assert.assertEquals(0, target.get("reverse_search_ns").getAsLong());
        Assert.assertEquals("target", target.get("exact_session").getAsString());
        Assert.assertEquals(cold.get("path_cost"), account.get("path_cost"));
        Assert.assertEquals(cold.get("path_cost"), target.get("path_cost"));
    }

    private static Path exactManifest(String session, boolean warmup) throws Exception {
        CanonicalRoute route = firstRoute();
        Path manifest = writeManifest(List.of(caseJson(route, "early", 0, expectedReachable(route, "early"))),
            1, warmup, 100000000L, true);
        JsonObject json = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
        json.addProperty("project", "shortest-path-exact");
        JsonObject adapterArgs = json.getAsJsonObject("policy").getAsJsonObject("adapter_args");
        adapterArgs.addProperty("algorithm", "exact");
        if (session != null) {
            adapterArgs.addProperty("exact_session", session);
        }
        Files.writeString(manifest, new Gson().toJson(json), StandardCharsets.UTF_8);
        return manifest;
    }

    private static JsonObject onlyObservation(Path manifest) throws Exception {
        Path output = Files.createTempFile("benchmark-main-result", ".json");
        System.setProperty("benchmark.runId", "java-test-run");
        try {
            BenchmarkMain.run(manifest, CORPUS, output);
        } finally {
            System.clearProperty("benchmark.runId");
        }
        JsonObject result = JsonParser.parseString(Files.readString(output)).getAsJsonObject();
        Assert.assertEquals(1, result.getAsJsonArray("observations").size());
        return result.getAsJsonArray("observations").get(0).getAsJsonObject();
    }

    private static CanonicalRoute firstRoute() throws Exception {
        return CanonicalCorpusLoader.loadRoutes(CORPUS.resolve("corpus/routes-v1.json")).get(0);
    }

    private static boolean expectedReachable(CanonicalRoute route, String profile) {
        return !route.getNegativeProfiles().contains(profile);
    }

    private static Path manifest(String routeId, String profile, int repetitions, long clock)
            throws Exception {
        CanonicalRoute route = firstRoute();
        boolean expectedReachable = profile.equals("early") && route.getId().equals(routeId)
            ? expectedReachable(route, profile) : false;
        return writeManifest(List.of(caseJson(routeId, profile, 0, expectedReachable)), repetitions,
            false, clock, false);
    }

    private static JsonObject caseJson(CanonicalRoute route, String profile, int repetition,
            boolean expectedReachable) {
        return caseJson(route.getId(), profile, repetition, expectedReachable);
    }

    private static JsonObject caseJson(String routeId, String profile, int repetition,
            boolean expectedReachable) {
        JsonObject result = new JsonObject();
        result.addProperty("route_id", routeId);
        result.addProperty("profile", profile);
        result.addProperty("repetition", repetition);
        result.addProperty("expected_reachable", expectedReachable);
        return result;
    }

    private static Path writeManifest(List<JsonObject> cases, int repetitions, boolean warmup,
            long clock, boolean diagnostic) throws Exception {
        JsonObject policy = new JsonObject();
        policy.addProperty("repetitions", repetitions);
        policy.addProperty("warmup", warmup);
        policy.addProperty("synthetic_benchmark_time", clock);
        JsonObject adapterArgs = new JsonObject();
        adapterArgs.addProperty("diagnostic", diagnostic);
        policy.add("adapter_args", adapterArgs);

        JsonObject result = new JsonObject();
        result.addProperty("format_version", 1);
        result.addProperty("project", "shortest-path");
        result.add("policy", policy);
        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        cases.forEach(array::add);
        result.add("cases", array);
        Path path = Files.createTempFile("benchmark-manifest", ".json");
        Files.writeString(path, new Gson().toJson(result), StandardCharsets.UTF_8);
        return path;
    }

    private static void expectFailure(Path manifest) throws Exception {
        try {
            BenchmarkMain.loadPlan(manifest, CORPUS);
            Assert.fail("expected manifest rejection");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
