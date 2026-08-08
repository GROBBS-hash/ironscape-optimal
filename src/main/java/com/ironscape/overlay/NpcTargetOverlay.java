package com.ironscape.overlay;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
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

	private final Client client;
	private final SpriteManager spriteManager;
	private final net.runelite.client.game.ItemManager itemManager;
	private final net.runelite.client.ui.overlay.outline.ModelOutlineRenderer outlineRenderer;

	private Supplier<Set<String>> namesSupplier = Collections::emptySet;
	private Supplier<Set<Integer>> indexesSupplier = Collections::emptySet;
	private Supplier<Boolean> questIconSupplier = () -> false;
	private Supplier<Integer> itemIconSupplier = () -> -1;
	private Supplier<java.util.Map<String, Integer>> perNpcIconSupplier =
		Collections::emptyMap;
	private BufferedImage icon;
	/** Item id -> sprite. A per-NPC icon means several are live at once. */
	private final java.util.Map<Integer, BufferedImage> itemImages = new java.util.HashMap<>();

	@Inject
	public NpcTargetOverlay(Client client, SpriteManager spriteManager,
		net.runelite.client.game.ItemManager itemManager,
		net.runelite.client.ui.overlay.outline.ModelOutlineRenderer outlineRenderer)
	{
		this.client = client;
		this.spriteManager = spriteManager;
		this.itemManager = itemManager;
		this.outlineRenderer = outlineRenderer;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	/** Lowercased NPC names the current sub-step mentions; empty = hidden. */
	public void setNamesSupplier(Supplier<Set<String>> namesSupplier)
	{
		this.namesSupplier = namesSupplier;
	}

	/**
	 * SPECIFIC NPCs by index — the anchor-nominated shopkeeper. Unlike a
	 * name, an index never spreads to lookalikes.
	 */
	public void setIndexesSupplier(Supplier<Set<Integer>> indexesSupplier)
	{
		this.indexesSupplier = indexesSupplier;
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

	/**
	 * Per-VENDOR overrides, keyed by lowercase NPC name — "Buy candle, 2
	 * fishing rods, lobster pot" spans two Catherby shops, and each
	 * keeper should wear their own stock. Anyone absent falls back to
	 * setItemIconSupplier.
	 */
	public void setPerNpcIconSupplier(Supplier<java.util.Map<String, Integer>> supplier)
	{
		this.perNpcIconSupplier = supplier;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		Set<String> names = namesSupplier.get();
		Set<Integer> indexes = indexesSupplier.get();
		boolean anyNames = names != null && !names.isEmpty();
		boolean anyIndexes = indexes != null && !indexes.isEmpty();
		if (!anyNames && !anyIndexes)
		{
			return null;
		}
		boolean questIcon = Boolean.TRUE.equals(questIconSupplier.get());

		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			String name = npc.getName();
			boolean byName = anyNames && name != null
				&& names.contains(Text.removeTags(name).toLowerCase());
			boolean byIndex = anyIndexes && indexes.contains(npc.getIndex());
			if (!byName && !byIndex)
			{
				continue;
			}

			// Quest Helper look: a crisp line hugging the model silhouette,
			// not a convex-hull blob.
			outlineRenderer.drawOutline(npc, 2, OUTLINE, 2);

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
				// A vendor named in item_sources wears the item THEY sell:
				// one step can span two shops, and a single shared icon put
				// a fishing rod over the candle maker's head.
				Integer itemId = name == null ? null
					: perNpcIconSupplier.get().get(Text.removeTags(name).toLowerCase());
				if (itemId == null)
				{
					itemId = itemIconSupplier.get();
				}
				if (itemId != null && itemId > 0)
				{
					overhead = itemImages.computeIfAbsent(itemId, itemManager::getImage);
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
