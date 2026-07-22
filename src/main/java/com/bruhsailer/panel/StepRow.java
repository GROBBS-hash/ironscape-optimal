package com.bruhsailer.panel;

import com.bruhsailer.annotations.StepAnnotation;
import com.bruhsailer.goals.GoalDetector;
import com.bruhsailer.guide.GuideStep;
import com.bruhsailer.guide.SubStep;
import com.bruhsailer.guide.TextRun;
import com.bruhsailer.places.PlaceManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
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
	 * and scrollbar — being too generous here makes the widest row widen
	 * the whole column and push buttons off-screen.
	 * JEditorPane does NOT wrap to its container on its own — see setHtml().
	 */
	private static final int TEXT_WIDTH = 128;
	private static final int INDENT_PER_LEVEL = 10;

	private static final Color CAPTURED_COLOR = new Color(0x4c, 0xaf, 0x50);
	private static final String SATISFIED_HEX = "#4caf50";
	private static final String IN_BANK_HEX = "#ffa000";
	private static final String MISSING_HEX = "#e57373";

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
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2));

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
		}

		addMetadataChips();

		// Trailing commentary paragraphs — informational, not tickable.
		// Rendered as width-locked panes, NOT labels: a pane wraps at our
		// width (an over-wide child stretches every row and clips the whole
		// panel) and its links ("Safespot location") actually click.
		for (List<TextRun> paragraph : step.getAdditionalContent())
		{
			JEditorPane note = htmlPane(RichText.paragraphHtml(paragraph), 22);
			note.setFont(new Font(Font.DIALOG, Font.ITALIC, 11));
			note.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			add(note);
		}
	}

	GuideStep getStep()
	{
		return step;
	}

	/** Re-read live item counts into every badge (called after inventory/bank changes). */
	void refreshItemBadges()
	{
		badgeRefreshers.forEach(Runnable::run);
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
	 * Adds a live "have/need" line. Sources, in priority order: reviewed
	 * item annotations for the annotation id, else item goals detected in
	 * the sub-step's own text ("buy 1250 nature runes"). Once the owning
	 * sub/step is ticked the badge greys out — a red "0/1" under a done
	 * step reads as a problem when it's just a consumed item.
	 *
	 * @param sub the sub-step this badge belongs to; null = the step
	 *            header badge of a multi-action step
	 */
	private void addItemBadge(String annotationId, SubStep sub, int indentPx)
	{
		String goalSubId = sub == null ? null : sub.getId();
		List<StepAnnotation.ItemNeed> needs = ctx.getAnnotations().getItems(annotationId);
		if (needs.isEmpty() && goalSubId != null)
		{
			needs = new ArrayList<>();
			for (GoalDetector.ItemGoal goal : ctx.getItemGoals()
				.getOrDefault(goalSubId, Collections.emptyList()))
			{
				StepAnnotation.ItemNeed need = new StepAnnotation.ItemNeed();
				need.name = goal.getItemName();
				need.quantity = goal.getQuantity();
				needs.add(need);
			}
		}
		boolean hasActionBadge = goalSubId != null && ctx.getActionBadge() != null
			&& ctx.getActionBadge().apply(goalSubId) != null;
		if (needs.isEmpty() && !hasActionBadge)
		{
			return;
		}
		JLabel badge = new JLabel();
		badge.setFont(new Font(Font.DIALOG, Font.PLAIN, 11));
		badge.setBorder(BorderFactory.createEmptyBorder(0, indentPx, 2, 0));
		badge.setAlignmentX(LEFT_ALIGNMENT);
		// indent + wrap width must stay inside the panel column, or this
		// badge widens EVERY row and pushes the buttons off-screen
		int wrapWidth = Math.max(80, 170 - indentPx);
		if (!needs.isEmpty())
		{
			StringBuilder tip = new StringBuilder("<html>Counts inventory + worn + bank "
				+ "(bank as of your last visit).<br>Matching item names:");
			for (StepAnnotation.ItemNeed need : needs)
			{
				tip.append(" \"").append(RichText.escape(need.name)).append('"');
			}
			badge.setToolTipText(tip.append("</html>").toString());
		}
		else
		{
			badge.setToolTipText("Live progress toward this goal (your skill level, or xp drops counted so far)");
		}
		List<StepAnnotation.ItemNeed> badgeNeeds = needs;
		String actionSubId = goalSubId;
		SubStep badgeSub = sub;
		Runnable refresh = () -> {
			boolean done = badgeSub == null
				? ctx.getProgress().isCompleted(ctx.getVariant(), step.getId())
				: ctx.getProgress().isSubCompleted(ctx.getVariant(), step, badgeSub);
			if (!badgeNeeds.isEmpty())
			{
				badge.setText(badgeHtml(badgeNeeds, wrapWidth, done));
				return;
			}
			String action = ctx.getActionBadge().apply(actionSubId);
			if (done && action != null)
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

	private String badgeHtml(List<StepAnnotation.ItemNeed> needs, int wrapWidth, boolean done)
	{
		// The width style makes long badges WRAP instead of widening the
		// whole panel column (which would break text wrapping everywhere).
		StringBuilder sb = new StringBuilder("<html><body style='width:" + wrapWidth + "px'>");
		for (int i = 0; i < needs.size(); i++)
		{
			StepAnnotation.ItemNeed need = needs.get(i);
			int required = need.quantity == null ? 1 : need.quantity;
			int have = ctx.getItems().countOf(need.name);
			int carried = ctx.getItems().carriedCountOf(need.name);

			// green: carrying enough | orange: enough, but some is banked
			// | red: not enough anywhere | grey: the step is already done,
			// so the count is history, not a warning
			String color;
			String note = "";
			if (done)
			{
				color = "#808080";
			}
			else if (carried >= required)
			{
				color = SATISFIED_HEX;
			}
			else if (have >= required)
			{
				color = IN_BANK_HEX;
				note = " (in bank)";
			}
			else
			{
				color = MISSING_HEX;
			}

			if (i > 0)
			{
				sb.append(" <font color='#606060'>·</font> ");
			}
			// The count and "(in bank)" stay glued to the item name
			// (non-breaking spaces), so entries wrap as whole units
			// instead of stranding "bank)" on its own line.
			sb.append("<b><font color='").append(color).append("'>")
				.append(RichText.escape(need.name)).append("&nbsp;")
				.append(have).append('/').append(required)
				.append(note.isEmpty() ? "" : "&nbsp;(in&nbsp;bank)")
				.append("</font></b>");
		}
		return sb.append("</body></html>").toString();
	}

	/** Header for multi-action steps: master checkbox + label + step-level buttons. */
	private JPanel buildHeader()
	{
		masterBox = new JCheckBox("Step " + (step.getStepIndex() + 1));
		masterBox.setSelected(ctx.getProgress().isCompleted(ctx.getVariant(), step.getId()) || allSubsTicked());
		masterBox.setBackground(ColorScheme.DARK_GRAY_COLOR);
		masterBox.setForeground(ColorScheme.BRAND_ORANGE);
		masterBox.setFont(FontManager.getRunescapeSmallFont());
		masterBox.setToolTipText(metadataTooltip());
		masterBox.addActionListener(e -> {
			boolean completed = masterBox.isSelected();
			ctx.getProgress().setCompleted(ctx.getVariant(), step, completed);
			for (SubRowUi row : subRows)
			{
				row.setCompletedSilently(completed);
			}
			refreshItemBadges(); // done rows grey their badges
			ctx.getOnProgressChanged().run();
		});

		JPanel header = new JPanel(new BorderLayout(4, 0));
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
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
		buttons.setBackground(ColorScheme.DARK_GRAY_COLOR);

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
					+ " — click to overwrite with my current location");
			}
		}
		if (navigate != null)
		{
			navigate.setVisible(target != null);
		}
	}

	/**
	 * The Oziris guide tags each step with a location and sometimes a
	 * quest — render them as the same 📍/📜 chips the website shows.
	 * Clicking one routes there (locations via places.json, quest names
	 * via the quest-start/Quest Helper handoff). One width-locked HTML
	 * pane, so a long location + quest pair WRAPS instead of widening
	 * every row in the panel. Steps without these metadata keys (all of
	 * BRUHsailer's) simply show nothing.
	 */
	private void addMetadataChips()
	{
		String location = step.getMetadata().get("location");
		String quest = step.getMetadata().get("quest");
		if (location == null && quest == null)
		{
			return;
		}
		StringBuilder html = new StringBuilder();
		if (location != null)
		{
			html.append(chipHtml("📍 " + location, location));
		}
		if (quest != null)
		{
			boolean completes = "complete".equalsIgnoreCase(step.getMetadata().get("questStatus"));
			if (location != null)
			{
				// A NORMAL space between chips: the pair must be able to
				// wrap onto two lines ("📍 Falador 📜 The Knight's Sword"
				// is wider than the panel).
				html.append("&nbsp; ");
			}
			html.append(chipHtml("📜 " + quest + (completes ? " ✓" : ""), quest));
		}
		JEditorPane chips = htmlPane("<html><body style='width:" + (TEXT_WIDTH + 30) + "px'>"
			+ html + "</body></html>", 22);
		chips.setFont(new Font(Font.DIALOG, Font.PLAIN, 11));
		chips.setToolTipText("Show the route (needs the Shortest Path plugin)");
		add(chips);
	}

	/** One chip as a place link — clicks land in the shared hyperlink handler. */
	private static String chipHtml(String label, String target)
	{
		try
		{
			return "<a style='color:#e8a838;text-decoration:none' href='"
				+ PlaceManager.LINK_PREFIX + java.net.URLEncoder.encode(target, "UTF-8") + "'>"
				+ RichText.escape(label).replace(" ", "&nbsp;") + "</a>";
		}
		catch (UnsupportedEncodingException e)
		{
			throw new IllegalStateException(e); // UTF-8 always exists
		}
	}

	/**
	 * A non-editable HTML pane, width-locked the same way sub-step text
	 * is (see SubRowUi#setHtml) and wired to the shared link handler.
	 */
	private JEditorPane htmlPane(String html, int leftIndent)
	{
		JEditorPane pane = new JEditorPane();
		pane.setContentType("text/html");
		pane.setEditable(false);
		pane.setOpaque(false);
		pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
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
					decode(description.substring(PlaceManager.LINK_PREFIX.length())));
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
		if (meta.isEmpty())
		{
			return null;
		}
		StringBuilder sb = new StringBuilder("<html>");
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

		SubRowUi(SubStep sub, boolean multi)
		{
			this.sub = sub;
			boolean completed = ctx.getProgress().isSubCompleted(ctx.getVariant(), step, sub);

			checkBox = new JCheckBox();
			checkBox.setSelected(completed);
			checkBox.setBackground(ColorScheme.DARK_GRAY_COLOR);
			checkBox.addActionListener(e -> {
				boolean nowCompleted = checkBox.isSelected();
				ctx.getProgress().setSubCompleted(ctx.getVariant(), step, sub, nowCompleted);
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
			checkBoxWrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
			checkBoxWrapper.add(checkBox, BorderLayout.NORTH);

			panel = new JPanel(new BorderLayout(2, 0));
			panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
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
				buttonsWrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
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
			text.setText(RichText.runsHtml(sub.getContent(), completed,
				ctx.getPlaces() == null ? null : ctx.getPlaces()::linkify));
			// JEditorPane's preferred width is the longest UNWRAPPED line,
			// which would push the row off the panel's right edge. Force
			// the pane to our width first, then ask how tall the wrapped
			// text is, and lock that in as the preferred size.
			int width = TEXT_WIDTH - sub.getIndentLevel() * INDENT_PER_LEVEL;
			text.setSize(width, Short.MAX_VALUE);
			text.setPreferredSize(new Dimension(width, text.getPreferredSize().height));
		}
	}

	private static String decode(String encoded)
	{
		try
		{
			return URLDecoder.decode(encoded, "UTF-8");
		}
		catch (UnsupportedEncodingException e)
		{
			throw new IllegalStateException(e); // UTF-8 always exists
		}
	}
}
