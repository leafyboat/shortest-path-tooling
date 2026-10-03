#!/usr/bin/env python3
"""Deterministic data-validation checks for committed plugin data.

Inputs (all read-only):
- committed submodule TSVs, enumerated via
  ``git -C shortest-path ls-files src/main/resources`` — never a
  filesystem glob, so gitignored scratch can never leak into a check
- curated league-region bboxes at src/test/resources/leagues_regions.tsv
- the committed collision-map.zip

Outputs: ``file:line`` findings on stdout, a ``Summary:`` trailer, and
an exit code that is nonzero only when a ran hard check produced
findings — advisory checks report but never move the exit code.

Run standalone::

    python3 scripts/validate_data.py [check ...]
"""

import re
import subprocess
import sys
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
# Sibling helpers are plain scripts, not a package; the insert keeps
# the imports working under importlib-spec test loads.
sys.path.insert(0, str(HERE))
from collision_zip import CollisionMap, REGION_SIZE  # noqa: E402
from verify_seasonal_regions import (  # noqa: E402
    classify_chunk, classify_tile, load_bboxes)

REPO = HERE.parent
PLUGIN = REPO / "shortest-path"
RESOURCES = "src/main/resources"
BBOX_TSV = REPO / "src" / "test" / "resources" / "leagues_regions.tsv"
DESTINATION_EXCEPTIONS = (REPO / "src" / "test" / "resources"
                          / "destination_walkability_exceptions.tsv")
ANCHOR_EXCEPTIONS = (REPO / "src" / "test" / "resources"
                     / "transport_anchor_exceptions.tsv")
WALKABILITY_EXCEPTIONS = (REPO / "src" / "test" / "resources"
                          / "transport_walkability_exceptions.tsv")
BBOX_EXCEPTIONS = (REPO / "src" / "test" / "resources"
                   / "seasonal_bbox_exceptions.tsv")
COLLISION_ZIP = PLUGIN / RESOURCES / "collision-map.zip"

GIT_TIMEOUT_SECONDS = 120
COORD_RE = re.compile(r"^\d+ \d+ \d+$")
REGION_NAME_RE = re.compile(r"^\d+_\d+$")
BITS_PER_PLANE = REGION_SIZE * REGION_SIZE * 2
# The committed map covers thousands of regions; a handful of entries
# means a truncated artifact, not a healthy map.
MIN_REGION_COUNT = 1000

# Canonical transport TSV column names — mirrors
# TransportRecord.Fields in the plugin's Java parser.  The loader maps
# columns by name, so an unknown header cell is silently dropped
# data, not an error — which is exactly what this check must catch.
TRANSPORT_FIELDS = frozenset({
    "Origin", "Destination", "Skills", "Items", "Quests", "Duration",
    "Display info", "Consumable", "Wilderness level",
    "menuOption menuTarget objectID", "Varbits", "VarPlayers",
    "Region override",
})

# Canonical destination TSV column names — mirrors DOCUMENTED_COLUMNS
# in DestinationDataLintTest.  The Java lint only covers a few
# hard-coded resources, so this lint applies the set to every
# committed destinations/ file; a typo'd column name is silently
# dropped by the loader otherwise.
DESTINATION_FIELDS = frozenset({
    "Destination", "Info", "Skills", "Quests", "Varbits",
    "VarPlayers",
})

# Dashboard preset names — mirrors the PRESETS registry keys in
# src/test/java/shortestpath/dashboard/DashboardPresets.java.  The
# loader upper-cases the CSV cell before lookup, so matching is
# case-insensitive here as well.
SCENARIO_PRESETS = frozenset({
    "NONE", "ALL", "BANK", "BANK_PERM", "INVENTORY",
    "INVENTORY_NON_CONSUMABLE", "SEASONAL", "UNIT_TEST",
})

# League region enum names — mirrors the LeagueRegion enum in
# shortest-path/src/main/java/shortestpath/leagues/LeagueRegion.java.
# The plugin resolves `Region override` cells via
# LeagueRegion.valueOf and swallows IllegalArgumentException, so a
# typo'd cell silently becomes NEUTRAL (always-unlocked) — exact
# case-sensitive matching is the point of the check.
LEAGUE_REGIONS = frozenset({
    "VARLAMORE", "KARAMJA", "ASGARNIA", "KANDARIN", "FREMENNIK",
    "KOUREND", "WILDERNESS", "MORYTANIA", "DESERT", "TIRANNWN",
    "MISTHALIN", "NEUTRAL",
})

