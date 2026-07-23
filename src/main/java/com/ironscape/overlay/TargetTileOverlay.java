package com.ironscape.overlay;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Highlights the EXACT tile of the current sub-step's annotated target —
 * dig spots, item spawns, and other captured ⌖ locations. The tile gets a
 * translucent fill plus a floating arrow so it reads at a glance even in
 * cluttered scenes. The plugin supplies the point once per game tick;
 * null (no annotated target, or feature off) hides the overlay.
 */
@Singleton
public class TargetTileOverlay extends Overlay
{
	/** The plugin's accent orange, also used by the sidebar icon. */
	private static final Color ACCENT = new Color(230, 138, 23);
	private static final Color FILL = new Color(230, 138, 23, 60);

	/** Height above the tile the arrow floats, in local units. */
	private static final int ARROW_HEIGHT = 160;
	/** Arrow size in pixels: half-width and length of the triangle. */
	private static final int ARROW_HALF_WIDTH = 7;
	private static final int ARROW_LENGTH = 14;

	/** Ground items to pick up render green — "loot", not "walk here". */
	private static final Color ITEM_ACCENT = new Color(0x4c, 0xaf, 0x50);
	private static final Color ITEM_FILL = new Color(0x4c, 0xaf, 0x50, 50);

	private final Client client;

	private Supplier<WorldPoint> targetSupplier = () -> null;
	private Supplier<java.util.List<WorldPoint>> groundItemsSupplier = java.util.Collections::emptyList;

	@Inject
	public TargetTileOverlay(Client client)
	{
		this.client = client;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	public void setTargetSupplier(Supplier<WorldPoint> targetSupplier)
	{
		this.targetSupplier = targetSupplier;
	}

	public void setGroundItemsSupplier(Supplier<java.util.List<WorldPoint>> groundItemsSupplier)
	{
		this.groundItemsSupplier = groundItemsSupplier;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		for (WorldPoint itemSpot : groundItemsSupplier.get())
		{
			LocalPoint local = LocalPoint.fromWorld(client.getTopLevelWorldView(), itemSpot);
			if (local == null)
			{
				continue;
			}
			Polygon poly = Perspective.getCanvasTilePoly(client, local);
			if (poly != null)
			{
				graphics.setColor(ITEM_FILL);
				graphics.fill(poly);
				graphics.setColor(ITEM_ACCENT);
				graphics.setStroke(new BasicStroke(2f));
				graphics.draw(poly);
			}
		}

		WorldPoint point = targetSupplier.get();
		if (point == null || point.getPlane() != client.getTopLevelWorldView().getPlane())
		{
			return null;
		}
		LocalPoint local = LocalPoint.fromWorld(client.getTopLevelWorldView(), point);
		if (local == null)
		{
			return null; // target isn't in the loaded scene yet
		}

		Polygon tile = Perspective.getCanvasTilePoly(client, local);
		if (tile != null)
		{
			graphics.setColor(FILL);
			graphics.fill(tile);
			graphics.setColor(ACCENT);
			graphics.setStroke(new BasicStroke(2f));
			graphics.draw(tile);
		}

		// A downward-pointing arrow floating over the tile centre.
		net.runelite.api.Point centre = Perspective.localToCanvas(
			client, local, client.getTopLevelWorldView().getPlane(), ARROW_HEIGHT);
		if (centre != null)
		{
			int x = centre.getX();
			int y = centre.getY();
			Polygon arrow = new Polygon(
				new int[]{x - ARROW_HALF_WIDTH, x + ARROW_HALF_WIDTH, x},
				new int[]{y - ARROW_LENGTH, y - ARROW_LENGTH, y},
				3);
			graphics.setColor(ACCENT);
			graphics.fill(arrow);
			graphics.setColor(Color.BLACK);
			graphics.setStroke(new BasicStroke(1f));
			graphics.draw(arrow);
		}
		return null;
	}
}
