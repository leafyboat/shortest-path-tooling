# Dashboard Guide

## Purpose

The dashboard is a developer tool for inspecting pathfinder behavior visually.

It exists for cases where a pass/fail assertion is not enough:

- a route is unexpectedly unreachable
- a route reaches the target but uses the wrong transport
- a bank-state transition happens in the wrong place
- a user reports a bad destination and we want a lightweight regression case
- profiling data needs visual inspection (phase timings, heatmaps, queue sizes)

Instead of looking only at a failing JUnit assertion, the dashboard lets you inspect:

- the rendered route on a Leaflet map
- path statistics (nodes checked, transports checked, elapsed time)
- transports used along the path
- where banked state begins
- profiler phase breakdowns, sub-phase timings, and time-series charts
- tile visit heatmaps showing search distribution
- multiple datasets through one shared UI with a bundle selector

## Project Architecture

The dashboard tooling lives in its own Gradle project (`shortest-path-tooling`), separate from the plugin.
The `shortest-path` plugin is included as a [composite build](https://docs.gradle.org/current/userguide/composite_builds.html)
via `settings.gradle`, so `testImplementation 'shortestpath:shortest-path'` resolves to the local submodule.

### Key source paths

| Path | Purpose |
|---|---|
| `src/test/java/shortestpath/dashboard/` | Dashboard test runner, scenario loading, report writing |
| `src/test/java/shortestpath/pathfinder/` | `ProfilingPathfinder`, `PathfinderProfile` (test-only instrumented pathfinder) |
| `src/test/java/shortestpath/dump/` | Cache dumpers |
| `src/test/resources/dashboard/` | CSV datasets consumed by the dashboard tasks |
| `src/test/resources/reachability-dashboard/` | Static frontend assets (`index.html`, `app.js`, `profiler.js`, `styles.css`) |
| `gradle/dashboards.gradle` | Gradle task definitions for all dashboard and capture tasks |
| `gradle/cache-dumpers.gradle` | Gradle task definitions for cache dump tasks |

The `ProfilingPathfinder` and `PathfinderProfile` classes live in this tooling repo, not in the plugin.
They are compiled alongside the dashboard test code and are not shipped as part of the RuneLite plugin.

Two additional files from the plugin's test tree are pulled into compilation via a `compileTestJava.source`
override in `build.gradle`:

- `shortest-path/src/test/java/shortestpath/pathfinder/TestPathfinderConfig.java`
- `shortest-path/src/test/java/shortestpath/TestShortestPathConfig.java`

## Site Model

The dashboard site is a static bundle written to:

`build/reports/pathfinder-dashboard`

It contains one shared frontend plus multiple report bundles:

```text
build/reports/pathfinder-dashboard/
  index.html
  app.js
  profiler.js
  styles.css
  bundles/
    index.json
    <bundle-name>/
      report.json
      heatmaps/
        <run-name>.json     (tile visit counts, one per route)
```

`bundles/index.json` is the registry the frontend reads first. Each bundle directory contains a `report.json`
with all route results and, when profiling is enabled, a `heatmaps/` directory with per-route tile visit count data.

To serve the site locally after building:

```bash
python -m http.server --directory build/reports/pathfinder-dashboard 8000
# Then open: http://localhost:8000
```

## CSV Dataset Format

All dashboard datasets are CSV files under `src/test/resources/dashboard/`.
The loader (`DashboardScenarioLoader`) auto-detects format from the header row.

### Extended routes CSV (primary format)

Header row must include `start_x` and `preset` (or the legacy alias `teleports`).

```
name,category,start_x,start_y,start_plane,x,y,plane,preset,inventory,equipment,bank,varbits,varplayers,skill_levels,config_overrides,expected_length,minimum_length,expect_reachable,quests
```

| Column | Required | Description |
|---|---|---|
| `name` | yes | Human-readable route label shown in the UI |
| `category` | yes | Grouping label (e.g. `walk`, `teleport`, `fairy-ring`, `agility-shortcut`) |
| `start_x`, `start_y`, `start_plane` | yes | Start tile world coordinates |
| `x`, `y`, `plane` | yes | Target tile world coordinates |
| `preset` | yes | Named pathfinder config preset (see [Presets](#presets)) |
| `inventory` | no | `itemId:qty;itemId:qty` — items in inventory (qty defaults to 1) |
| `equipment` | no | `itemId:qty;itemId:qty` — equipped items |
| `bank` | no | `itemId:qty;itemId:qty` — items in bank |
| `varbits` | no | `id=value;id=value` — stubs `Client.getVarbitValue(id)`; varbits are bit-packed slices of varplayer state |
| `varplayers` | no | `id=value;id=value` — stubs `Client.getVarpValue(id)`, the raw varp. E.g. quest points are varp `101` (`VarPlayerID.QP`); varp `139` is `LEGENDSQUEST` progress — a common mix-up |
| `skill_levels` | no | `SKILL_NAME=level;…` (e.g. `AGILITY=70;MAGIC=55`) |
| `quests` | no | `Quest Name=STATE;…` — per-quest state overrides (see [Quests](#quests)) |
| `config_overrides` | no | `settingName=value;…` — override specific `ShortestPathConfig` settings |
| `expected_length` | no | Expected path length in tiles; fails the run if actual differs |
| `minimum_length` | no | Minimum acceptable path length; fails if actual is shorter |
| `expect_reachable` | no | `false` asserts the route is unreachable (an intentional-failure scenario); absent or `true` means expected reachable |

An empty column is equivalent to omitting it. Rows beginning with `#` are treated as comments.

#### Variable stubs and the bypass flags

`varbits` and `varplayers` stub two *different* client reads — `getVarbitValue` vs `getVarpValue` — and they interact with transport gating in a way that is easy to miss:

- Transport TSVs under `transports/` carry separate `Varbits` and `VarPlayers` requirement columns, evaluated by `varbitChecks`/`varPlayerChecks` in `PathfinderConfig`.
- The harness **bypasses both checks by default** (`bypassVarbitChecks=true`, `bypassVarPlayerChecks=true`), so a stubbed id only gates a transport when the row also sets the matching `config_overrides` key — `bypassVarbitChecks=false` or `bypassVarPlayerChecks=false`.
- Example: the digsite rows in `routing-issues.csv` pair `varbits=3637=153` / `varbits=3637=0` with `bypassVarbitChecks=false` so the kudos varbit actually opens/closes the gate transport.
- A stub whose id appears in a `Varbits`/`VarPlayers` requirement column but lacks the matching `bypass*Checks=false` is *dead for transport gating* — it may still feed direct client reads (e.g. the quest-points read of varp `101`) or destination requirements. The `scenario-var-gating` advisory check in `scripts/validate_data.py` reports exactly these rows.
- Asymmetry: `destinations/game_features/bank.tsv` requirement columns (`Skills`/`Quests`/`Varbits`/`VarPlayers`) read the client directly and are **never bypassed** — scenario stubs always feed them, no flag needed.
- Flipping a bypass flag off evaluates *every* requirement of that kind in the corpus against the stubs — unstubbed ids read Mockito's default `0`, so unrelated transports can gate off too. Re-capture `expected_length` with the `captureExpectedLengths` task after enabling a bypass on a row.

#### Quests

`quests` cells are `Quest Name=STATE;…` tokens. Names must match `Quest.getName()` exactly (spaces, apostrophes and `&` are fine; `=`/`;`/`,` never appear in quest names) and `STATE` is one of `NOT_STARTED`, `IN_PROGRESS`, `FINISHED`. The column defaults to all-quests-`FINISHED`, matching the historical harness behavior — overrides only ever narrow state, and only `FINISHED` satisfies a quest requirement. Unknown names, unknown states, and `=`-less tokens abort the dataset run with `IllegalArgumentException`.

Quest states gate more than individual transports: `The Grand Tree=NOT_STARTED` disables the whole gnome-glider transport type, `Bone Voyage` gates all magic mushtrees, and `Tree Gnome Village` gates all spirit trees — plus quest requirements on `bank.tsv` destinations.

#### Presets

The `preset` column maps to a named `DashboardPresets` entry that configures the `PathfinderConfig`:

| Preset | Description |
|---|---|
| `NONE` | No teleportation items |
| `ALL` | All teleportation items enabled |
| `BANK` | All teleportation items (bank mode, diary stub = not complete) |
| `BANK_PERM` | Bank mode with non-consumable teleportation items (diary stub = not complete) |
| `INVENTORY` | Inventory teleportation items only |
| `INVENTORY_NON_CONSUMABLE` | Non-consumable inventory items only |
| `SEASONAL` | Seasonal world — inventory items + seasonal transports, avoids wilderness |
| `UNIT_TEST` | Exact Mockito defaults — reproduces `PathfinderTest` config precisely |

Preset names are case-insensitive. The old column name `teleports` is accepted as an alias for `preset`.

`config_overrides` can further override individual settings on top of a preset, e.g.:
```
useAgilityShortcuts=true;useTeleportationItems=INVENTORY_AND_BANK;includeBankPath=true
```

### Clue-step CSV format

Has `clue_type`, `x`, `y`, `plane` columns. No start coordinate — the pathfinder sweeps from a default
start position. Used by `clue_locations_full.csv`.

### TSV format

Tab-separated with `Description`, `X`, `Y`, `Plane` columns. Legacy format.

### Available datasets

| File | Description |
|---|---|
| `routes.csv` | General-purpose routes: walks, teleports, various transport types |
| `unit-tests.csv` | Regression cases for specific logic (bank branching, gating, wilderness, spells) |
| `quetzal_whistle_routes.csv` | Quetzal whistle and primo-quetzal transport routes |
| `clue_locations_full.csv` | Full clue-step reachability corpus (clue-step format) |

## Gradle Tasks

### `dashboard` (default task)

Runs `DashboardTest` against one dataset and writes a bundle into the output site.
Profiling is **on** by default.

```bash
./gradlew dashboard \
  -PdashboardDataset=/dashboard/routes.csv \
  -PdashboardBundle=routes-profiled \
  -PdashboardTitle="Routes Profiled"
```

| Property | Default | Description |
|---|---|---|
| `dashboardDataset` | `/dashboard/routes.csv` | Classpath resource path for the input CSV |
| `dashboardBundle` | auto-derived from dataset filename + profile flag | Bundle directory name |
| `dashboardTitle` | auto-derived from dataset stem | Title shown in the UI |
| `dashboardSubtitle` | *(empty)* | Subtitle shown in the UI |
| `dashboardProfile` | `true` | Enable profiling (heatmaps, phase timings) |

The bundle name and title are auto-derived when not overridden. For example:
- dataset `/dashboard/clue_locations_full.csv`, profiling on → bundle `clue-locations-full-profiled`, title `Clue Locations Full Profiled`
- dataset `/dashboard/routes.csv`, profiling off → bundle `routes`, title `Routes`

To build without profiling (faster, no heatmaps):

```bash
./gradlew dashboard -PdashboardProfile=false -PdashboardDataset=/dashboard/clue_locations_full.csv
```

### `captureExpectedLengths`

Like `dashboard` but writes actual path lengths back into the source CSV's `expected_length` column.
Use this to bootstrap or refresh expected lengths after changing pathfinding behavior.

```bash
./gradlew captureExpectedLengths -PdashboardDataset=/dashboard/routes.csv
```

## Profiler

### Architecture

The profiler is a test-only pathfinder that mirrors the production search loop with `System.nanoTime()`
instrumentation at each phase boundary.

Key files:

- `src/test/java/shortestpath/pathfinder/ProfilingPathfinder.java` — mirrors `Pathfinder.java`'s loop, each phase wrapped in nanoTime calls
- `src/test/java/shortestpath/pathfinder/PathfinderProfile.java` — data class collecting all profiling measurements
- `src/test/resources/reachability-dashboard/profiler.js` — frontend rendering (bar charts, time-series, heatmap overlay)

### What It Measures

**Top-level phases** (accumulated nanos per search loop iteration):
- `queueSelection` — dequeuing the next node from the priority queue
- `addNeighbors` — expanding tile and transport neighbors
- `targetCheck` — checking if the current node is a target
- `wildernessCheck` — wilderness level boundary handling
- `cutoffCheck` — cost cutoff evaluation
- `bookkeeping` — iteration counter updates and sampling

**Sub-phases within addNeighbors**:
- `bankCheck` — checking bank proximity for state transitions
- `transportLookup` — looking up transports at the current position
- `collisionCheck` — collision map queries
- `walkableTile` — processing walkable tile neighbors
- `blockedTileTransport` — checking transports on blocked tiles
- `abstractNode` — abstract node expansion

**Counters**:
- `tileNeighborsAdded`, `transportNeighborsAdded` — neighbor counts
- `visitedSkipped` — nodes skipped because already visited
- `transportEvaluations` — total transport evaluations attempted
- `blockedTileTransportChecks` — blocked-tile transport lookups
- `bankTransitions`, `wildernessLevelChanges` — state change counts
- `peakBoundarySize`, `peakPendingSize` — high-water marks for queue sizes

**Time series** (sampled every 2000 iterations):
- boundary queue size, pending queue size, current cost, elapsed nanos

**Heatmap**:
- sparse map of `packedPosition → visitCount` for every tile visited during search

### Keeping the Profiler in Sync

`PathfinderTest.profilingDoesNotAffectResults` verifies that `ProfilingPathfinder` produces identical
results to `Pathfinder` across multiple route types. It checks:

- path equality (every step's position and bank-visited flag)
- result metadata (reached, terminationReason, nodesChecked, transportsChecked)
- profiling data validity (phase nanos > 0, sub-phase nanos > 0, counter consistency, heatmap non-empty)

When modifying `Pathfinder.java`'s search loop, run this test to confirm the profiler still mirrors it.

## How To Choose A Dataset

| Situation | Dataset / task |
|---|---|
| "This destination should be reachable" | Add a row to `routes.csv` or `unit-tests.csv`, run `dashboard` |
| Route quality, bank usage, transport choice, exact config control | `unit-tests.csv` with `UNIT_TEST` preset and `config_overrides` |
| Broad sweep after changing core search behavior | `clue_locations_full.csv`, profiling off |
| Quetzal whistle transport regressions | `quetzal_whistle_routes.csv` |
| Performance analysis (where time is spent, queue sizes) | Any dataset with `dashboardProfile=true` |
| Bootstrap or refresh expected path lengths | `captureExpectedLengths` task |

## Suggested Workflow

**For a reported unreachable destination:**

1. Add a row to `src/test/resources/dashboard/routes.csv` with the start and target coordinates.
2. Run `./gradlew dashboard -PdashboardDataset=/dashboard/routes.csv`.
3. Serve the output and inspect the route in the dashboard.
4. If the route should be verified on every run, add an `expected_length` value (use `captureExpectedLengths`).

**For a routing regression (bank logic, transport gating, etc.):**

1. Add a row to `src/test/resources/dashboard/unit-tests.csv` with `preset=UNIT_TEST` and precise `config_overrides`.
2. Set `expected_length` (and optionally `minimum_length`) for the assertion.
3. Run `./gradlew dashboard -PdashboardDataset=/dashboard/unit-tests.csv`.
4. Inspect the route and confirm the path length assertion matches the intended behavior.

**For a performance investigation:**

1. Run `./gradlew dashboard` (profiling on by default).
2. Inspect phase timings, sub-phase breakdowns, and heatmaps in the profiler tab.

For profiling after a pathfinder change:

1. Run profiled bundles for the datasets you care about.
2. Compare heatmaps and phase breakdowns against the pre-change baselines.
3. Use `scripts/analyze_heatmap_counts.py` to get distribution statistics.

## Serving The Site

Open the generated site through a local webserver, not `file://`, because the frontend loads bundle JSON dynamically.

```bash
python -m http.server --directory build/reports/pathfinder-dashboard 8000
```

Then open `http://localhost:8000`.

## Scripts

Helper scripts in `scripts/`:

| Script | Purpose |
|---|---|
| `analyze_heatmap_counts.py` | Analyze tile visit count distributions from heatmap JSON files in a bundle |
| `check_tsv.py` | Validate TSV transport files for format errors |
| `tsv-lint.sh` | Lint all transport TSV files |
| `compute_changed_coordinates.sh` | Diff coordinate changes between branches |
| `diff_coordinate_json.py` | Compare coordinate JSON files |
| `dump_transport_coordinates.py` | Extract transport coordinates from TSV files |
| `gen_clue_csv.py` | Generate the clue locations CSV from source data |

## Implementation Split

The implementation is split into:

1. `PathfinderDashboardReportWriter` — builds the report payload (route results, profiler data, metadata)
2. `DashboardBundlePublisher` — publishes a named bundle into the shared site root and updates `bundles/index.json`
3. `PathfinderDashboardAssetWriter` — writes the shared frontend assets (`index.html`, `app.js`, `profiler.js`, `styles.css`)
