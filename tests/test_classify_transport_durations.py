"""Tests for ``scripts/classify_transport_durations.py``.

The classifier buckets each ``transports.tsv`` data row into an
interaction class (door/gate/ladder/stairs/cave-entrance/ride/agility/
other) from its ``menuOption menuTarget objectID`` cell so the duration
audit can apply per-class defaults.  Bucket fixtures exercise the rule
table plus the ``other`` fallback; a full-file run proves 100% coverage
— the emitted map must carry one data line per source row.
"""

import importlib.util
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
SCRIPT_PATH = ROOT / "scripts" / "classify_transport_durations.py"
TRANSPORTS_TSV = (
    ROOT / "shortest-path" / "src" / "main" / "resources"
    / "transports" / "transports.tsv"
)

spec = importlib.util.spec_from_file_location(
    "classify_transport_durations", SCRIPT_PATH
)
ctd = importlib.util.module_from_spec(spec)
sys.modules["classify_transport_durations"] = ctd
spec.loader.exec_module(ctd)


def classify_field(field):
    """Classify one raw ``menuOption menuTarget objectID`` cell."""
    option, target, object_id = ctd.split_object_info(field)
    return ctd.classify(option, target, object_id)


@pytest.mark.parametrize(
    "field, expected",
    [
        # doors
        ("Open Door 9398", "door"),
        ("Open Magic guild door 1732", "door"),
        ("Enter Door of Dinh 12345", "door"),
        ("Pick-lock Door 1530", "door"),
        ("Search Bookcase 23316", "door"),
        # gates / barriers
        ("Open Gate 9470", "gate"),
        ("Exit City Gate 36523", "gate"),
        ("Pay-toll(2-Ecto) Energy Barrier 16105", "gate"),
        ("Go-through Ice gate 5044", "gate"),
        ("Climb-over Stile 993", "gate"),
        ("Pass Barrier 3971", "gate"),
        # ladders
        ("Climb-up Ladder 16685", "ladder"),
        ("Climb-down Ship's ladder 246", "ladder"),
        ("Climb-up Bamboo Ladder 4780", "ladder"),
        ("Climb Rope 23548", "ladder"),
        # stairs
        ("Climb-up Staircase 15645", "stairs"),
        ("Walk-down Stairs 34402", "stairs"),
        ("Climb Steps 24089", "stairs"),
        ("Descend Steps 17385", "stairs"),
        # cave entrances / apertures
        ("Enter Cave entrance 2852", "cave-entrance"),
        ("Go-through Passage 36105", "cave-entrance"),
        ("Enter Tunnel 34498", "cave-entrance"),
        ("Climb-down Trapdoor 17234", "cave-entrance"),
        ("Jump-into Whirlpool 25152", "cave-entrance"),
        ("Squeeze-through Crevice 2796", "cave-entrance"),
        # rides (vehicles, NPC escorts, lifts)
        ("Board Travel cart 2265", "ride"),
        ("Cross Gangplank 1822", "ride"),
        ("Board Log raft 30250", "ride"),
        ("Turn Iron Winch 23104", "ride"),
        ("Follow Mountain Guide 7600", "ride"),
        # agility / obstacle crossings
        ("Cross Wilderness Ditch 23271", "agility"),
        ("Jump-to Pillar 27724", "agility"),
        ("Climb Climbing rocks 11948", "agility"),
        ("Chop-down Jungle Bush 2893", "agility"),
        ("Walk-across Rope bridge 28617", "agility"),
        ("Slash Web 32292", "agility"),
        # everything else
        ("Pray-at Altar 26466", "other"),
        ("Teleport Mage of Zamorak 41527", "other"),
        ("Use Fairy ring 29560", "other"),
        ("Cross Bridge 155", "other"),
    ],
)
def test_bucket_rules(field, expected):
    assert classify_field(field) == expected


def test_empty_row_falls_back_to_other():
    assert ctd.classify("", "", "") == "other"


def test_object_info_split():
    assert ctd.split_object_info("Open Door 9398") == ("Open", "Door", "9398")
    assert ctd.split_object_info("Enter Cave entrance 2852") == (
        "Enter", "Cave entrance", "2852",
    )
    assert ctd.split_object_info("") == ("", "", "")


def test_full_file_map_covers_every_row(tmp_path, monkeypatch):
    monkeypatch.setattr(
        ctd, "MAP_TSV", tmp_path / "duration-class-map.tsv"
    )
    assert ctd.main() == 0
    out_lines = (tmp_path / "duration-class-map.tsv").read_text().splitlines()
    assert any(line.startswith("#") for line in out_lines)
    data = [line for line in out_lines if not line.startswith("#")]

    src_lines = TRANSPORTS_TSV.read_text().splitlines()
    # The header line itself is '#'-prefixed, so the non-comment
    # non-blank remainder is exactly the data rows.
    expected = sum(
        1 for line in src_lines
        if line.strip() and not line.startswith("#")
    )
    assert len(data) == expected

    known = {
        "door", "gate", "ladder", "stairs",
        "cave-entrance", "ride", "agility", "other",
    }
    for line in data:
        parts = line.split("\t")
        assert len(parts) == 7
        assert parts[5] in known
