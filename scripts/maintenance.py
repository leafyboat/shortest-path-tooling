#!/usr/bin/env python3
"""Orchestrating maintenance CLI for the shortest-path tooling repo.

One discoverable entry point for the post-game-update maintenance
surfaces.  Every external invocation goes through the single ``run``
seam below: list-form argv, captured output, per-operation timeouts.
All scratch state lives under ``build/``; regenerated data lands only in
the submodule's ``src/main/resources/``.

Subcommands:
    cache          download the latest OSRS cache + keys.json from
                   archive.openrs2.org, then patch keys.json into the
                   shape the RuneLite XteaKeyManager expects
    collision-map  update the submodule's collision-map.zip — by default
                   fast-forward the submodule to upstream/master and print
                   an edge diff of the new artifact for review;
                   ``--local`` regenerates the zip through the local
                   runelite pipeline instead
    regions        regenerate the league + f2p region TSVs from the
                   cache and copy them into the submodule (the f2p copy
                   is gated on a plugin-side consumer or ``--f2p``)
    bank           regenerate bank tile placements and merge them into
                   bank.tsv via rebuild_bank_tsv.py
    seasonal       check seasonal transport region assignments against
                   wiki ground truth (read-only)
    refresh        run the derivable update chain end-to-end in fixed
                   order: collision-map -> cache -> regions -> bank ->
                   seasonal (``--skip-collision`` omits the first step)
    probes         run every season-discovery cache dumper in
                   sequence; ``--names-file`` adds the name-driven scans
    verify         run the compatibility gate: compileTestJava ->
                   submodule test -> dashboard sweep over every
                   committed scenario CSV -> collision-map edge-diff
    validate       run the data-validation checks — deterministic hard
                   gate plus advisory drift/season tiers
"""

import argparse
import json
import shutil
import subprocess
import sys
from datetime import datetime, timedelta
from pathlib import Path
from typing import List, Optional, Tuple

REPO = Path(__file__).resolve().parent.parent
SUBMODULE = REPO / "shortest-path"

GIT_TIMEOUT_SECONDS = 120
SCRIPT_TIMEOUT_SECONDS = 300
DOWNLOAD_TIMEOUT_SECONDS = 1800
GRADLE_TIMEOUT_SECONDS = 1800
RUNELITE_BUILD_TIMEOUT_SECONDS = 3600
JAVA_DUMPER_TIMEOUT_SECONDS = 1800


def run(cmd: List[str], *, cwd: Optional[Path] = None,
        timeout: int = SCRIPT_TIMEOUT_SECONDS,
        binary: bool = False) -> subprocess.CompletedProcess:
    """The only subprocess call site — injected/monkeypatched in tests.

    ``binary=True`` captures stdout/stderr as bytes (needed for ``git
    show`` of binary artifacts); otherwise output is decoded text.  The
    default cwd is the repo root so wrapped scripts write their scratch
    files where ``.gitignore`` already covers them.
    """
    try:
        return subprocess.run(
            cmd, cwd=cwd if cwd is not None else REPO,
            capture_output=True, text=not binary, timeout=timeout)
    except subprocess.TimeoutExpired:
        # A wedged subprocess (network stall, gradle daemon hang) must
        # not hang the whole maintenance run without a diagnostic.
        raise SystemExit(
            f"{cmd[0]} timed out after {timeout}s") from None
    except FileNotFoundError:
        # A missing binary (java, zip, ./gradlew, ...) must exit with a
        # diagnostic, not a raw traceback mid-pipeline.
        raise SystemExit(f"{cmd[0]}: command not found") from None


def _stderr_tail(proc: subprocess.CompletedProcess, lines: int = 8) -> str:
    stderr = proc.stderr or ""
    if isinstance(stderr, bytes):
        # binary=True captures produce bytes — decode so callers can
        # print the diagnostic instead of crashing mid-error.
        stderr = stderr.decode("utf-8", errors="replace")
    tail = stderr.strip().splitlines()
    return "\n".join(tail[-lines:])


def check_tools(names: List[str]) -> None:
    """Fail fast when a wrapped binary is missing from PATH."""
    missing = [n for n in names if shutil.which(n) is None]
    if missing:
        raise SystemExit(
            f"missing required tools: {', '.join(missing)}")


def patch_keys_json(path: Path) -> int:
    """Rename openrs2 key fields to the shape the RuneLite
    XteaKeyManager expects: ``mapsquare`` -> ``region`` and
    ``key`` -> ``keys``.

    Operates on parsed JSON, so it is field-precise and idempotent —
    unlike the workflow's sed pair, which corrupts ``"keys"`` into
    ``"keyss"`` on re-run and differs between BSD and GNU ``sed -i``.
    Malformed input (not a JSON list of objects) aborts before anything
    is written.  Returns the number of fields renamed.
    """
    try:
        entries = json.loads(path.read_text())
    except (json.JSONDecodeError, OSError) as exc:
        raise SystemExit(
            f"{path}: cannot parse keys JSON ({exc})") from None
    if not isinstance(entries, list) or not all(
            isinstance(e, dict) for e in entries):
        raise SystemExit(
            f"{path}: expected a JSON list of objects — refusing to "
            f"patch")
    renamed = 0
    for entry in entries:
        if "mapsquare" in entry:
            entry["region"] = entry.pop("mapsquare")
            renamed += 1
        if "key" in entry:
            entry["keys"] = entry.pop("key")
            renamed += 1
    if renamed:
        path.write_text(json.dumps(entries, indent=2) + "\n")
    return renamed


def do_cache() -> int:
    """Download the latest OSRS cache and patch keys.json."""
    check_tools(["curl", "jq", "unzip"])
    script = REPO / "collision-map-update" / "download-latest-cache.sh"
    # The script writes ./cache, ./cache.zip and ./keys.json into its
    # working directory — pin cwd to the repo root so the artifacts land
    # where every consumer (dumpers, the keys patch, .gitignore)
    # expects them.
    proc = run([str(script)], cwd=REPO, timeout=DOWNLOAD_TIMEOUT_SECONDS)
    if proc.returncode != 0:
        print(f"download-latest-cache.sh failed "
              f"(rc={proc.returncode})", file=sys.stderr)
        tail = _stderr_tail(proc)
        if tail:
            print(tail, file=sys.stderr)
        return 1
    keys_path = REPO / "keys.json"
    if not keys_path.exists():
        print(f"download finished but {keys_path} is missing",
              file=sys.stderr)
        return 1
    renamed = patch_keys_json(keys_path)
    cache_dir = REPO / "cache"
    if not cache_dir.is_dir():
        print(f"download finished but {cache_dir} is missing",
              file=sys.stderr)
        return 1
    entries = sum(1 for _ in cache_dir.iterdir())
    print(f"cache/: {entries} entries, keys.json: {renamed} fields "
          f"renamed")
    return 0


def cmd_cache(args: argparse.Namespace) -> int:
    return do_cache()


def require_clean_submodule() -> None:
    """Refuse to bump or overwrite submodule data on a dirty worktree —
    an in-progress edit must never be silently mixed into a bump."""
    proc = run(["git", "-C", "shortest-path", "status", "--porcelain"],
               timeout=GIT_TIMEOUT_SECONDS)
    if (proc.stdout or "").strip():
        raise SystemExit(
            "submodule worktree is dirty — commit or stash before "
            "updating")


