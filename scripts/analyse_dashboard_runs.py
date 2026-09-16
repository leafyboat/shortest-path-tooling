#!/usr/bin/env python3
"""Compare two sets of dashboard report.json files and emit per-route
user-experience metrics.

Plugin use case: a user clicks a destination and waits for the path. They
plan paths infrequently, not in batches. The metric that matters is
per-route latency (median for the common case, p95/max for the worst case),
split by reachable vs unreachable routes (the latter are the dominant
worst-case pain point because the search runs until the cutoff).

Usage:
    analyse_dashboard_runs.py BASELINE_DIR CANDIDATE_DIR [--label-baseline X] [--label-candidate Y]

Each DIR may be either a dashboard output root — whose bundles live under
bundles/<name>/report.json with a bundles/index.json registry — or a bare
directory of <bundle>/report.json files, e.g.:
    /tmp/dashboard-runs/master/routes/report.json
    /tmp/dashboard-runs/master/unit-tests/report.json
    ...

When the output root's bundles/index.json is present, its entry titles are
used as the bundle labels in the comparison output.
"""
from __future__ import annotations

import argparse
import json
import statistics
import sys
from pathlib import Path
from typing import Dict, List, Tuple


def load_runs(report_path: Path) -> List[Dict]:
    if not report_path.is_file():
        return []
    try:
        data = json.loads(report_path.read_text())
    except json.JSONDecodeError:
        # A corrupt report degrades like a missing one — the comparison
        # treats the bundle as empty rather than crashing the whole run.
        return []
    return data.get("runs", []) or []


def find_bundles(root: Path) -> Dict[str, Path]:
    # A dashboard output root nests its bundles under bundles/; prefer that
    # real layout when it exists, else accept a bare <bundle>/report.json dir.
    search = root / "bundles"
    if not search.is_dir():
        search = root
    out = {}
    for p in sorted(search.glob("*/report.json")):
        out[p.parent.name] = p
    return out


def index_titles(root: Path) -> Dict[str, str]:
    """Bundle name -> title map from ``root/bundles/index.json``.

    Returns an empty map when the index is absent or unreadable, so callers
    fall back to directory names.
    """
    index_path = root / "bundles" / "index.json"
    if not index_path.is_file():
        return {}
    try:
        data = json.loads(index_path.read_text())
    except (json.JSONDecodeError, OSError):
        return {}
    out = {}
    for entry in data.get("bundles", []) or []:
        name = entry.get("name")
        if name:
            out[name] = entry.get("title") or name
    return out


def index_by_name(runs: List[Dict]) -> Dict[str, Dict]:
    out = {}
    for r in runs:
        name = r.get("name") or ""
        out[name] = r
    return out


def elapsed_ms(run: Dict) -> float:
    stats = run.get("stats") or {}
    ns = stats.get("elapsedNanos")
    if ns is None:
        return float("nan")
    return ns / 1_000_000.0


def pct(p: float, xs: List[float]) -> float:
    if not xs:
        return float("nan")
    xs = sorted(xs)
    if len(xs) == 1:
        return xs[0]
    k = (len(xs) - 1) * p
    f, c = int(k), min(int(k) + 1, len(xs) - 1)
    if f == c:
        return xs[f]
    return xs[f] + (xs[c] - xs[f]) * (k - f)


def fmt_ms(x: float) -> str:
    if x != x:  # NaN
        return "—"
    if x >= 100:
        return f"{x:.0f}"
    if x >= 10:
        return f"{x:.1f}"
    return f"{x:.2f}"


def fmt_pct(x: float) -> str:
    if x != x:
        return "—"
    sign = "+" if x >= 0 else ""
    return f"{sign}{x:.1f}%"


