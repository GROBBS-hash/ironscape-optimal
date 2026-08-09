package com.ironscape.items;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.RuneLite;
import net.runelite.client.game.ItemManager;

/**
 * Knows how many of each item the player owns: inventory + equipment live,
 * plus the bank as of the last time it was opened.
 *
 * The bank can only be read while its interface is open (a game
 * limitation every plugin shares), so we snapshot it on every bank visit
 * and persist the snapshot per game account — the same approach the Bank
 * Memory plugin uses. Counts are keyed by lowercase item NAME, because
 * step annotations name items in prose, not by id.
 *
 * All game reads happen on the client thread (the plugin forwards events
 * here); the Swing panel only reads the finished name->count map.
 */
@Slf4j
@Singleton
public class ItemTracker
{
	private final Client client;
	private final ItemManager itemManager;
	private final Gson gson;

	/** inventory + equipment + bank snapshot, by lowercase item name. Guarded by `this`. */
	private final Map<String, Integer> ownedByName = new HashMap<>();

	/** inventory + equipment only (what you have ON you). Guarded by `this`. */
	private final Map<String, Integer> carriedByName = new HashMap<>();

	/** EQUIPMENT only — "Equip Gas mask" is done when it's worn, not owned. */
	private final Map<String, Integer> wornByName = new HashMap<>();

	/** Bank snapshot by lowercase item name (client thread only). */
	private Map<String, Integer> bankByName = new HashMap<>();

	/** Which account the current bank snapshot belongs to. */
	private long accountHash = -1;

	@Inject
	public ItemTracker(Client client, ItemManager itemManager, Gson gson)
	{
		this.client = client;
		this.itemManager = itemManager;
		this.gson = gson;
	}

	/** How many of this item the player owns anywhere (0 if none or unknown name). Safe from any thread. */
	public synchronized int countOf(String name)
	{
		return resolve(ownedByName, name);
	}

	/** How many the player has ON them (inventory + worn), excluding the bank. */
	public synchronized int carriedCountOf(String name)
	{
		return resolve(carriedByName, name);
	}

	/** How many the player is WEARING — carrying one in the bag doesn't count. */
	public synchronized int wornCountOf(String name)
	{
		return resolve(wornByName, name);
	}

	/**
	 * Items that COUNT AS another item — a Bruma torch lights braziers
	 * and fires, so "tinderbox 0/1" must go green for the player who
	 * upgraded. One-directional: the torch satisfies tinderbox needs,
	 * never the other way around.
	 */
	/** Every metal tier of a tool: bare "pickaxe" means ANY pickaxe. */
	private static String[] tiers(String tool)
	{
		String[] metals = {"bronze", "iron", "steel", "black", "mithril", "adamant", "rune", "dragon"};
		String[] out = new String[metals.length];
		for (int i = 0; i < metals.length; i++)
		{
			out[i] = metals[i] + " " + tool;
		}
		return out;
	}

	private static final Map<String, String[]> SUBSTITUTES = Map.ofEntries(
		Map.entry("tinderbox", new String[]{"bruma torch"}),
		Map.entry("hammer", new String[]{"imcando hammer"}),
		// Bare "gloves"/"boots" in the guide mean the basic leather ones
		// the clothes shops sell.
		Map.entry("glove", new String[]{"leather gloves"}),
		Map.entry("gloves", new String[]{"leather gloves"}),
		Map.entry("boot", new String[]{"leather boots"}),
		Map.entry("boots", new String[]{"leather boots"}),
		// An unqualified tool means any tier you own ("take out pickaxe").
		Map.entry("pickaxe", tiers("pickaxe")),
		Map.entry("pickaxes", tiers("pickaxe")),
		Map.entry("axe", tiers("axe")),
		Map.entry("axes", tiers("axe")));

	/**
	 * Bare family words: "runes" means the runes you bought, "bars" the
	 * bars you smelted, "beads" the four imp bead colours — no single
	 * item can match, so anything in the family counts. Suffixes are in
	 * CANONICAL (singular) form and members are matched canonically,
	 * because the game names some families singular ("Black bead") and
	 * others plural ("Steel nails").
	 */
	private static final Map<String, String> FAMILY_SUFFIX = Map.of(
		"runes", " rune",
		"all runes", " rune",
		"all of your runes", " rune",
		"bars", " bar",
		"beads", " bead",
		"nails", " nail",
		"nail", " nail");

