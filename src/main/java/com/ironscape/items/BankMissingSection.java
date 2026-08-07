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
 * from BANKMAIN_FINISHBUILDING, deferred to the end of the tick).
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
	 * Every widget WE added to the bank container, in creation order, so
	 * the next pass can tear them down. No pooling: see create().
	 */
	private final java.util.Set<Widget> addedWidgets = new java.util.LinkedHashSet<>();

	/** Real bank widgets we moved/restyled last pass, to restore on the next. */
	private final java.util.Set<Widget> movedWidgets = new java.util.HashSet<>();

	/** Where the bank originally had each moved widget: widget -> {x, y}. */
	private final Map<Widget, int[]> movedHome = new java.util.HashMap<>();

	/** Native widgets WE hid, so turning the filter off can show them again. */
	private final java.util.Set<Widget> hiddenByUs = new java.util.LinkedHashSet<>();

	/** Last logged pass shape, so the diagnosis line only prints on change. */
	private String lastShape;

	/** Last per-item branch record, so only CHANGES print. */
	private String lastTrace;

	/** Real bank items the last COMPLETED pass saw (see the not-ready guard). */
	private int lastNativeCount;

	/** Bank item widgets carrying an actual item right now. */
	private static int countPopulatedItems(Widget container)
	{
		int populated = 0;
		for (Widget child : container.getDynamicChildren())
		{
			if (child != null && child.getItemId() > 0 && child.getItemQuantity() > 0)
			{
				populated++;
			}
		}
		return populated;
	}

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
		// NOT-READY GUARD, and it must come BEFORE anything below touches
		// the pools. BANKMAIN_BUILD fires TWICE around a withdrawal: the
		// first time the child array has already been resized but the item
		// widgets carry no itemId yet. Laying out from that snapshot found
		// zero real items and drew EVERY icon as a ghost for one frame —
		// the "icons move/disappear as I withdraw" reports. The owner's
		// captured log, one withdrawal:
		//   pass: 9 moved,  2 ghosts, 1461 children
		//   pools went stale (container resized) — recreating
		//   pass: 0 moved, 11 ghosts, 1438 children   <-- this frame
		//   pass: 10 moved, 1 ghosts, 1466 children
		// Skipping leaves the previous layout alone and the very next
		// build (milliseconds later) lays out properly. Guarded on having
		// seen items before, so a genuinely EMPTY bank still renders.
		Widget itemContainer = client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (show && !sections.isEmpty() && itemContainer != null
			&& lastNativeCount > 0 && countPopulatedItems(itemContainer) == 0)
		{
			log.info("bank filter: container not populated yet — keeping the last pass");
			return;
		}

		// Tear down last pass's widgets rather than re-styling them.
		if (itemContainer != null)
		{
			removeAddedWidgets(itemContainer);
		}
		// Put the NATIVE grid back exactly as we found it: original x/y for
		// every widget we moved, and un-hide everything we hid.
		//
		// This used to be unnecessary — we ran DURING the bank build, so the
		// client's own layout overwrote us afterwards ("nothing is
		// permanently lost"). Running at TICK END means our changes now
		// land last and STICK: with the filter off, reopening the bank
		// showed only the step's items, still sitting in our layout, and
		// nothing else. Restoring is our job now.
		for (Widget widget : movedWidgets)
		{
			widget.setItemQuantityMode(1);
			int[] home = movedHome.get(widget);
			if (home != null)
			{
				widget.setOriginalX(home[0]);
				widget.setOriginalY(home[1]);
				widget.revalidate();
			}
		}
		movedWidgets.clear();
		movedHome.clear();
		if (!hiddenByUs.isEmpty())
		{
			for (Widget widget : hiddenByUs)
			{
				try
				{
					widget.setHidden(false);
					widget.revalidate();
				}
				catch (RuntimeException ignored)
				{
					// destroyed by the client's own rebuild
				}
			}
			hiddenByUs.clear();
		}
		if (!show || sections.isEmpty())
		{
			if (itemContainer != null)
			{
				itemContainer.revalidate();
			}
			return;
		}
		Widget container = itemContainer;
		if (container == null)
		{
			return;
		}

		// The stale-pool detection that used to live here is GONE: it only
		// existed because we reused widgets across passes and the bank's
		// rebuild could drop them out from under us. Building fresh every
		// pass makes the whole question moot.
		java.util.Set<Widget> liveChildren = new java.util.HashSet<>();
		for (Widget child : container.getDynamicChildren())
		{
			if (child != null)
			{
				liveChildren.add(child);
			}
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
		// How many real bank items this pass could see — the not-ready guard
		// above compares against it, so "0 now, plenty last time" reads as
		// a half-built container rather than an empty bank.
		lastNativeCount = nativeByName.size();
		java.util.Set<Widget> kept = new java.util.HashSet<>();
		// Per-item branch record, logged when it CHANGES: "name=moved" (the
		// real bank widget), "=ghost" (our drawn copy), "=skip" (no icon id,
		// so the slot collapses), "!" = still short. Two blind fixes went
		// past this bug; the log names the item and the branch now.
		List<String> trace = new java.util.ArrayList<>();

		int y = 10;

		int textsUsed = 0;
		int iconsUsed = 0;
		for (Section section : sections)
		{
			if (section.items.isEmpty())
			{
				continue;
			}
			// Full step text, word-wrapped — a cut-off "Buy 3 buckets and 1
			// bucket pack from the gene..." leaves the player guessing.
			for (String line : wrapTitle(section.title))
			{
				Widget header = create(container, WidgetType.TEXT);
				textsUsed++;
				header.setText(line);
				header.setTextColor(HEADER_COLOR);
				header.setFontId(FontID.PLAIN_11);
				header.setTextShadowed(true);
				header.setOriginalX(FIRST_COLUMN_X);
				header.setOriginalY(y);
				header.setOriginalWidth(380);
				header.setOriginalHeight(14);
				header.setHidden(false);
				header.revalidate();
				y += 15;
			}
			y += 2;

			int column = 0;
			boolean anyIcon = false;
			for (Map.Entry<String, Integer> entry : section.items.entrySet())
			{
				int itemId = itemTracker.iconIdFor(entry.getKey());
				if (itemId <= 0)
				{
					// SKIPPED entirely — no icon, no count, and no column
					// taken, so every later item slides up a slot. If this
					// ever flips mid-session it IS the "icons move and go
					// invisible" report; the trace below names it.
					trace.add(entry.getKey() + "=skip");
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
				if (banked == null)
				{
					// The alias map is exact names only — "axe" must still
					// find YOUR axe whatever its tier, and "beads" any bead
					// colour. Full matching stack (substitutes, families,
					// canonical drift), first bank-order hit wins.
					for (Map.Entry<String, Widget> nativeEntry : nativeByName.entrySet())
					{
						if (!kept.contains(nativeEntry.getValue())
							&& ItemTracker.nameMatchesGoal(nativeEntry.getKey(), entry.getKey()))
						{
							banked = nativeEntry.getValue();
							break;
						}
					}
				}
				// You cannot MOVE the bank widget of an item you own none of.
				// The loose fallback above (families, canonical drift) scans
				// every bank item and takes the first fuzzy hit, then CLAIMS
				// it via `kept` — so "plague sample", owned 0, was stealing a
				// real stack's widget from the item that needed it. Bank child
				// order changes on every withdrawal, so a different item got
				// robbed each pass: that is the shuffling-and-vanishing icons.
				// Owning none means ghost, always.
				if (banked != null && itemTracker.countOf(entry.getKey()) <= 0)
				{
					banked = null;
				}
				trace.add(entry.getKey()
					+ (banked != null && !kept.contains(banked) ? "=moved" : "=ghost")
					+ (met ? "" : "!"));
				if (banked != null && !kept.contains(banked))
				{
					kept.add(banked);
					movedWidgets.add(banked);
					// Remember where the bank had it, so turning the filter
					// off can put it back.
					movedHome.putIfAbsent(banked,
						new int[]{banked.getOriginalX(), banked.getOriginalY()});
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
					Widget icon = create(container, WidgetType.GRAPHIC);
					iconsUsed++;
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
				Widget count = create(container, WidgetType.TEXT);
				textsUsed++;
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

		// Self-diagnosis for the vanishing-icon reports: one line per pass
		// whenever the composition changes — if icons vanish WITHOUT a new
		// line, the bank redrew without this pass running at all.
		String shape = sections.size() + " sections, " + kept.size() + " moved, "
			+ iconsUsed + " ghosts, " + textsUsed + " texts, "
			+ liveChildren.size() + " container children, "
			+ lastNativeCount + " native items";
		if (!shape.equals(lastShape))
		{
			lastShape = shape;
			log.info("bank filter pass: {}", shape);
		}
		String traceLine = String.join(" ", trace);
		if (!traceLine.equals(lastTrace))
		{
			lastTrace = traceLine;
			log.info("bank filter items: {}", traceLine);
		}

		// NOW take the rest of the container over: hide everything the
		// native layout drew that we didn't move into a section — leftover
		// items and tab separators. Whatever bank tab is selected, the
		// filter view looks the same. The next build redraws the native
		// children, and this pass runs again after it, so nothing is
		// permanently lost.
		java.util.Set<Widget> ours = addedWidgets;
		for (Widget child : container.getDynamicChildren())
		{
			if (!ours.contains(child) && !kept.contains(child) && !child.isHidden())
			{
				child.setHidden(true);
				hiddenByUs.add(child); // so the filter can be undone
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

	/** Chars per header line at PLAIN_11 across the item area's width. */
	private static final int HEADER_WRAP_CHARS = 52;
	private static final int HEADER_MAX_LINES = 3;

	/** Greedy word-wrap; the (rare) overflow past 3 lines gets "...". */
	private static java.util.List<String> wrapTitle(String title)
	{
		java.util.List<String> lines = new ArrayList<>();
		String rest = title.trim();
		while (!rest.isEmpty() && lines.size() < HEADER_MAX_LINES)
		{
			if (rest.length() <= HEADER_WRAP_CHARS)
			{
				lines.add(rest);
				return lines;
			}
			int cut = rest.lastIndexOf(' ', HEADER_WRAP_CHARS);
			if (cut <= 0)
			{
				cut = HEADER_WRAP_CHARS; // one giant word; hard-split it
			}
			if (lines.size() == HEADER_MAX_LINES - 1)
			{
				lines.add(rest.substring(0, Math.min(rest.length(), HEADER_WRAP_CHARS - 3)) + "...");
				return lines;
			}
			lines.add(rest.substring(0, cut).trim());
			rest = rest.substring(cut).trim();
		}
		return lines;
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
	/**
	 * A FRESH child every pass — Quest Helper's lifecycle, and the answer
	 * to the icons that kept blanking. The old version reused pooled
	 * widgets by index, so each one carried whatever the previous pass had
	 * set on it: opacity above all. A slot that held a missing item
	 * (opacity 120) and was then reused for an owned one relied on
	 * setOpacity(0) restoring it, and in practice the icon simply went
	 * blank. QH never re-styles a used widget; it throws them away and
	 * builds again, which is why its bank tab is stable.
	 */
	private Widget create(Widget container, int type)
	{
		Widget widget = container.createChild(-1, type);
		addedWidgets.add(widget);
		return widget;
	}

	/**
	 * Drop last pass's widgets before building again (QH's
	 * removeAddedWidgets). Only the TRAILING run that is entirely ours is
	 * truncated — QH truncates to a child count captured once, which would
	 * delete real bank slots for us if the container ever grew.
	 */
	private void removeAddedWidgets(Widget container)
	{
		if (addedWidgets.isEmpty())
		{
			return;
		}
		Widget[] children = container.getChildren();
		if (children != null)
		{
			int keep = children.length;
			while (keep > 0 && addedWidgets.contains(children[keep - 1]))
			{
				keep--;
			}
			if (keep < children.length)
			{
				container.setChildren(java.util.Arrays.copyOf(children, keep));
				container.revalidate();
			}
		}
		// Anything of ours the bank's own rebuild already dropped, or that
		// isn't in the trailing run, just gets hidden.
		for (Widget widget : addedWidgets)
		{
			try
			{
				widget.setHidden(true);
			}
			catch (RuntimeException ignored)
			{
				// already destroyed by the client's rebuild
			}
		}
		addedWidgets.clear();
	}

	/** The bank interface was rebuilt from scratch: our widgets are gone. */
	public void invalidate()
	{
		addedWidgets.clear();
		// The client rebuilt these too — the recorded positions and hidden
		// flags belong to widgets that no longer exist.
		movedWidgets.clear();
		movedHome.clear();
		hiddenByUs.clear();
	}
}
