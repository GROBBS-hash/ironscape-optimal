package com.ironscape.items;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
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

	/**
	 * "Grab 4 copper" is what every guide says; the item is "Copper ore".
	 * The badge sat at 0/4 next to a copper-ore icon, because item_ids gave
	 * the name a SPRITE while counting matched by NAME and found nothing
	 * (owner, in play). Same shorthand as the elemental runes, one metal
	 * over.
	 */
	@Test
	public void bareOreNamesResolveToTheOre()
	{
		assertTrue(hasAlias("copper", "copper ore"));
		assertTrue(hasAlias("tin", "tin ore"));
		assertTrue(hasAlias("iron", "iron ore"));
		// The plural the guide actually writes must work too.
		assertTrue(hasAlias("coppers", "copper ore"));
	}

	/**
	 * A goal that spells out a dose means THAT dose.
	 *
	 * canonical() folds "(4)" away so that "drink a restore potion" is
	 * satisfied by any dose, which is right nearly everywhere and wrong
	 * for "decant them until you have like 6 full pots": before this,
	 * twenty-four 1-dose vials read 6/6 while the step's entire job is
	 * turning them into four-dose ones. Written to fail under the old
	 * rule — the first assertion is the one that did.
	 */
	@Test
	public void aStatedDoseMeansThatDose()
	{
		assertFalse(ItemTracker.nameMatchesGoal("Superantipoison(1)", "superantipoison(4)"));
		assertFalse(ItemTracker.nameMatchesGoal("Superantipoison(2)", "superantipoison(4)"));
		assertTrue(ItemTracker.nameMatchesGoal("Superantipoison(4)", "superantipoison(4)"));
		// Dose-LESS goals keep taking any dose, which is most of the guide.
		assertTrue(ItemTracker.nameMatchesGoal("Superantipoison(1)", "super antipoison"));
		assertTrue(ItemTracker.nameMatchesGoal("Restore potion(3)", "restore potion"));
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

	/**
	 * A parenthetical that names a VARIANT picks that variant only.
	 *
	 * The priest gown is two separate items, "(top)" and "(bottom)", both
	 * needed for Biohazard. Buying one half ticked the whole shopping step
	 * because each half satisfied both annotation entries. Same shape as the
	 * dose rule above: naming the variant means that variant.
	 */
	@Test
	public void variantParentheticalsAreDistinctItems()
	{
		assertTrue(ItemTracker.nameMatchesGoal("Priest gown (top)", "priest gown (top)"));
		assertTrue(ItemTracker.nameMatchesGoal("Priest gown (bottom)", "priest gown (bottom)"));
		assertFalse(ItemTracker.nameMatchesGoal("Priest gown (top)", "priest gown (bottom)"));
		assertFalse(ItemTracker.nameMatchesGoal("Priest gown (bottom)", "priest gown (top)"));
		// KNOWN GAP, deliberately not asserted either way: a goal naming
		// neither half ("priest gown") matches NEITHER, because canonical()
		// keeps the variant word. Nothing relies on it — the guide says
		// "priest robes", which the colloquial map sends to the top — so it
		// is recorded here rather than fixed on speculation.
	}
}