def per_route_deltas(baseline_runs: List[Dict], candidate_runs: List[Dict]) -> Tuple[List[Tuple[str, float, float, float, bool, bool]], List[str]]:
    """Returns rows of (name, baseline_ms, candidate_ms, pct_change, baseline_reached, candidate_reached) and warnings."""
    b = index_by_name(baseline_runs)
    c = index_by_name(candidate_runs)
    warnings = []
    rows = []
    for name in b:
        if name not in c:
            warnings.append(f"scenario in baseline but not candidate: {name}")
            continue
        br = b[name]
        cr = c[name]
        bms = elapsed_ms(br)
        cms = elapsed_ms(cr)
        b_reached = bool(br.get("reached"))
        c_reached = bool(cr.get("reached"))
        if b_reached != c_reached:
            warnings.append(f"reachability mismatch: {name} baseline={b_reached} candidate={c_reached}")
        # assertionPassed covers expect_reachable, expected_length and
        # minimum_length: a reached route can still be a failing
        # regression, and an expected-unreachable row reached by both
        # sides fails on both.  None means no assertion was configured.
        b_assert = br.get("assertionPassed")
        c_assert = cr.get("assertionPassed")
        if b_assert is False or c_assert is False:
            detail = next((m for m in (cr.get("assertionMessage"),
                                       br.get("assertionMessage")) if m),
                          "")
            warnings.append(
                f"assertion failure: {name} baseline={b_assert} "
                f"candidate={c_assert}"
                + (f" ({detail})" if detail else ""))
        bp = br.get("pathLength") or len(br.get("path") or [])
        cp = cr.get("pathLength") or len(cr.get("path") or [])
        if b_reached and c_reached and bp != cp:
            warnings.append(f"path length mismatch: {name} baseline={bp} candidate={cp}")
        if bms > 0:
            pct_change = (cms - bms) / bms * 100.0
        else:
            pct_change = float("nan")
        rows.append((name, bms, cms, pct_change, b_reached, c_reached))
    for name in c:
        if name not in b:
            warnings.append(f"scenario in candidate but not baseline: {name}")
    return rows, warnings


def summarise(rows: List[Tuple[str, float, float, float, bool, bool]], reachable: bool) -> Dict[str, float]:
    sub = [r for r in rows if r[4] == reachable and r[5] == reachable]
    bms = [r[1] for r in sub]
    cms = [r[2] for r in sub]
    pcts = [r[3] for r in sub if r[3] == r[3]]  # filter NaN
    return {
        "count": len(sub),
        "baseline_median_ms": pct(0.5, bms),
        "candidate_median_ms": pct(0.5, cms),
        "baseline_p95_ms": pct(0.95, bms),
        "candidate_p95_ms": pct(0.95, cms),
        "baseline_max_ms": max(bms) if bms else float("nan"),
        "candidate_max_ms": max(cms) if cms else float("nan"),
        "median_pct": pct(0.5, pcts),
        "mean_pct": statistics.fmean(pcts) if pcts else float("nan"),
        "p95_pct": pct(0.95, pcts),
    }


