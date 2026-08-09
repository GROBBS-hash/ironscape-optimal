package com.ironscape.goals;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ironscape.guide.Guide;
import com.ironscape.guide.GuideLoader;
import com.ironscape.guide.GuideStep;
import com.ironscape.guide.GuideVariant;
import com.ironscape.guide.SubStep;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Guards the two conditions that make POSSESSION a safe completion path.
 *
 * Steps whose objective is having the things ("decant them until you have
 * like 6 full pots") carry their count in an annotation, because the
 * detector cannot name "6 full pots". Annotation items are display-only,
 * so those steps had NO completion path at all — a green 6/6 badge over a
 * checkbox nothing could tick.
 *
 * The wide rule ("no path + all numbered annotation items held") was
 * measured at 25 steps of which ~2 were right, because annotation items
 * are overwhelmingly what you BRING: a spade would tick all six "dig up
 * the clue" steps and the barcrawl card all ten bars. So the rule reads
 * the SENTENCE instead, and this test pins the blast radius at the two
 * steps that measurement approved.
 *
 * Both guards were checked by disabling them and re-running, and they are
 * NOT equally load-bearing today — worth knowing before anyone trims one:
 *
 *   parenthetical  PROVEN. Disable it and aReminderInsideBrackets fails;
 *                  the Brimstail reminder qualifies on text alone.
 *   checkpoint     PRECAUTIONARY. Disable it and everything still passes,
 *                  because the bracket guard already excludes the only
 *                  step where the two overlap. It is kept because wave 6
 *                  settled that an authored requires clause owns its sub —
 *                  the annotation exists precisely because a heuristic
 *                  fired early — and the next such step may not happen to
 *                  be bracketed.
 */
public class PossessionObjectiveTest
{
	/** Mirrors IronscapePlugin.PARENTHETICAL / POSSESSION_OBJECTIVE. */
	private static final Pattern PARENTHETICAL = Pattern.compile("\\([^)]*\\)");
	private static final Pattern POSSESSION_OBJECTIVE = Pattern.compile(
		"\\b(?:until you have|make sure you have|until you own|so you have)\\b",
		Pattern.CASE_INSENSITIVE);

	private JsonObject annotations() throws Exception
	{
		try (InputStream in = getClass().getResourceAsStream(
			"/com/ironscape/annotations/annotations_oziris.json"))
		{
			return new Gson()
				.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class)
				.getAsJsonObject("annotations");
		}
	}

	/**
	 * Exactly the two measured steps qualify. If a third appears, it has
	 * not been reviewed — look at it before widening this number, because
	 * the failure mode is a step ticking itself off on the tools you
	 * happen to be carrying.
	 */
	@Test
	public void onlyTheReviewedStepsCompleteOnPossession() throws Exception
	{
		JsonObject annotations = annotations();
		Guide guide = new GuideLoader(new Gson()).load(GuideVariant.OZIRIS);
		List<String> qualifying = new ArrayList<>();

		for (GuideStep step : guide.getAllSteps())
		{
			for (SubStep sub : step.getSubSteps())
			{
				if (!isPossessionObjective(annotations, step, sub))
				{
					continue;
				}
				qualifying.add(sub.getPlainText().trim());
			}
		}

		assertEquals("possession-completable steps changed: " + qualifying,
			2, qualifying.size());
		assertTrue(qualifying.toString(), qualifying.stream()
			.anyMatch((t) -> t.startsWith("Hop worlds for super antipoison")));
		assertTrue(qualifying.toString(), qualifying.stream()
			.anyMatch((t) -> t.equals("Make sure you have all ghosts ahoy items")));
	}

	/**
	 * "Use Brimstails to go to ess mines (scrying orb 2/3, make sure you
	 * have it with you)" is a TRAVEL instruction with a reminder attached.
	 * Holding the orb must not tick it — it is the step wave 9 already had
	 * to stop completing early once.
	 *
	 * It fails the rule twice over, and both are asserted: the phrase is
	 * inside a parenthetical, and the sub carries a region checkpoint that
	 * owns its completion.
	 */
	@Test
	public void aReminderInsideBracketsIsNotAnObjective() throws Exception
	{
		JsonObject annotations = annotations();
		Guide guide = new GuideLoader(new Gson()).load(GuideVariant.OZIRIS);

		SubStep brimstail = null;
		GuideStep brimstailStep = null;
		for (GuideStep step : guide.getAllSteps())
		{
			for (SubStep sub : step.getSubSteps())
			{
				if (sub.getPlainText().contains("Use Brimstails to go to ess mines"))
				{
					brimstail = sub;
					brimstailStep = step;
				}
			}
		}
		assertTrue("the Brimstail step is gone from the guide", brimstail != null);

		assertTrue("the reminder is no longer bracketed — the guard is now load-bearing "
				+ "on the checkpoint alone",
			!POSSESSION_OBJECTIVE.matcher(
				PARENTHETICAL.matcher(brimstail.getPlainText()).replaceAll(" ")).find());
		assertTrue("the region checkpoint that actually completes this step is gone",
			hasRequirement(annotations, brimstailStep, brimstail));
		assertTrue(!isPossessionObjective(annotations, brimstailStep, brimstail));
	}

	private boolean hasRequirement(JsonObject annotations, GuideStep step, SubStep sub)
	{
		JsonObject entry = annotations.getAsJsonObject(annotationId(step, sub));
		JsonObject subEntry = annotations.getAsJsonObject(sub.getId());
		return (entry != null && entry.has("requires"))
			|| (subEntry != null && subEntry.has("requires"));
	}

	private String annotationId(GuideStep step, SubStep sub)
	{
		return step.getSubSteps().size() == 1 ? step.getId() : sub.getId();
	}

	private boolean isPossessionObjective(JsonObject annotations, GuideStep step, SubStep sub)
	{
		String text = PARENTHETICAL.matcher(sub.getPlainText()).replaceAll(" ");
		if (!POSSESSION_OBJECTIVE.matcher(text).find())
		{
			return false;
		}
		if (hasRequirement(annotations, step, sub))
		{
			return false;
		}
		JsonObject entry = annotations.getAsJsonObject(annotationId(step, sub));
		if (entry == null || !entry.has("items"))
		{
			return false;
		}
		for (JsonElement item : entry.getAsJsonArray("items"))
		{
			JsonObject need = item.getAsJsonObject();
			if (need.has("quantity") && !need.get("quantity").isJsonNull()
				&& need.get("quantity").getAsInt() > 0
				&& !isTrue(need, "granted") && !isTrue(need, "consumed")
				&& !isTrue(need, "optional") && !isTrue(need, "ingredient"))
			{
				return true;
			}
		}
		return false;
	}

	private boolean isTrue(JsonObject need, String flag)
	{
		return need.has(flag) && !need.get(flag).isJsonNull() && need.get(flag).getAsBoolean();
	}
}