# config_overrides keys — mirrors every case label of the switch in
# DashboardScenarioRunner.applyConfigOverrides
# (src/test/java/shortestpath/dashboard/DashboardScenarioRunner.java).
# An unlisted key aborts the whole dataset run with
# IllegalArgumentException, so the lint whitelists them up front.
SCENARIO_CONFIG_KEYS = frozenset({
    "avoidWilderness", "useAgilityShortcuts", "useGrappleShortcuts",
    "useBoats", "useCanoes", "useCharterShips", "useShips",
    "useFairyRings", "useGnomeGliders", "useHotAirBalloons",
    "useMagicCarpets", "useMagicMushtrees", "useMinecarts",
    "useQuetzals", "useSpiritTrees", "useTeleportationItems",
    "useTeleportationLevers", "useTeleportationPortals",
    "useTeleportationSpells", "useTeleportationSpellsHome",
    "useTeleportationMinigames",
    "useWildernessObelisks", "useSeasonalTransports",
    "includeBankPath", "bypassVarbitChecks", "bypassVarPlayerChecks",
    "currencyThreshold",
    "calculationCutoff", "usePoh", "usePohFairyRing",
    "usePohSpiritTree", "useTeleportationPortalsPoh",
    "usePohMountedItems", "usePohObelisk",
    "costConsumableTeleportationItems",
    "costNonConsumableTeleportationItems", "costAgilityShortcuts",
    "costGrappleShortcuts", "costFairyRings", "costBoats",
    "costCanoes", "costCharterShips", "costShips",
    "costGnomeGliders", "costHotAirBalloons", "costMagicCarpets",
    "costMagicMushtrees", "costMinecarts", "costQuetzals",
    "costSpiritTrees", "costTeleportationLevers",
    "costTeleportationPortals", "costTeleportationSpells",
    "costTeleportationSpellsHome",
    "costTeleportationMinigames", "costWildernessObelisks",
    "costSeasonalTransports", "costBankVisit", "respawnPrifddinas",
    "costQuetzalWhistle", "costTeleportationBoxes",
    "builtTeleportationBoxes", "builtTeleportationPortalsPoh",
    "pohJewelleryBoxTier", "unreachableTargetDistanceThreshold",
})

# Optional-column cell grammars — mirrors the loader's parseItems /
# parseIntMap / parseStringIntMap shapes (DashboardScenarioLoader).
# A malformed cell aborts the whole dataset run with
# IllegalArgumentException under ignoreFailures = true — no report.json
# and a bare "missing or unreadable report" — so the lint catches it up
# front.  skill_levels keys must be Skill enum names
# (DashboardScenarioRunner calls Skill.valueOf on each key).
SCENARIO_ITEMS_RE = re.compile(r"^\d+(:\d+)?(;\d+(:\d+)?)*$")
SCENARIO_INT_MAP_RE = re.compile(r"^\d+=\d+(;\d+=\d+)*$")
SCENARIO_SKILL_MAP_RE = re.compile(r"^[A-Z_]+=\d+(;[A-Z_]+=\d+)*$")
# quests cells are `Quest Name=STATE` tokens — quest names carry spaces,
# apostrophes, `&`, `-` and `.` but never `=`, `;` or `,` (the loader splits
# on those), and STATE is a QuestState enum name.
# Residual: the name token itself is not checked against the Quest.getName()
# vocabulary (a ~200-entry enum in the runelite-api jar, not available to this
# lint), so a typo'd quest name passes here and is only caught at load time —
# parseQuestStateMap throws IllegalArgumentException and aborts the dataset run.
SCENARIO_QUEST_MAP_RE = re.compile(
    r"^[^=;,]+=(NOT_STARTED|IN_PROGRESS|FINISHED)"
    r"(;[^=;,]+=(NOT_STARTED|IN_PROGRESS|FINISHED))*$")
SCENARIO_COLUMN_GRAMMARS = {
    "inventory": SCENARIO_ITEMS_RE,
    "equipment": SCENARIO_ITEMS_RE,
    "bank": SCENARIO_ITEMS_RE,
    "varbits": SCENARIO_INT_MAP_RE,
    "varplayers": SCENARIO_INT_MAP_RE,
    "skill_levels": SCENARIO_SKILL_MAP_RE,
    "quests": SCENARIO_QUEST_MAP_RE,
}

# Column names the dashboard scenario loader understands, across both
# CSV grammars — mirrors the indexOf lookups in
# DashboardScenarioLoader.parseRoutesCsv/parseClueCsv.  A column not
# listed here is silently ignored by the loader (dropped data, the
# same bug class as an unknown TSV header), so it is a finding.
# ``source_file``/``source_line`` are tolerated provenance extras and
# ``expect_reachable`` is reserved for the reachability column.
SCENARIO_KNOWN_COLUMNS = frozenset({
    "name", "category", "start_x", "start_y", "start_plane",
    "x", "y", "plane", "preset", "teleports", "inventory",
    "equipment", "bank", "varbits", "varplayers", "skill_levels",
    "config_overrides", "expected_length", "minimum_length",
    "expect_reachable", "clue_type", "source_file", "source_line",
    "quests",
})


