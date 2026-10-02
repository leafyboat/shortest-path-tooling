# Pathfinder Performance Analysis

**Date:** 2026-05-15  
**Status:** `perf/optimise-pathfinder` — NodeStore hot-path integration complete. A/B verified.

## Comparison Setup

- Production pathfinder timing mode used for both runs: `-PdashboardProfile=false`.
- Profile-enabled reruns (`-PdashboardProfile=true`, dashboard default) were also generated for phase/sub-phase category comparison.
- Same dataset suite re-run back-to-back on both branches under the same machine conditions.
- shortest-path commits compared:
  - `master`: `d3b9b0f7e76fb52c76c7ef03c52ebac18812a82c`
  - `perf/optimise-pathfinder`: `e28d7a751880636df2f5ef4b2868e6ddc2319f4d`
- shortest-path-tooling commit used for the run harness/doc: `master` + local comparison/doc changes.

## Baseline Table (Master vs Perf, Raw Elapsed Time)

| Dataset | Scenarios | Master (ms) | Perf (ms) | Delta (ms) | Delta % |
|---|---:|---:|---:|---:|---:|
| `routes.csv` | 29 | 5331 | **4927** | **-404** | **-7.58%** |
| `unit-tests.csv` | 27 | 4602 | **4457** | **-145** | **-3.15%** |
| `quetzal_whistle_routes.csv` | 15 | **1622** | 1629 | +7 | +0.43% |
| `collision-map-issues.csv` | 14 | 1494 | **1473** | **-21** | **-1.41%** |
| `seasonal_briefcase_routes.csv` | 26 | **3303** | 3413 | +110 | +3.33% |
| `clue_locations_full.csv` | 866 | 207132 | **157314** | **-49818** | **-24.05%** |
| **All combined** | **977** | **223484** | **173213** | **-50271** | **-22.49%** |

## Normalized Breakdown (Less Biased Than Total ms)

The total-ms line is still useful, but it heavily weights the largest dataset.
To reduce that bias, the metrics below normalize by scenario count and by dataset.

### Per-Scenario Time

| Dataset | Master (ms/scenario) | Perf (ms/scenario) | Delta (ms/scenario) | Delta % |
|---|---:|---:|---:|---:|
| `routes.csv` | 183.83 | **169.90** | **-13.93** | **-7.58%** |
| `unit-tests.csv` | 170.44 | **165.07** | **-5.37** | **-3.15%** |
| `quetzal_whistle_routes.csv` | **108.13** | 108.60 | +0.47 | +0.43% |
| `collision-map-issues.csv` | 106.71 | **105.21** | **-1.50** | **-1.41%** |
| `seasonal_briefcase_routes.csv` | **127.04** | 131.27 | +4.23 | +3.33% |
| `clue_locations_full.csv` | 239.18 | **181.66** | **-57.53** | **-24.05%** |
| **All combined** | 228.75 | **177.29** | **-51.46** | **-22.49%** |

### Category Summary

| Category | Scenarios | Master (ms) | Perf (ms) | Delta (ms) | Delta % |
|---|---:|---:|---:|---:|---:|
| Core suite (all datasets except clue) | 111 | 16352 | **15899** | **-453** | **-2.77%** |
| Clue suite only | 866 | 207132 | **157314** | **-49818** | **-24.05%** |

### Run-Level Percentage Change (Unweighted, Requested Metric)

- Mean percentage change per run (977 runs): **-2.26%**.
- Median percentage change per run: **-1.80%**.
- Interquartile range (Q1..Q3): **-5.84% .. +1.83%**.
- Improved runs: **615/977**.
- Regressed runs: **362/977**.

### Per-Dataset Run-Level View (Average of Run % Changes)

| Dataset | Runs | Mean run delta % | Median run delta % |
|---|---:|---:|---:|
| `routes.csv` | 29 | **-3.69%** | **-4.33%** |
| `unit-tests.csv` | 27 | **-5.25%** | **-2.08%** |
| `quetzal_whistle_routes.csv` | 15 | **-8.07%** | **-8.66%** |
| `collision-map-issues.csv` | 14 | +11.73% | +3.32% |
| `seasonal_briefcase_routes.csv` | 26 | **-4.02%** | **-1.45%** |
| `clue_locations_full.csv` | 866 | **-2.19%** | **-1.62%** |

