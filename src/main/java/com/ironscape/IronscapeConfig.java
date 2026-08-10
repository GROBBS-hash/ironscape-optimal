package com.ironscape;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

/**
 * Plugin settings. RuneLite generates the implementation of this interface
 * at runtime and renders a settings UI from it automatically — we only
 * declare what the options are.
 *
 * "ironscape" is the config group key: every value is stored under it in
 * the user's RuneLite profile. Progress persistence (step 3 of the build
 * plan) will use this same mechanism via ConfigManager.
 */
@ConfigGroup("ironscape")
public interface IronscapeConfig extends Config
{
	@ConfigItem(
		keyName = "showCompletedSteps",
		name = "Show completed steps",
		description = "Keep completed steps visible in the panel instead of hiding them"
	)
	default boolean showCompletedSteps()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showCaptureButtons",
		name = "Show capture buttons",
		description = "Show the per-step button that saves your current location as that step's target (for building navigation annotations)"
	)
	default boolean showCaptureButtons()
	{
		return true;
	}

	@ConfigItem(
		keyName = "autoCompleteSteps",
		name = "Auto-complete steps",
		description = "Automatically tick off steps whose annotated requirement (e.g. a skill level) you have met"
	)
	default boolean autoCompleteSteps()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showStepOverlay",
		name = "Show step overlay",
		description = "On-screen box with your current step's remaining actions and live item/level counts (alt-drag to move)"
	)
	default boolean showStepOverlay()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showHandoffBanner",
		name = "Show Quest Helper handoff banners",
		description = "Draw an on-screen banner (and send a notification) when guidance changes hands: when our route stands down for a quest, and when a guide step finishes part way through one and you should stop following Quest Helper"
	)
	default boolean showHandoffBanner()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showQuestStartMarker",
		name = "Quest start marker",
		description = "Float the blue quest icon over a quest's start point while you head there; disappears once the quest begins (and Quest Helper takes over)"
	)
	default boolean showQuestStartMarker()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showGroundItemMarkers",
		name = "Mark ground items",
		description = "Highlight the tiles of ground items the current step wants picked up (item spawns, dropped quest items)"
	)
	default boolean showGroundItemMarkers()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showTargetMarker",
		name = "Mark target tile",
		description = "Highlight the exact tile of the current step's captured ⌖ location in the world (dig spots, item spawns)"
	)
	default boolean showTargetMarker()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showTeleportHints",
		name = "Highlight teleport UI",
		description = "When the current step is a minigame teleport, highlight the tab, dropdown entry, and Teleport button to click, Quest Helper-style"
	)
	default boolean showTeleportHints()
	{
		return true;
	}

	@ConfigItem(
		keyName = "autoNavigate",
		name = "Auto-navigate to next step",
		description = "After a step is ticked off, point Shortest Path at the next open step's target (a captured ⌖ location, or a recognised NPC/quest/place name in its text)"
	)
	default boolean autoNavigate()
	{
		return true;
	}

	/**
	 * GREEN on purpose. Quest Helper outlines in cyan, and so did every one
	 * of these overlays, so on a quest step there was no way to tell "the
	 * guide needs this" from "Quest Helper says click this now" — the two
	 * plugins were drawing the same colour over each other (owner, in
	 * play). Green for us, cyan for QH.
	 */
	@ConfigItem(
		keyName = "hintColour",
		name = "Highlight colour",
		description = "Colour of the plugin's item, NPC and object outlines. Green by default so they read apart from Quest Helper's cyan"
	)
	default java.awt.Color hintColour()
	{
		return new java.awt.Color(0, 255, 128);
	}

	@ConfigItem(
		keyName = "showInventoryHints",
		name = "Highlight step items in inventory",
		description = "Outline the carried items the current step is about (its tools, ingredients and tabs) in the inventory"
	)
	default boolean showInventoryHints()
	{
		return true;
	}

	/**
	 * Developer escape hatch, empty for everyone else. See DataFiles: with
	 * a folder set, the guide data, annotations and places are read from
	 * there instead of the jar, so a data correction needs no rebuild and
	 * no restart — type ::ironreload in game and it is live.
	 */
	@ConfigItem(
		keyName = "dataFolder",
		name = "Data folder (developer)",
		description = "Read annotations and places from this folder instead of the bundled copies. Leave empty unless you are editing the plugin's data. Type ::ironreload in game to re-read them"
	)
	default String dataFolder()
	{
		return "";
	}
}
