package com.ironscape.annotations;

import java.util.List;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;

/**
 * How far along an errand chain the player is.
 *
 * Pulled out of the plugin so the ORDER RULE can be tested without a game
 * client, because the rule is not obvious and getting it wrong is silent:
 * a chain that comes back on itself (into Keep Le Faye, up two floors,
 * fight, back down, out) marks the fight done on the way IN unless
 * position is judged in the right place.
 *
 * The rule, in one line: <b>monotonic conditions may look ahead,
 * reversible ones may not.</b>
 *
 * <ul>
 *   <li><b>Monotonic</b> — a quest var, or owning an item. These cannot
 *       come undone, so a later stage being satisfied proves every stage
 *       before it served its purpose ("the key disappears into the lock").
 *       That look-ahead is what rescues a leg the player teleported past.
 *   <li><b>Reversible</b> — where the player is standing. A position only
 *       proves progress at the moment the chain is waiting for it, so it
 *       is judged at the FRONT of the chain and nowhere else. The same
 *       rule already governs {@code requires.equipped}, which is
 *       frontier-only because worn gear comes off.
 * </ul>
 */
public final class ErrandProgress
{
	/** Everything about the world one chain needs to judge itself. */
	public interface World
	{
		int varValue(Integer varbit, Integer varp);

		/** In the inventory or worn — never the bank. */
		int carriedCount(String item);

		/** Carried or banked. */
		int totalCount(String item);

		/** Null when the player's position is not known this tick. */
		WorldPoint here();
	}

	/**
	 * How near an OPTIONAL leg must be before it speaks up.
	 *
	 * An optional leg is a side task that happens to sit near the route --
	 * a diary talk, a field of flax -- so "while you are here anyway" is
	 * literally its condition. Beyond this it is transparent: the chain
	 * runs past it as though it were not there.
	 */
	public static final int OPTIONAL_NUDGE_RADIUS = 40;

	private ErrandProgress()
	{
	}

	private static boolean optional(StepAnnotation.Errand stage)
	{
		return Boolean.TRUE.equals(stage.optional);
	}

	/**
	 * Advance {@code done} as far as the world allows and return the index
	 * of the first unsatisfied stage — {@code chain.size()} when the chain
	 * is complete. {@code done} is the caller's sticky set: a stage seen
	 * satisfied once stays satisfied for the session, because most of these
	 * items are spent on the very next stage.
	 */
	public static int advance(String stepId, List<StepAnnotation.Errand> chain,
		Set<String> done, World world)
	{
		// PASS 1 — monotonic conditions, judged anywhere in the chain.
		for (int i = 0; i < chain.size(); i++)
		{
			StepAnnotation.Errand stage = chain.get(i);
			if (optional(stage) || isPositional(stage)
				|| !monotonicSatisfied(stage, i, chain.size(), world))
			{
				continue;
			}
			if (Boolean.TRUE.equals(stage.given) || stage.bit != null)
			{
				// Hand-ins are INDEPENDENT: giving Da Vinci his ethenea
				// first must not mark Hops and Chancy done behind it. Only
				// the normal "the key served its purpose" cascade implies
				// the earlier stages.
				//
				// A BIT gate is the same shape, and this is why the rule is
				// worth stating rather than flagging per chain: a threshold
				// is ordinal, so reaching it really does imply everything
				// before it, but one bit says nothing whatever about
				// another. The ten Ardougne easy diary tasks are ten
				// unrelated errands packed into one varp, and cascading
				// from Aleck's Emporium in Yanille (bit 11, done) marked
				// the Fishing Trawler, the combat camp and Tindel's sword
				// done behind it — so the chain skipped all three and
				// routed straight to the cloak (owner, in play,
				// 2026-08-11, confirmed against his own diary tab).
				done.add(stageKey(stepId, chain, i));
			}
			else
			{
				for (int k = 0; k <= i; k++)
				{
					// ... and the cascade never reaches an OPTIONAL leg.
					// Carrying bat bones proves you walked past the flax
					// field; it does not prove you picked any. That
					// inference is exactly why the diary legs of the
					// Merlin chain had never once guided anyone.
					if (!optional(chain.get(k)))
					{
						done.add(stageKey(stepId, chain, k));
					}
				}
			}
		}
		// PASS 1b — OPTIONAL legs, each judged alone.
		//
		// They sit outside the ordering entirely: nothing implies them and
		// they imply nothing, so a side task can be done at any point, or
		// never. Positional ones get no "closer to the next stage" escape
		// either -- being on your way somewhere else is not picking flax.
		for (int i = 0; i < chain.size(); i++)
		{
			StepAnnotation.Errand stage = chain.get(i);
			if (!optional(stage))
			{
				continue;
			}
			boolean satisfied = isPositional(stage)
				? atStage(stage, world.here())
				: monotonicSatisfied(stage, i, chain.size(), world);
			if (satisfied)
			{
				done.add(stageKey(stepId, chain, i));
			}
		}
		// PASS 2 — positional conditions, at the front only. Everything
		// before the front is done by definition, so a satisfied front
		// stage marks only itself, and the loop then re-reads the front so
		// several legs can fall in one tick (walking out of a zone can
		// satisfy a leave-stage and the waypoint after it at once).
		for (int front = front(stepId, chain, done, world); front < chain.size();
			front = front(stepId, chain, done, world))
		{
			StepAnnotation.Errand stage = chain.get(front);
			if (!isPositional(stage) || !positionalSatisfied(stage, front, chain, world.here()))
			{
				break;
			}
			done.add(stageKey(stepId, chain, front));
		}
		return front(stepId, chain, done, world);
	}

