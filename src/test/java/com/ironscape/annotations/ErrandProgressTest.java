package com.ironscape.annotations;

import com.google.gson.Gson;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The order rule, exercised on the journey that forced it into existence:
 * Merlin's Crystal sends you INTO Keep Le Faye, up two floors, through a
 * fight, and back down and out the way you came.
 *
 * That shape is what makes the rule load-bearing. The ground floor of the
 * keep is a leg on the way in AND a leg on the way out, so if position
 * were judged anywhere in the chain (as it was before), simply walking in
 * would satisfy the "back down to the ground floor" leg and the cascade
 * would mark Mordred dead before the fight.
 */
public class ErrandProgressTest
{
	/** A world the test moves around by hand. */
	private static class Fake implements ErrandProgress.World
	{
		WorldPoint at = new WorldPoint(2801, 3442, 0);
		int varp14;
		final Set<String> carried = new HashSet<>();

		@Override
		public int varValue(Integer varbit, Integer varp)
		{
			return varp != null && varp == 14 ? varp14 : 0;
		}

		@Override
		public int carriedCount(String item)
		{
			return carried.contains(item) ? 1 : 0;
		}

		@Override
		public int totalCount(String item)
		{
			return carriedCount(item);
		}

		@Override
		public WorldPoint here()
		{
			return at;
		}
	}

	private static StepAnnotation.Errand at(int x, int y, int plane)
	{
		StepAnnotation.Errand e = new StepAnnotation.Errand();
		e.x = x;
		e.y = y;
		e.plane = plane;
		return e;
	}

	private static StepAnnotation.Zone zone(int plane)
	{
		StepAnnotation.Zone z = new StepAnnotation.Zone();
		z.x1 = 2764;
		z.y1 = 3395;
		z.x2 = 2781;
		z.y2 = 3410;
		z.plane = plane;
		return z;
	}

	/**
	 * The whole Keep Le Faye journey, leg by leg. Each assertion is a
	 * place the player stands and the leg the chain should be pointing at.
	 */
	@Test
	public void guidesInUpFightAndBackOutAgain()
	{
		List<StepAnnotation.Errand> chain = new ArrayList<>();
		StepAnnotation.Errand enter = at(2801, 3442, 0);       // 0 the crate
		enter.zone = zone(0);
		chain.add(enter);
		StepAnnotation.Errand up1 = at(2770, 3405, 1);         // 1 up to floor 1
		up1.zone = zone(1);
		chain.add(up1);
		StepAnnotation.Errand up2 = at(2770, 3399, 2);         // 2 up to floor 2
		up2.zone = zone(2);
		chain.add(up2);
		StepAnnotation.Errand fight = at(2770, 3403, 2);       // 3 Mordred + Morgan
		fight.varp = 14;
		fight.value = 4;
		chain.add(fight);
		StepAnnotation.Errand down1 = at(2769, 3405, 1);       // 4 back down to 1
		down1.zone = zone(1);
		chain.add(down1);
		StepAnnotation.Errand down0 = at(2769, 3405, 0);       // 5 back down to ground
		down0.zone = zone(0);
		chain.add(down0);
		StepAnnotation.Errand candle = at(2797, 3440, 0);      // 6 the black candle
		candle.item = "black candle";
		chain.add(candle);

		Set<String> done = new HashSet<>();
		Fake w = new Fake();

		// Outside in Catherby: get in.
		assertEquals("outside, first leg is the crate", 0,
			ErrandProgress.advance("s", chain, done, w));

		// Smuggled in at ground level. THE REGRESSION GUARD: the ground
		// floor also matches the descent leg five stages later, and that
		// leg must NOT be judged from here.
		w.at = new WorldPoint(2770, 3406, 0);
		assertEquals("inside at ground level, next leg is the first staircase", 1,
			ErrandProgress.advance("s", chain, done, w));

		w.at = new WorldPoint(2770, 3404, 1);
		assertEquals("on floor 1, next leg is the second staircase", 2,
			ErrandProgress.advance("s", chain, done, w));

		w.at = new WorldPoint(2770, 3400, 2);
		assertEquals("on floor 2, next leg is the fight", 3,
			ErrandProgress.advance("s", chain, done, w));

		// Standing next to Mordred is not progress; only the varp is.
		assertEquals("still fighting", 3, ErrandProgress.advance("s", chain, done, w));

		w.varp14 = 4;
		assertEquals("Morgan done, now climb back down", 4,
			ErrandProgress.advance("s", chain, done, w));

		w.at = new WorldPoint(2769, 3405, 1);
		assertEquals("down one floor, one to go", 5,
			ErrandProgress.advance("s", chain, done, w));

		w.at = new WorldPoint(2769, 3405, 0);
		assertEquals("back at ground level, now leave and get the candle", 6,
			ErrandProgress.advance("s", chain, done, w));

		w.at = new WorldPoint(2797, 3441, 0);
		w.carried.add("black candle");
		assertEquals("chain complete", chain.size(),
			ErrandProgress.advance("s", chain, done, w));
	}

