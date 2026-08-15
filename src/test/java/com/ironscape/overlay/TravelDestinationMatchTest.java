package com.ironscape.overlay;

import java.util.Locale;
import java.util.Set;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

/**
 * The word-set rule that decides whether a menu entry names where the
 * current step is sending you.
 *
 * <p>It is shared by two callers that look nothing alike: the Adventure
 * Log travel list, and ORDINARY CHAT OPTIONS — a magic carpet offers its
 * destinations through the dialogue widget, which is why the step named
 * Pollnivneach, the menu offered it, and nothing pointed at it (session
 * log, 2026-08-15). These tests exist because a wrongly highlighted
 * dialogue option is worse than none: you act on it before reading it.
 */
public class TravelDestinationMatchTest
{
	/** The words the plugin builds from the sub text + 📍 tag + travelVia. */
	private static Set<String> wordsOf(String stepText)
	{
		Set<String> words = new java.util.HashSet<>();
		for (String token : stepText.toLowerCase(Locale.ROOT).split("[^a-z0-9']+"))
		{
			if (!token.isEmpty())
			{
				words.add(token);
			}
		}
		return words;
	}

	@Test
	public void carpetDestinationNamedByTheStepMatches()
	{
		Set<String> words = wordsOf("Carpet to Pollnivneach and do the Feud");
		assertTrue(TravelMenuOverlay.destinationMatches("Pollnivneach", words));
	}

	@Test
	public void theOtherCarpetStopDoesNot()
	{
		// The real menu offers both. Highlighting the one the step does not
		// name is the failure this rule has to avoid.
		Set<String> words = wordsOf("Carpet to Pollnivneach and do the Feud");
		assertFalse(TravelMenuOverlay.destinationMatches("Bedabin camp", words));
		assertFalse(TravelMenuOverlay.destinationMatches("Cancel", words));
	}

	@Test
	public void wordOrderAndFillerDoNotMatter()
	{
		// The guide says "Khazard Battlefield", the menu "Battlefield of
		// Khazard" — this is why the rule is a word SET and not a phrase.
		Set<String> words = wordsOf("Spirit tree to Khazard Battlefield");
		assertTrue(TravelMenuOverlay.destinationMatches("Battlefield of Khazard", words));
	}

	@Test
	public void listDecorationIsStripped()
	{
		// Two prefixes, both seen in play: the Adventure Log numbers its
		// rows, and a chat menu tags the option under the cursor.
		assertEquals("Pollnivneach", TravelMenuOverlay.cleanEntry("[2] Pollnivneach"));
		assertEquals("Battlefield of Khazard",
			TravelMenuOverlay.cleanEntry("<col=ff9040>3: Battlefield of Khazard</col>"));
	}

	@Test
	public void anEntryWithNoUsableWordsNeverMatches()
	{
		// Every token being a stopword must not read as "all tokens found".
		assertFalse(TravelMenuOverlay.destinationMatches("the", wordsOf("Carpet to the camp")));
		assertFalse(TravelMenuOverlay.destinationMatches("", wordsOf("Carpet to Pollnivneach")));
	}
}
