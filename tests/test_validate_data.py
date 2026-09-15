"""Tests for ``scripts/validate_data.py`` and ``scripts/collision_zip.py``.

The leaf is loaded via importlib because ``scripts/`` has no
``__init__.py``.  Fixtures build mini TSV trees and collision zips
under ``tmp_path``; ``vd._git_ls_files`` (the leaf's own seam) and the
module path constants are monkeypatched — no test touches the real
submodule, git, or the committed zip.
"""

import importlib.util
import sys
import zipfile
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
SCRIPTS = ROOT / "scripts"
sys.path.insert(0, str(SCRIPTS))

import collision_zip  # noqa: E402
import rebuild_bank_tsv  # noqa: E402


def load_vd():
    spec = importlib.util.spec_from_file_location(
        "validate_data", SCRIPTS / "validate_data.py")
    vd = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(vd)
    return vd


def _write_tsv(root, rel, header_cells, rows):
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "# " + "\t".join(header_cells) + "\n"
        + "\n".join(rows) + "\n")
    return rel


def _mini_zip(path, entries):
    """Build a collision zip.  ``entries`` maps ``"rx_ry"`` to an
    iterable of ``(lx, ly, plane, flag)`` bits to set."""
    with zipfile.ZipFile(path, "w") as z:
        for name, bits in entries.items():
            data = bytearray(1024)  # one plane's worth of bits
            for lx, ly, plane, flag in bits:
                idx = ((plane * 64 * 64) + (ly * 64) + lx) * 2 + flag
                data[idx >> 3] |= 1 << (idx & 7)
            z.writestr(name, bytes(data))
    return path


# ---------- collision_zip shared reader ----------


def test_collision_zip_round_trip(tmp_path):
    # Region (30,40): set N flag at (2,3,p0) and E flag at (5,3,p0).
    zp = _mini_zip(tmp_path / "collision-map.zip",
                   {"30_40": [(2, 3, 0, collision_zip.FLAG_N),
                              (5, 3, 0, collision_zip.FLAG_E)]})
    cmap = collision_zip.CollisionMap(zp)
    wx, wy = 30 * 64 + 2, 40 * 64 + 3
    assert cmap.n(wx, wy, 0)
    assert not cmap.e(wx, wy, 0)
    # s() reads the northern flag of the tile below; w() the eastern
    # flag of the tile to the left.
    assert cmap.s(wx, wy + 1, 0)
    assert not cmap.s(wx, wy, 0)
    assert cmap.w(30 * 64 + 6, wy, 0)
    assert not cmap.w(30 * 64 + 5, wy, 0)
    assert cmap.walkable(wx, wy, 0)
    assert not cmap.is_blocked(wx, wy, 0)
    assert cmap.is_blocked(wx + 10, wy + 10, 0)
    # A plane with no data is blocked, not an error.
    assert cmap.is_blocked(wx, wy, 3)


def test_rebuild_uses_shared_reader():
    assert rebuild_bank_tsv.CollisionMap is collision_zip.CollisionMap
    assert rebuild_bank_tsv.FLAG_N == collision_zip.FLAG_N
    assert rebuild_bank_tsv.FLAG_E == collision_zip.FLAG_E
    assert rebuild_bank_tsv.REGION_SIZE == collision_zip.REGION_SIZE


# ---------- collision-zip structural check ----------


def _patch_leaf(vd, monkeypatch, plugin, *, ls_files=(), zip_path=None):
    monkeypatch.setattr(vd, "PLUGIN", plugin)
    monkeypatch.setattr(vd, "_git_ls_files", lambda *ps: list(ls_files))
    if zip_path is not None:
        monkeypatch.setattr(vd, "COLLISION_ZIP", zip_path)


def test_collision_zip_structure_check(tmp_path, monkeypatch):
    vd = load_vd()
    zp = _mini_zip(tmp_path / "collision-map.zip",
                   {"30_40": [(2, 3, 0, 0)], "not_a_region": [],
                    "31_40": [(1, 1, 0, 0)]})
    _patch_leaf(vd, monkeypatch, tmp_path / "sp", zip_path=zp)
    findings = vd.CHECKS["collision-zip"]()
    # The non-<int>_<int> entry is a finding; the conforming entries
    # are not — but the tiny region count is itself suspicious.
    assert any("not_a_region" in f for f in findings)
    assert any("truncat" in f.lower() or "region" in f.lower()
               and "entries" in f for f in findings)


