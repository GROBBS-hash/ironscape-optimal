package com.bruhsailer.overlay;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.SpriteID;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Floats the blue quest icon over a quest's start point — the Quest
 * Helper-style "the quest starts HERE" cue. The plugin supplies the
 * point while the player is heading to an unstarted quest (current
 * "Start X" sub-step, or a clicked quest link); the moment the quest
 * begins the supplier goes null, the icon vanishes, and Quest Helper's
 * own overlays take over.
 */
@Singleton
public class QuestStartMarkerOverlay extends Overlay
{
	/** Pixels above the tile the icon floats — roughly NPC head height. */
	private static final int HEIGHT_OFFSET = 220;

	private final Client client;
	private final SpriteManager spriteManager;

	private Supplier<WorldPoint> targetSupplier = () -> null;
	private BufferedImage icon;

	@Inject
	public QuestStartMarkerOverlay(Client client, SpriteManager spriteManager)
	{
		this.client = client;
		this.spriteManager = spriteManager;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	public void setTargetSupplier(Supplier<WorldPoint> targetSupplier)
	{
		this.targetSupplier = targetSupplier;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		WorldPoint point = targetSupplier.get();
		if (point == null)
		{
			return null;
		}
		LocalPoint local = LocalPoint.fromWorld(client.getTopLevelWorldView(), point);
		if (local == null)
		{
			return null; // start point isn't in the loaded scene yet
		}
		if (icon == null)
		{
			icon = spriteManager.getSprite(SpriteID.QUESTS_PAGE_ICON_BLUE_QUESTS, 0);
			if (icon == null)
			{
				return null; // sprite cache not warm yet; try next frame
			}
		}
		net.runelite.api.Point canvas =
			Perspective.getCanvasImageLocation(client, local, icon, HEIGHT_OFFSET);
		if (canvas != null)
		{
			graphics.drawImage(icon, canvas.getX(), canvas.getY(), null);
		}
		return null;
	}
}
