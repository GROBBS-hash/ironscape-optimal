package com.bruhsailer.panel;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RichTextTest
{
	@Test
	public void escapesHtmlCharacters()
	{
		assertEquals("&lt;b&gt; &amp; &lt;/b&gt;", RichText.escape("<b> & </b>"));
	}

	@Test
	public void linkifiesWorldNumbers()
	{
		assertEquals(
			"hop to <a href='bruh:world:444'>world 444</a> for forestry",
			RichText.linkifyWorlds("hop to world 444 for forestry"));
		// Capitalised, and multiple mentions
		assertEquals(
			"<a href='bruh:world:330'>World 330</a> or <a href='bruh:world:444'>world 444</a>",
			RichText.linkifyWorlds("World 330 or world 444"));
		// "world" without a 3-digit number is prose, not a link
		assertEquals("a forestry world is best", RichText.linkifyWorlds("a forestry world is best"));
	}

	@Test
	public void lightensUnreadablyDarkColors()
	{
		// Black on a dark panel must come out lighter.
		String result = RichText.readableOnDark("#000000");
		assertTrue("expected lightened color, got " + result, !result.equals("#000000"));

		// The guide's warning red is already readable: keep it as-is.
		assertEquals("#ff3838", RichText.readableOnDark("#ff3838"));
	}
}
