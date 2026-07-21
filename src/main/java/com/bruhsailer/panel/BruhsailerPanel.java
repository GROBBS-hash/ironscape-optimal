package com.bruhsailer.panel;

import com.bruhsailer.BruhsailerConfig;
import com.bruhsailer.annotations.AnnotationManager;
import com.bruhsailer.goals.GoalDetector;
import com.bruhsailer.guide.Guide;
import com.bruhsailer.items.ItemTracker;
import com.bruhsailer.places.PlaceManager;
import com.bruhsailer.guide.GuideChapter;
import com.bruhsailer.guide.GuideSection;
import com.bruhsailer.guide.GuideStep;
import com.bruhsailer.guide.TextRun;
import com.bruhsailer.progress.ProgressManager;
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
public class BruhsailerPanel extends PluginPanel
{
	private static final String CONFIG_GROUP = "bruhsailer";
	private static final int MAX_SEARCH_RESULTS = 50;

	private final ProgressManager progressManager;
	private final ConfigManager configManager;
	private final BruhsailerConfig config;
	private final AnnotationManager annotationManager;
	private final ItemTracker itemTracker;
	private final PlaceManager placeManager;

	/** Set by the plugin; null until then (capture buttons stay hidden). */
	private CaptureHandler captureHandler;

	/** Set by the plugin; routes to a target (by annotation id) via Shortest Path. */
	private Consumer<String> navigateHandler;

	/** Set by the plugin; routes to a named place via Shortest Path. */
	private Consumer<String> placeNavigateHandler;

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

	// Toolbar (stays fixed while the content below scrolls)
	private final JProgressBar progressBar = new JProgressBar();
	private final IconTextField searchBar = new IconTextField();
	private final JCheckBox hideDoneBox = new JCheckBox("Hide done");
	private final JButton resumeButton = new JButton("Resume");

	// Scrollable content
	private final JPanel content = new JPanel(new GridBagLayout());
	private final JScrollPane scrollPane;

	private Guide guide;

	// Which section is open, -1/-1 = overview. Used to rebuild the same view on refresh.
	private int openChapter = -1;
	private int openSection = -1;

	@Inject
	public BruhsailerPanel(ProgressManager progressManager, ConfigManager configManager,
		BruhsailerConfig config, AnnotationManager annotationManager,
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
		// instead of vertically centered in the viewport.
		JPanel wrapper = new JPanel(new BorderLayout());
		wrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
		wrapper.add(content, BorderLayout.NORTH);

		scrollPane = new JScrollPane(wrapper,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
			JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.setBorder(null);
		scrollPane.getVerticalScrollBar().setUnitIncrement(16);
		add(scrollPane, BorderLayout.CENTER);
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

	public void setNavigateHandler(Consumer<String> navigateHandler)
	{
		this.navigateHandler = navigateHandler;
	}

	public void setPlaceNavigateHandler(Consumer<String> placeNavigateHandler)
	{
		this.placeNavigateHandler = placeNavigateHandler;
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
		rebuild();
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
			buildSectionView(null);
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

		if (scrollTarget != null)
		{
			StepRow target = scrollTarget;
			// After layout has happened, scroll the row into view.
			SwingUtilities.invokeLater(() ->
				content.scrollRectToVisible(target.getBounds()));
		}
		else
		{
			SwingUtilities.invokeLater(() ->
				scrollPane.getVerticalScrollBar().setValue(0));
		}
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
			itemGoals, actionBadgeSupplier,
			this::onManualProgressChange,
			config.showCaptureButtons() ? captureHandler : null,
			navigateHandler,
			placeNavigateHandler);
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
		for (GuideStep step : guide.getAllSteps())
		{
			if (!progressManager.isCompleted(guide.getVariant(), step.getId()))
			{
				openSection(step.getChapterIndex(), step.getSectionIndex(), step.getId());
				// Resume also points the map at what's next.
				if (progressChangedListener != null)
				{
					progressChangedListener.run();
				}
				return;
			}
		}
		// Everything done — nothing to resume. (Congratulations.)
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
