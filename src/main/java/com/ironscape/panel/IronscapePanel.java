package com.ironscape.panel;

import com.ironscape.IronscapeConfig;
import com.ironscape.annotations.AnnotationManager;
import com.ironscape.goals.GoalDetector;
import com.ironscape.guide.Guide;
import com.ironscape.items.ItemTracker;
import com.ironscape.places.PlaceManager;
import com.ironscape.guide.GuideChapter;
import com.ironscape.guide.GuideSection;
import com.ironscape.guide.GuideStep;
import com.ironscape.guide.TextRun;
import com.ironscape.progress.ProgressManager;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.IconTextField;

/**
 * The guide side panel. Three views share one scroll area:
 *
 *  - OVERVIEW: chapters and sections with per-section progress. Start here.
 *  - SECTION:  the steps of one section, with checkboxes. Only one
 *    section's steps are live Swing components at a time — that's the
 *    panel-performance strategy.
 *  - SEARCH:   matching steps across the whole guide (as soon as the
 *    search box is non-empty).
 */
public class IronscapePanel extends PluginPanel
{
	/**
	 * Scroll content that always matches the viewport's width (vertical
	 * scrolling only). Without Scrollable, a JViewport sizes a plain
	 * JPanel to its PREFERRED width — one over-wide row and everything
	 * right of the viewport edge is simply cut off.
	 */
	private static class ViewportWidthPanel extends JPanel implements javax.swing.Scrollable
	{
		ViewportWidthPanel(java.awt.LayoutManager layout)
		{
			super(layout);
		}

		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(java.awt.Rectangle visible, int orientation, int direction)
		{
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(java.awt.Rectangle visible, int orientation, int direction)
		{
			return 96;
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return false;
		}
	}

	private static final String CONFIG_GROUP = "ironscape";
	private static final int MAX_SEARCH_RESULTS = 50;

	private final ProgressManager progressManager;
	private final ConfigManager configManager;
	private final IronscapeConfig config;
	private final AnnotationManager annotationManager;
	private final ItemTracker itemTracker;
	private final PlaceManager placeManager;

	/** Set by the plugin; null until then (capture buttons stay hidden). */
	private CaptureHandler captureHandler;
	private CaptureHandler safespotCaptureHandler;

	/** Turn the nearest-person outline on or off for a captured ⌖. */
	private java.util.function.BiConsumer<String, Boolean> outlineNpcHandler;

	/** Set by the plugin; routes to a target (by annotation id) via Shortest Path. */
	private Consumer<String> navigateHandler;

	/** Set by the plugin; routes to a named place via Shortest Path. The step
	 * is passed along so quest-name links know whether the step is ABOUT that
	 * quest or just uses its name as a landmark. */
	private java.util.function.BiConsumer<String, com.ironscape.guide.GuideStep> placeNavigateHandler;

	/** Set by the plugin; hops to a world number ("world 444" links). */
	private Consumer<Integer> worldHopHandler;

	/** Set by the plugin; captures the current location under a place name. */
	private CaptureHandler addPlaceHandler;

	/** Set by the plugin; clears the Shortest Path route. */
	private Runnable clearPathHandler;

	/** Text-detected item goals by sub-step id, for the have/need badges. */
	private Map<String, List<GoalDetector.ItemGoal>> itemGoals = Collections.emptyMap();

	/** Set by the plugin; notified after any manual tick (drives auto-navigation). */
	private Runnable progressChangedListener;

	/** Set by the plugin; sub-id -> html for counted-action progress badges. */
	private java.util.function.Function<String, String> actionBadgeSupplier;
	private java.util.function.Function<String, String> badgeIconSupplier;
	private java.util.function.Function<String, java.awt.image.BufferedImage> skillIconSupplier;

	/** sub-id -> errand stage items and their NEEDED/HELD/SPENT state. */
	private java.util.function.Function<String, java.util.LinkedHashMap<String, String>> errandStagesSupplier;
	private java.util.function.Function<String, java.util.LinkedHashMap<String, String>> errandChecklistSupplier;

	/** sub-id -> "nothing can auto-tick this"; see RowContext.manualOnly. */
	private java.util.function.Predicate<String> manualOnlySupplier;

	/** step id -> the destination adopted from one of its notes; see RowContext. */
	private java.util.function.Function<String, net.runelite.api.coords.WorldPoint>
		chosenAlternativeSupplier;

	/** Adopt or drop a note's destination for a step. */
	private java.util.function.BiConsumer<String, net.runelite.api.coords.WorldPoint>
		alternativeHandler;

	// Toolbar (stays fixed while the content below scrolls)
	private final JProgressBar progressBar = new JProgressBar();
	private final IconTextField searchBar = new IconTextField();
	private final JCheckBox hideDoneBox = new JCheckBox("Hide done");
	private final JButton resumeButton = new JButton("Resume");

	// Scrollable content
	private final JPanel content = new JPanel(new GridBagLayout());

	/**
	 * Empty filler under the last step, so a step near the end of a section
	 * can still be scrolled to the TOP of the panel. Height is set by
	 * {@link #scrollRowIntoView} to exactly the shortfall and reset on every
	 * rebuild; zero means "this section already scrolls far enough".
	 */
	private final JPanel scrollTail = new JPanel();

	/**
	 * The step the panel last landed on, so {@link #followCurrentStep} re-lands
	 * only when the frontier actually moves rather than every game tick.
	 */
	private String lastFollowedStepId;

	/**
	 * The row the panel is currently held on, and the exact viewport position
	 * we put it at. Together they let {@link #holdAnchor} tell "the rows above
	 * grew and pushed my step away" apart from "the player scrolled".
	 */
	private StepRow anchorRow;
	private int anchorAppliedY = -1;
	/**
	 * Set by a real wheel turn or scrollbar drag, and by nothing else.
	 *
	 * <p>This is the discriminator the scroll code never had. Cleared
	 * whenever we begin a landing of our own, so it always answers "has the
	 * player moved the view SINCE we started trying to land".
	 */
	private boolean userMovedTheView;
	/** True while the panel is deliberately setting the view position. */
	private boolean weAreScrolling;
	/** Last position the change listener saw, so it can report a delta. */
	private int lastSeenViewY;
	/**
	 * Where the viewport top sits INSIDE the anchor row. Normally the row's
	 * own {@code scrollOffset()}; after the player scrolls by hand it is
	 * whatever part-way position they chose, so re-asserting holds their view
	 * instead of tidying the row's top up to the viewport's top.
	 */
	private int anchorOffsetInRow;
	private final JScrollPane scrollPane;

	private Guide guide;

	// Which section is open, -1/-1 = overview. Used to rebuild the same view on refresh.
	private int openChapter = -1;
	private int openSection = -1;