def do_collision_map(args: argparse.Namespace) -> int:
    """Primary path: fast-forward the submodule to upstream/master and
    print the edge diff against the previous collision-map.zip.

    Uses ``fetch`` + ``merge --ff-only`` rather than the remote-tracking
    submodule bump: that command detaches HEAD unconditionally, which
    would break the feature-branch precondition the data-writing
    subcommands rely on.  A fast-forward keeps a checked-out origin
    (fork) branch intact.
    """
    require_clean_submodule()
    old = run(["git", "-C", "shortest-path", "rev-parse", "HEAD"],
              timeout=GIT_TIMEOUT_SECONDS).stdout.strip()
    fetch = run(["git", "-C", "shortest-path", "fetch", "upstream"],
                timeout=GIT_TIMEOUT_SECONDS)
    if fetch.returncode != 0:
        print("git fetch failed in submodule:", file=sys.stderr)
        tail = _stderr_tail(fetch)
        if tail:
            print(tail, file=sys.stderr)
        return 1
    merge = run(["git", "-C", "shortest-path", "merge", "--ff-only",
                 "upstream/master"], timeout=GIT_TIMEOUT_SECONDS)
    if merge.returncode != 0:
        print("submodule diverged from upstream/master — merge or "
              "rebase it manually", file=sys.stderr)
        tail = _stderr_tail(merge)
        if tail:
            print(tail, file=sys.stderr)
        return 1
    new = run(["git", "-C", "shortest-path", "rev-parse", "HEAD"],
              timeout=GIT_TIMEOUT_SECONDS).stdout.strip()
    if new == old:
        print(f"collision-map.zip already up to date ({old[:7]})")
        return 0
    # The previous artifact lives in git history — extract it in binary
    # mode without touching the worktree, then diff old vs new.
    proc = run(["git", "-C", "shortest-path", "show",
                f"{old}:src/main/resources/collision-map.zip"],
               binary=True, timeout=GIT_TIMEOUT_SECONDS)
    if proc.returncode != 0:
        print(f"could not extract collision-map.zip at {old[:7]}:",
              file=sys.stderr)
        tail = _stderr_tail(proc)
        if tail:
            print(tail, file=sys.stderr)
        return 1
    build = REPO / "build"
    build.mkdir(parents=True, exist_ok=True)
    old_zip = build / "old-collision-map.zip"
    old_zip.write_bytes(proc.stdout)
    new_zip = (SUBMODULE / "src" / "main" / "resources" /
               "collision-map.zip")
    diff = run([sys.executable,
                str(REPO / "scripts" / "compare_collision_maps.py"),
                str(old_zip), str(new_zip)],
               timeout=SCRIPT_TIMEOUT_SECONDS)
    if diff.stdout:
        print(diff.stdout,
              end="" if diff.stdout.endswith("\n") else "\n")
    if diff.returncode != 0:
        tail = _stderr_tail(diff)
        if tail:
            print(tail, file=sys.stderr)
        return 1
    if args.commit:
        short = run(["git", "-C", "shortest-path", "rev-parse",
                     "--short", "HEAD"],
                    timeout=GIT_TIMEOUT_SECONDS).stdout.strip()
        add = run(["git", "add", "shortest-path"], cwd=REPO,
                  timeout=GIT_TIMEOUT_SECONDS)
        if add.returncode != 0:
            print("git add shortest-path failed:", file=sys.stderr)
            tail = _stderr_tail(add)
            if tail:
                print(tail, file=sys.stderr)
            return 1
        commit = run(
            ["git", "commit", "-m",
             f"chore: update shortest-path submodule to {short}"],
            cwd=REPO, timeout=GIT_TIMEOUT_SECONDS)
        if commit.returncode != 0:
            # A failed hook or "nothing to commit" must surface — an
            # unchecked failure here silently loses the gitlink bump.
            print("git commit of the gitlink bump failed:",
                  file=sys.stderr)
            tail = _stderr_tail(commit)
            if tail:
                print(tail, file=sys.stderr)
            return 1
    else:
        print()
        print("Next steps:")
        print("  git add shortest-path")
        print(f'  git commit -m "chore: update shortest-path submodule '
              f'to {new[:7]}"')
    return 0


def require_write_branch() -> None:
    """Refuse data writes unless the submodule sits on an origin (fork)
    feature branch — regenerated data must land on the fork and PR
    upstream, never on detached HEAD, master, or a branch tracking
    upstream."""
    branch = run(["git", "-C", "shortest-path", "rev-parse",
                  "--abbrev-ref", "HEAD"],
                 timeout=GIT_TIMEOUT_SECONDS).stdout.strip()
    if branch == "HEAD":
        raise SystemExit(
            "submodule is on a detached HEAD — create a feature branch "
            "first (`git -C shortest-path checkout -b <name>`)")
    if branch == "master":
        raise SystemExit(
            "data commits must land on an origin (fork) feature "
            "branch, not master — create one first")
    up = run(["git", "-C", "shortest-path", "rev-parse", "--abbrev-ref",
              "@{u}"], timeout=GIT_TIMEOUT_SECONDS)
    if up.returncode == 0:
        upstream = up.stdout.strip()
        if not upstream.startswith("origin/"):
            raise SystemExit(
                f"submodule branch tracks '{upstream}' — data commits "
                f"must land on a branch tracking origin (the fork)")
    else:
        print("warning: branch has no upstream — push to origin before "
              "committing data", file=sys.stderr)


def ensure_cache_ready(fetch: bool = False) -> None:
    """Guarantee ./cache and a patched keys.json exist.

    The keys patch is applied unconditionally — it is idempotent, so it
    also repairs a manually downloaded, still-unpatched keys.json.
    """
    cache_dir = REPO / "cache"
    keys_path = REPO / "keys.json"
    if not cache_dir.is_dir() or not keys_path.exists():
        if not fetch:
            raise SystemExit(
                "cache/ and keys.json are required — run "
                "`maintenance.py cache` first")
        if do_cache() != 0:
            raise SystemExit("cache download failed")
    patch_keys_json(keys_path)


def _precondition_fail(exc: SystemExit) -> int:
    """Convert a precondition helper's SystemExit into a subcommand
    return code, preserving its message on stderr."""
    print(exc.code if isinstance(exc.code, str)
          else "precondition failed", file=sys.stderr)
    return 1


def _print_stdout(proc: subprocess.CompletedProcess) -> None:
    if proc.stdout:
        print(proc.stdout, end="" if proc.stdout.endswith("\n") else "\n")


def f2p_consumer_present() -> bool:
    """True when the plugin side can consume f2p/regions.tsv — either
    the resources directory already exists or an F2p* class exists in
    the submodule's main sources.  The region dump is forward-looking
    tooling: until the consumer lands, the output stays staged in
    build/ instead of being committed as dead data."""
    if (SUBMODULE / "src/main/resources/f2p").is_dir():
        return True
    java_root = SUBMODULE / "src/main/java"
    return java_root.is_dir() and any(java_root.rglob("F2p*.java"))