	/**
	 * The leg to guide: the first unsatisfied one, except that an OPTIONAL
	 * leg is passed over unless the player is already near it.
	 *
	 * That is what makes it optional in both directions. It never blocks --
	 * skipping the diary can never wedge a quest chain, which the seeded
	 * note on the Sherlock leg was already worried about -- and it never
	 * disappears, because nothing behind it can imply it. It simply waits
	 * until you are in the area and then asks.
	 */
	public static int front(String stepId, List<StepAnnotation.Errand> chain,
		Set<String> done, World world)
	{
		for (int i = 0; i < chain.size(); i++)
		{
			StepAnnotation.Errand stage = chain.get(i);
			if (done.contains(stageKey(stepId, chain, i)))
			{
				continue;
			}
			if (optional(stage) && !nearStage(stage, world.here(), OPTIONAL_NUDGE_RADIUS))
			{
				continue;
			}
			return i;
		}
		return chain.size();
	}

	/**
	 * Is the chain done? OPTIONAL legs do not count -- a step whose work is
	 * its chain is finished when the work is, whether or not you took the
	 * diary detour on the way.
	 */
	public static boolean complete(String stepId, List<StepAnnotation.Errand> chain, Set<String> done)
	{
		for (int i = 0; i < chain.size(); i++)
		{
			if (!optional(chain.get(i)) && !done.contains(stageKey(stepId, chain, i)))
			{
				return false;
			}
		}
		return true;
	}

	private static boolean nearStage(StepAnnotation.Errand stage, WorldPoint here, int radius)
	{
		return here != null
			&& here.distanceTo2D(new WorldPoint(stage.x, stage.y, stage.plane)) <= radius;
	}

	/** Standing at the stage itself -- its own radius, no escape hatches. */
	private static boolean atStage(StepAnnotation.Errand stage, WorldPoint here)
	{
		if (stage.zone != null)
		{
			return here != null && Boolean.TRUE.equals(stage.leave) != stage.zone.contains(here);
		}
		if (stage.region != null)
		{
			return here != null
				&& Boolean.TRUE.equals(stage.leave) != (here.getRegionID() == stage.region);
		}
		return nearStage(stage, here, stage.radius != null ? stage.radius : 12);
	}

	/**
	 * Sticky-satisfaction key for one stage.
	 *
	 * Carries the stage's INDEX because a chain may legitimately visit one
	 * place twice — the first floor of Keep Le Faye is a leg on the way up
	 * and another on the way down — and a key built from coordinates alone
	 * would let the first visit tick the second.
	 */
	public static String stageKey(String stepId, List<StepAnnotation.Errand> chain, int index)
	{
		StepAnnotation.Errand stage = chain.get(index);
		String what;
		if (varGated(stage))
		{
			what = "var:" + (stage.varbit != null ? stage.varbit : "p" + stage.varp)
				+ (stage.bit != null ? "#" + stage.bit : ">=" + stage.value);
		}
		else
		{
			what = stage.item != null ? stage.item : "wp:" + stage.x + "," + stage.y;
		}
		return stepId + "|" + index + "|" + what;
	}

