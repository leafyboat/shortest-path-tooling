# shortest-path-tooling

Developer dashboards and OSRS cache dumpers for the [shortest-path](https://github.com/Skretzo/shortest-path) RuneLite plugin.

This repo carries `shortest-path` as a git submodule and uses a Gradle [composite build](https://docs.gradle.org/current/userguide/composite_builds.html) to consume the plugin's sources — so the dashboard and cache-dumper code never pollutes plugin PRs.

## Quick start

```bash
git clone --recurse-submodules https://github.com/osrs-pathfinding/shortest-path-tooling.git
cd shortest-path-tooling
./gradlew dashboard
python -m http.server --directory build/reports/pathfinder-dashboard 8000
```

Then open `http://localhost:8000`.

The published dashboard is also available on GitHub Pages:

`https://skretzo.github.io/shortest-path/`

## Available tasks

| Task | Description |
|------|-------------|
| `./gradlew dashboard` | Build the pathfinder dashboard from the default routes dataset and the sailing routes |
| `./gradlew dashboard -PdashboardDataset=/dashboard/clue_locations_full.csv` | Build dashboard from a specific dataset (only that one) |
| `./gradlew sailingDashboard -PpluginDir=../shortest-path` | Build only the sailing routes, with the experimental sailing search drawn next to the normal path (needs a plugin checkout with the sailing search) |
| `./gradlew captureExpectedLengths` | Write actual path lengths back into the source CSV as `expected_length` |
| `./gradlew sailingBenchmark -PpluginDir=../shortest-path` | Time the experimental sailing search against the existing search on routes at sea (needs a plugin checkout with the sailing search) |
| `./gradlew bankTileDump -PbankTileCacheDir=<dir> -PbankTileXteaPath=<keys.json>` | Dump bank-object placements from an OSRS cache to TSV |
| `./gradlew sailingAmenityVarbitDump -PsailingAmenityCacheDir=<dir> -PsailingAmenityXteaPath=<keys.json>` | Dump Sailing island amenity varbits from an OSRS cache |

## Maintenance

`scripts/maintenance.py` orchestrates post-update data maintenance —
`cache`, `collision-map`, `regions`, `bank`, `seasonal`, `refresh`,
`probes`, and `verify` subcommands wrap the cache download, Gradle
dumpers, and verification tiers in one entry point. See
[docs/maintenance.md](docs/maintenance.md) for the runbook.

Any task can run against another checkout of the plugin instead of the submodule with
`-PpluginDir=<path>` (relative to this directory), e.g. `-PpluginDir=../shortest-path` to try a branch
you're working on. The sailing tools in `src/sailing/java` are only compiled when that checkout has the
experimental sailing search.

## Dashboard options

All options are passed via `-P`:

| Option | Default | Description |
|--------|---------|-------------|
| `dashboardDataset` | `/dashboard/routes.csv` | Path to the CSV dataset (resolved from test resources) |
| `dashboardBundle` | derived from filename | Bundle name in the output site |
| `dashboardTitle` | derived from filename | Title shown in the UI |
| `dashboardSubtitle` | *(empty)* | Subtitle shown in the UI |
| `dashboardProfile` | `true` | Whether to enable the profiler |

For the datasets and when to use each, see [docs/dashboard-design.md](docs/dashboard-design.md).

## Keeping up with the plugin

The `shortest-path` submodule is pinned to a specific commit. To update it to the latest plugin master:

```bash
python3 scripts/maintenance.py collision-map
```

This fast-forwards the submodule to `upstream/master`, prints an edge diff
of the new `collision-map.zip` for review, and stages the gitlink bump
with `--commit`. Do not use `git submodule update --remote` — it checks
out a detached HEAD, which the data-writing subcommands refuse.

## Cache dumpers

The cache dumpers require a local OSRS cache. Download one first:

```bash
python3 scripts/maintenance.py cache
```

The `cache` subcommand downloads the cache and patches `keys.json`
idempotently — never `sed` the file by hand (the BSD/GNU `-i` syntax
differs, and `s/key/keys/g` corrupts `"keys"` into `"keyss"` on re-run).

Then run the desired task (see table above).

The `rebuild_bank_tsv.py` script merges the bankTileDump output into `shortest-path/src/main/resources/destinations/game_features/bank.tsv`:

```bash
python3 scripts/rebuild_bank_tsv.py
```