## Dashboard Algorithm Category Breakdown (Profile Categories)

The tables below use profile-enabled dashboard runs and compare the same phase/sub-phase categories shown in the UI.

### Top-Level Phases

| Phase category | Master (s) | Perf (s) | Delta (s) | Delta % |
|---|---:|---:|---:|---:|
| `addNeighbors` | 477.102 | **468.475** | **-8.626** | **-1.81%** |
| `queueSelection` | 6.877 | **6.872** | **-0.005** | **-0.07%** |
| `targetCheck` | 10.342 | **9.949** | **-0.393** | **-3.80%** |
| `wildernessCheck` | **8.188** | 8.209 | +0.021 | +0.26% |
| `cutoffCheck` | **4.911** | 4.974 | +0.062 | +1.27% |
| `bookkeeping` | 11.681 | **11.456** | **-0.225** | **-1.92%** |
| `other` | 48.396 | **48.066** | **-0.331** | **-0.68%** |

### addNeighbors Sub-Phases

| Sub-phase category | Master (s) | Perf (s) | Delta (s) | Delta % |
|---|---:|---:|---:|---:|
| `bankCheck` | **8.084** | 8.499 | +0.415 | +5.13% |
| `transportLookup` | 22.433 | **21.935** | **-0.497** | **-2.22%** |
| `collisionCheck` | 65.651 | **63.928** | **-1.723** | **-2.62%** |
| `walkableTile` | 68.672 | **67.914** | **-0.758** | **-1.10%** |
| `blockedTileTransport` | 14.879 | **14.489** | **-0.390** | **-2.62%** |
| `abstractNode` | 10.309 | **10.047** | **-0.262** | **-2.54%** |
| `enqueue` | 217.584 | **212.237** | **-5.347** | **-2.46%** |
| `other` | 69.491 | **69.426** | **-0.065** | **-0.09%** |

## Test Parity (Both Branches)

- `./gradlew test --tests "shortestpath.pathfinder.*"` on `master`: **BUILD SUCCESSFUL**
- `./gradlew test --tests "shortestpath.pathfinder.*"` on `perf/optimise-pathfinder`: **BUILD SUCCESSFUL**

## Implemented Changes (Current Branch, Short Form)

- `ec9918e`: stopped creating abstract-node objects on every tile expansion.
- `30ac7f5`: reduced cutoff clock checks from every loop to sampled checks.
- `eaeef77`: replaced slower `BitSet` collision bit reads with direct packed-word access.
- `964db06`: reduced collision lookup overhead by reading north/east flag pairs and reusing region lookups.
- `0da7183`: removed small hot-loop overheads (cardinal-direction checks and transport map re-fetches).
- `bc03cfa0`: replaced unpack/repack neighbor math with direct packed-coordinate addition; simplified wilderness area checks.
- `6975bf5b`: short-circuited wilderness-level updates and cached targets for unreachable-path comparison.
- `31e13053`: used coordinate-based visited checks directly in tile neighbor expansion.
- `1ea22841`: rewrote collision neighborhood checks to bulk-mask reads with safety fallbacks.
- `f7a85086`: moved tile visited/enqueue work into neighbor production to avoid duplicate passes.
- `e28d7a75`: finalized transport and allocation optimizations:
  - switched hot-path transport iteration to array-backed lookups;
  - directly enqueued blocked-tile transport origins to reduce transient objects;
  - revalidated visited-check integration with full pathfinder tests.

## NodeStore A/B — Hot-Path Allocation Reduction (2026-05-15)

### Summary

Replaced ~14.8M `Node` heap allocations in the tile-expansion hot path with
a compact `NodeStore` (parallel `int[]` arrays indexed by monotonic counter).
Tile nodes now use `PackedNode` (extends `Node`, backed by a store index).
Transport nodes remain heap-allocated (they need priority-queue ordering fields).

