"""Tests for ``scripts/import_issues.py``.

All tests run against recorded ``gh ... --json`` fixtures in
``tests/fixtures/`` — no network access.  The script is loaded via
importlib because ``scripts/`` has no ``__init__.py``.
"""
from __future__ import annotations

import importlib.util
import json
import sys
from pathlib import Path

import pytest
import yaml

ROOT = Path(__file__).resolve().parent.parent
SCRIPT_PATH = ROOT / "scripts" / "import_issues.py"
FIXTURES = Path(__file__).resolve().parent / "fixtures"

spec = importlib.util.spec_from_file_location("import_issues", SCRIPT_PATH)
ii = importlib.util.module_from_spec(spec)
sys.modules["import_issues"] = ii
spec.loader.exec_module(ii)


def load_fixture(name):
    return json.loads((FIXTURES / name).read_text())


def run_sync(tmp_path, monkeypatch, issues=None, prs=None, extra_args=None):
    def dispatch(args):
        if args[:2] == ["pr", "list"]:
            return prs if prs is not None else load_fixture("gh_pr_list.json")
        if args[:2] == ["issue", "view"]:
            # `gh issue view` returns a single object, not a list.
            number = int(args[2])
            pool = (issues if issues is not None
                    else load_fixture("gh_issue_list.json"))
            return next(r for r in pool if r["number"] == number)
        return issues if issues is not None else load_fixture("gh_issue_list.json")
    monkeypatch.setattr(ii, "gh_json", dispatch)
    argv = ["sync", "--output-dir", str(tmp_path)]
    if extra_args:
        argv.extend(extra_args)
    return ii.main(argv)


def test_gh_json_uses_timeout(monkeypatch):
    calls = {}

    class FakeProc:
        stdout = "[]"

    def fake_run(cmd, **kwargs):
        calls.update(kwargs)
        return FakeProc()

    monkeypatch.setattr(ii.subprocess, "run", fake_run)
    assert ii.gh_json(["issue", "list"]) == []
    assert calls["timeout"] > 0


def test_gh_json_timeout_exits_cleanly(monkeypatch):
    def fake_run(cmd, **kwargs):
        raise ii.subprocess.TimeoutExpired(cmd, kwargs.get("timeout"))

    monkeypatch.setattr(ii.subprocess, "run", fake_run)
    with pytest.raises(SystemExit):
        ii.gh_json(["issue", "list"])


def stub_prs(monkeypatch):
    monkeypatch.setattr(
        ii, "gh_json", lambda args: load_fixture("gh_pr_list.json"))
    return ii.fetch_fix_candidates()


def frontmatter_and_body(path):
    text = path.read_text()
    assert text.startswith("---"), "shadow file must start with YAML frontmatter"
    fm, body = ii.load_shadow(path)
    assert fm is not None, "shadow file frontmatter must parse as a dict"
    return fm, body


def test_sync_writes_shadow_file(tmp_path, monkeypatch):
    issues = load_fixture("gh_issue_list.json")
    rc = run_sync(tmp_path, monkeypatch, issues)
    assert rc == 0
    files = sorted(tmp_path.glob("ISSUE-*.md"))
    assert len(files) == len(issues)
    fm, body = frontmatter_and_body(files[0])
    number = int(files[0].stem.split("-")[1])
    assert fm["upstream"] == f"Skretzo/shortest-path#{number}"
    assert fm["status"] == "reported"
    assert isinstance(fm["history"], list) and len(fm["history"]) >= 1
    assert "## Upstream Report" in body
    assert "UNTRUSTED external content" in body


def test_sync_filename_is_issue_number(tmp_path, monkeypatch):
    issues = load_fixture("gh_issue_list.json")
    run_sync(tmp_path, monkeypatch, issues)
    names = sorted(p.name for p in tmp_path.iterdir())
    expected = sorted(f"ISSUE-{r['number']}.md" for r in issues)
    assert names == expected
    # The hostile title ("../../evil ...") must not influence the filename.
    assert (tmp_path / "ISSUE-99990.md").is_file()


def test_sync_dry_run_writes_nothing(tmp_path, monkeypatch, capsys):
    rc = run_sync(tmp_path, monkeypatch, extra_args=["--dry-run"])
    assert rc == 0
    assert list(tmp_path.iterdir()) == []
    out = capsys.readouterr().out
    assert "ISSUE-549.md" in out


def test_sync_single_issue_fetch(tmp_path, monkeypatch):
    # --issue N goes through `gh issue view`, which returns one object.
    issues = load_fixture("gh_issue_list.json")
    target = issues[0]["number"]
    rc = run_sync(tmp_path, monkeypatch, issues,
                  extra_args=["--issue", str(target)])
    assert rc == 0
    names = [f.name for f in tmp_path.glob("ISSUE-*.md")]
    assert names == [f"ISSUE-{target}.md"]


def test_sync_state_all_writes_all_issues(tmp_path, monkeypatch):
    issues = load_fixture("gh_issue_list_all.json")
    rc = run_sync(tmp_path, monkeypatch, issues,
                  extra_args=["--state", "all", "--no-digest"])
    assert rc == 0
    names = {f.name for f in tmp_path.glob("ISSUE-*.md")}
    assert names == {f"ISSUE-{r['number']}.md" for r in issues}


def test_sync_empty_body_and_comments(tmp_path, monkeypatch):
    run_sync(tmp_path, monkeypatch)
    path = tmp_path / "ISSUE-99991.md"
    assert path.is_file()
    fm, _body = frontmatter_and_body(path)
    assert fm["status"] == "reported"


def test_fix_candidate_confirmed_link(monkeypatch):
    out = stub_prs(monkeypatch)
    assert 509 in out
    entry = next(e for e in out[509] if e["pr"] == 541)
    assert entry["link"] == "confirmed"
    assert entry["url"] == "https://github.com/Skretzo/shortest-path/pull/541"
    assert entry["author"] == "pr0f3ss"
    assert entry["branch"] == "fix/509-quest-cape-all-quests"


def test_fix_candidate_heuristic_body_link(monkeypatch):
    # Live-verified gap: body says "Fixes issue #504" but GitHub produced
    # no closingIssuesReferences entry.
    out = stub_prs(monkeypatch)
    assert 504 in out
    entry = next(e for e in out[504] if e["pr"] == 539)
    assert entry["link"] == "heuristic"


def test_fix_candidate_heuristic_branch_link(monkeypatch):
    out = stub_prs(monkeypatch)
    assert 99992 in out
    entry = next(e for e in out[99992] if e["pr"] == 99993)
    assert entry["link"] == "heuristic"


def test_fix_candidate_unrelated_pr_absent(monkeypatch):
    out = stub_prs(monkeypatch)
    all_prs = [e["pr"] for entries in out.values() for e in entries]
    assert 99994 not in all_prs


def test_fix_candidate_ignores_foreign_repo_refs(monkeypatch):
    # PR 530's closingIssuesReferences resolve into
    # KeiranY/clue-pathing-runelite-plugin — those issue numbers belong to
    # a different project and must not be counted as confirmed upstream
    # links.
    out = stub_prs(monkeypatch)
    assert 27 not in out and 31 not in out and 34 not in out
    assert all(e["pr"] != 530 for entries in out.values() for e in entries)


def test_fix_candidates_merged_state_lookup(monkeypatch):
    # The close-time attribution ladder needs merged PRs — the stored
    # fix_candidates map stays open-only, so callers pass state=.
    captured = {}

    def fake_gh(args):
        captured["args"] = list(args)
        return load_fixture("gh_pr_list_merged.json")

    monkeypatch.setattr(ii, "gh_json", fake_gh)
    out = ii.fetch_fix_candidates(state="merged")
    args = captured["args"]
    assert args[args.index("--state") + 1] == "merged"
    # confirmed via closingIssuesReferences resolving into upstream
    assert 509 in out
    entry = next(e for e in out[509] if e["pr"] == 541)
    assert entry["link"] == "confirmed"
    assert entry["mergedAt"] == "2026-08-20T00:00:00Z"
    # heuristic via the "Fixes issue #504" phrasing GitHub's parser misses
    assert 504 in out
    entry = next(e for e in out[504] if e["pr"] == 539)
    assert entry["link"] == "heuristic"
    assert entry["mergedAt"] == "2026-08-19T10:00:00Z"
    # the unrelated merged PR maps to nothing
    all_prs = [e["pr"] for entries in out.values() for e in entries]
    assert 571 not in all_prs


def test_fix_candidates_default_state_is_open(monkeypatch):
    # sync's stored map semantics are unchanged: no state argument still
    # fetches --state open.
    captured = {}

    def fake_gh(args):
        captured["args"] = list(args)
        return load_fixture("gh_pr_list.json")

    monkeypatch.setattr(ii, "gh_json", fake_gh)
    ii.fetch_fix_candidates()
    args = captured["args"]
    assert args[args.index("--state") + 1] == "open"


def test_fix_candidates_entries_carry_mergedat(monkeypatch):
    # mergedAt flows through on every emitted entry — null on open PRs.
    out = stub_prs(monkeypatch)
    entry = next(e for e in out[509] if e["pr"] == 541)
    assert entry["mergedAt"] is None


def test_sync_populates_fix_candidates_frontmatter(tmp_path, monkeypatch):
    run_sync(tmp_path, monkeypatch)
    fm, _body = frontmatter_and_body(tmp_path / "ISSUE-549.md")
    prs = [e["pr"] for e in fm["fix_candidates"]]
    assert 99995 in prs
    entry = next(e for e in fm["fix_candidates"] if e["pr"] == 99995)
    assert entry["link"] == "confirmed"


def test_shadow_schema_fields(tmp_path, monkeypatch):
    run_sync(tmp_path, monkeypatch)
    fm, _body = frontmatter_and_body(tmp_path / "ISSUE-549.md")
    assert set(fm.keys()) == {
        "upstream", "url", "title", "upstream_state",
        "upstream_state_reason", "labels", "author", "created_at",
        "updated_at", "synced_at", "status", "phase", "fix_candidates",
        "scenario_rows", "triage", "verification", "history",
    }
    t = fm["triage"]
    assert isinstance(t, dict)
    assert set(t.keys()) == {
        "verdict", "expressible", "blocked_on", "unblock_conditions",
        "feature_size", "evidence", "triaged_at", "triaged_by",
    }
    # The skeleton ships verdict: null so a freshly synced open issue
    # fails the coverage gate until it is classified.
    assert t["verdict"] is None
    v = fm["verification"]
    assert isinstance(v, dict)
    assert set(v.keys()) == {
        "command", "dataset_rows", "report", "fix_commit", "fix_pr",
        "verifier", "verified_at",
    }


