package com.bruhsailer.guide;

import java.util.List;
import lombok.Value;

/**
 * One tickable action inside a step. Steps in the guide are paragraphs
 * covering several actions ("grab X. talk to Y. bank Z.") plus nested
 * bullets — each becomes a SubStep so it can be checked off and annotated
 * (location target, requirement) individually.
 */
@Value
public class SubStep
{
	/**
	 * Parent step id + ":" + position, e.g. "0b04db5277:2". Positional is
	 * fine here: if upstream edits the step's text, the PARENT hash id
	 * changes anyway. GuideManifest then re-links each saved sub tick to
	 * the identically-worded clause of the edited step (by fingerprint),
	 * so ticks survive edits that reword only one clause of a step.
	 */
	String id;

	/** 0-based position within the parent step. */
	int index;

	/** 0 for sentences of the main paragraph; nested bullets keep their depth (1-4). */
	int indentLevel;

	String plainText;

	List<TextRun> content;
}