def _git_ls_files(*pathspecs):
    """Committed submodule files matching the given pathspecs.

    The leaf's own subprocess seam — monkeypatched in tests.  Only
    committed paths come back; an uncommitted or gitignored scratch
    TSV can never enter a check.
    """
    proc = subprocess.run(
        ["git", "-C", "shortest-path", "ls-files", *pathspecs],
        cwd=REPO, capture_output=True, text=True,
        timeout=GIT_TIMEOUT_SECONDS)
    if proc.returncode != 0:
        tail = (proc.stderr or "").strip().splitlines()
        sys.exit("git -C shortest-path ls-files failed: "
                 + (tail[-1] if tail else f"exit {proc.returncode}"))
    return [line for line in proc.stdout.splitlines() if line.strip()]


def _git_ls_files_repo(*pathspecs):
    """Committed tooling-repo files matching the given pathspecs.

    Same contract as ``_git_ls_files`` but rooted at this repository
    rather than the plugin submodule — used for the dashboard
    datasets under ``src/test/resources/dashboard/``.  The leaf's
    repo-level subprocess seam — monkeypatched in tests.
    """
    proc = subprocess.run(
        ["git", "ls-files", *pathspecs],
        cwd=REPO, capture_output=True, text=True,
        timeout=GIT_TIMEOUT_SECONDS)
    if proc.returncode != 0:
        tail = (proc.stderr or "").strip().splitlines()
        sys.exit("git ls-files failed: "
                 + (tail[-1] if tail else f"exit {proc.returncode}"))
    return [line for line in proc.stdout.splitlines() if line.strip()]


def _parse_tsv(path):
    """Parse a plugin TSV into (headers, header_lineno, rows).

    The first non-blank line is the header — ``#``-prefixed by
    convention.  Later ``#`` lines and blank lines are comments.
    Data rows come back as (lineno, fields) pairs.  Trailing-empty
    handling differs by loader — the transport parser splits with
    ``-1`` and keeps them, the destinations loader splits without
    ``-1`` and drops them, and Python's ``str.split`` keeps them —
    but the checks below only index the columns they assert on, so
    the difference is immaterial here.
    """
    headers = None
    header_lineno = 0
    rows = []
    for lineno, line in enumerate(path.read_text().splitlines(), 1):
        if not line.strip():
            continue
        if headers is None:
            headers = line.lstrip("#").strip().split("\t")
            header_lineno = lineno
            continue
        if line.startswith("#"):
            continue
        rows.append((lineno, line.split("\t")))
    return headers, header_lineno, rows


def check_tsv_structure():
    """Format lint over committed transports/ + destinations/ TSVs.

    Asserts per file: header cells drawn from the canonical
    TransportRecord.Fields set (transports) or the documented
    destination columns (destinations); every data row reaching its
    file's coordinate columns; every non-empty Origin/Destination
    cell in ``x y z`` form; and permutation rows present on both
    sides or neither — a one-sided set is dead data the loader never
    turns into an edge.  Transport files carrying a
    ``menuOption menuTarget objectID`` column additionally get the
    anchor-cell shape lint: every non-empty cell must parse as
    ``<menuOption> <menuTarget...> <objectID>`` — the same grammar
    the transportAnchorDrift detector's parseAnchor applies — with
    the committed no-id menu-text cells carried in the curated
    exceptions file.
    """
    findings = []
    anchor_exceptions = _load_anchor_exceptions(ANCHOR_EXCEPTIONS)
    rels = _git_ls_files(
        f"{RESOURCES}/transports", f"{RESOURCES}/destinations")
    for rel in sorted(r for r in rels if r.endswith(".tsv")):
        headers, hln, rows = _parse_tsv(PLUGIN / rel)
        if headers is None:
            findings.append(f"{rel}:1: no header line")
            continue
        if rel.startswith(f"{RESOURCES}/transports/"):
            canonical = TRANSPORT_FIELDS
        elif rel.startswith(f"{RESOURCES}/destinations/"):
            canonical = DESTINATION_FIELDS
        else:
            canonical = None
        if canonical is not None:
            for cell in headers:
                if cell not in canonical:
                    findings.append(
                        f"{rel}:{hln}: unknown header cell {cell!r}")
        if "Destination" not in headers:
            findings.append(f"{rel}:{hln}: missing Destination column")
            continue
        di = headers.index("Destination")
        oi = headers.index("Origin") if "Origin" in headers else -1
        # A row that stops short of a coordinate column parses to a
        # permutation marker or an undefined endpoint — silent loader
        # degradation rather than the intended transport.
        required = max(oi, di) + 1
        perm_origins = 0
        perm_destinations = 0
        for lineno, fields in rows:
            if len(fields) < required:
                findings.append(
                    f"{rel}:{lineno}: row has {len(fields)} fields, "
                    f"needs {required} to cover coordinate columns")
                continue
            origin = fields[oi].strip() if oi >= 0 else ""
            destination = fields[di].strip()
            for label, cell in (("Origin", origin),
                                ("Destination", destination)):
                if cell and not COORD_RE.match(cell):
                    findings.append(
                        f"{rel}:{lineno}: malformed {label} "
                        f"coordinate {cell!r}")
            if oi >= 0:
                if origin and not destination:
                    perm_origins += 1
                elif destination and not origin:
                    perm_destinations += 1
        # A concrete-Origin/blank-Destination row is a permutation
        # anchor that only emits edges when paired with a
        # blank-Origin/concrete-Destination row in the same file (and
        # vice versa) — a one-sided set is dead anchor data.  Files
        # without an Origin column are usable-anywhere teleport lists
        # by design, so the pair rule does not apply to them.
        if oi >= 0 and (perm_origins > 0) != (perm_destinations > 0):
            findings.append(
                f"{rel}:{hln}: one-sided permutation set "
                f"({perm_origins} origin rows, "
                f"{perm_destinations} destination rows)")
        # Anchor-cell shape — mirrors parseAnchor in the drift
        # detector: a non-empty cell must split into >=2 whitespace
        # tokens with an all-digit trailing token (the object id).
        # Empty cells are legitimate no-anchor rows; the committed
        # no-id menu-text cells are curated in the exceptions file.
        ai = (headers.index("menuOption menuTarget objectID")
              if "menuOption menuTarget objectID" in headers else -1)
        if ai >= 0:
            for lineno, fields in rows:
                if ai >= len(fields):
                    continue
                cell = fields[ai].strip()
                if not cell or (rel, cell) in anchor_exceptions:
                    continue
                tokens = cell.split()
                if len(tokens) < 2 or not tokens[-1].isdigit():
                    findings.append(
                        f"{rel}:{lineno}: anchor cell {cell!r} has "
                        f"no trailing object id")
    return findings


