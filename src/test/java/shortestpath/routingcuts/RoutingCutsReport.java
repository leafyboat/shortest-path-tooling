package shortestpath.routingcuts;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import shortestpath.Destination;
import shortestpath.ShortestPathPlugin;
import shortestpath.pathfinder.CollisionMap;
import shortestpath.pathfinder.SplitFlagMap;
import shortestpath.pathfinder.exact.RoutingCuts;
import shortestpath.pathfinder.exact.RoutingStaticBuilder;
import shortestpath.transport.Transport;
import shortestpath.transport.TransportLoader;

/**
 * Reports how well a cut file fits the plugin's current collision map: how many cuts no longer
 * apply, the resulting component sizes, and how long the exact routing data takes to build.
 * Arguments: {@code [CUTS]}; defaults to the plugin's packaged {@code routing-cuts.bin}.
 */
public final class RoutingCutsReport
{
	public static void main(String[] args) throws Exception
	{
		RoutingCuts cuts = args.length > 0 ? RoutingCuts.read(Files.readAllBytes(Paths.get(args[0])))
			: RoutingCuts.loadFromResources();
		byte[] collisionZip;
		try (InputStream stream = ShortestPathPlugin.class.getResourceAsStream("/collision-map.zip"))
		{
			if (stream == null) throw new IllegalStateException("missing /collision-map.zip");
			collisionZip = stream.readAllBytes();
		}
		CollisionMap collision = new CollisionMap(SplitFlagMap.fromResources());
		Map<Integer, Set<Transport>> transports = TransportLoader.loadAllFromResources();
		Set<Integer> banks = Destination.loadAllFromResources().get("bank");
		RoutingStaticBuilder.Diagnostics diagnostics = RoutingStaticBuilder.build(collision, transports,
			banks == null ? Set.of() : banks, cuts.pairs()).diagnostics;

		long currentFingerprint = RoutingCuts.fingerprint(collisionZip);
		System.out.println("cuts generated for collision map: " + Long.toHexString(cuts.collisionFingerprint())
			+ (cuts.collisionFingerprint() == currentFingerprint ? " (current)" : " (current map is "
			+ Long.toHexString(currentFingerprint) + ")"));
		System.out.println("parameters: " + cuts.parameters());
		System.out.println("cuts: " + diagnostics.inputCutCount + " in file, " + diagnostics.crossingCount
			+ " applied, " + diagnostics.invalidCutCount + " no longer walking edges, "
			+ diagnostics.nonSeparatingCutCount + " no longer separating");
		System.out.println("components: " + diagnostics.routingComponentCount + " routing, largest "
			+ diagnostics.largestRoutingComponentSize + " tiles");
		System.out.println("search tiles: " + diagnostics.searchTileCount + ", sites: " + diagnostics.siteCount);
		System.out.println("build: " + diagnostics.totalNanos / 1_000_000 + " ms");
	}
}