def test_collision_zip_structure_clean(tmp_path, monkeypatch):
    vd = load_vd()
    zp = _mini_zip(tmp_path / "collision-map.zip",
                   {"30_40": [(2, 3, 0, 0)]})
    _patch_leaf(vd, monkeypatch, tmp_path / "sp", zip_path=zp)
    monkeypatch.setattr(vd, "MIN_REGION_COUNT", 1)
    findings = vd.CHECKS["collision-zip"]()
    assert findings == []


def test_collision_zip_structure_committed_zip_passes():
    # The real committed zip is the healthy-data case: conforming
    # names, plausible count, plane counts in range.
    vd = load_vd()
    findings = vd.CHECKS["collision-zip"]()
    assert findings == []


def test_collision_zip_bad_blob(tmp_path, monkeypatch):
    vd = load_vd()
    zp = tmp_path / "collision-map.zip"
    with zipfile.ZipFile(zp, "w") as z:
        z.writestr("30_40", bytes(5000))  # yields 5+ planes
        z.writestr("31_40", b"")          # empty blob
    _patch_leaf(vd, monkeypatch, tmp_path / "sp", zip_path=zp)
    findings = vd.CHECKS["collision-zip"]()
    assert any("30_40" in f for f in findings)
    assert any("31_40" in f for f in findings)


# ---------- walkability check ----------


def test_walkability_flags_blocked_endpoint(tmp_path, monkeypatch):
    vd = load_vd()
    plugin = tmp_path / "sp"
    rel = _write_tsv(
        plugin, "src/main/resources/transports/transports.tsv",
        ["Origin", "Destination", "menuOption menuTarget objectID"],
        ["1921 2561 0\t1950 2570 0\tOpen Door 1",
         "1922 2561 0\t1921 2561 0\tOpen Door 2"])
    # Only (1,1) and (2,1) of region (30,40) are walkable: world tiles
    # (1921,2561) and (1922,2561).  The destination (1950,2570) has no
    # flags and no walkable neighbour.
    zp = _mini_zip(tmp_path / "collision-map.zip",
                   {"30_40": [(1, 1, 0, 0), (2, 1, 0, 0)]})
    _patch_leaf(vd, monkeypatch, plugin, ls_files=[rel], zip_path=zp)
    findings = vd.CHECKS["walkability"]()
    assert findings
    assert any(f.startswith(f"{rel}:2") and "1950 2570 0" in f
               for f in findings)
    # The walkable endpoints are not findings.
    assert not any("1921 2561 0" in f or "1922 2561 0" in f
                   for f in findings)


def test_walkability_origin_adjacent_interaction(tmp_path, monkeypatch):
    vd = load_vd()
    plugin = tmp_path / "sp"
    # Origin (1950,2570) is flagless but sits next to walkable
    # (1921,2561)-style tile (3,1) — the plugin's blocked-adjacent
    # interaction, not a dead endpoint.
    rel = _write_tsv(
        plugin, "src/main/resources/transports/transports.tsv",
        ["Origin", "Destination", "menuOption menuTarget objectID"],
        ["1924 2561 0\t1921 2561 0\tUse Object 1"])
    zp = _mini_zip(tmp_path / "collision-map.zip",
                   {"30_40": [(3, 1, 0, 0), (1, 1, 0, 0)]})
    _patch_leaf(vd, monkeypatch, plugin, ls_files=[rel], zip_path=zp)
    # (1924,2561) = local (4,1): flagless but adjacent to walkable (3,1).
    assert vd.CHECKS["walkability"]() == []


def test_walkability_skips_absent_region(tmp_path, monkeypatch):
    vd = load_vd()
    plugin = tmp_path / "sp"
    rel = _write_tsv(
        plugin, "src/main/resources/transports/transports.tsv",
        ["Origin", "Destination", "menuOption menuTarget objectID"],
        ["5000 5000 0\t5001 5000 0\tUse Object 1"])
    zp = _mini_zip(tmp_path / "collision-map.zip",
                   {"30_40": [(1, 1, 0, 0)]})
    _patch_leaf(vd, monkeypatch, plugin, ls_files=[rel], zip_path=zp)
    # Region (78,78) is absent from the zip — instanced content the
    # committed map does not cover; not a finding.
    assert vd.CHECKS["walkability"]() == []


# ---------- bbox check ----------


def _bbox_file(path, rows):
    path.write_text(
        "# curated league region bboxes\n"
        + "\n".join("\t".join(map(str, r)) for r in rows) + "\n")


