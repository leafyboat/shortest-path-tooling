# Contributing

How development on this project actually works. For the file-level map
see `AGENTS.md`; for the architecture see `docs/system-overview.md`.

## Repositories and remotes

Two repositories are involved:

- **This repo** (`osrs-pathfinding/shortest-path-tooling`) — dashboards,
  cache dumpers, Python maintenance scripts, the scenario corpus. Commits
  go straight to `origin/master`.
- **The plugin** (`Skretzo/shortest-path`) — lives inside this repo as the
  `shortest-path/` submodule. Nobody pushes to upstream directly; work
  goes through a personal fork.

Inside `shortest-path/` the remotes are:

```text
upstream = https://github.com/Skretzo/shortest-path.git   (canonical plugin)
origin   = https://github.com/<you>/shortest-path.git     (your fork)
```

Set that up once:

```bash
git clone --recurse-submodules https://github.com/osrs-pathfinding/shortest-path-tooling.git
cd shortest-path-tooling/shortest-path
git remote rename origin upstream            # submodule clones Skretzo's repo
git remote add origin https://github.com/<you>/shortest-path.git
git remote set-url --push upstream no-push   # belt and braces
```

Then install Python deps (`pip install -r requirements.txt`) and fetch a
local game cache once (`python3 scripts/maintenance.py cache`) — the
dumpers and probes need `cache/` + `keys.json`, both gitignored.

## The golden rule: two commit streams

Never mix the two repos in one commit. Tooling changes (scripts, probes,
dashboard code, this file) commit in the outer repo. Plugin changes
(data TSVs, `collision-map.zip`, Java) commit inside `shortest-path/` on a
branch, and only land here as a **submodule pin bump** after they merge
upstream.

```bash
cd shortest-path
git fetch upstream master
git checkout -b data/my-change upstream/master   # real branch, never detached HEAD
# ...edit, test...
git push origin data/my-change
gh pr create --repo Skretzo/shortest-path --head <you>:data/my-change ...
```

- **One logical fix per PR.** Reviewers upstream read diffs line by line;
  unrelated rows in one PR get the whole thing held up.
- **PR title is the commit.** Upstream squash-merges, so make the title a
  plain imperative sentence ("Gate slash webs…", "Correct durations in…").
  Reference issues with `Fixes #NNN` (auto-closes on merge) or `Refs #NNN`
  (partial/honest-deferral) — and only claim `Fixes` when the data is
  actually correct.
- After upstream merges, re-pin: `git -C shortest-path fetch upstream
  master && git -C shortest-path checkout upstream/master -B master`, then
  commit the gitlink change in this repo.
- **Never** `git submodule update --remote` (detached HEAD breaks the
  data-writing guards) and never `git add -f` an ignored path.

## Verifying plugin data before a PR

Minimum per data change, run from inside `shortest-path/`:

```bash
python3 scripts/check_tsv.py && bash scripts/tsv-lint.sh   # structure
./gradlew test                                           # pathfinder suite
```

Before pushing something that touches requirements or routes, sanity-check
a scenario from the tooling side too: `./gradlew test` runs the committed
dashboard CSVs, and `python3 scripts/maintenance.py verify` is the full
gate (compile → submodule tests → dashboard sweep → collision diff;
~15–30 min, `--skip-*` flags exist for iteration).

## Confirming requirement values

The expensive part of this work is proving *which* varbit/varp gates a
transport. Do it from the cache, not by guessing:

- `VarAccessProbeTest` finds which client scripts read or write a var
  (`-Dtile.probe.vars=571,1499`), disassembles scripts
  (`-Dtile.probe.scripts=113`), and searches operands
  (`-Dtile.probe.strings`, `-Dtile.probe.iops`). The minigame-teleport
  eligibility script is a goldmine — it gates several transports.
- Names live in `build/runelite-work/.../gameval/VarbitID.java` and
  `VarPlayerID.java`. Reporter-observed varbits are frequently wrong
  (repurposed UI vars); a script that *reads* the var for the check is
  the proof.
- `Quests` in the TSVs means **finished** only. Partial quest state
  (e.g. "visited Keldagrim") goes in `Varbits` as a threshold on the
  quest varbit (`571>4`).
- Wiki lookups: use the API wikitext, not rendered HTML —
  `https://oldschool.runescape.wiki/api.php?action=parse&page=<page>&prop=wikitext&format=json`.

**Unexposed requirements:** if no client-visible var tracks an unlock
(stored canoe axe, paid-once gates), do **not** delete the requirement
row — the plugin would offer it to players who can't use it. Honest
options: leave the conservative gate, or add a config-option requirement
type. Removing data is the one outcome that is never acceptable.

## House style

- Commit messages: imperative, self-contained, explain *why*. No
  references to local planning files, internal task IDs, or tooling that
  doesn't ship with the repo.
- Don't bump `collision-map.zip` by hand — `python3 scripts/maintenance.py
  collision-map` regenerates it from the dumper on a feature branch.
- Upstream periodically regenerates data from a new cache (bot commits);
  tooling-side overrides must live in `collision-map-update/
  CollisionMapDumper.java`, not as hand edits, or the next regen reverts
  them.
- Submodule branches are throwaway: after merge, rebase the next PR onto
  fresh `upstream/master` rather than chaining branches.
