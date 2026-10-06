package shortestpath.routingcuts;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.exact.RoutingCuts;

public class RoutingCutsGeneratorTest
{
	private static final RoutingCuts.Parameters SMALL = new RoutingCuts.Parameters(20, 5, 10, 40, 42, "strong");

	@Test
	public void splitsLargeReachableComponentAlongSeparator() throws Exception
	{
		// A 6x6 grid (36 tiles > 20); the fake separator uses the third row as separator.
		GridGraph grid = new GridGraph(6, 6, true);
		RowSeparator separator = new RowSeparator(6, 2);
		RoutingCutsGenerator generator = new RoutingCutsGenerator(grid, SMALL, separator);

		int[] cuts = generator.generate();

		assertEquals(1, generator.separatorCalls());
		assertEquals(0, generator.rejectedSplits());
		// Every walking edge between the separator row (y=102) and rows y=101 or y=103 is cut:
		// per neighbouring row 6 vertical + 10 diagonal edges.
		assertEquals(2 * (6 + 10), cuts.length / 2);
		for (int i = 0; i < cuts.length; i += 2)
		{
			int y1 = WorldPointUtil.unpackWorldY(cuts[i]);
			int y2 = WorldPointUtil.unpackWorldY(cuts[i + 1]);
			assertTrue(y1 == 102 || y2 == 102);
			assertTrue(Integer.compareUnsigned(cuts[i], cuts[i + 1]) < 0);
		}
	}

	@Test
	public void leavesSmallOrUnreachableComponentsUncut() throws Exception
	{
		RowSeparator separator = new RowSeparator(6, 2);
		assertEquals(0, new RoutingCutsGenerator(new GridGraph(4, 4, true), SMALL, separator).generate().length);
		assertEquals(0, new RoutingCutsGenerator(new GridGraph(6, 6, false), SMALL, separator).generate().length);
		assertEquals(0, separator.calls);
	}

	@Test
	public void rejectsSplitWithOversizedSeparatorOrSmallChild() throws Exception
	{
		RoutingCuts.Parameters tightSeparator = new RoutingCuts.Parameters(20, 5, 5, 40, 42, "strong");
		RoutingCutsGenerator generator =
			new RoutingCutsGenerator(new GridGraph(6, 6, true), tightSeparator, new RowSeparator(6, 2));
		assertEquals(0, generator.generate().length);
		assertEquals(1, generator.rejectedSplits());

		generator = new RoutingCutsGenerator(new GridGraph(6, 6, true), SMALL, new RowSeparator(6, 0));
		assertEquals(0, generator.generate().length);
		assertEquals(1, generator.rejectedSplits());
	}

	@Test
	public void isDeterministic() throws Exception
	{
		int[] first = new RoutingCutsGenerator(new GridGraph(6, 6, true), SMALL, new RowSeparator(6, 2)).generate();
		int[] second = new RoutingCutsGenerator(new GridGraph(6, 6, true), SMALL, new RowSeparator(6, 2)).generate();
		assertArrayEquals(first, second);
	}

	@Test
	public void writesMetisAndReadsPartitions() throws Exception
	{
		Path directory = Files.createTempDirectory("metis");
		Path graph = directory.resolve("g.metis");
		KahipNodeSeparator.writeMetis(new int[][] {{1}, {0, 2}, {1}}, graph);
		assertEquals(Arrays.asList("3 2", "2", "1 3", "2"), Files.readAllLines(graph, StandardCharsets.US_ASCII));

		Path parts = directory.resolve("g.separator");
		Files.write(parts, "0\n2\n1\n".getBytes(StandardCharsets.US_ASCII));
		assertArrayEquals(new int[] {0, 2, 1}, KahipNodeSeparator.readParts(parts, 3));
	}

	/** Labels nodes by local row (local order is row-major for a grid): rows before, at and after. */
	private static final class RowSeparator implements NodeSeparator
	{
		private final int width;
		private final int separatorRow;
		int calls;

		RowSeparator(int width, int separatorRow)
		{
			this.width = width;
			this.separatorRow = separatorRow;
		}

		@Override
		public int[] separate(int[][] adjacency)
		{
			calls++;
			int[] parts = new int[adjacency.length];
			for (int i = 0; i < parts.length; i++)
			{
				int row = i / width;
				parts[i] = row < separatorRow ? 0 : row == separatorRow ? 2 : 1;
			}
			return parts;
		}
	}

	/** A fully walkable W x H grid with 8-way movement, forming one natural component. */
	private static final class GridGraph implements CutGraph
	{
		private final int width;
		private final int height;
		private final boolean reachable;

		GridGraph(int width, int height, boolean reachable)
		{
			this.width = width;
			this.height = height;
			this.reachable = reachable;
		}

		@Override
		public int tileCount()
		{
			return width * height;
		}

		@Override
		public int tile(int index)
		{
			return WorldPointUtil.packWorldPoint(100 + index % width, 100 + index / width, 0);
		}

		@Override
		public int naturalComponent(int index)
		{
			return 1;
		}

		@Override
		public int naturalComponentCount()
		{
			return 1;
		}

		@Override
		public boolean isStructurallyReachable(int naturalComponent)
		{
			return reachable;
		}

		@Override
		public int[] neighbours(int index)
		{
			int x = index % width, y = index / width;
			List<Integer> result = new ArrayList<>();
			for (int dy = -1; dy <= 1; dy++)
				for (int dx = -1; dx <= 1; dx++)
				{
					int nx = x + dx, ny = y + dy;
					if ((dx != 0 || dy != 0) && nx >= 0 && ny >= 0 && nx < width && ny < height)
						result.add(ny * width + nx);
				}
			return result.stream().mapToInt(Integer::intValue).toArray();
		}
	}
}
