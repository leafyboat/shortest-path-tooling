package shortestpath;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.*;

/**
 * Non-growth dependency-rule lint: the plugin's leaf packages
 * ({@code transport}, {@code pathfinder}, {@code requirement},
 * {@code leagues}, {@code overlay} — subtrees included) must not reference
 * {@code ShortestPathPlugin}. Cross-cutting configuration and world-geometry
 * access goes through owned services (the effective-config settings service,
 * the POH service) or leaf utilities; any new coupling arrives through an
 * injected seam instead.
 *
 * <p>Every pre-existing violation is frozen in {@link #ALLOWLIST}, keyed by
 * {@code path:line} relative to {@code shortest-path/src/main/java/}. The
 * set-equality assertion makes the lint self-maintaining: extraction work
 * <em>removes</em> entries as each site migrates — entries are removed,
 * never added. The resource-anchor sites ({@code TransportLoader},
 * {@code SplitFlagMap}, {@code LeagueRegionChecker}) disappear when the data
 * loader lands self-anchored. An unlisted reference site fails the build
 * (new coupling); an allowlist key with no matching site fails it too
 * (stale entry — remove it in the same change that removed the reference).
 *
 * <p>Run with {@code ./gradlew test --tests '*DependencyRule*'}. Enforcement
 * is that command — the milestone's phase gate plus a checklist item on
 * every extraction pull request. The wrapper {@code test} task runs in
 * neither CI nor the maintenance-verify chain; that gap is deliberate, and
 * wiring the suite into CI is a separate scope decision.
 *
 * <p>{@link #observedSites(Path)} is factored out so
 * {@link #flagsUnlistedCoupling()} can prove both failure branches against
 * temporary fixture roots — the submodule tree is never written to.
 */
public class PluginDependencyRuleTest
{
	private static final Path MAIN_ROOT = Paths.get("shortest-path/src/main/java");

	// "requirement" rejoins when the upstream rework that introduces it lands.
	private static final List<String> LEAF_PACKAGES = List.of(
		"transport", "pathfinder", "leagues", "overlay");

	private static final String PLUGIN_REFERENCE = "ShortestPathPlugin.";

	/**
	 * Frozen census of existing {@code ShortestPathPlugin} reference sites in
	 * the leaf packages. Key = {@code path:line} relative to
	 * {@code shortest-path/src/main/java/}; value = justification + owning
	 * migration. Extraction work removes entries as sites migrate — entries
	 * are removed, never added.
	 */
	private static final Map<String, String> ALLOWLIST = new TreeMap<>();

