package shortestpath.routingcuts;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import shortestpath.ShortestPathPlugin;
import shortestpath.pathfinder.CollisionMap;
import shortestpath.pathfinder.SplitFlagMap;
import shortestpath.pathfinder.exact.RoutingCuts;
import shortestpath.pathfinder.exact.RoutingStaticBuilder;
import shortestpath.transport.Transport;
import shortestpath.transport.TransportLoader;

/**
 * Generates {@code routing-cuts.bin} for the exact backend by recursively partitioning large,
 * structurally reachable walking components with KaHIP's node separator.
 *
 * <p>Every natural component larger than the maximum component size and reachable from the
 * production seed is split: a component no larger than the maximum becomes a leaf region; a
 * component too small to give two children of the minimum size becomes a leaf; otherwise KaHIP
 * splits it into left, right and separator tiles. A split with a child below the minimum size or
 * a separator larger than the maximum is rejected and the whole component becomes one leaf.
 * Accepted children are partitioned recursively and the separator becomes its own region. Cuts
 * are every walking edge between tiles in different regions.
 *
 * <p>Tiles are presented to KaHIP in ascending packed-tile order, so output is deterministic for
 * a given collision map, transport data, parameters and KaHIP build.
 */
public final class RoutingCutsGenerator
{
	static final RoutingCuts.Parameters DEFAULT_PARAMETERS =
		new RoutingCuts.Parameters(20000, 500, 32, 40, 42, "strong");

	private final CutGraph graph;
	private final RoutingCuts.Parameters parameters;
	private final NodeSeparator separator;
	private final int[] region;
	private final int[] localIndex;
	private int nextRegion = 1;
	private int separatorCalls;
	private int rejectedSplits;

	RoutingCutsGenerator(CutGraph graph, RoutingCuts.Parameters parameters, NodeSeparator separator)
	{
		if (parameters.maximumComponentSize < 2 * parameters.minimumChildSize)
			throw new IllegalArgumentException("maximum component size must be at least twice the minimum child size");
		this.graph = graph;
		this.parameters = parameters;
		this.separator = separator;
		this.region = new int[graph.tileCount()];
		this.localIndex = new int[graph.tileCount()];
		Arrays.fill(localIndex, -1);
	}

	/** Partitions the graph and returns canonical cut pairs as a flat {@code (from, to)} array. */
	int[] generate() throws IOException, InterruptedException
	{
		for (int[] component : selectedComponents())
			partition(component);
		return cuts();
	}

	int separatorCalls()
	{
		return separatorCalls;
	}

	int rejectedSplits()
	{
		return rejectedSplits;
	}

	private List<int[]> selectedComponents()
	{
		int[] sizes = new int[graph.naturalComponentCount() + 1];
		for (int i = 0; i < graph.tileCount(); i++)
			sizes[graph.naturalComponent(i)]++;
		int[][] members = new int[sizes.length][];
		int[] filled = new int[sizes.length];
		for (int component = 1; component < sizes.length; component++)
			if (sizes[component] > parameters.maximumComponentSize && graph.isStructurallyReachable(component))
				members[component] = new int[sizes[component]];
		for (int i = 0; i < graph.tileCount(); i++)
		{
			int component = graph.naturalComponent(i);
			if (members[component] != null)
				members[component][filled[component]++] = i;
		}
		List<int[]> selected = new ArrayList<>();
		for (int[] component : members)
			if (component != null) selected.add(component);
		return selected;
	}

	private void partition(int[] tiles) throws IOException, InterruptedException
	{
		if (tiles.length <= parameters.maximumComponentSize || tiles.length < 2 * parameters.minimumChildSize)
		{
			assign(tiles);
			return;
		}
		separatorCalls++;
		int[] parts = separator.separate(inducedAdjacency(tiles));
		int left = 0, right = 0;
		for (int part : parts)
		{
			if (part == 0) left++;
			else if (part == 1) right++;
		}
		int separatorSize = tiles.length - left - right;
		if (left < parameters.minimumChildSize || right < parameters.minimumChildSize
			|| separatorSize > parameters.maximumSeparatorSize)
		{
			rejectedSplits++;
			assign(tiles);
			return;
		}
		int[][] split = {new int[left], new int[right], new int[separatorSize]};
		int[] counts = new int[3];
		for (int i = 0; i < tiles.length; i++)
			split[parts[i]][counts[parts[i]]++] = tiles[i];
		partition(split[0]);
		partition(split[1]);
		assign(split[2]);
	}

