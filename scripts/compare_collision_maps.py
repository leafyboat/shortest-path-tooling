#!/usr/bin/env python3
"""Compare two collision-map.zip artifacts at the edge-flag level.

For each region present in either zip, count edges that flipped
open->blocked, blocked->open, or are unique to one side (region
added/removed). Flag decoding is shared with ``collision_zip.py``.

Optionally, ``--probe x y plane`` prints the four-edge state at that world
coordinate in both maps.
"""

from __future__ import annotations

import argparse
import sys
from collections import Counter
from pathlib import Path
from typing import Dict, Tuple

sys.path.insert(0, str(Path(__file__).resolve().parent))
from collision_zip import (  # noqa: E402
    CollisionMap,
    FLAG_E,
    FLAG_N,
    FLAG_WALL_E,
    FLAG_WALL_N,
    REGION_SIZE,
)

FLAGS = (FLAG_N, FLAG_E)
WALL_FLAGS = (FLAG_WALL_N, FLAG_WALL_E)


def region_planes(m: CollisionMap, key: Tuple[int, int]) -> int:
    region = m.regions.get(key)
    return region[1] if region else 0


def compare_region(old: CollisionMap, new: CollisionMap,
                   key: Tuple[int, int]) -> Dict[str, int]:
    rx, ry = key
    pmax = max(region_planes(old, key), region_planes(new, key))
    stats = Counter()
    for p in range(pmax):
        for ly in range(REGION_SIZE):
            for lx in range(REGION_SIZE):
                wx, wy = rx * REGION_SIZE + lx, ry * REGION_SIZE + ly
                for flag in FLAGS:
                    o = old.flag(wx, wy, p, flag)
                    n = new.flag(wx, wy, p, flag)
                    if o == n:
                        stats["both_open" if o else "both_blocked"] += 1
                    elif o and not n:
                        stats["opened_to_blocked"] += 1
                    else:
                        stats["blocked_to_opened"] += 1
                for flag in WALL_FLAGS:
                    o = old.flag(wx, wy, p, flag)
                    n = new.flag(wx, wy, p, flag)
                    if o == n:
                        stats["wall_both" if o else "wall_neither"] += 1
                    elif o and not n:
                        stats["wall_removed"] += 1
                    else:
                        stats["wall_added"] += 1
    return stats


def probe(maps: Dict[str, CollisionMap], wx: int, wy: int, p: int) -> None:
    for label, m in maps.items():
        print(f"{label} ({wx},{wy},{p}): N={int(m.n(wx, wy, p))} "
              f"E={int(m.e(wx, wy, p))} S={int(m.s(wx, wy, p))} "
              f"W={int(m.w(wx, wy, p))} "
              f"wallN={int(m.wall_n(wx, wy, p))} "
              f"wallE={int(m.wall_e(wx, wy, p))} "
              f"wallS={int(m.wall_s(wx, wy, p))} "
              f"wallW={int(m.wall_w(wx, wy, p))}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("old")
    ap.add_argument("new")
    ap.add_argument("--probe", nargs=3, type=int, metavar=("X", "Y", "PLANE"),
                    action="append", help="Print four-edge state for a world tile")
    ap.add_argument("--top", type=int, default=10,
                    help="Show top-N regions with most changes")
    args = ap.parse_args()

    old = CollisionMap(Path(args.old))
    new = CollisionMap(Path(args.new))

    totals = Counter()
    per_region = []
    keys = set(old.regions) | set(new.regions)
    for k in keys:
        s = compare_region(old, new, k)
        totals.update(s)
        changes = s.get("opened_to_blocked", 0) + s.get("blocked_to_opened", 0)
        if changes:
            per_region.append((k, changes, s.get("opened_to_blocked", 0),
                               s.get("blocked_to_opened", 0)))

    only_old = sorted(set(old.regions) - set(new.regions))
    only_new = sorted(set(new.regions) - set(old.regions))

    print("=" * 60)
    print(f"Regions in old: {len(old.regions)}  new: {len(new.regions)}")
    if only_old:
        print(f"Removed regions ({len(only_old)}): {only_old[:8]}{'...' if len(only_old)>8 else ''}")
    if only_new:
        print(f"Added regions   ({len(only_new)}): {only_new[:8]}{'...' if len(only_new)>8 else ''}")
    print()
    print("Edge totals:")
    for k in ("both_open", "both_blocked", "opened_to_blocked", "blocked_to_opened"):
        print(f"  {k:>22}: {totals[k]:>12,}")
    print("Boundary-flag totals:")
    for k in ("wall_both", "wall_neither", "wall_removed", "wall_added"):
        print(f"  {k:>22}: {totals[k]:>12,}")
    changed = totals["opened_to_blocked"] + totals["blocked_to_opened"]
    total = sum(totals.values()) or 1
    print(f"  {'changed':>22}: {changed:>12,} ({100.0*changed/total:.4f}%)")
    print(f"  {'total edges scanned':>22}: {total:>12,}")
    print()

    per_region.sort(key=lambda r: -r[1])
    print(f"Top {args.top} most-changed regions (region_x, region_y):")
    for (rx, ry), c, o2b, b2o in per_region[: args.top]:
        bx, by = rx * REGION_SIZE, ry * REGION_SIZE
        print(f"  ({rx:>2},{ry:>2}) world ~({bx},{by})  changed={c:>6}  "
              f"+blocked={o2b:>6}  -blocked={b2o:>6}")

    if args.probe:
        print()
        print("Probes:")
        maps = {"old": old, "new": new}
        for px, py, pp in args.probe:
            probe(maps, px, py, pp)

    return 0


if __name__ == "__main__":
    sys.exit(main())