	static
	{
		// Resource anchors — plugin-class resource reads that die when the
		// data loader anchors on its own class.
		ALLOWLIST.put("shortestpath/transport/TransportLoader.java:33",
			"resource anchor — transport TSV read; the loader migrates self-anchored");
		ALLOWLIST.put("shortestpath/pathfinder/SplitFlagMap.java:100",
			"resource anchor — collision-map resource read; the loader migrates self-anchored");
		ALLOWLIST.put("shortestpath/leagues/LeagueRegionChecker.java:105",
			"resource anchor — league-region TSV read; the loader migrates self-anchored");

		// Static imports of the POH landing-tile constants.
		ALLOWLIST.put("shortestpath/pathfinder/PathfinderConfig.java:36",
			"import static of POH_LANDING_X — migrates to the POH service");
		ALLOWLIST.put("shortestpath/pathfinder/PathfinderConfig.java:37",
			"import static of POH_LANDING_Y — migrates to the POH service");
		ALLOWLIST.put("shortestpath/pathfinder/TransportAvailability.java:10",
			"import static of POH_LANDING_X — migrates to the POH service");
		ALLOWLIST.put("shortestpath/pathfinder/TransportAvailability.java:11",
			"import static of POH_LANDING_Y — migrates to the POH service");

		// override() config reads — migrate to the settings service.
		ALLOWLIST.put("shortestpath/transport/TransportTypeConfig.java:66",
			"override read of useTeleportationItems — migrates to the settings service");
		ALLOWLIST.put("shortestpath/transport/TransportTypeConfig.java:109",
			"override read of a per-type config value — migrates to the settings service");
		ALLOWLIST.put("shortestpath/transport/TransportTypeConfig.java:125",
			"override read of a per-type config value — migrates to the settings service");
		ALLOWLIST.put("shortestpath/pathfinder/PathfinderConfig.java:329",
			"override read of unreachableTargetDistanceThreshold — migrates to the settings service");
		ALLOWLIST.put("shortestpath/pathfinder/PathfinderConfig.java:330",
			"override read of collisionAwareBlockedTargets — migrates to the settings service");
		ALLOWLIST.put("shortestpath/pathfinder/PathfinderConfig.java:333",
			"override read of exactHeuristicWeight — migrates to the settings service");
		ALLOWLIST.put("shortestpath/pathfinder/PathfinderConfig.java:334",
			"override read of avoidWilderness — migrates to the settings service");
		ALLOWLIST.put("shortestpath/pathfinder/PathfinderConfig.java:335",
			"override read of usePoh — migrates to the settings service");
		ALLOWLIST.put("shortestpath/pathfinder/PathfinderConfig.java:341",
			"override read of usePohFairyRing — migrates to the settings service");
		ALLOWLIST.put("shortestpath/pathfinder/PathfinderConfig.java:342",
			"override read of usePohSpiritTree — migrates to the settings service");
		ALLOWLIST.put("shortestpath/pathfinder/PathfinderConfig.java:343",
			"override read of usePohObelisk — migrates to the settings service");
		ALLOWLIST.put("shortestpath/pathfinder/PathfinderConfig.java:347",
			"override read of pohJewelleryBoxTier — migrates to the settings service");
		ALLOWLIST.put("shortestpath/pathfinder/PathfinderConfig.java:350",
			"override read of currencyThreshold — migrates to the settings service");
		ALLOWLIST.put("shortestpath/pathfinder/PathfinderConfig.java:355",
			"override read of includeBankPath — migrates to the settings service");
		ALLOWLIST.put("shortestpath/pathfinder/PathfinderConfig.java:358",
			"override read of respawnPrifddinas — migrates to the settings service");
		ALLOWLIST.put("shortestpath/pathfinder/PathfinderConfig.java:362",
			"override read of unlockCanoeAxe — migrates to the settings service");
		ALLOWLIST.put("shortestpath/pathfinder/PathfinderConfig.java:366",
			"override read of unlockXericsHonour — migrates to the settings service");
		ALLOWLIST.put("shortestpath/pathfinder/PathfinderConfig.java:370",
			"override read of unlockDragontoothPassage — migrates to the settings service");
		ALLOWLIST.put("shortestpath/pathfinder/PathfinderConfig.java:377",
			"override read of costConsumableTeleportationItems — migrates to the settings service");
		ALLOWLIST.put("shortestpath/pathfinder/PathfinderConfig.java:378",
			"override read of costBankVisit — migrates to the settings service");

		// isInsidePoh world-geometry reads — migrate to the POH service.
		ALLOWLIST.put("shortestpath/pathfinder/PathfinderConfig.java:754",
			"isInsidePoh redirect filter — migrates to the POH service");
		ALLOWLIST.put("shortestpath/pathfinder/PathfinderConfig.java:820",
			"isInsidePoh transport filter — migrates to the POH service");
		ALLOWLIST.put("shortestpath/pathfinder/PathfinderConfig.java:1069",
			"isInsidePoh POH-transport classification — migrates to the POH service");
		ALLOWLIST.put("shortestpath/pathfinder/TransportAvailability.java:104",
			"isInsidePoh origin check — migrates to the POH service");
		ALLOWLIST.put("shortestpath/overlay/PathTileOverlay.java:253",
			"isInsidePoh marker filter — migrates to the POH service");
		ALLOWLIST.put("shortestpath/overlay/PathTileOverlay.java:288",
			"isInsidePoh tracer filter — migrates to the POH service");
		ALLOWLIST.put("shortestpath/overlay/PathTileOverlay.java:323",
			"isInsidePoh marker filter — migrates to the POH service");
		ALLOWLIST.put("shortestpath/overlay/PathTileOverlay.java:367",
			"isInsidePoh marker filter — migrates to the POH service");
		ALLOWLIST.put("shortestpath/overlay/PathTileOverlay.java:394",
			"isInsidePoh click-point filter — migrates to the POH service");
		ALLOWLIST.put("shortestpath/overlay/PathTileOverlay.java:785",
			"isInsidePoh transport-tile check — migrates to the POH service");
		ALLOWLIST.put("shortestpath/overlay/PathTileOverlay.java:786",
			"isInsidePoh player-tile check — migrates to the POH service");
	}

