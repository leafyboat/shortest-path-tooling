package shortestpath.benchmark.canonical;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import shortestpath.pathfinder.ExactPathfinder;
import shortestpath.pathfinder.PathfinderResult;
import shortestpath.pathfinder.exact.ExactForwardSearch;
import shortestpath.pathfinder.exact.RoutingStatic;

/** Stable, manifest-driven Java adapter entry point. */
public final class BenchmarkMain {
    private static final int FORMAT_VERSION = 1;
    private static final int PROTOCOL_VERSION = 1;
    private static final String IMPLEMENTATION = "shortest-path-java";

    private BenchmarkMain() { }

    public static void main(String[] args) throws Exception {
        Arguments arguments = Arguments.parse(args);
        run(arguments.manifest, arguments.corpus, arguments.output);
    }

    static void run(Path manifestPath, Path corpus, Path output) throws IOException {
        Plan plan = loadPlan(manifestPath, corpus);
        String runId = System.getenv("BENCHMARK_RUN_ID");
        if (runId == null || runId.isBlank()) {
            runId = System.getProperty("benchmark.runId");
        }
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("BENCHMARK_RUN_ID is required");
        }

        CanonicalAccountCompiler compiler = new CanonicalAccountCompiler();
        long routingStaticStarted = System.nanoTime();
        RoutingStatic routingStatic = plan.algorithm.equals("exact") ? CanonicalRouteAdapter.buildRoutingStatic() : null;
        long routingStaticBuildNanos = routingStatic == null ? 0 : System.nanoTime() - routingStaticStarted;
        Map<String, CanonicalAccountCompiler.CompiledAccount> accounts = new HashMap<>();
        Map<String, String> accountFailures = new HashMap<>();
        for (String profileName : plan.profiles.keySet()) {
            CanonicalAccountProfile profile = plan.profiles.get(profileName);
            for (boolean allowTransports : plan.transportModes) {
                String key = accountKey(profileName, allowTransports);
                try {
                    accounts.put(key, compiler.compileAtTime(profileName, profile, allowTransports,
                        plan.syntheticBenchmarkTime));
                } catch (RuntimeException exception) {
                    accountFailures.put(key, exception.getClass().getName() + ": " + exception.getMessage());
                }
            }
        }

        if (plan.warmup) {
            progressPhase("warming up");
            for (Case current : plan.logicalCases) {
                execute(current, plan, accounts, accountFailures, routingStatic, false, runId);
            }
        }

        List<JsonObject> observations = new ArrayList<>();
        progressPhase("running");
        for (Case current : plan.cases) {
            progressStart(current);
            JsonObject observation = execute(current, plan, accounts, accountFailures, routingStatic, true, runId);
            observations.add(observation);
            progressComplete(observation);
        }

