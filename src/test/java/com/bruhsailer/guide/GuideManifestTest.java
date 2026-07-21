package com.bruhsailer.guide;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
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
}