def do_regions(args: argparse.Namespace) -> int:
    """Regenerate leagues/regions.tsv + f2p/regions.tsv from the cache
    and copy them into the submodule.  The league copy is unconditional
    (the plugin already ships that file); the f2p copy only runs when a
    plugin-side consumer exists or ``--f2p`` forces it."""
    try:
        ensure_cache_ready()
        require_write_branch()
        require_clean_submodule()
    except SystemExit as exc:
        return _precondition_fail(exc)

    proc = run(["./gradlew", "leagueRegionDump",
                f"-PleagueRegionsCacheDir={REPO / 'cache'}",
                f"-PleagueRegionsXteaPath={REPO / 'keys.json'}"],
               cwd=REPO, timeout=GRADLE_TIMEOUT_SECONDS)
    _print_stdout(proc)
    if proc.returncode != 0:
        tail = _stderr_tail(proc)
        if tail:
            print(tail, file=sys.stderr)
        return proc.returncode
    shutil.copyfile(REPO / "build" / "league-regions" / "regions.tsv",
                    SUBMODULE / "src/main/resources/leagues/regions.tsv")
    print("updated shortest-path/src/main/resources/leagues/regions.tsv")

    proc = run(["./gradlew", "f2pRegionDump",
                f"-Pf2pRegionsCacheDir={REPO / 'cache'}",
                f"-Pf2pRegionsXteaPath={REPO / 'keys.json'}"],
               cwd=REPO, timeout=GRADLE_TIMEOUT_SECONDS)
    _print_stdout(proc)
    if proc.returncode != 0:
        tail = _stderr_tail(proc)
        if tail:
            print(tail, file=sys.stderr)
        return proc.returncode

    if args.f2p or f2p_consumer_present():
        f2p_dir = SUBMODULE / "src/main/resources/f2p"
        f2p_dir.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(REPO / "build" / "f2p-regions" / "regions.tsv",
                        f2p_dir / "regions.tsv")
        print("updated shortest-path/src/main/resources/f2p/regions.tsv")
    else:
        print("f2p regions.tsv staged at build/f2p-regions/regions.tsv "
              "— plugin-side F2P consumer not merged yet; pass --f2p "
              "to force the copy")
    return 0


def cmd_regions(args: argparse.Namespace) -> int:
    return do_regions(args)


def do_bank(args: argparse.Namespace) -> int:
    """Regenerate bank tile placements and merge them into bank.tsv.
    The dump must write to its default output path — that is exactly
    the PLACEMENTS_TSV constant rebuild_bank_tsv.py reads, so no
    ``-PbankTileOutput`` override is passed."""
    try:
        ensure_cache_ready()
        require_write_branch()
        require_clean_submodule()
    except SystemExit as exc:
        return _precondition_fail(exc)

    proc = run(["./gradlew", "bankTileDump",
                f"-PbankTileCacheDir={REPO / 'cache'}",
                f"-PbankTileXteaPath={REPO / 'keys.json'}"],
               cwd=REPO, timeout=GRADLE_TIMEOUT_SECONDS)
    _print_stdout(proc)
    if proc.returncode != 0:
        tail = _stderr_tail(proc)
        if tail:
            print(tail, file=sys.stderr)
        return proc.returncode

    proc = run([sys.executable,
                str(REPO / "scripts" / "rebuild_bank_tsv.py")],
               cwd=REPO, timeout=SCRIPT_TIMEOUT_SECONDS)
    _print_stdout(proc)
    if proc.returncode != 0:
        tail = _stderr_tail(proc)
        if tail:
            print(tail, file=sys.stderr)
    return proc.returncode


def cmd_bank(args: argparse.Namespace) -> int:
    return do_bank(args)


def do_seasonal(args: argparse.Namespace) -> int:
    """Run the seasonal region verification against the submodule's
    seasonal_transports.tsv.  Read-only — no write preconditions; the
    script's return code propagates."""
    proc = run([sys.executable,
                str(REPO / "scripts" / "verify_seasonal_regions.py")],
               cwd=REPO, timeout=SCRIPT_TIMEOUT_SECONDS)
    _print_stdout(proc)
    if proc.returncode != 0:
        tail = _stderr_tail(proc)
        if tail:
            print(tail, file=sys.stderr)
    return proc.returncode


def cmd_seasonal(args: argparse.Namespace) -> int:
    return do_seasonal(args)


# Season-discovery probes: (gradle task, cache-prop prefix,
# xtea-prop prefix or None, names-file prop or None).  The eight
# default-tier tasks run unconditionally; NAMES_FILE_TASKS only run
# when --names-file supplies a canonical destination list.
PROBE_TASKS: List[Tuple[str, str, Optional[str], Optional[str]]] = [
    ("leagueIdProbe", "leagueId", "leagueId", None),
    ("leagueTeleportItemDump", "leagueTeleportItem",
     "leagueTeleportItem", None),
    ("leagueAreaStructDump", "leagueAreaStruct", "leagueAreaStruct",
     None),
    ("leagueScriptScan", "leagueScript", "leagueScript", None),
    ("briefcaseEnumProbe", "briefcaseEnum", "briefcaseEnum", None),
    ("briefcaseParamScriptScan", "briefcaseParam", "briefcaseParam",
     None),
    ("briefcaseTeleportTables", "briefcaseTt", None, None),
    ("sailingAmenityVarbitDump", "sailingAmenity", "sailingAmenity",
     None),
]

NAMES_FILE_TASKS: List[Tuple[str, str, Optional[str], Optional[str]]] = [
    ("briefcaseDestOverlapScan", "briefcaseDest", "briefcaseDest",
     "briefcaseDestNamesFile"),
    ("briefcaseStructHunt", "briefcaseStruct", None,
     "briefcaseStructNamesFile"),
    ("briefcaseDbRowScan", "briefcaseDb", None,
     "briefcaseDbNamesFile"),
]


def do_probes(args: argparse.Namespace) -> int:
    """Run every season-discovery dumper sequentially — each requests
    an 8 GB heap, so they are deliberately never parallelized.
    Probes write only to build/ and stdout, so there is no branch gate.
    A failing probe is recorded and the rest still run: one broken
    scan must not hide the remaining discovery output."""
    try:
        ensure_cache_ready()
    except SystemExit as exc:
        return _precondition_fail(exc)

    tasks = list(PROBE_TASKS)
    if args.names_file is not None:
        # Resolve against the process CWD once — the -P...File= string
        # handed to Gradle would otherwise resolve a relative path
        # against the project dir and miss the file just checked.
        args.names_file = args.names_file.resolve()
        if not args.names_file.exists():
            print(f"--names-file {args.names_file} does not exist",
                  file=sys.stderr)
            return 1
        tasks.extend(NAMES_FILE_TASKS)

    failed = []
    for task, cache_prop, xtea_prop, names_prop in tasks:
        argv = ["./gradlew", task,
                f"-P{cache_prop}CacheDir={REPO / 'cache'}"]
        if xtea_prop:
            argv.append(f"-P{xtea_prop}XteaPath={REPO / 'keys.json'}")
        if names_prop:
            argv.append(f"-P{names_prop}={args.names_file}")
        proc = run(argv, cwd=REPO, timeout=GRADLE_TIMEOUT_SECONDS)
        _print_stdout(proc)
        if proc.returncode != 0:
            tail = _stderr_tail(proc)
            if tail:
                print(tail, file=sys.stderr)
            failed.append((task, proc.returncode))

    if failed:
        print("failed probes:", file=sys.stderr)
        for task, rc in failed:
            print(f"  {task}: rc={rc}", file=sys.stderr)
        return 1
    print("all probes ok")
    return 0


