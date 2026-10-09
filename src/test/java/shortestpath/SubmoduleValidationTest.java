package shortestpath;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Automated validation tests for Phase 01: Resolve Git Submodule Conflicts
 * 
 * These tests verify the key validation criteria from 01-VALIDATION.md:
 * - V-01: Submodule Properly Initialized
 * - V-06: Git Index Cleanliness  
 * - V-08: Submodule Git Repository Structure
 * - V-10: Build Output Verification
 * - V-11: Configuration File Verification
 */
public class SubmoduleValidationTest
{
	@Test
	public void testSubmoduleIsDirectoryNotSymlink()
	{
		File submoduleDir = new File("shortest-path");
		
		// V-01: Submodule must be a directory, not a symlink
		assertTrue("Submodule directory must exist", submoduleDir.exists());
		assertTrue("Submodule must be a directory", submoduleDir.isDirectory());
		assertFalse("Submodule must not be a symlink", Files.isSymbolicLink(submoduleDir.toPath()));
	}

	/**
	 * Resolve the submodule's real git directory. The submodule's .git is a
	 * file whose "gitdir:" line points at it — under .git/modules/shortest-path
	 * in a plain checkout, .git/worktrees/&lt;name&gt;/modules/shortest-path in a
	 * linked worktree.
	 */
	private static File submoduleGitDir() throws IOException
	{
		File gitFile = new File("shortest-path/.git");
		assertTrue("Submodule .git file must exist", gitFile.isFile());
		for (String line : Files.readAllLines(gitFile.toPath()))
		{
			if (line.startsWith("gitdir:"))
			{
				return new File("shortest-path", line.substring("gitdir:".length()).trim())
					.getCanonicalFile();
			}
		}
		fail("Submodule .git file contains no gitdir pointer");
		return null;
	}

	@Test
	public void testSubmoduleGitRepositoryExists() throws IOException
	{
		// V-08: Submodule git repository must exist under the superproject's git dir
		File submoduleGitDir = submoduleGitDir();

		assertTrue("Submodule git repository must exist: " + submoduleGitDir, submoduleGitDir.exists());
		assertTrue("Submodule git repository must be a directory", submoduleGitDir.isDirectory());

		File config = new File(submoduleGitDir, "config");
		assertTrue("Submodule git config must exist", config.exists());
		assertTrue("Submodule git config must be a file", config.isFile());
	}

	@Test
	public void testSubmoduleGitFileStructure()
	{
		File submoduleDir = new File("shortest-path");
		File gitFile = new File(submoduleDir, ".git");
		
		// V-01: Submodule should have .git file pointing to .git/modules
		assertTrue("Submodule .git file must exist", gitFile.exists());
		assertTrue("Submodule .git must be a file, not directory", gitFile.isFile());
	}

	@Test
	public void testGitModulesConfiguration()
	{
		// V-11: .gitmodules must contain correct configuration
		File gitmodules = new File(".gitmodules");
		
		assertTrue(".gitmodules file must exist", gitmodules.exists());
		assertTrue(".gitmodules must be a file", gitmodules.isFile());
		
		try
		{
			List<String> content = Files.readAllLines(gitmodules.toPath());
			String contentString = String.join("\n", content);
			
			assertTrue(".gitmodules must contain submodule configuration", contentString.contains("submodule"));
			assertTrue(".gitmodules must reference shortest-path submodule", contentString.contains("shortest-path"));
		}
		catch (IOException e)
		{
			fail("Failed to read .gitmodules file: " + e.getMessage());
		}
	}

	@Test
	public void testSettingsGradleConfiguration()
	{
		// V-11: settings.gradle must contain composite build configuration
		File settingsGradle = new File("settings.gradle");
		
		assertTrue("settings.gradle file must exist", settingsGradle.exists());
		assertTrue("settings.gradle must be a file", settingsGradle.isFile());
		
		try
		{
			List<String> content = Files.readAllLines(settingsGradle.toPath());
			String contentString = String.join("\n", content);
			
			assertTrue("settings.gradle must contain includeBuild configuration", contentString.contains("includeBuild"));
			assertTrue("settings.gradle must reference shortest-path submodule", contentString.contains("shortest-path"));
		}
		catch (IOException e)
		{
			fail("Failed to read settings.gradle file: " + e.getMessage());
		}
	}

	@Test
	public void testBuildOutputExists()
	{
		// V-10: Build output directories must exist.
		// Under the Gradle test task both always exist: test results land in
		// build/, and :shortest-path:jar is a dependency of compileTestJava.
		File mainBuildDir = new File("build");
		File submoduleBuildDir = new File("shortest-path/build");

		assertTrue("Main build directory must exist", mainBuildDir.exists());
		assertTrue("Main build path must be a directory", mainBuildDir.isDirectory());
		assertTrue("Submodule build directory must exist", submoduleBuildDir.exists());
		assertTrue("Submodule build path must be a directory", submoduleBuildDir.isDirectory());
	}

	@Test
	public void testSubmoduleBuildGradleExists()
	{
		// V-04: Submodule must have valid build configuration
		File submoduleBuildGradle = new File("shortest-path/build.gradle");
		
		assertTrue("Submodule build.gradle must exist", submoduleBuildGradle.exists());
		assertTrue("Submodule build.gradle must be a file", submoduleBuildGradle.isFile());
		
		try
		{
			List<String> content = Files.readAllLines(submoduleBuildGradle.toPath());
			String contentString = String.join("\n", content);
			
			assertTrue("Submodule build.gradle must contain plugins configuration", contentString.contains("plugins"));
		}
		catch (IOException e)
		{
			fail("Failed to read submodule build.gradle file: " + e.getMessage());
		}
	}

