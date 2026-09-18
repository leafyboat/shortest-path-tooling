#!/usr/bin/env python3
"""Shared reader for the plugin's committed collision-map.zip.

Entries are named ``<regionX>_<regionY>`` and hold a trimmed BitSet
byte stream — the same layout ``SplitFlagMap`` decodes via
``BitSet.valueOf`` in the plugin.  Bit index is
``((plane * 64 * 64) + (ly * 64) + lx) * 2 + flag`` with ``FLAG_N`` /
``FLAG_E`` per ``CollisionMap.java``; ``s()``/``w()`` derive from the
neighbouring tile's ``n()``/``e()``.  Trailing zero bytes are trimmed
by the writer, so blob lengths need not cover a whole plane — readers
treat out-of-range bits as unset, exactly like the Java BitSet.
"""

import re
import zipfile
from pathlib import Path

REGION_SIZE = 64

# Entry names are ``<regionX>_<regionY>`` — anything else is a stray
# archive member, which the collision-zip structural check reports as
# its own finding. Skipping here keeps the reader usable on the
# well-formed entries instead of crashing on int()/unpack errors.
_ENTRY_NAME_RE = re.compile(r"^\d+_\d+$")

# Order matches CollisionMap.java flags.
FLAG_N = 0
FLAG_E = 1


def plane_count(data: bytes) -> int:
    """Planes covered by a region blob.

    ``BitSet.toByteArray()`` trims trailing zero bytes, so a region whose
    highest set bit sits on its top plane serializes to a non-plane-aligned
    length — floor division would drop that partial plane. Ceiling matches
    ``SplitFlagMap``: readers treat out-of-range bits as unset.
    """
    scale = REGION_SIZE * REGION_SIZE * 2
    return (len(data) * 8 + scale - 1) // scale


class CollisionMap:
    """Minimal Python port of FlagMap/SplitFlagMap needed for walkability."""

    def __init__(self, zip_path: Path):
        self.regions: dict[tuple[int, int], tuple[bytes, int]] = {}
        with zipfile.ZipFile(zip_path) as z:
            for name in z.namelist():
                if not _ENTRY_NAME_RE.match(name):
                    continue
                rx, ry = (int(n) for n in name.split("_"))
                data = z.read(name)
                self.regions[(rx, ry)] = (data, plane_count(data))

    def _bit(self, data: bytes, index: int) -> bool:
        byte = data[index >> 3]
        return bool((byte >> (index & 7)) & 1)

    def flag(self, x: int, y: int, z: int, flag: int) -> bool:
        rx, ry = x // REGION_SIZE, y // REGION_SIZE
        region = self.regions.get((rx, ry))
        if region is None:
            return False
        data, plane_count = region
        if z < 0 or z >= plane_count:
            return False
        lx = x - rx * REGION_SIZE
        ly = y - ry * REGION_SIZE
        idx = (z * REGION_SIZE * REGION_SIZE + ly * REGION_SIZE + lx) * 2 + flag
        if idx < 0 or idx >= len(data) * 8:
            return False
        return self._bit(data, idx)

    def n(self, x: int, y: int, z: int) -> bool:
        return self.flag(x, y, z, FLAG_N)

    def e(self, x: int, y: int, z: int) -> bool:
        return self.flag(x, y, z, FLAG_E)

    def s(self, x: int, y: int, z: int) -> bool:
        return self.n(x, y - 1, z)

    def w(self, x: int, y: int, z: int) -> bool:
        return self.e(x - 1, y, z)

    def walkable(self, x: int, y: int, z: int) -> bool:
        """A tile is considered walkable if there is any outgoing/incoming
        movement flag touching it (i.e. the collision map knows about it)."""
        return self.n(x, y, z) or self.e(x, y, z) or self.s(x, y, z) or self.w(x, y, z)

    def is_blocked(self, x: int, y: int, z: int) -> bool:
        return not self.walkable(x, y, z)