def test_shadow_body_sections(tmp_path, monkeypatch):
    run_sync(tmp_path, monkeypatch)
    _fm, body = frontmatter_and_body(tmp_path / "ISSUE-549.md")
    headings = [
        "## Upstream Report", "## Upstream Comments",
        "## Normalized Scenario", "## Triage Notes",
        "## Requirements", "## Acceptance Criteria",
        "## Canonical References",
    ]
    idx = [body.index(h) for h in headings]
    assert idx == sorted(idx)


def test_shadow_upstream_text_fenced(tmp_path, monkeypatch):
    run_sync(tmp_path, monkeypatch)
    _fm, body = frontmatter_and_body(tmp_path / "ISSUE-549.md")
    issue = next(r for r in load_fixture("gh_issue_list.json")
                 if r["number"] == 549)
    title = issue["title"]
    body_line = next(l for l in (issue.get("body") or "").splitlines()
                     if l.strip())
    report_idx = body.index("## Upstream Report")
    scenario_idx = body.index("## Normalized Scenario")
    upstream_region = body[report_idx:scenario_idx]
    assert "UNTRUSTED external content" in upstream_region
    assert title in upstream_region
    assert body_line in upstream_region
    # Upstream text must not leak into the maintainer sections.
    assert title not in body[scenario_idx:]
    assert body_line not in body[scenario_idx:]


def test_shadow_normalized_scenario_table(tmp_path, monkeypatch):
    run_sync(tmp_path, monkeypatch)
    _fm, body = frontmatter_and_body(tmp_path / "ISSUE-549.md")
    section = body.split("## Normalized Scenario", 1)[1].split("## ", 1)[0]
    for field in ("name", "category", "start", "target", "preset",
                  "config_overrides", "expected"):
        assert f"| {field} |" in section
    assert "CSV row ref:" in section
    assert "scenarios.csv" in section


def test_list_outputs_status_lines(tmp_path, monkeypatch, capsys):
    run_sync(tmp_path, monkeypatch)
    capsys.readouterr()  # discard sync output
    rc = ii.main(["list", "--output-dir", str(tmp_path)])
    assert rc == 0
    lines = [l for l in capsys.readouterr().out.splitlines() if l.strip()]
    issues = load_fixture("gh_issue_list.json")
    assert len(lines) == len(issues)
    numbers = [int(l.split()[0].split("-")[1]) for l in lines]
    assert numbers == sorted(numbers)
    assert any(l.startswith("ISSUE-549") and "reported" in l
               for l in lines)


def test_list_outputs_verdict_column(tmp_path, capsys):
    # The verdict column sits between status and title; a file without a
    # triage block renders "-", a verdicted one renders the enum token.
    make_shadow(tmp_path, 1, status="reported", fm_extra={"triage": None})
    make_shadow(tmp_path, 2, status="reported",
                fm_extra={"triage": {"verdict": "data-gap"}})
    rc = ii.main(["list", "--output-dir", str(tmp_path)])
    assert rc == 0
    lines = [l for l in capsys.readouterr().out.splitlines() if l.strip()]
    by_stem = {l.split()[0]: l for l in lines}
    parts = by_stem["ISSUE-1"].split()
    assert parts[0] == "ISSUE-1" and parts[1] == "reported"
    assert parts[2] == "-"
    parts = by_stem["ISSUE-2"].split()
    assert parts[0] == "ISSUE-2" and parts[1] == "reported"
    assert parts[2] == "data-gap"


# --------------------------------------------------------------------------
# status subcommand — lifecycle transitions
# --------------------------------------------------------------------------

def make_shadow(tmp_path, number, status="reported", fm_extra=None,
                body_text="\n## Triage Notes\n\nmaintainer note\n"):
    """Write a minimal valid shadow file and return its path."""
    fm = {
        "upstream": f"Skretzo/shortest-path#{number}",
        "url": f"https://example.invalid/issues/{number}",
        "title": "fixture issue",
        "upstream_state": "open",
        "upstream_state_reason": None,
        "labels": ["bug"],
        "author": "reporter",
        "created_at": "2026-01-01T00:00:00Z",
        "updated_at": "2026-01-01T00:00:00Z",
        "synced_at": "2026-01-01T00:00:00Z",
        "status": status,
        "phase": None,
        "fix_candidates": [],
        "scenario_rows": [],
        "triage": {
            "verdict": "invalid",
            "expressible": None,
            "blocked_on": None,
            "unblock_conditions": ["fixture unblock condition"],
            "feature_size": None,
            "evidence": {"pin_sha": "fixturepin", "report": None},
            "triaged_at": None,
            "triaged_by": None,
        },
        "verification": {
            "command": None, "dataset_rows": [], "report": None,
            "fix_commit": None, "fix_pr": None, "verifier": None,
            "verified_at": None,
        },
        "history": [{"at": "2026-01-01T00:00:00Z", "event": "imported",
                     "by": "import_issues.py"}],
    }
    if fm_extra:
        fm.update(fm_extra)
    path = tmp_path / f"ISSUE-{number}.md"
    path.write_text(
        "---\n" + yaml.safe_dump(fm, sort_keys=False) + "---\n" + body_text)
    return path


def run_status(tmp_path, *argv):
    return ii.main(["status", "--output-dir", str(tmp_path), *argv])


def test_status_transition_appends_history(tmp_path, capsys):
    path = make_shadow(tmp_path, 1, status="reported")
    before = path.read_text()
    rc = run_status(tmp_path, "1", "triaged", "--by", "tester")
    assert rc == 0
    out = capsys.readouterr().out
    assert "ISSUE-1: reported -> triaged" in out
    fm, body = frontmatter_and_body(path)
    assert fm["status"] == "triaged"
    last = fm["history"][-1]
    assert last["event"] == "status: reported -> triaged"
    assert last["by"] == "tester"
    # Upstream-owned fields and the body must be untouched.
    assert fm["title"] == "fixture issue"
    assert fm["upstream_state"] == "open"
    assert body == before.split("---", 2)[2]


def test_status_note_appends_to_event(tmp_path):
    path = make_shadow(tmp_path, 10, status="reported")
    rc = run_status(tmp_path, "10", "needs_info", "--note", "asked reporter")
    assert rc == 0
    fm, _body = frontmatter_and_body(path)
    assert fm["history"][-1]["event"].endswith("— asked reporter")


def test_status_rejects_unknown_status(tmp_path, capsys):
    path = make_shadow(tmp_path, 2, status="reported")
    before = path.read_text()
    rc = run_status(tmp_path, "2", "bogus")
    assert rc != 0
    assert capsys.readouterr().err
    assert path.read_text() == before


def test_status_rejects_invalid_transition(tmp_path):
    path = make_shadow(tmp_path, 3, status="reported")
    before = path.read_text()
    assert run_status(tmp_path, "3", "verified") != 0
    assert run_status(tmp_path, "3", "closed") != 0
    assert path.read_text() == before


def test_status_verified_rejected_use_verify(tmp_path, capsys):
    # fixed -> verified looks plausible in the map but evidence-bearing
    # verification is a separate gate, so `status` must always refuse it.
    path = make_shadow(tmp_path, 4, status="fixed")
    before = path.read_text()
    rc = run_status(tmp_path, "4", "verified")
    assert rc != 0
    assert "verify" in capsys.readouterr().err
    assert path.read_text() == before


def test_status_phase_linked_requires_phase(tmp_path):
    path = make_shadow(tmp_path, 5, status="triaged")
    before = path.read_text()
    assert run_status(tmp_path, "5", "phase_linked") != 0
    assert path.read_text() == before
    assert run_status(tmp_path, "5", "phase_linked",
                      "--phase", "phases/xyz") == 0
    fm, _body = frontmatter_and_body(path)
    assert fm["status"] == "phase_linked"
    assert fm["phase"] == "phases/xyz"


def test_status_reopened_from_terminal(tmp_path):
    for n, status in ((6, "closed"), (7, "wontfix"), (8, "duplicate")):
        make_shadow(tmp_path, n, status=status)
        assert run_status(tmp_path, str(n), "reopened") == 0
        fm, _body = frontmatter_and_body(tmp_path / f"ISSUE-{n}.md")
        assert fm["status"] == "reopened"


def test_status_blocked_returns_to_active(tmp_path):
    make_shadow(tmp_path, 9, status="blocked")
    assert run_status(tmp_path, "9", "in_progress") == 0
    fm, _body = frontmatter_and_body(tmp_path / "ISSUE-9.md")
    assert fm["status"] == "in_progress"


def test_status_missing_file(tmp_path, capsys):
    rc = run_status(tmp_path, "999", "triaged")
    assert rc != 0
    assert "ISSUE-999.md" in capsys.readouterr().err


def test_status_user_fallback_when_getuser_raises(tmp_path, monkeypatch):
    # Minimal environments (containers, CI) can lack USER/LOGNAME and a
    # passwd entry — attribution falls back instead of crashing.
    monkeypatch.setattr(ii.getpass, "getuser",
                        lambda: (_ for _ in ()).throw(OSError()))
    make_shadow(tmp_path, 40, status="reported")
    assert run_status(tmp_path, "40", "triaged") == 0
    fm, _ = frontmatter_and_body(tmp_path / "ISSUE-40.md")
    assert fm["history"][-1]["by"] == "unknown"


# --------------------------------------------------------------------------
# check subcommand — shadow lint + scenario CSV lint
# --------------------------------------------------------------------------

SCENARIO_HEADER = (
    "name,category,start_x,start_y,start_plane,x,y,plane,preset,"
    "inventory,equipment,bank,varbits,skill_levels,config_overrides,"
    "expected_length,minimum_length")

PRD_BODY = (
    "\n## Triage Notes\n\nnote\n"
    "\n## Requirements\n- fix the thing\n"
    "\n## Acceptance Criteria\n- scenario passes\n"
    "\n## Canonical References\n- some file\n")


def make_scenarios_csv(tmp_path, rows, header=SCENARIO_HEADER):
    path = tmp_path / "scenarios.csv"
    path.write_text(header + "\n" + "\n".join(rows) + "\n")
    return path


def scenario_row(name="alpha scenario", category="collision-issue-1",
                 preset="UNIT_TEST", start="2504,3671,0",
                 target="2504,3660,0"):
    return f"{name},{category},{start},{target},{preset},,,,,,,,"


def run_check(tmp_path):
    return ii.main(["check", "--output-dir", str(tmp_path)])


def triage_block(**overrides):
    """A lint-clean ``triage:`` block (prose-only ``invalid`` verdict).

    ``verdict: invalid`` is not row-backed, so the fixture needs no
    scenario rows; ``unblock_conditions`` and ``evidence.pin_sha`` are
    populated so the block satisfies every non-coverage verdict rule.
    """
    block = {
        "verdict": "invalid",
        "expressible": None,
        "blocked_on": None,
        "unblock_conditions": ["fixture unblock condition"],
        "feature_size": None,
        "evidence": {"pin_sha": "fixturepin", "report": None},
        "triaged_at": None,
        "triaged_by": None,
    }
    block.update(overrides)
    return block


