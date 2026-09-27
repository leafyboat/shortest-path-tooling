# shortest-path-tooling

Developer dashboards and OSRS cache dumpers for the [shortest-path](https://github.com/Skretzo/shortest-path) RuneLite plugin.

This repo carries `shortest-path` as a git submodule and uses a Gradle [composite build](https://docs.gradle.org/current/userguide/composite_builds.html) to consume the plugin's sources — so the dashboard and cache-dumper code never pollutes plugin PRs.

New here? [docs/system-overview.md](docs/system-overview.md) explains how the pieces fit together and the workflows they support.

## Looking for the plugin?

This repository contains **developer tooling** — it is not the plugin itself.

- **To use the plugin:** install *Shortest Path* from the RuneLite Plugin
  Hub. The [plugin repo](https://github.com/Skretzo/shortest-path) and its
  [wiki](https://github.com/Skretzo/shortest-path/wiki) cover features,
  examples, and options. Bugs and feature requests go to the plugin's
  [issue tracker](https://github.com/Skretzo/shortest-path/issues), and
  there's a [Discord server](https://discord.gg/uX47xg8u3M) for help and
  discussion.
- **To browse pathfinding results:** the published dashboard is at
  `https://skretzo.github.io/shortest-path/` — no setup needed.
- **To hack on the plugin or its data:** you're in the right place —
  keep reading.

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

## Using a separate shortest-path checkout

Developers working on the plugin and tooling side by side can point the tooling
build at an external checkout:

```bash
./gradlew -PshortestPathDir=../shortest-path test
./gradlew -PshortestPathDir=../shortest-path dashboard
```

Paths are resolved relative to `shortest-path-tooling`. The `shortestPathDir`
property controls both the Gradle composite build for
`shortestpath:shortest-path` and the directly compiled plugin test helpers.
The default remains the `./shortest-path` Git submodule.

## Available tasks

| Task | Description |
|------|-------------|
| `./gradlew dashboard` | Build the pathfinder dashboard from the default routes dataset |
| `./gradlew dashboard -PdashboardDataset=/dashboard/clue_locations_full.csv` | Build dashboard from a specific dataset |
| `./gradlew captureExpectedLengths` | Write actual path lengths back into the source CSV as `expected_length` |
| `./gradlew bankTileDump -PbankTileCacheDir=<dir> -PbankTileXteaPath=<keys.json>` | Dump bank-object placements from an OSRS cache to TSV |
| `./gradlew sailingAmenityVarbitDump -PsailingAmenityCacheDir=<dir> -PsailingAmenityXteaPath=<keys.json>` | Dump Sailing island amenity varbits from an OSRS cache |
| `./gradlew routingCuts -PkahipNodeSeparator=<path>` | Generate the exact pathfinder's `routing-cuts.bin` with KaHIP (see below) |
| `./gradlew routingCutsReport` | Report how many committed cuts still apply to the current collision map |

## Exact routing cuts

The plugin's exact pathfinder derives its routing data from `collision-map.zip`
at runtime, using a small list of separator cut edges (`routing-cuts.bin`) to
split large walking areas. `routingCuts` regenerates that file with KaHIP's
`node_separator`, using the plugin's own walking graph:

```bash
./gradlew routingCuts \
  -PshortestPathDir=../shortest-path \
  -PkahipNodeSeparator=$(command -v node_separator) \
  -ProutingCutsOutput=../shortest-path/src/main/resources/routing-cuts.bin
```

KaHIP is not a Gradle dependency. Use `nix-shell -p kahip` or build KaHIP with
CMake (`-DNOMPI=On`) and pass the binary path. The plugin's weekly
`ExtractCollisionMap` workflow runs this task after dumping a new collision map
and commits both files together. Stale cuts only slow preparation down; they
never make exact routes wrong.

## Maintenance

`scripts/maintenance.py` orchestrates post-update data maintenance —
`cache`, `collision-map`, `regions`, `bank`, `seasonal`, `refresh`,
`probes`, and `verify` subcommands wrap the cache download, Gradle
dumpers, and verification tiers in one entry point. See
[docs/maintenance.md](docs/maintenance.md) for the runbook.

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

## Related repositories

- [`shortest-path`](https://github.com/Skretzo/shortest-path) — the plugin
  itself (carried here as the `shortest-path/` submodule).
- [`shortest-path-corpus`](https://github.com/osrs-pathfinding/shortest-path-corpus)
  — implementation-neutral benchmark corpus: canonical routes, account
  profiles, and reachability expectations shared across pathfinding
  implementations.
