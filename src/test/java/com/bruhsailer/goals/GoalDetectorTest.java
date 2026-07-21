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
