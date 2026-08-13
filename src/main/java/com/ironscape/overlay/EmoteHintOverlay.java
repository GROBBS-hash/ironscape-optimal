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

/**
 * Quest Helper-style guidance for steps that ask for an EMOTE.
 *
 * <p>"Perform the Goblin Bow emote next to Mistag" tells you what to do and
 * leaves you scrolling a list of eighty icons to find it (owner, in play,
 * wave 27). This points at it.
 *
 * <p>Two states, decided by what is on screen rather than by any state we
 * keep — the same shape as the minigame teleport guidance:
 *
 * <ol>
 *   <li>emote panel open -> outline the emote itself,</li>
 *   <li>panel closed -> outline the Emotes side tab, labelled, so you know
 *       where to go first.</li>
 * </ol>
 *
 * <p>The emote is found by SPRITE, not by position in the list: the order
 * shifts as emotes unlock, so an index would point at the wrong icon on
 * most accounts. Quest Helper matches by sprite for the same reason.
 *
 * <p>NOTE: deliberately does NOT scroll the list to the emote. Quest Helper
 * does, via a UPDATE_SCROLLBAR runScript — and running a script from inside
 * a render/callback is what hard-froze this client twice during the bank
 * filter work (see the wave 3 notes). Pointing is worth the freeze risk;
 * scrolling for you is not.
 */
@Singleton
public class EmoteHintOverlay extends Overlay
{
	private static final Color FALLBACK = new Color(0, 255, 128);

	/**
	 * The Emotes side tab across the three client layouts. Only one exists at
	 * a time, so the first visible one wins.
	 */
	private static final int[] EMOTE_TAB = {
		InterfaceID.Toplevel.STONE12,
		InterfaceID.ToplevelOsrsStretch.STONE12,
		InterfaceID.ToplevelPreEoc.STONE12,
	};

	/**
	 * Emote name -> sprite. Names are Quest Helper's spelling, matched
	 * loosely (case and spacing ignored) so "goblin bow" and "Goblin Bow"
	 * both land. Sprite constants come from RuneLite's gameval, so a name
	 * that stops resolving is a compile error rather than a silent miss.
	 */
	private static final java.util.Map<String, Integer> BY_NAME = buildNames();

	private static java.util.Map<String, Integer> buildNames()
	{
		java.util.Map<String, Integer> m = new java.util.HashMap<>();
		m.put("yes", net.runelite.api.gameval.SpriteID.Emotes.YES);
		m.put("no", net.runelite.api.gameval.SpriteID.Emotes.NO);
		m.put("think", net.runelite.api.gameval.SpriteID.Emotes.THINK);
		m.put("bow", net.runelite.api.gameval.SpriteID.Emotes.BOW);
		m.put("angry", net.runelite.api.gameval.SpriteID.Emotes.ANGRY);
		m.put("cry", net.runelite.api.gameval.SpriteID.Emotes.CRY);
		m.put("laugh", net.runelite.api.gameval.SpriteID.Emotes.LAUGH);
		m.put("cheer", net.runelite.api.gameval.SpriteID.Emotes.CHEER);
		m.put("wave", net.runelite.api.gameval.SpriteID.Emotes.WAVE);
		m.put("beckon", net.runelite.api.gameval.SpriteID.Emotes.BECKON);
		m.put("dance", net.runelite.api.gameval.SpriteID.Emotes.DANCE);
		m.put("clap", net.runelite.api.gameval.SpriteID.Emotes.CLAP);
		m.put("panic", net.runelite.api.gameval.SpriteID.Emotes.PANIC);
		m.put("jig", net.runelite.api.gameval.SpriteID.Emotes.JIG);
		m.put("spin", net.runelite.api.gameval.SpriteID.Emotes.SPIN);
		m.put("headbang", net.runelite.api.gameval.SpriteID.Emotes.HEADBANG);
		m.put("jumpforjoy", net.runelite.api.gameval.SpriteID.Emotes.JUMP_FOR_JOY);
		m.put("raspberry", net.runelite.api.gameval.SpriteID.Emotes.RASPBERRY);
		m.put("yawn", net.runelite.api.gameval.SpriteID.Emotes.YAWN);
		m.put("salute", net.runelite.api.gameval.SpriteID.Emotes.SALUTE);
		m.put("shrug", net.runelite.api.gameval.SpriteID.Emotes.SHRUG);
		m.put("blowkiss", net.runelite.api.gameval.SpriteID.Emotes.BLOW_KISS);
		m.put("glassbox", net.runelite.api.gameval.SpriteID.Emotes.GLASS_BOX);
		m.put("climbrope", net.runelite.api.gameval.SpriteID.Emotes.CLIMB_ROPE);
		m.put("lean", net.runelite.api.gameval.SpriteID.Emotes.LEAN);
		m.put("glasswall", net.runelite.api.gameval.SpriteID.Emotes.GLASS_WALL);
		m.put("goblinbow", net.runelite.api.gameval.SpriteID.Emotes.GOBLIN_BOW);
		m.put("goblinsalute", net.runelite.api.gameval.SpriteID.Emotes.GOBLIN_SALUTE);
		m.put("scared", net.runelite.api.gameval.SpriteID.Emotes.SCARED);
		m.put("slaphead", net.runelite.api.gameval.SpriteID.Emotes.SLAP_HEAD);
		m.put("stamp", net.runelite.api.gameval.SpriteID.Emotes.STAMP);
		m.put("flap", net.runelite.api.gameval.SpriteID.Emotes.FLAP);
		m.put("idea", net.runelite.api.gameval.SpriteID.Emotes.IDEA);
		m.put("zombiewalk", net.runelite.api.gameval.SpriteID.Emotes.ZOMBIE_WALK);
		m.put("zombiedance", net.runelite.api.gameval.SpriteID.Emotes.ZOMBIE_DANCE);
		m.put("zombiehand", net.runelite.api.gameval.SpriteID.Emotes.ZOMBIE_HAND);
		m.put("rabbithop", net.runelite.api.gameval.SpriteID.Emotes.RABBIT_HOP);
		m.put("skillcape", net.runelite.api.gameval.SpriteID.Emotes.SKILLCAPE);
		m.put("airguitar", net.runelite.api.gameval.SpriteID.Emotes.AIR_GUITAR);
		m.put("jog", net.runelite.api.gameval.SpriteID.Emotes.JOG);
		m.put("situp", net.runelite.api.gameval.SpriteID.Emotes.SIT_UP);
		m.put("starjump", net.runelite.api.gameval.SpriteID.Emotes.STAR_JUMP);
		m.put("pushup", net.runelite.api.gameval.SpriteID.Emotes.PUSH_UP);
		m.put("smoothdance", net.runelite.api.gameval.SpriteID.Emotes.SMOOTH_DANCE);
		m.put("crazydance", net.runelite.api.gameval.SpriteID.Emotes.CRAZY_DANCE);
		m.put("premiershield", net.runelite.api.gameval.SpriteID.Emotes.PREMIER_SHIELD);
		m.put("fortissalute", net.runelite.api.gameval.SpriteID.Emotes.FORTIS_SALUTE);
		m.put("crabdance", net.runelite.api.gameval.SpriteID.Emotes.CRAB_DANCE);
		m.put("uritransform", net.runelite.api.gameval.SpriteID.Emotes.URI_TRANSFORM);
		return m;
	}

