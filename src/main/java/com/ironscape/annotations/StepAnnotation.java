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
	 * Method commentary the guide's own prose lacks ("Soft clay: use a
	 * bucket of water on clay") — rendered as a boxed NOTE block under the
	 * step, same style as the guide's authored notes.
	 */
	public String note;

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

	/**
	 * Chat-menu options this step wants picked — recolored blue when the
	 * menu opens, Quest Helper style. For steps whose whole action is one
	 * dialogue choice ("Use Brimstails to go to ess mines" = "Can you
	 * teleport me to the Rune Essence Mine?"). Errand stages carry their
	 * own `dialog`; this is the same thing for steps with no chain.
	 * Matched case-insensitively against the exact option text.
	 */
	public List<String> dialog;

	public static class Errand
	{
		public int x;
		public int y;
		public int plane;
		/** Item this stage yields; the stage stands down once you own one. */
		public String item;
		/** Optional reminder text; defaults to naming the item. */
		public String note;
		/**
		 * Var-GATED stage (instead of item/proximity): satisfied only once
		 * the varbit/varp reaches `value`. Stages inside a quest sequence
		 * ("hand the Cook the items" -> var 2, "watch the cutscene" -> 3)
		 * sit tiles apart — proximity can't order them, quest progress can.
		 */
		public Integer varbit;
		public Integer varp;
		public Integer value;
		/**
		 * Where the ROUTE points when it differs from the satisfaction
		 * point: Shortest Path can't draw into interiors, so a basement
		 * stage routes to the surface trapdoor (routeX/Y/Plane) while
		 * satisfaction still waits for x/y — reached only downstairs.
		 */
		public Integer routeX;
		public Integer routeY;
		public Integer routePlane;
		/**
		 * Waypoint satisfaction radius (default 12). Small rooms need a
		 * tight one: the Culinaromancer's Chest sits 10 tiles from the
		 * ladder, so a 12-tile stage self-satisfied on arrival downstairs.
		 */
		public Integer radius;
		/**
		 * On the FIRST stage only: the chain guides even while the step's
		 * quest is NOT_STARTED — for PREP steps that craft hand-ins before
		 * the quest begins (Prince Ali's paste/wig/imprint run). Without
		 * it a quest-tagged chain waits for the quest to start.
		 */
		public Boolean preQuest;
		/**
		 * The NPC this stage is about, outlined BY NAME — the nearest-NPC
		 * fallback crowned a bystander when Aggie wandered off her tile.
		 */
		public String npc;
		/**
		 * The hand-ins for THIS stage: while it's active, only these glow
		 * in the inventory (QH-style) instead of the whole step kit.
		 */
		public java.util.List<String> items;
		/**
		 * Chat options to pick while this stage is active — recolored
		 * blue in the dialog menu, Quest Helper-style. Exact option text.
		 */
		public java.util.List<String> dialog;
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
		/**
		 * Keep-if-you-get-it, not a requirement ("keep robes, opal and
		 * buttons"): the badge shows a muted "(optional)" tag and an unmet
		 * count stays grey, never alarm red.
		 */
		public Boolean optional;
		/**
		 * A material for the step's PRODUCTS (redberries for the dyes),
		 * not the deliverable itself — the badge indents under the
		 * products with a muted "(ingredient)" tag.
		 */
		public Boolean ingredient;
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
		/**
		 * This target IS a safespot ("Capture as safespot" on the ⌖
		 * button) — the tile marker gets the floating "Safespot" label
		 * even when the step text never says the word.
		 */
		public Boolean safespot;
		/**
		 * FALSE when this pin marks a place rather than a person — a cave
		 * door, a ladder, a dig spot. The "nearest NPC to the pin" fallback
		 * exists to find unnamed shopkeepers and quest givers; aimed at a
		 * doorway it just crowns whoever loiters there (a gnome outside
		 * Brimstail's cave wore the outline for a step about Brimstail).
		 * Omit for pins that DO mark someone — Wizard Cromperty's does.
		 */
		public Boolean npc;
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
		/**
		 * Position checkpoint: met while the player stands in this map
		 * region (WorldPoint.getRegionID()). For destinations the place
		 * list can't express — interiors and off-map areas like the rune
		 * essence mine — where a text/📍 arrival would anchor on the
		 * ORIGIN and false-tick. Same exclusivity as varbit checkpoints
		 * (the sub completes ONLY off it), but evaluated frontier-only
		 * like arrival, and gated on the step's annotation items being
		 * carried ("make sure you have it with you").
		 */
		public Integer region;
		/**
		 * Item name that must be WORN. "Equip Gas mask" is done when the
		 * mask is on your face — owning one proves nothing, and the item
		 * goal detector deliberately ignores equip/wear/wield clauses
		 * (they'd otherwise swallow "...and equip it" into item lists).
		 * Same exclusivity as the other checkpoints, and evaluated
		 * frontier-only: worn state is reversible, so a later step's
		 * "equip X" must not tick just because you happen to have X on.
		 */
		public String equipped;
	}
}
