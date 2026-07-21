package com.bruhsailer;

import com.bruhsailer.annotations.AnnotationManager;
import com.bruhsailer.annotations.StepAnnotation;
import com.bruhsailer.goals.GoalDetector;
import com.bruhsailer.guide.Guide;
import com.bruhsailer.guide.GuideLoader;
import com.bruhsailer.guide.GuideStep;
import com.bruhsailer.guide.GuideVariant;
import com.bruhsailer.guide.SubStep;
import com.bruhsailer.items.ItemTracker;
import com.bruhsailer.panel.BruhsailerPanel;
import com.bruhsailer.places.PlaceManager;
import com.bruhsailer.progress.ProgressManager;
import com.google.inject.Provides;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.events.StatChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PluginMessage;
import net.runelite.client.events.ProfileChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;

/**
 * IRONSCAPE Optimal — the BRUHsailer ironman guide as a RuneLite plugin.
 *
 * Guide by So Iron BRUH and ParasailerOSRS. Web adaptation by kyyznn,
 * improved by Jesper (osrsper). https://osrsper.github.io/BRUHsailer/
 *
 * Internal ids (config group, package, data directory) deliberately keep
 * the "bruhsailer" name so existing progress and annotations survive the
 * rebrand.
 *
 * How RuneLite finds and runs this class:
 * - The @PluginDescriptor annotation marks it as a plugin and provides the
 *   name shown in the client's plugin list.
 * - RuneLite constructs it via dependency injection (Guice). Every field
 *   marked @Inject is filled in for us — we never call `new Client()`.
 * - startUp()/shutDown() are called when the user toggles the plugin on/off
 *   in the config panel (and once at client boot if it's enabled).
 */
@Slf4j // Lombok: generates a `log` field so we can write log.info(...)
@PluginDescriptor(
	name = "IRONSCAPE Optimal",
	description = "Step-by-step ironman guide (BRUHsailer, by So Iron BRUH & ParasailerOSRS) with auto-completion and navigation",
	tags = {"ironman", "guide", "bruhsailer", "ironscape", "efficient"}
)
public class BruhsailerPlugin extends Plugin
{
	private static final String CONFIG_GROUP = "bruhsailer";

	@Inject
	private BruhsailerConfig config;

	@Inject
	private ConfigManager configManager;

	/** The live game client — where the player's position comes from. */
	@Inject
	private Client client;

	/**
	 * Runs code on the game's client thread. Game state (player position,
	 * skills, inventory) must only be read there, never from Swing.
	 */
	@Inject
	private ClientThread clientThread;

	@Inject
	private AnnotationManager annotationManager;

	@Inject
	private ItemTracker itemTracker;

	@Inject
	private ItemManager itemManager;

	@Inject
	private PlaceManager placeManager;

	/**
	 * RuneLite's event bus — how plugins in isolated classloaders talk to
	 * each other. We post PluginMessage events for Shortest Path; if it
	 * isn't installed, nobody is listening and nothing happens.
	 */
	@Inject
	private EventBus eventBus;

	@Inject
	private GuideLoader guideLoader;

	@Inject
	private ProgressManager progressManager;

	/** The client's sidebar, where our navigation button goes. */
	@Inject
	private ClientToolbar clientToolbar;

	/**
	 * A Provider delays construction until we call get() in startUp() —
	 * building Swing components at injection time (before the client UI
	 * exists) would be too early.
	 */
	@Inject
	private Provider<BruhsailerPanel> panelProvider;

	/** Parsed guides, loaded once per client session. */
	private final Map<GuideVariant, Guide> guides = new EnumMap<>(GuideVariant.class);

	/** Step id -> its reviewed skill requirement annotation. */
	private final Map<String, StepRequirement> stepSkillRequirements = new HashMap<>();

	/** Quest goals by sub-step id, for the in-order evaluator. */
	private final Map<String, GoalDetector.QuestGoal> questGoalBySub = new HashMap<>();

	/** Skill-action goals by sub-step id ("Chop down a dying tree" -> WOODCUTTING). */
	private final Map<String, Skill> actionGoalBySub = new HashMap<>();

