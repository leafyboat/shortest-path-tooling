#!/usr/bin/env python3
"""Classify every transports.tsv row into a duration class.

Reads:
    shortest-path/src/main/resources/transports/transports.tsv

Writes:
    .planning/issues/duration-class-map.tsv

Each data row is emitted once, in source order, with its source line number,
all original columns, the parsed interaction triple (menuOption / menuTarget /
objectID), the assigned duration class, and the name of the rule that fired.
The map is the input for the manual duration-probe sampling work: each class
groups transports whose traversal duration is expected to behave the same way.

Classes (fixed vocabulary):

    door           hinged/openable barriers, including trapdoors and secret
                   panels (pushable walls, bookcases)
    gate           gates, portcullises, and toll-pass boundaries
    ladder         ladders plus rope/vine climbs (vertical hand-over-hand
                   transitions behave like ladders for duration purposes)
    stairs         staircases, stairs and steps
    cave-entrance  transitions into or out of enclosed areas via openings:
                   caves, tunnels, passageways, holes, rifts, whirlpools,
                   wells, entrances and exits; also Enter/Exit-family menu
                   options on otherwise unclassified targets
    ride           scripted vehicle/guide traversal: carts, rafts, lifts,
                   winches, glider/balloon pilots, tunnel guides, swims and
                   slides that move the player a long distance in one action
    other          everything else: single-obstacle crossings (ditches,
                   stiles, bridges, gangplanks, rocks, webs, foliage),
                   teleports, and rows with no interaction metadata

Classification is deterministic: rules are evaluated in the order listed
above and the first match wins. menuTarget keywords are matched before
menuOption fallbacks so that e.g. "Go-through Shantay pass" lands in gate
(boundary checkpoint) rather than cave-entrance, and "Climb-up Ship's
ladder" lands in ladder rather than ride.

Stdlib only. Runnable from any working directory.
"""

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
SOURCE_TSV = REPO_ROOT / "shortest-path" / "src" / "main" / "resources" / "transports" / "transports.tsv"
OUTPUT_TSV = REPO_ROOT / ".planning" / "issues" / "duration-class-map.tsv"

CLASSES = ("door", "gate", "ladder", "stairs", "cave-entrance", "ride", "other")

# menuTarget substrings that map straight onto a class. Matched
# case-insensitively against the whole target string.
LADDER_TARGET_RE = re.compile(r"ladder")
STAIRS_TARGET_RE = re.compile(r"stair|\bsteps?\b")
DOOR_TARGET_RE = re.compile(r"door")  # includes trapdoor and doorway
GATE_TARGET_RE = re.compile(r"gate|portcullis|shantay pass")
CAVE_TARGET_RE = re.compile(
    r"cave|cavern|tunnel|grotto|passage|hole|crevice|crevasse|"
    r"entrance|entry|exit|opening|manhole|rift|whirlpool|dungeon|"
    r"crypt|memorial|crack|chasm|\bwell\b"
)

# Targets that are door-like only under specific menu options: hidden panels
# you push, search or open. The same targets under climb/crawl options are
# plain obstacles and fall through to `other`.
SECRET_PANEL_TARGETS = frozenset(
    {
        "wall",
        "odd looking wall",
        "odd-looking wall",
        "strange wall",
        "bookcase",
        "roots",
    }
)
SECRET_PANEL_OPTIONS = frozenset({"open", "push", "search", "unlock"})

# menuOptions starting with `climb` on rope-family targets are vertical
# transitions equivalent to ladders.
ROPE_CLIMB_TARGETS = frozenset(
    {
        "rope",
        "climbing rope",
        "vine",
        "dripping vine",
        "goo covered vine",
        "trellis",
        "roped tree",
        "bone chain",
        "rocky handholds",
        "handholds",
    }
)

# Scripted traversal: the menuOption alone marks the interaction as a ride.
RIDE_OPTIONS = frozenset(
    {
        "board",
        "travel",
        "ride",
        "follow",
        "transport",
        "swim",
        "slide",
        "get",
        "use-lift",
    }
)

# Vehicles, lifts, and guide NPCs that carry the player regardless of the
# option word used (e.g. "Talk-to Dondakan the Dwarf" is a mine-cart ride,
# "Mines Kazgar" is a guided tunnel trip).
RIDE_TARGETS = frozenset(
    {
        "travel cart",
        "log raft",
        "aged log",
        "mountain guide",
        "elkoy",
        "waydar",
        "daero",
        "primio",
        "kazgar",
        "mistag",
        "dartog",
        "brother tranquility",
        "dondakan the dwarf",
        "lift",
        "lift platform",
        "platform",
        "iron winch",
        "in barrel",
        "barrel",
        "canoe",
    }
)

