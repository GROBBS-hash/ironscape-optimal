package com.ironscape;

import com.ironscape.annotations.AnnotationManager;
import com.ironscape.annotations.StepAnnotation;
import com.ironscape.goals.GoalDetector;
import com.ironscape.guide.Guide;
import com.ironscape.guide.GuideLoader;
import com.ironscape.guide.GuideStep;
import com.ironscape.guide.GuideVariant;
import com.ironscape.guide.SubStep;
import com.ironscape.guide.TextRun;
import com.ironscape.items.ItemTracker;
import com.ironscape.panel.IronscapePanel;
import com.ironscape.places.PlaceManager;
import com.ironscape.progress.ProgressManager;
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
import com.ironscape.items.BankFilterButton;
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
 * IRONSCAPE Optimal — the Ironman Efficiency Guide as a RuneLite plugin.
 *
 * Guide content by Oziris and the ironman.guide community (v4, Enhanced
 * 2026 edition), bundled with their permission. https://ironman.guide/
 *
 * Earlier development phases used "bruhsailer" as the internal id (config
 * group, package, data directory). A one-time migration in startUp() copies
 * that legacy data into the "ironscape" ids so saved progress survives.
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
	description = "The Ironman Efficiency Guide (by Oziris & the ironman.guide community, used with permission) as an in-game step-by-step panel with auto-completion and navigation",
	tags = {"ironman", "guide", "oziris", "ironscape", "efficient"}
)
public class IronscapePlugin extends Plugin
{
	private static final String CONFIG_GROUP = "ironscape";

	/** The config group and data directory name this plugin used before the rename. */
	private static final String LEGACY_CONFIG_GROUP = "bruhsailer";

	/** Per-profile flag marking that the legacy config keys were copied over. */
	private static final String MIGRATION_FLAG = "migratedFromBruhsailer";

	@Inject
	private IronscapeConfig config;

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
	private com.ironscape.items.BankMissingSection bankMissingSection;

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
	private com.ironscape.guide.GuideManifest guideManifest;

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
	private com.ironscape.overlay.MinigameTeleportOverlay minigameTeleportOverlay;

	@Inject
	private com.ironscape.overlay.StepOverlay stepOverlay;

	@Inject
	private com.ironscape.overlay.QuestStartMarkerOverlay questStartMarkerOverlay;

	@Inject
	private com.ironscape.overlay.NpcTargetOverlay npcTargetOverlay;

	@Inject
	private com.ironscape.overlay.TargetTileOverlay targetTileOverlay;

	/** Lowercased names of scene NPCs the current sub mentions. Written per tick. */
	private volatile java.util.Set<String> npcTargetNames = java.util.Collections.emptySet();

	/** True while the current sub is a quest goal (adds the NPC quest icon). */
	private volatile boolean currentSubIsQuest;

	/** Where the quest-start icon floats; null = hidden. Written per tick. */
	private volatile WorldPoint questStartMarker;

	/** The current sub's annotated ⌖ target tile; null = hidden. Written per tick. */
	private volatile WorldPoint targetTileMarker;

	/** Tiles of ground items the current sub wants picked up. Written per tick. */
	private volatile List<WorldPoint> groundItemTargets = java.util.Collections.emptyList();

	/** Item id of the current sub's first unmet item goal; -1 = none. Written per tick. */
	private volatile int currentSubItemIcon = -1;

	/**
	 * Scene tiles holding a ground item that matches one of the current
	 * sub's item goals — the "pick up 2 iron bars" / item-spawn case.
	 * Scans the current plane's tiles once per game tick; ~11k null checks
	 * is nothing, and it needs no spawn/despawn bookkeeping.
	 */
	private List<WorldPoint> findWantedGroundItems(Current current)
	{
		List<GoalDetector.ItemGoal> wanted = itemGoalsBySub.get(current.sub.getId());
		if (wanted == null || wanted.isEmpty())
		{
			return java.util.Collections.emptyList();
		}
		java.util.Set<String> names = new java.util.HashSet<>();
		for (GoalDetector.ItemGoal goal : wanted)
		{
			java.util.Collections.addAll(names, ItemTracker.aliases(goal.getItemName()));
		}
		List<WorldPoint> spots = new ArrayList<>();
		net.runelite.api.WorldView view = client.getTopLevelWorldView();
		net.runelite.api.Tile[][] tiles = view.getScene().getTiles()[view.getPlane()];
		for (net.runelite.api.Tile[] column : tiles)
		{
			for (net.runelite.api.Tile tile : column)
			{
				if (tile == null || tile.getGroundItems() == null)
				{
					continue;
				}
				for (net.runelite.api.TileItem item : tile.getGroundItems())
				{
					String name = itemManager.getItemComposition(item.getId())
						.getName().toLowerCase(Locale.ROOT);
					if (names.contains(name))
					{
						spots.add(tile.getWorldLocation());
						break;
					}
				}
				if (spots.size() >= 50)
				{
					return spots; // plenty; don't paint the whole floor
				}
			}
		}
		return spots;
	}

	/** Quest whose start marker was requested by clicking its link. */
	private Quest clickedQuest;
	private int clickedQuestTicks;

	/** Snapshot the step overlay draws; rebuilt once per game tick. */
	private volatile com.ironscape.overlay.StepOverlay.Model stepOverlayModel;

	/**
	 * A Provider delays construction until we call get() in startUp() —
	 * building Swing components at injection time (before the client UI
	 * exists) would be too early.
	 */
	@Inject
	private Provider<IronscapePanel> panelProvider;