        JsonObject envelope = new JsonObject();
        envelope.addProperty("protocol_version", PROTOCOL_VERSION);
        envelope.add("execution_metadata", executionMetadata(plan, routingStaticBuildNanos));
        JsonArray rows = new JsonArray();
        observations.forEach(rows::add);
        envelope.add("observations", rows);
        Path absolute = output.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        Files.writeString(absolute, new GsonBuilder().serializeNulls().create().toJson(envelope),
            StandardCharsets.UTF_8);
    }

    private static void progressPhase(String phase) throws IOException {
        JsonObject event = new JsonObject();
        event.addProperty("type", "phase");
        event.addProperty("phase", phase);
        appendProgress(event);
    }

    private static void progressStart(Case current) throws IOException {
        JsonObject event = new JsonObject();
        event.addProperty("type", "case-start");
        event.addProperty("route_id", current.route.getId());
        event.addProperty("account_profile", current.profile);
        event.addProperty("repetition", current.repetition);
        appendProgress(event);
    }

    private static void progressComplete(JsonObject observation) throws IOException {
        JsonObject event = new JsonObject();
        event.addProperty("type", "case-complete");
        event.add("observation", observation);
        appendProgress(event);
    }

    private static void appendProgress(JsonObject event) throws IOException {
        String progress = System.getenv("BENCHMARK_PROGRESS_FILE");
        if (progress == null || progress.isBlank()) {
            progress = System.getProperty("benchmark.progressFile");
        }
        if (progress == null || progress.isBlank()) return;
        Path path = Path.of(progress).toAbsolutePath();
        Files.createDirectories(path.getParent());
        Files.writeString(path, event + "\n", StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    static Plan loadPlan(Path manifestPath, Path corpus) throws IOException {
        JsonObject manifest = JsonParser.parseString(
            Files.readString(manifestPath, StandardCharsets.UTF_8)).getAsJsonObject();
        requireInt(manifest, "format_version", FORMAT_VERSION);
        String project = requireString(manifest, "project");
        if (!project.equals("shortest-path") && !project.equals("shortest-path-exact")) {
            throw new IllegalArgumentException("unsupported Java benchmark project: " + project);
        }
        JsonObject policy = requiredObject(manifest, "policy");
        int repetitions = requirePositiveInt(policy, "repetitions");
        boolean warmup = requireBoolean(policy, "warmup");
        long syntheticTime = requireNonNegativeLong(policy, "synthetic_benchmark_time");
        JsonObject adapterArgs = requiredObject(policy, "adapter_args");
        boolean diagnostic = false;
        String algorithm = "legacy";
        for (String key : adapterArgs.keySet()) {
            if (!key.equals("diagnostic") && !key.equals("algorithm")) {
                throw new IllegalArgumentException("unsupported Java adapter argument: " + key);
            }
            if (key.equals("diagnostic")) diagnostic = requireBoolean(adapterArgs, key);
            else algorithm = requireString(adapterArgs, key);
        }
        if (!algorithm.equals("legacy") && !algorithm.equals("exact")) {
            throw new IllegalArgumentException("algorithm must be legacy or exact");
        }
        if (project.equals("shortest-path-exact") != algorithm.equals("exact")) {
            throw new IllegalArgumentException("benchmark project and algorithm disagree");
        }

        Map<String, CanonicalRoute> routes = new LinkedHashMap<>();
        for (CanonicalRoute route : CanonicalCorpusLoader.loadRoutes(
                corpus.resolve("corpus/routes-v1.json"))) {
            routes.put(route.getId(), route);
        }
        JsonArray rawCases = requiredArray(manifest, "cases");
        if (rawCases.size() == 0) {
            throw new IllegalArgumentException("manifest cases must not be empty");
        }

        List<Case> cases = new ArrayList<>();
        Set<String> profilesUsed = new LinkedHashSet<>();
        Set<String> identities = new HashSet<>();
        for (JsonElement element : rawCases) {
            JsonObject raw = element.getAsJsonObject();
            String routeId = requireString(raw, "route_id");
            String profile = requireString(raw, "profile");
            int repetition = requireNonNegativeInt(raw, "repetition");
            if (repetition >= repetitions) {
                throw new IllegalArgumentException("repetition outside policy: " + repetition);
            }
            CanonicalRoute route = routes.get(routeId);
            if (route == null) {
                throw new IllegalArgumentException("unknown route ID: " + routeId);
            }
            if (!CanonicalCorpusLoader.PROFILES.contains(profile)) {
                throw new IllegalArgumentException("unknown profile ID: " + profile);
            }
            String identity = caseKey(routeId, profile, repetition);
            if (!identities.add(identity)) {
                throw new IllegalArgumentException("duplicate manifest case: " + identity);
            }
            boolean expectedReachable = requireBoolean(raw, "expected_reachable");
            boolean curatedReachable = !route.getNegativeProfiles().contains(profile);
            if (expectedReachable != curatedReachable) {
                throw new IllegalArgumentException(
                    "manifest expected_reachable disagrees with curated negativeProfiles: " + identity);
            }
            cases.add(new Case(route, profile, repetition, expectedReachable));
            profilesUsed.add(profile);
        }

        Map<String, CanonicalAccountProfile> profiles = CanonicalAccountProfileLoader.load(
            corpus.resolve("accounts/account-profiles-v1.json"), profilesUsed);
        Set<String> logicalKeys = new HashSet<>();
        for (Case current : cases) {
            logicalKeys.add(current.route.getId() + "/" + current.profile);
        }
        for (String logical : logicalKeys) {
            for (int repetition = 0; repetition < repetitions; repetition++) {
                if (!identities.contains(caseKey(logical.split("/", 2)[0], logical.split("/", 2)[1], repetition))) {
                    throw new IllegalArgumentException("manifest repetitions are incomplete: " + logical);
                }
            }
        }

        Set<Boolean> transportModes = new LinkedHashSet<>();
        for (Case current : cases) {
            transportModes.add(current.route.isAllowTransports());
        }
        List<Case> logicalCases = new ArrayList<>();
        Set<String> seenLogical = new HashSet<>();
        for (Case current : cases) {
            String key = current.route.getId() + "/" + current.profile;
            if (seenLogical.add(key)) {
                logicalCases.add(current);
            }
        }
        return new Plan(project, cases, logicalCases, profiles, transportModes, repetitions, warmup,
            syntheticTime, diagnostic, algorithm);
    }

    private static JsonObject executionMetadata(Plan plan, long routingStaticBuildNanos) {
        JsonObject metadata = new JsonObject();
        metadata.addProperty("implementation", IMPLEMENTATION);
        metadata.addProperty("algorithm", plan.algorithm);
        JsonObject runtime = new JsonObject();
        runtime.addProperty("java_version", System.getProperty("java.version"));
        runtime.addProperty("java_vendor", System.getProperty("java.vendor"));
        runtime.addProperty("vm_name", System.getProperty("java.vm.name"));
        runtime.addProperty("vm_version", System.getProperty("java.vm.version"));
        runtime.addProperty("available_processors", Runtime.getRuntime().availableProcessors());
        metadata.add("runtime", runtime);
        JsonObject dependencies = new JsonObject();
        dependencies.add("shortest_path", dependency("benchmark.shortestPathCommit", "benchmark.shortestPathDirty"));
        dependencies.add("shortest_path_tooling", dependency("benchmark.toolingCommit", "benchmark.toolingDirty"));
        metadata.add("dependencies", dependencies);
        metadata.addProperty("synthetic_benchmark_time", plan.syntheticBenchmarkTime);
        if (routingStaticBuildNanos > 0) {
            metadata.addProperty("routing_static_build_ms", routingStaticBuildNanos / 1_000_000L);
        }
        JsonObject adapterArgs = new JsonObject();
        adapterArgs.addProperty("diagnostic", plan.diagnostic);
        adapterArgs.addProperty("algorithm", plan.algorithm);
        metadata.add("adapter_args", adapterArgs);
        return metadata;
    }

    private static JsonObject dependency(String commitProperty, String dirtyProperty) {
        JsonObject result = new JsonObject();
        result.addProperty("commit", System.getProperty(commitProperty, "unknown"));
        result.addProperty("dirty", Boolean.parseBoolean(System.getProperty(dirtyProperty, "false")));
        return result;
    }

    private static JsonObject execute(Case current, Plan plan,
            Map<String, CanonicalAccountCompiler.CompiledAccount> accounts,
            Map<String, String> accountFailures, RoutingStatic routingStatic, boolean measured, String runId) {
        CanonicalAccountCompiler.CompiledAccount account = accounts.get(
            accountKey(current.profile, current.route.isAllowTransports()));
        String accountFailure = accountFailures.get(accountKey(current.profile, current.route.isAllowTransports()));
        if (accountFailure != null) {
            return measured ? failure(current, runId, plan.project, plan.algorithm, accountFailure) : null;
        }
        if (account == null) {
            throw new IllegalStateException("no compiled account for " + current.profile);
        }
        try {
            long start = System.nanoTime();
            ExactPathfinder exact = plan.algorithm.equals("exact")
                ? CanonicalRouteAdapter.runExact(current.route.getStartPacked(), current.route.getTargetPacked(),
                    account, routingStatic)
                : null;
            PathfinderResult result = exact == null
                ? CanonicalRouteAdapter.runLegacy(current.route.getStartPacked(), current.route.getTargetPacked(), account)
                : exact.getResult();
            long total = System.nanoTime() - start;
            if (!measured) {
                return null;
            }
            boolean reachable = result.isReached();
            Integer returnedCost = result.getPathCost() == PathfinderResult.NO_PATH_COST
                ? null : result.getPathCost();
            Integer pathCost = reachable ? returnedCost : null;
            if (reachable && pathCost == null) {
                throw new IllegalStateException("reached result has no path cost");
            }
            JsonObject row = baseObservation(current, runId, plan.project, plan.algorithm);
            row.addProperty("reachable", reachable);
            addNullable(row, "path_cost", pathCost);
            row.addProperty("correctness", classify(current.expectedReachable, reachable));
            row.addProperty("total_ns", total);
            if (plan.diagnostic) {
                row.addProperty("forward_search_ns", exact == null
                    ? result.getElapsedNanos() : exact.getForwardSearchNanos());
                row.addProperty("path_length", result.getPathSteps().size());
                row.addProperty("states_popped", result.getNodesChecked());
                row.addProperty("transports_checked", result.getTransportsChecked());
                if (exact != null) {
                    ExactForwardSearch.Counters counters = exact.getExactStats();
                    row.addProperty("account_prepare_ns",
                        exact.getAccountPrepareNanos() + exact.getGraphPrepareNanos());
                    row.addProperty("graph_prepare_ns", exact.getGraphPrepareNanos());
                    row.addProperty("reverse_search_ns", exact.getReverseSearchNanos());
                    row.addProperty("seed_table_ns", exact.getHeuristicPrepareNanos());
                    row.addProperty("heuristic_prepare_ns",
                        exact.getReverseSearchNanos() + exact.getHeuristicPrepareNanos());
                    row.addProperty("unique_states_reached", counters.uniqueStatesReached());
                    row.addProperty("pq_pushes", counters.pqPushes());
                    row.addProperty("stale_entries", counters.staleEntries());
                    row.addProperty("max_queue_size", counters.maxQueueSize());
                    row.addProperty("walking_pq_pushes", counters.walkingPqPushes());
                    row.addProperty("local_transport_pq_pushes", counters.localTransportPqPushes());
                    row.addProperty("global_pq_pushes", counters.globalPqPushes());
                    row.addProperty("banking_pq_pushes", counters.bankingPqPushes());
                    row.addProperty("transport_candidates", counters.transportCandidates());
                    row.addProperty("transport_relaxations", counters.transportRelaxations());
                    row.addProperty("successful_transport_relaxations",
                        counters.successfulTransportRelaxations());
                    row.addProperty("initial_capability", counters.initialCapability());
                    row.addProperty("normal_heuristic_enabled_at_start",
                        counters.normalHeuristicEnabledAtStart());
                    row.addProperty("restricted_heuristic_states", counters.restrictedHeuristicStates());
                    row.addProperty("restricted_heuristic_zeroes", counters.restrictedHeuristicZeroes());
                    row.addProperty("normal_heuristic_states", counters.normalHeuristicStates());
                }
            }
            if (result.getTerminationReason() != null) {
                row.addProperty("termination_reason", result.getTerminationReason().name());
            }
            return row;
        } catch (RuntimeException exception) {
            if (!measured) {
                throw exception;
            }
            return failure(current, runId, plan.project, plan.algorithm,
                exception.getClass().getName() + ": " + exception.getMessage());
        }
    }

    private static JsonObject failure(Case current, String runId, String project, String algorithm,
            String reason) {
        JsonObject row = baseObservation(current, runId, project, algorithm);
        row.add("reachable", JsonNull.INSTANCE);
        row.add("path_cost", JsonNull.INSTANCE);
        row.addProperty("correctness", "harness_failure");
        row.add("total_ns", JsonNull.INSTANCE);
        row.addProperty("failure_reason", reason == null || reason.isBlank() ? "unknown failure" : reason);
        return row;
    }

    private static JsonObject baseObservation(Case current, String runId, String project,
            String algorithm) {
        JsonObject row = new JsonObject();
        row.addProperty("schema_version", 2);
        row.addProperty("protocol_version", PROTOCOL_VERSION);
        row.addProperty("run_id", runId);
        row.addProperty("project", project);
        row.addProperty("implementation", IMPLEMENTATION);
        row.addProperty("algorithm", algorithm);
        row.addProperty("route_id", current.route.getId());
        row.addProperty("route_name", current.route.getName());
        row.addProperty("account_profile", current.profile);
        row.addProperty("repetition", current.repetition);
        row.addProperty("expected_reachable", current.expectedReachable);
        return row;
    }

    private static String classify(boolean expectedReachable, boolean actualReachable) {
        return expectedReachable == actualReachable ? "expected" : "reachability_mismatch";
    }

    private static void addNullable(JsonObject object, String field, Integer value) {
        if (value == null) {
            object.add(field, JsonNull.INSTANCE);
        } else {
            object.addProperty(field, value);
        }
    }

    private static String accountKey(String profile, boolean allowTransports) {
        return profile + ":" + allowTransports;
    }

    private static String caseKey(String route, String profile, int repetition) {
        return route + "/" + profile + "/" + repetition;
    }

    private static JsonObject requiredObject(JsonObject object, String field) {
        if (!object.has(field) || !object.get(field).isJsonObject()) {
            throw new IllegalArgumentException("manifest field must be an object: " + field);
        }
        return object.getAsJsonObject(field);
    }

    private static JsonArray requiredArray(JsonObject object, String field) {
        if (!object.has(field) || !object.get(field).isJsonArray()) {
            throw new IllegalArgumentException("manifest field must be an array: " + field);
        }
        return object.getAsJsonArray(field);
    }

    private static String requireString(JsonObject object, String field) {
        if (!object.has(field) || !object.get(field).isJsonPrimitive()
                || !object.get(field).getAsJsonPrimitive().isString()
                || object.get(field).getAsString().isBlank()) {
            throw new IllegalArgumentException("manifest field must be a non-empty string: " + field);
        }
        return object.get(field).getAsString();
    }

    private static boolean requireBoolean(JsonObject object, String field) {
        if (!object.has(field) || !object.get(field).isJsonPrimitive()
                || !object.get(field).getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("manifest field must be a JSON boolean: " + field);
        }
        return object.get(field).getAsBoolean();
    }

    private static int requirePositiveInt(JsonObject object, String field) {
        int value = requireNonNegativeInt(object, field);
        if (value < 1) {
            throw new IllegalArgumentException("manifest field must be positive: " + field);
        }
        return value;
    }

    private static int requireNonNegativeInt(JsonObject object, String field) {
        long value = requireNonNegativeLong(object, field);
        if (value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("manifest integer is too large: " + field);
        }
        return (int) value;
    }

    private static void requireInt(JsonObject object, String field, int expected) {
        if (requireNonNegativeInt(object, field) != expected) {
            throw new IllegalArgumentException("manifest " + field + " must be " + expected);
        }
    }

    private static long requireNonNegativeLong(JsonObject object, String field) {
        if (!object.has(field) || !object.get(field).isJsonPrimitive()
                || !object.get(field).getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("manifest field must be an integer: " + field);
        }
        try {
            long value = Long.parseLong(object.get(field).getAsString());
            if (value < 0) {
                throw new IllegalArgumentException("manifest field must be non-negative: " + field);
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("manifest field must be an integer: " + field, exception);
        }
    }

    static final class Plan {
        final String project;
        final List<Case> cases;
        final List<Case> logicalCases;
        final Map<String, CanonicalAccountProfile> profiles;
        final Set<Boolean> transportModes;
        final int repetitions;
        final boolean warmup;
        final long syntheticBenchmarkTime;
        final boolean diagnostic;
        final String algorithm;

        Plan(String project, List<Case> cases, List<Case> logicalCases,
                Map<String, CanonicalAccountProfile> profiles, Set<Boolean> transportModes,
                int repetitions, boolean warmup, long syntheticBenchmarkTime, boolean diagnostic,
                String algorithm) {
            this.project = project;
            this.cases = List.copyOf(cases);
            this.logicalCases = List.copyOf(logicalCases);
            this.profiles = Map.copyOf(profiles);
            this.transportModes = Set.copyOf(transportModes);
            this.repetitions = repetitions;
            this.warmup = warmup;
            this.syntheticBenchmarkTime = syntheticBenchmarkTime;
            this.diagnostic = diagnostic;
            this.algorithm = algorithm;
        }
    }

    static final class Case {
        final CanonicalRoute route;
        final String profile;
        final int repetition;
        final boolean expectedReachable;

        Case(CanonicalRoute route, String profile, int repetition, boolean expectedReachable) {
            this.route = route;
            this.profile = profile;
            this.repetition = repetition;
            this.expectedReachable = expectedReachable;
        }
    }

    private static final class Arguments {
        final Path manifest;
        final Path corpus;
        final Path output;

        private Arguments(Path manifest, Path corpus, Path output) {
            this.manifest = manifest;
            this.corpus = corpus;
            this.output = output;
        }

        static Arguments parse(String[] args) {
            Path manifest = null;
            Path corpus = null;
            Path output = null;
            for (int i = 0; i < args.length; i++) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("missing value for " + args[i]);
                }
                Path value = Path.of(args[++i]);
                switch (args[i - 1]) {
                    case "--manifest": manifest = value; break;
                    case "--corpus": corpus = value; break;
                    case "--output": output = value; break;
                    default: throw new IllegalArgumentException("unknown argument: " + args[i - 1]);
                }
            }
            if (manifest == null || corpus == null || output == null) {
                throw new IllegalArgumentException("usage: --manifest FILE --corpus DIR --output FILE");
            }
            return new Arguments(manifest, corpus, output);
        }
    }
}
