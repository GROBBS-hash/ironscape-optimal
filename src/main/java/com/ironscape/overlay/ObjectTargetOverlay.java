package com.ironscape.overlay;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.GameObject;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Outlines scene OBJECTS the current sub-step is about — the copper and
 * iron rocks of "Mine 4 copper ore and 1 iron ore" — the same way
 * NpcTargetOverlay outlines NPCs. The plugin rescans the scene once per
 * game tick (matching live rocks only: a depleted rock's impostor is
 * plain "Rocks" and drops out); the hulls are re-read per frame so the
 * outline tracks camera movement.
 */
@Singleton
public class ObjectTargetOverlay extends Overlay
{
	private static final Color OUTLINE = new Color(0, 255, 255);
	private static final Color OUTLINE_FILL = new Color(0, 255, 255, 25);

	private Supplier<List<GameObject>> objectsSupplier = Collections::emptyList;

	@Inject
	public ObjectTargetOverlay()
	{
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	/** Live scene objects to outline; empty = hidden. */
	public void setObjectsSupplier(Supplier<List<GameObject>> objectsSupplier)
	{
		this.objectsSupplier = objectsSupplier;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		List<GameObject> objects = objectsSupplier.get();
		if (objects == null || objects.isEmpty())
		{
			return null;
		}
		for (GameObject object : objects)
		{
			Shape hull = object.getConvexHull();
			if (hull == null)
			{
				continue;
			}
			graphics.setColor(OUTLINE_FILL);
			graphics.fill(hull);
			graphics.setColor(OUTLINE);
			graphics.setStroke(new BasicStroke(2));
			graphics.draw(hull);
		}
		return null;
	}
}
