package shortestpath.dashboard;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.BoatHull;
import shortestpath.pathfinder.PathStep;
import shortestpath.pathfinder.Pathfinder;
import shortestpath.pathfinder.PathfinderResult;
import shortestpath.pathfinder.SailingMoves;
import shortestpath.pathfinder.TestPathfinderConfig;

/** Searches a route with the sailing search and records what the dashboard draws of it. */
public class SailingRouteRunnerImpl implements SailingRouteRunner {
    @Override
    public PathfinderDashboardModels.SailingRun run(TestPathfinderConfig config, DashboardScenario scenario,
        List<PathStep> normalPath) {
        double speed = scenario.getSailingSpeed().orElseThrow();
        SailingMoves moves = SailingMoves.forSpeed(speed);
        BoatHull hull = SailingBoats.hull(scenario.getBoat());
        Pathfinder pathfinder = new Pathfinder(config, scenario.getStartPoint(), Set.of(scenario.getEndPoint()), null,
            moves, hull);
        pathfinder.run();
        PathfinderResult result = pathfinder.getResult();

        PathfinderDashboardModels.SailingRun run = new PathfinderDashboardModels.SailingRun();
        run.speed = speed;
        run.boat = hull == null ? "" : scenario.getBoat();
        run.reached = result.isReached();
        run.terminationReason = result.getTerminationReason().name();
        run.nodesChecked = result.getNodesChecked() + result.getTransportsChecked();
        run.elapsedNanos = result.getElapsedNanos();
        run.path = new ArrayList<>();
        run.headings = new ArrayList<>();
        run.outlines = new ArrayList<>();

        List<PathStep> path = result.getPathSteps();
        run.distance = SailingPaths.distance(path);
        // Up to where the normal path gets as close to the target as the sailing path stops
        List<PathStep> normalToGap = SailingPaths.upToGap(normalPath, scenario.getEndPoint(),
            SailingPaths.endGap(path, scenario.getEndPoint()));
        run.normalDistance = SailingPaths.distance(normalToGap);
        run.normalLegs = SailingPaths.legs(normalToGap);
        run.normalTicks = SailingPaths.ticksToSail(normalToGap, moves);
        if (hull != null) {
            BoatHull clearance = SailingBoats.hull(scenario.getBoat());
            clearance.allowStartOverlaps(config.getMap(), scenario.getStartPoint());
            run.normalCollisions = SailingPaths.collisions(normalToGap, clearance, config.getMap());
        }
        int heading = -1;
        int previousMove = -1;
        for (int i = 0; i < path.size(); i++) {
            int point = path.get(i).getPackedPosition();
            if (i + 1 < path.size()) {
                int next = path.get(i + 1).getPackedPosition();
                int move = moves.indexOf(WorldPointUtil.unpackWorldX(next) - WorldPointUtil.unpackWorldX(point),
                    WorldPointUtil.unpackWorldY(next) - WorldPointUtil.unpackWorldY(point));
                if (move >= 0) {
                    run.ticks += moves.ticks(move);
                    heading = moves.heading(move);
                }
                if (move == previousMove && i > 0) {
                    // Only keep the points where the path turns
                    continue;
                }
                run.legs++;
                previousMove = move;
            }
            run.path.add(PathfinderDashboardReportWriter.worldPointJsonPacked(point));
            run.headings.add(heading);
            run.outlines.add(hull == null || heading < 0 ? null : hull.outline(heading));
        }
        return run;
    }
}
