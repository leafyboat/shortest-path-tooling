# AGENTS.md

Instructions for agents using this repo to develop the
[`shortest-path`](https://github.com/Skretzo/shortest-path) RuneLite plugin
(OSRS pathfinding).

## What this is

Plugin work happens on branches in the `shortest-path/` submodule (work on a
feature branch, PRs go to `upstream` = Skretzo/shortest-path). This repo wraps
the plugin via a Gradle composite build and adds the pathfinder dashboard
generator, OSRS cache dumpers, and Python maintenance/validation scripts
around it.

## Layout

| Path | Contents |
|------|----------|
| `shortest-path/` | Git submodule (pinned commit). Plugin sources + data in `src/main/resources/` (`collision-map.zip`, `destinations/`, `transports/`, `leagues/`). |
| `src/test/java/shortestpath/` | All Java lives under *test* sources: `dashboard/` (site generator), `dump/` (cache dumpers), `pathfinder/` (profiling). |
| `src/test/resources/` | Dashboard web assets + CSV route datasets under `dashboard/` + region TSVs. |
| `gradle/` | Task definitions: `dashboards.gradle`, `cache-dumpers.gradle`. |
| `scripts/` | Python orchestration (see Scripts map below). |
| `tests/` | pytest suite for the Python scripts (`fixtures/` for test data). |
| `collision-map-update/` | Standalone upstream pipeline pieces: `download-latest-cache.sh`, `CollisionMapDumper.java`, `build.gradle.kts.patch`. |
| `docs/` | `maintenance.md` runbook, `dashboard-design.md`, performance analysis. |

## Toolchain

- Java 11 (CI: temurin 11). Gradle via `./gradlew` wrapper only — never a
  system `gradle`.
- Python 3 + pytest for `tests/` and `scripts/`.
- RuneLite deps resolve as `latest.release` — upstream RuneLite releases can
  break compilation without any local change.
- `cache/` and `keys.json` are gitignored; the cache dumpers need a local
  OSRS cache. Get one with `python3 scripts/maintenance.py cache`.

## Common commands

```bash
./gradlew compileTestJava          # compile gate (what CI runs)
./gradlew test                     # JUnit suite incl. dashboard scenarios (8g heap configured)
./gradlew dashboard                # build dashboard → build/reports/pathfinder-dashboard
python -m http.server --directory build/reports/pathfinder-dashboard 8000
python3 -m pip install -r requirements.txt   # pytest, pyyaml, tqdm (homebrew python needs --break-system-packages or a venv)
python3 -m pytest tests/           # Python script tests (no network, all mocked)
python3 scripts/maintenance.py verify    # full gate: compile → submodule test → dashboard sweep → edge diff
python3 scripts/maintenance.py validate  # data validation: hard gate + advisory tiers
```

Dashboard options are `-P` properties: `dashboardDataset` (default
`/dashboard/routes.csv`), `dashboardBundle`, `dashboardTitle`,
`dashboardSubtitle`, `dashboardProfile` (default true), `dashboardHeatmap`
(default true; only applies when profiling is on), `dashboardSeasonal`,
`dashboardF2p`, `dashboardThreads` (parallel scenario workers; default
`availableProcessors()-3`). Other tasks: `captureExpectedLengths` (writes actual lengths
back into the CSV) and the cache dumpers/probes in `gradle/cache-dumpers.gradle`
(`bankTileDump`, `sailingAmenityVarbitDump`, `leagueRegionDump`,
`f2pRegionDump`, `leagueIdProbe`, `transportAnchorDrift`, the `briefcase*`
scans, …). Dumpers take `-P<name>CacheDir=` and `-P<name>XteaPath=` props.

Profiling is expensive — pick flags deliberately for dev runs:

- **Default to `-PdashboardProfile=false` for development/verification runs.**
  Profiling adds ~30–50× per-search cost on heavy datasets (866 scenarios:
  ~1 min unprofiled vs tens of minutes profiled) and `nanoTime()` calls sit
  in the search hot loop.
- **`dashboardHeatmap=false`** drops the per-tile visit map
  (`HashMap<Integer,int[]>` — millions of boxed entries × worker count),
  which is the dominant profiling memory cost. Use it when you need the
  profiler's counters/timing/heatmap-less data but not the tile map.
- Keep `dashboardProfile=true` (with heatmap) only when producing profiled
  bundles for the dashboard site or when investigating search internals
  (phase timings, boundary sizes, tile visit counts).

`verify` streams tier progress (per-dataset header + tqdm bar driven by the
`[i/N]` scenario heartbeats in `DashboardTest`). Expect ~15–30 min: the
dashboard tier launches one cold JVM per committed CSV (8+ datasets, the
largest ~860 scenarios). `--skip-compile/--skip-lint/--skip-dashboard/
--skip-diff` narrow the gate for fast iteration.

`VarAccessProbeTest` scans cache clientscripts for var/operand access —
`-Dtile.probe.vars=<ids>` (scripts reading/writing those varps/varbits),
`-Dtile.probe.scripts=<ids>` (full disassembly), `-Dtile.probe.strings=<text>`
(scripts containing a string operand), `-Dtile.probe.iops=<ints>` (raw int
operands, e.g. packed coords). Opcodes: GET_VARP=1, SET_VARP=2,
GET_VARBIT=25, SET_VARBIT=27; branch ops are `Opcodes.java` IF_ICMP*.
Varbit/varplayer names are in
`build/runelite-work/runelite/runelite-api/.../gameval/{VarbitID,VarPlayerID}.java`.
This is how requirement candidates get confirmed without an account — e.g.
the minigame-teleport eligibility script exposed MTA (varbit 1499) and the
Keldagrim "visited" gate (varbit 571 ≥ 5).

TSV requirement grammar: `Quests` means FINISHED only — partial quest
progress must be expressed via the quest *varbit* in the `Varbits` column
(`id>4`, `id=1`, `id<3`, `id&mask`; `VarPlayers` adds `@` cooldown minutes).
If a requirement is not exposed in any client var, do not drop the data —
it is gated via a config-option mechanism instead.

Dev client: `./gradlew -p shortest-path run` launches RuneLite with the
plugin bundled; on macOS it needs
`JDK_JAVA_OPTIONS="--add-opens=java.desktop/com.apple.eawt=ALL-UNNAMED"`
(else `OSXFullScreenAdapter` crashes at startup). In-game dev console
commands `::getvarp <id>` / `::getvarb <id>` print values as chat lines that
land in the Gradle log — usable when the Var Inspector panel can't copy.

## Scripts map

| Script | Purpose |
|--------|---------|
| `scripts/maintenance.py` | Single entry point for upkeep. Subcommands: `cache`, `collision-map`, `regions`, `bank`, `seasonal`, `refresh` (full chain), `probes`, `verify`, `validate`. See `docs/maintenance.md`. |
| `scripts/validate_data.py` | Deterministic checks on committed plugin data (TSV structure, zip structure, walkability, bbox, freshness). |
| `scripts/verify_seasonal_regions.py` | Verify seasonal transport region assignments vs wiki ground truth. |
| `scripts/rebuild_bank_tsv.py` | Merge `bankTileDump` output into submodule `destinations/game_features/bank.tsv`. |
| `scripts/compare_collision_maps.py` | Edge-flag diff between two `collision-map.zip` artifacts. |
| `scripts/collision_zip.py` | Shared reader library for `collision-map.zip` (imported by other scripts). |
| `scripts/analyse_dashboard_runs.py` | Compare two sets of dashboard `report.json` files, per-route deltas. |
| `scripts/import_issues.py` | Sync upstream Skretzo/shortest-path issues/PRs into local datasets. |

### Submodule scripts (`shortest-path/scripts/`)

These live in the plugin repo — run them there, and remember changes to them
belong to the submodule's branch/PR flow, not this repo.

| Script | Purpose |
|--------|---------|
| `check_tsv.py` | Validate transport/destination TSVs: header shape, recognized columns, column counts, whitespace, coordinate format. |
| `tsv-lint.sh` | Shell linter — every TSV line must have the same column count. `./tsv-lint.sh [dir]` (defaults to `src/main/resources`). |
| `dump_transport_coordinates.py` | Dump all transport/destination coordinates as JSON for the coordinate-preview tooling. |
| `diff_coordinate_json.py` | Diff two coordinate dumps, keeping only new or semantically changed tiles. |
| `compute_changed_coordinates.sh` | Orchestrates the merge-base vs PR-head coordinate dumps for the preview workflow. |
| `renumber_config_positions.py` | Fix duplicate `@ConfigItem`/`@ConfigSection` `position` values in a RuneLite config file. |

## Submodule rules (important)

- Remotes inside `shortest-path/`: `upstream` = Skretzo/shortest-path,
  `origin` = your fork.
- **Never** `git submodule update --remote` — it leaves a detached HEAD,
  which the data-writing subcommands refuse. Update via
  `python3 scripts/maintenance.py collision-map [--commit]` instead.
- Regenerated data lands on an `origin` (fork) feature branch and goes
  upstream by PR. Data-writing subcommands refuse `master`, detached HEAD,
  and dirty worktrees — keep the submodule clean before running them.
- Plugin test helpers are shared, not copied: `build.gradle` pulls
  `TestPathfinderConfig.java` and `TestShortestPathConfig.java` straight
  from `shortest-path/src/test/java` into `compileTestJava`.
- Run submodule tests with `./gradlew -p shortest-path test`.

## Upstream PR workflow

The upstream maintainer reviews at a deliberate pace and has flagged
the submission pace as rushed — requirement-satisfaction logic is
spread across several places, so subtle changes are expensive to review.

- Changes to requirement satisfaction, eligibility, or
  `usable()`/`useTransport` semantics must include adversarial tests:
  null/empty requirement branches, OR-vs-AND combinations, and every
  teleportation-item mode — not just the happy path.
- Transport/quest availability varbits are usually recoverable by
  decompiling cache clientscripts (`VarAccessProbeTest`,
  `-Dtile.probe.*` flags); cross-check with the Quest Helper plugin.
  If a condition genuinely isn't var-exposed, use the config-option
  mechanism rather than dropping the data.

## Verify-command rules

Checks that cannot fail get written every cycle — audit before shipping:

- A verify/check command must be able to fail. No `; cmd` after an `&&`
  chain (the trailing command discards the chain's status). When piping,
  assert the source exit code — capture `out=$(cmd 2>&1); rc=$?` and
  require both `rc -eq 0` and no error lines in `$out`. A crashed
  checker must never read as green.
- Greps must target identifiers the change introduces (new constants,
  enum values, tokens), never pre-existing symbols — a presence check
  that passes on the untouched tree is not a check.
- `gh` invocations must be real: `gh pr list --head` takes a bare
  branch name (filter fork owner via `headRepositoryOwner.login`);
  `gh pr view` has no `--head` flag.
- Every file an action step creates or edits must be declared in that
  step's file list.
- After adding `@ConfigItem`s, `position` values must stay unique:
  `grep -o 'position = [0-9]*' ShortestPathConfig.java | sort | uniq -d`
  → empty. `renumber_config_positions.py` fixes collisions.

## Benchmark corpus (related repo)

[`shortest-path-corpus`](https://github.com/osrs-pathfinding/shortest-path-corpus)
is the implementation-neutral benchmark corpus for OSRS pathfinding — a
separate repo consumed by this and other pathfinding projects as test
fixtures. It owns:

- `corpus/routes-v1.json` — canonical routes with stable `id`, authoritative
  `start`/`target` `[x, y, plane]` coordinates, `allowTransports`, benchmark
  `tiers` (`smoke`, `standard`, `full`), and `negativeProfiles` (profiles
  expected to find the route unreachable — hand-curated, not derived).
- `corpus/excluded-routes-v1.json` — excluded routes.
- `accounts/account-profiles-v1.json` — four account profiles (`early`,
  `mid`, `end`, `maxed`; the last two are quest-cape accounts) describing
  skills, quests, diaries, items, unlocks, and routing variables.
- `manifest.json` — format/file versions.

The account JSON is generated — edit the Java sources under
`profile-generator/src/main/java/shortestpath/corpus/profiles/`
(`CanonicalProfiles.java`, `CanonicalItems.java`, `RoutingVariables.java`),
never the JSON. Treat the generated JSON as an opaque contract: preserve
field meanings, don't infer semantics from profile names, and treat unknown
fields as extension data. RuneLite version is pinned in
`profile-generator/build.gradle` — bumping it can change generated fixtures.

Corpus repo commands (run there, not here):

```bash
./profile-generator/gradlew -p profile-generator test
./profile-generator/gradlew -p profile-generator generateAccountProfiles
./profile-generator/gradlew -p profile-generator verifyAccountProfiles
node tools/validate.js
```

Generation intentionally rewrites the committed fixture; CI runs tests and
verification, not generation.

## OSRS wiki lookups

When verifying game data against the wiki (e.g. seasonal transport region
assignments, transport requirements), fetch the machine-readable wikitext
instead of rendered HTML:

- Append `?action=raw` to the page URL, or
- call the API directly:
  `https://oldschool.runescape.wiki/api.php?action=parse&page=<page>&prop=wikitext&format=json`

## Gotchas

- Never hand-edit or `sed` `keys.json` — `maintenance.py cache` patches it
  idempotently (naive sed corrupts `"keys"` → `"keyss"` on re-run).
- Checkstyle is applied but disabled; don't "fix" by enabling it.
- All dashboard/dump Gradle tasks are `Test` tasks with `ignoreFailures =
  true` and `doNotTrackState` — they always re-run and don't fail the build
  on scenario failures. Inspect output/`build/reports/` for real status.
