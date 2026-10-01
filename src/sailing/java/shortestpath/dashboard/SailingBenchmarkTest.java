package shortestpath.dashboard;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarbitID;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.BoatHull;
import shortestpath.pathfinder.PathStep;
import shortestpath.pathfinder.Pathfinder;
import shortestpath.pathfinder.PathfinderResult;
import shortestpath.pathfinder.SailingMoves;

/**
 * Times the experimental sailing search against the existing search on routes at sea.
 *
 * <p>Each route in the dataset runs with the existing search (the path the plugin draws in red while
 * sailing), then with the sailing search (the game's 16 boat headings) at each speed, first keeping only the
 * boat's centre clear and then each boat's whole hull. The rows' own {@code speed} and {@code boat} columns
 * are ignored; the searches to compare come from the properties below. Rows with the same start and target
 * are one route, named after the first of them without a trailing note in brackets such as "(sloop)".</p>
 *
 * <p>The player counts as on a boat, as they are whenever the plugin runs the sailing search, so neither search
 * uses teleports. Off a boat the existing search would spread out from every teleport's destination as well, and
 * take several times as long as it does in game.</p>
 *
 * <p>Every search on a route runs {@code warmup} times untimed, then {@code rounds} times timed, taking turns
 * so noise spreads evenly. The report gives the median and fastest search times (as the plugin's debug panel
 * measures them), the nodes checked, and the path each search found.</p>
 *
 * <p>It also compares the paths themselves, for each row with its own {@code speed} and {@code boat}: how far
 * the existing path and the sailing path go, their straight legs, and how many ticks the boat takes to sail
 * each (see {@link SailingPaths#ticksToSail} for the existing path).</p>
 *
 * <h3>Gradle invocation</h3>
 * <pre>
 * ./gradlew sailingBenchmark -PpluginDir=../shortest-path
 * </pre>
 *
 * <h3>System properties</h3>
 * <table>
 *   <tr><th>Property</th><th>Default</th></tr>
 *   <tr><td>{@code sailingBenchmark.dataset}</td><td>{@code /dashboard/sailing_routes.csv}</td></tr>
 *   <tr><td>{@code sailingBenchmark.speeds}</td><td>{@code 1.5,3.0}</td></tr>
 *   <tr><td>{@code sailingBenchmark.boats}</td><td>{@code none,raft,skiff,sloop}</td></tr>
 *   <tr><td>{@code sailingBenchmark.warmup}</td><td>{@code 2}</td></tr>
 *   <tr><td>{@code sailingBenchmark.rounds}</td><td>{@code 5}</td></tr>
 *   <tr><td>{@code sailingBenchmark.outputDir}</td><td>{@code build/reports/sailing-benchmark}</td></tr>
 * </table>
 */
public class SailingBenchmarkTest {

    static {
        ((Logger) LoggerFactory.getLogger("shortestpath.transport.Transport")).setLevel(Level.OFF);
    }

    private final DashboardScenarioLoader loader = new DashboardScenarioLoader();

    private Client client;
    private ItemContainer bank;
    private Runnable clientBaseline;

    /** One way of searching a route: the existing search, or the sailing search at a speed with a boat's hull. */
    private static final class Search {
        final String name;
        /** Tiles per tick, or {@code NaN} for the existing search. */
        final double speed;
        /** {@code none} or a {@link SailingBoats} name; unused by the existing search. */
        final String boat;

        Search(String name, double speed, String boat) {
            this.name = name;
            this.speed = speed;
            this.boat = boat;
        }

        boolean isSailing() {
            return !Double.isNaN(speed);
        }
    }

    /** What one search found, and how long each timed run took. */
    static final class Result {
        String route;
        String search;
        boolean reached;
        int nodesChecked;
        /** Path steps for the existing search; for the sailing search, the ticks the path takes. */
        int pathLength;
        String pathUnit;
        int legs;
        double distanceTiles;
        double medianMs;
        double fastestMs;
        double medianWallMs;
        List<Double> runsMs = new ArrayList<>();
    }

