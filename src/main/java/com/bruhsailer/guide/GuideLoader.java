package com.bruhsailer.guide;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Parses the upstream BRUHsailer JSON (bundled as a resource) into our
 * internal model. This is the ONLY class that knows the upstream format —
 * if the guide's JSON shape ever changes, this file is the only one to fix.
 *
 * The upstream data is regenerated from the authors' Google Doc, which is
 * why it carries word-processor noise like fontSize and fontFamily; we keep
 * the formatting that matters for reading (bold, color, links) and drop the
 * rest.
 */
@Singleton // one shared instance; guides are immutable so this is safe
public class GuideLoader
{
	private final Gson gson;

	/**
	 * RuneLite provides a shared Gson instance for plugins to inject —
	 * preferred over new Gson() so settings are consistent client-wide.
	 */
	@Inject
	public GuideLoader(Gson gson)
	{
		this.gson = gson;
	}

	/** Loads a bundled guide variant. Takes ~100ms for the 1MB file. */
	public Guide load(GuideVariant variant) throws IOException
	{
		// The JSON files live in src/main/resources under this class's
		// package, so this resolves relative to GuideLoader.class.
		try (InputStream in = GuideLoader.class.getResourceAsStream(variant.getResourceName()))
		{
			if (in == null)
			{
				throw new IOException("Bundled guide data missing: " + variant.getResourceName());
			}
			return parse(variant, new InputStreamReader(in, StandardCharsets.UTF_8));
		}
	}

	/** Parses guide JSON from any reader (bundled file now; downloaded refresh later). */
	public Guide parse(GuideVariant variant, Reader reader)
	{
		RawGuide raw = gson.fromJson(reader, RawGuide.class);

		List<GuideChapter> chapters = new ArrayList<>();
		List<GuideStep> allSteps = new ArrayList<>();
		Map<String, GuideStep> byId = new LinkedHashMap<>();
		// Tracks how often each hash has been seen, to disambiguate the
		// (currently zero, but theoretically possible) case of two steps
		// with identical text.
		Map<String, Integer> idCounts = new HashMap<>();

		for (int ci = 0; ci < raw.chapters.size(); ci++)
		{
			RawChapter rawChapter = raw.chapters.get(ci);
			List<GuideSection> sections = new ArrayList<>();

			for (int si = 0; si < rawChapter.sections.size(); si++)
			{
				RawSection rawSection = rawChapter.sections.get(si);
				List<GuideStep> steps = new ArrayList<>();

				for (int ti = 0; ti < rawSection.steps.size(); ti++)
				{
					RawStep rawStep = rawSection.steps.get(ti);
					List<TextRun> content = convertRuns(rawStep.content);
					String plainText = plainText(content);

					String id = uniqueId(stepId(plainText), idCounts);
					List<NestedBlock> nested = convertNested(rawStep.nestedContent);

					GuideStep step = new GuideStep(
						id,
						ci, si, ti, allSteps.size(),
						plainText,
						content,
						buildSubSteps(id, content, nested),
						nested,
						convertParagraphs(rawStep.additionalContent),
						rawStep.metadata == null
							? Collections.emptyMap()
							: Collections.unmodifiableMap(rawStep.metadata));

					steps.add(step);
					allSteps.add(step);
					byId.put(id, step);
				}

				sections.add(new GuideSection(rawSection.title, Collections.unmodifiableList(steps)));
			}

			chapters.add(new GuideChapter(
				rawChapter.title,
				Collections.unmodifiableList(sections),
				convertFootnotes(rawChapter.footnotes)));
		}

		return new Guide(
			variant,
			raw.updatedOn,
			raw.title,
			Collections.unmodifiableList(chapters),
			Collections.unmodifiableList(allSteps),
			Collections.unmodifiableMap(byId));
	}

	/**
	 * A step's id is the first 10 hex chars of the SHA-256 of its own text
	 * (whitespace-collapsed, lowercased). Content-based rather than
	 * positional on purpose: when upstream inserts or reorders steps,
	 * every untouched step keeps its id, so saved progress and annotations
	 * survive. Only a step whose text was edited gets a new id.
	 */
	static String stepId(String plainText)
	{
		String normalized = plainText.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
		try
		{
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder();
			for (int i = 0; i < 5; i++) // 5 bytes = 10 hex chars
			{
				hex.append(String.format("%02x", hash[i]));
			}
			return hex.toString();
		}
		catch (NoSuchAlgorithmException e)
		{
			// Every JVM is required to ship SHA-256; this cannot happen.
			throw new IllegalStateException(e);
		}
	}

	/**
	 * One tickable sub-step per sentence of the main paragraph, then one
	 * per nested bullet. Falls back to a single sub-step covering the whole
	 * step if splitting somehow yields nothing.
	 */
	private static List<SubStep> buildSubSteps(String parentId, List<TextRun> content, List<NestedBlock> nested)
	{
		List<SubStep> subs = new ArrayList<>();
		for (List<TextRun> sentence : SentenceSplitter.split(content))
		{
			addSubStep(subs, parentId, 0, sentence);
		}
		for (NestedBlock block : nested)
		{
			addSubStep(subs, parentId, block.getLevel(), block.getContent());
		}
		if (subs.isEmpty())
		{
			addSubStep(subs, parentId, 0, content);
		}
		return Collections.unmodifiableList(subs);
	}

