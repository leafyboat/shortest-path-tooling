package shortestpath.dashboard;

import java.util.List;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.BoatHull;
import shortestpath.pathfinder.CollisionMap;
import shortestpath.pathfinder.PathStep;
import shortestpath.pathfinder.SailingMoves;

/**
 * How far a path at sea goes, how many straight legs it has and how many ticks a boat takes to sail it, to
 * compare the normal search's path with the sailing search's.
 */
final class SailingPaths {
    private SailingPaths() {
    }

    /** How far the path ends from the target, in tiles. */
    static double endGap(List<PathStep> path, int target) {
        if (path.isEmpty()) {
            return Double.NaN;
        }
        return gap(path.get(path.size() - 1).getPackedPosition(), target);
    }

    /**
     * The path up to the first point within {@code gap} tiles of the target, or all of it if it never gets
     * that close. A sailing path with a hull stops as close to the target as the hull fits, so the normal
     * search's path, which goes all the way, is compared up to the same distance.
     */
    static List<PathStep> upToGap(List<PathStep> path, int target, double gap) {
        for (int i = 0; i < path.size(); i++) {
            if (gap(path.get(i).getPackedPosition(), target) <= gap + 1e-9) {
                return path.subList(0, i + 1);
            }
        }
        return path;
    }

    private static double gap(int point, int target) {
        return Math.hypot(WorldPointUtil.unpackWorldX(target) - WorldPointUtil.unpackWorldX(point),
            WorldPointUtil.unpackWorldY(target) - WorldPointUtil.unpackWorldY(point));
    }

    /** The path's length in tiles, straight between its points. */
    static double distance(List<PathStep> path) {
        double distance = 0;
        for (int i = 1; i < path.size(); i++) {
            distance += Math.hypot(dx(path, i), dy(path, i));
        }
        return distance;
    }

    /** Straight legs: runs of the same step. */
    static int legs(List<PathStep> path) {
        int legs = 0;
        for (int i = 1; i < path.size(); i++) {
            if (i == 1 || dx(path, i) != dx(path, i - 1) || dy(path, i) != dy(path, i - 1)) {
                legs++;
            }
        }
        return legs;
    }

    /** The ticks a path of sailing moves takes: each move's heading held for its ticks. */
    static int sailingTicks(List<PathStep> path, SailingMoves moves) {
        int ticks = 0;
        for (int i = 1; i < path.size(); i++) {
            int move = moves.indexOf(dx(path, i), dy(path, i));
            ticks += move < 0 ? 0 : moves.ticks(move);
        }
        return ticks;
    }

    /**
     * About how many ticks a boat at the moves' speed takes to sail a path of one-tile steps, such as the
     * normal search's, holding the straight or diagonal heading of each step. Each tick the boat moves a whole
     * number of quarter tiles, so at speed 1.5 a straight heading goes 1.5 tiles a tick but a diagonal only one
     * tile across and one up. Part ticks count, as if the boat could turn between ticks, and turning takes no
     * time, as in the sailing search. Steps of more than a tile, such as transports, are left out.
     */
    static double ticksToSail(List<PathStep> path, SailingMoves moves) {
        double ticks = 0;
        for (int i = 1; i < path.size(); i++) {
            int dx = dx(path, i);
            int dy = dy(path, i);
            if (Math.max(Math.abs(dx), Math.abs(dy)) != 1) {
                continue;
            }
            int move = moveFacing(moves, stepHeading(dx, dy));
            // The move covers a whole number of tiles along its axis (and both axes for a diagonal)
            ticks += (double) moves.ticks(move) / Math.max(Math.abs(moves.dx(move)), Math.abs(moves.dy(move)));
        }
        return ticks;
    }

    /**
     * How many of a path's one-tile steps, such as the normal search's, would run the boat's hull over a
     * blocked tile, with the boat facing each step's straight or diagonal heading.
     */
    static int collisions(List<PathStep> path, BoatHull hull, CollisionMap map) {
        int collisions = 0;
        for (int i = 1; i < path.size(); i++) {
            int dx = dx(path, i);
            int dy = dy(path, i);
            int from = path.get(i - 1).getPackedPosition();
            if (Math.max(Math.abs(dx), Math.abs(dy)) == 1 && !hull.canMove(map, WorldPointUtil.unpackWorldX(from),
                WorldPointUtil.unpackWorldY(from), WorldPointUtil.unpackWorldPlane(from), stepHeading(dx, dy), dx, dy)) {
                collisions++;
            }
        }
        return collisions;
    }

    // The heading of a one-tile step: 0 is south, 4 west, 8 north and 12 east
    private static int stepHeading(int dx, int dy) {
        int[][] headings = {{2, 4, 6}, {0, -1, 8}, {14, 12, 10}};
        return headings[dx + 1][dy + 1];
    }

    private static int moveFacing(SailingMoves moves, int heading) {
        for (int move = 0; move < moves.size(); move++) {
            if (moves.heading(move) == heading) {
                return move;
            }
        }
        throw new IllegalStateException("No sailing move faces heading " + heading);
    }

    private static int dx(List<PathStep> path, int i) {
        return WorldPointUtil.unpackWorldX(path.get(i).getPackedPosition())
            - WorldPointUtil.unpackWorldX(path.get(i - 1).getPackedPosition());
    }

    private static int dy(List<PathStep> path, int i) {
        return WorldPointUtil.unpackWorldY(path.get(i).getPackedPosition())
            - WorldPointUtil.unpackWorldY(path.get(i - 1).getPackedPosition());
    }
}
