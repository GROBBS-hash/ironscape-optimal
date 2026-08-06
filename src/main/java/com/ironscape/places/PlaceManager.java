package com.ironscape.places;

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
 * the jar, overridden per name by ~/.runelite/ironscape/places.json.
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
		this(gson, new File(RuneLite.RUNELITE_DIR, "ironscape/places.json"));
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
		// Item SOURCES ("glarial's pebble" -> Golrie's cell) share the place
		// namespace: same links, same routing, plus a how-to note chatted on
		// click. Real places win a name clash.
		read(() -> {
			InputStream in = PlaceManager.class.getResourceAsStream("item_sources.json");
			return in == null ? null : new InputStreamReader(in, StandardCharsets.UTF_8);
		}).forEach(bundled::putIfAbsent);
		local = read(() -> localFile.exists() ? new FileReader(localFile) : null);
		rebuildPattern();
		loadQuestGivers();
		loadShopKeepers();
		log.debug("Places loaded: {} bundled, {} local, {} quest givers, {} shop keepers",
			bundled.size(), local.size(), questGivers.size(), shopKeepers.size());
	}

	/** False when this source's vendor is an OBJECT — no NPC nomination. */
	public synchronized boolean sourceNominatesNpc(String name)
	{
		Place place = local.get(key(name));
		if (place == null)
		{
			place = bundled.get(key(name));
		}
		return place == null || !Boolean.FALSE.equals(place.npc);
	}

	/** The item source's how-to note, or null for ordinary places. */
	public synchronized String note(String name)
	{
		Place place = local.get(key(name));
		if (place == null)
		{
			place = bundled.get(key(name));
		}
		return place == null ? null : place.note;
	}

	/** Quest name (lowercase) -> the NPC who starts it (wiki-seeded, bundled). */
	private Map<String, String> questGivers = new HashMap<>();

	/** The NPC who starts this quest, or null when unknown. */
	public synchronized String questGiver(String questName)
	{
		return questGivers.get(questName.toLowerCase(Locale.ROOT));
	}

	private void loadQuestGivers()
	{
		try (InputStream in = PlaceManager.class.getResourceAsStream("quest_givers.json"))
		{
			if (in == null)
			{
				return;
			}
			GiversFile file = gson.fromJson(
				new InputStreamReader(in, StandardCharsets.UTF_8), GiversFile.class);
			if (file != null && file.givers != null)
			{
				questGivers = file.givers;
			}
		}
		catch (Exception e)
		{
			log.warn("Could not read bundled quest givers", e);
		}
	}

	private static class GiversFile
	{
		int version;
		Map<String, String> givers;
	}

	/** Step id -> the shopkeeper NPC of that buy-step's shop (wiki-seeded). */
	private Map<String, String> shopKeepers = new HashMap<>();

	/**
	 * The NPC who runs the shop this buy step targets, or null when
	 * unknown. Named beats nearest: the pin's closest NPC was crowning
	 * the Master Farmer with the compost icon while Richard ran the shop
	 * four tiles away (owner report 2026-08-05).
	 */
	public synchronized String shopKeeper(String stepId)
	{
		return shopKeepers.get(stepId);
	}

	private void loadShopKeepers()
	{
		try (InputStream in = PlaceManager.class.getResourceAsStream("shop_npcs.json"))
		{
			if (in == null)
			{
				return;
			}
			KeepersFile file = gson.fromJson(
				new InputStreamReader(in, StandardCharsets.UTF_8), KeepersFile.class);
			if (file != null && file.keepers != null)
			{
				shopKeepers = file.keepers;
			}
		}
		catch (Exception e)
		{
			log.warn("Could not read bundled shop keepers", e);
		}
	}

	private static class KeepersFile
	{
		int version;
		Map<String, String> keepers;
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

	/**
	 * Like get(), but forgiving about directional prefixes — the guide's
	 * authored location tags say "West of Lumbridge" or "South-west of
	 * Castle Wars"; the base place is close enough for navigation.
	 */
	public synchronized WorldPoint getLoose(String name)
	{
		WorldPoint exact = get(name);
		if (exact != null)
		{
			return exact;
		}
		String stripped = name.replaceFirst(
			"(?i)^(?:north|south|east|west)(?:[ -](?:east|west))?\\s+of\\s+", "");
		return stripped.equals(name) ? null : get(stripped);
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
		String lower = text.toLowerCase(Locale.ROOT).replace('’', '\'');
		Matcher matcher = namePattern.matcher(text);
		while (matcher.find())
		{
			String key = key(matcher.group());
			Place place = local.containsKey(key) ? local.get(key) : bundled.get(key);
			if (place == null)
			{
				continue;
			}
			// Transport networks ("spirit tree") are CLICK affordances that
			// route to the nearest one — never automatic targets: journeys
			// START next to one, so auto-targeting (and arrival detection!)
			// would misfire on the spot the player is leaving from.
			if ("transport".equals(place.type))
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
	 * The LAST recognised place in the text — the destination of a travel
	 * sub: "Home tele to Lumbridge and run north to Varrock east bank"
	 * ends at Varrock east bank, not Lumbridge. Same quest-name filtering
	 * as firstPlaceIn.
	 */
	public synchronized WorldPoint lastPlaceIn(String text)
	{
		if (namePattern == null)
		{
			return null;
		}
		String lower = text.toLowerCase(Locale.ROOT).replace('’', '\'');
		Matcher matcher = namePattern.matcher(text);
		WorldPoint last = null;
		while (matcher.find())
		{
			String key = key(matcher.group());
			Place place = local.containsKey(key) ? local.get(key) : bundled.get(key);
			if (place == null)
			{
				continue;
			}
			// Same transport-network exclusion as firstPlaceIn.
			if ("transport".equals(place.type))
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
			last = new WorldPoint(place.x, place.y, place.plane);
		}
		return last;
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
			// normalized key in the href, original spelling as the label
			String href = LINK_PREFIX + encode(key(matcher.group()));
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
		// Longest first so "Romeo & Juliet" beats "Romeo".
		names.sort((a, b) -> b.length() - a.length());
		StringBuilder alternation = new StringBuilder();
		for (String name : names)
		{
			if (alternation.length() > 0)
			{
				alternation.append('|');
			}
			alternation.append(tolerantPattern(name));
		}
		namePattern = Pattern.compile("\\b(?:" + alternation + ")\\b",
			Pattern.CASE_INSENSITIVE);
	}

	/**
	 * A regex for one name that tolerates the punctuation drift between
	 * the wiki, the guide's Google-Docs text, and HTML escaping:
	 * both apostrophes (' and ’), and & as either "&" or "&amp;".
	 */
	private static String tolerantPattern(String name)
	{
		StringBuilder sb = new StringBuilder();
		for (char c : name.toCharArray())
		{
			if (Character.isLetterOrDigit(c) || c == ' ')
			{
				sb.append(c);
			}
			else if (c == '\'' || c == '’')
			{
				sb.append("['’]");
			}
			else if (c == '&')
			{
				sb.append("(?:&amp;|&)");
			}
			else
			{
				sb.append(Pattern.quote(String.valueOf(c)));
			}
		}
		return sb.toString();
	}

	/**
	 * [start, end) span of every place name occurring in the text — lets
	 * the NPC matcher ignore an NPC name that's really part of a place
	 * ("Barbarian" inside "Barbarian Village").
	 */
	public synchronized List<int[]> placeSpans(String text)
	{
		List<int[]> spans = new ArrayList<>();
		if (namePattern == null)
		{
			return spans;
		}
		Matcher matcher = namePattern.matcher(text);
		while (matcher.find())
		{
			spans.add(new int[]{matcher.start(), matcher.end()});
		}
		return spans;
	}

	/** Normalized lookup key: lowercase, straight apostrophes, raw ampersand. */
	private static String key(String name)
	{
		return name.toLowerCase(Locale.ROOT).trim()
			.replace('’', '\'')
			.replace("&amp;", "&");
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
		/** Item sources only: HOW to get it, chatted when the link routes. */
		String note;
		/**
		 * Item sources only, false = the vendor is an OBJECT (a chest, a
		 * dig spot) — the nearest-NPC anchor must not crown a bystander
		 * (the Lumbridge Cook wore the milk icon for the Culinaromancer's
		 * Chest). Null/absent = NPC nomination allowed.
		 */
		Boolean npc;
	}
}
