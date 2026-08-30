package com.ironscape;

import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The arrival latch survives a restart, which is the whole point of storing
 * it: the moment it is needed most is a login INSIDE the place the pin is the
 * door to, and there is no arrival left to witness then. He logged in in the
 * Rogues' Den maze and the login resume drew him straight back out.
 *
 * <p>Anything unreadable must come back null rather than throw — a stored
 * value can be edited, truncated, or written by an older build, and a route
 * that stops working is a far worse failure than one extra route.
 */
public class ReachedTargetTest
{
	@Test
	public void roundTripsAPoint()
	{
		WorldPoint stored = IronscapePlugin.parsePoint("2905,3537,0");
		assertEquals(new WorldPoint(2905, 3537, 0), stored);
		assertEquals(new WorldPoint(3056, 4990, 1),
			IronscapePlugin.parsePoint(" 3056 , 4990 , 1 "));
	}

	@Test
	public void unreadableValuesAreNullNotAnException()
	{
		assertNull(IronscapePlugin.parsePoint(null));
		assertNull(IronscapePlugin.parsePoint(""));
		assertNull(IronscapePlugin.parsePoint("2905,3537"));
		assertNull(IronscapePlugin.parsePoint("2905,3537,0,4"));
		assertNull(IronscapePlugin.parsePoint("Rogues' Den"));
		assertNull(IronscapePlugin.parsePoint("2905,x,0"));
	}
}