def cmd_probes(args: argparse.Namespace) -> int:
    return do_probes(args)


def do_refresh(args: argparse.Namespace) -> int:
    """Run the derivable update chain end-to-end in fixed order:
    collision-map -> cache -> regions -> bank -> seasonal.

    The order is deliberate: rebuild_bank_tsv.py walks the submodule's
    current collision-map.zip for stand-tile reachability, so the map
    update must precede ``bank``; ``cache`` precedes every dumper that
    reads it.  The write-branch gate runs before even the collision
    step — refresh exists to land data, and on master the ff-merge
    would advance the wrong branch."""
    try:
        require_write_branch()
        require_clean_submodule()
    except SystemExit as exc:
        return _precondition_fail(exc)

    steps = []
    if not args.skip_collision:
        # Dispatch through the cmd_* wrapper — it owns the --local
        # branch to the local runelite pipeline.
        steps.append(("collision-map", lambda: cmd_collision_map(args)))
    steps.extend([
        ("cache", lambda: do_cache()),
        ("regions", lambda: do_regions(args)),
        ("bank", lambda: do_bank(args)),
        ("seasonal", lambda: do_seasonal(args)),
    ])
    for name, step in steps:
        rc = step()
        if rc != 0:
            print(f"refresh aborted at {name}", file=sys.stderr)
            return rc
    print("refresh complete")
    return 0


def cmd_refresh(args: argparse.Namespace) -> int:
    return do_refresh(args)


def do_collision_map_local(args: argparse.Namespace) -> int:
    """Fallback path: regenerate collision-map.zip locally through the
    runelite pipeline, mirroring the upstream ExtractCollisionMap
    workflow's six steps.  All scratch state lives under ``build/``.

    ``--compare-only`` runs the same pipeline but diverts the artifact
    to ``build/validate-collision-map.zip`` and diffs it against the
    current worktree zip — the deep audit proves artifact freshness
    without touching the submodule, so the write-branch and
    clean-worktree gates (which exist to protect data-landing) do not
    apply.  The cache fetch is kept: a stale cache would audit the
    wrong game state."""
    compare_only = getattr(args, "compare_only", False)
    check_tools(["git", "java", "zip"])
    if not compare_only:
        require_write_branch()
        require_clean_submodule()
    ensure_cache_ready(fetch=True)

    build = REPO / "build"
    build.mkdir(parents=True, exist_ok=True)
    new_zip = (SUBMODULE / "src" / "main" / "resources" /
               "collision-map.zip")
    old_zip = build / "old-collision-map.zip"
    # Track whether the baseline was captured this run — a leftover
    # old_zip from a previous run must never become the diff baseline
    # when the worktree has no committed collision-map.zip.
    baseline = None
    if new_zip.exists():
        # Preserve the outgoing artifact before anything can overwrite
        # it — it is the diff baseline at the end of the pipeline.
        shutil.copy2(new_zip, old_zip)
        baseline = old_zip

    work = build / "runelite-work"
    runelite = work / "runelite"
    if (runelite / ".git").exists():
        fetch = run(["git", "-C", str(runelite), "fetch", "--depth",
                     "1", "origin", "master"],
                    timeout=GIT_TIMEOUT_SECONDS)
        if fetch.returncode != 0:
            print("git fetch of runelite master failed:",
                  file=sys.stderr)
            tail = _stderr_tail(fetch)
            if tail:
                print(tail, file=sys.stderr)
            return 1
        reset = run(["git", "-C", str(runelite), "reset", "--hard",
                     "FETCH_HEAD"], timeout=GIT_TIMEOUT_SECONDS)
        if reset.returncode != 0:
            # Resetting against a stale or absent FETCH_HEAD would
            # build whatever code happens to be checked out.
            print("git reset --hard FETCH_HEAD failed in the "
                  "runelite clone:", file=sys.stderr)
            tail = _stderr_tail(reset)
            if tail:
                print(tail, file=sys.stderr)
            return 1
    else:
        work.mkdir(parents=True, exist_ok=True)
        clone = run(["git", "clone", "--depth", "1",
                     "https://github.com/runelite/runelite",
                     str(runelite)], timeout=DOWNLOAD_TIMEOUT_SECONDS)
        if clone.returncode != 0:
            print("git clone of runelite failed:", file=sys.stderr)
            tail = _stderr_tail(clone)
            if tail:
                print(tail, file=sys.stderr)
            return 1

    cache_mod = runelite / "cache"
    shutil.copyfile(
        REPO / "collision-map-update" / "CollisionMapDumper.java",
        cache_mod / "src" / "main" / "java" / "net" / "runelite" /
        "cache" / "CollisionMapDumper.java")
    shutil.copyfile(
        REPO / "collision-map-update" / "build.gradle.kts.patch",
        cache_mod / "build.gradle.kts.patch")
    check = run(["git", "apply", "--check", "build.gradle.kts.patch"],
                cwd=cache_mod, timeout=GIT_TIMEOUT_SECONDS)
    if check.returncode != 0:
        reverse = run(["git", "apply", "--reverse", "--check",
                       "build.gradle.kts.patch"],
                      cwd=cache_mod, timeout=GIT_TIMEOUT_SECONDS)
        if reverse.returncode == 0:
            print("build.gradle.kts.patch already applied — skipping")
        else:
            print("git apply --check failed for "
                  "build.gradle.kts.patch:", file=sys.stderr)
            tail = _stderr_tail(check)
            if tail:
                print(tail, file=sys.stderr)
            return 1
    else:
        apply = run(["git", "apply", "build.gradle.kts.patch"],
                    cwd=cache_mod, timeout=GIT_TIMEOUT_SECONDS)
        if apply.returncode != 0:
            # --check passed but the real apply failed (the tree moved
            # under us) — building without the patch is worse.
            print("git apply of build.gradle.kts.patch failed:",
                  file=sys.stderr)
            tail = _stderr_tail(apply)
            if tail:
                print(tail, file=sys.stderr)
            return 1

    # The patch pins a Java 11 toolchain; Gradle toolchain
    # auto-provisioning resolves it when only a newer JDK is installed.
    print("note: the runelite build uses a Java 11 toolchain — Gradle "
          "auto-provisions it on first run")
    proc = run([str(runelite / "gradlew"), ":cache:shadowJar", "-x",
                "test", "--dependency-verification=off"],
               cwd=runelite, timeout=RUNELITE_BUILD_TIMEOUT_SECONDS)
    if proc.returncode != 0:
        print("runelite :cache:shadowJar failed:", file=sys.stderr)
        tail = _stderr_tail(proc)
        if tail:
            print(tail, file=sys.stderr)
        return 1

    jars = list((cache_mod / "build" / "libs").glob("*-all.jar"))
    if len(jars) != 1:
        libs = cache_mod / "build" / "libs"
        print(f"expected exactly one *-all.jar under {libs}, "
              f"found {len(jars)}", file=sys.stderr)
        return 1
    jar = build / "cache.jar"
    shutil.copyfile(jars[0], jar)

    output_dir = build / "collision-output"
    # Rebuild from scratch — leftovers from a previous run (including a
    # stale collision-map.zip, which `zip -r` would update rather than
    # recreate) must not leak into the artifact committed upstream.
    if output_dir.exists():
        shutil.rmtree(output_dir)
    output_dir.mkdir(parents=True)
    proc = run(["java", "-jar", str(jar),
                "--cachedir", str(REPO / "cache"),
                "--xteapath", str(REPO / "keys.json"),
                "--outputdir", str(output_dir)],
               timeout=JAVA_DUMPER_TIMEOUT_SECONDS)
    if proc.returncode != 0:
        print("CollisionMapDumper failed:", file=sys.stderr)
        tail = _stderr_tail(proc)
        if tail:
            print(tail, file=sys.stderr)
        return 1

    proc = run(["zip", "-r", "collision-map.zip", "."],
               cwd=output_dir, timeout=SCRIPT_TIMEOUT_SECONDS)
    if proc.returncode != 0:
        print("zip of collision map output failed:", file=sys.stderr)
        tail = _stderr_tail(proc)
        if tail:
            print(tail, file=sys.stderr)
        return 1
    # The move target is computed once — compare-only must never
    # write the submodule path.
    target = (build / "validate-collision-map.zip") if compare_only \
        else new_zip
    shutil.move(str(output_dir / "collision-map.zip"), str(target))

    if baseline is not None and baseline.exists():
        diff = run([sys.executable,
                    str(REPO / "scripts" / "compare_collision_maps.py"),
                    str(baseline), str(target)],
                   timeout=SCRIPT_TIMEOUT_SECONDS)
        if diff.stdout:
            print(diff.stdout,
                  end="" if diff.stdout.endswith("\n") else "\n")
        if compare_only:
            print("compare-only: regenerated artifact at "
                  "build/validate-collision-map.zip (submodule "
                  "untouched)")
        else:
            print("review the diff, then commit on your origin "
                  "(fork) feature branch and open a PR upstream")
    else:
        # No baseline artifact existed — nothing was diffed.
        if compare_only:
            print("compare-only: no committed baseline to diff "
                  "against")
        else:
            print("no previous collision-map.zip to diff against — "
                  "commit on your origin (fork) feature branch and "
                  "open a PR upstream")
    return 0


