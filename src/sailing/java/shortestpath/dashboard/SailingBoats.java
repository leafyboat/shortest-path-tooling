package shortestpath.dashboard;

import java.util.List;
import shortestpath.pathfinder.BoatHull;

/**
 * The player-owned boats' hulls, as the game's world entity bounds give them (local units, 128 per tile:
 * centre across, centre along with negative toward the bow, width, length). In game the plugin reads these
 * from the boarded boat; here routes name the boat instead.
 */
public final class SailingBoats {
    public static final List<String> NAMES = List.of("raft", "skiff", "sloop");

    private SailingBoats() {
    }

    /**
     * A hull for {@code boat} sitting on the centre of its tile, or {@code null} for no hull ({@code ""} or
     * {@code none}), where only the boat's centre must stay clear.
     */
    public static BoatHull hull(String boat) {
        switch (boat == null ? "" : boat) {
            case "":
            case "none":
                return null;
            case "raft":
                return BoatHull.fromBounds(0, 0, 128, 384, 0, 0);
            case "skiff":
                return BoatHull.fromBounds(0, 0, 256, 640, 0, 0);
            case "sloop":
                return BoatHull.fromBounds(0, -256, 384, 1280, 0, 0);
            default:
                throw new IllegalArgumentException("Unknown boat '" + boat + "', expected one of " + NAMES + " or none");
        }
    }
}
