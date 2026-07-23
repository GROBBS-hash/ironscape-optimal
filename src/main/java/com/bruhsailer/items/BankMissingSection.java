package com.bruhsailer.items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.FontID;
import net.runelite.api.ScriptID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetType;

/**
 * The Quest Helper-style twist on the bank filter: below the (native,
 * filtered) grid of items you DO have, append a section of ghost icons
 * for the items upcoming steps need that are NOT in your bank — the
 * things you still have to buy, gather or quest for. Rebuilt after every
 * bank layout pass while the filter is active; hidden otherwise.
 *
 * All of it must run on the client thread (the plugin calls update()
 * from the BANKMAIN_BUILD script post-fire).
 */
@Slf4j
@Singleton
public class BankMissingSection
{
	/** Native bank grid geometry: 8 columns of 36x32 icons. */
	private static final int FIRST_COLUMN_X = 51;
	private static final int COLUMN_SPACING = 48;
	private static final int ROW_SPACING = 40;
	private static final int COLUMNS = 8;

	private static final int HEADER_COLOR = 0xff981f; // RuneScape orange

	private final Client client;
	private final ItemTracker itemTracker;

	/** Widgets we created inside the bank items container, for reuse/hiding. */
	private final List<Widget> created = new ArrayList<>();

	@Inject
	public BankMissingSection(Client client, ItemTracker itemTracker)
	{
		this.client = client;
		this.itemTracker = itemTracker;
	}

	/**
	 * Show (or hide) the section. Called after every bank build.
	 *
	 * @param missing display name -> quantity still needed, in guide order
	 */
	public void update(boolean show, Map<String, Integer> missing)
	{
		for (Widget widget : created)
		{
			widget.setHidden(true);
		}
		if (!show || missing.isEmpty())
		{
			return;
		}
		Widget container = client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (container == null)
		{
			return;
		}

		// Start below the last visible native item.
		int y = 0;
		for (Widget child : container.getDynamicChildren())
		{
			if (!child.isHidden() && child.getItemId() > 0)
			{
				y = Math.max(y, child.getOriginalY() + child.getOriginalHeight());
			}
		}
		y += 10;

		int used = 0;
		Widget header = reuse(container, used++, WidgetType.TEXT);
		header.setText("Still needed — not in your bank:");
		header.setTextColor(HEADER_COLOR);
		header.setFontId(FontID.PLAIN_11);
		header.setTextShadowed(true);
		header.setOriginalX(FIRST_COLUMN_X);
		header.setOriginalY(y);
		header.setOriginalWidth(350);
		header.setOriginalHeight(14);
		header.setHidden(false);
		header.revalidate();
		y += 18;

		int column = 0;
		for (Map.Entry<String, Integer> entry : missing.entrySet())
		{
			int itemId = itemTracker.iconIdFor(entry.getKey());
			if (itemId <= 0)
			{
				continue; // untradeable/unknown name: no icon to show
			}
			Widget icon = reuse(container, used++, WidgetType.GRAPHIC);
			icon.setItemId(itemId);
			icon.setItemQuantity(entry.getValue());
			icon.setItemQuantityMode(1); // always show the needed count
			icon.setOriginalX(FIRST_COLUMN_X + column * COLUMN_SPACING);
			icon.setOriginalY(y);
			icon.setOriginalWidth(36);
			icon.setOriginalHeight(32);
			icon.setOpacity(120); // ghosted: you don't have it yet
			icon.setName("<col=ff9040>" + entry.getKey() + "</col>");
			icon.setHidden(false);
			icon.revalidate();
			if (++column == COLUMNS)
			{
				column = 0;
				y += ROW_SPACING;
			}
		}
		if (column > 0)
		{
			y += ROW_SPACING;
		}

		// Grow the scroll area so the section is reachable.
		if (container.getScrollHeight() < y + 8)
		{
			container.setScrollHeight(y + 8);
			client.runScript(ScriptID.UPDATE_SCROLLBAR,
				InterfaceID.Bankmain.SCROLLBAR, InterfaceID.Bankmain.ITEMS,
				container.getScrollY());
			container.revalidateScroll();
		}
	}

	/** Reuse our n-th created widget, or create it on first need. */
	private Widget reuse(Widget container, int index, int type)
	{
		if (index < created.size())
		{
			return created.get(index);
		}
		Widget widget = container.createChild(-1, type);
		created.add(widget);
		return widget;
	}

	/** The bank interface was rebuilt from scratch: our widgets are gone. */
	public void invalidate()
	{
		created.clear();
	}
}