def cmd_collision_map(args: argparse.Namespace) -> int:
    # getattr: do_refresh dispatches here with a namespace that has no
    # compare_only attribute.
    if getattr(args, "compare_only", False) and not args.local:
        print("--compare-only requires --local", file=sys.stderr)
        return 1
    if args.local:
        return do_collision_map_local(args)
    return do_collision_map(args)


def scan_report(path: Path) -> List[str]:
    """Extract failure lines from a dashboard bundle's report.json.

    report.json is the only pass/fail surface for the dashboard task —
    ``ignoreFailures = true`` plus zero hard assertions in DashboardTest
    mean a green Gradle exit carries no scenario signal.  A missing or
    unparseable report therefore fails closed: it returns a failure
    naming the path rather than an empty (passing) list.
    """
    try:
        data = json.loads(Path(path).read_text())
    except (json.JSONDecodeError, OSError):
        return [f"missing or unreadable report: {path}"]
    # Valid JSON of the wrong shape ([], "text", 42, or a dict whose
    # "runs" is not a list) must fail closed too — an AttributeError
    # here would crash the whole verify run instead of failing the
    # dashboard tier.
    if not isinstance(data, dict):
        return [f"unexpected report shape: {path}"]
    runs = data.get("runs")
    if runs is not None and not isinstance(runs, list):
        return [f"unexpected report shape: {path}"]
    failures = []
    for r in runs or []:
        if not isinstance(r, dict):
            failures.append(f"malformed run record in {path}")
            continue
        if not r.get("reached"):
            failures.append(f"{r.get('name')}: unreachable")
        elif r.get("assertionPassed") is False:
            failures.append(
                f"{r.get('name')}: {r.get('assertionMessage')}")
    return failures


def dashboard_datasets() -> List[str]:
    """Committed dashboard scenario CSV basenames, sorted.

    The sweep enumerates ``git ls-files`` — never the filesystem — so
    gitignored scratch state (``debug.csv``) can never leak into the
    gate, and newly committed datasets are picked up automatically.
    """
    proc = run(["git", "ls-files", "src/test/resources/dashboard/"],
               cwd=REPO, timeout=GIT_TIMEOUT_SECONDS)
    # Only *.csv files are datasets — a committed README/.gitignore
    # under dashboard/ must not become a phantom bundle.
    return sorted(
        Path(line).name
        for line in (proc.stdout or "").splitlines()
        if line.strip().endswith(".csv"))


