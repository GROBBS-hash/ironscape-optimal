package com.bruhsailer.goals;

import com.bruhsailer.guide.Guide;
import com.bruhsailer.guide.GuideStep;
import com.bruhsailer.guide.SubStep;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Value;
import net.runelite.api.Quest;
import net.runelite.api.Skill;

/**
 * Reads completion GOALS straight out of sub-step text, no annotations
 * needed:
 *
 *  - "Grab 110 logs", "buy 20 balls of wool"  -> item goal: auto-tick
 *    once you own that many (inventory + equipment + bank snapshot).
 *  - "Start Rune Mysteries" / "Complete Dragon Slayer" -> quest goal:
 *    auto-tick when the quest reaches that state (RuneLite's Quest API
 *    reads this for every quest).
 *
 * Deliberately conservative: item goals REQUIRE an explicit number —
 * "grab a knife" is ambiguous prose, "grab 5 logs" is a measurable goal.
 * Run once per guide load; the plugin checks the resulting lists against
 * live game state.
 */
public final class GoalDetector
{
	/**
	 * "number + item" pairs — clause splitting turns
	 * "Buy 1250 nature runes, 700 law runes, 50 earth and 20 fire runes"
	 * into fragments where only the first has the verb; the rest start with
	 * their quantity and may chain more with "and".
	 */
	private static final Pattern QUANTITY_NAME = Pattern.compile(
		"(\\d[\\d,]*)\\s+([a-z][a-z'/ -]+)", Pattern.CASE_INSENSITIVE);

	/** Words that end an item name ("110 logs AND bank them" -> "logs"). */
	private static final Pattern NAME_TERMINATOR = Pattern.compile(
		"\\s+(?:and|from|at|in|to|for|so|until|then|with|on|off|per)\\b.*|[(.,].*");

	/** "air/mind runes" — two alternatives sharing a suffix word. */
	private static final Pattern COMPOUND_NAME = Pattern.compile(
		"([a-z]+)/([a-z]+)\\s+([a-z' ]+)");

	/** "get 43 prayer" is a skill goal (handled by annotations), not an item. */
	private static final java.util.Set<String> SKILL_WORDS = java.util.Set.of(
		"attack", "strength", "defence", "defense", "hitpoints", "hp", "ranged", "range",
		"prayer", "magic", "cooking", "woodcutting", "fletching", "fishing", "firemaking",
		"crafting", "smithing", "mining", "herblore", "agility", "thieving", "slayer",
		"farming", "runecraft", "runecrafting", "hunter", "construction", "combat");

	/** "Grab a bucket of milk" — acquisition without a number means quantity 1. */
	private static final Pattern VERB_NO_QUANTITY = Pattern.compile(
		"\\b(?:get|grab|buy|collect|take|withdraw)\\s+(?:(?:a|an|some|your|the)\\s+)?([a-z][a-z'/ -]+)",
		Pattern.CASE_INSENSITIVE);

	/** A bare noun fragment that can continue an item list ("raw sardine"). */
	private static final Pattern BARE_ITEM = Pattern.compile(
		"^[A-Za-z][A-Za-z'/ -]{2,40}$");

	/**
	 * A name can't START with these — "all but 10 of your logs" must not
	 * become an item called "of your logs".
	 */
	private static final java.util.Set<String> NAME_REJECT_FIRST_WORD = java.util.Set.of(
		"of", "the", "a", "an", "and", "or", "your", "more", "other", "extra",
		"those", "these", "them", "that", "this", "it", "its", "each", "per",
		"is", "are", "was", "if", "as", "so");

	/**
	 * Fragments starting with these are actions/prose, never list items —
	 * they also BREAK a running item list.
	 */
	private static final java.util.Set<String> NOT_AN_ITEM_FIRST_WORD = java.util.Set.of(
		"go", "walk", "run", "talk", "speak", "teleport", "head", "climb", "open", "close",
		"start", "begin", "complete", "finish", "kill", "return", "enter", "exit", "equip",
		"wear", "wield", "use", "drop", "deposit", "pray", "cast", "read", "bank", "do",
		"if", "note", "continue", "progress", "keep", "repeat", "check", "turn", "give",
		"bring", "show", "search", "dig", "light", "burn", "sell", "then", "when", "once",
		"optional", "optionally", "remember", "swap", "hop", "log", "world",
		"travel", "fly", "sail", "ride", "cross", "move", "follow", "proceed", "wait",
		"stay", "chop");

	@Value
	public static class ItemGoal
	{
		GuideStep step;
		SubStep sub;
		String itemName;
		int quantity;
	}

	@Value
	public static class QuestGoal
	{
		GuideStep step;
		SubStep sub;
		Quest quest;
		/** true = quest must be FINISHED; false = merely started counts. */
		boolean requiresFinished;
	}

	@Value
	public static class SkillActionGoal
	{
		GuideStep step;
		SubStep sub;
		/** The first XP gained in this skill (while current) completes the sub-step. */
		Skill skill;
	}

