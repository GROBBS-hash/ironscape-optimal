package com.bruhsailer.guide;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class SentenceSplitterTest
{
	private static TextRun plain(String text)
	{
		return new TextRun(text, false, false, false, false, null, null);
	}

	private static String join(List<TextRun> runs)
	{
		StringBuilder sb = new StringBuilder();
		runs.forEach(r -> sb.append(r.getText()));
		return sb.toString();
	}

	@Test
	public void splitsSentencesAndKeepsFormattingAcrossTheCut()
	{
		// One bold run spanning the end of sentence 1 and start of sentence 2.
		List<TextRun> runs = Arrays.asList(
			plain("Grab a knife. Talk to "),
			new TextRun("Duke Horacio. Burn logs", true, false, false, false, null, null),
			plain(" to 15 firemaking."));

		List<List<TextRun>> sentences = SentenceSplitter.split(runs);

		assertEquals(3, sentences.size());
		assertEquals("Grab a knife.", join(sentences.get(0)));
		assertEquals("Talk to Duke Horacio.", join(sentences.get(1)));
		assertEquals("Burn logs to 15 firemaking.", join(sentences.get(2)));
		// the bold style survived the slicing
		assertEquals(true, sentences.get(1).get(1).isBold());
		assertEquals("Duke Horacio.", sentences.get(1).get(1).getText());
	}

	@Test
	public void splitsAtNewlines()
	{
		List<List<TextRun>> sentences = SentenceSplitter.split(Arrays.asList(
			plain("do the thing\nthen the other thing\n")));

		assertEquals(2, sentences.size());
		assertEquals("do the thing", join(sentences.get(0)));
		assertEquals("then the other thing", join(sentences.get(1)));
	}

	@Test
	public void mergesTrivialFragmentsIntoThePreviousSentence()
	{
		// ")." alone is not a sentence; it belongs to the previous one.
		List<List<TextRun>> sentences = SentenceSplitter.split(Arrays.asList(
			plain("Choose a look (this is fine. ). Bank everything.")));

		assertEquals(2, sentences.size());
	}

	@Test
	public void splitsClausesAtCommasButNotInsideParentheses()
	{
		List<List<TextRun>> clauses = SentenceSplitter.split(Arrays.asList(
			plain("Grab a knife, 2 buckets (hop once, if busy), cabbage, leather boots.")));

		assertEquals(4, clauses.size());
		assertEquals("Grab a knife", join(clauses.get(0)));
		assertEquals("2 buckets (hop once, if busy)", join(clauses.get(1)));
		assertEquals("cabbage", join(clauses.get(2)));
		assertEquals("leather boots.", join(clauses.get(3)));
	}

	@Test
	public void shortItemsLikeSawAndGpGetTheirOwnFragment()
	{
		List<List<TextRun>> clauses = SentenceSplitter.split(Arrays.asList(
			plain("grab your hammer, saw, steel nails, one POH tab, GP, burnt meat")));

		assertEquals(6, clauses.size());
		assertEquals("saw", join(clauses.get(1)));
		assertEquals("GP", join(clauses.get(4)));
	}

	@Test
	public void doesNotSplitNumbersWithThousandsSeparators()
	{
		List<List<TextRun>> clauses = SentenceSplitter.split(Arrays.asList(
			plain("Buy 1,500 arrow shafts")));

		assertEquals(1, clauses.size());
		assertEquals("Buy 1,500 arrow shafts", join(clauses.get(0)));
	}
}
