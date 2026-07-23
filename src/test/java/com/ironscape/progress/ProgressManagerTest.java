package com.ironscape.progress;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ProgressManagerTest
{
	@Test
	public void countsSurviveEncodeDecodeRoundTrip()
	{
		Map<String, Integer> counts = new LinkedHashMap<>();
		counts.put("s1-14:0", 3);
		counts.put("s2-7:2", 12);

		Map<String, Integer> decoded = ProgressManager.decodeCounts(ProgressManager.encodeCounts(counts));

		assertEquals(counts, decoded);
	}

	@Test
	public void emptyAndNullDecodeToNothing()
	{
		assertTrue(ProgressManager.decodeCounts(null).isEmpty());
		assertTrue(ProgressManager.decodeCounts("").isEmpty());
		assertEquals("", ProgressManager.encodeCounts(new LinkedHashMap<>()));
	}

	@Test
	public void corruptedEntriesAreDroppedWithoutLosingTheRest()
	{
		Map<String, Integer> decoded = ProgressManager.decodeCounts(
			"s1-14:0=3,garbage,=5,s2-7:2=notanumber,s3-1:0=7");

		assertEquals(2, decoded.size());
		assertEquals(Integer.valueOf(3), decoded.get("s1-14:0"));
		assertEquals(Integer.valueOf(7), decoded.get("s3-1:0"));
	}
}