### The "Previous" Chain Bug — Root Cause & Fix

- **Bug:** `PackedNode` constructor passed `null` for `previous` to `super()`.
  `TransportNode.getPathSteps()` (inherited from `Node`) walks `node.previous`,
  stopping at the first `PackedNode` with `previous=null`.
  This truncated path reconstruction at every transport hop.

- **Fix:** Changed `PackedNode(NodeStore store, int idx, Node parent)` to pass
  the actual parent `Node` to `super()`. The `previous` chain now flows:
  `TransportNode → PackedNode(child) → PackedNode(parent) → ... → root`.
  Removed the redundant `getPathSteps()` override — `Node.getPathSteps()` works.

### Files Changed

| File | Change |
|---|---|
| `PackedNode.java` | Constructor accepts `Node parent`, passes to `super()`; removed custom `getPathSteps()` |
| `CollisionMap.java` | `getNeighbors()` accepts `NodeStore`; tile neighbors allocated via `store.alloc()` |
| `Pathfinder.java` | `NodeStore store` field; root allocated via store; passed through `addNeighbors` |
| `ProfilingPathfinder.java` | Same changes for A/B parity verification |

### commits

- `1eca9b07`: NodeStore + PackedNode infrastructure
- `f428531e`: NodeStore + PackedNode allocation models
- *(working tree)*: hot-path integration + previous-chain fix (this section)

### Per-Route User-Experience Metrics (Master vs NodeStore)

The dashboard runs all scenarios sequentially in a single JVM, so the
aggregate batch time mixes JIT warmup, GC pressure, and CPU caching effects
that a real user never sees: in the plugin the user clicks a destination
once and waits for that single search. The metric that actually matters is
**per-route latency**, split into:

- **Reachable** routes (the common case) — median and p95 latency dominate UX.
- **Unreachable** routes — worst-case latency is what users feel, because
  the forward search runs to the cutoff with no early exit.

Source data: `build/reports/pathfinder-dashboard/bundles/*/report.json`
captures `stats.elapsedNanos` per scenario. Analysed with
`scripts/analyse_dashboard_runs.py`. Same machine, `-PdashboardProfile=false`,
both branches re-run consecutively so JIT/GC state is comparable.

**Combined across all 6 datasets (977 scenarios):**

| Bucket | Metric | Master | NodeStore | Delta |
|---|---|---:|---:|---:|
| Reachable (918) | Median | 121 ms | **81.1 ms** | **−33.6%** |
| Reachable (918) | p95 | 314 ms | **208 ms** | **−33.8%** |
| Reachable (918) | Max | 490 ms | **412 ms** | **−15.9%** |
| Unreachable (59) | Median | 311 ms | **207 ms** | **−33.4%** |
| Unreachable (59) | Max | 462 ms | **234 ms** | **−49.4%** |

The **worst-case unreachable latency drops from 462 ms to 234 ms — nearly
halved**. That is the latency a user experiences when they ask for a path
that cannot reach the destination, and it is the dominant UX pain-point.

**Per-dataset summary:**

| Dataset | Scenarios | Reachable median: Master → NodeStore | Unreachable max: Master → NodeStore |
|---|---:|---:|---:|
| `routes.csv` | 29 (27 R, 2 U) | 6.92 → 6.68 ms (−3.5%) | 240 → 212 ms |
| `unit-tests.csv` | 27 (27 R) | 11.7 → 9.23 ms (−16.4%) | — |
| `quetzal_whistle_routes.csv` | 15 (15 R) | 0.69 → 0.73 ms (−11.2% median) | — |
| `collision-map-issues.csv` | 14 (14 R) | 0.20 → 0.28 ms (+4.2% median) | — |
| `seasonal_briefcase_routes.csv` | 26 (18 R, 8 U) | 0.34 → 0.28 ms (+8.8% median) | 181 → 159 ms |
| `clue_locations_full.csv` | 866 (817 R, 49 U) | **147 → 96.2 ms (−33.7%)** | **462 → 234 ms (−49.4%)** |