	private static void addSubStep(List<SubStep> subs, String parentId, int indentLevel, List<TextRun> runs)
	{
		if (runs.isEmpty())
		{
			return;
		}
		String plain = plainText(runs);
		if (plain.trim().isEmpty())
		{
			return;
		}
		subs.add(new SubStep(
			parentId + ":" + subs.size(),
			subs.size(),
			indentLevel,
			plain,
			Collections.unmodifiableList(runs)));
	}

	/** Appends -2, -3... if the same text (and so the same hash) appears twice. */
	private static String uniqueId(String id, Map<String, Integer> idCounts)
	{
		int seen = idCounts.merge(id, 1, Integer::sum);
		return seen == 1 ? id : id + "-" + seen;
	}

	private static List<TextRun> convertRuns(List<RawRun> rawRuns)
	{
		if (rawRuns == null)
		{
			return Collections.emptyList();
		}
		List<TextRun> runs = new ArrayList<>(rawRuns.size());
		for (RawRun r : rawRuns)
		{
			if (r.text == null || r.text.isEmpty())
			{
				continue;
			}
			RawFormatting f = r.formatting;
			runs.add(new TextRun(
				r.text,
				f != null && Boolean.TRUE.equals(f.bold),
				f != null && Boolean.TRUE.equals(f.italic),
				f != null && Boolean.TRUE.equals(f.underline),
				f != null && Boolean.TRUE.equals(f.strikethrough),
				f == null ? null : toHex(f.color),
				f == null ? null : f.url));
		}
		return Collections.unmodifiableList(runs);
	}

	private static List<NestedBlock> convertNested(List<RawNested> rawNested)
	{
		if (rawNested == null)
		{
			return Collections.emptyList();
		}
		List<NestedBlock> blocks = new ArrayList<>(rawNested.size());
		for (RawNested n : rawNested)
		{
			blocks.add(new NestedBlock(n.level, convertRuns(n.content)));
		}
		return Collections.unmodifiableList(blocks);
	}

	private static List<List<TextRun>> convertParagraphs(List<List<RawRun>> rawParagraphs)
	{
		if (rawParagraphs == null)
		{
			return Collections.emptyList();
		}
		List<List<TextRun>> paragraphs = new ArrayList<>(rawParagraphs.size());
		for (List<RawRun> p : rawParagraphs)
		{
			paragraphs.add(convertRuns(p));
		}
		return Collections.unmodifiableList(paragraphs);
	}

	private static List<List<TextRun>> convertFootnotes(List<RawFootnote> rawFootnotes)
	{
		if (rawFootnotes == null)
		{
			return Collections.emptyList();
		}
		List<List<TextRun>> footnotes = new ArrayList<>(rawFootnotes.size());
		for (RawFootnote fn : rawFootnotes)
		{
			footnotes.add(convertRuns(fn.content));
		}
		return Collections.unmodifiableList(footnotes);
	}

	/** Upstream colors are float r/g/b in 0..1; the panel wants "#rrggbb". */
	private static String toHex(RawColor color)
	{
		if (color == null)
		{
			return null;
		}
		return String.format("#%02x%02x%02x",
			Math.round(color.r * 255), Math.round(color.g * 255), Math.round(color.b * 255));
	}

	private static String plainText(List<TextRun> runs)
	{
		StringBuilder sb = new StringBuilder();
		for (TextRun run : runs)
		{
			sb.append(run.getText());
		}
		return sb.toString();
	}

	// ------------------------------------------------------------------
	// The classes below mirror the upstream JSON exactly. Gson fills them
	// by matching field names to JSON keys. They never leave this file.
	// ------------------------------------------------------------------

	private static class RawGuide
	{
		String updatedOn;
		String title;
		List<RawChapter> chapters;
	}

	private static class RawChapter
	{
		String title;
		List<RawSection> sections;
		List<RawFootnote> footnotes;
		// upstream also has titleFormatted; the plain title is enough for us
	}

	private static class RawSection
	{
		String title;
		List<RawStep> steps;
	}

	private static class RawStep
	{
		List<RawRun> content;
		List<RawNested> nestedContent;
		// a list of paragraphs, each paragraph being a list of runs
		List<List<RawRun>> additionalContent;
		Map<String, String> metadata;
	}

	private static class RawNested
	{
		int level;
		List<RawRun> content;
	}

	private static class RawFootnote
	{
		List<RawRun> content;
		// upstream also has "type", always "chapter_footnote" so far
	}

	private static class RawRun
	{
		String text;
		RawFormatting formatting;
	}

	private static class RawFormatting
	{
		Boolean bold;
		Boolean italic;
		Boolean underline;
		Boolean strikethrough;
		String url;
		RawColor color;
		// fontSize, fontFamily, isLink also exist upstream; not needed
	}

	private static class RawColor
	{
		float r;
		float g;
		float b;
	}
}
