package com.ironscape.overlay;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.ui.overlay.WidgetItemOverlay;

/**
 * Outlines the teleport ITEM the route hint has chosen — a diary cloak,
 * a glory, a tablet — and labels it with the option to pick.
 *
 * Separate from {@link InventoryItemHintOverlay} on purpose: that one says
 * "this item belongs to the step you are doing", this one says "this is
 * how to get there". Sharing an overlay would have made the two meanings
 * indistinguishable on screen, which is the complaint that produced the
 * highlight-colour setting in the first place.
 *
 * Shows on equipment as well as inventory, because a worn cloak is the
 * common case and the reason this exists at all.
 */
@Singleton
public class TeleportItemHintOverlay extends WidgetItemOverlay
{
	private final com.ironscape.IronscapeConfig config;
	private final net.runelite.client.game.ItemManager itemManager;

	/** The chosen teleport, or null when the hint is not offering one. */
	private Supplier<com.ironscape.travel.TeleportItems.Entry> entrySupplier = () -> null;

	@Inject
	public TeleportItemHintOverlay(com.ironscape.IronscapeConfig config,
		net.runelite.client.game.ItemManager itemManager)
	{
		this.config = config;
		this.itemManager = itemManager;
		showOnInventory();
		showOnEquipment();
	}

	public void setEntrySupplier(Supplier<com.ironscape.travel.TeleportItems.Entry> entrySupplier)
	{
		this.entrySupplier = entrySupplier;
	}

	@Override
	public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem)
	{
		if (!config.showTeleportHints())
		{
			return;
		}
		com.ironscape.travel.TeleportItems.Entry entry = entrySupplier.get();
		if (entry == null)
		{
			return;
		}
		boolean wanted = false;
		for (int id : entry.getItemIds())
		{
			if (id == itemId)
			{
				wanted = true;
				break;
			}
		}
		if (!wanted)
		{
			return;
		}
		Rectangle bounds = widgetItem.getCanvasBounds();
		if (bounds == null)
		{
			return;
		}
		Color colour = config.hintColour();
		java.awt.image.BufferedImage outline =
			itemManager.getItemOutline(itemId, widgetItem.getQuantity(), colour);
		if (outline != null)
		{
			graphics.drawImage(outline, bounds.x, bounds.y, null);
		}
		else
		{
			// The sprite loads async and is missing on first sight; a box
			// beats drawing nothing at the moment you are looking for it.
			graphics.setColor(colour);
			graphics.setStroke(new BasicStroke(2));
			graphics.drawRoundRect(bounds.x - 1, bounds.y - 1,
				bounds.width + 1, bounds.height + 1, 6, 6);
		}
		drawLabel(graphics, bounds, entry.getDisplay(), colour);
	}

	/**
	 * The destination, under the item. An Ardougne cloak has five, so the
	 * outline alone only tells you half of what to do.
	 *
	 * Drawn with a shadow rather than a filled box: item sprites sit on a
	 * busy inventory background and plain text on it is unreadable, while a
	 * box large enough to hold this label would cover the slots either side.
	 */
	private void drawLabel(Graphics2D graphics, Rectangle bounds, String text, Color colour)
	{
		FontMetrics metrics = graphics.getFontMetrics();
		int width = metrics.stringWidth(text);
		// Keep the label on screen: nudge left when the slot is near the
		// right edge of the inventory rather than letting it run off.
		int x = bounds.x + (bounds.width / 2) - (width / 2);
		x = Math.max(2, x);
		int y = bounds.y + bounds.height + metrics.getAscent();
		graphics.setColor(Color.BLACK);
		graphics.drawString(text, x + 1, y + 1);
		graphics.setColor(colour);
		graphics.drawString(text, x, y);
	}
}
