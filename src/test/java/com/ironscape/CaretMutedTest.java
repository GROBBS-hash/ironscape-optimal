package com.ironscape;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Every JEditorPane in the panel must have its caret muted.
 *
 * <p>An un-muted caret scrolls the entire guide panel to whichever row it
 * belongs to. Each pane carries one; when the pane's document changes — which
 * happens to any row whose item counts are rewritten — the caret moves, and a
 * moving caret calls {@code scrollRectToVisible} on itself, which walks up to
 * the JScrollPane and drags the view there.
 *
 * <p>It cost six rounds of diagnosis, because the panel's own scrolling code
 * is not involved and therefore logs nothing: the view simply leaves. The
 * owner met it as the panel "jumping to steps I've completed", then to steps
 * far AHEAD, on opening a bank and then a shop. It was finally pinned by a
 * change listener on the viewport printing the caller —
 * {@code DefaultCaret.adjustVisibility}, moving the view 366 -> 6855.
 *
 * <p>Nothing in the compiler or the LAF prevents the next pane from being
 * added without the call, and the symptom would be blamed on the scroll code
 * again, so the pairing is asserted here.
 */
public class CaretMutedTest
{
	@Test
	public void everyEditorPaneMutesItsCaret() throws IOException
	{
		int panes = 0;
		int muted = 0;
		List<String> files = new ArrayList<>();
		Path root = Paths.get("src/main/java");
		if (!Files.isDirectory(root))
		{
			// Say so rather than pass silently — a check that quietly measures
			// nothing is worse than no check (wave 25).
			throw new IOException("cannot find src/main/java from " + Paths.get("").toAbsolutePath());
		}
		try (Stream<Path> tree = Files.walk(root))
		{
			for (Path file : (Iterable<Path>) tree.filter(p -> p.toString().endsWith(".java"))::iterator)
			{
				String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
				int here = count(source, "new JEditorPane()");
				if (here > 0)
				{
					panes += here;
					files.add(file.toString());
				}
				// The declaration reads "private static void muteCaret(", which
				// must not be counted as one of its own call sites.
				muted += count(source, "muteCaret(") - count(source, "void muteCaret(");
			}
		}
		assertEquals("Every JEditorPane must be passed to muteCaret(), or it will"
				+ " scroll the whole panel to itself whenever its text changes."
				+ " Panes live in: " + files,
			panes, muted);
	}

	private static int count(String haystack, String needle)
	{
		int n = 0;
		for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1))
		{
			n++;
		}
		return n;
	}
}
