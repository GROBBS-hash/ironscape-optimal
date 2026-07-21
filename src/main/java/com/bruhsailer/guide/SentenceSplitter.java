package com.bruhsailer.guide;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a step's styled text into individual actions, preserving
 * formatting runs across the cuts. Used to turn one prose step into
 * tickable sub-steps.
 *
 * Two passes:
 *  1. sentences — cut after . ! ? when followed by whitespace and a
 *     capital/digit/bracket, and at newlines;
 *  2. clauses — cut sentences at ", " and "; ", because the guide writes
 *     action lists that way ("Grab a knife, 2 buckets, cabbage"). Commas
 *     inside parentheses do NOT split, so "(hop once)" stays attached.
 *
 * Heuristic, not perfect: an abbreviation before a number ("incl. 401")
 * can over-split — the cost is only cosmetic granularity, so we accept it.
 */
final class SentenceSplitter
{
	private static final Pattern SENTENCE_BOUNDARY =
		Pattern.compile("(?<=[.!?])\\s+(?=[\"(A-Z0-9])|\\n+");

	/**
	 * Fragments shorter than this (or with no letters/digits) merge into
	 * the previous sentence. Two, not more: "saw" and "GP" are real items
	 * that deserve their own row.
	 */
	private static final int MIN_FRAGMENT_LENGTH = 2;

	private SentenceSplitter()
	{
	}

	static List<List<TextRun>> split(List<TextRun> runs)
	{
		StringBuilder sb = new StringBuilder();
		for (TextRun run : runs)
		{
			sb.append(run.getText());
		}
		String plain = sb.toString();

		List<int[]> ranges = new ArrayList<>();
		Matcher matcher = SENTENCE_BOUNDARY.matcher(plain);
		int previousEnd = 0;
		while (matcher.find())
		{
			addClauses(ranges, plain, previousEnd, matcher.start());
			previousEnd = matcher.end();
		}
		addClauses(ranges, plain, previousEnd, plain.length());

		List<List<TextRun>> sentences = new ArrayList<>(ranges.size());
		for (int[] range : ranges)
		{
			sentences.add(slice(runs, range[0], range[1]));
		}
		return sentences;
	}

	/**
	 * Splits one sentence [from, to) into clauses at top-level ", " and
	 * "; " (never inside parentheses, never without following whitespace —
	 * which also protects numbers like "1,500").
	 */
	private static void addClauses(List<int[]> ranges, String plain, int from, int to)
	{
		int parenDepth = 0;
		int clauseStart = from;
		for (int i = from; i < to; i++)
		{
			char c = plain.charAt(i);
			if (c == '(')
			{
				parenDepth++;
			}
			else if (c == ')')
			{
				parenDepth = Math.max(0, parenDepth - 1);
			}
			else if (parenDepth == 0 && (c == ',' || c == ';')
				&& i + 1 < to && Character.isWhitespace(plain.charAt(i + 1)))
			{
				addRange(ranges, plain, clauseStart, i); // the , or ; itself is dropped
				clauseStart = i + 1;
			}
		}
		addRange(ranges, plain, clauseStart, to);
	}

	/** Adds [from, to) trimmed; merges trivial fragments (") ." etc.) into the previous range. */
	private static void addRange(List<int[]> ranges, String plain, int from, int to)
	{
		while (from < to && Character.isWhitespace(plain.charAt(from)))
		{
			from++;
		}
		while (to > from && Character.isWhitespace(plain.charAt(to - 1)))
		{
			to--;
		}
		if (from >= to)
		{
			return;
		}

		String text = plain.substring(from, to);
		boolean substantial = text.length() >= MIN_FRAGMENT_LENGTH
			&& text.chars().anyMatch(Character::isLetterOrDigit);
		if (!substantial && !ranges.isEmpty())
		{
			ranges.get(ranges.size() - 1)[1] = to;
			return;
		}
		ranges.add(new int[]{from, to});
	}

	/** Cuts the run list down to the characters in [from, to), keeping each fragment's style. */
	private static List<TextRun> slice(List<TextRun> runs, int from, int to)
	{
		List<TextRun> out = new ArrayList<>();
		int position = 0;
		for (TextRun run : runs)
		{
			int start = position;
			int end = position + run.getText().length();
			position = end;

			int sliceStart = Math.max(from, start);
			int sliceEnd = Math.min(to, end);
			if (sliceStart >= sliceEnd)
			{
				continue;
			}
			out.add(new TextRun(
				run.getText().substring(sliceStart - start, sliceEnd - start),
				run.isBold(), run.isItalic(), run.isUnderline(), run.isStrikethrough(),
				run.getColorHex(), run.getUrl()));
		}
		return out;
	}
}
