package com.ironscape.items;

import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.SpriteID;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.plugins.bank.BankSearch;

/**
 * The clickable button inside the bank interface that toggles the
 * guide-items view. The button only flips a flag and relayouts: while
 * active, BankMissingSection hides the native layout's children after
 * every bank build and draws per-step sections instead — no search or
 * tab state is touched, so the view is identical from any tab.
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
	private final net.runelite.client.callback.ClientThread clientThread;
	private final BankSearch bankSearch;

	/** True while the bank is showing only guide items. */
	@Getter
	private boolean active;

	private Widget background;

	@Inject
	public BankFilterButton(Client client,
		net.runelite.client.callback.ClientThread clientThread, BankSearch bankSearch)
	{
		this.client = client;
		this.clientThread = clientThread;
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
			deactivate(true);
		}
		else
		{
			log.info("bank filter: activated");
			active = true;
			background.setSpriteId(SpriteID.Miscgraphics3.UNKNOWN_BUTTON_SQUARE_SMALL_SELECTED);
			background.revalidate();
			// No search state is touched — the filter view simply hides the
			// native layout's children after each build (BankMissingSection)
			// and draws its own sections, so it works the same from any tab.
			//
			// invokeLater is LOAD-BEARING: this op listener runs INSIDE the
			// click's clientscript, and layoutBank() runs the bank build
			// script SYNCHRONOUSLY — re-entering the script engine from a
			// script froze the whole client. Deferred one tick, the build
			// runs from a clean stack.
			clientThread.invokeLater(() -> {
				if (active)
				{
					bankSearch.layoutBank();
				}
			});
		}
	}

	/**
	 * Turn the filter off.
	 *
	 * @param clearSearch also reset the bank search and relayout — true
	 *                    when the bank should snap back to its normal view
	 *                    (button toggled off, player clicked a real tab);
	 *                    false when the player just opened their OWN
	 *                    search, which must be left alone (their search
	 *                    interaction already relayouts).
	 */
	public void deactivate(boolean clearSearch)
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
		if (clearSearch)
		{
			// BankSearch.reset defers to the client thread internally, so
			// this is safe from any event context.
			bankSearch.reset(true);
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