	/** Parsed guides, loaded once per client session. */
	private final Map<GuideVariant, Guide> guides = new EnumMap<>(GuideVariant.class);

	/**
	 * The guide being followed — one bundled guide today, but everything
	 * stays keyed by variant so adding another is an enum entry + JSON.
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

	/** "make bookcases UNTIL OUT OF planks": sub id -> item that must run out. */
	private final Map<String, String> depletionBySub = new HashMap<>();

	/**
	 * Depletion subs seen HOLDING their item while current — only those may
	 * tick when the count hits zero, or arriving empty-handed would tick
	 * them instantly. Session state, cleared on shutdown/profile switch.
	 */
	private final java.util.Set<String> depletionArmed = new java.util.HashSet<>();

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

	/** True while the current sub says "home tele(port)" — spellbook hint. */
	private volatile boolean homeTeleportHint;

	/** "Home tele to lumby" / "Home teleport, run north..." */
	private static final java.util.regex.Pattern HOME_TELEPORT = java.util.regex.Pattern.compile(
		"\\bhome\\s+tele(?:port)?\\b", java.util.regex.Pattern.CASE_INSENSITIVE);

	/** Ticks remaining in which a recent item consumption can complete an interaction sub. */
	private int recentConsumeTicks;

	/** Tick of the last bank container change — consumption near it is just banking. */
	private int lastBankEventTick = -10;

	/** Last seen XP per skill, to spot the moment an action grants xp. */
	private final Map<Skill, Integer> lastXpBySkill = new EnumMap<>(Skill.class);

	/** Where the player stood last tick; a big jump means they teleported. */
	private WorldPoint lastTickPosition;

	/** Type this in the bank search box to filter to upcoming guide items. */
	/** Bank-search keywords that trigger the guide-items filter. */
	private static final java.util.Set<String> BANK_FILTER_KEYWORDS =
		java.util.Set.of("ironman", "bruh");

	/** Tick the upcoming-needs sections were last rebuilt on. */
	private int bankFilterCacheTick = -1;

	/** Ticks remaining in which a recent teleport can complete a travel sub-step. */
	private int recentTeleportTicks;

	/** Last poll of questHelperOwnsGuidance, to react to the handoff edges. */
	private boolean lastQuestOwnsGuidance;

	/**
	 * Auto-completion applies to the first few incomplete sub-steps, not
	 * just the very first — one un-tickable prose fragment must not freeze
	 * the whole system — but never further ahead than this.
	 */
	private static final int AUTO_COMPLETE_WINDOW = 8;

	/**
	 * The in-order lookahead for auto-completion. Tuned as 8 CLAUSES for
	 * the prose guide; on an atomic guide 8 would mean eight whole STEPS
	 * of reach, letting one strong signal tick far ahead of the player —
	 * a single out-of-order tick then drags the frontier (and Resume,
	 * navigation, item detection) past everything in between.
	 */
	private int autoCompleteWindow()
	{
		return activeVariant.isAtomicSteps() ? 4 : AUTO_COMPLETE_WINDOW;
	}

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

	/** How close (tiles) counts as "arrived" at a PRECISE ⌖ target. */
	private static final int ARRIVE_RADIUS = 8;

	/**
	 * Arrival radius when the target is a PLACE NAME ("walk to Ardy") —
	 * town points sit at the market square, and entering from any gate
	 * should count as having arrived.
	 */
	private static final int PLACE_ARRIVE_RADIUS = 25;

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

	private IronscapePanel panel;
	private NavigationButton navButton;

	@Override
	protected void startUp() throws Exception
	{
		// Legacy data must be in place BEFORE anything loads it below.
		migrateLegacyFiles();
		migrateLegacyConfig();
		activeVariant = GuideVariant.OZIRIS;
		annotationManager.load();
		placeManager.load();
		loadGuideState();
		registerUi();
	}

	/**
	 * One-time copy of ~/.runelite/bruhsailer/* (local annotations, guide
	 * manifest, bank snapshots) into ~/.runelite/ironscape/. Files are
	 * COPIED, not moved — a rollback to an older build keeps working — and
	 * anything already present in the new directory is never overwritten.
	 * Idempotent and cheap, so it simply runs every startUp.
	 */
	private void migrateLegacyFiles()
	{
		java.io.File oldDir = new java.io.File(net.runelite.client.RuneLite.RUNELITE_DIR, LEGACY_CONFIG_GROUP);
		java.io.File newDir = new java.io.File(net.runelite.client.RuneLite.RUNELITE_DIR, CONFIG_GROUP);
		java.io.File[] files = oldDir.listFiles(java.io.File::isFile);
		if (files == null)
		{
			return; // no legacy directory — fresh install
		}
		int copied = 0;
		for (java.io.File file : files)
		{
			java.io.File dest = new java.io.File(newDir, file.getName());
			if (dest.exists())
			{
				continue;
			}
			try
			{
				newDir.mkdirs();
				java.nio.file.Files.copy(file.toPath(), dest.toPath());
				copied++;
			}
			catch (IOException e)
			{
				log.warn("Could not migrate legacy data file {}", file.getName(), e);
			}
		}
		if (copied > 0)
		{
			log.info("Migrated {} data file(s) from ~/.runelite/{} to ~/.runelite/{}",
				copied, LEGACY_CONFIG_GROUP, CONFIG_GROUP);
		}
	}

