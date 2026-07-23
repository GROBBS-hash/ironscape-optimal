package com.bruhsailer.items;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.assertTrue;

public class ItemAliasTest
{
	@org.junit.Test
	public void metalArrowheadsAreArrowtipsInGame()
	{
		org.junit.Assert.assertEquals("bronze arrowtips", ItemTracker.aliases("bronze arrowheads")[0]);
		org.junit.Assert.assertEquals("rune arrowtips", ItemTracker.aliases("rune arrowhead")[0]);
		// Broad arrowheads are genuinely named arrowheads — untouched.
		org.junit.Assert.assertEquals("broad arrowheads", ItemTracker.aliases("broad arrowheads")[0]);
	}

	private static boolean hasAlias(String guidePhrase, String realItemName)
	{
		return Arrays.asList(ItemTracker.aliases(guidePhrase)).contains(realItemName);
	}

	@Test
	public void guidePhrasesResolveToRealItemNames()
	{
		assertTrue(hasAlias("noted bolts of cloth", "bolt of cloth"));
		assertTrue(hasAlias("noted planks", "plank"));
		assertTrue(hasAlias("poh tab", "teleport to house"));
		assertTrue(hasAlias("gp", "coins"));
		assertTrue(hasAlias("mind", "mind rune"));
		assertTrue(hasAlias("log", "logs"));
	}

	@Test
	public void teleportTabPhrasesResolveToWikiNames()
	{
		assertTrue(hasAlias("house teleport", "teleport to house"));
		assertTrue(hasAlias("house teleports", "teleport to house"));
		assertTrue(hasAlias("rellekka tab", "rellekka teleport"));
		assertTrue(hasAlias("rimmington tab", "rimmington teleport"));
		assertTrue(hasAlias("trollheim tab", "trollheim teleport"));
	}
}
