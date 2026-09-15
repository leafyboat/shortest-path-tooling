"""Tests for ``scripts/analyse_dashboard_runs.py``.

The script is loaded via importlib because ``scripts/`` has no
``__init__.py``.  Fixtures build fabricated bundle trees under
``tmp_path`` — no Gradle or real dashboard output needed.
"""

import importlib.util
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCRIPTS = ROOT / "scripts"


def load_adr():
    spec = importlib.util.spec_from_file_location(
        "analyse_dashboard_runs", SCRIPTS / "analyse_dashboard_runs.py")
    adr = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(adr)
    return adr


def _write_report(bundle_dir, runs=None):
    bundle_dir.mkdir(parents=True, exist_ok=True)
    report_path = bundle_dir / "report.json"
    report_path.write_text(json.dumps({"runs": runs or []}))
    return report_path


def _write_index(bundles_dir, entries):
    (bundles_dir / "index.json").write_text(
        json.dumps({"bundles": entries}))


def test_find_bundles_prefers_bundles_subdir(tmp_path):
    adr = load_adr()
    root = tmp_path / "out"
    report = _write_report(root / "bundles" / "a")
    # Flat-layout reports alongside bundles/ must not win — a real
    # dashboard output root keeps its bundles under bundles/.
    _write_report(root / "legacy")
    (root / "report.json").write_text("{}")
    assert adr.find_bundles(root) == {"a": report}


def test_find_bundles_flat_fallback(tmp_path):
    adr = load_adr()
    root = tmp_path / "out"
    report = _write_report(root / "a")
    assert adr.find_bundles(root) == {"a": report}


def test_index_titles_label_output(tmp_path, monkeypatch, capsys):
    adr = load_adr()
    baseline = tmp_path / "baseline"
    candidate = tmp_path / "candidate"
    for root in (baseline, candidate):
        _write_report(root / "bundles" / "a",
                      runs=[{"name": "r1", "reached": True,
                             "stats": {"elapsedNanos": 5_000_000}}])
        _write_index(root / "bundles",
                     [{"name": "a", "title": "My Title",
                       "reportPath": "a/report.json"}])
    monkeypatch.setattr(
        sys, "argv",
        ["analyse_dashboard_runs.py", str(baseline), str(candidate)])
    assert adr.main() == 0
    out = capsys.readouterr().out
    assert "My Title" in out
    assert "### `a`" not in out


def test_load_runs_missing_and_malformed(tmp_path):
    adr = load_adr()
    assert adr.load_runs(tmp_path / "nope" / "report.json") == []
    bad = tmp_path / "bad"
    bad.mkdir()
    (bad / "report.json").write_text("{not json")
    assert adr.load_runs(bad / "report.json") == []