# Option fallbacks for the entrance family: only applied after every
# target-based rule, so "Enter Door" style conflicts cannot occur (door
# targets are claimed first) and toll checkpoints (gate) and vehicles
# (ride) win over generic Enter/Go-through wording.
CAVE_OPTIONS = frozenset(
    {
        "enter",
        "exit",
        "leave",
        "exit-through",
        "go-through",
        "walk-through",
        "crawl-through",
        "crawl-into",
        "climb-through",
        "climb-into",
        "descend",
        "ascend",
        "jump-into",
    }
)


def parse_object(obj_field: str) -> tuple[str, str, str]:
    """Split a 'menuOption menuTarget objectID' cell into lowercase parts.

    The cell packs three logical fields separated by spaces: the menuOption
    (first token), the objectID (last token, only when numeric), and the
    menuTarget (everything in between, which may itself contain spaces or be
    empty). A missing trailing numeric token means the row has no objectID.
    """
    tokens = obj_field.split()
    if tokens and tokens[-1].isdigit():
        tokens = tokens[:-1]
        object_id = obj_field.split()[-1]
    else:
        object_id = ""
    if len(tokens) >= 2:
        return tokens[0].lower(), " ".join(tokens[1:]).lower(), object_id
    if tokens:
        return "", tokens[0].lower(), object_id
    return "", "", object_id


def classify(menu_option: str, menu_target: str, object_id: str = "") -> tuple[str, str]:
    """Return (class, rule) for one parsed interaction triple.

    object_id is accepted for future disambiguation but is not currently
    consulted: the option/target vocabulary covers the whole file.
    """
    target = menu_target
    option = menu_option

    if LADDER_TARGET_RE.search(target):
        return "ladder", "ladder:target"
    if STAIRS_TARGET_RE.search(target):
        return "stairs", "stairs:target"
    if DOOR_TARGET_RE.search(target):
        return "door", "door:target"
    if option in SECRET_PANEL_OPTIONS and target in SECRET_PANEL_TARGETS:
        return "door", "door:secret-panel"
    if option.startswith("climb") and target in ROPE_CLIMB_TARGETS:
        return "ladder", "ladder:climb-rope"
    if GATE_TARGET_RE.search(target):
        return "gate", "gate:target"
    if option in RIDE_OPTIONS or (option in {"go-up", "go-down"} and "lift" in target):
        return "ride", "ride:option"
    if target in RIDE_TARGETS:
        return "ride", "ride:target"
    if CAVE_TARGET_RE.search(target):
        return "cave-entrance", "cave:target"
    if option in CAVE_OPTIONS:
        return "cave-entrance", "cave:option"
    return "other", "other"


def iter_data_rows(path: Path):
    """Yield (line_number, fields) for each data row of a transports TSV.

    Comment lines (starting with '#', including the header) and blank lines
    are skipped. Every other line is split on tabs and yielded verbatim;
    trailing empty fields are preserved.
    """
    with open(path, encoding="utf-8") as handle:
        for lineno, line in enumerate(handle, 1):
            if line.startswith("#") or not line.strip():
                continue
            yield lineno, line.rstrip("\n").split("\t")


def main() -> int:
    counts = {name: 0 for name in CLASSES}
    rows_written = 0

    OUTPUT_TSV.parent.mkdir(parents=True, exist_ok=True)
    with open(OUTPUT_TSV, "w", encoding="utf-8") as out:
        out.write(
            "# line\torigin\tdestination\tobject\tmenu_option\tmenu_target\t"
            "object_id\tskills\titems\tquests\tvarbits\tvarplayers\tduration\t"
            "display\tinfo\tclass\trule\n"
        )
        for lineno, fields in iter_data_rows(SOURCE_TSV):
            fields = fields + [""] * (11 - len(fields))  # tolerate ragged rows
            object_field = fields[2]
            option, target, object_id = parse_object(object_field)
            cls, rule = classify(option, target, object_id)
            counts[cls] += 1
            rows_written += 1
            out.write(
                "\t".join(
                    [
                        str(lineno),
                        fields[0],
                        fields[1],
                        object_field,
                        option,
                        target,
                        object_id,
                        fields[3],
                        fields[4],
                        fields[5],
                        fields[6],
                        fields[7],
                        fields[8],
                        fields[9],
                        fields[10],
                        cls,
                        rule,
                    ]
                )
                + "\n"
            )

    print(f"wrote {rows_written} rows -> {OUTPUT_TSV.relative_to(REPO_ROOT)}")
    for name in CLASSES:
        print(f"  {name:<14} {counts[name]:>5}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