def test_check_clean_store_passes(tmp_path, capsys):
    make_shadow(tmp_path, 1, status="triaged", body_text=PRD_BODY)
    make_scenarios_csv(tmp_path, [scenario_row()])
    rc = run_check(tmp_path)
    assert rc == 0
    assert "check: clean" in capsys.readouterr().out


def test_check_flags_bad_status_enum(tmp_path, capsys):
    make_shadow(tmp_path, 2, status="bogus")
    rc = run_check(tmp_path)
    assert rc != 0
    out = capsys.readouterr().out
    assert "ISSUE-2.md" in out and "status" in out


def test_check_flags_missing_prd_sections_when_triaged(tmp_path, capsys):
    # The default helper body has no Requirements/Acceptance Criteria/
    # Canonical References sections — fine at `reported`, flagged at
    # `triaged` or later.
    make_shadow(tmp_path, 3, status="triaged")
    assert run_check(tmp_path) != 0
    out = capsys.readouterr().out
    assert "ISSUE-3.md" in out and "Requirements" in out

    sub = tmp_path / "second"
    sub.mkdir()
    make_shadow(sub, 3, status="reported")
    assert run_check(sub) == 0


def test_check_flags_verified_without_verification(tmp_path, capsys):
    for n, status in ((4, "verified"), (5, "closed")):
        make_shadow(tmp_path, n, status=status, body_text=PRD_BODY)
    rc = run_check(tmp_path)
    assert rc != 0
    out = capsys.readouterr().out
    assert "ISSUE-4.md" in out and "ISSUE-5.md" in out
    assert "verification" in out


def test_check_scenario_comma_in_name(tmp_path, capsys):
    # The naive split(",") parser reads an embedded comma as a field-count
    # mismatch — name/category must stay comma-free.
    make_shadow(tmp_path, 1, status="triaged", body_text=PRD_BODY)
    make_scenarios_csv(tmp_path, [
        scenario_row(name="Ape Atoll, dungeon gate")])
    assert run_check(tmp_path) != 0
    assert "scenarios.csv" in capsys.readouterr().out


def test_check_scenario_bad_preset(tmp_path):
    make_scenarios_csv(tmp_path, [
        scenario_row(category="collision-control", preset="BOGUS")])
    assert run_check(tmp_path) != 0


def test_check_scenario_unit_test_preset_accepted(tmp_path, capsys):
    # UNIT_TEST is the registry name the committed datasets and the
    # seed row use — it must lint clean.
    make_scenarios_csv(tmp_path, [
        scenario_row(category="collision-control", preset="UNIT_TEST")])
    rc = run_check(tmp_path)
    assert rc == 0
    assert "check: clean" in capsys.readouterr().out


def test_check_scenario_nonint_coord(tmp_path, capsys):
    make_scenarios_csv(tmp_path, [
        scenario_row(category="collision-control",
                     start="abc,3671,0")])
    assert run_check(tmp_path) != 0
    assert "start_x" in capsys.readouterr().out


def test_check_scenario_rows_crossref(tmp_path, capsys):
    # Direction 1: a shadow file's scenario_rows entry missing from the CSV.
    make_shadow(tmp_path, 1, status="triaged", body_text=PRD_BODY,
                fm_extra={"scenario_rows": ["ghost row"]})
    # Direction 2: an `*-issue-<N>` category with no sibling ISSUE-<N>.md.
    make_scenarios_csv(tmp_path, [
        scenario_row(name="alpha", category="collision-issue-42")])
    rc = run_check(tmp_path)
    assert rc != 0
    out = capsys.readouterr().out
    assert "ghost row" in out
    assert "42" in out


def test_check_scenario_leading_comment_line(tmp_path, capsys):
    # The dashboard loader reads the literal first line as the header —
    # a leading comment must not lint clean while the loader misparses.
    csv = tmp_path / "scenarios.csv"
    csv.write_text("# comment first\n" + SCENARIO_HEADER + "\n"
                   + scenario_row() + "\n")
    assert run_check(tmp_path) != 0
    assert "line 1" in capsys.readouterr().out


def test_check_scenario_header_columns(tmp_path, capsys):
    make_scenarios_csv(tmp_path, [scenario_row(
        category="collision-control")],
        header=SCENARIO_HEADER + ",bogus_col")
    assert run_check(tmp_path) != 0
    assert "bogus_col" in capsys.readouterr().out


def test_lint_accepts_expect_reachable(tmp_path, capsys):
    # `expect_reachable` is the committed-datasets column for intentional
    # unreachable assertions — the lint whitelist must accept it.
    make_shadow(tmp_path, 1, status="triaged", body_text=PRD_BODY)
    make_scenarios_csv(tmp_path, [scenario_row() + ",false"],
                       header=SCENARIO_HEADER + ",expect_reachable")
    rc = run_check(tmp_path)
    assert rc == 0
    assert "expect_reachable" not in capsys.readouterr().out


def test_lint_accepts_quests(tmp_path, capsys):
    # `quests` carries `Quest Name=STATE` tokens (NOT_STARTED/IN_PROGRESS/
    # FINISHED) — the staging lint must whitelist the column and accept
    # the grammar so quest-gated scenarios can be staged.
    make_shadow(tmp_path, 1, status="triaged", body_text=PRD_BODY)
    make_scenarios_csv(
        tmp_path,
        [scenario_row() + ",false,The Grand Tree=NOT_STARTED"],
        header=SCENARIO_HEADER + ",expect_reachable,quests")
    rc = run_check(tmp_path)
    assert rc == 0
    assert "quests" not in capsys.readouterr().out


def test_lint_rejects_malformed_quests(tmp_path, capsys):
    # A bare quest name carries no =STATE — it would silently mean
    # FINISHED, which is already the implicit default — so the cell must
    # fail the grammar rather than lint clean while changing nothing.
    make_shadow(tmp_path, 1, status="triaged", body_text=PRD_BODY)
    make_scenarios_csv(
        tmp_path,
        [scenario_row() + ",false,The Grand Tree"],
        header=SCENARIO_HEADER + ",expect_reachable,quests")
    rc = run_check(tmp_path)
    assert rc != 0
    assert "does not match its grammar" in capsys.readouterr().out


# --- triage verdict gate: coverage + evidence-shape rules ---------------

def test_check_open_issue_without_verdict_fails_coverage(tmp_path, capsys):
    # Every upstream-open file must carry a six-way verdict — a missing
    # or null verdict is a coverage error, not a silent pass.
    make_shadow(tmp_path, 50, status="reported",
                fm_extra={"triage": None})
    rc = run_check(tmp_path)
    assert rc != 0
    assert "lacks a valid triage.verdict" in capsys.readouterr().out


def test_check_open_issue_null_verdict_fails_coverage(tmp_path, capsys):
    # A skeleton `triage:` block with verdict: null (what a fresh sync
    # emits) must fail coverage exactly like a missing block.
    make_shadow(tmp_path, 59, status="reported",
                fm_extra={"triage": triage_block(verdict=None)})
    assert run_check(tmp_path) != 0
    assert "lacks a valid triage.verdict" in capsys.readouterr().out


def test_check_verdict_must_be_known_enum(tmp_path, capsys):
    make_shadow(tmp_path, 51, status="reported",
                fm_extra={"triage": triage_block(verdict="bogus")})
    assert run_check(tmp_path) != 0
    assert "lacks a valid triage.verdict" in capsys.readouterr().out


def test_check_unhashable_verdict_lints_not_crashes(tmp_path, capsys):
    # A non-string verdict (`[fixed]`, `{a: b}`, `5`) must produce a
    # coverage error, never a TypeError — the lint exists to catch
    # exactly this class of malformed YAML.
    for n, verdict in ((63, ["fixed"]), (64, {"a": "b"}), (65, 5)):
        make_shadow(tmp_path, n, status="reported",
                    fm_extra={"triage": triage_block(verdict=verdict)})
    rc = run_check(tmp_path)
    assert rc != 0
    out = capsys.readouterr().out
    assert out.count("lacks a valid triage.verdict") == 3


def test_check_scenario_rows_must_be_list(tmp_path, capsys):
    # scenario_rows is consumed as a list — a truthy string previously
    # satisfied the row-backed gate and then emitted one cross-ref error
    # per character, while an int crashed the iteration with TypeError.
    make_shadow(tmp_path, 66, status="reported",
                fm_extra={"scenario_rows": "alpha"})
    make_shadow(tmp_path, 67, status="reported",
                fm_extra={"scenario_rows": 5})
    rc = run_check(tmp_path)
    assert rc != 0
    out = capsys.readouterr().out
    assert out.count("scenario_rows must be a list") == 2
    # One schema error per file — never per-character garbage errors.
    assert "entry 'a'" not in out


def test_check_non_fixed_verdict_requires_unblock_conditions(tmp_path,
                                                           capsys):
    make_shadow(tmp_path, 52, status="reported",
                fm_extra={"triage": triage_block(
                    verdict="data-gap", expressible=False,
                    blocked_on="f2p-harness", unblock_conditions=[])})
    assert run_check(tmp_path) != 0
    assert "unblock_conditions" in capsys.readouterr().out


def test_check_row_backed_verdict_requires_rows_or_unexpressible(
        tmp_path, capsys):
    # `fixed` needs no unblock_conditions but is row-backed: without
    # scenario_rows it must declare expressible: false.
    make_shadow(tmp_path, 53, status="reported",
                fm_extra={"triage": triage_block(verdict="fixed",
                                               unblock_conditions=[])})
    assert run_check(tmp_path) != 0
    out = capsys.readouterr().out
    assert "scenario_rows" in out or "expressible" in out


def test_check_unexpressible_requires_blocked_on(tmp_path, capsys):
    make_shadow(tmp_path, 54, status="reported",
                fm_extra={"triage": triage_block(
                    verdict="data-gap", expressible=False,
                    blocked_on=None)})
    assert run_check(tmp_path) != 0
    assert "blocked_on" in capsys.readouterr().out


def test_check_unexpressible_row_backed_verdict_clean(tmp_path, capsys):
    # expressible: false + blocked_on is the lint escape for a
    # row-backed verdict with no scenario rows.
    make_shadow(tmp_path, 55, status="reported",
                fm_extra={"triage": triage_block(
                    verdict="data-gap", expressible=False,
                    blocked_on="f2p-harness")})
    rc = run_check(tmp_path)
    assert rc == 0
    assert "check: clean" in capsys.readouterr().out


def test_check_feature_verdict_requires_feature_size(tmp_path, capsys):
    make_shadow(tmp_path, 56, status="reported",
                fm_extra={"triage": triage_block(verdict="feature")})
    assert run_check(tmp_path) != 0
    assert "feature_size" in capsys.readouterr().out

    sub = tmp_path / "sized"
    sub.mkdir()
    make_shadow(sub, 56, status="reported",
                fm_extra={"triage": triage_block(
                    verdict="feature", feature_size="small")})
    assert run_check(sub) == 0


