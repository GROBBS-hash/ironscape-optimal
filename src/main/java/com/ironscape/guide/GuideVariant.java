package com.ironscape.guide;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The guides this plugin can follow. Everything (progress, annotations,
 * the loader, the manifest) is keyed by variant, so adding one is a new
 * enum entry plus its bundled JSON.
 *
 * OZIRIS is the Ironman Efficiency Guide v4 in the community "Enhanced
 * 2026" edition from https://ironman.guide/, scraped by
 * tools/scrape-oziris.mjs and bundled WITH the authors' permission
 * (Oziris and the ironman.guide maintainers, 2026-07-23). A BRUHsailer
 * variant existed during development; its authors declined, so all of
 * its content was removed.
 */
@Getter
@RequiredArgsConstructor // Lombok: generates the constructor taking the two fields below
public enum GuideVariant
{
	OZIRIS("Ironman Efficiency (Oziris)", "guide_data_oziris.json", true);

	/** Name shown to the user in the panel. */
	private final String displayName;

	/** Bundled resource file next to GuideLoader.class on the classpath. */
	private final String resourceName;

	/**
	 * True when the guide's authors already write ONE action per step
	 * (Oziris) — each step becomes a single tickbox, exactly mirroring
	 * the source. False for prose guides (BRUHsailer), whose paragraphs
	 * the SentenceSplitter breaks into tickable clauses.
	 */
	private final boolean atomicSteps;
}
