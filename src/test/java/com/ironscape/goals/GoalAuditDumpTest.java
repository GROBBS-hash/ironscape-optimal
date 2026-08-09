package com.ironscape.goals;

import com.google.gson.Gson;
import com.ironscape.guide.Guide;
import com.ironscape.guide.GuideLoader;
import com.ironscape.guide.GuideVariant;
import com.ironscape.items.ItemTracker;
import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/**
 * Not a test of behaviour — a DUMP for tools/audit-goals.mjs: every
 * text-detected item goal with its full alias chain, written to
 * build/goal-audit.tsv on each test run. The Node checker matches the
 * aliases against the real OSRS item list and reports goals that can
 * never resolve (the "cow calf 0/1" class) all at once, instead of one
 * in-game surprise at a time.
 */
public class GoalAuditDumpTest
{
	/**
	 * id -> gameval ItemID constant name, for the item_ids.json key check:
	 * a key whose id is right but whose NAME isn't the real item ("notes
	 * for dwarf cannon" -> NULODIONS_NOTES) shows a correct sprite, passes
	 * the resolvability audit, and never counts a thing in play.
	 */
	@Test
	public void dumpItemIdConstants() throws Exception
	{
		File out = new File("build/item-id-constants.tsv");
		out.getParentFile().mkdirs();
		try (PrintWriter writer = new PrintWriter(out, StandardCharsets.UTF_8))
		{
			for (java.lang.reflect.Field field : net.runelite.api.gameval.ItemID.class.getFields())
			{
				if (field.getType() == int.class)
				{
					writer.println(field.getInt(null) + "\t" + field.getName());
				}
			}
		}
	}

