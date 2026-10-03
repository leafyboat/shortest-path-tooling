package shortestpath.pathfinder;

import java.util.List;
import java.util.Set;

import shortestpath.PrimitiveIntList;
import shortestpath.WorldPointUtil;
import shortestpath.leagues.LeagueModeState;
import shortestpath.transport.Transport;

/**
 * Test-only pathfinder that replicates the core search loop from {@link Pathfinder}
 * with full profiling instrumentation. This class never ships in the production build.
 *
 * <p>The search algorithm is kept structurally identical to
 * {@code Pathfinder.run()} so that the {@code profilingDoesNotAffectResults}
 * test can catch any drift.</p>
 *
 * <p>Like {@link Pathfinder}, nodes are stored as {@code int} ids into a shared
 * {@link NodeGraph} (structure-of-arrays) rather than one object per explored tile (issue #491).
 * The neighbour-generation phases that {@link CollisionMap#getNeighbors} performs are re-inlined
 * here so each sub-phase can be timed; the node bookkeeping is otherwise identical.</p>
 */
public class ProfilingPathfinder {

    private final PathfinderConfig config;
    private final CollisionMap map;
    private final int start;
    private final Set<Integer> targets;
    private final boolean targetInWilderness;
    private final boolean targetInBlockedRegion;

    private final NodeGraph graph = new NodeGraph(1 << 14);
    private final IntDeque boundary = new IntDeque(4096);
    private final IntMinHeap pending = new IntMinHeap(graph, 256);
    private final VisitedTiles visited;

    private PathfinderProfile profile;
    private PathfinderResult result;

    private int bestLastNode = NodeGraph.NO_NODE;
    private int bestRemainingDistance = Integer.MAX_VALUE;
    private int bestTravelledDistance = Integer.MAX_VALUE;
    private int bestX = Integer.MAX_VALUE;
    private int bestY = Integer.MAX_VALUE;
    private int reachedTarget = WorldPointUtil.UNDEFINED;
    private PathTerminationReason terminationReason;
    private int wildernessLevel;

    private int nodesChecked;
    private int transportsChecked;

    // Shared neighbor list, matching CollisionMap's single-threaded assumption
    private final PrimitiveIntList neighbors = new PrimitiveIntList(16);
    private final boolean[] traversable = new boolean[8];
    private static final OrdinalDirection[] ORDINAL_VALUES = OrdinalDirection.values();

    public ProfilingPathfinder(PathfinderConfig config, int start, Set<Integer> targets) {
        this.config = config;
        this.map = config.getMap();
        this.start = start;
        this.targets = targets;
        this.visited = new VisitedTiles(map);
        this.targetInWilderness = WildernessChecker.isInWilderness(targets);
        this.targetInBlockedRegion = anyInBlockedRegion(config.getLeagueModeState(), targets);
        this.wildernessLevel = 31;
        this.profile = new PathfinderProfile();
    }