def do_verify(args: argparse.Namespace) -> int:
    """Four-tier compatibility gate: compile -> submodule test ->
    dashboard sweep -> collision edge-diff.

    Every tier runs even when an earlier one fails — the maintainer
    wants the whole picture, not just the first red.  The compile and
    lint tiers judge on exit codes; the dashboard tier parses each
    bundle's report.json (the task's ``ignoreFailures = true`` makes
    Gradle's exit code meaningless); the edge-diff tier fails only when
    an artifact cannot be materialized or the compare tool errors — a
    non-empty diff is review evidence, not a failure.  All evidence
    stays under ``build/``; nothing here writes a committed log.
    """
    tiers = {}  # name -> list of failure lines (only ran tiers)

    if not args.skip_compile:
        proc = run(["./gradlew", "compileTestJava"], cwd=REPO,
                   timeout=GRADLE_TIMEOUT_SECONDS)
        tiers["compile"] = ([] if proc.returncode == 0 else [
            f"compileTestJava exited {proc.returncode}"])

    if not args.skip_lint:
        # The whole submodule test task — strictly more coverage than
        # *LintTest alone.
        proc = run(["./gradlew", "-p", "shortest-path", "test"],
                   cwd=REPO, timeout=GRADLE_TIMEOUT_SECONDS)
        tiers["lint"] = ([] if proc.returncode == 0 else [
            f"submodule test exited {proc.returncode}"])

    if not args.skip_dashboard:
        failures = []
        datasets = dashboard_datasets()
        if not datasets:
            # An empty sweep is a broken gate, not a green one.
            failures.append(
                "no committed dashboard datasets found via "
                "git ls-files")
        for csv in datasets:
            argv = ["./gradlew", "dashboard",
                    f"-PdashboardDataset=/dashboard/{csv}",
                    "-PdashboardProfile=false"]
            # Belt-and-braces over the slug auto-tagging — a renamed
            # dataset would otherwise lose its overlay silently.
            if csv.startswith("seasonal_"):
                argv.append("-PdashboardSeasonal=true")
            if csv.startswith("f2p_"):
                argv.append("-PdashboardF2p=true")
            run(argv, cwd=REPO, timeout=GRADLE_TIMEOUT_SECONDS)
            # profile=false makes the bundle name equal the slug —
            # the same derivation dashboards.gradle applies.
            slug = Path(csv).stem.lower().replace("_", "-")
            report = (REPO / "build" / "reports" /
                      "pathfinder-dashboard" / slug / "report.json")
            failures.extend(scan_report(report))
        tiers["dashboard"] = failures

    if not args.skip_diff:
        failures = []
        if args.old_zip is not None and args.new_zip is not None:
            old_zip, new_zip = args.old_zip, args.new_zip
        else:
            old_zip = REPO / "build" / "verify-old-collision-map.zip"
            new_zip = (SUBMODULE / "src" / "main" / "resources" /
                       "collision-map.zip")
            tree = run(["git", "ls-tree", "HEAD", "shortest-path"],
                       cwd=REPO, timeout=GIT_TIMEOUT_SECONDS)
            fields = (tree.stdout or "").split()
            # fields[1] must be "commit" — a plain tree entry would
            # mean shortest-path is no longer a submodule, and
            # git show of a tree SHA would fail with a confusing
            # "could not extract" instead of this diagnostic.
            if (tree.returncode != 0 or len(fields) < 3
                    or fields[1] != "commit"):
                failures.append(
                    "could not resolve the pinned submodule gitlink "
                    "via git ls-tree")
            else:
                sha = fields[2]
                show = run(["git", "-C", "shortest-path", "show",
                            f"{sha}:src/main/resources/"
                            "collision-map.zip"],
                           binary=True, timeout=GIT_TIMEOUT_SECONDS)
                if show.returncode != 0:
                    failures.append(
                        f"could not extract collision-map.zip at "
                        f"{sha[:7]}")
                else:
                    old_zip.parent.mkdir(parents=True, exist_ok=True)
                    old_zip.write_bytes(show.stdout)
        if not failures:
            missing = [p for p in (old_zip, new_zip) if not p.exists()]
            if missing:
                failures.append(
                    f"missing collision-map artifact: {missing[0]}")
            elif old_zip.read_bytes() == new_zip.read_bytes():
                print("collision-map: no edge changes")
            else:
                diff = run([sys.executable,
                            str(REPO / "scripts" /
                                "compare_collision_maps.py"),
                            str(old_zip), str(new_zip)],
                           timeout=SCRIPT_TIMEOUT_SECONDS)
                _print_stdout(diff)
                if diff.returncode != 0:
                    tail = _stderr_tail(diff)
                    failures.append(
                        tail.splitlines()[-1] if tail else
                        f"compare_collision_maps.py exited "
                        f"{diff.returncode}")
        tiers["diff"] = failures

    failed = 0
    passed = 0
    for name in ("compile", "lint", "dashboard", "diff"):
        if name not in tiers:
            print(f"SKIP {name}")
            continue
        failures = tiers[name]
        if failures:
            failed += 1
            print(f"FAIL {name}: {failures[0]}")
            for extra in failures[1:]:
                print(f"  {extra}")
        else:
            passed += 1
            print(f"PASS {name}")
    print(f"verify: {passed}/{len(tiers)} tiers passed")
    return 1 if failed else 0


def cmd_verify(args: argparse.Namespace) -> int:
    return do_verify(args)


# Deterministic data-validation checks.  Each entry is a (name, kind)
# pair: "leaf" invokes scripts/validate_data.py <name>; "internal"
# calls a module function in INTERNAL_CHECKS returning (findings,
# report detail); "drift" and "season" are the flag-gated advisory
# runners below.  Hard checks gate the exit code; advisory checks
# report findings but can never reach the failed count — keep the two
# registries disjoint.
VALIDATE_HARD_CHECKS: Tuple[Tuple[str, str], ...] = (
    ("tsv-structure", "leaf"), ("collision-zip", "leaf"),
    ("walkability", "leaf"), ("bbox", "leaf"), ("regions", "leaf"),
    ("scenario-csv", "leaf"), ("freshness", "internal"))
VALIDATE_ADVISORY_CHECKS: Tuple[Tuple[str, str], ...] = (
    ("destinations", "leaf"), ("drift", "drift"), ("season", "season"))

CACHES_JSON_URL = "https://archive.openrs2.org/caches.json"
SEASON_MARKER = REPO / "src" / "test" / "resources" / "season_active"
# Upstream's weekly ExtractCollisionMap run auto-commits under this
# subject; the freshness check anchors on it.
_AUTO_COMMIT_GREP = "Update collision map"
# The league probes the season tier runs when a cache is prepared —
# the same (task, cache-prop, xtea-prop) tuple shape do_probes uses.
SEASON_PROBE_TASKS: List[Tuple[str, str, Optional[str],
                               Optional[str]]] = [
    p for p in PROBE_TASKS
    if p[0] in ("leagueAreaStructDump", "leagueScriptScan")]


