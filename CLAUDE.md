# BRUHsailer RuneLite Plugin — Project Brief

## What we're building

A RuneLite plugin that turns the BRUHsailer OSRS ironman guide into a guided,
step-by-step side panel inside the game client, with optional automatic step
completion detection and integration with the Shortest Path plugin for navigation.

**Guide source:** https://osrsper.github.io/BRUHsailer/
**Upstream repo:** https://github.com/umkyzn/BRUHsailer (see `/data` folder)
**Guide authors:** So Iron BRUH & ParasailerOSRS. Web adaptation by kyyznn,
improved by Jesper (osrsper). Credit them prominently in the plugin.

## Developer context — read this first

The project owner is **new to Java**. He is not new to software generally —
he has React/Supabase and Google Apps Script experience — but Java, Gradle,
and the RuneLite plugin lifecycle are all unfamiliar.

Implications:
- Explain build errors rather than silently fixing them.
- Prefer boring, readable Java over clever Java.
- Comment the RuneLite-specific lifecycle bits (`@Subscribe`, `startUp()`,
  `shutDown()`, injection) because those are non-obvious to newcomers.
- When something needs to be installed or configured, give exact commands.

## Core architecture

### The data problem

The guide's steps are prose written for humans ("get 43 Prayer, then chin at
MM2 tunnels until 70 Range"). The upstream JSON has an ordered list of steps
with text, but **no machine-readable completion conditions and no coordinates.**

Therefore the design principle is:

> **Annotation is optional everywhere. The plugin is fully usable with zero
> annotations, and gets smarter as they accumulate.**

Unannotated steps fall back to a manual checkbox — exactly how the website
works today. Nothing breaks.

### Two-file data model

1. **Guide data** — ingested from upstream JSON. Treated as read-only.
   Should be refreshable, since upstream regenerates it from a Google Doc
   via webhook.
2. **Annotation overlay** — our own file, keyed by step ID. Holds optional
   `requires` conditions and optional `target` coordinates. Kept separate so
   upstream guide updates never clobber annotation work, and so other players
   can contribute annotations back via PR.

Sketch of an annotation entry:

```json
{
  "stepId": "s1-14",
  "requires": { "skill": "PRAYER", "level": 43 },
  "target": { "x": 3222, "y": 3218, "plane": 0 }
}
```

### Annotation tiers

- **Tier 1 (do this):** skill levels, quest completion, item checks. These
  are stated literally in the guide prose, so write an extraction script to
  generate *draft* annotations, then have the owner review/approve rather
  than author from scratch.
- **Tier 2 (do this, via tooling):** location targets. Do NOT ask the owner
  to look up world coordinates by hand. Build a **"capture location for this
  step" button** into the panel that reads the player's current `WorldPoint`
  and writes it into the annotation file. He annotates passively while
  playing his own ironman.
- **Tier 3 (skip):** varbit-backed state like diary tasks, shortcut unlocks,
  POH rooms. Too much effort to discover. Leave these as manual checkboxes.

The capture button is the highest-leverage feature in the whole project.
Build it early, not as a nice-to-have.

## Shortest Path integration

Target plugin: https://github.com/Skretzo/shortest-path (currently ~v0.3.1)

**Constraint:** Plugin Hub plugins load in isolated classloaders, so you
cannot simply import Shortest Path's classes and call them.

Approaches, in order of preference:

1. **ConfigManager handoff** — write the target into the `shortestpath`
   config group and let its own listener react. No hard dependency; degrades
   gracefully when the user doesn't have it installed.
2. **EventBus** — post an event it subscribes to, if it exposes one.
3. **Vendor the pathfinder** — it's open source. Heaviest option, zero
   runtime dependency. Check the license and honour attribution.

**Verify before building:** read the current `ShortestPathPlugin.java` and
its config class to confirm which config keys it actually listens to. Its
interop surface has changed across versions — do not assume.

Prior art worth reading:
- A Plugin Hub plugin exists that uses Shortest Path to route to clue scroll
  locations — evidence clean integration is achievable.
- `JaredEzz/shortest-path-quest-helper` is a *fork* of Shortest Path that
  auto-targets the next Quest Helper location. The fact it's a fork rather
  than an integration suggests friction on the clean route — read it to learn
  what that friction was.

GPS plugin is an acceptable fallback pathing target if Shortest Path proves
hostile to integration.

**Reference implementation:** Quest Helper is open source and solves most of
the step/requirement architecture problems already. Read its step and
requirement model before inventing one.

## Build order

1. Gradle project scaffold that builds and launches a RuneLite client with
   the plugin loaded (`gradlew run` should Just Work).
2. Ingestion: upstream JSON → internal step model.
3. Side panel: ordered steps, manual checkboxes, search, filter, progress
   bar. Persist progress to the RuneLite profile (not browser localStorage).
   At this point it is already useful — ship-quality on its own.
