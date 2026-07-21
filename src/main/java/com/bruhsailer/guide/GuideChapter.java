package com.bruhsailer.guide;

import java.util.List;
import lombok.Value;

/** One of the guide's three chapters. */
@Value
public class GuideChapter
{
	String title;
	List<GuideSection> sections;
	/** End-of-chapter notes (expected stats etc.), one list of runs per footnote. */
	List<List<TextRun>> footnotes;
}
