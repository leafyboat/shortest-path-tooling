"""Contract tests for ``gradle/cache-dumpers.gradle``.

The cache-dumper fleet registers from a spec table plus the
``registerCacheDumper`` factory closure.  These tests freeze the two
ends of the contract:

* the spec table's ``cachePrefix``/``xteaPrefix``/``namesProp`` fields
  must regenerate exactly the ``-P`` property names that
  ``maintenance.py`` ``PROBE_TASKS``/``NAMES_FILE_TASKS`` construct
  (``-P<prefix>CacheDir``, ``-P<prefix>XteaPath``, ``-P<namesProp>``);
* the emitted ``-D`` sysprop keys must stay identical to what the Java
  ``requiredProperty``/``Boolean.getBoolean``/``System.getProperty``
  call sites in ``src/test/java/shortestpath/dump/`` read;
* fleet-wide invariants (``outputs.upToDateWhen { false }``, JUnit,
  heap args, existence-checking guards, no failure-swallowing flag)
  are emitted once by the factory and asserted here so a drift fails
  this suite instead of an operator's probe run weeks later.

The gradle file is a build script, not Python — parsing is regex over
the spec-table literals (a bracket-depth scan for entries plus
``key: 'value'`` field extraction).  ``maintenance.py`` is loaded via
importlib (``scripts/`` has no ``__init__.py``) so the probe tuples are
read from source, never mirrored.
"""

import importlib.util
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GRADLE_PATH = ROOT / "gradle" / "cache-dumpers.gradle"
SCRIPT_PATH = ROOT / "scripts" / "maintenance.py"

spec = importlib.util.spec_from_file_location("maintenance", SCRIPT_PATH)
mm = importlib.util.module_from_spec(spec)
sys.modules["maintenance"] = mm
spec.loader.exec_module(mm)

EXPECTED_TASKS = frozenset({
    "bankTileDump",
    "sailingAmenityVarbitDump",
    "leagueRegionDump",
    "f2pRegionDump",
    "leagueIdProbe",
    "briefcaseEnumProbe",
    "leagueTeleportItemDump",
    "leagueAreaStructDump",
    "leagueScriptScan",
    "transportAnchorDrift",
    "briefcaseDestOverlapScan",
    "briefcaseStructHunt",
    "briefcaseParamScriptScan",
    "briefcaseDbRowScan",
    "briefcaseTeleportTables",
})

# The exact -D keys each task emits — what the Java call sites read.
EXPECTED_SYSPROPS = {
    "bankTileDump": {"bankTiles.dump", "bankTiles.cacheDir",
                     "bankTiles.xteaPath", "bankTiles.output"},
    "sailingAmenityVarbitDump": {"sailing.amenity.dump",
                                 "sailing.amenity.cacheDir",
                                 "sailing.amenity.xteaPath"},
    "leagueRegionDump": {"league.regions.dump", "league.regions.cacheDir",
                         "league.regions.xteaPath", "league.regions.output"},
    "f2pRegionDump": {"f2p.regions.dump", "f2p.regions.cacheDir",
                      "f2p.regions.xteaPath", "f2p.regions.output"},
    "leagueIdProbe": {"league.id.probe", "league.id.cacheDir",
                      "league.id.xteaPath"},
    "briefcaseEnumProbe": {"briefcase.enum.probe",
                           "briefcase.enum.cacheDir",
                           "briefcase.enum.xteaPath"},
    "leagueTeleportItemDump": {"league.teleport.item.dump",
                               "league.teleport.item.cacheDir",
                               "league.teleport.item.xteaPath"},
    "leagueAreaStructDump": {"league.area.struct.dump",
                             "league.area.struct.cacheDir",
                             "league.area.struct.xteaPath"},
    "leagueScriptScan": {"league.script.scan", "league.script.cacheDir",
                         "league.script.xteaPath", "league.script.outPath"},
    "transportAnchorDrift": {"transport.drift.scan",
                             "transport.drift.cacheDir",
                             "transport.drift.xteaPath",
                             "transport.drift.tsvDir",
                             "transport.drift.outPath"},
    "briefcaseDestOverlapScan": {"briefcase.dest.scan",
                                 "briefcase.dest.cacheDir",
                                 "briefcase.dest.xteaPath",
                                 "briefcase.dest.namesFile",
                                 "briefcase.dest.outPath"},
    "briefcaseStructHunt": {"briefcase.struct.hunt",
                            "briefcase.struct.cacheDir",
                            "briefcase.struct.namesFile",
                            "briefcase.struct.outPath"},
    "briefcaseParamScriptScan": {"briefcase.param.scan",
                                 "briefcase.param.cacheDir",
                                 "briefcase.param.xteaPath",
                                 "briefcase.param.outPath"},
    "briefcaseDbRowScan": {"briefcase.db.scan", "briefcase.db.cacheDir",
                           "briefcase.db.namesFile", "briefcase.db.outPath"},
    "briefcaseTeleportTables": {"briefcase.tt.scan",
                                "briefcase.tt.cacheDir",
                                "briefcase.tt.outPath"},
}


def _spec_blocks(text):
    """Split ``cacheDumperSpecs = [ ... ]`` into its top-level map
    entries via bracket depth.  Assumes no ``[``/``]`` inside spec
    strings or comments — holds for the current table (quoted keys are
    dotted identifiers, defaults are plain paths)."""
    m = re.search(r"cacheDumperSpecs\s*=\s*\[", text)
    assert m, "cacheDumperSpecs table not found"
    i = m.end()
    depth = 1
    start = None
    blocks = []
    while i < len(text) and depth:
        ch = text[i]
        if ch == "[":
            depth += 1
            if depth == 2:
                start = i
        elif ch == "]":
            depth -= 1
            if depth == 1 and start is not None:
                blocks.append(text[start:i])
                start = None
        i += 1
    assert depth == 0, "unbalanced brackets in cacheDumperSpecs"
    return blocks