	/**
	 * Owning a later stage's item still rescues the legs before it — the
	 * look-ahead that positional stages deliberately do NOT get.
	 */
	@Test
	public void owningTheGoalStillClearsTheLegsBeforeIt()
	{
		List<StepAnnotation.Errand> chain = new ArrayList<>();
		chain.add(at(2548, 9565, 0));                          // a waypoint
		StepAnnotation.Errand key = at(2548, 9565, 0);
		key.item = "key";
		chain.add(key);
		StepAnnotation.Errand pebble = at(2514, 9580, 0);
		pebble.item = "glarial's pebble";
		chain.add(pebble);

		Set<String> done = new HashSet<>();
		Fake w = new Fake();
		w.at = new WorldPoint(3200, 3200, 0);                  // nowhere near
		w.carried.add("glarial's pebble");

		assertEquals("holding the pebble completes the chain", chain.size(),
			ErrandProgress.advance("s", chain, done, w));
	}

	/** A `leave` stage is done by getting OUT, and only then. */
	@Test
	public void leaveStageWaitsUntilYouAreOut()
	{
		List<StepAnnotation.Errand> chain = new ArrayList<>();
		StepAnnotation.Errand out = at(2769, 3405, 0);
		out.zone = zone(0);
		out.leave = true;
		chain.add(out);

		Set<String> done = new HashSet<>();
		Fake w = new Fake();
		w.at = new WorldPoint(2770, 3405, 0);                  // inside
		assertEquals("still inside", 0, ErrandProgress.advance("s", chain, done, w));

		w.at = new WorldPoint(2757, 3401, 0);                  // out by the bats
		assertEquals("out", chain.size(), ErrandProgress.advance("s", chain, done, w));
	}

	/**
	 * A region is 64x64, and region 11061 holds Keep Le Faye AND the giant
	 * bats the same chain sends you to two stages earlier. This is the
	 * measurement behind preferring zones — kept as a test so nobody
	 * "simplifies" a zone back to a region.
	 */
	@Test
	public void regionIsTooCoarseForTheKeep()
	{
		WorldPoint insideKeep = new WorldPoint(2770, 3403, 2);
		WorldPoint giantBats = new WorldPoint(2757, 3401, 0);
		assertEquals("same region", insideKeep.getRegionID(), giantBats.getRegionID());
		assertTrue("the zone tells them apart", zone(2).contains(insideKeep));
		assertTrue("the bats are outside every floor of the zone",
			!zone(0).contains(giantBats) && !zone(1).contains(giantBats)
				&& !zone(2).contains(giantBats));
	}

	/**
	 * The bundled chains, read through the real model. Catches a seeded
	 * zone whose plane disagrees with the stage it belongs to — the shape
	 * of "I set the box to the wrong floor", which nothing else would say
	 * out loud.
	 */
	@Test
	public void bundledZoneStagesAgreeWithTheirOwnPlane() throws Exception
	{
		try (Reader reader = new InputStreamReader(
			getClass().getResourceAsStream("/com/ironscape/annotations/annotations_oziris.json"),
			StandardCharsets.UTF_8))
		{
			com.google.gson.JsonObject file = new Gson().fromJson(reader,
				com.google.gson.JsonObject.class);
			com.google.gson.JsonObject annotations = file.getAsJsonObject("annotations");
			assertNotNull(annotations);
			int zoneStages = 0;
			for (String key : annotations.keySet())
			{
				StepAnnotation annotation = new Gson().fromJson(
					annotations.get(key), StepAnnotation.class);
				if (annotation.errands == null)
				{
					continue;
				}
				for (int i = 0; i < annotation.errands.size(); i++)
				{
					StepAnnotation.Errand stage = annotation.errands.get(i);
					if (stage.zone == null)
					{
						continue;
					}
					zoneStages++;
					assertEquals(key + " stage " + i + ": the stage sits on a different"
							+ " floor from the zone that satisfies it",
						stage.zone.plane, stage.plane);
				}
			}
			assertTrue("no zone stages found — did the corpus lose them?", zoneStages > 0);
		}
	}
}
