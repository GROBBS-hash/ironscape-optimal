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
	public void lightensUnreadablyDarkColors()
	{
		// Black on a dark panel must come out lighter.
		String result = RichText.readableOnDark("#000000");
		assertTrue("expected lightened color, got " + result, !result.equals("#000000"));

		// The guide's warning red is already readable: keep it as-is.
		assertEquals("#ff3838", RichText.readableOnDark("#ff3838"));
	}
}
