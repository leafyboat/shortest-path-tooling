"""Tests for scripts/classify_transport_durations.py.

Uses fixture rows shaped like real transports.tsv lines to pin down the
classifier's precedence rules and row coverage behaviour.
"""

import importlib.util
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
SCRIPT = REPO_ROOT / "scripts" / "classify_transport_durations.py"

spec = importlib.util.spec_from_file_location("classify_transport_durations", SCRIPT)
mod = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = mod
spec.loader.exec_module(mod)

parse_object = mod.parse_object
classify = mod.classify
iter_data_rows = mod.iter_data_rows


def classify_obj(obj_field: str):
    """Classify a raw 'menuOption menuTarget objectID' cell."""
    option, target, object_id = parse_object(obj_field)
    return classify(option, target, object_id)


class TestParseObject:
    def test_standard_three_part(self):
        assert parse_object("Open Door 9398") == ("open", "door", "9398")

    def test_multiword_target(self):
        assert parse_object("Enter Cave entrance 1234") == (
            "enter",
            "cave entrance",
            "1234",
        )

    def test_missing_object_id(self):
        assert parse_object("Open Odd-looking wall") == (
            "open",
            "odd-looking wall",
            "",
        )

    def test_blank(self):
        assert parse_object("") == ("", "", "")
        assert parse_object("   ") == ("", "", "")

    def test_case_normalised(self):
        assert parse_object("Climb-UP Ladder 5") == ("climb-up", "ladder", "5")


class TestDoor:
    def test_plain_door(self):
        assert classify_obj("Open Door 9398")[0] == "door"

    def test_large_door(self):
        assert classify_obj("Open Large door 1234")[0] == "door"

    def test_named_doors(self):
        for target in (
            "Magic guild door",
            "Prison door",
            "Tree Door",
            "Guild door",
            "Temple Door",
            "Door of Dinh",
        ):
            assert classify_obj(f"Open {target} 100")[0] == "door", target

    def test_doorway(self):
        assert classify_obj("Walk-through Doorway 50")[0] == "door"

    def test_trapdoor_is_a_door(self):
        assert classify_obj("Open Trapdoor 60")[0] == "door"
        assert classify_obj("Climb-down Trapdoor 60")[0] == "door"

    def test_secret_panels(self):
        assert classify_obj("Push Wall 1597")[0] == "door"
        assert classify_obj("Push Odd looking wall 1736")[0] == "door"
        assert classify_obj("Open Odd-looking wall")[0] == "door"
        assert classify_obj("Search Bookcase 156")[0] == "door"
        assert classify_obj("Open Strange wall 60")[0] == "door"

    def test_wall_climb_is_not_a_door(self):
        assert classify_obj("Climb-up Wall 60")[0] == "other"
        assert classify_obj("Crawl-under Wall 60")[0] == "other"


class TestGate:
    def test_plain_gate(self):
        assert classify_obj("Open Gate 9470")[0] == "gate"

    def test_gate_variants(self):
        for target in ("Ice gate", "Huge Gate", "Colony gate", "City Gate"):
            assert classify_obj(f"Open {target} 100")[0] == "gate", target

    def test_portcullis(self):
        assert classify_obj("Open Portcullis 100")[0] == "gate"

    def test_shantay_pass(self):
        # Toll checkpoint boundary behaves like a gate, not a cave entrance.
        assert classify_obj("Go-through Shantay pass 4031")[0] == "gate"


class TestLadder:
    def test_plain_ladder(self):
        assert classify_obj("Climb-up Ladder 300")[0] == "ladder"
        assert classify_obj("Climb-down Ladder 300")[0] == "ladder"

    def test_ladder_variants(self):
        for target in (
            "Ship's ladder",
            "Bamboo Ladder",
            "Stone Ladder",
            "Vine ladder",
            "Tower ladder",
            "Troll ladder",
            "Up Ladder",
            "Down Ladder",
        ):
            assert classify_obj(f"Climb-up {target} 100")[0] == "ladder", target

    def test_rope_climbs_count_as_ladders(self):
        for obj in (
            "Climb-up Rope 100",
            "Climb Climbing rope 100",
            "Climb-up Vine 100",
            "Climb-down Dripping vine 100",
            "Climb Trellis 100",
            "Climb-up Bone Chain 100",
            "Climb Rocky handholds 100",
            "Climb Handholds 100",
        ):
            assert classify_obj(obj)[0] == "ladder", obj

    def test_climbed_rocks_are_not_ladders(self):
        assert classify_obj("Climb Climbing rocks 100")[0] == "other"
        assert classify_obj("Climb-up Rock 100")[0] == "other"

    def test_ship_ladder_beats_any_ride_hint(self):
        # 'Ship' smells like a vehicle but the object is a ladder.
        assert classify_obj("Climb-up Ship's ladder 100")[0] == "ladder"