def _check_freshness() -> Tuple[List[str], Optional[str]]:
    """Collision-map currency check: compare the newest live-cache
    timestamp in caches.json against the newest upstream auto-commit,
    then check the submodule pin is not behind upstream/master.

    Fails closed — every unresolvable input (missing curl, an
    unreachable upstream remote, an unparseable caches.json, an empty
    auto-commit history) produces a finding naming what could not be
    determined rather than a silent pass.  Returns (findings, detail);
    detail carries both timestamps so the report line is diagnostic
    either way."""
    try:
        check_tools(["curl"])
    except SystemExit as exc:
        return ([exc.code if isinstance(exc.code, str)
                 else "missing required tools"], None)
    fetch = run(["git", "-C", "shortest-path", "fetch", "upstream"],
                cwd=REPO, timeout=GIT_TIMEOUT_SECONDS)
    if fetch.returncode != 0:
        tail = _stderr_tail(fetch)
        suffix = f": {tail.splitlines()[-1]}" if tail else ""
        return ([f"cannot fetch the upstream remote{suffix}"], None)
    proc = run(["curl", "-s", CACHES_JSON_URL], cwd=REPO,
               timeout=SCRIPT_TIMEOUT_SECONDS)
    if proc.returncode != 0:
        return ([f"cannot fetch {CACHES_JSON_URL} "
                 f"(curl exited {proc.returncode})"], None)
    try:
        entries = json.loads(proc.stdout or "")
    except json.JSONDecodeError:
        return ([f"cannot parse {CACHES_JSON_URL} as JSON"], None)
    if not isinstance(entries, list):
        return ([f"unexpected shape in {CACHES_JSON_URL} "
                 "(expected a JSON list)"], None)
    timestamps = []
    for entry in entries:
        if not isinstance(entry, dict):
            continue
        if (entry.get("game") != "oldschool"
                or entry.get("environment") != "live"):
            continue
        ts = entry.get("timestamp")
        if ts is None:  # documented nullable field
            continue
        try:
            timestamps.append(datetime.fromisoformat(ts))
        except (TypeError, ValueError):
            return ([f"unparseable timestamp {ts!r} in "
                     f"{CACHES_JSON_URL}"], None)
    if not timestamps:
        return ([f"no live oldschool caches with timestamps in "
                 f"{CACHES_JSON_URL}"], None)
    latest_cache = max(timestamps)

    log = run(["git", "-C", "shortest-path", "log", "upstream/master",
               f"--grep={_AUTO_COMMIT_GREP}", "-1", "--format=%cI"],
              cwd=REPO, timeout=GIT_TIMEOUT_SECONDS)
    if log.returncode != 0:
        tail = _stderr_tail(log)
        suffix = f": {tail.splitlines()[-1]}" if tail else ""
        return ([f"cannot read upstream/master history{suffix}"],
                None)
    auto = (log.stdout or "").strip()
    if not auto:
        return (["no 'Update collision map' auto-commit on "
                 "upstream/master — upstream's weekly regeneration "
                 "CI may be dead"], None)
    try:
        last_commit = datetime.fromisoformat(auto)
    except ValueError:
        return ([f"unparseable auto-commit date {auto!r}"], None)
    detail = (f"latest cache {latest_cache.isoformat()}, "
              f"last auto-commit {last_commit.isoformat()}")

    findings = []
    try:
        gap = latest_cache - last_commit
    except TypeError:
        # One timestamp naive, the other aware — incomparable.
        return ([f"incomparable timestamps {latest_cache} vs "
                 f"{last_commit}"], detail)
    if gap > timedelta(days=7):
        findings.append(
            f"collision map missed its weekly bump — the newest "
            f"live cache is {gap.days} days newer than the last "
            f"auto-commit")
    # The check measures the *recorded pin* — the commit the
    # superproject's gitlink points at — not the worktree HEAD, which
    # can sit on a feature branch or ahead of an uncommitted bump.
    tree = run(["git", "ls-tree", "HEAD", "shortest-path"],
               cwd=REPO, timeout=GIT_TIMEOUT_SECONDS)
    fields = (tree.stdout or "").split()
    if (tree.returncode != 0 or len(fields) < 3
            or fields[1] != "commit"):
        findings.append("could not resolve the pinned submodule "
                        "gitlink via git ls-tree")
    else:
        ahead = run(["git", "-C", "shortest-path", "log",
                     f"{fields[2]}..upstream/master",
                     f"--grep={_AUTO_COMMIT_GREP}", "--format=%H"],
                    cwd=REPO, timeout=GIT_TIMEOUT_SECONDS)
        if ahead.returncode != 0:
            findings.append("cannot compare the submodule pin against "
                            "upstream/master")
        else:
            commits = [l for l in (ahead.stdout or "").splitlines()
                       if l.strip()]
            if commits:
                findings.append(
                    f"submodule pin is {len(commits)} collision-map "
                    f"commits behind upstream/master — bump the gitlink "
                    f"per docs/maintenance.md")
    return findings, detail


# Internal hard-check runners: name -> zero-arg callable returning
# (findings, report detail).
INTERNAL_CHECKS = {"freshness": _check_freshness}


def _run_drift(args: argparse.Namespace) -> Tuple[str, str]:
    """The opt-in cache-diff detector tier: runs transportAnchorDrift
    only when explicitly requested and a prepared cache exists —
    routine validate stays download-free.  Returns a (status, detail)
    pair for the report loop."""
    if not getattr(args, "drift", False):
        return ("skip", " — pass --drift to run the cache-diff "
                        "detector")
    if not ((REPO / "cache").is_dir()
            and (REPO / "keys.json").exists()):
        return ("advisory",
                "skipped — cache/ and keys.json required (run "
                "maintenance.py cache)")
    proc = run(["./gradlew", "transportAnchorDrift",
                f"-PtransportDriftCacheDir={REPO / 'cache'}",
                f"-PtransportDriftXteaPath={REPO / 'keys.json'}"],
               cwd=REPO, timeout=GRADLE_TIMEOUT_SECONDS)
    _print_stdout(proc)
    if proc.returncode != 0:
        tail = _stderr_tail(proc)
        if tail:
            print(tail, file=sys.stderr)
        return ("advisory", f"detector error rc={proc.returncode}")
    return ("advisory", "report at build/transport-drift.txt")


def _season_label() -> Optional[str]:
    """First non-comment line of the committed season marker — the
    league name reported alongside the tier's output."""
    try:
        for line in SEASON_MARKER.read_text().splitlines():
            line = line.strip()
            if line and not line.startswith("#"):
                return line
    except OSError:
        pass
    return None


def _run_season(args: argparse.Namespace) -> Tuple[str, str]:
    """The gated league-season tier: the wiki cross-check always, the
    two league cache probes when a prepared cache exists.  Gated on
    --season-active or the committed marker — between seasons the
    checks go silent or noisy, so they only ever feed the runbook."""
    if not (getattr(args, "season_active", False)
            or SEASON_MARKER.exists()):
        return ("skip",
                " — no active league season (pass --season-active or "
                "commit src/test/resources/season_active)")
    label = _season_label()
    proc = run([sys.executable,
                str(REPO / "scripts" / "verify_seasonal_regions.py")],
               cwd=REPO, timeout=SCRIPT_TIMEOUT_SECONDS)
    _print_stdout(proc)
    if (REPO / "cache").is_dir() and (REPO / "keys.json").exists():
        for task, cache_prop, xtea_prop, _ in SEASON_PROBE_TASKS:
            argv = ["./gradlew", task,
                    f"-P{cache_prop}CacheDir={REPO / 'cache'}"]
            if xtea_prop:
                argv.append(
                    f"-P{xtea_prop}XteaPath={REPO / 'keys.json'}")
            probe = run(argv, cwd=REPO,
                        timeout=GRADLE_TIMEOUT_SECONDS)
            _print_stdout(probe)
        print("diff probe output against "
              "src/test/resources/leagues_regions.tsv per "
              "docs/maintenance.md")
        detail = "wiki cross-check + league probes ran"
    else:
        detail = ("wiki cross-check ran — cache absent, league "
                  "probes skipped (run maintenance.py cache)")
    if label:
        detail = f"{label}: {detail}"
    return ("advisory", detail)


