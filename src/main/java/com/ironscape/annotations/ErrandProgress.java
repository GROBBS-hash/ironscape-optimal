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

	private ErrandProgress()
	{
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
			if (isPositional(stage) || !monotonicSatisfied(stage, i, chain.size(), world))
			{
				continue;
			}
			if (Boolean.TRUE.equals(stage.given))
			{
				// Hand-ins are INDEPENDENT: giving Da Vinci his ethenea
				// first must not mark Hops and Chancy done behind it. Only
				// the normal "the key served its purpose" cascade implies
				// the earlier stages.
				done.add(stageKey(stepId, chain, i));
			}
			else
			{
				for (int k = 0; k <= i; k++)
				{
					done.add(stageKey(stepId, chain, k));
				}
			}
		}
		// PASS 2 — positional conditions, at the front only. Everything
		// before the front is done by definition, so a satisfied front
		// stage marks only itself, and the loop then re-reads the front so
		// several legs can fall in one tick (walking out of a zone can
		// satisfy a leave-stage and the waypoint after it at once).
		for (int front = front(stepId, chain, done); front < chain.size();
			front = front(stepId, chain, done))
		{
			StepAnnotation.Errand stage = chain.get(front);
			if (!isPositional(stage) || !positionalSatisfied(stage, front, chain, world.here()))
			{
				break;
			}
			done.add(stageKey(stepId, chain, front));
		}
		return front(stepId, chain, done);
	}

	/** First stage not in {@code done}; {@code chain.size()} when all are. */
	public static int front(String stepId, List<StepAnnotation.Errand> chain, Set<String> done)
	{
		for (int i = 0; i < chain.size(); i++)
		{
			if (!done.contains(stageKey(stepId, chain, i)))
			{
				return i;
			}
		}
		return chain.size();
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
		if (stage.value != null && (stage.varbit != null || stage.varp != null))
		{
			what = "var:" + (stage.varbit != null ? stage.varbit : "p" + stage.varp)
				+ ">=" + stage.value;
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
		if (stage.value != null && (stage.varbit != null || stage.varp != null))
		{
			return false;
		}
		return stage.zone != null || stage.region != null || stage.item == null;
	}

	private static boolean monotonicSatisfied(StepAnnotation.Errand stage, int index,
		int chainSize, World world)
	{
		if (stage.value != null && (stage.varbit != null || stage.varp != null))
		{
			return world.varValue(stage.varbit, stage.varp) >= stage.value;
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
}
