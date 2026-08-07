package com.ironscape.overlay;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import net.runelite.api.Client;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The "stop following Quest Helper, come back here" banner.
 *
 * A guide step often ends PART WAY through a quest — Biohazard's plague
 * sample is collected, but the guide leaves the quest there and sends you
 * to Rimmington. Quest Helper knows nothing about that and will happily
 * run you to the end of the quest, so the moment our step completes has
 * to be impossible to miss.
 *
 * Chat alone was not enough (owner, 2026-08-08): a console line scrolls
 * away behind quest dialogue and the player is watching the game, not the
 * chatbox. This draws across the middle of the viewport instead, where
 * the eyes already are.
 *
 * It expires on its own (see the plugin's tick countdown) rather than
 * needing a dismiss click — a banner you must close to keep playing is
 * worse than the problem it solves.
 */
@Singleton
public class QuestHandoffOverlay extends Overlay
{
	private static final Color HEADLINE = new Color(0x2e, 0xcc, 0x40);
	private static final Color BODY = Color.WHITE;
	private static final Color NEXT = new Color(0xff, 0x98, 0x1f); // brand orange
	private static final Color BACKGROUND = new Color(0, 0, 0, 205);
	private static final Color BORDER = new Color(0x2e, 0xcc, 0x40, 220);

	private static final int PADDING = 12;
	private static final int LINE_GAP = 5;
	/** Never let the banner span an ultrawide client edge to edge. */
	private static final int MAX_WIDTH = 460;

	/** Everything the banner shows. Rebuilt only when a handoff fires. */
	@Value
	public static class Model
	{
		String quest;
		/** The guide's next action, already truncated. */
		String next;
	}

	private final Client client;

	private Supplier<Model> modelSupplier = () -> null;

	@Inject
	public QuestHandoffOverlay(Client client)
	{
		this.client = client;
		setPosition(OverlayPosition.DYNAMIC);
		// Above widgets: the handoff usually fires while a quest dialogue
		// or Quest Helper's own box is on screen, which is exactly what it
		// needs to be seen over.
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	public void setModelSupplier(Supplier<Model> modelSupplier)
	{
		this.modelSupplier = modelSupplier;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		Model model = modelSupplier.get();
		if (model == null)
		{
			return null;
		}
		Rectangle viewport = new Rectangle(client.getViewportXOffset(),
			client.getViewportYOffset(), client.getViewportWidth(), client.getViewportHeight());
		if (viewport.width <= 0 || viewport.height <= 0)
		{
			return null;
		}

		graphics.setFont(FontManager.getRunescapeBoldFont());
		FontMetrics boldMetrics = graphics.getFontMetrics();
		String headline = "STOP following Quest Helper";

		graphics.setFont(FontManager.getRunescapeFont());
		FontMetrics metrics = graphics.getFontMetrics();
		int maxTextWidth = Math.min(MAX_WIDTH, viewport.width - 2 * PADDING) - 2 * PADDING;
		List<String> body = new ArrayList<>(
			wrap(model.quest + " is deliberately left part-finished — the guide comes back to it.",
				metrics, maxTextWidth));
		List<String> next = wrap("Next: " + model.next, metrics, maxTextWidth);

		int textWidth = boldMetrics.stringWidth(headline);
		for (String line : body)
		{
			textWidth = Math.max(textWidth, metrics.stringWidth(line));
		}
		for (String line : next)
		{
			textWidth = Math.max(textWidth, metrics.stringWidth(line));
		}
		textWidth = Math.min(textWidth, maxTextWidth);

		int lineHeight = metrics.getHeight() + LINE_GAP;
		int boxWidth = textWidth + 2 * PADDING;
		int boxHeight = boldMetrics.getHeight() + LINE_GAP
			+ (body.size() + next.size()) * lineHeight + 2 * PADDING;

		// Slightly above centre: dead centre sits under the chatbox on
		// fixed-mode clients and over the NPC you are talking to.
		int x = viewport.x + (viewport.width - boxWidth) / 2;
		int y = viewport.y + Math.max(PADDING, viewport.height / 3 - boxHeight / 2);

		graphics.setColor(BACKGROUND);
		graphics.fillRect(x, y, boxWidth, boxHeight);
		graphics.setColor(BORDER);
		graphics.drawRect(x, y, boxWidth, boxHeight);

		int textX = x + PADDING;
		int textY = y + PADDING + boldMetrics.getAscent();
		graphics.setFont(FontManager.getRunescapeBoldFont());
		graphics.setColor(HEADLINE);
		graphics.drawString(headline, textX, textY);
		textY += boldMetrics.getHeight() + LINE_GAP;

		graphics.setFont(FontManager.getRunescapeFont());
		graphics.setColor(BODY);
		for (String line : body)
		{
			graphics.drawString(line, textX, textY);
			textY += lineHeight;
		}
		graphics.setColor(NEXT);
		for (String line : next)
		{
			graphics.drawString(line, textX, textY);
			textY += lineHeight;
		}
		return new Dimension(boxWidth, boxHeight);
	}

	/** Greedy word-wrap to a pixel width. */
	private static List<String> wrap(String text, FontMetrics metrics, int maxWidth)
	{
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" "))
		{
			String candidate = line.length() == 0 ? word : line + " " + word;
			if (metrics.stringWidth(candidate) > maxWidth && line.length() > 0)
			{
				lines.add(line.toString());
				line = new StringBuilder(word);
			}
			else
			{
				line = new StringBuilder(candidate);
			}
		}
		if (line.length() > 0)
		{
			lines.add(line.toString());
		}
		return lines;
	}
}
