package shortestpath.routingcuts;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import shortestpath.pathfinder.exact.RoutingCuts;

/** Runs KaHIP's {@code node_separator} on an unweighted METIS graph file. */
final class KahipNodeSeparator implements NodeSeparator
{
	private final String executable;
	private final RoutingCuts.Parameters parameters;
	private final Path workDirectory;
	private int calls;

	KahipNodeSeparator(String executable, RoutingCuts.Parameters parameters, Path workDirectory)
	{
		this.executable = executable;
		this.parameters = parameters;
		this.workDirectory = workDirectory;
	}

	@Override
	public int[] separate(int[][] adjacency) throws IOException, InterruptedException
	{
		int call = ++calls;
		Path graph = workDirectory.resolve("graph-" + call + ".metis");
		Path output = workDirectory.resolve("graph-" + call + ".separator");
		writeMetis(adjacency, graph);
		Process process = new ProcessBuilder(executable, graph.toString(),
			"--output_filename=" + output,
			"--seed=" + parameters.seed,
			"--imbalance=" + parameters.imbalance,
			"--preconfiguration=" + parameters.preconfiguration)
			.redirectErrorStream(true)
			.redirectOutput(workDirectory.resolve("graph-" + call + ".log").toFile())
			.start();
		int exit = process.waitFor();
		if (exit != 0)
			throw new IOException(executable + " exited with " + exit + " (see graph-" + call + ".log)");
		int[] parts = readParts(output, adjacency.length);
		Files.delete(graph);
		Files.delete(output);
		return parts;
	}

	static void writeMetis(int[][] adjacency, Path path) throws IOException
	{
		long degreeSum = 0;
		for (int[] neighbours : adjacency)
			degreeSum += neighbours.length;
		if (degreeSum % 2 != 0) throw new IllegalArgumentException("adjacency is not symmetric");
		try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.US_ASCII))
		{
			writer.write(adjacency.length + " " + degreeSum / 2);
			writer.newLine();
			StringBuilder line = new StringBuilder();
			for (int[] neighbours : adjacency)
			{
				line.setLength(0);
				for (int i = 0; i < neighbours.length; i++)
				{
					if (i > 0) line.append(' ');
					line.append(neighbours[i] + 1);
				}
				writer.write(line.toString());
				writer.newLine();
			}
		}
	}

	static int[] readParts(Path path, int expected) throws IOException
	{
		List<String> lines = new ArrayList<>(Files.readAllLines(path, StandardCharsets.US_ASCII));
		while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank())
			lines.remove(lines.size() - 1);
		if (lines.size() != expected)
			throw new IOException("partition output has " + lines.size() + " lines, expected " + expected);
		int[] parts = new int[expected];
		for (int i = 0; i < expected; i++)
		{
			String value = lines.get(i).trim();
			if (!value.equals("0") && !value.equals("1") && !value.equals("2"))
				throw new IOException("invalid partition value on line " + (i + 1) + ": " + value);
			parts[i] = value.charAt(0) - '0';
		}
		return parts;
	}
}
