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

	/** Real bank widgets we moved/restyled last pass, to restore on the next. */
	private final java.util.Set<Widget> movedWidgets = new java.util.HashSet<>();

	private final net.runelite.client.game.ItemManager itemManager;

	@Inject
	public BankMissingSection(Client client,
		net.runelite.client.callback.ClientThread clientThread, ItemTracker itemTracker,
		net.runelite.client.game.ItemManager itemManager)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.itemTracker = itemTracker;
		this.itemManager = itemManager;
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
		// Undo last pass's cosmetic change to the REAL widgets we moved, in
		// case the native build doesn't reset it — a coin stack with no
		// number in the normal bank view would look like a bug.
		for (Widget widget : movedWidgets)
		{
			widget.setItemQuantityMode(1);
		}
		movedWidgets.clear();
		if (!show || sections.isEmpty())
		{
			return;
		}
		Widget container = client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (container == null)
		{
			return;
		}

		// Index the REAL bank item widgets by their item NAME — including
		// currently hidden ones (items on other tabs). Owned items are laid
		// out by MOVING these into our sections, so their withdraw menu
		// keeps working; only items you don't have are drawn as ghosts.
		// Name (not icon id) is the join key because the guide's names run
		// through the alias chain — "gp" must find the "Coins" stack, whose
		// id never matches the coin ICON id.
		Map<String, Widget> nativeByName = new LinkedHashMap<>();
		for (Widget child : container.getDynamicChildren())
		{
			if (child.getItemId() > 0 && child.getItemQuantity() > 0)
			{
				String name = itemManager.getItemComposition(itemManager.canonicalize(child.getItemId()))
					.getName().toLowerCase(java.util.Locale.ROOT);
				nativeByName.putIfAbsent(name, child);
			}
		}
		java.util.Set<Widget> kept = new java.util.HashSet<>();

		int y = 10;

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
				// Carried count for anything you're meant to WITHDRAW — the
				// line goes green as items land in your inventory and red
				// again if you bank them back. Unstackable gathers (130
				// planks) still count the bank; holding them all at once
				// was never the goal.
				int have = itemTracker.bankCountable(entry.getKey(), need)
					? itemTracker.countOf(entry.getKey())
					: itemTracker.carriedCountOf(entry.getKey());
				boolean met = have >= need;
				int x = FIRST_COLUMN_X + column * COLUMN_SPACING;

				// Banked? MOVE the real widget here — it stays clickable
				// (Withdraw-1/5/10/X/All), even for items from other tabs.
				// A section further down needing the same item falls back
				// to a ghost copy; the real one can only sit in one place.
				Widget banked = null;
				for (String alias : ItemTracker.aliases(entry.getKey()))
				{
					banked = nativeByName.get(alias);
					if (banked != null)
					{
						break;
					}
				}
				if (banked != null && !kept.contains(banked))
				{
					kept.add(banked);
					movedWidgets.add(banked);
					banked.setOriginalX(x);
					banked.setOriginalY(y);
					// The native stack number draws over the icon's top —
					// cramped next to our have/need line, so hide it (the
					// line already shows how many you own). Display only;
					// the withdraw menu is untouched. The next bank build
					// resets the mode, and so does this pass.
					banked.setItemQuantityMode(0);
					banked.setHidden(false);
					banked.revalidate();
				}
				else
				{
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
				}

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

		// NOW take the rest of the container over: hide everything the
		// native layout drew that we didn't move into a section — leftover
		// items and tab separators. Whatever bank tab is selected, the
		// filter view looks the same. The next build redraws the native
		// children, and this pass runs again after it, so nothing is
		// permanently lost.
		java.util.Set<Widget> ours = new java.util.HashSet<>(textPool);
		ours.addAll(iconPool);
		for (Widget child : container.getDynamicChildren())
		{
			if (!ours.contains(child) && !kept.contains(child) && !child.isHidden())
			{
				child.setHidden(true);
			}
		}

		// Fit the scroll area to OUR content (grow or shrink — a 946-item
		// tab leaves a huge stale scroll range behind). DEFERRED: update()
		// runs from onScriptPostFired — still inside the bank build
		// script's interpreter — and calling client.runScript from there
		// re-enters the script engine and hard-freezes the client (both
		// observed freezes: the filter button, and typing "bruh" in the
		// search). One tick later the stack is clean.
		int newScrollHeight = y + 8;
		if (container.getScrollHeight() != newScrollHeight)
		{
			clientThread.invokeLater(() -> {
				Widget items = client.getWidget(InterfaceID.Bankmain.ITEMS);
				if (items == null || items.getScrollHeight() == newScrollHeight)
				{
					return; // bank closed or already right in the meantime
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
