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

	/**
	 * Guide word -> Skill enum, including the guide's abbreviations.
	 * "combat" is deliberately absent — it's not one trainable skill.
	 */
	private static final java.util.Map<String, Skill> SKILL_BY_WORD = java.util.Map.ofEntries(
		java.util.Map.entry("attack", Skill.ATTACK),
		java.util.Map.entry("strength", Skill.STRENGTH),
		java.util.Map.entry("defence", Skill.DEFENCE),
		java.util.Map.entry("defense", Skill.DEFENCE),
		java.util.Map.entry("hitpoints", Skill.HITPOINTS),
		java.util.Map.entry("hp", Skill.HITPOINTS),
		java.util.Map.entry("ranged", Skill.RANGED),
		java.util.Map.entry("range", Skill.RANGED),
		java.util.Map.entry("prayer", Skill.PRAYER),
		java.util.Map.entry("magic", Skill.MAGIC),
		java.util.Map.entry("cooking", Skill.COOKING),
		java.util.Map.entry("woodcutting", Skill.WOODCUTTING),
		java.util.Map.entry("fletching", Skill.FLETCHING),
		java.util.Map.entry("fishing", Skill.FISHING),
		java.util.Map.entry("firemaking", Skill.FIREMAKING),
		java.util.Map.entry("crafting", Skill.CRAFTING),
		java.util.Map.entry("smithing", Skill.SMITHING),
		java.util.Map.entry("mining", Skill.MINING),
		java.util.Map.entry("herblore", Skill.HERBLORE),
		java.util.Map.entry("agility", Skill.AGILITY),
		java.util.Map.entry("thieving", Skill.THIEVING),
		java.util.Map.entry("slayer", Skill.SLAYER),
		java.util.Map.entry("farming", Skill.FARMING),
		java.util.Map.entry("runecraft", Skill.RUNECRAFT),
		java.util.Map.entry("runecrafting", Skill.RUNECRAFT),
		java.util.Map.entry("hunter", Skill.HUNTER),
		java.util.Map.entry("construction", Skill.CONSTRUCTION));

	/** "50 firemaking", "level 43 prayer", "70 range" — number then skill word. */
	private static final Pattern LEVEL_SKILL = Pattern.compile(
		"\\b(\\d{1,2})\\s+([a-zA-Z]+)");

	/** "Grab a bucket of milk" — acquisition without a number means quantity 1. */
	private static final Pattern VERB_NO_QUANTITY = Pattern.compile(
		"\\b(?:get|grab|buy|collect|take|withdraw)\\s+(?:(?:a|an|some|your|the)\\s+)?([a-z][a-z'/ -]+)",
		Pattern.CASE_INSENSITIVE);

	/** A bare noun fragment that can continue an item list ("raw sardine", "GP"). */
	private static final Pattern BARE_ITEM = Pattern.compile(
		"^[A-Za-z][A-Za-z'/ -]{1,40}$");

	/** "one POH tab" means quantity 1; "two buckets" means quantity 2. */
	private static final java.util.Map<String, Integer> NUMBER_WORDS = java.util.Map.of(
		"one", 1, "two", 2, "three", 3, "four", 4, "five", 5,
		"six", 6, "seven", 7, "eight", 8, "nine", 9, "ten", 10);

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
		"stay", "chop", "break", "drink", "eat", "empty", "fill", "rub", "activate",
		"unnote", "train");

	@Value
	public static class ItemGoal
	{
		GuideStep step;
		SubStep sub;
		String itemName;
		int quantity;
		/**
		 * True for "buy X from her shop" — a transaction, not a state.
		 * Already OWNING the item must not tick it; the plugin requires
		 * the carried count to INCREASE while the sub is current.
		 */
		boolean acquisition;
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

	/**
	 * "burn them to level 50 firemaking" — the sub-step completes when the
	 * REAL skill level reaches the target. Monotonic, so it's strong
	 * evidence, like quest state. A sub with several ("get 40 attack and
	 * 30 strength") completes when ALL are met, mirroring item goals.
	 */
	@Value
	public static class SkillLevelGoal
	{
		GuideStep step;
		SubStep sub;
		Skill skill;
		int level;
	}

	@Value
	public static class TravelGoal
	{
		GuideStep step;
		SubStep sub;
	}

	/**
	 * "Minigame teleport to Soul Wars" — completion is still the travel
	 * goal's position jump; this extra goal exists so an overlay can walk
	 * the player through the Grouping UI (tab -> dropdown -> Teleport).
	 */
	@Value
	public static class MinigameTeleportGoal
	{
		GuideStep step;
		SubStep sub;
		/** The minigame name as written in the guide, e.g. "Soul Wars". */
		String minigame;
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
	public static class CountedSkillGoal
	{
		GuideStep step;
		SubStep sub;
		Skill skill;
		/** Expected number of XP drops (builds/crafts) to complete this sub. */
		int count;
	}

	@Value
	public static class Goals
	{
		List<ItemGoal> itemGoals;
		List<QuestGoal> questGoals;
		List<SkillActionGoal> skillActionGoals;
		List<TravelGoal> travelGoals;
		List<InteractionGoal> interactionGoals;
		List<CountedSkillGoal> countedSkillGoals;
		List<MinigameTeleportGoal> minigameTeleportGoals;
		List<SkillLevelGoal> skillLevelGoals;
	}

	/** "Minigame teleport to Soul Wars" — the name feeds the Grouping-UI overlay. */
	private static final Pattern MINIGAME_TELEPORT = Pattern.compile(
		"minigame teleport to ([A-Za-z][A-Za-z' -]+)", Pattern.CASE_INSENSITIVE);

	/**
	 * "use your planks to train construction (6 wooden chairs, 1 rug...)"
	 * — built things never enter the inventory, but each build is one XP
	 * drop, and the parenthetical tells us how many to expect.
	 */
	private static final Pattern TRAIN_SKILL = Pattern.compile(
		"train\\s+(construction|crafting|smithing|cooking|firemaking|fletching|herblore|prayer|magic|fishing|woodcutting|mining|thieving|agility)",
		Pattern.CASE_INSENSITIVE);

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

	/** Purchase verbs — see ItemGoal#acquisition. */
	private static final Pattern PURCHASE_VERB = Pattern.compile(
		"\\b(?:buy|purchase)\\b", Pattern.CASE_INSENSITIVE);

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
		List<CountedSkillGoal> countedGoals = new ArrayList<>();
		List<MinigameTeleportGoal> minigameGoals = new ArrayList<>();
		List<SkillLevelGoal> levelGoals = new ArrayList<>();

		for (GuideStep step : guide.getAllSteps())
		{
			// True while we're inside a comma-list of items within this
			// step ("Grab a bucket of milk, raw sardine, doogle leaves…").
			boolean inItemList = false;
			// Whether that list was opened with a purchase verb — bare
			// continuations of "buy 5 X, 3 Y" are purchases too.
			boolean acquisitionList = false;
			for (SubStep sub : step.getSubSteps())
			{
				int itemsBefore = itemGoals.size();
				int questsBefore = questGoals.size();
				acquisitionList = detectItemGoal(step, sub, itemGoals, inItemList, acquisitionList);
				boolean producedItems = itemGoals.size() > itemsBefore;
				inItemList = producedItems;
				detectQuestGoal(step, sub, questGoals);
				// "burn them to level 50 firemaking" — a level target.
				List<SkillLevelGoal> subLevels = detectLevelGoals(step, sub);
				levelGoals.addAll(subLevels);
				// Item goals are stronger evidence than an action verb —
				// "chop 110 logs" waits for the logs, not the first swing.
				// A level target likewise beats "first xp drop ticks it".
				if (!producedItems && subLevels.isEmpty())
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

				// "Minigame teleport to X" — remember which minigame, so the
				// overlay can point at the Grouping UI while this sub is next.
				Matcher minigame = MINIGAME_TELEPORT.matcher(sub.getPlainText());
				if (minigame.find())
				{
					minigameGoals.add(new MinigameTeleportGoal(step, sub, minigame.group(1).trim()));
				}

				// "train construction (6 chairs, 1 rug...)" -> counted XP drops.
				// Not when a level target exists — "train fm to 50" counts
				// levels, not drops (and the 50 would poison the drop sum).
				Matcher train = TRAIN_SKILL.matcher(sub.getPlainText());
				if (!producedItems && subLevels.isEmpty() && train.find())
				{
					Skill skill = Skill.valueOf(train.group(1).toUpperCase(Locale.ROOT));
					int count = 0;
					Matcher quantities = Pattern.compile("\\b(\\d{1,3})\\b").matcher(sub.getPlainText());
					while (quantities.find())
					{
						count += Integer.parseInt(quantities.group(1));
					}
					countedGoals.add(new CountedSkillGoal(step, sub, skill, Math.max(1, count)));
				}
			}
		}
		return new Goals(itemGoals, questGoals, actionGoals, travelGoals, interactionGoals,
			countedGoals, minigameGoals, levelGoals);
	}

	/**
	 * Every "number + skill word" pair in the sub-step is a level target:
	 * "burn them to level 50 firemaking", "get 43 prayer", "until 70 range".
	 * Parentheticals are commentary and skipped, same as for items. If a
	 * skill appears twice the higher target wins.
	 */
	private static List<SkillLevelGoal> detectLevelGoals(GuideStep step, SubStep sub)
	{
		String text = sub.getPlainText().replaceAll("\\([^)]*\\)", " ");
		java.util.Map<Skill, Integer> targets = new java.util.LinkedHashMap<>();
		Matcher matcher = LEVEL_SKILL.matcher(text);
		while (matcher.find())
		{
			Skill skill = SKILL_BY_WORD.get(matcher.group(2).toLowerCase(Locale.ROOT));
			int level = Integer.parseInt(matcher.group(1));
			if (skill != null && level >= 2 && level <= 99)
			{
				targets.merge(skill, level, Math::max);
			}
		}
		List<SkillLevelGoal> goals = new ArrayList<>(targets.size());
		targets.forEach((skill, level) -> goals.add(new SkillLevelGoal(step, sub, skill, level)));
		return goals;
	}

	/**
	 * Clause glue that can precede the real action verb: "and do a lap...",
	 * "then chop...". Skipped before the verb lookup.
	 */
	private static final java.util.Set<String> LEADING_CONNECTIVES = java.util.Set.of(
		"and", "then", "also", "now", "next", "finally", "optionally");

	/** "do a lap of the Shayzien agility course" -> an Agility XP drop. */
	private static final Pattern AGILITY_LAP = Pattern.compile(
		"\\blaps?\\b.*\\bagility course\\b", Pattern.CASE_INSENSITIVE);

	/** "Chop down a dying tree" -> a Woodcutting XP drop completes it. */
	private static void detectActionGoal(GuideStep step, SubStep sub, List<SkillActionGoal> out)
	{
		String text = sub.getPlainText().trim().toLowerCase(Locale.ROOT);
		String[] words = text.split("[^a-z]+");
		int first = 0;
		while (first < words.length
			&& (words[first].isEmpty() || LEADING_CONNECTIVES.contains(words[first])))
		{
			first++;
		}
		Skill skill = first < words.length ? ACTION_VERB_SKILLS.get(words[first]) : null;
		if (skill == null && AGILITY_LAP.matcher(text).find())
		{
			skill = Skill.AGILITY;
		}
		if (skill != null)
		{
			out.add(new SkillActionGoal(step, sub, skill));
		}
	}

	/**
	 * Returns the acquisition flag a bare continuation of this step's item
	 * list should inherit: a sub with its own verb (re)defines the list
	 * ("buy 5 X" -> purchases), a bare continuation carries it unchanged,
	 * and a sub without goals breaks the list.
	 */
	private static boolean detectItemGoal(GuideStep step, SubStep sub, List<ItemGoal> out,
		boolean inItemList, boolean inheritedAcquisition)
	{
		// Parentheticals are commentary, not shopping — "(so there are 3
		// planks left in your bank)" must not become a "planks left" goal.
		String text = sub.getPlainText().trim().replaceAll("\\([^)]*\\)", " ");
		boolean ownPurchase = PURCHASE_VERB.matcher(text).find();
		int before = out.size();
		java.util.Set<String> seen = new java.util.HashSet<>();

		// Every "number + name" pair anywhere in the fragment, so "buy 5
		// bolts of cloth and 100 steel nails" yields BOTH goals. Junk pairs
		// ("world 444 is recommended") die in addIfValid's name checks.
		Matcher pairs = QUANTITY_NAME.matcher(text);
		while (pairs.find())
		{
			addIfValid(out, step, sub, pairs.group(1), pairs.group(2), seen, ownPurchase);
		}

		if (out.size() > before)
		{
			return ownPurchase; // numbered goals found; done
		}

		// "Make the logs into a plank" — done when you own the product.
		Matcher product = INTO_PRODUCT.matcher(text);
		if (product.find())
		{
			addIfValid(out, step, sub, "1", product.group(1), seen, false);
			return false;
		}

		// "Grab a bucket of milk" — acquisition verb, no number: one of it.
		Matcher verbOnly = VERB_NO_QUANTITY.matcher(text);
		if (verbOnly.find())
		{
			addIfValid(out, step, sub, "1", verbOnly.group(1), seen, ownPurchase);
			return ownPurchase;
		}

		// "raw sardine" — a bare noun fragment continuing the item list
		// started by an earlier fragment of this step.
		if (inItemList)
		{
			String cleaned = NAME_TERMINATOR.matcher(text).replaceFirst("").trim();
			// "a house teleport" is elliptical for "grab a house teleport" —
			// strip the article or the name check rejects it, and the sub
			// then wrongly falls through to travel detection ("teleport").
			cleaned = cleaned.replaceFirst("(?i)^(?:a|an|some|the)\\s+", "");
			if (BARE_ITEM.matcher(cleaned).matches()
				&& !NOT_AN_ITEM_FIRST_WORD.contains(
					cleaned.split(" ")[0].toLowerCase(Locale.ROOT)))
			{
				addIfValid(out, step, sub, "1", cleaned, seen, inheritedAcquisition);
				return inheritedAcquisition;
			}
		}
		return false;
	}

	private static void addIfValid(List<ItemGoal> out, GuideStep step, SubStep sub,
		String rawQuantity, String rawName, java.util.Set<String> seen, boolean acquisition)
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

		// "one POH tab" -> quantity 1 of "poh tab"
		String[] numberSplit = name.split(" ", 2);
		Integer numberWord = NUMBER_WORDS.get(numberSplit[0]);
		if (numberWord != null && numberSplit.length > 1)
		{
			name = numberSplit[1];
			if (quantity == 1)
			{
				quantity = numberWord;
			}
		}

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
			out.add(new ItemGoal(step, sub, compound.group(1) + " " + compound.group(3), quantity, acquisition));
			out.add(new ItemGoal(step, sub, compound.group(2) + " " + compound.group(3), quantity, acquisition));
			return;
		}

		out.add(new ItemGoal(step, sub, name, quantity, acquisition));
	}

	private static void detectQuestGoal(GuideStep step, SubStep sub, List<QuestGoal> out)
	{
		// straight apostrophes: the guide writes "Witch’s Potion" (curly),
		// the Quest enum says "Witch's Potion"
		String text = sub.getPlainText().toLowerCase(Locale.ROOT).replace('’', '\'');
		if (!text.contains("start") && !text.contains("begin")
			&& !text.contains("complete") && !text.contains("finish") && !text.contains("do "))
		{
			return; // cheap pre-filter before the quest-name scan
		}

		for (Quest quest : Quest.values())
		{
			String questName = quest.getName().toLowerCase(Locale.ROOT);
			if (verbPhrase(text, questName, "complete", "finish", "do"))
			{
				out.add(new QuestGoal(step, sub, quest, true));
				return; // one quest goal per sub-step is plenty
			}
			if (verbPhrase(text, questName, "start", "begin"))
			{
				out.add(new QuestGoal(step, sub, quest, false));
				return;
			}
		}
	}

	/**
	 * "complete Tower of Life" AND "complete THE Tower of Life" — the
	 * guide freely inserts the article, which used to break the match
	 * (so finishing the quest never ticked the sub).
	 */
	private static boolean verbPhrase(String text, String questName, String... verbs)
	{
		for (String verb : verbs)
		{
			if (text.contains(verb + " " + questName)
				|| text.contains(verb + " the " + questName))
			{
				return true;
			}
		}
		return false;
	}
}