def check_collision_zip():
    """Structural heuristics on the committed collision-map.zip.

    Every entry name must be ``<int>_<int>`` — the plugin's loader
    ``Integer.parseInt``s both halves, so a stray entry would crash
    map loading outright.  Each blob must decode to 1-4 planes worth
    of bits (blobs are trimmed BitSet streams, so any byte length in
    that range is legal) and carry at least one set bit.  The region
    count itself must be plausibly non-trivial — a handful of entries
    means a truncated artifact.
    """
    findings = []
    if not COLLISION_ZIP.exists():
        return [f"{COLLISION_ZIP}: collision-map.zip missing"]
    with zipfile.ZipFile(COLLISION_ZIP) as z:
        names = z.namelist()
        for name in names:
            if not REGION_NAME_RE.match(name):
                findings.append(
                    f"collision-map.zip: entry {name!r} does not "
                    f"match <int>_<int>")
                continue
            data = z.read(name)
            planes = (len(data) * 8 + BITS_PER_PLANE - 1) // BITS_PER_PLANE
            if not 1 <= planes <= 4:
                findings.append(
                    f"collision-map.zip:{name}: blob yields {planes} "
                    f"planes ({len(data)} bytes)")
            elif not any(data):
                findings.append(
                    f"collision-map.zip:{name}: blob has no set bits")
    if len(names) < MIN_REGION_COUNT:
        findings.append(
            f"collision-map.zip: only {len(names)} region entries — "
            f"truncated artifact")
    return findings


def _concrete(cell):
    return COORD_RE.match(cell) is not None


def _walkable_neighbour(cmap, x, y, z):
    return any(cmap.walkable(x + dx, y + dy, z)
               for dx, dy in ((0, 1), (0, -1), (1, 0), (-1, 0)))


def _load_collision_map():
    """CollisionMap for the committed zip, or a finding naming why not.

    Returns ``(cmap, findings)`` — exactly one element is meaningful:
    a usable reader, or a fail-closed diagnostic in the same
    ``file:line`` style as the other checks.  A missing or corrupt zip
    must degrade to a finding, not a traceback mid-check.
    """
    if not COLLISION_ZIP.exists():
        return None, [f"{COLLISION_ZIP}: collision-map.zip missing"]
    try:
        return CollisionMap(COLLISION_ZIP), None
    except (OSError, zipfile.BadZipFile) as exc:
        return None, [f"{COLLISION_ZIP}: unreadable "
                      f"collision-map.zip ({exc})"]