def _field(block, name):
    m = re.search(r"\b" + name + r"\s*:\s*'([^']*)'", block)
    return m.group(1) if m else None


def _map_inner(block, name):
    m = re.search(r"\b" + name + r"\s*:\s*\[(.*?)\]", block, re.S)
    return m.group(1) if m else ""


def _specs():
    """Return ``{task_name: {field: value}}`` parsed from the spec
    table.  ``sysProps`` keeps its quoted keys; ``sysPropKeys`` becomes
    a ``{role: sysprop-key}`` dict."""
    text = GRADLE_PATH.read_text()
    specs = {}
    for block in _spec_blocks(text):
        name = _field(block, "name")
        assert name, f"spec entry without a name:\n{block[:200]}"
        sys_props = re.findall(r"'([^']+)'\s*:",
                               _map_inner(block, "sysProps"))
        sys_prop_keys = dict(re.findall(r"(\w+)\s*:\s*'([^']*)'",
                                        _map_inner(block, "sysPropKeys")))
        specs[name] = {
            "cachePrefix": _field(block, "cachePrefix"),
            "xteaPrefix": _field(block, "xteaPrefix"),
            "namesProp": _field(block, "namesProp"),
            "outputProp": _field(block, "outputProp"),
            "outputDefault": _field(block, "outputDefault"),
            "testClass": _field(block, "testClass"),
            "sysProps": sys_props,
            "sysPropKeys": sys_prop_keys,
        }
    return specs


def test_all_15_task_names_in_spec():
    assert frozenset(_specs()) == EXPECTED_TASKS


def test_probe_task_prop_parity():
    """Every (task, cachePrefix, xteaPrefix, namesProp) tuple in
    maintenance.py must resolve against the spec table unchanged —
    the -P names are constructed from these prefixes."""
    specs = _specs()
    tuples = list(mm.PROBE_TASKS) + list(mm.NAMES_FILE_TASKS)
    assert tuples, "maintenance.py probe registries are empty"
    for task, cache_prop, xtea_prop, names_prop in tuples:
        entry = specs.get(task)
        assert entry is not None, (
            f"{task} is in maintenance.py but missing from the spec table")
        assert entry["cachePrefix"] == cache_prop, (
            f"{task}: cachePrefix {entry['cachePrefix']!r} != "
            f"maintenance.py {cache_prop!r}")
        assert entry["xteaPrefix"] == xtea_prop, (
            f"{task}: xteaPrefix {entry['xteaPrefix']!r} != "
            f"maintenance.py {xtea_prop!r}")
        assert entry["namesProp"] == names_prop, (
            f"{task}: namesProp {entry['namesProp']!r} != "
            f"maintenance.py {names_prop!r}")


def test_sysprop_keys_verbatim():
    """Each spec emits exactly the -D keys the Java call sites read —
    a renamed or dropped key silently breaks requiredProperty."""
    specs = _specs()
    for task, expected in EXPECTED_SYSPROPS.items():
        entry = specs.get(task)
        assert entry is not None, f"{task} missing from spec table"
        emitted = set(entry["sysProps"]) | set(entry["sysPropKeys"].values())
        assert emitted == expected, (
            f"{task}: emitted sysprops {sorted(emitted)} != "
            f"expected {sorted(expected)}")


def test_invariants():
    """Fleet-wide invariants live in the one factory body — a leak or
    a drop applies to all 15 tasks at once."""
    text = GRADLE_PATH.read_text()
    assert "outputs.upToDateWhen { false }" in text
    assert "useJUnit()" in text
    assert "jvmArgs = ['-Xms1g', '-Xmx8g']" in text
    assert "ignoreFailures" not in text
    # Exactly one registration path — the factory closure.
    assert len(re.findall(r"\btasks\.register\b", text)) == 1


def test_no_gstring_systemproperty():
    """No systemProperty emission path may carry an unstringified
    ``${`` interpolation — a GStringImpl in the systemProperties input
    map fails Gradle fingerprinting before doFirst can run."""
    text = GRADLE_PATH.read_text()
    for lineno, line in enumerate(text.splitlines(), 1):
        if "systemProperty" not in line or "${" not in line:
            continue
        assert ".toString()" in line, (
            f"cache-dumpers.gradle:{lineno}: systemProperty with "
            f"un-toStringed interpolation: {line.strip()}")
    # Spec-table sysProp values flow into systemProperty verbatim —
    # the same rule applies to their literals.
    for block in _spec_blocks(text):
        inner = _map_inner(block, "sysProps")
        for key, value in re.findall(r"'([^']+)'\s*:\s*([^,\]]+)", inner):
            if "${" in value:
                assert ".toString()" in value, (
                    f"sysProps[{key}] is an un-toStringed GString: "
                    f"{value.strip()}")


def test_guard_checks_existence():
    """The factory doFirst must check path existence (isDirectory for
    the cache dir, isFile for keys/names files) — not mere emptiness —
    and throw a GradleException that names the task and the -P prop."""
    text = GRADLE_PATH.read_text()
    assert "isDirectory()" in text, "cache-dir existence check missing"
    assert "isFile()" in text, "xtea/names-file existence check missing"
    assert not re.search(r"\.get\(\)\.isEmpty\(\)", text), (
        "guard regressed to an emptiness-only check")
    assert "GradleException" in text
