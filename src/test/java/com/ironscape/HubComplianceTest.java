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
 * Rules the Plugin Hub enforces that our own build does not.
 *
 * <p>The hub rejects a plugin outright for these, and it only finds out
 * when the pin is bumped and its check runs — so the failure arrives
 * hours later, on a public PR, after a "clean build passed locally". That
 * happened on 2026-08-12: a `new Gson()` in a field initialiser passed
 * every local check and failed the hub with "Do not create fresh Gson
 * instances, always @Inject the client's Gson."
 *
 * <p>Cheaper to fail here. Add a rule whenever the hub teaches us one.
 */
public class HubComplianceTest
{
	/**
	 * The client's Gson is injectable and configured; a fresh one has
	 * different settings and the hub treats it as a terminally deprecated
	 * API. Customise with {@code gson.newBuilder()} if needed.
	 *
	 * <p>MAIN sources only: tests are not shipped in the jar, and the hub
	 * never sees them.
	 */
	@Test
	public void noFreshGsonInstances() throws IOException
	{
		List<String> offenders = scanMainSources("new Gson(", "new GsonBuilder(",
			"new com.google.gson.Gson(", "new com.google.gson.GsonBuilder(");
		assertTrue("The Plugin Hub rejects fresh Gson instances — @Inject the"
				+ " client's Gson instead (use .newBuilder() to customise). Found in:\n  "
				+ String.join("\n  ", offenders),
			offenders.isEmpty());
	}

	/** Reflection is forbidden by the hub; it cost us the Quest Helper handoff in review round 1. */
	@Test
	public void noReflection() throws IOException
	{
		List<String> offenders = scanMainSources(".getDeclaredMethod(", ".getDeclaredField(",
			"java.lang.reflect.");
		assertTrue("The Plugin Hub forbids reflection. Found in:\n  "
			+ String.join("\n  ", offenders), offenders.isEmpty());
	}

	private static List<String> scanMainSources(String... banned) throws IOException
	{
		Path root = Paths.get("src/main/java");
		if (!Files.isDirectory(root))
		{
			// Run from an unexpected working directory — better to say so
			// than to pass silently, which is how a check stops meaning
			// anything.
			throw new IOException("cannot find src/main/java from " + Paths.get("").toAbsolutePath());
		}
		List<String> offenders = new ArrayList<>();
		try (Stream<Path> tree = Files.walk(root))
		{
			for (Path file : (Iterable<Path>) tree.filter(p -> p.toString().endsWith(".java"))::iterator)
			{
				String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
				String[] lines = source.split("\r?\n");
				for (int i = 0; i < lines.length; i++)
				{
					String line = lines[i];
					// Skip comments: these names appear in the very comments
					// explaining why we must not use them.
					String trimmed = line.trim();
					if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*"))
					{
						continue;
					}
					for (String needle : banned)
					{
						if (line.contains(needle))
						{
							offenders.add(file + ":" + (i + 1) + "  " + trimmed);
						}
					}
				}
			}
		}
		return offenders;
	}
}
