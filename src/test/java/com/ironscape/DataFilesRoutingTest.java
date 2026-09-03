package com.ironscape;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Bundled data is read from the jar, through one door, and from nowhere
 * else on disk.
 *
 * <p>Plugin Hub review, 2026-09-02: "all file i/o must occur within a
 * plugin-specific subdir within .runelite", and specifically that the
 * configurable data folder this class used to support "is not going to be
 * allowed". That folder let a data-only fix be picked up with
 * {@code ::ironreload} instead of a rebuild, and it is gone. These tests
 * exist so it cannot come back by accident, since the cost of finding out
 * is a rejected submission days later.
 *
 * <p>Files the plugin WRITES are a different question and are fine: they
 * live in {@code ~/.runelite/ironscape/}, which is exactly what the rule
 * asks for.
 */
public class DataFilesRoutingTest
{
	/**
	 * travel_distances.bin.gz is a compiled lookup table built by
	 * tools/build-travel-distances.mjs, never hand-edited, and it reads
	 * itself off the classpath.
	 */
	private static final String BUILD_ARTIFACT = "TravelDistances.java";

	@Test
	public void bundledDataIsReadThroughOneDoor() throws IOException
	{
		List<String> offenders = scan("getResourceAsStream(",
			f -> f.equals("DataFiles.java") || f.equals(BUILD_ARTIFACT));
		assertTrue("Read bundled data with DataFiles.open(...), so there is one"
			+ " place that decides where data comes from. Found in:\n  "
			+ String.join("\n  ", offenders), offenders.isEmpty());
	}

	/**
	 * DataFiles must not touch the filesystem at all. It is the class the
	 * reviewer pointed at, so the rule is worth asserting on the class
	 * itself rather than on a behaviour that could be reintroduced
	 * somewhere adjacent.
	 */
	@Test
	public void dataFilesDoesNotTouchTheFilesystem() throws IOException
	{
		Path file = Paths.get("src/main/java/com/ironscape/DataFiles.java");
		if (!Files.isRegularFile(file))
		{
			throw new IOException("cannot find DataFiles.java from "
				+ Paths.get("").toAbsolutePath());
		}
		List<String> offenders = new ArrayList<>();
		String[] lines = new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
			.split("\r?\n");
		for (int i = 0; i < lines.length; i++)
		{
			String trimmed = lines[i].trim();
			if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*"))
			{
				continue;   // the comment explaining the ban names the classes
			}
			for (String banned : new String[]{"java.io.File", "java.nio.file",
				"new File(", "Files.", "Paths.", "FileInputStream"})
			{
				if (lines[i].contains(banned))
				{
					offenders.add((i + 1) + ": " + trimmed);
				}
			}
		}
		assertTrue("DataFiles must resolve bundled data from the classpath only —"
			+ " reading a folder the user names is what the Plugin Hub rejected."
			+ " Found in:\n  " + String.join("\n  ", offenders), offenders.isEmpty());
	}

	private static List<String> scan(String needle,
		java.util.function.Predicate<String> exempt) throws IOException
	{
		Path root = Paths.get("src/main/java");
		if (!Files.isDirectory(root))
		{
			throw new IOException("cannot find src/main/java from "
				+ Paths.get("").toAbsolutePath());
		}
		List<String> offenders = new ArrayList<>();
		try (Stream<Path> tree = Files.walk(root))
		{
			for (Path file : (Iterable<Path>) tree.filter(p -> p.toString().endsWith(".java"))::iterator)
			{
				if (exempt.test(file.getFileName().toString()))
				{
					continue;
				}
				String[] lines = new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
					.split("\r?\n");
				for (int i = 0; i < lines.length; i++)
				{
					String trimmed = lines[i].trim();
					if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*"))
					{
						continue;
					}
					if (lines[i].contains(needle))
					{
						offenders.add(file + ":" + (i + 1) + "  " + trimmed);
					}
				}
			}
		}
		return offenders;
	}
}
