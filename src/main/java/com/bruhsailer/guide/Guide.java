package com.bruhsailer.guide;

import java.util.List;
import java.util.Map;
import lombok.Value;

/**
 * A fully loaded guide variant. This is what the rest of the plugin works
 * with — nothing outside GuideLoader ever sees the upstream JSON shape.
 */
@Value
public class Guide
{
	GuideVariant variant;
	/** Upstream publish date, e.g. "2026-07-17". */
	String updatedOn;
	String title;
	List<GuideChapter> chapters;

	/** Every step in guide order — convenient for search and the progress bar. */
	List<GuideStep> allSteps;

	/** Lookup by stable step id — used by progress and annotations. */
	Map<String, GuideStep> stepsById;
}
