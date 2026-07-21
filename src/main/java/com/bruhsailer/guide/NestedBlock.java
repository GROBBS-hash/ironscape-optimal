package com.bruhsailer.guide;

import java.util.List;
import lombok.Value;

/**
 * An indented sub-bullet under a step. The guide uses up to 4 levels of
 * nesting for things like gear lists and side-notes.
 */
@Value
public class NestedBlock
{
	/** Indent depth, 1 = first level under the step. */
	int level;
	List<TextRun> content;
}
