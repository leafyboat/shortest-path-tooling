# System Overview

How the shortest-path tooling fits together — the pieces, the data flow,
and the workflows they support. Read this first; the runbooks
([maintenance.md](maintenance.md), [dashboard-design.md](dashboard-design.md))
assume this picture.

## The problem this repo solves

The [`shortest-path`](https://github.com/Skretzo/shortest-path) RuneLite
plugin routes players across the OSRS world. Its answers are only as good
as its data: a collision map (which tiles are walkable), transport data
(doors, ladders, ships, teleports — anything that moves you non-trivially),
and destination lists. The game updates weekly, so the data drifts.

This repo is the plugin's maintenance harness. It answers three questions:

1. **Is the data still correct?** — validation and verification gates
2. **How do we refresh it?** — cache dumpers + orchestrated maintenance
3. **Does a reported bug actually reproduce, and is it fixed?** — the
   issue-replay loop

Everything here is development tooling. None of it ships with the plugin.

## The pieces

| Piece | What it is | Where |
|-------|-----------|-------|
| Plugin submodule | The actual plugin, pinned at a commit. All pathfinding data lives under `shortest-path/src/main/resources/` | `shortest-path/` |
| Composite build | Gradle wires the submodule in as an included build, so tooling code uses the plugin's real classes without living in the plugin repo | `settings.gradle`, `build.gradle` |
| Dashboard | Generates an HTML report that runs pathfinding scenarios (CSV rows) against the collision map and records where each path went, how long it took, and whether it met expectations | `src/test/java/shortestpath/dashboard/` → `build/reports/pathfinder-dashboard/` |
| Cache dumpers | Java programs that read the OSRS game cache and dump ground truth (object placements, varbits, league regions, bank tiles) to files | `src/test/java/shortestpath/dump/`, `gradle/cache-dumpers.gradle` |
| Orchestrator | `scripts/maintenance.py` — one CLI for the whole maintenance surface | `scripts/maintenance.py` |
| Validators | Deterministic data checks (TSV shape, zip structure, walkability, regions, scenario grammar) | `scripts/validate_data.py` + friends |
| Issue store | Local shadow copies of upstream bug reports with a lifecycle and replay machinery | `.planning/issues/` + `scripts/import_issues.py` |
| CI gate | Compile + pytest + deterministic validate on every push/PR | `.github/workflows/ci.yml` |

## How data flows

```
OSRS game cache ──dumpers──► ground truth (TSV/TXT reports)
                                 │
openrs2 (cache mirror) ──► cache/ + keys.json
                                 │
collision map pipeline ──► shortest-path/.../collision-map.zip
dumpers + merge scripts ──► shortest-path/.../transports/*.tsv
                            shortest-path/.../destinations/*.tsv
                            shortest-path/.../leagues/*.tsv
                                 │
                            plugin loads this data at runtime
                                 │
scenario CSVs ──► dashboard runs pathfinder over collision map
                                 │
                            report.json (reached / steps / assertionPassed)
                                 │
                        compare expectations vs actual
```

Three ideas make this work:

- **The submodule stays clean.** Tooling never writes into plugin code;
  data updates land on fork branches and go upstream by PR. The tooling
  repo only bumps the pinned commit (the "gitlink").
- **The game cache is the oracle.** Whatever the TSVs claim, the cache
  knows what the world actually contains. Dumpers extract that truth;
  validators compare claims against it.
- **A red row is a failing gate.** Every committed scenario CSV is
  swept by the verify pipeline; intentional failures must be annotated
  (`expect_reachable=false`) so red always means something.

## The two gates

There are two different "check everything" commands — they answer
different questions:

### `python3 scripts/maintenance.py validate` — is the data *well-formed*?

Deterministic checks on committed data, no game cache needed (except the
optional `--drift`/`--season` tiers). Hard checks gate; advisory checks
report. This is what CI runs (`--skip-freshness`).

### `python3 scripts/maintenance.py verify` — does the plugin still *work*?

Four tiers: compile → submodule test suite → dashboard sweep over every
committed dataset → collision-map edge diff. Run this after an upstream
bump or data refresh, before trusting the result.

### `python3 scripts/import_issues.py check` — is the issue store clean?

Lints the shadow files and the scenario dataset for grammar errors.

## The issue-replay loop

When someone reports a routing bug upstream, the loop is:

1. **Capture** — `import_issues.py sync` pulls the report into a local
   `ISSUE-<N>.md` shadow file (upstream text quarantined in UNTRUSTED
   sections; maintainer notes kept in separate sections that re-sync
   preserves).
2. **Normalize** — turn the report into a scenario row in
   `scenarios.csv`: start, target, game state (varbits/varplayers/quests/
   equipment), and what "correct" means (`expected_length`,
   `expect_reachable`, …).
3. **Reproduce** — run the dashboard on that row against the current
   data. `reached:false` or a length mismatch = bug reproduced.
4. **Fix** — repair the data (usually submodule TSVs, on a fork branch).
5. **Verify** — `import_issues.py verify` re-reads the post-fix
   `report.json`; only with passing evidence does the issue reach
   `status: verified`. Then the fixed row gets promoted into the
   committed datasets (`src/test/resources/dashboard/*-issues.csv`)
   so it becomes a permanent regression test.

That last step is the point: **every fixed bug becomes a dataset row**,
and every dataset row is swept by `verify`, so regressions surface the
moment data drifts again.

## A typical maintenance cycle

After a game update or upstream plugin change:

```bash
python3 scripts/maintenance.py collision-map   # bump pin + refresh collision data
python3 scripts/maintenance.py refresh         # re-derive regions/bank/seasonal data
python3 scripts/maintenance.py verify          # four-tier compatibility gate
python3 scripts/maintenance.py validate        # deterministic data checks
python3 -m pytest tests/                       # script test suite
```

The [maintenance runbook](maintenance.md) walks each workflow
(weekly refresh, new league season, upstream-issue-driven edits)
step by step.

## Exceptions baselines — why "known bad" is written down

Some committed data is intentionally imperfect: upstream authors a few
transport anchors with menu text but no object ID; a few destination
tiles aren't walkable in the strict sense. Rather than weaken the
checks, the repo curates the exceptions:

- `src/test/resources/transport_anchor_exceptions.tsv` — anchor cells
  allowed to lack a trailing object ID
- `src/test/resources/destination_walkability_exceptions.tsv` —
  coordinate-keyed walkability suppressions
- `src/test/resources/season_active` — marker file that turns on the
  seasonal cross-check tier during league seasons

The contract: **baseline debt is listed; new debt still flags.** When
upstream data changes, findings can jump — that's the gate working,
and the fix is to re-curate (verify each new case is genuinely
intentional) or fix the data, never to silence the check.

## Terms worth knowing

- **Anchor cell** — a transport row's `menuOption menuTarget objectID`
  cell, e.g. `Climb-up Ladder 16683`. The drift detector resolves each
  one against the cache: does that object exist near the origin tile
  with that menu option?
- **Collision map** — per-tile movement flags (`collision-map.zip`),
  the substrate the pathfinder walks on.
- **Gitlink** — the superproject's pinned submodule commit. `verify`
  compares committed expectations against *this*, so expectations
  captured on a newer checkout go red until the pin catches up.
- **Varbit / varplayer** — game-state bits that gate content
  (e.g., "this door is unlocked"). Scenarios set them in the
  `varbits`/`varplayers` columns; `bypassVarbitChecks` /
  `bypassVarPlayerChecks` tell the harness to ignore those gates.
- **Scenario / dataset / bundle** — a scenario is one CSV row (start →
  target + state + expectation); a dataset is a CSV file of them; a
  bundle is one dataset's rendered output under
  `build/reports/pathfinder-dashboard/bundles/`, including its
  `report.json` evidence file.
- **Drift detector** — two cache-backed scans run by `validate --drift`:
  `transportAnchorDrift` reports anchors whose object vanished, moved,
  or changed menu options; `destinationDrift` reports destination rows
  with no interaction-capable object near their tile (the
  deleted-destination check — a removed anvil leaves the floor
  walkable, so committed-data checks can't see it).

## Where to go next

| You want to… | Read |
|--------------|------|
| Refresh data after a game update | [maintenance.md](maintenance.md) |
| Add or tune a dashboard scenario | [dashboard-design.md](dashboard-design.md) (CSV format) |
| Triage a reported routing bug | `.planning/issues/README.md` |
| Understand the pathfinder itself | [pathfinder-performance-analysis.md](pathfinder-performance-analysis.md) |
