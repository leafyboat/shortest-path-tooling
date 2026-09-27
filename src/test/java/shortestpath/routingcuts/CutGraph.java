package shortestpath.routingcuts;

import shortestpath.pathfinder.exact.RoutingStaticBuilder;

/** The walking graph the cut generator partitions. Tile indexes follow ascending packed order. */
interface CutGraph
{
	int tileCount();

	int tile(int index);

	/** Natural component id of a tile index, in {@code 1..naturalComponentCount()}. */
	int naturalComponent(int index);

	int naturalComponentCount();

	boolean isStructurallyReachable(int naturalComponent);

	/** Indexes of the walkable tiles one ordinary walking step away. */
	int[] neighbours(int index);

	static CutGraph of(RoutingStaticBuilder.WalkingGraph graph)
	{
		return new CutGraph()
		{
			@Override
			public int tileCount()
			{
				return graph.tileCount();
			}

			@Override
			public int tile(int index)
			{
				return graph.tile(index);
			}

			@Override
			public int naturalComponent(int index)
			{
				return graph.naturalComponent(index);
			}

			@Override
			public int naturalComponentCount()
			{
				return graph.naturalComponentCount();
			}

			@Override
			public boolean isStructurallyReachable(int naturalComponent)
			{
				return graph.isStructurallyReachable(naturalComponent);
			}

			@Override
			public int[] neighbours(int index)
			{
				return graph.neighbours(index);
			}
		};
	}
}