def test_check_verdict_requires_evidence_pin_sha(tmp_path, capsys):
    # Presence gate only — the pin is recorded at verdict time and may
    # legitimately move afterwards, so it is never compared to live HEAD.
    make_shadow(tmp_path, 57, status="reported",
                fm_extra={"triage": triage_block(
                    evidence={"pin_sha": None, "report": None})})
    assert run_check(tmp_path) != 0
    assert "pin_sha" in capsys.readouterr().out


def test_check_closed_upstream_skips_verdict_coverage(tmp_path, capsys):
    # Closed-upstream files are exempt from the coverage gate — once
    # upstream closes an issue it leaves the ratchet.
    make_shadow(tmp_path, 58, status="reported",
                fm_extra={"triage": None, "upstream_state": "closed"})
    rc = run_check(tmp_path)
    assert rc == 0
    assert "triage" not in capsys.readouterr().out


def test_check_missing_upstream_state_fails_closed(tmp_path, capsys):
    # upstream_state keys the verdict-coverage gate — a file that never
    # recorded it (hand-authored or written by another tool) must fail
    # closed, not lint clean without a verdict.
    path = make_shadow(tmp_path, 71, status="reported")
    fm, body = ii.load_shadow(path)
    del fm["upstream_state"]
    path.write_text("---\n" + yaml.safe_dump(fm, sort_keys=False)
                    + "---\n" + body)
    make_shadow(tmp_path, 72, status="reported",
                fm_extra={"upstream_state": None})
    rc = run_check(tmp_path)
    assert rc != 0
    out = capsys.readouterr().out
    assert out.count("missing required field upstream_state") == 2
    # The schema error fires — the verdict gate is waived, not skipped.
    assert "triage.verdict" not in out


def test_check_fixed_verdict_with_rows_clean(tmp_path, capsys):
    # `fixed` is the only verdict exempt from unblock_conditions — the
    # upstream fix already landed; the paired scenario rows are the
    # evidence it stays fixed.
    make_shadow(tmp_path, 60, status="reported",
                fm_extra={
                    "scenario_rows": ["alpha scenario"],
                    "triage": triage_block(verdict="fixed",
                                           unblock_conditions=[])})
    make_scenarios_csv(tmp_path, [scenario_row(category="routing-issue-60")])
    rc = run_check(tmp_path)
    assert rc == 0
    assert "check: clean" in capsys.readouterr().out


def test_check_plugin_bug_with_rows_clean(tmp_path, capsys):
    make_shadow(tmp_path, 61, status="reported",
                fm_extra={
                    "scenario_rows": ["alpha scenario"],
                    "triage": triage_block(verdict="plugin-bug")})
    make_scenarios_csv(tmp_path, [scenario_row(category="routing-issue-61")])
    rc = run_check(tmp_path)
    assert rc == 0
    assert "check: clean" in capsys.readouterr().out


def test_check_feature_size_large_clean(tmp_path, capsys):
    make_shadow(tmp_path, 62, status="reported",
                fm_extra={"triage": triage_block(
                    verdict="feature", feature_size="large")})
    rc = run_check(tmp_path)
    assert rc == 0
    assert "check: clean" in capsys.readouterr().out


# --------------------------------------------------------------------------
# re-sync preservation, upstream-state mapping, STATE.md digest
# --------------------------------------------------------------------------

def fixture_issue(name, number):
    return next(r for r in load_fixture(name) if r["number"] == number)


def test_resync_updates_upstream_preserves_maintainer(tmp_path, monkeypatch):
    issues = load_fixture("gh_issue_list_all.json")
    run_sync(tmp_path, monkeypatch, issues, extra_args=["--no-digest"])
    path = tmp_path / "ISSUE-549.md"
    fm, _body = frontmatter_and_body(path)
    # Maintainer edits: triage the file, fill the handoff sections.
    fm["status"] = "triaged"
    fm["phase"] = "phases/fix-549"
    fm["scenario_rows"] = ["alpha scenario"]
    fm["verification"]["command"] = "./gradlew dashboard"
    maintainer_body = (
        "## Triage Notes\n\nmy root-cause theory\n\n"
        "## Requirements\n- fix it\n\n"
        "## Acceptance Criteria\n- it works\n\n"
        "## Canonical References\n- file.java\n")
    path.write_text("---\n" + yaml.safe_dump(fm, sort_keys=False)
                    + "---\n\n" + maintainer_body)
    # Re-sync with changed upstream data.
    changed = dict(fixture_issue("gh_issue_list_all.json", 549))
    changed["title"] = "Retitled upstream report"
    run_sync(tmp_path, monkeypatch, [changed],
             extra_args=["--no-digest"])
    fm, body = frontmatter_and_body(path)
    # Upstream-owned fields and sections refresh...
    assert fm["title"] == "Retitled upstream report"
    upstream = ii.split_sections(body)
    assert "Retitled upstream report" in upstream["Upstream Report"]
    # ...while every maintainer-owned field and section is preserved.
    assert fm["status"] == "triaged"
    assert fm["phase"] == "phases/fix-549"
    assert fm["scenario_rows"] == ["alpha scenario"]
    assert fm["verification"]["command"] == "./gradlew dashboard"
    events = [h["event"] for h in fm["history"]]
    assert "imported" in events and "re-synced" in events
    for section, marker in (("Triage Notes", "my root-cause theory"),
                            ("Requirements", "- fix it"),
                            ("Acceptance Criteria", "- it works"),
                            ("Canonical References", "- file.java")):
        assert marker in upstream[section]


def test_resync_preserves_triage(tmp_path, monkeypatch):
    # A recorded verdict must survive re-sync verbatim — `triage` sits in
    # the carry-over tuple alongside the other maintainer-owned fields.
    issues = load_fixture("gh_issue_list_all.json")
    run_sync(tmp_path, monkeypatch, issues, extra_args=["--no-digest"])
    path = tmp_path / "ISSUE-549.md"
    fm, _body = frontmatter_and_body(path)
    fm["triage"] = triage_block(
        verdict="plugin-bug", expressible=True,
        unblock_conditions=["ship the fix upstream"],
        evidence={"pin_sha": "abc1234",
                  "report": "build/reports/x/report.json"},
        triaged_at="2026-09-20T00:00:00Z", triaged_by="tester")
    path.write_text("---\n" + yaml.safe_dump(fm, sort_keys=False)
                    + "---\n\n## Triage Notes\n\nnote\n")
    changed = dict(fixture_issue("gh_issue_list_all.json", 549))
    changed["title"] = "Retitled upstream report"
    run_sync(tmp_path, monkeypatch, [changed], extra_args=["--no-digest"])
    fm2, _body = frontmatter_and_body(path)
    assert fm2["triage"] == fm["triage"]


def test_resync_state_reason_suggestions(tmp_path, monkeypatch, capsys):
    # NOT_PLANNED -> suggest wontfix + history marker.
    np_issue = fixture_issue("gh_issue_list_all.json", 99996)
    run_sync(tmp_path, monkeypatch, [np_issue],
             extra_args=["--no-digest"])
    assert "suggest wontfix" in capsys.readouterr().out
    fm, _ = frontmatter_and_body(tmp_path / "ISSUE-99996.md")
    assert "upstream-closed: NOT_PLANNED" in [
        h["event"] for h in fm["history"]]

    # COMPLETED -> suggest closed.
    sub = tmp_path / "b"
    comp = fixture_issue("gh_issue_list_all.json", 511)
    run_sync(sub, monkeypatch, [comp], extra_args=["--no-digest"])
    assert "suggest closed" in capsys.readouterr().out

    # REOPENED on a locally closed file -> status flips to reopened.
    sub2 = tmp_path / "c"
    sub2.mkdir()
    make_shadow(sub2, 99997, status="closed", body_text=PRD_BODY)
    reop = fixture_issue("gh_issue_list_all.json", 99997)
    run_sync(sub2, monkeypatch, [reop], extra_args=["--no-digest"])
    fm, _ = frontmatter_and_body(sub2 / "ISSUE-99997.md")
    assert fm["status"] == "reopened"
    assert "status: closed -> reopened (upstream)" in [
        h["event"] for h in fm["history"]]


def test_resync_upstream_closed_dedup(tmp_path, monkeypatch, capsys):
    # While the issue stays closed upstream, re-syncs must not pile up
    # duplicate upstream-closed history events.
    np_issue = fixture_issue("gh_issue_list_all.json", 99996)
    run_sync(tmp_path, monkeypatch, [np_issue],
             extra_args=["--no-digest"])
    run_sync(tmp_path, monkeypatch, [np_issue],
             extra_args=["--no-digest"])
    fm, _ = frontmatter_and_body(tmp_path / "ISSUE-99996.md")
    events = [h["event"] for h in fm["history"]]
    assert events.count("upstream-closed: NOT_PLANNED") == 1


def test_resync_title_with_triple_dash_preserves_maintainer(tmp_path,
                                                          monkeypatch):
    # yaml.safe_dump emits a scalar containing "---" unquoted; the
    # frontmatter split must be line-anchored or maintainer state is
    # silently destroyed on re-sync.
    issue = dict(fixture_issue("gh_issue_list_all.json", 549))
    issue["title"] = "Bad --- title"
    run_sync(tmp_path, monkeypatch, [issue], extra_args=["--no-digest"])
    path = tmp_path / "ISSUE-549.md"
    fm, _body = frontmatter_and_body(path)
    fm["status"] = "triaged"
    fm["phase"] = "phases/x"
    path.write_text("---\n" + yaml.safe_dump(fm, sort_keys=False)
                    + "---\n\nbody\n")
    run_sync(tmp_path, monkeypatch, [issue], extra_args=["--no-digest"])
    fm, _ = frontmatter_and_body(path)
    assert fm["title"] == "Bad --- title"
    assert fm["status"] == "triaged"
    assert fm["phase"] == "phases/x"


def test_resync_corrupt_frontmatter_preserves_body(tmp_path, monkeypatch):
    # Unparseable frontmatter is not a license to drop maintainer work —
    # load_shadow still recovers the body, so re-sync must carry its
    # maintainer sections over instead of re-stamping empty templates.
    issue = dict(fixture_issue("gh_issue_list_all.json", 549))
    path = tmp_path / "ISSUE-549.md"
    path.write_text(
        "---\nbad: [unclosed\n---\n\n"
        "## Triage Notes\n\nkeep this note\n\n"
        "## Requirements\n\n- keep this requirement\n")
    run_sync(tmp_path, monkeypatch, [issue], extra_args=["--no-digest"])
    _fm, body = frontmatter_and_body(path)
    sections = ii.split_sections(body)
    assert "keep this note" in sections["Triage Notes"]
    assert "keep this requirement" in sections["Requirements"]