    /**
     * The existing path and the sailing path for one row, with the row's boat and speed. The existing path is
     * measured up to where it gets as close to the target as the sailing path stops (see
     * {@link SailingPaths#upToGap}); its full length is kept too.
     */
    static final class Comparison {
        String route;
        String boat;
        double speed;
        boolean normalReached;
        double normalDistance;
        double normalTicks;
        int normalLegs;
        double normalEndGap;
        double normalFullDistance;
        double normalFullTicks;
        /** Steps of the existing path (as far as the sailing path goes) that would run the boat's hull into a blocked tile. */
        int normalCollisions;
        int normalSteps;
        boolean sailingReached;
        double sailingDistance;
        int sailingTicks;
        int sailingLegs;
        double sailingEndGap;
    }

    @Before
    public void setUp() {
        client = mock(Client.class);
        bank = mock(ItemContainer.class);
        when(bank.getItems()).thenReturn(new Item[0]);
        clientBaseline = () -> {
            reset(client);
            when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
            when(client.getClientThread()).thenReturn(Thread.currentThread());
            when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);
            when(client.getTotalLevel()).thenReturn(2277);
            when(client.getVarbitValue(VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE)).thenReturn(1);
            when(client.getVarbitValue(VarbitID.FAIRY2_QUEENCURE_QUEST)).thenReturn(100);
            // On a boat, which turns teleports off for every search, as in game while sailing
            when(client.getVarbitValue(VarbitID.SAILING_BOARDED_BOAT)).thenReturn(1);
            when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
            when(client.getItemContainer(InventoryID.INV)).thenReturn(null);
            when(client.getItemContainer(InventoryID.WORN)).thenReturn(null);
        };
        clientBaseline.run();
    }

    @Test
    public void run() throws IOException {
        Assume.assumeTrue("Run with ./gradlew sailingBenchmark", Boolean.getBoolean("sailingBenchmark.run"));

        String dataset = System.getProperty("sailingBenchmark.dataset", "/dashboard/sailing_routes.csv");
        int warmup = Integer.getInteger("sailingBenchmark.warmup", 2);
        int rounds = Integer.getInteger("sailingBenchmark.rounds", 5);
        Path outputDir = Paths.get(System.getProperty("sailingBenchmark.outputDir", "build/reports/sailing-benchmark"));
        List<Search> searches = searches(
            System.getProperty("sailingBenchmark.speeds", "1.5,3.0"),
            System.getProperty("sailingBenchmark.boats", "none,raft,skiff,sloop"));

        List<DashboardScenario> rows = dataset.startsWith("/")
            ? loader.loadFromResource(dataset)
            : loader.loadFromCsv(Paths.get(dataset));
        List<DashboardScenario> routes = distinctRoutes(rows);
        System.out.printf("Sailing benchmark: %d routes x %d searches, %d warm-up and %d timed runs each%n%n",
            routes.size(), searches.size(), warmup, rounds);

        List<Result> results = new ArrayList<>();
        for (DashboardScenario route : routes) {
            DashboardScenarioRunner.ApplyResult applied = DashboardScenarioRunner.apply(route, client, clientBaseline, bank);
            List<Result> routeResults = new ArrayList<>();
            for (Search search : searches) {
                Result result = new Result();
                result.route = routeName(route);
                result.search = search.name;
                routeResults.add(result);
            }
            List<List<Double>> wallRuns = new ArrayList<>();
            for (int i = 0; i < searches.size(); i++) {
                wallRuns.add(new ArrayList<>());
            }
            for (int round = 0; round < warmup + rounds; round++) {
                for (int i = 0; i < searches.size(); i++) {
                    System.gc();
                    long before = System.nanoTime();
                    Pathfinder pathfinder = searchOnce(applied, route, searches.get(i));
                    long wallNanos = System.nanoTime() - before;
                    if (round < warmup) {
                        continue;
                    }
                    Result result = routeResults.get(i);
                    PathfinderResult found = pathfinder.getResult();
                    result.runsMs.add(pathfinder.getStats().getElapsedTimeNanos() / 1e6);
                    wallRuns.get(i).add(wallNanos / 1e6);
                    describePath(result, found, searches.get(i));
                }
            }
            for (int i = 0; i < searches.size(); i++) {
                Result result = routeResults.get(i);
                result.medianMs = median(result.runsMs);
                result.fastestMs = result.runsMs.stream().mapToDouble(Double::doubleValue).min().orElse(Double.NaN);
                result.medianWallMs = median(wallRuns.get(i));
            }
            results.addAll(routeResults);
            System.out.print(routeTable(routeName(route), routeResults));
        }

        List<Comparison> comparisons = new ArrayList<>();
        for (DashboardScenario row : rows) {
            if (row.getSailingSpeed().isPresent()) {
                comparisons.add(compare(row));
            }
        }

        String report = "# Sailing benchmark\n\n"
            + String.format(Locale.ROOT, "%d warm-up and %d timed runs of each search; times are the search as the "
                + "plugin's debug panel measures it. The player is on a boat, so neither search uses teleports.%n%n",
                warmup, rounds)
            + comparisonTable(comparisons) + "\n"
            + summaryTable(searches, results) + "\n" + String.join("", routeTables(routes, results));
        System.out.println(summaryTable(searches, results));
        System.out.println(comparisonTable(comparisons));

        Files.createDirectories(outputDir);
        String stem = datasetStem(dataset);
        Files.write(outputDir.resolve(stem + ".md"), report.getBytes(StandardCharsets.UTF_8));
        java.util.Map<String, Object> json = new java.util.LinkedHashMap<>();
        json.put("searches", results);
        json.put("paths", comparisons);
        Files.write(outputDir.resolve(stem + ".json"),
            new GsonBuilder().setPrettyPrinting().create().toJson(json).getBytes(StandardCharsets.UTF_8));
        System.out.println("Written to " + outputDir.toAbsolutePath().resolve(stem + ".md"));
    }

    // Searches a row with the existing search and with the sailing search at the row's speed and boat
    private Comparison compare(DashboardScenario row) {
        DashboardScenarioRunner.ApplyResult applied = DashboardScenarioRunner.apply(row, client, clientBaseline, bank);
        SailingMoves moves = SailingMoves.forSpeed(row.getSailingSpeed().getAsDouble());
        Pathfinder normal = new Pathfinder(applied.pathfinderConfig, row.getStartPoint(), Set.of(row.getEndPoint()));
        normal.run();
        Pathfinder sailing = new Pathfinder(applied.pathfinderConfig, row.getStartPoint(), Set.of(row.getEndPoint()), null,
            moves, SailingBoats.hull(row.getBoat()));
        sailing.run();

        Comparison comparison = new Comparison();
        comparison.route = row.getName();
        comparison.boat = row.getBoat().isEmpty() ? "centre only" : row.getBoat();
        comparison.speed = moves.speed();
        List<PathStep> sailingPath = sailing.getResult().getPathSteps();
        comparison.sailingReached = sailing.getResult().isReached();
        comparison.sailingDistance = SailingPaths.distance(sailingPath);
        comparison.sailingTicks = SailingPaths.sailingTicks(sailingPath, moves);
        comparison.sailingLegs = SailingPaths.legs(sailingPath);
        comparison.sailingEndGap = SailingPaths.endGap(sailingPath, row.getEndPoint());

        List<PathStep> fullNormalPath = normal.getResult().getPathSteps();
        List<PathStep> normalPath = SailingPaths.upToGap(fullNormalPath, row.getEndPoint(), comparison.sailingEndGap);
        comparison.normalReached = normal.getResult().isReached();
        comparison.normalDistance = SailingPaths.distance(normalPath);
        comparison.normalTicks = SailingPaths.ticksToSail(normalPath, moves);
        comparison.normalLegs = SailingPaths.legs(normalPath);
        comparison.normalEndGap = SailingPaths.endGap(normalPath, row.getEndPoint());
        comparison.normalFullDistance = SailingPaths.distance(fullNormalPath);
        comparison.normalFullTicks = SailingPaths.ticksToSail(fullNormalPath, moves);
        comparison.normalSteps = normalPath.size() - 1;
        BoatHull hull = SailingBoats.hull(row.getBoat());
        if (hull != null) {
            hull.allowStartOverlaps(applied.pathfinderConfig.getMap(), row.getStartPoint());
            comparison.normalCollisions = SailingPaths.collisions(normalPath, hull, applied.pathfinderConfig.getMap());
        }
        return comparison;
    }

    private static String comparisonTable(List<Comparison> comparisons) {
        StringBuilder table = new StringBuilder("## Existing path vs sailing path\n\n"
            + "Each route with its own boat and speed. A sailing path with a hull stops where the hull gets as close "
            + "to the target as it fits, so the existing path is measured up to where it gets that close too. Its "
            + "ticks are about how long the boat takes to sail it holding the straight or diagonal heading of each "
            + "step; neither path counts time spent turning. The existing path isn't one the boat can actually sail: "
            + "the last column counts its steps that would run the boat's hull over a blocked tile.\n\n"
            + "| Route | Speed | Stops from target | Existing: tiles | Existing: ticks | Existing: legs "
            + "| Sailing: tiles | Sailing: ticks | Sailing: legs | Ticks saved | Existing steps that hit rocks |\n"
            + "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (Comparison c : comparisons) {
            table.append(String.format(Locale.ROOT, "| %s%s | %.1f | %.0f | %.0f | %.0f | %d | %.0f | %d%s | %d | %d%% | %d of %d |%n",
                c.route, c.normalReached ? "" : " (existing not reached)", c.speed, c.sailingEndGap, c.normalDistance,
                c.normalTicks, c.normalLegs, c.sailingDistance, c.sailingTicks, c.sailingReached ? "" : " (not reached)",
                c.sailingLegs, Math.round(100 * (c.normalTicks - c.sailingTicks) / c.normalTicks), c.normalCollisions,
                c.normalSteps));
        }
        return table.toString();
    }

    // One row per start and target
    private static List<DashboardScenario> distinctRoutes(List<DashboardScenario> rows) {
        List<DashboardScenario> routes = new ArrayList<>();
        Set<Long> seen = new java.util.HashSet<>();
        for (DashboardScenario row : rows) {
            if (seen.add(((long) row.getStartPoint() << 32) | (row.getEndPoint() & 0xFFFFFFFFL))) {
                routes.add(row);
            }
        }
        return routes;
    }

    private static String routeName(DashboardScenario route) {
        return route.getName().replaceFirst("\\s*\\([^)]*\\)$", "");
    }

    private static Pathfinder searchOnce(DashboardScenarioRunner.ApplyResult applied, DashboardScenario route, Search search) {
        Pathfinder pathfinder = search.isSailing()
            ? new Pathfinder(applied.pathfinderConfig, route.getStartPoint(), Set.of(route.getEndPoint()), null,
                SailingMoves.forSpeed(search.speed), SailingBoats.hull(search.boat))
            : new Pathfinder(applied.pathfinderConfig, route.getStartPoint(), Set.of(route.getEndPoint()));
        pathfinder.run();
        return pathfinder;
    }

    private static List<Search> searches(String speeds, String boats) {
        List<Search> searches = new ArrayList<>();
        searches.add(new Search("existing", Double.NaN, "none"));
        for (String speedText : speeds.split(",")) {
            double speed = Double.parseDouble(speedText.trim());
            for (String boatText : boats.split(",")) {
                String boat = boatText.trim().toLowerCase(Locale.ROOT);
                if (!boat.equals("none") && !SailingBoats.NAMES.contains(boat)) {
                    throw new IllegalArgumentException("Unknown boat '" + boat + "', expected one of " + SailingBoats.NAMES + " or none");
                }
                String hull = boat.equals("none") ? "centre only" : boat;
                searches.add(new Search(String.format(Locale.ROOT, "sailing %s, %s", speedText.trim(), hull), speed, boat));
            }
        }
        return searches;
    }

    private static void describePath(Result result, PathfinderResult found, Search search) {
        List<PathStep> path = found.getPathSteps();
        result.reached = found.isReached();
        result.nodesChecked = found.getNodesChecked() + found.getTransportsChecked();
        result.distanceTiles = 0;
        for (int i = 1; i < path.size(); i++) {
            int from = path.get(i - 1).getPackedPosition();
            int to = path.get(i).getPackedPosition();
            result.distanceTiles += Math.hypot(WorldPointUtil.unpackWorldX(to) - WorldPointUtil.unpackWorldX(from),
                WorldPointUtil.unpackWorldY(to) - WorldPointUtil.unpackWorldY(from));
        }
        if (!search.isSailing()) {
            result.pathLength = path.size();
            result.pathUnit = "steps";
            result.legs = 0;
            return;
        }
        SailingMoves moves = SailingMoves.forSpeed(search.speed);
        int ticks = 0;
        int legs = 0;
        int previous = -1;
        for (int i = 1; i < path.size(); i++) {
            int from = path.get(i - 1).getPackedPosition();
            int to = path.get(i).getPackedPosition();
            int move = moves.indexOf(WorldPointUtil.unpackWorldX(to) - WorldPointUtil.unpackWorldX(from),
                WorldPointUtil.unpackWorldY(to) - WorldPointUtil.unpackWorldY(from));
            ticks += move < 0 ? 0 : moves.ticks(move);
            if (move != previous) {
                legs++;
            }
            previous = move;
        }
        result.pathLength = ticks;
        result.pathUnit = "ticks";
        result.legs = legs;
    }

    private static String summaryTable(List<Search> searches, List<Result> results) {
        double existingTotal = results.stream().filter(r -> r.search.equals("existing")).mapToDouble(r -> r.medianMs).sum();
        StringBuilder table = new StringBuilder("## All routes\n\n"
            + "| Search | Routes reached | Total of median times (ms) | vs existing | Total nodes checked |\n"
            + "|---|---:|---:|---:|---:|\n");
        for (Search search : searches) {
            List<Result> mine = new ArrayList<>();
            for (Result result : results) {
                if (result.search.equals(search.name)) {
                    mine.add(result);
                }
            }
            double total = mine.stream().mapToDouble(r -> r.medianMs).sum();
            table.append(String.format(Locale.ROOT, "| %s | %d of %d | %.1f | %.2fx | %,d |%n", search.name,
                mine.stream().filter(r -> r.reached).count(), mine.size(), total, total / existingTotal,
                mine.stream().mapToLong(r -> r.nodesChecked).sum()));
        }
        return table.toString();
    }

    private static List<String> routeTables(List<DashboardScenario> routes, List<Result> results) {
        List<String> tables = new ArrayList<>();
        for (DashboardScenario route : routes) {
            List<Result> mine = new ArrayList<>();
            for (Result result : results) {
                if (result.route.equals(routeName(route))) {
                    mine.add(result);
                }
            }
            tables.add(routeTable(routeName(route), mine));
        }
        return tables;
    }

    private static String routeTable(String route, List<Result> results) {
        StringBuilder table = new StringBuilder("## " + route + "\n\n"
            + "| Search | Reached | Median ms | Fastest ms | Nodes checked | Path | Distance sailed (tiles) |\n"
            + "|---|---|---:|---:|---:|---|---:|\n");
        for (Result result : results) {
            String path = result.pathUnit.equals("ticks")
                ? String.format(Locale.ROOT, "%d ticks, %d legs", result.pathLength, result.legs)
                : String.format(Locale.ROOT, "%d steps", result.pathLength);
            table.append(String.format(Locale.ROOT, "| %s | %s | %.1f | %.1f | %,d | %s | %.0f |%n", result.search,
                result.reached ? "yes" : "no", result.medianMs, result.fastestMs, result.nodesChecked, path,
                result.distanceTiles));
        }
        return table.append("\n").toString();
    }

    private static double median(List<Double> values) {
        if (values.isEmpty()) {
            return Double.NaN;
        }
        double[] sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        int middle = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
    }

    private static String datasetStem(String dataset) {
        String name = Paths.get(dataset).getFileName().toString();
        return name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
    }
}
