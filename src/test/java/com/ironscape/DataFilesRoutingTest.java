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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Every hand-editable data file must be read through {@link DataFiles}.
 *
 * <p>That is what makes {@code ::ironreload} work: DataFiles looks in the
 * configured folder first, so an edit in the repo checkout wins over the
 * copy baked into the jar. A file read straight off the classpath instead
 * quietly ignores the folder — the reload prints its success line, the
 * plugin re-reads the jar, and nothing changes.
 *
 * <p>Four files were in that state until 2026-08-27: the guide data,
 * minigame_landings.json, item_ids.json and gear_sets.json. The owner
 * found it the ordinary way — five item rows with no sprites after a
 * reload that said it had worked.
 */
public class DataFilesRoutingTest
{
	/**
	 * The one legitimate exception is travel_distances.bin.gz: a compiled
	 * lookup table produced by tools/build-travel-distances.mjs, never
	 * hand-edited, so there is nothing to hot-reload.
	 */
	private static final String BUILD_ARTIFACT = "TravelDistances.java";

	@Test
	public void dataFilesAreReadThroughTheOverride() throws IOException
	{
		List<String> offenders = new ArrayList<>();
		Path root = Paths.get("src/main/java");
		if (!Files.isDirectory(root))
		{
			throw new IOException("cannot find src/main/java from " + Paths.get("").toAbsolutePath());
		}
		try (Stream<Path> tree = Files.walk(root))
		{
			for (Path file : (Iterable<Path>) tree.filter(p -> p.toString().endsWith(".java"))::iterator)
			{
				String name = file.getFileName().toString();
				if (name.equals("DataFiles.java") || name.equals(BUILD_ARTIFACT))
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
					if (lines[i].contains("getResourceAsStream("))
					{
						offenders.add(file + ":" + (i + 1) + "  " + trimmed);
					}
				}
			}
		}
		assertTrue("Read data files with DataFiles.open(...), not getResourceAsStream:"
			+ " a classpath read ignores the data folder, so ::ironreload silently"
			+ " does nothing for that file. Found in:\n  "
			+ String.join("\n  ", offenders), offenders.isEmpty());
	}

	/**
	 * Callers pass resource names in both shapes — package-relative
	 * ("item_ids.json") and absolute ("/com/ironscape/places/x.json") — and
	 * the override folder is searched by FILE NAME. Comparing the absolute
	 * form against a file name matches nothing, and the miss is silent: it
	 * falls back to the jar, so the override looks configured and does
	 * nothing. Reads a real file out of a real folder rather than asserting
	 * on a string, so it fails if resolve() stops stripping the path.
	 */
	@Test
	public void anAbsoluteResourceNameFindsTheOverride() throws IOException
	{
		Path folder = Files.createTempDirectory("ironscape-data");
		try
		{
			Path nested = folder.resolve("places");
			Files.createDirectories(nested);
			Files.write(nested.resolve("minigame_landings.json"),
				"OVERRIDE".getBytes(StandardCharsets.UTF_8));
			DataFiles.setFolder(folder.toString());

			try (java.io.InputStream in = DataFiles.open(DataFilesRoutingTest.class,
				"/com/ironscape/places/minigame_landings.json"))
			{
				assertNotNull("the override folder was not searched at all", in);
				byte[] read = new byte[8];
				assertEquals(8, in.read(read));
				assertEquals("OVERRIDE", new String(read, StandardCharsets.UTF_8));
			}
		}
		finally
		{
			DataFiles.setFolder("");
		}
	}
}
