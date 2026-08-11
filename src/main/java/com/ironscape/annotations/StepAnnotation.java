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

	/**
	 * Where to go and TRAIN while this step's skill requirement is unmet.
	 *
	 * "Train Runecraft to 10, then complete Temple of the Eye" is two jobs
	 * in one step, and the guide is atomic so it cannot be split into
	 * sub-steps. Routing to the quest is useless until the level is there
	 * (Temple of the Eye needs Runecraft 10 and is not boostable), and a
	 * plain ⌖ would then keep pointing at the training spot for the rest of
	 * the step.
	 *
	 * So this is used ONLY while `requires` (or requiresAll) names a skill
	 * the player has not reached. The moment they do, routing falls back to
	 * the step's normal target — the quest giver — and once the quest is
	 * started Quest Helper takes over as usual. Three steps guide-wide have
	 * this grind-then-quest shape.
	 *
	 * An explicit ⌖ capture still wins: a location captured by hand is a
	 * deliberate statement about where to be.
	 */
	public Target trainAt;

	/**
	 * What the training at `trainAt` CONSUMES — the other half of the loop.
	 *
	 * Runecrafting is bank, altar, bank, altar: with essence in the bag the
	 * place to be is the altar, and with none it is the nearest bank. A
	 * pin alone only ever describes half of that, and the first cut of
	 * trainAt kept pointing at the altar while the player stood there with
	 * an empty inventory (owner: "it should pull us to the bank to grab
	 * more essence").
	 *
	 * Counted as CARRIED, not owned: the whole question is whether there is
	 * anything to work with right now, and the bank total is what sends you
	 * to the bank in the first place.
	 */
	public String trainWith;

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
	 * The NETWORK STOP to actually take, when the step names a destination
	 * the network doesn't serve. "Spirit tree to ardy" — there is no
	 * Ardougne spirit tree; the stop is Battlefield of Khazard, then you
	 * run north (owner: "i know the step doesnt label it like this but for
	 * our overlay and shortestpath thats the one we want").
	 *
	 * Its words join the travel-menu word set, so TravelMenuOverlay
	 * highlights the right list entry. Word-SET matching means the menu's
	 * exact phrasing doesn't matter.
	 *
	 * NOT arrival proof. A ⌖ on a network-travel sub already marks the
	 * BOARDING point, and only the step's text destination or 📍 tag
	 * proves you arrived (wave 7) — naming the stop you board toward must
	 * not start ticking the step at the stop instead of the destination.
	 */
	public String travelVia;

	/**
	 * The quest this step is a leg OF, when the guide never says so.
	 *
	 * "Continue Lost tribe until you need to go to the goblin village" is
	 * a quest leg by any reading, but it carries no quest metadata and its
	 * text yields no quest goal, so stepQuest() returned null: Quest
	 * Helper never took over, the green tip line never appeared, and our
	 * route argued with QH's for the whole leg. Eight steps guide-wide.
	 *
	 * It lives HERE rather than in the guide's metadata because metadata
	 * comes from the scraper — a hand-edit there dies at the next
	 * re-scrape, while annotations survive by design.
	 *
	 * PREP steps must never carry this. Tagging "buy a bronze sword for
	 * Horror from the deep" would hand a shopping trip to Quest Helper and
	 * stand our own routing down; only steps whose TASK is the quest leg
	 * qualify (tools/review-decisions.mjs draws that line and explains it).
	 */
	public String quest;

	/**
	 * This step can no longer be done — the game changed under the guide.
	 * The string is the REASON, shown on the chip's tooltip.
	 *
	 * "Get 100% hosidius favour" is the case: the Kourend favour system was
	 * removed in January 2024, so the step has no completion path and never
	 * will. Without a flag it reads as ordinary work, and the only way past
	 * it is to tick a step you never did.
	 *
	 * Deliberately NOT auto-skipped. Skipping for the player would move the
	 * frontier on evidence the plugin cannot check — the guide's own note is
	 * the only source — and a wrong auto-skip is invisible. It marks the
	 * step; "Start from here" on the next one moves past it.
	 */
	public String obsolete;

	/**
	 * A quest that must be FINISHED before this step can be started, when
	 * the guide's step order predates the requirement.
	 *
	 * The Lost Tribe now requires Goblin Diplomacy, which the guide does 25
	 * steps LATER — so following the order as written stalls at a quest you
	 * cannot start. Informational only: it never gates completion, because
	 * the step is genuinely doable once the prerequisite is met and nothing
	 * here should be able to wedge a step shut.
	 */
	public String prerequisiteQuest;

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
		/**
		 * Stage done once the player is standing in this REGION
		 * (WorldPoint.getRegionID()).
		 *
		 * For a leg whose whole point is GETTING SOMEWHERE that no item and
		 * no var records. Arhein's crate smuggles you into Keep Le Faye; the
		 * crate is in Catherby, so a proximity waypoint on it ticked the
		 * moment the player walked past on another errand, and modelling it
		 * as a coordinate meant navigation kept pointing back at the crate
		 * from INSIDE the keep.
		 *
		 * Quest Helper has had this all along and it is why its guidance
		 * looks seamless here — inFayeGround / inFaye1 / inFaye2 are zone
		 * checks it evaluates every tick. This is the same idea with one
		 * region instead of a zone: the stage asks "am I in yet?" rather
		 * than "am I near the door?".
		 *
		 * COARSE, and its very first use showed how coarse: a region is
		 * 64x64, and region 11061 holds Keep Le Faye AND the giant bats
		 * outside it that the same chain sends you to two stages earlier.
		 * Prefer {@link #zone} wherever the bounds are known — QH publishes
		 * them. Region stays for places whose extent nobody has written
		 * down and which fill their region anyway (instanced caves).
		 */
		public Integer region;
		/**
		 * Stage done once the player stands inside this BOX, on its plane —
		 * the precise form of "am I in yet?", and the only condition that
		 * can tell FLOORS apart.
		 *
		 * A journey that goes in, up, and back out cannot be expressed
		 * without it: "the ground floor of Keep Le Faye" is a different leg
		 * on the way in and on the way out, and a region cannot see the
		 * difference between them, nor between floor 1 and floor 2.
		 *
		 * Quest Helper's zones are the source — `setupZones()` in each
		 * quest helper publishes exactly these boxes, one per plane
		 * (fayeGround / faye1 / faye2), and `tools/qh-tree.mjs` prints the
		 * branch that each one guards.
		 */
		public Zone zone;
		/**
		 * INVERTS {@link #zone} / {@link #region}: this stage is done once
		 * the player is OUT of it. The way out of a one-way interior is a
		 * leg like any other — you have somewhere to be and something to
		 * click — and no proximity coordinate can express it, because every
		 * tile outside the door is a few tiles from every tile inside it.
		 */
		public Boolean leave;
		/**
		 * The scene object this leg is about, outlined by name at the
		 * stage's route point (or its own point when there is no split):
		 * the staircase to climb, the trapdoor to open, the crate to hide
		 * in, the gangplank to cross.
		 *
		 * Without it the outline falls back to a hardcoded guess-list of
		 * traversal words, which covers stairs and ladders and nothing
		 * else. Naming the object is the model saying precisely what the
		 * guess-list approximates.
		 */
		public String object;
		/**
		 * INVERTS `item`: this stage HANDS the item over, so it stands down
		 * once you no longer carry one. Biohazard's three vials go to three
		 * different people (Hops the sulphuric broline, Chancy the liquid
		 * honey, Da Vinci the ethenea) and giving the wrong one to the wrong
		 * person is the mistake worth preventing.
		 *
		 * A satisfied give-stage never marks EARLIER stages done, unlike the
		 * normal cascade — three hand-ins are independent, and doing Da Vinci
		 * first must not skip Hops.
		 *
		 * Limitation: banking the item reads as "given". The chain is
		 * guidance, not the completion gate (that's the step's checkpoint),
		 * so the cost is a skipped hint rather than a wrong tick.
		 */
		public Boolean given;
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
		 * Test ONE BIT of the var rather than its whole value. Achievement
		 * diaries pack a whole tier into a single varp — Ardougne Easy is
		 * ten tasks in varp 1196 — so a "greater than or equal" test would
		 * let any OTHER task's bit satisfy this stage, which is exactly the
		 * trap the barcrawl stamps hit (wave 6). When a bit is set, `value`
		 * is ignored.
		 */
		public Integer bit;
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
		 * There is no route to draw for this stage: post none at all.
		 *
		 * Two different situations, one behaviour. Either the stage is
		 * QUEST PROGRESS rather than a journey — "go to Hazelmere and
		 * continue the grand tree until you are at Karamja shipyard" walks
		 * to one place and then just... does the quest, and routing to the
		 * step's area for that half only fights Quest Helper — or Shortest
		 * Path genuinely cannot draw the leg, which is the case for every
		 * step of the way OUT of a one-way interior. Arhein's crate is not
		 * in its transport graph and the keep door is locked, so from
		 * inside Keep Le Faye it proposed a Lumbridge home teleport. A
		 * route you cannot walk is worse than no route; the object outline
		 * is the guidance for these legs.
		 *
		 * Opt-in per stage on purpose. Standing down for every mid-quest
		 * step was tried and reverted — it left players with no route at
		 * all ("run back to Falador" gave nothing) — so only a stage that
		 * SAYS so holds.
		 */
		public Boolean hold;
		/**
		 * A SIDE TASK that happens to sit near the route — the diary talk
		 * with Sherlock, the field of flax — rather than a leg of the work.
		 *
		 * It sits outside the chain's ordering in both directions, which is
		 * the only way a side task can behave. Nothing implies it: carrying
		 * bat bones proves you walked past the flax field, not that you
		 * picked any, and that inference meant the Merlin chain's two diary
		 * legs had never once guided anyone (owner, in play). And it
		 * implies nothing: skipping the diary can never wedge the quest,
		 * and the step still completes without it.
		 *
		 * It speaks up when you are within {@code OPTIONAL_NUDGE_RADIUS},
		 * because "while you are here anyway" is the whole of what an
		 * optional task asks.
		 */
		public Boolean optional;
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

	/**
	 * An axis-aligned box on ONE plane, inclusive at both corners — the
	 * same shape Quest Helper's Zone uses, and seeded straight from its
	 * `setupZones()`. One plane per zone on purpose: telling floors apart
	 * is the whole reason this exists.
	 */
	public static class Zone
	{
		public int x1;
		public int y1;
		public int x2;
		public int y2;
		public int plane;

		public boolean contains(net.runelite.api.coords.WorldPoint p)
		{
			return p != null
				&& p.getPlane() == plane
				&& p.getX() >= Math.min(x1, x2) && p.getX() <= Math.max(x1, x2)
				&& p.getY() >= Math.min(y1, y2) && p.getY() <= Math.max(y1, y2);
		}
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
		 * Count THIS EXACT item id instead of matching by name — for the
		 * items whose names cannot tell them apart.
		 *
		 * Both halves of the priest gown are called exactly "Priest gown"
		 * (426 and 428), so a name-matched pair of entries can only ever
		 * report the pair's total, and the panel showed one half as 1/1
		 * twice. With an id each entry counts its own half, and `name` is
		 * free to be the DISAMBIGUATING label ("Priest gown (top)") since
		 * nothing has to match on it any more.
		 *
		 * Use it only where a name genuinely cannot work: id matching skips
		 * the alias, substitute and family logic that makes everything else
		 * forgiving, and an id pins one exact item for ever.
		 */
		public Integer id;
		/**
		 * Keep-if-you-get-it, not a requirement ("keep robes, opal and
		 * buttons"): the badge shows a muted "(optional)" tag and an unmet
		 * count stays grey, never alarm red.
		 */
		public Boolean optional;
		/**
		 * Not for THIS step — for the NEXT one. "Chronicle tele and start
		 * Dragon slayer" needs a Chronicle you had to pack before leaving
		 * the bank, and by the time you read that line you have left it
		 * (owner, 2026-08-11). Listing it on the step before shows the
		 * icon and the have/need count while you can still act on it; a
		 * sentence in the note said the same thing and was easy to skim
		 * past, because it did not look like the item rows above it.
		 *
		 * ALWAYS written alongside optional:true, deliberately. It must
		 * never gate this step — it is not this step's requirement, and a
		 * required item here would block the step's own arrival tick — and
		 * an older build that has never heard of this field still sees the
		 * optional flag and renders it muted instead of alarm red.
		 */
		public Boolean bringAhead;
		/**
		 * A material for the step's PRODUCTS (redberries for the dyes),
		 * not the deliverable itself — the badge indents under the
		 * products with a muted "(ingredient)" tag.
		 */
		public Boolean ingredient;
		/**
		 * The QUEST HANDS YOU this item — Elena's plague sample, the Grand
		 * Tree's bark sample. You cannot fetch it from a bank or a shop, so
		 * a red "0/1" is misinformation and a bank detour for it is wrong
		 * (KIT-SEEDING POLICY, owner 2026-08-05). Renders muted with a
		 * "(from the quest)" tag and never routes.
		 *
		 * The item is still listed, and still auto-ticks when it lands:
		 * knowing the quest will give it to you is useful, being told to go
		 * shopping for it is not. Set it on a name a DETECTED goal already
		 * covers and the flag carries onto the goal (see StepRow's merge) —
		 * that is how the plague sample, which no seeder produced, is
		 * reachable at all.
		 */
		public Boolean granted;
		/**
		 * SPENT during the step itself — the mind bomb you drink before
		 * casting Camelot teleport. It is a true requirement (missing it
		 * stays red: go and get one), but it must not gate the step's
		 * arrival tick, because by the time you arrive it is GONE. Without
		 * this the step wedges forever: annotationItemsCarried reads 0/1
		 * and no landing can ever prove the journey.
		 *
		 * Same shape as the coins exclusion that arrival already hard-codes
		 * (a fare paid mid-step must not wedge the destination), just
		 * declarable per item.
		 */
		public Boolean consumed;
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
		 * A quest that must be FINISHED for this requirement to be met.
		 *
		 * For steps that ARE a list of quests: "Do these quests for QP and
		 * diaries: A soul's bane, Another slice of H.A.M, …" names thirteen,
		 * and the plugin could express "this skill level" or "this varbit"
		 * but never "these quests are done" — so a step whose completion is
		 * perfectly knowable sat as a hand tick.
		 *
		 * Meant for `requiresAll`, one entry per quest. The name is matched
		 * the same way every other quest name in this plugin is, aliases
		 * included, so "Rat catchers" and "Icthlarin's little helper" resolve
		 * despite the guide's casing.
		 *
		 * NOT a substitute for the `quest` TAG on the annotation root, which
		 * says "this step is a leg of that quest" and hands guidance to Quest
		 * Helper. This one only answers "is it finished?".
		 */
		public String quest;
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