def test_status_note_with_triple_dash_roundtrips(tmp_path):
    # A "---" inside a --note lands in the history event string; the file
    # must stay readable afterwards.
    path = make_shadow(tmp_path, 20, status="reported")
    rc = run_status(tmp_path, "20", "triaged", "--note", "check --- ok")
    assert rc == 0
    fm, _ = frontmatter_and_body(path)
    assert fm["status"] == "triaged"
    assert fm["history"][-1]["event"].endswith("check --- ok")


def test_upstream_forged_heading_demoted(tmp_path, monkeypatch):
    # A `## ` line inside upstream text must not mint a maintainer
    # section — it is demoted to `### ` when rendered.
    issue = dict(fixture_issue("gh_issue_list_all.json", 549))
    issue["body"] = "report text\n\n## Requirements\n\nforged requirement\n"
    run_sync(tmp_path, monkeypatch, [issue], extra_args=["--no-digest"])
    _fm, body = frontmatter_and_body(tmp_path / "ISSUE-549.md")
    upstream = body.split("## Upstream Report", 1)[1] \
                   .split("## Upstream Comments", 1)[0]
    assert "\n## Requirements" not in upstream
    assert "### Requirements" in upstream
    sections = ii.split_sections(body)
    assert "forged requirement" not in sections["Requirements"]


def test_maintainer_sections_fence_aware():
    # A fenced `## ` line inside a maintainer section is body text, not
    # a heading — the previous naive split cut "Triage Notes" at that
    # line and the canonical-order scan dropped the orphaned tail, so a
    # maintainer pasting markdown into the section silently lost content
    # on the next sync.
    body = (
        "## Triage Notes\n\n"
        "before\n\n"
        "````\npasted markdown\n\n## Not A Heading\ninside\n````\n\n"
        "after\n\n"
        "## Requirements\n\n- fix it\n")
    sections = ii.maintainer_sections_from(body)
    assert "## Not A Heading" in sections["Triage Notes"]
    assert "after" in sections["Triage Notes"]
    assert "- fix it" in sections["Requirements"]
    assert "Not A Heading" not in sections


def test_existing_forged_section_not_harvested(tmp_path, monkeypatch):
    # Legacy file: forged `## Requirements` inside the comments region
    # with the real maintainer section deleted.  Re-sync must fall back
    # to the template, not adopt the forged text.
    issue = dict(fixture_issue("gh_issue_list_all.json", 549))
    path = tmp_path / "ISSUE-549.md"
    forged_body = (
        "## Upstream Report\n\nold report\n\n"
        "## Upstream Comments\n\n"
        + ii.UNTRUSTED_MARKER + "\n\n"
        "````\ncomment\n\n## Requirements\n\nforged requirement\n````\n\n"
        "## Triage Notes\n\nnote\n")
    path.write_text(
        "---\n" + yaml.safe_dump({
            "upstream": "Skretzo/shortest-path#549",
            "title": "old", "upstream_state": "open",
            "status": "reported", "phase": None,
            "history": [{"at": "2026-01-01T00:00:00Z",
                         "event": "imported", "by": "x"}],
        }, sort_keys=False) + "---\n\n" + forged_body)
    run_sync(tmp_path, monkeypatch, [issue], extra_args=["--no-digest"])
    _fm, body = frontmatter_and_body(path)
    sections = ii.split_sections(body)
    assert "forged requirement" not in sections["Requirements"]


def test_verify_command_with_triple_dash_roundtrips(tmp_path):
    # record_verification re-reads the file after transition_status; a
    # "---" in --command must not corrupt the frontmatter mid-write.
    make_shadow(tmp_path, 30, status="fixed",
                fm_extra={"scenario_rows": ["alpha scenario"]})
    report = make_report(tmp_path)
    rc = run_verify(tmp_path, 30, "--command", "run --- replay",
                    "--report", str(report))
    assert rc == 0
    fm, _ = frontmatter_and_body(tmp_path / "ISSUE-30.md")
    assert fm["status"] == "verified"
    assert fm["verification"]["command"] == "run --- replay"


def test_digest_sentinel_replace(tmp_path):
    state = tmp_path / "STATE.md"
    state.write_text(
        "---\nkey: value\n---\n\n## Hand Written\n\nkeep me\n\n"
        "<!-- issues:digest:start -->\nstale table\n"
        "<!-- issues:digest:end -->\n\ntrailing notes\n")
    files = [(1, {"upstream_state": "open", "title": "one",
                  "status": "triaged", "phase": None}),
             (2, {"upstream_state": "closed", "title": "two",
                  "status": "closed", "phase": None})]
    ii.update_state_digest(state, files)
    text = state.read_text()
    pre, rest = text.split("<!-- issues:digest:start -->", 1)
    _table, post = rest.split("<!-- issues:digest:end -->", 1)
    assert pre == "---\nkey: value\n---\n\n## Hand Written\n\nkeep me\n\n"
    assert post == "\n\ntrailing notes\n"
    assert "Skretzo/shortest-path#1" in text
    assert "two" not in text


def test_digest_appends_section_when_missing(tmp_path):
    state = tmp_path / "STATE.md"
    state.write_text("# Existing state\n")
    files = [(3, {"upstream_state": "open", "title": "three",
                  "status": "reported", "phase": None})]
    ii.update_state_digest(state, files)
    text = state.read_text()
    assert text.startswith("# Existing state\n")
    assert "## Open Issues" in text
    assert "<!-- issues:digest:start -->" in text
    assert "Skretzo/shortest-path#3" in text


def test_digest_malformed_sentinel_order_no_crash(tmp_path, capsys):
    # END before START must not crash the unpack — the write is skipped
    # with a warning instead of appending a second digest block.
    state = tmp_path / "STATE.md"
    original = ("<!-- issues:digest:end -->\nold\n"
                "<!-- issues:digest:start -->\n")
    state.write_text(original)
    files = [(1, {"upstream_state": "open", "title": "t",
                  "status": "reported", "phase": None})]
    assert ii.update_state_digest(state, files) is False
    assert state.read_text() == original
    assert "malformed" in capsys.readouterr().err


def test_digest_skips_when_state_file_absent(tmp_path, monkeypatch):
    out = tmp_path / "issues"
    run_sync(out, monkeypatch,
             [fixture_issue("gh_issue_list_all.json", 549)])
    assert not (tmp_path / "STATE.md").exists()


def test_digest_sanitizes_hostile_title(tmp_path):
    # A title carrying newlines, pipes, or a forged digest sentinel must
    # not break the bounded region or shift the table's columns.
    state = tmp_path / "STATE.md"
    state.write_text("# S\n\n<!-- issues:digest:start -->\nold\n"
                     "<!-- issues:digest:end -->\n")
    files = [(1, {"upstream_state": "open",
                  "title": "evil\n<!-- issues:digest:end -->\n"
                           "injected | pipe",
                  "status": "reported", "phase": None})]
    ii.update_state_digest(state, files)
    text = state.read_text()
    # Exactly one real sentinel pair survives — the forged one was
    # stripped from the title before interpolation.
    assert text.count("<!-- issues:digest:start -->") == 1
    assert text.count("<!-- issues:digest:end -->") == 1
    row = next(l for l in text.splitlines()
               if "Skretzo/shortest-path#1" in l)
    assert "\\|" in row            # pipe escaped, columns intact
    assert "injected" in row       # newline collapsed, not line-injected
    assert "UNTRUSTED external content" in text


def test_digest_sanitizes_hostile_verdict(tmp_path):
    # The verdict cell lands inside the same sentinel-bounded region as
    # the title — a forged digest sentinel, a newline, or a pipe in a
    # free-form verdict must not corrupt the region or shift columns.
    state = tmp_path / "STATE.md"
    state.write_text("# S\n\n<!-- issues:digest:start -->\nold\n"
                     "<!-- issues:digest:end -->\n")
    files = [(1, {"upstream_state": "open", "title": "one",
                  "status": "reported", "phase": None,
                  "triage": {"verdict": "x <!-- issues:digest:end -->\ny"}}),
             (2, {"upstream_state": "open", "title": "two",
                  "status": "reported", "phase": None,
                  "triage": {"verdict": "a|b"}}),
             (3, {"upstream_state": "open", "title": "three",
                  "status": "reported", "phase": None,
                  "triage": {"verdict": ["fixed"]}})]
    ii.update_state_digest(state, files)
    text = state.read_text()
    # Exactly one real sentinel pair survives — the forged one in the
    # verdict was stripped before interpolation.
    assert text.count("<!-- issues:digest:start -->") == 1
    assert text.count("<!-- issues:digest:end -->") == 1
    row1 = next(l for l in text.splitlines() if "#1" in l)
    row2 = next(l for l in text.splitlines() if "#2" in l)
    row3 = next(l for l in text.splitlines() if "#3" in l)
    assert "x  y" in row1            # sentinel stripped, newline collapsed
    assert "a\\|b" in row2           # pipe escaped, columns intact
    assert "'fixed'" in row3         # unhashable verdict renders as data
    # The bounded region stays stable — re-running cannot duplicate the
    # table tail outside the markers.
    ii.update_state_digest(state, files)
    text = state.read_text()
    assert text.count("<!-- issues:digest:end -->") == 1
    assert text.count("Skretzo/shortest-path#1") == 1


def test_list_collapses_newline_verdict(tmp_path, capsys):
    # `list` prints one row per issue — a newline inside a free-form
    # verdict must be collapsed, not written through verbatim.
    make_shadow(tmp_path, 70, status="reported",
                fm_extra={"triage": triage_block(verdict="a\nb|c")})
    rc = ii.main(["list", "--output-dir", str(tmp_path)])
    assert rc == 0
    row = next(l for l in capsys.readouterr().out.splitlines()
               if "ISSUE-70" in l)
    assert "a b\\|c" in row


def test_digest_lists_only_upstream_open(tmp_path):
    state = tmp_path / "STATE.md"
    state.write_text("")
    files = [(2, {"upstream_state": "closed", "title": "closed one",
                  "status": "closed", "phase": None}),
             (1, {"upstream_state": "open", "title": "first",
                  "status": "triaged", "phase": None}),
             (3, {"upstream_state": "open", "title": "third",
                  "status": "reported", "phase": "phases/x"})]
    ii.update_state_digest(state, files)
    text = state.read_text()
    assert "#1" in text and "#3" in text
    assert "#2" not in text and "closed one" not in text
    assert text.index("#1") < text.index("#3")  # sorted by issue number
    assert "phases/x" in text


