#!/usr/bin/env python3
"""Render before/after image pairs of dashboard scenario path endpoints.

Pure-Python renderer: stitches the same RuneScape Wiki map tiles the
dashboard displays, then draws each run's path polyline, target marker and
endpoint marker — plus optional structural-boundary edges read from a
collision-map.zip so walls/fences around the destination are visible.

Each output pair shares the same viewport (bounding box of the target plus
both runs' path endpoints, padded) so the wall-side difference is directly
comparable.

Usage:
    build/shotenv/bin/python scripts/dashboard_render_pairs.py \
        --report-dir build/reports/pathfinder-dashboard \
        --before clue-wrongside-before --after clue-wrongside-after \
        --collision-zip shortest-path/src/main/resources/collision-map.zip \
        --out build/dashboard-screenshots/wrongside

Scenarios default to all runs that differ between the two bundles
(termination reason, reached flag, or path endpoint); --scenarios takes a
comma-separated list of run names for an explicit subset.

Requires: Pillow (e.g. `python3 -m venv build/shotenv && build/shotenv/bin/pip install pillow`)
and scripts/collision_zip.py on sys.path when --collision-zip is given.
"""

import argparse
import io
import json
import math
import re
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from PIL import Image, ImageDraw

TILE_BASE = "https://maps.runescape.wiki/osrs/versions/2026-08-12_a/tiles/rendered"
MAP_ID = -1
NATIVE_ZOOM = 3            # max native tile zoom: 2**3 = 8 px per world tile
TILE_PX = 256
WORLD_PER_TILE = TILE_PX >> NATIVE_ZOOM   # 32 world tiles per map tile at z=3

VIEW_RADIUS = 14           # world tiles of padding around the focus box
OUT_SCALE = 3              # integer upscale of the native tile pixels

PATH_COLOR = "#3b82f6"     # matches dashboard path styling
TARGET_COLOR = "#ef4444"
END_COLOR = "#22c55e"
WALL_COLOR = "#f59e0b"
TRANSPORT_COLOR = "#a855f7"


def slug(name: str) -> str:
    return re.sub(r"[^a-zA-Z0-9]+", "-", name).strip("-").lower()[:60]


def target_xy(name: str):
    m = re.search(r"\((\d+) (\d+) (\d+)\)", name)
    return tuple(map(int, m.groups())) if m else None


def end_pos(run):
    path = run.get("path") or []
    e = path[-1] if path else None
    return (e["x"], e["y"], e["plane"]) if e else None


def changed_names(before_runs, after_runs):
    amap = {r["name"]: r for r in after_runs}
    out = []
    for rb in before_runs:
        ra = amap.get(rb["name"])
        if ra and (rb.get("terminationReason") != ra.get("terminationReason")
                   or end_pos(rb) != end_pos(ra)
                   or rb.get("reached") != ra.get("reached")):
            out.append(rb["name"])
    return out


class TileSource:
    """Fetch + cache wiki map tiles; returns a stitched PIL image for a
    world-tile bounding box."""

    def __init__(self, cache_dir: Path, zoom=NATIVE_ZOOM):
        self.cache_dir = cache_dir
        self.zoom = zoom
        self.wpt = TILE_PX >> zoom            # world tiles per map tile
        self.ppwt = TILE_PX / self.wpt        # pixels per world tile

    def _tile_path(self, plane, tx, ty):
        return self.cache_dir / f"{self.zoom}_{plane}_{tx}_{ty}.png"

    def _fetch(self, plane, tx, ty):
        p = self._tile_path(plane, tx, ty)
        if not p.exists():
            url = f"{TILE_BASE}/{MAP_ID}/{self.zoom}/{plane}_{tx}_{ty}.png"
            try:
                req = urllib.request.Request(url, headers={"User-Agent": "dashboard-render-pairs"})
                with urllib.request.urlopen(req, timeout=15) as r:
                    p.parent.mkdir(parents=True, exist_ok=True)
                    p.write_bytes(r.read())
            except Exception:
                return None
        try:
            return Image.open(p).convert("RGB")
        except Exception:
            return None

    def region(self, plane, x0, y0, x1, y1):
        """Image covering world tiles [x0,x1) x [y0,y1); PIL y grows down so
        the image's top row is y1."""
        tx0, ty0 = x0 // self.wpt, y0 // self.wpt
        tx1, ty1 = (x1 - 1) // self.wpt, (y1 - 1) // self.wpt
        w, h = (x1 - x0) * self.ppwt, (y1 - y0) * self.ppwt
        img = Image.new("RGB", (int(w), int(h)), (20, 20, 24))
        jobs = [(plane, tx, ty) for tx in range(tx0, tx1 + 1) for ty in range(ty0, ty1 + 1)]
        with ThreadPoolExecutor(8) as ex:
            tiles = list(ex.map(lambda j: (j, self._fetch(*j)), jobs))
        for (plane_, tx, ty), tile in tiles:
            if tile is None:
                continue
            # world (x,y) -> px: x*ppwt, and y is flipped (north up)
            ox = (tx * self.wpt - x0) * self.ppwt
            oy = (y1 - (ty + 1) * self.wpt) * self.ppwt
            img.paste(tile, (int(ox), int(oy)))
        return img


