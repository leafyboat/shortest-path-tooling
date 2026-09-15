"""Contract test for ``.github/workflows/ci.yml``.

Parses the committed workflow and asserts the deterministic PR/push
gate keeps its shape: a ``validate`` job that runs the pytest suite
and ``maintenance.py validate --skip-freshness`` on Python 3.14,
installs the suite's only third-party deps, and checks out the
submodule recursively (the leaf checks enumerate ``git -C
shortest-path ls-files``).  Also asserts ``ci.yml`` carries no
``schedule`` trigger — scheduled validation was rejected in favour of
the existing ``update-submodule.yml`` daily bump.  A workflow edit
that removes or weakens the gate fails the suite it gates.
"""

from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
CI = ROOT / ".github" / "workflows" / "ci.yml"


def _load_ci():
    return yaml.safe_load(CI.read_text())


def _validate_job():
    return _load_ci()["jobs"]["validate"]


def _run_commands(job):
    return " ".join(step.get("run", "") for step in job["steps"])


def test_validate_job_exists_and_runs_gate():
    runs = _run_commands(_validate_job())
    assert "pytest tests/ -x" in runs
    assert "validate --skip-freshness" in runs


def test_ci_has_no_cron():
    doc = _load_ci()
    # PyYAML (YAML 1.1) parses the bare key ``on:`` as boolean True.
    on_block = doc.get("on") or doc.get(True) or {}
    assert "schedule" not in on_block


def test_ci_installs_test_deps():
    runs = _run_commands(_validate_job())
    assert "pip install" in runs
    assert "pytest" in runs
    assert "pyyaml" in runs


def test_ci_checks_out_submodules():
    steps = _validate_job()["steps"]
    checkouts = [s for s in steps
                 if str(s.get("uses", "")).startswith("actions/checkout")]
    assert checkouts, "validate job has no actions/checkout step"
    assert checkouts[0].get("with", {}).get("submodules") == "recursive"


def test_runbook_documents_ci_gate():
    text = (ROOT / "docs" / "maintenance.md").read_text()
    assert "validate --skip-freshness" in text
    assert "pytest" in text.lower()
