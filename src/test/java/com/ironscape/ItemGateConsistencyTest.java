package com.ironscape;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Three places in the plugin walk a step's annotation items and decide
 * whether each one has a vote. They must agree about which flags mean
 * "this is not an objective, so it gets no vote".
 *
 * <p>They drifted, and the drift was invisible for months because it
 * failed in the SILENT direction. {@code annotationItemsCarried} — the
 * arrival gate — skipped only {@code consumed} and money, while
 * {@code possessionObjectiveMet} and {@code purchaseListAcquired} also
 * skipped {@code optional} and {@code granted}. The consequence was that
 * the documented remedy for "this step will not tick" did nothing at all:
 * marking an item {@code optional} was applied three times across waves
 * 19, 26 and 27 (the ghost's skull, the Camelot lit candle, step 280's
 * dyes) and never unwedged anything, because the gate doing the blocking
 * never read the flag. Nine steps were blocked guide-wide when it was
 * finally measured.
 *
 * <p>Nothing in the compiler connects these three, and nothing in play
 * distinguishes "blocked by a wrongly-listed item" from "detection is
 * broken" — the owner sees only a step that will not tick. So the rule is
 * asserted on the source itself, the same way {@link HubComplianceTest}
 * asserts the hub's rules.
 *
 * <p>Deliberately NOT required of these gates: {@code ingredient} (a
 * material for something you make at the destination really is meant to
 * be in your bag) and unnumbered carry-list items (a bare "Lumby" ticks
 * on arriving PREPARED, never on jogging past — 51 steps depend on it).
 */
public class ItemGateConsistencyTest
{
	/**
	 * Methods that decide whether an annotation item gets a vote.
	 *
	 * <p>{@code gateableItems} rather than its caller {@code
	 * purchaseListAcquired}: the caller delegates the whole "which items
	 * count" question to it, so that is where the rule lives.
	 */
	private static final List<String> GATES = Arrays.asList(
		"annotationItemsCarried",
		"possessionObjectiveMet",
		"gateableItems");

	/** Flags that always mean "not an objective, so no vote". */
	private static final List<String> EXEMPTIONS = Arrays.asList("optional", "granted");

	@Test
	public void everyItemGateHonoursTheNotAnObjectiveFlags() throws IOException
	{
		String source = readPlugin();
		List<String> failures = new ArrayList<>();
		for (String gate : GATES)
		{
			String body = methodBody(source, gate);
			for (String flag : EXEMPTIONS)
			{
				if (!body.contains("need." + flag))
				{
					failures.add(gate + "() never reads need." + flag);
				}
			}
		}
		assertTrue("An item flagged as not-an-objective must not get a vote in ANY"
				+ " of the gates — otherwise marking an item optional silently"
				+ " changes nothing and a step stays blocked with no way to tell"
				+ " why. Missing:\n  " + String.join("\n  ", failures),
			failures.isEmpty());
	}

	private static String readPlugin() throws IOException
	{
		Path file = Paths.get("src/main/java/com/ironscape/IronscapePlugin.java");
		if (!Files.isRegularFile(file))
		{
			// Say so rather than pass silently, which is how a check stops
			// meaning anything (wave 25).
			throw new IOException("cannot find " + file + " from " + Paths.get("").toAbsolutePath());
		}
		return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
	}

	/**
	 * The text of one method, from its DECLARATION to its closing brace, by
	 * counting braces. Crude, and enough: these are ordinary methods in one
	 * file, and a rename fails the test loudly rather than quietly passing —
	 * which is the failure mode that matters here.
	 *
	 * <p>The declaration is matched on its access modifier, NOT on the bare
	 * name. A plain name search finds the first CALL SITE instead — every
	 * one of these is called before it is declared — and then brace-matching
	 * from there reads some unrelated block. The first version of this test
	 * did exactly that and reported all three gates broken, including the
	 * two that were already correct.
	 */
	private static String methodBody(String source, String method)
	{
		java.util.regex.Matcher declaration = java.util.regex.Pattern
			// The dot matters: a return type can be qualified, as in
			// "private List<StepAnnotation.ItemNeed> gateableItems(...)".
			.compile("(private|protected|public)[\\w\\s.<>,\\[\\]]*\\b" + method + "\\s*\\(")
			.matcher(source);
		if (!declaration.find())
		{
			fail("no method DECLARATION named " + method + " in IronscapePlugin —"
				+ " if it was renamed, update GATES; if it was removed, say why here.");
		}
		int at = declaration.start();
		int open = source.indexOf('{', at);
		int depth = 0;
		for (int i = open; i < source.length(); i++)
		{
			char c = source.charAt(i);
			if (c == '{')
			{
				depth++;
			}
			else if (c == '}' && --depth == 0)
			{
				return source.substring(open, i + 1);
			}
		}
		fail("unbalanced braces reading " + method);
		return "";
	}
}
