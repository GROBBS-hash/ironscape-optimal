package com.bruhsailer.items;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.assertTrue;

public class ItemAliasTest
{
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
}
