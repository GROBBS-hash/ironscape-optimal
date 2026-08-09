package com.ironscape.overlay;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.GameObject;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;

/**
 * Outlines scene OBJECTS the current sub-step is about — the copper and
 * iron rocks of "Mine 4 copper ore and 1 iron ore", the spirit tree of a
 * travel sub — the same way NpcTargetOverlay outlines NPCs. The plugin
 * rescans the scene once per game tick (matching live rocks only: a
 * depleted rock's impostor is plain "Rocks" and drops out).
 *
 * Drawn with ModelOutlineRenderer — the Quest Helper look: a crisp line
 * hugging the model's silhouette, not a convex-hull blob around it.
 */
@Singleton
public class ObjectTargetOverlay extends Overlay
{
	private final com.ironscape.IronscapeConfig config;

	private final ModelOutlineRenderer outlineRenderer;
	private final net.runelite.api.Client client;
	private final net.runelite.client.game.ItemManager itemManager;

	private Supplier<List<GameObject>> objectsSupplier = Collections::emptyList;

	/** Progress label floated over each object ("1,234 to go"); null = none. */
	private Supplier<String> labelSupplier = () -> null;

	/** Goal item id floated over each object (the chest wears the milk); -1 = none. */
	private Supplier<Integer> itemIconSupplier = () -> -1;
	private int cachedItemId = -1;
	private java.awt.image.BufferedImage cachedItemImage;

	@Inject
	public ObjectTargetOverlay(ModelOutlineRenderer outlineRenderer,
		net.runelite.api.Client client, net.runelite.client.game.ItemManager itemManager,
		com.ironscape.IronscapeConfig config)
	{
		this.config = config;
		this.outlineRenderer = outlineRenderer;
		this.client = client;
		this.itemManager = itemManager;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	public void setItemIconSupplier(Supplier<Integer> itemIconSupplier)
	{
		this.itemIconSupplier = itemIconSupplier;
	}

	/** Live scene objects to outline; empty = hidden. */
	public void setObjectsSupplier(Supplier<List<GameObject>> objectsSupplier)
	{
		this.objectsSupplier = objectsSupplier;
	}

	public void setLabelSupplier(Supplier<String> labelSupplier)
	{
		this.labelSupplier = labelSupplier;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		List<GameObject> objects = objectsSupplier.get();
		if (objects == null || objects.isEmpty())
		{
			return null;
		}
		String label = labelSupplier.get();
		Integer itemId = itemIconSupplier.get();
		if (itemId != null && itemId > 0 && itemId != cachedItemId)
		{
			cachedItemId = itemId;
			cachedItemImage = itemManager.getImage(itemId);
		}
		// The in-game bold font in white over a black shadow — the same
		// treatment RuneLite's own overlays use; small cyan text vanished
		// against stall produce.
		graphics.setFont(net.runelite.client.ui.FontManager.getRunescapeBoldFont());
		for (GameObject object : objects)
		{
			outlineRenderer.drawOutline(object, 2, config.hintColour(), 2);
			if (itemId != null && itemId > 0 && cachedItemImage != null)
			{
				net.runelite.api.Point at = net.runelite.api.Perspective.getCanvasImageLocation(
					client, object.getLocalLocation(), cachedItemImage, 220);
				if (at != null)
				{
					graphics.drawImage(cachedItemImage, at.getX(), at.getY(), null);
				}
			}
			if (label != null && !label.isEmpty())
			{
				net.runelite.api.Point at = object.getCanvasTextLocation(graphics, label, 120);
				if (at != null)
				{
					graphics.setColor(Color.BLACK);
					graphics.drawString(label, at.getX() + 1, at.getY() + 1);
					graphics.setColor(Color.WHITE);
					graphics.drawString(label, at.getX(), at.getY());
				}
			}
		}
		return null;
	}
}