    private static boolean anyInBlockedRegion(LeagueModeState league, Set<Integer> packed) {
        if (!league.isSeasonal() || packed == null || packed.isEmpty()) {
            return false;
        }
        for (Integer point : packed) {
            if (league.isInBlockedRegion(point)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Runs the pathfinding algorithm with full profiling. After this method
     * returns, {@link #getProfile()} and {@link #getResult()} are available.
     */
    public void run() {
        long startNanos = System.nanoTime();
        boundary.addFirst(graph.createStart(start));

        long cutoffDurationMillis = config.getCalculationCutoffMillis();
        long cutoffTimeMillis = System.currentTimeMillis() + cutoffDurationMillis;
        int iteration = 0;

        while (!boundary.isEmpty() || !pending.isEmpty()) {
            // ── Queue selection phase ──
            long phaseStart = System.nanoTime();

            int boundaryHead = boundary.peekFirst();
            int pendingHead = pending.peek();

            int node;
            if (pendingHead != NodeGraph.NO_NODE
                && (boundaryHead == NodeGraph.NO_NODE || graph.compareCost(pendingHead) < graph.cost(boundaryHead))) {
                node = pending.poll();

                // For delayed-visit nodes, check if the destination was already
                // reached by a cheaper path while this node was queued.
                if (graph.isDelayedVisit(node)) {
                    int packed = graph.packedPosition(node);
                    boolean bank = graph.bankVisited(node);
                    if (visited.get(packed, bank)) {
                        profile.delayedVisitSkipped++;
                        profile.queueSelectionNanos += System.nanoTime() - phaseStart;
                        continue;
                    }
                    visited.set(packed, bank);
                }
            } else {
                node = boundary.pollFirst();
            }

            profile.queueSelectionNanos += System.nanoTime() - phaseStart;

            if (node == NodeGraph.NO_NODE) {
                continue;
            }

            final boolean nodeIsTile = graph.isTile(node);
            final int nodePacked = nodeIsTile ? graph.packedPosition(node) : WorldPointUtil.UNDEFINED;

            // ── Wilderness check phase ──
            if (nodeIsTile) {
                phaseStart = System.nanoTime();
                updateWildernessLevel(nodePacked);
                profile.wildernessCheckNanos += System.nanoTime() - phaseStart;
            }

            // ── Target check phase ──
            if (nodeIsTile) {
                phaseStart = System.nanoTime();

                if (targets.contains(nodePacked)) {
                    bestLastNode = node;
                    reachedTarget = nodePacked;
                    terminationReason = PathTerminationReason.TARGET_REACHED;
                    profile.targetCheckNanos += System.nanoTime() - phaseStart;
                    break;
                }

                if (updateBestPathWhenUnreachable(node, nodePacked)) {
                    cutoffTimeMillis = System.currentTimeMillis() + cutoffDurationMillis;
                }

                profile.targetCheckNanos += System.nanoTime() - phaseStart;
            }

            // ── Cutoff check phase ──
            phaseStart = System.nanoTime();

            if (System.currentTimeMillis() > cutoffTimeMillis) {
                terminationReason = PathTerminationReason.CUTOFF_REACHED;
                profile.cutoffCheckNanos += System.nanoTime() - phaseStart;
                break;
            }

            profile.cutoffCheckNanos += System.nanoTime() - phaseStart;

            // ── addNeighbors phase ──
            phaseStart = System.nanoTime();
            addNeighbors(node, nodeIsTile, nodePacked);
            profile.addNeighborsNanos += System.nanoTime() - phaseStart;

            // ── Bookkeeping phase ──
            phaseStart = System.nanoTime();

            profile.updatePeakBoundarySize(boundary.size());
            profile.updatePeakPendingSize(pending.size());
            iteration++;
            if (profile.shouldSample(iteration)) {
                profile.recordSample(iteration, boundary.size(), pending.size(),
                    graph.cost(node), System.nanoTime() - startNanos);
            }

            profile.bookkeepingNanos += System.nanoTime() - phaseStart;
        }

        if (terminationReason == null) {
            terminationReason = PathTerminationReason.SEARCH_EXHAUSTED;
        }

        boolean reached = reachedTarget != WorldPointUtil.UNDEFINED;
        int target = reached ? reachedTarget : (targets.isEmpty() ? WorldPointUtil.UNDEFINED : targets.iterator().next());
        // Materialise the path/closest tile from the graph before releasing it.
        int closestReached = bestLastNode != NodeGraph.NO_NODE ? graph.getClosestTilePosition(bestLastNode) : start;
        List<PathStep> path = bestLastNode != NodeGraph.NO_NODE ? graph.getPathSteps(bestLastNode) : List.of();

        long elapsedNanos = System.nanoTime() - startNanos;

        boundary.clear();
        visited.clear();
        pending.clear();
        graph.release();

        result = new PathfinderResult(start, target, reached, path, closestReached,
            nodesChecked, transportsChecked, elapsedNanos, terminationReason);
    }

    // ── addNeighbors: matches Pathfinder.addNeighbors exactly ───────────

    private void addNeighbors(int node, boolean nodeIsTile, int nodePacked) {
        PrimitiveIntList nodes;
        if (nodeIsTile) {
            nodes = getTileNeighbors(node, nodePacked);
        } else {
            // ── Abstract node expansion sub-phase ──
            // getAbstractNodeNeighbors iterates all usable teleports; accumulate into
            // abstractNodeNanos alongside the creation cost tracked in getTileNeighbors.
            long abstractExpansionStart = System.nanoTime();
            nodes = getAbstractNodeNeighbors(node);
            profile.abstractNodeNanos += System.nanoTime() - abstractExpansionStart;
        }

        // ── Enqueue sub-phase ──
        long enqueueStart = System.nanoTime();

        final int count = nodes.size();
        for (int i = 0; i < count; i++) {
            int neighbor = nodes.get(i);
            final boolean neighborIsTile = graph.isTile(neighbor);
            if (nodeIsTile && neighborIsTile) {
                final int neighborPacked = graph.packedPosition(neighbor);
                if (config.avoidWilderness(nodePacked, neighborPacked, targetInWilderness)) {
                    continue;
                }
                if (config.avoidBlockedRegion(nodePacked, neighborPacked, targetInBlockedRegion)) {
                    continue;
                }
            }

            final boolean neighborIsTransport = graph.isTransport(neighbor);
            // For delayed-visit nodes (shared destinations), don't mark as visited on enqueue.
            // They will be checked and marked when dequeued from pending.
            if (!(neighborIsTransport && graph.isDelayedVisit(neighbor))) {
                visited.set(neighbor, graph);
            } else {
                profile.delayedVisitEnqueued++;
            }
            if (neighborIsTransport) {
                pending.add(neighbor);
                ++transportsChecked;
                profile.transportNeighborsAdded++;
            } else {
                boundary.addLast(neighbor);
                ++nodesChecked;
                profile.tileNeighborsAdded++;
            }
        }

        // Tile visit counting for tile nodes
        if (nodeIsTile) {
            profile.incrementTileVisit(nodePacked);
        }

        profile.enqueueNanos += System.nanoTime() - enqueueStart;
    }

    // ── getTileNeighbors: matches CollisionMap.getTileNeighbors ─────────
    // Only calls visited.get() (never visited.set()), returns the neighbor list.

    private PrimitiveIntList getTileNeighbors(int node, int packedPosition) {
        final int x = WorldPointUtil.unpackWorldX(packedPosition);
        final int y = WorldPointUtil.unpackWorldY(packedPosition);
        final int z = WorldPointUtil.unpackWorldPlane(packedPosition);

        neighbors.clear();

        // ── Bank check sub-phase ──
        long subStart = System.nanoTime();

        boolean nodeBankVisited = graph.bankVisited(node);
        boolean pathBankVisited = nodeBankVisited
            || (config.isBankPathEnabled() && config.bankAccessible(packedPosition));

        profile.bankCheckNanos += System.nanoTime() - subStart;
        if (pathBankVisited && !nodeBankVisited) {
            profile.bankTransitions++;
        }

        // ── Transport lookup sub-phase ──
        subStart = System.nanoTime();

        Transport[] transports = config.getTransportsPacked(pathBankVisited)
            .getOrDefault(packedPosition, TransportAvailability.EMPTY_TRANSPORTS);
        int inheritedDifferential = (graph.isTransport(node) && graph.isDelayedVisit(node))
            ? graph.differentialCost(node)
            : 0;
        for (Transport transport : transports) {
            profile.transportEvaluations++;
            boolean delayedVisit = transport.getType().sharesDestinationsWith() != null;
            if (!delayedVisit && visited.get(transport.getDestination(), pathBankVisited)) {
                profile.visitedSkipped++;
                continue;
            }
            int chainPenalty = (delayedVisit && inheritedDifferential > 0) ? inheritedDifferential : 0;
            neighbors.add(graph.createTransport(
                transport.getDestination(), node,
                transport.getDuration(), config.getAdditionalTransportCost(transport) + chainPenalty,
                pathBankVisited,
                delayedVisit,
                delayedVisit ? config.getDifferentialCost(transport) : 0));
        }

        profile.transportLookupNanos += System.nanoTime() - subStart;

        // ── Abstract node sub-phase ──
        subStart = System.nanoTime();

        AbstractNodeKind abstractKind = AbstractNodeKind.fromWildernessLevel(wildernessLevel);
        if (!visited.getAbstract(abstractKind, pathBankVisited)) {
            neighbors.add(graph.createAbstract(abstractKind, node, pathBankVisited));
            profile.abstractNodesExpanded++;
        }

        profile.abstractNodeNanos += System.nanoTime() - subStart;

        // ── Collision check sub-phase ──
        subStart = System.nanoTime();

        if (map.isBlocked(x, y, z)) {
            boolean westBlocked = map.isBlocked(x - 1, y, z);
            boolean eastBlocked = map.isBlocked(x + 1, y, z);
            boolean southBlocked = map.isBlocked(x, y - 1, z);
            boolean northBlocked = map.isBlocked(x, y + 1, z);
            boolean southWestBlocked = map.isBlocked(x - 1, y - 1, z);
            boolean southEastBlocked = map.isBlocked(x + 1, y - 1, z);
            boolean northWestBlocked = map.isBlocked(x - 1, y + 1, z);
            boolean northEastBlocked = map.isBlocked(x + 1, y + 1, z);
            traversable[0] = !westBlocked;
            traversable[1] = !eastBlocked;
            traversable[2] = !southBlocked;
            traversable[3] = !northBlocked;
            traversable[4] = !southWestBlocked && !westBlocked && !southBlocked;
            traversable[5] = !southEastBlocked && !eastBlocked && !southBlocked;
            traversable[6] = !northWestBlocked && !westBlocked && !northBlocked;
            traversable[7] = !northEastBlocked && !eastBlocked && !northBlocked;
        } else {
            traversable[0] = map.w(x, y, z);
            traversable[1] = map.e(x, y, z);
            traversable[2] = map.s(x, y, z);
            traversable[3] = map.n(x, y, z);
            // Diagonals: same logic as CollisionMap's private sw/se/nw/ne methods
            traversable[4] = map.s(x, y, z) && map.w(x, y - 1, z) && map.w(x, y, z) && map.s(x - 1, y, z);
            traversable[5] = map.s(x, y, z) && map.e(x, y - 1, z) && map.e(x, y, z) && map.s(x + 1, y, z);
            traversable[6] = map.n(x, y, z) && map.w(x, y + 1, z) && map.w(x, y, z) && map.n(x - 1, y, z);
            traversable[7] = map.n(x, y, z) && map.e(x, y + 1, z) && map.e(x, y, z) && map.n(x + 1, y, z);
        }

        profile.collisionCheckNanos += System.nanoTime() - subStart;

        // ── Walkable tile iteration sub-phase ──
        subStart = System.nanoTime();

        for (int i = 0; i < traversable.length; i++) {
            OrdinalDirection d = ORDINAL_VALUES[i];
            int neighborPacked = WorldPointUtil.packWorldPoint(x + d.x, y + d.y, z);
            if (visited.get(neighborPacked, pathBankVisited)) continue;

            if (traversable[i]) {
                neighbors.add(graph.createTile(neighborPacked, node, pathBankVisited));
            } else if (Math.abs(d.x + d.y) == 1 && map.isBlocked(x + d.x, y + d.y, z)) {
                // Blocked-tile transport fallback
                profile.walkableTileNanos += System.nanoTime() - subStart;
                subStart = System.nanoTime();

                Transport[] neighborTransports = config.getTransportsPacked(pathBankVisited)
                    .getOrDefault(neighborPacked, TransportAvailability.EMPTY_TRANSPORTS);
                for (Transport transport : neighborTransports) {
                    profile.blockedTileTransportChecks++;
                    if (transport.getOrigin() == Transport.UNDEFINED_ORIGIN
                        || !transport.isUsableAtWildernessLevel(wildernessLevel)
                        || visited.get(transport.getOrigin(), pathBankVisited)) {
                        continue;
                    }
                    neighbors.add(graph.createTile(transport.getOrigin(), node, pathBankVisited));
                }

                profile.blockedTileTransportNanos += System.nanoTime() - subStart;
                subStart = System.nanoTime();
            }
        }

        profile.walkableTileNanos += System.nanoTime() - subStart;

        return neighbors;
    }

    // ── getAbstractNodeNeighbors: matches CollisionMap.getAbstractNodeNeighbors ──

    private PrimitiveIntList getAbstractNodeNeighbors(int node) {
        neighbors.clear();
        int sourceTile = graph.getClosestTilePosition(node);
        boolean bankVisited = graph.bankVisited(node);
        int maxWildernessLevel = graph.abstractKind(node).maxWildernessLevel();
        for (Transport transport : config.getUsableTeleports(bankVisited)) {
            profile.transportEvaluations++;
            boolean delayedVisit = transport.getType().sharesDestinationsWith() != null;
            if (!delayedVisit && visited.get(transport.getDestination(), bankVisited)) {
                profile.visitedSkipped++;
                continue;
            }
            if (!transport.isUsableAtWildernessLevel(maxWildernessLevel)) {
                continue;
            }
            if (config.avoidWilderness(sourceTile, transport.getDestination(), targetInWilderness)) {
                continue;
            }
            int differentialCost = delayedVisit ? config.getDifferentialCost(transport) : 0;
            neighbors.add(graph.createTransport(
                transport.getDestination(), node,
                transport.getDuration(), config.getAdditionalTransportCost(transport),
                bankVisited,
                delayedVisit,
                differentialCost));
        }
        return neighbors;
    }

    private boolean updateBestPathWhenUnreachable(int node, int packedPosition) {
        boolean update = false;
        final int travelledDistance = graph.cost(node);
        for (int target : targets) {
            int remainingDistance = WorldPointUtil.distanceBetween(target, packedPosition, WorldPointUtil.EUCLIDEAN_SQUARED_DISTANCE_METRIC);
            int x = WorldPointUtil.unpackWorldX(packedPosition);
            int y = WorldPointUtil.unpackWorldY(packedPosition);
            if ((remainingDistance < bestRemainingDistance) ||
                (remainingDistance == bestRemainingDistance && travelledDistance < bestTravelledDistance) ||
                (remainingDistance == bestRemainingDistance && travelledDistance == bestTravelledDistance && x < bestX) ||
                (remainingDistance == bestRemainingDistance && travelledDistance == bestTravelledDistance && x == bestX && y < bestY)) {
                bestRemainingDistance = remainingDistance;
                bestTravelledDistance = travelledDistance;
                bestX = x;
                bestY = y;
                bestLastNode = node;
                update = true;
            }
        }
        return update;
    }

    private void updateWildernessLevel(int packedPosition) {
        int previousLevel = wildernessLevel;
        if (wildernessLevel > 0) {
            if (wildernessLevel > 30 && !WildernessChecker.isInLevel30Wilderness(packedPosition)) {
                wildernessLevel = 30;
            }
            if (wildernessLevel > 20 && !WildernessChecker.isInLevel20Wilderness(packedPosition)) {
                wildernessLevel = 20;
            }
            if (wildernessLevel > 0 && !WildernessChecker.isInWilderness(packedPosition)) {
                wildernessLevel = 0;
            }
        }
        if (wildernessLevel != previousLevel) {
            profile.wildernessLevelChanges++;
        }
    }

    public int getStart() { return start; }
    public Set<Integer> getTargets() { return targets; }
    public PathfinderProfile getProfile() { return profile; }
    public PathfinderResult getResult() { return result; }
}
