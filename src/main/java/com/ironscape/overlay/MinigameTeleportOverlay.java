package com.ironscape.overlay;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.Text;

/**
 * Quest Helper-style click guidance for "Minigame teleport to X" steps.
 *
 * While the CURRENT sub-step is a minigame teleport, this walks the player
 * through the UI one highlighted click at a time:
 *
 *   1. the Chat-channel side tab (whichever layout's widget is visible),
 *   2. the "Grouping" sub-tab inside that panel,
 *   3. the minigame dropdown (and, once open, the right entry in the list),
 *   4. the Teleport button.
 *
 * Each render just inspects which widgets currently exist and highlights
 * the next one — no state machine to get stuck. The overlay disappears by
 * itself when the teleport lands, because the position jump auto-ticks the
 * travel sub and it stops being current.
 */
@Singleton
public class MinigameTeleportOverlay extends Overlay
{
	private static final Color HIGHLIGHT = new Color(0, 255, 128);
	private static final Color FILL = new Color(0, 255, 128, 40);

	/**
	 * The Chat-channel/Grouping side tab in each of the three client
	 * layouts (fixed, resizable classic, resizable modern). Exactly one is
	 * a real visible widget at any time. Stone/icon 7 is the friends-chat
	 * tab in every layout (ComponentID's deprecated names confirm it:
	 * FIXED_VIEWPORT_FRIENDS_CHAT_TAB etc. have these same values).
	 */
	private static final int[] SIDE_TAB_CANDIDATES = {
		InterfaceID.Toplevel.STONE7,
		InterfaceID.ToplevelOsrsStretch.STONE7,
		InterfaceID.ToplevelPreEoc.ICON7,
	};

	/**
	 * The Magic side tab per layout — highlighted alongside the grouping
	 * tab, because the spellbook's Minigame Teleport button is an equally
	 * valid (often faster) route. STONE6 in every layout.
	 */
	private static final int[] MAGIC_TAB_CANDIDATES = {
		InterfaceID.Toplevel.STONE6,
		InterfaceID.ToplevelOsrsStretch.STONE6,
		InterfaceID.ToplevelPreEoc.STONE6,
	};

	/**
	 * Each spellbook's "Minigame Teleport" button (added to the magic book
	 * in a game update) — clicking it opens the same Grouping flow. Only
	 * the active spellbook's widget exists at a time.
	 */
	private static final int[] SPELLBOOK_MINIGAME_TELEPORTS = {
		InterfaceID.MagicSpellbook.TELEPORT_MINIGAME_STANDARD,
		InterfaceID.MagicSpellbook.TELEPORT_MINIGAME_ANCIENT,
		InterfaceID.MagicSpellbook.TELEPORT_MINIGAME_ARCEUUS,
		InterfaceID.MagicSpellbook.TELEPORT_MINIGAME_LUNAR,
	};

	/**
	 * Each spellbook's Home Teleport spell — for "Home tele to Lumbridge"
	 * steps: highlight the spell if the book is open, else the Magic tab.
	 */
	private static final int[] SPELLBOOK_HOME_TELEPORTS = {
		InterfaceID.MagicSpellbook.TELEPORT_HOME_STANDARD,
		InterfaceID.MagicSpellbook.TELEPORT_HOME_ZAROS,
		InterfaceID.MagicSpellbook.TELEPORT_HOME_ARCEUUS,
		InterfaceID.MagicSpellbook.TELEPORT_HOME_LUNAR,
	};

	private final Client client;

	/** Set by the plugin: the minigame the current sub-step wants, or null. */
	private Supplier<String> targetSupplier = () -> null;

	/** Set by the plugin: true while the current sub is a home teleport. */
	private Supplier<Boolean> homeTeleportSupplier = () -> false;

