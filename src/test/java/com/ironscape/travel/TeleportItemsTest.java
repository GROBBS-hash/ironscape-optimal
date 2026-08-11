package com.ironscape.travel;

import com.google.gson.Gson;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The rules that decide whether a teleport item is offered.
 *
 * Worth testing rather than eyeballing because every one of these
 * failures is silent in play: a teleport wrongly offered just looks like
 * a hint, and a teleport wrongly withheld looks like nothing at all.
 */
public class TeleportItemsTest
{
	/** Answers whatever the test sets, and NOTHING by default. */
	private static final class FakeState implements TeleportItems.Availability
	{
		final Set<Integer> carried = new HashSet<>();
		final Map<Integer, Integer> varbits = new HashMap<>();
		final Map<Integer, Integer> varplayers = new HashMap<>();
		final Map<String, Integer> skills = new HashMap<>();
		final Set<String> finishedQuests = new HashSet<>();
		int total = 0;
		int questPoints = 0;

		@Override
		public boolean carries(int itemId)
		{
			return carried.contains(itemId);
		}

		@Override
		public int varbit(int id)
		{
			return varbits.getOrDefault(id, 0);
		}

		@Override
		public int varplayer(int id)
		{
			return varplayers.getOrDefault(id, 0);
		}

		@Override
		public int skillLevel(String skill)
		{
			return skills.getOrDefault(skill, -1);
		}

		@Override
		public int totalLevel()
		{
			return total;
		}

		@Override
		public int questPoints()
		{
			return questPoints;
		}

		@Override
		public boolean questFinished(String questName)
		{
			return finishedQuests.contains(questName);
		}
	}

	private static TeleportItems.Entry parse(String json)
	{
		TeleportItems.Entry[] entries = new Gson().fromJson("[" + json + "]", TeleportItems.Entry[].class);
		return entries[0];
	}

	private final TeleportItems index = TeleportItems.load(new Gson());

	@Test
	public void bundledIndexLoads()
	{
		// The whole feature is data. If the resource stops loading, every
		// test below still passes against hand-built entries while the
		// plugin silently suggests nothing.
		assertTrue("bundled teleport index should not be empty", index.size() > 100);
	}

	@Test
	public void everyBundledEntryHasADestinationAndItems()
	{
		for (TeleportItems.Entry entry : index.all())
		{
			assertNotNull("entry with no label", entry.getDisplay());
			assertTrue("no item provides " + entry.getDisplay(), entry.getItemIds().length > 0);
			assertNotNull("no destination for " + entry.getDisplay(), entry.getDestination());
		}
	}

	@Test
	public void anyOneOfTheItemsIsEnough()
	{
		// The tiers of a diary cloak are alternatives, not a set to collect.
		TeleportItems.Entry cloak = parse(
			"{\"display\":\"Ardougne cloak: Kandarin Monastery\",\"itemIds\":[13121,13122,13123],"
				+ "\"x\":2607,\"y\":3221,\"plane\":0}");
		FakeState state = new FakeState();
		assertFalse(index.isAvailable(cloak, state));
		state.carried.add(13123);
		assertTrue(index.isAvailable(cloak, state));
	}

	@Test
	public void unlockVarbitGatesTheTeleport()
	{
		// Holding the cloak is not the same as having earned the tier that
		// unlocks this particular destination.
		TeleportItems.Entry farm = parse(
			"{\"display\":\"Ardougne cloak: Ardougne Farm\",\"itemIds\":[13123],"
				+ "\"x\":2665,\"y\":3374,\"plane\":0,"
				+ "\"varbits\":[{\"id\":4460,\"op\":\"=\",\"value\":1}]}");
		FakeState state = new FakeState();
		state.carried.add(13123);
		assertFalse("diary not done — must not be offered", index.isAvailable(farm, state));
		state.varbits.put(4460, 1);
		assertTrue(index.isAvailable(farm, state));
	}

