# Maintenance workflows

## Purpose

Weekly OSRS game updates can break collision data, transport availability,
and region access. This repo orchestrates the upkeep — the plugin itself is
unchanged. `scripts/maintenance.py` is the single entry point: each
maintenance surface is one subcommand that shells out to the existing
Gradle dumpers, shell scripts, and Python helpers, so step ordering and
preconditions live in exactly one place.

Regenerated data files land in the `shortest-path` submodule's
`src/main/resources/` on an `origin` (fork) feature branch and go upstream by PR —
the established dev cycle. Data-writing subcommands refuse to run on
`master`, detached HEAD, or a dirty worktree, so a maintenance run can
never commit to the wrong place.

## Quick reference

| Subcommand | What it wraps |
|---|---|
| `cache` | `collision-map-update/download-latest-cache.sh` + the keys.json field patch |
| `collision-map` | submodule fetch + ff-merge to `upstream/master`, `compare_collision_maps.py`; the local runelite pipeline on `--local` |
| `regions` | `leagueRegionDump` + `f2pRegionDump` Gradle tasks |
| `bank` | `bankTileDump` + `scripts/rebuild_bank_tsv.py` |
| `seasonal` | `scripts/verify_seasonal_regions.py` |
| `refresh` | the ordered chain: collision-map -> cache -> regions -> bank -> seasonal |
| `probes` | the 11 season-discovery dump tasks (8 default + 3 name-driven) |
| `verify` | `compileTestJava` + submodule `test` + `dashboard` sweep + edge-diff |
| `validate` | `scripts/validate_data.py` deterministic checks + `freshness` currency check + advisory destinations/drift/season |

Run any subcommand with `--help` for its flags.

## Subcommands

### `cache`

```bash
python3 scripts/maintenance.py cache
```

Downloads the latest OSRS cache and `keys.json` from archive.openrs2.org
via `download-latest-cache.sh` (into `./cache` and `./keys.json`), then
patches the key file's `mapsquare`/`key` fields into the `region`/`keys`
shape the RuneLite XteaKeyManager expects. The patch is JSON-level and
idempotent — never `sed` the file by hand.

### `collision-map`

```bash
python3 scripts/maintenance.py collision-map [--local] [--commit]
```

Primary path: fast-forwards the submodule to `upstream/master` (upstream's
weekly `ExtractCollisionMap.yml` regenerates the zip every Wednesday), then
prints the `compare_collision_maps.py` edge diff between the previous
artifact (extracted from git history) and the new one. Prints the gitlink
commit hint, or stages it directly with `--commit`.

`--local` regenerates the zip through the runelite pipeline instead
(clone -> dumper inject -> `:cache:shadowJar` -> `java -jar` -> zip), for
when upstream's automation lags or breaks. See Notes for first-run cost.

`--local --compare-only` is the deep audit: the same regeneration
pipeline, but the artifact lands at `build/validate-collision-map.zip`
and is diffed against the worktree zip — the submodule is never
written, so the write-branch and clean-worktree gates do not apply.
Reach for it when upstream's workflow appears stale or before manually
bumping the gitlink.

### `regions`

```bash
python3 scripts/maintenance.py regions [--f2p]
```

Runs `leagueRegionDump` then `f2pRegionDump` against `./cache`, copies
`leagues/regions.tsv` into the submodule unconditionally, and copies
`f2p/regions.tsv` only when a plugin-side consumer exists (an `f2p/`
resources dir or `F2p*.java` class) or `--f2p` forces it — otherwise the
output stays staged in `build/f2p-regions/`.

Preconditions: cache present, `origin` (fork) feature branch, clean submodule
worktree.

### `bank`

```bash
python3 scripts/maintenance.py bank
```

Runs `bankTileDump` at its default output path, then merges placements
into `destinations/game_features/bank.tsv` via `rebuild_bank_tsv.py`
(additive merge — curated stand tiles win). Same write preconditions as
`regions`.

