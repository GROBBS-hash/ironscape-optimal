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
	private final com.ironscape.IronscapeConfig config;
	private final net.runelite.client.game.ItemManager itemManager;

	private Supplier<java.util.Set<Integer>> itemIdsSupplier = java.util.Collections::emptySet;

	@Inject
	public InventoryItemHintOverlay(com.ironscape.IronscapeConfig config,
		net.runelite.client.game.ItemManager itemManager)
	{
		this.config = config;
		this.itemManager = itemManager;
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
		// Trace the ITEM, not a box around its slot. A rounded rectangle
		// says "this square matters"; Quest Helper draws the sprite's own
		// silhouette, which reads instantly as "this thing" (owner, after
		// watching QH outline a single garlic). getItemOutline is the same
		// API QH uses, so the two look like siblings rather than rivals.
		java.awt.image.BufferedImage outline =
			itemManager.getItemOutline(itemId, widgetItem.getQuantity(), config.hintColour());
		if (outline != null)
		{
			graphics.drawImage(outline, bounds.x, bounds.y, null);
			return;
		}
		// Some items have no cached sprite yet (the image loads async on
		// first sight). Fall back to the old box rather than nothing.
		graphics.setColor(config.hintColour());
		graphics.setStroke(new BasicStroke(2));
		graphics.drawRoundRect(bounds.x - 1, bounds.y - 1,
			bounds.width + 1, bounds.height + 1, 6, 6);
	}
}