class TestStairs:
    def test_staircase(self):
        assert classify_obj("Climb-up Staircase 34816")[0] == "stairs"
        assert classify_obj("Climb-down Staircase 34816")[0] == "stairs"

    def test_stairs_and_steps(self):
        for obj in (
            "Climb Stairs 100",
            "Climb-up Stairs up 100",
            "Climb-down Stairs down 100",
            "Climb Steps 100",
            "Climb-up Stone Staircase 100",
            "Climb Spooky stairs 100",
            "Top-floor Staircase 4568",
            "Bottom-floor Staircase 4570",
        ):
            assert classify_obj(obj)[0] == "stairs", obj

    def test_stepping_stone_is_not_stairs(self):
        assert classify_obj("Cross Stepping stone 100")[0] == "other"


class TestCaveEntrance:
    def test_cave_targets(self):
        for obj in (
            "Enter Cave entrance 100",
            "Enter Cave 100",
            "Exit Cave Exit 100",
            "Enter Cavern 100",
            "Enter Ice Cavern 100",
            "Enter Tunnel 100",
            "Climb-down Tunnel entrance 100",
            "Enter Passageway 100",
            "Leave Old passageway 31892",
            "Enter Grotto 100",
            "Enter Hole 100",
            "Climb-down Dark hole 100",
            "Use Crevice 100",
            "Climb-down Crevice 100",
            "Enter Dungeon entrance 100",
            "Climb-down Secret entrance 11046",
            "Enter Pyramid entrance 100",
            "Enter Colosseum entrance 100",
            "Enter Crypt Entrance 100",
            "Exit Opening 100",
            "Climb-down Manhole 100",
            "Enter Law rift 100",
            "Jump-into Whirlpool 25274",
            "Climb-down Well 100",
            "Climb-down Smokey well 100",
            "Enter Crevasse 100",
            "Push Memorial 5167",
        ):
            assert classify_obj(obj)[0] == "cave-entrance", obj

    def test_enter_option_fallback(self):
        assert classify_obj("Enter Centre of crop circle 24991")[0] == "cave-entrance"
        assert classify_obj("Enter Blocked tunnel 100")[0] == "cave-entrance"
        assert classify_obj("Enter Rock 100")[0] == "cave-entrance"

    def test_exit_option_fallback(self):
        assert classify_obj("Leave exit 20878")[0] == "cave-entrance"
        assert classify_obj("Pass-through Entryway")[0] == "cave-entrance"

    def test_obstacles_not_entrances(self):
        assert classify_obj("Crawl-under Wall 100")[0] == "other"
        assert classify_obj("Squeeze-through Loose Railing 100")[0] == "other"
        assert classify_obj("Climb-over Mud pile 100")[0] == "other"

    def test_barrier_pass_throughs_are_other(self):
        # Walkable boundary barriers are single-obstacle crossings like the
        # Pass/Quick-pass barrier rows, not area entrances.
        assert classify_obj("Pass-through Barrier 39653")[0] == "other"
        assert classify_obj("Pass-through Magic barrier 11005")[0] == "other"
        assert classify_obj("Pass-through Holy barrier 3443")[0] == "other"
        # But a named entryway still counts as an entrance.
        assert classify_obj("Pass-through Entryway")[0] == "cave-entrance"


