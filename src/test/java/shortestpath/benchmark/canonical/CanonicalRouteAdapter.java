package shortestpath.benchmark.canonical;

import java.util.Set;
import shortestpath.pathfinder.CollisionMap;
import shortestpath.pathfinder.ExactPathfinder;
import shortestpath.pathfinder.ExactRoutingStaticProvider;
import shortestpath.pathfinder.Pathfinder;
import shortestpath.pathfinder.PathfinderConfig;
import shortestpath.pathfinder.PathfinderResult;
import shortestpath.pathfinder.SplitFlagMap;
import shortestpath.pathfinder.exact.ExactRoutingSession;
import shortestpath.pathfinder.exact.RoutingStatic;

/** The single production execution path shared by the benchmark and route query. */
final class CanonicalRouteAdapter {
    private CanonicalRouteAdapter() { }

    /** Builds the exact backend's static routing data the same way the plugin does. */
    static RoutingStatic buildRoutingStatic() {
        return new ExactRoutingStaticProvider(() -> new CollisionMap(SplitFlagMap.fromResources())).get();
    }

    static PathfinderResult runLegacy(int start, int target, CanonicalAccountCompiler.CompiledAccount account) {
        Pathfinder pathfinder = new Pathfinder(account.getConfig(), start, Set.of(target));
        pathfinder.run();
        return pathfinder.getResult();
    }

    /** Runs through {@code session} so prepared stages are reused; {@code null} prepares everything. */
    static ExactPathfinder runExact(int start, int target,
            CanonicalAccountCompiler.CompiledAccount account, RoutingStatic routingStatic,
            ExactRoutingSession session) {
        PathfinderConfig config = account.getConfig();
        ExactPathfinder pathfinder = new ExactPathfinder(config, routingStatic, session,
            start, Set.of(target), null, config.getExactHeuristicWeight());
        pathfinder.run();
        return pathfinder;
    }
}
