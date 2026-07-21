package com.bruhsailer.annotations;

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
	 * Tier-1 requirement: a skill level, e.g. {"skill": "PRAYER", "level": 43}.
	 * More kinds (quests, items) arrive with the extraction script.
	 */
	public static class Requirement
	{
		public String skill;
		public Integer level;
	}
}