	private static int resolve(Map<String, Integer> counts, String name)
	{
		String suffix = FAMILY_SUFFIX.get(name.toLowerCase(Locale.ROOT).trim());
		if (suffix != null)
		{
			int total = 0;
			for (Map.Entry<String, Integer> entry : counts.entrySet())
			{
				if (canonical(entry.getKey()).endsWith(suffix))
				{
					total += entry.getValue();
				}
			}
			return total;
		}
		String[] chain = aliases(name);
		Integer base = null;
		for (String candidate : chain)
		{
			Integer count = counts.get(candidate);
			if (count != null)
			{
				base = count;
				break;
			}
		}
		// Even with zero of the item itself, a substitute still counts —
		// look substitutes up under every alias, not just the matched one.
		int substitutes = 0;
		for (String candidate : chain)
		{
			String[] alternates = SUBSTITUTES.get(candidate);
			if (alternates == null)
			{
				continue;
			}
			for (String alternate : alternates)
			{
				substitutes += counts.getOrDefault(alternate, 0);
			}
			break;
		}
		// Last resort: canonical comparison that forgives apostrophes,
		// possessives and plural drift — the guide writes "wizard mind
		// bombs", the item is "Wizard's mind bomb". Exact/alias/substitute
		// matches above always win.
		if (base == null && substitutes == 0)
		{
			// canonical of the RESOLVED key (chain[0]), not the raw name.
			// aliases() is where "gp"->coins, "noted X"->X, teletab->tab
			// and the whole COLLOQUIAL map get applied, so measuring the
			// raw name here meant a colloquial could never reach this
			// fallback: "super antipoison" -> "superantipoison" was a
			// dead entry against the carried "Superantipoison(1)", which
			// is what the new dose test caught on its very first run.
			String want = canonical(chain.length > 0 ? chain[0] : name);
			// A goal that NAMES a dose means that dose. canonical() folds
			// "(4)" away so any dose satisfies any other, which is right
			// for "drink a restore potion" and wrong for "decant them
			// until you have 6 full pots" — twenty-four 1-dose vials would
			// read 6/6 while the step's whole job is turning them into
			// four-dose ones. Dose-less goals are untouched: this only
			// engages when the annotation spells the dose out.
			String wantDose = doseOf(name);
			if (!want.isEmpty())
			{
				for (Map.Entry<String, Integer> entry : counts.entrySet())
				{
					if (!canonical(entry.getKey()).equals(want))
					{
						continue;
					}
					if (wantDose != null && !wantDose.equals(doseOf(entry.getKey())))
					{
						continue;
					}
					return entry.getValue();
				}
			}
		}
		return (base == null ? 0 : base) + substitutes;
	}

	/**
	 * Does a carried item (by its in-game name) satisfy a guide goal name?
	 * Full matching stack: aliases, substitutes (any pickaxe tier),
	 * family sums and canonical spelling drift. Used by the inventory
	 * hint overlay to light up the step's items.
	 */
	public static boolean nameMatchesGoal(String itemName, String goalName)
	{
		return resolve(Map.of(itemName.toLowerCase(Locale.ROOT), 1), goalName) > 0;
	}

	/** The "(4)" of "Superantipoison(4)", or null when it names no dose. */
	private static String doseOf(String name)
	{
		java.util.regex.Matcher m = DOSE.matcher(name);
		return m.find() ? m.group(1) : null;
	}

	private static final java.util.regex.Pattern DOSE =
		java.util.regex.Pattern.compile("\\((\\d+)\\)");

	/**
	 * Spelling-drift-proof form of an item name: lowercase, punctuation
	 * gone (so possessive 's collapses), every word depluralized.
	 * "Wizard's mind bomb" and "wizard mind bombs" both become
	 * "wizard mind bomb".
	 */
	// Public so the audit dump can emit the REAL canonical forms rather
	// than a second copy of this method (com.ironscape.goals is a
	// different package). Pure function; nothing else changes.
	public static String canonical(String name)
	{
		StringBuilder sb = new StringBuilder();
		// Numeric potion doses collapse: "Restore potion(3)" and the
		// guide's "restore potion" are the same thing, any dose counts.
		// Non-numeric parens ("(unf)") are DIFFERENT items and stay.
		for (String word : name.toLowerCase(Locale.ROOT).replaceAll("\\(\\d+\\)", "")
			.replaceAll("[^a-z0-9 ]", "").split("\\s+"))
		{
			if (word.isEmpty())
			{
				continue;
			}
			// Irregular -ves plurals fold to their -f singular: leaves ->
			// leaf; knives and knife both land on "knif".
			if (word.length() > 4 && word.endsWith("ves"))
			{
				word = word.substring(0, word.length() - 3) + "f";
			}
			else if (word.length() > 3 && word.endsWith("fe"))
			{
				word = word.substring(0, word.length() - 1);
			}
			// "glass"/"grass" keep their s; short words like "gp" too.
			else if (word.length() > 3 && word.endsWith("s") && !word.endsWith("ss"))
			{
				word = word.substring(0, word.length() - 1);
			}
			if (sb.length() > 0)
			{
				sb.append(' ');
			}
			sb.append(word);
		}
		return sb.toString();
	}