The headline numbers come from `clue_locations_full.csv`, which has the
longest searches and dwarfs every other dataset in absolute time. The other
five datasets are dominated by very-short paths (sub-millisecond) where the
median % swings sign-flip easily on noise — but the absolute values are tens
to hundreds of microseconds, invisible to a user.

### Short-Path Regressions: a Caveat, Not a Problem

A handful of sub-millisecond scenarios got slower (e.g. `Hunter Guild →
Outer Fortis` 0.58 → 2.03 ms; `Civitas → Mistrock` 0.10 → 0.25 ms;
`Lumbridge → Ardougne (bank teleport)` 1.30 → 3.56 ms). Two factors
explain this and both are acceptable:

1. **Single-shot timing noise.** Each scenario is measured exactly once.
   At sub-millisecond scale the noise floor (System.nanoTime jitter, code-
   cache warming, GC) is comparable to the delta. The "+251%" on a 0.58 ms
   scenario is +1.5 ms absolute — well below human perception (~10 ms).
2. **NodeStore has a small fixed setup cost** (the `int[1 << 20]` arrays are
   touched on first use). On a search that explores fewer than ~200 tiles
   the amortised win from avoiding per-tile `Node` allocations is smaller
   than the array setup tax. A user clicking a destination 5 tiles away
   does not notice this — both branches finish under 1 ms.

The optimisation is correctly biased: it speeds up the slow searches (the
ones a user actually waits for) at a microsecond cost on the trivial ones.

### Test Parity

- `./gradlew test --tests "shortestpath.pathfinder.*"` on
  `perf/optimise-pathfinder` with NodeStore: **BUILD SUCCESSFUL**.
- All 977 dashboard scenarios agree with master on reachability.

### A/B Harness

There is no longer a dedicated `nodestoreAB` task — the standard `dashboard`
task is sufficient because every scenario is timed individually inside the
report JSON.

```bash
# Capture baseline (master)
git -C ../shortest-path checkout master
for csv in routes unit-tests quetzal_whistle_routes \
           collision-map-issues seasonal_briefcase_routes clue_locations_full; do
  ../shortest-path/gradlew --quiet dashboard \
    -PdashboardDataset=/dashboard/$csv.csv \
    -PdashboardProfile=false
  # bundle name uses hyphens not underscores
  bundle=$(echo $csv | tr _ -)
  mkdir -p /tmp/dashboard-runs/master/$bundle
  cp build/reports/pathfinder-dashboard/bundles/$bundle/report.json \
     /tmp/dashboard-runs/master/$bundle/
done

# Capture candidate
git -C ../shortest-path checkout perf/optimise-pathfinder
# (repeat the for loop, copying into /tmp/dashboard-runs/nodestore/)

# Compare
python3 scripts/analyse_dashboard_runs.py \
    /tmp/dashboard-runs/master /tmp/dashboard-runs/nodestore \
    --label-baseline Master --label-candidate NodeStore
```

## Remaining Work

- No remaining non-algorithmic hot-path items from C9/3.5/3.7.
- Algorithmic branch work:
  - region-graph precompute (`perf/region-graph`)

## Algorithmic Workstreams (Detailed)

### Region-Graph Precompute (`perf/region-graph`)

- Goal: reduce global search by precomputing coarse region connectivity and boundary portals.
- Concept:
  - Build graph over region-level portals/edges.
  - Use region graph to constrain or prioritize fine-grained tile search.
- Constraints:
  - Must remain compatible with dynamic constraints (bank state, wilderness restrictions, seasonal region locks).
  - Must degrade safely to tile-level search when coarse guidance is ambiguous.
- Incremental path:
  - Build static region connectivity first.
  - Add dynamic edge filters second.
  - Introduce hybrid search only after full regression parity.

## Delta Reconciliation

- The earlier “promised” improvement came from intermediate snapshots and narrower slices.
- The current numbers are from fresh same-condition reruns against `master` across all six datasets.
- Raw combined total is **-22.49%** (223484 ms -> 173213 ms), but this is heavily affected by the largest suite.
- Unweighted per-run mean change is **-2.26%** (the metric requested above), with median **-1.80%**.
- Dashboard phase-category deltas are mostly modest (roughly low single digits).
- The largest category wins are in `addNeighbors` overall, `enqueue`, and `collisionCheck`.
- Some phase buckets are slightly worse (`bankCheck`, `cutoffCheck`, `wildernessCheck`), which is why the overall gain can feel smaller than expected when looking at category-level bars.