	/**
	 * How would each MOVEMENT-shaped sub prove arrival? TEXT = a place
	 * name in the sub's own text resolves; PIN = only the step's 📍 tag
	 * resolves (the fallback that anchored 'go to ess mines' on Gnome
	 * Stronghold — the ORIGIN — and false-ticked); NONE = nothing
	 * resolves, the sub can only be ticked manually. tools/audit-arrivals.mjs
	 * turns the PIN rows into the review list.
	 */
	@Test
	public void dumpArrivalResolution() throws Exception
	{
		Guide guide = new GuideLoader(new Gson()).load(GuideVariant.OZIRIS);
		com.ironscape.places.PlaceManager places =
			new com.ironscape.places.PlaceManager(new Gson(), new File("no-local-places.json"));
		places.load();
		GoalDetector.Goals goals = GoalDetector.detect(guide);
		java.util.Set<String> subsWithItems = new java.util.HashSet<>();
		for (GoalDetector.ItemGoal goal : goals.getItemGoals())
		{
			subsWithItems.add(goal.getSub().getId());
		}
		// Annotation keys whose completion is owned elsewhere: a ⌖ target
		// (precise arrival), an errand chain, or a varbit/varp/region
		// checkpoint. Raw-parse the bundled file — this dump runs without
		// a client.
		java.util.Set<String> owned = new java.util.HashSet<>();
		try (java.io.InputStream in = GoalAuditDumpTest.class.getResourceAsStream(
			"/com/ironscape/annotations/annotations_oziris.json"))
		{
			com.google.gson.JsonObject file = new Gson().fromJson(
				new java.io.InputStreamReader(in, StandardCharsets.UTF_8),
				com.google.gson.JsonObject.class);
			for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry
				: file.getAsJsonObject("annotations").entrySet())
			{
				com.google.gson.JsonObject ann = entry.getValue().getAsJsonObject();
				boolean checkpoint = ann.has("requires")
					&& (ann.getAsJsonObject("requires").has("varbit")
						|| ann.getAsJsonObject("requires").has("varp")
						|| ann.getAsJsonObject("requires").has("region"));
				if (ann.has("target") || ann.has("errands") || checkpoint)
				{
					owned.add(entry.getKey());
				}
			}
		}
		// Keep in sync with IronscapePlugin.MOVEMENT_WORD + the
		// CHARTER/SPIRIT_TREE network-travel phrasing.
		java.util.regex.Pattern movement = java.util.regex.Pattern.compile(
			"\\b(?:go|walk|run|head|return|travel|enter|exit|climb|cross|move|proceed|sail|ride|fly|swim|tele|teleport|tabs?|charter)\\b|spirit tree",
			java.util.regex.Pattern.CASE_INSENSITIVE);
		File out = new File("build/arrival-audit.tsv");
		out.getParentFile().mkdirs();
		try (PrintWriter writer = new PrintWriter(out, StandardCharsets.UTF_8))
		{
			for (com.ironscape.guide.GuideStep step : guide.getAllSteps())
			{
				String location = step.getMetadata().get("location");
				for (com.ironscape.guide.SubStep sub : step.getSubSteps())
				{
					String text = sub.getPlainText();
					if (!movement.matcher(text).find()
						|| subsWithItems.contains(sub.getId())
						|| owned.contains(sub.getId()) || owned.contains(step.getId()))
					{
						continue;
					}
					net.runelite.api.coords.WorldPoint fromText = places.lastPlaceIn(text);
					if (fromText == null)
					{
						fromText = places.firstPlaceIn(text);
					}
					net.runelite.api.coords.WorldPoint fromPin =
						location == null ? null : places.getLoose(location);
					String source = fromText != null ? "TEXT" : fromPin != null ? "PIN" : "NONE";
					writer.println("ARRIVAL\t" + sub.getId()
						+ "\t" + source
						+ "\t" + (location == null ? "" : location)
						+ "\t" + text.trim().replaceAll("[\\t\\r\\n]+", " "));
				}
			}
		}
	}

	/**
	 * Every RuneLite Quest enum name. The guide's `quest` metadata has to
	 * match one of these EXACTLY today, and several do not — the wiki and
	 * the game disagree about articles ("The Hand in the Sand"), spelling
	 * ("Tranquillity"), and sequel numbering ("Desert Treasure I"). Dumped
	 * so the mismatches can be found by diff rather than by memory.
	 */
	@Test
	public void dumpQuestNames() throws Exception
	{
		File out = new File("build/quest-names.tsv");
		out.getParentFile().mkdirs();
		try (PrintWriter writer = new PrintWriter(out, StandardCharsets.UTF_8))
		{
			for (net.runelite.api.Quest quest : net.runelite.api.Quest.values())
			{
				writer.println("QUEST\t" + quest.name() + "\t" + quest.getName());
			}
		}
	}

	/**
	 * The plugin's MOVEMENT_WORD pattern, verbatim.
	 *
	 * tools/preflight.mjs has to know which subs read as travel
	 * instructions, because that is what decides whether a step with no
	 * detector can still tick by walking there. It used to keep its own
	 * hand-copied regex, which is the same shape of fault as the percent
	 * codec that drifted the moment encode and decode lived in two
	 * classes: the copy is right until someone edits one side, and then
	 * the check confidently reports a step as tickable that the plugin
	 * cannot tick. Dumping the real pattern means there is only one.
	 */
	@Test
	public void dumpMovementWord() throws Exception
	{
		java.lang.reflect.Field field =
			com.ironscape.IronscapePlugin.class.getDeclaredField("MOVEMENT_WORD");
		field.setAccessible(true);
		java.util.regex.Pattern pattern = (java.util.regex.Pattern) field.get(null);

		File out = new File("build/movement-word.txt");
		out.getParentFile().mkdirs();
		try (PrintWriter writer = new PrintWriter(out, StandardCharsets.UTF_8))
		{
			writer.println(pattern.pattern());
		}
	}

	/**
	 * Which detector path can complete each sub, and what the annotation
	 * file says about it. Written for tools/audit-item-gating.mjs: whether
	 * making annotation items gate completion CHANGES a step depends
	 * entirely on how that step completes today — a travel/arrival sub is
	 * already gated by annotationItemsCarried, an item-goal sub is not.
	 * Inferring that from step text (as the first cut of the audit did)
	 * over-counts; this dumps the detector's own answer.
	 */
	@Test
	public void dumpCompletionPaths() throws Exception
	{
		Guide guide = new GuideLoader(new Gson()).load(GuideVariant.OZIRIS);
		GoalDetector.Goals goals = GoalDetector.detect(guide);

		java.util.Map<String, java.util.Set<String>> paths = new java.util.HashMap<>();
		java.util.function.BiConsumer<String, String> mark = (subId, kind) ->
			paths.computeIfAbsent(subId, k -> new java.util.TreeSet<>()).add(kind);

		for (GoalDetector.ItemGoal g : goals.getItemGoals())
		{
			mark.accept(g.getSub().getId(), g.isAcquisition() ? "item-buy" : "item");
		}
		for (GoalDetector.QuestGoal g : goals.getQuestGoals())
		{
			mark.accept(g.getSub().getId(), g.isRequiresFinished() ? "quest-finish" : "quest-start");
		}
		for (GoalDetector.SkillActionGoal g : goals.getSkillActionGoals())
		{
			mark.accept(g.getSub().getId(), "xp-drop");
		}
		for (GoalDetector.TravelGoal g : goals.getTravelGoals())
		{
			mark.accept(g.getSub().getId(), "travel");
		}
		for (GoalDetector.InteractionGoal g : goals.getInteractionGoals())
		{
			mark.accept(g.getSub().getId(), "interaction");
		}
		for (GoalDetector.CountedSkillGoal g : goals.getCountedSkillGoals())
		{
			mark.accept(g.getSub().getId(), "counted");
		}
		for (GoalDetector.SkillLevelGoal g : goals.getSkillLevelGoals())
		{
			mark.accept(g.getSub().getId(), "level");
		}
		for (GoalDetector.DepletionGoal g : goals.getDepletionGoals())
		{
			mark.accept(g.getSub().getId(), "depletion");
		}

		File out = new File("build/completion-paths.tsv");
		out.getParentFile().mkdirs();
		try (PrintWriter writer = new PrintWriter(out, StandardCharsets.UTF_8))
		{
			for (com.ironscape.guide.GuideStep step : guide.getAllSteps())
			{
				for (com.ironscape.guide.SubStep sub : step.getSubSteps())
				{
					java.util.Set<String> kinds = paths.get(sub.getId());
					writer.println("PATH\t" + sub.getId()
						+ "\t" + (kinds == null ? "none" : String.join(",", kinds))
						+ "\t" + sub.getPlainText().trim().replaceAll("[\\t\\r\\n]+", " "));
				}
			}
		}
	}

	@Test
	public void dumpGoals() throws Exception
	{
		Guide guide = new GuideLoader(new Gson()).load(GuideVariant.OZIRIS);
		GoalDetector.Goals goals = GoalDetector.detect(guide);
		File out = new File("build/goal-audit.tsv");
		out.getParentFile().mkdirs();
		java.util.Set<String> subsWithItems = new java.util.HashSet<>();
		for (GoalDetector.ItemGoal goal : goals.getItemGoals())
		{
			subsWithItems.add(goal.getSub().getId());
		}
		try (PrintWriter writer = new PrintWriter(out, StandardCharsets.UTF_8))
		{
			for (GoalDetector.ItemGoal goal : goals.getItemGoals())
			{
				writer.println("ITEM\t" + goal.getSub().getId()
					+ "\t" + goal.getQuantity()
					+ "\t" + goal.getItemName()
					+ "\t" + String.join("|", ItemTracker.aliases(goal.getItemName()))
					+ "\t" + goal.getSub().getPlainText().trim()
						.replaceAll("[\\t\\r\\n]+", " "));
			}
			// The audit's other blind spot: a BUY sub where detection
			// produced NO goal at all (the "1 PACK of eyes of newt and
			// pestle and mortar" class — nothing to resolve, nothing red,
			// just silently untracked).
			for (com.ironscape.guide.GuideStep step : guide.getAllSteps())
			{
				for (com.ironscape.guide.SubStep sub : step.getSubSteps())
				{
					String text = sub.getPlainText();
					if (!subsWithItems.contains(sub.getId())
						&& text.toLowerCase().matches(".*\\b(?:buy|purchase|grab)\\b.*"))
					{
						writer.println("NOGOAL\t" + sub.getId() + "\t"
							+ text.trim().replaceAll("[\\t\\r\\n]+", " "));
					}
				}
			}
		}
	}
}
