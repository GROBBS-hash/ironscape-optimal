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
	private net.runelite.client.game.SkillIconManager skillIconManager;

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
	private com.google.gson.Gson gson;

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
	private com.ironscape.overlay.TravelMenuOverlay travelMenuOverlay;

	@Inject
	private com.ironscape.overlay.StepOverlay stepOverlay;

	@Inject
	private com.ironscape.overlay.QuestStartMarkerOverlay questStartMarkerOverlay;

	@Inject
	private com.ironscape.overlay.NpcTargetOverlay npcTargetOverlay;

	@Inject
	private com.ironscape.overlay.TargetTileOverlay targetTileOverlay;

	@Inject
	private com.ironscape.overlay.ObjectTargetOverlay objectTargetOverlay;

	@Inject
	private com.ironscape.overlay.InventoryItemHintOverlay inventoryItemHintOverlay;

	/** Inventory slot item ids the current step is about; overlay-outlined. */
	private volatile java.util.Set<Integer> inventoryHintItemIds = java.util.Collections.emptySet();

	/** "use the house tab", "fally teletab to..." — the tab phrase in a travel sub. */
	private static final java.util.regex.Pattern TAB_PHRASE = java.util.regex.Pattern.compile(
		"\\b([a-z]+(?:\\s+[a-z]+)?\\s+(?:tele)?tab)s?\\b", java.util.regex.Pattern.CASE_INSENSITIVE);

	/**
	 * The sub PRESCRIBES its own transport ("Home tele, Lumby", "charter to
	 * port sarim") — route-aware first-leg hints must stand down: suggesting
	 * Shades of Mort'ton as a shortcut toward a free home teleport misleads.
	 * ("telegrab" stays unmatched: tele needs a word boundary after it.)
	 */
	private static final java.util.regex.Pattern PRESCRIBED_TRANSPORT =
		java.util.regex.Pattern.compile("\\btele(?:port|tab)?s?\\b|\\btabs?\\b"
			+ "|\\bcharter\\b|\\bglider\\b|\\bfairy rings?\\b|\\bspirit trees?\\b"
			+ "|\\bcanoes?\\b|\\bboat\\b|\\bship\\b|\\bsail\\b");

	/** Live scene objects (ore rocks) the current sub is about; overlay-outlined. */
	private volatile List<net.runelite.api.GameObject> objectTargets = java.util.Collections.emptyList();

	/**
	 * Anchor-nominated NPCs by INDEX — the one shopkeeper nearest a ⌖
	 * target. Index, not name: a "Barbarian" nominee must not light up
	 * every barbarian in the building.
	 */
	private volatile java.util.Set<Integer> npcTargetIndexes = java.util.Collections.emptySet();

	/** Ore/stone item goal -> the LIVE rock object that yields it. */
	private static final Map<String, String> ROCK_BY_ORE = Map.ofEntries(
		Map.entry("copper ore", "copper rocks"),
		Map.entry("tin ore", "tin rocks"),
		Map.entry("iron ore", "iron rocks"),
		Map.entry("coal", "coal rocks"),
		Map.entry("silver ore", "silver rocks"),
		Map.entry("gold ore", "gold rocks"),
		Map.entry("clay", "clay rocks"),
		Map.entry("sandstone", "sandstone rocks"),
		Map.entry("mithril ore", "mithril rocks"),
		Map.entry("adamantite ore", "adamantite rocks"),
		Map.entry("runite ore", "runite rocks"));

	/** "steal from the fruit stall" — a qualified stall is a scene OBJECT. */
	private static final java.util.regex.Pattern STALL_PHRASE =
		java.util.regex.Pattern.compile("\\b([a-z']+)\\s+stalls?\\b");

	/** Words that precede "stall" without naming one ("the stall at..."). */
	private static final java.util.Set<String> STALL_STOPWORDS =
		java.util.Set.of("the", "a", "an", "that", "this", "from", "one", "each", "both");

	/**
	 * Wiki-verified xp per successful action at a grind object — drives the
	 * "N to go" label over the outline when the sub carries a level goal.
	 */
	private static final Map<String, Double> XP_PER_ACTION = Map.of(
		"fruit stall", 28.5);

	/** "1,234 to go" floated over outlined grind objects; null = hidden. */
	private volatile String objectActionsLabel;

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
			if (isCoins(goal.getItemName()))
			{
				continue; // stray dropped gp is not "your step's items"
			}
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

	/** Any acquisition ("buy X") goal on this sub — the shop-anchor gate. */
	private boolean hasPurchaseGoal(SubStep sub)
	{
		List<GoalDetector.ItemGoal> subGoals = itemGoalsBySub.get(sub.getId());
		if (subGoals == null)
		{
			return false;
		}
		for (GoalDetector.ItemGoal goal : subGoals)
		{
			if (goal.isAcquisition())
			{
				return true;
			}
		}
		return false;
	}

	/** Gold is a grind target, never something an NPC hands you — no coin
	 *  icons over heads, no ground-coin tile markers. */
	private static boolean isCoins(String itemName)
	{
		for (String alias : ItemTracker.aliases(itemName))
		{
			if (alias.equals("coins"))
			{
				return true;
			}
		}
		return false;
	}

	/** True when [start, end) sits strictly inside a LONGER span. */
	private static boolean insideLongerSpan(List<int[]> spans, int start, int end)
	{
		for (int[] span : spans)
		{
			if (span[0] <= start && end <= span[1] && span[1] - span[0] > end - start)
			{
				return true;
			}
		}
		return false;
	}

	/** Whether {@code word} appears in {@code text} as a whole word. */
	/** "imp" also as "imps", "wolf" as "wolves", "fairy" as "fairies". */
	private static String[] pluralVariants(String name)
	{
		if (name.endsWith("f"))
		{
			return new String[]{name, name.substring(0, name.length() - 1) + "ves"};
		}
		if (name.endsWith("fe"))
		{
			return new String[]{name, name.substring(0, name.length() - 2) + "ves"};
		}
		if (name.endsWith("y"))
		{
			return new String[]{name, name.substring(0, name.length() - 1) + "ies"};
		}
		return new String[]{name, name + "s", name + "es"};
	}

	private static boolean containsWord(String text, String word)
	{
		int at = text.indexOf(word);
		while (at >= 0)
		{
			boolean startOk = at == 0 || !Character.isLetter(text.charAt(at - 1));
			int end = at + word.length();
			boolean endOk = end == text.length() || !Character.isLetter(text.charAt(end));
			if (startOk && endOk)
			{
				return true;
			}
			at = text.indexOf(word, at + 1);
		}
		return false;
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

	/** Earliest guide step (global index) each quest's goal appears on. */
	private final Map<Quest, Integer> minStepIndexByQuest = new HashMap<>();

	/**
	 * Last observed state per quest — the jumped-ahead transition baseline
	 * AND the read cache for every per-tick quest-state consumer.
	 * Quest.getState runs a CLIENTSCRIPT: the old code executed it for
	 * ~100 guide quests every tick plus per-sub in the subsume loop, and
	 * that script spam on the shared client thread degraded Quest
	 * Helper's own behavior (owner: "I have to reload quest state to get
	 * QH pathing to work"). Refreshed by the every-5-ticks scan; misses
	 * fall through to one live read. Client thread only.
	 */
	private final Map<Quest, QuestState> lastQuestState = new HashMap<>();

	/** Cached quest state (≤3s stale) — use for all per-tick reads. */
	private QuestState cachedQuestState(Quest quest)
	{
		QuestState state = lastQuestState.get(quest);
		if (state == null)
		{
			state = quest.getState(client);
			lastQuestState.put(quest, state);
		}
		return state;
	}

	/** The later-step quest the player started live; null = none. */
	private Quest jumpedAheadQuest;

	/**
	 * Where the player died — the gravestone (or Wilderness item pile)
	 * stands there. While set, navigation routes HERE above everything
	 * else: no gear, no route. Cleared on getting within 8 tiles.
	 */
	private WorldPoint deathPoint;

	/**
	 * True while a quest belonging to a LATER guide step is in progress —
	 * the player jumped ahead (doing Tourist Trap while the frontier is
	 * still Sleeping Giants). ALL frontier guidance stands down: routing
	 * them back mid-quest interrupts Quest Helper. Drive-by starts from
	 * EARLIER steps never trigger this.
	 */
	private volatile boolean playerJumpedAhead;

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

	/**
	 * Swing-readable cache of each varbit/varp checkpoint sub's met-state
	 * (the "stamp 0/1" badges can't read the client off its thread).
	 * Refreshed every game tick; a flip re-renders the panel badges.
	 */
	private final Map<String, Boolean> checkpointMetBySub = new java.util.concurrent.ConcurrentHashMap<>();

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

	/** The quest we last handed guidance to (Quest Helper's show), to pull
	 * our panel back to the front the moment it FINISHES. */
	private Quest handedOffQuest;

	/** Words the travel-menu overlay matches list entries against. */
	private volatile java.util.Set<String> travelMenuWords = java.util.Collections.emptySet();

	/** Last widget group loaded while a travel sub was current (-1 = none). */
	private volatile int travelMenuGroup = -1;

	/** Errand stages whose nudge already fired this session (step|item). */
	private final java.util.Set<String> errandReminded = new java.util.HashSet<>();

	/**
	 * Errand stages seen OWNED at least once this session (step|item).
	 * Sticky on purpose: the gate key disappears into the lock, and the
	 * chain must not point back at the empty crate.
	 */
	private final java.util.Set<String> errandDone = new java.util.HashSet<>();

	/** Last poll of activeErrand()'s stage item, to reroute on stage changes. */
	private String lastErrandStage;

	/**
	 * The first unsatisfied stage of the current sub/step's errand chain,
	 * once its quest has STARTED (before that, quest-start guidance is
	 * the right pointer). Deliberately still active after the quest
	 * finishes: completing Tree Gnome Village without the pebble leaves
	 * the errand as the step's only remaining signal.
	 */
	private StepAnnotation.Errand activeErrand()
	{
		Current current = findCurrent();
		if (current == null)
		{
			return null;
		}
		// Quest steps wait for the quest to START (before that, the
		// quest-start guidance is the right pointer). Quest-LESS steps
		// ("run to ZMI bank and safespot...") have no such conflict —
		// their errand chain guides from the moment the step is current.
		Quest quest = stepQuest(current);
		if (quest != null && quest.getState(client) == QuestState.NOT_STARTED)
		{
			return null;
		}
		return unsatisfiedErrandStage(current.step, current.sub);
	}

	/**
	 * First unsatisfied stage of the sub/step's errand chain, else null.
	 * A stage seen OWNED once stays satisfied for the session, and owning
	 * a LATER stage's item satisfies every earlier one — the gate key
	 * disappears into the lock, but holding the pebble proves it served.
	 */
	/** The sub/step's errand chain (sub-keyed winning), possibly empty. */
	private List<StepAnnotation.Errand> errandChain(GuideStep step, SubStep sub)
	{
		List<StepAnnotation.Errand> chain = annotationManager.getErrands(sub.getId());
		return chain.isEmpty() ? annotationManager.getErrands(step.getId()) : chain;
	}

	private StepAnnotation.Errand unsatisfiedErrandStage(GuideStep step, SubStep sub)
	{
		List<StepAnnotation.Errand> chain = errandChain(step, sub);
		if (chain.isEmpty())
		{
			return null;
		}
		Player me = client.getLocalPlayer();
		WorldPoint here = me == null ? null : me.getWorldLocation();
		for (int i = 0; i < chain.size(); i++)
		{
			StepAnnotation.Errand stage = chain.get(i);
			boolean satisfied;
			if (stage.value != null && (stage.varbit != null || stage.varp != null))
			{
				// Var-gated stage: quest progress orders stages that sit
				// tiles apart (Cook -> large doors), where proximity can't.
				int varValue = stage.varbit != null
					? client.getVarbitValue(stage.varbit)
					: client.getVarpValue(stage.varp);
				satisfied = varValue >= stage.value;
			}
			else if (stage.item != null)
			{
				// Intermediate stages count CARRIED only: quest keys are
				// all literally named "Key", and an unrelated one in the
				// BANK must not skip the crate. The LAST stage is the
				// objective itself and may sit banked — still done.
				satisfied = (i == chain.size() - 1
					? itemTracker.countOf(stage.item)
					: itemTracker.carriedCountOf(stage.item)) > 0;
			}
			else
			{
				// Item-less WAYPOINT stage (the cave entrance on the way
				// to the warriors): satisfied by getting there — or by
				// being closer to the NEXT stage than to it (a teleport
				// skipped it; it served its purpose either way).
				int radius = stage.radius != null ? stage.radius : 12;
				satisfied = here != null
					&& (here.distanceTo2D(new WorldPoint(stage.x, stage.y, stage.plane)) <= radius
						|| (i + 1 < chain.size()
							&& here.distanceTo2D(new WorldPoint(chain.get(i + 1).x,
								chain.get(i + 1).y, chain.get(i + 1).plane))
								< here.distanceTo2D(new WorldPoint(stage.x, stage.y, stage.plane))));
			}
			if (satisfied)
			{
				for (int k = 0; k <= i; k++)
				{
					errandDone.add(errandStageKey(step, chain.get(k)));
				}
			}
		}
		for (StepAnnotation.Errand stage : chain)
		{
			if (!errandDone.contains(errandStageKey(step, stage)))
			{
				return stage;
			}
		}
		return null;
	}

	/** Where the route (and marker) points for this stage — the routable entrance when set. */
	private static WorldPoint errandRoutePoint(StepAnnotation.Errand stage)
	{
		return stage.routeX != null && stage.routeY != null
			? new WorldPoint(stage.routeX, stage.routeY,
				stage.routePlane == null ? 0 : stage.routePlane)
			: new WorldPoint(stage.x, stage.y, stage.plane);
	}

	/** Sticky-satisfaction key for one errand stage (item, var, or waypoint). */
	private static String errandStageKey(GuideStep step, StepAnnotation.Errand stage)
	{
		if (stage.value != null && (stage.varbit != null || stage.varp != null))
		{
			return step.getId() + "|var:"
				+ (stage.varbit != null ? stage.varbit : "p" + stage.varp) + ">=" + stage.value;
		}
		return step.getId() + "|"
			+ (stage.item != null ? stage.item : "wp:" + stage.x + "," + stage.y);
	}

	/** The quest the current step is about (any state), else null. */
	private Quest stepQuest(Current current)
	{
		GoalDetector.QuestGoal goal = questGoalBySub.get(current.sub.getId());
		if (goal != null)
		{
			return goal.getQuest();
		}
		String questName = current.step.getMetadata().get("quest");
		if (questName == null)
		{
			return null;
		}
		for (Quest quest : Quest.values())
		{
			if (quest.getName().equalsIgnoreCase(questName.trim()))
			{
				return quest;
			}
		}
		return null;
	}

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
	/** "Use the spirit tree..." subs route to the NEAREST tree first. */
	private static final java.util.regex.Pattern SPIRIT_TREE =
		java.util.regex.Pattern.compile("(?i)spirit\\s+tree");

	/** "Charter to port sarim" — board the nearest charter ship first. */
	private static final java.util.regex.Pattern CHARTER =
		java.util.regex.Pattern.compile("(?i)\\bcharter\\b");

	/** "safespot the zamorak warrior" — names the ⌖ tile "Safespot". */
	private static final java.util.regex.Pattern SAFESPOT =
		java.util.regex.Pattern.compile("(?i)safe\\s*-?\\s*spot");

	/** A sub that KILLS something — species-word NPC matching is safe there. */
	private static final java.util.regex.Pattern COMBAT_VERB =
		java.util.regex.Pattern.compile("(?i)\\b(?:kill|slay|safe\\s*-?\\s*spot|fight)\\b");

	/** Label floated over the ⌖ tile marker; null = none. */
	private volatile String targetTileLabel;

	/** Spellbook teleport component the route wants next, or -1. */
	private volatile int activeSpellTeleport = -1;

	/** Subs whose TEXT has matched a scene NPC this session — the
	 * nearest-to-anchor fallback stays off for them permanently. */
	private final java.util.Set<String> namedNpcSubs = new java.util.HashSet<>();

	/** The always-available spirit trees (quest/farming ones excluded). */
	private static final WorldPoint[] SPIRIT_TREES = {
		new WorldPoint(2542, 3170, 0), // Tree Gnome Village
		new WorldPoint(2461, 3444, 0), // Gnome Stronghold
		new WorldPoint(2555, 3259, 0), // Battlefield of Khazard
		new WorldPoint(3183, 3508, 0), // Grand Exchange
		new WorldPoint(2488, 2850, 0), // Feldip Hills
	};

	/**
	 * Charter ship docks (Trader Crewmembers) — same network treatment as
	 * spirit trees: "Charter to port sarim" means board HERE, not walk
	 * there. Owner-tentative dock spots; correct in play.
	 */
	private static final WorldPoint[] CHARTER_DOCKS = {
		new WorldPoint(2674, 3149, 0), // Port Khazard
		new WorldPoint(2792, 3414, 0), // Catherby
		new WorldPoint(2760, 3238, 0), // Brimhaven
		new WorldPoint(3038, 3192, 0), // Port Sarim
		new WorldPoint(2954, 3158, 0), // Musa Point
		new WorldPoint(3702, 3503, 0), // Port Phasmatys
		new WorldPoint(3001, 3032, 0), // Shipyard
		new WorldPoint(2620, 2857, 0), // Corsair Cove
	};

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

	/**
	 * Wider "arrived" radius in the ticks right after a TELEPORT lands:
	 * teleport pads sit at the EDGE of the area they serve (the spirit
	 * tree stands ~34 tiles from the Khazard Battlefield point), and
	 * landing on one is the strongest possible arrival evidence.
	 */
	private static final int TELEPORT_ARRIVE_RADIUS = 45;

	/**
	 * A sub reads as a movement instruction — anywhere in the text, since
	 * travel is often compound ("Use the spirit tree and go to the
	 * battlefield"). Gates place-name arrival ticks.
	 */
	private static final java.util.regex.Pattern MOVEMENT_WORD = java.util.regex.Pattern.compile(
		"\\b(?:go|walk|run|head|return|travel|enter|exit|climb|cross|move|proceed|sail|ride|fly|swim|tele|teleport|tabs?|charter)\\b",
		java.util.regex.Pattern.CASE_INSENSITIVE);

	/**
	 * Frontier step id whose manual ⌖ capture is holding auto-navigation:
	 * while the frontier stays on this step, the captured route is not
	 * recomputed away. Null = no hold.
	 */
	private volatile String navHoldStepId;

	/**
	 * Sub ids seen OUTSIDE their place-name arrival radius while current —
	 * only then may arriving tick them (session-only, like baselines).
	 */
	private final java.util.Set<String> arrivalArmed = new java.util.HashSet<>();

	/**
	 * The minigame the player is AT right now, if any — presence carries
	 * across region borders while movement stays contiguous (walking from
	 * the Foundry entrance down into its interior region), and breaks on
	 * any teleport, so leaving brings the hint back. No one-way flags.
	 */
	private String minigamePresence;

	/**
	 * Regions CONFIRMED to be each minigame: recorded on teleport landings
	 * (the grouping teleport puts you there) and near-pin sightings.
	 * Session-only; makes teleporting back recognisable instantly.
	 */
	private final Map<String, java.util.Set<Integer>> minigameRegions = new HashMap<>();

	/**
	 * Presence survives a client restart: "minigame|region" saved whenever
	 * present, restored on login IF the player is still in that region —
	 * logging in inside the Foundry must not re-show the teleport hint.
	 */
	private String pendingPresenceRestore;
	private String lastSavedPresenceState;

	/** Game tick of the last GAME OBJECT click (ladder, cave, portal). */
	private int lastObjectClickTick = -10;

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
	/** Set on a real (re)connect: fire auto-nav once when the grace ends. */
	private boolean navOnLoginPending;

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
		loadMinigameLandings();
		loadGuideState();
		// "minigame|region" from the previous session — restored on the
		// first evaluation if the player is still standing in that region.
		pendingPresenceRestore = configManager.getConfiguration(CONFIG_GROUP, "minigamePresence");
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
		minStepIndexByQuest.clear();
		for (GoalDetector.QuestGoal goal : goals.getQuestGoals())
		{
			questGoalBySub.put(goal.getSub().getId(), goal);
			minStepIndexByQuest.merge(goal.getQuest(),
				goal.getStep().getGlobalIndex(), Math::min);
		}
		// Metadata quest tags join the min-index map: "Start Barcrawl
		// miniquest" never says the full "Alfred Grimhand's Barcrawl", so
		// no TEXT goal lands on the start step — leaving only the later
		// finish step in the map and making the long-running miniquest
		// look "jumped ahead" (which cleared ALL auto-navigation).
		for (GuideStep step : guideFor(activeVariant).getAllSteps())
		{
			String questName = step.getMetadata().get("quest");
			if (questName == null)
			{
				continue;
			}
			for (Quest quest : Quest.values())
			{
				if (quest.getName().equalsIgnoreCase(questName.trim()))
				{
					minStepIndexByQuest.merge(quest, step.getGlobalIndex(), Math::min);
					break;
				}
			}
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
		minigameTeleportOverlay.setSpellTeleportSupplier(() -> activeSpellTeleport);
		overlayManager.add(minigameTeleportOverlay);
		travelMenuOverlay.setWordsSupplier(() -> travelMenuWords);
		travelMenuOverlay.setGroupSupplier(() -> travelMenuGroup);
		overlayManager.add(travelMenuOverlay);
		stepOverlay.setModelSupplier(() -> stepOverlayModel);
		overlayManager.add(stepOverlay);
		questStartMarkerOverlay.setTargetSupplier(() -> questStartMarker);
		overlayManager.add(questStartMarkerOverlay);
		npcTargetOverlay.setNamesSupplier(() -> npcTargetNames);
		npcTargetOverlay.setIndexesSupplier(() -> npcTargetIndexes);
		npcTargetOverlay.setQuestIconSupplier(() -> currentSubIsQuest);
		npcTargetOverlay.setItemIconSupplier(() -> currentSubItemIcon);
		overlayManager.add(npcTargetOverlay);
		targetTileOverlay.setTargetSupplier(() -> targetTileMarker);
		targetTileOverlay.setLabelSupplier(() -> targetTileLabel);
		targetTileOverlay.setGroundItemsSupplier(() -> groundItemTargets);
		overlayManager.add(targetTileOverlay);
		objectTargetOverlay.setObjectsSupplier(() -> objectTargets);
		objectTargetOverlay.setLabelSupplier(() -> objectActionsLabel);
		objectTargetOverlay.setItemIconSupplier(() -> currentSubItemIcon);
		overlayManager.add(objectTargetOverlay);
		inventoryItemHintOverlay.setItemIdsSupplier(() -> inventoryHintItemIds);
		overlayManager.add(inventoryItemHintOverlay);

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
						.append(goal.getSkill().getName())
						.append(' ').append(have).append('/').append(goal.getLevel())
						.append("</font>");
				}
				return sb.toString();
			}
			// Reviewed annotation skill requirements get the SAME badge
			// row as items and level goals ("smithing 29/15") instead of
			// living only in the step's prose — uniform look (owner ask).
			List<StepRequirement> requirements = subRequirements.get(subId);
			if (requirements == null)
			{
				requirements = stepSkillRequirements.get(subId);
			}
			if (requirements != null)
			{
				StringBuilder sb = new StringBuilder();
				for (StepRequirement requirement : requirements)
				{
					if (requirement.skill == null)
					{
						// Varbit/varp checkpoints with an authored label get a
						// live "stamp 0/1" badge (met-state from the per-tick
						// cache); combat/unlabeled ones stay badge-less.
						if (requirement.label != null
							&& (requirement.varbit != null || requirement.varp != null))
						{
							boolean met = Boolean.TRUE.equals(checkpointMetBySub.get(subId));
							if (sb.length() > 0)
							{
								sb.append(" <font color='#606060'>·</font> ");
							}
							sb.append("<font color='").append(met ? "#4caf50" : "#ffa000")
								.append("'>").append(ItemTracker.capitalize(requirement.label))
								.append(' ').append(met ? 1 : 0).append("/1</font>");
						}
						continue;
					}
					int have = realLevelBySkill.getOrDefault(requirement.skill, 1);
					String reqColor = have >= requirement.threshold ? "#4caf50" : "#ffa000";
					if (sb.length() > 0)
					{
						sb.append(" <font color='#606060'>·</font> ");
					}
					sb.append("<font color='").append(reqColor).append("'>")
						.append(requirement.skill.getName())
						.append(' ').append(have).append('/').append(requirement.threshold)
						.append("</font>");
				}
				if (sb.length() > 0)
				{
					return sb.toString();
				}
			}
			GoalDetector.CountedSkillGoal counted = countedGoalBySub.get(subId);
			if (counted == null)
			{
				return null;
			}
			int seen = Math.min(progressManager.countedProgress(activeVariant, subId),
				counted.getCount());
			String color = seen >= counted.getCount() ? "#4caf50" : "#ffa000";
			return "<font color='" + color + "'>" + counted.getSkill().getName()
				+ " " + seen + "/" + counted.getCount() + " done</font>";
		});
		panel.setBadgeIconSupplier(subId -> {
			// Checkpoint badges can carry an item sprite ("Barcrawl card"
			// next to "stamp 0/1") — the icon name is authored in the
			// annotation, resolved via the same icon machinery as items.
			List<StepRequirement> requirements = subRequirements.get(subId);
			if (requirements == null)
			{
				return null;
			}
			for (StepRequirement requirement : requirements)
			{
				if (requirement.icon != null)
				{
					return requirement.icon;
				}
			}
			return null;
		});
		panel.setSkillIconSupplier(subId -> {
			// The skill whose progress the badge shows — same lookup chain
			// as the badge text, so icon and number always agree.
			Skill skill = null;
			List<GoalDetector.SkillLevelGoal> levels = levelGoalsBySub.get(subId);
			if (levels != null && !levels.isEmpty())
			{
				skill = levels.get(0).getSkill();
			}
			if (skill == null)
			{
				List<StepRequirement> requirements = subRequirements.get(subId);
				if (requirements == null)
				{
					requirements = stepSkillRequirements.get(subId);
				}
				if (requirements != null)
				{
					for (StepRequirement requirement : requirements)
					{
						if (requirement.skill != null)
						{
							skill = requirement.skill;
							break;
						}
					}
				}
			}
			if (skill == null)
			{
				GoalDetector.CountedSkillGoal counted = countedGoalBySub.get(subId);
				skill = counted == null ? null : counted.getSkill();
			}
			return skill == null ? null : skillIconManager.getSkillImage(skill, true);
		});
		panel.setProgressChangedListener(this::maybeNavigateToNext);
		panel.setCaptureHandler(this::captureLocation);
		panel.setSafespotCaptureHandler(this::captureSafespot);
		panel.setClearTargetHandler(this::clearCapturedTarget);
		panel.setNavigateHandler(this::navigateToStep);
		panel.setPlaceNavigateHandler(this::navigateToPlace);
		panel.setWorldHopHandler(this::hopToWorld);
		panel.setAddPlaceHandler(this::addPlace);
		panel.setClearPathHandler(this::clearPath);
		panel.setGuide(guideFor(activeVariant));

		// If we start while already logged in (plugin toggled mid-session),
		// prime the item counts and resume the route; otherwise the login
		// event does both.
		clientThread.invoke(() -> {
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				itemTracker.onLoggedIn();
				loginGraceTicks = 10;
				navOnLoginPending = true;
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
		overlayManager.remove(travelMenuOverlay);
		overlayManager.remove(stepOverlay);
		overlayManager.remove(questStartMarkerOverlay);
		overlayManager.remove(npcTargetOverlay);
		overlayManager.remove(targetTileOverlay);
		overlayManager.remove(objectTargetOverlay);
		overlayManager.remove(inventoryItemHintOverlay);
		npcTargetNames = java.util.Collections.emptySet();
		npcTargetIndexes = java.util.Collections.emptySet();
		objectTargets = java.util.Collections.emptyList();
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
		arrivalArmed.clear();
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
		arrivalArmed.clear();
		minigameRegions.clear();
		minigamePresence = null;
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
				List<StepRequirement> checkpoint = subRequirements.get(subId);
				if (checkpoint != null && hasVarCheckpoint(checkpoint))
				{
					continue; // authored checkpoint owns this sub's completion
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
			navOnLoginPending = true; // fire nav once the grace window ends
			lastXpBySkill.clear(); // next account/session sets fresh baselines
			// A (re)connect may be a DIFFERENT account: quest states must
			// re-baseline, and a jumped-ahead jaunt doesn't survive relog.
			lastQuestState.clear();
			jumpedAheadQuest = null;
			itemTracker.onLoggedIn();
			if (panel != null)
			{
				SwingUtilities.invokeLater(panel::refreshItemCounts);
			}
		}
		lastGameState = event.getGameState();
	}

	/**
	 * The local player died: remember the spot. The gravestone spawns
	 * where you fall (Wilderness deaths leave the items there instead —
	 * same destination either way), and after the respawn the route pins
	 * to it until the player gets close.
	 */
	@Subscribe
	public void onActorDeath(net.runelite.api.events.ActorDeath event)
	{
		Player me = client.getLocalPlayer();
		if (me == null || event.getActor() != me)
		{
			return;
		}
		deathPoint = me.getWorldLocation();
		log.info("player died at {} — routing to the gravestone until reached", deathPoint);
		client.addChatMessage(ChatMessageType.CONSOLE, "",
			"IRONSCAPE: you died — the route now points at your gravestone.", null);
		maybeNavigateToNext();
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
				if (isCoins(goal.getItemName()) || goal.isAcquisition())
				{
					// Money is meant to be SPENT after its step, and a
					// PURCHASE ("buy 1 inv of bronze bars") already
					// happened — using or banking the goods later must
					// not re-open the transaction behind you.
					continue;
				}
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

	// (The old "signs your card" chat hook is gone: each pub prints its own
	// flavor text — the Flying Horse Inn says "signing your barcrawl card"
	// and never matched. The card's varp bits (annotation checkpoints on
	// varp 77) are the authoritative signal now, and unlike chat they also
	// catch up on login after crawling bars with the plugin off.)

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
			// A fresh bank interface = a fresh banking session: the frozen
			// filter window re-anchors on wherever the frontier is now.
			frozenFilterStepIds = null;
		}
		// Travel menus (spirit trees, gliders): whatever interface just
		// loaded while a travel sub is current is probably the destination
		// list — the overlay scans it for matching entries, so the group
		// id never needs hardcoding. Logged for diagnosis when a menu
		// still doesn't highlight.
		if (!travelMenuWords.isEmpty())
		{
			travelMenuGroup = event.getGroupId();
			log.info("travel-menu probe: widget group {} loaded while travel sub current",
				event.getGroupId());
		}
	}

	/** Clicking a real bank tab or the search button turns our filter off. */
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		// Scene-transition tracking for minigame presence: a coordinate
		// jump right after clicking a GAME OBJECT is a ladder/cave/portal
		// (stay present), not a teleport item/spell (presence breaks).
		switch (event.getMenuAction())
		{
			case GAME_OBJECT_FIRST_OPTION:
			case GAME_OBJECT_SECOND_OPTION:
			case GAME_OBJECT_THIRD_OPTION:
			case GAME_OBJECT_FOURTH_OPTION:
			case GAME_OBJECT_FIFTH_OPTION:
				lastObjectClickTick = client.getTickCount();
				break;
			default:
				break;
		}
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
			else
			{
				// Filter off: next activation re-anchors on the live frontier.
				frozenFilterStepIds = null;
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
		// The account hash lags the login event; the moment it exists the
		// persisted bank snapshot loads and banked counts stop reading 0.
		if (client.getGameState() == GameState.LOGGED_IN
			&& itemTracker.ensureBankLoaded() && panel != null)
		{
			SwingUtilities.invokeLater(panel::refreshItemCounts);
		}
		refreshCheckpointBadgeCache();
		// Death retrieval: while a gravestone waits, keep the route pinned
		// on it (re-post every 10 ticks — the respawn's loading screen and
		// any teleport en route can drop a Shortest Path route). Getting
		// close clears it and normal frontier routing resumes.
		if (deathPoint != null)
		{
			Player me = client.getLocalPlayer();
			if (me != null && me.getWorldLocation().getPlane() == deathPoint.getPlane()
				&& me.getWorldLocation().distanceTo2D(deathPoint) <= 8)
			{
				deathPoint = null;
				client.addChatMessage(ChatMessageType.CONSOLE, "",
					"IRONSCAPE: gravestone reached — routing back to the guide.", null);
				maybeNavigateToNext();
			}
			else if (tickCounter % 10 == 0)
			{
				maybeNavigateToNext();
			}
		}
		if (loginGraceTicks > 0)
		{
			loginGraceTicks--;
			// Resume-and-navigate: a fresh session used to sit routeless
			// until the first progress event fired nav. Once the grace
			// window ends (quest states and item baselines are settled),
			// route to the next target exactly as if progress just happened.
			if (loginGraceTicks == 0 && navOnLoginPending)
			{
				navOnLoginPending = false;
				logNavDecision("login: resuming route to the next target");
				maybeNavigateToNext();
			}
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
			// A waiting gravestone mutes the teleport signal: the RESPAWN
			// is a 20+ tile jump ("you teleported to Lumbridge" — no), and
			// any teleport taken to reach the grave is off-route travel.
			if (lastTickPosition != null && loginGraceTicks == 0 && deathPoint == null
				&& (here.getPlane() != lastTickPosition.getPlane()
					|| lastTickPosition.distanceTo2D(here) > 20))
			{
				recentTeleportTicks = 8;
				// A click-requested minigame hint is done once ANY teleport
				// lands — the guided click path served its purpose.
				clickedMinigameTicks = 0;
				// A teleport landing while the minigame hint shows means the
				// grouping teleport was used: wherever we landed IS that
				// minigame — confirm the region so returning later is
				// recognised instantly.
				if (activeMinigameTarget != null)
				{
					String landedKey = activeMinigameTarget.toLowerCase(Locale.ROOT)
						.replace('’', '\'');
					minigameRegions.computeIfAbsent(landedKey, k -> new java.util.HashSet<>())
						.add(here.getRegionID());
					minigamePresence = landedKey;
				}
				// A jump right after clicking a GAME OBJECT while present is
				// the minigame's own ladder/cave/portal — walking "inside
				// the cave" jumps coordinates but must NOT break presence.
				// Teleport items/spells aren't object clicks and still do.
				else if (minigamePresence != null
					&& client.getTickCount() - lastObjectClickTick <= 3)
				{
					minigameRegions.computeIfAbsent(minigamePresence, k -> new java.util.HashSet<>())
						.add(here.getRegionID());
				}
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
			Quest questOwner = questOwningGuidance();
			boolean questOwns = questOwner != null;
			if (questOwns != lastQuestOwnsGuidance)
			{
				lastQuestOwnsGuidance = questOwns;
				maybeNavigateToNext();
				if (questOwns)
				{
					handedOffQuest = questOwner;
				}
				else
				{
					// Guidance came back to us. If it's because the quest we
					// handed off FINISHED (not a logout or a step edit), pull
					// our panel back in front — Quest Helper closes its quest
					// UI but stays selected in the sidebar otherwise.
					if (handedOffQuest != null
						&& cachedQuestState(handedOffQuest) == QuestState.FINISHED)
					{
						SwingUtilities.invokeLater(() -> clientToolbar.openPanel(navButton));
					}
					handedOffQuest = null;
				}
			}
		}

		// Feed the overlays. Computed here once per tick, not per frame.
		Current current = findCurrent();

		// Jumped ahead? The player STARTING a later step's quest right now
		// means they're off doing it — frontier guidance would just fight
		// Quest Helper (it routed him back to the Foundry mid Tourist
		// Trap). Only a LIVE NOT_STARTED -> IN_PROGRESS transition arms
		// this: the route deliberately parks a dozen-plus quests half-done
		// for hours (owner's journal: 17 yellow at once), so the old "any
		// later-step quest in progress" test was ON almost permanently —
		// every auto-navigation silently dead. Quests already in progress
		// when the session starts just baseline on first observation.
		// Every 5 ticks, not every tick: this loop runs one clientscript
		// per guide quest (~100), and per-tick it was enough script-engine
		// load to break Quest Helper's pathing. 3s transition latency is
		// harmless; the scan doubles as the cachedQuestState refresher.
		if (current != null && loginGraceTicks == 0
			&& (tickCounter % 5 == 0 || lastQuestState.isEmpty()))
		{
			int frontierIndex = current.step.getGlobalIndex();
			for (Map.Entry<Quest, Integer> entry : minStepIndexByQuest.entrySet())
			{
				QuestState state = entry.getKey().getState(client);
				QuestState previous = lastQuestState.put(entry.getKey(), state);
				if (previous == QuestState.NOT_STARTED && state == QuestState.IN_PROGRESS
					&& entry.getValue() > frontierIndex)
				{
					jumpedAheadQuest = entry.getKey();
					log.info("jumped-ahead ON ({} started live, first guide step ahead of frontier)",
						entry.getKey().getName());
				}
			}
			// Disarm when the jaunt ends: the quest wrapped up, or the
			// frontier caught up to its step. (Route progress also disarms
			// — see completeSubGoal — ticking steps IS following the route.)
			if (jumpedAheadQuest != null
				&& (cachedQuestState(jumpedAheadQuest) != QuestState.IN_PROGRESS
					|| minStepIndexByQuest.getOrDefault(jumpedAheadQuest, 0) <= frontierIndex))
			{
				log.info("jumped-ahead OFF ({} finished or frontier caught up)",
					jumpedAheadQuest.getName());
				jumpedAheadQuest = null;
			}
		}
		playerJumpedAhead = jumpedAheadQuest != null;

		// Teleport hints: a clicked minigame link wins while its countdown
		// runs; otherwise the current "Minigame teleport to X" sub.
		if (clickedMinigameTicks > 0)
		{
			clickedMinigameTicks--;
		}
		// Recomputed fresh each tick by the chain below; only the
		// route-aware branch sets it, so stale values must not linger when
		// an earlier branch wins.
		activeSpellTeleport = -1;
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
			// No explicit "minigame teleport to X" sub, but the frontier
			// step's own 📍 area IS a Grouping destination and the player
			// is far from it: the minigame teleport is how you get there,
			// so show the click path unprompted (Giants' Foundry steps
			// gave no hint until the chip was clicked). Arriving — or
			// getting anywhere near — drops the hint.
			// NOT gated on quest-in-progress: starting the quest and then
			// teleporting away to gather items is normal — presence alone
			// decides whether the way back needs showing. But jumping AHEAD
			// to a later step's quest means leave the player alone.
			if (activeMinigameTarget == null && current != null && !playerJumpedAhead)
			{
				String location = current.step.getMetadata().get("location");
				String key = location == null ? null
					: location.toLowerCase(Locale.ROOT).replace('’', '\'');
				// Route-aware hint: the step names no minigame and its 📍
				// isn't one, but the best first leg toward the nav target
				// (or a waiting gravestone) IS a Grouping teleport —
				// Shortest Path routes through it, so show the click path
				// to the teleport it expects you to take. Presence check:
				// once you've taken it, interiors (Mor Ul Rek) keep 2D
				// distances huge — "already there" must kill the hint, not
				// the distance math.
				if (key == null || !GROUPING_MINIGAMES.contains(key))
				{
					// An active errand stage IS the journey — Shortest Path
					// routes to it (the Grand Tree), so the first-leg hint
					// must aim there too, not at the sub's own ⌖ ten tiles
					// away (the hint sat dark while SP suggested a teleport).
					StepAnnotation.Errand hintErrand = activeErrand();
					// Chain complete = nav is HOLDING at the destination —
					// no first-leg hints either (from the basement, the
					// surface trapdoor reads as 6,400 tiles away and the
					// hint offered a Lumbridge teleport one ladder below it).
					boolean chainHolding = hintErrand == null
						&& !errandChain(current.step, current.sub).isEmpty();
					WorldPoint routeTarget = chainHolding ? null
						: deathPoint != null ? deathPoint
						: hintErrand != null
							? errandRoutePoint(hintErrand)
							: targetFor(current.step, current.sub);
					// A sub that names its own transport gets no alternative
					// first-leg suggestions — the guide already said how to
					// travel. Death routes and errand legs prescribe nothing.
					boolean prescribed = deathPoint == null && hintErrand == null
						&& PRESCRIBED_TRANSPORT.matcher(
							current.sub.getPlainText().toLowerCase(Locale.ROOT)).find();
					FirstLeg leg = prescribed ? null
						: firstLegTowards(routeTarget, !minigameTeleportOnCooldown());
					if (leg != null && leg.minigame != null)
					{
						String towardsKey = leg.minigame.toLowerCase(Locale.ROOT).replace('’', '\'');
						java.util.Set<Integer> confirmed = minigameRegions.get(towardsKey);
						Player me = client.getLocalPlayer();
						boolean there = towardsKey.equals(minigamePresence)
							|| (me != null && confirmed != null
								&& confirmed.contains(me.getWorldLocation().getRegionID()));
						if (!there)
						{
							activeMinigameTarget = leg.minigame;
						}
					}
					activeSpellTeleport = activeMinigameTarget == null
						&& leg != null && leg.spell != null ? leg.spell.component : -1;
				}
				else
				{
					activeSpellTeleport = -1;
				}
				if (key != null && GROUPING_MINIGAMES.contains(key))
				{
					WorldPoint area = placeManager.getLoose(location);
					Player me = client.getLocalPlayer();
					if (area != null && me != null)
					{
						int region = me.getWorldLocation().getRegionID();
						// Restore saved presence: logged in still standing
						// in the region we were AT when the client closed.
						if (pendingPresenceRestore != null)
						{
							String[] saved = pendingPresenceRestore.split("\\|");
							pendingPresenceRestore = null;
							if (saved.length == 2 && saved[0].equals(key)
								&& saved[1].equals(Integer.toString(region)))
							{
								minigamePresence = key;
								minigameRegions.computeIfAbsent(key, k -> new java.util.HashSet<>())
									.add(region);
							}
						}
						boolean near = me.getWorldLocation().getPlane() == area.getPlane()
							&& me.getWorldLocation().distanceTo2D(area) <= 100;
						java.util.Set<Integer> confirmed = minigameRegions.get(key);
						// AT the minigame = near its pin, in a region
						// confirmed to be it, or contiguous walking since
						// last tick's presence (entrance -> interior). A
						// teleport breaks the walking chain, so leaving
						// brings the hint straight back.
						boolean present = near
							|| (confirmed != null && confirmed.contains(region))
							|| (key.equals(minigamePresence) && recentTeleportTicks == 0);
						if (near)
						{
							minigameRegions.computeIfAbsent(key, k -> new java.util.HashSet<>())
								.add(region);
						}
						if (present)
						{
							minigamePresence = key;
							String state = key + "|" + region;
							if (!state.equals(lastSavedPresenceState))
							{
								configManager.setConfiguration(CONFIG_GROUP,
									"minigamePresence", state);
								lastSavedPresenceState = state;
							}
						}
						else
						{
							if (key.equals(minigamePresence))
							{
								minigamePresence = null;
							}
							if (lastSavedPresenceState != null)
							{
								configManager.unsetConfiguration(CONFIG_GROUP, "minigamePresence");
								lastSavedPresenceState = null;
							}
							activeMinigameTarget = location;
						}
					}
				}
			}
		}

		// "Home tele to lumby": highlight the spellbook click path (spell if
		// the book is open, else the Magic tab) while that sub is current.
		homeTeleportHint = config.showTeleportHints() && current != null
			&& activeMinigameTarget == null
			&& HOME_TELEPORT.matcher(current.sub.getPlainText()).find();

		// Travel menus (spirit trees, gliders — interface 187): the word
		// set the overlay matches list entries against. Sub text + the
		// step's 📍 tag, so "Khazard Battlefield" still lights up the
		// menu's "Battlefield of Khazard" whatever the word order.
		java.util.Set<String> menuWords = java.util.Collections.emptySet();
		if (config.showTeleportHints() && current != null)
		{
			StringBuilder hay = new StringBuilder(current.sub.getPlainText());
			String locationTag = current.step.getMetadata().get("location");
			if (locationTag != null)
			{
				hay.append(' ').append(locationTag);
			}
			menuWords = new java.util.HashSet<>();
			for (String token : hay.toString().toLowerCase(Locale.ROOT)
				.replace('’', '\'').split("[^a-z0-9']+"))
			{
				if (!token.isEmpty())
				{
					menuWords.add(token);
				}
			}
		}
		travelMenuWords = menuWords;

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
					&& cachedQuestState(questGoal.getQuest()) == QuestState.NOT_STARTED)
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

		// On-the-way errand: an annotated side pickup ("get Glarial's
		// pebble during Tree Gnome Village") keeps OUR guidance alive from
		// quest start until the item is in the bag — mid-quest QH knows
		// nothing about it, and post-quest it's the step's only signal.
		StepAnnotation.Errand errand = activeErrand();
		String errandStage = errand == null ? null
			: (errand.item != null ? errand.item : "wp:" + errand.x + "," + errand.y);
		if (!java.util.Objects.equals(errandStage, lastErrandStage))
		{
			// Activation, stage advance (key -> pebble) and final pickup
			// all reroute: Shortest Path targets the live stage, and must
			// move on the moment it's satisfied.
			lastErrandStage = errandStage;
			maybeNavigateToNext();
		}
		else if (errand != null && tickCounter % 10 == 0)
		{
			// Keep the stage route ALIVE while the errand runs — ladder
			// hops and other transitions can drop the Shortest Path route,
			// and QH-style guidance means it always points at the current
			// stage. Idempotent when the route is already right.
			maybeNavigateToNext();
		}
		WorldPoint errandPoint = errand == null ? null : errandRoutePoint(errand);
		if (spot == null && errandPoint != null && config.showTargetMarker())
		{
			spot = errandPoint;
		}
		targetTileMarker = spot;
		// Name the tile when the step says what it IS ("safespot the
		// zamorak warrior") — or when the player captured it AS a safespot
		// ("Capture as safespot" on the ⌖ button of any kill step).
		targetTileLabel = spot != null && current != null
			&& (SAFESPOT.matcher(current.sub.getPlainText()).find()
				|| annotationManager.isSafespotTarget(current.sub.getId())
				|| annotationManager.isSafespotTarget(current.step.getId()))
			? "Safespot" : null;
		// A waiting gravestone takes the marker over: that's where you're
		// going, whatever the current step wants.
		if (deathPoint != null)
		{
			targetTileMarker = deathPoint;
			targetTileLabel = "Gravestone";
		}

		// One-time nudge when the route brings you NEAR the errand spot —
		// the whole point is not walking past Golrie's tunnel. Waypoint
		// stages skip it: reaching one IS the event, nothing to say.
		if (errandPoint != null && player != null && errand.item != null)
		{
			WorldPoint here = player.getWorldLocation();
			if (here.getPlane() == errandPoint.getPlane()
				&& here.distanceTo2D(errandPoint) <= 30
				&& errandReminded.add(current.step.getId() + "|" + errand.item))
			{
				client.addChatMessage(ChatMessageType.CONSOLE, "",
					"IRONSCAPE: on-the-way pickup — "
						+ (errand.note != null ? errand.note : "get " + errand.item + " here")
						+ ".", null);
			}
		}

		// Shop-keeper anchor: for a sub that still needs items ("From
		// sawmill buy 500 bronze nails"), the sub's resolved nav target
		// (⌖ capture, place name, or the step's 📍 tag) marks the shop —
		// the nearest NPC to it gets the outline and the wanted item
		// floats over their head, Quest Helper-style. ⌖ captures keep
		// priority; the ≤4-tile rule below keeps town-center points from
		// outlining random passers-by. An active errand's spot anchors the
		// same way (Golrie gets the outline, the pebble floats overhead).
		// With an errand active, ONLY an ITEM stage nominates — Golrie
		// wears the pebble because the NPC is the point of the stage. A
		// WAYPOINT stage is a travel leg: whoever happens to stand at it
		// (the Cook by the kitchen trapdoor) must not wear the goal item.
		// The sub's own ⌖ never anchors while a chain is driving — it's a
		// PLACE then (the RFD dining-hall doors), not a vendor.
		WorldPoint shopAnchor = errand != null
			? (errand.item != null ? errandPoint : null)
			: spot;
		if (shopAnchor == null && current != null && hasPurchaseGoal(current.sub))
		{
			// Text places only — the step's 📍 town tag is far too coarse
			// to nominate a shopkeeper (it outlined random passers-by at
			// the town center). And only for PURCHASE subs: "do Wintertodt
			// until 200k cash" has an item goal too, and its camp pin was
			// nominating pyromancers (who then wore coin stacks). The
			// NOMINATING variant skips object-vendor sources — "buckets of
			// milk" in the text matched the trapdoor pin and the Cook wore
			// the milk icon.
			shopAnchor = placeManager.firstNominatingPlaceIn(current.sub.getPlainText());
		}
		if (shopAnchor == null && current != null)
		{
			// GATHER subs anchor at their item's authored SOURCE ("get 100
			// compost" -> Vannah's stall in item_sources): a curated
			// item-source point is as precise as a shop pin — the coarse
			// pyromancer problem above can't happen, item names only ever
			// resolve to sources someone seeded. First unmet goal wins.
			List<GoalDetector.ItemGoal> gatherGoals = itemGoalsBySub.get(current.sub.getId());
			if (gatherGoals != null)
			{
				for (GoalDetector.ItemGoal goal : gatherGoals)
				{
					if (itemTracker.countOf(goal.getItemName()) >= goal.getQuantity())
					{
						continue;
					}
					WorldPoint source = placeManager.get(goal.getItemName());
					// Object-vendor sources (a chest, a dig spot) never
					// nominate: the nearest NPC is a bystander.
					if (source != null && placeManager.sourceNominatesNpc(goal.getItemName()))
					{
						shopAnchor = source;
						break;
					}
				}
			}
		}

		// NPC targets: outline scene NPCs whose name the current sub-step
		// mentions ("speak with Veos" -> Veos). Names matched once per
		// tick; the overlay re-reads the live hulls per frame, which is
		// what keeps the outline glued to wandering NPCs.
		java.util.Set<String> npcNames = new java.util.HashSet<>();
		// Mid-quest, Quest Helper's show: our NPC outlines stand down (the
		// giver's outline served its purpose getting the quest STARTED) and
		// resume when the quest finishes and the step ticks — otherwise we
		// keep pointing at Kovac while QH points at the actual objective.
		// EXCEPT an active errand: its anchor still nominates the nearest
		// NPC. The name scan stays off only MID-QUEST (names are QH's job
		// there) — a quest-less errand step keeps its name outlines: the
		// Zamorak warriors light up while the chain routes you to them.
		boolean errandOnly = errand != null && questHelperOwnsGuidance();
		if (current != null && (!questHelperOwnsGuidance() || errand != null))
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
			boolean combatSub = COMBAT_VERB.matcher(subText).find();
			// Place-name spans in the same text: an NPC name inside a LONGER
			// place name is the place talking, not the NPC — "Walk to
			// Barbarian Village" must not outline every Barbarian (an
			// equal-length span is the NPC itself, e.g. "Romeo", and stays).
			List<int[]> placeSpans = placeManager.placeSpans(subText);
			// Nearest ONE to each anchor point — "everyone within 4 tiles"
			// outlined the whole gnome crowd around Gulluck's shop. Tracked
			// by NPC INDEX, not name: nominating "Barbarian" by name put
			// the wanted item over every barbarian in the longhouse.
			int nearestToMarker = -1;
			int nearestToSpot = -1;
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
				// The guide speaks in plurals ("fire strike imps", "kill
				// cows"); the NPC is named in the singular ("Imp"). Match
				// the name and its plural forms, word-bounded either side.
				if (!errandOnly)
				{
					for (String variant : pluralVariants(clean))
					{
						int at = subText.indexOf(variant);
						if (at > 0
							&& !Character.isLetter(subText.charAt(at - 1))
							&& !Character.isLetter(subText.charAt(at + variant.length()))
							&& !insideLongerSpan(placeSpans, at, at + variant.length()))
						{
							npcNames.add(clean);
							break;
						}
					}
					// COMBAT subs name their target by SPECIES ("kill a
					// rat", "safespot a bear") while the scene NPC is
					// "Giant rat" or "Black bear": the name's last word
					// counts too. Kill subs only — "guard" in ordinary
					// prose must not light up every H.A.M. Guard. The
					// specific-shadows-generic pass below then prefers
					// "Giant rat" over a plain "Rat" when both match.
					int lastSpace = clean.lastIndexOf(' ');
					if (combatSub && lastSpace > 0 && !npcNames.contains(clean))
					{
						String species = clean.substring(lastSpace + 1);
						if (species.length() >= 3)
						{
							for (String variant : pluralVariants(species))
							{
								int at = subText.indexOf(variant);
								if (at > 0
									&& !Character.isLetter(subText.charAt(at - 1))
									&& !Character.isLetter(subText.charAt(at + variant.length()))
									&& !insideLongerSpan(placeSpans, at, at + variant.length()))
								{
									npcNames.add(clean);
									break;
								}
							}
						}
					}
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
						nearestToMarker = npc.getIndex();
					}
				}
				if (shopAnchor != null
					&& npc.getWorldLocation().getPlane() == shopAnchor.getPlane())
				{
					int distance = npc.getWorldLocation().distanceTo2D(shopAnchor);
					if (distance <= 4 && distance < spotBest)
					{
						spotBest = distance;
						nearestToSpot = npc.getIndex();
					}
				}
			}
			// The step's ACTUAL keeper (wiki-seeded shop owner, or the
			// bartender a barcrawl step means) beats whoever stands nearest
			// the pin — the Master Farmer wore the compost icon while
			// Richard ran the farming shop four tiles away. No purchase-goal
			// gate: an entry in shop_npcs.json is curated intent ("this
			// step is about talking to THIS npc"), and barcrawl drink subs
			// carry a varp checkpoint instead of a purchase goal.
			if (!errandOnly)
			{
				String keeper = placeManager.shopKeeper(current.step.getId());
				if (keeper != null)
				{
					npcNames.add(keeper.toLowerCase(Locale.ROOT));
				}
			}
			// The quest's ACTUAL giver (wiki-seeded from the quest infobox)
			// beats guessing whoever stands nearest the start pin — a
			// decorative giant at the Foundry wore Kovac's quest icon.
			for (SubStep questSub : errandOnly
				? java.util.Collections.<SubStep>emptyList() : current.step.getSubSteps())
			{
				GoalDetector.QuestGoal questGoal = questGoalBySub.get(questSub.getId());
				if (questGoal == null)
				{
					continue;
				}
				String giver = placeManager.questGiver(questGoal.getQuest().getName());
				if (giver != null)
				{
					npcNames.add(giver.toLowerCase(Locale.ROOT));
				}
				break;
			}
			// A specific name shadows a generic one it contains: "milk the
			// dairy cow" matches the NPCs "Dairy cow" AND "Cow", but the
			// guide means the specific one — without this every regular cow
			// in the field wears the bucket icon. Word-boundary containment
			// so "Woman" never shadows "Man".
			java.util.Set<String> shadowed = new java.util.HashSet<>();
			for (String a : npcNames)
			{
				for (String b : npcNames)
				{
					if (!a.equals(b) && containsWord(b, a))
					{
						shadowed.add(a);
					}
				}
			}
			npcNames.removeAll(shadowed);
			// A name the step TEXT matched wins outright: "buy 2 teleport
			// cards from Diango" must outline only Diango — the nearest-NPC
			// fallback exists for steps that DON'T name their NPC, and with
			// NPCs wandering it happily picked a villager standing closer
			// to the anchor than the actual seller. STICKY per sub: once the
			// text has matched a scene NPC, the fallback stays off even
			// while they're all briefly dead — the safespot pin must not
			// crown a passing Zamorak crafter with the scimitar.
			if (!npcNames.isEmpty())
			{
				namedNpcSubs.add(current.sub.getId());
			}
			// A sub whose subject is a scene OBJECT ("train thieving at the
			// fruit stall") never wants the nearest-NPC fallback — it crowned
			// a Woman browsing the stall house with the outline.
			if (npcNames.isEmpty() && !namedNpcSubs.contains(current.sub.getId())
				&& objectGrindNames(subText).isEmpty())
			{
				java.util.Set<Integer> indexes = new java.util.HashSet<>();
				if (nearestToMarker != -1)
				{
					indexes.add(nearestToMarker);
				}
				if (nearestToSpot != -1)
				{
					indexes.add(nearestToSpot);
				}
				npcTargetIndexes = indexes;
			}
			else
			{
				npcTargetIndexes = java.util.Collections.emptySet();
			}
		}
		else
		{
			npcTargetIndexes = java.util.Collections.emptySet();
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
					if (isCoins(goal.getItemName()))
					{
						continue; // "until 200k cash" put coin stacks on pyromancers
					}
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
		// An active errand's item wins the overhead slot — the outlined NPC
		// IS the errand (the pebble over Golrie, not some other step item).
		if (errand != null && errand.item != null)
		{
			int errandIcon = itemTracker.iconIdFor(errand.item);
			if (errandIcon > 0)
			{
				wantedIcon = errandIcon;
			}
		}
		currentSubItemIcon = wantedIcon;

		// Ground items the current sub wants picked up ("Pick up 2 iron
		// bars...", item spawns): highlight their tiles, QH-style.
		groundItemTargets = config.showGroundItemMarkers() && current != null
			? findWantedGroundItems(current)
			: java.util.Collections.emptyList();
		// Mining subs: outline the live rocks for every still-unmet ore
		// goal ("Mine 4 copper ore and 1 iron ore" lights up both).
		objectTargets = current != null
			? findWantedRocks(current)
			: java.util.Collections.emptyList();
		objectActionsLabel = current != null ? actionsRemainingLabel(current) : null;

		// Outline the carried items the current step is ABOUT — its tab
		// ("Use house tab..."), tools, ingredients and goal items — so
		// what to use next is obvious at a glance.
		inventoryHintItemIds = config.showInventoryHints() && current != null
			? findStepInventoryItems(current)
			: java.util.Collections.emptySet();
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
							ItemTracker.capitalize(goal.getItemName()),
							ItemTracker.formatCount(have) + "/" + ItemTracker.formatCount(goal.getQuantity()),
							color));
					}
				}
				List<GoalDetector.SkillLevelGoal> levels = levelGoalsBySub.get(sub.getId());
				if (levels != null)
				{
					for (GoalDetector.SkillLevelGoal goal : levels)
					{
						int have = realLevelBySkill.getOrDefault(goal.getSkill(), 1);
						reqs.add(new com.ironscape.overlay.StepOverlay.Requirement(
							goal.getSkill().getName(),
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
						counted.getSkill().getName() + " actions",
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
		// "warm clothing 3/4" — the step's gear check, live on screen.
		StepAnnotation.GearCheck gear = annotationManager.getGearCheck(step.getId());
		if (gear != null)
		{
			int have = itemTracker.distinctCarried(gear.set);
			reqs.add(new com.ironscape.overlay.StepOverlay.Requirement(
				gear.set, have + "/" + gear.need,
				have >= gear.need ? OVERLAY_GREEN : OVERLAY_RED));
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
		// ASCII dots, not "…": the in-game font has no ellipsis glyph and
		// renders it as a broken rune (owner spotted it on bank headers).
		return text.length() <= maxLength ? text : text.substring(0, maxLength - 3) + "...";
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
	/**
	 * Step ids the OPEN filter session shows, fixed at activation. The
	 * window used to re-anchor on the live frontier every rebuild —
	 * withdrawing the last item of a step auto-ticked it, the window
	 * slid, and the whole layout jumped mid-banking (owner report, twice).
	 * Frozen composition, live counts; null = compute fresh next build.
	 */
	private List<String> frozenFilterStepIds;

	private void refreshUpcomingNeeds()
	{
		if (bankFilterCacheTick == tickCounter)
		{
			return;
		}
		Guide guide = guideFor(activeVariant);
		if (frozenFilterStepIds == null)
		{
			List<String> ids = new ArrayList<>();
			Current frontier = findCurrent();
			if (frontier != null)
			{
				List<GuideStep> steps = guide.getAllSteps();
				for (int i = frontier.step.getGlobalIndex();
					i < steps.size() && ids.size() < BANK_FILTER_STEPS; i++)
				{
					if (!progressManager.isCompleted(activeVariant, steps.get(i).getId()))
					{
						ids.add(steps.get(i).getId());
					}
				}
			}
			frozenFilterStepIds = ids;
		}
		Map<String, GuideStep> stepById = new HashMap<>();
		for (GuideStep step : guide.getAllSteps())
		{
			stepById.put(step.getId(), step);
		}
		List<com.ironscape.items.BankMissingSection.Section> sections = new ArrayList<>();
		for (String stepId : frozenFilterStepIds)
		{
			GuideStep step = stepById.get(stepId);
			if (step == null)
			{
				continue;
			}
			// NO completed-step skip here: a step finishing mid-banking keeps
			// its section — counts go green, nothing moves.
			com.ironscape.items.BankMissingSection.Section section =
				new com.ironscape.items.BankMissingSection.Section(
					truncate(step.getPlainText().trim(), 200));
			for (StepAnnotation.ItemNeed need : annotationManager.getItems(step.getId()))
			{
				String name = need.name.toLowerCase(Locale.ROOT);
				int quantity = need.quantity == null ? 1 : need.quantity;
				section.items.merge(name, quantity, Math::max);
			}
			for (SubStep sub : step.getSubSteps())
			{
				// A DONE sub keeps its items listed while you still meet
				// the count — withdrawing the runes auto-ticks the sub,
				// and the runes vanishing from the bank view mid-banking
				// reads as a bug. Items you no longer have (consumed long
				// ago) stay hidden; a done sub must not demand them back.
				boolean subDone = progressManager.isSubCompleted(activeVariant, step, sub);
				List<GoalDetector.ItemGoal> itemGoals = itemGoalsBySub.get(sub.getId());
				if (itemGoals != null)
				{
					for (GoalDetector.ItemGoal goal : itemGoals)
					{
						if (subDone && !stillMet(goal.getItemName(), goal.getQuantity()))
						{
							continue;
						}
						section.items.merge(goal.getItemName(), goal.getQuantity(), Math::max);
					}
				}
				for (StepAnnotation.ItemNeed need : annotationManager.getItems(sub.getId()))
				{
					String name = need.name.toLowerCase(Locale.ROOT);
					int quantity = need.quantity == null ? 1 : need.quantity;
					if (subDone && !stillMet(name, quantity))
					{
						continue;
					}
					section.items.merge(name, quantity, Math::max);
				}
			}
			sections.add(section);
		}
		upcomingSections = sections;
		bankFilterCacheTick = tickCounter;
	}

	/** Same have/need arithmetic the bank section renders with. */
	private boolean stillMet(String itemName, int need)
	{
		int have = itemTracker.bankCountable(itemName, need)
			? itemTracker.countOf(itemName)
			: itemTracker.carriedCountOf(itemName);
		return have >= need;
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
				// A sub with a varbit/varp checkpoint completes ONLY here:
				// the annotation exists precisely because the heuristics
				// fire early ("get a drink for the barcrawl" ticked on
				// walking into the bar, stamp or no stamp).
				List<StepRequirement> subReqs = subRequirements.get(current.sub.getId());
				if (subReqs != null && hasVarCheckpoint(subReqs))
				{
					if (requirementsMet(subReqs))
					{
						completeSubGoal(current.step, current.sub, "quest checkpoint (varbit/varp)");
						completedSomething = true;
						break;
					}
					continue; // checkpoint not reached — heuristics don't get a vote
				}
				if (subReqs != null && requirementsMet(subReqs))
				{
					completeSubGoal(current.step, current.sub, "sub requirement met");
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
		// An unsatisfied errand chain BLOCKS quest-state ticking: "Do Tree
		// gnome village, get Glarial's pebble on the way" must not tick on
		// the quest jingle while the pebble is still in Golrie's tunnel.
		boolean errandPending = unsatisfiedErrandStage(step, sub) != null;

		// A FINISHED quest subsumes EVERY sub of a step that carries its
		// goal: "Continue Gertrude's cat, talk to the kids" cannot have
		// unfinished errands once the quest itself is done — the kids sub
		// has no signal of its own and stayed unticked (owner hit this).
		if (!errandPending)
		{
			for (SubStep other : step.getSubSteps())
			{
				GoalDetector.QuestGoal stepQuest = questGoalBySub.get(other.getId());
				if (stepQuest != null
					&& cachedQuestState(stepQuest.getQuest()) == QuestState.FINISHED)
				{
					return true;
				}
			}
		}

		// Quest state FIRST: atomic guide steps combine errands with the
		// quest action ("Buy a spade, start X marks the spot quest") — the
		// quest's own state is the authoritative "done" signal, and it's
		// strong evidence (monotonic), unlike carried-item counts.
		GoalDetector.QuestGoal atomicQuestGoal = questGoalBySub.get(sub.getId());
		if (atomicQuestGoal != null && itemGoalsBySub.containsKey(sub.getId()))
		{
			QuestState state = cachedQuestState(atomicQuestGoal.getQuest());
			boolean questDone = atomicQuestGoal.isRequiresFinished()
				? state == QuestState.FINISHED
				: state != QuestState.NOT_STARTED;
			return questDone && !errandPending;
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
				// Small quantities only: nobody carries 4000 mind runes by
				// accident, and the session-only baseline left bulk buys
				// stuck green-but-unticked after a client restart.
				if (goal.isAcquisition() && goal.getQuantity() < 3)
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
			QuestState state = cachedQuestState(questGoal.getQuest());
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
		// Gated on the step's annotation items being IN HAND: "Home tele,
		// Lumby" with a cakes/runes shopping list means "teleport WITH the
		// items" — the jump alone must not tick it (owner hit this).
		// No early false: a travel sub can ALSO complete by arriving at its
		// destination below ("Home tele to lumby and run north to Varrock
		// east bank" — walking the second half needs arrival detection).
		if (travelGoalSubs.contains(sub.getId())
			&& inFrontierStep && recentTeleportTicks > 0
			&& annotationItemsCarried(step, sub))
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
		if (!annotationItemsCarried(step, sub))
		{
			return false;
		}

		WorldPoint here = player.getWorldLocation();
		// A ⌖ capture is a precise spot; a place name is a whole town —
		// entering from any gate should count. EXCEPT network travel
		// ("Charter to port sarim", "use the spirit tree"): a capture
		// there marks the BOARDING point (owner captured the Khazard dock
		// and the step ticked on arriving at the dock, destination unseen)
		// — the text's destination is what arrival must prove.
		boolean networkTravel = CHARTER.matcher(sub.getPlainText()).find()
			|| SPIRIT_TREE.matcher(sub.getPlainText()).find();
		// Arrival — precise ⌖ or place radius — only ever PROVES subs that
		// are themselves movement instructions ("Walk north to Fred's
		// farm"). An action that merely happens somewhere ("Kill a chicken
		// ... at Fred's farm", "Pickpocket HAM members" with a hideout ⌖)
		// must not tick just because the player showed up — the owner hit
		// this twice; the second time the ⌖ branch sat ABOVE this gate.
		if (!travelGoalSubs.contains(sub.getId())
			&& !MOVEMENT_WORD.matcher(sub.getPlainText()).find())
		{
			return false;
		}
		StepAnnotation.Target precise = annotationManager.getTarget(sub.getId());
		if (precise == null)
		{
			precise = annotationManager.getTarget(step.getId());
		}
		if (precise != null && !networkTravel)
		{
			return here.getPlane() == precise.plane
				&& here.distanceTo(new WorldPoint(precise.x, precise.y, precise.plane)) <= ARRIVE_RADIUS;
		}
		// Travel subs end at their LAST place mention (the destination);
		// everything else anchors on the first ("Talk to Reldo" -> Reldo).
		WorldPoint place = travelGoalSubs.contains(sub.getId())
			? placeManager.lastPlaceIn(sub.getPlainText())
			: placeManager.firstPlaceIn(sub.getPlainText());
		if (place == null)
		{
			// The guide's phrasing flips word order against the place list
			// ("go to battlefield of khazard" vs the place "Khazard
			// Battlefield") — no text match, and teleport arrivals never
			// ticked. The step's authored 📍 tag IS the destination; use
			// it whenever the text comes up empty.
			String location = step.getMetadata().get("location");
			place = location == null ? null : placeManager.getLoose(location);
		}
		if (place == null)
		{
			return false;
		}
		boolean within = here.getPlane() == place.getPlane()
			&& here.distanceTo(place) <= (recentTeleportTicks > 0
				? TELEPORT_ARRIVE_RADIUS : PLACE_ARRIVE_RADIUS);
		// ARMING: already standing at the destination when the sub became
		// current proves nothing — "run to Thurgo for the sword, run back
		// to Falador" ticked at the START point. Arrival only counts after
		// the player has been seen OUTSIDE the radius while it's current.
		if (!within)
		{
			arrivalArmed.add(sub.getId());
			return false;
		}
		return arrivalArmed.contains(sub.getId());
	}

	/**
	 * Are the step's ANNOTATION items (its authored shopping list) in the
	 * player's hands? Gates travel/arrival ticks: "teleport with X, Y"
	 * means WITH them. Names the tracker can't resolve to a real item
	 * ("all of your mind and air runes") are skipped — an uncountable
	 * name must degrade to not-gating, never to never-completing.
	 * Client thread (item id resolution).
	 */
	private boolean annotationItemsCarried(GuideStep step, SubStep sub)
	{
		String annotationId = step.getSubSteps().size() == 1 ? step.getId() : sub.getId();
		for (StepAnnotation.ItemNeed need : annotationManager.getItems(annotationId))
		{
			if (itemTracker.iconIdFor(need.name) <= 0)
			{
				continue; // unresolvable name: can't count it, don't block on it
			}
			String lower = need.name.toLowerCase(Locale.ROOT);
			if (lower.equals("coins") || lower.equals("gp"))
			{
				// GP-cost badges are informational: paying the fare mid-step
				// drops the count below "need", and arrival at the paid-for
				// destination must not hang on money already spent.
				continue;
			}
			int required = need.quantity == null ? 1 : need.quantity;
			int count = itemTracker.bankCountable(need.name, required)
				? itemTracker.countOf(need.name)
				: itemTracker.carriedCountOf(need.name);
			if (count < required)
			{
				return false;
			}
		}
		return true;
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
		// Route progress = following the route again: the jumped-ahead
		// stand-down (if armed) has served its purpose.
		jumpedAheadQuest = null;
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
					if (requires.value != null || requires.bit != null)
					{
						// value = threshold test; bit = bitfield test (varp 77
						// packs one bit per barcrawl bar). Threshold 1 is a
						// placeholder when only a bit is given.
						parsed.add(new StepRequirement(null, requires.varbit, requires.varp,
							requires.value == null ? 1 : requires.value,
							requires.bit, requires.icon, requires.label));
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

	/**
	 * Re-read every checkpoint sub's varbit/varp into the Swing-readable
	 * badge cache (~a dozen var reads, once per tick). On a flip — a
	 * bartender just signed the card — the panel badges re-render.
	 */
	private void refreshCheckpointBadgeCache()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		boolean changed = false;
		for (Map.Entry<String, List<StepRequirement>> entry : subRequirements.entrySet())
		{
			if (!hasVarCheckpoint(entry.getValue()))
			{
				continue;
			}
			Boolean met = requirementsMet(entry.getValue());
			if (!met.equals(checkpointMetBySub.put(entry.getKey(), met)))
			{
				changed = true;
			}
		}
		if (changed && panel != null)
		{
			SwingUtilities.invokeLater(panel::refreshItemCounts);
		}
	}

	/** Does the list carry a varbit/varp checkpoint (vs only skill levels)? */
	private static boolean hasVarCheckpoint(List<StepRequirement> requirements)
	{
		for (StepRequirement requirement : requirements)
		{
			if (requirement.varbit != null || requirement.varp != null)
			{
				return true;
			}
		}
		return false;
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
			if (requirement.bit != null)
			{
				// Bitfield var: only this bit matters — a >= test would let
				// OTHER bars' stamps (higher bits) fake this one.
				if (((have >> requirement.bit) & 1) == 0)
				{
					return false;
				}
			}
			else if (have < requirement.threshold)
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
		/** Bitfield test: met when this bit of the var is set (barcrawl card). */
		final Integer bit;
		/** Optional badge: item name for the sprite + short label ("stamp"). */
		final String icon;
		final String label;

		StepRequirement(Skill skill, int level)
		{
			this(skill, null, null, level, null, null, null);
		}

		StepRequirement(Skill skill, Integer varbit, Integer varp, int threshold)
		{
			this(skill, varbit, varp, threshold, null, null, null);
		}

		StepRequirement(Skill skill, Integer varbit, Integer varp, int threshold,
			Integer bit, String icon, String label)
		{
			this.skill = skill;
			this.varbit = varbit;
			this.varp = varp;
			this.threshold = threshold;
			this.bit = bit;
			this.icon = icon;
			this.label = label;
		}
	}

	/**
	 * The capture button was clicked (on the Swing thread). Hop to the
	 * client thread to read the player's position, save it, then hop back
	 * to Swing to update the button.
	 */
	/**
	 * Scene scan for the rocks the current sub still needs ore from.
	 * Impostor-resolved names, so a mined-out rock (plain "Rocks") drops
	 * out of the highlight until it respawns. Runs once per game tick and
	 * only while an ore goal is current. Client thread.
	 */
	/**
	 * "1,234 to go" over an outlined grind object: the sub's level goal plus
	 * the object's wiki xp-per-action say exactly how many successful
	 * actions remain to the target level. Null when the sub has no level
	 * goal, no outlined object with known xp, or the goal is already met.
	 * Client thread (reads live xp).
	 */
	private String actionsRemainingLabel(Current current)
	{
		List<GoalDetector.SkillLevelGoal> levels = levelGoalsBySub.get(current.sub.getId());
		if (levels == null || objectTargets.isEmpty())
		{
			return null;
		}
		Double xpPer = null;
		for (net.runelite.api.GameObject object : objectTargets)
		{
			String name = liveObjectName(object.getId());
			if (name != null && XP_PER_ACTION.containsKey(name))
			{
				xpPer = XP_PER_ACTION.get(name);
				break;
			}
		}
		if (xpPer == null)
		{
			return null;
		}
		for (GoalDetector.SkillLevelGoal goal : levels)
		{
			long xpLeft = net.runelite.api.Experience.getXpForLevel(goal.getLevel())
				- client.getSkillExperience(goal.getSkill());
			if (xpLeft <= 0)
			{
				continue;
			}
			return String.format("%,d to go", (long) Math.ceil(xpLeft / xpPer));
		}
		return null;
	}

	/** Scene-object names a sub's text is about ("fruit stall"); lowercase in, lowercase out. */
	private static java.util.Set<String> objectGrindNames(String lowerText)
	{
		java.util.Set<String> names = new java.util.HashSet<>();
		java.util.regex.Matcher m = STALL_PHRASE.matcher(lowerText);
		while (m.find())
		{
			if (!STALL_STOPWORDS.contains(m.group(1)))
			{
				names.add(m.group(1) + " stall");
			}
		}
		return names;
	}

	private List<net.runelite.api.GameObject> findWantedRocks(Current current)
	{
		java.util.Set<String> rockNames = new java.util.HashSet<>();
		java.util.Set<String> vendorNames = new java.util.HashSet<>();
		List<GoalDetector.ItemGoal> wanted = itemGoalsBySub.get(current.sub.getId());
		if (wanted != null)
		{
			for (GoalDetector.ItemGoal goal : wanted)
			{
				boolean gather = itemTracker.bankCountable(goal.getItemName(), goal.getQuantity());
				int count = gather
					? itemTracker.countOf(goal.getItemName())
					: itemTracker.carriedCountOf(goal.getItemName());
				if (count >= goal.getQuantity())
				{
					continue;
				}
				String rock = ROCK_BY_ORE.get(goal.getItemName().toLowerCase(Locale.ROOT));
				if (rock != null)
				{
					rockNames.add(rock);
				}
				// The goal's item source may name its vending OBJECT — the
				// object-vendor counterpart of the shopkeeper outline. Kept
				// SEPARATE from rockNames: the RFD chest is just "Chest",
				// and matching by name alone would light every decorative
				// chest in the scene — only the NEAREST match outlines.
				String vendor = placeManager.sourceObject(goal.getItemName());
				if (vendor != null)
				{
					vendorNames.add(vendor);
				}
			}
		}
		// "Use the spirit tree...": outline the tree itself — it IS the
		// click target, same as an ore rock is for a mining sub.
		// liveObjectName lowercases, so the names here must be lowercase
		// too (that mismatch cost a play-test round).
		if (SPIRIT_TREE.matcher(current.sub.getPlainText()).find())
		{
			rockNames.add("spirit tree");
		}
		// Grind-at-object subs: "train 42 thieving at the fruit stall..."
		// outlines the stalls themselves, same as rocks for a mining sub.
		rockNames.addAll(objectGrindNames(
			current.sub.getPlainText().toLowerCase(Locale.ROOT)));
		if (rockNames.isEmpty() && vendorNames.isEmpty())
		{
			return java.util.Collections.emptyList();
		}
		Player scanMe = client.getLocalPlayer();
		WorldPoint here = scanMe == null ? null : scanMe.getWorldLocation();
		java.util.LinkedHashSet<net.runelite.api.GameObject> found = new java.util.LinkedHashSet<>();
		net.runelite.api.GameObject nearestVendor = null;
		int vendorBest = Integer.MAX_VALUE;
		net.runelite.api.Tile[][][] tiles = client.getTopLevelWorldView().getScene().getTiles();
		int plane = client.getTopLevelWorldView().getPlane();
		for (net.runelite.api.Tile[] row : tiles[plane])
		{
			for (net.runelite.api.Tile tile : row)
			{
				if (tile == null)
				{
					continue;
				}
				for (net.runelite.api.GameObject object : tile.getGameObjects())
				{
					if (object == null)
					{
						continue;
					}
					String name = liveObjectName(object.getId());
					if (name == null)
					{
						continue;
					}
					if (rockNames.contains(name))
					{
						found.add(object);
					}
					else if (here != null && vendorNames.contains(name))
					{
						int d = object.getWorldLocation().distanceTo2D(here);
						if (d < vendorBest)
						{
							vendorBest = d;
							nearestVendor = object;
						}
					}
				}
			}
		}
		if (nearestVendor != null)
		{
			found.add(nearestVendor);
		}
		return new java.util.ArrayList<>(found);
	}

	/**
	 * Inventory slot ids matching anything the current step needs: its
	 * annotation items (tools/ingredients/shopping list), the sub's text
	 * goals, and a "use X tab" phrase. Matching runs through the tracker's
	 * full stack, so a rune pickaxe lights up for "pickaxe". Client thread.
	 */
	private java.util.Set<Integer> findStepInventoryItems(Current current)
	{
		java.util.List<String> wanted = new java.util.ArrayList<>();
		for (StepAnnotation.ItemNeed need : annotationManager.getItems(current.step.getId()))
		{
			wanted.add(need.name);
		}
		for (StepAnnotation.ItemNeed need : annotationManager.getItems(current.sub.getId()))
		{
			wanted.add(need.name);
		}
		List<GoalDetector.ItemGoal> goals = itemGoalsBySub.get(current.sub.getId());
		if (goals != null)
		{
			for (GoalDetector.ItemGoal goal : goals)
			{
				wanted.add(goal.getItemName());
			}
		}
		java.util.regex.Matcher tab = TAB_PHRASE.matcher(current.sub.getPlainText());
		if (tab.find())
		{
			// The capture may swallow a verb ("USE house tab") — offer
			// the shorter word suffix too.
			String full = tab.group(1).toLowerCase(Locale.ROOT);
			wanted.add(full);
			if (full.contains(" "))
			{
				wanted.add(full.substring(full.indexOf(' ') + 1));
			}
		}
		if (wanted.isEmpty())
		{
			return java.util.Collections.emptySet();
		}
		net.runelite.api.ItemContainer inventory =
			client.getItemContainer(net.runelite.api.gameval.InventoryID.INV);
		if (inventory == null)
		{
			return java.util.Collections.emptySet();
		}
		java.util.Set<Integer> ids = new java.util.HashSet<>();
		for (net.runelite.api.Item item : inventory.getItems())
		{
			if (item.getId() < 0)
			{
				continue;
			}
			String itemName = itemManager.getItemComposition(item.getId()).getName();
			if (itemName == null)
			{
				continue;
			}
			for (String goalName : wanted)
			{
				if (goalName != null && ItemTracker.nameMatchesGoal(itemName, goalName))
				{
					ids.add(item.getId());
					break;
				}
			}
		}
		return ids;
	}

	/** The object's CURRENT (impostor-resolved) name, lowercased; null if none. */
	private String liveObjectName(int id)
	{
		net.runelite.api.ObjectComposition composition = client.getObjectDefinition(id);
		if (composition == null)
		{
			return null;
		}
		if (composition.getImpostorIds() != null)
		{
			try
			{
				composition = composition.getImpostor();
			}
			catch (Exception e)
			{
				return null; // no active impostor for this varbit state
			}
			if (composition == null)
			{
				return null;
			}
		}
		String name = composition.getName();
		return name == null || name.equals("null") ? null : name.toLowerCase(Locale.ROOT);
	}

	private void captureLocation(String annotationId, Consumer<Boolean> onDone)
	{
		captureLocation(annotationId, false, onDone);
	}

	/** "Capture as safespot": same capture, tile gets the Safespot label. */
	private void captureSafespot(String annotationId, Consumer<Boolean> onDone)
	{
		captureLocation(annotationId, true, onDone);
	}

	private void captureLocation(String annotationId, boolean safespot, Consumer<Boolean> onDone)
	{
		clientThread.invoke(() -> {
			Player player = client.getLocalPlayer();
			if (client.getGameState() != GameState.LOGGED_IN || player == null)
			{
				SwingUtilities.invokeLater(() -> onDone.accept(false));
				return;
			}

			WorldPoint where = player.getWorldLocation();
			annotationManager.setTarget(annotationId, where, safespot);
			// A manual capture also OVERRIDES auto-navigation: the player
			// is doing this step HERE, so route to the captured spot and
			// hold until the frontier moves on (owner request).
			Current current = findCurrent();
			navHoldStepId = current == null ? null : current.step.getId();
			navigateTo(where);
			// Confirm in the chatbox so you don't have to look at the panel.
			client.addChatMessage(ChatMessageType.CONSOLE, "",
				"IRONSCAPE: location (" + where.getX() + ", " + where.getY()
					+ (where.getPlane() != 0 ? ", plane " + where.getPlane() : "")
					+ ") saved for " + annotationId, null);
			SwingUtilities.invokeLater(() -> onDone.accept(true));
		});
	}

	/** Right-click on ⌖: forget an accidental LOCAL capture. */
	private void clearCapturedTarget(String annotationId)
	{
		AnnotationManager.ClearResult result = annotationManager.clearTarget(annotationId);
		// The capture also pinned auto-navigation — release the pin so the
		// next pass routes by the step's normal target chain again.
		navHoldStepId = null;
		maybeNavigateToNext();
		String message;
		switch (result)
		{
			case REMOVED_LOCAL:
				message = "IRONSCAPE: captured location for " + annotationId + " removed.";
				break;
			case MASKED_BUNDLED:
				message = "IRONSCAPE: bundled location for " + annotationId
					+ " hidden — use the ⌖ button to capture the right spot.";
				break;
			default:
				message = "IRONSCAPE: no location to remove for this step.";
				break;
		}
		clientThread.invokeLater(() -> client.addChatMessage(ChatMessageType.CONSOLE, "",
			message, null));
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
			// A waiting gravestone outranks EVERYTHING — captures, errands,
			// stand-downs: without the gear there is no route to follow.
			if (deathPoint != null)
			{
				logNavDecision("routing to gravestone at " + deathPoint);
				eventBus.post(new PluginMessage("shortestpath", "path",
					Map.of("target", deathPoint)));
				return;
			}
			// A manual ⌖ capture pinned the route to where the player is
			// working — leave it alone until the frontier step changes.
			if (navHoldStepId != null)
			{
				Current heldCurrent = findCurrent();
				if (heldCurrent != null && navHoldStepId.equals(heldCurrent.step.getId()))
				{
					logNavDecision("holding: manual ⌖ capture pins the route for this step");
					return;
				}
				navHoldStepId = null;
			}
			// An active errand outranks everything below, INCLUDING the
			// jumped-ahead stand-down — it's explicit hand-authored
			// guidance for the CURRENT step, not a heuristic. Shortest
			// Path knows the dungeon transports, so the route points at
			// the LADDER down to Golrie — from the surface the errand's
			// tile marker is invisible and gave no hint to descend. Holds
			// both mid-quest AND after (quest done, pebble unclaimed).
			StepAnnotation.Errand errand = activeErrand();
			if (errand != null)
			{
				eventBus.post(new PluginMessage("shortestpath", "path",
					Map.of("target", errandRoutePoint(errand))));
				return;
			}
			// Chain COMPLETE but the sub's own goal isn't (standing at the
			// chest, milks not yet bought): any route now points AWAY from
			// the destination the chain just delivered — hold here until
			// the sub ticks.
			Current chainCurrent = findCurrent();
			if (chainCurrent != null
				&& !errandChain(chainCurrent.step, chainCurrent.sub).isEmpty())
			{
				logNavDecision("errand chain complete — holding until the sub's goal ticks");
				eventBus.post(new PluginMessage("shortestpath", "clear"));
				return;
			}
			// Player jumped ahead to a later step's quest: ANY route we
			// post drags them back toward the frontier mid-quest — full
			// stand-down until that quest wraps up.
			if (playerJumpedAhead)
			{
				logNavDecision("cleared: jumped ahead to a later step's quest");
				eventBus.post(new PluginMessage("shortestpath", "clear"));
				return;
			}
			// Quest in progress = Quest Helper's show for the DETAILS. But
			// standing down completely left players without QH pointing
			// nowhere ("run back to Falador" gave no route) — so still
			// route to the step's own 📍 area: QH users get a route to the
			// same area QH is guiding them through, no conflict.
			if (questHelperOwnsGuidance())
			{
				Current questCurrent = findCurrent();
				// An explicit ⌖ on the step (bundled or player-captured)
				// IS the step's destination — the loose 📍 area sent the
				// player to the courtyard while their pin sat on the RFD
				// dining-hall doors.
				WorldPoint area = null;
				if (questCurrent != null)
				{
					StepAnnotation.Target pinned =
						annotationManager.getTarget(questCurrent.sub.getId());
					if (pinned == null)
					{
						pinned = annotationManager.getTarget(questCurrent.step.getId());
					}
					if (pinned != null)
					{
						area = new WorldPoint(pinned.x, pinned.y, pinned.plane);
					}
				}
				String location = questCurrent == null
					? null : questCurrent.step.getMetadata().get("location");
				if (area == null && location != null)
				{
					area = placeManager.getLoose(location);
				}
				if (area != null)
				{
					logNavDecision("routing to step area " + (location == null ? area : location)
						+ " (quest owns guidance)");
					eventBus.post(new PluginMessage("shortestpath", "path", Map.of("target", area)));
				}
				else
				{
					logNavDecision("cleared: quest owns guidance, step has no routable area");
					eventBus.post(new PluginMessage("shortestpath", "clear"));
				}
				return;
			}
			WorldPoint target = findNextTarget();
			if (target != null)
			{
				logNavDecision("routing to " + target);
				eventBus.post(new PluginMessage("shortestpath", "path", Map.of("target", target)));
			}
			else
			{
				// The next thing to do has no known location — clear the
				// route so a STALE one (last step's quest etc.) doesn't
				// keep pointing somewhere you no longer need to go.
				logNavDecision("cleared: no routable target in the window");
				eventBus.post(new PluginMessage("shortestpath", "clear"));
			}
		});
	}

	private String lastNavDecision;

	/**
	 * One INFO line per CHANGE of auto-navigation outcome. "Auto-nav seems
	 * dead" reports were undiagnosable — every stand-down branch was
	 * silent; now the session log (mine-session-log.mjs) names the branch.
	 */
	private void logNavDecision(String decision)
	{
		if (!decision.equals(lastNavDecision))
		{
			lastNavDecision = decision;
			log.info("auto-nav: {}", decision);
		}
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
		return questOwningGuidance() != null;
	}

	/** The current step's quest while it's IN_PROGRESS, else null. */
	private Quest questOwningGuidance()
	{
		Current current = findCurrent();
		if (current == null)
		{
			return null;
		}
		Quest quest = stepQuest(current);
		return quest != null && cachedQuestState(quest) == QuestState.IN_PROGRESS
			? quest : null;
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

		// "Use the spirit tree and go to X": the journey STARTS at the
		// nearest spirit tree, not at the far-off destination — Shortest
		// Path would otherwise draw the long walk. After the teleport
		// lands, the reroute finds the destination close (or the tree
		// beside you) and guidance continues normally.
		Current frontier = window.get(0);
		if (SPIRIT_TREE.matcher(frontier.sub.getPlainText()).find())
		{
			WorldPoint destination = targetFor(frontier.step, frontier.sub);
			Player me = client.getLocalPlayer();
			if (me != null && (destination == null
				|| me.getWorldLocation().distanceTo2D(destination) > 40))
			{
				WorldPoint tree = nearestOf(SPIRIT_TREES);
				if (tree != null)
				{
					return tree;
				}
			}
		}
		// "Charter to port sarim": the journey starts at the NEAREST charter
		// dock, not at the destination on foot — same network treatment as
		// spirit trees (owner report: routed the whole walk from Khazard
		// with the Trader Crewmembers thirty tiles east).
		if (CHARTER.matcher(frontier.sub.getPlainText()).find())
		{
			WorldPoint destination = targetFor(frontier.step, frontier.sub);
			Player me = client.getLocalPlayer();
			if (me != null && (destination == null
				|| me.getWorldLocation().distanceTo2D(destination) > 40))
			{
				WorldPoint dock = nearestOf(CHARTER_DOCKS);
				if (dock != null && me.getWorldLocation().distanceTo2D(dock)
					< (destination == null ? Integer.MAX_VALUE
						: me.getWorldLocation().distanceTo2D(destination)))
				{
					return dock;
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
		return nearestOf(BANKS);
	}

	/** The closest of the given points to the player (straight-line). */
	private WorldPoint nearestOf(WorldPoint[] points)
	{
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return null;
		}
		WorldPoint here = player.getWorldLocation();
		WorldPoint best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (WorldPoint point : points)
		{
			int distance = here.distanceTo2D(point);
			if (distance < bestDistance)
			{
				bestDistance = distance;
				best = point;
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
		// An UNSTARTED quest goal routes to the quest's START point — the
		// same pin the quest-start marker floats at. The 📍 fallback routed
		// "Start RFD" to the generic Lumbridge pin outside the castle
		// instead of the Cook in the dining room.
		GoalDetector.QuestGoal questGoal = questGoalBySub.get(sub.getId());
		if (questGoal != null
			&& cachedQuestState(questGoal.getQuest()) == QuestState.NOT_STARTED)
		{
			WorldPoint questStart = placeManager.get(questGoal.getQuest().getName());
			if (questStart != null)
			{
				return questStart;
			}
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
			return;
		}
		// No captured ⌖ for this step: the Go button silently did NOTHING
		// ("Fire strike imps west of the tower of life" — the tower is a
		// known place!). Fall back to the step's best-known location: a
		// place named in its text, else its 📍 area tag.
		String stepId = annotationId.contains(":")
			? annotationId.substring(0, annotationId.indexOf(':')) : annotationId;
		GuideStep step = guideFor(activeVariant).getStepsById().get(stepId);
		if (step == null || step.getSubSteps().isEmpty())
		{
			return;
		}
		SubStep sub = step.getSubSteps().get(0);
		if (annotationId.contains(":"))
		{
			for (SubStep candidate : step.getSubSteps())
			{
				if (candidate.getId().equals(annotationId))
				{
					sub = candidate;
					break;
				}
			}
		}
		WorldPoint fallback = targetFor(step, sub);
		if (fallback != null)
		{
			navigateTo(fallback);
		}
	}

	/** Multi-location transports by clicked name: route to the NEAREST one. */
	private static final Map<String, WorldPoint[]> TRANSPORT_NETWORKS = Map.of(
		"spirit tree", SPIRIT_TREES,
		"spirit trees", SPIRIT_TREES);

	/** A place-name link was clicked in the step text. */
	private void navigateToPlace(String placeName, GuideStep contextStep)
	{
		// A transport NETWORK name ("spirit tree") means "take me to the
		// nearest one" — there is no single fixed point to route to.
		WorldPoint[] network = TRANSPORT_NETWORKS.get(
			placeName.toLowerCase(Locale.ROOT).trim());
		if (network != null)
		{
			clientThread.invokeLater(() -> {
				WorldPoint nearest = nearestOf(network);
				if (nearest != null)
				{
					eventBus.post(new PluginMessage("shortestpath", "path",
						Map.of("target", nearest)));
				}
			});
			return;
		}
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
		// BUT: quest-name semantics only apply while the name can still MEAN
		// the quest — many steps use quest names as landmarks ("fire strike
		// imps west of the Tower of Life"). If the quest is finished, or this
		// step isn't about that quest, the click means "take me there".
		Quest quest = questByName(placeName);
		if (quest != null)
		{
			String stepQuest = contextStep != null ? contextStep.getMetadata().get("quest") : null;
			boolean questIsTheTask = stepQuest != null
				&& stripArticle(stepQuest).equalsIgnoreCase(stripArticle(quest.getName()));
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
				else if (questIsTheTask && state == QuestState.IN_PROGRESS)
				{
					// No programmatic Quest Helper handoff: QH exposes no
					// public API, and the Plugin Hub forbids reflection —
					// pointing the player at it is the compliant version.
					client.addChatMessage(ChatMessageType.CONSOLE, "",
						"IRONSCAPE: " + quest.getName()
							+ " is in progress — its start point isn't where you need to go. "
							+ "Select it in Quest Helper for step-by-step guidance.",
						null);
				}
				else
				{
					// Finished quest, or a landmark reference on some other
					// step — the name means the PLACE now, so just route.
					eventBus.post(new PluginMessage("shortestpath", "path", Map.of("target", point)));
				}
			});
			return;
		}

		// Clicking an item that an active errand CHAIN yields routes to the
		// chain's CURRENT stage instead of the item's final spot — the
		// pebble link must first walk you to the key crate, stage by
		// stage, the way Quest Helper would.
		clientThread.invokeLater(() -> {
			StepAnnotation.Errand stage = activeErrand();
			Current chainCurrent = findCurrent();
			boolean clickedInChain = false;
			if (stage != null && chainCurrent != null)
			{
				for (StepAnnotation.Errand link : errandChain(chainCurrent.step, chainCurrent.sub))
				{
					if (link.item != null && link.item.equalsIgnoreCase(placeName.trim()))
					{
						clickedInChain = true;
						break;
					}
				}
			}
			if (clickedInChain)
			{
				if (stage.note != null)
				{
					client.addChatMessage(ChatMessageType.CONSOLE, "",
						"IRONSCAPE: next: " + stage.note, null);
				}
				eventBus.post(new PluginMessage("shortestpath", "path",
					Map.of("target", new WorldPoint(stage.x, stage.y, stage.plane))));
				return;
			}
			// Item sources carry a how-to ("ask Golrie... key from the
			// crate") — the route shows WHERE, the note says HOW.
			String note = placeManager.note(placeName);
			if (note != null)
			{
				client.addChatMessage(ChatMessageType.CONSOLE, "",
					"IRONSCAPE: " + note, null);
			}
			eventBus.post(new PluginMessage("shortestpath", "path", Map.of("target", point)));
		});
	}

	/** "The Tower of Life" and "Tower of Life" name the same quest. */
	private static String stripArticle(String name)
	{
		return name.trim().replaceFirst("(?i)^the\\s+", "");
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
	 * Every Grouping-UI minigame destination — clicking any of these as a
	 * place (📍 chip or link) lights the teleport click path even when no
	 * guide sub literally says "minigame teleport to X" (Giants' Foundry
	 * was reached via its location chip and got a walking route).
	 */
	private static final java.util.Set<String> GROUPING_MINIGAMES = java.util.Set.of(
		"barbarian assault", "burthorpe games room", "castle wars", "clan wars",
		"fishing trawler", "giants' foundry", "guardians of the rift",
		"last man standing", "nightmare zone", "pest control", "rat pits",
		"shades of mort'ton", "soul wars", "tithe farm", "trouble brewing",
		"tzhaar fight pit");

	/**
	 * Where each Grouping teleport effectively lands for ROUTING (bundled
	 * minigame_landings.json; interiors use their surface exit). Lets the
	 * click-path hint fire when the route's best first leg is a minigame
	 * teleport even though the step never names one — Shortest Path told
	 * the owner "TzHaar Fight Pit Minigame Teleport" for a Brimhaven bar
	 * step while our overlay stayed dark.
	 */
	private final Map<String, WorldPoint> minigameLandings = new HashMap<>();

	private void loadMinigameLandings()
	{
		try (java.io.InputStream in = IronscapePlugin.class
			.getResourceAsStream("/com/ironscape/places/minigame_landings.json"))
		{
			if (in == null)
			{
				return;
			}
			com.google.gson.JsonObject root = gson.fromJson(
				new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8),
				com.google.gson.JsonObject.class);
			com.google.gson.JsonObject landings = root.getAsJsonObject("landings");
			for (String name : landings.keySet())
			{
				com.google.gson.JsonObject p = landings.getAsJsonObject(name);
				minigameLandings.put(name, new WorldPoint(
					p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("plane").getAsInt()));
			}
		}
		catch (Exception e)
		{
			log.warn("Could not read bundled minigame landings", e);
		}
	}

	/** The Grouping teleport's 20-minute cooldown (varp 888 = minute stamp). */
	private boolean minigameTeleportOnCooldown()
	{
		int lastUseMinutes = client.getVarpValue(net.runelite.api.VarPlayer.LAST_MINIGAME_TELEPORT);
		return System.currentTimeMillis() - lastUseMinutes * 60_000L < 20 * 60_000L;
	}

	/**
	 * One standard-spellbook teleport: where it lands, what it takes to
	 * cast, and the spell's widget for the overlay to point at. Element
	 * runes are deliberately NOT checked — staves substitute for them and
	 * modelling that isn't worth it for a hint; law runes can't be
	 * substituted, so they're the real gate.
	 */
	private static final class TeleportSpell
	{
		final String name;
		final int component;
		final int level;
		final int laws;
		final WorldPoint destination;
		final Quest requiredQuest;

		TeleportSpell(String name, int component, int level, int laws,
			WorldPoint destination, Quest requiredQuest)
		{
			this.name = name;
			this.component = component;
			this.level = level;
			this.laws = laws;
			this.destination = destination;
			this.requiredQuest = requiredQuest;
		}
	}

	private static final TeleportSpell[] TELEPORT_SPELLS = {
		new TeleportSpell("Varrock Teleport",
			net.runelite.api.gameval.InterfaceID.MagicSpellbook.VARROCK_TELEPORT,
			25, 1, new WorldPoint(3213, 3424, 0), null),
		new TeleportSpell("Lumbridge Teleport",
			net.runelite.api.gameval.InterfaceID.MagicSpellbook.LUMBRIDGE_TELEPORT,
			31, 1, new WorldPoint(3222, 3218, 0), null),
		new TeleportSpell("Falador Teleport",
			net.runelite.api.gameval.InterfaceID.MagicSpellbook.FALADOR_TELEPORT,
			37, 1, new WorldPoint(2965, 3379, 0), null),
		new TeleportSpell("Camelot Teleport",
			net.runelite.api.gameval.InterfaceID.MagicSpellbook.CAMELOT_TELEPORT,
			45, 1, new WorldPoint(2757, 3479, 0), null),
		new TeleportSpell("Ardougne Teleport",
			net.runelite.api.gameval.InterfaceID.MagicSpellbook.ARDOUGNE_TELEPORT,
			51, 2, new WorldPoint(2662, 3305, 0), Quest.PLAGUE_CITY),
		new TeleportSpell("Watchtower Teleport",
			net.runelite.api.gameval.InterfaceID.MagicSpellbook.WATCHTOWER_TELEPORT,
			58, 2, new WorldPoint(2547, 3113, 0), Quest.WATCHTOWER),
	};

	/** One chosen first leg toward a far target: a Grouping minigame OR a spell. */
	private static final class FirstLeg
	{
		final String minigame;
		final TeleportSpell spell;

		FirstLeg(String minigame, TeleportSpell spell)
		{
			this.minigame = minigame;
			this.spell = spell;
		}
	}

	/**
	 * Straight-line distance, unless riding the spirit-tree network is
	 * shorter: the five permanent trees interconnect, so a point near ANY
	 * tree effectively reaches the tree nearest the target — Varrock
	 * teleport + the GE tree beats every direct landing for the Grand
	 * Tree. Needs Tree Gnome Village done; before that the trees won't
	 * talk to you. The +20 charges a couple of clicks for the ride so a
	 * tree hop never beats a landing that's already close.
	 */
	private int effectiveDistance(WorldPoint from, WorldPoint target)
	{
		int direct = from.distanceTo2D(target);
		if (cachedQuestState(Quest.TREE_GNOME_VILLAGE) != QuestState.FINISHED)
		{
			return direct;
		}
		int toTree = Integer.MAX_VALUE;
		int fromTree = Integer.MAX_VALUE;
		for (WorldPoint tree : SPIRIT_TREES)
		{
			toTree = Math.min(toTree, from.distanceTo2D(tree));
			fromTree = Math.min(fromTree, tree.distanceTo2D(target));
		}
		return Math.min(direct, toTree + fromTree + 20);
	}

	/**
	 * Boss quests that count toward Nightmare Zone's 5-quest entry gate
	 * (the wiki's eligible-quest list, 2026-08-06).
	 */
	private static final Quest[] NMZ_BOSS_QUESTS = {
		Quest.THE_ASCENT_OF_ARCEUUS, Quest.CONTACT, Quest.THE_CORSAIR_CURSE,
		Quest.THE_DEPTHS_OF_DESPAIR, Quest.DESERT_TREASURE_I, Quest.DRAGON_SLAYER_I,
		Quest.DREAM_MENTOR, Quest.FAIRYTALE_I__GROWING_PAINS, Quest.FAMILY_CREST,
		Quest.FIGHT_ARENA, Quest.THE_FREMENNIK_ISLES, Quest.GETTING_AHEAD,
		Quest.THE_GRAND_TREE, Quest.THE_GREAT_BRAIN_ROBBERY, Quest.GRIM_TALES,
		Quest.HAUNTED_MINE, Quest.HOLY_GRAIL, Quest.HORROR_FROM_THE_DEEP,
		Quest.IN_SEARCH_OF_THE_MYREQUE, Quest.LEGENDS_QUEST, Quest.LOST_CITY,
		Quest.LUNAR_DIPLOMACY, Quest.MONKEY_MADNESS_I, Quest.MOUNTAIN_DAUGHTER,
		Quest.MY_ARMS_BIG_ADVENTURE, Quest.ONE_SMALL_FAVOUR, Quest.RECIPE_FOR_DISASTER,
		Quest.ROVING_ELVES, Quest.SHADOW_OF_THE_STORM, Quest.SHILO_VILLAGE,
		Quest.SONG_OF_THE_ELVES, Quest.TALE_OF_THE_RIGHTEOUS, Quest.TREE_GNOME_VILLAGE,
		Quest.TROLL_ROMANCE, Quest.TROLL_STRONGHOLD, Quest.VAMPYRE_SLAYER,
		Quest.WHAT_LIES_BELOW, Quest.WITCHS_HOUSE,
	};

	/**
	 * Entry gates for the minigames whose GROUPING TELEPORT is locked
	 * behind account state — a hint must never point at a teleport the
	 * game will refuse (NMZ scored second-best for the Grand Tree while
	 * the picker showed it locked for the owner). Unlisted = usable.
	 */
	private boolean minigameLandingAvailable(String name)
	{
		Player me = client.getLocalPlayer();
		switch (name.toLowerCase(Locale.ROOT))
		{
			case "nightmare zone":
				int bosses = 0;
				for (Quest quest : NMZ_BOSS_QUESTS)
				{
					if (cachedQuestState(quest) == QuestState.FINISHED)
					{
						bosses++;
					}
				}
				return bosses >= 5;
			case "pest control":
			case "soul wars":
				return me != null && me.getCombatLevel() >= 40;
			case "shades of mort'ton":
				return cachedQuestState(Quest.SHADES_OF_MORTTON) == QuestState.FINISHED;
			case "trouble brewing":
				return cachedQuestState(Quest.CABIN_FEVER) == QuestState.FINISHED
					&& client.getRealSkillLevel(Skill.COOKING) >= 40;
			default:
				return true;
		}
	}

	/**
	 * The best first leg toward `target`, minigame landings and CASTABLE
	 * spellbook teleports (magic level, law runes carried, quest unlocks)
	 * competing on EFFECTIVE distance — or null when walking is
	 * comparable: the winner must be under 60% of the player's own
	 * effective distance, and the journey >100 tiles.
	 */
	private FirstLeg firstLegTowards(WorldPoint target, boolean minigameAvailable)
	{
		Player me = client.getLocalPlayer();
		if (me == null || target == null)
		{
			return null;
		}
		// Dungeons live at y+6400: a surface target read from underground
		// (or vice versa) makes every 2D distance fiction — no hint beats
		// a wrong one ("teleport to Lumbridge" while one ladder below it).
		if (Math.abs(me.getWorldLocation().getY() - target.getY()) > 4000)
		{
			return null;
		}
		int playerDistance = effectiveDistance(me.getWorldLocation(), target);
		if (playerDistance <= 100)
		{
			return null;
		}
		int bestDistance = (int) (playerDistance * 0.6);
		String bestMinigame = null;
		if (minigameAvailable)
		{
			for (Map.Entry<String, WorldPoint> entry : minigameLandings.entrySet())
			{
				if (!minigameLandingAvailable(entry.getKey()))
				{
					continue;
				}
				int d = effectiveDistance(entry.getValue(), target);
				if (d < bestDistance)
				{
					bestDistance = d;
					bestMinigame = entry.getKey();
				}
			}
		}
		TeleportSpell bestSpell = null;
		int magic = client.getRealSkillLevel(Skill.MAGIC);
		int laws = itemTracker.carriedCountOf("law runes");
		for (TeleportSpell spell : TELEPORT_SPELLS)
		{
			if (magic < spell.level || laws < spell.laws)
			{
				continue;
			}
			if (spell.requiredQuest != null
				&& cachedQuestState(spell.requiredQuest) != QuestState.FINISHED)
			{
				continue;
			}
			int d = effectiveDistance(spell.destination, target);
			if (d < bestDistance)
			{
				bestDistance = d;
				bestSpell = spell;
				bestMinigame = null;
			}
		}
		return bestMinigame == null && bestSpell == null
			? null : new FirstLeg(bestMinigame, bestSpell);
	}

	/** The minigame-teleport name matching this place name, or null. */
	private String minigameByName(String placeName)
	{
		for (String minigame : minigameBySub.values())
		{
			if (minigame.equalsIgnoreCase(placeName))
			{
				return minigame;
			}
		}
		String key = placeName.toLowerCase(Locale.ROOT).replace('’', '\'');
		return GROUPING_MINIGAMES.contains(key) ? placeName : null;
	}

	/**
	 * A "world 444" link in the guide text was clicked. On the login
	 * screen this switches the world directly (like the Default World
	 * plugin). In game, automated hopping (client.hopToWorld) is not
	 * permitted on the Plugin Hub — we open the world switcher and let
	 * the player click the world themselves.
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

			if (client.getGameState() == GameState.LOGIN_SCREEN)
			{
				// The api World is a client-side struct we fill from the
				// downloaded world list entry.
				net.runelite.api.World rsWorld = client.createWorld();
				rsWorld.setActivity(world.getActivity());
				rsWorld.setAddress(world.getAddress());
				rsWorld.setId(world.getId());
				rsWorld.setPlayerCount(world.getPlayers());
				rsWorld.setLocation(world.getLocation());
				rsWorld.setTypes(net.runelite.client.util.WorldUtil.toWorldTypes(world.getTypes()));
				client.changeWorld(rsWorld);
				return;
			}

			client.openWorldHopper();
			client.addChatMessage(ChatMessageType.CONSOLE, "",
				"IRONSCAPE: pick world " + worldNumber + " in the world switcher.", null);
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