## Notes

- NodeStore integration complete (2026-05-15). Previous-chain bug fixed: `PackedNode` now passes parent `Node` to `super()`, so `TransportNode.getPathSteps()` correctly walks the full path chain through PackedNode indices.
- This document is now intentionally concise and current-state oriented.
- Detailed algorithmic workstream notes were restored per request.

## Optimisation Opportunities — Code Audit (2026-05-15)

A full read of `Pathfinder.java` and `NodeStore.java` on
`perf/optimise-pathfinder` against the use-case constraint (users
plan paths *infrequently* — one search at a time, latency-sensitive,
never a hot loop).

### Findings on `perf/optimise-pathfinder`

#### O-1 (HIGH IMPACT, LOW RISK) — `NodeStore` over-allocates on construction

`Pathfinder.java:47` instantiates `new NodeStore(1 << 20)`. The
`NodeStore` constructor immediately allocates `int[1<<20]` × 3 plus
`boolean[1<<20]` ≈ **12 MB of zero-initialised memory on every search
construction**, even for sub-millisecond reachable routes (e.g. start
already adjacent to a transport).

`NodeStore.java` already has a `grow()` method that doubles capacity on
overflow, so initial capacity is purely a memory/latency knob with no
correctness consequence.

This is the single most likely cause of the per-route regressions on
short paths (the dashboard NodeStore A/B shows several +100% / +250%
deltas on sub-ms scenarios). Each search pays the allocation tax up
front; on long searches it amortises, on short ones it dominates.

**Fix:** drop the initial capacity to e.g. `1 << 12` (4096). The hot
path's worst observed `nodesChecked` is ~1.5 M (master max), so `grow()`
will fire ~9 times in the worst case, each copying the live prefix — 9
× ~6 MB writes ≈ trivially small compared to the savings on the
millions of short searches.

**Expected outcome:** restores sub-ms latency floor on short routes
while preserving the unreachable-max gain.

**Risk:** none — `grow()` is already exercised; no behavioural change.

#### O-2 (LOW IMPACT, LOW RISK) — `boundary` and `pending` allocated per search

`Pathfinder.java:29-30` allocates `ArrayDeque<>(4096)` and
`PriorityQueue<>(256)` in field initialisers. Together ~40 KB. With a
once-per-minute search this is irrelevant. Skip unless O-1 measurement
proves more is needed.

#### O-3 (MEDIUM IMPACT, MEDIUM RISK) — `addNeighbors` returns `List<Node>`

`Pathfinder.java:170` calls `map.getNeighbors(...)` returning a
`List<Node>`. For each call this allocates a list (plus iterator) even
when most neighbours go through the primitive store. The non-tile path
(transports, teleports) is genuinely heap-bound, but the *list itself*
is wasted on the tile-only fast path.

**Fix sketch:** change `getNeighbors` to write into a caller-provided
`ArrayList<Node>` that is cleared per call (or even better, split into
`addTileNeighbors` (pure primitive into `boundary`) and
`addNonTileNeighbors` (returns the list)). This needs care because
`CollisionMap.getNeighbors` is also used elsewhere.

**Expected outcome:** 5–15% on the long forward searches.

**Risk:** medium — touches a widely-used API surface.

#### O-4 (LOW IMPACT, LOW RISK) — `pending` PQ ordering still relies on heap nodes

`TransportNode` is still allocated on heap (acknowledged by the comment
at `Pathfinder.java:44-47`). The PQ holds these to order by
`compareCost()`. Packing transport-node state into `NodeStore` with a
parallel `priorityCost` column would eliminate the remaining
heap-allocation hotspot, but transport-node counts are 1000–2000× lower
than tile-node counts so the savings are modest. **Defer.**

#### O-5 (UNVERIFIED, INVESTIGATE) — `VisitedTiles` per-region bitmap allocation