	/**
	 * One-time copy of every "bruhsailer.*" config key (saved progress,
	 * position, counted-xp counters, cleanup flags, settings) into the
	 * "ironscape" group. Config keys live per RuneLite profile, so this
	 * runs once PER PROFILE — from startUp for the active profile, and
	 * from onProfileChanged for any profile activated later — guarded by
	 * a marker key written into the new group. The legacy value wins:
	 * when this runs, anything already under "ironscape" can only be a
	 * default RuneLite seeded while registering the config (it seeds
	 * every @ConfigItem before startUp), never something the user chose.
	 * The legacy keys themselves are left untouched.
	 */
	private void migrateLegacyConfig()
	{
		if ("done".equals(configManager.getConfiguration(CONFIG_GROUP, MIGRATION_FLAG)))
		{
			return;
		}
		String prefix = LEGACY_CONFIG_GROUP + ".";
		int copied = 0;
		for (String fullKey : configManager.getConfigurationKeys(prefix))
		{
			String key = fullKey.substring(prefix.length());
			String value = configManager.getConfiguration(LEGACY_CONFIG_GROUP, key);
			if (value != null && !value.equals(configManager.getConfiguration(CONFIG_GROUP, key)))
			{
				configManager.setConfiguration(CONFIG_GROUP, key, value);
				copied++;
			}
		}
		configManager.setConfiguration(CONFIG_GROUP, MIGRATION_FLAG, "done");
		if (copied > 0)
		{
			log.info("Migrated {} config key(s) from group '{}' to '{}' for this profile",
				copied, LEGACY_CONFIG_GROUP, CONFIG_GROUP);
		}
	}

	/**
	 * (Re)builds everything derived from the ACTIVE guide: manifest
	 * reconcile, requirements, detected goals and their per-sub lookup
	 * maps. Called from startUp and again when the user switches guides
	 * in the config — always on a thread that isn't racing the game tick
	 * (startUp runs before the event subscriptions matter; the config
	 * switch hops to the client thread first).
	 */
	private void loadGuideState()
	{
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
		depletionBySub.clear();
		for (GoalDetector.DepletionGoal goal : goals.getDepletionGoals())
		{
			depletionBySub.put(goal.getSub().getId(), goal.getItemName());
		}
		levelGoalsBySub.clear();
		for (GoalDetector.SkillLevelGoal goal : goals.getSkillLevelGoals())
		{
			levelGoalsBySub.computeIfAbsent(goal.getSub().getId(), id -> new ArrayList<>()).add(goal);
		}
		log.info("Detected {} item goals and {} quest goals in the guide text",
			goals.getItemGoals().size(), goals.getQuestGoals().size());

		cleanupStaleAmbientTicks();
	}