def test_digest_includes_verdict_column(tmp_path):
    # The digest gains a trailing Verdict column: a verdicted file shows
    # the enum token, a verdictless one the "-" placeholder — including
    # when the triage key is absent or malformed.
    state = tmp_path / "STATE.md"
    state.write_text("# S\n\n<!-- issues:digest:start -->\nold\n"
                     "<!-- issues:digest:end -->\n")
    files = [(1, {"upstream_state": "open", "title": "one",
                  "status": "reported", "phase": None,
                  "triage": {"verdict": "data-gap"}}),
             (2, {"upstream_state": "open", "title": "two",
                  "status": "reported", "phase": None}),
             (3, {"upstream_state": "open", "title": "three",
                  "status": "reported", "phase": None,
                  "triage": "not-a-dict"})]
    ii.update_state_digest(state, files)
    text = state.read_text()
    assert "| Verdict |" in text
    row1 = next(l for l in text.splitlines() if "#1" in l)
    row2 = next(l for l in text.splitlines() if "#2" in l)
    row3 = next(l for l in text.splitlines() if "#3" in l)
    assert row1.rstrip().endswith("| data-gap |")
    assert row2.rstrip().endswith("| - |")
    assert row3.rstrip().endswith("| - |")


def test_sync_writes_digest_to_derived_state_file(tmp_path, monkeypatch):
    out = tmp_path / "issues"
    state = tmp_path / "STATE.md"
    state.write_text("# State\n")
    issues = load_fixture("gh_issue_list_all.json")
    rc = run_sync(out, monkeypatch, issues)
    assert rc == 0
    text = state.read_text()
    assert "## Open Issues" in text
    assert "Skretzo/shortest-path#549" in text
    # Closed-upstream files are written but excluded from the digest.
    assert (out / "ISSUE-511.md").is_file()
    digest = text.split("<!-- issues:digest:start -->")[1]
    assert "#511" not in digest


# --------------------------------------------------------------------------
# verify subcommand — evidence-gated path to `verified`
# --------------------------------------------------------------------------

def make_report(tmp_path, name="alpha scenario", reached=True,
                assertion_passed=True, assertion_message=None,
                expected_reachable=None, filename="report.json"):
    """Write a report.json in the DashboardBundlePublisher shape."""
    report = json.loads((FIXTURES / "report.json").read_text())
    run = report["runs"][0]
    run["name"] = name
    run["reached"] = reached
    run["assertionPassed"] = assertion_passed
    run["assertionMessage"] = assertion_message
    # expectedReachable stays absent unless asked for — old reports and
    # fixtures predate the field, and the absent-field fail-closed case
    # must keep being exercised.
    if expected_reachable is not None:
        run["expectedReachable"] = expected_reachable
    path = tmp_path / filename
    path.write_text(json.dumps(report))
    return path


def run_verify(tmp_path, issue, *argv):
    return ii.main(
        ["verify", "--output-dir", str(tmp_path), str(issue), *argv])


def test_verification_records_report_evidence(tmp_path):
    make_shadow(tmp_path, 1, status="fixed",
                fm_extra={"scenario_rows": ["alpha scenario"]})
    report = make_report(tmp_path)
    rc = run_verify(
        tmp_path, 1,
        "--command", "./gradlew dashboard -PdashboardDataset=x "
        "-PdashboardBundle=issue-1",
        "--report", str(report),
        "--fix-commit", "abc1234",
        "--fix-pr", "https://example.invalid/pr/1",
        "--verifier", "tester")
    assert rc == 0
    fm, _body = frontmatter_and_body(tmp_path / "ISSUE-1.md")
    v = fm["verification"]
    assert v["command"].startswith("./gradlew dashboard")
    assert v["dataset_rows"] == ["alpha scenario"]
    assert v["report"] == str(report)
    assert v["fix_commit"] == "abc1234"
    assert v["fix_pr"] == "https://example.invalid/pr/1"
    assert v["verifier"] == "tester"
    assert v["verified_at"]
    assert fm["status"] == "verified"
    assert fm["history"][-1]["event"] == "status: fixed -> verified"


def test_verification_rejects_unreached_run(tmp_path, capsys):
    path = make_shadow(tmp_path, 2, status="fixed",
                       fm_extra={"scenario_rows": ["alpha scenario"]})
    before = path.read_text()
    report = make_report(tmp_path, reached=False)
    rc = run_verify(tmp_path, 2, "--command", "cmd",
                    "--report", str(report))
    assert rc != 0
    assert "alpha scenario" in capsys.readouterr().err
    assert path.read_text() == before


def test_verification_rejects_assertion_failure(tmp_path, capsys):
    path = make_shadow(tmp_path, 3, status="fixed",
                       fm_extra={"scenario_rows": ["alpha scenario"]})
    before = path.read_text()
    report = make_report(tmp_path, assertion_passed=False,
                         assertion_message="expected 12, got 20")
    rc = run_verify(tmp_path, 3, "--command", "cmd",
                    "--report", str(report))
    assert rc != 0
    assert "alpha scenario" in capsys.readouterr().err
    assert path.read_text() == before


def test_verification_rejects_missing_row_name(tmp_path, capsys):
    path = make_shadow(
        tmp_path, 4, status="fixed",
        fm_extra={"scenario_rows": ["alpha scenario", "beta scenario"]})
    before = path.read_text()
    report = make_report(tmp_path)  # only covers "alpha scenario"
    rc = run_verify(tmp_path, 4, "--command", "cmd",
                    "--report", str(report))
    assert rc != 0
    assert "beta scenario" in capsys.readouterr().err
    assert path.read_text() == before


def test_verification_requires_rows_unless_manual(tmp_path, capsys):
    make_shadow(tmp_path, 5, status="fixed")  # empty scenario_rows
    report = make_report(tmp_path)
    rc = run_verify(tmp_path, 5, "--command", "cmd",
                    "--report", str(report))
    assert rc != 0
    assert capsys.readouterr().err


def test_verification_manual_path(tmp_path):
    # The dashboard cannot express every game state — --manual records the
    # escape path with the evidence ref stored in `report`.
    make_shadow(tmp_path, 6, status="fixed")
    rc = run_verify(tmp_path, 6,
                    "--command", "DashboardTest#testFoo passes",
                    "--manual",
                    "--evidence", "junit: DashboardTest#testFoo")
    assert rc == 0
    fm, _body = frontmatter_and_body(tmp_path / "ISSUE-6.md")
    v = fm["verification"]
    assert v["command"] == "DashboardTest#testFoo passes"
    assert v["report"] == "junit: DashboardTest#testFoo"
    assert fm["status"] == "verified"


def test_verification_manual_requires_evidence(tmp_path, capsys):
    path = make_shadow(tmp_path, 12, status="fixed")
    before = path.read_text()
    rc = run_verify(tmp_path, 12, "--command", "cmd", "--manual")
    assert rc != 0
    assert capsys.readouterr().err
    assert path.read_text() == before


def test_verification_only_from_fixed_states(tmp_path):
    path = make_shadow(tmp_path, 7, status="reported",
                       fm_extra={"scenario_rows": ["alpha scenario"]})
    before = path.read_text()
    report = make_report(tmp_path)
    rc = run_verify(tmp_path, 7, "--command", "cmd",
                    "--report", str(report))
    assert rc != 0
    assert path.read_text() == before


def test_verification_from_in_progress_and_reopened(tmp_path):
    for n, status in ((8, "in_progress"), (9, "reopened")):
        make_shadow(tmp_path, n, status=status,
                    fm_extra={"scenario_rows": ["alpha scenario"]})
        report = make_report(tmp_path, filename=f"report{n}.json")
        rc = run_verify(tmp_path, n, "--command", "cmd",
                        "--report", str(report))
        assert rc == 0, status
        fm, _body = frontmatter_and_body(tmp_path / f"ISSUE-{n}.md")
        assert fm["status"] == "verified"


def test_verification_accepts_absent_assertion(tmp_path):
    # assertionPassed is legitimately absent when no length assertion was
    # configured — only an explicit `false` is a failure.
    make_shadow(tmp_path, 10, status="fixed",
                fm_extra={"scenario_rows": ["alpha scenario"]})
    report_data = json.loads((FIXTURES / "report.json").read_text())
    report_data["runs"][0].pop("assertionPassed")
    report = tmp_path / "report.json"
    report.write_text(json.dumps(report_data))
    rc = run_verify(tmp_path, 10, "--command", "cmd",
                    "--report", str(report))
    assert rc == 0


def test_verification_dataset_rows_override(tmp_path):
    # --dataset-rows overrides (and is written into) scenario_rows.
    make_shadow(tmp_path, 11, status="fixed")
    report = make_report(tmp_path)
    rc = run_verify(tmp_path, 11, "--command", "cmd",
                    "--report", str(report),
                    "--dataset-rows", "alpha scenario")
    assert rc == 0
    fm, _body = frontmatter_and_body(tmp_path / "ISSUE-11.md")
    assert fm["scenario_rows"] == ["alpha scenario"]
    assert fm["verification"]["dataset_rows"] == ["alpha scenario"]


def test_verification_accepts_green_tripwire_run(tmp_path):
    # An expect_reachable=false row is green when the run stayed
    # unreachable with no failed assertion — the defect-tripwire shape
    # real reports emit (reached=false, expectedReachable=false,
    # assertionPassed=true).
    make_shadow(tmp_path, 13, status="fixed",
                fm_extra={"scenario_rows": ["alpha scenario"]})
    report = make_report(tmp_path, reached=False, expected_reachable=False)
    rc = run_verify(tmp_path, 13, "--command", "cmd",
                    "--report", str(report))
    assert rc == 0
    fm, _body = frontmatter_and_body(tmp_path / "ISSUE-13.md")
    assert fm["status"] == "verified"
    assert fm["verification"]["dataset_rows"] == ["alpha scenario"]


def test_verification_rejects_reached_tripwire_run(tmp_path, capsys):
    # A tripwire row that now reaches means the guard broke — a polarity
    # violation, not a green run.
    path = make_shadow(tmp_path, 14, status="fixed",
                       fm_extra={"scenario_rows": ["alpha scenario"]})
    before = path.read_text()
    report = make_report(tmp_path, reached=True, expected_reachable=False)
    rc = run_verify(tmp_path, 14, "--command", "cmd",
                    "--report", str(report))
    assert rc != 0
    err = capsys.readouterr().err
    assert "alpha scenario" in err
    assert "expected unreachable" in err
    assert path.read_text() == before


def test_verification_rejects_tripwire_assertion_failure(tmp_path, capsys):
    # assertionPassed: false fails on either polarity — an
    # expected-unreachable row with a failed assertion still rejects.
    path = make_shadow(tmp_path, 15, status="fixed",
                       fm_extra={"scenario_rows": ["alpha scenario"]})
    before = path.read_text()
    report = make_report(tmp_path, reached=False, expected_reachable=False,
                         assertion_passed=False,
                         assertion_message="tripwire assertion failed")
    rc = run_verify(tmp_path, 15, "--command", "cmd",
                    "--report", str(report))
    assert rc != 0
    assert "tripwire assertion failed" in capsys.readouterr().err
    assert path.read_text() == before