def _seasonal_fixture(plugin, rows):
    return _write_tsv(
        plugin, "src/main/resources/transports/seasonal_transports.tsv",
        ["Destination", "menuOption menuTarget objectID", "Skills",
         "Items", "Quests", "Duration", "Display info", "Consumable",
         "Wilderness level", "Varbits", "VarPlayers", "Region override"],
        rows)


def test_bbox_flags_silent_neutral(tmp_path, monkeypatch):
    vd = load_vd()
    plugin = tmp_path / "sp"
    bboxes = tmp_path / "leagues_regions.tsv"
    # One KANDARIN bbox covering chunk (0,0) only — tiles at
    # (100,100) classify NEUTRAL.
    _bbox_file(bboxes, [("KANDARIN", 0, 63, 0, 63)])
    rel = _seasonal_fixture(plugin, [
        "100 100 0\t\t\t\t\t4\tEvil Eye: somewhere\tF\t60\t\t\t",
        "30 30 0\t\t\t\t\t4\tEvil Eye: covered\tF\t60\t\t\t",
        "100 100 0\t\t\t\t\t4\tEvil Eye: overridden\tF\t60\t\t\tMORYTANIA",
    ])
    _patch_leaf(vd, monkeypatch, plugin, ls_files=[rel])
    monkeypatch.setattr(vd, "BBOX_TSV", bboxes)
    findings = vd.CHECKS["bbox"]()
    # Row 2 lands NEUTRAL with no override -> finding naming the row.
    assert any(f.startswith(f"{rel}:2") and "NEUTRAL" in f
               for f in findings)
    # Row 3 classifies KANDARIN; row 4 carries an override.
    assert not any(f.startswith(f"{rel}:3") for f in findings)
    assert not any(f.startswith(f"{rel}:4") for f in findings)


def test_bbox_flags_neutral_origin(tmp_path, monkeypatch):
    vd = load_vd()
    plugin = tmp_path / "sp"
    bboxes = tmp_path / "leagues_regions.tsv"
    _bbox_file(bboxes, [("KANDARIN", 0, 63, 0, 63)])
    # A seasonal file with an Origin column gets both endpoints
    # classified.
    rel = _write_tsv(
        plugin, "src/main/resources/transports/seasonal_transports.tsv",
        ["Origin", "Destination", "Region override"],
        ["100 100 0\t30 30 0\t"])
    _patch_leaf(vd, monkeypatch, plugin, ls_files=[rel])
    monkeypatch.setattr(vd, "BBOX_TSV", bboxes)
    findings = vd.CHECKS["bbox"]()
    assert any(f.startswith(f"{rel}:2") and "Origin" in f
               for f in findings)


# ---------- regions consistency check ----------