	/** Guide slang -> the item's real in-game name. */
	private static final Map<String, String> COLLOQUIAL = Map.ofEntries(
		Map.entry("poh tab", "teleport to house (tablet)"),
		Map.entry("poh tabs", "teleport to house (tablet)"),
		Map.entry("house tab", "teleport to house (tablet)"),
		Map.entry("house tabs", "teleport to house (tablet)"),
		Map.entry("varrock tab", "varrock teleport (tablet)"),
		Map.entry("falador tab", "falador teleport (tablet)"),
		Map.entry("fally tab", "falador teleport (tablet)"),
		Map.entry("lumbridge tab", "lumbridge teleport (tablet)"),
		Map.entry("lumby tab", "lumbridge teleport (tablet)"),
		Map.entry("camelot tab", "camelot teleport (tablet)"),
		Map.entry("ardougne tab", "ardougne teleport (tablet)"),
		Map.entry("ardy tab", "ardougne teleport (tablet)"),
		// The POH tab's in-game name is "Teleport to house"; the guide also
		// says "house teleport". Redirected tabs (scroll of redirection) are
		// literally named "<Place> teleport" in game — wiki-confirmed.
		Map.entry("house teleport", "teleport to house (tablet)"),
		Map.entry("house teleports", "teleport to house (tablet)"),
		Map.entry("redirected poh tab", "teleport to house (tablet)"),
		Map.entry("rimmington tab", "rimmington teleport"),
		Map.entry("taverley tab", "taverley teleport"),
		Map.entry("pollnivneach tab", "pollnivneach teleport"),
		Map.entry("hosidius tab", "hosidius teleport"),
		Map.entry("rellekka tab", "rellekka teleport"),
		Map.entry("brimhaven tab", "brimhaven teleport"),
		Map.entry("yanille tab", "yanille teleport"),
		Map.entry("trollheim tab", "trollheim teleport"),
		Map.entry("prifddinas tab", "prifddinas teleport"),
		Map.entry("catherby tab", "catherby teleport (tablet)"),
		// The sawmill's "regular plank" is the item just called "Plank".
		Map.entry("regular plank", "plank"),
		Map.entry("regular planks", "plank"),
		// Guide shorthand the full-guide audit flagged (tools/audit-goals.mjs).
		Map.entry("wine", "jug of wine"),
		Map.entry("wines", "jug of wine"),
		Map.entry("teleports", "teleport card"),
		Map.entry("chocolate", "chocolate bar"),
		Map.entry("dueling ring", "ring of dueling"),
		Map.entry("dueling rings", "ring of dueling"),
		Map.entry("soft leather", "leather"),
		Map.entry("priest robes", "priest gown (top)"),
		Map.entry("silver", "silver bar"),
		// The farming shop's pack of normal compost is just "Compost pack".
		Map.entry("normal compost pack", "compost pack"),
		// Shops sell "Pot of flour"; the bare item "Flour" exists but is
		// unobtainable — which is why the existence audit passed it.
		Map.entry("flour", "pot of flour"),
		Map.entry("flours", "pot of flour"),
		// The black hat Betty sells is named, in game, just "Wizard hat"
		// (id 1017) — the black one is the plain one, and every hat that
		// SAYS black ("Black wizard hat (g)/(t)", 12453/12455) is a trim
		// variant the guide does not mean. So the guide's phrase is a
		// DESCRIPTION, not a name, and no amount of plural handling
		// bridges it.
		//
		// Two things conspired to hide that. item_ids maps the phrase to
		// the right id, so the badge wore a correct SPRITE while counting
		// matched by NAME and found nothing (the copper-ore shape again,
		// owner in play). And the id's gameval constant is BLACKWIZHAT,
		// which reads like a real item name and disagrees with the item's
		// actual display name — checking the constant CONFIRMS the wrong
		// answer. The live item mapping (prices.runescape.wiki) is the
		// authority on names; the constant is not.
		Map.entry("black wizards hat", "wizard hat"),
		// Four more of the same shape, found by cross-checking every
		// item_ids id against the live item mapping rather than against
		// the gameval constant. All four are LIVE goals whose badge would
		// have shown the right sprite over a count frozen at 0, and none
		// had been reported because nobody has stood there holding one.
		Map.entry("green d'hide top", "green d'hide body"),
		Map.entry("mithril grapple", "mith grapple"),
		Map.entry("armor seeds", "crystal armour seed"),
		Map.entry("enhanced weapon seeds", "enhanced crystal weapon seed"),
		// The item is "Superantipoison(1)" — ONE word. canonical() strips
		// the dose but not the space, so the guide's "super antipoison"
		// misses it by a single character.
		Map.entry("super antipoison", "superantipoison"),
		Map.entry("super antipoisons", "superantipoison"),
		// Reviewed in build/item-names-review.html (owner, 2026-08-09).
		// Every one had the right id and sprite and counted nothing.
		Map.entry("d bones", "dragon bones"),
		Map.entry("dragon scim", "dragon scimitar"),
		Map.entry("rune scim", "rune scimitar"),
		Map.entry("empty buckets", "bucket"),
		Map.entry("maples", "maple logs"),
		Map.entry("nats", "nature rune"),
		Map.entry("normal compost", "compost"),
		Map.entry("normal log", "logs"),
		Map.entry("red chins", "red chinchompa"),
		Map.entry("willow branches", "willow branch"),
		Map.entry("lantern lenses", "lantern lens"),
		// Approved as "Agility potion(1)" but deliberately seeded WITHOUT
		// the dose. The review page offers the id's exact name, and that
		// id happens to be the 1-dose one — but the guide's "agility pots"
		// means the potion, any dose, and the new dose rule would have
		// read a stated "(1)" as "one-dose only", quietly making the badge
		// stricter than the sentence.
		Map.entry("agility pots", "agility potion"),
		// Second review pass (owner, 2026-08-09) — the five the first page
		// left open. Same shape as every row above: right id, right
		// sprite, a count that could never leave 0.
		Map.entry("b gloves", "barrows gloves"),
		Map.entry("dramen branches", "dramen branch"),
		Map.entry("pack of normal compost", "compost pack"),
		Map.entry("translation notes", "translated notes"),
		// "Get range void from PC" buys the whole ranged set, but only the
		// helm is a distinct item name to count — the top and robe are
		// shared with the melee/mage sets. So this counts the piece that
		// identifies the set, not the set.
		Map.entry("range void", "void ranger helm"));