4. Annotation overlay file format + the location capture button.
5. Tier 1 auto-extraction script + review flow.
6. Shortest Path bridge.

Do not start step 6 before step 3 works end to end.

## Environment setup needed

- JDK (RuneLite plugins target Java 11)
- IntelliJ IDEA Community Edition (free)
- Gradle wrapper (committed into the repo, not installed separately)

Plugin Hub distribution eventually requires a public GitHub repo with a
`runelite-plugin.properties` file, but that's a later concern.

## Implementation status (2026-07-21, end of first build session)

All six build-order steps are DONE, plus substantial extras. Current state:

- **Panel:** steps render as clause-level tick-lists (SentenceSplitter →
  SubStep, positional ids `parentId:N`); master checkbox per multi-action
  step; search, progress bar, Resume, hide-done; place names/quests are
  clickable links (PlaceManager, 562 seeded entries, punctuation-tolerant
  matching).
- **Auto-completion** (`GoalDetector` + evaluator in plugin): item goals
  (carried counts only), quest state, skill-level goals ("burn them to
  level 50 firemaking" -> live "firemaking 43/50" badge + auto-tick at
  50; suppresses xp-drop/counted goals on that sub), xp-drop actions,
  counted xp drops ("train construction (6 chairs...)"), teleport
  position-jumps, arrival, and consumption-gated interactions (give/fix).
  Everything gated to an in-order window of 8 (owner wants order);
  ambient signals (carried items, xp drops, teleports, consumption)
  additionally only tick subs of the FRONTIER STEP — later window steps
  need strong evidence (quest state, skill levels, reviewed skill
  requirement). Arrival stays frontier-sub-only.
- **Teleport hints:** MinigameTeleportOverlay highlights the click path
  while the current sub is "Minigame teleport to X" OR for ~1 min after
  the user clicks that minigame's place link (clickedMinigameTarget;
  cleared when a teleport lands): center-screen Minigames picker entry if
  open, else Grouping UI (dropdown entry -> Teleport), else the
  spellbook's Minigame Teleport button, else side tab -> Grouping
  sub-tab. Config toggle showTeleportHints.
- **Step overlay:** StepOverlay (OverlayPanel, toggle showStepOverlay) —
  QH-style box: frontier step's remaining actions (3 lines max) + live
  item/level/counted requirement counts; model rebuilt per game tick.
- Panel opens at the first unfinished step whenever it becomes showing
  (HierarchyListener — RuneLite never calls Activatable.onActivate for
  plain PluginPanels, only via MultiplexingPluginPanel).
- **World links:** "world 444" in step text is clickable -> hops there
  (WorldService lookup; changeWorld on login screen, openWorldHopper +
  hopToWorld dance in game, see onGameTick).
- **Items:** ItemTracker counts inventory+worn live + bank (live container
  when cached, else persisted snapshot per account); badges show have/need
  with green/orange "(in bank)"/red; alias chain handles plurals,
  of-phrases, "noted X", gp→coins, POH/city tabs.
- **Navigation:** Shortest Path PluginMessages; auto-navigate to next
  target after any progress; nearest-bank routing when frontier items are
  banked; route cleared when nothing targetable ahead.
- **Bank filter:** button inside the bank UI (Quest Helper-style widget +
  getSearchingTagTab callback) or type "bruh" in bank search. Shows items
  still needed by the CURRENT SECTION (incomplete steps/subs only), not a
  fixed sub-step lookahead.
- **Tooling** (`tools/*.mjs`, Node): extract/review annotations (skills +
  items — owner has NOT run the review yet), seed-places (NPC scan,
  --quests incl. miniquests, --locations), tag-quest-places.
- **Known limits:** interaction/arrival detection is heuristic (proxy
  signals, not quest varbits — deliberate; QH-style per-quest authoring
  rejected); sub ids reset when their parent step's text changes upstream.
  (Counted-xp progress now persists via ProgressManager, `counted_MAIN`
  config key; unticking a step/sub resets its counter.)

Owner's testing profile: RuneLite profile "ironman test" (keep "IRONMAN"
untouched). Jagex-account dev login via `--insecure-write-credentials`
refresh when "Failed to login" appears.

## Scope notes

- Owner decision (2026-07-21): the plugin's display name is **IRONSCAPE
  Optimal**. Internal ids (package `com.bruhsailer`, config group
  `bruhsailer`, `~/.runelite/bruhsailer/`) keep the old name on purpose —
  renaming them would orphan saved progress and annotations. BRUHsailer
  guide credits stay prominent.

- Guide has two variants upstream: **Main** and **Landlubber**. Owner
  decision (2026-07-21): ship **Main only**. Everything stays keyed by
  `GuideVariant` so re-adding Landlubber is one enum entry + its JSON.
- Guide is ~1000 steps across three chapters. Panel performance matters —
  don't naively render every step as a live Swing component at once.
