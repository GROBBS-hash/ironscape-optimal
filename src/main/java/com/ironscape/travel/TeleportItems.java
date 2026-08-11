package com.ironscape.travel;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

/**
 * Every teleport an ITEM can provide — diary cloaks, teleport jewellery,
 * tablets, memoirs — with where it lands and what it takes to use.
 *
 * The hint used to know only minigame teleports, standard-spellbook spells,
 * the free home teleport and the Chronicle, so it offered a Varrock teleport
 * for a West Ardougne target while an Ardougne cloak sat in the bag that
 * lands next door (owner, 2026-08-11).
 *
 * The data is Shortest Path's, converted by tools/seed-teleport-items.mjs —
 * see that file for why this is not a wiki scrape. Re-running it picks up
 * their corrections.
 *
 * This class is deliberately free of client calls: it holds the conditions
 * and asks a {@link Availability} for the game state, so the matching rules
 * can be tested without a running game.
 */
@Slf4j
public final class TeleportItems
{
	/** A varbit/varplayer clause: {@code id op value}. */
	public static final class VarCondition
	{
		int id;
		String op;
		int value;

		public boolean met(int actual)
		{
			switch (op)
			{
				case "=":
					return actual == value;
				case ">":
					return actual > value;
				case "<":
					return actual < value;
				case "&":
					// Bitmask: the named bits must be set. A `>=` here would
					// let any OTHER bit in the same var satisfy it — the
					// barcrawl-stamp trap (wave 6).
					return (actual & value) == value;
				case "@":
					// A real-time countdown in minutes: the var holds a
					// minute stamp and the teleport is ready once `value`
					// minutes have passed. Handled by the caller, which
					// knows the wall clock; treated as met here.
					return true;
				default:
					return false;
			}
		}

		public int getId()
		{
			return id;
		}

		public boolean isCooldown()
		{
			return "@".equals(op);
		}

		public int getValue()
		{
			return value;
		}
	}

	/** A level gate. {@code skill} is a Skill name, or TOTAL / QUEST. */
	public static final class SkillNeed
	{
		int level;
		String skill;

		public int getLevel()
		{
			return level;
		}

		public String getSkill()
		{
			return skill;
		}
	}

	/** One destination an item can take you to. */
	public static final class Entry
	{
		String display;
		@SerializedName("itemIds")
		int[] itemIds;
		int x;
		int y;
		int plane;
		boolean consumable;
		int maxWilderness;
		List<VarCondition> varbits;
		List<VarCondition> varplayers;
		List<SkillNeed> skills;
		List<String> quests;

		private WorldPoint destination;

		/** The option to pick in the item's menu — "Ardougne cloak: Kandarin Monastery". */
		public String getDisplay()
		{
			return display;
		}

		public int[] getItemIds()
		{
			return itemIds;
		}

		public WorldPoint getDestination()
		{
			if (destination == null)
			{
				destination = new WorldPoint(x, y, plane);
			}
			return destination;
		}

		public boolean isConsumable()
		{
			return consumable;
		}

		public List<VarCondition> getVarbits()
		{
			return varbits == null ? Collections.emptyList() : varbits;
		}

		public List<VarCondition> getVarplayers()
		{
			return varplayers == null ? Collections.emptyList() : varplayers;
		}

		public List<SkillNeed> getSkills()
		{
			return skills == null ? Collections.emptyList() : skills;
		}

		public List<String> getQuests()
		{
			return quests == null ? Collections.emptyList() : quests;
		}

		/**
		 * The item name the player would recognise, taken from the label:
		 * "Ardougne cloak: Kandarin Monastery" -> "Ardougne cloak".
		 */
		public String itemLabel()
		{
			int colon = display.indexOf(':');
			return colon > 0 ? display.substring(0, colon).trim() : display;
		}
	}

	/**
	 * What the game says right now. Implemented by the plugin against the
	 * client; implemented by tests against fixed values.
	 */
	public interface Availability
	{
		/** Does the player hold or wear this item id? */
		boolean carries(int itemId);

		int varbit(int id);

		int varplayer(int id);

		/** Level in a named skill, or -1 if the name is not a skill. */
		int skillLevel(String skill);

