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
import java.util.Locale;
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
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.client.game.ItemManager;
import com.bruhsailer.items.BankFilterButton;
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
	private BankFilterButton bankFilterButton;

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

	@Inject
	private com.bruhsailer.guide.GuideManifest guideManifest;

	/**
	 * Old->new ids for steps a guide refresh edited in place, kept for
	 * the whole session: progress lives per RuneLite profile, so every
	 * profile that becomes active needs the same remap applied once.
	 */
	private Map<String, String> guideRemap = new HashMap<>();

	/** The client's sidebar, where our navigation button goes. */
	@Inject
	private ClientToolbar clientToolbar;

	/** Registry of on-screen overlays; ours highlights teleport UI widgets. */
	@Inject
	private net.runelite.client.ui.overlay.OverlayManager overlayManager;

	/** RuneLite's downloaded world list, for "world 444" hop links. */
	@Inject
	private net.runelite.client.game.WorldService worldService;

	/** All loaded plugins — used to find Quest Helper for the quest handoff. */
	@Inject
	private net.runelite.client.plugins.PluginManager pluginManager;

	@Inject
	private com.bruhsailer.overlay.MinigameTeleportOverlay minigameTeleportOverlay;

	@Inject
	private com.bruhsailer.overlay.StepOverlay stepOverlay;

	@Inject
	private com.bruhsailer.overlay.QuestStartMarkerOverlay questStartMarkerOverlay;

	@Inject
	private com.bruhsailer.overlay.NpcTargetOverlay npcTargetOverlay;

	@Inject
	private com.bruhsailer.overlay.TargetTileOverlay targetTileOverlay;

	/** Lowercased names of scene NPCs the current sub mentions. Written per tick. */
	private volatile java.util.Set<String> npcTargetNames = java.util.Collections.emptySet();

	/** True while the current sub is a quest goal (adds the NPC quest icon). */
	private volatile boolean currentSubIsQuest;

	/** Where the quest-start icon floats; null = hidden. Written per tick. */
	private volatile WorldPoint questStartMarker;

	/** The current sub's annotated ⌖ target tile; null = hidden. Written per tick. */
	private volatile WorldPoint targetTileMarker;

	/** Quest whose start marker was requested by clicking its link. */
	private Quest clickedQuest;
	private int clickedQuestTicks;

	/** Snapshot the step overlay draws; rebuilt once per game tick. */
	private volatile com.bruhsailer.overlay.StepOverlay.Model stepOverlayModel;

	/**
	 * A Provider delays construction until we call get() in startUp() —
	 * building Swing components at injection time (before the client UI
	 * exists) would be too early.
	 */
	@Inject
	private Provider<BruhsailerPanel> panelProvider;

	/** Parsed guides, loaded once per client session. */
	private final Map<GuideVariant, Guide> guides = new EnumMap<>(GuideVariant.class);

	/**
	 * The guide being followed, read from config ONCE at startUp — every
	 * progress/annotation/goal structure in this class is built for one
	 * variant, so changing it mid-session requires a plugin restart
	 * (toggle off/on), as the config item says.
	 */
	private GuideVariant activeVariant = GuideVariant.OZIRIS;

	/** Step id -> its reviewed requirements (ALL must be met to auto-complete). */
	private final Map<String, List<StepRequirement>> stepSkillRequirements = new HashMap<>();

	/**
	 * Sub id ("stepId:N") -> requirements ticking JUST that sub — used for
	 * mid-quest checkpoints ("do the quest up to the orb" via a quest
	 * progress varbit) inside steps that also hold unrelated errands.
	 */
	private final Map<String, List<StepRequirement>> subRequirements = new HashMap<>();

	/** Quest goals by sub-step id, for the in-order evaluator. */
	private final Map<String, GoalDetector.QuestGoal> questGoalBySub = new HashMap<>();

	/** Skill-action goals by sub-step id ("Chop down a dying tree" -> WOODCUTTING). */
	private final Map<String, Skill> actionGoalBySub = new HashMap<>();

	/** Sub-step ids completed by a teleport/travel position jump. */
	private final java.util.Set<String> travelGoalSubs = new java.util.HashSet<>();

	/** Sub-step ids that require items LEAVING the inventory (give/fix/...). */
	private final java.util.Set<String> interactionGoalSubs = new java.util.HashSet<>();

	/** "train construction (6 chairs...)" goals: N xp drops complete the sub. */
	private final Map<String, GoalDetector.CountedSkillGoal> countedGoalBySub =
		new java.util.concurrent.ConcurrentHashMap<>();

	/** Sub-step id -> minigame name for "Minigame teleport to X" subs. */
	private final Map<String, String> minigameBySub = new HashMap<>();

	/** Level goals by sub id ("burn them to level 50 firemaking"). */
	private final Map<String, List<GoalDetector.SkillLevelGoal>> levelGoalsBySub = new HashMap<>();

	/**
	 * Latest REAL (unboosted) level per skill. Written on the client
	 * thread from StatChanged, read from Swing by the level badges —
	 * hence a concurrent map rather than an EnumMap.
	 */
	private final Map<Skill, Integer> realLevelBySkill = new java.util.concurrent.ConcurrentHashMap<>();

	/** World the user asked to hop to (clicked "world 444"); consumed on game ticks. */
	private net.runelite.api.World pendingHopWorld;

	/** Ticks spent waiting for the world switcher to open before giving up. */
	private int hopAttempts;

	/**
	 * The minigame the CURRENT sub-step wants to teleport to, or null.
	 * Written on the game thread each tick, read by the overlay at render
	 * time — hence volatile.
	 */
	private volatile String activeMinigameTarget;

	/**
	 * Minigame hint requested by CLICKING its name in the panel (e.g. the
	 * "Soul Wars" link) — shown even when that sub isn't the current one.
	 * Cleared by the teleport happening, or after the tick countdown.
	 */
	private volatile String clickedMinigameTarget;
	private int clickedMinigameTicks;

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

	/**
	 * Well-known bank locations, for "your items are in the bank" routing.
	 * Includes bank CHESTS, not just booths — routing someone standing at
	 * Port Khazard all the way to Ardougne is worse than useless. Targets
	 * only need to be near the bank; Shortest Path ends the trail there.
	 */
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
		new WorldPoint(2664, 3161, 0), // Port Khazard bank chest
		new WorldPoint(2443, 3083, 0), // Castle Wars bank chest
		new WorldPoint(3130, 3631, 0), // Ferox Enclave bank chest
		new WorldPoint(3308, 3120, 0), // Shantay Pass bank chest
		new WorldPoint(2613, 3093, 0), // Yanille
		new WorldPoint(3045, 3234, 0), // Port Sarim
		new WorldPoint(2586, 3420, 0), // Fishing Guild
		new WorldPoint(1640, 3944, 0), // Wintertodt camp bank chest
		new WorldPoint(1512, 3421, 0), // Land's End bank chest
		new WorldPoint(1591, 3479, 0), // Woodcutting Guild bank chest
		new WorldPoint(1749, 3599, 0), // Hosidius
		new WorldPoint(1624, 3745, 0), // Arceuus
		new WorldPoint(2852, 2954, 0), // Shilo Village
		new WorldPoint(3512, 3480, 0), // Canifis
		new WorldPoint(3688, 3467, 0), // Port Phasmatys
		new WorldPoint(2383, 4458, 0), // Zanaris bank chest
		new WorldPoint(3381, 3268, 0), // PvP Arena bank
		new WorldPoint(3428, 2892, 0), // Nardah
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
	 * "subId|item" -> carried count when an acquisition ("buy X") sub first
	 * became current. Ticking needs the count to RISE above this. Session
	 * state only, cleared on shutdown and profile switch.
	 */
	private final Map<String, Integer> acquisitionBaseline = new HashMap<>();

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
		activeVariant = config.activeGuide();
		annotationManager.load();

		// Did a guide refresh edit steps in place since last run? If so
		// their ids changed (ids hash the text) — re-link saved progress
		// and annotations BEFORE anything reads them.
		Guide loadedGuide = guideFor(activeVariant);
		guideRemap = guideManifest.reconcile(loadedGuide);
		if (!guideRemap.isEmpty())
		{
			progressManager.remapIds(activeVariant, guideRemap);
			int moved = annotationManager.remapIds(guideRemap);
			// The remap holds one entry per edited step plus one per sub
			// clause of sub-aware steps; count only the step entries here.
			long editedSteps = guideRemap.keySet().stream().filter(k -> k.indexOf(':') < 0).count();
			log.info("Guide update: re-linked {} edited step(s) to saved progress ({} annotation(s) moved)",
				editedSteps, moved);
		}
		guideManifest.save(loadedGuide);

		placeManager.load();
		rebuildStepRequirements();
		goals = GoalDetector.detect(guideFor(activeVariant));
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
		countedGoalBySub.clear();
		for (GoalDetector.CountedSkillGoal goal : goals.getCountedSkillGoals())
		{
			countedGoalBySub.put(goal.getSub().getId(), goal);
		}
		minigameBySub.clear();
		for (GoalDetector.MinigameTeleportGoal goal : goals.getMinigameTeleportGoals())
		{
			minigameBySub.put(goal.getSub().getId(), goal.getMinigame());
		}
		levelGoalsBySub.clear();
		for (GoalDetector.SkillLevelGoal goal : goals.getSkillLevelGoals())
		{
			levelGoalsBySub.computeIfAbsent(goal.getSub().getId(), id -> new ArrayList<>()).add(goal);
		}
		log.info("Detected {} item goals and {} quest goals in the guide text",
			goals.getItemGoals().size(), goals.getQuestGoals().size());

		cleanupStaleAmbientTicks();

		minigameTeleportOverlay.setTargetSupplier(() -> activeMinigameTarget);
		overlayManager.add(minigameTeleportOverlay);
		stepOverlay.setModelSupplier(() -> stepOverlayModel);
		overlayManager.add(stepOverlay);
		questStartMarkerOverlay.setTargetSupplier(() -> questStartMarker);
		overlayManager.add(questStartMarkerOverlay);
		npcTargetOverlay.setNamesSupplier(() -> npcTargetNames);
		npcTargetOverlay.setQuestIconSupplier(() -> currentSubIsQuest);
		overlayManager.add(npcTargetOverlay);
		targetTileOverlay.setTargetSupplier(() -> targetTileMarker);
		overlayManager.add(targetTileOverlay);

		panel = panelProvider.get();
		panel.setItemGoals(itemGoalsBySub);
		panel.setActionBadgeSupplier(subId -> {
			// Level goals first: "firemaking 43/50", same color rules as
			// item badges (orange = in progress, green = met).
			List<GoalDetector.SkillLevelGoal> levels = levelGoalsBySub.get(subId);
			if (levels != null)
			{
				StringBuilder sb = new StringBuilder();
				for (GoalDetector.SkillLevelGoal goal : levels)
				{
					int have = realLevelBySkill.getOrDefault(goal.getSkill(), 1);
					String levelColor = have >= goal.getLevel() ? "#4caf50" : "#ffa000";
					if (sb.length() > 0)
					{
						sb.append(" <font color='#606060'>·</font> ");
					}
					sb.append("<font color='").append(levelColor).append("'>")
						.append(goal.getSkill().getName().toLowerCase())
						.append(' ').append(have).append('/').append(goal.getLevel())
						.append("</font>");
				}
				return sb.toString();
			}
			GoalDetector.CountedSkillGoal counted = countedGoalBySub.get(subId);
			if (counted == null)
			{
				return null;
			}
			int seen = Math.min(progressManager.countedProgress(activeVariant, subId),
				counted.getCount());
			String color = seen >= counted.getCount() ? "#4caf50" : "#ffa000";
			return "<font color='" + color + "'>" + counted.getSkill().getName().toLowerCase()
				+ " " + seen + "/" + counted.getCount() + " done</font>";
		});
		panel.setProgressChangedListener(this::maybeNavigateToNext);
		panel.setCaptureHandler(this::captureLocation);
		panel.setNavigateHandler(this::navigateToStep);
		panel.setPlaceNavigateHandler(this::navigateToPlace);
		panel.setWorldHopHandler(this::hopToWorld);
		panel.setAddPlaceHandler(this::addPlace);
		panel.setClearPathHandler(this::clearPath);
		panel.setGuide(guideFor(activeVariant));

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

		Guide guide = guideFor(activeVariant);
		log.info("IRONSCAPE Optimal started: loaded '{}' ({}), {} chapters, {} steps",
			guide.getTitle(), guide.getUpdatedOn(),
			guide.getChapters().size(), guide.getAllSteps().size());
	}

	@Override
	protected void shutDown() throws Exception
	{
		overlayManager.remove(minigameTeleportOverlay);
		overlayManager.remove(stepOverlay);
		overlayManager.remove(questStartMarkerOverlay);
		overlayManager.remove(npcTargetOverlay);
		overlayManager.remove(targetTileOverlay);
		npcTargetNames = java.util.Collections.emptySet();
		currentSubIsQuest = false;
		questStartMarker = null;
		targetTileMarker = null;
		clickedQuest = null;
		clickedQuestTicks = 0;
		stepOverlayModel = null;
		minigameBySub.clear();
		activeMinigameTarget = null;
		clickedMinigameTarget = null;
		clickedMinigameTicks = 0;
		levelGoalsBySub.clear();
		realLevelBySkill.clear();
		pendingHopWorld = null;
		clientToolbar.removeNavigation(navButton);
		navButton = null;
		panel = null;
		guides.clear();
		stepSkillRequirements.clear();
		subRequirements.clear();
		questGoalBySub.clear();
		itemGoalsBySub.clear();
		actionGoalBySub.clear();
		travelGoalSubs.clear();
		interactionGoalSubs.clear();
		countedGoalBySub.clear();
		acquisitionBaseline.clear();
		guideRemap = new HashMap<>();
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
		// Baselines describe the OLD profile's inventory state.
		acquisitionBaseline.clear();
		// The new profile's saved progress may still use pre-refresh step
		// ids; apply the same remap startUp applied (no-op if none).
		progressManager.remapIds(activeVariant, guideRemap);
		cleanupStaleAmbientTicks();
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

		// Keep the Swing-readable level cache current; on a level change
		// the "firemaking 43/50" badges need re-rendering.
		Integer previousLevel = realLevelBySkill.put(event.getSkill(), event.getLevel());
		if ((previousLevel == null || event.getLevel() != previousLevel) && panel != null)
		{
			SwingUtilities.invokeLater(panel::refreshItemCounts);
		}

		if (gainedXp && config.autoCompleteSteps())
		{
			List<Current> window = findWindow(AUTO_COMPLETE_WINDOW);
			GuideStep frontierStep = window.isEmpty() ? null : window.get(0).step;
			for (Current current : window)
			{
				if (current.step != frontierStep)
				{
					// An xp drop is ambient evidence: chopping for THIS
					// step must not tick a later step's "chop..." sub.
					break;
				}
				String subId = current.sub.getId();
				if (itemGoalsBySub.containsKey(subId))
				{
					continue;
				}
				if (event.getSkill() == actionGoalBySub.get(subId))
				{
					// "Chop down a dying tree" + Woodcutting xp = done.
					completeSubGoal(current.step, current.sub);
					break;
				}
				GoalDetector.CountedSkillGoal counted = countedGoalBySub.get(subId);
				if (counted != null && counted.getSkill() == event.getSkill())
				{
					// one build = one xp drop; N of them completes the sub
					int seen = progressManager.incrementCounted(activeVariant, subId);
					if (seen >= counted.getCount())
					{
						completeSubGoal(current.step, current.sub);
					}
					if (panel != null)
					{
						SwingUtilities.invokeLater(panel::refreshItemCounts);
					}
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

		reopenBankedItemSubs();
		evaluateAutoCompletion();
		if (panel != null)
		{
			SwingUtilities.invokeLater(panel::refreshItemCounts);
		}
	}

	/**
	 * Re-banking items the CURRENT step already "grabbed" re-opens those
	 * subs — the tick meant "in hand", and upcoming steps still need the
	 * items. Items that were CONSUMED (fletched away, eaten, handed in)
	 * stay ticked: a sub only re-opens when every missing item is sitting
	 * in the bank in full, which is the signature of re-banking.
	 */
	private void reopenBankedItemSubs()
	{
		if (!config.autoCompleteSteps() || goals == null || loginGraceTicks > 0
			|| client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		List<Current> window = findWindow(1);
		if (window.isEmpty())
		{
			return;
		}
		GuideStep step = window.get(0).step;
		boolean reopened = false;
		boolean pastFirstIncomplete = false;
		for (SubStep sub : step.getSubSteps())
		{
			if (!progressManager.isSubCompleted(activeVariant, step, sub))
			{
				pastFirstIncomplete = true;
				continue;
			}
			// The contiguous done-head is HISTORY: "grab a house teleport"
			// stays ticked when you later break that tab with spares in
			// the bank — indistinguishable from re-banking by state alone,
			// and un-ticking it wrecks the ordering. Only out-of-order
			// ticks past your position are eligible to reopen.
			if (!pastFirstIncomplete)
			{
				continue;
			}
			List<GoalDetector.ItemGoal> subGoals = itemGoalsBySub.get(sub.getId());
			if (subGoals == null)
			{
				continue;
			}
			boolean missingSomething = false;
			boolean missingAllBanked = true;
			for (GoalDetector.ItemGoal goal : subGoals)
			{
				if (itemTracker.carriedCountOf(goal.getItemName()) >= goal.getQuantity())
				{
					continue;
				}
				missingSomething = true;
				if (itemTracker.countOf(goal.getItemName()) < goal.getQuantity())
				{
					missingAllBanked = false; // consumed, not banked: stays done
					break;
				}
			}
			if (missingSomething && missingAllBanked)
			{
				progressManager.setSubCompleted(activeVariant, step, sub, false);
				reopened = true;
				String text = sub.getPlainText().trim();
				if (text.length() > 60)
				{
					text = text.substring(0, 57) + "...";
				}
				client.addChatMessage(ChatMessageType.CONSOLE, "",
					"IRONSCAPE: ↩ " + text + " (items back in the bank)", null);
			}
		}
		if (reopened && panel != null)
		{
			SwingUtilities.invokeLater(panel::refresh);
		}
	}

	/** The bank interface (re)opened: (re)create our filter button in it. */
	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == net.runelite.api.gameval.InterfaceID.BANKMAIN)
		{
			bankFilterButton.init();
		}
	}

	/** Clicking a real bank tab or the search button turns our filter off. */
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (!bankFilterButton.isActive())
		{
			return;
		}
		String option = event.getMenuOption();
		if (option != null
			&& (option.startsWith("View tab") || option.equals("View all items")
				|| option.startsWith("View tag tab") || option.startsWith("Potion store")))
		{
			bankFilterButton.deactivate();
		}
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() == net.runelite.api.ScriptID.BANKMAIN_SEARCH_TOGGLE
			&& bankFilterButton.isActive())
		{
			bankFilterButton.deactivate();
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
				// A click-requested minigame hint is done once ANY teleport
				// lands — the guided click path served its purpose.
				clickedMinigameTicks = 0;
			}
			lastTickPosition = here;
		}

		// Quest state and player position have no change events of their
		// own; polling every other tick is cheap.
		if (++tickCounter % 2 == 0)
		{
			evaluateAutoCompletion();
		}

		// A "world 444" link was clicked: in game, hopToWorld only works
		// while the world switcher panel is open (same dance as RuneLite's
		// own world hopper) — open it, then hop once its list exists.
		if (pendingHopWorld != null)
		{
			if (client.getWidget(net.runelite.api.gameval.InterfaceID.Worldswitcher.LIST) == null)
			{
				client.openWorldHopper();
				if (++hopAttempts > 10)
				{
					pendingHopWorld = null; // switcher never opened; give up quietly
				}
			}
			else
			{
				client.hopToWorld(pendingHopWorld);
				pendingHopWorld = null;
			}
		}

		// Feed the overlays. Computed here once per tick, not per frame.
		Current current = findCurrent();

		// Teleport hints: a clicked minigame link wins while its countdown
		// runs; otherwise the current "Minigame teleport to X" sub.
		if (clickedMinigameTicks > 0)
		{
			clickedMinigameTicks--;
		}
		if (!config.showTeleportHints())
		{
			activeMinigameTarget = null;
		}
		else if (clickedMinigameTicks > 0)
		{
			activeMinigameTarget = clickedMinigameTarget;
		}
		else
		{
			activeMinigameTarget = current == null ? null : minigameBySub.get(current.sub.getId());
		}

		// Quest-start marker: float the quest icon at the start point of
		// the quest the player is heading to — a clicked quest link, or
		// the current "Start X" sub. Clears itself the moment the quest
		// actually begins (Quest Helper's overlays take over from there).
		if (clickedQuestTicks > 0)
		{
			clickedQuestTicks--;
			if (clickedQuest == null || clickedQuest.getState(client) != QuestState.NOT_STARTED)
			{
				clickedQuestTicks = 0;
				clickedQuest = null;
			}
		}
		WorldPoint marker = null;
		if (config.showQuestStartMarker())
		{
			if (clickedQuestTicks > 0 && clickedQuest != null)
			{
				marker = placeManager.get(clickedQuest.getName());
			}
			else if (current != null)
			{
				GoalDetector.QuestGoal questGoal = questGoalBySub.get(current.sub.getId());
				if (questGoal != null && !questGoal.isRequiresFinished()
					&& questGoal.getQuest().getState(client) == QuestState.NOT_STARTED)
				{
					marker = placeManager.get(questGoal.getQuest().getName());
				}
			}
		}
		questStartMarker = marker;

		// Exact-spot marker: if the current sub (or its single-action
		// step) has an annotated ⌖ target, highlight that tile in the
		// world — dig spots, item spawns, and other precise locations.
		WorldPoint spot = null;
		if (config.showTargetMarker() && current != null)
		{
			StepAnnotation.Target target = annotationManager.getTarget(current.sub.getId());
			if (target == null && current.step.getSubSteps().size() == 1)
			{
				target = annotationManager.getTarget(current.step.getId());
			}
			if (target != null)
			{
				spot = new WorldPoint(target.x, target.y, target.plane);
			}
		}
		targetTileMarker = spot;

		// NPC targets: outline scene NPCs whose name the current sub-step
		// mentions ("speak with Veos" -> Veos). Names matched once per
		// tick; the overlay re-reads the live hulls per frame, which is
		// what keeps the outline glued to wandering NPCs.
		java.util.Set<String> npcNames = new java.util.HashSet<>();
		if (current != null)
		{
			String subText = " " + current.sub.getPlainText().toLowerCase(Locale.ROOT)
				.replace('’', '\'') + " ";
			for (net.runelite.api.NPC npc : client.getTopLevelWorldView().npcs())
			{
				String name = npc.getName();
				if (name == null)
				{
					continue;
				}
				// NPC names use non-breaking spaces; the guide uses real ones.
				String clean = net.runelite.client.util.Text.removeTags(name)
					.replace(' ', ' ').trim().toLowerCase(Locale.ROOT);
				if (clean.length() < 3)
				{
					continue;
				}
				int at = subText.indexOf(clean);
				if (at > 0
					&& !Character.isLetter(subText.charAt(at - 1))
					&& !Character.isLetter(subText.charAt(at + clean.length())))
				{
					npcNames.add(clean);
				}
			}
		}
		npcTargetNames = npcNames;
		currentSubIsQuest = current != null && questGoalBySub.containsKey(current.sub.getId());

		updateStepOverlay();
	}

	/** How many remaining action lines the on-screen step box shows. */
	private static final int OVERLAY_MAX_LINES = 3;

	/**
	 * Rebuild the on-screen step box's snapshot: the frontier step's
	 * remaining actions plus live counts for every requirement the step's
	 * open subs still have. Runs on the client thread once per tick.
	 */
	private void updateStepOverlay()
	{
		if (!config.showStepOverlay())
		{
			stepOverlayModel = null;
			return;
		}
		Current frontier = findCurrent();
		if (frontier == null)
		{
			stepOverlayModel = null;
			return;
		}

		GuideStep step = frontier.step;
		String current = null;
		List<String> upNext = new ArrayList<>();
		List<com.bruhsailer.overlay.StepOverlay.Requirement> reqs = new ArrayList<>();
		int openSubs = 0;
		for (SubStep sub : step.getSubSteps())
		{
			if (progressManager.isSubCompleted(activeVariant, step, sub))
			{
				continue;
			}
			openSubs++;
			if (current == null)
			{
				// The ONE action to do now — only ITS counts are shown, so
				// a huge step's far-off errands can't read as current.
				current = truncate(sub.getPlainText().trim(), 130);
				List<GoalDetector.ItemGoal> itemGoals = itemGoalsBySub.get(sub.getId());
				if (itemGoals != null)
				{
					for (GoalDetector.ItemGoal goal : itemGoals)
					{
						int carried = itemTracker.carriedCountOf(goal.getItemName());
						int have = itemTracker.countOf(goal.getItemName());
						java.awt.Color color = carried >= goal.getQuantity() ? OVERLAY_GREEN
							: have >= goal.getQuantity() ? OVERLAY_ORANGE : OVERLAY_RED;
						reqs.add(new com.bruhsailer.overlay.StepOverlay.Requirement(
							goal.getItemName(), have + "/" + goal.getQuantity(), color));
					}
				}
				List<GoalDetector.SkillLevelGoal> levels = levelGoalsBySub.get(sub.getId());
				if (levels != null)
				{
					for (GoalDetector.SkillLevelGoal goal : levels)
					{
						int have = realLevelBySkill.getOrDefault(goal.getSkill(), 1);
						reqs.add(new com.bruhsailer.overlay.StepOverlay.Requirement(
							goal.getSkill().getName().toLowerCase(Locale.ROOT),
							have + "/" + goal.getLevel(),
							have >= goal.getLevel() ? OVERLAY_GREEN : OVERLAY_ORANGE));
					}
				}
				GoalDetector.CountedSkillGoal counted = countedGoalBySub.get(sub.getId());
				if (counted != null)
				{
					int seen = Math.min(progressManager.countedProgress(activeVariant, sub.getId()),
						counted.getCount());
					reqs.add(new com.bruhsailer.overlay.StepOverlay.Requirement(
						counted.getSkill().getName().toLowerCase(Locale.ROOT) + " actions",
						seen + "/" + counted.getCount(),
						seen >= counted.getCount() ? OVERLAY_GREEN : OVERLAY_ORANGE));
				}
			}
			else if (upNext.size() < OVERLAY_MAX_LINES - 1)
			{
				upNext.add(truncate(sub.getPlainText().trim(), 70));
			}
		}
		if (current == null)
		{
			stepOverlayModel = null;
			return;
		}
		int moreCount = openSubs - 1 - upNext.size();
		stepOverlayModel = new com.bruhsailer.overlay.StepOverlay.Model(
			"Step " + (step.getStepIndex() + 1), current, upNext, moreCount, reqs);
	}

	private static final java.awt.Color OVERLAY_GREEN = new java.awt.Color(0x4c, 0xaf, 0x50);
	private static final java.awt.Color OVERLAY_ORANGE = new java.awt.Color(0xff, 0xa0, 0x00);
	private static final java.awt.Color OVERLAY_RED = new java.awt.Color(0xe5, 0x73, 0x73);

	private static String truncate(String text, int maxLength)
	{
		return text.length() <= maxLength ? text : text.substring(0, maxLength - 1) + "…";
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
		// The bank layout script asks whether a "tag tab" search is active;
		// answering 1 while our button is toggled makes it lay the bank out
		// through bankSearchFilter below with an empty search string.
		if ("getSearchingTagTab".equals(event.getEventName()))
		{
			if (bankFilterButton.isActive())
			{
				client.getIntStack()[client.getIntStackSize() - 1] = 1;
			}
			return;
		}
		if (!"bankSearchFilter".equals(event.getEventName()))
		{
			return;
		}
		Object[] objectStack = client.getObjectStack();
		String search = (String) objectStack[client.getObjectStackSize() - 1];
		boolean keywordSearch = search != null
			&& BANK_FILTER_KEYWORD.equalsIgnoreCase(search.trim());
		if (!keywordSearch && !bankFilterButton.isActive())
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

	/** How many upcoming incomplete STEPS the bank filter collects items from. */
	private static final int BANK_FILTER_STEPS = 10;

	/**
	 * Item names the next few steps still need (alias-expanded), starting
	 * at the frontier. Step-count scoped: a fixed sub-step window reached
	 * too far, and section scoping collected almost nothing near a section
	 * boundary. Ten steps of shopping ahead is predictable.
	 */
	private java.util.Set<String> upcomingItemNames()
	{
		if (bankFilterCacheTick == tickCounter && bankFilterNames != null)
		{
			return bankFilterNames;
		}
		java.util.Set<String> names = new java.util.HashSet<>();
		Current frontier = findCurrent();
		if (frontier != null)
		{
			Guide guide = guideFor(activeVariant);
			List<GuideStep> steps = guide.getAllSteps();
			int included = 0;
			for (int i = frontier.step.getGlobalIndex();
				i < steps.size() && included < BANK_FILTER_STEPS; i++)
			{
				GuideStep step = steps.get(i);
				if (progressManager.isCompleted(activeVariant, step.getId()))
				{
					continue;
				}
				included++;
				for (StepAnnotation.ItemNeed need : annotationManager.getItems(step.getId()))
				{
					java.util.Collections.addAll(names, ItemTracker.aliases(need.name));
				}
				for (SubStep sub : step.getSubSteps())
				{
					if (progressManager.isSubCompleted(activeVariant, step, sub))
					{
						continue;
					}
					List<GoalDetector.ItemGoal> itemGoals = itemGoalsBySub.get(sub.getId());
					if (itemGoals != null)
					{
						for (GoalDetector.ItemGoal goal : itemGoals)
						{
							java.util.Collections.addAll(names, ItemTracker.aliases(goal.getItemName()));
						}
					}
					for (StepAnnotation.ItemNeed need : annotationManager.getItems(sub.getId()))
					{
						java.util.Collections.addAll(names, ItemTracker.aliases(need.name));
					}
				}
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

		Guide guide = guideFor(activeVariant);
		boolean progressed = false;

		for (int guard = 0; guard < 100; guard++)
		{
			boolean completedSomething = false;
			List<Current> window = findWindow(AUTO_COMPLETE_WINDOW);
			GuideStep frontierStep = window.isEmpty() ? null : window.get(0).step;
			for (int i = 0; i < window.size(); i++)
			{
				Current current = window.get(i);
				// Reviewed requirements complete a WHOLE step when ALL are met.
				List<StepRequirement> requirements = stepSkillRequirements.get(current.step.getId());
				if (requirements != null && requirementsMet(requirements))
				{
					completeStep(current.step);
					completedSomething = true;
					break; // window shifted; rebuild it
				}

				// Sub-keyed requirements tick one sub: "do the quest up to
				// the orb" completes off the quest's progress varbit while
				// the step's other errands stay open. Monotonic game state
				// = strong evidence, so anywhere in the window is fine.
				List<StepRequirement> subReqs = subRequirements.get(current.sub.getId());
				if (subReqs != null && requirementsMet(subReqs))
				{
					completeSubGoal(current.step, current.sub);
					completedSomething = true;
					break;
				}

				if (currentSubSatisfied(current.step, current.sub, i == 0,
					current.step == frontierStep))
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

	/**
	 * The first `limit` incomplete sub-steps in guide order — starting
	 * AFTER the last completed step. Unticked steps before that point were
	 * skipped on purpose; anchoring the frontier (auto-ticks, overlay,
	 * navigation, panel landing) on them would pin everything to old
	 * content the player has moved past.
	 */
	private List<Current> findWindow(int limit)
	{
		List<Current> window = new ArrayList<>();
		Guide guide = guideFor(activeVariant);
		List<GuideStep> steps = guide.getAllSteps();
		int start = 0;
		for (int i = 0; i < steps.size(); i++)
		{
			if (progressManager.isCompleted(activeVariant, steps.get(i).getId()))
			{
				start = i + 1;
			}
		}
		for (int i = start; i < steps.size(); i++)
		{
			GuideStep step = steps.get(i);
			for (SubStep sub : step.getSubSteps())
			{
				if (!progressManager.isSubCompleted(activeVariant, step, sub))
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

	/**
	 * Is this sub-step's goal met? Items beat quests beat arrival.
	 *
	 * @param frontier true if this is the FIRST incomplete sub-step.
	 *                 Pure arrival — the weakest signal — only counts at
	 *                 the frontier, so standing next to Hetty can't tick
	 *                 "return to Hetty" three steps early.
	 * @param inFrontierStep true if this sub belongs to the first
	 *                 incomplete STEP. Ambient signals (items you happen
	 *                 to carry, a teleport, a consumption) may tick subs
	 *                 out of order WITHIN the current step — that's what
	 *                 the window is for — but never a later step: carrying
	 *                 1 gp must not tick next step's "grab your gp" and
	 *                 drag navigation ahead of where the player really is.
	 */
	private boolean currentSubSatisfied(GuideStep step, SubStep sub, boolean frontier,
		boolean inFrontierStep)
	{
		// Quest state FIRST: atomic guide steps combine errands with the
		// quest action ("Buy a spade, start X marks the spot quest") — the
		// quest's own state is the authoritative "done" signal, and it's
		// strong evidence (monotonic), unlike carried-item counts.
		GoalDetector.QuestGoal atomicQuestGoal = questGoalBySub.get(sub.getId());
		if (atomicQuestGoal != null && itemGoalsBySub.containsKey(sub.getId()))
		{
			QuestState state = atomicQuestGoal.getQuest().getState(client);
			return atomicQuestGoal.isRequiresFinished()
				? state == QuestState.FINISHED
				: state != QuestState.NOT_STARTED;
		}

		List<GoalDetector.ItemGoal> itemGoals = itemGoalsBySub.get(sub.getId());
		if (itemGoals != null)
		{
			if (!inFrontierStep)
			{
				return false;
			}
			for (GoalDetector.ItemGoal goal : itemGoals)
			{
				int carried = itemTracker.carriedCountOf(goal.getItemName());
				// "buy shears from her shop" is a TRANSACTION: already
				// carrying shears from three quests ago must not tick it.
				// The first evaluation records how many you had when the
				// sub became current; only gaining one after that counts.
				// This runs BEFORE the quantity check so the baseline is
				// captured while you still have too few.
				if (goal.isAcquisition())
				{
					String key = sub.getId() + "|" + goal.getItemName();
					Integer baseline = acquisitionBaseline.get(key);
					if (baseline == null || carried < baseline)
					{
						// (Re)base — also downward, so banking the spares
						// and then buying still registers as a gain.
						acquisitionBaseline.put(key, carried);
						baseline = carried;
					}
					if (carried <= baseline)
					{
						return false;
					}
				}
				// Carried only: items sitting in the bank show an orange
				// badge but do NOT tick the step for you.
				if (carried < goal.getQuantity())
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

		// "burn them to level 50 firemaking" — levels only go up, so like
		// quest state this is strong evidence and may complete ahead of
		// the frontier. ALL of the sub's level targets must be met.
		List<GoalDetector.SkillLevelGoal> levelGoals = levelGoalsBySub.get(sub.getId());
		if (levelGoals != null)
		{
			for (GoalDetector.SkillLevelGoal goal : levelGoals)
			{
				if (client.getRealSkillLevel(goal.getSkill()) < goal.getLevel())
				{
					return false;
				}
			}
			return true;
		}

		// "Give the letter to Romeo" / "Fix his house" — something must have
		// LEFT the inventory (and if the step has a target, near it too).
		if (interactionGoalSubs.contains(sub.getId()))
		{
			if (!inFrontierStep || recentConsumeTicks <= 0)
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
			return inFrontierStep && recentTeleportTicks > 0;
		}

		// No item/quest goal: a movement step. Arriving at its target
		// (⌖ capture or recognised place name) completes it — but only at
		// the frontier.
		if (!frontier)
		{
			return false;
		}
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
		progressManager.setCompleted(activeVariant, step, true);
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
		progressManager.setSubCompleted(activeVariant, step, sub, true);

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

	/**
	 * One-time hygiene per profile. Before frontier gating (2026-07-22),
	 * ambient signals — carried items, xp drops, teleports, consumption —
	 * could tick sub-steps up to 8 ahead, crossing into steps and errands
	 * the player never reached ("buy shears" done before ever visiting the
	 * shop). Gating stopped NEW strays; this clears the leftovers: every
	 * ticked ambient-goal sub past the first incomplete one. Quest and
	 * level ticks (strong evidence) and goal-less manual ticks stay.
	 */
	private void cleanupStaleAmbientTicks()
	{
		if ("done".equals(configManager.getConfiguration(CONFIG_GROUP, "ambientTickCleanupV1")))
		{
			return;
		}
		Guide guide = guideFor(activeVariant);
		List<GuideStep> steps = guide.getAllSteps();
		int lastCompleted = -1;
		for (int i = 0; i < steps.size(); i++)
		{
			if (progressManager.isCompleted(activeVariant, steps.get(i).getId()))
			{
				lastCompleted = i;
			}
		}
		int cleared = 0;
		boolean pastFirstIncomplete = false;
		for (int i = lastCompleted + 1; i < steps.size(); i++)
		{
			GuideStep step = steps.get(i);
			for (SubStep sub : step.getSubSteps())
			{
				boolean ticked = progressManager.isSubCompleted(activeVariant, step, sub);
				if (!ticked)
				{
					pastFirstIncomplete = true;
					continue;
				}
				if (!pastFirstIncomplete)
				{
					continue; // the contiguous done-head of the frontier step is real progress
				}
				String subId = sub.getId();
				boolean ambient = itemGoalsBySub.containsKey(subId)
					|| travelGoalSubs.contains(subId)
					|| interactionGoalSubs.contains(subId)
					|| actionGoalBySub.containsKey(subId)
					|| countedGoalBySub.containsKey(subId);
				if (ambient)
				{
					progressManager.setSubCompleted(activeVariant, step, sub, false);
					cleared++;
				}
			}
		}
		configManager.setConfiguration(CONFIG_GROUP, "ambientTickCleanupV1", "done");
		if (cleared > 0)
		{
			log.info("Cleared {} stale ambient tick(s) beyond the current position", cleared);
		}
	}

	private void rebuildStepRequirements()
	{
		stepSkillRequirements.clear();
		subRequirements.clear();
		annotationManager.allRequirements().forEach((stepId, requirementList) -> {
			List<StepRequirement> parsed = new ArrayList<>();
			for (com.bruhsailer.annotations.StepAnnotation.Requirement requires : requirementList)
			{
				if (requires.varbit != null || requires.varp != null)
				{
					if (requires.value != null)
					{
						parsed.add(new StepRequirement(null, requires.varbit, requires.varp, requires.value));
					}
					continue;
				}
				if (requires.skill == null || requires.level == null)
				{
					continue;
				}
				if ("COMBAT".equals(requires.skill))
				{
					parsed.add(new StepRequirement(null, requires.level));
					continue;
				}
				try
				{
					parsed.add(new StepRequirement(Skill.valueOf(requires.skill), requires.level));
				}
				catch (IllegalArgumentException e)
				{
					// One bad name poisons the whole step: evaluating only the
					// valid remainder could tick the step early.
					log.warn("Annotation for step {} names unknown skill '{}' — requirement disabled",
						stepId, requires.skill);
					return;
				}
			}
			if (!parsed.isEmpty())
			{
				// "stepId:14" targets ONE sub-step; a bare step id
				// completes the whole step when met.
				if (stepId.indexOf(':') >= 0)
				{
					subRequirements.put(stepId, parsed);
				}
				else
				{
					stepSkillRequirements.put(stepId, parsed);
				}
			}
		});
	}

	/** ALL requirements met? (Reviewed annotations; runs on the client thread.) */
	private boolean requirementsMet(List<StepRequirement> requirements)
	{
		for (StepRequirement requirement : requirements)
		{
			int have;
			if (requirement.varbit != null)
			{
				have = client.getVarbitValue(requirement.varbit);
			}
			else if (requirement.varp != null)
			{
				have = client.getVarpValue(requirement.varp);
			}
			else if (requirement.skill == null)
			{
				Player me = client.getLocalPlayer();
				if (me == null)
				{
					return false;
				}
				have = me.getCombatLevel();
			}
			else
			{
				have = client.getRealSkillLevel(requirement.skill);
			}
			if (have < requirement.threshold)
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * One reviewed condition: a skill level, combat level (skill null), or
	 * a varbit/varp threshold — the latter detects mid-quest checkpoints
	 * ("do the quest up to the orb": quest progress varbits only count up).
	 */
	private static class StepRequirement
	{
		final Skill skill;
		final Integer varbit;
		final Integer varp;
		final int threshold;

		StepRequirement(Skill skill, int level)
		{
			this(skill, null, null, level);
		}

		StepRequirement(Skill skill, Integer varbit, Integer varp, int threshold)
		{
			this.skill = skill;
			this.varbit = varbit;
			this.varp = varp;
			this.threshold = threshold;
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
		// Clicking a minigame's name ("Soul Wars") means "how do I get
		// there?" — and the answer is the minigame teleport, so light up
		// its click path and do NOT hand the place to Shortest Path: a
		// walking route to a teleport-only island is just misleading.
		String minigame = minigameByName(placeName);
		if (minigame != null && config.showTeleportHints())
		{
			clickedMinigameTarget = minigame;
			clickedMinigameTicks = 100; // ~1 minute, or until a teleport lands
			activeMinigameTarget = minigame; // show now, not next tick
			return;
		}

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
					// Route there AND float the quest icon at the start
					// point (~2 min, or until the quest begins).
					clickedQuest = quest;
					clickedQuestTicks = 200;
					questStartMarker = point;
					eventBus.post(new PluginMessage("shortestpath", "path", Map.of("target", point)));
				}
				else if (state == QuestState.FINISHED)
				{
					client.addChatMessage(ChatMessageType.CONSOLE, "",
						"IRONSCAPE: " + quest.getName() + " is already finished.", null);
				}
				else if (tryQuestHelperSelect(quest.getName()))
				{
					client.addChatMessage(ChatMessageType.CONSOLE, "",
						"IRONSCAPE: " + quest.getName()
							+ " is in progress — opened it in Quest Helper.", null);
				}
				else
				{
					client.addChatMessage(ChatMessageType.CONSOLE, "",
						"IRONSCAPE: " + quest.getName()
							+ " is in progress — its start point isn't where you need to go. "
							+ "Select it in Quest Helper for step-by-step guidance.",
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

	/**
	 * Best-effort handoff to the Quest Helper plugin: select the quest in
	 * its panel so its step-by-step overlays activate. QH exposes no
	 * public API, so this reaches its QuestMenuHandler#startUpQuest(String)
	 * — a public method on a private field — via reflection on the live
	 * plugin instance (works across plugin classloaders). Any version
	 * drift or missing plugin just returns false and the caller falls
	 * back to a chat message. Client thread.
	 */
	private boolean tryQuestHelperSelect(String questName)
	{
		try
		{
			for (net.runelite.client.plugins.Plugin plugin : pluginManager.getPlugins())
			{
				if (!"Quest Helper".equals(plugin.getName()))
				{
					continue;
				}
				if (!pluginManager.isPluginEnabled(plugin))
				{
					return false;
				}
				java.lang.reflect.Field field = plugin.getClass().getDeclaredField("questMenuHandler");
				field.setAccessible(true);
				Object handler = field.get(plugin);
				handler.getClass().getMethod("startUpQuest", String.class).invoke(handler, questName);
				return true;
			}
		}
		catch (Throwable t)
		{
			log.debug("Quest Helper handoff failed (its internals may have changed)", t);
		}
		return false;
	}

	/** The guide's minigame-teleport name matching this place name, or null. */
	private String minigameByName(String placeName)
	{
		for (String minigame : minigameBySub.values())
		{
			if (minigame.equalsIgnoreCase(placeName))
			{
				return minigame;
			}
		}
		return null;
	}

	/**
	 * A "world 444" link in the guide text was clicked. Look the world up
	 * in RuneLite's world list and switch to it — directly on the login
	 * screen, via the world switcher when logged in (see onGameTick).
	 */
	private void hopToWorld(int worldNumber)
	{
		clientThread.invoke(() -> {
			net.runelite.http.api.worlds.WorldResult worldResult = worldService.getWorlds();
			net.runelite.http.api.worlds.World world =
				worldResult == null ? null : worldResult.findWorld(worldNumber);
			if (world == null)
			{
				log.warn("World {} not found in the world list", worldNumber);
				if (client.getGameState() == GameState.LOGGED_IN)
				{
					client.addChatMessage(ChatMessageType.CONSOLE, "",
						"IRONSCAPE: world " + worldNumber + " isn't in the world list.", null);
				}
				return;
			}
			if (client.getWorld() == worldNumber)
			{
				return; // already there
			}

			// The api World is a client-side struct we fill from the
			// downloaded world list entry.
			net.runelite.api.World rsWorld = client.createWorld();
			rsWorld.setActivity(world.getActivity());
			rsWorld.setAddress(world.getAddress());
			rsWorld.setId(world.getId());
			rsWorld.setPlayerCount(world.getPlayers());
			rsWorld.setLocation(world.getLocation());
			rsWorld.setTypes(net.runelite.client.util.WorldUtil.toWorldTypes(world.getTypes()));

			if (client.getGameState() == GameState.LOGIN_SCREEN)
			{
				client.changeWorld(rsWorld);
				return;
			}
			pendingHopWorld = rsWorld;
			hopAttempts = 0;
		});
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