def check_walkability():
    """Transport endpoints must be live in the plugin's pathing model.

    The plugin does not require an endpoint tile to be walkable —
    transport origins are frequently the object tile itself and the
    pathfinder reaches them from a walkable neighbour (the documented
    blocked-adjacent mechanic, e.g. fairy rings), or lands on them via
    another transport's destination (stepping-stone chains), or sits
    in instanced content the committed map never covers.  The dead
    cases this check exists to catch are therefore:

    - an Origin that is flagless, has no walkable 4-neighbour, and is
      no row's Destination (nothing can deliver the player to it)
    - a Destination that is flagless, has no walkable 4-neighbour, and
      is no row's Origin (the player is stranded on arrival)

    Endpoints in regions absent from the zip are skipped — instanced
    interiors are outside the committed map's coverage, not findings.
    Files without an Origin column are usable-anywhere teleport lists;
    their destinations are reachable and re-usable by construction.
    """
    cmap, load_findings = _load_collision_map()
    if cmap is None:
        return load_findings
    exceptions = _load_exceptions(WALKABILITY_EXCEPTIONS)
    rels = _git_ls_files(f"{RESOURCES}/transports")
    origins = set()
    destinations = set()
    parsed = []  # (rel, lineno, headers, fields)
    for rel in sorted(r for r in rels if r.endswith(".tsv")):
        headers, _, rows = _parse_tsv(PLUGIN / rel)
        if headers is None:
            continue
        for lineno, fields in rows:
            parsed.append((rel, lineno, headers, fields))
            for col in ("Origin", "Destination"):
                if col not in headers:
                    continue
                idx = headers.index(col)
                if idx >= len(fields):
                    continue
                cell = fields[idx].strip()
                if _concrete(cell):
                    coord = tuple(int(p) for p in cell.split())
                    (origins if col == "Origin"
                     else destinations).add(coord)

    findings = []
    for rel, lineno, headers, fields in parsed:
        has_origin = "Origin" in headers
        for label, other in (("Origin", destinations),
                             ("Destination", origins)):
            if label not in headers:
                continue
            idx = headers.index(label)
            if idx >= len(fields):
                continue
            cell = fields[idx].strip()
            if not _concrete(cell):
                continue
            if label == "Destination" and not has_origin:
                continue  # usable-anywhere teleport list
            x, y, z = (int(p) for p in cell.split())
            if (x // REGION_SIZE, y // REGION_SIZE) not in cmap.regions:
                continue  # instanced content — outside committed coverage
            if (x, y, z) in exceptions:
                continue  # curated known-unreachable transport tile
            if (cmap.walkable(x, y, z)
                    or _walkable_neighbour(cmap, x, y, z)
                    or (x, y, z) in other):
                continue
            findings.append(
                f"{rel}:{lineno}: {label.lower()} {cell} is "
                f"unreachable in the collision map")
    return findings


def check_bbox():
    """Seasonal transport endpoints must classify to a league region.

    LeagueRegionChecker packs a tile to its chunk region id and
    defaults unmapped chunks to NEUTRAL — which is always unlocked, so
    a seasonal row landing outside every curated bbox silently works
    for everyone.  Every concrete Origin/Destination tile in
    seasonal_transports.tsv must classify through the curated bboxes
    or carry an explicit ``Region override``.
    """
    bboxes = load_bboxes(BBOX_TSV)
    exceptions = _load_exceptions(BBOX_EXCEPTIONS)
    findings = []
    rels = _git_ls_files(
        f"{RESOURCES}/transports/seasonal_transports.tsv")
    for rel in rels:
        headers, _, rows = _parse_tsv(PLUGIN / rel)
        if headers is None:
            continue
        ri = (headers.index("Region override")
              if "Region override" in headers else -1)
        for lineno, fields in rows:
            override = (fields[ri].strip()
                        if 0 <= ri < len(fields) else "")
            for label in ("Origin", "Destination"):
                if label not in headers:
                    continue
                idx = headers.index(label)
                if idx >= len(fields):
                    continue
                cell = fields[idx].strip()
                if not _concrete(cell):
                    continue
                x, y, z = (int(p) for p in cell.split())
                if (x, y, z) in exceptions:
                    continue  # curated known-NEUTRAL seasonal tile
                region = classify_tile(x, y, bboxes)
                if region == "NEUTRAL" and not override:
                    findings.append(
                        f"{rel}:{lineno}: {label} {cell} classifies "
                        f"NEUTRAL (no Region override)")
    return findings


def check_regions():
    """Generated leagues/regions.tsv must agree with zip + bboxes.

    LeagueRegionChecker resolves a region id to NEUTRAL when it is
    absent from the generated file — so a zip surface region that the
    curated classifier says belongs to a league but is missing from
    the file is silently NEUTRAL (the authoring bug this phase
    exists to catch), and a generated row that disagrees with the
    classifier is a stale artifact.
    """
    bboxes = load_bboxes(BBOX_TSV)
    findings = []
    generated = {}
    rels = _git_ls_files(f"{RESOURCES}/leagues/regions.tsv")
    if not rels:
        findings.append(
            f"{RESOURCES}/leagues/regions.tsv: not committed")
    for rel in rels:
        for lineno, line in enumerate(
                (PLUGIN / rel).read_text().splitlines(), 1):
            s = line.strip()
            if not s or s.startswith("#"):
                continue
            parts = s.split("\t")
            if len(parts) != 2:
                findings.append(
                    f"{rel}:{lineno}: malformed region row {s!r}")
                continue
            try:
                generated[int(parts[0])] = parts[1]
            except ValueError:
                findings.append(
                    f"{rel}:{lineno}: malformed region id "
                    f"{parts[0]!r}")
    if not COLLISION_ZIP.exists():
        findings.append(f"{COLLISION_ZIP}: collision-map.zip missing")
        return findings
    with zipfile.ZipFile(COLLISION_ZIP) as z:
        for name in z.namelist():
            if not REGION_NAME_RE.match(name):
                continue  # reported by the collision-zip check
            rx, ry = (int(p) for p in name.split("_"))
            rid = (rx << 8) | ry
            expected = classify_chunk(rx, ry, bboxes)
            if expected != "NEUTRAL" and rid not in generated:
                findings.append(
                    f"leagues/regions.tsv: zip region id {rid} "
                    f"({rx}_{ry}) absent — classifier expects "
                    f"{expected}")
    for rid, region in generated.items():
        expected = classify_chunk(rid >> 8, rid & 0xFF, bboxes)
        if region != expected:
            findings.append(
                f"leagues/regions.tsv: region id {rid} is {region} "
                f"but classifier expects {expected}")
    return findings


def _load_exceptions(path):
    """Curated (x, y, z) suppression set for the destinations check.

    Rows are ``X Y Z<TAB>reason``; ``#`` and blank lines are comments.
    A malformed row fails closed — a suppression that might be a typo
    must never silently apply.
    """
    tiles = set()
    try:
        lines = path.read_text().splitlines()
    except OSError as exc:
        sys.exit(f"cannot read {path}: {exc.strerror or exc}")
    for lineno, line in enumerate(lines, 1):
        s = line.strip()
        if not s or s.startswith("#"):
            continue
        cell, _, reason = s.partition("\t")
        if not COORD_RE.match(cell.strip()) or not reason.strip():
            sys.exit(f"{path}:{lineno}: malformed exception row {s!r}")
        tiles.add(tuple(int(p) for p in cell.split()))
    return tiles


def _load_anchor_exceptions(path):
    """Curated (rel, cell) suppression set for the anchor-cell lint.

    Rows are ``<tsv relpath><TAB><cell text><TAB>reason``; ``#`` and
    blank lines are comments.  The key is the file plus the exact
    cell text — tighter than a coordinate key, so a *different*
    malformed cell on the same row still flags.  A malformed row
    fails closed — a suppression that might be a typo must never
    silently apply.
    """
    cells = set()
    try:
        lines = path.read_text().splitlines()
    except OSError as exc:
        sys.exit(f"cannot read {path}: {exc.strerror or exc}")
    for lineno, line in enumerate(lines, 1):
        s = line.strip()
        if not s or s.startswith("#"):
            continue
        parts = s.split("\t")
        if len(parts) != 3 or not all(p.strip() for p in parts):
            sys.exit(f"{path}:{lineno}: malformed anchor exception "
                     f"row {s!r}")
        cells.add((parts[0].strip(), parts[1].strip()))
    return cells


def check_region_override():
    """Every `Region override` cell must be a LeagueRegion enum name.

    TransportBuilder resolves the cell via ``LeagueRegion.valueOf``
    and swallows ``IllegalArgumentException`` — a typo'd override
    silently drops to NEUTRAL, which is always-unlocked in league
    mode.  Files without the column are skipped; empty cells mean
    "classify by bbox" and are legitimate.
    """
    findings = []
    rels = _git_ls_files(f"{RESOURCES}/transports")
    for rel in sorted(r for r in rels if r.endswith(".tsv")):
        headers, _, rows = _parse_tsv(PLUGIN / rel)
        if headers is None or "Region override" not in headers:
            continue
        ri = headers.index("Region override")
        for lineno, fields in rows:
            if ri >= len(fields):
                continue
            cell = fields[ri].strip()
            if cell and cell not in LEAGUE_REGIONS:
                findings.append(
                    f"{rel}:{lineno}: Region override {cell!r} is "
                    f"not a LeagueRegion enum name")
    return findings


def check_destinations():
    """Advisory: destination TSV targets blocked on the committed zip.

    Flags every committed destinations/**/*.tsv row whose Destination
    tile has no cardinal movement flags — the drift surface for moved
    or deleted content — minus the curated exceptions list.  Advisory
    by design: by-design unreachable tiles (NPC-serviced banks,
    adjacent-interaction objects) are legitimate, so findings triage
    into the exceptions file rather than gating.
    """
    cmap, load_findings = _load_collision_map()
    if cmap is None:
        return load_findings
    exceptions = _load_exceptions(DESTINATION_EXCEPTIONS)
    findings = []
    rels = _git_ls_files(f"{RESOURCES}/destinations")
    for rel in sorted(r for r in rels if r.endswith(".tsv")):
        headers, hln, rows = _parse_tsv(PLUGIN / rel)
        if headers is None:
            continue
        if "Destination" not in headers:
            findings.append(
                f"{rel}:{hln}: missing Destination column")
            continue
        di = headers.index("Destination")
        ii = headers.index("Info") if "Info" in headers else -1
        for lineno, fields in rows:
            if di >= len(fields):
                continue
            cell = fields[di].strip()
            if not _concrete(cell):
                continue
            x, y, z = (int(p) for p in cell.split())
            if (x, y, z) in exceptions or not cmap.is_blocked(x, y, z):
                continue
            info = fields[ii].strip() if 0 <= ii < len(fields) else ""
            findings.append(f"{rel}:{lineno}: {cell} {info}".rstrip())
    return findings


def _int_or_finding(findings, rel, lineno, col, cell):
    """Append a finding unless ``cell`` parses as an integer."""
    try:
        int(cell)
    except ValueError:
        findings.append(
            f"{rel}:{lineno}: non-integer {col} {cell!r}")


def check_scenario_csv():
    """Lint committed dashboard scenario CSVs against the loader grammar.

    DashboardScenarioLoader splits rows on ``,`` with no quote
    handling, so an embedded comma in ``name``/``category`` shifts
    every later field; a header cell outside the known column set is
    silently ignored (dropped data); an unknown ``preset`` or
    ``config_overrides`` key aborts the whole dataset run with
    ``IllegalArgumentException``.  Two grammars, dispatched on the
    header: a ``clue_type`` column means the clue-step format
    (requires ``clue_type`` + ``x``/``y``/``plane``), otherwise the
    extended routes format (requires ``x``/``y``/``plane``).

    Files are enumerated via ``git ls-files`` on the tooling repo —
    never a filesystem glob — so a gitignored scratch CSV can never
    leak into the gate.
    """
    findings = []
    rels = _git_ls_files_repo("src/test/resources/dashboard")
    for rel in sorted(r for r in rels if r.endswith(".csv")):
        lines = (REPO / rel).read_text().splitlines()
        if not lines:
            continue
        # The loader takes the first line as the header verbatim.
        headers = [h.strip() for h in lines[0].split(",")]
        for cell in headers:
            if cell and cell not in SCENARIO_KNOWN_COLUMNS:
                findings.append(
                    f"{rel}:1: unknown header cell {cell!r}")
        clue = "clue_type" in headers
        required = (("clue_type", "x", "y", "plane") if clue
                    else ("x", "y", "plane"))
        idx = {h: i for i, h in enumerate(headers)}
        missing = [c for c in required if c not in idx]
        if missing:
            findings.append(
                f"{rel}:1: missing required column {missing[0]!r}")
            continue
        need = max(idx[c] for c in required) + 1
        for lineno, line in enumerate(lines[1:], 2):
            if not line.strip() or line.startswith("#"):
                continue
            fields = line.split(",")
            if len(fields) > len(headers):
                findings.append(
                    f"{rel}:{lineno}: row has {len(fields)} fields "
                    f"vs {len(headers)} headers (embedded comma)")
                continue
            if len(fields) < need:
                findings.append(
                    f"{rel}:{lineno}: row has {len(fields)} "
                    f"fields, needs {need} to cover required "
                    f"columns")
                continue
            for col in required:
                cell = fields[idx[col]].strip()
                if not cell:
                    findings.append(
                        f"{rel}:{lineno}: empty required column "
                        f"{col!r}")
                elif col != "clue_type":
                    _int_or_finding(findings, rel, lineno, col, cell)
            if clue:
                continue
            for col in ("start_x", "start_y", "start_plane",
                        "expected_length", "minimum_length"):
                i = idx.get(col, -1)
                if 0 <= i < len(fields):
                    cell = fields[i].strip()
                    if cell:
                        _int_or_finding(
                            findings, rel, lineno, col, cell)
            for col in ("preset", "teleports"):
                i = idx.get(col, -1)
                if 0 <= i < len(fields):
                    cell = fields[i].strip()
                    if cell and cell.upper() not in SCENARIO_PRESETS:
                        findings.append(
                            f"{rel}:{lineno}: unknown preset "
                            f"{cell!r}")
            i = idx.get("config_overrides", -1)
            if 0 <= i < len(fields):
                for token in fields[i].split(";"):
                    token = token.strip()
                    if not token:
                        continue
                    key, _, value = token.partition("=")
                    key = key.strip()
                    if key not in SCENARIO_CONFIG_KEYS:
                        findings.append(
                            f"{rel}:{lineno}: unknown "
                            f"config_overrides key {key!r}")
                    elif not value.strip():
                        findings.append(
                            f"{rel}:{lineno}: config_overrides "
                            f"key {key!r} has an empty value")
            for col, grammar in SCENARIO_COLUMN_GRAMMARS.items():
                i = idx.get(col, -1)
                if 0 <= i < len(fields):
                    cell = fields[i].strip()
                    if cell and not grammar.match(cell):
                        findings.append(
                            f"{rel}:{lineno}: {col} value {cell!r} "
                            f"does not match its grammar")
            i = idx.get("expect_reachable", -1)
            if 0 <= i < len(fields):
                cell = fields[i].strip()
                if cell and cell.lower() not in ("true", "false"):
                    findings.append(
                        f"{rel}:{lineno}: expect_reachable "
                        f"{cell!r} is not true/false")
    return findings


def check_scenario_var_gating():
    """Advisory: scenario var stubs that can never gate a transport.

    A ``varbits``/``varplayers`` cell whose ids appear in the committed
    transports TSV ``Varbits``/``VarPlayers`` requirement columns is a
    transport-gating stub — but it only reaches the gate when the row
    also sets ``bypassVarbitChecks=false`` /
    ``bypassVarPlayerChecks=false`` (both default to bypassed in the
    harness).  A gating-id stub without the matching flag is silently
    dead for transport gating, so the finding names the ids and notes
    the stub may still feed direct client reads or always-enforced
    destination requirements — varbit 4498 is legitimately dual-use,
    which is why this tier is advisory rather than hard.

    The corpus is built per transports file from that file's own
    header — column order differs per file — and ``destinations/`` is
    excluded even if the seam lists it: bank-destination requirements
    are never bypassed, so stubs feeding them are never dead.  Both
    enumerations go through the git seams, so an uncommitted scratch
    TSV/CSV can never enter a finding.
    """
    varbit_corpus = set()
    varp_corpus = set()
    for rel in sorted(r for r in _git_ls_files(f"{RESOURCES}/transports")
                      if r.endswith(".tsv")
                      and r.startswith(f"{RESOURCES}/transports/")):
        headers, _, rows = _parse_tsv(PLUGIN / rel)
        if headers is None:
            continue
        for col, corpus in (("Varbits", varbit_corpus),
                            ("VarPlayers", varp_corpus)):
            if col not in headers:
                continue
            ci = headers.index(col)
            for _lineno, fields in rows:
                if ci >= len(fields):
                    continue
                for token in fields[ci].split(";"):
                    m = re.match(r"(\d+)[=><&@]", token.strip())
                    if m:
                        corpus.add(int(m.group(1)))

    findings = []
    rels = _git_ls_files_repo("src/test/resources/dashboard")
    for rel in sorted(r for r in rels if r.endswith(".csv")):
        lines = (REPO / rel).read_text().splitlines()
        if not lines:
            continue
        headers = [h.strip() for h in lines[0].split(",")]
        idx = {h: i for i, h in enumerate(headers)}
        config_i = idx.get("config_overrides", -1)
        for lineno, line in enumerate(lines[1:], 2):
            if not line.strip() or line.startswith("#"):
                continue
            fields = line.split(",")
            config = {}
            if 0 <= config_i < len(fields):
                for token in fields[config_i].split(";"):
                    key, _, value = token.partition("=")
                    key = key.strip()
                    if key:
                        config[key] = value.strip()
            for col, corpus, flag in (
                    ("varbits", varbit_corpus, "bypassVarbitChecks"),
                    ("varplayers", varp_corpus, "bypassVarPlayerChecks")):
                i = idx.get(col, -1)
                if not 0 <= i < len(fields):
                    continue
                stubbed = set()
                for token in fields[i].split(";"):
                    ident, _, _value = token.partition("=")
                    ident = ident.strip()
                    if ident.isdigit():
                        stubbed.add(int(ident))
                dead = stubbed & corpus
                # Mirror the harness's Boolean.parseBoolean: the bypass
                # stays on (checks disabled, stub dead for gating) only
                # when the flag is absent or parses true — i.e. equals
                # "true" case-insensitively. Every other value ("false",
                # "0", "off", empty) disables the bypass and the stub is
                # live for transport gating.
                flag_value = config.get(flag)
                checks_enabled = (flag_value is not None
                                  and flag_value.lower() != "true")
                if dead and not checks_enabled:
                    findings.append(
                        f"{rel}:{lineno}: {col} stub ids "
                        f"{sorted(dead)} are transport-requirement "
                        f"gated but {flag} is not false — stub is dead "
                        f"for transport gating (it may still feed "
                        f"direct client reads or destination "
                        f"requirements)")
    return findings


# name -> check function returning a list of finding strings.
CHECKS = {
    "tsv-structure": check_tsv_structure,
    "collision-zip": check_collision_zip,
    "walkability": check_walkability,
    "bbox": check_bbox,
    "regions": check_regions,
    "destinations": check_destinations,
    "scenario-csv": check_scenario_csv,
    "region-override": check_region_override,
    "scenario-var-gating": check_scenario_var_gating,
}

# Checks whose findings are reported but never move the exit code.
ADVISORY_CHECKS = frozenset({"destinations", "scenario-var-gating"})

# Section titles for advisory check output.
SECTION_TITLES = {"destinations": "Destination walkability",
                  "scenario-var-gating": "Scenario var gating bypass"}


def main(argv=None):
    names = list(sys.argv[1:] if argv is None else argv)
    if not names:
        names = list(CHECKS)
    unknown = [n for n in names if n not in CHECKS]
    if unknown:
        sys.exit(f"unknown check {unknown[0]!r} — valid checks: "
                 + ", ".join(CHECKS))
    total = 0
    hard_fail = False
    for name in names:
        findings = CHECKS[name]()
        total += len(findings)
        if name in ADVISORY_CHECKS:
            title = SECTION_TITLES.get(name, name)
            print(f"=== {title} ({len(findings)} findings) ===")
            for finding in findings:
                print(f"  {finding}")
        else:
            for finding in findings:
                print(f"FAIL {finding}")
            if findings:
                hard_fail = True
    print(f"Summary: {total} findings across {len(names)} checks")
    return 1 if hard_fail else 0


if __name__ == "__main__":
    sys.exit(main())