	@Inject
	public IronscapePanel(ProgressManager progressManager, ConfigManager configManager,
		IronscapeConfig config, AnnotationManager annotationManager,
		ItemTracker itemTracker, PlaceManager placeManager)
	{
		// false = don't wrap the whole panel in a scroll pane; we scroll
		// only the step list so the toolbar stays put.
		super(false);
		this.progressManager = progressManager;
		this.configManager = configManager;
		this.config = config;
		this.annotationManager = annotationManager;
		this.itemTracker = itemTracker;
		this.placeManager = placeManager;

		setLayout(new BorderLayout(0, 4));
		setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		add(buildToolbar(), BorderLayout.NORTH);

		content.setBackground(ColorScheme.DARK_GRAY_COLOR);
		// BorderLayout.NORTH wrapper keeps short content top-aligned
		// instead of vertically centered in the viewport. It TRACKS the
		// viewport width: a plain JPanel lays out at preferred width when
		// content prefers wider, and with the horizontal scrollbar set to
		// NEVER the overflow just clips off-screen — that silently ate the
		// ⌖/Go buttons whenever any row grew past the viewport.
		JPanel wrapper = new ViewportWidthPanel(new BorderLayout());
		wrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
		wrapper.add(content, BorderLayout.NORTH);

		scrollPane = new JScrollPane(wrapper,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
			JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.setBorder(null);
		scrollPane.getVerticalScrollBar().setUnitIncrement(16);
		add(scrollPane, BorderLayout.CENTER);

		// ASK THE PLAYER, DO NOT INFER.
		//
		// Everything that scrolls this panel needs to know one thing: did the
		// player move the view, or did we? Five rounds of scroll bugs answered
		// it by comparing the viewport against where we last put it — and that
		// is not the same question. Swing moves the viewport too: rebuilding a
		// section empties the content, the view size collapses, and the
		// position is CLAMPED. The panel read its own clamp as the player
		// grabbing the scrollbar and stood down, silently, at exactly the
		// moment it was meant to be landing on the new step.
		//
		// A wheel turn and a scrollbar drag are real events. Recording them is
		// the only honest answer, and it costs two listeners.
		scrollPane.addMouseWheelListener(e -> userMovedTheView = true);
		scrollPane.getVerticalScrollBar().addAdjustmentListener(e -> {
			if (e.getValueIsAdjusting())
			{
				userMovedTheView = true; // dragging the thumb
			}
		});

		// WHO MOVED THE VIEW?
		//
		// Five rounds of this bug have been diagnosed from screenshots and
		// five have been wrong, because the panel's own log can only report
		// the scrolls the panel PERFORMS — and every failure so far has been
		// the view moving when the panel did nothing at all. The owner opened
		// a shop on step 287 (374px from the top of its section) and the view
		// landed on 315, thousands of pixels down, with no scroll line
		// written. Rows changing height above cannot do that. Something else
		// is calling setViewPosition, and no amount of reasoning from the
		// picture can name it.
		//
		// So: log every movement we did not make, with the stack that caused
		// it. One report then names the culprit outright.
		scrollPane.getViewport().addChangeListener(e -> {
			int y = scrollPane.getViewport().getViewPosition().y;
			if (y == lastSeenViewY)
			{
				return;
			}
			int from = lastSeenViewY;
			lastSeenViewY = y;
			if (wheelEventInFlight())
			{
				// Claim it here rather than waiting for our own wheel listener
				// further down the chain, so the answer does not depend on the
				// order the look-and-feel happens to install its listeners in.
				userMovedTheView = true;
				return;
			}
			if (weAreScrolling || userMovedTheView)
			{
				return; // ours, or a real gesture: both already accounted for
			}
			org.slf4j.LoggerFactory.getLogger(IronscapePanel.class).info(
				"scroll: VIEW MOVED BY SOMETHING ELSE, {} -> {} (delta {}), caller:\n    {}",
				from, y, y - from, blameForViewMove());
		});

		// Opening the sidebar panel should land on "what do I do next", not
		// wherever was last browsed. RuneLite only calls Activatable's
		// onActivate() for MultiplexingPluginPanel, never for a plain
		// PluginPanel like this one — so watch our own Swing visibility:
		// this fires every time the panel becomes showing.
		addHierarchyListener(e -> {
			if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0
				&& isShowing())
			{
				// invokeLater: never mutate the tree mid-hierarchy-event.
				// Scroll only; the Resume button is what redraws the route.
				SwingUtilities.invokeLater(() -> jumpToCurrent(false));
			}
		});
	}

	private JPanel buildToolbar()
	{
		JPanel toolbar = new JPanel();
		toolbar.setLayout(new BoxLayout(toolbar, BoxLayout.Y_AXIS));
		toolbar.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Row 1: progress bar + resume
		progressBar.setStringPainted(true);
		progressBar.setFont(FontManager.getRunescapeSmallFont());
		progressBar.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);
		progressBar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		progressBar.setPreferredSize(new Dimension(100, 24));

		resumeButton.setToolTipText("Jump to your first unfinished step");
		resumeButton.addActionListener(e -> resume());

		JButton clearPathButton = new JButton("✕");
		clearPathButton.setMargin(new java.awt.Insets(2, 6, 2, 6));
		clearPathButton.setToolTipText("Clear the Shortest Path route");
		clearPathButton.addActionListener(e -> {
			if (clearPathHandler != null)
			{
				clearPathHandler.run();
			}
		});

		JButton addPlaceButton = new JButton("+");
		addPlaceButton.setMargin(new java.awt.Insets(2, 6, 2, 6));
		addPlaceButton.setToolTipText("Save your current spot as a named place — "
			+ "that name becomes clickable everywhere in the guide");
		addPlaceButton.addActionListener(e -> addPlace());

		JPanel row1Buttons = new JPanel(new BorderLayout(2, 0));
		row1Buttons.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row1Buttons.add(resumeButton, BorderLayout.WEST);
		row1Buttons.add(addPlaceButton, BorderLayout.CENTER);
		row1Buttons.add(clearPathButton, BorderLayout.EAST);

		JPanel row1 = new JPanel(new BorderLayout(4, 0));
		row1.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row1.add(progressBar, BorderLayout.CENTER);
		row1.add(row1Buttons, BorderLayout.EAST);
		toolbar.add(row1);
		toolbar.add(Box.createVerticalStrut(4));

		// Row 2: search + hide-done
		searchBar.setIcon(IconTextField.Icon.SEARCH);
		searchBar.setPreferredSize(new Dimension(100, 26));
		searchBar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchBar.addKeyListener(new KeyAdapter()
		{
			@Override
			public void keyReleased(KeyEvent e)
			{
				rebuild();
			}
		});
		searchBar.addClearListener(this::rebuild);