class TestRide:
    def test_board_and_travel_options(self):
        for obj in (
            "Board Travel cart 2230",
            "Board Log raft 1987",
            "Ride Aged log 25216",
            "Travel Mountain Guide 7600",
            "Follow Mountain Guide 14529",
            "Follow Elkoy 4968",
            "Travel Primio 12888",
            "Travel Daero 1445",
            "Travel Waydar 1446",
            "Transport Brother Tranquility 550",
            "Talk-to Dondakan the Dwarf 4891",
        ):
            assert classify_obj(obj)[0] == "ride", obj

    def test_guided_tunnels(self):
        # Option slot holds the destination name; target is the guide NPC.
        for obj in (
            "Mines Kazgar 7301",
            "Watermill Kazgar 7301",
            "Cellar Mistag 7299",
            "Mines Dartog 7301",
            "Cellar Dartog 997",
        ):
            assert classify_obj(obj)[0] == "ride", obj

    def test_mechanical_lifts(self):
        for obj in (
            "Go-down Lift 4940",
            "Go-up Lift 4942",
            "Use-Lift Lift Platform 15242",
            "Use-Lift Platform 15239",
            "Turn Iron Winch 23104",
        ):
            assert classify_obj(obj)[0] == "ride", obj

    def test_scripted_water_traversal(self):
        for obj in (
            "Swim River 10283",
            "Swim to Rock 1996",
            "Get in Barrel 2022",
            "Slide Slope 5015",
        ):
            assert classify_obj(obj)[0] == "ride", obj

    def test_teleports_are_not_rides(self):
        assert classify_obj("Teleport Mage of Zamorak 2581")[0] == "other"
        assert classify_obj("Operate Teleportation Device 4869")[0] == "other"
        assert classify_obj("Use Fairy ring 12094")[0] == "other"

    def test_climb_over_cart_is_not_a_ride(self):
        assert classify_obj("Climb-over Mine cart 4918")[0] == "other"
        assert classify_obj("Climb-over over Broken cart 100")[0] == "other"


class TestOther:
    def test_clear_obstacles(self):
        for obj in (
            "Cross Wilderness Ditch 100",
            "Cross Bridge 100",
            "Climb-over Stile 100",
            "Chop-down Jungle Bush 100",
            "Chop-down Jungle tree 100",
            "Jump-to Pillar 100",
            "Jump-to Ledge 100",
            "Jump-to Floorboards 100",
            "Jump Gap 100",
            "Pass Barrier 100",
            "Quick-pass Barrier 100",
            "Pay-toll(2-Ecto) Energy Barrier 100",
            "Cross Gangplank 2085",
            "Slash Web 733",
            "Swing-on Ropeswing 23568",
            "Walk-across Rope bridge 100",
            "Climb-over Crushed barricade 100",
            "Jump-across Basalt rock 100",
            "Pray-at Altar 412",
            "Investigate Statue 27785",
            "Operate Appendage 27027",
            "Walk-across Washing line 100",
        ):
            assert classify_obj(obj)[0] == "other", obj

    def test_blank_object(self):
        assert classify_obj("")[0] == "other"


class TestRuleNames:
    def test_rule_names_documented(self):
        # Every classification carries a stable rule tag for auditability.
        valid = {
            "ladder:target",
            "ladder:climb-rope",
            "stairs:target",
            "door:target",
            "door:secret-panel",
            "gate:target",
            "ride:option",
            "ride:target",
            "ride:lift-option",
            "cave:target",
            "cave:option",
            "other",
        }
        samples = [
            "Open Door 1",
            "Open Gate 1",
            "Climb-up Ladder 1",
            "Climb-up Staircase 1",
            "Enter Cave 1",
            "Board Travel cart 1",
            "Cross Bridge 1",
            "",
        ]
        for obj in samples:
            cls, rule = classify_obj(obj)
            assert rule in valid, (obj, rule)
            assert cls in {
                "door",
                "gate",
                "ladder",
                "stairs",
                "cave-entrance",
                "ride",
                "other",
            }


class TestIterDataRows:
    def test_skips_comments_and_blanks(self, tmp_path):
        src = tmp_path / "t.tsv"
        src.write_text(
            "# header\n"
            "\n"
            "1 2 0\t3 4 0\tOpen Door 1\t\t\t\t\t\t1\t\n"
            "# another comment\n"
            "5 6 0\t7 8 0\t\t\t\t\t\t\t\t\n"
        )
        rows = list(iter_data_rows(src))
        assert len(rows) == 2
        assert rows[0][0] == 3  # 1-based source line number
        assert rows[1][0] == 5

    def test_fields_preserved(self, tmp_path):
        src = tmp_path / "t.tsv"
        line = "1 2 0\t3 4 0\tOpen Door 1\tA\tB\tC\tD\tE\t1\tF\tG\n"
        src.write_text(line)
        rows = list(iter_data_rows(src))
        lineno, fields = rows[0]
        assert fields[0] == "1 2 0"
        assert fields[2] == "Open Door 1"
        assert fields[8] == "1"
        assert len(fields) == 11
