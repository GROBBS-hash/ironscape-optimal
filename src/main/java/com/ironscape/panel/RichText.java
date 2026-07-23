package com.ironscape.panel;

import com.ironscape.guide.TextRun;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts guide text runs into the small subset of HTML that Swing
 * components can render. All panel text goes through here.
 */
final class RichText
{
	/** Pixel width the HTML body wraps at — forces Swing to compute a sane height. */
	private static final int BODY_WIDTH_PX = 165;

	/** Link prefix for "world 444" hop links; StepRow recognises it on click. */
	static final String WORLD_LINK_PREFIX = "bruh:world:";

	/** "world 444" / "World 330" — always a hoppable world number in this guide. */
	private static final Pattern WORLD_MENTION = Pattern.compile(
		"\\bworld\\s+(\\d{3})\\b", Pattern.CASE_INSENSITIVE);

	private RichText()
	{
	}

	/**
	 * One run of text (a sub-step's sentence or bullet) as HTML. Completed
	 * text renders dimmed so the eye skips it. No width here — StepRow
	 * sizes the component itself.
	 *
	 * @param linkifier optional pass over each escaped fragment that may
	 *                  wrap known place names in links; null = no links
	 */
	static String runsHtml(List<TextRun> runs, boolean completed, UnaryOperator<String> linkifier)
	{
		StringBuilder sb = new StringBuilder("<html><body>");
		if (completed)
		{
			sb.append("<font color='#808080'>");
		}
		appendRuns(sb, runs, completed, linkifier);
		if (completed)
		{
			sb.append("</font>");
		}
		sb.append("</body></html>");
		return sb.toString();
	}

	/** A standalone paragraph (used for chapter footnotes). */
	static String paragraphHtml(List<TextRun> runs)
	{
		StringBuilder sb = new StringBuilder("<html><body style='width:" + BODY_WIDTH_PX + "px'>");
		appendRuns(sb, runs, false, null);
		sb.append("</body></html>");
		return sb.toString();
	}

	private static void appendRuns(StringBuilder sb, List<TextRun> runs, boolean muted,
		UnaryOperator<String> linkifier)
	{
		for (TextRun run : runs)
		{
			String text = escape(run.getText()).replace("\n", "<br>");

			// Wrap known place names in navigation links — but not inside
			// text that is already a link.
			if (linkifier != null && run.getUrl() == null)
			{
				text = linkifier.apply(text);
			}
			// "world 444" becomes a click-to-hop link. After the place
			// linkifier: place names never contain "world <digits>", so
			// the two can't nest.
			if (run.getUrl() == null)
			{
				text = linkifyWorlds(text);
			}

			if (run.isBold())
			{
				text = "<b>" + text + "</b>";
			}
			if (run.isItalic())
			{
				text = "<i>" + text + "</i>";
			}
			if (run.isUnderline())
			{
				text = "<u>" + text + "</u>";
			}
			if (run.isStrikethrough())
			{
				text = "<s>" + text + "</s>";
			}
			// Skip author colors on completed steps — everything stays dim gray.
			if (!muted && run.getColorHex() != null)
			{
				text = "<font color='" + readableOnDark(run.getColorHex()) + "'>" + text + "</font>";
			}
			if (run.getUrl() != null)
			{
				text = "<a href='" + escape(run.getUrl()) + "'>" + text + "</a>";
			}
			sb.append(text);
		}
	}

	/**
	 * Wraps the given item names (the sub's detected item goals) in OSRS
	 * Wiki links — "make the hangover cure" links straight to the page
	 * that explains HOW. Applied after place linkification, so regions
	 * already inside an anchor are skipped; first occurrence per name.
	 */
	static String linkifyWikiItems(String html, java.util.Collection<String> names)
	{
		if (names == null || names.isEmpty())
		{
			return html;
		}
		// Split into anchor and non-anchor segments; only the latter get links.
		String[] segments = html.split("(?=<a )|(?<=</a>)");
		java.util.Set<String> remaining = new java.util.LinkedHashSet<>(names);
		StringBuilder out = new StringBuilder();
		for (String segment : segments)
		{
			if (!segment.startsWith("<a "))
			{
				for (java.util.Iterator<String> it = remaining.iterator(); it.hasNext(); )
				{
					String name = it.next();
					Matcher m = Pattern.compile("(?i)(?<![\\w>])"
						+ Pattern.quote(name) + "(?![\\w<])").matcher(segment);
					if (m.find())
					{
						String page = (Character.toUpperCase(name.charAt(0)) + name.substring(1))
							.replace(' ', '_');
						segment = segment.substring(0, m.start())
							+ "<a href='https://oldschool.runescape.wiki/w/" + page + "'>"
							+ m.group() + "</a>"
							+ segment.substring(m.end());
						it.remove();
					}
				}
			}
			out.append(segment);
		}
		return out.toString();
	}

	/** Wraps "world NNN" mentions in bruh:world: links (escaped HTML in/out). */
	static String linkifyWorlds(String escapedHtml)
	{
		Matcher matcher = WORLD_MENTION.matcher(escapedHtml);
		StringBuffer sb = new StringBuffer();
		while (matcher.find())
		{
			matcher.appendReplacement(sb, Matcher.quoteReplacement(
				"<a href='" + WORLD_LINK_PREFIX + matcher.group(1) + "'>" + matcher.group() + "</a>"));
		}
		matcher.appendTail(sb);
		return sb.toString();
	}

	/**
	 * The guide was written in a Google Doc on a WHITE page, so some author
	 * colors (black, dark purple) are unreadable on RuneLite's dark panels.
	 * Blend such colors toward white until they have enough contrast.
	 */
	static String readableOnDark(String hex)
	{
		int r = Integer.parseInt(hex.substring(1, 3), 16);
		int g = Integer.parseInt(hex.substring(3, 5), 16);
		int b = Integer.parseInt(hex.substring(5, 7), 16);

		for (int i = 0; i < 3 && luminance(r, g, b) < 110; i++)
		{
			r = (r + 255) / 2;
			g = (g + 255) / 2;
			b = (b + 255) / 2;
		}
		return String.format("#%02x%02x%02x", r, g, b);
	}

	private static double luminance(int r, int g, int b)
	{
		return 0.299 * r + 0.587 * g + 0.114 * b;
	}

	static String escape(String text)
	{
		return text
			.replace("&", "&amp;")
			.replace("<", "&lt;")
			.replace(">", "&gt;");
	}
}
