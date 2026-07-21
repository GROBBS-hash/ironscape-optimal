package com.bruhsailer.places;

import com.google.gson.Gson;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.RuneLite;

/**
 * The gazetteer: place/NPC names mapped to world coordinates. Any name in
 * here becomes a clickable link EVERYWHERE it appears in the guide text —
 * clicking routes there via Shortest Path. One capture lights up every
 * mention at once.
 *
 * Same two-layer scheme as annotations: a bundled community file inside
 * the jar, overridden per name by ~/.runelite/bruhsailer/places.json.
 */
@Slf4j
@Singleton
public class PlaceManager
{
	/** Link prefix used in generated HTML; StepRow recognises it on click. */
	public static final String LINK_PREFIX = "bruh:place:";

	private static final int FILE_VERSION = 1;

	private final Gson gson;
	private final File localFile;

	private Map<String, Place> bundled = new HashMap<>();
	private Map<String, Place> local = new HashMap<>();

	/** Case-insensitive alternation over all known names, longest first. Null when empty. */
	private Pattern namePattern;

	@Inject
	public PlaceManager(Gson gson)
	{
		this(gson, new File(RuneLite.RUNELITE_DIR, "bruhsailer/places.json"));
	}

	PlaceManager(Gson gson, File localFile)
	{
		this.gson = gson.newBuilder().setPrettyPrinting().create();
		this.localFile = localFile;
	}

	public synchronized void load()
	{
		bundled = read(() -> {
			InputStream in = PlaceManager.class.getResourceAsStream("places.json");
			return in == null ? null : new InputStreamReader(in, StandardCharsets.UTF_8);
		});
		local = read(() -> localFile.exists() ? new FileReader(localFile) : null);
		rebuildPattern();
		log.debug("Places loaded: {} bundled, {} local", bundled.size(), local.size());
	}

	public synchronized WorldPoint get(String name)
	{
		Place place = local.get(key(name));
		if (place == null)
		{
			place = bundled.get(key(name));
		}
		return place == null ? null : new WorldPoint(place.x, place.y, place.plane);
	}

	/** Save a place under a name and start linkifying it. */
	public synchronized void add(String name, WorldPoint point)
	{
		Place place = new Place();
		place.display = name.trim();
		place.x = point.getX();
		place.y = point.getY();
		place.plane = point.getPlane();
		local.put(key(name), place);
		save();
		rebuildPattern();
	}

	/**
	 * The location of the first known place name mentioned in the text,
	 * or null. Used to derive a navigation target for steps that were
	 * never ⌖-captured ("Talk to Reldo" -> Reldo's spot).
	 *
	 * QUEST names only count when the text says to start/do that quest —
	 * an incidental mention ("your Shield of Arrav partner") must not
	 * route you to the quest start. Clicking a quest link by hand still
	 * always navigates; this filter is for AUTOMATIC targeting only.
	 */
	public synchronized WorldPoint firstPlaceIn(String text)
	{
		if (namePattern == null)
		{
			return null;
		}
		String lower = text.toLowerCase(Locale.ROOT);
		Matcher matcher = namePattern.matcher(text);
		while (matcher.find())
		{
			String key = key(matcher.group());
			Place place = local.containsKey(key) ? local.get(key) : bundled.get(key);
			if (place == null)
			{
				continue;
			}
			if ("quest".equals(place.type)
				&& !lower.contains("start " + key)
				&& !lower.contains("begin " + key)
				&& !lower.contains("do " + key)
				&& !lower.contains("complete " + key)
				&& !lower.contains("finish " + key))
			{
				continue;
			}
			return new WorldPoint(place.x, place.y, place.plane);
		}
		return null;
	}

	/**
	 * Wraps every known place name in the given ESCAPED html fragment with
	 * a bruh:place: link. Case-insensitive, longest name wins.
	 */
	public synchronized String linkify(String escapedHtml)
	{
		if (namePattern == null)
		{
			return escapedHtml;
		}
		Matcher matcher = namePattern.matcher(escapedHtml);
		StringBuffer sb = new StringBuffer();
		while (matcher.find())
		{
			String href = LINK_PREFIX + encode(matcher.group());
			matcher.appendReplacement(sb,
				Matcher.quoteReplacement("<a href='" + href + "'>" + matcher.group() + "</a>"));
		}
		matcher.appendTail(sb);
		return sb.toString();
	}

	private void rebuildPattern()
	{
		List<String> names = new ArrayList<>();
		bundled.values().forEach(p -> names.add(p.display));
		local.values().forEach(p -> names.add(p.display));
		if (names.isEmpty())
		{
			namePattern = null;
			return;
		}
		// Longest first so "Duke Horacio" beats a hypothetical "Duke".
		names.sort((a, b) -> b.length() - a.length());
		StringBuilder alternation = new StringBuilder();
		for (String name : names)
		{
			if (alternation.length() > 0)
			{
				alternation.append('|');
			}
			alternation.append(Pattern.quote(name));
		}
		namePattern = Pattern.compile("\\b(?:" + alternation + ")\\b",
			Pattern.CASE_INSENSITIVE);
	}

	private static String key(String name)
	{
		return name.toLowerCase(Locale.ROOT).trim();
	}

	private static String encode(String name)
	{
		try
		{
			return URLEncoder.encode(name, "UTF-8");
		}
		catch (UnsupportedEncodingException e)
		{
			throw new IllegalStateException(e); // UTF-8 always exists
		}
	}

	private interface ReaderSupplier
	{
		Reader open() throws IOException;
	}

	private Map<String, Place> read(ReaderSupplier source)
	{
		try
		{
			Reader reader = source.open();
			if (reader == null)
			{
				return new HashMap<>();
			}
			try (Reader r = reader)
			{
				PlaceFile file = gson.fromJson(r, PlaceFile.class);
				return file == null || file.places == null ? new HashMap<>() : file.places;
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Could not read places file", e);
			return new HashMap<>();
		}
	}

	private void save()
	{
		File dir = localFile.getParentFile();
		if (dir != null && !dir.exists() && !dir.mkdirs())
		{
			log.warn("Could not create {}", dir);
			return;
		}
		PlaceFile file = new PlaceFile();
		file.version = FILE_VERSION;
		file.places = local;
		try (Writer writer = new FileWriter(localFile))
		{
			gson.toJson(file, writer);
		}
		catch (IOException e)
		{
			log.warn("Could not save places to {}", localFile, e);
		}
	}

	/** On-disk: {"version":1,"places":{"duke horacio":{"display":"Duke Horacio","x":3210,...}}} */
	private static class PlaceFile
	{
		int version;
		Map<String, Place> places;
	}

	private static class Place
	{
		String display;
		/** "quest" for quest start points; null for NPCs/towns. */
		String type;
		int x;
		int y;
		int plane;
	}
}
