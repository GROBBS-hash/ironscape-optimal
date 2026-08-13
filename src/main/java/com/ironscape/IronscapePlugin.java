package com.ironscape;

import com.ironscape.annotations.AnnotationManager;
import com.ironscape.annotations.ErrandProgress;
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

	/** Precomputed walking distances from every teleport landing — see firstLegTowards. */
	@Inject
	private com.ironscape.travel.TravelDistances travelDistances;

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
	private com.ironscape.overlay.QuestHandoffOverlay questHandoffOverlay;

	/** RuneLite's notification channel (tray/sound/flash, user-configured). */
	@Inject
	private net.runelite.client.Notifier notifier;

	@Inject
	private com.ironscape.overlay.QuestStartMarkerOverlay questStartMarkerOverlay;

	@Inject
	private com.ironscape.overlay.NpcTargetOverlay npcTargetOverlay;

	@Inject
	private com.ironscape.overlay.TargetTileOverlay targetTileOverlay;

	@Inject
	private com.ironscape.overlay.ObjectTargetOverlay objectTargetOverlay;

	@Inject
	private com.ironscape.overlay.EmoteHintOverlay emoteHintOverlay;

	/** Sprite of the emote the current step asks for, or -1. */
	private volatile int activeEmoteSprite = -1;
	/** Its name, for the tab label. */
	private volatile String activeEmoteName;

	@Inject
	private com.ironscape.overlay.InventoryItemHintOverlay inventoryItemHintOverlay;

	@Inject
	private com.ironscape.overlay.TeleportItemHintOverlay teleportItemHintOverlay;

	@Inject
	private com.ironscape.overlay.ShopItemHintOverlay shopItemHintOverlay;

	/** Inventory slot item ids the current step is about; overlay-outlined. */
	private volatile java.util.Set<Integer> inventoryHintItemIds = java.util.Collections.emptySet();

	/**
	 * The same step items by NAME, for the shop overlay: stock you have
	 * not bought yet has no inventory id to match on.
	 */
	private volatile java.util.Set<String> shopHintItemNames = java.util.Collections.emptySet();

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
	 * Overhead icon per VENDOR name, when one step buys from several
	 * shops — the candle maker wears a candle, Harry wears a fishing rod.
	 * Falls back to currentSubItemIcon for anyone not named here.
	 */
	private volatile java.util.Map<String, Integer> npcItemIcons =
		java.util.Collections.emptyMap();

	/**
	 * Scene tiles holding a ground item that matches one of the current
	 * sub's item goals — the "pick up 2 iron bars" / item-spawn case.
	 * Scans the current plane's tiles once per game tick; ~11k null checks
	 * is nothing, and it needs no spawn/despawn bookkeeping.
	 */
	/**
	 * Is the thing this errand stage wants lying in the scene right now?
	 *
	 * If it is, the nearest-NPC fallback stands down. The owner walked into
	 * the Catherby house for the insect repellent, found it on a table, and
	 * watched the outline go on a bystanding NPC instead — a pickup stage
	 * has no NPC in it, the ITEM is the target. Same rule as the one that
	 * stops a fruit-stall sub crowning a passing Woman.
	 *
	 * Deliberately re-scans rather than reading groundItemTargets: that
	 * field is assigned further down this same tick, and reordering the
	 * block to reuse it would leave it stale on the paths that skip it.
	 */
	private boolean errandPickupInScene(Current current, StepAnnotation.Errand errand)
	{
		return errand != null && errand.item != null
			&& !findWantedGroundItems(current, errand).isEmpty();
	}

	private List<WorldPoint> findWantedGroundItems(Current current, StepAnnotation.Errand errand)
	{
		java.util.Set<String> names = new java.util.HashSet<>();
		List<GoalDetector.ItemGoal> wanted = itemGoalsBySub.get(current.sub.getId());
		if (wanted != null)
		{
			for (GoalDetector.ItemGoal goal : wanted)
			{
				if (isCoins(goal.getItemName()))
				{
					continue; // stray dropped gp is not "your step's items"
				}
				java.util.Collections.addAll(names, ItemTracker.aliases(goal.getItemName()));
			}
		}
		// The ACTIVE ERRAND STAGE's item counts too, and on the steps where
		// this matters it is the only thing that does. "Kill Mordred and get
		// bat bones/black candle" detects no item goal at all, so the insect
		// repellent sitting on a table in the Catherby house was invisible
		// here and the nearest NPC got the outline instead (owner, in play).
		// Same root as the missing badges: the chain knew the item, nothing
		// else did.
		if (errand != null && errand.item != null && !isCoins(errand.item))
		{
			java.util.Collections.addAll(names, ItemTracker.aliases(errand.item));
		}
		if (names.isEmpty())
		{
			return java.util.Collections.emptyList();
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
						// SCENE coordinates on purpose: these go straight to
						// the overlay's LocalPoint.fromWorld, which wants the
						// tile as the scene has it, not its template twin.
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
	 * The "back to the guide" banner, or null when nothing is showing.
	 * Volatile: written on the client thread, read by the overlay render.
	 */
	private volatile com.ironscape.overlay.QuestHandoffOverlay.Model handoffModel;

	/** Ticks the banner has left. */
	private int handoffBannerTicks;

	/**
	 * The step whose "Quest Helper takes over" banner has already been shown.
	 *
	 * The stand-down is re-decided on every navigation event, so without this
	 * the banner would re-fire on each one. Keyed by STEP rather than by a
	 * boolean edge so it survives the route flicking away and back (a bank
	 * stop, a death) and still fires again on the next quest step.
	 */
	private String standDownAnnouncedStepId;

	/**
	 * ~18 seconds. Long enough to finish the dialogue you are in and read
	 * it, short enough that it never becomes furniture. It expires rather
	 * than needing a dismiss click - a banner you must close to keep
	 * playing is worse than the problem it solves.
	 */
	private static final int HANDOFF_BANNER_TICKS = 30;

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

	/**
	 * The requirements that apply to a SUB: its own, else its step's.
	 *
	 * The badge row asks by sub id ("67baf2f956:0") while a bare-step
	 * `requires` is stored under the STEP id, so looking the sub id up in
	 * the step map could only ever miss. On this guide every step has one
	 * sub, which made that miss total: all 48 annotated skill requirements
	 * were invisible, and "Train Runecraft to 10" showed no runecraft
	 * badge at all (owner, in play). Completion was never affected — it
	 * looks these up by step id — so the data was right and only the panel
	 * was silent.
	 */
	/**
	 * Does this sub carry a SKILL requirement the player has not reached?
	 * Only skills count — a varbit checkpoint says nothing about whether a
	 * grind is still ahead.
	 */
	private boolean skillRequirementUnmet(SubStep sub)
	{
		List<StepRequirement> requirements = requirementsFor(sub.getId());
		if (requirements == null)
		{
			return false;
		}
		for (StepRequirement requirement : requirements)
		{
			if (requirement.skill != null
				&& realLevelBySkill.getOrDefault(requirement.skill, 1) < requirement.threshold)
			{
				return true;
			}
		}
		return false;
	}

	private List<StepRequirement> requirementsFor(String subId)
	{
		List<StepRequirement> own = subRequirements.get(subId);
		if (own != null)
		{
			return own;
		}
		int colon = subId.lastIndexOf(':');
		return stepSkillRequirements.get(colon > 0 ? subId.substring(0, colon) : subId);
	}

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

	/** Round-robin position in the amortised quest-state scan (see onGameTick). */
	private int questScanCursor;

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

	/** Worn-slot component to highlight for an equipped-item teleport; -1 none. */
	private volatile int activeEquippedTeleport = -1;

	/**
	 * What to call that highlight — the teleport item's destination
	 * ("Ardougne cloak: Kandarin Monastery"). Null falls back to the
	 * overlay's generic "Teleport", which is what the Chronicle uses.
	 */
	private volatile String equippedTeleportLabel;

	/** "Chronicle tele" / "chronicle tele and buy a kitten" — the worn Chronicle. */
	private static final java.util.regex.Pattern CHRONICLE_TELE =
		java.util.regex.Pattern.compile("\\bchronicle\\s+tele", java.util.regex.Pattern.CASE_INSENSITIVE);

	/** Route-aware: the FREE home teleport is the best first leg. */
	private volatile boolean routeHomeTeleportHint;

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

	/**
	 * Game ticks to keep re-laying the filter view out after the bank
	 * container changes. Three (~1.8s) because we do not know how long the
	 * client takes to give a deposited item its widget back — one tick was
	 * the timing the failed attempts assumed.
	 */
	private static final int BANK_RELAYOUT_TICKS = 3;

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

	/**
	 * Sub id -> the chain's stage items in order, each NEEDED / HELD / SPENT.
	 * Written on the client thread by cacheErrandBadges, read from Swing.
	 */
	/** Stage checklist per sub: "index|label" -> DONE | CURRENT | TODO. */
	private final Map<String, java.util.LinkedHashMap<String, String>> errandChecklistBySub =
		new java.util.concurrent.ConcurrentHashMap<>();

	private final Map<String, java.util.LinkedHashMap<String, String>> errandStagesBySub =
		new java.util.concurrent.ConcurrentHashMap<>();

	/** Last poll of activeErrand()'s stage item, to reroute on stage changes. */
	private String lastErrandStage;

	/**
	 * The player's REAL-WORLD tile — the one our annotations, places and
	 * region checkpoints are written in.
	 *
	 * INSTANCES: areas the server copies per-party (most quest interiors,
	 * minigames, and the rune essence mine, which spawns a fresh copy every
	 * 5 entrants) are built as a DYNAMIC region somewhere else on the map,
	 * and {@code Actor#getWorldLocation} reports that copy's coordinates.
	 * Read raw, a region checkpoint, an arrival radius, an errand stage or
	 * a ⌖ capture is then comparing against a different map — this is why
	 * "Use Brimstails to go to ess mines" never ticked while the player
	 * stood in the mine holding the orb (region 11595, checkpoint sound,
	 * position wrong). fromLocalInstance maps back to the TEMPLATE tile and
	 * falls through to the plain reading outside instances, so callers use
	 * it unconditionally. Client thread (reads the scene).
	 */
	private WorldPoint playerPoint()
	{
		Player me = client.getLocalPlayer();
		return me == null ? null : realPoint(me);
	}

	/** {@link #playerPoint()} for any actor — an NPC in an instance moves too. */
	private WorldPoint realPoint(net.runelite.api.Actor actor)
	{
		net.runelite.api.coords.LocalPoint local = actor.getLocalLocation();
		// A just-despawned actor has no local point; its last world location
		// is the best answer left.
		return local == null ? actor.getWorldLocation()
			: WorldPoint.fromLocalInstance(client, local);
	}

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
		// A chain whose first stage says preQuest guides regardless: PREP
		// steps craft their hand-ins BEFORE the quest begins. (Cached
		// state — Quest.getState runs a clientscript, and this is per-tick.)
		Quest quest = stepQuest(current);
		if (quest != null && cachedQuestState(quest) == QuestState.NOT_STARTED)
		{
			List<StepAnnotation.Errand> chain = errandChain(current.step, current.sub);
			if (chain.isEmpty() || !Boolean.TRUE.equals(chain.get(0).preQuest))
			{
				return null;
			}
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
		// The order rule lives in ErrandProgress, away from the client, so
		// it can be tested: monotonic conditions (quest vars, owning an
		// item) may look ahead; where the player is standing is judged at
		// the front of the chain and nowhere else. Without that split a
		// chain that comes back on itself is inexpressible — walking into
		// Keep Le Faye at ground level would satisfy the "back down to the
		// ground floor" leg that belongs after Mordred, and the cascade
		// would mark the fight done on the way in.
		int front = ErrandProgress.advance(step.getId(), chain, errandDone, errandWorld);
		cacheErrandBadges(step, sub, chain);
		return front < chain.size() ? chain.get(front) : null;
	}

	/** The sub of this step with that id, else null. */
	private static SubStep findSub(GuideStep step, String subId)
	{
		for (SubStep sub : step.getSubSteps())
		{
			if (sub.getId().equals(subId))
			{
				return sub;
			}
		}
		return null;
	}

	/**
	 * Is Quest Helper installed and switched on?
	 *
	 * Read out of RuneLite's own config, which is the sanctioned way to
	 * learn anything about another plugin — the hub forbids reflection, and
	 * QH's classes sit in an isolated classloader besides. Two keys: the
	 * installed hub plugins, and an explicit disable. The toggle is ABSENT
	 * when a plugin sits at its default, so only a literal "false" is off.
	 *
	 * The comma split matters: "sea-charting-quest-helper" CONTAINS
	 * "quest-helper", so a substring test says yes to the wrong plugin.
	 *
	 * Honest limit: this says INSTALLED, not "actively guiding". Closing
	 * QH's sidebar while leaving the plugin on is invisible to us and the
	 * route stays held; disabling the plugin restores our own routing.
	 */
	private boolean questHelperInstalled()
	{
		String installed = configManager.getConfiguration("runelite", "externalPlugins");
		if (installed == null)
		{
			return false;
		}
		boolean present = false;
		for (String id : installed.split(","))
		{
			if ("quest-helper".equals(id.trim().replaceAll("^\\[|\\]$", "")))
			{
				present = true;
				break;
			}
		}
		return present && !"false".equalsIgnoreCase(
			configManager.getConfiguration("runelite", "questhelperplugin"));
	}

	/**
	 * Is a hub plugin installed AND switched on?
	 *
	 * <p>Split on commas and compare WHOLE ids: a substring test says yes to
	 * the wrong plugin, since "sea-charting-quest-helper" contains
	 * "quest-helper". The enable toggle is ABSENT at its default, so only a
	 * literal "false" counts as off.
	 */
	private boolean hubPluginActive(String installedId, String toggleKey)
	{
		String installed = configManager.getConfiguration("runelite", "externalPlugins");
		if (installed == null)
		{
			return false;
		}
		for (String id : installed.split(","))
		{
			if (installedId.equals(id.trim().replaceAll("^\\[|\\]$", "")))
			{
				return !"false".equalsIgnoreCase(
					configManager.getConfiguration("runelite", toggleKey));
			}
		}
		return false;
	}

	/** Said once per session; two routes on screen only needs explaining once. */
	private boolean warnedAboutTwoPathers;

	/**
	 * Both pathing plugins on at once draws every route TWICE.
	 *
	 * <p>We deliberately post on the "shortestpath" channel so one message
	 * serves either plugin — GPS is a fork of Shortest Path and keeps that
	 * namespace as a compatibility alias. The cost of that reach is that a
	 * player running both gets two overlapping lines, which looks like a
	 * fault in THIS plugin rather than a configuration choice. Cheaper to
	 * say so than to receive the bug report.
	 */
	private void warnIfTwoPathersActive()
	{
		if (warnedAboutTwoPathers)
		{
			return;
		}
		if (!hubPluginActive("shortest-path", "shortestpathplugin")
			|| !hubPluginActive("gps", "gpsplugin"))
		{
			return;
		}
		warnedAboutTwoPathers = true;
		// ASCII only: the game font has no check marks or arrows and renders
		// them as "?" (wave 11).
		client.addChatMessage(ChatMessageType.CONSOLE, "",
			"IRONSCAPE: Shortest Path and GPS are both enabled, so every route"
				+ " will be drawn twice. Turn one of them off.", null);
	}

	/**
	 * Does any detector claim this sub? The same seven collections the
	 * ambient-tick sweep consults — if none of them holds the sub, nothing
	 * can ever tick it but a hand tick or its errand chain.
	 */
	private boolean hasAnyGoal(String subId)
	{
		return itemGoalsBySub.containsKey(subId)
			|| questGoalBySub.containsKey(subId)
			|| levelGoalsBySub.containsKey(subId)
			|| countedGoalBySub.containsKey(subId)
			|| actionGoalBySub.containsKey(subId)
			|| travelGoalSubs.contains(subId)
			|| interactionGoalSubs.contains(subId);
	}

	/** Every non-optional leg of the sub's chain satisfied (false when it has none). */
	private boolean errandChainComplete(GuideStep step, SubStep sub)
	{
		List<StepAnnotation.Errand> chain = errandChain(step, sub);
		if (chain.isEmpty())
		{
			return false;
		}
		// Re-run the progress pass first: errandDone is only as current as
		// its last evaluation, and this runs in the completion loop, which
		// may reach a sub the guidance path has not looked at this tick.
		ErrandProgress.advance(step.getId(), chain, errandDone, errandWorld);
		return ErrandProgress.complete(step.getId(), chain, errandDone);
	}

	/** The client readings ErrandProgress needs, bound once. */
	private final ErrandProgress.World errandWorld = new ErrandProgress.World()
	{
		@Override
		public int varValue(Integer varbit, Integer varp)
		{
			return varbit != null ? client.getVarbitValue(varbit) : client.getVarpValue(varp);
		}

		@Override
		public int carriedCount(String item)
		{
			return itemTracker.carriedCountOf(item);
		}

		@Override
		public int totalCount(String item)
		{
			return itemTracker.countOf(item);
		}

		@Override
		public WorldPoint here()
		{
			return playerPoint();
		}
	};

	/**
	 * Publish the chain's stage items for the panel to badge.
	 *
	 * A step whose objective lives in an errand chain had NOTHING to show:
	 * "Kill Mordred and get bat bones/black candle" detects no item goal at
	 * all and its annotation carries only a note, so the card rendered with
	 * no items while the step below it listed four (owner, 2026-08-08).
	 *
	 * The naive fix — synthesise "0/1" needs from the stage items — is wrong
	 * for exactly the chains that need it most. Merlin's Crystal spends the
	 * repellent and the bucket to make the wax, and the wax to make the
	 * candle, so three badges would sit permanently red the moment you made
	 * progress. That is the misinformation the kit policy exists to stop.
	 *
	 * So each stage publishes a STATE rather than a count, and the chain's
	 * own verdict decides it: SPENT for a stage already behind you whose item
	 * you no longer hold, HELD for one you cleared and still carry, NEEDED
	 * for the rest. Written here because this runs per tick on the client
	 * thread with errandDone already computed; the panel reads the map from
	 * Swing, the same split checkpointMetBySub uses (badges cannot read game
	 * state off the client thread).
	 */
	/**
	 * Publish the chain as a CHECKLIST for the panel: every stage in order,
	 * each labelled DONE, CURRENT or TODO.
	 *
	 * The badges above answer "what am I carrying"; a diary chain has ten
	 * or fifteen legs and most carry no item at all, so they were invisible
	 * -- the card said "Finish off Ardy easy tasks" and nothing else, while
	 * the game's own diary interface has always shown the list with the
	 * finished ones struck through (owner's suggestion, and it is the right
	 * model to copy: he already reads that screen).
	 *
	 * Same client-thread/Swing split as the badges: computed here where
	 * errandDone is already known, read from Swing through a supplier.
	 */
	private void cacheErrandChecklist(GuideStep step, SubStep sub,
		List<StepAnnotation.Errand> chain)
	{
		int active = ErrandProgress.advance(step.getId(), chain, errandDone, errandWorld);
		java.util.LinkedHashMap<String, String> list = new java.util.LinkedHashMap<>();
		for (int i = 0; i < chain.size(); i++)
		{
			StepAnnotation.Errand stage = chain.get(i);
			boolean done = errandDone.contains(ErrandProgress.stageKey(step.getId(), chain, i));
			// The INDEX prefix keeps two legs with the same wording apart --
			// a map key collision would silently drop one row from the list.
			list.put(i + "|" + ErrandProgress.checklistLabel(stage),
				done ? "DONE" : i == active ? "CURRENT" : "TODO");
		}
		if (!list.equals(errandChecklistBySub.put(sub.getId(), list)) && panel != null)
		{
			// refreshItemBadges, never panel::refresh -- see cacheErrandBadges.
			SwingUtilities.invokeLater(panel::refreshItemCounts);
		}
	}

	private void cacheErrandBadges(GuideStep step, SubStep sub, List<StepAnnotation.Errand> chain)
	{
		cacheErrandChecklist(step, sub, chain);
		java.util.LinkedHashMap<String, String> states = new java.util.LinkedHashMap<>();
		for (int i = 0; i < chain.size(); i++)
		{
			StepAnnotation.Errand stage = chain.get(i);
			if (stage.item == null)
			{
				continue;                     // waypoint: nothing to carry
			}
			String state;
			if (!errandDone.contains(ErrandProgress.stageKey(step.getId(), chain, i)))
			{
				state = "NEEDED";
			}
			else if (Boolean.TRUE.equals(stage.given) || itemTracker.countOf(stage.item) <= 0)
			{
				// Handed over, or consumed into the next stage. Saying "used
				// here" beats a red shortfall for something you were supposed
				// to spend.
				state = "SPENT";
			}
			else
			{
				state = "HELD";
			}
			states.put(stage.item, state);
		}
		if (!states.equals(errandStagesBySub.put(sub.getId(), states)) && panel != null)
		{
			// NEVER panel::refresh from here. That rebuilds the whole view,
			// scroll and jump-to-current included, and this runs on the tick
			// path once per chain -- the owner picked up the insect repellent
			// and the panel went BLANK (no exception logged; the rebuilds
			// themselves are the fault). refreshItemCounts only re-runs the
			// refreshers a row already owns, so it cannot blank anything.
			//
			// The cost is that a row cannot APPEAR from here: stage badges
			// show from the next natural rebuild instead. In practice the
			// cache is already warm by then, since it fills on the first tick
			// the step is current and the panel rebuilds when the frontier
			// moves. Letting a refresher add rows is the real fix and wants
			// its own change, not a rebuild hidden inside a badge update.
			SwingUtilities.invokeLater(panel::refreshItemCounts);
		}
	}

	/**
	 * QH-style dialog highlighting: matching options in the chat menu
	 * recolor blue. Two sources — the active ERRAND stage's `dialog`, and
	 * any `dialog` list on the current sub/step's own annotation, so a
	 * plain step can say which option to pick without inventing a
	 * one-stage chain to carry it ("Can you teleport me to the Rune
	 * Essence Mine?" sits under "What's that cute creature wandering
	 * around?"). Runs on widget load AND per tick — the menu rebuilds on
	 * every dialog advance and the recolor must survive it. Client thread.
	 */
	/**
	 * "Speak to Lady of the lake" is done when she speaks back.
	 *
	 * The guide has steps whose whole job is a conversation, and nothing
	 * could ever tick them: arriving is not talking (TALK_INSTRUCTION), and
	 * no detector reads dialogue. They sat as hand-ticks, and the arrival
	 * tick that used to cover one of them was a lie that also stole the
	 * NPC outline by advancing the frontier.
	 *
	 * The signal is the dialogue box itself: the speaker's NAME widget is
	 * the game telling us who is talking, which is as direct as evidence
	 * gets — no proxy, no radius, no timing window.
	 *
	 * Deliberately narrow, because the failure mode of a new detector is
	 * ticking things early:
	 *   - the sub must NAME the speaker, so "ask every question" with
	 *     nobody named stays a hand tick rather than completing on whoever
	 *     happens to talk;
	 *   - only subs NOTHING else can tick (hasAnyGoal), so a step that
	 *     already completes off a quest state or an item keeps doing that
	 *     — talking to the Duke must not tick "talk to the duke to START
	 *     Rune mysteries" before the quest actually starts;
	 *   - an authored checkpoint or an unsatisfied chain still owns the
	 *     sub, the same precedence every other heuristic observes.
	 */
	private void detectConversation()
	{
		Current current = findCurrent();
		if (current == null)
		{
			return;
		}
		String text = current.sub.getPlainText();
		if (!TALK_INSTRUCTION.matcher(text).find()
			|| hasAnyGoal(current.sub.getId()))
		{
			return;
		}
		List<StepRequirement> reqs = subRequirements.get(current.sub.getId());
		if (reqs != null && hasVarCheckpoint(reqs))
		{
			return;             // authored checkpoint owns this sub
		}
		if (unsatisfiedErrandStage(current.step, current.sub) != null)
		{
			return;             // the chain defines "done"
		}
		String speaker = dialogueSpeaker();
		if (speaker != null && subNamesSpeaker(text, speaker))
		{
			completeSubGoal(current.step, current.sub, "talked to " + speaker);
		}
	}

	/** Who is talking in the dialogue box, or null if nobody is. */
	private String dialogueSpeaker()
	{
		int[] nameWidgets =
		{
			net.runelite.api.gameval.InterfaceID.ChatLeft.NAME,
			net.runelite.api.gameval.InterfaceID.ChatRight.NAME,
		};
		for (int id : nameWidgets)
		{
			net.runelite.api.widgets.Widget name = client.getWidget(id);
			if (name == null || name.isHidden() || name.getText() == null)
			{
				continue;
			}
			// NPC names carry tags and non-breaking spaces; the guide does not.
			String clean = net.runelite.client.util.Text.removeTags(name.getText())
				.replace(' ', ' ').trim();
			if (!clean.isEmpty())
			{
				return clean;
			}
		}
		return null;
	}

	/**
	 * Does the sub name this speaker? The full name first ("Oziach"), then
	 * its leading word, because the guide abbreviates where the game does
	 * not — "Speak to Martin" against a "Martin the Master Gardener". Four
	 * characters minimum so a "the"/"man" style lead-in cannot match, and
	 * word-bounded either side so "Ned" does not match "needed".
	 */
	private static boolean subNamesSpeaker(String subText, String speaker)
	{
		String haystack = subText.toLowerCase(Locale.ROOT);
		String full = speaker.toLowerCase(Locale.ROOT);
		if (containsWord(haystack, full))
		{
			return true;
		}
		// The game's article is not the guide's: she is "The Lady of the
		// Lake" in the menu and "Lady of the lake" in the step, which failed
		// BOTH tests — the full name for the extra word, and the leading-word
		// fallback because "the" is below the floor (owner's screenshot,
		// before this had ever run).
		String bare = full.startsWith("the ") ? full.substring(4) : full;
		if (bare != full && containsWord(haystack, bare))
		{
			return true;
		}
		int space = bare.indexOf(' ');
		String first = space > 0 ? bare.substring(0, space) : bare;
		return first.length() >= 4 && containsWord(haystack, first);
	}

	private void highlightStageDialog()
	{
		java.util.Set<String> wanted = new java.util.LinkedHashSet<>();
		Current current = findCurrent();
		// The whole live chain's options, not just the active stage's.
		//
		// Scoping them to the active stage looks tidier and is wrong,
		// because a stage gated on quest progress is satisfied BY the
		// conversation it is guiding: Morgan Le Faye's last option is the
		// one that advances varp 14 to 4, and the session log has the chain
		// moving on to the Candle maker twenty seconds before the player
		// picked it. Every "talk to X until the var moves" stage has that
		// shape, so a stage can never own its own final option.
		//
		// Costs nothing to widen: these are exact option strings, and an
		// option that is not on screen simply does not match.
		if (current != null && activeErrand() != null)
		{
			for (StepAnnotation.Errand stage : errandChain(current.step, current.sub))
			{
				if (stage.dialog != null)
				{
					wanted.addAll(stage.dialog);
				}
			}
		}
		if (current != null)
		{
			// Sub-keyed first, then the step — same precedence as ⌖ targets.
			wanted.addAll(annotationManager.getDialog(current.sub.getId()));
			wanted.addAll(annotationManager.getDialog(current.step.getId()));

			// GENERIC quest options, no seeding required. Hand-authoring a
			// dialog list per quest step is Quest Helper's job and we will
			// never match it, but the options that actually matter are the
			// same three everywhere, so cover them for free: the owner hit a
			// plain "Your quest." sitting unhighlighted mid-Biohazard.
			// Only while the step's quest is genuinely in progress, so an
			// unrelated NPC offering "Your quest." stays untouched.
			Quest quest = stepQuest(current);
			if (quest != null && cachedQuestState(quest) == QuestState.IN_PROGRESS)
			{
				wanted.add("Your quest");
				wanted.add(quest.getName());
				wanted.add("Talk about " + quest.getName());
			}
		}
		if (wanted.isEmpty())
		{
			return;
		}
		net.runelite.api.widgets.Widget options = client.getWidget(
			net.runelite.api.gameval.InterfaceID.Chatmenu.OPTIONS);
		if (options == null || options.isHidden())
		{
			return;
		}
		net.runelite.api.widgets.Widget[] children = options.getDynamicChildren();
		if (children == null)
		{
			return;
		}
		int matched = 0;
		java.util.List<String> offered = new java.util.ArrayList<>();
		for (net.runelite.api.widgets.Widget child : children)
		{
			String text = child == null ? null : child.getText();
			// "Select an option" is the menu's HEADER, not a choice — it was
			// making every count read one high.
			if (text == null || text.isEmpty() || "Select an option".equals(text))
			{
				continue;
			}
			offered.add(text);
			for (String want : wanted)
			{
				if (dialogOptionMatches(text, want))
				{
					child.setTextColor(0x1a1aff);
					matched++;
					break;
				}
			}
		}
		// Forensics, in the shape of logNavDecision / logHintDecision. This
		// path had none, so when the owner reported Morgan Le Faye's options
		// staying white there was no way to tell a dead code path from a
		// string that never could have matched — and the log settled it only
		// because a THIRD-PARTY plugin happened to print the chosen option.
		// Quest Helper says "Ok I will do all that."; the game says "Ok, I
		// will go do all that.". Logged only when the menu's contents change,
		// so a menu left open costs one line.
		String menu = matched + "/" + offered.size() + " " + offered + " vs " + wanted;
		if (!menu.equals(lastDialogMenu))
		{
			lastDialogMenu = menu;
			log.info("dialog-highlight: matched {} of {} options {} against {}",
				matched, offered.size(), offered, wanted);
		}
	}

	/** Last dialog-menu forensic line, so an open menu logs once. */
	private String lastDialogMenu;

	/**
	 * Does this chat option mean the wanted one? Exact matching was too
	 * brittle for the generic quest options: the menu renders "Your quest."
	 * with a trailing stop, sometimes wrapped in colour tags, and a seeded
	 * string copied out of Quest Helper rarely carries the punctuation.
	 * Compare on letters and digits only.
	 */
	private static boolean dialogOptionMatches(String optionText, String wanted)
	{
		return dialogKey(optionText).equals(dialogKey(wanted));
	}

	private static String dialogKey(String text)
	{
		return net.runelite.client.util.Text.removeTags(text)
			.replace('’', '\'')
			.replaceAll("[^\\p{Alnum}]+", "")
			.toLowerCase(Locale.ROOT);
	}

	/** Where the route (and marker) points for this stage — the routable entrance when set. */
	private static WorldPoint errandRoutePoint(StepAnnotation.Errand stage)
	{
		return stage.routeX != null && stage.routeY != null
			? new WorldPoint(stage.routeX, stage.routeY,
				stage.routePlane == null ? 0 : stage.routePlane)
			: new WorldPoint(stage.x, stage.y, stage.plane);
	}

	/**
	 * Where to actually send someone for an errand stage: the NAMED NPC's
	 * real position when he is in the scene, else the recorded tile.
	 *
	 * <p>A coordinate copied out of Quest Helper is where that NPC STOOD.
	 * Plenty of them wander — the owner stood next to Merlin while the
	 * route pointed at a tile twelve tiles away, on the right floor but in
	 * another room, because Merlin had walked off (in play, from an
	 * `::ironwrong` report). We already find and outline these NPCs by
	 * name, so the live position is there for the asking, and a person you
	 * can see beats a tile he used to occupy.
	 *
	 * <p>The recorded tile still governs everything else — it is what the
	 * stage is SATISFIED by, and what to walk toward when he is out of
	 * sight.
	 * Client thread (scene read).
	 */
	private WorldPoint errandRouteTarget(StepAnnotation.Errand stage)
	{
		WorldPoint recorded = errandRoutePoint(stage);
		if (stage.npc == null || stage.routeX != null)
		{
			// An explicit route point is a deliberate override (a surface
			// entrance for an interior leg); never second-guess it.
			return recorded;
		}
		String wanted = stage.npc.trim().toLowerCase(Locale.ROOT);
		for (net.runelite.api.NPC npc : client.getTopLevelWorldView().npcs())
		{
			String name = npc.getName();
			if (name == null || isPet(npc))
			{
				continue;
			}
			String clean = net.runelite.client.util.Text.removeTags(name)
				.replace(' ', ' ').trim().toLowerCase(Locale.ROOT);
			if (clean.equals(wanted))
			{
				return realPoint(npc);
			}
		}
		return recorded;
	}

	/** The quest the current step is about (any state), else null. */
	private Quest stepQuest(Current current)
	{
		GoalDetector.QuestGoal goal = questGoalBySub.get(current.sub.getId());
		if (goal != null)
		{
			return goal.getQuest();
		}
		// An annotation tag first: it is how a step the guide never labels
		// ("Continue Lost tribe…") becomes a quest leg the plugin can see.
		String questName = annotationManager.getQuest(current.sub.getId());
		if (questName == null)
		{
			questName = annotationManager.getQuest(current.step.getId());
		}
		if (questName == null)
		{
			questName = current.step.getMetadata().get("quest");
		}
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

	/** "Take the boat back to Ardy" — arrival needs the gangplank crossed. */
	private static final java.util.regex.Pattern BOAT =
		java.util.regex.Pattern.compile("(?i)\\bboats?\\b");

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
	 * The same five trees as named in the bundled travel distance table,
	 * index for index with SPIRIT_TREES. The trees are origins there as well
	 * as targets, because riding the network means measuring a leg TO a tree
	 * and another FROM one. TravelDistancesTest fails the build if a name
	 * here stops resolving.
	 */
	private static final String[] SPIRIT_TREE_ORIGINS = {
		"Spirit Tree: Tree Gnome Village",
		"Spirit Tree: Gnome Stronghold",
		"Spirit Tree: Battlefield of Khazard",
		"Spirit Tree: Grand Exchange",
		"Spirit Tree: Feldip Hills",
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
		// Gnome Stronghold — both banks. Missing entirely, so a stronghold
		// bank stop routed to the Fishing Guild 150 tiles away. Both are
		// UPSTAIRS: the location pages' map pins are ground-level and drop
		// you at the foot of the staircase, but every gnome banker on the
		// wiki's NPC map sits at plane 1. Aim at the booths so Shortest
		// Path draws the stairs too.
		new WorldPoint(2445, 3425, 1), // Gnome Stronghold south bank (Nieve)
		new WorldPoint(2444, 3484, 1), // Gnome Stronghold north bank (Grand Tree)
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

	/**
	 * Northern edge of the walkable SURFACE. Everything above it is an
	 * area the map parks off to the side — dungeons (surface y + 6400),
	 * the rune essence mine (y≈4830), the Abyss — where a 2D distance to
	 * a surface point means nothing.
	 */
	private static final int SURFACE_MAX_Y = 4000;

	/**
	 * Fewest tiles a suggested teleport must SAVE to be worth making, on
	 * top of the 60% test. A percentage alone approves any short hop.
	 */
	private static final int MIN_TILES_SAVED = 75;

	/**
	 * How far from a router leg's ORIGIN we still treat it as the next thing
	 * to do. Beyond this the leg is behind you (or not yet reachable), and
	 * highlighting its button is noise.
	 */
	private static final int ROUTER_LEG_ORIGIN_RADIUS = 40;

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
	 *
	 * "make your way", "cart" and "carpet" name a journey without using a
	 * movement VERB, so the guide's three such steps ("Make your way to
	 * Wintertodt", "Take the cart to Shilo Village", "Carpet back to
	 * Shantay pass") could not tick by any route at all. Each of those
	 * words was measured against the whole guide before being added here
	 * and matches exactly one step, so this widens nothing else.
	 */
	private static final java.util.regex.Pattern MOVEMENT_WORD = java.util.regex.Pattern.compile(
		"\\b(?:go|walk|run|head|return|travel|enter|exit|climb|cross|move|proceed|sail|ride|fly|swim|tele|teleport|tabs?|charter|cart|carpet)\\b"
			+ "|\\bmake (?:your|my) way\\b",
		java.util.regex.Pattern.CASE_INSENSITIVE);

	/**
	 * Is the route currently pointed at a BANK because the frontier step's
	 * kit is banked? Withdrawing the kit is not "progress" and fires no
	 * event, so without re-checking, the route stays on the bank after the
	 * reason for it has gone.
	 */
	private volatile boolean navRoutedToBank;

	/**
	 * Step id of the last bank stop SUGGESTED while Quest Helper owned
	 * guidance, so we suggest one once per step instead of re-seizing the
	 * route every ten ticks. Deliberately NOT keyed by the bank too: this
	 * picks the nearest one, so walking far enough would otherwise restart
	 * the "once". Survives across navigation passes — see the QH-owns bank
	 * branch.
	 */
	private String lastBankSuggestion;

	/**
	 * The sub tells you to hold a CONVERSATION — "speak to Lady of the
	 * lake", "talk to Oziach", "ask every question".
	 *
	 * Arriving is not talking. Three steps in the guide pair a journey with
	 * a conversation ("Go under the mountain and speak to Lady of the lake
	 * in Taverly"), and the movement half satisfied the arrival gate while
	 * the talk half has no detector at all, so they ticked themselves on
	 * walking up. That is worse than not ticking: the tick advances the
	 * frontier, which takes the NPC outline and the dialogue highlighting
	 * away at the exact moment they were about to be useful (owner,
	 * 2026-08-09, confirmed in the log — the sub completed at ~0 tiles).
	 */
	private static final java.util.regex.Pattern TALK_INSTRUCTION =
		java.util.regex.Pattern.compile("\\b(?:speak|talk|ask)\\b", java.util.regex.Pattern.CASE_INSENSITIVE);

	/**
	 * Leading filler on a step that is otherwise just a destination —
	 * "To Lumby", "Then Varrock east bank".
	 */
	private static final java.util.regex.Pattern DESTINATION_FILLER =
		java.util.regex.Pattern.compile("^(?:the|to|at|in|then|and)\\s+", java.util.regex.Pattern.CASE_INSENSITIVE);

	/**
	 * Is this sub nothing but a destination? The guide writes some travel
	 * steps as a bare place — "Lumby", "Varrock east bank" — which name
	 * where to be without using a movement VERB, so MOVEMENT_WORD cannot
	 * see them and they could not tick by any route.
	 *
	 * Deliberately strict: the WHOLE text has to be the place, so "Kill a
	 * chicken at Fred's farm" is untouched and cannot tick by showing up.
	 * Quest and transport names are excluded by PlaceManager, because they
	 * share the place namespace — without that, the bare steps "Cabin
	 * fever" and "One small favour" would tick by walking past the giver.
	 *
	 * These steps still have to get past annotationItemsCarried, which is
	 * what makes this safe rather than eager: "Lumby" carries a six-item
	 * kit, so it ticks on arriving PREPARED, never on merely passing through.
	 */
	private boolean isBareDestination(String text)
	{
		if (text == null)
		{
			return false;
		}
		String trimmed = DESTINATION_FILLER.matcher(text.trim()).replaceFirst("").trim();
		return !trimmed.isEmpty() && placeManager.isTravelDestination(trimmed);
	}

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
	 * Baseline key recording that a sub's annotated SHOPPING LIST was seen
	 * complete. Holding the goods is reversible state proving a one-way
	 * fact — "Buy 1 pack of normal compost and all farming tools, store
	 * everything in leprechaun" ends by putting the tools INTO the
	 * leprechaun, which no container we can read holds, so a gate that
	 * only asked "are they in hand NOW" would slam shut on the deposit and
	 * wedge the very step it fixes.
	 *
	 * Stored beside the acquisition baselines rather than in a session Set
	 * because it needs their two behaviours exactly: it must survive a
	 * client restart (P0's persisted-baseline lesson), and unticking the
	 * sub must clear it (clearAcquisitionBaselines drops every "subId|..."
	 * key, so this rides along). No item is named "@purchase-list", so the
	 * reserved key cannot collide with a real goal's baseline.
	 */
	private static final String PURCHASE_LIST_KEY = "@purchase-list";

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

	/**
	 * Tick and tile of the last gangplank crossing — where the player
	 * stood when they clicked it, which is the side they were LEAVING.
	 * See ashoreOfBoat: boarding crosses a plank too, so the tile is what
	 * separates "boarded at the far end" from "walked off here".
	 */
	private int lastGangplankTick = -1000;
	private WorldPoint lastGangplankPoint;

	/** Text-detected "get N items" / "start quest X" goals (see GoalDetector). */
	private GoalDetector.Goals goals;

	/**
	 * Item goals grouped by sub-step id. A sub-step like "Buy 1250 nature
	 * runes and 700 law runes" has two goals and completes only when BOTH
	 * counts are met.
	 */
	private final Map<String, List<GoalDetector.ItemGoal>> itemGoalsBySub = new LinkedHashMap<>();

	// Acquisition baselines ("subId|item" -> carried count when a "buy X"
	// sub first became current) now live in ProgressManager, persisted per
	// profile: session-only state meant a client restart re-based them with
	// the goods already bought, wedging the step green-but-unticked.

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
		// Before ANY load below: decides whether those loads read the jar
		// or a folder on disk.
		DataFiles.setFolder(config.dataFolder());
		annotationManager.load();
		placeManager.load();
		loadMinigameLandings();
		loadQuestNpcs();
		teleportItemIndex = com.ironscape.travel.TeleportItems.load(gson);
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
		minigameTeleportOverlay.setHomeTeleportSupplier(
			() -> homeTeleportHint || routeHomeTeleportHint);
		minigameTeleportOverlay.setSpellTeleportSupplier(() -> activeSpellTeleport);
		minigameTeleportOverlay.setEquippedTeleportSupplier(() -> activeEquippedTeleport);
		// NOTE: this label must come from the item the router actually picked.
		// A second setter hardcoding "Chronicle" used to sit here and silently
		// clobbered this one, so every worn teleport item — an Ardougne cloak,
		// say — was labelled Chronicle on screen (owner, in play, wave 27).
		minigameTeleportOverlay.setEquippedTeleportLabelSupplier(() -> equippedTeleportLabel);
		overlayManager.add(minigameTeleportOverlay);
		travelMenuOverlay.setWordsSupplier(() -> travelMenuWords);
		travelMenuOverlay.setGroupSupplier(() -> travelMenuGroup);
		overlayManager.add(travelMenuOverlay);
		stepOverlay.setModelSupplier(() -> stepOverlayModel);
		overlayManager.add(stepOverlay);
		questHandoffOverlay.setModelSupplier(() -> handoffModel);
		overlayManager.add(questHandoffOverlay);
		questStartMarkerOverlay.setTargetSupplier(() -> questStartMarker);
		overlayManager.add(questStartMarkerOverlay);
		npcTargetOverlay.setNamesSupplier(() -> npcTargetNames);
		npcTargetOverlay.setIndexesSupplier(() -> npcTargetIndexes);
		npcTargetOverlay.setQuestIconSupplier(() -> currentSubIsQuest);
		npcTargetOverlay.setItemIconSupplier(() -> currentSubItemIcon);
		npcTargetOverlay.setPerNpcIconSupplier(() -> npcItemIcons);
		overlayManager.add(npcTargetOverlay);
		targetTileOverlay.setTargetSupplier(() -> targetTileMarker);
		targetTileOverlay.setLabelSupplier(() -> targetTileLabel);
		targetTileOverlay.setGroundItemsSupplier(() -> groundItemTargets);
		overlayManager.add(targetTileOverlay);
		objectTargetOverlay.setObjectsSupplier(() -> objectTargets);
		objectTargetOverlay.setLabelSupplier(() -> objectActionsLabel);
		objectTargetOverlay.setItemIconSupplier(() -> currentSubItemIcon);
		overlayManager.add(objectTargetOverlay);
		emoteHintOverlay.setSpriteSupplier(() -> activeEmoteSprite);
		emoteHintOverlay.setNameSupplier(() -> activeEmoteName);
		emoteHintOverlay.setColorSupplier(config::hintColour);
		overlayManager.add(emoteHintOverlay);
		inventoryItemHintOverlay.setItemIdsSupplier(() -> inventoryHintItemIds);
		overlayManager.add(inventoryItemHintOverlay);
		teleportItemHintOverlay.setEntrySupplier(() -> activeTeleportItem);
		overlayManager.add(teleportItemHintOverlay);
		shopItemHintOverlay.setItemNamesSupplier(() -> shopHintItemNames);
		overlayManager.add(shopItemHintOverlay);

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
			List<StepRequirement> requirements = requirementsFor(subId);
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
		panel.setErrandStagesSupplier(errandStagesBySub::get);
		panel.setErrandChecklistSupplier(errandChecklistBySub::get);
		// Say so when a step cannot complete itself. Deliberately the SAME
		// three tests the completion loop makes, so the label can never
		// disagree with the behaviour it describes.
		panel.setManualOnlySupplier(subId -> {
			if (hasAnyGoal(subId))
			{
				return false;
			}
			List<StepRequirement> requirements = subRequirements.get(subId);
			if (requirements != null && !requirements.isEmpty())
			{
				return false;
			}
			if (!annotationManager.getErrands(subId).isEmpty()
				|| !annotationManager.getErrands(subId.split(":")[0]).isEmpty())
			{
				return false;
			}
			// ARRIVAL is the completion path with no goal behind it, so
			// "no goal" is NOT the same as "cannot tick" — "Run south to
			// Port sarim" has no detector at all and still completes when
			// you get there. It needs a movement instruction AND somewhere
			// the text or the 📍 tag can resolve, which is the same pair
			// currentSubSatisfied tests.
			Guide active = guideFor(activeVariant);
			GuideStep owner = active == null ? null : active.getStepsById().get(subId.split(":")[0]);
			SubStep sub = owner == null ? null : findSub(owner, subId);
			if (sub == null)
			{
				return false;             // unknown: say nothing rather than guess
			}
			String text = sub.getPlainText();
			if (MOVEMENT_WORD.matcher(text).find()
				|| CHARTER.matcher(text).find() || SPIRIT_TREE.matcher(text).find()
				|| isBareDestination(text))
			{
				String location = owner.getMetadata().get("location");
				boolean somewhere = placeManager.lastPlaceIn(text) != null
					|| placeManager.firstPlaceIn(text) != null
					|| (location != null && placeManager.getLoose(location) != null)
					|| annotationManager.getTarget(subId) != null
					|| annotationManager.getTarget(owner.getId()) != null;
				if (somewhere)
				{
					return false;         // arrival can finish this one
				}
			}
			return true;
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
				List<StepRequirement> requirements = requirementsFor(subId);
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
		overlayManager.remove(questHandoffOverlay);
		overlayManager.remove(questStartMarkerOverlay);
		overlayManager.remove(npcTargetOverlay);
		overlayManager.remove(targetTileOverlay);
		overlayManager.remove(objectTargetOverlay);
		overlayManager.remove(inventoryItemHintOverlay);
		overlayManager.remove(teleportItemHintOverlay);
		overlayManager.remove(shopItemHintOverlay);
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
	 * {@code ::ironreload} — re-read the data files and rebuild everything
	 * derived from them, without a rebuild or a client restart.
	 *
	 * <p>Only useful with a data folder configured (see {@link DataFiles});
	 * without one this re-reads the same jar and changes nothing, which the
	 * reply says so nobody is left wondering why their edit did not take.
	 *
	 * <p>Runs on the client thread, which is where the command arrives, so
	 * the caches it rebuilds are never half-written under a reader.
	 * Progress and route position are untouched: those live in the config
	 * profile, not in these files.
	 */
	@Subscribe
	public void onCommandExecuted(net.runelite.api.events.CommandExecuted event)
	{
		if ("ironwrong".equalsIgnoreCase(event.getCommand()))
		{
			writeProblemReport();
			return;
		}
		if (!"ironreload".equalsIgnoreCase(event.getCommand()))
		{
			return;
		}
		DataFiles.setFolder(config.dataFolder());
		annotationManager.load();
		placeManager.load();
		loadMinigameLandings();
		loadQuestNpcs();
		teleportItemIndex = com.ironscape.travel.TeleportItems.load(gson);
		loadGuideState();
		// Derived per-tick caches that outlive a reload would otherwise
		// describe the OLD data until the next natural rebuild.
		checkpointMetBySub.clear();
		SwingUtilities.invokeLater(() ->
		{
			if (panel != null)
			{
				panel.refresh();
			}
		});
		String where = DataFiles.overriding()
			? "data folder" : "the bundled files (no data folder set)";
		log.info("::ironreload — reloaded from {}", where);
		client.addChatMessage(ChatMessageType.CONSOLE, "",
			"IRONSCAPE: reloaded guide data from " + where + ".", null);
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

	/** Previous game state, logged per transition for diagnosis. */
	private GameState lastGameState;

	/** Set on LOGGING_IN; the next LOGGED_IN is a real (re)connect. */
	private boolean sawLoggingIn;

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		// LOGGED_IN fires after EVERY loading screen, not just real logins.
		// A teleport is LOADING -> LOGGED_IN: resetting the login grace
		// there ate the very tick the position jump would be detected on —
		// travel subs never ticked across a loading screen. Only a real
		// (re)connect needs the grace window and fresh xp baselines.
		// One line per transition: the real-login detection has burned us
		// with silent misfires (login resume never arming) — the log
		// settles which sequence this client actually fires.
		log.info("game state: {} -> {}", lastGameState, event.getGameState());
		// A REAL (re)connect passes through LOGGING_IN; a teleport or
		// region load is just LOADING -> LOGGED_IN. The old lastGameState
		// != LOADING test never fired on FRESH logins — the diagnosis log
		// proved the sequence is LOGGING_IN -> LOADING -> LOGGED_IN, so
		// the final hop looks exactly like a teleport.
		if (event.getGameState() == GameState.LOGGING_IN)
		{
			sawLoggingIn = true;
		}
		if (event.getGameState() == GameState.LOGGED_IN && sawLoggingIn)
		{
			sawLoggingIn = false;
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
		deathPoint = realPoint(me);
		log.info("player died at {} — routing to the gravestone until reached", deathPoint);
		client.addChatMessage(ChatMessageType.CONSOLE, "",
			"IRONSCAPE: you died - the route now points at your gravestone.", null);
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
			// DEPOSITS. An item you deposit while the filter is on used to
			// stay an unclickable ghost until the filter was toggled off and
			// on: we only ever draw a REAL bank widget for something we can
			// find populated in the item container, and right after a
			// deposit there is none to find.
			//
			// Two earlier attempts tried to force the CLIENT to rebuild here
			// (bankSearch.layoutBank(), then reset(true)+layoutBank()) and
			// both did nothing. They were aiming at the wrong thing: the
			// session log shows a pass DOES run on a deposit — the shape
			// simply came out byte-identical, and the shape line only prints
			// on change, so it looked like no pass at all. What that pass
			// reads is a container whose widgets have not caught up yet.
			//
			// So: no client rebuild. Re-run OUR layout for the next few
			// ticks, and the pass that lands after the widgets repopulate
			// turns the ghosts back into real, withdrawable ones. Cheap —
			// composition is frozen, only counts and widget joins redo.
			bankRelayoutTicks = BANK_RELAYOUT_TICKS;
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
					"IRONSCAPE: reopened: " + text
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
		// Chat options opening: recolor the stage's dialog choices right
		// away (one tick late looks laggy); deferred a frame so the
		// option children exist.
		if (event.getGroupId()
			== net.runelite.api.gameval.InterfaceID.Chatmenu.UNIVERSE >> 16)
		{
			clientThread.invokeLater(this::highlightStageDialog);
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
				// Crossing a gangplank is the moment a boat trip actually
				// ends — see ashoreOfBoat. Recorded WITH the tile the click
				// was made from, since the same object is crossed at both
				// ends of the journey.
				String target = net.runelite.client.util.Text.removeTags(
					event.getMenuTarget() == null ? "" : event.getMenuTarget());
				if (target.toLowerCase(Locale.ROOT).contains("gangplank"))
				{
					lastGangplankTick = client.getTickCount();
					lastGangplankPoint = playerPoint();
				}
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

		// FINISHBUILDING, not BANKMAIN_BUILD — Quest Helper's hook, and the
		// reason its bank tab is rock solid. BUILD fires while the grid is
		// still being assembled, so we kept laying out over half-populated
		// containers (icons blank for a frame, real widgets and ghosts
		// swapping places as items were withdrawn). FINISHBUILDING fires
		// when the bank is done, and the layout is then deferred to the END
		// OF THE TICK so nothing runs inside the client's own script — the
		// same deferral this file already needed for UPDATE_SCROLLBAR after
		// two hard freezes.
		if (event.getScriptId() == net.runelite.api.ScriptID.BANKMAIN_FINISHBUILDING)
		{
			scheduleBankFilterPass("build");
		}
	}

	/**
	 * Lay the filter view out at the end of this tick.
	 *
	 * Filter view active (button, or a keyword typed by hand): the native
	 * grid is blanked (see bankSearchFilter) and EVERY upcoming step renders
	 * as its own section — all of its items, have/need counts, Quest
	 * Helper-style.
	 */
	private void scheduleBankFilterPass(String trigger)
	{
		boolean filterView = bankFilterActive();
		List<com.ironscape.items.BankMissingSection.Section> sections = new ArrayList<>();
		if (filterView)
		{
			refreshUpcomingNeeds();
			sections = upcomingSections;
		}
		else
		{
			// Filter off: next activation re-anchors on the live frontier
			// and rebuilds the sections from scratch.
			frozenFilterStepIds = null;
			frozenSections = null;
		}
		List<com.ironscape.items.BankMissingSection.Section> pass = sections;
		clientThread.invokeAtTickEnd(() -> bankMissingSection.update(filterView, pass, trigger));
	}

	/**
	 * Ticks left to re-lay the filter view out after the bank container
	 * changed. See the deposit note in onItemContainerChanged.
	 */
	private int bankRelayoutTicks;

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
		if (handoffBannerTicks > 0 && --handoffBannerTicks == 0)
		{
			handoffModel = null;
		}
		// The bank container changed recently (a deposit, most of all): keep
		// re-laying the filter view out until the client has given the moved
		// items their widgets back. Costs nothing while the bank is shut —
		// update() no-ops without the ITEMS widget.
		if (bankRelayoutTicks > 0)
		{
			bankRelayoutTicks--;
			if (client.getWidget(net.runelite.api.gameval.InterfaceID.Bankmain.ITEMS) != null)
			{
				scheduleBankFilterPass("bank-change");
			}
			// NO forced rebuild here. One was tried and REMOVED: play-testing
			// showed layoutBank() leaving the widget count exactly where it
			// was (291 of 330 items), because the re-run asked our own
			// bankSearchFilter callback again and got the same "hide" answer
			// that starved the widgets in the first place. That answer is now
			// "show" (see onScriptCallbackEvent), which fixes the cause, so
			// there is nothing left for a rebuild to repair.
		}
		// Death retrieval: while a gravestone waits, keep the route pinned
		// on it (re-post every 10 ticks — the respawn's loading screen and
		// any teleport en route can drop a Shortest Path route). Getting
		// close clears it and normal frontier routing resumes.
		if (deathPoint != null)
		{
			WorldPoint me = playerPoint();
			if (me != null && me.getPlane() == deathPoint.getPlane()
				&& me.distanceTo2D(deathPoint) <= 8)
			{
				deathPoint = null;
				client.addChatMessage(ChatMessageType.CONSOLE, "",
					"IRONSCAPE: gravestone reached - routing back to the guide.", null);
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
				// Before routing: if two pathers are on, whatever we draw is
				// about to appear twice, and saying so first explains it.
				warnIfTwoPathersActive();
				// The router's config override does not survive a client
				// restart, and on a Quest Helper step we never post a route
				// that would set it — so switch reporting on here, once,
				// whether or not we end up drawing anything ourselves.
				enableRouteReporting();
				logNavDecision("login: resuming route to the next target");
				maybeNavigateToNext();
				// AFTER the nav decision, deliberately: if that decision was
				// the stand-down, it has already said this and announceStandDown
				// will have claimed the step id.
				announceMidQuestResume();
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
			// Real-world tiles on BOTH sides: entering an instance is a genuine
			// teleport either way, but walking around inside one must not read
			// as a 20-tile jump every tick.
			WorldPoint here = realPoint(player);
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
				//
				// The router computes its route FROM THE PLAYER, so a jump
				// invalidates it even when the destination is identical. Wave
				// 26 stopped re-posting an unchanged target (it was stomping
				// Quest Helper every six seconds) and that guard silently ate
				// this re-post too: climbing out of the Temple of Ikov left
				// GPS still showing the route it had worked out from INSIDE
				// the dungeon — an Ardougne cloak teleport — with its own
				// panel reading "Off route" (owner, in play, wave 27).
				// Forgetting the last target re-opens the gate for this one
				// post only; the timer-driven re-posting stays gone.
				lastPostedTarget = null;
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
		// Quest.getState runs a CLIENTSCRIPT, and this scan covers every
		// quest the guide mentions (~100). Run per-tick it was enough
		// script-engine load to break Quest Helper's pathing, with the
		// player having to hit "reload quest" to recover (wave 7).
		//
		// Moving it to every 5th tick fixed the average but kept the SPIKE:
		// ~100 scripts landing on one tick, five times a second's worth of
		// work in a single frame, and the symptom came back on a quest where
		// QH is pathing hard. So the pass is now AMORTISED — a fifth of the
		// quests each tick, round-robin — which completes a full sweep just
		// as often while never running more than ~20 scripts in one tick.
		//
		// The cursor rides over a stable snapshot of the key order. A quest
		// arriving mid-sweep only shifts when it is first seen, and a
		// missed transition is picked up on the next pass: `previous` is
		// null until a quest has been observed once, so nothing can arm
		// jumped-ahead spuriously.
		if (current != null && loginGraceTicks == 0 && !minStepIndexByQuest.isEmpty())
		{
			int frontierIndex = current.step.getGlobalIndex();
			List<Quest> order = new ArrayList<>(minStepIndexByQuest.keySet());
			int perTick = Math.max(1, (order.size() + 4) / 5);
			if (questScanCursor >= order.size())
			{
				questScanCursor = 0;
			}
			for (int scanned = 0; scanned < perTick; scanned++)
			{
				Quest quest = order.get(questScanCursor);
				questScanCursor = (questScanCursor + 1) % order.size();
				QuestState state = quest.getState(client);
				QuestState previous = lastQuestState.put(quest, state);
				if (previous == QuestState.NOT_STARTED && state == QuestState.IN_PROGRESS
					&& minStepIndexByQuest.getOrDefault(quest, 0) > frontierIndex)
				{
					jumpedAheadQuest = quest;
					log.info("jumped-ahead ON ({} started live, first guide step ahead of frontier)",
						quest.getName());
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
		routeHomeTeleportHint = false;
		// Which branch below decided the hint — logged on change, because
		// on screen every source looks identical (see logHintDecision).
		String hintReason;
		if (!config.showTeleportHints())
		{
			activeMinigameTarget = null;
			hintReason = "hints disabled in config";
		}
		else if (clickedMinigameTicks > 0)
		{
			activeMinigameTarget = clickedMinigameTarget;
			hintReason = "clicked place link '" + clickedMinigameTarget
				+ "' (" + clickedMinigameTicks + " ticks left)";
		}
		else
		{
			activeMinigameTarget = current == null ? null : minigameBySub.get(current.sub.getId());
			hintReason = activeMinigameTarget != null
				? "sub names minigame teleport '" + activeMinigameTarget + "'"
				: "none";
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
					// Nav has HANDED OFF to Quest Helper on this step, so the
					// hint must stand down with it. It never asked: it called
					// targetFor() straight, computing a first leg toward a
					// destination the router had already abandoned. In play
					// (2026-08-10, Temple of the Eye) nav logged the stand-down
					// at 21:44:25 and the hint went on offering a Lumbridge home
					// teleport at 21:47, 21:48, 21:49 and 21:51 — on top of the
					// route QH was drawing correctly.
					//
					// The same two exceptions nav makes are made here, in the
					// same order, because they are already ahead of this in the
					// chain below: a gravestone outranks everything (wave 7) and
					// an active errand deliberately outranks QH (waves 4/8).
					//
					// Deliberately quiet rather than clever: when the kit is
					// banked nav still suggests a bank, and this could aim there
					// too, but a hint pointing somewhere while QH guides is the
					// exact complaint. Silence is never wrong here.
					boolean handedOff = questHelperOwnsGuidance() && questHelperInstalled();
					WorldPoint routeTarget = chainHolding ? null
						: deathPoint != null ? deathPoint
						: hintErrand != null
							? errandRoutePoint(hintErrand)
						: handedOff ? null
							: targetFor(current.step, current.sub);
					// A sub that names its own transport gets no alternative
					// first-leg suggestions — the guide already said how to
					// travel. Death routes and errand legs prescribe nothing.
					boolean prescribed = deathPoint == null && hintErrand == null
						&& PRESCRIBED_TRANSPORT.matcher(
							current.sub.getPlainText().toLowerCase(Locale.ROOT)).find();
					// ... but if it prescribes a spell we can actually point
					// at ("Use mind bomb and camelot tele"), highlight THAT
					// one. Standing down entirely left the player in the
					// essence mine with no prompt at all (owner,
					// 2026-08-08). No distance test: the guide named the
					// spell, so where you are is irrelevant — which also
					// gets past the surface-band guard that would otherwise
					// suppress every hint from inside a dungeon.
					TeleportSpell named = prescribed
						? prescribedSpell(current.sub.getPlainText()) : null;
					FirstLeg leg = prescribed
						? (named == null ? null : new FirstLeg(null, named, false, null))
						: firstLegTowards(routeTarget, !minigameTeleportOnCooldown());
					hintReason = prescribed
						? (named == null
							? "none — sub prescribes its own transport"
							: "prescribed spell " + named.name
								+ (castable(named) ? "" : " (not castable yet — boost/runes)"))
						: chainHolding
							? "none — errand chain complete, holding"
						: handedOff
							? "none — Quest Helper owns this step's route"
						: leg == null
							? "none — no first leg beats walking to " + routeTarget
								+ metricNote(routeTarget)
							: "route-aware first leg toward " + routeTarget
								+ metricNote(routeTarget)
								+ " [leg " + leg.legDistance + " had to beat " + leg.mustBeat + "]";
					if (leg != null && leg.minigame != null)
					{
						String towardsKey = leg.minigame.toLowerCase(Locale.ROOT).replace('’', '\'');
						java.util.Set<Integer> confirmed = minigameRegions.get(towardsKey);
						WorldPoint me = playerPoint();
						boolean there = towardsKey.equals(minigamePresence)
							|| (me != null && confirmed != null
								&& confirmed.contains(me.getRegionID()));
						if (!there)
						{
							activeMinigameTarget = leg.minigame;
						}
						else
						{
							hintReason = "none — already present at " + leg.minigame;
						}
					}
					activeSpellTeleport = activeMinigameTarget == null
						&& leg != null && leg.spell != null ? leg.spell.component : -1;
					routeHomeTeleportHint = activeMinigameTarget == null
						&& leg != null && leg.home;
					activeTeleportItem = activeMinigameTarget == null
						&& leg != null ? leg.item : null;
					// The router's own pick OUTRANKS ours, because it ranked
					// the same choice better than we can: real walked
					// routes, every transport type, the player's actual
					// unlocks, and the player's own cost settings.
					//
					// EXCEPT where the guide named the transport itself.
					// "Teleport to Camelot" means the Camelot teleport, and
					// letting the router overrule it highlighted VARROCK
					// while the reason line still read "prescribed spell
					// Camelot Teleport" (owner, in play). The router is
					// answering "what is quickest from here", which is a
					// different question from "what does this step say to
					// do" — and on a prescribed step the step wins.
					if (!prescribed)
					{
						applyRouterChoice();
					}
					if (activeTeleportItem != null)
					{
						// Name the OPTION, not just the item: an Ardougne
						// cloak has five destinations and pointing at the
						// cloak without saying which one is half an answer.
						hintReason += " via " + activeTeleportItem.getDisplay();
					}
				}
				else
				{
					activeSpellTeleport = -1;
					routeHomeTeleportHint = false;
					activeTeleportItem = null;
					// Even with no first leg of our OWN — including while we
					// have stood down for Quest Helper — highlight whatever
					// the router picked. This is not a rival opinion: it IS
					// the leg on screen, made clickable, which is the one
					// thing the router cannot do for itself. Standing fully
					// silent was right only while our hint proposed its own
					// destination (owner, wave 23); since we can read the
					// router's choice, silence just hides the button.
					if (applyRouterChoice())
					{
						hintReason = "following the route's own choice";
						if (activeTeleportItem != null)
						{
							hintReason += " via " + activeTeleportItem.getDisplay();
						}
					}
				}
				if (key != null && GROUPING_MINIGAMES.contains(key))
				{
					WorldPoint area = placeManager.getLoose(location);
					WorldPoint me = playerPoint();
					if (area != null && me != null)
					{
						// TEMPLATE region: a minigame interior is usually
						// instanced, and a dynamic region id is different every
						// visit — learning one would teach us nothing reusable.
						int region = me.getRegionID();
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
						boolean near = me.getPlane() == area.getPlane()
							&& me.distanceTo2D(area) <= 100;
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
							hintReason = "step 📍 area '" + location
								+ "' is a Grouping destination and you're not there";
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
		if (homeTeleportHint)
		{
			hintReason = "sub text says home teleport";
		}
		// The forensic line. NOTE what this does NOT cover: a teleport
		// marker drawn on a world TILE is Shortest Path's own transport
		// suggestion for the route it computed — a different plugin's
		// opinion, which can disagree with ours.
		logHintDecision(activeMinigameTarget != null
			? "minigame '" + activeMinigameTarget + "' — " + hintReason
			: activeSpellTeleport != -1
				? "spell widget " + activeSpellTeleport + " — " + hintReason
				: routeHomeTeleportHint || homeTeleportHint
					? "home teleport — " + hintReason
					: hintReason);
		// "Chronicle tele": the teleport lives on the Chronicle, which is
		// usually WORN (shield slot) — highlight the equipment click path.
		// But it works just as well from the inventory, and pointing at an
		// empty equipment tab while it sits in your bag is worse than not
		// pointing at all (owner). Resolve the container at render time:
		// worn -> the equipment slot, carried -> the inventory outline
		// below picks it up instead.
		boolean chronicleSub = config.showTeleportHints() && current != null
			&& CHRONICLE_TELE.matcher(current.sub.getPlainText()).find();
		activeEquippedTeleport = chronicleSub
			&& itemTracker.wornCountOf("chronicle") > 0
			? net.runelite.api.gameval.InterfaceID.Wornitems.SLOT5 : -1;
		// A chosen teleport item that is WORN needs the same signpost. Our
		// outline draws on the equipment panel, which is a screen you are
		// usually not looking at — so it was highlighting a cloak nobody
		// could see (owner, in play: "its shown this overlay, just not the
		// usual one on the equipment tab"). Pointing at the tab is what
		// turns a correct answer into a visible one.
		if (activeEquippedTeleport == -1 && activeTeleportItem != null)
		{
			int slot = wornSlotComponentFor(activeTeleportItem);
			if (slot != -1)
			{
				activeEquippedTeleport = slot;
				equippedTeleportLabel = activeTeleportItem.getDisplay();
			}
		}
		if (activeEquippedTeleport == -1 || chronicleSub)
		{
			equippedTeleportLabel = null;
		}

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
			// The stop the step MEANS but never names. "Spirit tree to ardy"
			// + 📍 "Ardougne" share no word with the menu's "Battlefield of
			// Khazard", so the overlay had nothing to match and highlighted
			// nothing — the SS-07-works / SS-08-doesn't split.
			for (String annotationId : new String[]{current.sub.getId(), current.step.getId()})
			{
				String via = annotationManager.getTravelVia(annotationId);
				if (via != null)
				{
					hay.append(' ').append(via);
					break;
				}
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
		// Which quest that marker belongs to, so the nomination below can
		// ask whether a candidate is even in that quest's cast.
		String markerQuest = null;
		if (config.showQuestStartMarker())
		{
			if (clickedQuestTicks > 0 && clickedQuest != null)
			{
				marker = placeManager.get(clickedQuest.getName());
				markerQuest = clickedQuest.getName();
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
					markerQuest = questGoal.getQuest().getName();
				}
			}
		}
		// The quest's cast, by ID (seed-quest-npcs.mjs, from Quest Helper).
		// Empty when we have no index for it, which means "no opinion" —
		// the old nearest-to-marker behaviour then stands unchanged.
		java.util.Set<Integer> markerCast = questNpcIdsFor(markerQuest);
		questStartMarker = marker;

		// Exact-spot marker: if the current sub (or its single-action
		// step) has an annotated ⌖ target, highlight that tile in the
		// world — dig spots, item spawns, and other precise locations.
		WorldPoint spot = null;
		// Does that ⌖ mark a PERSON? A pin on a door or a dig spot must not
		// nominate the nearest NPC to it (see Target.npc).
		boolean spotNominates = true;
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
				spotNominates = !Boolean.FALSE.equals(target.npc);
			}
		}

		// On-the-way errand: an annotated side pickup ("get Glarial's
		// pebble during Tree Gnome Village") keeps OUR guidance alive from
		// quest start until the item is in the bag — mid-quest QH knows
		// nothing about it, and post-quest it's the step's only signal.
		StepAnnotation.Errand errand = activeErrand();
		// Identity includes the ROUTE point, not just the item or the
		// satisfaction tile: the two staircase legs of one keep are told
		// apart by which staircase they send you to, and nothing else.
		String errandStage = errand == null ? null
			: (errand.item != null ? errand.item : "wp:" + errand.x + "," + errand.y)
				+ "@" + errandRoutePoint(errand);
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
			WorldPoint here = realPoint(player);
			if (here.getPlane() == errandPoint.getPlane()
				&& here.distanceTo2D(errandPoint) <= 30
				&& errandReminded.add(current.step.getId() + "|" + errand.item))
			{
				client.addChatMessage(ChatMessageType.CONSOLE, "",
					"IRONSCAPE: on-the-way pickup - "
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
			: (spotNominates ? spot : null);
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
				// A PET is never the answer. It stands right beside you, so
				// on a step that names nobody the nearest-to-anchor fallback
				// crowns it — the owner's cat wore the Jungle Potion quest
				// icon. Both tests earn their place: getFollower is your own
				// pet wherever it stands, isFollower covers other people's,
				// which are equally never a target.
				if (isPet(npc))
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
				// The game's article is not the guide's: the menu says "The
				// Lady of the Lake" and the step says "Lady of the lake", so
				// the full name matched nothing and she was never outlined
				// (owner, 2026-08-09). Try the bare name too — the same
				// mismatch the conversation detector hit on this NPC.
				String bare = clean.startsWith("the ") ? clean.substring(4) : clean;
				if (!errandOnly)
				{
					List<String> forms = new ArrayList<>();
					forms.addAll(java.util.Arrays.asList(pluralVariants(clean)));
					if (!bare.equals(clean))
					{
						forms.addAll(java.util.Arrays.asList(pluralVariants(bare)));
					}
					for (String variant : forms)
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
				// Real-world tile: the marker and the shop anchor are annotation
				// coordinates, so the NPC has to be read in the same map.
				WorldPoint npcPoint = marker != null || shopAnchor != null
					? realPoint(npc) : null;
				if (marker != null
					&& npcPoint.getPlane() == marker.getPlane()
					// If we know the quest's cast, the nominee has to be IN
					// it. Nearest-to-the-pin alone has crowned rats, a
					// Market Guard, a Master Farmer and the owner's cat —
					// each patched separately, because the fallback had
					// nothing to check itself against. By ID, so none of the
					// name traps (articles, plurals, a name inside a place
					// name) apply.
					&& (markerCast.isEmpty() || markerCast.contains(npc.getId())))
				{
					int distance = npcPoint.distanceTo2D(marker);
					if (distance <= 4 && distance < markerBest)
					{
						markerBest = distance;
						nearestToMarker = npc.getIndex();
					}
				}
				if (shopAnchor != null
					&& npcPoint.getPlane() == shopAnchor.getPlane())
				{
					int distance = npcPoint.distanceTo2D(shopAnchor);
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
			// An errand stage that NAMES its NPC outlines them by name —
			// named beats nearest, so Aggie stays lit even mid-wander
			// while the bystander on her tile stays dark.
			if (errand != null && errand.npc != null)
			{
				npcNames.add(errand.npc.toLowerCase(Locale.ROOT));
			}
			// Item-source VENDORS join the same way ("teleports" names
			// Diango): the seller outlines by name, and the nearest-to-pin
			// nominee never gets a vote (a Market Guard wore the card icon).
			if (!errandOnly)
			{
				List<GoalDetector.ItemGoal> vendorGoals = itemGoalsBySub.get(current.sub.getId());
				if (vendorGoals != null)
				{
					for (GoalDetector.ItemGoal goal : vendorGoals)
					{
						if (itemTracker.countOf(goal.getItemName()) >= goal.getQuantity())
						{
							continue;
						}
						String vendor = placeManager.sourceVendor(goal.getItemName());
						if (vendor != null)
						{
							npcNames.add(vendor.toLowerCase(Locale.ROOT));
						}
					}
				}
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
				&& objectGrindNames(subText).isEmpty()
				&& !errandPickupInScene(current, errand))
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
		java.util.Map<String, Integer> perNpcIcons = new java.util.HashMap<>();
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
						// One step, several shops: "Buy candle, 2 fishing
						// rods, lobster pot" in Catherby is a candle maker
						// AND a fishing shop, so a single shared icon hung
						// a fishing rod over the candle maker (owner,
						// 2026-08-08). Where item_sources names the vendor,
						// each NPC wears the item THEY sell.
						int goalIcon = itemTracker.iconIdFor(goal.getItemName());
						String vendor = placeManager.sourceVendor(goal.getItemName());
						if (vendor != null && goalIcon > 0)
						{
							perNpcIcons.putIfAbsent(
								vendor.toLowerCase(Locale.ROOT), goalIcon);
						}
						if (wantedIcon == -1)
						{
							wantedIcon = goalIcon;
						}
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
		// A stage that yields no item can still say what to PICTURE.
		else if (errand != null && errand.icon != null)
		{
			int named = itemTracker.iconIdFor(errand.icon);
			if (named > 0)
			{
				wantedIcon = named;
			}
		}
		// A diary/quest checkpoint stage yields nothing, so there is no item
		// to float — and inheriting the step's goal actively lies about the
		// job in front of you (Boots of lightness hovering over the Seers'
		// church organ). Better to show nothing than the wrong thing.
		// Waypoints keep the inherited icon: on those the step's goal really
		// is still what you are walking towards.
		else if (errand != null && errand.item == null
			&& (errand.bit != null || errand.varp != null || errand.varbit != null))
		{
			wantedIcon = -1;
		}
		currentSubItemIcon = wantedIcon;
		npcItemIcons = perNpcIcons;

		// An emote the step asks for ("Perform the Goblin Bow emote next to
		// Mistag"). Sub key first, then the step, same as every other
		// annotation lookup.
		String emote = null;
		if (current != null)
		{
			emote = annotationManager.getEmote(current.sub.getId());
			if (emote == null)
			{
				emote = annotationManager.getEmote(current.step.getId());
			}
		}
		activeEmoteName = emote;
		activeEmoteSprite = com.ironscape.overlay.EmoteHintOverlay.spriteFor(emote);
		if (emote != null && activeEmoteSprite < 0)
		{
			// A name nothing can match would just draw nothing, silently.
			log.warn("emote '{}' on step {} is not a name we know — no hint will show",
				emote, current.step.getId());
		}

		// Ground items the current sub wants picked up ("Pick up 2 iron
		// bars...", item spawns): highlight their tiles, QH-style.
		groundItemTargets = config.showGroundItemMarkers() && current != null
			? findWantedGroundItems(current, errand)
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
		if (config.showInventoryHints() && current != null)
		{
			// Also refreshes shopHintItemNames — see findStepInventoryItems.
			inventoryHintItemIds = findStepInventoryItems(current);
		}
		else
		{
			inventoryHintItemIds = java.util.Collections.emptySet();
			shopHintItemNames = java.util.Collections.emptySet();
		}
		// Chat menus rebuild their option children WITHOUT reloading the
		// widget group — the widget-load hook alone missed every rebuilt
		// menu (owner: "options not showing"). Reapply per tick; cheap.
		highlightStageDialog();
		detectConversation();
		// A bank stop is the one route whose REASON can disappear without
		// any event: the kit comes out of the bank and nothing tells the
		// router. Same 10-tick cadence the errand re-post uses, and only
		// while a bank is actually why we routed, so it costs nothing on
		// every other step.
		if (navRoutedToBank && tickCounter % 10 == 0)
		{
			maybeNavigateToNext();
		}
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
		// SHOW, not hide — and this is the deposit bug's root cause.
		//
		// We used to answer 0 ("hide") for every slot, on the reasoning that
		// the native grid should be blank. But the bank's own build script
		// treats this answer as permission to lay the slot out AT ALL:
		//
		//   filtertest:
		//     invoke 279          ; ~bankmain_filteritem -> this callback
		//     if_icmpne LABEL972  ; answer != 1 -> skip the slot entirely
		//   LABEL929:
		//     cc_sethide / cc_setobject / cc_setposition
		//
		// A slot we rejected never reaches cc_setobject, so the client never
		// gives that item a widget. Widgets that existed before the filter
		// came on survived (which is why WITHDRAWING looked fine), but an
		// item DEPOSITED while the filter was on got none — leaving us
		// nothing to move and no choice but an unclickable ghost. Forcing
		// the build to re-run could never help: the re-run asked us again
		// and we said no again (confirmed in play 2026-08-08 — the forced
		// rebuild left native items at 291 of 330).
		//
		// Blanking the grid was never this callback's job anyway.
		// BankMissingSection hides every native child it did not move into
		// a section, which is what actually produces the clean view.
		intStack[client.getIntStackSize() - 2] = 1; // 1 = lay this slot out
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

	/**
	 * The sections themselves, also fixed at activation. Freezing which
	 * STEPS show (above) was only half the job: WITHIN a step, an item is
	 * dropped once its sub is done and you no longer meet the count, so
	 * withdrawing made items enter and leave the list and every icon after
	 * them shifted a slot — "the bank is still changing as I withdraw".
	 * The counts are re-read live on every pass, so freezing composition
	 * costs nothing but the reshuffle.
	 */
	private List<com.ironscape.items.BankMissingSection.Section> frozenSections;

	private void refreshUpcomingNeeds()
	{
		if (frozenSections != null)
		{
			upcomingSections = frozenSections;
			return;
		}
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
		frozenSections = sections;
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
				if (requirements != null && requirementsMet(requirements)
					&& !skillGateForAQuest(current, requirements))
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
					// Region checkpoints are POSITION proofs, not monotonic
					// state: frontier-sub-only (passing through the region
					// for another reason must not tick a later window step)
					// and the annotation items must be in hand — "go to the
					// ess mine WITH the orb" is only done with the orb.
					boolean positional = hasRegionCheckpoint(subReqs);
					boolean eligible = !positional
						|| (current.step == frontierStep
							&& annotationItemsCarried(current.step, current.sub));
					if (eligible && requirementsMet(subReqs))
					{
						completeSubGoal(current.step, current.sub, "quest checkpoint (varbit/varp/region)");
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

				// A step whose whole job IS its errand chain has nothing
				// else to detect: "Kill Mordred and get bat bones/black
				// candle" parses to no goal at all, so it could never tick
				// by any route and nav sat holding for a goal that does not
				// exist (owner, in play, having done every leg). The chain
				// already knows when it is finished, so let it say so.
				//
				// Only when the sub has NO goal of its own. Where a chain is
				// incidental the goal still rules: finishing the Glarial's
				// pebble chain must not tick "Do Tree Gnome Village", whose
				// completion is the quest. Two steps guide-wide qualify, and
				// neither could auto-complete at all before.
				if (!hasAnyGoal(current.sub.getId())
					&& errandChainComplete(current.step, current.sub))
				{
					completeSubGoal(current.step, current.sub, "errand chain complete");
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
	/**
	 * The next thing the guide wants, in one short line — so the "stop
	 * following Quest Helper" message says what to do INSTEAD of following
	 * it, rather than just telling the player to look elsewhere.
	 */
	private String nextStepSummary()
	{
		Current next = findCurrent();
		if (next == null)
		{
			return "see the Ironscape panel.";
		}
		String text = next.sub.getPlainText().trim();
		// ASCII dots: the game font has no ellipsis glyph.
		return text.length() > 70 ? text.substring(0, 67) + "..." : text;
	}

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
	/**
	 * Where a travel sub says it ENDS, or null when nothing resolves: the
	 * last place its text names, else the step's authored 📍 tag (the
	 * guide's word order flips against the place list often enough —
	 * "go to battlefield of khazard" vs "Khazard Battlefield" — that the
	 * tag is the reliable half).
	 *
	 * Shared by the teleport-jump shortcut and the arrival check, so the
	 * two can never disagree about where the sub was heading.
	 */
	private WorldPoint travelDestination(GuideStep step, SubStep sub)
	{
		WorldPoint place = placeManager.lastPlaceIn(sub.getPlainText());
		if (place == null)
		{
			String location = step.getMetadata().get("location");
			place = location == null ? null : placeManager.getLoose(location);
		}
		return place;
	}

	/** How long a gangplank crossing counts as "just now" (~60s). */
	private static final int GANGPLANK_FRESH_TICKS = 100;

	/**
	 * Has a BOAT sub's journey actually put the player back on land?
	 *
	 * A docked ship's deck sits well inside the destination's arrival
	 * radius, so "Take the boat back to Ardy" ticked the moment the boat
	 * moored — with the player still aboard. Shortest Path then routes
	 * from a deck tile it has no path off and navigation bricks (owner,
	 * 2026-08-08). Destination proof (P0-04) can't help here: the deck IS
	 * the destination as far as distance is concerned.
	 *
	 * Crossing the gangplank is the transition, so that click is the
	 * signal — but only one made NEAR THE DESTINATION. Boarding at the far
	 * end crosses the same object, which is why the crossing TILE is
	 * recorded and not just the fact of it.
	 *
	 * RELEASE VALVE: no gangplank loaded near the player means there is
	 * nothing to cross, so the gate opens. A route that drops you straight
	 * onto the dock ticks normally instead of wedging forever — worth
	 * having, because whether all six of the guide's boat trips even end
	 * at a plank is unverified.
	 *
	 * Client thread (scene scan).
	 */
	private boolean ashoreOfBoat(GuideStep step, SubStep sub)
	{
		if (!BOAT.matcher(sub.getPlainText()).find())
		{
			return true;
		}
		WorldPoint destination = travelDestination(step, sub);
		boolean crossedHere = lastGangplankPoint != null
			&& client.getTickCount() - lastGangplankTick <= GANGPLANK_FRESH_TICKS
			&& (destination == null
				|| lastGangplankPoint.distanceTo(destination) <= PLACE_ARRIVE_RADIUS);
		if (crossedHere)
		{
			logBoatGate(sub.getId() + ": ashore, gangplank crossed");
			return true;
		}
		if (gangplankNearby())
		{
			logBoatGate(sub.getId() + ": holding, gangplank loaded but not crossed"
				+ crossingNote(destination));
			return false;
		}
		logBoatGate(sub.getId() + ": open, no gangplank in range" + crossingNote(destination));
		return true;
	}

	/**
	 * What the crossing recorder is actually holding, appended to every gate
	 * line.
	 *
	 * The gate's first exercise (Port Sarim, 2026-08-08) went "holding" then
	 * "open, no gangplank in range", so the crossing was never accepted — but
	 * the line cannot say WHY. Two different faults produce it: no click was
	 * recorded at all (the object is not named "Gangplank" at that dock, or
	 * the crossing came through a menu action the switch does not cover), or a
	 * click WAS recorded and then rejected for being too far from the
	 * destination. Those want opposite fixes, and one dock is not six, so
	 * nothing changes until a second trip says which — this just makes that
	 * trip conclusive rather than needing a third.
	 */
	private String crossingNote(WorldPoint destination)
	{
		if (lastGangplankPoint == null)
		{
			return " (no crossing click ever recorded)";
		}
		int age = client.getTickCount() - lastGangplankTick;
		return " (last crossing " + lastGangplankPoint + ", " + age + " ticks ago"
			+ (age > GANGPLANK_FRESH_TICKS ? ", STALE" : "")
			+ (destination == null ? ", no destination to compare"
				: ", " + lastGangplankPoint.distanceTo(destination) + " tiles from " + destination
					+ " (needs " + PLACE_ARRIVE_RADIUS + ")")
			+ ")";
	}

	/** One INFO line per change — a held boat sub must be greppable. */
	private void logBoatGate(String message)
	{
		if (!message.equals(lastBoatGateLog))
		{
			lastBoatGateLog = message;
			log.info("boat gate: {}", message);
		}
	}

	private String lastBoatGateLog;

	/**
	 * Is a gangplank loaded within sight of the player? Scene coordinates
	 * on both sides — a nearest-object search inside the loaded scene,
	 * never a comparison against annotation data.
	 */
	private boolean gangplankNearby()
	{
		Player me = client.getLocalPlayer();
		if (me == null)
		{
			return false; // can't prove a plank is there; never wedge on doubt
		}
		WorldPoint here = me.getWorldLocation();
		net.runelite.api.WorldView view = client.getTopLevelWorldView();
		net.runelite.api.Tile[][] tiles = view.getScene().getTiles()[view.getPlane()];
		for (net.runelite.api.Tile[] column : tiles)
		{
			for (net.runelite.api.Tile tile : column)
			{
				if (tile == null
					|| tile.getWorldLocation().distanceTo2D(here) > ARRIVE_RADIUS)
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
					if (name != null && name.contains("gangplank"))
					{
						return true;
					}
				}
			}
		}
		return false;
	}

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
			if (atomicQuestGoal.isRequiresFinished())
			{
				// FINISHED genuinely subsumes the errands — you cannot have
				// completed the quest with its own pickups outstanding.
				return state == QuestState.FINISHED && !errandPending;
			}
			// STARTED does not. "Start biohazard, get the plague sample and
			// 3 potions" ticked itself the instant the quest began and
			// handed off to Quest Helper, with the sample still in Elena's
			// house — the start is the PRECONDITION, the items are the
			// objective. (Contrast "Buy a spade, start X marks the spot",
			// where the quest action is the point; there the item goal is
			// already satisfied by then, so falling through costs nothing.)
			if (state == QuestState.NOT_STARTED || errandPending)
			{
				return false;
			}
			// fall through: the item goals below decide.
		}

		List<GoalDetector.ItemGoal> itemGoals = itemGoalsBySub.get(sub.getId());
		if (itemGoals != null)
		{
			if (!inFrontierStep)
			{
				return false;
			}
			// The chain defines "done" when one exists: the scrying orb
			// (an item goal) ticked the step at the Chaos Temple while the
			// chain's Aubury/essence-mine leg was still ahead. Goals only
			// count once every stage is satisfied.
			if (errandPending)
			{
				return false;
			}
			// The detector sees only what the sentence NAMES. "Buy 1 pack of
			// normal compost and all farming tools" yields one goal — the
			// pack — so buying it ticked the whole step while the five tools
			// (seeded as annotation items) had no vote. On a PURCHASE step
			// the annotated list is the rest of the shopping list, so it
			// gates too. See purchaseListAcquired for why ONLY purchases.
			//
			// Evaluated BEFORE the goals below, so the arming does not
			// depend on what you buy first: pick the tools up before the
			// compost pack and the list is still recorded as acquired.
			boolean purchaseListDone = purchaseListAcquired(step, sub, itemGoals);
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
					Integer baseline = progressManager.acquisitionBaseline(activeVariant, key);
					if (baseline == null || count < baseline)
					{
						// (Re)base — also downward, so banking the spares
						// and then buying still registers as a gain.
						progressManager.setAcquisitionBaseline(activeVariant, key, count);
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
			if (!purchaseListDone)
			{
				return false;
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
			WorldPoint me = playerPoint();
			return me != null
				&& me.getPlane() == interactionTarget.getPlane()
				&& me.distanceTo(interactionTarget) <= ARRIVE_RADIUS;
		}

		// "Teleport using the chronicle" — a recent position jump proves it.
		// Gated on the step's annotation items being IN HAND: "Home tele,
		// Lumby" with a cakes/runes shopping list means "teleport WITH the
		// items" — the jump alone must not tick it (owner hit this).
		// No early false: a travel sub can ALSO complete by arriving at its
		// destination below ("Home tele to lumby and run north to Varrock
		// east bank" — walking the second half needs arrival detection).
		//
		// And the jump must have landed WHERE THE SUB SAYS. Without that
		// this branch ticked any travel sub that happened to be current
		// while a teleport was warm: Brimstail's jump into the essence mine
		// ticked the region checkpoint, the loop cascaded to "Use mind bomb
		// and camelot tele", and that sub completed from inside the mine
		// with Camelot 1,300 tiles away (owner, 2026-08-08; the same shape
		// as the Chronicle/Castle Wars report). Charter and spirit-tree
		// subs have required destination proof since wave 7 — teleports get
		// the same rule. Unresolvable destination falls through to the
		// arrival check below rather than ticking on the jump alone.
		if (travelGoalSubs.contains(sub.getId())
			&& inFrontierStep && recentTeleportTicks > 0
			&& annotationItemsCarried(step, sub))
		{
			Player traveller = client.getLocalPlayer();
			WorldPoint landed = traveller == null ? null : realPoint(traveller);
			WorldPoint destination = travelDestination(step, sub);
			if (landed != null && destination != null
				&& landed.getPlane() == destination.getPlane()
				&& landed.distanceTo(destination) <= TELEPORT_ARRIVE_RADIUS
				&& ashoreOfBoat(step, sub))
			{
				return true;
			}
		}

		// The step's OBJECTIVE is possession, and its own sentence says so:
		// "decant them until you have like 6 full pots", "Make sure you have
		// all ghosts ahoy items". The detector cannot name what to count
		// there ("6 full pots" is not an item), so the annotation carries it
		// — but annotation items are display-only, which left these steps
		// with NO completion path at all: the badge read a green 6/6 over a
		// checkbox nothing could ever tick (owner, standing on it).
		//
		// The obvious rule — "no path + all numbered annotation items held"
		// — was MEASURED AND REJECTED. It changes 25 steps and roughly 2 are
		// right, because annotation items are overwhelmingly what you BRING,
		// not what the step is FOR: a spade would tick all six "dig up the
		// clue" steps on sight, and the barcrawl card would tick all ten
		// bars at once. That is wave 13's finding arriving from the opposite
		// direction, and the flags do not separate the cases.
		//
		// What separates them is the SENTENCE. A step whose objective is
		// having something says so in words, so that is what is matched, and
		// the blast radius is 2 steps guide-wide.
		//
		// Two guards, both earned by a real false positive in the
		// measurement:
		//   - PARENTHETICALS DON'T COUNT. "Use Brimstails to go to ess mines
		//     (scrying orb 2/3, make sure you have it with you)" is a
		//     TRAVEL instruction with a reminder attached; holding the orb
		//     must not tick it. Stripping bracketed asides drops it.
		//   - A CHECKPOINT OWNS ITS SUB. Same rule as wave 6's varbits: an
		//     authored requires clause exists precisely because heuristics
		//     fired early, so it does not get outvoted by one. That step's
		//     region checkpoint is its real completion path — invisible to
		//     completion-paths.tsv, which is why it read as unreachable.
		if (inFrontierStep && possessionObjectiveMet(step, sub))
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

		WorldPoint here = realPoint(player);
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
		// Network-travel phrasing has no movement verb ("Spirit tree to
		// gnome stronghold") but IS the movement instruction.
		// Arriving is not talking: a sub that asks for a conversation is not
		// finished by standing next to the NPC, however it is worded.
		if (TALK_INSTRUCTION.matcher(sub.getPlainText()).find())
		{
			return false;
		}
		if (!travelGoalSubs.contains(sub.getId())
			&& !networkTravel
			&& !MOVEMENT_WORD.matcher(sub.getPlainText()).find()
			&& !isBareDestination(sub.getPlainText()))
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
				&& here.distanceTo(new WorldPoint(precise.x, precise.y, precise.plane)) <= ARRIVE_RADIUS
				&& ashoreOfBoat(step, sub);
		}
		// Travel subs end at their LAST place mention (the destination);
		// everything else anchors on the first ("Talk to Reldo" -> Reldo).
		// Network travel is destination-anchored too ("Charter to X").
		WorldPoint place = travelGoalSubs.contains(sub.getId()) || networkTravel
			? travelDestination(step, sub)
			: placeManager.firstPlaceIn(sub.getPlainText());
		if (place == null && !travelGoalSubs.contains(sub.getId()) && !networkTravel)
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
		// Standing at the destination is not enough for a BOAT sub — the
		// deck is inside the radius, and nav bricks from there.
		return arrivalArmed.contains(sub.getId()) && ashoreOfBoat(step, sub);
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
			if (need.id == null && itemTracker.iconIdFor(need.name) <= 0)
			{
				continue; // unresolvable name: can't count it, don't block on it
			}
			if (Boolean.TRUE.equals(need.consumed))
			{
				// Drunk/spent during the step: it is gone by the time you
				// land, so gating on it would wedge the step forever.
				continue;
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
				? ownedCount(need)
				: carriedCount(need);
			if (count < required)
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * Owned/carried counts for an annotation item, by ID when it names one.
	 * See StepAnnotation.ItemNeed.id — some items share a name and can only
	 * be told apart by id.
	 */
	private int ownedCount(StepAnnotation.ItemNeed need)
	{
		return need.id != null ? itemTracker.countOfId(need.id) : itemTracker.countOf(need.name);
	}

	private int carriedCount(StepAnnotation.ItemNeed need)
	{
		return need.id != null
			? itemTracker.carriedCountOfId(need.id) : itemTracker.carriedCountOf(need.name);
	}

	/**
	 * Bracketed asides, removed. "(scrying orb 2/3, make sure you have it
	 * with you)" is a reminder hung off a travel instruction, not the
	 * instruction — reading the whole string treats the two as the same
	 * sentence and they are not.
	 */
	private static final java.util.regex.Pattern PARENTHETICAL =
		java.util.regex.Pattern.compile("\\([^)]*\\)");

	/**
	 * Steps whose objective IS having the things. Deliberately literal: the
	 * sentence has to say it, because possession is otherwise indis-
	 * tinguishable from carrying the tools for the job.
	 */
	private static final java.util.regex.Pattern POSSESSION_OBJECTIVE =
		java.util.regex.Pattern.compile(
			"\\b(?:until you have|make sure you have|until you own|so you have)\\b",
			java.util.regex.Pattern.CASE_INSENSITIVE);

	/**
	 * Does this sub's own sentence make POSSESSION the objective, and is
	 * that possession satisfied?
	 *
	 * See the call site for why this is matched on text rather than on the
	 * shape of the annotation: the wide rule was measured at 25 steps, ~2
	 * of them correct.
	 *
	 * Client thread (item id resolution).
	 */
	private boolean possessionObjectiveMet(GuideStep step, SubStep sub)
	{
		String text = PARENTHETICAL.matcher(sub.getPlainText()).replaceAll(" ");
		if (!POSSESSION_OBJECTIVE.matcher(text).find())
		{
			return false;
		}
		String annotationId = step.getSubSteps().size() == 1 ? step.getId() : sub.getId();
		// An authored checkpoint is the sub's completion path and outranks
		// every heuristic, this one included (wave 6).
		if (annotationManager.getRequirement(annotationId) != null
			|| annotationManager.getRequirement(sub.getId()) != null)
		{
			return false;
		}
		List<StepAnnotation.ItemNeed> needs = annotationManager.getItems(annotationId);
		boolean counted = false;
		for (StepAnnotation.ItemNeed need : needs)
		{
			if (need.quantity == null || need.quantity <= 0
				|| Boolean.TRUE.equals(need.granted)
				|| Boolean.TRUE.equals(need.consumed)
				|| Boolean.TRUE.equals(need.optional)
				|| Boolean.TRUE.equals(need.ingredient))
			{
				// Unnumbered or not-an-objective: no opinion either way. A
				// step made entirely of these never reaches "counted" and so
				// never completes here, which is the safe direction.
				continue;
			}
			if (need.id == null && itemTracker.iconIdFor(need.name) <= 0)
			{
				return false; // can't count it, so can't claim it is satisfied
			}
			int required = need.quantity;
			int count = itemTracker.bankCountable(need.name, required)
				? ownedCount(need)
				: carriedCount(need);
			if (count < required)
			{
				return false;
			}
			counted = true;
		}
		return counted;
	}

	/**
	 * Are the annotated items of a PURCHASE step in hand (or were they,
	 * while this sub was current)?
	 *
	 * Scoped to purchase steps on the evidence, not out of caution.
	 * tools/audit-item-gating.mjs measured the obvious wider rule — gate on
	 * every explicit-quantity item on every non-quest step — and it changed
	 * 30 steps, of which 29 were WRONG: annotation items are overwhelmingly
	 * TOOLS and INGREDIENTS, not objectives. Gating on those wedges in ways
	 * the flags don't catch:
	 *
	 *   "Get 61 Crafting"          -> would demand 1,200 buckets you spend
	 *                                 crafting, on a LEVEL goal. Never ticks.
	 *   "Hunt 15k red chins"       -> chins count the bank, box traps don't;
	 *                                 bank the traps and the step wedges.
	 *   "give the bread to the
	 *    beggar to get excalibur"  -> the bread is GONE by the time you hold
	 *                                 the reward. Wedged forever.
	 *
	 * On a purchase step the relationship inverts: the annotated list is
	 * what the sentence told you to buy but the detector could not name.
	 * That was one step guide-wide when this shipped (the compost/farming
	 * tools step that prompted it) and it covers any future "buy A and B"
	 * where only one half parses.
	 *
	 * Client thread (item id resolution).
	 */
	private boolean purchaseListAcquired(GuideStep step, SubStep sub,
		List<GoalDetector.ItemGoal> itemGoals)
	{
		boolean purchase = false;
		for (GoalDetector.ItemGoal goal : itemGoals)
		{
			purchase |= goal.isAcquisition();
		}
		if (!purchase)
		{
			return true;
		}
		List<StepAnnotation.ItemNeed> gating = gateableItems(step, sub);
		if (gating.isEmpty())
		{
			// Nothing on the list, so nothing to have acquired. Do NOT arm:
			// the flag persists across sessions, so arming on an empty list
			// silently disarms the gate for ever — and then SEEDING a list
			// later can never engage it. That is exactly what happened to
			// "Buy priest robes …, boots, gloves": armed on an empty list
			// long before its four items were annotated, so the step ticked
			// off its one detected goal (the gown TOP) the moment one half
			// was bought.
			return true;
		}
		// The signature is part of the key, so EDITING the list re-gates the
		// step instead of inheriting an arming that was granted for a
		// different shopping list. Seeding item lists is ongoing work; a flag
		// that outlives the list it was about is a trap that gets re-sprung
		// every time one is added.
		StringBuilder signature = new StringBuilder();
		for (StepAnnotation.ItemNeed need : gating)
		{
			signature.append(need.name.toLowerCase(Locale.ROOT)).append('x')
				.append(need.quantity == null ? 1 : need.quantity).append(';');
		}
		String armedKey = sub.getId() + "|" + PURCHASE_LIST_KEY + "|"
			+ Integer.toHexString(signature.toString().hashCode());
		if (progressManager.acquisitionBaseline(activeVariant, armedKey) != null)
		{
			return true;
		}
		for (StepAnnotation.ItemNeed need : gating)
		{
			int required = need.quantity == null ? 1 : need.quantity;
			// Counts the BANK, unlike most goals. What this gate asks is
			// "is there anything left on the shopping list to acquire" —
			// already owning the item answers that wherever it sits. The
			// first cut used carried-only and instantly hit the trap this
			// project keeps hitting: the owner had all five farming tools
			// BANKED, the panel showed them green with a 🏦 badge, and the
			// step would not tick. A gate stricter than its own badge is
			// unexplainable to the player (owner, 2026-08-08).
			if (ownedCount(need) < required)
			{
				return false;
			}
		}
		progressManager.setAcquisitionBaseline(activeVariant, armedKey, 1);
		return true;
	}

	/**
	 * The annotation items that may gate completion: everything the
	 * authored list holds MINUS the four flags and the two special cases
	 * that exist because gating on them wedges the step.
	 *
	 * Unspecified quantity is excluded on the same grounds as P0-07 left
	 * it out of the arrival gate: null means "bring some" (the scraper's
	 * per-step carry list), and a carry list is not an objective —
	 * Gertrude's Cat carries bucket/barcrawl card/rune mysteries package,
	 * none of which the step is about.
	 */
	private List<StepAnnotation.ItemNeed> gateableItems(GuideStep step, SubStep sub)
	{
		List<StepAnnotation.ItemNeed> gating = new ArrayList<>();
		List<StepAnnotation.ItemNeed> items = new ArrayList<>(
			annotationManager.getItems(step.getId()));
		if (!step.getId().equals(sub.getId()))
		{
			items.addAll(annotationManager.getItems(sub.getId()));
		}
		for (StepAnnotation.ItemNeed need : items)
		{
			if (need.quantity == null                       // "bring some"
				|| Boolean.TRUE.equals(need.granted)        // the quest hands it over
				|| Boolean.TRUE.equals(need.consumed)       // spent during the step
				|| Boolean.TRUE.equals(need.optional)       // keep-if-you-get-it
				|| Boolean.TRUE.equals(need.ingredient))    // material for the product
			{
				continue;
			}
			String lower = need.name.toLowerCase(Locale.ROOT);
			if (lower.equals("coins") || lower.equals("gp"))
			{
				continue; // paying for the purchase must not block the purchase
			}
			if (itemTracker.iconIdFor(need.name) <= 0)
			{
				continue; // unresolvable name: can't count it, don't block on it
			}
			gating.add(need);
		}
		return gating;
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
			client.addChatMessage(ChatMessageType.CONSOLE, "", "IRONSCAPE: done: " + text, null);
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
			client.addChatMessage(ChatMessageType.CONSOLE, "", "IRONSCAPE: done: " + text, null);
		}

		// HANDOFF RETURN, mid-quest flavour. A guide step often ends PART WAY
		// through a quest ("get the plague sample and 3 potions", then leave
		// for Rimmington). Our step completing is the moment the player needs
		// us again, not Quest Helper, which would happily run them to the end
		// of the quest — so this has to be unmissable.
		//
		// atFrontier, NOT isFrontierStep(step): advancePositionTo() above has
		// already moved the frontier PAST this step, so re-asking says no and
		// the message never fired. Owner hit exactly this on Biohazard.
		//
		// It no longer needs handedOffQuest either. The condition that
		// matters is inherent in the state — a frontier step finished while
		// its quest is still IN_PROGRESS — and hanging it on a transition
		// flag was a second way to silently not fire.
		if (loginGraceTicks == 0 && atFrontier
			&& progressManager.isCompleted(activeVariant, step.getId()))
		{
			Quest quest = stepQuest(new Current(step, sub));
			if (quest != null && cachedQuestState(quest) == QuestState.IN_PROGRESS)
			{
				handedOffQuest = null;
				String next = nextStepSummary();
				// ASCII only, and colored instead. The game font has no glyph
				// for "✓" — it renders as "?", which is what the owner saw
				// (same trap as the ellipsis on bank headers).
				client.addChatMessage(ChatMessageType.CONSOLE, "",
					"<col=00ff00>IRONSCAPE: STOP following Quest Helper here.</col>", null);
				client.addChatMessage(ChatMessageType.CONSOLE, "",
					"<col=00ff00>The guide leaves " + quest.getName()
						+ " part-finished on purpose - it comes back to it later. "
						+ "Next: " + next + "</col>", null);
				client.addChatMessage(ChatMessageType.CONSOLE, "",
					"<col=00ff00>Close it in the Quest Helper side panel (the X by "
						+ "the quest name) so its arrows stop fighting the route.</col>", null);
				SwingUtilities.invokeLater(() -> clientToolbar.openPanel(navButton));
				// Chat alone was not enough (owner): a console line scrolls
				// away behind quest dialogue while the player watches the
				// game. Put it across the viewport and ping the notifier too.
				if (config.showHandoffBanner())
				{
					handoffModel = com.ironscape.overlay.QuestHandoffOverlay.Model.stop(
						quest.getName(), next);
					handoffBannerTicks = HANDOFF_BANNER_TICKS;
					notifier.notify("Guide step done - stop following Quest Helper. Next: " + next);
				}
			}
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
				if (requires.quest != null)
				{
					// Resolved ONCE here rather than per tick, and warned
					// about loudly: a name the enum does not know would
					// otherwise be an invisible always-false condition.
					StepRequirement parsedQuest = new StepRequirement((Integer) null);
					parsedQuest.questName = requires.quest;
					parsedQuest.quest = questByName(requires.quest);
					if (parsedQuest.quest == null)
					{
						log.warn("annotation {} names a quest the game does not know: '{}'",
							stepId, requires.quest);
					}
					parsed.add(parsedQuest);
					continue;
				}
				if (requires.region != null)
				{
					parsed.add(new StepRequirement(requires.region));
					continue;
				}
				if (requires.equipped != null)
				{
					parsed.add(new StepRequirement(requires.equipped,
						requires.icon == null ? requires.equipped : requires.icon,
						requires.label == null ? "worn" : requires.label));
					continue;
				}
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
		logCheckpointValues();
	}

	/** Last logged "sub -> var values" line, so only CHANGES print. */
	private String lastCheckpointLog;

	/**
	 * Print the LIVE value of the frontier sub's varbit/varp checkpoint
	 * whenever it moves.
	 *
	 * Seeding a checkpoint means picking a threshold, and the threshold is
	 * guesswork until something reports the real numbers: Quest Helper's
	 * loadSteps() map only lists the values IT handles, so Biohazard reads
	 * "10 chemist, 12 smuggle, 14 return to Elena" and says nothing about
	 * whatever sits between 12 and 14. A threshold guessed from that map
	 * left the step stuck after all three vials were handed over.
	 *
	 * One line per change is enough to author the next one from evidence
	 * rather than inference, and to explain a stuck step in hindsight.
	 */
	private void logCheckpointValues()
	{
		Current current = findCurrent();
		if (current == null)
		{
			return;
		}
		List<StepRequirement> requirements = subRequirements.get(current.sub.getId());
		if (requirements == null || !hasVarCheckpoint(requirements))
		{
			return;
		}
		StringBuilder line = new StringBuilder();
		for (StepRequirement requirement : requirements)
		{
			if (requirement.varbit != null)
			{
				line.append(" varbit ").append(requirement.varbit).append('=')
					.append(client.getVarbitValue(requirement.varbit));
			}
			else if (requirement.varp != null)
			{
				line.append(" varp ").append(requirement.varp).append('=')
					.append(client.getVarpValue(requirement.varp));
			}
			else
			{
				continue;
			}
			line.append(" (need ").append(requirement.threshold)
				.append(requirement.bit != null ? " bit " + requirement.bit : "").append(')');
		}
		if (line.length() == 0)
		{
			return;
		}
		String message = current.sub.getId() + ":" + line;
		if (!message.equals(lastCheckpointLog))
		{
			lastCheckpointLog = message;
			log.info("checkpoint {}", message);
		}
	}

	/** Does the list carry a varbit/varp/region/equipped checkpoint (vs only skill levels)? */
	private static boolean hasVarCheckpoint(List<StepRequirement> requirements)
	{
		for (StepRequirement requirement : requirements)
		{
			if (requirement.varbit != null || requirement.varp != null
				|| requirement.region != null || requirement.equipped != null)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Position (region) and EQUIPPED checkpoints are live, reversible
	 * state rather than monotonic game progress — both get the
	 * frontier-only treatment, so a later step in the window cannot tick
	 * because you walked through its region or happen to be wearing its
	 * item.
	 */
	private static boolean hasRegionCheckpoint(List<StepRequirement> requirements)
	{
		for (StepRequirement requirement : requirements)
		{
			if (requirement.region != null || requirement.equipped != null)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Is this requirement list a PREREQUISITE for the step's quest rather
	 * than the step's finish line?
	 *
	 * "Train Runecraft to 10, then complete Temple of the Eye" is two jobs
	 * in one step, and reaching 10 completed the whole thing (owner, in
	 * play) because a met requirement completes the step outright. But that
	 * level is the GATE on the quest -- Temple of the Eye needs Runecraft
	 * 10 and will not start without it -- so meeting it means the work can
	 * now begin, not that it is done.
	 *
	 * So a skill-only list on a quest step stops completing it. The quest
	 * goal does that instead, by which time the level is necessarily met,
	 * and the badge still shows progress toward it the whole way.
	 *
	 * Var/region/equipped checkpoints are untouched: those are authored
	 * precisely to mark where a step stops, which is the opposite case.
	 * Four steps guide-wide pair a skill requirement with a quest.
	 */
	private boolean skillGateForAQuest(Current current, List<StepRequirement> requirements)
	{
		if (hasVarCheckpoint(requirements))
		{
			return false;
		}
		return stepQuest(current) != null;
	}

	/** ALL requirements met? (Reviewed annotations; runs on the client thread.) */
	private boolean requirementsMet(List<StepRequirement> requirements)
	{
		for (StepRequirement requirement : requirements)
		{
			if (requirement.questName != null)
			{
				// "Do these quests: A soul's bane, Contact!, ..." — thirteen
				// named quests and no way to say so until now, so a step
				// whose completion is perfectly knowable was a hand tick.
				// An unresolved name is NOT met: failing closed leaves the
				// step visible rather than letting it complete early.
				if (requirement.quest == null
					|| cachedQuestState(requirement.quest) != QuestState.FINISHED)
				{
					return false;
				}
				continue;
			}
			if (requirement.region != null)
			{
				// TEMPLATE region — the essence mine (and most quest interiors)
				// hand out a per-party dynamic copy whose raw region id is
				// never the annotated one. See playerPoint().
				WorldPoint me = playerPoint();
				if (me == null || me.getRegionID() != requirement.region)
				{
					return false;
				}
				continue;
			}
			if (requirement.equipped != null)
			{
				if (itemTracker.wornCountOf(requirement.equipped) < 1)
				{
					return false;
				}
				continue;
			}
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
		/** Position checkpoint: met while standing in this map region. */
		final Integer region;
		/** Equipment checkpoint: met while this item name is WORN. */
		final String equipped;
		/**
		 * Quest checkpoint: met when this quest is FINISHED. Deliberately
		 * NOT final — it was added long after the constructors below, and
		 * threading a null through every one of them to express "this is
		 * not a quest requirement" is churn with no reader.
		 *
		 * The NAME is kept beside the resolved quest so an unresolved name
		 * fails CLOSED: a typo must leave the step unticked and visible,
		 * never quietly drop out of an "all of these" list and let the
		 * step complete early.
		 */
		String questName;
		Quest quest;

		StepRequirement(Integer region)
		{
			this.skill = null;
			this.varbit = null;
			this.varp = null;
			this.threshold = 1;
			this.bit = null;
			this.icon = null;
			this.label = null;
			this.region = region;
			this.equipped = null;
		}

		StepRequirement(String equipped, String icon, String label)
		{
			this.skill = null;
			this.varbit = null;
			this.varp = null;
			this.threshold = 1;
			this.bit = null;
			this.icon = icon;
			this.label = label;
			this.region = null;
			this.equipped = equipped;
		}

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
			this.region = null;
			this.equipped = null;
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

	/**
	 * Traversal objects worth outlining when a route point sits on one.
	 * Lowercase: liveObjectName lowercases (that mismatch has cost a
	 * play-test round before).
	 */
	private static final java.util.Set<String> TRAVERSAL_OBJECTS = java.util.Set.of(
		"staircase", "stairs", "ladder", "trapdoor", "trap door",
		"stairwell", "steps");

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
				String plain = goal.getItemName().toLowerCase(Locale.ROOT);
				String rock = ROCK_BY_ORE.get(plain);
				if (rock != null)
				{
					rockNames.add(rock);
				}
				// Pick-plants share their item's name — "Onion" plants for
				// the onion goal, cabbage, flax. A goal with no same-named
				// scene object simply matches nothing.
				rockNames.add(plain);
				if (plain.endsWith("s"))
				{
					rockNames.add(plain.substring(0, plain.length() - 1));
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
		// A stage with a route/satisfaction split points its route at a
		// TRAVERSAL object — the staircase up to Lancelot, the trapdoor
		// down to a basement. The ⌖ marker showed where to stand but not
		// what to click, and "the stairs need highlighting so people know
		// where to go" (owner, 2026-08-08). Only while the player is on
		// the route's own plane: once upstairs, the stage's real target
		// takes over and the staircase behind you is noise.
		//
		// A stage that NAMES its object gets that name instead of the
		// guess-list, and needs no route/satisfaction split to qualify:
		// the way out of Keep Le Faye is a door at the stage's own point,
		// with nothing to route to at all.
		WorldPoint traversal = null;
		String traversalName = null;
		StepAnnotation.Errand routedStage = activeErrand();
		if (routedStage != null
			&& (routedStage.object != null
				|| (routedStage.routeX != null && routedStage.routeY != null)))
		{
			WorldPoint route = errandRoutePoint(routedStage);
			WorldPoint here = playerPoint();
			if (here != null && here.getPlane() == route.getPlane())
			{
				traversal = route;
				traversalName = routedStage.object == null ? null
					: routedStage.object.toLowerCase(Locale.ROOT);
			}
		}
		// The object a sub NAMES, at the step's own ⌖: "Put pineapples into
		// the COMPOST BIN" walked you to the bin and then left you to find
		// it (owner). Matching bare object names guide-wide would light up
		// every table and tree, so this is anchored on the ⌖ — the pin marks
		// the exact spot, and only an object AT it can match.
		StepAnnotation.Target pinned = annotationManager.getTarget(current.sub.getId());
		if (pinned == null)
		{
			pinned = annotationManager.getTarget(current.step.getId());
		}
		WorldPoint namedAt = pinned == null || Boolean.TRUE.equals(pinned.cleared)
			? null : new WorldPoint(pinned.x, pinned.y, pinned.plane);
		if (rockNames.isEmpty() && vendorNames.isEmpty()
			&& traversal == null && namedAt == null)
		{
			return java.util.Collections.emptyList();
		}
		Player scanMe = client.getLocalPlayer();
		// SCENE coordinates on BOTH sides (see the object compare below): this
		// is a nearest-object-to-me search within the loaded scene, never a
		// comparison against annotation data, so instance mapping would only
		// cost a conversion per object per tick.
		WorldPoint here = scanMe == null ? null : scanMe.getWorldLocation();
		java.util.LinkedHashSet<net.runelite.api.GameObject> found = new java.util.LinkedHashSet<>();
		net.runelite.api.GameObject nearestVendor = null;
		int vendorBest = Integer.MAX_VALUE;
		net.runelite.api.GameObject nearestTraversal = null;
		int traversalBest = Integer.MAX_VALUE;
		net.runelite.api.GameObject nearestNamed = null;
		int namedBest = Integer.MAX_VALUE;
		String subText = current.sub.getPlainText().toLowerCase(Locale.ROOT);
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
					// Nearest traversal object to the ROUTE POINT, not to the
					// player: Camelot's ground floor has several staircases and
					// the route names which one. Scene coords on both sides, so
					// an instanced copy simply matches nothing rather than
					// outlining the wrong stairs.
					// A stage naming a GENERIC way up or down ("Staircase")
					// accepts any traversal object at the point, because the
					// game's own wording varies — stairs/staircase/steps — and
					// an exact-match guess that is one letter out silently
					// outlines nothing at all. A stage naming something
					// SPECIFIC (Crate, Chest, Cave entrance) still has to match
					// exactly; that precision is the whole point there.
					if (traversal != null && (traversalName != null
						? (name.equals(traversalName)
							|| (TRAVERSAL_OBJECTS.contains(traversalName)
								&& TRAVERSAL_OBJECTS.contains(name)))
						: TRAVERSAL_OBJECTS.contains(name)))
					{
						int d = object.getWorldLocation().distanceTo2D(traversal);
						if (d <= ARRIVE_RADIUS && d < traversalBest)
						{
							traversalBest = d;
							nearestTraversal = object;
						}
					}
					// Named in the sub AND standing on the ⌖ (see namedAt).
					// The length floor keeps one-word scenery ("door", "sign")
					// from matching on a coincidence.
					if (namedAt != null && name.length() >= 5 && subText.contains(name))
					{
						int d = object.getWorldLocation().distanceTo2D(namedAt);
						if (d <= ARRIVE_RADIUS && d < namedBest)
						{
							namedBest = d;
							nearestNamed = object;
						}
					}
				}
			}
		}
		if (nearestVendor != null)
		{
			found.add(nearestVendor);
		}
		if (nearestTraversal != null)
		{
			found.add(nearestTraversal);
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
		// QH-style stage focus: an active errand stage that NAMES its
		// hand-ins narrows the inventory outline to just those (only the
		// paste ingredients glow at Aggie, not the whole step kit). No
		// stage item list = the default full-step highlight below.
		StepAnnotation.Errand stage = activeErrand();
		if (stage != null && stage.items != null && !stage.items.isEmpty())
		{
			wanted.addAll(stage.items);
			return hintIdsFor(wanted);
		}
		// FOCUS, in priority order. Outlining every item a step mentions is
		// what made this overlay noise: on a step carrying seven items,
		// seven glowed, while Quest Helper outlined the one garlic its
		// current instruction wanted (owner, in play).
		//
		// So narrow to what THIS action is about, and never to the guide's
		// carry list — an unnumbered item is "have this on you around now",
		// not "use this next", which is the same distinction the panel
		// draws by colouring those counts grey.
		//
		// Honest limit: our unit is a whole guide step, often several
		// actions, so this approximates QH rather than matching it. Errand
		// stages are the exception — a stage's `items` is per-action, which
		// is why that branch above returns immediately.
		List<GoalDetector.ItemGoal> goals = itemGoalsBySub.get(current.sub.getId());
		java.util.List<String> focus = new java.util.ArrayList<>();
		if (goals != null)
		{
			for (GoalDetector.ItemGoal goal : goals)
			{
				focus.add(goal.getItemName());
			}
		}
		if (focus.isEmpty())
		{
			// No detected goal: fall back to what the step REQUIRES, which
			// is the numbered items only. Optional and quest-granted ones
			// are not this action's business either.
			for (String id : new String[]{current.step.getId(), current.sub.getId()})
			{
				for (StepAnnotation.ItemNeed need : annotationManager.getItems(id))
				{
					if (need.quantity != null
						&& !Boolean.TRUE.equals(need.optional)
						&& !Boolean.TRUE.equals(need.granted))
					{
						focus.add(need.name);
					}
				}
			}
		}
		// The SHOP overlay keeps the wide list: standing in a shop, seeing
		// every item this step might have you buy is help, not noise.
		for (String id : new String[]{current.step.getId(), current.sub.getId()})
		{
			for (StepAnnotation.ItemNeed need : annotationManager.getItems(id))
			{
				wanted.add(need.name);
			}
		}
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
			focus.add(full);
			if (full.contains(" "))
			{
				wanted.add(full.substring(full.indexOf(' ') + 1));
				focus.add(full.substring(full.indexOf(' ') + 1));
			}
		}
		// "Chronicle tele" with the Chronicle in the BAG rather than worn:
		// the equipment-slot hint stands down (see activeEquippedTeleport),
		// so outline the inventory copy instead of pointing nowhere.
		// Both lists: the sub NAMES this one, so it is precisely the "use
		// this next" case the focus list is for.
		if (CHRONICLE_TELE.matcher(current.sub.getPlainText()).find()
			&& itemTracker.wornCountOf("chronicle") == 0)
		{
			wanted.add("chronicle");
			focus.add("chronicle");
		}
		// The same list drives the SHOP overlay, which cannot use the ids
		// below: what you are there to buy is by definition not in your
		// inventory yet, so it matches the shop's stock by NAME instead.
		java.util.Set<String> names = new java.util.HashSet<>();
		for (String name : wanted)
		{
			if (name != null && !name.isEmpty())
			{
				names.add(name.toLowerCase(Locale.ROOT));
			}
		}
		shopHintItemNames = names;
		return hintIdsFor(focus);
	}

	/** Inventory slot item ids whose names match any wanted name. */
	private java.util.Set<Integer> hintIdsFor(java.util.List<String> wanted)
	{
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

			// Real-world tile: a capture taken inside an instance would write
			// that copy's throwaway coordinates into the annotation file — and
			// these get shared back.
			WorldPoint where = realPoint(player);
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
					+ " hidden - use the capture-location button to set the right spot.";
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
			// A silent no-op here looked like a broken router in play —
			// one deduped line tells the session log the truth.
			logNavDecision("auto-navigate is disabled in the plugin config");
			return;
		}
		clientThread.invokeLater(() -> {
			// Every outcome below re-decides whether a BANK is why we routed,
			// so start from "no". Left sticky, the flag outlived its route:
			// a bank stop on one step kept the 10-tick re-check running on
			// the next, and — worse — the branches that set it only on the
			// way IN left it true after standing down.
			navRoutedToBank = false;
			// A waiting gravestone outranks EVERYTHING — captures, errands,
			// stand-downs: without the gear there is no route to follow.
			if (deathPoint != null)
			{
				logNavDecision("routing to gravestone at " + deathPoint);
				postPath(deathPoint, true);
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
				// ... unless the stage says there is no route to draw —
				// either because it IS the quest ("continue the grand tree
				// until you are at Karamja shipyard"), where the step's 📍
				// area only fights Quest Helper, or because Shortest Path
				// cannot path from where the player is standing (inside a
				// one-way interior). Hold, and say what will release it.
				if (Boolean.TRUE.equals(errand.hold))
				{
					logNavDecision("holding: stage draws no route"
						+ (errand.note == null ? "" : " — " + errand.note));
					postClear();
					return;
				}
				// The ONE path in this method that used to post a route
				// without logging a decision. Because logNavDecision only
				// prints on CHANGE, lastNavDecision kept whatever the
				// previous step had set, and the session log went on
				// claiming the route pointed at the last step's
				// destination — six minutes and a boat trip out of date.
				// A route you cannot account for is worse than no route:
				// the owner read it as "navigation is not working" while
				// nav was in fact working, on an errand stage nobody could
				// see (2026-08-08, the Mordred bat bones/black candle
				// chain). Name the stage as well as the tile.
				WorldPoint errandRoute = errandRouteTarget(errand);
				logNavDecision("routing to errand stage " + errandRoute
					+ (errand.npc == null ? "" : " (" + errand.npc + ")")
					+ (errand.item == null ? "" : " for " + errand.item)
					+ (errand.note == null ? "" : " — " + errand.note));
				postPath(errandRoute, true);
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
				postClear();
				return;
			}
			// Player jumped ahead to a later step's quest: ANY route we
			// post drags them back toward the frontier mid-quest — full
			// stand-down until that quest wraps up.
			if (playerJumpedAhead)
			{
				logNavDecision("cleared: jumped ahead to a later step's quest");
				postClear();
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
				// The quest kit sitting in the BANK outranks the step area:
				// arriving at the Wizards' Tower beadless helps nobody.
				if (questCurrent != null)
				{
					WorldPoint kitBank = bankFirstTarget(questCurrent);
					if (kitBank != null)
					{
						// Remember the bank here TOO, not just in
						// findNextTarget. Withdrawing a kit completes nothing
						// and fires no event, so without this the 10-tick
						// re-check never ran on a QUEST step: the route sat on
						// the bank with the items already in the bag, and the
						// stand-down below — the whole handoff to Quest Helper
						// — could not arrive on its own (2026-08-09, in play,
						// Black Knights' Fortress with its kit banked).
						navRoutedToBank = true;
						// SUGGEST the bank once; do not keep seizing the route.
						//
						// The 10-tick re-check has to keep RUNNING here — that
						// is what notices the kit has left the bank and lets the
						// stand-down below finally arrive. But re-POSTING on
						// every one of those passes turns a suggestion into an
						// override no player can escape: mid-Observatory, with
						// the kit still banked, our Castle Wars path landed on
						// top of Quest Helper's every six seconds, so the route
						// "went to the right place briefly and then something
						// took it over again" (owner, in play).
						//
						// It is also invisible in the log, because logNavDecision
						// only prints on CHANGE and the decision never changed.
						//
						// Posting once per (step, bank) keeps wave 19's handoff
						// sequence intact — bank, withdraw, hand over — while
						// letting a player who would rather crack on simply walk
						// away from the suggestion. The non-quest bank route
						// below still re-posts freely: nothing else is drawing
						// there, so there is nothing to fight.
						// Keyed by STEP ALONE. It used to include the bank, and
						// that leaked the whole fix: this picks the NEAREST
						// bank, so every time the player moved far enough for a
						// different one to win, the key changed and the
						// "once" started over. In play it re-posted four
						// different banks in two minutes — Varrock east, Al
						// Kharid, Falador, then one underground — which reads
						// exactly like the pestering wave 20 set out to stop
						// (owner, 2026-08-10). Suggest a bank once per step and
						// then leave the player alone, wherever they wander.
						//
						// Not cleared where navRoutedToBank is: that resets at
						// the top of every pass, which is exactly the
						// once-per-pass behaviour being fixed.
						String suggestion = questCurrent.step.getId();
						if (!suggestion.equals(lastBankSuggestion))
						{
							lastBankSuggestion = suggestion;
							// Name the bank. "routing to a bank first" alone could
							// not say WHICH, so a report of "it sent me across
							// town" took reconstructing from four other lines.
							logNavDecision("routing to a bank first — the step's kit is banked: "
								+ kitBank);
							postPath(kitBank);
						}
						return;
					}
				}
				// ... and once the kit is sorted, get out of Quest Helper's
				// way entirely if it is actually installed and running.
				//
				// Routing to the step's area was a compromise for players
				// WITHOUT QH, who were left pointing nowhere when a blanket
				// stand-down was tried and reverted. But for everyone who
				// does have it, our line has been arguing with theirs on
				// every quest step since — the owner watched it happen the
				// moment he accepted Murder Mystery.
				//
				// Both cases are servable now that we can tell them apart,
				// which needs no reflection: RuneLite records installed hub
				// plugins and any explicit disable in its own config group.
				if (questHelperInstalled())
				{
					logNavDecision("standing down: Quest Helper is installed"
						+ " and this step's quest is in progress");
					announceStandDown(questCurrent);
					postClear();
					return;
				}
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
					postPath(area);
				}
				else
				{
					logNavDecision("cleared: quest owns guidance, step has no routable area");
					postClear();
				}
				return;
			}
			WorldPoint target = findNextTarget();
			if (target != null)
			{
				logNavDecision("routing to " + target);
				postPath(target);
			}
			else
			{
				// The next thing to do has no known location — clear the
				// route so a STALE one (last step's quest etc.) doesn't
				// keep pointing somewhere you no longer need to go.
				logNavDecision("cleared: no routable target in the window");
				postClear();
			}
		});
	}

	/**
	 * Say — once per step — that we just handed the wheel to Quest Helper.
	 *
	 * The mirror of the "STOP following Quest Helper" banner, and asked for
	 * in the same shape (owner, 2026-08-09). Note what it is NOT: a prompt on
	 * every quest step. It fires only where we actively CLEAR a route we were
	 * drawing, which is the moment that otherwise looks like a fault — the
	 * line simply vanishes. On this step that lands after the bank trip, not
	 * before it, which is the whole reason it hangs off the stand-down rather
	 * than off the quest going in progress.
	 *
	 * Client thread only (called from inside the navigation lambda).
	 */
	private void announceStandDown(Current questCurrent)
	{
		if (questCurrent == null || loginGraceTicks > 0)
		{
			return;
		}
		String stepId = questCurrent.step.getId();
		if (stepId.equals(standDownAnnouncedStepId))
		{
			return;
		}
		Quest quest = stepQuest(questCurrent);
		if (quest == null)
		{
			return;
		}
		standDownAnnouncedStepId = stepId;
		// ASCII only, coloured instead: the game font has no glyph for the
		// tidy typographic characters and renders them as "?".
		client.addChatMessage(ChatMessageType.CONSOLE, "",
			"<col=00ff00>IRONSCAPE: Use Quest Helper for " + quest.getName()
				+ " - our route stops here and resumes when you finish it.</col>", null);
		if (config.showHandoffBanner())
		{
			handoffModel = com.ironscape.overlay.QuestHandoffOverlay.Model.start(quest.getName());
			handoffBannerTicks = HANDOFF_BANNER_TICKS;
			notifier.notify(quest.getName() + " is Quest Helper's from here.");
		}
	}

	/**
	 * You logged in part way through a quest — say which one.
	 *
	 * The gap this closes: the STOP and START banners both fire on an EDGE,
	 * and logging back in has no edge. Guidance was already Quest Helper's
	 * when you logged out, so nothing transitions and nothing fires — the
	 * one moment you have genuinely forgotten where you were is the one
	 * moment the plugin says nothing (owner, on logging in mid-Observatory).
	 *
	 * Deliberately narrow, because a banner on every login would be noise
	 * and would devalue the rare STOP one (wave 19's weighting argument):
	 *
	 *   - only on a REAL login, once, off the same resume hook as nav;
	 *   - only when the frontier step's own quest is IN_PROGRESS. Not
	 *     started means there is nothing to resume, and finished means the
	 *     step is about to tick itself;
	 *   - only if the stand-down has not already announced this step, so a
	 *     login that lands straight on the stand-down says it once, not
	 *     twice;
	 *   - only when Quest Helper is actually installed. Naming a panel the
	 *     player does not have is worse than silence.
	 *
	 * Client thread (quest state, chat, overlay model).
	 */
	private void announceMidQuestResume()
	{
		if (!questHelperInstalled())
		{
			return;
		}
		Current current = findCurrent();
		if (current == null || current.step.getId().equals(standDownAnnouncedStepId))
		{
			return;
		}
		Quest quest = stepQuest(current);
		if (quest == null || cachedQuestState(quest) != QuestState.IN_PROGRESS)
		{
			return;
		}
		standDownAnnouncedStepId = current.step.getId();
		client.addChatMessage(ChatMessageType.CONSOLE, "",
			"<col=00ff00>IRONSCAPE: " + quest.getName() + " is still in progress"
				+ " - use Quest Helper to pick it up.</col>", null);
		if (config.showHandoffBanner())
		{
			handoffModel = com.ironscape.overlay.QuestHandoffOverlay.Model.resume(quest.getName());
			handoffBannerTicks = HANDOFF_BANNER_TICKS;
			notifier.notify("You left off part way through " + quest.getName() + ".");
		}
	}

	private String lastNavDecision;

	/**
	 * One INFO line per CHANGE of auto-navigation outcome. "Auto-nav seems
	 * dead" reports were undiagnosable — every stand-down branch was
	 * silent; now the session log (mine-session-log.mjs) names the branch.
	 */
	/**
	 * The last few decisions, kept so a bug report can carry them.
	 *
	 * Every one of these is already logged, but the owner cannot read a
	 * client log and should not have to: reports arrive as "nav is broken"
	 * and Claude reconstructs the moment from four other lines. Bounded and
	 * tiny — this is a report attachment, not a second log.
	 */
	private final java.util.Deque<String> recentDecisions =
		new java.util.ArrayDeque<>();

	/**
	 * DX-4. Write down everything about THIS MOMENT that a bug report needs,
	 * so the owner can say "something is wrong here" and the evidence comes
	 * with it.
	 *
	 * Reports used to arrive as a sentence — "nav is broken", "the overlay
	 * is on the wrong thing" — and the first hour of every fix went on
	 * reconstructing where he was and what the plugin believed. All of this
	 * is already in the client log; none of it is readable by the person who
	 * hit the problem.
	 *
	 * Runs on the client thread (the command arrives there), so every
	 * reading below is a consistent snapshot rather than a race.
	 */
	private void writeProblemReport()
	{
		Current current = findCurrent();
		WorldPoint me = playerPoint();
		StringBuilder out = new StringBuilder();
		out.append("IRONSCAPE problem report\n");
		out.append("guide      : ").append(activeVariant).append("\n");
		out.append("position   : ").append(progressManager.position(activeVariant)).append("\n");
		if (current != null)
		{
			out.append("step       : ").append(current.step.getGlobalIndex())
				.append("  ").append(current.step.getId()).append("\n");
			out.append("text       : ").append(current.step.getPlainText()).append("\n");
			out.append("sub        : ").append(current.sub.getId()).append("\n");
			Quest quest = stepQuest(current);
			out.append("quest      : ").append(quest == null ? "(none)"
				: quest.getName() + " - " + cachedQuestState(quest)).append("\n");
			// What we ACTUALLY asked the router to draw. This used to print
			// targetFor(), which is only ONE of the sources a route can come
			// from — on an errand step it showed a different point entirely
			// (2764,3513 plane 0 against the errand's 2763,3513 plane 1),
			// which cost a round of doubting the plane. A report that
			// disagrees with the decision it is reporting is worse than none.
			out.append("route posted: ").append(
				lastPostedTarget == null ? "(nothing)" : lastPostedTarget).append("\n");
			WorldPoint target = targetFor(current.step, current.sub);
			out.append("step target : ").append(target == null ? "(nowhere)" : target)
				.append("  (the step's own place; nav may prefer an errand leg)\n");
		}
		else
		{
			out.append("step       : (no current step)\n");
		}
		out.append("you are at : ").append(me == null ? "(unknown)" : me).append("\n");
		out.append("quest helper installed: ").append(questHelperInstalled()).append("\n");
		out.append("\nWhat the plugin decided most recently:\n");
		if (recentDecisions.isEmpty())
		{
			out.append("  (nothing yet this session)\n");
		}
		for (String line : recentDecisions)
		{
			out.append("  ").append(line).append("\n");
		}
		java.io.File dir = new java.io.File(
			net.runelite.client.RuneLite.RUNELITE_DIR, CONFIG_GROUP + "/reports");
		dir.mkdirs();
		// Named by STEP, then by how many reports that step already has.
		// One-file-per-step overwrote: the owner pressed this twice on the
		// same step ON PURPOSE — once where the route led, once standing
		// beside the NPC — and the second erased the first, losing exactly
		// the comparison he had gone to the trouble of capturing.
		String stem = "report-step-"
			+ (current == null ? "none" : String.valueOf(current.step.getGlobalIndex()));
		java.io.File file = new java.io.File(dir, stem + ".txt");
		for (int n = 2; file.exists() && n < 100; n++)
		{
			file = new java.io.File(dir, stem + "-" + n + ".txt");
		}
		try
		{
			java.nio.file.Files.write(file.toPath(),
				out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
			log.info("problem report written to {}", file);
			client.addChatMessage(ChatMessageType.CONSOLE, "",
				"IRONSCAPE: noted. Saved " + file.getName()
					+ " - mention it to Claude and it will be read.", null);
		}
		catch (IOException e)
		{
			log.warn("could not write problem report", e);
		}
	}
	private void remember(String line)
	{
		recentDecisions.addLast(line);
		while (recentDecisions.size() > 25)
		{
			recentDecisions.removeFirst();
		}
	}

	private void logNavDecision(String decision)
	{
		if (!decision.equals(lastNavDecision))
		{
			lastNavDecision = decision;
			log.info("auto-nav: {}", decision);
			remember("nav: " + decision);
		}
	}

	/** Last teleport-hint outcome logged, so only CHANGES print. */
	private String lastHintDecision;

	/**
	 * One INFO line per CHANGE of teleport-hint outcome — logNavDecision's
	 * counterpart. A hint has five possible sources (a clicked place link,
	 * a "minigame teleport to X" sub, the step's own 📍 Grouping area, the
	 * route-aware first leg, or the sub saying "home tele") and on screen
	 * they are indistinguishable, so "why is it pointing THERE?" was
	 * unanswerable from a screenshot.
	 */
	private void logHintDecision(String decision)
	{
		if (!decision.equals(lastHintDecision))
		{
			lastHintDecision = decision;
			log.info("teleport-hint: {}", decision);
			remember("hint: " + decision);
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

	/**
	 * A bank to visit FIRST when the sub's kit is owned-but-banked, else
	 * null. Checks TEXT goals and ANNOTATION items both — quest kits are
	 * annotations, so the old text-only check went blind the day kits
	 * were seeded ("Finish Imp catcher" with every bead in the bank
	 * routed straight to the Wizards' Tower). Coins are wealth, not
	 * cargo; optional items and bank-countable bulk never gate.
	 */
	private WorldPoint bankFirstTarget(Current current)
	{
		// A step still behind its SKILL gate is a grind, and its kit belongs
		// to the job AFTER it. "Train Runecraft to 10, then complete Temple
		// of the Eye" banks a bucket, chisel and pickaxe for the QUEST;
		// bank-first saw them banked and seized the route, and since it
		// suggests a bank only once per (step, bank), the route then sat
		// there and never came back (owner, in play).
		//
		// The grind still gets its bank leg — targetFor's training loop
		// sends the player to a bank whenever the TRAINING material has run
		// out, which is the trip that actually matters here. What stands
		// down is only the pull toward a kit the current job does not use.
		//
		// Scoped to steps naming a trainAt, so nothing else changes:
		// elsewhere an unmet skill requirement still earns its bank stop,
		// since there is nowhere better to send anyone and the kit is worth
		// collecting on the way.
		if (skillRequirementUnmet(current.sub)
			&& (annotationManager.getTrainAt(current.step.getId()) != null
				|| annotationManager.getTrainAt(current.sub.getId()) != null))
		{
			return null;
		}
		java.util.List<String[]> needs = new java.util.ArrayList<>();
		List<GoalDetector.ItemGoal> goals = itemGoalsBySub.get(current.sub.getId());
		if (goals != null)
		{
			for (GoalDetector.ItemGoal goal : goals)
			{
				// The quest hands this one over, so there is nothing in any
				// bank to withdraw and no stop to justify.
				if (annotationManager.isGranted(goal.getItemName(),
					current.step.getId(), current.sub.getId()))
				{
					continue;
				}
				needs.add(new String[]{goal.getItemName(), String.valueOf(goal.getQuantity())});
			}
		}
		for (String annotationId : new String[]{current.step.getId(), current.sub.getId()})
		{
			for (StepAnnotation.ItemNeed need : annotationManager.getItems(annotationId))
			{
				if (Boolean.TRUE.equals(need.optional) || Boolean.TRUE.equals(need.granted))
				{
					continue;
				}
				// UNSPECIFIED quantity is the guide's carry list, not a
				// requirement — a banked pickaxe on the Biohazard step sent
				// the player to a SECOND bank right after the first one.
				// Soft reminders don't get to hijack the route.
				if (need.quantity == null)
				{
					continue;
				}
				needs.add(new String[]{need.name, String.valueOf(need.quantity)});
			}
		}
		if (needs.isEmpty())
		{
			return null;
		}
		// ANY banked shortfall justifies the stop — items you don't own at
		// all don't cancel it (the missing notes vetoed a bank the player
		// stood beside while the beads sat inside). Once withdrawn, the
		// shortfall clears and routing continues to the destination.
		boolean anyBankedShortfall = false;
		for (String[] need : needs)
		{
			String name = need[0];
			int quantity = Integer.parseInt(need[1]);
			if (ItemTracker.nameMatchesGoal("coins", name)
				|| itemTracker.bankCountable(name, quantity))
			{
				continue;
			}
			anyBankedShortfall |= itemTracker.carriedCountOf(name) < quantity
				&& itemTracker.countOf(name) >= quantity;
		}
		return anyBankedShortfall ? nearestBank() : null;
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
		WorldPoint bankFirst = bankFirstTarget(window.get(0));
		if (bankFirst != null)
		{
			// Remember that a BANK is why we are routing here, so the tick
			// handler knows to look again. Withdrawing the kit completes
			// nothing, and every other trigger is an event — death, login,
			// a teleport, a stage change, progress — so with the items in
			// hand the route simply stayed pointed at the bank until the
			// owner pressed Go (2026-08-09, in play, Doric's quest).
			navRoutedToBank = true;
			return bankFirst;
		}
		navRoutedToBank = false;

		// "Use the spirit tree and go to X": the journey STARTS at the
		// nearest spirit tree, not at the far-off destination — Shortest
		// Path would otherwise draw the long walk. After the teleport
		// lands, the reroute finds the destination close (or the tree
		// beside you) and guidance continues normally.
		Current frontier = window.get(0);
		if (SPIRIT_TREE.matcher(frontier.sub.getPlainText()).find())
		{
			WorldPoint destination = targetFor(frontier.step, frontier.sub);
			WorldPoint me = playerPoint();
			if (me != null && (destination == null
				|| me.distanceTo2D(destination) > 40))
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
			WorldPoint me = playerPoint();
			if (me != null && (destination == null
				|| me.distanceTo2D(destination) > 40))
			{
				WorldPoint dock = nearestOf(CHARTER_DOCKS);
				if (dock != null && me.distanceTo2D(dock)
					< (destination == null ? Integer.MAX_VALUE
						: me.distanceTo2D(destination)))
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
		WorldPoint here = playerPoint();
		if (here == null)
		{
			return null;
		}
		// Same band rule as firstLegTowards: standing in the rune essence
		// mine (y≈4830), the ZANARIS bank chest (y=4459) read as 370 tiles
		// off while every surface bank read as 1,300+, so "nearest bank"
		// sent the player to Zanaris. Compare only within your own band —
		// nothing to compare against beats a confident wrong answer.
		boolean onSurface = here.getY() < SURFACE_MAX_Y;
		WorldPoint best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (WorldPoint point : points)
		{
			if ((point.getY() < SURFACE_MAX_Y) != onSurface)
			{
				continue;
			}
			int distance = here.distanceTo2D(point);
			if (distance < bestDistance)
			{
				bestDistance = distance;
				best = point;
			}
		}
		// ...and the winner still has to be PLAUSIBLE. The band rule keeps
		// us from comparing across the surface/underground divide, but it
		// treats all of "underground" as one place: from the Shilo Village
		// caverns every surface bank was filtered out and the Zanaris chest
		// won by being the last candidate standing, ~4,900 tiles away and
		// behind a dramen staff the player did not have. The router dutifully
		// planned a home teleport, a boat and three ladders and then said
		// "Destination could not be reached" (owner, in play 2026-08-12).
		//
		// A bank this far off is not a detour on the way somewhere, it IS
		// the journey — so answer nothing, which the callers already treat
		// as "no bank stop". Underground that usually means surfacing first,
		// which is correct: we cannot measure a route out of a cavern.
		if (best != null && bestDistance > MAX_SENSIBLE_DETOUR)
		{
			return null;
		}
		return best;
	}

	/**
	 * Beyond this (straight-line tiles) a "nearest" anything is not a
	 * detour, and a confident wrong answer is worse than none.
	 */
	private static final int MAX_SENSIBLE_DETOUR = 1000;

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
		// A step that says "train X to N, THEN do the quest" is two jobs,
		// and the guide is atomic so it cannot be split. While the level is
		// missing the quest is not startable at all — Temple of the Eye
		// needs Runecraft 10 and is not boostable — so route to the
		// training spot, and stop the moment the level lands.
		if (skillRequirementUnmet(sub))
		{
			StepAnnotation.Target train = annotationManager.getTrainAt(step.getId());
			if (train == null)
			{
				train = annotationManager.getTrainAt(sub.getId());
			}
			if (train != null)
			{
				// The loop, not just the spot: with materials in the bag the
				// place to be is the altar, with none it is the nearest bank.
				// Pointing at the altar while the player stands on it holding
				// nothing is the half of the job a pin cannot express.
				String with = annotationManager.getTrainWith(step.getId());
				if (with == null)
				{
					with = annotationManager.getTrainWith(sub.getId());
				}
				if (with != null && itemTracker.carriedCountOf(with) <= 0)
				{
					WorldPoint bank = nearestBank();
					if (bank != null)
					{
						return bank;
					}
				}
				return new WorldPoint(train.x, train.y, train.plane);
			}
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
		String routableText = withoutStoppingPoint(sub.getPlainText());
		WorldPoint inText = travelGoalSubs.contains(sub.getId())
			? placeManager.lastPlaceIn(routableText)
			: placeManager.firstPlaceIn(routableText);
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

	/**
	 * "…until you need to go to X" names where you STOP, not where to go.
	 *
	 * "Continue Lost tribe until you need to go to the goblin village" is
	 * tagged 📍Varrock and the work IS in Varrock — but the only place name
	 * in the sentence is the one you leave for, so the route pointed 268
	 * tiles away, at the thing the step ends before reaching. Dropping the
	 * clause lets it fall back to the step's own area tag.
	 *
	 * Phrase-exact rather than a rule about "until": measured across the
	 * guide, 27 steps say "until" and exactly TWO have this shape, both in
	 * the Lost Tribe pair. The other 25 are level and quantity targets
	 * ("chin until 70 range"), which name no place and must not be touched.
	 */
	private static final java.util.regex.Pattern STOPPING_POINT =
		java.util.regex.Pattern.compile(
			"(?i)\\buntil\\s+you\\s+(?:need|have)\\s+to\\b.*$");

	private static String withoutStoppingPoint(String text)
	{
		return text == null ? null : STOPPING_POINT.matcher(text).replaceFirst("");
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
					postPath(nearest, true);
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
					+ "\" - stand there and add it with the panel's + button.", null));
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
					postPath(point, true);
				}
				else if (questIsTheTask && state == QuestState.IN_PROGRESS)
				{
					// No programmatic Quest Helper handoff: QH exposes no
					// public API, and the Plugin Hub forbids reflection —
					// pointing the player at it is the compliant version.
					client.addChatMessage(ChatMessageType.CONSOLE, "",
						"IRONSCAPE: " + quest.getName()
							+ " is in progress - its start point isn't where you need to go. "
							+ "Select it in Quest Helper for step-by-step guidance.",
						null);
				}
				else
				{
					// Finished quest, or a landmark reference on some other
					// step — the name means the PLACE now, so just route.
					postPath(point, true);
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
				postPath(new WorldPoint(stage.x, stage.y, stage.plane), true);
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
			postPath(point, true);
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
		// The player just clicked something — always redraw, even if it is
		// the route already on screen.
		clientThread.invokeLater(() -> postPath(point, true));
	}

	/**
	 * Ask Shortest Path to route to a point, and ask it to tell us which
	 * transports it picked on the way.
	 *
	 * The "config" key is Shortest Path's documented per-request config
	 * override. We set "postTransports" because it defaults to OFF (it
	 * lives in their Debug section) — without it they never answer, and
	 * we are not making the user go and find a setting. It only controls
	 * whether they post the message; it does not change the route they
	 * draw.
	 *
	 * It must go on EVERY path post, not once at startup: their override
	 * map is wiped by any "clear" message, including our own.
	 */
	private void postPath(WorldPoint target)
	{
		postPath(target, false);
	}

	/**
	 * The last target we asked Shortest Path to draw, so we do not ask
	 * again for the one it is already drawing.
	 */
	private WorldPoint lastPostedTarget;

	/**
	 * Ask Shortest Path to route to a point.
	 *
	 * <p>UNCHANGED TARGETS ARE NOT RE-POSTED. This method is reached from a
	 * re-check that runs every 10 ticks, and it used to post every time
	 * even when the decision had not moved — logNavDecision only prints on
	 * CHANGE, so the route was being overwritten every six seconds with
	 * nothing in the log to show it. The owner saw it as Quest Helper's
	 * route surviving for a few seconds after "reload quest" and then being
	 * replaced by ours, which in his case could not even be drawn
	 * ("Destination could not be reached"). Wave 20 fixed this shape for
	 * the bank nudge alone; it was general all along.
	 *
	 * <p>{@code force} is for the routes that are MEANT to reassert over
	 * whatever else is on screen — a gravestone, an active errand leg, and
	 * anything the player just clicked. Those keep their designed
	 * precedence over Quest Helper (waves 4, 7, 8, 20).
	 */
	private void postPath(WorldPoint target, boolean force)
	{
		if (!force && target != null && target.equals(lastPostedTarget))
		{
			return;
		}
		lastPostedTarget = target;
		// "source" names US in GPS's route header, which otherwise reads
		// "another plugin" — so when a route appears you can tell whether
		// it came from this guide or from Quest Helper. Shortest Path reads
		// only the keys it knows and ignores this one, so the same message
		// serves both.
		//
		// We keep posting on the "shortestpath" channel deliberately. GPS
		// is a FORK of Shortest Path and accepts that namespace as a
		// documented compatibility alias, so one message drives whichever
		// the player has installed. Targeting GPS's own "gps" namespace
		// would work for GPS users and silently do nothing for everyone
		// else.
		eventBus.post(new PluginMessage("shortestpath", "path",
			Map.of("target", target,
				"source", "IRONSCAPE Optimal",
				"config", Map.of("postTransports", true))));
	}

	/**
	 * Tell Shortest Path to stop drawing. Caller is already on the client
	 * thread (unlike {@link #clearPath}, which hops onto it first).
	 */
	/**
	 * Stop drawing OUR route — and only ours.
	 *
	 * <p>"Clear" wipes whatever the pathing plugin is showing, no matter
	 * who asked for it. Standing down for Quest Helper ran this on every
	 * evaluation, so a route QH had set was deleted the moment anything
	 * made us re-evaluate; the owner saw it as QH's navigation dying when
	 * he took the teleport QH had told him to take, and coming back when
	 * he hit "reload quest" (in play, 2026-08-12).
	 *
	 * <p>So a clear only happens when we have something posted to clear.
	 * Standing down still removes a stale route of ours exactly once, and
	 * then stays quiet instead of talking over whoever owns the screen.
	 */
	private void postClear()
	{
		if (lastPostedTarget == null)
		{
			// Nothing of ours on screen — anything showing belongs to
			// another plugin, and is not ours to erase. Note we do NOT
			// forget spRoute here: the route still being drawn is somebody
			// else's, and its chosen first leg is exactly what we want to
			// highlight. Nulling it unconditionally meant every stand-down
			// (which runs each evaluation) threw the answer away, so the
			// highlight could never fire on a Quest Helper route.
			return;
		}
		spRoute = null;
		lastPostedTarget = null;
		eventBus.post(new PluginMessage("shortestpath", "clear"));
		// A clear also wipes the config override that makes the router
		// report its choices, so switch it straight back on.
		enableRouteReporting();
	}

	/**
	 * Ask the routing plugin to keep telling us which transports it picks,
	 * WITHOUT asking it to draw anything.
	 *
	 * <p>Reporting is off by default and we enable it through the config
	 * override that rides on a path request — but we only send those when
	 * WE own the route. On a Quest Helper step we never post, so it was
	 * never switched on, and the highlight had nothing to follow (owner, in
	 * play: GPS listing "Use Ardougne cloak: Kandarin Monastery" while our
	 * overlay stayed dark).
	 *
	 * <p>A path message carrying only a config is honoured and then
	 * returns before it needs a target — documented behaviour in both
	 * Shortest Path and GPS — so this sets the flag and draws nothing.
	 */
	private void enableRouteReporting()
	{
		eventBus.post(new PluginMessage("shortestpath", "path",
			Map.of("config", Map.of("postTransports", true))));
	}

	/**
	 * One transport on the route Shortest Path actually chose — where you
	 * board it, where it puts you, the object to click, and the option to
	 * pick ("Ardougne cloak: Kandarin Monastery", "Barbarian Assault
	 * Minigame Teleport", "Travel Spirit tree").
	 */
	static final class SpLeg
	{
		final WorldPoint origin;
		final WorldPoint destination;
		final String objectInfo;
		final String displayInfo;

		SpLeg(WorldPoint origin, WorldPoint destination, String objectInfo, String displayInfo)
		{
			this.origin = origin;
			this.destination = destination;
			this.objectInfo = objectInfo;
			this.displayInfo = displayInfo;
		}

		@Override
		public String toString()
		{
			String what = displayInfo != null ? displayInfo : objectInfo;
			return (what == null ? "transport" : what) + " " + origin + " -> " + destination;
		}
	}

	/**
	 * The transports on Shortest Path's chosen route, in travel order, or
	 * an empty list when it routed us on foot. Empty is MEANINGFUL — it is
	 * SP saying "no transport needed" — so it is distinct from null, which
	 * means SP has not answered (not installed, or too old).
	 *
	 * Written from Shortest Path's pathfinding thread, so volatile; read
	 * from the client thread. Nothing here touches game state.
	 */
	private volatile List<SpLeg> spRoute = null;

	/**
	 * Shortest Path answering with the route it picked. We asked for this
	 * in {@link #postPath} — see the note there about why it needs
	 * switching on.
	 *
	 * This is the antidote to computing a rival journey of our own: our
	 * first-leg hint exists only to highlight the button to click, and SP
	 * has already decided WHICH transport, knowing the player's real
	 * unlocks, items and quest state.
	 *
	 * NOTE: this arrives on Shortest Path's pathfinding worker thread, not
	 * the client thread. Store and log only.
	 */
	@Subscribe
	public void onPluginMessage(PluginMessage event)
	{
		if (!"shortestpath".equals(event.getNamespace()) || !"transports".equals(event.getName()))
		{
			return;
		}
		Map<String, Object> data = event.getData();
		if (data == null)
		{
			return;
		}
		List<WorldPoint> origins = pointList(data.get("origin"));
		List<WorldPoint> destinations = pointList(data.get("destination"));
		List<String> objectInfos = stringList(data.get("objectInfo"));
		List<String> displayInfos = stringList(data.get("displayInfo"));

		// Four parallel lists from another plugin. Count the legs by the
		// LONGEST, not the shortest, and index-guard every read.
		//
		// Keying on origin lost whole routes: a home teleport can be cast
		// anywhere, so it has no origin, and min() collapsed a route that was
		// nothing but a home teleport to ZERO legs — reported to us as
		// "walking the whole way" while GPS's own panel read "1. Use
		// Lumbridge Home Teleport" (owner, in play, wave 27). The LABEL is
		// what we act on; coordinates are extra, and every consumer already
		// null-checks them.
		int legs = Math.max(Math.max(origins.size(), destinations.size()),
			Math.max(objectInfos.size(), displayInfos.size()));
		List<SpLeg> route = new ArrayList<>(legs);
		for (int i = 0; i < legs; i++)
		{
			route.add(new SpLeg(
				i < origins.size() ? origins.get(i) : null,
				i < destinations.size() ? destinations.get(i) : null,
				i < objectInfos.size() ? objectInfos.get(i) : null,
				i < displayInfos.size() ? displayInfos.get(i) : null));
		}
		spRoute = route;
		// Say so when the lists disagree: that mismatch is exactly what hid
		// the home teleport, and it is invisible from the route alone.
		if (origins.size() != displayInfos.size()
			|| destinations.size() != displayInfos.size())
		{
			log.info("router-choice: leg lists disagree — {} origins, {} destinations,"
					+ " {} objectInfos, {} displayInfos",
				origins.size(), destinations.size(), objectInfos.size(), displayInfos.size());
		}
		logHintDecision("shortest path chose: "
			+ (route.isEmpty() ? "no transport (walking the whole way)" : route.toString()));
	}

	/**
	 * The teleport ITEM Shortest Path chose as the first leg of the route it
	 * is currently drawing, or null.
	 *
	 * Only the FIRST transport counts: that is the one you act on now, and
	 * highlighting a later leg would point at a button for a journey you
	 * have not started.
	 *
	 * The possession check is not redundant. Shortest Path can be set to
	 * count teleport items in the BANK (the owner's is
	 * INVENTORY_AND_BANK), so it will happily route through a glory sitting
	 * in a bank — which is a fine route and a useless highlight.
	 * Client thread: reads the inventory.
	 */
	/**
	 * Worn-equipment panel components, indexed by the game's equipment slot
	 * id. The ids are NOT contiguous (there is no slot 6, 8 or 11), which is
	 * why this is a lookup rather than arithmetic — and the Chronicle's
	 * existing shield hint at SLOT5 is what confirms the mapping.
	 */
	private static final int[] WORN_SLOT_COMPONENTS = buildWornSlotComponents();

	private static int[] buildWornSlotComponents()
	{
		int[] slots = new int[14];
		java.util.Arrays.fill(slots, -1);
		slots[0] = net.runelite.api.gameval.InterfaceID.Wornitems.SLOT0;
		slots[1] = net.runelite.api.gameval.InterfaceID.Wornitems.SLOT1;
		slots[2] = net.runelite.api.gameval.InterfaceID.Wornitems.SLOT2;
		slots[3] = net.runelite.api.gameval.InterfaceID.Wornitems.SLOT3;
		slots[4] = net.runelite.api.gameval.InterfaceID.Wornitems.SLOT4;
		slots[5] = net.runelite.api.gameval.InterfaceID.Wornitems.SLOT5;
		slots[7] = net.runelite.api.gameval.InterfaceID.Wornitems.SLOT7;
		slots[9] = net.runelite.api.gameval.InterfaceID.Wornitems.SLOT9;
		slots[10] = net.runelite.api.gameval.InterfaceID.Wornitems.SLOT10;
		slots[12] = net.runelite.api.gameval.InterfaceID.Wornitems.SLOT12;
		slots[13] = net.runelite.api.gameval.InterfaceID.Wornitems.SLOT13;
		return slots;
	}

	/**
	 * The worn-panel component holding this teleport item, or -1 when it is
	 * not being worn (in which case the inventory outline already shows it
	 * on a tab you are probably looking at).
	 * Client thread.
	 */
	private int wornSlotComponentFor(com.ironscape.travel.TeleportItems.Entry entry)
	{
		net.runelite.api.ItemContainer worn =
			client.getItemContainer(net.runelite.api.gameval.InventoryID.WORN);
		if (worn == null)
		{
			return -1;
		}
		for (int slot = 0; slot < WORN_SLOT_COMPONENTS.length; slot++)
		{
			if (WORN_SLOT_COMPONENTS[slot] == -1)
			{
				continue;
			}
			net.runelite.api.Item item = worn.getItem(slot);
			if (item == null)
			{
				continue;
			}
			for (int id : entry.getItemIds())
			{
				if (item.getId() == id)
				{
					return WORN_SLOT_COMPONENTS[slot];
				}
			}
		}
		return -1;
	}

	/**
	 * A pet or familiar — never a guide target, however close it is.
	 * Client thread.
	 */
	private boolean isPet(net.runelite.api.NPC npc)
	{
		if (npc == null)
		{
			return false;
		}
		net.runelite.api.NPC follower = client.getFollower();
		if (follower != null && follower.getIndex() == npc.getIndex())
		{
			return true;
		}
		net.runelite.api.NPCComposition composition = npc.getComposition();
		return composition != null && composition.isFollower();
	}

	/**
	 * Highlight whatever the routing plugin picked as the FIRST leg of the
	 * route it is drawing — a teleport item, a spellbook teleport, the home
	 * teleport, or a Grouping minigame teleport.
	 *
	 * Highlighting the button is the one thing the router cannot do for
	 * itself, and following its choice is never a rival opinion: it IS the
	 * line on screen. Originally this covered teleport ITEMS only, so a
	 * route that chose a spell or the home teleport lit nothing at all
	 * (owner, in play with GPS: "GPS wants to take me to Lumbridge, but
	 * there are no overlays in our TP book").
	 *
	 * @return true when something was highlighted.
	 * Client thread.
	 */
	private boolean applyRouterChoice()
	{
		List<SpLeg> route = spRoute;
		if (route == null)
		{
			// De-duplicated by logHintDecision, so this is one line per
			// change, not per tick. Three rounds were spent on this feature
			// guessing which of these cases applied.
			logRouterChoice("the router has not told us its route");
			return false;
		}
		if (route.isEmpty())
		{
			logRouterChoice("the router chose no transport (walking)");
			return false;
		}
		// Only the FIRST transport: that is the one to act on now, and
		// highlighting a later leg points at a button for a journey you
		// have not started.
		SpLeg first = route.get(0);
		// A TRIVIALLY SHORT HOP IS NOT WORTH A BUTTON. The router rates a
		// teleport as very nearly free, so it will pick one to cross a town:
		// standing in Falador it chose Falador Teleport, a 33-tile jump from
		// 2995,3366 to 2964,3378, and we lit up the spell (owner, in play,
		// wave 27 — "it wants me to tp to Falador, I'm in Falador").
		//
		// We do NOT argue with the route — the router still draws whatever it
		// likes. We simply decline to point at a button that saves less than
		// the floor the owner set for our own suggestions. Same number, so a
		// hint means the same thing whoever proposed it.
		// A LEG YOU HAVE ALREADY TAKEN IS NOT A HINT. The router republishes
		// when its DISPLAYED route changes, but arriving is not such a change
		// — so its last word stands, and we went on highlighting a Lumbridge
		// home teleport while the player was deep in the tunnels under
		// Lumbridge, long past using it (owner, in play, wave 27).
		//
		// The leg's origin is where you board or cast it. Stray far from
		// there and the leg is behind you (or not yet reachable), either way
		// not the button to press now. A leg with no origin can be used
		// anywhere, so nothing can be concluded and it still shows.
		WorldPoint standing = playerPoint();
		if (first.origin != null && standing != null
			&& standing.distanceTo2D(first.origin) > ROUTER_LEG_ORIGIN_RADIUS)
		{
			logRouterChoice("first leg starts " + standing.distanceTo2D(first.origin)
				+ " tiles away — already taken it, or not there yet");
			return false;
		}
		if (first.origin != null && first.destination != null
			&& first.origin.distanceTo2D(first.destination) < MIN_TILES_SAVED)
		{
			logRouterChoice("first leg only covers "
				+ first.origin.distanceTo2D(first.destination)
				+ " tiles — not worth a teleport (floor is " + MIN_TILES_SAVED + ")");
			return false;
		}
		String display = first.displayInfo;
		if (display == null)
		{
			logRouterChoice("first leg has no label to match");
			return false;
		}
		com.ironscape.travel.TeleportItems.Entry item = teleportItemChosenBySp();
		if (item != null)
		{
			activeTeleportItem = item;
			activeSpellTeleport = -1;
			routeHomeTeleportHint = false;
			activeMinigameTarget = null;
			return true;
		}
		// "Lumbridge Home Teleport", and the other spellbooks' equivalents.
		if (display.toLowerCase(Locale.ROOT).contains("home teleport"))
		{
			routeHomeTeleportHint = true;
			activeSpellTeleport = -1;
			activeTeleportItem = null;
			activeMinigameTarget = null;
			return true;
		}
		// "Barbarian Assault Minigame Teleport" -> the Grouping click path.
		String minigameSuffix = " Minigame Teleport";
		if (display.endsWith(minigameSuffix))
		{
			activeMinigameTarget = display.substring(0, display.length() - minigameSuffix.length());
			activeSpellTeleport = -1;
			routeHomeTeleportHint = false;
			activeTeleportItem = null;
			return true;
		}
		// "Varrock Teleport", and variants that name a destination after a
		// colon ("Varrock Teleport: GE") — the same spell either way.
		String spellName = display.contains(":")
			? display.substring(0, display.indexOf(':')).trim() : display;
		for (TeleportSpell spell : TELEPORT_SPELLS)
		{
			if (spell.name.equalsIgnoreCase(spellName))
			{
				activeSpellTeleport = spell.component;
				routeHomeTeleportHint = false;
				activeTeleportItem = null;
				activeMinigameTarget = null;
				return true;
			}
		}
		// Nothing we can point at. Naming the label matters: it is either a
		// transport with no button to click (a boat, a gate) or one whose
		// name we failed to match, and those want opposite fixes.
		logRouterChoice("nothing to highlight for \"" + display + "\"");
		return false;
	}

	/** Last router-choice explanation, so it logs on change rather than per tick. */
	private String lastRouterChoiceNote;

	private void logRouterChoice(String note)
	{
		if (!note.equals(lastRouterChoiceNote))
		{
			lastRouterChoiceNote = note;
			log.info("router-choice: {}", note);
		}
	}

	private com.ironscape.travel.TeleportItems.Entry teleportItemChosenBySp()
	{
		List<SpLeg> route = spRoute;
		if (route == null || route.isEmpty())
		{
			return null;
		}
		com.ironscape.travel.TeleportItems.Entry entry =
			teleportItemIndex.byDisplay(route.get(0).displayInfo);
		if (entry == null)
		{
			return null;
		}
		for (int id : entry.getItemIds())
		{
			if (itemTracker.carriedCountOfId(id) > 0)
			{
				return entry;
			}
		}
		return null;
	}

	private static List<WorldPoint> pointList(Object value)
	{
		List<WorldPoint> points = new ArrayList<>();
		if (value instanceof List<?>)
		{
			for (Object item : (List<?>) value)
			{
				if (item instanceof WorldPoint)
				{
					points.add((WorldPoint) item);
				}
			}
		}
		return points;
	}

	private static List<String> stringList(Object value)
	{
		List<String> strings = new ArrayList<>();
		if (value instanceof List<?>)
		{
			for (Object item : (List<?>) value)
			{
				strings.add(item instanceof String ? (String) item : null);
			}
		}
		return strings;
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
			WorldPoint where = realPoint(player);
			placeManager.add(placeName, where);
			// PLANE included (⌖ capture already does): an upstairs pin read
			// back as a ground-floor one is silently wrong, and "which floor
			// is that bank booth on" is exactly what this gets used for.
			client.addChatMessage(ChatMessageType.CONSOLE, "",
				"IRONSCAPE: place '" + placeName + "' saved at ("
					+ where.getX() + ", " + where.getY()
					+ (where.getPlane() != 0 ? ", plane " + where.getPlane() : "")
					+ ").", null);
			SwingUtilities.invokeLater(() -> onDone.accept(true));
		});
	}

	/**
	 * Off-thread caller's version of {@link #postClear} — same rule about
	 * only erasing our own route, hopped onto the client thread. Kept as
	 * one behaviour rather than two, because a second copy of "when may we
	 * clear" is exactly the drift that produces these faults.
	 */
	private void clearPath()
	{
		clientThread.invokeLater(this::postClear);
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

	/**
	 * Every teleport an ITEM can provide (diary cloaks, jewellery, tablets),
	 * from Shortest Path's own maintained table. The hint used to know only
	 * minigames, spells, the home teleport and the Chronicle, so it offered
	 * a Varrock teleport for a West Ardougne target while an Ardougne cloak
	 * that lands next door sat in the bag (owner, 2026-08-11).
	 */
	// Starts EMPTY and is loaded in startUp with the client's injected Gson.
	// It used to initialise with `new Gson()`, which the Plugin Hub rejects
	// outright ("Do not create fresh Gson instances, always @Inject the
	// client's Gson") — a rule our own build does not enforce, so it passed
	// locally and failed the hub's check. The initialiser was redundant
	// anyway: startUp and ::ironreload both reload this properly.
	private com.ironscape.travel.TeleportItems teleportItemIndex =
		com.ironscape.travel.TeleportItems.empty();

	/**
	 * The teleport item the hint is pointing at, or null. Rebuilt per tick
	 * on the client thread and read by the overlay.
	 */
	private volatile com.ironscape.travel.TeleportItems.Entry activeTeleportItem;

	/**
	 * Game state for the teleport index. Kept as one object rather than
	 * passing the client in, so the index stays testable without a game.
	 * Every read here is client-thread only, which is where the hint runs.
	 */
	private final com.ironscape.travel.TeleportItems.Availability itemAvailability =
		new com.ironscape.travel.TeleportItems.Availability()
		{
			@Override
			public boolean carries(int itemId)
			{
				return itemTracker.carriedCountOfId(itemId) > 0;
			}

			@Override
			public int varbit(int id)
			{
				return client.getVarbitValue(id);
			}

			@Override
			public int varplayer(int id)
			{
				return client.getVarpValue(id);
			}

			@Override
			public int skillLevel(String skill)
			{
				try
				{
					return client.getRealSkillLevel(Skill.valueOf(skill));
				}
				catch (IllegalArgumentException e)
				{
					// Not a skill name. Fail closed — see TeleportItems.
					return -1;
				}
			}

			@Override
			public int totalLevel()
			{
				return client.getTotalLevel();
			}

			@Override
			public int questPoints()
			{
				return client.getVarpValue(net.runelite.api.gameval.VarPlayerID.QP);
			}

			@Override
			public boolean questFinished(String questName)
			{
				Quest quest = questByName(questName);
				// An unknown quest name fails CLOSED: withholding a hint
				// costs nothing, offering a teleport the player cannot make
				// sends them looking for an item they do not have.
				return quest != null && cachedQuestState(quest) == QuestState.FINISHED;
			}
		};

	/**
	 * Every NPC each quest involves, by id, keyed by a NORMALISED quest
	 * name. Normalised because the index is keyed by the guide's wording
	 * while lookups come from RuneLite's Quest enum, and the two disagree
	 * about articles, punctuation and case as a matter of course.
	 */
	private final Map<String, java.util.Set<Integer>> questNpcIds = new HashMap<>();

	private static String questKey(String name)
	{
		return name == null ? null
			: name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
	}

	/** The quest's cast, or an empty set meaning "we have no index for it". */
	private java.util.Set<Integer> questNpcIdsFor(String questName)
	{
		String key = questKey(questName);
		if (key == null)
		{
			return java.util.Collections.emptySet();
		}
		return questNpcIds.getOrDefault(key, java.util.Collections.emptySet());
	}

	private void loadQuestNpcs()
	{
		questNpcIds.clear();
		try (java.io.InputStream in = DataFiles.open(IronscapePlugin.class, "quest_npcs.json"))
		{
			if (in == null)
			{
				return;
			}
			com.google.gson.JsonObject root = gson.fromJson(
				new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8),
				com.google.gson.JsonObject.class);
			com.google.gson.JsonObject byQuest = root.getAsJsonObject("questNpcs");
			for (String quest : byQuest.keySet())
			{
				java.util.Set<Integer> ids = new java.util.HashSet<>();
				for (com.google.gson.JsonElement id : byQuest.getAsJsonArray(quest))
				{
					ids.add(id.getAsInt());
				}
				questNpcIds.put(questKey(quest), ids);
			}
			log.debug("Loaded NPC rosters for {} quests", questNpcIds.size());
		}
		catch (Exception e)
		{
			log.warn("Could not read the quest NPC index", e);
		}
	}

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

	/** The free home teleport's 30-minute cooldown (varp 892, same scheme). */
	private boolean homeTeleportOnCooldown()
	{
		int lastUseMinutes = client.getVarpValue(net.runelite.api.VarPlayer.LAST_HOME_TELEPORT);
		return System.currentTimeMillis() - lastUseMinutes * 60_000L < 30 * 60_000L;
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
		/** Elemental cost: air, water, earth, fire — a staff can supply these. */
		final int air;
		final int water;
		final int earth;
		final int fire;
		final WorldPoint destination;
		final Quest requiredQuest;

		TeleportSpell(String name, int component, int level, int laws,
			int air, int water, int earth, int fire,
			WorldPoint destination, Quest requiredQuest)
		{
			this.name = name;
			this.component = component;
			this.level = level;
			this.laws = laws;
			this.air = air;
			this.water = water;
			this.earth = earth;
			this.fire = fire;
			this.destination = destination;
			this.requiredQuest = requiredQuest;
		}
	}

	private static final TeleportSpell[] TELEPORT_SPELLS = {
		//                                              lvl law air wat ear fir
		new TeleportSpell("Varrock Teleport",
			net.runelite.api.gameval.InterfaceID.MagicSpellbook.VARROCK_TELEPORT,
			25, 1, 3, 0, 0, 1, new WorldPoint(3213, 3424, 0), null),
		new TeleportSpell("Lumbridge Teleport",
			net.runelite.api.gameval.InterfaceID.MagicSpellbook.LUMBRIDGE_TELEPORT,
			31, 1, 3, 0, 1, 0, new WorldPoint(3222, 3218, 0), null),
		new TeleportSpell("Falador Teleport",
			net.runelite.api.gameval.InterfaceID.MagicSpellbook.FALADOR_TELEPORT,
			37, 1, 3, 1, 0, 0, new WorldPoint(2965, 3379, 0), null),
		new TeleportSpell("Camelot Teleport",
			net.runelite.api.gameval.InterfaceID.MagicSpellbook.CAMELOT_TELEPORT,
			45, 1, 5, 0, 0, 0, new WorldPoint(2757, 3479, 0), null),
		new TeleportSpell("Ardougne Teleport",
			net.runelite.api.gameval.InterfaceID.MagicSpellbook.ARDOUGNE_TELEPORT,
			51, 2, 0, 2, 0, 0, new WorldPoint(2662, 3305, 0), Quest.PLAGUE_CITY),
		new TeleportSpell("Watchtower Teleport",
			net.runelite.api.gameval.InterfaceID.MagicSpellbook.WATCHTOWER_TELEPORT,
			58, 2, 0, 0, 2, 0, new WorldPoint(2547, 3113, 0), Quest.WATCHTOWER),
	};

	/**
	 * One chosen first leg toward a far target: a Grouping minigame, a
	 * spell, the free home teleport, or a teleport ITEM already in the bag
	 * (diary cloak, jewellery, tablet).
	 */
	private static final class FirstLeg
	{
		final String minigame;
		final TeleportSpell spell;
		final boolean home;
		final com.ironscape.travel.TeleportItems.Entry item;
		/**
		 * The numbers that WON, purely so the log can show its working.
		 *
		 * A hint fired for a Varrock teleport toward West Ardougne while
		 * the player was ~350 tiles away and the landing is ~750 from the
		 * target — which the rule should have rejected. The reason line
		 * quoted the player distance only, so the two numbers that decide
		 * it were both invisible and there was nothing to check the claim
		 * against (owner, in play, 2026-08-11).
		 */
		int legDistance;
		int mustBeat;

		FirstLeg(String minigame, TeleportSpell spell, boolean home,
			com.ironscape.travel.TeleportItems.Entry item)
		{
			this.minigame = minigame;
			this.spell = spell;
			this.home = home;
			this.item = item;
		}
	}

	/** Where the free (standard-book) home teleport lands. */
	private static final WorldPoint HOME_TELEPORT_LANDING = new WorldPoint(3222, 3218, 0);

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
	 * How far a NAMED landing really is from the target — walked tiles from
	 * the bundled table, or the straight-line metric above when the table
	 * cannot answer.
	 *
	 * This is the P1-08 fix. Ranking landings by straight line offered a
	 * Burthorpe Games Room teleport toward Keep Le Faye, ~145 tiles away
	 * against the Fishing Trawler's ~240 — but Keep Le Faye sits behind White
	 * Wolf Mountain, so those walks are 476 and 405. The owner took the hint
	 * and landed in a corner. It is wave 10's distance fiction again with a
	 * mountain in place of a plane offset, and it covers the Ardougne wall,
	 * every river and every island. Scored against full-resolution truth over
	 * 340 (player, target) pairs from the guide's own pins, this names the
	 * right landing 84% of the time where the straight line managed 64%.
	 *
	 * The spirit-tree network layers on exactly as effectiveDistance does it,
	 * but in walked tiles: the five trees interconnect, so a landing near any
	 * tree reaches the tree nearest the target. That needs a leg TO a tree and
	 * another FROM one, which is why the trees are table origins too.
	 *
	 * Returns MAX_VALUE for a landing with no ungated route to the target —
	 * a Mos Le'Harmless landing can never win a first leg to the mainland,
	 * which is the barrier case stated as a distance.
	 */
	private int legDistance(String origin, WorldPoint landing, WorldPoint target, boolean walked)
	{
		if (!walked)
		{
			return effectiveDistance(landing, target);
		}
		int direct = travelDistances.distance(origin, target);
		int best = direct == com.ironscape.travel.TravelDistances.UNKNOWN
			? Integer.MAX_VALUE : direct;
		if (cachedQuestState(Quest.TREE_GNOME_VILLAGE) != QuestState.FINISHED)
		{
			return best;
		}
		int toTree = Integer.MAX_VALUE;
		int fromTree = Integer.MAX_VALUE;
		for (int i = 0; i < SPIRIT_TREES.length; i++)
		{
			int boarding = travelDistances.distance(origin, SPIRIT_TREES[i]);
			if (boarding != com.ironscape.travel.TravelDistances.UNKNOWN)
			{
				toTree = Math.min(toTree, boarding);
			}
			int riding = travelDistances.distance(SPIRIT_TREE_ORIGINS[i], target);
			if (riding != com.ironscape.travel.TravelDistances.UNKNOWN)
			{
				fromTree = Math.min(fromTree, riding);
			}
		}
		if (toTree == Integer.MAX_VALUE || fromTree == Integer.MAX_VALUE)
		{
			return best;
		}
		return Math.min(best, toTree + fromTree + 20);
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
		WorldPoint me = playerPoint();
		if (me == null || target == null)
		{
			return null;
		}
		// Off-surface areas are parked far north on the map — dungeons at
		// y+6400, but the rune essence mine at y≈4830 and the Abyss beside
		// it. A 2D distance between one of those and a surface point is
		// FICTION, and no hint beats a wrong one ("teleport to Lumbridge"
		// while one ladder below it). Testing the BAND rather than a fixed
		// delta is what catches the mine: standing in it, the Gnome
		// Stronghold reads as 1,372 tiles away — under the old 4,000
		// threshold — so the Barbarian Assault landing "won" the first leg
		// while the way out was the exit portal three tiles away.
		if ((me.getY() >= SURFACE_MAX_Y) != (target.getY() >= SURFACE_MAX_Y))
		{
			return null;
		}
		int playerDistance = effectiveDistance(me, target);
		if (playerDistance <= 100)
		{
			return null;
		}
		// ONE metric for the whole decision. Walked tiles when the bundled
		// table can speak about this target, straight lines when it cannot —
		// never a mix, because 400 walked tiles and a 300-tile straight line
		// are different quantities and ranking them together is meaningless.
		boolean walked = travelDistances.reachable(target);
		// Two bars, and a candidate must clear BOTH: 60% of the journey, and
		// an absolute 75 tiles saved. The percentage alone let a teleport win
		// a trip it barely improved — 60% of a 150-tile jog is a 90-tile jog,
		// which is not worth the runes (owner's call, 2026-08-10). The floor
		// binds on short journeys, the percentage on long ones, so a 1,000-
		// tile trip still demands a real shortcut rather than 75 tiles off.
		int bestDistance = Math.min((int) (playerDistance * 0.6),
			playerDistance - MIN_TILES_SAVED);
		// The FREE home teleport competes first (SP suggests it; we never
		// did — the owner stood in Draynor with SP saying "home teleport"
		// and our overlay dark). Free beats paid on ties, so it leads.
		boolean bestHome = false;
		if (!homeTeleportOnCooldown())
		{
			int d = legDistance("Home Teleport", HOME_TELEPORT_LANDING, target, walked);
			if (d < bestDistance)
			{
				bestDistance = d;
				bestHome = true;
			}
		}
		String bestMinigame = null;
		if (minigameAvailable)
		{
			for (Map.Entry<String, WorldPoint> entry : minigameLandings.entrySet())
			{
				if (!minigameLandingAvailable(entry.getKey()))
				{
					continue;
				}
				int d = legDistance(entry.getKey(), entry.getValue(), target, walked);
				if (d < bestDistance)
				{
					bestDistance = d;
					bestMinigame = entry.getKey();
					bestHome = false;
				}
			}
		}
		TeleportSpell bestSpell = null;
		for (TeleportSpell spell : TELEPORT_SPELLS)
		{
			if (!castable(spell))
			{
				continue;
			}
			int d = legDistance(spell.name, spell.destination, target, walked);
			if (d < bestDistance)
			{
				bestDistance = d;
				bestSpell = spell;
				bestMinigame = null;
				bestHome = false;
			}
		}
		// Teleport ITEMS compete last, so on a tie the cheaper options above
		// keep it — a charge off a glory is a real cost, a spell you can
		// already cast is not. They are only ever suggested when already
		// carried, so this never sends anyone shopping.
		com.ironscape.travel.TeleportItems.Entry bestItem = null;
		for (com.ironscape.travel.TeleportItems.Entry entry : teleportItemIndex.available(itemAvailability))
		{
			int d = legDistance(entry.getDisplay(), entry.getDestination(), target, walked);
			if (d < bestDistance)
			{
				bestDistance = d;
				bestItem = entry;
				bestSpell = null;
				bestMinigame = null;
				bestHome = false;
			}
		}
		if (bestMinigame == null && bestSpell == null && !bestHome && bestItem == null)
		{
			return null;
		}
		FirstLeg won = new FirstLeg(bestMinigame, bestSpell, bestHome, bestItem);
		won.legDistance = bestDistance;
		won.mustBeat = Math.min((int) (playerDistance * 0.6), playerDistance - MIN_TILES_SAVED);
		return won;
	}

	/**
	 * Which metric the hint just ranked on, and how far the player is under
	 * it. Both outcomes carry it, including "none": a session log has to be
	 * able to say whether the travel table was consulted at all, and "none"
	 * is the outcome you get while standing next to the target, which is
	 * exactly when you would wonder.
	 */
	private String metricNote(WorldPoint target)
	{
		if (target == null)
		{
			return "";
		}
		WorldPoint me = playerPoint();
		// Bucketed to 50 tiles ON PURPOSE. logHintDecision prints one line per
		// CHANGE and de-duplicates on the whole string, so an exact distance
		// makes every step you take a new log line -- 139 of them in four
		// minutes on the first run, drowning the file mine-session-log reads.
		// 50 keeps the useful reading (which side of the 100-tile floor, and
		// roughly how far) at a handful of lines per journey.
		return (travelDistances.reachable(target) ? " (walked distances" : " (straight lines")
			+ (me == null ? "" : ", player ~" + (effectiveDistance(me, target) / 50 * 50) + " away")
			+ ")";
	}

	/**
	 * Can the player cast this teleport right now? Level, LAW runes in
	 * hand and the quest gate. Elemental runes go unchecked on purpose —
	 * staves make them unanswerable.
	 * Client thread (skill and inventory reads).
	 */
	private boolean castable(TeleportSpell spell)
	{
		return client.getRealSkillLevel(Skill.MAGIC) >= spell.level
			&& itemTracker.carriedCountOf("law runes") >= spell.laws
			&& hasElement("air", spell.air) && hasElement("water", spell.water)
			&& hasElement("earth", spell.earth) && hasElement("fire", spell.fire)
			&& (spell.requiredQuest == null
				|| cachedQuestState(spell.requiredQuest) == QuestState.FINISHED);
	}

	/**
	 * Staves that supply an element without spending a rune. Combination
	 * staves count for BOTH of their elements, which is the whole reason
	 * this table exists rather than a name guess: a mud battlestaff is not
	 * called a water staff and never would be matched by a suffix rule.
	 */
	private static final Map<String, String[]> ELEMENT_STAVES = Map.of(
		"air", new String[]{"staff of air", "air battlestaff", "mystic air staff",
			"smoke battlestaff", "mystic smoke staff", "mist battlestaff",
			"mystic mist staff", "dust battlestaff", "mystic dust staff"},
		"water", new String[]{"staff of water", "water battlestaff", "mystic water staff",
			"mud battlestaff", "mystic mud staff", "steam battlestaff",
			"mystic steam staff", "mist battlestaff", "mystic mist staff", "kodai wand"},
		"earth", new String[]{"staff of earth", "earth battlestaff", "mystic earth staff",
			"mud battlestaff", "mystic mud staff", "lava battlestaff",
			"mystic lava staff", "dust battlestaff", "mystic dust staff"},
		"fire", new String[]{"staff of fire", "fire battlestaff", "mystic fire staff",
			"lava battlestaff", "mystic lava staff", "steam battlestaff",
			"mystic steam staff", "smoke battlestaff", "mystic smoke staff"});

	/**
	 * Can the player pay this spell's cost in one element — either the
	 * runes are in the bag, or a staff supplying it is held or worn?
	 *
	 * The elemental cost used to go unchecked entirely, so a Varrock
	 * teleport was suggested to anyone holding a single law rune, with no
	 * air or fire to their name (owner, in play, 2026-08-10). It was left
	 * out because a staff makes a rune count meaningless, and skipping the
	 * check was the cautious way to avoid hiding a hint from someone
	 * wielding one — the fix is to model the staff, not to stop asking.
	 *
	 * carriedCountOf covers worn equipment as well as inventory, which is
	 * what makes a WIELDED staff count.
	 */
	private boolean hasElement(String element, int needed)
	{
		if (needed <= 0)
		{
			return true;
		}
		for (String staff : ELEMENT_STAVES.get(element))
		{
			if (itemTracker.carriedCountOf(staff) > 0)
			{
				return true;
			}
		}
		return itemTracker.carriedCountOf(element + " runes") >= needed;
	}

	/**
	 * The standard-book teleport a sub NAMES, or null — "Use mind bomb
	 * and camelot tele" -> Camelot Teleport. Matched on the DESTINATION
	 * word, since the guide never writes the full spell name.
	 *
	 * The destination must sit either side of a tele word ("camelot
	 * tele", "Teleport to Varrock"). Merely MENTIONING a town is not a
	 * teleport instruction, and three steps in the guide prove it: "Use
	 * the falador teletab" (a tab, not the spell), "Use house tab and run
	 * back to thurgo ... run back to Falador" (a destination on foot),
	 * and "Home tele to lumby and run north to Varrock east bank" (a
	 * different teleport entirely). The optional "port" carries a word
	 * boundary, which is what makes "teletab" fail to match.
	 *
	 * Castability is deliberately NOT required here, unlike the spell
	 * SUGGESTION path. "Use mind bomb and camelot tele" is the guide
	 * telling you to BOOST into Camelot Teleport, so the player's real
	 * Magic level is under 45 by design — gating on castable() silenced
	 * the one step in the guide that most needs the prompt (owner,
	 * 2026-08-08). A suggestion has to be castable to be worth making; a
	 * prescription is the guide's call, and missing runes are the item
	 * badges' job to report, not the hint's.
	 */
	private TeleportSpell prescribedSpell(String subText)
	{
		String lower = subText.toLowerCase(Locale.ROOT);
		for (TeleportSpell spell : TELEPORT_SPELLS)
		{
			String destination = java.util.regex.Pattern.quote(
				spell.name.toLowerCase(Locale.ROOT).replace(" teleport", ""));
			boolean named = java.util.regex.Pattern
				.compile("\\b" + destination + "\\s+tele(?:port)?\\b"
					+ "|\\btele(?:port)?\\s+to\\s+" + destination + "\\b")
				.matcher(lower).find();
			if (named)
			{
				return spell;
			}
		}
		return null;
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