# --------------------------------------------------------------------------
# Verdict enum breadth, upstream-state gating edges, fix-candidate refresh,
# list filtering, digest membership, verify error paths
# --------------------------------------------------------------------------

def test_check_accepts_all_six_verdicts(tmp_path, capsys):
    # Every member of the six-way enum must be able to lint clean when its
    # companions are populated — the ratchet is coverage, not a whitelist
    # of the easy verdicts.
    per_verdict = {
        # Row-backed verdicts without rows take the expressible: false
        # escape, which in turn requires blocked_on.
        "fixed": {"expressible": False, "blocked_on": "manual replay"},
        "data-gap": {"expressible": False, "blocked_on": "f2p-harness"},
        "plugin-bug": {"expressible": False, "blocked_on": "f2p-harness"},
        # Prose-only verdicts need unblock_conditions + pin_sha (defaults).
        "grammar-gap": {},
        "invalid": {},
        "feature": {"feature_size": "small"},
    }
    assert set(per_verdict) == set(ii.VERDICT_ENUM)
    for n, verdict in enumerate(sorted(per_verdict), start=80):
        make_shadow(tmp_path, n, status="reported",
                    fm_extra={"triage": triage_block(
                        verdict=verdict, **per_verdict[verdict])})
    rc = run_check(tmp_path)
    assert rc == 0
    assert "check: clean" in capsys.readouterr().out


def test_check_verdict_is_case_sensitive(tmp_path, capsys):
    # The enum is a lowercase vocabulary — a capitalized verdict is an
    # unknown token and fails coverage rather than silently matching.
    make_shadow(tmp_path, 86, status="reported",
                fm_extra={"triage": triage_block(verdict="Fixed")})
    assert run_check(tmp_path) != 0
    assert "lacks a valid triage.verdict" in capsys.readouterr().out


def test_check_upstream_state_gating_is_case_insensitive(tmp_path, capsys):
    # The verdict gate compares upstream_state case-insensitively: a
    # hand-edited "Open" must still trigger coverage, while a "CLOSED"
    # file stays exempt from it.
    make_shadow(tmp_path, 87, status="reported",
                fm_extra={"triage": None, "upstream_state": "Open"})
    make_shadow(tmp_path, 88, status="reported",
                fm_extra={"triage": None, "upstream_state": "CLOSED"})
    rc = run_check(tmp_path)
    assert rc != 0
    out = capsys.readouterr().out
    assert "ISSUE-87.md" in out and "lacks a valid triage.verdict" in out
    assert "ISSUE-88.md" not in out


def test_check_non_dict_evidence_requires_pin_sha(tmp_path, capsys):
    # A malformed evidence block degrades to the pin_sha error, never a
    # crash — shadow frontmatter is not a trusted schema.
    make_shadow(tmp_path, 89, status="reported",
                fm_extra={"triage": triage_block(evidence="not-a-dict")})
    rc = run_check(tmp_path)
    assert rc != 0
    assert "pin_sha" in capsys.readouterr().out


def test_resync_refreshes_fix_candidates(tmp_path, monkeypatch):
    # fix_candidates is upstream-owned: a re-sync rebuilds it from the
    # latest fetch (new candidates appear, stale ones drop) while
    # maintainer-owned triage carries over untouched.
    issues = load_fixture("gh_issue_list_all.json")
    run_sync(tmp_path, monkeypatch, issues, extra_args=["--no-digest"])
    path = tmp_path / "ISSUE-549.md"
    fm, _body = frontmatter_and_body(path)
    assert any(e["pr"] == 99995 for e in fm["fix_candidates"])
    fm["triage"] = triage_block(verdict="plugin-bug")
    path.write_text("---\n" + yaml.safe_dump(fm, sort_keys=False)
                    + "---\n\n## Triage Notes\n\nnote\n")
    new_candidates = [{
        "pr": 88888, "url": "https://example.invalid/pull/88888",
        "author": "dev", "branch": "fix/549-new", "link": "confirmed",
    }]
    monkeypatch.setattr(ii, "fetch_fix_candidates",
                        lambda *a, **k: {549: new_candidates})
    changed = dict(fixture_issue("gh_issue_list_all.json", 549))
    changed["title"] = "Retitled upstream report"
    run_sync(tmp_path, monkeypatch, [changed], extra_args=["--no-digest"])
    fm2, _body = frontmatter_and_body(path)
    assert fm2["fix_candidates"] == new_candidates
    assert fm2["triage"]["verdict"] == "plugin-bug"


def test_fix_candidate_reference_without_repo_data_not_confirmed(
        tmp_path, monkeypatch):
    # closingIssuesReferences only earns "confirmed" when the reference
    # resolves into the upstream repo itself — entries with missing
    # repository data, a foreign owner, or a foreign repo name are
    # dropped entirely when nothing else links them.
    prs = [{
        "number": 1, "title": "work", "state": "OPEN", "body": "",
        "author": {"login": "dev"}, "headRefName": "work",
        "headRepositoryOwner": {"login": "dev"}, "isDraft": False,
        "closingIssuesReferences": [
            {"number": 77, "repository": None},
            {"number": 78, "repository": {
                "owner": {"login": "Skretzo"}, "name": "other-repo"}},
            {"number": 79, "repository": {
                "owner": {"login": "someone-else"},
                "name": "shortest-path"}},
        ],
        "url": "https://example.invalid/pull/1",
        "createdAt": "2026-01-01T00:00:00Z",
        "updatedAt": "2026-01-01T00:00:00Z",
    }]
    monkeypatch.setattr(ii, "gh_json", lambda args: prs)
    out = ii.fetch_fix_candidates()
    assert 77 not in out and 78 not in out and 79 not in out


def test_resync_closed_with_unmapped_reason_stays_silent(
        tmp_path, monkeypatch, capsys):
    # The closure-signal mapping is deliberately conservative: a closed
    # upstream issue whose stateReason is neither NOT_PLANNED nor
    # COMPLETED produces no suggestion and no history event.
    issue = dict(fixture_issue("gh_issue_list_all.json", 99996))
    issue["stateReason"] = "DUPLICATE"
    rc = run_sync(tmp_path, monkeypatch, [issue],
                  extra_args=["--no-digest"])
    assert rc == 0
    out = capsys.readouterr().out
    assert "suggest" not in out
    fm, _body = frontmatter_and_body(tmp_path / "ISSUE-99996.md")
    assert fm["upstream_state"] == "closed"
    assert fm["upstream_state_reason"] == "DUPLICATE"
    assert fm["status"] == "reported"
    events = [h["event"] for h in fm["history"]]
    assert not any(e.startswith("upstream-closed") for e in events)


def test_list_status_filter(tmp_path, capsys):
    make_shadow(tmp_path, 33, status="reported")
    make_shadow(tmp_path, 34, status="triaged", body_text=PRD_BODY)
    rc = ii.main(["list", "--output-dir", str(tmp_path),
                  "--status", "triaged"])
    assert rc == 0
    lines = [l for l in capsys.readouterr().out.splitlines() if l.strip()]
    assert len(lines) == 1
    assert lines[0].startswith("ISSUE-34")


def test_list_skips_unparseable_frontmatter(tmp_path, capsys):
    # A shadow file whose frontmatter cannot be parsed is omitted from
    # the listing rather than crashing it — `check` is the tool that
    # reports malformed files.
    make_shadow(tmp_path, 35, status="reported")
    (tmp_path / "ISSUE-36.md").write_text(
        "---\nbad: [unclosed\n---\nbody\n")
    rc = ii.main(["list", "--output-dir", str(tmp_path)])
    assert rc == 0
    lines = [l for l in capsys.readouterr().out.splitlines() if l.strip()]
    assert [l.split()[0] for l in lines] == ["ISSUE-35"]


def test_sync_no_digest_leaves_state_file_untouched(tmp_path, monkeypatch):
    state = tmp_path / "STATE.md"
    state.write_text("# keep me\n")
    rc = run_sync(tmp_path / "issues", monkeypatch,
                  [fixture_issue("gh_issue_list_all.json", 549)],
                  extra_args=["--no-digest"])
    assert rc == 0
    assert state.read_text() == "# keep me\n"


def test_sync_state_file_override(tmp_path, monkeypatch):
    # --state-file redirects the digest; the default sibling location is
    # left alone.
    default_state = tmp_path / "STATE.md"
    default_state.write_text("# default\n")
    alt = tmp_path / "ALT.md"
    alt.write_text("# alt\n")
    rc = run_sync(tmp_path / "issues", monkeypatch,
                  [fixture_issue("gh_issue_list_all.json", 549)],
                  extra_args=["--state-file", str(alt)])
    assert rc == 0
    assert "Skretzo/shortest-path#549" in alt.read_text()
    assert default_state.read_text() == "# default\n"


def test_digest_excludes_missing_upstream_state(tmp_path):
    # A file that never recorded upstream_state cannot be known-open, so
    # it stays out of the digest — matching the fail-closed lint that
    # flags the same file rather than silently exempting it.
    state = tmp_path / "STATE.md"
    state.write_text("# S\n\n<!-- issues:digest:start -->\nold\n"
                     "<!-- issues:digest:end -->\n")
    files = [(1, {"title": "no state", "status": "reported",
                  "phase": None}),
             (2, {"upstream_state": "Open", "title": "mixed case",
                  "status": "reported", "phase": None})]
    ii.update_state_digest(state, files)
    text = state.read_text()
    assert "#1" not in text
    assert "#2" in text


def test_verification_rejects_unparseable_report(tmp_path, capsys):
    path = make_shadow(tmp_path, 21, status="fixed",
                       fm_extra={"scenario_rows": ["alpha scenario"]})
    before = path.read_text()
    report = tmp_path / "report.json"
    report.write_text("{ not json")
    rc = run_verify(tmp_path, 21, "--command", "cmd",
                    "--report", str(report))
    assert rc != 0
    assert "cannot parse" in capsys.readouterr().err
    assert path.read_text() == before


def test_verification_missing_file(tmp_path, capsys):
    rc = run_verify(tmp_path, 99, "--command", "cmd",
                    "--report", "report.json")
    assert rc != 0
    assert "file not found" in capsys.readouterr().err


# --------------------------------------------------------------------------
# gh_run seam — non-JSON gh calls (write ops)
# --------------------------------------------------------------------------

def test_gh_run_returns_completed_process(monkeypatch):
    proc = ii.subprocess.CompletedProcess(["gh"], 0, stdout="", stderr="")
    seen = {}

    def fake_run(cmd, **kwargs):
        seen["cmd"] = cmd
        seen.update(kwargs)
        return proc

    monkeypatch.setattr(ii.subprocess, "run", fake_run)
    assert ii.gh_run(["issue", "close", "504"]) is proc
    assert seen["cmd"] == ["gh", "issue", "close", "504"]
    assert seen["timeout"] > 0
    assert seen["check"] is True
    assert seen["text"] is True


