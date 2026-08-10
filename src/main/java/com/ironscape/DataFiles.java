package com.ironscape;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;

/**
 * Where the plugin's DATA files come from — the bundled copy inside the
 * jar normally, or a folder on disk when one is configured.
 *
 * <p>Why this exists: most corrections to this plugin are data, not code.
 * A wrong item in a quest kit, a map pin in the wrong place, a note, an
 * item the quest actually hands you — all of it lives in JSON that is
 * compiled into the jar. So changing one character used to mean a full
 * rebuild and a client restart, mid play-test, which on 2026-08-10 cost
 * three restarts in one evening.
 *
 * <p>Point {@code dataFolder} at a checkout's resources directory and the
 * files there win over the bundled ones, so an edit plus {@code ::ironreload}
 * is visible immediately with the repository still the single source of
 * truth. Empty by default, so a normal install reads the jar and nothing
 * about this class is reachable.
 *
 * <p>Lookup is by FILE NAME, searched recursively, because the resources
 * live in per-topic subfolders (annotations/, places/, items/) and asking
 * the caller to know its own subfolder would just be a second thing to
 * keep in step. Names are unique across those folders. Results are cached
 * so a reload does not re-walk the tree per file; {@link #setFolder}
 * clears the cache.
 */
@Slf4j
public final class DataFiles
{
	private DataFiles()
	{
	}

	private static volatile File folder;
	private static final Map<String, File> RESOLVED = new HashMap<>();

	/** Empty or missing path = bundled files only (the normal case). */
	public static synchronized void setFolder(String path)
	{
		RESOLVED.clear();
		if (path == null || path.trim().isEmpty())
		{
			folder = null;
			return;
		}
		File candidate = new File(path.trim());
		if (!candidate.isDirectory())
		{
			log.warn("Data folder '{}' is not a directory — using bundled files", path);
			folder = null;
			return;
		}
		folder = candidate;
		log.info("Data folder set: {} — files there override the bundled copies", candidate);
	}

	/** Is an override folder in force? Used only to describe the state in chat. */
	public static boolean overriding()
	{
		return folder != null;
	}

	/**
	 * The named data file: the override folder's copy if it has one, else
	 * the resource bundled beside {@code anchor}. Null when neither
	 * exists, which callers already treat as "this corpus is absent".
	 *
	 * <p>An unreadable override falls back to the bundled copy rather than
	 * failing: a half-written file saved mid-edit must not blank the
	 * plugin's data.
	 */
	public static InputStream open(Class<?> anchor, String name)
	{
		File override = resolve(name);
		if (override != null)
		{
			try
			{
				return new FileInputStream(override);
			}
			catch (IOException e)
			{
				log.warn("Could not read override {} — falling back to the bundled copy", override, e);
			}
		}
		return anchor.getResourceAsStream(name);
	}

	private static synchronized File resolve(String name)
	{
		File root = folder;
		if (root == null)
		{
			return null;
		}
		if (RESOLVED.containsKey(name))
		{
			return RESOLVED.get(name);
		}
		File found = null;
		try (Stream<Path> tree = Files.walk(root.toPath(), 6))
		{
			found = tree.filter(Files::isRegularFile)
				.filter(p -> p.getFileName().toString().equals(name))
				.findFirst().map(Path::toFile).orElse(null);
		}
		catch (IOException e)
		{
			log.warn("Could not search data folder {}", root, e);
		}
		RESOLVED.put(name, found);
		return found;
	}
}
