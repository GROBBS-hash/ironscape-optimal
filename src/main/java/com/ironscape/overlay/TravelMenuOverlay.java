package com.ironscape.overlay;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
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
 * Highlights the right destination inside the game's generic travel menu
 * ("Spirit Tree Locations", gnome gliders — any Adventure Log-style list,
 * interface group 187). An entry lights up when every word of its name
 * appears in the current sub-step's text or the step's 📍 location tag,
 * so "Use the spirit tree and go to battlefield of khazard" highlights
 * "3: Battlefield of Khazard". Word-SET matching, not phrase matching:
 * the guide says "Khazard Battlefield", the menu "Battlefield of Khazard".
 *
 * The plugin supplies the word set once per tick; render only does widget
 * reads, so an open menu costs nothing while no travel sub is current.
 */
@Singleton
public class TravelMenuOverlay extends Overlay
{
	private static final Color HIGHLIGHT = new Color(0, 255, 128);
	private static final Color FILL = new Color(0, 255, 128, 40);

	/** Words like "of" carry no meaning; they must not block a match. */
	private static final Set<String> STOPWORDS = Set.of("of", "the", "a", "an", "your");

	private final Client client;

	/** Lowercased words of the current sub + location tag; empty = hidden. */
	private Supplier<Set<String>> wordsSupplier = Collections::emptySet;

	@Inject
	public TravelMenuOverlay(Client client)
	{
		this.client = client;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	public void setWordsSupplier(Supplier<Set<String>> wordsSupplier)
	{
		this.wordsSupplier = wordsSupplier;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		Set<String> words = wordsSupplier.get();
		if (words == null || words.isEmpty())
		{
			return null;
		}
		Widget list = client.getWidget(InterfaceID.Menu.LJ_LAYER1);
		if (list == null || list.isHidden())
		{
			return null;
		}
		Widget[] children = list.getDynamicChildren();
		if (children == null)
		{
			return null;
		}
		for (Widget child : children)
		{
			String text = child.getText();
			if (child.isHidden() || text == null || text.isEmpty())
			{
				continue;
			}
			// "<col=ff9040>3: Battlefield of Khazard</col>" -> the name.
			String clean = Text.removeTags(text.replace("<br>", " "))
				.replaceFirst("^\\s*[0-9A-Za-z]\\s*:\\s*", "").trim();
			if (clean.isEmpty() || !entryMatches(clean, words))
			{
				continue;
			}
			Rectangle bounds = child.getBounds();
			if (bounds != null)
			{
				graphics.setColor(FILL);
				graphics.fill(bounds);
				graphics.setColor(HIGHLIGHT);
				graphics.setStroke(new BasicStroke(2));
				graphics.draw(bounds);
			}
		}
		return null;
	}

	/** Every meaningful word of the entry name appears in the sub's words. */
	private static boolean entryMatches(String entry, Set<String> words)
	{
		boolean any = false;
		for (String token : entry.toLowerCase(Locale.ROOT).replace('’', '\'')
			.split("[^a-z0-9']+"))
		{
			if (token.isEmpty() || STOPWORDS.contains(token))
			{
				continue;
			}
			if (!words.contains(token))
			{
				return false;
			}
			any = true;
		}
		return any;
	}
}
