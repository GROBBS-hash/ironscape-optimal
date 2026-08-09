package com.ironscape.overlay;

import com.ironscape.items.ItemTracker;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.WidgetItemOverlay;

/**
 * Outlines the current step's items inside a SHOP — the bank filter's
 * trick, for the other place a step sends you to fetch things. "Buy
 * candle, 2 fishing rods, lobster pot" lights up exactly those rows in
 * the shop's stock.
 *
 * Matches by NAME, not item id, because the inventory overlay's id set
 * cannot work here: what you are in the shop to BUY is by definition not
 * in your inventory yet. Name matching runs through the tracker's own
 * alias/family rules, so "pickaxe" lights up whichever tier the shop
 * stocks. Per-frame composition lookups are memoised against the name
 * set they were resolved for.
 */
@Singleton
public class ShopItemHintOverlay extends WidgetItemOverlay
{
	private final ItemManager itemManager;
	private final com.ironscape.IronscapeConfig config;

	private Supplier<java.util.Set<String>> itemNamesSupplier = java.util.Collections::emptySet;
	/** itemId -> wanted?, valid only while memoFor is the live name set. */
	private final java.util.Map<Integer, Boolean> memo = new java.util.HashMap<>();
	private java.util.Set<String> memoFor;

	@Inject
	public ShopItemHintOverlay(ItemManager itemManager, com.ironscape.IronscapeConfig config)
	{
		this.itemManager = itemManager;
		this.config = config;
		// 300 = the shop's stock, 301 = your inventory beside it.
		showOnInterfaces(InterfaceID.SHOPMAIN, InterfaceID.SHOPSIDE);
	}

	public void setItemNamesSupplier(Supplier<java.util.Set<String>> itemNamesSupplier)
	{
		this.itemNamesSupplier = itemNamesSupplier;
	}

	@Override
	public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem)
	{
		java.util.Set<String> wanted = itemNamesSupplier.get();
		if (wanted == null || wanted.isEmpty())
		{
			return;
		}
		if (wanted != memoFor)
		{
			memo.clear();
			memoFor = wanted;
		}
		if (!memo.computeIfAbsent(itemId, id -> matches(id, wanted)))
		{
			return;
		}
		Rectangle bounds = widgetItem.getCanvasBounds();
		if (bounds == null)
		{
			return;
		}
		// Trace the item, same as the inventory hint — see that overlay for
		// why a box around the slot reads worse than the sprite itself.
		java.awt.image.BufferedImage outline =
			itemManager.getItemOutline(itemId, widgetItem.getQuantity(), config.hintColour());
		if (outline != null)
		{
			graphics.drawImage(outline, bounds.x, bounds.y, null);
			return;
		}
		graphics.setColor(config.hintColour());
		graphics.setStroke(new BasicStroke(2));
		graphics.drawRoundRect(bounds.x - 1, bounds.y - 1,
			bounds.width + 1, bounds.height + 1, 6, 6);
	}

	private boolean matches(int itemId, java.util.Set<String> wanted)
	{
		String name = itemManager.getItemComposition(itemId).getName();
		if (name == null || name.equals("null"))
		{
			return false;
		}
		for (String goalName : wanted)
		{
			if (ItemTracker.nameMatchesGoal(name, goalName))
			{
				return true;
			}
		}
		return false;
	}
}