		hideDoneBox.setBackground(ColorScheme.DARK_GRAY_COLOR);
		hideDoneBox.setFont(FontManager.getRunescapeSmallFont());
		hideDoneBox.setToolTipText("Hide steps you've already completed");
		hideDoneBox.addActionListener(e ->
			// Store through the config system so it persists; the plugin's
			// ConfigChanged handler triggers the rebuild.
			configManager.setConfiguration(CONFIG_GROUP, "showCompletedSteps",
				String.valueOf(!hideDoneBox.isSelected())));

		JPanel row2 = new JPanel(new BorderLayout(4, 0));
		row2.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row2.add(searchBar, BorderLayout.CENTER);
		row2.add(hideDoneBox, BorderLayout.EAST);
		toolbar.add(row2);

		return toolbar;
	}

	/** The plugin provides the client-thread bridge for the capture buttons. */
	public void setCaptureHandler(CaptureHandler captureHandler)
	{
		this.captureHandler = captureHandler;
	}

	/** Set by the plugin; right-click on ⌖ forgets the LOCAL captured target. */
	private Consumer<String> clearTargetHandler;

	public void setClearTargetHandler(Consumer<String> clearTargetHandler)
	{
		this.clearTargetHandler = clearTargetHandler;
	}

	public void setNavigateHandler(Consumer<String> navigateHandler)
	{
		this.navigateHandler = navigateHandler;
	}

	public void setPlaceNavigateHandler(java.util.function.BiConsumer<String, com.ironscape.guide.GuideStep> placeNavigateHandler)
	{
		this.placeNavigateHandler = placeNavigateHandler;
	}

	public void setWorldHopHandler(Consumer<Integer> worldHopHandler)
	{
		this.worldHopHandler = worldHopHandler;
	}

	public void setAddPlaceHandler(CaptureHandler addPlaceHandler)
	{
		this.addPlaceHandler = addPlaceHandler;
	}

	public void setItemGoals(Map<String, List<GoalDetector.ItemGoal>> itemGoals)
	{
		this.itemGoals = itemGoals;
	}

	public void setActionBadgeSupplier(java.util.function.Function<String, String> actionBadgeSupplier)
	{
		this.actionBadgeSupplier = actionBadgeSupplier;
	}

	public void setBadgeIconSupplier(java.util.function.Function<String, String> badgeIconSupplier)
	{
		this.badgeIconSupplier = badgeIconSupplier;
	}

	public void setSkillIconSupplier(
		java.util.function.Function<String, java.awt.image.BufferedImage> skillIconSupplier)
	{
		this.skillIconSupplier = skillIconSupplier;
	}

	public void setErrandChecklistSupplier(
		java.util.function.Function<String, java.util.LinkedHashMap<String, String>> supplier)
	{
		this.errandChecklistSupplier = supplier;
	}

	public void setErrandStagesSupplier(
		java.util.function.Function<String, java.util.LinkedHashMap<String, String>> supplier)
	{
		this.errandStagesSupplier = supplier;
	}

	public void setManualOnlySupplier(java.util.function.Predicate<String> supplier)
	{
		this.manualOnlySupplier = supplier;
	}

	public void setChosenAlternativeSupplier(
		java.util.function.Function<String, net.runelite.api.coords.WorldPoint> supplier)
	{
		this.chosenAlternativeSupplier = supplier;
	}

	public void setAlternativeHandler(
		java.util.function.BiConsumer<String, net.runelite.api.coords.WorldPoint> handler)
	{
		this.alternativeHandler = handler;
	}

	public void setSafespotCaptureHandler(CaptureHandler safespotCaptureHandler)
	{
		this.safespotCaptureHandler = safespotCaptureHandler;
	}

	public void setOutlineNpcHandler(java.util.function.BiConsumer<String, Boolean> handler)
	{
		this.outlineNpcHandler = handler;
	}

	public void setProgressChangedListener(Runnable progressChangedListener)
	{
		this.progressChangedListener = progressChangedListener;
	}

	public void setClearPathHandler(Runnable clearPathHandler)
	{
		this.clearPathHandler = clearPathHandler;
	}

	/** Swap in a (newly loaded) guide and reset to the overview. */
	public void setGuide(Guide guide)
	{
		this.guide = guide;
		openChapter = -1;
		openSection = -1;
		refresh();
		// If RuneLite reopened the sidebar from the last session, the panel
		// was SHOWING before the guide existed — the hierarchy listener's
		// jump silently no-opped and nothing retried. Land on the current
		// step now that there is one.
		SwingUtilities.invokeLater(() -> {
			if (isShowing())
			{
				jumpToCurrent(false);
			}
		});
	}

	/** Re-read progress/config state and rebuild whatever view is showing. */
	public void refresh()
	{
		if (guide == null)
		{
			return;
		}
		hideDoneBox.setSelected(!config.showCompletedSteps());
		updateProgressBar();
		if (advanceAcrossSectionBoundary())
		{
			return; // opening the next section rebuilt the view already
		}
		rebuild();
	}

	/**
	 * Finishing the LAST step of a section used to leave you looking at a
	 * finished section with a "Next" button to press.
	 *
	 * <p>A rebuild only ever redraws the section already open, and the code
	 * that follows the frontier runs on the item-count path, which does not
	 * fire when a step completes. So the one moment the panel most needs to
	 * move — the section running out — was the moment nothing moved it
	 * (owner, 2026-08-14, standing on the last step of a section: "next step
	 * is the last until we click next, can we make this automatic?").
	 *
	 * <p>Only ever advances out of the section it was ALREADY FOLLOWING. If
	 * you have deliberately opened an earlier section to read, the frontier
	 * moving on is not a reason to drag you forward — same principle as the
	 * scroll anchor never fighting the wheel.
	 *
	 * @return true when the view was rebuilt into another section
	 */
	private boolean advanceAcrossSectionBoundary()
	{
		if (openChapter < 0 || lastFollowedStepId == null)
		{
			return false; // overview, or never followed anything: leave it be
		}
		String query = searchBar.getText() == null ? "" : searchBar.getText().trim();
		if (!query.isEmpty())
		{
			return false;
		}
		GuideStep current = currentStep();
		if (current == null
			|| (current.getChapterIndex() == openChapter && current.getSectionIndex() == openSection))
		{
			return false; // still in this section — an ordinary rebuild is right
		}
		// Was the open section the one we were tracking? If the followed step
		// is not here, the player navigated away and this is their view.
		boolean followingThisSection = false;
		for (GuideStep step : guide.getAllSteps())
		{
			if (step.getId().equals(lastFollowedStepId))
			{
				followingThisSection = step.getChapterIndex() == openChapter
					&& step.getSectionIndex() == openSection;
				break;
			}
		}
		if (!followingThisSection)
		{
			return false;
		}
		org.slf4j.LoggerFactory.getLogger(IronscapePanel.class).info(
			"scroll: section finished — advancing to step {} (index {}) in the next section",
			current.getId(), current.getGlobalIndex());
		jumpToCurrent(false);
		return true;
	}

