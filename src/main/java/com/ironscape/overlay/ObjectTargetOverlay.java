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
	private static final Color OUTLINE = new Color(0, 255, 255);

	private final ModelOutlineRenderer outlineRenderer;

	private Supplier<List<GameObject>> objectsSupplier = Collections::emptyList;

	@Inject
	public ObjectTargetOverlay(ModelOutlineRenderer outlineRenderer)
	{
		this.outlineRenderer = outlineRenderer;
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
			outlineRenderer.drawOutline(object, 2, OUTLINE, 2);
		}
		return null;
	}
}