def world_to_px(x, y, x0, y1, ppwt):
    return (x - x0) * ppwt, (y1 - y) * ppwt


def draw_run(img, run, x0, y1, ppwt, cm, plane):
    d = ImageDraw.Draw(img, "RGBA")

    def px(wx, wy):
        return world_to_px(wx + 0.5, wy + 0.5, x0, y1, ppwt)

    # wall edges (structural boundary flags) for every tile in view
    if cm is not None:
        for wy in range(int(y1 - img.height / ppwt), int(y1) + 1):
            for wx in range(int(x0), int(x0 + img.width / ppwt) + 1):
                edges = []
                if cm.wall_n(wx, wy, plane): edges.append(((wx, wy + 1), (wx + 1, wy + 1)))
                if cm.wall_s(wx, wy, plane): edges.append(((wx, wy), (wx + 1, wy)))
                if cm.wall_e(wx, wy, plane): edges.append(((wx + 1, wy), (wx + 1, wy + 1)))
                if cm.wall_w(wx, wy, plane): edges.append(((wx, wy), (wx, wy + 1)))
                for (ax, ay), (bx, by) in edges:
                    p0 = world_to_px(ax, ay, x0, y1, ppwt)
                    p1 = world_to_px(bx, by, x0, y1, ppwt)
                    d.line([p0, p1], fill=WALL_COLOR, width=max(2, int(ppwt // 3)))

    # transport legs (dashed purple) and walk path (blue)
    for t in run.get("transports") or []:
        o, dst = t.get("origin"), t.get("destination")
        if o and dst and o.get("plane") == plane and dst.get("plane") == plane:
            p0, p1 = px(o["x"], o["y"]), px(dst["x"], dst["y"])
            steps = int(math.dist(p0, p1) / 6) or 1
            pts = [(p0[0] + (p1[0] - p0[0]) * i / steps,
                    p0[1] + (p1[1] - p0[1]) * i / steps) for i in range(steps + 1)]
            for i in range(0, len(pts) - 1, 2):
                d.line([pts[i], pts[i + 1]], fill=TRANSPORT_COLOR, width=max(2, int(ppwt // 4)))

    path = [p for p in (run.get("path") or []) if p.get("plane") == plane]
    if len(path) > 1:
        pts = [px(p["x"], p["y"]) for p in path]
        d.line(pts, fill=PATH_COLOR, width=max(2, int(ppwt // 3)), joint="curve")

    # target marker (red square) and path end (green circle)
    tx, ty, tz = target_xy(run["name"])
    if tz == plane:
        c = px(tx, ty)
        r = ppwt * 0.9
        d.rectangle([c[0] - r / 2, c[1] - r / 2, c[0] + r / 2, c[1] + r / 2],
                    outline=TARGET_COLOR, width=max(2, int(ppwt // 3)))
    e = end_pos(run)
    if e and e[2] == plane:
        c = px(e[0], e[1])
        r = ppwt * 0.45
        d.ellipse([c[0] - r, c[1] - r, c[0] + r, c[1] + r],
                  fill=END_COLOR, outline="#052e16", width=2)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--report-dir", required=True, type=Path)
    ap.add_argument("--before", required=True)
    ap.add_argument("--after", required=True)
    ap.add_argument("--panel", action="append", default=[], metavar="BUNDLE[:LABEL]",
                    help="extra bundles rendered as additional panels, e.g. "
                         "clue-wrongside-after:'legacy after'; label defaults to the bundle name")
    ap.add_argument("--collision-zip", type=Path, default=None,
                    help="new-format collision-map.zip; draws structural boundary edges")
    ap.add_argument("--scenarios", default=None,
                    help="comma-separated run names; default is all runs that differ")
    ap.add_argument("--center", choices=["auto", "target"], default="auto",
                    help="auto: midpoint of target + all endpoints; target: anchor the "
                         "viewport on the target tile (use when a run ends far away)")
    ap.add_argument("--radius", type=int, default=VIEW_RADIUS,
                    help="world tiles of padding around the focus box")
    ap.add_argument("--scale", type=int, default=OUT_SCALE,
                    help="integer upscale of rendered pixels")
    ap.add_argument("--tile-cache", type=Path, default=Path("build/wiki-tiles"))
    ap.add_argument("--out", required=True, type=Path)
    args = ap.parse_args()

    cm = None
    if args.collision_zip:
        sys.path.insert(0, str(Path(__file__).parent))
        from collision_zip import CollisionMap
        cm = CollisionMap(args.collision_zip)

    report_dir = args.report_dir.resolve()

    def load(bundle):
        return json.loads(
            (report_dir / "bundles" / bundle / "report.json").read_text())["runs"]

    # panels: (label, runmap) in output order — before/after plus any extras
    panels = [("before", {r["name"]: r for r in load(args.before)}),
              ("after", {r["name"]: r for r in load(args.after)})]
    for spec in args.panel:
        bundle, _, label = spec.partition(":")
        panels.append((label or bundle,
                       {r["name"]: r for r in load(bundle)}))

    names = ([n.strip() for n in args.scenarios.split(",") if n.strip()]
             if args.scenarios else changed_names(list(panels[0][1].values()),
                                                  list(panels[1][1].values())))
    missing = [n for n in names if any(n not in runmap for _, runmap in panels)]
    if missing:
        sys.exit(f"scenario names not found in every bundle: {missing[:5]}")
    if not names:
        sys.exit("no differing scenarios to render")

    args.out.mkdir(parents=True, exist_ok=True)
    src = TileSource(args.tile_cache)
    head = "| # | scenario |" + "".join(f" {label} end | {label} term |" for label, _ in panels)
    manifest = ["# Dashboard render pairs", "", head,
                "|---|---|" + "---|---|" * len(panels)]

    for i, name in enumerate(names):
        runs = [runmap[name] for _, runmap in panels]
        t = target_xy(name)
        ends = [end_pos(r) for r in runs]
        plane = t[2]

        xs = [t[0]] + [e[0] for e in ends if e and e[2] == plane]
        ys = [t[1]] + [e[1] for e in ends if e and e[2] == plane]
        cx, cy = (t[0], t[1]) if args.center == "target" else \
            ((min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2)
        x0, y0 = int(cx - args.radius), int(cy - args.radius)
        x1, y1 = int(cx + args.radius), int(cy + args.radius)

        ppwt = src.ppwt * args.scale
        img = src.region(plane, x0, y0, x1, y1)
        if args.scale != 1:
            img = img.resize((img.width * args.scale, img.height * args.scale),
                             Image.NEAREST)

        s = slug(name)
        frames = []
        for label, run in zip([p[0] for p in panels], runs):
            frame = img.copy()
            draw_run(frame, run, x0, y1, ppwt, cm, plane)
            frame.save(args.out / f"{i:03d}-{s}-{slug(label)}.png")
            frames.append((label, run, frame))

        # combined side-by-side for PR embedding
        label_h = max(24, int(ppwt))
        combo = Image.new("RGB", (frames[0][2].width * len(frames) + 8 * (len(frames) - 1),
                                  frames[0][2].height + label_h), (24, 24, 28))
        d = ImageDraw.Draw(combo)
        for k, (label, run, frame) in enumerate(frames):
            d.text((8 + k * (frame.width + 8), label_h // 3),
                   f"{label}: {run.get('terminationReason', '-')}",
                   fill="#f87171" if k == 0 else "#4ade80")
            combo.paste(frame, (k * (frame.width + 8), label_h))
        combo.save(args.out / f"{i:03d}-{s}-pair.png")

        row = f"| {i} | {name} |"
        for (label, run, _), e in zip(frames, ends):
            row += f" {e} | {run.get('terminationReason', '-')} |"
        manifest.append(row)
        print(f"[{i + 1}/{len(names)}] {name}")

    (args.out / "index.md").write_text("\n".join(manifest) + "\n")
    print(f"\nwrote {len(panels) * len(names)} images + index.md to {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
