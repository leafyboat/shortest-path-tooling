#!/usr/bin/env python3
"""
Classify transports.tsv rows into interaction classes for the duration
audit.

Reads:
- shortest-path/src/main/resources/transports/transports.tsv
  (plugin data in the shortest-path submodule)

Writes:
- .planning/issues/duration-class-map.tsv — one line per data row:
  line number, origin coords, menuOption, menuTarget, objectID,
  assigned class, current Duration cell.  Leading ``#`` comment lines
  record generation date, source file, and per-class counts so the
  audit sweep can eyeball the bucket sizes before applying defaults.

Classification is a first-match-wins rule table keyed on the
lower-cased menuOption (the verb) with menuTarget (the noun) as
tie-breaker.  Anything unrecognized lands in ``other`` — the map is a
coverage device, not a filter, so no row is ever dropped.
"""

import re
import sys
from collections import Counter
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
TRANSPORTS_TSV = (
    REPO / "shortest-path" / "src" / "main" / "resources"
    / "transports" / "transports.tsv"
)
MAP_TSV = REPO / ".planning" / "issues" / "duration-class-map.tsv"

CLASSES = (
    "door", "gate", "ladder", "stairs",
    "cave-entrance", "ride", "agility", "other",
)

# Object nouns that mean the interaction is a vehicle/NPC ride.
_RIDE_TARGETS = (
    r"\b(travel cart|mine ?carts?|gangplank|log rafts?|rafts?|row ?boats?|"
    r"ferry|ferries|barges?|sled|sledge|boats?|ships?|vessels?|lift|"
    r"lift platform|winch|canoe|balloon)\b"
)
# Obstacle nouns — climbs, jumps and squeezes over/through terrain.
_AGILITY_TARGETS = (
    r"\b(wilderness ditch|climbing rocks|rocks?|rocky (handholds|shore)|"
    r"handholds|ropeswing|rope bridge|stepping stones?|sand piles?|"
    r"basalt|jungle (bush|tree)|fallen trees?|dead trees?|broken carts?|"
    r"wall rubble|crumbling walls?|cracked walls?|boulders?|rubble|"
    r"mud piles?|ice chunks|floorboards|washing lines?|gaps?|"
    r"strange floor|rubber cap mushrooms?|beach|odd.looking|walls?|"
    r"planks?|a wooden log|aged log|logs?|trees?|roots?|shelf|shelves|"
    r"chains?|rockslides?|rock slides?|ledges?|little boulder|"
    r"broken bridge)\b"
)
# Aperture nouns — walking/climbing through a hole in the world.
_CAVE_TARGETS = (
    r"\b(caves?|caverns?|tunnels?|crevices?|crevasse|holes?|entrances?|"
    r"exits?|passages?|passageways?|manholes?|trap ?doors?|whirlpools?|"
    r"wells?|chasms?|grottos?|openings?|cracks?|dungeons?|crypts?|"
    r"pyramids?|strongholds?|barrels?)\b"
)

# (class, menuOption regex, menuTarget regex) — first match wins.
# A None pattern is a wildcard.  Option-only rules cover verbs whose
# target vocabulary is open-ended; target rules cover the big noun
# families; trailing generic-verb rules sweep the remainder so `other`
# stays small.
_RULES = [
    # Rides: decisive travel verbs (Board, Travel, Ride, Paddle, ...),
    # NPC-guide verbs (Follow/Talk-to), and lifts/winch machinery.
    ("ride", r"^(board|ride|paddle|sail|charter|row|transport|travel|"
             r"watermill|mines|cellar|use-lift)\b", None),
    ("ride", r"^(follow|talk-to)\b", None),
    ("ride", r"^(go-up|go-down|use|turn|operate)\b", _RIDE_TARGETS),
    ("ride", r"^(board|cross|use|enter|ride|travel)\b", _RIDE_TARGETS),
    # Ladders — the noun decides; rope/vine climbs behave like ladders.
    ("ladder", None, r"\bladder\b"),
    ("ladder", r"^climb", r"\b(ropes?|vines?|trellis)\b"),
    # Stairs — staircase/steps/slope nouns, then generic vertical verbs.
    ("stairs", None, r"\b(stairs?|staircase|steps|slope|stairwell)\b"),
    ("stairs", r"^(walk-up|walk-down|go-up|go-down|ascend|descend|"
               r"top-floor|bottom-floor)\b", None),
    # Doors — door/bookcase/tapestry nouns; open-y verbs on
    # secret-door-ish nouns (hollow trees, curtains, chests).
    ("door", None, r"\b(doors?|doorway|bookcases?|tapestr(?:y|ies))\b"),
    ("door", r"^(open|close|unlock|pick-lock|search)\b",
     r"\b(trees?|curtains?|chests?)\b"),
    # Gates / barriers — gate, portcullis, barrier, stile, fence nouns
    # plus the toll option.
    ("gate", None, r"\b(gates?|portcullis|barriers?|stiles?|fences?|"
                   r"shantay pass|railings?)\b"),
    ("gate", r"^pay-toll\b", None),
    # Cave entrances / apertures — the noun family is large.
    ("cave-entrance", None, _CAVE_TARGETS),
    # Agility obstacles — unambiguous obstacle verbs first, then
    # obstacle nouns under generic verbs.
    ("agility", r"^(jump|leap|swing|slash|chop|vault|balance|push|move|"
                r"cross-bridge|walk-across|walk-through|climb-over|"
                r"climb over|crawl|crawl-under|crawl-through|crawl-down|"
                r"swim)\b", None),
    ("agility",
     r"^(use|cross|pass|climb|climb-up|climb-down|enter|leave|squeeze-"
     r"through|search)\b", _AGILITY_TARGETS),
    # Fallbacks for generic verbs whose targets stayed unrecognized.
    ("stairs", r"^climb", None),
    ("cave-entrance", r"^(enter|go-through|exit-through|exit|leave|"
                      r"climb-into|pass|pass-through|quick-pass|"
                      r"investigate|get)\b", None),
    ("door", r"^(open|close)\b", None),
]

