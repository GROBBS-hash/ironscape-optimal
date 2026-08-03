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
 * Outlines ONE item in the inventory — the thing the current sub-step
 * tells you to use ("Use house tab and run back to Thurgo" lights up
 * the carried Teleport to house). The plugin resolves the wanted item
 * id once per game tick; -1 = nothing to hint.
 */
@Singleton
public class InventoryItemHintOverlay extends WidgetItemOverlay
{
	private static final Color OUTLINE = new Color(0, 255, 255);

	private Supplier<Integer> itemIdSupplier = () -> -1;

	@Inject
	public InventoryItemHintOverlay()
	{
		showOnInventory();
	}

	public void setItemIdSupplier(Supplier<Integer> itemIdSupplier)
	{
		this.itemIdSupplier = itemIdSupplier;
	}

	@Override
	public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem)
	{
		Integer wanted = itemIdSupplier.get();
		if (wanted == null || wanted <= 0 || itemId != wanted)
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