	// ------------------------------------------------------------------
	// Views
	// ------------------------------------------------------------------

	private void rebuild()
	{
		content.removeAll();

		String query = searchBar.getText() == null ? "" : searchBar.getText().trim();
		if (!query.isEmpty())
		{
			buildSearchView(query);
		}
		else if (openChapter >= 0)
		{
			// Rebuild KEEPS YOUR PLACE. Passing null threw the scroll away and
			// left the viewport wherever a changed layout happened to put it,
			// which is finished steps filling the screen. Proven by the log:
			// a rebuild produced no scroll line at all, because with no target
			// scrollRowIntoView is never called (owner, after ::ironreload,
			// wave 27).
			GuideStep current = currentStep();
			String keep = current != null
				&& current.getChapterIndex() == openChapter
				&& current.getSectionIndex() == openSection
				? current.getId() : null;
			buildSectionView(keep);
		}
		else
		{
			buildOverview();
		}

		content.revalidate();
		content.repaint();
	}

	private GridBagConstraints rowConstraints()
	{
		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 0;
		c.gridy = GridBagConstraints.RELATIVE;
		c.weightx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		return c;
	}

	private void buildOverview()
	{
		GridBagConstraints c = rowConstraints();

		List<GuideChapter> chapters = guide.getChapters();
		for (int ci = 0; ci < chapters.size(); ci++)
		{
			GuideChapter chapter = chapters.get(ci);

			JLabel header = new JLabel("<html><body style='width:180px'><b>"
				+ RichText.escape(chapter.getTitle()) + "</b></body></html>");
			header.setFont(FontManager.getRunescapeFont());
			header.setForeground(ColorScheme.BRAND_ORANGE);
			header.setBorder(BorderFactory.createEmptyBorder(8, 0, 4, 0));
			content.add(header, c);

			for (int si = 0; si < chapter.getSections().size(); si++)
			{
				content.add(sectionRow(ci, si, chapter.getSections().get(si)), c);
			}
		}

		// Guide content credit — a permission condition of shipping it.
		JLabel credit = new JLabel("<html><body style='width:180px'><i>"
			+ "Guide content by Oziris &amp; the ironman.guide community, "
			+ "used with permission. ironman.guide</i></body></html>");
		credit.setFont(FontManager.getRunescapeSmallFont());
		credit.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		credit.setBorder(BorderFactory.createEmptyBorder(14, 0, 6, 0));
		content.add(credit, c);
	}

