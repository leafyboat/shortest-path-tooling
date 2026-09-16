"""Tests for ``scripts/compare_collision_maps.py``.

The script is loaded via importlib because ``scripts/`` has no
``__init__.py``.
"""

import importlib.util
import io
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCRIPTS = ROOT / "scripts"

spec = importlib.util.spec_from_file_location(
    "compare_collision_maps", SCRIPTS / "compare_collision_maps.py")
ccm = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ccm)

BITS_PER_PLANE = ccm.REGION * ccm.REGION * 2  # 8192 bits = 1024 bytes


def test_planes_counts_partial_top_plane():
    # BitSet.toByteArray() trims trailing zero bytes: a region whose
    # highest set bit lands on plane 1 serializes to 1025 bytes, one
    # byte past a plane boundary.  Ceiling semantics — matching
    # SplitFlagMap and collision_zip.py — must count that top plane or
    # every edge on it goes uncompared.
    blob = bytearray(BITS_PER_PLANE // 8 + 1)
    assert ccm.planes(bytes(blob)) == 2
    # Exactly-aligned blobs still count exactly.
    assert ccm.planes(bytes(BITS_PER_PLANE // 8)) == 1
    assert ccm.planes(b"") == 0


def test_compare_region_detects_partial_top_plane_diff():
    # Old map has exactly one plane; new map sets the first flag bit of
    # plane 1 (tile (0,0) plane 1, N edge).  Floor-divided plane counts
    # would report no change.
    old = bytes(BITS_PER_PLANE // 8)
    new = bytearray(BITS_PER_PLANE // 8 + 1)
    new[BITS_PER_PLANE // 8] = 0x01
    stats = ccm.compare_region(old, bytes(new))
    assert stats["blocked_to_opened"] == 1
    assert stats["opened_to_blocked"] == 0


def test_compare_region_top_plane_regression():
    # The reverse direction: a set bit on the partial top plane of the
    # old map that is gone in the new map must count as opened->blocked.
    old = bytearray(BITS_PER_PLANE // 8 + 1)
    old[BITS_PER_PLANE // 8] = 0x02  # E edge of tile (0,0) plane 1
    new = bytes(BITS_PER_PLANE // 8)
    stats = ccm.compare_region(bytes(old), new)
    assert stats["opened_to_blocked"] == 1


def _zip_bytes(regions):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        for name, data in regions.items():
            zf.writestr(name, data)
    return buf.getvalue()


def test_main_reports_top_plane_change(tmp_path, capsys):
    old_zip = tmp_path / "old.zip"
    new_zip = tmp_path / "new.zip"
    old = bytes(BITS_PER_PLANE // 8)
    new = bytearray(BITS_PER_PLANE // 8 + 1)
    new[BITS_PER_PLANE // 8] = 0x01
    old_zip.write_bytes(_zip_bytes({"50_50": old}))
    new_zip.write_bytes(_zip_bytes({"50_50": bytes(new)}))
    sys_argv = ["compare_collision_maps.py", str(old_zip), str(new_zip)]
    argv, sys.argv = sys.argv, sys_argv
    try:
        rc = ccm.main()
    finally:
        sys.argv = argv
    assert rc == 0
    out = capsys.readouterr().out
    assert "blocked_to_opened" in out
    # The single flipped edge must appear in the totals, not be dropped
    # with the partial plane.
    totals_line = next(
        l for l in out.splitlines() if "blocked_to_opened" in l)
    assert totals_line.rstrip().endswith("1")
