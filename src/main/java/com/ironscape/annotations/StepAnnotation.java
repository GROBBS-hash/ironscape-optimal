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
	}
}