def emit_dataset(name: str, rows: List[Tuple[str, float, float, float, bool, bool]], lab_b: str, lab_c: str) -> str:
    r_reach = summarise(rows, True)
    r_unreach = summarise(rows, False)
    out = []
    out.append(f"### `{name}`  ({len(rows)} scenarios: {r_reach['count']} reachable, {r_unreach['count']} unreachable)")
    out.append("")
    out.append("**Reachable routes (the common case):**")
    out.append("")
    out.append(f"| Metric | {lab_b} | {lab_c} | Delta |")
    out.append("|---|---:|---:|---:|")
    out.append(f"| Median latency | {fmt_ms(r_reach['baseline_median_ms'])} ms | {fmt_ms(r_reach['candidate_median_ms'])} ms | {fmt_pct(r_reach['median_pct'])} |")
    out.append(f"| p95 latency | {fmt_ms(r_reach['baseline_p95_ms'])} ms | {fmt_ms(r_reach['candidate_p95_ms'])} ms | — |")
    out.append(f"| Max latency | {fmt_ms(r_reach['baseline_max_ms'])} ms | {fmt_ms(r_reach['candidate_max_ms'])} ms | — |")
    out.append(f"| Median per-route % | — | — | {fmt_pct(r_reach['median_pct'])} |")
    out.append("")
    if r_unreach["count"] > 0:
        out.append("**Unreachable routes (worst-case latency dominates):**")
        out.append("")
        out.append(f"| Metric | {lab_b} | {lab_c} | Delta |")
        out.append("|---|---:|---:|---:|")
        out.append(f"| Median latency | {fmt_ms(r_unreach['baseline_median_ms'])} ms | {fmt_ms(r_unreach['candidate_median_ms'])} ms | {fmt_pct(r_unreach['median_pct'])} |")
        out.append(f"| Max latency | {fmt_ms(r_unreach['baseline_max_ms'])} ms | {fmt_ms(r_unreach['candidate_max_ms'])} ms | — |")
        out.append("")
        # Per-route unreachable list
        unreach = [r for r in rows if not r[4] and not r[5]]
        unreach.sort(key=lambda r: -r[1])
        out.append("| Scenario | " + lab_b + " | " + lab_c + " | Delta |")
        out.append("|---|---:|---:|---:|")
        for name_, b_, c_, p_, _, _ in unreach[:10]:
            out.append(f"| {name_} | {fmt_ms(b_)} ms | {fmt_ms(c_)} ms | {fmt_pct(p_)} |")
        out.append("")
    # Worst regressions reachable
    regressions = [r for r in rows if r[4] and r[5] and r[3] == r[3] and r[3] > 5]
    regressions.sort(key=lambda r: -r[3])
    if regressions[:5]:
        out.append("**Top reachable-route regressions (>+5%):**")
        out.append("")
        out.append("| Scenario | " + lab_b + " | " + lab_c + " | Delta |")
        out.append("|---|---:|---:|---:|")
        for name_, b_, c_, p_, _, _ in regressions[:5]:
            out.append(f"| {name_} | {fmt_ms(b_)} ms | {fmt_ms(c_)} ms | {fmt_pct(p_)} |")
        out.append("")
    # Top improvements reachable
    improves = [r for r in rows if r[4] and r[5] and r[3] == r[3] and r[3] < -5]
    improves.sort(key=lambda r: r[3])
    if improves[:5]:
        out.append("**Top reachable-route improvements (<-5%):**")
        out.append("")
        out.append("| Scenario | " + lab_b + " | " + lab_c + " | Delta |")
        out.append("|---|---:|---:|---:|")
        for name_, b_, c_, p_, _, _ in improves[:5]:
            out.append(f"| {name_} | {fmt_ms(b_)} ms | {fmt_ms(c_)} ms | {fmt_pct(p_)} |")
        out.append("")
    return "\n".join(out)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("baseline_dir", type=Path)
    ap.add_argument("candidate_dir", type=Path)
    ap.add_argument("--label-baseline", default="Baseline")
    ap.add_argument("--label-candidate", default="Candidate")
    args = ap.parse_args()

    b_bundles = find_bundles(args.baseline_dir)
    c_bundles = find_bundles(args.candidate_dir)
    b_titles = index_titles(args.baseline_dir)
    c_titles = index_titles(args.candidate_dir)
    common = sorted(set(b_bundles) & set(c_bundles))
    if not common:
        print(f"No shared bundles between {args.baseline_dir} and {args.candidate_dir}", file=sys.stderr)
        return 1

    all_rows = []
    print(f"# Per-route UX comparison: {args.label_baseline} vs {args.label_candidate}\n")
    for name in common:
        # Prefer the candidate's index title so the freshest registry labels
        # the section; directory name is the fallback for index-less roots.
        label = c_titles.get(name) or b_titles.get(name) or name
        b_runs = load_runs(b_bundles[name])
        c_runs = load_runs(c_bundles[name])
        rows, warnings = per_route_deltas(b_runs, c_runs)
        for w in warnings:
            print(f"<!-- WARN [{label}]: {w} -->")
        print(emit_dataset(label, rows, args.label_baseline, args.label_candidate))
        all_rows.extend(rows)

    print("### Combined across all bundles")
    print("")
    r_reach = summarise(all_rows, True)
    r_unreach = summarise(all_rows, False)
    print(f"- Reachable routes: {r_reach['count']} total — median latency {fmt_ms(r_reach['baseline_median_ms'])} ms → {fmt_ms(r_reach['candidate_median_ms'])} ms ({fmt_pct(r_reach['median_pct'])} per route median); p95 {fmt_ms(r_reach['baseline_p95_ms'])} ms → {fmt_ms(r_reach['candidate_p95_ms'])} ms; max {fmt_ms(r_reach['baseline_max_ms'])} ms → {fmt_ms(r_reach['candidate_max_ms'])} ms.")
    print(f"- Unreachable routes: {r_unreach['count']} total — median latency {fmt_ms(r_unreach['baseline_median_ms'])} ms → {fmt_ms(r_unreach['candidate_median_ms'])} ms; max {fmt_ms(r_unreach['baseline_max_ms'])} ms → {fmt_ms(r_unreach['candidate_max_ms'])} ms.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