def _regions_file(path, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("# regionId\tLeagueRegion\n"
                    + "\n".join(f"{rid}\t{reg}" for rid, reg in rows)
                    + "\n")


def test_regions_consistency(tmp_path, monkeypatch):
    vd = load_vd()
    plugin = tmp_path / "sp"
    bboxes = tmp_path / "leagues_regions.tsv"
    # Chunk (30,40) fully inside the KANDARIN bbox; chunk (31,40)
    # outside every bbox -> NEUTRAL expectation.
    _bbox_file(bboxes, [("KANDARIN", 30 * 64, 30 * 64 + 63,
                        40 * 64, 40 * 64 + 63)])
    rid_kandarin = (30 << 8) | 40
    rid_neutral = (31 << 8) | 40
    zp = _mini_zip(tmp_path / "collision-map.zip",
                   {"30_40": [(1, 1, 0, 0)], "31_40": [(2, 2, 0, 0)]})
    regions = plugin / "src/main/resources/leagues/regions.tsv"
    # Generated file is missing rid_kandarin entirely and mislabels
    # rid_neutral as MORYTANIA.
    _regions_file(regions, [(rid_neutral, "MORYTANIA")])
    _patch_leaf(vd, monkeypatch, plugin, zip_path=zp,
                ls_files=["src/main/resources/leagues/regions.tsv"])
    monkeypatch.setattr(vd, "BBOX_TSV", bboxes)
    findings = vd.CHECKS["regions"]()
    assert any(str(rid_kandarin) in f and "absent" in f.lower()
               for f in findings)
    assert any(str(rid_neutral) in f and "MORYTANIA" in f
               for f in findings)


def test_regions_consistency_clean(tmp_path, monkeypatch):
    vd = load_vd()
    plugin = tmp_path / "sp"
    bboxes = tmp_path / "leagues_regions.tsv"
    _bbox_file(bboxes, [("KANDARIN", 30 * 64, 30 * 64 + 63,
                        40 * 64, 40 * 64 + 63)])
    rid_kandarin = (30 << 8) | 40
    zp = _mini_zip(tmp_path / "collision-map.zip",
                   {"30_40": [(1, 1, 0, 0)]})
    regions = plugin / "src/main/resources/leagues/regions.tsv"
    _regions_file(regions, [(rid_kandarin, "KANDARIN")])
    _patch_leaf(vd, monkeypatch, plugin, zip_path=zp,
                ls_files=["src/main/resources/leagues/regions.tsv"])
    monkeypatch.setattr(vd, "BBOX_TSV", bboxes)
    assert vd.CHECKS["regions"]() == []


# ---------- destinations advisory check ----------


def _exceptions_file(path, rows):
    path.write_text("# destination walkability exceptions\n"
                    + "\n".join(rows) + ("\n" if rows else ""))


def _destination_fixture(plugin, rows):
    return _write_tsv(
        plugin, "src/main/resources/destinations/game_features/bank.tsv",
        ["Destination", "Info", "Skills", "Quests", "Varbits",
         "VarPlayers"],
        rows)


def test_destinations_advisory_lists_blocked_tiles(tmp_path,
                                                 monkeypatch):
    vd = load_vd()
    plugin = tmp_path / "sp"
    rel = _destination_fixture(plugin, [
        "1950 2570 0\tBlocked Bank\t\t\t\t",
        "1921 2561 0\tOpen Bank\t\t\t\t",
    ])
    zp = _mini_zip(tmp_path / "collision-map.zip",
                   {"30_40": [(1, 1, 0, 0)]})
    exc = tmp_path / "exceptions.tsv"
    _exceptions_file(exc, [])
    _patch_leaf(vd, monkeypatch, plugin, ls_files=[rel], zip_path=zp)
    monkeypatch.setattr(vd, "DESTINATION_EXCEPTIONS", exc)
    findings = vd.CHECKS["destinations"]()
    # The blocked tile is a finding naming file:line, tile and Info;
    # the walkable tile is not.
    assert any(f.startswith(f"{rel}:2") and "1950 2570 0" in f
               and "Blocked Bank" in f for f in findings)
    assert not any("Open Bank" in f for f in findings)


def test_destinations_exceptions_suppress(tmp_path, monkeypatch):
    vd = load_vd()
    plugin = tmp_path / "sp"
    rel = _destination_fixture(plugin, [
        "1950 2570 0\tBlocked Bank\t\t\t\t",
    ])
    zp = _mini_zip(tmp_path / "collision-map.zip",
                   {"30_40": [(1, 1, 0, 0)]})
    exc = tmp_path / "exceptions.tsv"
    _exceptions_file(exc, ["1950 2570 0\tarrival animation relocates "
                           "the player"])
    _patch_leaf(vd, monkeypatch, plugin, ls_files=[rel], zip_path=zp)
    monkeypatch.setattr(vd, "DESTINATION_EXCEPTIONS", exc)
    assert vd.CHECKS["destinations"]() == []


def test_destinations_never_gates(tmp_path, monkeypatch, capsys):
    vd = load_vd()
    plugin = tmp_path / "sp"
    rel = _destination_fixture(plugin, [
        "1950 2570 0\tBlocked Bank\t\t\t\t",
    ])
    zp = _mini_zip(tmp_path / "collision-map.zip",
                   {"30_40": [(1, 1, 0, 0)]})
    exc = tmp_path / "exceptions.tsv"
    _exceptions_file(exc, [])
    _patch_leaf(vd, monkeypatch, plugin, ls_files=[rel], zip_path=zp)
    monkeypatch.setattr(vd, "DESTINATION_EXCEPTIONS", exc)
    rc = vd.main(["destinations"])
    out = capsys.readouterr().out
    # Advisory findings print under their own section and the leaf
    # still exits 0 — the gate is the orchestrator's hard checks.
    assert rc == 0
    assert "Destination walkability" in out
    assert "Summary:" in out


def test_exceptions_file_malformed_row(tmp_path, monkeypatch):
    vd = load_vd()
    plugin = tmp_path / "sp"
    rel = _destination_fixture(plugin, ["1950 2570 0\tB\t\t\t\t"])
    zp = _mini_zip(tmp_path / "collision-map.zip",
                   {"30_40": [(1, 1, 0, 0)]})
    _patch_leaf(vd, monkeypatch, plugin, ls_files=[rel], zip_path=zp)
    for bad in ("not-a-coord\tsome reason",
                "1950 2570 0",            # no reason cell
                "1950 2570 0\t"):         # empty reason
        exc = tmp_path / "exceptions.tsv"
        _exceptions_file(exc, [bad])
        monkeypatch.setattr(vd, "DESTINATION_EXCEPTIONS", exc)
        with pytest.raises(SystemExit):
            vd.CHECKS["destinations"]()


# ---------- scenario-csv check ----------


ROUTES_HEADER = ("name,category,start_x,start_y,start_plane,x,y,plane,"
                 "preset,inventory,equipment,bank,varbits,skill_levels,"
                 "config_overrides,expected_length,minimum_length")


def _write_csv(root, rel, header, rows):
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(header + "\n" + "\n".join(rows) + "\n")
    return rel


def _patch_repo_leaf(vd, monkeypatch, repo, *, ls_files=()):
    monkeypatch.setattr(vd, "REPO", repo)
    monkeypatch.setattr(vd, "_git_ls_files_repo",
                        lambda *ps: list(ls_files))


def test_scenario_csv_clean_fixture_passes(tmp_path, monkeypatch):
    vd = load_vd()
    repo = tmp_path / "repo"
    rel = _write_csv(
        repo, "src/test/resources/dashboard/routes.csv",
        ROUTES_HEADER,
        ["Gnome stronghold,quest,2459,3438,0,2460,3437,0,ALL"
         ",,,,,,,42,42",
         "Lumbridge,quest,3222,3218,0,3222,3218,0,bank"
         ",,,,,,useFairyRings=true,10,10"])
    _patch_repo_leaf(vd, monkeypatch, repo, ls_files=[rel])
    assert vd.CHECKS["scenario-csv"]() == []


def test_scenario_csv_flags(tmp_path, monkeypatch):
    vd = load_vd()
    repo = tmp_path / "repo"
    header = ROUTES_HEADER + ",expect_reachable"
    rel = _write_csv(
        repo, "src/test/resources/dashboard/routes.csv",
        header,
        [# embedded comma: 19 fields vs 18 headers
         "Bad,Name,3222,3218,0,3222,3218,0,ALL,,,,,,,,10,10,true",
         # non-integer x
         "R3,cat,3222,3218,0,abc,3218,0,ALL,,,,,,,10,10,true",
         # unknown preset
         "R4,cat,3222,3218,0,3222,3218,0,NOPE,,,,,,,10,10,true",
         # unknown config_overrides key
         "R5,cat,3222,3218,0,3222,3218,0,ALL,,,,,,noSuchKey=true,"
         ",10,true",
         # non-integer expected_length
         "R6,cat,3222,3218,0,3222,3218,0,ALL,,,,,,,abc,10,true",
         # expect_reachable not true/false
         "R7,cat,3222,3218,0,3222,3218,0,ALL,,,,,,,10,10,maybe"])
    _patch_repo_leaf(vd, monkeypatch, repo, ls_files=[rel])
    findings = vd.CHECKS["scenario-csv"]()
    assert any(f.startswith(f"{rel}:2") and "fields" in f
               for f in findings)
    assert any(f.startswith(f"{rel}:3") and "x" in f
               and "abc" in f for f in findings)
    assert any(f.startswith(f"{rel}:4") and "NOPE" in f
               for f in findings)
    assert any(f.startswith(f"{rel}:5") and "noSuchKey" in f
               for f in findings)
    assert any(f.startswith(f"{rel}:6") and "expected_length" in f
               and "abc" in f for f in findings)
    assert any(f.startswith(f"{rel}:7") and "expect_reachable" in f
               and "maybe" in f for f in findings)


def test_scenario_csv_missing_required_column(tmp_path, monkeypatch):
    vd = load_vd()
    repo = tmp_path / "repo"
    rel = _write_csv(
        repo, "src/test/resources/dashboard/routes.csv",
        "name,category,x,y",   # routes header missing plane
        ["R,cat,1,2"])
    _patch_repo_leaf(vd, monkeypatch, repo, ls_files=[rel])
    findings = vd.CHECKS["scenario-csv"]()
    assert any("plane" in f for f in findings)


def test_scenario_csv_clue_format(tmp_path, monkeypatch):
    vd = load_vd()
    repo = tmp_path / "repo"
    # clue grammar: requires clue_type+x+y+plane; routes-only rules
    # (preset/config_overrides/expected_length) must not fire even
    # when a like-named column carries values they would reject.
    rel = _write_csv(
        repo, "src/test/resources/dashboard/clues.csv",
        "clue_type,x,y,plane,preset,source_file",
        ["EASY,1234,5678,0,NOPE,clues.txt",
         "HARD,notanint,5678,0,ALL,clues.txt"])
    _patch_repo_leaf(vd, monkeypatch, repo, ls_files=[rel])
    findings = vd.CHECKS["scenario-csv"]()
    assert any(f.startswith(f"{rel}:3") and "x" in f
               for f in findings)
    # NOPE preset is tolerated under the clue grammar — the loader
    # hardcodes ALL for clue rows and never reads preset.
    assert not any("NOPE" in f for f in findings)


def test_scenario_csv_unknown_columns_tolerated(tmp_path, monkeypatch):
    vd = load_vd()
    repo = tmp_path / "repo"
    # source_file/source_line are registered extras carried by
    # clue_locations_full.csv today; they must not be findings.
    rel = _write_csv(
        repo, "src/test/resources/dashboard/routes.csv",
        ROUTES_HEADER + ",source_file,source_line",
        ["R,cat,3222,3218,0,3222,3218,0,ALL,,,,,,,10,10,"
         "routes.md,12"])
    _patch_repo_leaf(vd, monkeypatch, repo, ls_files=[rel])
    assert vd.CHECKS["scenario-csv"]() == []


def test_scenario_csv_unknown_header_cell(tmp_path, monkeypatch):
    vd = load_vd()
    repo = tmp_path / "repo"
    # A typo'd column is silently dropped by the Java loader — the
    # same bug class the TSV header whitelist exists to catch.
    rel = _write_csv(
        repo, "src/test/resources/dashboard/routes.csv",
        ROUTES_HEADER + ",expeced_length",
        ["R,cat,3222,3218,0,3222,3218,0,ALL,,,,,,,10,10,10"])
    _patch_repo_leaf(vd, monkeypatch, repo, ls_files=[rel])
    findings = vd.CHECKS["scenario-csv"]()
    assert any("expeced_length" in f for f in findings)


def test_scenario_csv_expect_reachable_column_known(tmp_path,
                                                  monkeypatch):
    vd = load_vd()
    repo = tmp_path / "repo"
    rel = _write_csv(
        repo, "src/test/resources/dashboard/routes.csv",
        ROUTES_HEADER + ",expect_reachable",
        ["R1,cat,3222,3218,0,3222,3218,0,ALL,,,,,,,10,10,true",
         "R2,cat,3222,3218,0,3222,3218,0,ALL,,,,,,,10,10,false"])
    _patch_repo_leaf(vd, monkeypatch, repo, ls_files=[rel])
    assert vd.CHECKS["scenario-csv"]() == []


def test_scenario_csv_enumerates_committed_only(tmp_path,
                                                monkeypatch):
    vd = load_vd()
    repo = tmp_path / "repo"
    # A malformed CSV sits on disk but is uncommitted — the seam
    # does not list it, so it can never enter the check.
    bad_rel = _write_csv(
        repo, "src/test/resources/dashboard/debug.csv",
        ROUTES_HEADER,
        ["R,cat,3222,3218,0,abc,3218,0,ALL,,,,,,,10,10"])
    _patch_repo_leaf(vd, monkeypatch, repo, ls_files=[])
    assert vd.CHECKS["scenario-csv"]() == []
    _patch_repo_leaf(vd, monkeypatch, repo, ls_files=[bad_rel])
    findings = vd.CHECKS["scenario-csv"]()
    assert any("abc" in f for f in findings)


def test_scenario_csv_registered_and_dispatches(tmp_path,
                                                monkeypatch, capsys):
    vd = load_vd()
    repo = tmp_path / "repo"
    rel = _write_csv(
        repo, "src/test/resources/dashboard/routes.csv",
        ROUTES_HEADER,
        ["R,cat,3222,3218,0,3222,3218,0,ALL,,,,,,,10,10"])
    _patch_repo_leaf(vd, monkeypatch, repo, ls_files=[rel])
    assert "scenario-csv" in vd.CHECKS
    rc = vd.main(["scenario-csv"])
    out = capsys.readouterr().out
    assert rc == 0
    assert "Summary:" in out


def test_scenario_csv_committed_datasets_clean():
    # The real committed dashboard CSVs are the healthy-data case.
    vd = load_vd()
    assert vd.CHECKS["scenario-csv"]() == []