	/**
	 * Is this leg about WHERE THE PLAYER IS — a zone, a region, or a plain
	 * waypoint? A var gate wins over everything (quest progress orders
	 * stages that proximity cannot), and a zone or region wins over the
	 * item branches, so a positional stage can still carry an item for its
	 * badge while the position remains the gate.
	 */
	public static boolean isPositional(StepAnnotation.Errand stage)
	{
		if (varGated(stage))
		{
			return false;
		}
		return stage.zone != null || stage.region != null || stage.item == null;
	}


	/**
	 * Does this stage turn on a VAR reading rather than where you are or
	 * what you hold? Either a threshold (value) or a single bit — diaries
	 * pack a whole tier into one varp, so a bit test is the only honest
	 * question there. One helper because three places asked it separately
	 * and a new gate kind has to reach all three.
	 */
	private static boolean varGated(StepAnnotation.Errand stage)
	{
		return (stage.value != null || stage.bit != null)
			&& (stage.varbit != null || stage.varp != null);
	}

	private static boolean monotonicSatisfied(StepAnnotation.Errand stage, int index,
		int chainSize, World world)
	{
		if (varGated(stage))
		{
			int v = world.varValue(stage.varbit, stage.varp);
			// A bit test, never a threshold: see StepAnnotation.Errand.bit.
			return stage.bit != null ? (v & (1 << stage.bit)) != 0 : v >= stage.value;
		}
		if (stage.item == null)
		{
			return false;
		}
		if (Boolean.TRUE.equals(stage.given))
		{
			// HAND-IN stage: done once the item has left your hands.
			return world.carriedCount(stage.item) == 0;
		}
		// Intermediate stages count CARRIED only: quest keys are all
		// literally named "Key", and an unrelated one in the BANK must not
		// skip the crate. The LAST stage is the objective itself and may
		// sit banked — still done.
		return (index == chainSize - 1
			? world.totalCount(stage.item)
			: world.carriedCount(stage.item)) > 0;
	}

	private static boolean positionalSatisfied(StepAnnotation.Errand stage, int index,
		List<StepAnnotation.Errand> chain, WorldPoint here)
	{
		if (here == null)
		{
			return false;
		}
		if (stage.zone != null)
		{
			// Precise "am I in yet?", and the only condition that tells
			// FLOORS apart. `leave` inverts it: the way OUT of a one-way
			// interior is a leg with somewhere to be and something to
			// click, and no coordinate can express it, because every tile
			// outside the door is a few tiles from every tile inside it.
			return Boolean.TRUE.equals(stage.leave) != stage.zone.contains(here);
		}
		if (stage.region != null)
		{
			return Boolean.TRUE.equals(stage.leave) != (here.getRegionID() == stage.region);
		}
		// Item-less WAYPOINT stage (the cave entrance on the way to the
		// warriors): satisfied by getting there — or by being closer to the
		// NEXT stage than to it (a teleport skipped it; it served its
		// purpose either way).
		int radius = stage.radius != null ? stage.radius : 12;
		WorldPoint mine = new WorldPoint(stage.x, stage.y, stage.plane);
		if (here.distanceTo2D(mine) <= radius)
		{
			return true;
		}
		if (index + 1 >= chain.size())
		{
			return false;
		}
		StepAnnotation.Errand next = chain.get(index + 1);
		return here.distanceTo2D(new WorldPoint(next.x, next.y, next.plane))
			< here.distanceTo2D(mine);
	}

	/** One short line describing a stage: its note, else its item, else where it is. */
	public static String checklistLabel(StepAnnotation.Errand stage)
	{
		if (stage.note != null && !stage.note.trim().isEmpty())
		{
			// First SENTENCE of the note: a full stop followed by a space.
			//
			// Written with indexOf rather than a regex on purpose. This was
			// `split("(?<=\.)\s")` and reached the file as `split("(?<=.)s")`
			// — the escapes were eaten generating this method through a
			// script — which means "any character followed by an s", so
			// every label was chopped at its first lowercase s: "Ask
			// Wizard Cromperty…" rendered as "A". It cost three rebuilds
			// spent adjusting a layout that had been correct since the
			// first fix. Nothing to escape here, nothing to eat.
			String first = stage.note.trim();
			int sentence = first.indexOf(". ");
			if (sentence > 0)
			{
				first = first.substring(0, sentence + 1);
			}
			return first.length() > 90 ? first.substring(0, 88) + "..." : first;
		}
		if (stage.item != null)
		{
			return com.ironscape.items.ItemTracker.capitalize(stage.item);
		}
		return "Go to " + stage.x + ", " + stage.y;
	}
}