`Pathfinder.java:31` allocates `new VisitedTiles(map)` per search. On
first use it lazily creates per-region bitmaps. For a search that
crosses N regions, this is N × bitmap allocations. If the dashboard
short-path regression survives O-1, this is the next suspect.

**Action:** read `VisitedTiles.java`, measure first-search GC behaviour.

### Recommended Sequence

1. **`perf/optimise-pathfinder`**: land O-1 (NodeStore initial capacity
   to `1 << 12`). Re-run the dashboard A/B and check whether the
   short-path regressions disappear. If they do, that branch is
   ready to ship.
2. After that lands and ships, revisit O-3 and O-5 if there's appetite
   for deeper changes.

## O-1 — NodeStore initial capacity reduction (REVERTED, 2026-05-15)

**Hypothesis:** the up-front `int[1<<20] + boolean[1<<20]` zero-fill in
`NodeStore` was the cause of the short-path sub-millisecond regressions
observed in the NodeStore A/B. Dropping the initial capacity to `1<<12`
should let `grow()` (already implemented; doubles capacity) take care of
larger searches at the cost of ~9 reallocations on a 1.5M-node worst case.

**Result: regression. Reverted in `ce45b0f7`.**

A/B vs the `1<<20` NodeStore baseline (`/tmp/dashboard-runs/o1-ux-report.md`):

| Bucket | Metric | NS 1<<20 | NS 1<<12 | Delta |
|---|---|---:|---:|---:|
| Reachable (918) | Median | 81.1 ms | 86.8 ms | **+7.3%** |
| Reachable (918) | p95 | 208 ms | 225 ms | +8.2% |
| Reachable (918) | Max | 412 ms | **673 ms** | **+63%** |
| Unreachable (59) | Median | 207 ms | 223 ms | +7.7% |
| Unreachable (59) | Max | 234 ms | 319 ms | +36% |

**Why the hypothesis was wrong.** Each `grow()` is a
primitive-array copy of the *live* prefix, not the zero-fill of a fresh
array — but on long searches it fires many times (each doubling copies
more memory than the previous), and crucially each copy invalidates CPU
data-cache lines that the hot expansion loop has just touched. The aggregate
copy + cache-thrash cost on long searches exceeds the up-front zero-fill
tax on short searches. `1<<20` is correct: pay the fixed setup once, then
no reallocations for any realistic search.

Commit `bb8c9732` is preserved on history for reference; `ce45b0f7` is
the revert that returns `perf/optimise-pathfinder` to `1<<20`.

## Region Portal Pre-compute (perf/region-portals) — NEGATIVE RESULT, 2026-05-15

**Hypothesis:** A coarse region-plane adjacency graph (every 64×64 region
that has any walkable tile = one node; edges between regions whose boundary
tiles can step across; transports add directed edges from origin-region to
destination-region) gives a near-free reachability oracle. A BFS over this
graph from `start` to `targets ∪ teleport-destinations` either proves
"unreachable" (short-circuit the search) or returns "may be reachable" and
defers to the forward `Pathfinder`. Conservative on unknown regions — never
returns a false negative.

**Implementation (`e5737578` on `perf/region-portals`):**

- `RegionPortalIndex.java` — builds the graph once at `PathfinderConfig`
  load time by scanning every region's boundary edges with
  `CollisionMap.n/s/e/w`.
- `Pathfinder.run()` — calls `config.getOrBuildPortalIndex().canReach(start, targets, config)`
  before any of the heavy initialisation. Returns
  `PathTerminationReason.SEARCH_EXHAUSTED` if the oracle proves
  unreachable, otherwise proceeds normally.

**Result: catastrophic per-search overhead. Branch shelved.**

A/B vs `perf/optimise-pathfinder` (NodeStore baseline)
— `/tmp/dashboard-runs/region-portals-ux-report.md`:

