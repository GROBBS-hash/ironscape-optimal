package com.bruhsailer.guide;

import com.google.gson.Gson;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * A real unit test (unlike BruhsailerPluginTest, which is the dev
 * launcher). Runs on every `gradlew build`.
 *
 * Deliberately asserts structural invariants rather than exact step counts,
 * so refreshing the bundled guide data doesn't break the build.
 */
public class GuideLoaderTest
{
	private final GuideLoader loader = new GuideLoader(new Gson());

	@Test
	public void loadsBothVariants() throws Exception
	{
		for (GuideVariant variant : GuideVariant.values())
		{
			Guide guide = loader.load(variant);

			assertEquals(variant, guide.getVariant());
			assertNotNull(guide.getUpdatedOn());
			assertTrue(variant + ": expected chapters", !guide.getChapters().isEmpty());
			assertTrue(variant + ": expected a substantial guide, got "
				+ guide.getAllSteps().size() + " steps", guide.getAllSteps().size() > 100);
		}
	}

	@Test
	public void atomicGuideStepsAreOneTickboxEach() throws Exception
	{
		// The Oziris guide writes one action per step — the panel must
		// mirror its step list exactly, never split it into clauses.
		Guide guide = loader.load(GuideVariant.OZIRIS);
		for (GuideStep step : guide.getAllSteps())
		{
			assertEquals("step " + step.getId() + " should be a single tickbox",
				1, step.getSubSteps().size());
		}
	}

	@Test
	public void stepIdsAreUniqueAndStable() throws Exception
	{
		Guide guide = loader.load(GuideVariant.OZIRIS);

		Set<String> ids = new HashSet<>();
		for (GuideStep step : guide.getAllSteps())
		{
			assertFalse("empty step text at global index " + step.getGlobalIndex(),
				step.getPlainText().trim().isEmpty());
			assertTrue("duplicate id " + step.getId(), ids.add(step.getId()));
			// the byId map must point back at the same step
			assertEquals(step, guide.getStepsById().get(step.getId()));

			assertFalse("step " + step.getId() + " has no sub-steps",
				step.getSubSteps().isEmpty());
			for (SubStep sub : step.getSubSteps())
			{
				assertTrue("sub id " + sub.getId() + " not under its parent",
					sub.getId().startsWith(step.getId() + ":"));
				assertTrue("duplicate sub id " + sub.getId(), ids.add(sub.getId()));
				assertFalse("empty sub-step text in " + step.getId(),
					sub.getPlainText().trim().isEmpty());
			}
		}

		// Hashing is deterministic: same text in, same id out — this is what
		// keeps progress and annotations valid across guide refreshes.
		assertEquals(GuideLoader.stepId("Some step text"), GuideLoader.stepId("some  STEP\ntext "));

		// Known value, shared with tools/extract-annotations.mjs — if this
		// ever fails, the Node script and the Java loader disagree on ids
		// and annotations would silently stop matching their steps.
		assertEquals("246d67e43e", GuideLoader.stepId("Some step text"));
	}

	@Test
	public void keepsMeaningfulFormatting() throws Exception
	{
		// Oziris step bodies are plain text; the formatting that matters
		// lives in additionalContent — green "Modern alternative" callouts
		// (color) and clickable map/safespot links (url).
		Guide guide = loader.load(GuideVariant.OZIRIS);

		boolean sawColor = false, sawLink = false;
		for (GuideStep step : guide.getAllSteps())
		{
			for (java.util.List<TextRun> paragraph : step.getAdditionalContent())
			{
				for (TextRun run : paragraph)
				{
					sawColor |= run.getColorHex() != null;
					sawLink |= run.getUrl() != null;
				}
			}
		}
		assertTrue("notes use colored callouts somewhere", sawColor);
		assertTrue("notes carry clickable links somewhere", sawLink);
	}
}