def test_gh_run_timeout_exits_cleanly(monkeypatch):
    def fake_run(cmd, **kwargs):
        raise ii.subprocess.TimeoutExpired(cmd, kwargs.get("timeout"))

    monkeypatch.setattr(ii.subprocess, "run", fake_run)
    with pytest.raises(SystemExit) as exc:
        ii.gh_run(["issue", "close", "504"])
    assert "gh issue close" in str(exc.value)


def test_gh_run_called_process_error_exits_with_stderr(monkeypatch):
    def fake_run(cmd, **kwargs):
        raise ii.subprocess.CalledProcessError(
            2, cmd, stderr="boom details")

    monkeypatch.setattr(ii.subprocess, "run", fake_run)
    with pytest.raises(SystemExit) as exc:
        ii.gh_run(["issue", "close", "504"])
    assert "boom details" in str(exc.value)


# --------------------------------------------------------------------------
# plan-close / close — closure-plan emission and execution
# --------------------------------------------------------------------------

def fixed_shadow(tmp_path, number=504,
                 fix_pr="https://github.com/Skretzo/shortest-path/pull/539",
                 scenario_rows=None, status="triaged"):
    """A close-set shadow: open upstream, verdict fixed, fix_pr set."""
    rows = ["alpha scenario"] if scenario_rows is None else scenario_rows
    return make_shadow(
        tmp_path, number, status=status, body_text=PRD_BODY,
        fm_extra={
            "scenario_rows": rows,
            "triage": triage_block(verdict="fixed", unblock_conditions=[]),
            "verification": {
                "command": ("./gradlew dashboard "
                            "-PdashboardDataset=scenarios.csv"),
                "dataset_rows": rows,
                "report": "build/reports/bundles/sweep/report.json",
                "fix_commit": "31bc5e9",
                "fix_pr": fix_pr,
                "verifier": "tester",
                "verified_at": "2026-09-28T00:00:00Z",
            },
        })


def run_plan_close(tmp_path, *argv):
    return ii.main(["plan-close", "--output-dir", str(tmp_path), *argv])


def replay_entry(issue=504, comment=None):
    return {
        "issue": issue,
        "kind": "replay",
        "comment": comment or (
            "Fixed by Skretzo/shortest-path#539 — verified via "
            "scenario 'alpha scenario', now covered in "
            "routing-issues.csv"),
        "fix_pr": "https://github.com/Skretzo/shortest-path/pull/539",
        "fix_commit": "31bc5e9",
        "evidence_rows": ["alpha scenario"],
        "upstream_state_at_plan": "open",
        "reason": "completed",
    }


def make_closure_plan(tmp_path, entries, filename="closure-plan.json",
                      command="cmd", report="rep", pin_sha="fixturepin"):
    plan = {
        "generated_at": "2026-09-28T00:00:00Z",
        "pin_sha": pin_sha,
        "command": command,
        "report": report,
        "entries": entries,
    }
    path = tmp_path / filename
    path.write_text(json.dumps(plan, indent=2))
    return path


def run_close(tmp_path, plan_path, *argv):
    return ii.main(["close", "--output-dir", str(tmp_path),
                    "--plan", str(plan_path), *argv])


def test_plan_close_emits_replay_entry(tmp_path):
    fixed_shadow(tmp_path, 504)
    rc = run_plan_close(tmp_path)
    assert rc == 0
    plan_path = tmp_path / "closure-plan.json"
    assert plan_path.is_file()
    plan = json.loads(plan_path.read_text())
    assert plan["pin_sha"] == "fixturepin"
    for key in ("generated_at", "command", "report", "entries"):
        assert key in plan
    assert len(plan["entries"]) == 1
    entry = plan["entries"][0]
    assert entry["issue"] == 504
    assert entry["kind"] == "replay"
    assert entry["reason"] == "completed"
    assert entry["upstream_state_at_plan"] == "open"
    assert entry["fix_pr"].endswith("/pull/539")
    assert entry["fix_commit"] == "31bc5e9"
    assert entry["evidence_rows"] == ["alpha scenario"]
    assert entry["comment"] == (
        "Fixed by Skretzo/shortest-path#539 — verified via "
        "scenario 'alpha scenario', now covered in "
        "routing-issues.csv")


def test_plan_close_dry_run_writes_nothing(tmp_path, capsys):
    fixed_shadow(tmp_path, 504)
    rc = run_plan_close(tmp_path, "--dry-run")
    assert rc == 0
    assert not (tmp_path / "closure-plan.json").exists()
    out = capsys.readouterr().out
    assert "would close ISSUE-504" in out
    assert "Skretzo/shortest-path#539" in out


def test_plan_close_skips_missing_fix_pr(tmp_path, capsys):
    # verdict fixed but attribution not yet populated — the entry is
    # skipped and the empty plan writes nothing.
    make_shadow(tmp_path, 504, fm_extra={
        "scenario_rows": ["alpha scenario"],
        "triage": triage_block(verdict="fixed", unblock_conditions=[])})
    rc = run_plan_close(tmp_path)
    assert rc != 0
    assert "SKIP ISSUE-504" in capsys.readouterr().out
    assert not (tmp_path / "closure-plan.json").exists()


def test_plan_close_skips_non_fixed_and_closed(tmp_path, capsys):
    make_shadow(tmp_path, 1, fm_extra={
        "triage": triage_block(verdict="data-gap")})
    make_shadow(tmp_path, 2, fm_extra={
        "upstream_state": "closed",
        "triage": triage_block(verdict="fixed", unblock_conditions=[]),
        "verification": {"command": "c",
                         "dataset_rows": ["alpha scenario"],
                         "report": "r", "fix_commit": "x",
                         "fix_pr": "https://example.invalid/pull/2",
                         "verifier": "t", "verified_at": "t"}})
    rc = run_plan_close(tmp_path)
    assert rc != 0
    assert not (tmp_path / "closure-plan.json").exists()


def test_plan_close_explicit_plan_path(tmp_path):
    fixed_shadow(tmp_path, 504)
    plan_path = tmp_path / "nested" / "plan.json"
    plan_path.parent.mkdir()
    rc = run_plan_close(tmp_path, "--plan", str(plan_path))
    assert rc == 0
    assert plan_path.is_file()


def test_close_dry_run_never_calls_gh_run(tmp_path, monkeypatch, capsys):
    make_shadow(tmp_path, 504)
    plan_path = make_closure_plan(tmp_path, [replay_entry(504)])
    monkeypatch.setattr(
        ii, "gh_json", lambda args: {"state": "OPEN", "stateReason": None})
    calls = []
    monkeypatch.setattr(ii, "gh_run",
                        lambda args: calls.append(list(args)))
    rc = run_close(tmp_path, plan_path, "--dry-run")
    assert rc == 0
    out = capsys.readouterr().out
    assert "would close ISSUE-504" in out
    assert "Skretzo/shortest-path#539" in out
    assert calls == []
    # Dry-run must not touch the shadow file's verification block.
    fm, _ = frontmatter_and_body(tmp_path / "ISSUE-504.md")
    assert fm["verification"]["fix_pr"] is None


def test_close_skips_already_closed_upstream(tmp_path, monkeypatch, capsys):
    make_shadow(tmp_path, 504)
    plan_path = make_closure_plan(tmp_path, [replay_entry(504)])
    monkeypatch.setattr(ii, "gh_json",
                        lambda args: load_fixture(
                            "gh_issue_view_closed.json"))
    calls = []
    monkeypatch.setattr(ii, "gh_run",
                        lambda args: calls.append(list(args)))
    rc = run_close(tmp_path, plan_path)
    assert rc == 0
    assert "already closed" in capsys.readouterr().out
    assert calls == []
    fm, _ = frontmatter_and_body(tmp_path / "ISSUE-504.md")
    assert fm["verification"]["fix_pr"] is None


def test_close_executes_and_records(tmp_path, monkeypatch):
    path = make_shadow(tmp_path, 504)
    plan_path = make_closure_plan(tmp_path, [replay_entry(504)])
    upstream = {"open": True}

    def fake_gh_json(args):
        if upstream["open"]:
            upstream["open"] = False
            return {"state": "OPEN", "stateReason": None}
        return dict(load_fixture("gh_issue_view_closed.json"))

    gh_run_calls = []

    def fake_gh_run(args):
        gh_run_calls.append(list(args))
        return ii.subprocess.CompletedProcess(["gh", *args], 0)

    monkeypatch.setattr(ii, "gh_json", fake_gh_json)
    monkeypatch.setattr(ii, "gh_run", fake_gh_run)
    rc = run_close(tmp_path, plan_path)
    assert rc == 0
    entry = replay_entry(504)
    assert gh_run_calls == [[
        "issue", "close", "504", "--repo", "Skretzo/shortest-path",
        "-c", entry["comment"], "-r", "completed"]]
    fm, _ = frontmatter_and_body(path)
    v = fm["verification"]
    assert v["fix_pr"] == entry["fix_pr"]
    assert v["fix_commit"] == "31bc5e9"
    assert v["dataset_rows"] == ["alpha scenario"]
    assert v["report"] == "rep"
    assert v["command"] == "cmd"
    assert v["verified_at"]
    events = [h["event"] for h in fm["history"]]
    assert events.count("upstream-close: executed (gh)") == 1

    # Re-run with upstream reporting OPEN again: the close executes a
    # second time but the identical history event is not duplicated.
    upstream["open"] = True
    rc = run_close(tmp_path, plan_path)
    assert rc == 0
    fm, _ = frontmatter_and_body(path)
    events = [h["event"] for h in fm["history"]]
    assert events.count("upstream-close: executed (gh)") == 1


def test_close_flags_unconfirmed_reason(tmp_path, monkeypatch, capsys):
    # A bare CLOSED with a non-COMPLETED reason means the close took an
    # unexpected path — flag it for maintainer review and write nothing.
    path = make_shadow(tmp_path, 504)
    before = path.read_text()
    plan_path = make_closure_plan(tmp_path, [replay_entry(504)])
    views = iter([
        {"state": "OPEN", "stateReason": None},
        {"state": "CLOSED", "stateReason": "NOT_PLANNED"},
    ])
    monkeypatch.setattr(ii, "gh_json", lambda args: next(views))
    monkeypatch.setattr(ii, "gh_run",
                        lambda args: ii.subprocess.CompletedProcess(
                            ["gh", *args], 0))
    rc = run_close(tmp_path, plan_path)
    assert rc != 0
    err = capsys.readouterr().err
    assert "ISSUE-504" in err
    assert path.read_text() == before