	/** Sub-step ids completed by a teleport/travel position jump. */
	private final java.util.Set<String> travelGoalSubs = new java.util.HashSet<>();

	/** Sub-step ids that require items LEAVING the inventory (give/fix/...). */
	private final java.util.Set<String> interactionGoalSubs = new java.util.HashSet<>();

	/** Ticks remaining in which a recent item consumption can complete an interaction sub. */
	private int recentConsumeTicks;

	/** Tick of the last bank container change — consumption near it is just banking. */
	private int lastBankEventTick = -10;

	/** Last seen XP per skill, to spot the moment an action grants xp. */
	private final Map<Skill, Integer> lastXpBySkill = new EnumMap<>(Skill.class);

	/** Where the player stood last tick; a big jump means they teleported. */
	private WorldPoint lastTickPosition;

	/** Type this in the bank search box to filter to upcoming guide items. */
	private static final String BANK_FILTER_KEYWORD = "bruh";

	/** How many upcoming sub-steps the bank filter collects items from. */
	private static final int BANK_FILTER_WINDOW = 15;

	/** Cached accepted item names for the bank filter (rebuilt each tick). */
	private java.util.Set<String> bankFilterNames;
	private int bankFilterCacheTick = -1;

	/** Ticks remaining in which a recent teleport can complete a travel sub-step. */
	private int recentTeleportTicks;

	/**
	 * Auto-completion applies to the first few incomplete sub-steps, not
	 * just the very first — one un-tickable prose fragment must not freeze
	 * the whole system — but never further ahead than this.
	 */
	private static final int AUTO_COMPLETE_WINDOW = 8;

	/** Well-known bank locations, for "your items are in the bank" routing. */
	private static final WorldPoint[] BANKS = {
		new WorldPoint(3164, 3487, 0), // Grand Exchange
		new WorldPoint(3185, 3436, 0), // Varrock west
		new WorldPoint(3253, 3420, 0), // Varrock east
		new WorldPoint(3094, 3493, 0), // Edgeville
		new WorldPoint(3092, 3243, 0), // Draynor
		new WorldPoint(3208, 3220, 2), // Lumbridge castle
		new WorldPoint(3269, 3167, 0), // Al Kharid
		new WorldPoint(2945, 3368, 0), // Falador west
		new WorldPoint(3013, 3355, 0), // Falador east
		new WorldPoint(2808, 3441, 0), // Catherby
		new WorldPoint(2725, 3491, 0), // Seers' Village
		new WorldPoint(2615, 3332, 0), // Ardougne north
		new WorldPoint(2655, 3283, 0), // Ardougne south
	};

	/** How close (tiles) counts as "arrived" for a location goal. */
	private static final int ARRIVE_RADIUS = 8;

	/** Text-detected "get N items" / "start quest X" goals (see GoalDetector). */
	private GoalDetector.Goals goals;

	/**
	 * Item goals grouped by sub-step id. A sub-step like "Buy 1250 nature
	 * runes and 700 law runes" has two goals and completes only when BOTH
	 * counts are met.
	 */
	private final Map<String, List<GoalDetector.ItemGoal>> itemGoalsBySub = new LinkedHashMap<>();

	/**
	 * Ticks left before goal completions announce in chat. Login floods
	 * events (bank load, quest sync); anything completed during the grace
	 * window completes silently.
	 */
	private int loginGraceTicks;

	private int tickCounter;

	private BruhsailerPanel panel;
	private NavigationButton navButton;

