package com.bruhsailer.items;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * filtered) grid of items you DO have, append per-step sections of ghost
 * icons for the items upcoming steps need that are NOT in your bank —
 * the things you still have to buy, gather or quest for, grouped under
 * the step that needs them. Rebuilt after every bank layout pass while
 * the filter is active; hidden otherwise.
 *
 * All of it must run on the client thread (the plugin calls update()
 * from the BANKMAIN_BUILD script post-fire).
 */
@Slf4j
@Singleton
public class BankMissingSection
{
	/** One step's worth of still-missing items, in guide order. */
	public static class Section
	{
		public final String title;
		public final Map<String, Integer> items = new LinkedHashMap<>();

		public Section(String title)
		{
			this.title = title;
		}
	}

	/** Native bank grid geometry: 8 columns of 36x32 icons. */
	private static final int FIRST_COLUMN_X = 51;
	private static final int COLUMN_SPACING = 48;
	private static final int ROW_SPACING = 40;
	private static final int COLUMNS = 8;

	private static final int HEADER_COLOR = 0xff981f; // RuneScape orange

	private final Client client;
	private final ItemTracker itemTracker;

	/**
	 * Widgets we created inside the bank items container, for reuse.
	 * Separate pools per widget type — a child's type is fixed at
	 * creation, so TEXT and GRAPHIC widgets can't swap roles between
	 * rebuilds.
	 */
	private final List<Widget> textPool = new ArrayList<>();
	private final List<Widget> iconPool = new ArrayList<>();

	@Inject
	public BankMissingSection(Client client, ItemTracker itemTracker)
	{
		this.client = client;
		this.itemTracker = itemTracker;
	}

	/**
	 * Show (or hide) the sections. Called after every bank build.
	 */
	public void update(boolean show, List<Section> sections)
	{
		for (Widget widget : textPool)
		{
			widget.setHidden(true);
		}
		for (Widget widget : iconPool)
		{
			widget.setHidden(true);
		}
		if (!show || sections.isEmpty())
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

		int textsUsed = 0;
		int iconsUsed = 0;
		for (Section section : sections)
		{
			if (section.items.isEmpty())
			{
				continue;
			}
			Widget header = reuse(container, textPool, textsUsed++, WidgetType.TEXT);
			header.setText(section.title);
			header.setTextColor(HEADER_COLOR);
			header.setFontId(FontID.PLAIN_11);
			header.setTextShadowed(true);
			header.setOriginalX(FIRST_COLUMN_X);
			header.setOriginalY(y);
			header.setOriginalWidth(380);
			header.setOriginalHeight(14);
			header.setHidden(false);
			header.revalidate();
			y += 17;

			int column = 0;
			boolean anyIcon = false;
			for (Map.Entry<String, Integer> entry : section.items.entrySet())
			{
				int itemId = itemTracker.iconIdFor(entry.getKey());
				if (itemId <= 0)
				{
					continue; // untradeable/unknown name: no icon to show
				}
				Widget icon = reuse(container, iconPool, iconsUsed++, WidgetType.GRAPHIC);
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
				anyIcon = true;
				if (++column == COLUMNS)
				{
					column = 0;
					y += ROW_SPACING;
				}
			}
			if (column > 0 || !anyIcon)
			{
				y += column > 0 ? ROW_SPACING : 0;
			}
			y += 6; // breathing room between sections
		}

		// Grow the scroll area so the sections are reachable.
		if (container.getScrollHeight() < y + 8)
		{
			container.setScrollHeight(y + 8);
			client.runScript(ScriptID.UPDATE_SCROLLBAR,
				InterfaceID.Bankmain.SCROLLBAR, InterfaceID.Bankmain.ITEMS,
				container.getScrollY());
			container.revalidateScroll();
		}
	}

	/** Reuse the pool's n-th widget, or create it on first need. */
	private static Widget reuse(Widget container, List<Widget> pool, int index, int type)
	{
		if (index < pool.size())
		{
			return pool.get(index);
		}
		Widget widget = container.createChild(-1, type);
		pool.add(widget);
		return widget;
	}

	/** The bank interface was rebuilt from scratch: our widgets are gone. */
	public void invalidate()
	{
		textPool.clear();
		iconPool.clear();
	}
}