	private JPanel sectionRow(int ci, int si, GuideSection section)
	{
		int done = progressManager.completedCount(guide.getVariant(), section.getSteps());
		int total = section.getSteps().size();

		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		JLabel title = new JLabel("<html><body style='width:130px'>"
			+ RichText.escape(section.getTitle()) + "</body></html>");
		title.setFont(FontManager.getRunescapeSmallFont());
		title.setForeground(done >= total
			? ColorScheme.PROGRESS_COMPLETE_COLOR
			: ColorScheme.LIGHT_GRAY_COLOR);
		row.add(title, BorderLayout.CENTER);

		JLabel count = new JLabel(done + "/" + total);
		count.setFont(FontManager.getRunescapeSmallFont());
		count.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		row.add(count, BorderLayout.EAST);

		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				openSection(ci, si, null);
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				row.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			}
		});

		// small gap between rows
		JPanel spaced = new JPanel(new BorderLayout());
		spaced.setBackground(ColorScheme.DARK_GRAY_COLOR);
		spaced.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
		spaced.add(row, BorderLayout.CENTER);
		return spaced;
	}

	private void openSection(int ci, int si, String scrollToStepId)
	{
		openChapter = ci;
		openSection = si;
		searchBar.setText("");
		content.removeAll();
		buildSectionView(scrollToStepId);
		content.revalidate();
		content.repaint();
	}

	private void buildSectionView(String scrollToStepId)
	{
		// Fresh section, fresh tail: the shortfall belongs to one layout.
		scrollTail.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scrollTail.setPreferredSize(new Dimension(1, 0));

		GuideChapter chapter = guide.getChapters().get(openChapter);
		GuideSection section = chapter.getSections().get(openSection);
		GridBagConstraints c = rowConstraints();

		// Header: back button + section title
		JButton back = new JButton("◀");
		back.setToolTipText("Back to overview");
		back.setMargin(new java.awt.Insets(2, 6, 2, 6));
		back.addActionListener(e -> {
			openChapter = -1;
			openSection = -1;
			rebuild();
		});

		JLabel title = new JLabel("<html><body style='width:140px'><b>"
			+ RichText.escape(section.getTitle()) + "</b></body></html>");
		title.setFont(FontManager.getRunescapeSmallFont());
		title.setForeground(ColorScheme.BRAND_ORANGE);

		JPanel header = new JPanel(new BorderLayout(6, 0));
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.setBorder(BorderFactory.createEmptyBorder(4, 0, 6, 0));
		header.add(back, BorderLayout.WEST);
		header.add(title, BorderLayout.CENTER);
		content.add(header, c);

		// Steps
		boolean hideDone = !config.showCompletedSteps();
		StepRow scrollTarget = null;
		for (GuideStep step : section.getSteps())
		{
			boolean completed = progressManager.isCompleted(guide.getVariant(), step.getId());
			if (hideDone && completed)
			{
				continue;
			}
			StepRow row = new StepRow(step, rowContext());
			if (step.getId().equals(scrollToStepId))
			{
				scrollTarget = row;
			}
			content.add(row, c);
		}

		// Chapter footnotes (expected stats etc.) after the chapter's last section
		if (openSection == chapter.getSections().size() - 1)
		{
			for (List<TextRun> footnote : chapter.getFootnotes())
			{
				JLabel note = new JLabel(RichText.paragraphHtml(footnote));
				note.setFont(FontManager.getRunescapeSmallFont());
				note.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				note.setBorder(BorderFactory.createEmptyBorder(8, 2, 0, 2));
				content.add(note, c);
			}
		}

		// Footer: previous / next section
		JPanel footer = new JPanel(new BorderLayout(4, 0));
		footer.setBackground(ColorScheme.DARK_GRAY_COLOR);
		footer.setBorder(BorderFactory.createEmptyBorder(8, 0, 4, 0));
		if (hasSection(openChapter, openSection - 1) || openChapter > 0)
		{
			JButton prev = new JButton("◀ Prev");
			prev.setFont(FontManager.getRunescapeSmallFont());
			prev.addActionListener(e -> stepSection(-1));
			footer.add(prev, BorderLayout.WEST);
		}
		if (hasSection(openChapter, openSection + 1) || openChapter < guide.getChapters().size() - 1)
		{
			JButton next = new JButton("Next ▶");
			next.setFont(FontManager.getRunescapeSmallFont());
			next.addActionListener(e -> stepSection(1));
			footer.add(next, BorderLayout.EAST);
		}
		content.add(footer, c);

		// Room to scroll PAST the end of the list.
		//
		// Without it the last steps of a section can never reach the top of
		// the panel: there is nothing below them to scroll, so the viewport
		// clamps and the step you are on sits at the BOTTOM with finished
		// ones filling the screen above it. The owner met this on step 278,
		// the 279th of 286 in its section, and it gets steadily worse the
		// further into a section you are — which is why it read as the scroll
		// "getting worse", and why two rounds of chasing async layout timing
		// found nothing (wave 27).
		//
		// Grown only as far as the step in hand actually needs, in
		// scrollRowIntoView, so sections that fit keep their exact old feel.
		content.add(scrollTail, c);

		if (scrollTarget != null)
		{
			scrollRowIntoView(scrollTarget, 20);
		}
		else
		{
			SwingUtilities.invokeLater(() ->
				scrollPane.getVerticalScrollBar().setValue(0));
		}
	}

	/**
	 * Scroll a row into view once layout has happened. Right after the
	 * panel is (re)shown the rows can still have zero bounds for an EDT
	 * cycle or two, so retry a few times instead of scrolling to nothing.
	 */
	private void scrollRowIntoView(StepRow target, int attemptsLeft)
	{
		// A landing of ours starts here, so anything the player did before now
		// is history — otherwise one wheel turn early in a session would stop
		// the panel ever landing again.
		userMovedTheView = false;
		// Release the OLD anchor for the duration. It is still pinned to the
		// previous step, and holdAnchor runs every tick, so it spends the
		// retry window hauling the view back to where we are trying to leave:
		//   frontier moved to step 289 - following
		//   holding step 288 - row moved or view drifted, 480 -> 501
		//   settled on step 289
		// The landing wins, so this was invisible in play — but two parts
		// pulling opposite ways for a second is how the next mystery starts,
		// and this whole bug was six rounds of exactly that. Nothing needs
		// holding while a landing is actively re-asserting; the settle (and
		// the give-up) sets the new anchor.
		anchorRow = null;
		anchorAppliedY = -1;
		scrollRowIntoView(target, attemptsLeft, Integer.MIN_VALUE);
	}

	/**
	 * Having a height is NOT the same as having settled. Item icons arrive
	 * asynchronously and the html panes size late, so rows keep growing for
	 * a few cycles after the first paint — and a row above the target that
	 * grows slides the target down, out of the view we just set. That is
	 * the panel "jumping to a step you are not on": the target was right
	 * (the frontier), the coordinates were stale (owner, in play, standing
	 * on 271 while the panel showed 275 — the step above it lists twelve
	 * items).
	 *
	 * So re-assert until the computed position stops moving, then stop.
	 * Bounded, and it stands down for a real scroll gesture rather than
	 * fighting the wheel.
	 */
	private void scrollRowIntoView(StepRow target, int attemptsLeft, int lastAt)
	{
		SwingUtilities.invokeLater(() -> {
			javax.swing.JViewport viewport = scrollPane.getViewport();
			// The player outranks us — but ONLY the player.
			//
			// This used to compare the viewport against where we last put it
			// and treat any difference as the player taking hold. Swing also
			// moves the viewport: a rebuild empties the content, the view
			// collapses, and the position is clamped. So a rebuild looked
			// identical to a scroll gesture, and the landing stood down
			// mid-retry — leaving the view at whatever the clamp chose, with
			// NO log line, which is why five rounds of this bug produced an
			// almost empty log (owner, 2026-08-14: the panel showing step 314
			// while the frontier was 287, no scroll line written at all).
			if (userMovedTheView)
			{
				org.slf4j.LoggerFactory.getLogger(IronscapePanel.class).info(
					"scroll: standing down on step {} (index {}) — the player scrolled",
					target.getStep().getId(), target.getStep().getGlobalIndex());
				return;
			}
			if (target.getBounds().height == 0 && attemptsLeft > 0)
			{
				scrollRowIntoView(target, attemptsLeft - 1, lastAt);
				return;
			}
			// Top-aligned on the card — and on a MULTI-action step, on its
			// first unticked sub instead, since such a step can be taller
			// than the viewport. See StepRow.scrollOffset.
			int y = Math.max(0, target.getY() + target.scrollOffset() - 8);
			int maxY = Math.max(0, viewport.getViewSize().height - viewport.getExtentSize().height);
			// Not enough list below this step to lift it to the top: extend the
			// tail by exactly the shortfall and let the retry land on it. Only
			// ever grows within one build, so it settles instead of oscillating.
			if (y > maxY && attemptsLeft > 0)
			{
				int shortfall = y - maxY;
				java.awt.Dimension tail = scrollTail.getPreferredSize();
				scrollTail.setPreferredSize(
					new java.awt.Dimension(1, tail.height + shortfall));
				content.revalidate();
				scrollRowIntoView(target, attemptsLeft - 1, lastAt);
				return;
			}
			// SETTLE ON THE POSITION WE ACTUALLY APPLIED, not on the raw y.
			// The two differ whenever the view is still short — rows below
			// the target had not been sized yet — and then y can be stable
			// while the clamp is still moving. Comparing y alone declared
			// victory on a clamped, too-high position and stopped, which is
			// the panel "landing a couple of cards above the step you are on"
			// (owner, in play, wave 27: current step at the very bottom edge
			// with two finished ones above it).
			int at = Math.min(y, maxY);
			setViewPosition(viewport, at);
			if (at == lastAt || attemptsLeft <= 0)
			{
				// ALWAYS report where it ended up, settled or not. Reporting
				// only failures is why three rounds of this bug produced no
				// evidence at all: on two of them the code never ran, and on
				// the third it "succeeded" at the wrong place. A landing line
				// per step change is cheap and makes the next report readable.
				org.slf4j.LoggerFactory.getLogger(IronscapePanel.class).info(
					"scroll: {} on step {} (index {}) — row y={}, offset={}, wanted={},"
						+ " applied={}, view={}, viewport={}, tail={}",
					at == lastAt ? "settled" : "GAVE UP",
					target.getStep().getId(), target.getStep().getGlobalIndex(),
					target.getY(), target.scrollOffset(), y, at,
					viewport.getViewSize().height, viewport.getExtentSize().height,
					scrollTail.getPreferredSize().height);
				// Hold this row from here (see holdAnchor). The offset is
				// carried rather than recomputed so that a hand scroll can
				// replace it with the player's own part-way position.
				anchorRow = target;
				anchorAppliedY = at;
				anchorOffsetInRow = at - target.getY();
				return;
			}
			// Item icons arrive asynchronously and html panes size late, so
			// the budget has to outlast them: 20 x 80ms rather than the old
			// 5 x 60ms, which expired while rows above were still growing.
			javax.swing.Timer retry = new javax.swing.Timer(80,
				e -> scrollRowIntoView(target, attemptsLeft - 1, at));
			retry.setRepeats(false);
			retry.start();
		});
	}

	private boolean hasSection(int ci, int si)
	{
		return ci >= 0 && ci < guide.getChapters().size()
			&& si >= 0 && si < guide.getChapters().get(ci).getSections().size();
	}

	/** Move to the neighbouring section, crossing chapter boundaries. */
	private void stepSection(int direction)
	{
		int ci = openChapter;
		int si = openSection + direction;
		if (!hasSection(ci, si))
		{
			ci += direction;
			if (ci < 0 || ci >= guide.getChapters().size())
			{
				return;
			}
			si = direction > 0 ? 0 : guide.getChapters().get(ci).getSections().size() - 1;
		}
		openSection(ci, si, null);
	}

	private void buildSearchView(String query)
	{
		GridBagConstraints c = rowConstraints();
		String needle = query.toLowerCase(Locale.ROOT);

		int shown = 0;
		int matches = 0;
		for (GuideStep step : guide.getAllSteps())
		{
			if (!step.getPlainText().toLowerCase(Locale.ROOT).contains(needle))
			{
				continue;
			}
			matches++;
			if (shown >= MAX_SEARCH_RESULTS)
			{
				continue; // keep counting matches, stop adding rows
			}
			shown++;

			GuideSection section = guide.getChapters().get(step.getChapterIndex())
				.getSections().get(step.getSectionIndex());
			JLabel crumb = new JLabel(RichText.escape(section.getTitle()));
			crumb.setFont(FontManager.getRunescapeSmallFont());
			crumb.setForeground(ColorScheme.BRAND_ORANGE);
			crumb.setBorder(BorderFactory.createEmptyBorder(6, 2, 0, 0));
			crumb.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			int ci = step.getChapterIndex();
			int si = step.getSectionIndex();
			String id = step.getId();
			crumb.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					openSection(ci, si, id);
				}
			});
			content.add(crumb, c);

			content.add(new StepRow(step, rowContext()), c);
		}

		JLabel summary = new JLabel(matches == 0
			? "No steps match \"" + query + "\""
			: matches + " match" + (matches == 1 ? "" : "es")
				+ (matches > shown ? " (showing first " + shown + ")" : ""));
		summary.setFont(FontManager.getRunescapeSmallFont());
		summary.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		summary.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 0));
		content.add(summary, c, 0); // summary goes at the top
	}

	// ------------------------------------------------------------------
	// Actions
	// ------------------------------------------------------------------

	/**
	 * A step was auto-completed by the plugin (skill requirement met).
	 * Update the progress bar, and if the step's row is currently on
	 * screen, tick its checkbox in place — no rebuild, no scroll jump.
	 */
	public void markStepCompleted(String stepId)
	{
		if (guide == null)
		{
			return;
		}
		updateProgressBar();
		for (Component component : content.getComponents())
		{
			if (component instanceof StepRow && ((StepRow) component).getStep().getId().equals(stepId))
			{
				((StepRow) component).setCompletedSilently(true);
			}
		}
	}

	/** Everything a StepRow needs. Capture buttons only when enabled in settings AND wired. */
	private RowContext rowContext()
	{
		return new RowContext(
			guide.getVariant(), progressManager, annotationManager, itemTracker, placeManager,
			itemGoals, actionBadgeSupplier, badgeIconSupplier, skillIconSupplier,
			errandStagesSupplier,
			errandChecklistSupplier,
			manualOnlySupplier,
			this::onManualProgressChange,
			config.showCaptureButtons() ? captureHandler : null,
			config.showCaptureButtons() ? safespotCaptureHandler : null,
			clearTargetHandler,
			navigateHandler,
			placeNavigateHandler,
			worldHopHandler,
			this::questStep,
			() -> jumpToCurrent(true),
			dependentSteps()::get,
			earlierQuestLegs()::get,
			chosenAlternativeSupplier,
			alternativeHandler,
			outlineNpcHandler);
	}

	/**
	 * The step whose task IS the named quest — the destination for the "do
	 * this first" button on a step with a prerequisiteQuest.
	 *
	 * Matched on the QUEST TAG only (the guide's own metadata, or our
	 * annotation tag), never on the step's text. A text scan would pick the
	 * earliest step that merely MENTIONS the quest, which on a prep step
	 * ("buy a bronze sword for Horror from the deep") is the wrong
	 * destination entirely — the same trap that made quest tagging exclude
	 * prep steps in the first place. If no step carries the tag this returns
	 * null and the caller shows the warning with no button, which is the
	 * honest failure: a button to nowhere is worse than none.
	 *
	 * Earliest match wins — that is where you BEGIN the quest.
	 */
	/**
	 * The inverse of prerequisiteQuest: prerequisite step id -> the step held
	 * up by it. Built once per rebuild rather than scanned per row.
	 *
	 * Earliest dependent wins if several steps ever name the same
	 * prerequisite — going back to the first one you were blocked on is the
	 * only answer that is right for all of them.
	 */
	private java.util.Map<String, GuideStep> dependentSteps()
	{
		java.util.Map<String, GuideStep> map = new java.util.HashMap<>();
		if (guide == null)
		{
			return map;
		}
		for (GuideStep step : guide.getAllSteps())
		{
			String needs = annotationManager.getPrerequisiteQuest(step.getId());
			if (needs == null)
			{
				// Either relationship earns a return trip: required or merely
				// the easier route, you came here from that step.
				needs = annotationManager.getAlternativeQuest(step.getId());
			}
			if (needs == null)
			{
				continue;
			}
			GuideStep target = questStep(needs);
			if (target != null)
			{
				map.putIfAbsent(target.getId(), step);
			}
		}
		return map;
	}

	/**
	 * Step id -> the earliest UNFINISHED step of the same quest that comes
	 * before it. Empty for a guide followed in order; populated the moment
	 * you work a quest's legs out of sequence.
	 *
	 * Guide order and quest order are not the same thing. The Lost Tribe is
	 * split across steps 256, 258, 282 and 284, so finishing the deferred
	 * Goblin Diplomacy at 281 advanced the frontier to 282 — "Continue Lost
	 * tribe", for a quest not yet started.
	 *
	 * Needs no annotation: the quest comes from the guide's own metadata or
	 * our tag, and "unfinished" from progress. That makes it true of every
	 * quest the guide splits up, rather than only the one that exposed it.
	 */
	private java.util.Map<String, GuideStep> earlierQuestLegs()
	{
		java.util.Map<String, GuideStep> result = new java.util.HashMap<>();
		if (guide == null)
		{
			return result;
		}
		java.util.Map<String, GuideStep> firstUnfinished = new java.util.HashMap<>();
		for (GuideStep step : guide.getAllSteps())
		{
			String quest = step.getMetadata().get("quest");
			if (quest == null)
			{
				quest = annotationManager.getQuest(step.getId());
			}
			if (quest == null)
			{
				continue;
			}
			String key = quest.trim().toLowerCase(java.util.Locale.ROOT);
			// Read before recording, so a step never points at itself.
			GuideStep earlier = firstUnfinished.get(key);
			if (earlier != null)
			{
				result.put(step.getId(), earlier);
			}
			else if (!progressManager.isCompleted(guide.getVariant(), step.getId()))
			{
				firstUnfinished.put(key, step);
			}
		}
		return result;
	}

	private GuideStep questStep(String questName)
	{
		if (guide == null || questName == null)
		{
			return null;
		}
		String want = questName.trim();
		for (GuideStep step : guide.getAllSteps())
		{
			String tag = step.getMetadata().get("quest");
			if (tag == null)
			{
				tag = annotationManager.getQuest(step.getId());
			}
			if (tag != null && tag.trim().equalsIgnoreCase(want))
			{
				return step;
			}
		}
		return null;
	}

	/**
	 * The plugin auto-completed a single sub-step (item or quest goal).
	 * Tick its checkbox in place if visible; no rebuild, no scroll jump.
	 */
	public void markSubCompleted(String stepId, String subId)
	{
		if (guide == null)
		{
			return;
		}
		updateProgressBar();
		for (Component component : content.getComponents())
		{
			if (component instanceof StepRow && ((StepRow) component).getStep().getId().equals(stepId))
			{
				((StepRow) component).setSubCompletedSilently(subId);
			}
		}
	}

	/** Live item counts changed (inventory/bank) — update the have/need badges in place. */
	public void refreshItemCounts()
	{
		for (Component component : content.getComponents())
		{
			if (component instanceof StepRow)
			{
				((StepRow) component).refreshItemBadges();
			}
		}
		followCurrentStep();
		holdAnchor();
	}

	/**
	 * Keep the view on the ROW, not on a pixel offset.
	 *
	 * <p>The scroll position is a pixel count into a view that is ~66,000px
	 * tall against an ~850px viewport. Item icons load asynchronously, so rows
	 * ABOVE the one you are on keep growing after the landing — and a few
	 * pixels each across a couple of hundred rows moves your step a long way
	 * down, without anything having scrolled. Re-applying the row's position
	 * costs nothing and holds it there.
	 *
	 * <p>Never fights the wheel: after a real scroll gesture the anchor
	 * RE-POINTS to the row the player is now reading rather than being
	 * abandoned. A viewport that moved on its own is the thing this exists
	 * to undo, and is put back.
	 */
	private void holdAnchor()
	{
		if (anchorRow == null || anchorRow.getParent() == null)
		{
			return;
		}
		javax.swing.JViewport viewport = scrollPane.getViewport();
		if (userMovedTheView)
		{
			// A real wheel turn or thumb drag. Re-point the anchor at the row
			// they have scrolled to rather than abandoning it.
			//
			// Dropping it was the old bug. Once dropped it never came back
			// until the next rebuild, which left the panel exposed to the very
			// thing the anchor exists to prevent: opening the BANK resolves
			// bank counts for every item row at once, hundreds of rows above
			// grow a few pixels each, and the content slides down under a
			// fixed pixel offset until finished steps fill the screen. Nothing
			// scrolled, so no scroll line was written and it looked exactly
			// like the panel jumping on its own (owner, 2026-08-14: "when I
			// opened the bank window the plugin scrolled to this spot", and —
			// the discriminator — "I did [scroll] but not to that spot").
			//
			// Testing the viewport POSITION here was itself wrong: Swing
			// clamps it on every rebuild, so the panel kept concluding the
			// player had scrolled when nobody had touched anything.
			int y = viewport.getViewPosition().y;
			anchorRow = topmostVisibleRow();
			// Keep the row exactly where they parked it, part-scrolled or
			// not — recomputing from scrollOffset() would snap the row's top
			// to the viewport's top, which is us tidying up their view.
			anchorOffsetInRow = anchorRow == null ? 0 : y - anchorRow.getY();
			anchorAppliedY = anchorRow == null ? -1 : y;
			userMovedTheView = false;
			return;
		}
		int want = Math.max(0, anchorRow.getY() + anchorOffsetInRow);
		int maxY = Math.max(0, viewport.getViewSize().height - viewport.getExtentSize().height);
		int at = Math.min(want, maxY);
		// Re-apply when the row has moved under us OR when the viewport has
		// drifted off the position we set. The second half matters on its
		// own: a rebuild clamps the position, and if the row happens to want
		// the same offset as before, comparing only `at` would decide there
		// was nothing to do and leave the clamped view in place.
		if (at != anchorAppliedY || viewport.getViewPosition().y != at)
		{
			org.slf4j.LoggerFactory.getLogger(IronscapePanel.class).info(
				"scroll: holding step {} — row moved or view drifted, {} -> {} (view was at {})",
				anchorRow.getStep().getId(), anchorAppliedY, at, viewport.getViewPosition().y);
			setViewPosition(viewport, at);
			anchorAppliedY = at;
		}
	}

	/**
	 * The first step row whose bottom is below the top of the viewport —
	 * what the player is looking at after scrolling by hand.
	 *
	 * <p>Rows are added to {@code content} in guide order, so the first hit
	 * scanning forwards is the topmost one on screen.
	 */
	/**
	 * The interesting frames of whoever is moving the viewport right now.
	 *
	 * <p>Swing's own plumbing dominates the stack, so the frames worth
	 * reading are the ones that are NOT this listener and NOT the raw
	 * viewport/scrollbar machinery. Trimmed to a handful because the point is
	 * to name a culprit, not to print a wall.
	 */
	/**
	 * Is the event being dispatched right now a scroll-wheel turn?
	 *
	 * <p>Our wheel listener is not the only one on the scroll pane: the
	 * look-and-feel installs its own, and listeners are called in a chain.
	 * The LAF's runs first, moves the scrollbar, and the viewport's change
	 * listener fires SYNCHRONOUSLY inside it — before ours has set the flag.
	 * So a genuine wheel turn was being reported as an unexplained move.
	 *
	 * <p>Harmless to the behaviour, since the flag is set a moment later and
	 * everything that reads it runs afterwards. Not harmless to the LOG: a
	 * diagnostic that cries wolf on ordinary scrolling buries the next real
	 * finding, which is the whole reason this line exists.
	 */
	private static boolean wheelEventInFlight()
	{
		java.awt.AWTEvent current = java.awt.EventQueue.getCurrentEvent();
		return current instanceof java.awt.event.MouseWheelEvent;
	}

	private static String blameForViewMove()
	{
		StringBuilder blame = new StringBuilder();
		int kept = 0;
		for (StackTraceElement frame : new Throwable().getStackTrace())
		{
			String at = frame.getClassName();
			if (at.startsWith("com.ironscape.panel.IronscapePanel")
				|| at.startsWith("javax.swing.JViewport")
				|| at.startsWith("javax.swing.plaf")
				|| at.startsWith("java.awt.EventQueue")
				|| at.startsWith("java.awt.event"))
			{
				continue;
			}
			blame.append(frame).append("\n    ");
			if (++kept >= 8)
			{
				break;
			}
		}
		return blame.length() == 0 ? "(nothing but Swing internals)" : blame.toString().trim();
	}

	/** Move the view, flagged so the change listener knows it was us. */
	private void setViewPosition(javax.swing.JViewport viewport, int y)
	{
		weAreScrolling = true;
		try
		{
			viewport.setViewPosition(new java.awt.Point(0, y));
			lastSeenViewY = y;
		}
		finally
		{
			weAreScrolling = false;
		}
	}

	private StepRow topmostVisibleRow()
	{
		int top = scrollPane.getViewport().getViewPosition().y;
		for (Component component : content.getComponents())
		{
			if (component instanceof StepRow && component.getY() + component.getHeight() > top)
			{
				return (StepRow) component;
			}
		}
		return null;
	}

	/**
	 * Keep the panel on the step you are ON as the frontier moves.
	 *
	 * <p>Nothing did this. Completing a step fires no rebuild — this per-tick
	 * path only RESTYLES rows — so the view stayed at its old pixel offset
	 * while the step you were on moved below the fold, leaving finished steps
	 * filling the screen. The owner saw it as the panel "auto-scrolling back
	 * to steps I've completed" without touching anything, which is exactly
	 * what it looks like when the content moves and nobody re-lands it.
	 *
	 * <p>Deliberately SCROLLS rather than rebuilds: every step of the open
	 * section is already a live row, and rebuilding from the per-tick path is
	 * what blanked the panel in wave 15. Only a step in a DIFFERENT section
	 * needs the rebuild, and that happens once per section boundary.
	 */
	private void followCurrentStep()
	{
		if (guide == null || openChapter < 0)
		{
			return; // overview or search: the player is browsing, leave them be.
		}
		String query = searchBar.getText() == null ? "" : searchBar.getText().trim();
		if (!query.isEmpty())
		{
			return;
		}
		GuideStep current = currentStep();
		if (current == null || current.getId().equals(lastFollowedStepId))
		{
			return;
		}
		lastFollowedStepId = current.getId();
		for (Component component : content.getComponents())
		{
			if (component instanceof StepRow
				&& ((StepRow) component).getStep().getId().equals(current.getId()))
			{
				org.slf4j.LoggerFactory.getLogger(IronscapePanel.class).info(
					"scroll: frontier moved to step {} (index {}) — following",
					current.getId(), current.getGlobalIndex());
				scrollRowIntoView((StepRow) component, 20);
				return;
			}
		}
		// Not in the open section — the frontier crossed a boundary.
		org.slf4j.LoggerFactory.getLogger(IronscapePanel.class).info(
			"scroll: frontier moved to step {} (index {}), which is NOT a row in the"
				+ " open section — rebuilding",
			current.getId(), current.getGlobalIndex());
		jumpToCurrent(false);
	}

	/** Toolbar "+": name the spot you're standing on; the name becomes a link guide-wide. */
	private void addPlace()
	{
		if (addPlaceHandler == null)
		{
			return;
		}
		String name = javax.swing.JOptionPane.showInputDialog(this,
			"Place name, exactly as the guide writes it (e.g. \"Duke Horacio\"):",
			"IRONSCAPE Optimal — add place", javax.swing.JOptionPane.PLAIN_MESSAGE);
		if (name == null || name.trim().isEmpty())
		{
			return;
		}
		addPlaceHandler.capture(name.trim(), saved -> {
			if (saved)
			{
				rebuild(); // re-render so the new name lights up as a link
			}
			else
			{
				javax.swing.JOptionPane.showMessageDialog(this,
					"You need to be logged in to capture a location.",
					"IRONSCAPE Optimal", javax.swing.JOptionPane.INFORMATION_MESSAGE);
			}
		});
	}

	/** A checkbox was clicked: refresh the bar, then let the plugin react (auto-navigation). */
	private void onManualProgressChange()
	{
		updateProgressBar();
		if (progressChangedListener != null)
		{
			progressChangedListener.run();
		}
	}

	private void resume()
	{
		jumpToCurrent(true);
	}

	/**
	 * "Current" = the first incomplete step AFTER the player's POSITION — the
	 * same frontier the overlays and auto-completion use. NOT "after the last
	 * completed step": a quest finished ages ago auto-ticks its step far ahead
	 * (Daddy's Home), and landing there would skip everything in between.
	 *
	 * @return the step, or null when everything is done
	 */
	private GuideStep currentStep()
	{
		if (guide == null)
		{
			return null;
		}
		java.util.List<GuideStep> steps = guide.getAllSteps();
		int start = Math.max(0, progressManager.playerPosition(guide) + 1);
		while (start < steps.size() && nothingLeftIn(steps.get(start)))
		{
			start++;
		}
		return start >= steps.size() ? null : steps.get(start);
	}

	private void jumpToCurrent(boolean navigate)
	{
		GuideStep step = currentStep();
		if (step == null)
		{
			return; // everything done — nothing to resume. (Congratulations.)
		}
		lastFollowedStepId = step.getId();
		openSection(step.getChapterIndex(), step.getSectionIndex(), step.getId());
		// Resume also points the map at what's next.
		if (navigate && progressChangedListener != null)
		{
			progressChangedListener.run();
		}
	}

	/** Completed, or every sub already ticked (auto-ticks ahead of the step). */
	private boolean nothingLeftIn(GuideStep step)
	{
		if (progressManager.isCompleted(guide.getVariant(), step.getId()))
		{
			return true;
		}
		for (com.ironscape.guide.SubStep sub : step.getSubSteps())
		{
			if (!progressManager.isSubCompleted(guide.getVariant(), step, sub))
			{
				return false;
			}
		}
		return true;
	}

	private void updateProgressBar()
	{
		int total = guide.getAllSteps().size();
		int done = progressManager.completedCount(guide);
		progressBar.setMaximum(total);
		progressBar.setValue(done);
		progressBar.setString(done + " / " + total + " (" + (total == 0 ? 0 : 100 * done / total) + "%)");
	}
}
