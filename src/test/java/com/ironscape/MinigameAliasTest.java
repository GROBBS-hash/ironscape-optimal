package com.ironscape;

import java.lang.reflect.Method;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

/**
 * The guide's spelling of a minigame has to reach the game's.
 *
 * <p>"Minigame tele to Burthrope games' room" is step 299, and the game's
 * Minigames list says "Burthorpe Games Room". The two differ by a
 * TRANSPOSITION — burth<b>ro</b>pe against burth<b>or</b>pe — so neither is
 * a prefix of the other, and the slang-tolerant word matching that pairs
 * "fish trawler" with "Fishing Trawler" cannot bridge it. The owner met it
 * as a Minigames list in which every entry highlighted except the one he
 * wanted (2026-08-19).
 *
 * <p>Guarding it because the failure is SILENT: the picker simply does not
 * light up, and the same string also decides GROUPING_MINIGAMES membership
 * and the landing lookup, so one typo quietly disables three things.
 */
public class MinigameAliasTest
{
	private static String canonical(String name) throws Exception
	{
		Method m = IronscapePlugin.class.getDeclaredMethod("canonicalMinigame", String.class);
		m.setAccessible(true);
		return (String) m.invoke(null, name);
	}

	@Test
	public void theGuidesSpellingReachesTheGames() throws Exception
	{
		assertEquals("Burthorpe Games Room", canonical("Burthrope games' room"));
		assertEquals("Burthorpe Games Room", canonical("burthrope games room"));
	}

	@Test
	public void aBareTownNameMeansItsOnlyGroupingTeleport() throws Exception
	{
		// Step 304 says just "minigame tele to Burthrope", and the games
		// room is the only Grouping teleport that lands there.
		assertEquals("Burthorpe Games Room", canonical("Burthrope"));
	}

	@Test
	public void anythingElseIsLeftAlone() throws Exception
	{
		// The map is hand-authored, so a name it does not know must pass
		// through untouched rather than be guessed at.
		assertEquals("Castle Wars", canonical("Castle Wars"));
		assertEquals("Soul Wars", canonical("Soul Wars"));
	}
}
