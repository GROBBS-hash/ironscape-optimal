package com.ironscape.guide;

import java.util.List;
import lombok.Value;

/** A titled group of steps, e.g. "1.1: Tutorial island up to and including Wintertodt". */
@Value
public class GuideSection
{
	String title;
	List<GuideStep> steps;
}
