package com.ironscape;

import java.io.InputStream;

/**
 * Where the plugin's DATA files come from: the copy bundled in the jar.
 *
 * <p>This class used to support a configurable "data folder" that overrode
 * the bundled files, so a data-only correction could be made in a checkout
 * and picked up with {@code ::ironreload} instead of a rebuild. Plugin Hub
 * review rejected it on 2026-09-02 — reading from a directory the user
 * names is outside the plugin's own subdirectory of {@code .runelite}, and
 * the rule there is that all file I/O stays within it. The feature is gone
 * rather than narrowed, which is what the reviewer asked for.
 *
 * <p>The class is kept as the single door every bundled data read goes
 * through, so if that rule ever changes there is one place to change, and
 * so a reader can see at a glance that data comes from the jar and nowhere
 * else. Files the plugin WRITES (captured pins, local places, the guide
 * manifest, bank snapshots, problem reports) live in
 * {@code ~/.runelite/ironscape/} and are unaffected.
 */
public final class DataFiles
{
	private DataFiles()
	{
	}

	/**
	 * The named data file from the jar, resolved against {@code anchor}'s
	 * package the way {@link Class#getResourceAsStream} does. Null when
	 * there is no such resource, which callers already treat as "this
	 * corpus is absent".
	 */
	public static InputStream open(Class<?> anchor, String name)
	{
		return anchor.getResourceAsStream(name);
	}
}