### `seasonal`

```bash
python3 scripts/maintenance.py seasonal
```

Read-only check: compares seasonal transport region assignments in the
submodule's `seasonal_transports.tsv` against wiki ground truth and
prints a per-section summary. No branch gate.

### `refresh`

```bash
python3 scripts/maintenance.py refresh [--skip-collision] [--local] [--f2p]
```

The derivable update chain as one command, in fixed order:
collision-map -> cache -> regions -> bank -> seasonal. The order matters —
`rebuild_bank_tsv.py` walks the submodule's current collision-map.zip for
stand-tile reachability, so the map update must come first. Aborts on the
first failing step. The write-branch and clean-worktree gates run up
front, before even the collision step.

### `probes`

```bash
python3 scripts/maintenance.py probes [--names-file names.txt]
```

Runs every season-discovery dumper sequentially (each wants an 8 GB
heap): `leagueIdProbe`, `leagueTeleportItemDump`, `leagueAreaStructDump`,
`leagueScriptScan`, `briefcaseEnumProbe`, `briefcaseParamScriptScan`,
`briefcaseTeleportTables`, `sailingAmenityVarbitDump`, plus the three
name-driven scans (`briefcaseDestOverlapScan`, `briefcaseStructHunt`,
`briefcaseDbRowScan`) when `--names-file` supplies a canonical destination
list. Writes to `build/` and stdout for human reading; a failing probe is
reported without masking the rest.

### `verify`

```bash
python3 scripts/maintenance.py verify [--skip-compile] [--skip-lint] \
    [--skip-dashboard] [--skip-diff] [--old-zip a.zip --new-zip b.zip]
```

The post-update compatibility gate — four tiers, all always run (a red
tier never hides the rest), printing `PASS`/`FAIL` per tier and a final
`verify: n/m tiers passed` line (m = the tiers that ran); exit code is
nonzero when any tier fails:

1. `compileTestJava` — the same compile gate CI runs.
2. `./gradlew -p shortest-path test` — the submodule's test suite
   (`TransportDataLintTest` et al.).
3. `dashboard` over every committed CSV under
   `src/test/resources/dashboard/` — enumerated via `git ls-files`, so
   the gitignored `debug.csv` scratch file can never enter the sweep.
   Pass/fail derives solely from each bundle's `report.json` run records
   (`reached` falsy or `assertionPassed` false) — the task sets
   `ignoreFailures = true`, so Gradle's exit code carries no signal and a
   missing or unparseable report fails the tier closed.
4. Collision-map edge-diff: the superproject-pinned gitlink artifact vs
   the worktree zip, compared by `compare_collision_maps.py`. Identical
   zips report "no edge changes" and pass; a non-empty diff is review
   evidence, not a failure. `--old-zip`/`--new-zip` (given together)
   override both sides.

Evidence stays under `build/` — `verify` writes no committed log.

### `validate`

```bash
python3 scripts/maintenance.py validate [--drift] [--season-active] \
    [--skip-<check>]
```

The data-quality gate — every check runs even after a failure, printing
`PASS`/`FAIL`/`ADVISORY`/`SKIP` per check and a `validate: n/m checks
passed` line (n/m = the hard checks that ran). Status lines: `PASS` the
check ran clean; `FAIL` a hard check produced findings (exit code 1);
`ADVISORY` the check ran and its findings are triage input that never
affects the exit code; `SKIP` the check was omitted by its
`--skip-<name>` flag or its tier gate. Every check has a `--skip-<name>`
flag (`--skip-tsv-structure`, `--skip-collision-zip`,
`--skip-walkability`, `--skip-bbox`, `--skip-regions`,
`--skip-freshness`, `--skip-destinations`, `--skip-drift`,
`--skip-season`).

