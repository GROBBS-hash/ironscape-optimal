package com.ironscape.panel;

import com.ironscape.annotations.StepAnnotation;
import com.ironscape.goals.GoalDetector;
import com.ironscape.guide.GuideStep;
import com.ironscape.items.ItemTracker;
import com.ironscape.guide.SubStep;
import com.ironscape.guide.TextRun;
import com.ironscape.places.PlaceManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.event.HyperlinkEvent;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.LinkBrowser;

/**
 * One guide step rendered as a tick-list:
 *
 *   [master checkbox] Step N          [⌖][Go]   <- header (multi-action steps)
 *     [x] first sentence of the step  [⌖][Go]
 *         Logs 87/110 · Knife 1/1               <- item badge, when annotated
 *     [ ]   nested bullet             [⌖][Go]
 *   dim trailing note (additionalContent)
 *
 * Known place names inside the text are links: clicking one routes there
 * via Shortest Path. Each sub-step ticks, captures a location, and
 * navigates independently. A step with a single action skips the header.
 */
class StepRow extends JPanel
{
	/**
	 * Width the text of a level-0 sub-step is laid out at. Panel is 225px;
	 * subtract panel padding, checkbox column, button column (⌖ AND Go),
	 * scrollbar, AND the card chrome (7px padding each side + 1px edges —
	 * forgetting it clipped the buttons off the first cards build) —
	 * being too generous here makes the widest row widen the whole column
	 * and push buttons off-screen.
	 * JEditorPane does NOT wrap to its container on its own — see setHtml().
	 */
	private static final int TEXT_WIDTH = 112;
	private static final int INDENT_PER_LEVEL = 10;

	/**
	 * Wrap a full-card-width line, the way the NOTE block does it.
	 *
	 * A width in the html — `<body style='width:150px'>` — does NOT constrain
	 * a JLabel here: the card's own self-check went from 228px to 255px when
	 * that was tried, because the label reports the UNWRAPPED text width and
	 * a JLabel never caps its own maximum size (Component#getMaximumSize
	 * hands back Short.MAX_VALUE), so a Y_AXIS BoxLayout lets it widen to
	 * whatever it asks for. htmlPane already solves this properly — explicit
	 * setSize, then preferred AND maximum pinned to it — which is why notes
	 * and sub-step text have always wrapped while these lines did not. Use it
	 * rather than inventing a third mechanism.
	 */
	private JEditorPane wrappedLine(String html, int leftIndent, int fontSize, Color fg)
	{
		return htmlPane("<html><body>" + html + "</body></html>", leftIndent,
			new Font(Font.DIALOG, Font.PLAIN, fontSize), fg);
	}