def do_validate(args: argparse.Namespace) -> int:
    """Run the registered data-validation checks.

    Every check runs even when an earlier one fails — like the
    verify tiers, the maintainer wants the whole picture.  Leaf checks
    invoke validate_data.py; internal checks call a module function
    returning (findings, detail); the drift and season advisory tiers
    are flag/marker-gated runners that can never reach the exit code.
    Only hard-check results count toward the exit code.
    """
    leaf = REPO / "scripts" / "validate_data.py"
    results = {}   # ran hard checks: name -> list of failure lines
    details = {}   # ran hard checks: name -> report-line detail
    advisory = {}  # advisory checks: name -> (status, detail)

    for name, kind in VALIDATE_HARD_CHECKS:
        if getattr(args, "skip_" + name.replace("-", "_")):
            continue
        if kind == "internal":
            findings, detail = INTERNAL_CHECKS[name]()
            results[name] = findings
            if detail:
                details[name] = detail
            continue
        proc = run([sys.executable, str(leaf), name],
                   cwd=REPO, timeout=SCRIPT_TIMEOUT_SECONDS)
        _print_stdout(proc)
        if proc.returncode == 0:
            results[name] = []
        else:
            tail = _stderr_tail(proc)
            lines = (proc.stdout or "").strip().splitlines()
            results[name] = [
                tail.splitlines()[-1] if tail else
                (lines[-1] if lines else
                 f"validate_data.py {name} exited {proc.returncode}")]

    for name, kind in VALIDATE_ADVISORY_CHECKS:
        if getattr(args, "skip_" + name.replace("-", "_")):
            continue
        if kind == "leaf":
            proc = run([sys.executable, str(leaf), name],
                       cwd=REPO, timeout=SCRIPT_TIMEOUT_SECONDS)
            _print_stdout(proc)
            advisory[name] = ("advisory", "")
        elif kind == "drift":
            advisory[name] = _run_drift(args)
        elif kind == "season":
            advisory[name] = _run_season(args)

    failed = 0
    passed = 0
    for name, _kind in VALIDATE_HARD_CHECKS:
        if name not in results:
            print(f"SKIP {name}")
            continue
        failures = results[name]
        if failures:
            failed += 1
            print(f"FAIL {name}: {failures[0]}")
            for extra in failures[1:]:
                print(f"  {extra}")
            if details.get(name):
                print(f"  {details[name]}")
        else:
            passed += 1
            line = f"PASS {name}"
            if details.get(name):
                line += f": {details[name]}"
            print(line)
    for name, _kind in VALIDATE_ADVISORY_CHECKS:
        entry = advisory.get(name)
        if entry is None:
            print(f"SKIP {name}")
            continue
        status, detail = entry
        if status == "skip":
            print(f"SKIP {name}{detail}")
        else:
            print(f"ADVISORY {name}"
                  + (f": {detail}" if detail else ""))
    print(f"validate: {passed}/{len(results)} checks passed")
    return 1 if failed else 0


def cmd_validate(args: argparse.Namespace) -> int:
    return do_validate(args)


def main(argv: Optional[List[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    sub = ap.add_subparsers(dest="cmd", required=True)

    sub.add_parser(
        "cache",
        help="Download the latest OSRS cache and patch keys.json")

    cm = sub.add_parser(
        "collision-map",
        help="Update the submodule's collision-map.zip and print an "
             "edge diff for review")
    cm.add_argument(
        "--local", action="store_true",
        help="Regenerate the zip through the local runelite pipeline "
             "instead of fast-forwarding to upstream's weekly artifact")
    cm.add_argument(
        "--commit", action="store_true",
        help="Commit the submodule gitlink bump after the diff")
    cm.add_argument(
        "--compare-only", action="store_true",
        help="With --local: regenerate into build/ and diff against "
             "the current artifact without touching the submodule")

    rg = sub.add_parser(
        "regions",
        help="Regenerate league + f2p region TSVs and copy them into "
             "the submodule")
    rg.add_argument(
        "--f2p", action="store_true",
        help="Force the f2p/regions.tsv copy even without a "
             "plugin-side consumer")

    sub.add_parser(
        "bank",
        help="Regenerate bank tile placements and merge them into "
             "bank.tsv")

    sub.add_parser(
        "seasonal",
        help="Verify seasonal transport region assignments against "
             "wiki ground truth")

    rf = sub.add_parser(
        "refresh",
        help="Run the derivable update chain end-to-end: "
             "collision-map -> cache -> regions -> bank -> seasonal")
    rf.add_argument(
        "--skip-collision", action="store_true",
        help="Omit the collision-map step (use when the zip is "
             "already current)")
    rf.add_argument(
        "--local", action="store_true",
        help="Run the collision step through the local runelite "
             "pipeline instead of fast-forwarding to upstream")
    rf.add_argument(
        "--f2p", action="store_true",
        help="Force the f2p/regions.tsv copy in the regions step")
    rf.set_defaults(commit=False)

    pb = sub.add_parser(
        "probes",
        help="Run every season-discovery cache dumper in sequence")
    pb.add_argument(
        "--names-file", type=Path, default=None,
        help="Canonical destination-name list (one per line) — also "
             "enables the three name-driven scans")

    vf = sub.add_parser(
        "verify",
        help="Run the compatibility gate: compile -> submodule test "
             "-> dashboard sweep over committed scenario CSVs -> "
             "collision-map edge-diff")
    vf.add_argument(
        "--skip-compile", action="store_true",
        help="Omit the compileTestJava tier")
    vf.add_argument(
        "--skip-lint", action="store_true",
        help="Omit the submodule test tier")
    vf.add_argument(
        "--skip-dashboard", action="store_true",
        help="Omit the dashboard sweep tier")
    vf.add_argument(
        "--skip-diff", action="store_true",
        help="Omit the collision-map edge-diff tier")
    vf.add_argument(
        "--old-zip", type=Path, default=None,
        help="Baseline collision-map.zip for the edge-diff — must be "
             "given together with --new-zip")
    vf.add_argument(
        "--new-zip", type=Path, default=None,
        help="Candidate collision-map.zip for the edge-diff — must "
             "be given together with --old-zip")

    vd = sub.add_parser(
        "validate",
        help="Run the data-validation checks: deterministic hard "
             "gate plus advisory drift/season tiers")
    vd.add_argument(
        "--skip-tsv-structure", action="store_true",
        help="Omit the TSV structure lint check")
    vd.add_argument(
        "--skip-collision-zip", action="store_true",
        help="Omit the collision-map.zip structural check")
    vd.add_argument(
        "--skip-walkability", action="store_true",
        help="Omit the transport-endpoint walkability check")
    vd.add_argument(
        "--skip-bbox", action="store_true",
        help="Omit the seasonal league-region bbox coverage check")
    vd.add_argument(
        "--skip-regions", action="store_true",
        help="Omit the generated leagues/regions.tsv consistency check")
    vd.add_argument(
        "--skip-scenario-csv", action="store_true",
        help="Omit the dashboard scenario CSV lint check")
    vd.add_argument(
        "--skip-freshness", action="store_true",
        help="Omit the collision-map freshness check (caches.json "
             "vs upstream auto-commit)")
    vd.add_argument(
        "--skip-destinations", action="store_true",
        help="Omit the advisory destination-walkability check")
    vd.add_argument(
        "--drift", action="store_true",
        help="Run the cache-backed transport-anchor drift detector "
             "(requires a prepared cache/ + keys.json)")
    vd.add_argument(
        "--season-active", action="store_true",
        help="Run the league-season tier even without the committed "
             "src/test/resources/season_active marker")
    vd.add_argument(
        "--skip-drift", action="store_true",
        help="Omit the drift tier entirely")
    vd.add_argument(
        "--skip-season", action="store_true",
        help="Omit the season tier entirely")

    args = ap.parse_args(argv)

    if args.cmd == "cache":
        return cmd_cache(args)
    if args.cmd == "collision-map":
        return cmd_collision_map(args)
    if args.cmd == "regions":
        return cmd_regions(args)
    if args.cmd == "bank":
        return cmd_bank(args)
    if args.cmd == "seasonal":
        return cmd_seasonal(args)
    if args.cmd == "refresh":
        return cmd_refresh(args)
    if args.cmd == "probes":
        return cmd_probes(args)
    if args.cmd == "verify":
        if (args.old_zip is None) != (args.new_zip is None):
            vf.error("--old-zip and --new-zip must be given together")
        return cmd_verify(args)
    if args.cmd == "validate":
        return cmd_validate(args)
    return 0


if __name__ == "__main__":
    sys.exit(main())