	@Inject
	public MinigameTeleportOverlay(Client client)
	{
		this.client = client;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	public void setTargetSupplier(Supplier<String> targetSupplier)
	{
		this.targetSupplier = targetSupplier;
	}

	public void setHomeTeleportSupplier(Supplier<Boolean> homeTeleportSupplier)
	{
		this.homeTeleportSupplier = homeTeleportSupplier;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		String minigame = targetSupplier.get();
		if (minigame == null)
		{
			if (Boolean.TRUE.equals(homeTeleportSupplier.get()))
			{
				renderHomeTeleport(graphics);
			}
			return null;
		}

		// The center-screen Minigames picker (what the spellbook button
		// opens): highlight the entry with the right name.
		Widget picker = client.getWidget(InterfaceID.Minigames.UNIVERSE);
		if (picker != null && !picker.isHidden())
		{
			Widget content = client.getWidget(InterfaceID.Minigames.CONTENT);
			for (int componentId = InterfaceID.Minigames.MINIGAME_21;
				componentId <= InterfaceID.Minigames.MINIGAME_1; componentId++)
			{
				Widget entry = client.getWidget(componentId);
				if (entry != null && !entry.isHidden() && widgetTextContains(entry, minigame)
					// scrolled-out entries still exist; don't draw outside the frame
					&& (content == null || content.getBounds().intersects(entry.getBounds())))
				{
					highlight(graphics, entry);
					return null;
				}
			}
			return null; // right entry is scrolled out of view; nothing to point at
		}

		// Grouping panel open: guide within it.
		Widget grouping = client.getWidget(InterfaceID.Grouping.UNIVERSE);
		if (grouping != null && !grouping.isHidden())
		{
			renderInsideGrouping(graphics, minigame);
			return null;
		}

		// Spellbook open: its Minigame Teleport button is the shortest path
		// from here — clicking it opens the Grouping flow we then guide.
		for (int componentId : SPELLBOOK_MINIGAME_TELEPORTS)
		{
			Widget spell = client.getWidget(componentId);
			if (spell != null && !spell.isHidden())
			{
				highlight(graphics, spell);
				return null;
			}
		}

		// Channels side panel open but on another sub-tab (Chat-channel,
		// Your Clan, Guest Clan): point at the Grouping sub-tab.
		Widget channels = client.getWidget(InterfaceID.SideChannels.UNIVERSE);
		if (channels != null && !channels.isHidden())
		{
			highlight(graphics, client.getWidget(InterfaceID.SideChannels.TAB_3));
			return null;
		}

		// Neither route's panel is open: light up BOTH ways in — the
		// grouping side tab and the magic side tab both lead to the
		// minigame teleport.
		highlightFirstVisible(graphics, SIDE_TAB_CANDIDATES);
		highlightFirstVisible(graphics, MAGIC_TAB_CANDIDATES);
		return null;
	}

	/**
	 * "Home tele to Lumbridge": one highlighted click at a time — the Home
	 * Teleport spell if the spellbook is open, else the Magic side tab.
	 */
	private void renderHomeTeleport(Graphics2D graphics)
	{
		for (int componentId : SPELLBOOK_HOME_TELEPORTS)
		{
			Widget spell = client.getWidget(componentId);
			if (spell != null && !spell.isHidden())
			{
				highlight(graphics, spell);
				return;
			}
		}
		highlightFirstVisible(graphics, MAGIC_TAB_CANDIDATES);
	}

	private void highlightFirstVisible(Graphics2D graphics, int[] componentIds)
	{
		for (int componentId : componentIds)
		{
			Widget widget = client.getWidget(componentId);
			if (widget != null && !widget.isHidden())
			{
				highlight(graphics, widget);
				return;
			}
		}
	}

	private void renderInsideGrouping(Graphics2D graphics, String minigame)
	{
		// Dropdown list open: highlight the entry with the right name.
		Widget dropdownList = client.getWidget(InterfaceID.Grouping.DROPDOWN);
		if (dropdownList != null && !dropdownList.isHidden())
		{
			Widget contents = client.getWidget(InterfaceID.Grouping.DROPDOWN_CONTENTS);
			if (contents != null)
			{
				for (Widget entry : contents.getDynamicChildren())
				{
					String text = entry.getText();
					if (text != null && Text.removeTags(text).equalsIgnoreCase(minigame))
					{
						highlight(graphics, entry);
						return;
					}
				}
			}
			// Right minigame not on screen yet — outline the list to say
			// "scroll in here".
			highlight(graphics, dropdownList);
			return;
		}

		// Dropdown closed: is the right minigame already selected?
		Widget selected = client.getWidget(InterfaceID.Grouping.CURRENTGAME);
		String selectedName = selected == null || selected.getText() == null
			? "" : Text.removeTags(selected.getText());
		if (!selectedName.equalsIgnoreCase(minigame))
		{
			highlight(graphics, client.getWidget(InterfaceID.Grouping.DROPDOWN_TOP));
			return;
		}

		highlight(graphics, client.getWidget(InterfaceID.Grouping.TELEPORT));
	}

	/**
	 * Does this widget (or any direct child) contain the given name?
	 * Picker entries render their name in child text widgets ("Soul Wars"
	 * over "Isle of Souls"), so the entry itself often has no text.
	 */
	private static boolean widgetTextContains(Widget widget, String needle)
	{
		String lowerNeedle = needle.toLowerCase();
		if (textContains(widget.getText(), lowerNeedle))
		{
			return true;
		}
		Widget[][] childArrays = {
			widget.getStaticChildren(), widget.getDynamicChildren(), widget.getNestedChildren(),
		};
		for (Widget[] children : childArrays)
		{
			if (children == null)
			{
				continue;
			}
			for (Widget child : children)
			{
				if (child != null && textContains(child.getText(), lowerNeedle))
				{
					return true;
				}
			}
		}
		return false;
	}

	private static boolean textContains(String text, String lowerNeedle)
	{
		return text != null && Text.removeTags(text).toLowerCase().contains(lowerNeedle);
	}

	private void highlight(Graphics2D graphics, Widget widget)
	{
		if (widget == null || widget.isHidden())
		{
			return;
		}
		Rectangle bounds = widget.getBounds();
		graphics.setColor(FILL);
		graphics.fill(bounds);
		graphics.setColor(HIGHLIGHT);
		graphics.setStroke(new BasicStroke(2));
		graphics.draw(bounds);
	}
}