| Check | Tier | What it proves |
|---|---|---|
| `tsv-structure` | hard | header cells, coordinate format, short rows and one-sided permutation sets across committed transport/destination TSVs |
| `collision-zip` | hard | entry names, blob plane counts and region-count sanity on the committed `collision-map.zip` |
| `walkability` | hard | transport endpoints reachable under the plugin's pathing model (flags, walkable neighbour, or transport edge) |
| `bbox` | hard | seasonal transport endpoints don't classify NEUTRAL against the curated league-region bboxes without a `Region override` |
| `regions` | hard | generated `leagues/regions.tsv` consistent with the zip surface and the curated-bbox classifier |
| `freshness` | hard | the collision map is current: newest live `caches.json` timestamp vs upstream's last `Update collision map` auto-commit, plus the submodule pin vs `upstream/master` |
| `destinations` | advisory | destination TSV targets blocked on the committed zip, minus the curated exceptions |
| `drift` | advisory | TSV object/menu anchors still exist in a live cache (`--drift` only) |
| `season` | advisory | league-season wiki cross-check + probes (`--season-active` or the marker file) |

The routine checks are cache-free and read-only against committed data,
enumerated via `git -C shortest-path ls-files` so gitignored scratch can
never enter the gate. `freshness` fetches only remote refs and a few KB
of JSON — a FAIL names the missing auto-commit history (dead upstream
CI), the missed weekly bump (newest cache more than a week newer than
the last auto-commit), or the pin lag (collision-map commits on
`upstream/master` the submodule HEAD lacks); any input it cannot resolve
fails the check closed rather than passing silently.

`--drift` runs the `transportAnchorDrift` cache scan and reports
`ADVISORY drift` with the grouped report at `build/transport-drift.txt`.
It needs a prepared `cache/` + `keys.json` (`python3
scripts/maintenance.py cache`) and never downloads one itself — with no
cache the tier prints an advisory skip carrying that hint. Findings mean
upstream objects moved or menu actions were renamed — triage the TSV
rows named.

`--season-active` (or the committed `src/test/resources/season_active`
marker) enables the league-season tier: the
`scripts/verify_seasonal_regions.py` wiki cross-check plus the
`leagueAreaStructDump`/`leagueScriptScan` probes when the cache is
prepared — all advisory, feeding the manual curated-region diff. The
marker procedure: commit the file naming the season (first non-comment
line) at league start, delete it at season end — while absent the tier
prints `SKIP season` with the enablement hint.

`destinations` findings triage into
`src/test/resources/destination_walkability_exceptions.tsv` — add
`X Y Z<tab>reason` rows for by-design blocked tiles.

#### CI gate

Every push and pull request runs the deterministic half of this gate
in the `validate` job of `.github/workflows/ci.yml`:
`python3 -m pytest tests/ -x` then
`python3 scripts/maintenance.py validate --skip-freshness`, on
Python 3.14 (`actions/setup-python`) with the suite's only
third-party deps installed via `pip install pytest pyyaml`. The job checks out `submodules: recursive` — the leaf checks
enumerate `git -C shortest-path ls-files`, so an empty submodule dir
would break them.

The environment-dependent pieces stay local-only, by design: the
`freshness` check needs the `upstream` remote plus openrs2 network
access, `drift` needs a prepared `cache/` + `keys.json`, and `verify`
runs the Gradle dashboard sweep — none of which exist in CI.
`tests/test_ci_workflow.py` asserts the job keeps this shape, so a
workflow edit that removes or weakens the gate fails the suite it
gates.

## Suggested workflows

### Weekly refresh

After the Wednesday game update and upstream's nightly collision-map run:

1. `python3 scripts/maintenance.py validate` — a hard FAIL means fix
   the named data before refreshing; a `freshness` FAIL on pin lag is
   what this workflow's `refresh` step resolves.
2. `git -C shortest-path checkout -b maint-<date>` — data lands on a
   `origin` feature branch, never master.
3. `python3 scripts/maintenance.py refresh` — runs the whole derivable
   chain (add `--skip-collision` if the zip is already current).
