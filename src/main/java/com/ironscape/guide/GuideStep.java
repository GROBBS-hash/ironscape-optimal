package com.ironscape.guide;

import java.util.List;
import java.util.Map;
import lombok.Value;

/**
 * One actionable step of the guide — the unit the player checks off.
 */
@Value
public class GuideStep
{
	/**
	 * Stable identifier: a short hash of the step's own text (see
	 * GuideLoader#stepId). Progress and annotations are keyed by this, so
	 * upstream inserting or reordering steps does NOT invalidate them —
	 * only editing this step's text does.
	 */
	String id;

	/** 0-based position of the chapter/section/step, for display and sorting. */
	int chapterIndex;
	int sectionIndex;
	/** Position within the section. */
	int stepIndex;
	/** Position across the whole guide (0 .. total-1), for the progress bar. */
	int globalIndex;

	/** The full step text with formatting stripped — used for search and hashing. */
	String plainText;

	/** The step text as styled runs, for rendering. */
	List<TextRun> content;

	/**
	 * The step broken into tickable actions: its sentences, then its nested
	 * bullets. Never empty — a one-sentence step has exactly one SubStep.
	 */
	List<SubStep> subSteps;

	/** Indented sub-bullets (gear lists, side notes). Often empty. */
	List<NestedBlock> nestedContent;

	/** Extra trailing paragraphs some steps have, one list of runs each. Usually empty. */
	List<List<TextRun>> additionalContent;

	/**
	 * Upstream per-step info: gp_stack, items_needed, total_time,
	 * skills_quests_met. Values are free-form prose.
	 */
	Map<String, String> metadata;
}