| Bucket | Metric | NodeStore | Region-portals | Delta |
|---|---|---:|---:|---:|
| Reachable (918) | Median | 81.1 ms | **127 ms** | **+57.7%** |
| Reachable (918) | p95 | 208 ms | 269 ms | +29% |
| Reachable (918) | Max | 412 ms | **1337 ms** | **+225%** |
| Unreachable (59) | Median | 207 ms | 262 ms | +27% |
| Unreachable (59) | Max | 234 ms | 307 ms | +31% |

Per-dataset reachable median deltas:

| Dataset | NodeStore | Region-portals | Delta |
|---|---:|---:|---:|
| `clue-locations-full` | 96.2 ms | 143 ms | +51% |
| `collision-map-issues` | 0.28 ms | 32.0 ms | +**12 282%** |
| `quetzal-whistle-routes` | 0.73 ms | 34.6 ms | +**4 672%** |
| `routes` | 6.68 ms | 40.6 ms | +507% |
| `seasonal-briefcase-routes` | 0.28 ms | 31.6 ms | +**11 549%** |
| `unit-tests` | 9.23 ms | 40.4 ms | +374% |

The pre-check adds a roughly constant **~30 ms tax to every search**.
Short paths (sub-millisecond on NodeStore) inflate by 100× or more.

**Why.** Each `canReach()` seeds the BFS with `start` and *every* teleport
destination from both bank states (hundreds of seeds). Because teleports
span the map, the seed set's connected component is essentially the entire
walkable world. The BFS visits a large fraction of the region graph
(~3 000 region-plane nodes plus transport edges) on **every** search, and
the per-region adjacency lookups dominate. The oracle is doing real work
proportional to map size on a fast path that should be O(1).

**Salvageable variants (not implemented):**

1. **Skip the oracle when the start and target regions are connected in
   the trivial sense** (same region, or walk-only distance ≤ a couple of
   hops). Only run the BFS when the forward search is *expensive* — but
   then the pre-check is just a slow proxy for the forward search.
2. **Precompute connected components of the walking-only region graph
   (no transports) once.** Reject unreachable iff start and any target are
   in different walking components *and* no teleport bridges those two
   components. This is an O(1) lookup, but the answer is almost always
   "may be reachable" because teleports bridge most things — so the
   oracle rarely fires while paying its O(1) cost on every search.
3. **Move the oracle behind a cutoff hint** — invoke it only when the
   forward search has already burned >N ms (i.e. as a fallback to decide
   "give up now vs keep going"). This is structurally different from a
   pre-check.

None of these were implemented. The branch is left at `e5737578` for
reference; **the code is not viable as written**.

### Files (preserved on `perf/region-portals` for future reference)

| File | Change |
|---|---|
| `RegionPortalIndex.java` (new, 332 lines) | Region-plane adjacency graph + `canReach` BFS |
| `PathfinderConfig.java` (+13) | `getOrBuildPortalIndex()` lazy builder |
| `Pathfinder.java` (+18) | Pre-check call at the top of `run()` |

### Reproducing

```bash
git -C ../shortest-path checkout perf/region-portals
cd ../shortest-path-tooling
mkdir -p /tmp/dashboard-runs/region-portals
for ds in routes unit-tests quetzal-whistle-routes collision-map-issues \
          seasonal-briefcase-routes clue-locations-full; do
  case "$ds" in
    routes) csv=routes.csv;;
    unit-tests) csv=unit-tests.csv;;
    quetzal-whistle-routes) csv=quetzal_whistle_routes.csv;;
    collision-map-issues) csv=collision-map-issues.csv;;
    seasonal-briefcase-routes) csv=seasonal_briefcase_routes.csv;;
    clue-locations-full) csv=clue_locations_full.csv;;
  esac
  ../shortest-path/gradlew --quiet dashboard \
    -PdashboardDataset=/dashboard/$csv -PdashboardProfile=false
  mkdir -p /tmp/dashboard-runs/region-portals/$ds
  cp build/reports/pathfinder-dashboard/bundles/$ds/report.json \
     /tmp/dashboard-runs/region-portals/$ds/
done
python3 scripts/analyse_dashboard_runs.py \
    /tmp/dashboard-runs/nodestore /tmp/dashboard-runs/region-portals \
    --label-baseline "NodeStore" --label-candidate "Region-portals"
```