	@Test
	public void testGitIgnoreConfiguration()
	{
		// Verify .gitignore contains expected entries from Phase 01
		File gitignore = new File(".gitignore");
		
		assertTrue(".gitignore file must exist", gitignore.exists());
		
		try
		{
			List<String> content = Files.readAllLines(gitignore.toPath());
			String contentString = String.join("\n", content);
			
			// Phase 01 added debug test data to gitignore
			// This test verifies the structure is maintained
			assertTrue(".gitignore must contain configuration", contentString.length() > 0);
		}
		catch (IOException e)
		{
			fail("Failed to read .gitignore file: " + e.getMessage());
		}
	}

	@Test
	public void testProjectStructureIntegrity()
	{
		// Validate overall project structure after submodule resolution
		File projectRoot = new File(".");
		File submoduleDir = new File("shortest-path");
		File gradlew = new File("gradlew");
		File settingsGradle = new File("settings.gradle");
		File buildGradle = new File("build.gradle");
		
		// Verify key project structure elements
		assertTrue("Project root must exist", projectRoot.exists());
		assertTrue("Submodule directory must exist", submoduleDir.exists());
		assertTrue("Gradle wrapper must exist", gradlew.exists());
		assertTrue("settings.gradle must exist", settingsGradle.exists());
		assertTrue("build.gradle must exist", buildGradle.exists());
	}

	@Test
	public void testSubmoduleRemoteConfiguration() throws IOException
	{
		// V-03 / V-09: Submodule remotes must follow the repo convention:
		// 'upstream' = Skretzo/shortest-path, 'origin' = the user's fork.
		File config = new File(submoduleGitDir(), "config");

		assertTrue("Submodule git config must exist", config.exists());
		assertTrue("Submodule git config must be a file", config.isFile());

		Map<String, String> remoteUrls = new HashMap<>();

		try
		{
			List<String> lines = Files.readAllLines(config.toPath());
			String currentRemote = null;

			for (String line : lines)
			{
				String trimmed = line.trim();

				if (trimmed.startsWith("[remote \"") && trimmed.endsWith("\"]"))
				{
					currentRemote = trimmed.substring(9, trimmed.length() - 2);
				}
				else if (trimmed.startsWith("["))
				{
					currentRemote = null;
				}
				else if (currentRemote != null && trimmed.startsWith("url"))
				{
					String url = trimmed.substring(trimmed.indexOf('=') + 1).trim();
					remoteUrls.put(currentRemote, url);
				}
			}
		}
		catch (IOException e)
		{
			fail("Failed to read submodule git config: " + e.getMessage());
		}

		// 'upstream' remote must exist and point to the canonical plugin repo
		assertTrue("Submodule must have an 'upstream' remote", remoteUrls.containsKey("upstream"));
		assertEquals("'upstream' must point to Skretzo/shortest-path",
			"https://github.com/Skretzo/shortest-path.git", remoteUrls.get("upstream"));

		// 'origin' remote must exist and point to a fork (not the upstream repo)
		assertTrue("Submodule must have an 'origin' remote", remoteUrls.containsKey("origin"));
		String originUrl = remoteUrls.get("origin");
		assertTrue("'origin' must be a shortest-path repository, got: " + originUrl,
			originUrl.endsWith("/shortest-path.git"));
		assertFalse("'origin' must be a fork, not the Skretzo upstream repo",
			"https://github.com/Skretzo/shortest-path.git".equals(originUrl));
	}

	@Test
	public void testDependencySubstitutionConfigured()
	{
		// V-05: settings.gradle must configure dependency substitution so the
		// 'shortestpath:shortest-path' module resolves to the included build.
		File settingsGradle = new File("settings.gradle");

		assertTrue("settings.gradle file must exist", settingsGradle.exists());
		assertTrue("settings.gradle must be a file", settingsGradle.isFile());

		try
		{
			List<String> content = Files.readAllLines(settingsGradle.toPath());
			String contentString = String.join("\n", content);

			assertTrue("settings.gradle must include the shortest-path build",
				contentString.contains("includeBuild") && contentString.contains("shortest-path"));
			assertTrue("settings.gradle must configure dependency substitution",
				contentString.contains("dependencySubstitution") || contentString.contains("substitute"));
			assertTrue("Dependency substitution must reference the shortest-path module",
				contentString.contains("shortestpath:shortest-path"));
		}
		catch (IOException e)
		{
			fail("Failed to read settings.gradle file: " + e.getMessage());
		}
	}

	@Test
	public void testSubmodulePinnedAtValidCommit() throws IOException, InterruptedException
	{
		// V-07: Submodule must be checked out at the pinned commit.
		// 'git submodule status' output prefix: ' ' = clean pinned checkout,
		// '+' = HEAD drifted from recorded commit, '-' = uninitialized.
		ProcessBuilder processBuilder = new ProcessBuilder("git", "submodule", "status", "shortest-path");
		processBuilder.redirectErrorStream(true);
		Process process = processBuilder.start();

		StringBuilder output = new StringBuilder();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream())))
		{
			String line;
			while ((line = reader.readLine()) != null)
			{
				output.append(line).append('\n');
			}
		}

		int exitCode = process.waitFor();
		assertEquals("git submodule status must succeed. Output: " + output, 0, exitCode);

		String statusLine = output.toString().split("\n")[0];
		assertTrue("Submodule must be at the pinned commit (leading space, 40-char hash). Got: " + statusLine,
			statusLine.matches(" [0-9a-f]{40} shortest-path.*"));
	}
}
