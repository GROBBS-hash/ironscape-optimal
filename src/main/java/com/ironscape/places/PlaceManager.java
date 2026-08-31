package com.ironscape.places;

import com.google.gson.Gson;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
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

	/** Explicit local file — tests and audit dumps point this at a scratch path. */
	public PlaceManager(Gson gson, File localFile)
	{
		this.gson = gson.newBuilder().setPrettyPrinting().create();
		this.localFile = localFile;
	}

	public synchronized void load()
	{
		bundled = read(() -> {
			InputStream in = com.ironscape.DataFiles.open(PlaceManager.class, "places.json");
			return in == null ? null : new InputStreamReader(in, StandardCharsets.UTF_8);
		});
		// Item SOURCES ("glarial's pebble" -> Golrie's cell) share the place
		// namespace: same links, same routing, plus a how-to note chatted on
		// click. Real places win a name clash.
		read(() -> {
			InputStream in = com.ironscape.DataFiles.open(PlaceManager.class, "item_sources.json");
			return in == null ? null : new InputStreamReader(in, StandardCharsets.UTF_8);
		}).forEach(bundled::putIfAbsent);
		local = read(() -> localFile.exists() ? new FileReader(localFile) : null);
		rebuildPattern();
		loadQuestGivers();
		loadShopKeepers();
		log.debug("Places loaded: {} bundled, {} local, {} quest givers, {} shop keepers",
			bundled.size(), local.size(), questGivers.size(), shopKeepers.size());
	}

	/** The NPC who sells this item, by name, or null. */
	public synchronized String sourceVendor(String name)
	{
		Place place = local.get(key(name));
		if (place == null)
		{
			place = bundled.get(key(name));
		}
		return place == null ? null : place.vendor;
	}

	/** The scene object vending this item (lowercase), or null. */
	public synchronized String sourceObject(String name)
	{
		Place place = local.get(key(name));
		if (place == null)
		{
			place = bundled.get(key(name));
		}
		return place == null ? null : place.object;
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
		try (InputStream in = com.ironscape.DataFiles.open(PlaceManager.class, "quest_givers.json"))
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
		try (InputStream in = com.ironscape.DataFiles.open(PlaceManager.class, "shop_npcs.json"))
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
	 * Is this name a place you TRAVEL to, as opposed to a quest?
	 *
	 * Quest start points live in the same namespace so a quest name can be
	 * clicked for a route, but they are not destinations in their own
	 * right: the guide has bare steps reading "Cabin fever" and "One small
	 * favour", and treating those as "be here" would let walking past the
	 * giver tick off a whole quest. The type field already records the
	 * difference, so this is a data question rather than a guess.
	 */
	public synchronized boolean isTravelDestination(String name)
	{
		Place place = local.get(key(name));
		if (place == null)
		{
			place = bundled.get(key(name));
		}
		return place != null && !"quest".equals(place.type) && !"transport".equals(place.type);
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
		return firstPlaceIn(text, false);
	}

	/**
	 * firstPlaceIn for the SHOPKEEPER anchor: additionally skips
	 * object-vendor sources (npc:false — a chest, a dig spot), so their
	 * doorway bystanders never get nominated (the Cook wore the milk
	 * icon because "buckets of milk" in the step text matched the
	 * trapdoor source).
	 */
	public synchronized WorldPoint firstNominatingPlaceIn(String text)
	{
		return firstPlaceIn(text, true);
	}

	private synchronized WorldPoint firstPlaceIn(String text, boolean forNpcNomination)
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
			if (forNpcNomination && Boolean.FALSE.equals(place.npc))
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
		Place place = lastPlaceMatchIn(text);
		return place == null ? null : new WorldPoint(place.x, place.y, place.plane);
	}

	/**
	 * The DISPLAY name of the place {@link #lastPlaceIn} answers with, so a
	 * control can SAY where it is about to send you instead of making the
	 * reader infer it from the sentence.
	 *
	 * <p>Shares its body with lastPlaceIn rather than repeating the matching
	 * rules, which are subtle enough (transport networks excluded, quest
	 * places only after a doing-verb) that two copies would drift.
	 */
	public synchronized String lastPlaceNameIn(String text)
	{
		Place place = lastPlaceMatchIn(text);
		return place == null ? null : place.display;
	}

	private Place lastPlaceMatchIn(String text)
	{
		if (namePattern == null)
		{
			return null;
		}
		String lower = text.toLowerCase(Locale.ROOT).replace('\u2019', '\'');
		Matcher matcher = namePattern.matcher(text);
		Place last = null;
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
			last = place;
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

	/**
	 * Percent-encode a place key so it can ride inside a Swing HTML href,
	 * which StepRow decodes when the link is clicked. Purely internal: this
	 * string never leaves the client and no request is ever made with it.
	 *
	 * Hand-rolled rather than java.net.URLEncoder because the plugin hub's
	 * automated review reasonably treats a java.net import as evidence of
	 * networking, and this plugin does none — it was costing us the fast
	 * lane for a string utility we can write in ten lines.
	 *
	 * Encodes every byte outside [A-Za-z0-9], so the output is always plain
	 * ASCII and safe in an attribute.
	 */
	public static String encode(String name)
	{
		StringBuilder out = new StringBuilder();
		for (byte raw : name.getBytes(StandardCharsets.UTF_8))
		{
			int b = raw & 0xFF;
			boolean unreserved = (b >= 'a' && b <= 'z')
				|| (b >= 'A' && b <= 'Z')
				|| (b >= '0' && b <= '9');
			if (unreserved)
			{
				out.append((char) b);
			}
			else
			{
				out.append('%');
				out.append(Character.toUpperCase(Character.forDigit(b >> 4, 16)));
				out.append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
			}
		}
		return out.toString();
	}

	/**
	 * Reverse of {@link #encode}: read a link payload back out of the href
	 * it was embedded in. Both halves live here so they cannot drift apart —
	 * a mismatch would silently break every place link in the panel.
	 */
	public static String decode(String encoded)
	{
		java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
		for (int i = 0; i < encoded.length(); i++)
		{
			char c = encoded.charAt(i);
			if (c == '%' && i + 2 < encoded.length())
			{
				int hi = Character.digit(encoded.charAt(i + 1), 16);
				int lo = Character.digit(encoded.charAt(i + 2), 16);
				if (hi >= 0 && lo >= 0)
				{
					bytes.write((hi << 4) | lo);
					i += 2;
					continue;
				}
			}
			bytes.write(c);
		}
		return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
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
		/**
		 * Item sources only: the SCENE OBJECT that vends the item
		 * (lowercase, e.g. "culinaromancer's chest") — outlined with the
		 * goal item overhead while the goal is unmet, the object-vendor
		 * counterpart of the shopkeeper outline.
		 */
		String object;
		/**
		 * Item sources only: the NPC who sells it, BY NAME ("Diango") —
		 * named outlines never fall back to nearest-to-pin (a Market
		 * Guard walked past the stall and wore the card icon).
		 */
		String vendor;
	}
}
