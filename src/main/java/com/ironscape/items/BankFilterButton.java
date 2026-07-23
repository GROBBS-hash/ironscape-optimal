package com.ironscape.items;

import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.VarClientInt;
import net.runelite.api.VarClientStr;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.SpriteID;
import net.runelite.api.vars.InputType;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.plugins.bank.BankSearch;

/**
 * The clickable button inside the bank interface that toggles the
 * "guide items only" view — same approach as Quest Helper's bank tab:
 * a child widget on the bank root, and while active the plugin answers
 * the bank layout script's getSearchingTagTab/bankSearchFilter callbacks
 * so the bank lays itself out as if a search matched our items.
 *
 * Everything here must run on the client thread.
 */
@Slf4j
@Singleton
public class BankFilterButton
{
	private static final int BUTTON_SIZE = 25;
	// Sits left of where Quest Helper puts its button (408), so both fit.
	private static final int BUTTON_X = 380;
	private static final int BUTTON_Y = 5;

	private final Client client;
	private final BankSearch bankSearch;

	/** True while the bank is showing only guide items. */
	@Getter
	private boolean active;

	private Widget background;

	@Inject
	public BankFilterButton(Client client, BankSearch bankSearch)
	{
		this.client = client;
		this.bankSearch = bankSearch;
	}

	/** (Re)create the button when the bank interface loads. */
	public void init()
	{
		Widget parent = client.getWidget(InterfaceID.Bankmain.UNIVERSE);
		if (parent == null)
		{
			log.info("bank filter button: bank UNIVERSE widget missing — button not created");
			return;
		}
		log.info("bank filter button: created on bank open");
		active = false;

		background = createGraphic(parent, "IRONSCAPE Optimal",
			SpriteID.Miscgraphics3.UNKNOWN_BUTTON_SQUARE_SMALL,
			BUTTON_SIZE, BUTTON_SIZE, BUTTON_X, BUTTON_Y);
		background.setAction(1, "View guide items");
		background.setOnOpListener((JavaScriptCallback) event -> toggle());

		createGraphic(parent, "",
			SpriteID.AchievementDiaryIcons.BLUE_QUESTS,
			BUTTON_SIZE - 6, BUTTON_SIZE - 6, BUTTON_X + 3, BUTTON_Y + 3);
	}

	/**
	 * True right after our own toggle triggered a bank relayout — the
	 * relayout fires the same search-toggle script the plugin watches to
	 * turn the filter off when the PLAYER opens a real search. Without
	 * this the button switches itself straight back off.
	 */
	private boolean selfToggle;

	/** Game tick of the last accepted toggle — the op event double-fires. */
	private int lastToggleTick = -1;

	private void toggle()
	{
		// One physical click delivers TWO op events (log showed activate +
		// deactivate ~a second apart, every time) — the filter switched
		// itself off before the player could see it. Anything within 3
		// ticks (~1.8s) of the last accepted toggle is the double-fire; a
		// deliberate re-click is slower than that.
		if (client.getTickCount() - lastToggleTick < 3)
		{
			log.info("bank filter: swallowed duplicate op event ({} ticks after toggle)",
				client.getTickCount() - lastToggleTick);
			return;
		}
		lastToggleTick = client.getTickCount();
		if (active)
		{
			log.info("bank filter: deactivated by button");
			deactivate();
			selfToggle = true;
			bankSearch.reset(true); // clears our programmatic search
		}
		else
		{
			log.info("bank filter: activated");
			active = true;
			background.setSpriteId(SpriteID.Miscgraphics3.UNKNOWN_BUTTON_SQUARE_SMALL_SELECTED);
			background.revalidate();
			// Start a REAL bank search for our keyword — the same varcs
			// typing sets, the same trick the core Bank Tags plugin uses.
			// (Answering getSearchingTagTab alone never made the layout
			// script call bankSearchFilter: log showed one probe, zero
			// filter passes, bank unchanged. The typed-search path is the
			// one proven to work.)
			client.setVarcIntValue(VarClientInt.INPUT_TYPE, InputType.SEARCH.getType());
			client.setVarcStrValue(VarClientStr.INPUT_TEXT, "ironman");
			selfToggle = true;
			bankSearch.layoutBank();
		}
	}

	/** Consume the "that was us" marker; true = ignore this search-toggle event. */
	public boolean consumeSelfToggle()
	{
		boolean was = selfToggle;
		selfToggle = false;
		return was;
	}

	/** Turn the filter off (e.g. the player clicked a real bank tab). */
	public void deactivate()
	{
		if (!active)
		{
			return;
		}
		active = false;
		if (background != null)
		{
			background.setSpriteId(SpriteID.Miscgraphics3.UNKNOWN_BUTTON_SQUARE_SMALL);
			background.revalidate();
		}
	}

	private static Widget createGraphic(Widget parent, String name, int spriteId,
		int width, int height, int x, int y)
	{
		Widget widget = parent.createChild(-1, WidgetType.GRAPHIC);
		widget.setOriginalWidth(width);
		widget.setOriginalHeight(height);
		widget.setOriginalX(x);
		widget.setOriginalY(y);
		widget.setSpriteId(spriteId);
		widget.setName(name);
		widget.setHasListener(true);
		widget.revalidate();
		return widget;
	}
}
