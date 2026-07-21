package com.bruhsailer.overlay;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.List;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * The Quest Helper-style on-screen box: your current step's remaining
 * actions plus live requirement counts (items, levels, xp-drop counts).
 *
 * The plugin rebuilds the {@link Model} once per game tick (progress and
 * item lookups belong on the client thread); render just draws whatever
 * model is current. Alt-drag moves the box like any RuneLite overlay.
 */
@Singleton
public class StepOverlay extends OverlayPanel
{
	private static final Color TITLE_COLOR = new Color(0xff, 0x98, 0x1f); // RuneLite brand orange

	/** One snapshot of everything the box shows. Immutable; built per tick. */
	@Value
	public static class Model
	{
		String title;
		/** The step's remaining action texts, already truncated. */
		List<String> lines;
		List<Requirement> requirements;
	}

	/** "leather gloves  0/1" with the badge color (green/orange/red). */
	@Value
	public static class Requirement
	{
		String name;
		String progress;
		Color color;
	}

	private Supplier<Model> modelSupplier = () -> null;

	@Inject
	public StepOverlay()
	{
		setPosition(OverlayPosition.TOP_LEFT);
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

		panelComponent.setPreferredSize(new Dimension(190, 0));
		panelComponent.getChildren().add(TitleComponent.builder()
			.text(model.getTitle())
			.color(TITLE_COLOR)
			.build());

		for (String line : model.getLines())
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left(line)
				.build());
		}

		for (Requirement requirement : model.getRequirements())
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left(requirement.getName())
				.leftColor(Color.LIGHT_GRAY)
				.right(requirement.getProgress())
				.rightColor(requirement.getColor())
				.build());
		}

		return super.render(graphics);
	}
}
