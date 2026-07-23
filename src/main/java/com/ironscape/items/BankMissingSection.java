package com.ironscape.items;

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
 * The Quest Helper-style bank view: while the filter is active the
 * native grid is blanked (see the plugin's bankSearchFilter callback)
 * and every upcoming step renders as its own section — a step-title
 * header, then ALL of that step's items with a green/red "have/need"
 * count under each icon; items still missing are ghosted. Rebuilt after
 * every bank layout pass while the filter is active; hidden otherwise.
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
	/** Icon (32) + have/need line (12) + breathing room. */
	private static final int ROW_SPACING = 52;
	private static final int COLUMNS = 8;

	private static final int HEADER_COLOR = 0xff981f; // RuneScape orange
	private static final int COUNT_MET_COLOR = 0x2ecc40; // green: have >= need
	private static final int COUNT_SHORT_COLOR = 0xff6060; // red: still missing

	private final Client client;
	private final net.runelite.client.callback.ClientThread clientThread;
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
	public BankMissingSection(Client client,
		net.runelite.client.callback.ClientThread clientThread, ItemTracker itemTracker)
	{
		this.client = client;
		this.clientThread = clientThread;
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
				int need = entry.getValue();
				int have = itemTracker.countOf(entry.getKey());
				boolean met = have >= need;
				int x = FIRST_COLUMN_X + column * COLUMN_SPACING;

				Widget icon = reuse(container, iconPool, iconsUsed++, WidgetType.GRAPHIC);
				icon.setItemId(itemId);
				icon.setItemQuantity(need);
				icon.setItemQuantityMode(0); // the have/need line says it all
				icon.setOriginalX(x);
				icon.setOriginalY(y);
				icon.setOriginalWidth(36);
				icon.setOriginalHeight(32);
				icon.setOpacity(met ? 0 : 120); // ghosted while still missing
				icon.setName("<col=ff9040>" + entry.getKey() + "</col>");
				icon.setHidden(false);
				icon.revalidate();

				// Quest Helper-style count under the icon: "have/need".
				Widget count = reuse(container, textPool, textsUsed++, WidgetType.TEXT);
				count.setText(compact(have) + "/" + compact(need));
				count.setTextColor(met ? COUNT_MET_COLOR : COUNT_SHORT_COLOR);
				count.setFontId(FontID.PLAIN_11);
				count.setTextShadowed(true);
				count.setOriginalX(x - 4);
				count.setOriginalY(y + 33);
				count.setOriginalWidth(COLUMN_SPACING - 2);
				count.setOriginalHeight(12);
				count.setHidden(false);
				count.revalidate();

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

		// Grow the scroll area so the sections are reachable. DEFERRED:
		// update() runs from onScriptPostFired — still inside the bank
		// build script's interpreter — and calling client.runScript from
		// there re-enters the script engine and hard-freezes the client
		// (both observed freezes: the filter button, and typing "bruh" in
		// the search; plain searches were safe only because this block
		// never ran with no ghost sections). One tick later the stack is
		// clean.
		if (container.getScrollHeight() < y + 8)
		{
			int newScrollHeight = y + 8;
			clientThread.invokeLater(() -> {
				Widget items = client.getWidget(InterfaceID.Bankmain.ITEMS);
				if (items == null || items.getScrollHeight() >= newScrollHeight)
				{
					return; // bank closed or rebuilt taller in the meantime
				}
				items.setScrollHeight(newScrollHeight);
				client.runScript(ScriptID.UPDATE_SCROLLBAR,
					InterfaceID.Bankmain.SCROLLBAR, InterfaceID.Bankmain.ITEMS,
					items.getScrollY());
				items.revalidateScroll();
			});
		}
	}

	/** 25321 -> "25.3k": the counts must fit under a 36px icon. */
	private static String compact(int n)
	{
		if (n < 10_000)
		{
			return Integer.toString(n);
		}
		if (n < 1_000_000)
		{
			return (n / 100) / 10.0 + "k";
		}
		return (n / 100_000) / 10.0 + "m";
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
