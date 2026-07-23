package com.ironscape.items;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.HashMap;
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

	private static int resolve(Map<String, Integer> counts, String name)
	{
		for (String candidate : aliases(name))
		{
			Integer count = counts.get(candidate);
			if (count != null)
			{
				return count;
			}
		}
		return 0;
	}

	/** Guide slang -> the item's real in-game name. */
	private static final Map<String, String> COLLOQUIAL = Map.ofEntries(
		Map.entry("poh tab", "teleport to house"),
		Map.entry("poh tabs", "teleport to house"),
		Map.entry("house tab", "teleport to house"),
		Map.entry("house tabs", "teleport to house"),
		Map.entry("varrock tab", "varrock teleport"),
		Map.entry("falador tab", "falador teleport"),
		Map.entry("fally tab", "falador teleport"),
		Map.entry("lumbridge tab", "lumbridge teleport"),
		Map.entry("lumby tab", "lumbridge teleport"),
		Map.entry("camelot tab", "camelot teleport"),
		Map.entry("ardougne tab", "ardougne teleport"),
		Map.entry("ardy tab", "ardougne teleport"),
		// The POH tab's in-game name is "Teleport to house"; the guide also
		// says "house teleport". Redirected tabs (scroll of redirection) are
		// literally named "<Place> teleport" in game — wiki-confirmed.
		Map.entry("house teleport", "teleport to house"),
		Map.entry("house teleports", "teleport to house"),
		Map.entry("redirected poh tab", "teleport to house"),
		Map.entry("rimmington tab", "rimmington teleport"),
		Map.entry("taverley tab", "taverley teleport"),
		Map.entry("pollnivneach tab", "pollnivneach teleport"),
		Map.entry("hosidius tab", "hosidius teleport"),
		Map.entry("rellekka tab", "rellekka teleport"),
		Map.entry("brimhaven tab", "brimhaven teleport"),
		Map.entry("yanille tab", "yanille teleport"),
		Map.entry("trollheim tab", "trollheim teleport"),
		Map.entry("prifddinas tab", "prifddinas teleport"),
		Map.entry("catherby tab", "catherby teleport"));

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

		return new String[]{
			key,             // exact:      "mind rune"
			singular,        // deplural:   "buckets" -> "bucket"
			firstSingular,   // of-phrase:  "bolts of cloth" -> "bolt of cloth"
			key + "s",       // plural:     "log" -> "logs"
			key + " rune",   // elemental:  "mind" -> "mind rune"
			singular + " rune", // "minds" -> "mind rune"
		};
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
		if (quantity <= com.ironscape.goals.GoalDetector.CARRYABLE_LIMIT)
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
				if (price.getName().equalsIgnoreCase(alias))
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
		if (hash != accountHash)
		{
			accountHash = hash;
			bankByName = loadBank();
		}
		rebuild();
	}

	private void rebuild()
	{
		Map<String, Integer> carried = new HashMap<>();
		mergeContainer(carried, client.getItemContainer(InventoryID.INV));
		mergeContainer(carried, client.getItemContainer(InventoryID.WORN));

		// Bank counts come ONLY from bank container EVENTS (bankByName):
		// withdrawals always fire one, so the snapshot tracks reality.
		// We used to prefer client.getItemContainer(BANK) here, but that
		// cached container was seen retaining withdrawn items ("steel axe
		// 2/1" with one axe carried and none banked) — event-sourced state
		// can't drift like that.
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
			// -1 = empty slot; quantity 0 = bank placeholder
			if (item.getId() < 0 || item.getQuantity() <= 0)
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