	/**
	 * Scan the leaf packages under {@code mainRoot} (expected layout:
	 * {@code <mainRoot>/shortestpath/<pkg>} plus nested subdirectories) and
	 * return the sorted
	 * set of {@code path:line} keys — one per source line containing a
	 * {@code ShortestPathPlugin.} reference, paths relative to
	 * {@code mainRoot}. A line with several references yields one key.
	 */
	static SortedSet<String> observedSites(Path mainRoot) throws IOException
	{
		SortedSet<String> sites = new TreeSet<>();
		Path base = mainRoot.resolve("shortestpath");
		for (String pkg : LEAF_PACKAGES)
		{
			Path dir = base.resolve(pkg);
			if (!Files.isDirectory(dir))
			{
				continue;
			}
			List<Path> sources;
			try (Stream<Path> stream = Files.walk(dir))
			{
				sources = stream
					.filter(p -> p.toString().endsWith(".java"))
					.collect(Collectors.toList());
			}
			for (Path source : sources)
			{
				String rel = mainRoot.relativize(source).toString()
					.replace(File.separatorChar, '/');
				List<String> lines = Files.readAllLines(source);
				for (int i = 0; i < lines.size(); i++)
				{
					if (lines.get(i).contains(PLUGIN_REFERENCE))
					{
						sites.add(rel + ":" + (i + 1));
					}
				}
			}
		}
		return sites;
	}

	@Test
	public void leafPackagesDoNotReferencePlugin() throws IOException
	{
		SortedSet<String> observed = observedSites(MAIN_ROOT);

		SortedSet<String> unlisted = new TreeSet<>(observed);
		unlisted.removeAll(ALLOWLIST.keySet());

		SortedSet<String> stale = new TreeSet<>(ALLOWLIST.keySet());
		stale.removeAll(observed);

		StringBuilder message = new StringBuilder();
		if (!unlisted.isEmpty())
		{
			message.append("new leaf-package coupling to ShortestPathPlugin is not ")
				.append("allowlisted — route cross-cutting config and world-geometry ")
				.append("access through an owned service or injected seam instead: ")
				.append(unlisted).append(' ');
		}
		if (!stale.isEmpty())
		{
			message.append("stale allowlist entries with no matching reference site ")
				.append("— remove this allowlist entry in the change that removed the ")
				.append("reference: ").append(stale);
		}
		assertTrue(message.toString(), unlisted.isEmpty() && stale.isEmpty());
	}

	@Test
	public void scanHasReach() throws IOException
	{
		assertFalse("the frozen allowlist must not be empty — a vacuous guard "
			+ "is worse than none", ALLOWLIST.isEmpty());
		Path base = MAIN_ROOT.resolve("shortestpath");
		for (String pkg : LEAF_PACKAGES)
		{
			Path dir = base.resolve(pkg);
			assertTrue("leaf package directory missing: " + dir, Files.isDirectory(dir));
			long sources;
			try (Stream<Path> stream = Files.walk(dir))
			{
				sources = stream.filter(p -> p.toString().endsWith(".java")).count();
			}
			assertTrue("leaf package scanned no .java files: " + dir, sources > 0);
		}
	}

	/**
	 * Red-direction proof: {@link #observedSites(Path)} must flag an
	 * unlisted {@code ShortestPathPlugin.} site, and an empty fixture root
	 * must mark every allowlist entry stale. Fixture roots live in the temp
	 * directory — the submodule is never written.
	 */
	@Test
	public void flagsUnlistedCoupling() throws IOException
	{
		Path fixture = Files.createTempDirectory("dep-rule-probe");
		Path dir = fixture.resolve("shortestpath").resolve("transport");
		Files.createDirectories(dir);
		Files.write(dir.resolve("LintProbeTmp.java"), Collections.singletonList(
			"class LintProbeTmp { String s = ShortestPathPlugin.CONFIG_GROUP; }"));

		SortedSet<String> observed = observedSites(fixture);
		assertTrue("probe reference not observed: " + observed,
			observed.contains("shortestpath/transport/LintProbeTmp.java:1"));

		SortedSet<String> unlisted = new TreeSet<>(observed);
		unlisted.removeAll(ALLOWLIST.keySet());
		assertEquals("the probe site must land in the unlisted-hit set",
			Collections.singleton("shortestpath/transport/LintProbeTmp.java:1"), unlisted);

		Path empty = Files.createTempDirectory("dep-rule-empty");
		SortedSet<String> nothing = observedSites(empty);
		assertTrue("an empty fixture root must observe no sites: " + nothing,
			nothing.isEmpty());
		SortedSet<String> stale = new TreeSet<>(ALLOWLIST.keySet());
		stale.removeAll(nothing);
		assertEquals("an empty fixture must mark every allowlist entry stale",
			new TreeSet<>(ALLOWLIST.keySet()), stale);
	}
}