# Pre-compile once: [(class, option_re|None, target_re|None)].
RULES = [
    (
        cls,
        re.compile(o, re.IGNORECASE) if o else None,
        re.compile(t, re.IGNORECASE) if t else None,
    )
    for cls, o, t in _RULES
]


def split_object_info(field):
    """Split a `menuOption menuTarget objectID` cell.

    The option is the first token, the objectID the last token, and the
    target is everything between (multi-word nouns like
    ``Cave entrance`` or ``Ship's ladder``).  Missing parts come back
    as empty strings — never None — so classifiers can regex freely.
    """
    parts = field.split()
    if not parts:
        return "", "", ""
    if len(parts) == 1:
        return parts[0], "", ""
    if len(parts) == 2:
        return parts[0], parts[1], ""
    return parts[0], " ".join(parts[1:-1]), parts[-1]


def classify(menu_option, menu_target, object_id=""):
    """Return the interaction class for one transport row."""
    opt = menu_option.strip()
    tgt = menu_target.strip()
    for cls, o_re, t_re in RULES:
        if o_re is not None and not o_re.search(opt):
            continue
        if t_re is not None and not t_re.search(tgt):
            continue
        return cls
    return "other"


def iter_body_lines(path):
    """Yield (line_no, fields) for every data row in a transport TSV.

    Mirrors TsvParser's skip rules: ``#``-prefixed comment lines and
    whitespace-only lines are skipped; the first remaining line is the
    header and yields the column-name list instead of a row.
    """
    header = None
    with open(path, encoding="utf-8") as f:
        for lineno, raw in enumerate(f, 1):
            line = raw.rstrip("\n")
            if not line.strip():
                continue
            if header is None:
                # The first non-blank line is the header; it may carry a
                # leading '#' comment marker (TsvParser.parseHeaderLine
                # strips '#'/ '# ' the same way).
                header = [
                    h.lstrip("#").strip() for h in line.split("\t")
                ]
                yield lineno, header, True
                continue
            if line.startswith("#"):
                continue
            yield lineno, line.split("\t"), False


def main():
    rows = []
    header = []
    for lineno, fields, is_header in iter_body_lines(TRANSPORTS_TSV):
        if is_header:
            header = fields
        else:
            rows.append((lineno, fields))
    if not rows:
        sys.exit(f"no data rows in {TRANSPORTS_TSV}")

    try:
        duration_idx = header.index("Duration")
    except ValueError:
        sys.exit(f"{TRANSPORTS_TSV}: no 'Duration' column in header")
    origin_idx = header.index("Origin")
    object_idx = header.index("menuOption menuTarget objectID")

    counts = Counter()
    empty_durations = Counter()
    out = [
        "# duration-class-map — generated by "
        "scripts/classify_transport_durations.py",
        "# source: shortest-path/src/main/resources/transports/"
        "transports.tsv",
    ]
    for lineno, fields in rows:
        cell = fields[object_idx] if len(fields) > object_idx else ""
        option, target, object_id = split_object_info(cell)
        cls = classify(option, target, object_id)
        duration = (
            fields[duration_idx].strip() if len(fields) > duration_idx
            else ""
        )
        counts[cls] += 1
        if not duration or duration == "0":
            empty_durations[cls] += 1
        origin = fields[origin_idx] if len(fields) > origin_idx else ""
        out.append(
            f"{lineno}\t{origin}\t{option}\t{target}\t{object_id}"
            f"\t{cls}\t{duration}"
        )

    summary = " ".join(f"{cls}={counts[cls]}" for cls in CLASSES)
    empty_summary = " ".join(
        f"{cls}={empty_durations[cls]}" for cls in CLASSES
    )
    out.insert(2, f"# rows: {len(rows)} — {summary}")
    out.insert(3, f"# empty-or-zero Duration rows per class — "
                  f"{empty_summary}")
    MAP_TSV.parent.mkdir(parents=True, exist_ok=True)
    MAP_TSV.write_text("\n".join(out) + "\n", encoding="utf-8")

    print(f"wrote {MAP_TSV} ({len(rows)} rows)")
    print(f"class counts: {summary}")
    print(f"empty/0 Duration per class: {empty_summary}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
