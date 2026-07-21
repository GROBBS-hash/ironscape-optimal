package com.bruhsailer.items;

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

	/**
	 * The in-game item names a guide phrase might refer to, most literal
	 * first. The guide abbreviates: "100 mind" means Mind runes, "1 log" is
	 * the item "Logs", "2 buckets" is the item "Bucket", "bolts of cloth"
	 * is "Bolt of cloth". Also used by the bank filter.
	 */
	public static String[] aliases(String name)
	{
		String key = name.toLowerCase(Locale.ROOT).trim();
		// the guide says gp/gold/cash; the item is "Coins"
		if (key.equals("gp") || key.equals("gold") || key.equals("cash") || key.equals("money"))
		{
			key = "coins";
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

		// The client caches the bank container even after you close the
		// bank, and it's ALWAYS current when present — using it directly
		// avoids double counting an item during the moment of withdrawal.
		// The persisted snapshot only covers "haven't banked yet today".
		ItemContainer liveBank = client.getItemContainer(InventoryID.BANK);
		Map<String, Integer> bank = liveBank != null ? countByName(liveBank) : bankByName;

		Map<String, Integer> total = new HashMap<>(bank);
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
		return new File(RuneLite.RUNELITE_DIR, "bruhsailer/bank-" + accountHash + ".json");
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