4. `python3 scripts/maintenance.py verify` — the four-tier gate.
5. Review the edge-diff output and any dashboard failures in
   `build/reports/pathfinder-dashboard/`.
6. `git -C shortest-path add` + `commit` the regenerated resources on the
   feature branch; `git -C shortest-path push origin` and open a PR
   upstream.
7. `git add shortest-path` + commit the gitlink bump in this repo.

### New league season

Discovery first, then curated edits, then the chain:

1. Commit `src/test/resources/season_active` naming the season (first
   non-comment line, e.g. `Demonic Pacts`) — the marker gates the
   `season` tier of `validate` for the league's duration. Delete it at
   season end.
2. `python3 scripts/maintenance.py validate` — with the marker present
   the `season` tier runs the wiki cross-check and, when the cache is
   prepared, the league probes; all output is advisory and feeds the
   manual curated-region diff.
3. `python3 scripts/maintenance.py probes` — all eight default-tier
   dumpers; add `--names-file <file>` (one canonical destination name per
   line) to also run the three briefcase name-driven scans.
4. Curate the bounding boxes in `src/test/resources/leagues_regions.tsv`
   and `src/test/resources/f2p_regions.tsv` from `leagueAreaStructDump`
   output.
5. Curate `shortest-path/src/main/resources/transports/seasonal_transports.tsv`
   — item IDs and teleports from `leagueIdProbe`/`leagueTeleportItemDump`
   output. Per-row region exceptions go in the `Region override` column;
   general region gating belongs in code, not in the TSV (see
   `shortest-path/docs/Transport-TSV-format.md` maintenance notes).
6. `python3 scripts/maintenance.py refresh` then
   `python3 scripts/maintenance.py verify`.
7. Commit on the feature branch, push `origin`, PR upstream.

### Upstream-issue-driven transport edits

For a reported transport/routing bug that resolves to a TSV fix:

1. Edit the relevant `shortest-path/src/main/resources/transports/*.tsv`
   on an `origin` (fork) feature branch.
2. `python3 scripts/maintenance.py validate` — `tsv-structure` catches
   malformed rows (bad coordinates, short rows, one-sided permutations)
   before the PR; `walkability` and `bbox` re-check the endpoint under
   the real zip.
3. `python3 scripts/maintenance.py verify --skip-diff` as the lint +
   scenario gate.
4. Commit on the feature branch, push `origin`, PR upstream.

## Notes

- Upstream's `ExtractCollisionMap.yml` (Wednesdays 23:20 UTC) plus this
  repo's `update-submodule.yml` (daily 06:00 UTC) are the primary
  collision-map source — `collision-map` consumes and diffs that artifact.
  Fork-side Actions, if ever added, must be `workflow_dispatch`-only: a
  competing schedule auto-committing the binary to the fork's master
  guarantees divergence from upstream's own auto-commits.
- `collision-map --local` needs a JDK 11 toolchain — the runelite build
  pins `languageVersion = 11` and Gradle auto-provisions it on first run
  (or install one via sdkman). The first `--local` run downloads multiple
  GB and builds runelite (~10+ min); all scratch state lives under
  `build/runelite-work/`.
- keys.json patching is owned by the CLI (`cache` applies it
  unconditionally — it is idempotent). Never `sed` the file: BSD/GNU
  `-i` syntax differs and `s/key/keys/g` corrupts `"keys"` into `"keyss"`
  on re-run.
- `f2p/regions.tsv` is staged in `build/` until the plugin-side F2P
  consumer merges — `regions --f2p` forces the copy for feature-branch
  work.
- Verification evidence is ephemeral `build/` output — there is no
  committed verification log; weekly verification is for the maintainer's
  own confidence.
- In-game smoke testing is the conditional manual tier: reserve it for
  release gates and API-touching updates. The RuneLite client cannot be
  automated, so it is deliberately not part of `verify`.