	@Override
	protected void startUp() throws Exception
	{
		annotationManager.load();
		placeManager.load();
		rebuildStepRequirements();
		goals = GoalDetector.detect(guideFor(GuideVariant.MAIN));
		itemGoalsBySub.clear();
		for (GoalDetector.ItemGoal goal : goals.getItemGoals())
		{
			itemGoalsBySub.computeIfAbsent(goal.getSub().getId(), id -> new ArrayList<>()).add(goal);
		}
		questGoalBySub.clear();
		for (GoalDetector.QuestGoal goal : goals.getQuestGoals())
		{
			questGoalBySub.put(goal.getSub().getId(), goal);
		}
		actionGoalBySub.clear();
		for (GoalDetector.SkillActionGoal goal : goals.getSkillActionGoals())
		{
			actionGoalBySub.put(goal.getSub().getId(), goal.getSkill());
		}
		travelGoalSubs.clear();
		for (GoalDetector.TravelGoal goal : goals.getTravelGoals())
		{
			travelGoalSubs.add(goal.getSub().getId());
		}
		interactionGoalSubs.clear();
		for (GoalDetector.InteractionGoal goal : goals.getInteractionGoals())
		{
			interactionGoalSubs.add(goal.getSub().getId());
		}
		log.info("Detected {} item goals and {} quest goals in the guide text",
			goals.getItemGoals().size(), goals.getQuestGoals().size());

		panel = panelProvider.get();
		panel.setItemGoals(itemGoalsBySub);
		panel.setProgressChangedListener(this::maybeNavigateToNext);
		panel.setCaptureHandler(this::captureLocation);
		panel.setNavigateHandler(this::navigateToStep);
		panel.setPlaceNavigateHandler(this::navigateToPlace);
		panel.setAddPlaceHandler(this::addPlace);
		panel.setClearPathHandler(this::clearPath);
		panel.setGuide(guideFor(GuideVariant.MAIN));

		// If we start while already logged in (plugin toggled mid-session),
		// prime the item counts; otherwise the login event does it.
		clientThread.invoke(() -> {
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				itemTracker.onLoggedIn();
			}
		});