	private void assign(int[] tiles)
	{
		if (tiles.length == 0) return;
		int id = nextRegion++;
		for (int tile : tiles)
			region[tile] = id;
	}

	private int[][] inducedAdjacency(int[] tiles)
	{
		for (int i = 0; i < tiles.length; i++)
			localIndex[tiles[i]] = i;
		int[][] adjacency = new int[tiles.length][];
		int[] scratch = new int[8];
		for (int i = 0; i < tiles.length; i++)
		{
			int count = 0;
			for (int neighbour : graph.neighbours(tiles[i]))
				if (localIndex[neighbour] >= 0) scratch[count++] = localIndex[neighbour];
			adjacency[i] = Arrays.copyOf(scratch, count);
		}
		for (int tile : tiles)
			localIndex[tile] = -1;
		return adjacency;
	}

	private int[] cuts()
	{
		List<int[]> pairs = new ArrayList<>();
		for (int i = 0; i < region.length; i++)
		{
			if (region[i] == 0) continue;
			for (int neighbour : graph.neighbours(i))
			{
				if (region[neighbour] == 0 || region[neighbour] == region[i]) continue;
				int a = graph.tile(i), b = graph.tile(neighbour);
				if (Integer.compareUnsigned(a, b) < 0) pairs.add(new int[] {a, b});
			}
		}
		int[] flat = new int[pairs.size() * 2];
		for (int i = 0; i < pairs.size(); i++)
		{
			flat[2 * i] = pairs.get(i)[0];
			flat[2 * i + 1] = pairs.get(i)[1];
		}
		return new RoutingCuts(0, parameters, flat).pairs();
	}

	/**
	 * Arguments: {@code OUTPUT [--kahip=PATH] [--work-dir=DIR]}. Uses the plugin's packaged
	 * collision map and transports and the default KaHIP parameters.
	 */
	public static void main(String[] args) throws Exception
	{
		if (args.length < 1)
			throw new IllegalArgumentException("usage: RoutingCutsGenerator OUTPUT [--kahip=PATH] [--work-dir=DIR]");
		Path output = Paths.get(args[0]).toAbsolutePath();
		String kahip = "node_separator";
		Path workDirectory = null;
		for (int i = 1; i < args.length; i++)
		{
			if (args[i].startsWith("--kahip=")) kahip = args[i].substring("--kahip=".length());
			else if (args[i].startsWith("--work-dir=")) workDirectory = Paths.get(args[i].substring("--work-dir=".length()));
			else throw new IllegalArgumentException("unknown argument " + args[i]);
		}
		if (workDirectory == null) workDirectory = Files.createTempDirectory("routing-cuts");
		Files.createDirectories(workDirectory);

		long started = System.nanoTime();
		byte[] collisionZip;
		try (InputStream stream = ShortestPathPlugin.class.getResourceAsStream("/collision-map.zip"))
		{
			if (stream == null) throw new IllegalStateException("missing /collision-map.zip");
			collisionZip = stream.readAllBytes();
		}
		CollisionMap collision = new CollisionMap(SplitFlagMap.fromResources());
		Map<Integer, Set<Transport>> transports = TransportLoader.loadAllFromResources();
		CutGraph graph = CutGraph.of(RoutingStaticBuilder.walkingGraph(collision, transports));

		RoutingCutsGenerator generator = new RoutingCutsGenerator(graph, DEFAULT_PARAMETERS,
			new KahipNodeSeparator(kahip, DEFAULT_PARAMETERS, workDirectory));
		int[] pairs = generator.generate();
		RoutingCuts cuts = new RoutingCuts(RoutingCuts.fingerprint(collisionZip), DEFAULT_PARAMETERS, pairs);
		Files.createDirectories(output.getParent());
		Files.write(output, cuts.write());
		System.out.printf("routing cuts: %d cuts, %d KaHIP calls, %d rejected splits, %d regions, %.1fs -> %s%n",
			cuts.cutCount(), generator.separatorCalls(), generator.rejectedSplits(), generator.nextRegion - 1,
			(System.nanoTime() - started) / 1e9, output);
	}
}
