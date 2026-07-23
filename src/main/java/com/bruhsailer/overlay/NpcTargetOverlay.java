package com.bruhsailer.overlay;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.Set;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.SpriteID;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.Text;

/**
 * Outlines every NPC named in the CURRENT sub-step ("speak with Veos"
 * -> Veos gets a cyan outline that follows him as he wanders), Quest
 * Helper-style but generic: no per-quest authoring, just the NPC's own
 * name appearing in the guide text. When the sub is a quest goal, the
 * blue quest icon floats over the NPC's head as well.
 *
 * The plugin supplies the lowercased names once per tick; the outline
 * itself is recomputed every frame from the live NPC objects, which is
 * what keeps it glued to moving NPCs — their convex hull is wherever
 * the model currently is.
 */
@Singleton
public class NpcTargetOverlay extends Overlay
{
	private static final Color OUTLINE = new Color(0, 255, 255);
	private static final Color OUTLINE_FILL = new Color(0, 255, 255, 30);

	private final Client client;
	private final SpriteManager spriteManager;
	private final net.runelite.client.game.ItemManager itemManager;

	private Supplier<Set<String>> namesSupplier = Collections::emptySet;
	private Supplier<Boolean> questIconSupplier = () -> false;
	private Supplier<Integer> itemIconSupplier = () -> -1;
	private BufferedImage icon;
	private int cachedItemId = -1;
	private BufferedImage cachedItemImage;

	@Inject
	public NpcTargetOverlay(Client client, SpriteManager spriteManager,
		net.runelite.client.game.ItemManager itemManager)
	{
		this.client = client;
		this.spriteManager = spriteManager;
		this.itemManager = itemManager;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	/** Lowercased NPC names the current sub-step mentions; empty = hidden. */
	public void setNamesSupplier(Supplier<Set<String>> namesSupplier)
	{
		this.namesSupplier = namesSupplier;
	}

	/** Whether the current sub is a quest goal (adds the quest icon). */
	public void setQuestIconSupplier(Supplier<Boolean> questIconSupplier)
	{
		this.questIconSupplier = questIconSupplier;
	}

	/**
	 * Item id to float over outlined NPCs — the thing you're there to BUY
	 * from them ("Trade Gulluck" + bronze arrowtips overhead). -1 = none;
	 * the quest icon wins when both apply.
	 */
	public void setItemIconSupplier(Supplier<Integer> itemIconSupplier)
	{
		this.itemIconSupplier = itemIconSupplier;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		Set<String> names = namesSupplier.get();
		if (names == null || names.isEmpty())
		{
			return null;
		}
		boolean questIcon = Boolean.TRUE.equals(questIconSupplier.get());

		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			String name = npc.getName();
			if (name == null || !names.contains(Text.removeTags(name).toLowerCase()))
			{
				continue;
			}

			Shape hull = npc.getConvexHull();
			if (hull != null)
			{
				graphics.setColor(OUTLINE_FILL);
				graphics.fill(hull);
				graphics.setColor(OUTLINE);
				graphics.setStroke(new BasicStroke(2));
				graphics.draw(hull);
			}

			BufferedImage overhead = null;
			if (questIcon)
			{
				if (icon == null)
				{
					icon = spriteManager.getSprite(SpriteID.QUESTS_PAGE_ICON_BLUE_QUESTS, 0);
				}
				overhead = icon;
			}
			else
			{
				Integer itemId = itemIconSupplier.get();
				if (itemId != null && itemId > 0)
				{
					if (itemId != cachedItemId)
					{
						cachedItemId = itemId;
						cachedItemImage = itemManager.getImage(itemId);
					}
					overhead = cachedItemImage;
				}
			}
			if (overhead != null)
			{
				LocalPoint local = npc.getLocalLocation();
				if (local != null)
				{
					net.runelite.api.Point canvas = Perspective.getCanvasImageLocation(
						client, local, overhead, npc.getLogicalHeight() + 40);
					if (canvas != null)
					{
						graphics.drawImage(overhead, canvas.getX(), canvas.getY(), null);
					}
				}
			}
		}
		return null;
	}
}