	/**
	 * A wrapped line inside a coloured box — the jump buttons and the warning
	 * chips. The box's border and padding sit OUTSIDE the pane's own pinned
	 * width, so the whole thing stays inside the 223px viewport.
	 */
	private JPanel boxedLine(String html, Color fg, int fontSize)
	{
		JEditorPane pane = wrappedLine(html, 0, fontSize, fg);
		JPanel box = new JPanel(new BorderLayout());
		box.setOpaque(false);
		box.setAlignmentX(LEFT_ALIGNMENT);
		box.add(pane, BorderLayout.CENTER);
		box.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createEmptyBorder(4, 22, 2, 0),
			BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(fg, 1),
				BorderFactory.createEmptyBorder(3, 6, 3, 6))));
		box.setMaximumSize(new Dimension(box.getPreferredSize().width,
			box.getPreferredSize().height));
		return box;
	}

	private static final Color CAPTURED_COLOR = new Color(0x4c, 0xaf, 0x50);
	private static final String SATISFIED_HEX = "#4caf50";
	private static final String IN_BANK_HEX = "#ffa000";
	private static final String MISSING_HEX = "#e57373";

	// "Cards & chips" restyle (owner-picked mockup, 2026-08-05): every
	// step is a card, metadata renders as bordered chips, notes get a
	// boxed NOTE block. ACTIVE cards are near-black so their content pops;
	// DONE cards flatten to panel-grey AND dim wholesale (paint-time
	// alpha) — the first cut styled both faces the same and active vs
	// done barely read apart (owner feedback).
	/** ACTIVE card face — near-black, warm, darker than the #282828 panel. */
	static final Color CARD_BG = new Color(0x1f, 0x1e, 0x1b);
	private static final Color CARD_EDGE = new Color(0x3f, 0x3b, 0x35);
	/** DONE card face — flat grey, melts into the panel. */
	private static final Color DONE_BG = new Color(0x2b, 0x2b, 0x2b);
	private static final Color DONE_EDGE = new Color(0x34, 0x34, 0x34);
	private static final int CARD_MARGIN_TOP = 2;
	private static final int CARD_MARGIN_BOTTOM = 6;
	/** Inset boxes: chips and the NOTE block — a step LIGHTER than the face. */
	private static final Color BOX_BG = new Color(0x2a, 0x27, 0x22);
	private static final Color BOX_EDGE = new Color(0x45, 0x40, 0x3a);
	private static final Color CHIP_FG = new Color(0xc2, 0xab, 0x7c);
	private static final Color CHIP_QUEST_FG = new Color(0x8f, 0xbf, 0x8f);
	private static final Color NOTE_FG = new Color(0xb8, 0xb1, 0xa5);
	/** Warning chips — the same red the missing-item counts use (MISSING_HEX). */
	private static final Color WARN_FG = new Color(0xe5, 0x73, 0x73);
	private static final Color NOTE_LABEL_FG = new Color(0x87, 0x7e, 0x6f);
	private static final Color ITEM_NAME_FG = new Color(0xc9, 0xc4, 0xbc);

	private final GuideStep step;
	private final RowContext ctx;

	private JCheckBox masterBox;
	private final List<SubRowUi> subRows = new ArrayList<>();

	/** One per item badge on this row; run them to re-read live item counts. */
	private final List<Runnable> badgeRefreshers = new ArrayList<>();

	StepRow(GuideStep step, RowContext ctx)
	{
		this.step = step;
		this.ctx = ctx;

		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		// The card face + edge are painted in paintComponent (a plain
		// opaque panel would flood its background over the transparent
		// margin band too); the border is pure spacing: margin + edge +
		// padding on each side.
		setOpaque(false);
		setBorder(BorderFactory.createEmptyBorder(
			CARD_MARGIN_TOP + 6, 7, CARD_MARGIN_BOTTOM + 6, 7));

		boolean multi = step.getSubSteps().size() > 1;
		if (multi)
		{
			add(buildHeader());
			addItemBadge(step.getId(), null, 22);
		}
		for (SubStep sub : step.getSubSteps())
		{
			SubRowUi row = new SubRowUi(sub, multi);
			subRows.add(row);
			add(row.panel);
			addItemBadge(multi ? sub.getId() : step.getId(), sub,
				22 + sub.getIndentLevel() * INDENT_PER_LEVEL);
			addErrandChecklist(sub, 22 + sub.getIndentLevel() * INDENT_PER_LEVEL);
		}

		addGearBadge();
		addAnnotationLink();
		addMetadataChips();

		// Right-click anywhere on the card to jump here. Attached to the card
		// AND to each row's text/panel, because a child component that gets
		// the click never bubbles it to the card.
		attachStepMenu(this);
		for (SubRowUi row : subRows)
		{
			attachStepMenu(row.panel);
			attachStepMenu(row.text);
		}

		// Trailing commentary paragraphs — informational, not tickable.
		// Boxed "NOTE" blocks (cards & chips restyle): the old grey italic
		// at #808080 sat at ~3.6:1 contrast and vanished (owner complaint).
		for (List<TextRun> paragraph : step.getAdditionalContent())
		{
			add(noteBlock(paragraph));
		}
		// An annotation method note ("Soft clay: use a bucket of water on
		// clay") renders in the same boxed NOTE style as authored notes,
		// with light structure: bold topic lead-ins, space between lines.
		String annotationNote = ctx.getAnnotations().getNote(step.getId());
		if (annotationNote != null && !annotationNote.isEmpty())
		{
			add(noteBlock(noteRuns(annotationNote)));
		}
	}

	GuideStep getStep()
	{
		return step;
	}

	/** Logged once per row: which child forced the card past the viewport. */
	private boolean widthChecked;

	/** Card face and 1px edge, inset by the transparent margin band. */
	@Override
	protected void paintComponent(java.awt.Graphics g)
	{
		// Width self-check: a child wider than the viewport clips ⌖/Go off
		// the right edge and the culprit is invisible in a screenshot —
		// name it in the log instead (this bit us twice in one evening).
		if (!widthChecked && getWidth() > 0)
		{
			widthChecked = true;
			if (getPreferredSize().width > getWidth())
			{
				java.awt.Component widest = null;
				for (java.awt.Component child : getComponents())
				{
					if (widest == null
						|| child.getPreferredSize().width > widest.getPreferredSize().width)
					{
						widest = child;
					}
				}
				org.slf4j.LoggerFactory.getLogger(StepRow.class).info(
					"card wider than viewport ({} > {}) on step {} — widest child: {} at {}px",
					getPreferredSize().width, getWidth(), step.getId(),
					widest == null ? "?" : widest.getClass().getSimpleName(),
					widest == null ? 0 : widest.getPreferredSize().width);
			}
		}
		boolean done = ctx.getProgress().isCompleted(ctx.getVariant(), step.getId());
		g.setColor(done ? DONE_BG : CARD_BG);
		g.fillRect(0, CARD_MARGIN_TOP, getWidth(),
			getHeight() - CARD_MARGIN_TOP - CARD_MARGIN_BOTTOM);
		g.setColor(done ? DONE_EDGE : CARD_EDGE);
		g.drawRect(0, CARD_MARGIN_TOP, getWidth() - 1,
			getHeight() - CARD_MARGIN_TOP - CARD_MARGIN_BOTTOM - 1);
	}

	/**
	 * A fully-done step dims WHOLESALE — one alpha over card + children —
	 * instead of restyling every child grey (which left link colors and
	 * badge colors each needing their own "done" variant).
	 */
	@Override
	public void paint(java.awt.Graphics g)
	{
		if (ctx.getProgress().isCompleted(ctx.getVariant(), step.getId()))
		{
			java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
			g2.setComposite(java.awt.AlphaComposite.SrcOver.derive(0.55f));
			super.paint(g2);
			g2.dispose();
		}
		else
		{
			super.paint(g);
		}
	}

	/** Re-read live item counts into every badge (called after inventory/bank changes). */
	void refreshItemBadges()
	{
		badgeRefreshers.forEach(Runnable::run);
	}

	/**
	 * Light formatting for AUTHORED notes: a "Topic: rest" line renders
	 * the topic bold, and a blank line separates lines — a multi-recipe
	 * note read as one dense grey slab without it (owner report).
	 */
	private static List<TextRun> noteRuns(String note)
	{
		List<TextRun> runs = new ArrayList<>();
		String[] lines = note.split("\n");
		java.util.regex.Pattern topic = java.util.regex.Pattern.compile("^([^:]{2,32}):\\s*(.*)$");
		for (int i = 0; i < lines.length; i++)
		{
			java.util.regex.Matcher m = topic.matcher(lines[i]);
			if (m.matches())
			{
				runs.add(new TextRun(m.group(1) + ": ", true, false, false, false, null, null));
				runs.add(new TextRun(m.group(2), false, false, false, false, null, null));
			}
			else
			{
				runs.add(new TextRun(lines[i], false, false, false, false, null, null));
			}
			if (i < lines.length - 1)
			{
				runs.add(new TextRun("\n\n", false, false, false, false, null, null));
			}
		}
		return runs;
	}

	/**
	 * One commentary paragraph as a boxed NOTE block: darker inset panel,
	 * a small NOTE caption, and the text at a readable warm grey — not
	 * italic, not #808080. A leading "Note:" in the prose is dropped
	 * because the caption already says it.
	 */
	private JPanel noteBlock(List<TextRun> paragraph)
	{
		List<TextRun> runs = paragraph;
		if (!runs.isEmpty())
		{
			TextRun first = runs.get(0);
			java.util.regex.Matcher prefix = java.util.regex.Pattern
				.compile("^\\s*note\\s*:?\\s*", java.util.regex.Pattern.CASE_INSENSITIVE)
				.matcher(first.getText());
			if (prefix.find() && prefix.end() > 0)
			{
				runs = new ArrayList<>(paragraph);
				runs.set(0, new TextRun(first.getText().substring(prefix.end()),
					first.isBold(), first.isItalic(), first.isUnderline(),
					first.isStrikethrough(), first.getColorHex(), first.getUrl()));
			}
		}

		JLabel caption = new JLabel("NOTE");
		caption.setFont(new Font(Font.DIALOG, Font.BOLD, 9));
		caption.setForeground(NOTE_LABEL_FG);
		caption.setAlignmentX(LEFT_ALIGNMENT);
		caption.setBorder(BorderFactory.createEmptyBorder(0, 0, 1, 0));

		// Same width-locked pane trick as sub text, so long notes wrap
		// instead of widening every row; indent 0 — the box provides it.
		JEditorPane text = htmlPane(RichText.runsHtml(runs, false, null), 0,
			new Font(Font.DIALOG, Font.PLAIN, 11), NOTE_FG);

		JPanel box = new JPanel();
		box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
		box.setBackground(BOX_BG);
		box.setBorder(BorderFactory.createEmptyBorder(3, 6, 4, 4));
		box.setAlignmentX(LEFT_ALIGNMENT);
		box.add(caption);
		box.add(text);

		// Transparent wrapper carries the sub-text indent; capping max
		// height stops BoxLayout stretching the box over trailing space.
		JPanel wrapper = new JPanel(new BorderLayout());
		wrapper.setOpaque(false);
		wrapper.setBorder(BorderFactory.createEmptyBorder(3, 22, 1, 0));
		wrapper.setAlignmentX(LEFT_ALIGNMENT);
		wrapper.add(box, BorderLayout.CENTER);
		wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE,
			wrapper.getPreferredSize().height));
		return wrapper;
	}

	/**
	 * Y offset (inside this row) of the first unticked sub-step — where a
	 * scroll should land. A giant step is taller than the panel, so
	 * scrolling to "the step" would otherwise show whichever end Swing
	 * favours instead of the player's actual position within it.
	 */
	int firstIncompleteSubY()
	{
		for (SubRowUi row : subRows)
		{
			if (!row.checkBox.isSelected())
			{
				return row.panel.getY();
			}
		}
		return 0;
	}

	/**
	 * How far INTO this card to scroll when landing on it.
	 *
	 * Skipping to the first unticked sub exists for multi-action steps
	 * taller than the panel, where the top of the card can be a screen away
	 * from the line you are on. This guide is ATOMIC — every step is one
	 * action — and its sub row sits BELOW the item list, so that rule
	 * scrolled straight past the step's own heading and the items it tells
	 * you to bring (owner, in play: "it flicks to this spot", showing the
	 * tail of the current step and the three after it).
	 *
	 * With one sub there is nothing to skip to, so land on the card.
	 */
	int scrollOffset()
	{
		return subRows.size() > 1 ? firstIncompleteSubY() : 0;
	}

	/**
	 * Adds a live "have/need" line. Sources, in priority order: reviewed
	 * item annotations for the annotation id, else item goals detected in
	 * the sub-step's own text ("buy 1250 nature runes"). Once the owning
	 * sub/step is ticked the badge greys out — a red "0/1" under a done
	 * step reads as a problem when it's just a consumed item.
	 *
	 * @param sub the sub-step this badge belongs to; null = the step
	 *            header badge of a multi-action step
	 */
	/**
	 * The chain as a CHECKLIST, the way the game shows an achievement diary:
	 * every leg listed, finished ones struck through and greyed, the one you
	 * are on in green (owner's suggestion — copying a screen he already
	 * reads beats inventing a third convention).
	 *
	 * Rows are created HERE, at build time, and only ever RESTYLED by the
	 * refresher. A row cannot appear from the per-tick path: that would need
	 * a full rebuild, which blanked the panel once already (wave 15). The
	 * label set is fixed for a chain, so nothing needs to appear later —
	 * only the three states change.
	 */
	private void addErrandChecklist(SubStep sub, int indentPx)
	{
		// The LIST comes from the annotation, which is always there; only
		// the STATE comes from the live cache.
		//
		// It used to read both from the cache, and the cache is written by
		// the per-tick guidance pass — which only runs for the step you are
		// standing on. So every other chain step rendered with no list at
		// all, and the moment step 268 completed its tasks vanished
		// entirely (owner, in play). A card should describe its step
		// whether or not you happen to be on it.
		java.util.List<StepAnnotation.Errand> chain = ctx.getAnnotations().getErrands(sub.getId());
		if (chain == null || chain.isEmpty())
		{
			chain = ctx.getAnnotations().getErrands(step.getId());
		}
		if (chain == null || chain.isEmpty())
		{
			return;
		}
		java.util.LinkedHashMap<String, String> stages =
			ctx.getErrandChecklist() == null ? null : ctx.getErrandChecklist().apply(sub.getId());
		final boolean stepDone = isBadgeDone(sub);
		for (int i = 0; i < chain.size(); i++)
		{
			// Same "index|label" key the plugin publishes state under, built
			// here from the same data so the two cannot disagree.
			final String key = i + "|" + com.ironscape.annotations.ErrandProgress.checklistLabel(chain.get(i));
			final String label = key.substring(key.indexOf("|") + 1);
			// A JEditorPane, NOT a JLabel. A label cannot be constrained by
			// an html body width: it reports its UNWRAPPED width and never
			// caps its own maximum size, so every task line was CLIPPED
			// mid-word ("Steal a cake from the Ea") instead of wrapping.
			// htmlPane pins the size properly and the note block has always
			// used it — this is wave 22's lesson, arrived at again by
			// inventing a third mechanism instead of reusing that one.
			//
			// It cost more than tidiness: the clipped half of the lever task
			// is the sentence telling you to pull the lever BACK, so the
			// player walked out of the Wilderness overland (owner, in play).
			// leftIndent ZERO, deliberately. htmlPane sizes itself as
			// TEXT_WIDTH + 40 + leftIndent because its border sits INSIDE
			// its width — so passing the indent here asks for a pane wider
			// than the card, and the card clips the overflow off the right.
			// That is why the first attempt still lost the ends of lines
			// even once they wrapped: it was cut by exactly the indent.
			// The NOTE block passes 0 for the same reason.
			JEditorPane line = htmlPane("", 0,
				new Font(Font.DIALOG, Font.PLAIN, 11), NOTE_FG);
			line.setAlignmentX(LEFT_ALIGNMENT);
			// TEXT_WIDTH, not what htmlPane chose.
			//
			// Three attempts failed by picking a width and hoping. The
			// symptom was never horizontal clipping: "A", "Sell", "U" are
			// whole FIRST LINES, so the pane was wrapping into more lines
			// than its pinned height allowed and losing the rest. That is
			// what a pane measured at one width and then laid out at a
			// narrower one does.
			//
			// Widened back to the NOTE block's width once the real cause
			// turned out to be a broken string, not the layout. At
			// TEXT_WIDTH every task wrapped to three or four lines and the
			// card grew enormous; the note block has always sized itself
			// this way and fits. The height is measured at this same width,
			// which is the part that was genuinely wrong.
			final int paneWidth = TEXT_WIDTH + 40;
			// No left indent: the border eats into the same width, and these
			// lines are full sentences that need every pixel. A little
			// vertical breathing room instead — a dozen struck-through
			// sentences with no gaps reads as a solid block (owner).
			line.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
			Runnable refresh = () -> {
				java.util.LinkedHashMap<String, String> now = ctx.getErrandChecklist() == null
					? null : ctx.getErrandChecklist().apply(sub.getId());
				// No live state means this step is not the one being
				// guided. A FINISHED step's tasks are all done by
				// definition; an unreached one's are all still ahead.
				String fallback = stepDone ? "DONE" : "TODO";
				String state = now == null ? fallback : now.getOrDefault(key, fallback);
				String body = RichText.escape(label);
				// A dozen struck-through sentences all in one weight is a
				// wall (owner: "the formatting makes it kinda hard to
				// read"). Done work is dimmed hard and gets out of the way;
				// the one you are on is bold and green; what is ahead sits
				// in between. The strikethrough then reads as texture
				// rather than as the only signal.
				if ("DONE".equals(state))
				{
					body = "<s><font color='#5f5a52'>" + body + "</font></s>";
				}
				else if ("CURRENT".equals(state))
				{
					body = "<b><font color='#4caf50'>" + body + "</font></b>";
				}
				else
				{
					body = "<font color='#b8b1a5'>" + body + "</font>";
				}
				// htmlPane pins preferred AND maximum size at construction,
				// which is what makes it wrap — but a pinned preferred size
				// is also what getPreferredSize returns, so it must be
				// cleared before the pane can re-measure the new text.
				// Height changes here whenever a task wraps to two lines.
				line.setPreferredSize(null);
				line.setMaximumSize(null);
				// A whole document, not a fragment: every other caller of
				// htmlPane passes one, and Swing's html handling is happier
				// measuring a document than a bare run of tags.
				line.setText("<html><body>" + body + "</body></html>");
				line.setSize(paneWidth, Short.MAX_VALUE);
				java.awt.Dimension fitted =
					new java.awt.Dimension(paneWidth, line.getPreferredSize().height);
				line.setPreferredSize(fitted);
				line.setMaximumSize(fitted);
			};
			refresh.run();
			badgeRefreshers.add(refresh);
			add(line);
		}
	}
	private void addItemBadge(String annotationId, SubStep sub, int indentPx)
	{
		String goalSubId = sub == null ? null : sub.getId();
		// MERGE detected goals with annotation items, requirements first —
		// annotating tool lines onto Wintertodt must not evict the step's
		// actual "cash 200,000" completion goal from the panel.
		List<StepAnnotation.ItemNeed> needs = new ArrayList<>();
		java.util.Set<String> seenNames = new java.util.HashSet<>();
		// Item ids the annotation spells out for this step. A DETECTED goal
		// that resolves to one of them is the same item said less precisely
		// — "Buy priest robes" detects one goal, which the annotation splits
		// into the two halves it actually means — so badging both showed
		// "Priest robes 1/2" above the pair that says which half is missing.
		// Display only: the goal still drives completion, and the purchase
		// gate still demands every annotated item.
		java.util.Set<Integer> annotatedIds = new java.util.HashSet<>();
		for (StepAnnotation.ItemNeed annotated : ctx.getAnnotations().getItems(annotationId))
		{
			if (annotated.id != null)
			{
				annotatedIds.add(annotated.id);
			}
		}
		if (goalSubId != null && !goalSubId.equals(annotationId))
		{
			for (StepAnnotation.ItemNeed annotated : ctx.getAnnotations().getItems(goalSubId))
			{
				if (annotated.id != null)
				{
					annotatedIds.add(annotated.id);
				}
			}
		}
		if (goalSubId != null)
		{
			for (GoalDetector.ItemGoal goal : ctx.getItemGoals()
				.getOrDefault(goalSubId, Collections.emptyList()))
			{
				if (!annotatedIds.isEmpty()
					&& annotatedIds.contains(ctx.getItems().iconIdFor(goal.getItemName())))
				{
					continue;
				}
				if (seenNames.add(goal.getItemName().toLowerCase(java.util.Locale.ROOT)))
				{
					StepAnnotation.ItemNeed need = new StepAnnotation.ItemNeed();
					need.name = goal.getItemName();
					need.quantity = goal.getQuantity();
					// A goal beats an annotation of the same name (see the
					// merge below), so a quest-granted item detected from
					// step text would lose its flag and go back to showing
					// a red shortfall. Carry that one flag over.
					need.granted = ctx.getAnnotations()
						.isGranted(goal.getItemName(), annotationId, goalSubId)
						? Boolean.TRUE : null;
					needs.add(need);
				}
			}
		}
		for (StepAnnotation.ItemNeed annotated : ctx.getAnnotations().getItems(annotationId))
		{
			if (seenNames.add(annotated.name.toLowerCase(java.util.Locale.ROOT)))
			{
				needs.add(annotated);
			}
		}
		// Single-sub steps pass the STEP id as annotationId — items keyed
		// to the sub ("624c2f822c:0"'s gate key) must still render.
		if (goalSubId != null && !goalSubId.equals(annotationId))
		{
			for (StepAnnotation.ItemNeed annotated : ctx.getAnnotations().getItems(goalSubId))
			{
				if (seenNames.add(annotated.name.toLowerCase(java.util.Locale.ROOT)))
				{
					needs.add(annotated);
				}
			}
		}
		// ERRAND STAGE ITEMS. A step whose objective lives in a chain had
		// nothing else to badge: "Kill Mordred and get bat bones/black
		// candle" detects no item goal and its annotation is a note only.
		// State comes from the chain rather than a raw count, because half
		// these items get consumed into the next stage and a plain "0/1"
		// would sit red forever once you made progress.
		if (goalSubId != null && ctx.getErrandStages() != null)
		{
			java.util.LinkedHashMap<String, String> stages =
				ctx.getErrandStages().apply(goalSubId);
			if (stages != null)
			{
				for (Map.Entry<String, String> stage : stages.entrySet())
				{
					if (!seenNames.add(stage.getKey().toLowerCase(java.util.Locale.ROOT)))
					{
						continue;             // a real goal already covers it
					}
					StepAnnotation.ItemNeed need = new StepAnnotation.ItemNeed();
					need.name = stage.getKey();
					need.quantity = 1;
					// SPENT reuses the "(used here)" styling consumed items
					// already have — muted, never a red shortfall.
					need.consumed = "SPENT".equals(stage.getValue()) ? Boolean.TRUE : null;
					needs.add(need);
				}
			}
		}
		boolean hasActionBadge = goalSubId != null && ctx.getActionBadge() != null
			&& ctx.getActionBadge().apply(goalSubId) != null;
		if (needs.isEmpty() && !hasActionBadge)
		{
			return;
		}
		SubStep badgeSub = sub;

		if (!needs.isEmpty())
		{
			// One line per item, Quest Helper-style: the item's sprite next
			// to a colored have/need count. Vertical list = nothing to wrap.
			JPanel list = new JPanel();
			list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
			list.setOpaque(false);
			list.setAlignmentX(LEFT_ALIGNMENT);
			list.setBorder(BorderFactory.createEmptyBorder(0, indentPx, 2, 0));
			list.setToolTipText("<html>Counts inventory + worn + bank (bank as of your last visit)<br>"
				+ "<b>Coloured counts</b> are requirements — the number is what you need.<br>"
				+ "<b>Grey counts</b> are the guide's carry list: no amount was ever given,"
				+ " so nothing checks them and no bank stop is made for them.</html>");
			for (StepAnnotation.ItemNeed need : needs)
			{
				// Cards & chips restyle: NAME neutral on the left (CENTER
				// of a BorderLayout, so a long one ellipsizes instead of
				// widening every row), COUNT bold + colored on the right —
				// scanning "what am I missing" goes down the right edge,
				// and the one red count finally stands out because names
				// no longer shout in the same colors.
				// "(optional)" marks keep-if-you-get-it items; "(ingredient)"
				// marks materials for the step's PRODUCTS (redberries under
				// the dyes) — both muted so requirements keep the spotlight.
				// A tag on EVERY unnumbered row said nothing and shouted it
				// seven times (owner: "can't just say BRING SOME for all
				// items"). The distinction still matters — a numbered item
				// is what the quest REQUIRES, an unnumbered one is the
				// guide's running carry advice — so it is carried by the
				// COUNT's colour instead, which is already per-row and
				// already where the eye goes. See itemCountHtml.
				// bringAhead is checked BEFORE optional because it is always
				// written with optional too (see StepAnnotation.ItemNeed):
				// "(optional)" would be a lie about an item the next step
				// genuinely needs.
				String tag = Boolean.TRUE.equals(need.bringAhead) ? "(for next step)"
					: Boolean.TRUE.equals(need.optional) ? "(optional)"
					: Boolean.TRUE.equals(need.ingredient) ? "(ingredient)"
					: Boolean.TRUE.equals(need.granted) ? "(from the quest)"
					: Boolean.TRUE.equals(need.consumed) ? "(used here)" : null;
				JLabel name = new JLabel(tag != null
					? "<html>" + RichText.escape(ItemTracker.capitalize(need.name))
						+ " <font color='#877e6f'>" + tag + "</font></html>"
					: RichText.escape(ItemTracker.capitalize(need.name)));
				name.setFont(new Font(Font.DIALOG, Font.PLAIN, 11));
				name.setForeground(ITEM_NAME_FG);
				name.setIconTextGap(4);
				if (need.id != null)
				{
					ctx.getItems().attachIconById(need.id, name);
				}
				else
				{
					// icon overrides the name for the SPRITE only — a row
					// that stands for a category ("Food") still counts by
					// its own name and just borrows a picture.
					ctx.getItems().attachIcon(
						need.icon != null ? need.icon : need.name, name);
				}

				JLabel count = new JLabel();
				count.setFont(new Font(Font.DIALOG, Font.BOLD, 11));

				Runnable refresh = () -> {
					count.setText(itemCountHtml(need, isBadgeDone(badgeSub)));
					name.setForeground(isBadgeDone(badgeSub)
						? new Color(0x80, 0x80, 0x80) : ITEM_NAME_FG);
				};
				refresh.run();
				badgeRefreshers.add(refresh);

				JPanel line = new JPanel(new BorderLayout(4, 0));
				line.setOpaque(false);
				line.setAlignmentX(LEFT_ALIGNMENT);
				// Ingredients indent under the products they make.
				if (Boolean.TRUE.equals(need.ingredient))
				{
					line.setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 0));
				}
				line.add(name, BorderLayout.CENTER);
				line.add(count, BorderLayout.EAST);
				// Fixed preferred width so the widest name never becomes
				// the row that widens the whole column; BoxLayout may
				// stretch it wider, which just parks counts at the card edge.
				int lineHeight = Math.max(name.getPreferredSize().height, 20);
				line.setPreferredSize(new Dimension(TEXT_WIDTH + 44, lineHeight));
				line.setMaximumSize(new Dimension(Integer.MAX_VALUE, lineHeight));

				// Every item line is clickable: route to its known source
				// (place/item-source/errand chain), else open its wiki page.
				// Source knowledge is re-checked at CLICK time — a place
				// captured mid-session upgrades the click from wiki to nav.
				String itemName = need.name;
				boolean routable = ctx.getPlaces().get(itemName) != null;
				line.setToolTipText("<html>Matches item name \"" + RichText.escape(need.name)
					+ "\"<br>" + (routable
						? "Click: route to where you get it"
						: "Click: open its wiki page")
					+ "<br>Counts inventory + worn + bank; 🏦 = enough, but it's banked</html>");
				line.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
				line.addMouseListener(new java.awt.event.MouseAdapter()
				{
					@Override
					public void mouseClicked(java.awt.event.MouseEvent e)
					{
						if (ctx.getPlaces().get(itemName) != null
							&& ctx.getPlaceNavigateHandler() != null)
						{
							ctx.getPlaceNavigateHandler().accept(itemName, step);
						}
						else
						{
							LinkBrowser.browse(RichText.wikiUrl(itemName));
						}
					}
				});
				list.add(line);
			}
			add(list);
			// FALL THROUGH when there is also an action badge. A step can
			// have both an item list and a skill requirement — "Train
			// Runecraft to 10, then complete Temple of the Eye" carries the
			// quest's bucket, chisel and pickaxe — and returning here meant
			// the level badge never rendered on ANY step that listed items.
			// hasActionBadge was already computed above for exactly this
			// question; it just was not asked a second time.
			if (!hasActionBadge)
			{
				return;
			}
		}

		JLabel badge = new JLabel();
		badge.setFont(new Font(Font.DIALOG, Font.PLAIN, 11));
		badge.setBorder(BorderFactory.createEmptyBorder(0, indentPx, 2, 0));
		badge.setAlignmentX(LEFT_ALIGNMENT);
		badge.setToolTipText("Live progress toward this goal (your skill level, or xp drops counted so far)");
		String actionSubId = goalSubId;
		// Checkpoint badges can name an item sprite ("Barcrawl card" heads
		// the "stamp 0/1" line) — same async icon machinery as item lines.
		String badgeIconName = ctx.getBadgeIcon() == null ? null
			: ctx.getBadgeIcon().apply(actionSubId);
		if (badgeIconName != null)
		{
			badge.setIconTextGap(4);
			ctx.getItems().attachIcon(badgeIconName, badge);
		}
		else if (ctx.getSkillIcon() != null)
		{
			// Level/counted badges get their skill's icon ("magic 33/42"
			// wears the Magic star) — synchronous, RuneLite bundles them.
			java.awt.image.BufferedImage skillImage = ctx.getSkillIcon().apply(actionSubId);
			if (skillImage != null)
			{
				badge.setIconTextGap(4);
				badge.setIcon(new javax.swing.ImageIcon(skillImage));
				badgeIconName = ""; // non-null: the width math below sees the icon
			}
		}
		// indent + wrap width must stay inside the panel column, or this
		// badge widens EVERY row and pushes the buttons off-screen. An
		// icon's width comes OUT of the html body width: the label's
		// preferred width is icon + gap + body, and the fixed 170px body
		// alone already fills the column (shipped that bug 2026-08-05 —
		// every row widened, Go/⌖ pushed off-screen).
		// 138 base: icon (36px sprite + 4 gap) + html body + the 22px
		// indent + 14px card padding must stay under the ~200px viewport —
		// the stamp badge at 154 was the widest child and clipped ⌖/Go
		// off every card that carried a badge.
		final int wrapWidth = Math.max(60,
			138 - indentPx - (badgeIconName != null ? 40 : 0));
		Runnable refresh = () -> {
			String action = ctx.getActionBadge().apply(actionSubId);
			if (isBadgeDone(badgeSub) && action != null)
			{
				// The supplier bakes its own colors in; on a done row they
				// all become the completed-text grey.
				action = action.replaceAll("color='[^']*'", "color='#808080'");
			}
			badge.setText("<html><body style='width:" + wrapWidth + "px'><b>"
				+ action + "</b></body></html>");
		};
		refresh.run();
		badgeRefreshers.add(refresh);
		add(badge);
	}

	/** Is the sub (or, for header badges, the whole step) ticked off? */
	private boolean isBadgeDone(SubStep sub)
	{
		return sub == null
			? ctx.getProgress().isCompleted(ctx.getVariant(), step.getId())
			: ctx.getProgress().isSubCompleted(ctx.getVariant(), step, sub);
	}

	/** The colored have/need COUNT of one item line (the name stays neutral). */
	private String itemCountHtml(StepAnnotation.ItemNeed need, boolean done)
	{
		// UNSPECIFIED quantity (the guide's own carry list: "bring runes,
		// gp, a pickaxe") is NOT a requirement of one. Rendering it as
		// "134/1" claims you need one fire rune and are therefore ready —
		// the owner's report, and it's misinformation in the direction that
		// matters. Show what you carry, no threshold, no green "done".
		boolean unspecified = need.quantity == null;
		int required = unspecified ? 1 : need.quantity;
		// INGREDIENTS are consumed at the making spot — bank stock is
		// useless mid-Aggie, so their count is what's in your hands
		// (6,924 bank coins showed "enough" for a 65-coin dye run).
		boolean ingredient = Boolean.TRUE.equals(need.ingredient);
		// An id-keyed need counts that exact item; see StepAnnotation.ItemNeed.id.
		int owned = need.id != null
			? ctx.getItems().countOfId(need.id) : ctx.getItems().countOf(need.name);
		int carried = need.id != null
			? ctx.getItems().carriedCountOfId(need.id) : ctx.getItems().carriedCountOf(need.name);
		int have = ingredient ? carried : owned;

		// green: carrying enough | orange + 🏦: enough, but some is banked
		// | red: not enough anywhere | grey: the step is already done,
		// so the count is history, not a warning. UNSTACKABLE gathers
		// bigger than an inventory (130 planks) are green on TOTAL —
		// carrying them all unnoted is impossible. Stackables (1000 arrow
		// shafts) fit in one slot, so they count carried like anything else.
		String color;
		String flag = "";
		if (unspecified)
		{
			// Carry-list item: the question is "am I CARRYING what the guide
			// said to bring", so show the carried count, not the total owned.
			// Substitute families sum every tier (bare "pickaxe" counts all
			// eight metals), so an owned-total of 2 for one pickaxe in the
			// bag and one in the bank read as "I need 2 of something".
			// Only when you carry none does the bank total matter — that's
			// the 🏦 "go get it" case.
			String text = carried > 0
				? ItemTracker.formatCount(carried)
				: ItemTracker.formatCount(have) + (have > 0 ? "&nbsp;🏦" : "");
			// MUTED even when you are carrying it, because green here means
			// "requirement met" everywhere else on the card and there is no
			// requirement to meet — the guide never gave a number. Seven
			// green ticks next to seven carry-list items is what made a step
			// whose only real need was one pot look like seven obligations.
			// The one item the quest actually requires now carries a number
			// and keeps the colour, so it stands alone.
			return "<html><font color='" + (done ? "#808080"
				: carried > 0 ? "#877e6f"
				: have > 0 ? IN_BANK_HEX
				// Quest-granted and not carried yet is the NORMAL state
				// before the quest hands it over — muted, not red.
				: Boolean.TRUE.equals(need.granted) ? "#877e6f" : MISSING_HEX) + "'>"
				+ text + "</font></html>";
		}
		if (done)
		{
			color = "#808080";
		}
		else if (carried >= required
			|| (ctx.getItems().bankCountable(need.name, required) && have >= required))
		{
			color = SATISFIED_HEX;
		}
		else if (have >= required)
		{
			color = IN_BANK_HEX;
			flag = "&nbsp;🏦";
		}
		else
		{
			// An optional item you don't have is not a problem — muted
			// grey, never the alarm red of a real requirement. Nor is one
			// the quest is about to hand you: red there reads as "go and
			// fetch this", which is the misinformation the kit policy is
			// about.
			color = Boolean.TRUE.equals(need.optional) || Boolean.TRUE.equals(need.granted)
				? "#877e6f" : MISSING_HEX;
		}
		// CAPPED at what the step asks for. The raw total counts the bank
		// too, so six clay in the bag and four in the bank rendered as
		// "10/6" — true, and an answer to a question nobody asked (owner:
		// "I only have 6/6 in inventory"). A requirement badge is progress
		// towards a number, so it stops at that number; where the items
		// actually are is what the colour and the 🏦 flag are for.
		return "<html><font color='" + color + "'>"
			+ ItemTracker.formatCount(Math.min(have, required))
			+ "/" + ItemTracker.formatCount(required)
			+ flag + "</font></html>";
	}

	/**
	 * "warm clothing 3/4" — a live count of DISTINCT set items carried or
	 * worn (annotation gearCheck). Informational only: it colors green
	 * when met but never blocks the step's completion.
	 */
	private void addGearBadge()
	{
		StepAnnotation.GearCheck check = ctx.getAnnotations().getGearCheck(step.getId());
		if (check == null)
		{
			return;
		}
		JLabel badge = new JLabel();
		badge.setFont(new Font(Font.DIALOG, Font.PLAIN, 11));
		badge.setBorder(BorderFactory.createEmptyBorder(0, 22, 2, 0));
		badge.setAlignmentX(LEFT_ALIGNMENT);
		badge.setToolTipText("<html>Distinct \"" + RichText.escape(check.set)
			+ "\" items you carry or wear right now (bank doesn't count).<br>"
			+ "Informational only — it never blocks the step.</html>");
		Runnable refresh = () -> {
			int have = ctx.getItems().distinctCarried(check.set);
			String color = have >= check.need ? SATISFIED_HEX : MISSING_HEX;
			badge.setText("<html><b><font color='" + color + "'>"
				+ RichText.escape(ItemTracker.capitalize(check.set)) + " " + have + "/" + check.need
				+ "</font></b></html>");
		};
		refresh.run();
		badgeRefreshers.add(refresh);
		add(badge);
	}

	/**
	 * "Start from here": make this the step the guide is on, WITHOUT ticking
	 * anything off.
	 *
	 * The guide's own notes sometimes say to do things out of order — "Goblin
	 * Diplomacy is now required before starting The Lost Tribe… complete it
	 * first, then do The Lost Tribe afterward". Until now the only way to move
	 * on was to tick the steps you were stepping over, which records them as
	 * done when they are not, and pollutes the manual-tick record the plugin
	 * uses to spot detection that silently failed.
	 *
	 * Sets position to index - 1 because findWindow starts at position + 1, so
	 * this lands the frontier ON the step you clicked. Steps behind position
	 * are already treated as deliberately skipped and drop out of the window,
	 * which is what makes the jump reversible: right-click the earlier step
	 * when you come back and it becomes the frontier again.
	 */
	/**
	 * Make `index` the step the guide is on, and SHOW that it happened.
	 *
	 * Setting the position alone changes nothing on screen — no tick, no
	 * restyle — so the first version of the jump link read as a dead click
	 * even though it had already moved the position. Scrolling to the new
	 * current step is the feedback; it is also what the Resume button does,
	 * so the two cannot land in different places.
	 */
	private void goToStep(int index)
	{
		ctx.getProgress().setPosition(ctx.getVariant(), index - 1);
		if (ctx.getJumpToCurrentHandler() != null)
		{
			// Already re-points the route, so onProgressChanged would only
			// make the plugin react twice to one click.
			ctx.getJumpToCurrentHandler().run();
		}
		else
		{
			ctx.getOnProgressChanged().run();
		}
	}

	/**
	 * The green boxed jump control, used for both directions of a
	 * prerequisite: "GO TO STEP 281" on the step that is blocked, "BACK TO
	 * STEP 256" on the one blocking it. One builder so the pair cannot drift
	 * apart in look or behaviour.
	 *
	 * Reads as a BUTTON — bold, green, outlined. As a plain line it sat
	 * directly under the equally-green Quest Helper tip and looked like more
	 * prose, so nothing said it could be clicked.
	 *
	 * It WRAPS rather than truncates. The first cut rendered as "→ Go to step
	 * 28…" because it capped the label at TEXT_WIDTH, which is the narrow
	 * text COLUMN (112px, sized to sit beside the checkbox and the ⌖/Go
	 * buttons) while this line spans the whole card. Trimming to a measured
	 * width would only have moved the guess from characters to pixels; an
	 * html body width wraps on its own and loses nothing. That width is the
	 * row budget MINUS the box's own border and padding, or the outline runs
	 * past the card edge and takes ⌖/Go with it.
	 */
	private JPanel jumpButton(String lead, GuideStep target)
	{
		JPanel jump = boxedLine("<b>" + lead + " " + target.getGlobalIndex() + "</b> — "
			+ RichText.escape(target.getPlainText()), CAPTURED_COLOR, 11);
		String tip = "<html>Go to step " + target.getGlobalIndex() + ": "
			+ RichText.escape(target.getPlainText()) + "<br>"
			+ "Makes that the step the guide is on — nothing gets ticked off,"
			+ " and you can come back the same way.</html>";
		java.awt.event.MouseAdapter click = new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				goToStep(target.getGlobalIndex());
			}
		};
		// The pane fills the box, so it is what the pointer actually meets —
		// a listener on the panel alone would never fire.
		jump.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		jump.setToolTipText(tip);
		jump.addMouseListener(click);
		for (java.awt.Component child : jump.getComponents())
		{
			child.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			child.addMouseListener(click);
			if (child instanceof javax.swing.JComponent)
			{
				((javax.swing.JComponent) child).setToolTipText(tip);
			}
		}
		return jump;
	}

	private void attachStepMenu(java.awt.Component component)
	{
		component.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mousePressed(java.awt.event.MouseEvent e)
			{
				maybeShowMenu(e);
			}

			@Override
			public void mouseReleased(java.awt.event.MouseEvent e)
			{
				maybeShowMenu(e);
			}

			private void maybeShowMenu(java.awt.event.MouseEvent e)
			{
				// Platforms disagree about which of press/release is the
				// trigger, so both ask and only one answers.
				if (!e.isPopupTrigger())
				{
					return;
				}
				javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
				javax.swing.JMenuItem here =
					new javax.swing.JMenuItem("Start from here (step " + step.getGlobalIndex() + ")");
				here.setToolTipText("Make this the step the guide is on"
					+ " — nothing gets ticked off, and you can jump back the same way");
				here.addActionListener(a -> goToStep(step.getGlobalIndex()));
				menu.add(here);
				menu.show(e.getComponent(), e.getX(), e.getY());
			}
		});
	}

	/** Header for multi-action steps: master checkbox + label + step-level buttons. */
	private JPanel buildHeader()
	{
		masterBox = new JCheckBox("Step " + (step.getStepIndex() + 1));
		masterBox.setSelected(ctx.getProgress().isCompleted(ctx.getVariant(), step.getId()) || allSubsTicked());
		masterBox.setOpaque(false);
		masterBox.setForeground(ColorScheme.BRAND_ORANGE);
		masterBox.setFont(FontManager.getRunescapeSmallFont());
		masterBox.setToolTipText(metadataTooltip());
		masterBox.addActionListener(e -> {
			boolean completed = masterBox.isSelected();
			ctx.getProgress().setCompleted(ctx.getVariant(), step, completed);
			// Record that DETECTION did not do this. A silently-failing goal
			// is otherwise invisible — the player just ticks the box.
			ctx.getProgress().setManual(ctx.getVariant(), step.getId(), completed);
			for (SubStep s : step.getSubSteps())
			{
				ctx.getProgress().setManual(ctx.getVariant(), s.getId(), completed);
			}
			masterBox.setToolTipText(metadataTooltip());
			// A MANUAL tick is a deliberate "I'm here": move the player's
			// position; an untick means "redo this" — move it back.
			if (completed)
			{
				ctx.getProgress().advancePositionTo(ctx.getVariant(), step.getGlobalIndex());
			}
			else
			{
				ctx.getProgress().regressPositionTo(ctx.getVariant(), step.getGlobalIndex() - 1);
			}
			for (SubRowUi row : subRows)
			{
				row.setCompletedSilently(completed);
			}
			refreshItemBadges(); // done rows grey their badges
			ctx.getOnProgressChanged().run();
		});

		JPanel header = new JPanel(new BorderLayout(4, 0));
		header.setOpaque(false);
		header.setAlignmentX(LEFT_ALIGNMENT);
		header.add(masterBox, BorderLayout.CENTER);
		JPanel buttons = annotationButtons(step.getId());
		if (buttons != null)
		{
			header.add(buttons, BorderLayout.EAST);
		}
		return header;
	}

	/** True when every sub-step is individually ticked. */
	private boolean allSubsTicked()
	{
		for (SubStep sub : step.getSubSteps())
		{
			if (!ctx.getProgress().isSubCompleted(ctx.getVariant(), step, sub))
			{
				return false;
			}
		}
		return true;
	}

	/** The plugin auto-completed ONE sub-step (goal met): tick just that row, quietly. */
	void setSubCompletedSilently(String subId)
	{
		for (SubRowUi row : subRows)
		{
			if (row.sub.getId().equals(subId))
			{
				row.setCompletedSilently(true);
			}
		}
		// Completing the last open sub-step may have completed the step.
		if (masterBox != null)
		{
			masterBox.setSelected(ctx.getProgress().isCompleted(ctx.getVariant(), step.getId()));
		}
		refreshItemBadges();
	}

	/** The plugin auto-completed this step (requirement met): tick everything, quietly. */
	void setCompletedSilently(boolean completed)
	{
		if (masterBox != null)
		{
			masterBox.setSelected(completed);
		}
		for (SubRowUi row : subRows)
		{
			row.setCompletedSilently(completed);
		}
		refreshItemBadges();
	}

	/**
	 * The ⌖ / Go button pair for one annotation id (the step's or a
	 * sub-step's). Null when neither handler is wired.
	 */
	private JPanel annotationButtons(String annotationId)
	{
		if (ctx.getCaptureHandler() == null && ctx.getNavigateHandler() == null)
		{
			return null;
		}

		JPanel buttons = new JPanel();
		buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
		buttons.setOpaque(false);

		JButton navigate = null;
		if (ctx.getNavigateHandler() != null)
		{
			navigate = new JButton("Go");
			navigate.setMargin(new Insets(0, 2, 0, 2));
			navigate.setFocusable(false);
			navigate.setFont(FontManager.getRunescapeSmallFont());
			navigate.setToolTipText("Show the route to this target (needs the Shortest Path plugin)");
			navigate.addActionListener(e -> ctx.getNavigateHandler().accept(annotationId));
			buttons.add(navigate);
		}

		if (ctx.getCaptureHandler() != null)
		{
			JButton capture = new JButton("⌖");
			capture.setMargin(new Insets(0, 4, 0, 4));
			capture.setFocusable(false);
			JButton finalNavigate = navigate;
			capture.addActionListener(e -> ctx.getCaptureHandler().capture(annotationId, saved -> {
				if (saved)
				{
					styleAnnotationButtons(annotationId, capture, finalNavigate);
				}
				else
				{
					JOptionPane.showMessageDialog(this,
						"You need to be logged in to capture a location.",
						"IRONSCAPE Optimal", JOptionPane.INFORMATION_MESSAGE);
				}
			}));
			// Right-click menu: capture the tile AS A SAFESPOT (kill steps
			// whose safe tile the guide never names — owner ask), and undo
			// an accidental capture (bundled pins get tombstoned).
			if (ctx.getClearTargetHandler() != null || ctx.getSafespotCaptureHandler() != null)
			{
				capture.addMouseListener(new java.awt.event.MouseAdapter()
				{
					@Override
					public void mousePressed(java.awt.event.MouseEvent e)
					{
						maybeShowMenu(e);
					}

					@Override
					public void mouseReleased(java.awt.event.MouseEvent e)
					{
						maybeShowMenu(e);
					}

					private void maybeShowMenu(java.awt.event.MouseEvent e)
					{
						if (!e.isPopupTrigger())
						{
							return;
						}
						javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
						if (ctx.getSafespotCaptureHandler() != null)
						{
							javax.swing.JMenuItem safespot =
								new javax.swing.JMenuItem("Capture as safespot");
							safespot.setToolTipText("Save my current tile as this step's safespot"
								+ " — marked in the world with a \"Safespot\" label");
							safespot.addActionListener(a ->
								ctx.getSafespotCaptureHandler().capture(annotationId, saved -> {
									if (saved)
									{
										styleAnnotationButtons(annotationId, capture, finalNavigate);
									}
								}));
							menu.add(safespot);
						}
						if (ctx.getClearTargetHandler() != null
							&& ctx.getAnnotations().getTarget(annotationId) != null)
						{
							javax.swing.JMenuItem clear =
								new javax.swing.JMenuItem("Remove captured location");
							clear.addActionListener(a -> {
								ctx.getClearTargetHandler().accept(annotationId);
								styleAnnotationButtons(annotationId, capture, finalNavigate);
							});
							menu.add(clear);
						}
						if (menu.getComponentCount() > 0)
						{
							menu.show(e.getComponent(), e.getX(), e.getY());
						}
					}
				});
			}
			buttons.add(capture, 0);
			styleAnnotationButtons(annotationId, capture, navigate);
		}
		else
		{
			styleAnnotationButtons(annotationId, null, navigate);
		}

		return buttons;
	}

	/** Green ⌖ + visible Go when a target exists; plain ⌖ + hidden Go when not. */
	private void styleAnnotationButtons(String annotationId, JButton capture, JButton navigate)
	{
		StepAnnotation.Target target = ctx.getAnnotations().getTarget(annotationId);
		if (capture != null)
		{
			if (target == null)
			{
				capture.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				capture.setToolTipText("Save my current location as this target");
			}
			else
			{
				capture.setForeground(CAPTURED_COLOR);
				capture.setToolTipText("Target: " + target.x + ", " + target.y
					+ (target.plane != 0 ? " (plane " + target.plane + ")" : "")
					+ " — click to overwrite with my current location, right-click to remove");
			}
		}
		if (navigate != null)
		{
			navigate.setVisible(target != null || hasFallbackTarget(annotationId));
		}
	}

	/**
	 * Can Go do anything without a captured ⌖?
	 *
	 * Wave 3 gave the Go HANDLER a fallback chain (a place named in the
	 * step text, else the step's 📍 area tag) — but this button stayed
	 * hidden unless a ⌖ existed, so that fallback was unreachable through
	 * the UI for its whole life. Every un-pinned step simply had no Go
	 * button, which reads as "the plugin won't navigate me" even while
	 * auto-nav is routing perfectly well (owner, 2026-08-08, on "Start
	 * Fishing contest").
	 *
	 * Mirrors targetFor's chain using PlaceManager only, which is plain
	 * data and safe to read off the Swing thread. The quest-start pin in
	 * that chain needs live quest state and is deliberately NOT consulted
	 * here — missing it can only hide the button on a step that would
	 * have navigated, never show a dead one.
	 */
	private boolean hasFallbackTarget(String annotationId)
	{
		if (ctx.getPlaces() == null)
		{
			return false;
		}
		String text = annotationId.contains(":")
			? subTextFor(annotationId) : step.getPlainText();
		if (text != null
			&& (ctx.getPlaces().firstPlaceIn(text) != null
				|| ctx.getPlaces().lastPlaceIn(text) != null))
		{
			return true;
		}
		String location = step.getMetadata().get("location");
		return location != null && ctx.getPlaces().getLoose(location) != null;
	}

	/** The sub-step's own text, for the id the buttons belong to. */
	private String subTextFor(String annotationId)
	{
		for (SubStep candidate : step.getSubSteps())
		{
			if (candidate.getId().equals(annotationId))
			{
				return candidate.getPlainText();
			}
		}
		return step.getPlainText();
	}

	/**
	 * Annotated external reference ("Do museum..." -> the Natural History
	 * quiz wiki page): a 🔗 line whose real URL handleLink opens in the
	 * browser via LinkBrowser.
	 */
	private void addAnnotationLink()
	{
		StepAnnotation.Link link = ctx.getAnnotations().getLink(step.getId());
		if (link == null || link.url == null || link.label == null)
		{
			return;
		}
		add(htmlPane("<html><body><a style='color:#8ab4f8;text-decoration:none' href='"
				+ RichText.escape(link.url) + "'>" + RichText.escape("🔗 " + link.label)
				+ "</a></body></html>",
			22, new Font(Font.DIALOG, Font.PLAIN, 11), ColorScheme.LIGHT_GRAY_COLOR));
	}

	/**
	 * The Oziris guide tags each step with a location and sometimes a
	 * quest — render them as the same 📍/📜 chips the website shows.
	 * Clicking one routes there (locations via places.json, quest names
	 * via the quest-start/Quest Helper handoff). One width-locked HTML
	 * pane, so a long location + quest pair WRAPS instead of widening
	 * every row in the panel. Steps without these metadata keys simply
	 * show nothing.
	 */
	private void addMetadataChips()
	{
		String location = step.getMetadata().get("location");
		String quest = step.getMetadata().get("quest");
		// Structural annotations: a step the game has retired, and a
		// prerequisite the guide's step ORDER predates. Both are things the
		// prose note says and nothing else surfaces, and either can appear on
		// a step carrying no location or quest metadata at all — so they are
		// read before the early return, not after it.
		String obsolete = ctx.getAnnotations().getObsolete(step.getId());
		String prerequisite = ctx.getAnnotations().getPrerequisiteQuest(step.getId());
		// The step held up by THIS one, if any — the return trip. Read up here
		// with the others: a prerequisite step need not carry any metadata of
		// its own, and returning early would drop its button silently.
		GuideStep waiting = ctx.getDependentStep() == null
			? null : ctx.getDependentStep().apply(step.getId());
		if (waiting != null && ctx.getProgress().isCompleted(ctx.getVariant(), waiting.getId()))
		{
			waiting = null; // nothing to go back FOR once it is done
		}
		// An earlier leg of this step's own quest is still unfinished — you
		// are ahead of yourself. Suppressed when it would just repeat the
		// prerequisite button above (same destination, two buttons).
		GuideStep earlierLeg = ctx.getEarlierQuestLeg() == null
			? null : ctx.getEarlierQuestLeg().apply(step.getId());
		if (earlierLeg != null && waiting != null
			&& earlierLeg.getId().equals(waiting.getId()))
		{
			earlierLeg = null;
		}
		if (location == null && quest == null && obsolete == null
			&& prerequisite == null && waiting == null && earlierLeg == null)
		{
			return;
		}
		// JComponent, not JLabel: a place chip is a label, but a warning is a
		// boxed wrapped pane (see noticeChip).
		List<javax.swing.JComponent> chips = new ArrayList<>();
		if (obsolete != null)
		{
			chips.add(noticeChip("⚠ No longer possible", obsolete, WARN_FG));
		}
		if (earlierLeg != null)
		{
			chips.add(noticeChip(
				"⚠ Start this quest first — step " + earlierLeg.getGlobalIndex()
					+ " is unfinished",
				"This step continues a quest whose earlier steps are not done yet.",
				WARN_FG));
		}
		GuideStep prerequisiteStep = prerequisite == null || ctx.getQuestStep() == null
			? null : ctx.getQuestStep().apply(prerequisite);
		if (prerequisite != null)
		{
			chips.add(noticeChip("⚠ Needs " + prerequisite + " first",
				prerequisiteStep == null
					? "The guide does this quest later — see the step's note"
					: "The guide does it at step " + prerequisiteStep.getGlobalIndex(), WARN_FG));
		}
		if (location != null)
		{
			chips.add(chip("📍 " + location, location, CHIP_FG));
		}
		if (quest != null)
		{
			boolean completes = "complete".equalsIgnoreCase(step.getMetadata().get("questStatus"));
			chips.add(chip("📜 " + quest + (completes ? " ✓" : ""), quest, CHIP_QUEST_FG));
		}

		// One row when the pair fits the card, otherwise stacked — a
		// FlowLayout would report single-row height and clip the wrap
		// ("📍 Falador 📜 The Knight's Sword" is wider than the panel).
		int combined = 22;
		for (javax.swing.JComponent c : chips)
		{
			combined += c.getPreferredSize().width + 4;
		}
		boolean stack = combined > 184;
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, stack ? BoxLayout.Y_AXIS : BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(BorderFactory.createEmptyBorder(4, 22, 1, 0));
		for (int i = 0; i < chips.size(); i++)
		{
			javax.swing.JComponent c = chips.get(i);
			c.setAlignmentX(LEFT_ALIGNMENT);
			if (i > 0)
			{
				row.add(javax.swing.Box.createRigidArea(new Dimension(4, 3)));
			}
			row.add(c);
		}
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		add(row);

		// The Plugin Hub forbids starting Quest Helper FOR the player (the
		// old reflection handoff), so quest steps point at it — ONE compact
		// line now; the full how-to sentence on every quest step was noise.
		if (quest != null)
		{
			// Reads as an INSTRUCTION now. The old "➜ Quest Helper: "X""
			// was too terse to act on, and it matters more since our own
			// route stands down entirely on these steps — if the panel does
			// not send you to QH, nothing does.
			// Wraps: as a plain label this ran off the card for any longish
			// quest name, which is what the self-check meant by "card wider
			// than viewport … on step 4043e0d8dc" ("Do Elemental workshop 1")
			// long before today's buttons existed.
			JEditorPane tip = wrappedLine("Use Quest Helper → " + RichText.escape(quest),
				22, 11, CHIP_QUEST_FG);
			tip.setToolTipText("<html>Select \"" + RichText.escape(quest)
				+ "\" in the Quest Helper plugin for click-by-click quest guidance.<br>"
				+ "(The Plugin Hub forbids plugins starting it for you.)</html>");
			add(tip);
		}

		// The jump the note asks for, with OUR step number on it.
		//
		// The note's own number is wrong for us and cannot be fixed where it
		// is written: it is Oziris's prose, scraped into the guide payload, so
		// a hand-edit there dies at the next re-scrape — the same reason quest
		// tags live in annotations. Resolving the quest by NAME sidesteps the
		// numbering question altogether.
		if (prerequisiteStep != null)
		{
			add(jumpButton("GO TO STEP", prerequisiteStep));
		}

		// The return trip, on the prerequisite step itself (resolved above).
		if (waiting != null)
		{
			add(jumpButton("BACK TO STEP", waiting));
		}
		// ... and from a later leg back to where the quest actually starts.
		if (earlierLeg != null)
		{
			add(jumpButton("BACK TO STEP", earlierLeg));
		}

		// Nothing can tick this one for you. 139 steps guide-wide are in that
		// state — most are genuinely advice ("bank everything", "use
		// Authenticator") — and meeting one unwarned reads as a broken plugin
		// every time. Muted, one line, no icon: it is a fact about the step,
		// not a warning about it.
		if (ctx.getManualOnly() != null && !step.getSubSteps().isEmpty()
			&& ctx.getManualOnly().test(step.getSubSteps().get(0).getId()))
		{
			JEditorPane manual = wrappedLine("<i>tick by hand — nothing here to detect</i>",
				22, 11, new Color(0x87, 0x7e, 0x6f));
			manual.setToolTipText("<html>This step has no item, quest, level or travel goal,"
				+ " no varbit checkpoint and no errand chain,<br>"
				+ "so it cannot complete on its own. Tick it when you have done it.</html>");
			add(manual);
		}
	}

	/** One bordered chip; clicking routes to its place via Shortest Path. */
	/** The chip look, shared by the ones you can click and the ones you can't. */
	private void styleChip(JLabel chip, Color fg)
	{
		chip.setFont(new Font(Font.DIALOG, Font.PLAIN, 10));
		chip.setForeground(fg);
		chip.setOpaque(true);
		chip.setBackground(BOX_BG);
		chip.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(BOX_EDGE, 1),
			BorderFactory.createEmptyBorder(1, 5, 1, 5)));
	}

	/**
	 * A chip that SAYS something rather than going somewhere.
	 *
	 * The clickable chip below routes to a place and advertises that in its
	 * tooltip and cursor. A warning has no destination, so it gets neither —
	 * a hand cursor over "No longer possible" promises a route that does not
	 * exist and would navigate to the reason text.
	 */
	private JPanel noticeChip(String label, String tooltip, Color fg)
	{
		// A boxed WRAPPED line rather than a chip: a place chip is short by
		// nature ("📍 Lumbridge") and fits, while a warning names a quest and
		// ran the card 213px wide with nowhere to wrap to.
		JPanel chip = boxedLine(label, fg, 10);
		chip.setToolTipText(tooltip);
		for (java.awt.Component child : chip.getComponents())
		{
			if (child instanceof javax.swing.JComponent)
			{
				((javax.swing.JComponent) child).setToolTipText(tooltip);
			}
		}
		return chip;
	}

	private JLabel chip(String label, String target, Color fg)
	{
		JLabel chip = new JLabel(label);
		styleChip(chip, fg);
		chip.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		chip.setToolTipText("Show the route (needs the Shortest Path plugin)");
		chip.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				if (ctx.getPlaceNavigateHandler() != null)
				{
					ctx.getPlaceNavigateHandler().accept(target, step);
				}
			}
		});
		return chip;
	}

	/**
	 * A non-editable HTML pane, width-locked the same way sub-step text
	 * is (see SubRowUi#setHtml) and wired to the shared link handler.
	 *
	 * Font and color are parameters because with HONOR_DISPLAY_PROPERTIES
	 * they must be set BEFORE setText: changing them afterwards rebuilds
	 * the HTML views, which come back laid out at the text's unwrapped
	 * width — the "notes clip instead of wrapping" bug.
	 */
	/**
	 * Stop a display-only text pane from scrolling the whole panel to itself.
	 *
	 * <p>THIS IS THE PANEL-SCROLL BUG, and it took six rounds to find because
	 * nothing in the panel's own scrolling code was involved. Every JEditorPane
	 * carries a caret. When its document changes — which happens to every row
	 * whose item counts are rewritten — the caret moves, and a moving caret
	 * calls {@code scrollRectToVisible} on itself. That walks up to the
	 * JScrollPane and drags the view to that row.
	 *
	 * <p>So a row far down the list could haul the view thousands of pixels
	 * with the panel having scrolled nothing at all, which is why the log was
	 * empty and why every fix aimed at anchors and timing missed. Proven by a
	 * change listener on the viewport that printed the caller:
	 * {@code DefaultCaret.adjustVisibility}, moving the view 366 -> 6855 the
	 * instant the owner opened a shop (2026-08-14).
	 *
	 * <p>NEVER_UPDATE is the cure: the caret stops following document edits, so
	 * it never asks to be made visible. These panes are read-only prose with no
	 * cursor to keep on screen, so nothing is lost. They are also taken out of
	 * the focus order, which closes the other route to the same call.
	 */
	private static void muteCaret(JEditorPane pane)
	{
		javax.swing.text.Caret caret = pane.getCaret();
		if (caret instanceof javax.swing.text.DefaultCaret)
		{
			((javax.swing.text.DefaultCaret) caret)
				.setUpdatePolicy(javax.swing.text.DefaultCaret.NEVER_UPDATE);
		}
		pane.setFocusable(false);
	}

	private JEditorPane htmlPane(String html, int leftIndent, Font font, java.awt.Color foreground)
	{
		JEditorPane pane = new JEditorPane();
		pane.setContentType("text/html");
		pane.setEditable(false);
		pane.setOpaque(false);
		muteCaret(pane);
		pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
		pane.setFont(font);
		pane.setForeground(foreground);
		pane.setBorder(BorderFactory.createEmptyBorder(2, leftIndent, 0, 0));
		pane.setAlignmentX(LEFT_ALIGNMENT);
		pane.addHyperlinkListener(this::handleLink);
		pane.setText(html);
		// The border is INSIDE the component width, so the pane must be
		// content width + indent — sizing it at just the content width
		// clipped ~20px of every note and chip on the right.
		int width = TEXT_WIDTH + 40 + leftIndent;
		pane.setSize(width, Short.MAX_VALUE);
		pane.setPreferredSize(new Dimension(width, pane.getPreferredSize().height));
		// BoxLayout stretches children to the widest row; capping the max
		// size keeps THIS pane from being the widest row.
		pane.setMaximumSize(new Dimension(width, pane.getPreferredSize().height));
		return pane;
	}

	/** Shared link handling: place/quest routing, world hops, real URLs. */
	private void handleLink(HyperlinkEvent e)
	{
		if (e.getEventType() != HyperlinkEvent.EventType.ACTIVATED)
		{
			return;
		}
		String description = e.getDescription();
		if (description != null && description.startsWith(PlaceManager.LINK_PREFIX))
		{
			if (ctx.getPlaceNavigateHandler() != null)
			{
				ctx.getPlaceNavigateHandler().accept(
					PlaceManager.decode(
						description.substring(PlaceManager.LINK_PREFIX.length())), step);
			}
		}
		else if (description != null && description.startsWith(RichText.WORLD_LINK_PREFIX))
		{
			if (ctx.getWorldHopHandler() != null)
			{
				// the regex only puts digits after the prefix
				ctx.getWorldHopHandler().accept(Integer.parseInt(
					description.substring(RichText.WORLD_LINK_PREFIX.length())));
			}
		}
		else if (e.getURL() != null)
		{
			LinkBrowser.browse(e.getURL().toString());
		}
	}

	/** Metadata block as a tooltip, so rows stay compact. */
	private String metadataTooltip()
	{
		Map<String, String> meta = step.getMetadata();
		boolean manual = ctx.getProgress().isManual(ctx.getVariant(), step.getId());
		if (meta.isEmpty() && !manual)
		{
			return null;
		}
		StringBuilder sb = new StringBuilder("<html>");
		if (manual)
		{
			// The one line worth reading here: detection did NOT complete
			// this step, a person did.
			sb.append("<b>Ticked by hand</b> — no goal detected this<br>");
		}
		appendMetaLine(sb, meta, "total_time", "Time");
		appendMetaLine(sb, meta, "gp_stack", "GP stack");
		appendMetaLine(sb, meta, "items_needed", "Items");
		appendMetaLine(sb, meta, "skills_quests_met", "Skills/quests met");
		sb.append("</html>");
		return sb.toString();
	}

	private static void appendMetaLine(StringBuilder sb, Map<String, String> meta, String key, String label)
	{
		String value = meta.get(key);
		if (value != null && !value.isEmpty())
		{
			sb.append("<b>").append(label).append(":</b> ")
				.append(RichText.escape(value)).append("<br>");
		}
	}

	/** One tickable line: checkbox + styled text + its own ⌖/Go buttons. */
	private class SubRowUi
	{
		final JPanel panel;
		final JCheckBox checkBox;
		final JEditorPane text;
		final SubStep sub;
		/**
		 * Whether this row belongs to a step that also draws a header. Only
		 * a headerless (single-action) row numbers itself — on a multi-action
		 * step the header carries the number and repeating it on every clause
		 * would be noise.
		 */
		final boolean multi;

		SubRowUi(SubStep sub, boolean multi)
		{
			this.sub = sub;
			this.multi = multi;
			boolean completed = ctx.getProgress().isSubCompleted(ctx.getVariant(), step, sub);

			checkBox = new JCheckBox();
			checkBox.setSelected(completed);
			checkBox.setOpaque(false);
			checkBox.addActionListener(e -> {
				boolean nowCompleted = checkBox.isSelected();
				ctx.getProgress().setSubCompleted(ctx.getVariant(), step, sub, nowCompleted);
				// See masterBox: a hand tick means no goal fired for this sub.
				ctx.getProgress().setManual(ctx.getVariant(), sub.getId(), nowCompleted);
				checkBox.setToolTipText(nowCompleted
					? "Ticked by hand — no goal detected this" : null);
				// Manual ticks steer the player's position (see masterBox).
				if (nowCompleted && ctx.getProgress().isCompleted(ctx.getVariant(), step.getId()))
				{
					ctx.getProgress().advancePositionTo(ctx.getVariant(), step.getGlobalIndex());
				}
				else if (!nowCompleted)
				{
					ctx.getProgress().regressPositionTo(ctx.getVariant(), step.getGlobalIndex() - 1);
				}
				setHtml(nowCompleted);
				// Ticking the last open sub-step completes the step.
				if (masterBox != null)
				{
					masterBox.setSelected(ctx.getProgress().isCompleted(ctx.getVariant(), step.getId()));
				}
				refreshItemBadges(); // done rows grey their badges
				ctx.getOnProgressChanged().run();
			});

			text = new JEditorPane();
			text.setContentType("text/html");
			text.setEditable(false);
			text.setOpaque(false);
			muteCaret(text);
			// Normal system font, not the RuneScape pixel font — pixel fonts
			// are fine for short labels but painful for paragraphs of prose.
			text.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
			text.setFont(new Font(Font.DIALOG, Font.PLAIN, 12));
			text.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			if (!multi)
			{
				// Single-action steps have no header, so the metadata
				// tooltip lives on the row itself.
				text.setToolTipText(metadataTooltip());
				checkBox.setToolTipText(metadataTooltip());
			}
			text.addHyperlinkListener(e -> handleLink(e));
			setHtml(completed);

			JPanel checkBoxWrapper = new JPanel(new BorderLayout());
			checkBoxWrapper.setOpaque(false);
			checkBoxWrapper.add(checkBox, BorderLayout.NORTH);

			panel = new JPanel(new BorderLayout(2, 0));
			panel.setOpaque(false);
			panel.setAlignmentX(LEFT_ALIGNMENT);
			panel.setBorder(BorderFactory.createEmptyBorder(
				1, sub.getIndentLevel() * INDENT_PER_LEVEL, 1, 0));
			panel.add(checkBoxWrapper, BorderLayout.WEST);
			panel.add(text, BorderLayout.CENTER);

			// Single-action steps annotate under the STEP id, so targets
			// captured before the sub-step rework keep working.
			String annotationId = multi ? sub.getId() : step.getId();
			JPanel buttons = annotationButtons(annotationId);
			if (buttons != null)
			{
				JPanel buttonsWrapper = new JPanel(new BorderLayout());
				buttonsWrapper.setOpaque(false);
				buttonsWrapper.add(buttons, BorderLayout.NORTH);
				panel.add(buttonsWrapper, BorderLayout.EAST);
			}
		}

		void setCompletedSilently(boolean completed)
		{
			checkBox.setSelected(completed);
			setHtml(completed);
		}

		private void setHtml(boolean completed)
		{
			String html = RichText.runsHtml(sub.getContent(), completed,
				ctx.getPlaces() == null ? null : ctx.getPlaces()::linkify);
			// Item goals link to their wiki page — "hangover cure" opens
			// the page that explains how to make one.
			List<GoalDetector.ItemGoal> goals = ctx.getItemGoals()
				.getOrDefault(sub.getId(), Collections.emptyList());
			if (!goals.isEmpty() && !completed)
			{
				List<String> names = new ArrayList<>(goals.size());
				for (GoalDetector.ItemGoal goal : goals)
				{
					names.add(goal.getItemName());
				}
				html = RichText.linkifyWikiItems(html, names);
			}
			// The step's position in the guide, so "where am I?" is answerable
			// from the card instead of by counting. Only headerless steps need
			// it (see the `multi` field), which on the atomic Oziris guide is
			// every step — the "Step N" header this mirrors never renders.
			//
			// It is the GLOBAL index, deliberately: the same number the saved
			// position holds and the same one tools/preflight.mjs prints, so a
			// step named in conversation is the step you are looking at. The
			// header's own label counts within a SECTION, which would have
			// been a second, disagreeing numbering.
			//
			// Prefixed INTO the html rather than added as a label: the row is
			// checkbox | text | buttons, and a fourth column takes width from
			// the text, which is what pushes ⌖/Go off the panel edge.
			// runsHtml returns a whole <html><body> document, so the prefix has
			// to go INSIDE the body — Swing's parser drops anything before it.
			//
			// Green (the panel's existing SATISFIED_HEX, not a new colour) with
			// a colon, so it reads as a label rather than blending into the
			// prose; it greys with the rest of the card once the step is done.
			int bodyAt = html.indexOf("<body>");
			if (!multi && bodyAt >= 0)
			{
				int at = bodyAt + "<body>".length();
				html = html.substring(0, at)
					+ "<span style='color:" + (completed ? "#808080" : SATISFIED_HEX) + "'>"
					+ step.getGlobalIndex() + ":</span>&nbsp;&nbsp;"
					+ html.substring(at);
			}
			text.setText(html);
			// JEditorPane's preferred width is the longest UNWRAPPED line,
			// which would push the row off the panel's right edge. Force
			// the pane to our width first, then ask how tall the wrapped
			// text is, and lock that in as the preferred size.
			int width = TEXT_WIDTH - sub.getIndentLevel() * INDENT_PER_LEVEL;
			text.setSize(width, Short.MAX_VALUE);
			text.setPreferredSize(new Dimension(width, text.getPreferredSize().height));
		}
	}

}
