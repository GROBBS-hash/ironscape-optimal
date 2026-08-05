package com.ironscape.annotations;

import java.util.List;

/**
 * Optional extra knowledge about one guide step or sub-step. Everything
 * here is nullable — the plugin works fine with no annotations at all and
 * gets smarter as they accumulate.
 *
 * Plain mutable fields (no Lombok) because Gson reads/writes them directly
 * and the JSON file is the real source of truth.
 */
public class StepAnnotation
{
	/** Condition for automatic completion detection. Null = manual checkbox only. */
	public Requirement requires;

	/**
	 * Multi-condition variant: the step completes only when EVERY entry is
	 * met ("get 93 crafting AND 91 thieving"). When present, this wins over
	 * the single `requires`. Supports the pseudo-skill "COMBAT" for combat
	 * level ("train slayer to 100cb").
	 */
	public List<Requirement> requiresAll;

	/** Where this step happens, for Shortest Path navigation. */
	public Target target;

	/** Items this step needs — the panel shows a live have/need count for each. */
	public List<ItemNeed> items;

	/**
	 * "Have N of ANY item from a named set" — e.g. 4 pieces of warm
	 * clothing for Wintertodt. Purely informational: the badge shows
	 * have/need but never gates the step's completion. Sets live in
	 * items/gear_sets.json (seeded from the wiki).
	 */
	public GearCheck gearCheck;

	/**
	 * External reference for steps whose prose is too terse ("Do museum
	 * for 9 Hunter and Slayer" = the Natural History quiz) — rendered as
	 * a clickable 🔗 line under the step that opens the browser.
	 */
	public Link link;

	/**
	 * Mid-quest side pickups ("Do Tree gnome village, get Glarial's
	 * pebble on the way"). Quest Helper owns the quest flow but knows
	 * nothing about the errand — while the sub's quest is IN_PROGRESS,
	 * the first unsatisfied stage's spot gets our tile marker and a
	 * Shortest Path route, the nearest NPC to it the outline with the
	 * item overhead, and coming near it triggers a one-time chat
	 * reminder. Ordered: earlier stages are prerequisites (the gate KEY
	 * before Golrie's pebble); a stage seen owned once stays satisfied
	 * for the session even if using it consumes it.
	 */
	public List<Errand> errands;

	public static class Errand
	{
		public int x;
		public int y;
		public int plane;
		/** Item this stage yields; the stage stands down once you own one. */
		public String item;
		/** Optional reminder text; defaults to naming the item. */
		public String note;
	}

	public static class Link
	{
		/** Text shown in the panel, e.g. "Natural History Quiz (wiki)". */
		public String label;
		public String url;
	}

	public static class GearCheck
	{
		/** Set name in gear_sets.json, e.g. "warm clothing". */
		public String set;
		/** How many DISTINCT items from the set must be carried/worn. */
		public int need;
	}

	public static class ItemNeed
	{
		/** In-game item name, matched case-insensitively against what you own. */
		public String name;
		/** How many are needed; null means 1. */
		public Integer quantity;
	}

	public static class Target
	{
		public int x;
		public int y;
		public int plane;
		/**
		 * Local-file only: masks a bundled target the player removed
		 * in-game (the seeded pin was wrong). x/y are meaningless when set;
		 * capturing a new location replaces the tombstone.
		 */
		public Boolean cleared;
	}

	/**
	 * Tier-1 requirement: a skill level, e.g. {"skill": "PRAYER", "level": 43},
	 * or a game-state threshold, e.g. {"varbit": 5619, "value": 5} — met
	 * once the varbit/varp reaches the value. Quest progress varbits count
	 * up monotonically, which makes mid-quest checkpoints ("do the quest
	 * up to the orb") detectable without per-quest authoring. Keyed by a
	 * SUB id ("stepId:14") a requirement ticks just that sub; keyed by a
	 * step id it completes the whole step.
	 */
	public static class Requirement
	{
		public String skill;
		public Integer level;
		/** Varbit id to watch (e.g. 5619 = Client of Kourend progress). */
		public Integer varbit;
		/** Varplayer id to watch — for older quests tracked by varp. */
		public Integer varp;
		/** Met when the varbit/varp value is >= this. */
		public Integer value;
		/**
		 * Alternative to `value` for BITFIELD vars: met when this bit of the
		 * varbit/varp is set (e.g. varp 77 packs one bit per barcrawl bar —
		 * bit 7 = Flying Horse Inn signed your card). A sub with any
		 * varbit/varp requirement completes ONLY off that requirement:
		 * arrival at the bar must not tick "get a drink" before the stamp.
		 */
		public Integer bit;
		/**
		 * Optional panel badge for varbit/varp checkpoints: item name whose
		 * sprite heads the line (must resolve via item_ids.json for
		 * untradeables), e.g. "Barcrawl card".
		 */
		public String icon;
		/** Badge text for checkpoints, shown as "<label> 0/1", e.g. "stamp". */
		public String label;
	}
}
