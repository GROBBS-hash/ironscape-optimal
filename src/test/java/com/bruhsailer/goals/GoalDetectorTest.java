package com.bruhsailer.goals;

import com.bruhsailer.guide.Guide;
import com.bruhsailer.guide.GuideChapter;
import com.bruhsailer.guide.GuideSection;
import com.bruhsailer.guide.GuideStep;
import com.bruhsailer.guide.GuideVariant;
import com.bruhsailer.guide.SubStep;
import com.bruhsailer.guide.TextRun;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.runelite.api.Quest;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GoalDetectorTest
{
	private static Guide guideWithSubTexts(String... texts)
	{
		List<SubStep> subs = Arrays.stream(texts)
			.map(t -> new SubStep("parent:" + t.hashCode(), 0, 0, t,
				Collections.singletonList(new TextRun(t, false, false, false, false, null, null))))
			.collect(Collectors.toList());
		GuideStep step = new GuideStep("parent", 0, 0, 0, 0, String.join(" ", texts),
			Collections.emptyList(), subs, Collections.emptyList(),
			Collections.emptyList(), Collections.emptyMap());
		GuideSection section = new GuideSection("1.1: test", Collections.singletonList(step));
		GuideChapter chapter = new GuideChapter("c1", Collections.singletonList(section), Collections.emptyList());
		return new Guide(GuideVariant.MAIN, "today", "test",
			Collections.singletonList(chapter),
			Collections.singletonList(step),
			Map.of(step.getId(), step));
	}

	@Test
	public void detectsItemGoalsWithQuantities()
	{
		Guide guide = guideWithSubTexts(
			"Grab 110 logs and bank them",
			"buy 20 balls of wool from the shop",
			"grab a knife"); // no number -> quantity 1

		GoalDetector.Goals goals = GoalDetector.detect(guide);

		assertEquals(3, goals.getItemGoals().size());
		assertEquals("logs", goals.getItemGoals().get(0).getItemName());
		assertEquals(110, goals.getItemGoals().get(0).getQuantity());
		assertEquals("balls of wool", goals.getItemGoals().get(1).getItemName());
		assertEquals(20, goals.getItemGoals().get(1).getQuantity());
		assertEquals("knife", goals.getItemGoals().get(2).getItemName());
		assertEquals(1, goals.getItemGoals().get(2).getQuantity());
	}

	@Test
	public void herbCountsAsHerbloreNotAnItem()
	{
		// Oziris writes "UNTIL 77 herb" — a level target, not 77 herbs.
		Guide guide = guideWithSubTexts(
			"Use every book and lamp on Herblore UNTIL 77 herb, after that use them on agility");

		GoalDetector.Goals goals = GoalDetector.detect(guide);

		assertTrue(goals.getItemGoals().isEmpty());
		assertEquals(1, goals.getSkillLevelGoals().size());
		assertEquals(net.runelite.api.Skill.HERBLORE, goals.getSkillLevelGoals().get(0).getSkill());
		assertEquals(77, goals.getSkillLevelGoals().get(0).getLevel());
	}

	@Test
	public void agilityLapAndLeadingConnectiveProduceActionGoals()
	{
		Guide guide = guideWithSubTexts(
			"and do a lap of the Shayzien agility course for the diary.",
			"then chop a regular tree");

		GoalDetector.Goals goals = GoalDetector.detect(guide);

		assertEquals(2, goals.getSkillActionGoals().size());
		assertEquals(net.runelite.api.Skill.AGILITY, goals.getSkillActionGoals().get(0).getSkill());
		assertEquals(net.runelite.api.Skill.WOODCUTTING, goals.getSkillActionGoals().get(1).getSkill());
	}

	@Test
	public void buyIsAnAcquisitionAndBareContinuationsInheritIt()
	{
		Guide guide = guideWithSubTexts(
			"While visiting Jennifer, buy shears from her shop", // purchase
			"a chef's hat",     // bare continuation of the buy list
			"grab a knife",     // own verb "grab" resets the list: not a purchase
			"an apron");        // continues the GRAB list

		GoalDetector.Goals goals = GoalDetector.detect(guide);

		assertEquals(4, goals.getItemGoals().size());
		assertEquals("shears", goals.getItemGoals().get(0).getItemName());
		assertTrue(goals.getItemGoals().get(0).isAcquisition());
		assertEquals("chef's hat", goals.getItemGoals().get(1).getItemName());
		assertTrue(goals.getItemGoals().get(1).isAcquisition());
		assertFalse(goals.getItemGoals().get(2).isAcquisition());
		assertEquals("apron", goals.getItemGoals().get(3).getItemName());
		assertFalse(goals.getItemGoals().get(3).isAcquisition());
	}

	@Test
	public void expandsSlashCompoundsAndSkipsSkillPhrases()
	{
		Guide guide = guideWithSubTexts(
			"buy 30 air/mind runes",
			"get 43 prayer"); // a level, not an item

		GoalDetector.Goals goals = GoalDetector.detect(guide);

		assertEquals(2, goals.getItemGoals().size());
		assertEquals("air runes", goals.getItemGoals().get(0).getItemName());
		assertEquals("mind runes", goals.getItemGoals().get(1).getItemName());
		assertEquals(30, goals.getItemGoals().get(0).getQuantity());
	}

	@Test
	public void detectsLeadingQuantityFragments()
	{
		// clause splitting leaves "700 law runes" with the verb in the
		// previous fragment
		Guide guide = guideWithSubTexts("700 law runes");

		GoalDetector.Goals goals = GoalDetector.detect(guide);

		assertEquals(1, goals.getItemGoals().size());
		assertEquals("law runes", goals.getItemGoals().get(0).getItemName());
		assertEquals(700, goals.getItemGoals().get(0).getQuantity());
	}

	@Test
	public void detectsChainedQuantitiesInVerblessFragments()
	{
		Guide guide = guideWithSubTexts("50 earth and 20 fire runes (no rune packs)");

		GoalDetector.Goals goals = GoalDetector.detect(guide);

		assertEquals(2, goals.getItemGoals().size());
		assertEquals("earth", goals.getItemGoals().get(0).getItemName());
		assertEquals(50, goals.getItemGoals().get(0).getQuantity());
		assertEquals("fire runes", goals.getItemGoals().get(1).getItemName());
		assertEquals(20, goals.getItemGoals().get(1).getQuantity());
	}

	@Test
	public void detectsUnnumberedItemsAndListContinuations()
	{
		// One step split into fragments, like the Varrock East bank step.
		Guide guide = guideWithSubTexts(
			"Go to the Varrock East bank.",             // action: no goal, breaks nothing
			"Grab a bucket of milk",                    // verb, no number -> qty 1, starts list
			"raw sardine",                              // bare continuation -> qty 1
			"3650 gp",                                  // number + gp
			"knife and all but 10 of your logs (etc)"); // continuation, name cut at "and"

		GoalDetector.Goals goals = GoalDetector.detect(guide);

		assertEquals(4, goals.getItemGoals().size());
		assertEquals("bucket of milk", goals.getItemGoals().get(0).getItemName());
		assertEquals(1, goals.getItemGoals().get(0).getQuantity());
		assertEquals("raw sardine", goals.getItemGoals().get(1).getItemName());
		assertEquals("gp", goals.getItemGoals().get(2).getItemName());
		assertEquals(3650, goals.getItemGoals().get(2).getQuantity());
		assertEquals("knife", goals.getItemGoals().get(3).getItemName());
	}

	@Test
	public void detectsSkillLevelGoalsAndSuppressesActionGoals()
	{
		Guide guide = guideWithSubTexts(
			// the step 16 shape: action verb AND a level target — the level
			// must own the sub, or the first chop's xp drop would tick it
			"Chop teak logs on a forestry world and burn them to level 50 firemaking (note: 2t would be faster)",
			"get 40 attack and 30 strength",
			"until 70 range");

		GoalDetector.Goals goals = GoalDetector.detect(guide);

		assertEquals(4, goals.getSkillLevelGoals().size());
		assertEquals(net.runelite.api.Skill.FIREMAKING, goals.getSkillLevelGoals().get(0).getSkill());
		assertEquals(50, goals.getSkillLevelGoals().get(0).getLevel());
		assertEquals(net.runelite.api.Skill.ATTACK, goals.getSkillLevelGoals().get(1).getSkill());
		assertEquals(40, goals.getSkillLevelGoals().get(1).getLevel());
		assertEquals(net.runelite.api.Skill.STRENGTH, goals.getSkillLevelGoals().get(2).getSkill());
		assertEquals(net.runelite.api.Skill.RANGED, goals.getSkillLevelGoals().get(3).getSkill());
		assertEquals(70, goals.getSkillLevelGoals().get(3).getLevel());

		// no "chop -> woodcutting xp ticks it" goal on the level sub
		assertTrue(goals.getSkillActionGoals().isEmpty());
		// and levels never masquerade as items
		assertTrue(goals.getItemGoals().isEmpty());
	}

	@Test
	public void questPhrasesMatchWithAndWithoutArticle()
	{
		Guide guide = guideWithSubTexts(
			"Complete the Tower of Life.",   // article — used to silently not match
			"Start Rune Mysteries");

		GoalDetector.Goals goals = GoalDetector.detect(guide);

		assertEquals(2, goals.getQuestGoals().size());
		assertEquals(net.runelite.api.Quest.TOWER_OF_LIFE, goals.getQuestGoals().get(0).getQuest());
		assertTrue(goals.getQuestGoals().get(0).isRequiresFinished());
		assertEquals(net.runelite.api.Quest.RUNE_MYSTERIES, goals.getQuestGoals().get(1).getQuest());
		assertFalse(goals.getQuestGoals().get(1).isRequiresFinished());
	}

	@Test
	public void articleLedListContinuationIsAnItemNotATravelGoal()
	{
		// The bank-withdrawal list from step 17: "a house teleport" is an
		// ITEM to grab. Before the article strip it produced no item goal
		// and — containing "teleport" — became a travel sub that ANY
		// position jump ticked.
		Guide guide = guideWithSubTexts(
			"grab your gp",
			"one beer",
			"a house teleport");

		GoalDetector.Goals goals = GoalDetector.detect(guide);

		assertEquals(3, goals.getItemGoals().size());
		assertEquals("house teleport", goals.getItemGoals().get(2).getItemName());
		assertEquals(1, goals.getItemGoals().get(2).getQuantity());
		assertTrue(goals.getTravelGoals().isEmpty());
	}

	@Test
	public void detectsMinigameTeleportNames()
	{
		Guide guide = guideWithSubTexts(
			"Minigame teleport to Soul Wars.",
			"Minigame teleport to Fishing Trawler",
			"Teleport to Varrock."); // a spell, not a minigame teleport

		GoalDetector.Goals goals = GoalDetector.detect(guide);

		assertEquals(2, goals.getMinigameTeleportGoals().size());
		assertEquals("Soul Wars", goals.getMinigameTeleportGoals().get(0).getMinigame());
		assertEquals("Fishing Trawler", goals.getMinigameTeleportGoals().get(1).getMinigame());
		// they stay travel goals too — the position jump still ticks them
		assertEquals(3, goals.getTravelGoals().size());
	}

	@Test
	public void detectsSkillActionAndProductGoals()
	{
		Guide guide = guideWithSubTexts(
			"Chop down a dying tree.",          // action verb -> WOODCUTTING xp goal
			"Make the logs into a plank",       // product goal: own a plank
			"turn the waxwood logs into planks."); // product goal: planks

		GoalDetector.Goals goals = GoalDetector.detect(guide);

		assertEquals(1, goals.getSkillActionGoals().size());
		assertEquals(net.runelite.api.Skill.WOODCUTTING,
			goals.getSkillActionGoals().get(0).getSkill());

		assertEquals(2, goals.getItemGoals().size());
		assertEquals("plank", goals.getItemGoals().get(0).getItemName());
		assertEquals(1, goals.getItemGoals().get(0).getQuantity());
		assertEquals("planks", goals.getItemGoals().get(1).getItemName());
	}

	@Test
	public void ignoresParentheticalCommentary()
	{
		Guide guide = guideWithSubTexts(
			"5 noted bolts of cloth and 5 noted planks (so there are 3 planks left in your bank)");

		GoalDetector.Goals goals = GoalDetector.detect(guide);

		assertEquals(2, goals.getItemGoals().size());
		assertEquals("noted bolts of cloth", goals.getItemGoals().get(0).getItemName());
		assertEquals("noted planks", goals.getItemGoals().get(1).getItemName());
	}

	@Test
	public void handlesNumberWordsAndShortContinuations()
	{
		Guide guide = guideWithSubTexts(
			"grab your hammer", // starts the list
			"saw",
			"one POH tab",
			"GP");

		GoalDetector.Goals goals = GoalDetector.detect(guide);

		assertEquals(4, goals.getItemGoals().size());
		assertEquals("hammer", goals.getItemGoals().get(0).getItemName());
		assertEquals("saw", goals.getItemGoals().get(1).getItemName());
		assertEquals("poh tab", goals.getItemGoals().get(2).getItemName());
		assertEquals(1, goals.getItemGoals().get(2).getQuantity());
		assertEquals("gp", goals.getItemGoals().get(3).getItemName());
	}

	@Test
	public void questNamesMatchDespiteCurlyApostrophes()
	{
		// the guide's Google-Docs text uses ’, the Quest enum uses '
		Guide guide = guideWithSubTexts(
			"Talk to Hetty to start Witch’s Potion",
			"then return to Hetty complete Witch’s Potion.");

		GoalDetector.Goals goals = GoalDetector.detect(guide);

		assertEquals(2, goals.getQuestGoals().size());
		assertEquals(Quest.WITCHS_POTION, goals.getQuestGoals().get(0).getQuest());
		assertFalse(goals.getQuestGoals().get(0).isRequiresFinished());
		assertTrue("return-and-complete must wait for FINISHED",
			goals.getQuestGoals().get(1).isRequiresFinished());
	}

	@Test
	public void detectsInteractionGoals()
	{
		Guide guide = guideWithSubTexts(
			"Give the letter to Romeo.",   // interaction: needs consumption
			"Fix his house",               // interaction
			"Teleport using the chronicle"); // travel, not interaction

		GoalDetector.Goals goals = GoalDetector.detect(guide);

		assertEquals(2, goals.getInteractionGoals().size());
		assertEquals(1, goals.getTravelGoals().size());
	}

	@Test
	public void actionFragmentsBreakTheItemList()
	{
		Guide guide = guideWithSubTexts(
			"Grab a knife",           // starts a list
			"Talk to the banker",     // action -> breaks the list
			"raw sardine");           // NOT a continuation any more

		GoalDetector.Goals goals = GoalDetector.detect(guide);

		assertEquals(1, goals.getItemGoals().size());
		assertEquals("knife", goals.getItemGoals().get(0).getItemName());
	}

	@Test
	public void detectsQuestStartAndFinishGoals()
	{
		Guide guide = guideWithSubTexts(
			"Talk to Duke Horacio to start Rune Mysteries",
			"Complete Cook's Assistant",
			"walk to the castle"); // no quest mention

		GoalDetector.Goals goals = GoalDetector.detect(guide);

		assertEquals(2, goals.getQuestGoals().size());
		assertEquals(Quest.RUNE_MYSTERIES, goals.getQuestGoals().get(0).getQuest());
		assertFalse("starting the quest should be enough", goals.getQuestGoals().get(0).isRequiresFinished());
		assertEquals(Quest.COOKS_ASSISTANT, goals.getQuestGoals().get(1).getQuest());
		assertTrue(goals.getQuestGoals().get(1).isRequiresFinished());
	}
}