	@Value
	public static class TravelGoal
	{
		GuideStep step;
		SubStep sub;
	}

	@Value
	public static class InteractionGoal
	{
		GuideStep step;
		SubStep sub;
	}

	/**
	 * "Give the letter to Romeo", "Fix his house" — steps whose completion
	 * consumes something from the inventory. Arrival alone must not tick
	 * them; arrival + items leaving the player's hands does.
	 */
	private static final java.util.Set<String> INTERACTION_FIRST_WORDS = java.util.Set.of(
		"give", "fix", "repair", "build", "hand", "deliver", "pay", "feed");

	@Value
	public static class Goals
	{
		List<ItemGoal> itemGoals;
		List<QuestGoal> questGoals;
		List<SkillActionGoal> skillActionGoals;
		List<TravelGoal> travelGoals;
		List<InteractionGoal> interactionGoals;
	}

	/** A sub-step about moving via teleport/transport, completed by a position jump. */
	private static final Pattern TRAVEL_WORDS = Pattern.compile(
		"\\b(?:teleport|tele|quetzal|travel|sail|charter|gnome glider|balloon|minecart|fairy ring)\\b",
		Pattern.CASE_INSENSITIVE);

	/** Fragment-opening action verb -> the skill whose XP drop proves it was done. */
	private static final java.util.Map<String, Skill> ACTION_VERB_SKILLS = java.util.Map.ofEntries(
		java.util.Map.entry("chop", Skill.WOODCUTTING),
		java.util.Map.entry("cut", Skill.WOODCUTTING),
		java.util.Map.entry("mine", Skill.MINING),
		java.util.Map.entry("fish", Skill.FISHING),
		java.util.Map.entry("catch", Skill.FISHING),
		java.util.Map.entry("cook", Skill.COOKING),
		java.util.Map.entry("burn", Skill.FIREMAKING),
		java.util.Map.entry("light", Skill.FIREMAKING),
		java.util.Map.entry("smith", Skill.SMITHING),
		java.util.Map.entry("smelt", Skill.SMITHING),
		java.util.Map.entry("fletch", Skill.FLETCHING),
		java.util.Map.entry("spin", Skill.CRAFTING),
		java.util.Map.entry("steal", Skill.THIEVING),
		java.util.Map.entry("pickpocket", Skill.THIEVING),
		java.util.Map.entry("bury", Skill.PRAYER));

	/** "make/turn the logs INTO a plank" — completing means owning the product. */
	private static final Pattern INTO_PRODUCT = Pattern.compile(
		"\\b(?:make|turn|convert)\\b.*?\\binto\\s+(?:(?:a|an|some)\\s+)?([a-z][a-z'/ -]+)",
		Pattern.CASE_INSENSITIVE);

	private GoalDetector()
	{
	}

	public static Goals detect(Guide guide)
	{
		List<ItemGoal> itemGoals = new ArrayList<>();
		List<QuestGoal> questGoals = new ArrayList<>();
		List<SkillActionGoal> actionGoals = new ArrayList<>();
		List<TravelGoal> travelGoals = new ArrayList<>();
		List<InteractionGoal> interactionGoals = new ArrayList<>();

		for (GuideStep step : guide.getAllSteps())
		{
			// True while we're inside a comma-list of items within this
			// step ("Grab a bucket of milk, raw sardine, doogle leaves…").
			boolean inItemList = false;
			for (SubStep sub : step.getSubSteps())
			{
				int itemsBefore = itemGoals.size();
				int questsBefore = questGoals.size();
				detectItemGoal(step, sub, itemGoals, inItemList);
				boolean producedItems = itemGoals.size() > itemsBefore;
				inItemList = producedItems;
				detectQuestGoal(step, sub, questGoals);
				// Item goals are stronger evidence than an action verb —
				// "chop 110 logs" waits for the logs, not the first swing.
				if (!producedItems)
				{
					detectActionGoal(step, sub, actionGoals);
				}
				// "Give the letter to Romeo" — needs consumption, not just
				// arrival. Wins over travel/location semantics.
				String firstWord = sub.getPlainText().trim().toLowerCase(Locale.ROOT)
					.split("[^a-z]+", 2)[0];
				boolean interaction = !producedItems && questGoals.size() == questsBefore
					&& INTERACTION_FIRST_WORDS.contains(firstWord);
				if (interaction)
				{
					interactionGoals.add(new InteractionGoal(step, sub));
				}

				// "Teleport using the chronicle" — completed by the position
				// jump, unless another goal already owns this sub.
				if (!producedItems && !interaction && questGoals.size() == questsBefore
					&& TRAVEL_WORDS.matcher(sub.getPlainText()).find())
				{
					travelGoals.add(new TravelGoal(step, sub));
				}
			}
		}
		return new Goals(itemGoals, questGoals, actionGoals, travelGoals, interactionGoals);
	}