	/** Overlays, side panel and toolbar button — the once-per-startUp UI wiring. */
	private void registerUi()
	{
		minigameTeleportOverlay.setTargetSupplier(() -> activeMinigameTarget);
		minigameTeleportOverlay.setHomeTeleportSupplier(() -> homeTeleportHint);
		overlayManager.add(minigameTeleportOverlay);
		stepOverlay.setModelSupplier(() -> stepOverlayModel);
		overlayManager.add(stepOverlay);
		questStartMarkerOverlay.setTargetSupplier(() -> questStartMarker);
		overlayManager.add(questStartMarkerOverlay);
		npcTargetOverlay.setNamesSupplier(() -> npcTargetNames);
		npcTargetOverlay.setQuestIconSupplier(() -> currentSubIsQuest);
		npcTargetOverlay.setItemIconSupplier(() -> currentSubItemIcon);
		overlayManager.add(npcTargetOverlay);
		targetTileOverlay.setTargetSupplier(() -> targetTileMarker);
		targetTileOverlay.setGroundItemsSupplier(() -> groundItemTargets);
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
			// Warm the stackability cache for every detected item goal:
			// the panel's Swing badges can't compute it off-thread.
			for (GoalDetector.ItemGoal goal : goals.getItemGoals())
			{
				itemTracker.bankCountable(goal.getItemName(), goal.getQuantity());
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
		homeTeleportHint = false;
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
		depletionBySub.clear();
		depletionArmed.clear();
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
		// Each profile carries its own config keys; give this one its
		// legacy "bruhsailer" values before any of them are read.
		migrateLegacyConfig();
		progressManager.invalidate();
		// Baselines describe the OLD profile's inventory state.
		acquisitionBaseline.clear();
		depletionArmed.clear();
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
			List<Current> window = findWindow(autoCompleteWindow());
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
					completeSubGoal(current.step, current.sub, event.getSkill() + " xp drop");
					break;
				}
				GoalDetector.CountedSkillGoal counted = countedGoalBySub.get(subId);
				if (counted != null && counted.getSkill() == event.getSkill())
				{
					// one build = one xp drop; N of them completes the sub
					int seen = progressManager.incrementCounted(activeVariant, subId);
					if (seen >= counted.getCount())
					{
						completeSubGoal(current.step, current.sub,
							"counted " + event.getSkill() + " drops");
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

	/** Previous game state — LOADING->LOGGED_IN is a region load, not a login. */
	private GameState lastGameState;

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		// LOGGED_IN fires after EVERY loading screen, not just real logins.
		// A teleport is LOADING -> LOGGED_IN: resetting the login grace
		// there ate the very tick the position jump would be detected on —
		// travel subs never ticked across a loading screen. Only a real
		// (re)connect needs the grace window and fresh xp baselines.
		if (event.getGameState() == GameState.LOGGED_IN
			&& lastGameState != GameState.LOADING)
		{
			loginGraceTicks = 10;
			lastXpBySkill.clear(); // next account/session sets fresh baselines
			itemTracker.onLoggedIn();
			if (panel != null)
			{
				SwingUtilities.invokeLater(panel::refreshItemCounts);
			}
		}
		lastGameState = event.getGameState();
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
		GuideStep frontier = window.get(0).step;
		boolean reopened = reopenItemSubsIn(frontier, false);

		// The step COMPLETED just before the frontier is still "live" for
		// gather goals: dropping the 130 planks you just collected must
		// reopen it. Further back, later steps legitimately CONSUME the
		// gathered items, so history stays history.
		int previousIndex = frontier.getGlobalIndex() - 1;
		if (previousIndex >= 0)
		{
			GuideStep previous = guideFor(activeVariant).getAllSteps().get(previousIndex);
			if (progressManager.isCompleted(activeVariant, previous.getId()))
			{
				reopened |= reopenItemSubsIn(previous, true);
			}
		}
		if (reopened && panel != null)
		{
			SwingUtilities.invokeLater(panel::refresh);
		}
	}

	/**
	 * Reopens ticked item subs of one step. Two triggers:
	 *  - a GATHER goal (>28, counts the bank) fell below its quantity —
	 *    the stack was dropped/lost, unambiguous at any position;
	 *  - re-banking (carried items all back in the bank), only for
	 *    out-of-order ticks past the first incomplete sub — the
	 *    contiguous done-head is HISTORY: "grab a house teleport" stays
	 *    ticked when you later break that tab with spares in the bank.
	 *
	 * @param gatherOnly true for the already-completed step before the
	 *                   frontier: only gather-loss reopens there
	 */
	private boolean reopenItemSubsIn(GuideStep step, boolean gatherOnly)
	{
		boolean reopened = false;
		boolean pastFirstIncomplete = false;
		for (SubStep sub : step.getSubSteps())
		{
			if (!progressManager.isSubCompleted(activeVariant, step, sub))
			{
				pastFirstIncomplete = true;
				continue;
			}
			List<GoalDetector.ItemGoal> subGoals = itemGoalsBySub.get(sub.getId());
			if (subGoals == null)
			{
				continue;
			}
			boolean gatherLost = false;
			boolean missingSomething = false;
			boolean missingAllBanked = true;
			for (GoalDetector.ItemGoal goal : subGoals)
			{
				boolean gather = itemTracker.bankCountable(goal.getItemName(), goal.getQuantity());
				if (gather)
				{
					if (itemTracker.countOf(goal.getItemName()) < goal.getQuantity())
					{
						gatherLost = true;
					}
					continue; // banking a gather batch is expected, never "re-banked"
				}
				if (itemTracker.carriedCountOf(goal.getItemName()) >= goal.getQuantity())
				{
					continue;
				}
				missingSomething = true;
				if (itemTracker.countOf(goal.getItemName()) < goal.getQuantity())
				{
					missingAllBanked = false; // consumed, not banked: stays done
				}
			}
			boolean rebanked = !gatherOnly && pastFirstIncomplete
				&& missingSomething && missingAllBanked;
			if (gatherLost || rebanked)
			{
				progressManager.setSubCompleted(activeVariant, step, sub, false);
				// Reopening a step at/behind the player's position pulls
				// the position back so the frontier returns to it.
				progressManager.regressPositionTo(activeVariant, step.getGlobalIndex() - 1);
				reopened = true;
				String text = sub.getPlainText().trim();
				if (text.length() > 60)
				{
					text = text.substring(0, 57) + "...";
				}
				client.addChatMessage(ChatMessageType.CONSOLE, "",
					"IRONSCAPE: ↩ " + text
						+ (gatherLost ? " (you no longer have enough)" : " (items back in the bank)"),
					null);
			}
		}
		return reopened;
	}

	/**
	 * Barcrawl bars hand you the drink INSIDE dialogue and you gulp it
	 * immediately — no item ever exists to count. The reliable signal is
	 * the game message every one of the ten pubs prints: "<bartender>
	 * signs your card". Frontier sub only: the message names no bar, so
	 * order is the disambiguator.
	 */
	@Subscribe
	public void onChatMessage(net.runelite.api.events.ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE
			&& event.getType() != ChatMessageType.SPAM)
		{
			return;
		}
		if (!config.autoCompleteSteps() || loginGraceTicks > 0
			|| !event.getMessage().contains("signs your card"))
		{
			return;
		}
		Current current = findCurrent();
		if (current != null
			&& current.sub.getPlainText().toLowerCase(Locale.ROOT).contains("barcrawl"))
		{
			completeSubGoal(current.step, current.sub, "barcrawl card signed");
			if (panel != null)
			{
				SwingUtilities.invokeLater(panel::refresh);
			}
		}
	}

	/** The bank interface (re)opened: (re)create our filter button in it. */
	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == net.runelite.api.gameval.InterfaceID.BANKMAIN)
		{
			// A fresh bank interface: any widgets we created in the old one
			// are gone with it.
			bankMissingSection.invalidate();
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
			// Clear our search too — leaving "ironman" typed made the tab
			// view show a weird intersection instead of the tab's items.
			bankFilterButton.deactivate(true);
		}
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() == net.runelite.api.ScriptID.BANKMAIN_SEARCH_TOGGLE
			&& bankFilterButton.isActive())
		{
			// The player opened their own search — keep it, just step aside.
			// (Our filter no longer touches search state, so this event can
			// only be the player's.)
			bankFilterButton.deactivate(false);
		}

		if (event.getScriptId() == net.runelite.api.ScriptID.BANKMAIN_BUILD)
		{
			// Filter view active (button, or a keyword typed by hand): the
			// native grid is blanked (see bankSearchFilter) and EVERY
			// upcoming step renders as its own section — all of its items,
			// have/need counts, Quest Helper-style.
			boolean filterView = bankFilterActive();
			List<com.ironscape.items.BankMissingSection.Section> sections = new ArrayList<>();
			if (filterView)
			{
				refreshUpcomingNeeds();
				sections = upcomingSections;
			}
			bankMissingSection.update(filterView, sections);
		}
	}

	/** Button toggled on, or the filter keyword typed into the bank search. */
	private boolean bankFilterActive()
	{
		if (bankFilterButton.isActive())
		{
			return true;
		}
		String search = client.getVarcStrValue(net.runelite.api.VarClientStr.INPUT_TEXT);
		return search != null && BANK_FILTER_KEYWORDS.contains(
			search.trim().toLowerCase(java.util.Locale.ROOT));
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
				// Landing somewhere new invalidates the walking route: point
				// Shortest Path at the current destination FROM HERE ("home
				// tele to lumby, run north to Varrock east bank" — the
				// route to the bank should appear the moment you land).
				maybeNavigateToNext();
			}
			lastTickPosition = here;
		}

		// Quest state and player position have no change events of their
		// own; polling every other tick is cheap.
		if (++tickCounter % 2 == 0)
		{
			evaluateAutoCompletion();

			// Starting a quest fires no progress event, so the route to
			// its start point would linger under Quest Helper's guidance.
			// React to the handoff transition in both directions.
			boolean questOwns = questHelperOwnsGuidance();
			if (questOwns != lastQuestOwnsGuidance)
			{
				lastQuestOwnsGuidance = questOwns;
				maybeNavigateToNext();
			}
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

		// "Home tele to lumby": highlight the spellbook click path (spell if
		// the book is open, else the Magic tab) while that sub is current.
		homeTeleportHint = config.showTeleportHints() && current != null
			&& activeMinigameTarget == null
			&& HOME_TELEPORT.matcher(current.sub.getPlainText()).find();

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
				// Any quest goal on an UNSTARTED quest: whether the step
				// says "start X" or "complete X", the start point is where
				// you must go first.
				GoalDetector.QuestGoal questGoal = questGoalBySub.get(current.sub.getId());
				if (questGoal != null
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
			if (target == null)
			{
				target = annotationManager.getTarget(current.step.getId());
			}
			if (target != null)
			{
				spot = new WorldPoint(target.x, target.y, target.plane);
			}
		}
		targetTileMarker = spot;

		// Shop-keeper anchor: for a sub that still needs items ("From
		// sawmill buy 500 bronze nails"), the sub's resolved nav target
		// (⌖ capture, place name, or the step's 📍 tag) marks the shop —
		// the nearest NPC to it gets the outline and the wanted item
		// floats over their head, Quest Helper-style. ⌖ captures keep
		// priority; the ≤4-tile rule below keeps town-center points from
		// outlining random passers-by.
		WorldPoint shopAnchor = spot;
		if (shopAnchor == null && current != null
			&& itemGoalsBySub.containsKey(current.sub.getId()))
		{
			// Text places only — the step's 📍 town tag is far too coarse
			// to nominate a shopkeeper (it outlined random passers-by at
			// the town center).
			shopAnchor = placeManager.firstPlaceIn(current.sub.getPlainText());
		}

		// NPC targets: outline scene NPCs whose name the current sub-step
		// mentions ("speak with Veos" -> Veos). Names matched once per
		// tick; the overlay re-reads the live hulls per frame, which is
		// what keeps the outline glued to wandering NPCs.
		java.util.Set<String> npcNames = new java.util.HashSet<>();
		if (current != null)
		{
			// The step's NOTE lines join the scan: "Note: Use phials to
			// un-note planks" names the NPC the step is really about even
			// though no sub clause does.
			StringBuilder scanned = new StringBuilder(current.sub.getPlainText());
			for (List<TextRun> noteRuns : current.step.getAdditionalContent())
			{
				scanned.append(' ');
				for (TextRun run : noteRuns)
				{
					scanned.append(run.getText());
				}
			}
			String subText = " " + scanned.toString().toLowerCase(Locale.ROOT)
				.replace('’', '\'') + " ";
			// Nearest ONE to each anchor point — "everyone within 4 tiles"
			// outlined the whole gnome crowd around Gulluck's shop.
			String nearestToMarker = null;
			String nearestToSpot = null;
			int markerBest = Integer.MAX_VALUE;
			int spotBest = Integer.MAX_VALUE;
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
				// The quest giver is rarely NAMED by the step ("Do Waterfall
				// quest..."), but whoever stands NEAREST the quest's start
				// point is the quest giver — same for a ⌖ target and its
				// shopkeeper (Gulluck at his weapon shop).
				if (marker != null
					&& npc.getWorldLocation().getPlane() == marker.getPlane())
				{
					int distance = npc.getWorldLocation().distanceTo2D(marker);
					if (distance <= 4 && distance < markerBest)
					{
						markerBest = distance;
						nearestToMarker = clean;
					}
				}
				if (shopAnchor != null
					&& npc.getWorldLocation().getPlane() == shopAnchor.getPlane())
				{
					int distance = npc.getWorldLocation().distanceTo2D(shopAnchor);
					if (distance <= 4 && distance < spotBest)
					{
						spotBest = distance;
						nearestToSpot = clean;
					}
				}
			}
			// A name the step TEXT matched wins outright: "buy 2 teleport
			// cards from Diango" must outline only Diango — the nearest-NPC
			// fallback exists for steps that DON'T name their NPC, and with
			// NPCs wandering it happily picked a villager standing closer
			// to the anchor than the actual seller.
			if (npcNames.isEmpty())
			{
				if (nearestToMarker != null)
				{
					npcNames.add(nearestToMarker);
				}
				if (nearestToSpot != null)
				{
					npcNames.add(nearestToSpot);
				}
			}
		}
		npcTargetNames = npcNames;

		// The item you're there to BUY floats over the outlined NPC's
		// head: first still-unmet item goal of the current sub.
		int wantedIcon = -1;
		if (current != null)
		{
			List<GoalDetector.ItemGoal> wanted = itemGoalsBySub.get(current.sub.getId());
			if (wanted != null)
			{
				for (GoalDetector.ItemGoal goal : wanted)
				{
					boolean gather = itemTracker.bankCountable(goal.getItemName(), goal.getQuantity());
					int count = gather
						? itemTracker.countOf(goal.getItemName())
						: itemTracker.carriedCountOf(goal.getItemName());
					if (count < goal.getQuantity())
					{
						wantedIcon = itemTracker.iconIdFor(goal.getItemName());
						break;
					}
				}
			}
			// Depletion subs ("bookcases until out of planks") float the
			// item being USED UP instead — over Phials' head it reads as
			// "bring him the planks" — until none are left and the sub
			// ticks itself.
			String depleting = depletionBySub.get(current.sub.getId());
			if (wantedIcon == -1 && depleting != null
				&& itemTracker.carriedCountOf(depleting) > 0)
			{
				wantedIcon = itemTracker.iconIdFor(depleting);
			}
		}
		currentSubItemIcon = wantedIcon;

		// Ground items the current sub wants picked up ("Pick up 2 iron
		// bars...", item spawns): highlight their tiles, QH-style.
		groundItemTargets = config.showGroundItemMarkers() && current != null
			? findWantedGroundItems(current)
			: java.util.Collections.emptyList();
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
		List<com.ironscape.overlay.StepOverlay.Requirement> reqs = new ArrayList<>();
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
						// unstackable gathers are green on TOTAL, like the panel
						boolean enough = carried >= goal.getQuantity()
							|| (itemTracker.bankCountable(goal.getItemName(), goal.getQuantity())
								&& have >= goal.getQuantity());
						if (enough)
						{
							// Owner request: satisfied items drop OFF the
							// overlay ("bought the pineapple, stop showing
							// it") — what's left is the live shopping list.
							// The panel badges still show everything.
							continue;
						}
						java.awt.Color color =
							have >= goal.getQuantity() ? OVERLAY_ORANGE : OVERLAY_RED;
						reqs.add(new com.ironscape.overlay.StepOverlay.Requirement(
							goal.getItemName(), have + "/" + goal.getQuantity(), color));
					}
				}
				List<GoalDetector.SkillLevelGoal> levels = levelGoalsBySub.get(sub.getId());
				if (levels != null)
				{
					for (GoalDetector.SkillLevelGoal goal : levels)
					{
						int have = realLevelBySkill.getOrDefault(goal.getSkill(), 1);
						reqs.add(new com.ironscape.overlay.StepOverlay.Requirement(
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
					reqs.add(new com.ironscape.overlay.StepOverlay.Requirement(
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
		stepOverlayModel = new com.ironscape.overlay.StepOverlay.Model(
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
	 * whenever a bank search is active. While our filter is on, the answer
	 * is "hide it" for EVERYTHING: the native grid scattered matches under
	 * their tab separators (and fought the selected tab), so the whole
	 * grid is blanked and BankMissingSection draws the per-step sections
	 * instead — the full Quest Helper look.
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
		boolean keywordSearch = search != null
			&& BANK_FILTER_KEYWORDS.contains(search.trim().toLowerCase(java.util.Locale.ROOT));
		if (!keywordSearch && !bankFilterButton.isActive())
		{
			return;
		}
		int[] intStack = client.getIntStack();
		intStack[client.getIntStackSize() - 2] = 0; // 0 = hide this bank slot
	}

	/** How many upcoming incomplete STEPS the bank filter collects items from. */
	private static final int BANK_FILTER_STEPS = 10;

	/**
	 * Rebuilds the per-step item sections the bank filter renders — the
	 * next few steps' needs, starting at the frontier. Step-count scoped:
	 * a fixed sub-step window reached too far, and section scoping
	 * collected almost nothing near a section boundary. Cached per tick.
	 */
	private void refreshUpcomingNeeds()
	{
		if (bankFilterCacheTick == tickCounter)
		{
			return;
		}
		List<com.ironscape.items.BankMissingSection.Section> sections = new ArrayList<>();
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
				com.ironscape.items.BankMissingSection.Section section =
					new com.ironscape.items.BankMissingSection.Section(
						truncate(step.getPlainText().trim(), 48));
				for (StepAnnotation.ItemNeed need : annotationManager.getItems(step.getId()))
				{
					String name = need.name.toLowerCase(Locale.ROOT);
					int quantity = need.quantity == null ? 1 : need.quantity;
					section.items.merge(name, quantity, Math::max);
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
							section.items.merge(goal.getItemName(), goal.getQuantity(), Math::max);
						}
					}
					for (StepAnnotation.ItemNeed need : annotationManager.getItems(sub.getId()))
					{
						String name = need.name.toLowerCase(Locale.ROOT);
						int quantity = need.quantity == null ? 1 : need.quantity;
						section.items.merge(name, quantity, Math::max);
					}
				}
				sections.add(section);
			}
		}
		upcomingSections = sections;
		bankFilterCacheTick = tickCounter;
	}

	/** Per-step needs of the next steps, for the bank's filter sections. */
	private List<com.ironscape.items.BankMissingSection.Section> upcomingSections = new ArrayList<>();

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
			List<Current> window = findWindow(autoCompleteWindow());
			GuideStep frontierStep = window.isEmpty() ? null : window.get(0).step;
			for (int i = 0; i < window.size(); i++)
			{
				Current current = window.get(i);
				// Reviewed requirements complete a WHOLE step when ALL are met.
				List<StepRequirement> requirements = stepSkillRequirements.get(current.step.getId());
				if (requirements != null && requirementsMet(requirements))
				{
					completeStep(current.step, "annotated requirements met");
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
					completeSubGoal(current.step, current.sub, "quest checkpoint (varbit/varp)");
					completedSomething = true;
					break;
				}

				if (currentSubSatisfied(current.step, current.sub, i == 0,
					current.step == frontierStep))
				{
					completeSubGoal(current.step, current.sub, "goal satisfied (items/quest/level/arrival)");
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

	/** Is this step the player's current frontier step? */
	private boolean isFrontierStep(GuideStep step)
	{
		Current current = findCurrent();
		return current != null && current.step == step;
	}

	/** The player's position (see ProgressManager#playerPosition). */
	private int playerPosition()
	{
		return progressManager.playerPosition(guideFor(activeVariant));
	}

	/**
	 * The first `limit` incomplete sub-steps in guide order — starting
	 * AFTER the player's POSITION (ProgressManager#position), skipping
	 * anything already ticked. Position, not "last completed step":
	 * a quest done ages ago auto-ticks its step far ahead, and anchoring
	 * on that would teleport the frontier past undone steps (owner hit
	 * exactly this with Daddy's Home). Unticked steps BEHIND position
	 * were skipped on purpose and stay out of the window.
	 */
	private List<Current> findWindow(int limit)
	{
		List<Current> window = new ArrayList<>();
		Guide guide = guideFor(activeVariant);
		List<GuideStep> steps = guide.getAllSteps();
		int start = Math.max(0, playerPosition() + 1);
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
				// Carried only, so banked items don't tick "grab X" — EXCEPT
				// gather goals bigger than an inventory ("pick up 130
				// planks"): those count the bank too, because banking
				// batches is how the gather happens.
				boolean gather = itemTracker.bankCountable(goal.getItemName(), goal.getQuantity());
				int count = gather
					? itemTracker.countOf(goal.getItemName())
					: itemTracker.carriedCountOf(goal.getItemName());
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
					if (baseline == null || count < baseline)
					{
						// (Re)base — also downward, so banking the spares
						// and then buying still registers as a gain.
						acquisitionBaseline.put(key, count);
						baseline = count;
					}
					if (count <= baseline)
					{
						return false;
					}
				}
				if (count < goal.getQuantity())
				{
					return false;
				}
			}
			// A sub can carry BOTH kinds of target — "until 200k cash, get
			// at least 22 fletching": the gold alone must not tick it.
			List<GoalDetector.SkillLevelGoal> itemSubLevels = levelGoalsBySub.get(sub.getId());
			if (itemSubLevels != null)
			{
				for (GoalDetector.SkillLevelGoal goal : itemSubLevels)
				{
					if (client.getRealSkillLevel(goal.getSkill()) < goal.getLevel())
					{
						return false;
					}
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

		// "make bookcases until out of planks" — done when the last one is
		// used up. Only arms after the player is SEEN holding the item.
		String depletionItem = depletionBySub.get(sub.getId());
		if (depletionItem != null)
		{
			if (!inFrontierStep)
			{
				return false;
			}
			if (itemTracker.carriedCountOf(depletionItem) > 0)
			{
				depletionArmed.add(sub.getId());
				return false;
			}
			return depletionArmed.contains(sub.getId());
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
		// No early false: a travel sub can ALSO complete by arriving at its
		// destination below ("Home tele to lumby and run north to Varrock
		// east bank" — walking the second half needs arrival detection).
		if (travelGoalSubs.contains(sub.getId())
			&& inFrontierStep && recentTeleportTicks > 0)
		{
			return true;
		}

		// No item/quest goal: a movement step. Arriving at its target
		// (⌖ capture or recognised place name) completes it — but only at
		// the frontier.
		if (!frontier)
		{
			return false;
		}
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return false;
		}

		// "Walk to Ardy WITH rope, dwellberries, hangover cure" — the
		// items are part of the errand: every annotation item must be in
		// hand before arriving can tick the step.
		String annotationId = step.getSubSteps().size() == 1 ? step.getId() : sub.getId();
		for (StepAnnotation.ItemNeed need : annotationManager.getItems(annotationId))
		{
			int required = need.quantity == null ? 1 : need.quantity;
			int count = itemTracker.bankCountable(need.name, required)
				? itemTracker.countOf(need.name)
				: itemTracker.carriedCountOf(need.name);
			if (count < required)
			{
				return false;
			}
		}

		WorldPoint here = player.getWorldLocation();
		// A ⌖ capture is a precise spot; a place name is a whole town —
		// entering from any gate should count.
		StepAnnotation.Target precise = annotationManager.getTarget(sub.getId());
		if (precise == null)
		{
			precise = annotationManager.getTarget(step.getId());
		}
		if (precise != null)
		{
			return here.getPlane() == precise.plane
				&& here.distanceTo(new WorldPoint(precise.x, precise.y, precise.plane)) <= ARRIVE_RADIUS;
		}
		// Travel subs end at their LAST place mention (the destination);
		// everything else anchors on the first ("Talk to Reldo" -> Reldo).
		WorldPoint place = travelGoalSubs.contains(sub.getId())
			? placeManager.lastPlaceIn(sub.getPlainText())
			: placeManager.firstPlaceIn(sub.getPlainText());
		return place != null
			&& here.getPlane() == place.getPlane()
			&& here.distanceTo(place) <= PLACE_ARRIVE_RADIUS;
	}

	/** A whole step completed by its skill requirement annotation. */
	private void completeStep(GuideStep step, String reason)
	{
		// ALWAYS logged (even silent login-grace catch-ups): when a stray
		// tick drags the frontier ahead, this line is the forensic trail.
		log.info("auto-completed step {} ({}){}: {}", step.getId(), reason,
			loginGraceTicks > 0 ? " [login grace]" : "",
			step.getPlainText().trim());
		boolean atFrontier = isFrontierStep(step);
		progressManager.setCompleted(activeVariant, step, true);
		if (atFrontier)
		{
			// Only the FRONTIER step's completion moves the player's
			// position — a pre-done quest ticking five steps ahead must not.
			progressManager.advancePositionTo(activeVariant, step.getGlobalIndex());
		}
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
	private void completeSubGoal(GuideStep step, SubStep sub, String reason)
	{
		// See completeStep: the permanent forensic trail for auto-ticks.
		log.info("auto-completed sub {} ({}){}: {}", sub.getId(), reason,
			loginGraceTicks > 0 ? " [login grace]" : "",
			sub.getPlainText().trim());
		boolean atFrontier = isFrontierStep(step);
		progressManager.setSubCompleted(activeVariant, step, sub, true);
		if (atFrontier && progressManager.isCompleted(activeVariant, step.getId()))
		{
			progressManager.advancePositionTo(activeVariant, step.getGlobalIndex());
		}

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
			for (com.ironscape.annotations.StepAnnotation.Requirement requires : requirementList)
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
			// Quest in progress = Quest Helper's show. Two guidance systems
			// pointing different ways is worse than one: clear our route
			// and stand down until the quest completes and the step ticks
			// (the same handoff the quest-start marker already does).
			if (questHelperOwnsGuidance())
			{
				eventBus.post(new PluginMessage("shortestpath", "clear"));
				return;
			}
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

	/**
	 * True while the frontier step's quest is IN PROGRESS — started but
	 * not finished. From "quest accepted" to "quest complete" the player
	 * is following Quest Helper's own guidance; our navigation stands
	 * down rather than fight it. Covers both text/metadata quest goals
	 * ("complete the Pandemonium quest") and varbit-checkpoint steps
	 * ("do X up to the orb"), whose step metadata names the quest even
	 * when no quest goal was detected. Client thread only.
	 */
	private boolean questHelperOwnsGuidance()
	{
		Current current = findCurrent();
		if (current == null)
		{
			return false;
		}
		GoalDetector.QuestGoal goal = questGoalBySub.get(current.sub.getId());
		if (goal != null)
		{
			return goal.getQuest().getState(client) == QuestState.IN_PROGRESS;
		}
		String questName = current.step.getMetadata().get("quest");
		if (questName == null)
		{
			return false;
		}
		for (Quest quest : Quest.values())
		{
			if (quest.getName().equalsIgnoreCase(questName.trim()))
			{
				return quest.getState(client) == QuestState.IN_PROGRESS;
			}
		}
		return false;
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
		if (target == null)
		{
			target = annotationManager.getTarget(step.getId());
		}
		if (target != null)
		{
			return new WorldPoint(target.x, target.y, target.plane);
		}
		// A travel sub's destination is the LAST place it names.
		WorldPoint inText = travelGoalSubs.contains(sub.getId())
			? placeManager.lastPlaceIn(sub.getPlainText())
			: placeManager.firstPlaceIn(sub.getPlainText());
		if (inText != null)
		{
			return inText;
		}
		// No recognised place in the text: fall back to the step's authored
		// 📍 location tag ("Varrock", "West of Lumbridge"), so EVERY tagged
		// step navigates at least to the right area instead of silently
		// going nowhere.
		String location = step.getMetadata().get("location");
		return location == null ? null : placeManager.getLoose(location);
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

		WorldPoint found = placeManager.get(placeName);
		if (found == null)
		{
			// Oziris location tags are often directional ("North of
			// Ardougne", "West of Lumbridge") — route to the base place;
			// close enough to be useful.
			String base = placeName.replaceFirst(
				"(?i)^(?:north|south|east|west)(?:[ -](?:north|south|east|west))?\\s+of\\s+(?:the\\s+)?", "");
			found = placeManager.get(base);
		}
		if (found == null)
		{
			// Silence reads as breakage — say why the click did nothing.
			clientThread.invokeLater(() -> client.addChatMessage(ChatMessageType.CONSOLE, "",
				"IRONSCAPE: no saved location for \"" + placeName
					+ "\" — stand there and add it with the panel's + button.", null));
			return;
		}
		WorldPoint point = found; // effectively final for the lambdas below

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
	IronscapeConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(IronscapeConfig.class);
	}
}