	/**
	 * The in-game item names a guide phrase might refer to, most literal
	 * first. The guide abbreviates: "100 mind" means Mind runes, "1 log" is
	 * the item "Logs", "2 buckets" is the item "Bucket", "bolts of cloth"
	 * is "Bolt of cloth", "POH tab" is "Teleport to house". Also used by
	 * the bank filter.
	 */
	public static String[] aliases(String name)
	{
		String key = name.toLowerCase(Locale.ROOT).trim();
		// "fire staff (equip)" — the parenthetical is an instruction to
		// wear it, not part of the item name. Doses like "(1)" stay.
		key = key.replaceFirst("\\s*\\((?:equip(?:ped)?|wear|worn)\\)$", "");
		// noted items canonicalize to the real item when counting, so
		// "noted planks" is just "planks"
		if (key.startsWith("noted "))
		{
			key = key.substring("noted ".length());
		}
		// the guide says gp/gold/cash; the item is "Coins"
		if (key.equals("gp") || key.equals("gold") || key.equals("cash") || key.equals("money"))
		{
			key = "coins";
		}
		// "Buy 20 teleports for chronicle": the item is a "Teleport card".
		if (key.equals("teleports") || key.equals("teleport cards"))
		{
			key = "teleport card";
		}
		// Traiborn's bone-payment key is a "Silverlight key" (Demon Slayer).
		if (key.equals("demon slayer key"))
		{
			key = "silverlight key";
		}
		// Rune Mysteries' hand-ins are "Research package" and "Research
		// notes" in-game; kits call them by the quest's name (owner-hit).
		if (key.equals("rune mysteries notes"))
		{
			key = "research notes";
		}
		if (key.equals("rune mysteries package"))
		{
			key = "research package";
		}
		// Prince Ali's colloquials: the crafted items are literally named
		// "Paste" and "Key print" — the guide (and everyone) says otherwise.
		if (key.equals("skin paste"))
		{
			key = "paste";
		}
		if (key.equals("key imprint"))
		{
			key = "key print";
		}
		// "few cakes" / "plenty of stews" are cakes and stews — annotation
		// item names carry the guide's quantifier words; strip them like
		// the goal detector does or the count never matches anything.
		key = key.replaceFirst("^(?:a )?(?:few|couple|plenty|some|bunch) (?:of )?", "");
		// "house teletabs" / "fally teletab" are the same words the
		// COLLOQUIAL map already knows as "house tabs" / "fally tab".
		key = key.replace("teletab", "tab");
		// the guide says "arrowheads"; smithable metal ones are named
		// "arrowtips" in game. Metal-specific on purpose: "Broad
		// arrowheads" really is called arrowheads.
		key = key.replaceFirst(
			"^(bronze|iron|steel|mithril|adamant|rune|amethyst) arrowheads?$", "$1 arrowtips");
		String colloquial = COLLOQUIAL.get(key);
		if (colloquial != null)
		{
			key = colloquial;
		}
		String singular = key.endsWith("s") ? key.substring(0, key.length() - 1) : key;

		// "bolts of cloth" -> "bolt of cloth": the plural sits on the FIRST
		// word in of-phrases, so depluralize that too.
		String[] words = key.split(" ", 2);
		String firstSingular = words[0].endsWith("s")
			? words[0].substring(0, words[0].length() - 1) + (words.length > 1 ? " " + words[1] : "")
			: key;

		java.util.List<String> out = new java.util.ArrayList<>(java.util.List.of(
			key,             // exact:      "mind rune"
			singular,        // deplural:   "buckets" -> "bucket"
			firstSingular,   // of-phrase:  "bolts of cloth" -> "bolt of cloth"
			key + "s",       // plural:     "log" -> "logs"
			key + " rune",   // elemental:  "mind" -> "mind rune"
			singular + " rune", // "minds" -> "mind rune"
			// ORES, the same shorthand one metal over: everybody says
			// "grab 4 copper", the item is "Copper ore", and the badge sat
			// at 0/4 beside a copper-ore ICON — because item_ids gave the
			// name a sprite while counting matched by name and found
			// nothing (owner, in play). Safe for the same reason the rune
			// alias is: it only ever resolves if that item really exists.
			key + " ore",
			singular + " ore"));
		// Shop packs: the guide says "bucket pack", the item is "Empty
		// bucket pack". (Feather/bait packs really are named "X pack",
		// so the un-prefixed key stays first.)
		if (key.endsWith(" pack"))
		{
			out.add("empty " + key);
		}
		// Elemental staves: the guide says "fire staff", the item is
		// "Staff of fire". (Battlestaves really are "Fire battlestaff",
		// caught by the exact key.)
		String stem = singular.endsWith(" staff")
			? singular.substring(0, singular.length() - " staff".length()) : null;
		if (stem != null && java.util.Set.of("fire", "water", "air", "earth").contains(stem))
		{
			out.add("staff of " + stem);
		}
		// Irregular -ves plurals: "woad leaves" is the item "Woad leaf",
		// "knives" is "Knife".
		if (key.endsWith("ves"))
		{
			String base = key.substring(0, key.length() - 3);
			out.add(base + "f");
			out.add(base + "fe");
		}
		// "rune pick" is a rune pickaxe; "varrock armor" is spelt armour.
		if (singular.endsWith(" pick"))
		{
			out.add(singular + "axe");
		}
		if (key.contains("armor"))
		{
			out.add(key.replace("armor", "armour"));
		}
		// Last resort: drop a trailing parenthetical — annotation authors
		// write "bones (kill goblins south of the jail)". Runs after the
		// literal candidates so real parens ("super antipoison(1)") win.
		String noParen = key.replaceFirst("\\s*\\([^)]*\\)$", "").trim();
		if (!noParen.equals(key) && !noParen.isEmpty())
		{
			out.add(noParen);
			out.add(noParen.endsWith("s") ? noParen.substring(0, noParen.length() - 1) : noParen + "s");
		}
		return out.toArray(new String[0]);
	}