	/** "Chop down a dying tree" -> a Woodcutting XP drop completes it. */
	private static void detectActionGoal(GuideStep step, SubStep sub, List<SkillActionGoal> out)
	{
		String[] words = sub.getPlainText().trim().toLowerCase(Locale.ROOT).split("[^a-z]+", 2);
		if (words.length == 0 || words[0].isEmpty())
		{
			return;
		}
		Skill skill = ACTION_VERB_SKILLS.get(words[0]);
		if (skill != null)
		{
			out.add(new SkillActionGoal(step, sub, skill));
		}
	}

	private static void detectItemGoal(GuideStep step, SubStep sub, List<ItemGoal> out, boolean inItemList)
	{
		String text = sub.getPlainText().trim();
		int before = out.size();
		java.util.Set<String> seen = new java.util.HashSet<>();

		// Every "number + name" pair anywhere in the fragment, so "buy 5
		// bolts of cloth and 100 steel nails" yields BOTH goals. Junk pairs
		// ("world 444 is recommended") die in addIfValid's name checks.
		Matcher pairs = QUANTITY_NAME.matcher(text);
		while (pairs.find())
		{
			addIfValid(out, step, sub, pairs.group(1), pairs.group(2), seen);
		}

		if (out.size() > before)
		{
			return; // numbered goals found; done
		}

		// "Make the logs into a plank" — done when you own the product.
		Matcher product = INTO_PRODUCT.matcher(text);
		if (product.find())
		{
			addIfValid(out, step, sub, "1", product.group(1), seen);
			return;
		}

		// "Grab a bucket of milk" — acquisition verb, no number: one of it.
		Matcher verbOnly = VERB_NO_QUANTITY.matcher(text);
		if (verbOnly.find())
		{
			addIfValid(out, step, sub, "1", verbOnly.group(1), seen);
			return;
		}

		// "raw sardine" — a bare noun fragment continuing the item list
		// started by an earlier fragment of this step.
		if (inItemList)
		{
			String cleaned = NAME_TERMINATOR.matcher(text).replaceFirst("").trim();
			if (BARE_ITEM.matcher(cleaned).matches()
				&& !NOT_AN_ITEM_FIRST_WORD.contains(
					cleaned.split(" ")[0].toLowerCase(Locale.ROOT)))
			{
				addIfValid(out, step, sub, "1", cleaned, seen);
			}
		}
	}

	private static void addIfValid(List<ItemGoal> out, GuideStep step, SubStep sub,
		String rawQuantity, String rawName, java.util.Set<String> seen)
	{
		int quantity;
		try
		{
			quantity = Integer.parseInt(rawQuantity.replace(",", ""));
		}
		catch (NumberFormatException e)
		{
			return;
		}
		if (quantity < 1 || quantity > 100_000)
		{
			return;
		}
		String name = NAME_TERMINATOR.matcher(rawName).replaceFirst("").trim().toLowerCase(Locale.ROOT);
		// "110 logs" good; "110 of the things you like most" is not an item.
		// "gp" gets a pass on the length rule — it's the guide's word for coins.
		if ((name.length() < 3 && !name.equals("gp")) || name.split(" ").length > 4)
		{
			return;
		}
		if (NAME_REJECT_FIRST_WORD.contains(name.split(" ")[0]))
		{
			return;
		}
		// "get 43 prayer" / "5 attack" are levels, not items
		String singular = name.endsWith("s") ? name.substring(0, name.length() - 1) : name;
		if (SKILL_WORDS.contains(name) || SKILL_WORDS.contains(singular))
		{
			return;
		}

		if (!seen.add(name + ":" + quantity))
		{
			return; // same pair matched twice in one fragment
		}

		// "air/mind runes" means BOTH items: expand to "air runes" + "mind runes".
		Matcher compound = COMPOUND_NAME.matcher(name);
		if (compound.matches())
		{
			out.add(new ItemGoal(step, sub, compound.group(1) + " " + compound.group(3), quantity));
			out.add(new ItemGoal(step, sub, compound.group(2) + " " + compound.group(3), quantity));
			return;
		}

		out.add(new ItemGoal(step, sub, name, quantity));
	}

	private static void detectQuestGoal(GuideStep step, SubStep sub, List<QuestGoal> out)
	{
		String text = sub.getPlainText().toLowerCase(Locale.ROOT);
		if (!text.contains("start") && !text.contains("begin")
			&& !text.contains("complete") && !text.contains("finish") && !text.contains("do "))
		{
			return; // cheap pre-filter before the quest-name scan
		}

		for (Quest quest : Quest.values())
		{
			String questName = quest.getName().toLowerCase(Locale.ROOT);
			if (text.contains("complete " + questName)
				|| text.contains("finish " + questName)
				|| text.contains("do " + questName))
			{
				out.add(new QuestGoal(step, sub, quest, true));
				return; // one quest goal per sub-step is plenty
			}
			if (text.contains("start " + questName) || text.contains("begin " + questName))
			{
				out.add(new QuestGoal(step, sub, quest, false));
				return;
			}
		}
	}
}
