package com.ironscape.overlay;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.ui.overlay.WidgetItemOverlay;

/**
 * Outlines the inventory items the CURRENT step is about — its tools,
 * ingredients and tabs ("Use house tab..." lights up the carried
 * Teleport to house; "Make redberry pie" lights up the flour, water,
 * redberries and dish). The plugin resolves the wanted slot item ids
 * once per game tick; empty = nothing to hint.
 */
@Singleton
public class InventoryItemHintOverlay extends WidgetItemOverlay
{
	private static final Color OUTLINE = new Color(0, 255, 255);

	private Supplier<java.util.Set<Integer>> itemIdsSupplier = java.util.Collections::emptySet;

	@Inject
	public InventoryItemHintOverlay()
	{
		showOnInventory();
	}

	public void setItemIdsSupplier(Supplier<java.util.Set<Integer>> itemIdsSupplier)
	{
		this.itemIdsSupplier = itemIdsSupplier;
	}

	@Override
	public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem)
	{
		java.util.Set<Integer> wanted = itemIdsSupplier.get();
		if (wanted == null || !wanted.contains(itemId))
		{
			return;
		}
		Rectangle bounds = widgetItem.getCanvasBounds();
		if (bounds == null)
		{
			return;
		}
		graphics.setColor(OUTLINE);
		graphics.setStroke(new BasicStroke(2));
		graphics.drawRoundRect(bounds.x - 1, bounds.y - 1,
			bounds.width + 1, bounds.height + 1, 6, 6);
	}
}