	/** @return sprite id for an emote name, or -1 when the name is unknown. */
	public static int spriteFor(String emoteName)
	{
		if (emoteName == null)
		{
			return -1;
		}
		String key = emoteName.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z]", "");
		return BY_NAME.getOrDefault(key, -1);
	}

	private final Client client;

	/** Sprite id of the emote the current step wants, or -1 for none. */
	private Supplier<Integer> spriteSupplier = () -> -1;
	/** Human name of that emote, for the tab label ("Emotes: Goblin bow"). */
	private Supplier<String> nameSupplier = () -> null;
	private Supplier<Color> colorSupplier = () -> FALLBACK;

	@Inject
	public EmoteHintOverlay(Client client)
	{
		this.client = client;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	public void setSpriteSupplier(Supplier<Integer> supplier)
	{
		this.spriteSupplier = supplier;
	}

	public void setNameSupplier(Supplier<String> supplier)
	{
		this.nameSupplier = supplier;
	}

	public void setColorSupplier(Supplier<Color> supplier)
	{
		this.colorSupplier = supplier;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		Integer sprite = spriteSupplier.get();
		if (sprite == null || sprite < 0)
		{
			return null;
		}

		Widget contents = client.getWidget(InterfaceID.Emote.CONTENTS);
		if (contents != null && !contents.isHidden())
		{
			Widget[] children = contents.getDynamicChildren();
			if (children != null)
			{
				for (Widget child : children)
				{
					if (child != null && child.getSpriteId() == sprite)
					{
						draw(graphics, child.getBounds(), null);
						return null;
					}
				}
			}
			// Panel open but the emote is not among the children: it is
			// either scrolled out of the built range or genuinely not
			// unlocked. Say nothing rather than point somewhere wrong.
			return null;
		}

		String name = nameSupplier.get();
		for (int id : EMOTE_TAB)
		{
			Widget tab = client.getWidget(id);
			if (tab != null && !tab.isHidden())
			{
				draw(graphics, tab.getBounds(), name == null ? "Emotes" : "Emotes: " + name);
				return null;
			}
		}
		return null;
	}

	private void draw(Graphics2D graphics, Rectangle bounds, String label)
	{
		if (bounds == null)
		{
			return;
		}
		Color color = colorSupplier.get();
		if (color == null)
		{
			color = FALLBACK;
		}
		graphics.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 40));
		graphics.fill(bounds);
		graphics.setColor(color);
		graphics.setStroke(new BasicStroke(2));
		graphics.draw(bounds);
		if (label != null)
		{
			int tx = bounds.x;
			int ty = bounds.y - 3;
			graphics.setColor(Color.BLACK);
			graphics.drawString(label, tx + 1, ty + 1);
			graphics.setColor(color);
			graphics.drawString(label, tx, ty);
		}
	}
}
