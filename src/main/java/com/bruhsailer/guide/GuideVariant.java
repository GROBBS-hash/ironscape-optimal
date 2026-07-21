package com.bruhsailer.guide;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Guide versions. Upstream also publishes a "Landlubber" variant; the owner
 * only follows Main, so that's all we ship — but everything (progress,
 * annotations, the loader) stays keyed by variant, so re-adding one is just
 * a new enum entry plus its bundled JSON.
 */
@Getter
@RequiredArgsConstructor // Lombok: generates the constructor taking the two fields below
public enum GuideVariant
{
	MAIN("Main", "guide_data.json");

	/** Name shown to the user in the panel. */
	private final String displayName;

	/** Bundled resource file next to GuideLoader.class on the classpath. */
	private final String resourceName;
}
