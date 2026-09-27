package shortestpath.routingcuts;

import java.io.IOException;

/** Computes a node separator of an undirected graph. */
interface NodeSeparator
{
	/**
	 * @param adjacency zero-based symmetric adjacency lists
	 * @return one entry per node: 0 (left part), 1 (right part) or 2 (separator)
	 */
	int[] separate(int[][] adjacency) throws IOException, InterruptedException;
}
