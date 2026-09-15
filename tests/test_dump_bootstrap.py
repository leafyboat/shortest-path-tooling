"""Contract tests for the cache-dump bootstrap seam in
``src/test/java/shortestpath/dump/``.

``CacheUtils`` owns the single ``Store``/``XteaKeyManager``/``RegionLoader``
bootstrap for the whole dumper fleet via ``openStore``, ``loadXteaKeys`` and
``loadRegions``.  These tests pin that consolidation so it cannot silently
re-drift:

* no dumper class may self-construct ``new Store(new File(``,
  ``new XteaKeyManager(`` or ``new RegionLoader(`` — only
  ``CacheUtils.java`` may contain those constructor expressions;
* the three helpers must keep existing under their contract names —
  a rename or removal fails here, not at a probe run weeks later;
* every ``*Test.java`` in the directory keeps its
  ``Assume.assumeTrue`` self-gate, the dumper-as-Test inertness
  contract that keeps a plain ``./gradlew test`` from reaching for a
  cache that may not exist.

The checks are deliberately substring-based — the constructor
expressions and gate call are unique strings, so no Java parsing is
required.
"""

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DUMP_DIR = ROOT / "src" / "test" / "java" / "shortestpath" / "dump"
CACHE_UTILS = DUMP_DIR / "CacheUtils.java"

# Constructor expressions that constitute a hand-rolled cache bootstrap.
# CacheUtils is the single permitted construction site.
BOOTSTRAP_CONSTRUCTORS = (
    "new Store(new File(",
    "new XteaKeyManager(",
    "new RegionLoader(",
)

HELPER_NAMES = ("openStore", "loadXteaKeys", "loadRegions")


def _dump_sources():
    files = sorted(DUMP_DIR.glob("*.java"))
    assert files, f"no dump sources found under {DUMP_DIR}"
    return files


def test_no_direct_bootstrap_outside_cacheutils():
    """Every dumper routes its cache bootstrap through CacheUtils.
    A copy-pasted ``new Store(new File(`` / ``new XteaKeyManager(`` /
    ``new RegionLoader(`` outside CacheUtils.java reintroduces the
    per-class bootstrap duplication the helpers replaced."""
    offenders = []
    for path in _dump_sources():
        if path.name == "CacheUtils.java":
            continue
        text = path.read_text()
        for needle in BOOTSTRAP_CONSTRUCTORS:
            if needle in text:
                offenders.append(f"{path.name}: contains {needle!r}")
    assert not offenders, (
        "direct cache bootstrap found outside CacheUtils.java:\n"
        + "\n".join(offenders))


def test_helpers_exist():
    """The three bootstrap helpers are the contract every dumper
    calls — they must remain declared on CacheUtils under these exact
    names."""
    text = CACHE_UTILS.read_text()
    for name in HELPER_NAMES:
        assert re.search(r"public static [\w<>\[\]]+ " + name + r"\(", text), (
            f"CacheUtils.{name}() helper declaration missing")


def test_assume_gates_present():
    """Every dumper ``*Test`` self-gates on ``Assume.assumeTrue`` —
    without the gate the class executes under plain ``./gradlew test``
    and tries to open a cache/keys.json that may not exist."""
    missing = [
        path.name
        for path in sorted(DUMP_DIR.glob("*Test.java"))
        if "Assume.assumeTrue" not in path.read_text()
    ]
    assert not missing, (
        "dumper test classes missing their Assume.assumeTrue self-gate: "
        + ", ".join(missing))