		navButton = NavigationButton.builder()
			.tooltip("IRONSCAPE Optimal")
			.icon(drawIcon())
			.priority(6)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		Guide guide = guideFor(GuideVariant.MAIN);
		log.info("IRONSCAPE Optimal started: loaded '{}' ({}), {} chapters, {} steps",
			guide.getTitle(), guide.getUpdatedOn(),
			guide.getChapters().size(), guide.getAllSteps().size());
	}

	@Override
	protected void shutDown() throws Exception
	{
		clientToolbar.removeNavigation(navButton);
		navButton = null;
		panel = null;
		guides.clear();
		stepSkillRequirements.clear();
		questGoalBySub.clear();
		itemGoalsBySub.clear();
		actionGoalBySub.clear();
		travelGoalSubs.clear();
		interactionGoalSubs.clear();
		lastXpBySkill.clear();
		lastTickPosition = null;
		goals = null;
		log.info("IRONSCAPE Optimal stopped");
	}

	/**
	 * Fires whenever ANY config value changes (ours or another plugin's),
	 * from the settings UI, our panel, or a profile sync. React only to
	 * our group. Events can arrive off the Swing thread, so UI updates are
	 * wrapped in SwingUtilities.invokeLater.
	 */
	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!CONFIG_GROUP.equals(event.getGroup()) || panel == null)
		{
			return;
		}
		if ("showCompletedSteps".equals(event.getKey())
			|| "showCaptureButtons".equals(event.getKey()))
		{
			SwingUtilities.invokeLater(panel::refresh);
		}
	}

	/**
	 * The user switched RuneLite profiles: the progress we have cached
	 * belongs to the old profile, so drop it and re-render.
	 */
	@Subscribe
	public void onProfileChanged(ProfileChanged event)
	{
		progressManager.invalidate();
		if (panel != null)
		{
			SwingUtilities.invokeLater(panel::refresh);
		}
	}

	/**
	 * Fires on the client thread whenever any skill's xp/level changes —
	 * including once per skill right after login. That login flood is what
	 * brings saved progress up to date on a fresh account state.
	 */
	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		// The first event per skill after login just sets the baseline —
		// only a genuine increase counts as "you did the action".
		Integer previousXp = lastXpBySkill.put(event.getSkill(), event.getXp());
		boolean gainedXp = previousXp != null && event.getXp() > previousXp;

		if (gainedXp && config.autoCompleteSteps())
		{
			for (Current current : findWindow(AUTO_COMPLETE_WINDOW))
			{
				if (!itemGoalsBySub.containsKey(current.sub.getId())
					&& event.getSkill() == actionGoalBySub.get(current.sub.getId()))
				{
					// "Chop down a dying tree" + Woodcutting xp = done.
					completeSubGoal(current.step, current.sub);
					break;
				}
			}
		}
		evaluateAutoCompletion();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			loginGraceTicks = 10;
			lastXpBySkill.clear(); // next account/session sets fresh baselines
			itemTracker.onLoggedIn();
			if (panel != null)
			{
				SwingUtilities.invokeLater(panel::refreshItemCounts);
			}
		}
	}

	/** Fires on the client thread whenever any item container changes. */
	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		itemTracker.onItemContainerChanged(event);

		if (event.getContainerId() == net.runelite.api.gameval.InventoryID.BANK)
		{
			// Banking, not giving: cancel any consumption signal.
			lastBankEventTick = tickCounter;
			recentConsumeTicks = 0;
		}
		else if (itemTracker.lastRebuildConsumedCarried()
			&& tickCounter - lastBankEventTick > 2 && loginGraceTicks == 0)
		{
			// Something left the player's hands away from a bank — the
			// signal that a give/fix/build interaction actually happened.
			recentConsumeTicks = 10;
		}

		evaluateAutoCompletion();
		if (panel != null)
		{
			SwingUtilities.invokeLater(panel::refreshItemCounts);
		}
	}

	/** Once per game tick (0.6s) on the client thread. */
	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (loginGraceTicks > 0)
		{
			loginGraceTicks--;
		}
		if (recentTeleportTicks > 0)
		{
			recentTeleportTicks--;
		}
		if (recentConsumeTicks > 0)
		{
			recentConsumeTicks--;
		}

		// Teleport detection: nobody walks 20+ tiles (or changes plane
		// without stairs being that fast) in a single 0.6s tick.
		Player player = client.getLocalPlayer();
		if (player != null)
		{
			WorldPoint here = player.getWorldLocation();
			if (lastTickPosition != null && loginGraceTicks == 0
				&& (here.getPlane() != lastTickPosition.getPlane()
					|| lastTickPosition.distanceTo2D(here) > 20))
			{
				recentTeleportTicks = 8;
			}
			lastTickPosition = here;
		}

		// Quest state and player position have no change events of their
		// own; polling every other tick is cheap.
		if (++tickCounter % 2 == 0)
		{
			evaluateAutoCompletion();
		}
	}

	/**
	 * The bank's layout script asks every plugin about every bank slot
	 * whenever a bank search is active. When the search text is our
	 * keyword, we answer "show it" for items the guide needs soon — the
	 * same trick Quest Helper's bank tab uses, minus the custom widgets.
	 */
	@Subscribe
	public void onScriptCallbackEvent(ScriptCallbackEvent event)
	{
		if (!"bankSearchFilter".equals(event.getEventName()))
		{
			return;
		}
		Object[] objectStack = client.getObjectStack();
		String search = (String) objectStack[client.getObjectStackSize() - 1];
		if (search == null || !BANK_FILTER_KEYWORD.equalsIgnoreCase(search.trim()))
		{
			return;
		}

		int[] intStack = client.getIntStack();
		int intStackSize = client.getIntStackSize();
		int itemId = intStack[intStackSize - 1];

		String itemName = itemManager.getItemComposition(itemManager.canonicalize(itemId))
			.getName().toLowerCase(java.util.Locale.ROOT);
		if (upcomingItemNames().contains(itemName))
		{
			intStack[intStackSize - 2] = 1; // 1 = include this bank slot
		}
	}

	/** Item names needed by the next BANK_FILTER_WINDOW sub-steps (alias-expanded). */
	private java.util.Set<String> upcomingItemNames()
	{
		if (bankFilterCacheTick == tickCounter && bankFilterNames != null)
		{
			return bankFilterNames;
		}
		java.util.Set<String> names = new java.util.HashSet<>();
		for (Current current : findWindow(BANK_FILTER_WINDOW))
		{
			List<GoalDetector.ItemGoal> itemGoals = itemGoalsBySub.get(current.sub.getId());
			if (itemGoals != null)
			{
				for (GoalDetector.ItemGoal goal : itemGoals)
				{
					java.util.Collections.addAll(names, ItemTracker.aliases(goal.getItemName()));
				}
			}
			for (StepAnnotation.ItemNeed need : annotationManager.getItems(current.sub.getId()))
			{
				java.util.Collections.addAll(names, ItemTracker.aliases(need.name));
			}
			for (StepAnnotation.ItemNeed need : annotationManager.getItems(current.step.getId()))
			{
				java.util.Collections.addAll(names, ItemTracker.aliases(need.name));
			}
		}
		bankFilterNames = names;
		bankFilterCacheTick = tickCounter;
		return names;
	}

	/**
	 * The heart of auto-completion, and deliberately IN ORDER: only the
	 * CURRENT sub-step (the first incomplete one in guide order) can
	 * auto-tick. When it does, the loop immediately re-checks the next one,
	 * so catching up cascades — but nothing downstream ever ticks early,
	 * and the map always routes toward the real frontier. Client thread.
	 */
	private void evaluateAutoCompletion()
	{
		if (!config.autoCompleteSteps() || goals == null
			|| client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		Guide guide = guideFor(GuideVariant.MAIN);
		boolean progressed = false;

		for (int guard = 0; guard < 100; guard++)
		{
			boolean completedSomething = false;
			for (Current current : findWindow(AUTO_COMPLETE_WINDOW))
			{
				// A reviewed skill requirement completes a WHOLE step.
				StepRequirement requirement = stepSkillRequirements.get(current.step.getId());
				if (requirement != null
					&& client.getRealSkillLevel(requirement.skill) >= requirement.level)
				{
					completeStep(current.step);
					completedSomething = true;
					break; // window shifted; rebuild it
				}

				if (currentSubSatisfied(current.step, current.sub))
				{
					completeSubGoal(current.step, current.sub);
					if (travelGoalSubs.contains(current.sub.getId()))
					{
						// one teleport completes one travel sub-step
						recentTeleportTicks = 0;
					}
					if (interactionGoalSubs.contains(current.sub.getId()))
					{
						// one consumption completes one interaction sub-step
						recentConsumeTicks = 0;
					}
					completedSomething = true;
					break;
				}
			}
			if (!completedSomething)
			{
				break;
			}
			progressed = true;
		}

		if (progressed)
		{
			maybeNavigateToNext();
		}
	}

	/** The first incomplete sub-step in guide order — "what you're on now". */
	private Current findCurrent()
	{
		List<Current> window = findWindow(1);
		return window.isEmpty() ? null : window.get(0);
	}

	/** The first `limit` incomplete sub-steps in guide order. */
	private List<Current> findWindow(int limit)
	{
		List<Current> window = new ArrayList<>();
		Guide guide = guideFor(GuideVariant.MAIN);
		for (GuideStep step : guide.getAllSteps())
		{
			if (progressManager.isCompleted(GuideVariant.MAIN, step.getId()))
			{
				continue;
			}
			for (SubStep sub : step.getSubSteps())
			{
				if (!progressManager.isSubCompleted(GuideVariant.MAIN, step, sub))
				{
					window.add(new Current(step, sub));
					if (window.size() >= limit)
					{
						return window;
					}
				}
			}
		}
		return window;
	}

	private static class Current
	{
		final GuideStep step;
		final SubStep sub;

		Current(GuideStep step, SubStep sub)
		{
			this.step = step;
			this.sub = sub;
		}
	}

	/** Is the current sub-step's goal met? Items beat quests beat arrival. */
	private boolean currentSubSatisfied(GuideStep step, SubStep sub)
	{
		List<GoalDetector.ItemGoal> itemGoals = itemGoalsBySub.get(sub.getId());
		if (itemGoals != null)
		{
			for (GoalDetector.ItemGoal goal : itemGoals)
			{
				// Carried only: items sitting in the bank show an orange
				// badge but do NOT tick the step for you.
				if (itemTracker.carriedCountOf(goal.getItemName()) < goal.getQuantity())
				{
					return false;
				}
			}
			return true;
		}

		GoalDetector.QuestGoal questGoal = questGoalBySub.get(sub.getId());
		if (questGoal != null)
		{
			QuestState state = questGoal.getQuest().getState(client);
			return questGoal.isRequiresFinished()
				? state == QuestState.FINISHED
				: state != QuestState.NOT_STARTED;
		}

		// "Give the letter to Romeo" / "Fix his house" — something must have
		// LEFT the inventory (and if the step has a target, near it too).
		if (interactionGoalSubs.contains(sub.getId()))
		{
			if (recentConsumeTicks <= 0)
			{
				return false;
			}
			WorldPoint interactionTarget = targetFor(step, sub);
			if (interactionTarget == null)
			{
				return true;
			}
			Player me = client.getLocalPlayer();
			return me != null
				&& me.getWorldLocation().getPlane() == interactionTarget.getPlane()
				&& me.getWorldLocation().distanceTo(interactionTarget) <= ARRIVE_RADIUS;
		}

		// "Teleport using the chronicle" — a recent position jump proves it.
		if (travelGoalSubs.contains(sub.getId()))
		{
			return recentTeleportTicks > 0;
		}

		// No item/quest goal: a movement step. Arriving at its target
		// (⌖ capture or recognised place name) completes it.
		WorldPoint target = targetFor(step, sub);
		Player player = client.getLocalPlayer();
		if (target == null || player == null)
		{
			return false;
		}
		WorldPoint here = player.getWorldLocation();
		return here.getPlane() == target.getPlane()
			&& here.distanceTo(target) <= ARRIVE_RADIUS;
	}

	/** A whole step completed by its skill requirement annotation. */
	private void completeStep(GuideStep step)
	{
		progressManager.setCompleted(GuideVariant.MAIN, step, true);
		if (loginGraceTicks == 0)
		{
			String text = step.getPlainText().trim();
			if (text.length() > 60)
			{
				text = text.substring(0, 57) + "...";
			}
			client.addChatMessage(ChatMessageType.CONSOLE, "", "IRONSCAPE: ✓ " + text, null);
		}
		String stepId = step.getId();
		SwingUtilities.invokeLater(() -> {
			if (panel != null)
			{
				panel.markStepCompleted(stepId);
			}
		});
	}

	/** Mark one goal sub-step done: persist, announce (unless just logged in), update the panel. */
	private void completeSubGoal(GuideStep step, SubStep sub)
	{
		progressManager.setSubCompleted(GuideVariant.MAIN, step, sub, true);

		if (loginGraceTicks == 0)
		{
			String text = sub.getPlainText().trim();
			if (text.length() > 60)
			{
				text = text.substring(0, 57) + "...";
			}
			client.addChatMessage(ChatMessageType.CONSOLE, "", "IRONSCAPE: ✓ " + text, null);
		}

		String stepId = step.getId();
		String subId = sub.getId();
		SwingUtilities.invokeLater(() -> {
			if (panel != null)
			{
				panel.markSubCompleted(stepId, subId);
			}
		});
	}

	private void rebuildStepRequirements()
	{
		stepSkillRequirements.clear();
		annotationManager.allRequirements().forEach((stepId, requires) -> {
			if (requires.skill == null || requires.level == null)
			{
				return;
			}
			Skill skill;
			try
			{
				skill = Skill.valueOf(requires.skill);
			}
			catch (IllegalArgumentException e)
			{
				log.warn("Annotation for step {} names unknown skill '{}'", stepId, requires.skill);
				return;
			}
			stepSkillRequirements.put(stepId, new StepRequirement(skill, requires.level));
		});
	}

	/** "This step is done at skill level N", from a reviewed annotation. */
	private static class StepRequirement
	{
		final Skill skill;
		final int level;

		StepRequirement(Skill skill, int level)
		{
			this.skill = skill;
			this.level = level;
		}
	}

	/**
	 * The capture button was clicked (on the Swing thread). Hop to the
	 * client thread to read the player's position, save it, then hop back
	 * to Swing to update the button.
	 */
	private void captureLocation(String annotationId, Consumer<Boolean> onDone)
	{
		clientThread.invoke(() -> {
			Player player = client.getLocalPlayer();
			if (client.getGameState() != GameState.LOGGED_IN || player == null)
			{
				SwingUtilities.invokeLater(() -> onDone.accept(false));
				return;
			}

			WorldPoint where = player.getWorldLocation();
			annotationManager.setTarget(annotationId, where);
			// Confirm in the chatbox so you don't have to look at the panel.
			client.addChatMessage(ChatMessageType.CONSOLE, "",
				"IRONSCAPE: location (" + where.getX() + ", " + where.getY()
					+ (where.getPlane() != 0 ? ", plane " + where.getPlane() : "")
					+ ") saved for " + annotationId, null);
			SwingUtilities.invokeLater(() -> onDone.accept(true));
		});
	}

	/**
	 * Ask the Shortest Path plugin to draw a route from the player to this
	 * step's annotated target. The message namespace/keys are Shortest
	 * Path's documented PluginMessage API ("shortestpath" / "path" /
	 * "target"); a WorldPoint is accepted directly because net.runelite.api
	 * classes are shared across plugin classloaders.
	 */
	/**
	 * Point Shortest Path at the next thing to do: the first incomplete
	 * sub-step (in guide order) that has a resolvable target. Looks only a
	 * few sub-steps ahead so we never route toward something far downstream
	 * of work that still needs doing here.
	 */
	private void maybeNavigateToNext()
	{
		if (!config.autoNavigate())
		{
			return;
		}
		clientThread.invokeLater(() -> {
			WorldPoint target = findNextTarget();
			if (target != null)
			{
				eventBus.post(new PluginMessage("shortestpath", "path", Map.of("target", target)));
			}
			else
			{
				// The next thing to do has no known location — clear the
				// route so a STALE one (last step's quest etc.) doesn't
				// keep pointing somewhere you no longer need to go.
				eventBus.post(new PluginMessage("shortestpath", "clear"));
			}
		});
	}

	/** The target of the first incomplete sub-step, scanning at most a few ahead. */
	private WorldPoint findNextTarget()
	{
		List<Current> window = findWindow(5);
		if (window.isEmpty())
		{
			return null;
		}

		// If the frontier sub-step needs items that sit in the BANK, the
		// journey starts at a bank, not at the step's destination.
		List<GoalDetector.ItemGoal> frontierItems = itemGoalsBySub.get(window.get(0).sub.getId());
		if (frontierItems != null)
		{
			boolean allOwned = true;
			boolean allCarried = true;
			for (GoalDetector.ItemGoal goal : frontierItems)
			{
				allOwned &= itemTracker.countOf(goal.getItemName()) >= goal.getQuantity();
				allCarried &= itemTracker.carriedCountOf(goal.getItemName()) >= goal.getQuantity();
			}
			if (allOwned && !allCarried)
			{
				WorldPoint bank = nearestBank();
				if (bank != null)
				{
					return bank;
				}
			}
		}

		for (Current current : window)
		{
			WorldPoint target = targetFor(current.step, current.sub);
			if (target != null)
			{
				return target;
			}
		}
		return null;
	}

	/** The closest well-known bank to the player (straight-line distance). */
	private WorldPoint nearestBank()
	{
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return null;
		}
		WorldPoint here = player.getWorldLocation();
		WorldPoint best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (WorldPoint bank : BANKS)
		{
			int distance = here.distanceTo2D(bank);
			if (distance < bestDistance)
			{
				bestDistance = distance;
				best = bank;
			}
		}
		return best;
	}

	/**
	 * A sub-step's navigation target: its own ⌖ capture, else the step's
	 * capture (single-action steps), else the first recognised place name
	 * in its text ("Talk to Reldo" -> Reldo).
	 */
	private WorldPoint targetFor(GuideStep step, SubStep sub)
	{
		StepAnnotation.Target target = annotationManager.getTarget(sub.getId());
		if (target == null && step.getSubSteps().size() == 1)
		{
			target = annotationManager.getTarget(step.getId());
		}
		if (target != null)
		{
			return new WorldPoint(target.x, target.y, target.plane);
		}
		return placeManager.firstPlaceIn(sub.getPlainText());
	}

	private void navigateToStep(String annotationId)
	{
		StepAnnotation.Target target = annotationManager.getTarget(annotationId);
		if (target != null)
		{
			navigateTo(new WorldPoint(target.x, target.y, target.plane));
		}
	}

	/** A place-name link was clicked in the step text. */
	private void navigateToPlace(String placeName)
	{
		WorldPoint point = placeManager.get(placeName);
		if (point == null)
		{
			return;
		}

		// Quest links point at the quest's START. Once the quest is under
		// way that's the wrong place — Quest Helper (its own plugin) is the
		// tool that knows the current quest step; no API exists for us to
		// ask it, so we say so instead of routing somewhere misleading.
		Quest quest = questByName(placeName);
		if (quest != null)
		{
			clientThread.invokeLater(() -> {
				QuestState state = quest.getState(client);
				if (state == QuestState.NOT_STARTED)
				{
					eventBus.post(new PluginMessage("shortestpath", "path", Map.of("target", point)));
				}
				else
				{
					client.addChatMessage(ChatMessageType.CONSOLE, "",
						"IRONSCAPE: " + quest.getName()
							+ (state == QuestState.FINISHED
								? " is already finished."
								: " is in progress — its start point isn't where you need to go. "
									+ "Select it in Quest Helper for step-by-step guidance."),
						null);
				}
			});
			return;
		}

		navigateTo(point);
	}

	private static Quest questByName(String name)
	{
		for (Quest quest : Quest.values())
		{
			if (quest.getName().equalsIgnoreCase(name))
			{
				return quest;
			}
		}
		return null;
	}

	private void navigateTo(WorldPoint point)
	{
		// Post on the client thread: Shortest Path reads game state
		// (player position as the route start) in its handler.
		clientThread.invokeLater(() ->
			eventBus.post(new PluginMessage("shortestpath", "path", Map.of("target", point))));
	}

	/** Toolbar "+" button: name the player's current tile as a place. */
	private void addPlace(String placeName, Consumer<Boolean> onDone)
	{
		clientThread.invoke(() -> {
			Player player = client.getLocalPlayer();
			if (client.getGameState() != GameState.LOGGED_IN || player == null)
			{
				SwingUtilities.invokeLater(() -> onDone.accept(false));
				return;
			}
			WorldPoint where = player.getWorldLocation();
			placeManager.add(placeName, where);
			client.addChatMessage(ChatMessageType.CONSOLE, "",
				"IRONSCAPE: place '" + placeName + "' saved at ("
					+ where.getX() + ", " + where.getY() + ").", null);
			SwingUtilities.invokeLater(() -> onDone.accept(true));
		});
	}

	private void clearPath()
	{
		clientThread.invokeLater(() ->
			eventBus.post(new PluginMessage("shortestpath", "clear")));
	}

	private Guide guideFor(GuideVariant variant)
	{
		return guides.computeIfAbsent(variant, v -> {
			try
			{
				return guideLoader.load(v);
			}
			catch (IOException e)
			{
				// Bundled resources can't really be missing; if this ever
				// fires something is badly wrong with the jar itself.
				throw new IllegalStateException("Could not load bundled guide " + v, e);
			}
		});
	}

	/**
	 * Sidebar icon, drawn in code (16x16): an orange square with "IO".
	 * TODO replace with real icon art before a Plugin Hub release.
	 */
	private static BufferedImage drawIcon()
	{
		BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(230, 138, 23)); // RuneLite-ish orange
		g.fillRoundRect(0, 0, 16, 16, 5, 5);
		g.setColor(Color.WHITE);
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 9));
		g.setStroke(new BasicStroke(1f));
		// Centered by eye on the 16px square.
		g.drawString("IO", 2, 12);
		g.dispose();
		return image;
	}

	/**
	 * Tells Guice how to build our config object. RuneLite backs it with the
	 * user's settings storage, so values persist across sessions. Every
	 * plugin with a config interface has one of these @Provides methods.
	 */
	@Provides
	BruhsailerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BruhsailerConfig.class);
	}
}