	/** Resolved icon item ids by guide item name; -1 = no icon found. */
	private final Map<String, Integer> iconIdByName = new HashMap<>();

	/**
	 * Bundled name -> item id map seeded from the OSRS Wiki
	 * (tools/seed-item-ids.mjs). The price-list search below only knows
	 * TRADEABLE items; this covers quest items and other untradeables so
	 * they get sprites too. Loaded lazily on first icon lookup.
	 */
	private Map<String, Integer> bundledItemIds;

	private Map<String, Integer> bundledItemIds()
	{
		if (bundledItemIds == null)
		{
			bundledItemIds = new HashMap<>();
			try (java.io.InputStream in = ItemTracker.class.getResourceAsStream("item_ids.json"))
			{
				if (in != null)
				{
					Map<String, Double> parsed = gson.fromJson(
						new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8),
						new TypeToken<Map<String, Double>>()
						{
						}.getType());
					parsed.forEach((name, id) -> bundledItemIds.put(name, id.intValue()));
				}
			}
			catch (IOException | RuntimeException e)
			{
				log.warn("Could not read bundled item ids", e);
			}
		}
		return bundledItemIds;
	}

	/**
	 * Puts the item's sprite on a Swing label (async — RuneLite fills the
	 * image in when loaded). Names resolve through the same alias chain
	 * the counters use, against the client's item price list; quest-only
	 * untradeables simply get no icon.
	 */
	public void attachIcon(String name, javax.swing.JLabel label)
	{
		int id = iconIdFor(name);
		if (id > 0)
		{
			itemManager.getImage(id).addTo(label);
		}
	}

	/** The item id whose sprite represents this guide item name; -1 = none found. */
	public synchronized int iconIdFor(String name)
	{
		return iconIdByName.computeIfAbsent(
			name.toLowerCase(Locale.ROOT).trim(), this::lookupIconId);
	}

	/**
	 * Named item sets bundled from the wiki (items/gear_sets.json) —
	 * "warm clothing" is the Wintertodt list. Loaded lazily.
	 */
	private Map<String, List<String>> gearSets;

	private Map<String, List<String>> gearSets()
	{
		if (gearSets == null)
		{
			gearSets = new HashMap<>();
			try (java.io.InputStream in = ItemTracker.class.getResourceAsStream("gear_sets.json"))
			{
				if (in != null)
				{
					GearSetsFile parsed = gson.fromJson(
						new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8),
						GearSetsFile.class);
					if (parsed != null && parsed.sets != null)
					{
						gearSets = parsed.sets;
					}
				}
			}
			catch (IOException | RuntimeException e)
			{
				log.warn("Could not read bundled gear sets", e);
			}
		}
		return gearSets;
	}

	private static class GearSetsFile
	{
		int version;
		Map<String, List<String>> sets;
	}

	/**
	 * How many DISTINCT items of the named set the player carries or wears
	 * (bank excluded — the check is "am I equipped for this right now").
	 */
	public int distinctCarried(String setName)
	{
		int have = 0;
		for (String name : gearSets().getOrDefault(setName, Collections.emptyList()))
		{
			if (carriedCountOf(name) > 0)
			{
				have++;
			}
		}
		return have;
	}

	/** 200000 -> "200,000": item counts in badges/overlays get grouping. */
	public static String formatCount(int n)
	{
		return String.format(Locale.US, "%,d", n);
	}

	/**
	 * "compost pack" -> "Compost pack" for DISPLAY (badges, overlays) —
	 * guide/annotation names are lowercase for matching, but the panel
	 * should read like the game does (owner ask). Never use this on
	 * names that feed matching.
	 */
	public static String capitalize(String name)
	{
		return name == null || name.isEmpty()
			? name
			: Character.toUpperCase(name.charAt(0)) + name.substring(1);
	}

	/** Cached stackability by guide item name; see bankCountable. */
	private final Map<String, Boolean> stackableByName =
		new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * Should this goal count the BANK as well as carried items? Only when
	 * the quantity physically can't be carried: more than an inventory of
	 * a NON-stackable item ("gather 130 planks"). 1000 arrow shafts is one
	 * stack — the step means "holding them", so banking them again must
	 * re-open it, exactly like any other carried goal.
	 */
	public boolean bankCountable(String name, int quantity)
	{
		// Gold is WEALTH, not cargo: "until 200k cash" counts the bank no
		// matter what — banked coins showing 0/200,000 after a relog read
		// as lost progress. (Spending it later never re-opens the step;
		// the reopen logic skips coin goals.)
		for (String alias : aliases(name))
		{
			if (alias.equals("coins"))
			{
				return true;
			}
		}
		// A FULL inventory's worth ("buy 1 inv of bronze bars" = 28) is
		// bought to be banked — count the bank for it, like over-inventory
		// gathers. Below that, carried-only rules apply.
		if (quantity < com.ironscape.goals.GoalDetector.CARRYABLE_LIMIT)
		{
			return false;
		}
		String key = name.toLowerCase(Locale.ROOT).trim();
		Boolean stackable = stackableByName.get(key);
		if (stackable == null)
		{
			// Item compositions may only load on the client thread — the
			// panel's Swing badges asking first CRASHED the panel build
			// (AssertionError truncated the step list). Off-thread, fall
			// back to the quantity-only rule; the next game-tick
			// evaluation caches the real answer and the badges refresh.
			if (!client.isClientThread())
			{
				return true;
			}
			try
			{
				int id = iconIdFor(key);
				stackable = id > 0 && itemManager.getItemComposition(id).isStackable();
			}
			catch (RuntimeException e)
			{
				stackable = false; // unknown item: keep the quantity-only rule
			}
			stackableByName.put(key, stackable);
		}
		return !stackable;
	}

	private int lookupIconId(String name)
	{
		for (String alias : aliases(name))
		{
			// Wiki-seeded map first: it covers untradeables the price
			// list can't resolve.
			Integer bundled = bundledItemIds().get(alias);
			if (bundled != null)
			{
				return bundled;
			}
			// Coins aren't tradeable, so the price-list search misses them.
			if (alias.equals("coins"))
			{
				return net.runelite.api.gameval.ItemID.COINS;
			}
			for (net.runelite.http.api.item.ItemPrice price : itemManager.search(alias))
			{
				if (price.getName().equalsIgnoreCase(alias)
					|| canonical(price.getName()).equals(canonical(alias)))
				{
					return price.getId();
				}
			}
		}
		// A possessive in the REAL name defeats substring search ("wizard
		// mind bomb" never brings back "Wizard's mind bomb"): search the
		// name's last word alone and canonical-compare the hits.
		String[] words = name.toLowerCase(Locale.ROOT).trim().split("\\s+");
		if (words.length > 1)
		{
			String want = canonical(name);
			String lastSingular = words[words.length - 1];
			if (lastSingular.length() > 3 && lastSingular.endsWith("s") && !lastSingular.endsWith("ss"))
			{
				lastSingular = lastSingular.substring(0, lastSingular.length() - 1);
			}
			for (net.runelite.http.api.item.ItemPrice price : itemManager.search(lastSingular))
			{
				if (canonical(price.getName()).equals(want))
				{
					return price.getId();
				}
			}
		}
		// Substitute-only names ("pickaxe" = any tier) have no item of
		// their own — borrow the FIRST substitute's sprite.
		for (String alias : aliases(name))
		{
			String[] alternates = SUBSTITUTES.get(alias);
			if (alternates != null && alternates.length > 0)
			{
				int id = lookupIconId(alternates[0]);
				if (id > 0)
				{
					return id;
				}
			}
		}
		// Family names ("beads", "bars") borrow any family member's sprite.
		// Canonical comparison, same as counting: "Black bead" is singular
		// in game, the suffix is canonical-singular.
		String suffix = FAMILY_SUFFIX.get(name.toLowerCase(Locale.ROOT).trim());
		if (suffix != null)
		{
			for (net.runelite.http.api.item.ItemPrice price : itemManager.search(suffix.trim()))
			{
				if (canonical(price.getName()).endsWith(suffix))
				{
					return price.getId();
				}
			}
		}
		return -1;
	}

	/** Client thread. Forwarded by the plugin on every container change. */
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		int id = event.getContainerId();
		if (id == InventoryID.BANK)
		{
			bankByName = countByName(event.getItemContainer());
			saveBank();
			rebuild();
		}
		else if (id == InventoryID.INV || id == InventoryID.WORN)
		{
			rebuild();
		}
	}

	/** Client thread. Load the right account's bank snapshot after login. */
	public void onLoggedIn()
	{
		long hash = client.getAccountHash();
		if (hash != -1 && hash != accountHash)
		{
			accountHash = hash;
			bankByName = loadBank();
		}
		rebuild();
	}

	/**
	 * The account hash is often still -1 on the LOGIN event itself — the
	 * persisted bank snapshot silently never loaded, and every banked
	 * count read 0 until the player physically opened a bank. Called each
	 * game tick; loads the snapshot the moment the hash exists.
	 *
	 * @return true when the snapshot was just loaded (refresh the panel)
	 */
	public boolean ensureBankLoaded()
	{
		if (accountHash != -1)
		{
			return false;
		}
		long hash = client.getAccountHash();
		if (hash == -1)
		{
			return false;
		}
		accountHash = hash;
		bankByName = loadBank();
		rebuild();
		log.info("Loaded persisted bank snapshot ({} item names) for account", bankByName.size());
		return true;
	}

	private void rebuild()
	{
		Map<String, Integer> carried = new HashMap<>();
		mergeContainer(carried, client.getItemContainer(InventoryID.INV));
		mergeContainer(carried, client.getItemContainer(InventoryID.WORN));
		// Equipment kept separately too — an "equip X" step needs to tell
		// worn from merely carried, which the merged map can't.
		Map<String, Integer> worn = new HashMap<>();
		mergeContainer(worn, client.getItemContainer(InventoryID.WORN));

		// Bank counts come ONLY from bank container EVENTS (bankByName):
		// withdrawals always fire one, so the snapshot tracks reality.
		// We used to prefer client.getItemContainer(BANK) here, but that
		// cached container was seen retaining withdrawn items ("steel axe
		// 2/1" with one axe carried and none banked) — event-sourced state
		// can't drift like that.
		//
		// DEPOSIT BOXES are the one path into the bank that fires no bank
		// event: items that just left the player's hands while the box is
		// open were deposited, so credit them to the snapshot ourselves —
		// otherwise the gold dropped off at the Port Sarim box reads as
		// vanished until the next real bank visit.
		net.runelite.api.widgets.Widget depositBox =
			client.getWidget(net.runelite.api.gameval.InterfaceID.BankDepositbox.UNIVERSE);
		if (depositBox != null && !depositBox.isHidden())
		{
			boolean credited = false;
			synchronized (this)
			{
				for (Map.Entry<String, Integer> old : carriedByName.entrySet())
				{
					int delta = old.getValue() - carried.getOrDefault(old.getKey(), 0);
					if (delta > 0)
					{
						bankByName.merge(old.getKey(), delta, Integer::sum);
						credited = true;
					}
				}
			}
			if (credited)
			{
				saveBank();
			}
		}

		Map<String, Integer> total = new HashMap<>(bankByName);
		carried.forEach((name, count) -> total.merge(name, count, Integer::sum));

		synchronized (this)
		{
			// did anything LEAVE the player's hands? (give/fix/build steps)
			boolean consumed = false;
			for (Map.Entry<String, Integer> old : carriedByName.entrySet())
			{
				if (carried.getOrDefault(old.getKey(), 0) < old.getValue())
				{
					consumed = true;
					break;
				}
			}
			lastRebuildConsumedCarried = consumed;

			carriedByName.clear();
			carriedByName.putAll(carried);
			wornByName.clear();
			wornByName.putAll(worn);
			ownedByName.clear();
			ownedByName.putAll(total);
		}
	}

	private boolean lastRebuildConsumedCarried;

	/** Did the last container change reduce something the player carried? */
	public synchronized boolean lastRebuildConsumedCarried()
	{
		return lastRebuildConsumedCarried;
	}

	private void mergeContainer(Map<String, Integer> counts, ItemContainer container)
	{
		if (container == null)
		{
			return;
		}
		countByName(container).forEach((name, count) -> counts.merge(name, count, Integer::sum));
	}

	private Map<String, Integer> countByName(ItemContainer container)
	{
		Map<String, Integer> counts = new HashMap<>();
		for (Item item : container.getItems())
		{
			// -1 = empty slot; quantity 0 = defensive
			if (item.getId() < 0 || item.getQuantity() <= 0)
			{
				continue;
			}
			// A bank PLACEHOLDER is its own item id with quantity 1 —
			// canonicalize() would map it onto the real item and count a
			// phantom copy ("pickaxe 2/1" after withdrawing the only one).
			if (itemManager.getItemComposition(item.getId()).getPlaceholderTemplateId() != -1)
			{
				continue;
			}
			// canonicalize: a noted item counts as the real thing
			int canonicalId = itemManager.canonicalize(item.getId());
			String name = itemManager.getItemComposition(canonicalId).getName()
				.toLowerCase(Locale.ROOT);
			counts.merge(name, item.getQuantity(), Integer::sum);
		}
		return counts;
	}

	// ------------------------------------------------------------------
	// Bank snapshot persistence, one file per game account
	// ------------------------------------------------------------------

	private File bankFile()
	{
		return new File(RuneLite.RUNELITE_DIR, "ironscape/bank-" + accountHash + ".json");
	}

	private void saveBank()
	{
		if (accountHash == -1)
		{
			return;
		}
		File file = bankFile();
		File dir = file.getParentFile();
		if (dir != null && !dir.exists() && !dir.mkdirs())
		{
			log.warn("Could not create {}", dir);
			return;
		}
		try (Writer writer = new FileWriter(file))
		{
			gson.toJson(bankByName, writer);
		}
		catch (IOException e)
		{
			log.warn("Could not save bank snapshot", e);
		}
	}

	private Map<String, Integer> loadBank()
	{
		File file = bankFile();
		if (accountHash == -1 || !file.exists())
		{
			return new HashMap<>();
		}
		try (Reader reader = new FileReader(file))
		{
			Map<String, Integer> loaded = gson.fromJson(reader,
				new TypeToken<Map<String, Integer>>()
				{
				}.getType());
			return loaded == null ? new HashMap<>() : loaded;
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Could not load bank snapshot", e);
			return new HashMap<>();
		}
	}
}
