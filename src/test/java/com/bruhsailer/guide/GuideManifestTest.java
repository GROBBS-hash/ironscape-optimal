package com.bruhsailer.guide;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class GuideManifestTest
{
	/** A guide whose single chapter has one section per id-list. */
	private static Guide guideOf(List<List<String>> sectionIds)
	{
		List<GuideSection> sections = new ArrayList<>();
		List<GuideStep> allSteps = new ArrayList<>();
		Map<String, GuideStep> byId = new LinkedHashMap<>();
		for (int si = 0; si < sectionIds.size(); si++)
		{
			List<GuideStep> steps = new ArrayList<>();
			for (int ti = 0; ti < sectionIds.get(si).size(); ti++)
			{
				String id = sectionIds.get(si).get(ti);
				GuideStep step = new GuideStep(id, 0, si, ti, allSteps.size(),
					"text of " + id, Collections.emptyList(), Collections.emptyList(),
					Collections.emptyList(), Collections.emptyList(), Collections.emptyMap());
				steps.add(step);
				allSteps.add(step);
				byId.put(id, step);
			}
			sections.add(new GuideSection("1." + (si + 1) + ": test", steps));
		}
		GuideChapter chapter = new GuideChapter("c1", sections, Collections.emptyList());
		return new Guide(GuideVariant.MAIN, "today", "test",
			Collections.singletonList(chapter), allSteps, byId);
	}

	private static List<GuideManifest.ManifestStep> manifestOf(List<List<String>> sectionIds)
	{
		List<GuideManifest.ManifestStep> steps = new ArrayList<>();
		for (int si = 0; si < sectionIds.size(); si++)
		{
			for (int ti = 0; ti < sectionIds.get(si).size(); ti++)
			{
				GuideManifest.ManifestStep step = new GuideManifest.ManifestStep();
				step.id = sectionIds.get(si).get(ti);
				step.chapter = 0;
				step.section = si;
				step.step = ti;
				steps.add(step);
			}
		}
		return steps;
	}

	@Test
	public void editedStepInPlaceIsRemapped()
	{
		List<GuideManifest.ManifestStep> previous =
			manifestOf(Arrays.asList(Arrays.asList("aaa", "bbb", "ccc")));
		Guide current = guideOf(Arrays.asList(Arrays.asList("aaa", "EDITED", "ccc")));

		Map<String, String> remap = GuideManifest.pairEditedSteps(previous, current);

		assertEquals(1, remap.size());
		assertEquals("EDITED", remap.get("bbb"));
	}

	@Test
	public void insertionSkipsTheWholeSection()
	{
		// A new step shifts every later index; positional pairing would
		// remap wrongly, so the section must be left alone.
		List<GuideManifest.ManifestStep> previous =
			manifestOf(Arrays.asList(Arrays.asList("aaa", "bbb")));
		Guide current = guideOf(Arrays.asList(Arrays.asList("aaa", "NEW", "bbb")));

		assertTrue(GuideManifest.pairEditedSteps(previous, current).isEmpty());
	}

	@Test
	public void reorderIsNotTreatedAsAnEdit()
	{
		// Both ids still exist — swapping positions must not remap.
		List<GuideManifest.ManifestStep> previous =
			manifestOf(Arrays.asList(Arrays.asList("aaa", "bbb")));
		Guide current = guideOf(Arrays.asList(Arrays.asList("bbb", "aaa")));

		assertTrue(GuideManifest.pairEditedSteps(previous, current).isEmpty());
	}

	@Test
	public void editsInOneSectionDoNotBlockAnother()
	{
		List<GuideManifest.ManifestStep> previous = manifestOf(Arrays.asList(
			Arrays.asList("aaa", "bbb"),
			Arrays.asList("ccc", "ddd")));
		Guide current = guideOf(Arrays.asList(
			Arrays.asList("aaa", "bbb", "INSERTED"), // count changed: skip
			Arrays.asList("ccc", "EDITED")));        // clean in-place edit

		Map<String, String> remap = GuideManifest.pairEditedSteps(previous, current);

		assertEquals(1, remap.size());
		assertEquals("EDITED", remap.get("ddd"));
	}

	@Test
	public void remapIdRewritesStepAndSubIds()
	{
		Map<String, String> remap = Collections.singletonMap("old123", "new456");

		assertEquals("new456", GuideManifest.remapId("old123", remap));
		assertEquals("new456:3", GuideManifest.remapId("old123:3", remap));
		assertEquals("other:1", GuideManifest.remapId("other:1", remap));
		assertEquals("untouched", GuideManifest.remapId("untouched", remap));
	}

	// --- sub-clause fingerprint matching ---

	/** A one-step guide whose step has the given clause texts as sub-steps. */
	private static Guide guideWithSubs(String id, String... clauses)
	{
		List<SubStep> subs = new ArrayList<>();
		for (int i = 0; i < clauses.length; i++)
		{
			subs.add(new SubStep(id + ":" + i, i, 0, clauses[i], Collections.emptyList()));
		}
		GuideStep step = new GuideStep(id, 0, 0, 0, 0,
			"text of " + id, Collections.emptyList(), subs,
			Collections.emptyList(), Collections.emptyList(), Collections.emptyMap());
		GuideChapter chapter = new GuideChapter("c1",
			Collections.singletonList(new GuideSection("1.1: test", Collections.singletonList(step))),
			Collections.emptyList());
		return new Guide(GuideVariant.MAIN, "today", "test",
			Collections.singletonList(chapter), Collections.singletonList(step),
			Collections.singletonMap(id, step));
	}

	/** A stored manifest for that same one-step guide, fingerprints included. */
	private static List<GuideManifest.ManifestStep> manifestWithSubs(String id, String... clauses)
	{
		GuideManifest.ManifestStep step = new GuideManifest.ManifestStep();
		step.id = id;
		step.chapter = 0;
		step.section = 0;
		step.step = 0;
		step.subs = new ArrayList<>();
		for (String clause : clauses)
		{
			step.subs.add(GuideLoader.stepId(clause));
		}
		return Collections.singletonList(step);
	}

	@Test
	public void subTicksFollowTheirTextWhenAClauseIsInserted()
	{
		// Upstream added a clause in the middle: positional carry would put
		// the old "bank the logs" tick on the NEW clause. Text matching
		// must follow the clause to its shifted position instead.
		List<GuideManifest.ManifestStep> previous =
			manifestWithSubs("old", "grab an axe", "bank the logs");
		Guide current = guideWithSubs("new", "grab an axe", "chop a tree", "bank the logs");

		Map<String, String> remap = GuideManifest.pairEditedSteps(previous, current);

		assertEquals("new", GuideManifest.remapId("old", remap));
		assertEquals("new:0", GuideManifest.remapId("old:0", remap));
		assertEquals("new:2", GuideManifest.remapId("old:1", remap));
	}

	@Test
	public void editedClauseOrphansItsTickInsteadOfMisattaching()
	{
		List<GuideManifest.ManifestStep> previous =
			manifestWithSubs("old", "grab an axe", "chop a tree", "bank the logs");
		Guide current = guideWithSubs("new", "grab an axe", "chop two trees", "bank the logs");

		Map<String, String> remap = GuideManifest.pairEditedSteps(previous, current);

		assertEquals("new:0", GuideManifest.remapId("old:0", remap));
		assertNull("the reworded clause's tick must be dropped, not carried",
			GuideManifest.remapId("old:1", remap));
		assertEquals("new:2", GuideManifest.remapId("old:2", remap));
	}

	@Test
	public void duplicateClausesPairUpInOrder()
	{
		List<GuideManifest.ManifestStep> previous =
			manifestWithSubs("old", "drink the potion", "drink the potion", "changed bit");
		Guide current = guideWithSubs("new", "drink the potion", "drink the potion", "CHANGED bit differently");

		Map<String, String> remap = GuideManifest.pairEditedSteps(previous, current);

		assertEquals("new:0", GuideManifest.remapId("old:0", remap));
		assertEquals("new:1", GuideManifest.remapId("old:1", remap));
		assertNull(GuideManifest.remapId("old:2", remap));
	}

	@Test
	public void manifestFromBeforeFingerprintsCarriesSubIndexPositionally()
	{
		// A version-1 manifest has no sub fingerprints — the old (index
		// carried as-is) behaviour must still apply rather than orphaning.
		List<GuideManifest.ManifestStep> previous =
			manifestOf(Arrays.asList(Arrays.asList("old")));
		Guide current = guideWithSubs("new", "grab an axe", "bank the logs");

		Map<String, String> remap = GuideManifest.pairEditedSteps(previous, current);

		assertEquals("new:1", GuideManifest.remapId("old:1", remap));
	}
}