		int totalLevel();

		int questPoints();

		boolean questFinished(String questName);
	}

	private final List<Entry> entries;

	private TeleportItems(List<Entry> entries)
	{
		this.entries = entries;
	}

	public List<Entry> all()
	{
		return entries;
	}

	/**
	 * The entry Shortest Path means by a given label.
	 *
	 * An exact match is right here and a fuzzy one would be wrong: this
	 * index is built FROM Shortest Path's own table, so the strings are the
	 * same strings. If one ever stops matching, the answer is to re-run the
	 * seeder, not to loosen the comparison.
	 */
	public Entry byDisplay(String display)
	{
		if (display == null)
		{
			return null;
		}
		for (Entry entry : entries)
		{
			if (display.equals(entry.getDisplay()))
			{
				return entry;
			}
		}
		return null;
	}

	public int size()
	{
		return entries.size();
	}

	/**
	 * Every teleport the player could use RIGHT NOW, in no particular
	 * order. Ranking is the caller's job — it is the one that knows where
	 * the player is going.
	 */
	public List<Entry> available(Availability state)
	{
		List<Entry> usable = new ArrayList<>();
		for (Entry entry : entries)
		{
			if (isAvailable(entry, state))
			{
				usable.add(entry);
			}
		}
		return usable;
	}

	/**
	 * Every gate must pass. Anything we cannot evaluate fails CLOSED — a
	 * teleport we wrongly withhold costs a hint, one we wrongly offer sends
	 * the player digging for an item they do not have (wave 25's rule for
	 * unresolved quest names, same reasoning).
	 */
	public boolean isAvailable(Entry entry, Availability state)
	{
		boolean holdsOne = false;
		for (int id : entry.getItemIds())
		{
			if (state.carries(id))
			{
				holdsOne = true;
				break;
			}
		}
		if (!holdsOne)
		{
			return false;
		}
		for (VarCondition condition : entry.getVarbits())
		{
			if (!condition.met(state.varbit(condition.getId())))
			{
				return false;
			}
		}
		for (VarCondition condition : entry.getVarplayers())
		{
			if (!condition.met(state.varplayer(condition.getId())))
			{
				return false;
			}
		}
		for (SkillNeed need : entry.getSkills())
		{
			int have;
			// TOTAL and QUEST are not skills — they are the max cape's total
			// level and the quest cape's quest points, which share this
			// column in the source data.
			if ("TOTAL".equals(need.getSkill()))
			{
				have = state.totalLevel();
			}
			else if ("QUEST".equals(need.getSkill()))
			{
				have = state.questPoints();
			}
			else
			{
				have = state.skillLevel(need.getSkill());
			}
			if (have < need.getLevel())
			{
				return false;
			}
		}
		for (String quest : entry.getQuests())
		{
			if (!state.questFinished(quest))
			{
				return false;
			}
		}
		return true;
	}

	/** Load the bundled index; never throws, and an unreadable file yields an empty one. */
	public static TeleportItems load(Gson gson)
	{
		try (InputStream in = com.ironscape.DataFiles.open(TeleportItems.class, "teleport_items.json"))
		{
			if (in == null)
			{
				log.warn("teleport_items.json missing — teleport items will not be suggested");
				return new TeleportItems(Collections.emptyList());
			}
			Entry[] loaded = gson.fromJson(
				new InputStreamReader(in, StandardCharsets.UTF_8), Entry[].class);
			if (loaded == null)
			{
				return new TeleportItems(Collections.emptyList());
			}
			List<Entry> valid = new ArrayList<>(loaded.length);
			for (Entry entry : loaded)
			{
				// A row with no display or no items cannot be acted on, and
				// silently keeping it would inflate the count this is
				// measured by.
				if (entry != null && entry.display != null
					&& entry.itemIds != null && entry.itemIds.length > 0)
				{
					valid.add(entry);
				}
			}
			return new TeleportItems(Collections.unmodifiableList(valid));
		}
		catch (Exception e)
		{
			log.warn("Could not read the teleport item index", e);
			return new TeleportItems(Collections.emptyList());
		}
	}
}