	@Test
	public void chargesRunOut()
	{
		// "fewer than 5 used" — a less-than condition, which is how the
		// source data expresses remaining charges.
		TeleportItems.Entry limited = parse(
			"{\"display\":\"Ardougne cloak: Ardougne Farm\",\"itemIds\":[13123],"
				+ "\"x\":2665,\"y\":3374,\"plane\":0,"
				+ "\"varbits\":[{\"id\":6069,\"op\":\"<\",\"value\":5}]}");
		FakeState state = new FakeState();
		state.carried.add(13123);
		state.varbits.put(6069, 4);
		assertTrue(index.isAvailable(limited, state));
		state.varbits.put(6069, 5);
		assertFalse("no charges left — must not be offered", index.isAvailable(limited, state));
	}

	@Test
	public void bitmaskNeedsThatExactBit()
	{
		// The barcrawl trap: a `>=` here would let any other bit in the
		// same var satisfy an unrelated unlock.
		TeleportItems.Entry masked = parse(
			"{\"display\":\"Test\",\"itemIds\":[1],\"x\":1,\"y\":1,\"plane\":0,"
				+ "\"varbits\":[{\"id\":50,\"op\":\"&\",\"value\":4}]}");
		FakeState state = new FakeState();
		state.carried.add(1);
		state.varbits.put(50, 3);
		assertFalse("bits 1 and 2 set, bit 4 is not", index.isAvailable(masked, state));
		state.varbits.put(50, 5);
		assertTrue("bit 4 set alongside bit 1", index.isAvailable(masked, state));
	}

	@Test
	public void questGateFailsClosedOnAnUnknownName()
	{
		TeleportItems.Entry gated = parse(
			"{\"display\":\"Test\",\"itemIds\":[1],\"x\":1,\"y\":1,\"plane\":0,"
				+ "\"quests\":[\"Not A Real Quest\"]}");
		FakeState state = new FakeState();
		state.carried.add(1);
		assertFalse("an unresolved quest name must withhold the hint",
			index.isAvailable(gated, state));
	}

	@Test
	public void totalLevelAndQuestPointsAreNotSkills()
	{
		// The source data puts the max cape's total level and the quest
		// cape's quest points in the SKILLS column. Read as skill names
		// they resolve to nothing and the gate would fail forever.
		TeleportItems.Entry maxCape = parse(
			"{\"display\":\"Max cape\",\"itemIds\":[1],\"x\":1,\"y\":1,\"plane\":0,"
				+ "\"skills\":[{\"level\":2376,\"skill\":\"TOTAL\"}]}");
		FakeState state = new FakeState();
		state.carried.add(1);
		assertFalse(index.isAvailable(maxCape, state));
		state.total = 2376;
		assertTrue(index.isAvailable(maxCape, state));

		TeleportItems.Entry questCape = parse(
			"{\"display\":\"Quest cape\",\"itemIds\":[2],\"x\":1,\"y\":1,\"plane\":0,"
				+ "\"skills\":[{\"level\":327,\"skill\":\"QUEST\"}]}");
		state.carried.add(2);
		assertFalse(index.isAvailable(questCape, state));
		state.questPoints = 327;
		assertTrue(index.isAvailable(questCape, state));
	}

	@Test
	public void unknownSkillNameFailsClosed()
	{
		TeleportItems.Entry odd = parse(
			"{\"display\":\"Test\",\"itemIds\":[1],\"x\":1,\"y\":1,\"plane\":0,"
				+ "\"skills\":[{\"level\":50,\"skill\":\"NOTASKILL\"}]}");
		FakeState state = new FakeState();
		state.carried.add(1);
		assertFalse(index.isAvailable(odd, state));
	}

	@Test
	public void availableListsOnlyWhatCanBeUsedNow()
	{
		FakeState nothing = new FakeState();
		assertEquals("carrying nothing means no teleports",
			0, index.available(nothing).size());
	}

	@Test
	public void itemLabelDropsTheDestination()
	{
		assertEquals("Ardougne cloak", parse(
			"{\"display\":\"Ardougne cloak: Kandarin Monastery\",\"itemIds\":[1],"
				+ "\"x\":1,\"y\":1,\"plane\":0}").itemLabel());
		assertEquals("Digsite teleport", parse(
			"{\"display\":\"Digsite teleport\",\"itemIds\":[1],\"x\":1,\"y\":1,\"plane\":0}")
			.itemLabel());
	}
}
