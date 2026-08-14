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

The project owner has **no coding knowledge at all** (corrected by him
2026-08-10; the old "new to Java but knows React/Supabase and Apps Script"
note here was wrong and shaped months of over-technical reports). He is the
OSRS domain expert and directs everything in game. Claude is the lead
developer and owns every technical decision.

He also WANTS TO LEARN what development involves, and wants a heads-up on
build decisions so he can keep track of what has been added. So lead, but
narrate — the original failure was density, not consultation.

Implications:
- Give a short heads-up on what you are about to build and why, then build
  it. Not a request for permission on internals — a note so he keeps track.
- When a choice is real, frame it as outcomes IN GAME with a recommendation
  ("stops nudging entirely" vs "nudges once then leaves you alone"), never as
  a choice between implementations.
- Teach as you go: name the idea in ordinary words the first time it appears.
- Ask him about the GAME freely: item names, NPCs, slang, whether a step
  really behaves that way.
- Report in plain language, cause and effect. No class or method names, no
  file paths, no line numbers in the body of a report unless he asks. "The
  arrow kept pointing at the old destination because it never noticed the
  route had been handed over" — not the code path that did it.
- Keep reports SHORT. Lead with what changed for him in play, then what to
  watch for next.
- Propose the tools, audits and systems worth building. He cannot know what
  is possible, so unprompted recommendations are where the value is.
- Still prefer boring, readable Java, and still comment the RuneLite
  lifecycle bits — for the next developer, not for him.

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
  (WorldService lookup; changeWorld on login screen; in game
  it opens the world switcher (hub forbids client.hopToWorld)).
- **In-game test session findings (2026-07-22):** frontier = first
  incomplete step AFTER the last completed one, and panel scroll lands
  top-aligned on the first unticked SUB (giant steps are taller than the
  panel). Quest text matching accepts the article ("Complete THE Tower
  of Life"). Ambient ticks past the first incomplete sub were swept once
  per profile (ambientTickCleanupV1 flag). Re-banking reopens item subs
  ONLY past the first incomplete sub — the contiguous done-head is
  history (using a tab with bank spares is state-identical to
  re-banking; ordering wins). Bank filter = next 10 incomplete steps;
  BANKS list includes chests (Port Khazard etc.). Overlays:
  QuestStartMarkerOverlay (blue quest icon at start point until quest
  begins), NpcTargetOverlay (outlines scene NPCs named in the current
  sub; icon when quest sub), StepOverlay shows ONE current action + its
  counts + dimmed "then:" previews. Quest Helper handoff REMOVED (hub forbids reflection) — chat message
  points at QH instead.
- **Items:** ItemTracker counts inventory+worn live + bank (live container
  when cached, else persisted snapshot per account); badges show have/need
  with green/orange "(in bank)"/red; alias chain handles plurals,
  of-phrases, "noted X", gp→coins, POH/city tabs.
- **Navigation:** Shortest Path PluginMessages; auto-navigate to next
  target after any progress; nearest-bank routing when frontier items are
  banked; route cleared when nothing targetable ahead.
- **Bank filter (reworked 2026-07-23):** button inside the bank UI or
  type "bruh"/"ironman" in bank search. While active the native grid is
  BLANKED (bankSearchFilter answers 0 for every slot; getSearchingTagTab
  is NOT answered — it fought real tabs) and BankMissingSection renders
  the next 10 incomplete steps as per-step sections: header + ALL of the
  step's items, green/red have/need text under each icon, missing ones
  ghosted. Clicking a real tab deactivates AND clears our search
  (deactivate(clearSearch)); a player-opened search deactivates without
  clearing. TWO past hard freezes, both script-engine re-entrancy: the
  op listener runs inside the click script (activation defers via
  invokeLater) and update()'s UPDATE_SCROLLBAR runScript ran inside
  BANKMAIN_BUILD's post-fire (now deferred one tick).
- **Wiki seeding passes (2026-07-23, pre-publish):** seed-shops.mjs
  (buy-step shop phrase + 📍 tag -> wiki search -> {{Map}} coords ->
  reviewable draft -> --apply writes step-keyed ⌖ targets; 22 shops
  seeded, 5 hand-corrected). seed-facilities.mjs (facility word + town
  -> nearest surface pin on the facility's wiki page within 80 tiles —
  proximity IS the review; 2 furnaces seeded, "range"-the-skill steps
  correctly rejected). seed-item-ids re-run: 193 names mapped
  (191/295 tracked names covered; the rest are junk detector names).
  Step-level ⌖ targets now apply to EVERY sub of their step (nav, tile
  marker, arrival, shop-NPC anchor) — not just single-sub steps.
- **Tooling** (`tools/*.mjs`, Node): extract/review annotations (review
  COMPLETE 2026-07-22: bundled annotations cover 187 steps — 48 skill
  requirements, 170 item lists; review --trust auto-applies verifier
  verdicts >=0.8), verify-annotations (LLM
  verifier pass over the drafts: confirm/adjust/reject + confidence +
  flags written into draft-annotations.json; needs ANTHROPIC_API_KEY,
  `cd tools && npm install` first; review tool then shows verdicts, 'a'
  accepts recommendation, 'b' bulk-approves confirmed), seed-places (NPC
  scan, --quests incl. miniquests, --locations), tag-quest-places.
- **Guide-refresh safety:** GuideManifest persists each load's step
  ids+positions+sub-clause fingerprints (v2,
  ~/.runelite/ironscape/guide_manifest.json). On load, steps EDITED IN
  PLACE upstream (same section, same index, both ids changed) get their
  old ids remapped onto the new ones — progress (incl. sub ids + counted
  keys, re-applied per profile on switch) and local annotations survive
  text edits. Sub ticks of an edited step follow their clause TEXT to
  its new index; only the actually-reworded clause is orphaned (progress
  drops the tick, remapId returns null; annotations keep the old key —
  captured targets are never deleted). v1 manifests without fingerprints
  fall back to positional sub carry. Insertions/reorders of whole steps
  are left alone (conservative).
- **Splitter (2026-07-22):** subordinate fragments no longer become their
  own tickbox — a comma segment opening with while/whilst/when(ever)/
  once/after/before/if/unless/until glues to the clause it introduces
  ("While visiting Jennifer, buy shears" = ONE sub); sentence-final
  subordinates glue backward. Sub-index shifts from splitter changes are
  re-linked by the manifest's same-id fingerprint pass (v1 manifests
  can't be — one-time misalignment on partially-done steps, ticks just
  need re-doing there).
- **Acquisitions (2026-07-22):** "buy/purchase X" item goals are
  transactions — carrying the item already does NOT tick them; the
  carried count must RISE above a baseline captured when the sub first
  became current (acquisitionBaseline, session-only, cleared on profile
  switch). Bare list continuations inherit the purchase flag; an own
  verb ("grab a knife") resets it.
- **Mid-quest checkpoints (2026-07-22):** annotation requirements can be
  `{"varbit": id, "value": n}` (or "varp") — met when the value reaches
  n; keyed by SUB id ("stepId:14") they tick just that sub. Values come
  from Quest Helper's steps.put maps (N = quest var value); var ids
  from javap on the gameval VarbitID/VarPlayerID classes in the gradle
  cache. Seeded: Client of Kourend orb (varbit 5619>=5, BRUH guide);
  Oziris (in annotations_oziris.json, scraper preserves hand keys):
  Dwarf Cannon->Nulodion varp 0>=9, Waterfall->gnome maze varp 65>=3,
  Grand Tree->shipyard varp 150>=80, Lost Tribe->Varrock varbit
  532>=5, ->Goblin Village varbit 532>=6. ALL "do X until <part>"
  steps in the Oziris guide are covered; remaining "until" steps are
  skill/gp targets the level-goal detector handles.
  BundledAnnotationKeysTest fails the build if any bundled key stops
  resolving; PrintSubIdProbe (test sources, main()) prints step/sub ids
  for authoring these.
- **Target tile marker (2026-07-22):** TargetTileOverlay highlights the
  current sub's annotated ⌖ target tile (orange fill + floating arrow;
  toggle showTargetMarker) — nav already routed to sub targets, now the
  exact spot is visible too. Seeded: Clue hunter garb dig spot
  (af7ae8942e:20 -> 1595,3628). POIs get place links by adding them to
  places.json (seeded: shayzien agility course 1554,3630 — link + nav
  fallback via firstPlaceIn). Action-goal detection now skips leading
  connectives ("and do...") and maps "lap of ... agility course" to an
  AGILITY xp-drop goal.
- **Player POSITION (2026-07-23):** the frontier anchors on a persisted
  position index (`position_OZIRIS`), NOT on "last completed step" — a
  pre-done quest auto-ticking its step ahead (Daddy's Home) must not
  teleport the frontier past undone steps. Position advances when the
  FRONTIER step completes or the user manually ticks (deliberate skip);
  regresses on manual untick / gather-loss reopen. Initialized once
  from the contiguous completed prefix.
- **Wiki seeding (2026-07-23):** item_ids.json (181 wiki-verified
  name->id entries, tools/seed-item-ids.mjs + PrintItemNamesProbe)
  gives untradeables sprites; ItemTracker checks it before the price
  list. Places re-seeded over the Oziris guide with widened NPC verbs.
- **Known limits:** interaction/arrival detection is heuristic (proxy
  signals, not quest varbits — deliberate; QH-style per-quest authoring
  for STEP FLOW still rejected — varbit checkpoints are opt-in
  annotations, not authored quest scripts). (Counted-xp progress now
  persists via ProgressManager, `counted_MAIN` config key; unticking a
  step/sub resets its counter.)

Owner's testing profile: RuneLite profile "ironman test" (keep "IRONMAN"
untouched). Jagex-account dev login via `--insecure-write-credentials`
refresh when "Failed to login" appears.

## Scope notes

- Owner decision (2026-07-21): the plugin's display name is **IRONSCAPE
  Optimal**. REPACKAGE DONE (2026-07-23): package `com.ironscape`,
  IronscapePlugin/Config/Panel(+PluginTest), config group `ironscape`,
  data dir `~/.runelite/ironscape/`, runelite-plugin.properties updated.
  One-time migration in IronscapePlugin: migrateLegacyFiles() copies
  (never moves/overwrites) old data-dir files each startUp;
  migrateLegacyConfig() copies ALL `bruhsailer.*` config keys per
  profile (startUp + onProfileChanged, guarded by
  `ironscape.migratedFromBruhsailer`). Legacy keys/files left in place.
  Stale BRUH guide-refresh workflow (.github) deleted.

- Guide has two variants upstream: **Main** and **Landlubber**. Owner
  decision (2026-07-21): ship **Main only**. Everything stays keyed by
  `GuideVariant` so re-adding Landlubber is one enum entry + its JSON.

- PERMISSIONS SETTLED (2026-07-23): **Oziris & ironman.guide APPROVED**
  ("use their stuff however you want"); **BRUHsailer DECLINED** — all
  BRUH content removed: guide_data.json, bundled annotations.json (BRUH-
  derived), extract/review/verify annotation tools, GuideVariant.MAIN,
  the activeGuide config item, all BRUH credits. The plugin is the
  owner's; Oziris credited in the plugin descriptor, panel overview
  footer, README and runelite-plugin.properties. The ironscape
  repackage (see scope note above) shipped 2026-07-23 with the
  legacy-data migration; old "bruhsailer" ids survive only as the
  migration source constants.

- PUBLISHED (2026-07-23): repo renamed to GROBBS-hash/ironscape-optimal
  (public, BSD-2 LICENSE, 48x48 icon.png placeholder). Plugin Hub
  submission OPEN: https://github.com/runelite/plugin-hub/pull/14207
  (plugins/ironscape-optimal on the GROBBS-hash/plugin-hub fork, branch
  "ironscape-optimal", pin moves with review rounds — check the fork branch for the current sha). To ship a new hub
  version later: push to main, then update the commit= line in that
  fork branch. REVIEW ROUND 1 (2026-07-28, Alexsuperfly) ADDRESSED: no reflection
  (QH handoff removed -> chat message), no client.hopToWorld (world
  links open the switcher in game; changeWorld login-screen only),
  LICENSE verbatim BSD-2. History was REWRITTEN 2026-07-28 to strip
  Claude co-author trailers (owner request — NEVER add them; see
  memory) and force-pushed. Awaiting re-review; PIN BUMPED 2026-08-03
  twice, now cf9be08 (both play-test hardening waves). Nudge comment
  POSTED 2026-08-03 with owner's go
  (issuecomment-5163102844).

- PLAY-TEST SESSION (2026-08-03) — huge hardening pass, all pushed:
  goal-audit pipeline (GoalAuditDumpTest -> build/goal-audit.tsv ->
  tools/audit-goals.mjs; 0 unresolvable item goals/annotation items
  guide-wide, plus a NOGOAL section for buy-subs with no goal);
  canonical item matching (apostrophes/possessives/plurals incl. -ves:
  woad leaves->Woad leaf, wizard mind bombs->Wizard's mind bomb);
  recipe ingredient lists on 16 make-steps (tools/seed-recipes.mjs,
  wiki {{Recipe}}, recursive intermediate expansion); nav coverage
  pass (tools/audit-nav.mjs + seed-uncovered.mjs: mage tutor, Lumby,
  Fally, Aubury, Toby=achievement diary guy, dairy cow, barcrawl bars
  x10, Nulodion, Peksa); kill/fill goals ('kill 1 cow calf for meat'
  -> raw beef via MEAT_BY_NPC; 'fill 3 buckets with milk' -> buckets
  of milk; 'safespot X for meat' too); place-name arrival gated to
  movement-word subs; FINISHED quest subsumes every sub of its step;
  'continue' added to quest-goal pre-filter (Continue Gertrude's cat);
  acquisition gate only for qty<3 (bulk buys tick on having);
  ObjectTargetOverlay outlines live ore rocks (impostor-resolved);
  NPC fixes (specific name shadows generic 'dairy cow'>'cow'; names
  inside place names ignored 'Barbarian Village'; anchor nominee by
  INDEX not name); manual ⌖ capture pins auto-nav until frontier
  moves; 'house tab to X' is a travel sub; shop packs ('empty X
  pack'), elemental staves, teletab->tab, gloves/boots/pickaxe/axe
  substitutes, 'pestle and mortar' scanned whole, 'pack of X'->'X
  pack', bare runes/bars/beads/nails family sums.

- SESSION WAVE 2 (2026-08-03 evening): quest ITEM requirements seeded
  from wiki {{Quest details|items}} onto each quest's first step (104
  quests, tools/seed-quest-items.mjs, junk-filtered, audit 0/812);
  quest GIVERS seeded from infobox start text (109,
  tools/seed-quest-givers.mjs -> places/quest_givers.json,
  PlaceManager.questGiver) — quest subs outline the actual giver,
  nearest-to-pin only as fallback; minigame teleport hint rewritten as
  live PRESENCE (near pin | confirmed region learned from teleport
  landings | contiguous walking; any teleport breaks the chain — after
  THREE failed one-way-flag patches, and NOT gated on quest-in-progress
  since started-then-left-to-gather is normal); NPC outlines stand down
  mid-quest (questHelperOwnsGuidance) and mid-quest nav routes to the
  step's 📍 area instead of clearing; green Quest Helper tip line on
  every quest step ('select \"X\" for click-by-click guidance' — the
  reflection handoff is hub-forbidden); '(N Skill required)' prose
  parses to level badges; annotation skill requirements badge like
  levels; InventoryItemHintOverlay outlines ALL carried step items
  (config showInventoryHints); recipe ingredients on 16 make-steps
  (tools/seed-recipes.mjs); gather-tool badges (tools/seed-tools.mjs);
  bank placeholders no longer count as owned (the REAL 'steel axe 2/1'
  root cause); canonical matching collapses potion doses; place-name
  arrival requires being seen OUTSIDE the radius first (arrivalArmed);
  blurite sword id bundled (untradeables skip the items-in-hand gate
  when unresolvable — bundle ids to make gates real); GROUPING_MINIGAMES
  set lets any minigame place/chip light the teleport click path.

- SESSION WAVE 3 (2026-08-04, main at 18c082a, all pushed, audit
  0 text / 0 annotation): quest kits MOVED to the step that FINISHES
  the quest (questStatus=complete; migration in seed-quest-items);
  kit lines parse comma/and CHAINS incl. Oxford comma, or-alternatives
  keep first only; purchases are HISTORY (acquisition goals join coins
  in the reopen skip — consuming/banking bought goods never reopens);
  full-inventory goals (exactly 28, 'inv of X') count the bank;
  JUMPED-AHEAD stand-down: a quest on a LATER step being IN_PROGRESS
  (minStepIndexByQuest) clears nav + silences minigame hints until it
  finishes (drive-by starts from earlier steps never trigger);
  minigame presence hardened: cave-entrance jumps within 3 ticks of a
  GAME OBJECT click carry presence + confirm the region, presence
  persists as config 'minigamePresence' ("name|region") restored on
  login-in-place; minigame picker/dropdown matching is slang-tolerant
  (word-prefix windows, 'fish trawler' ~ 'Fishing Trawler') and
  normalizes <br> (removeTags fused 'TrawlerPort'); NOTE-carried
  quantities upgrade unnumbered goals ('around 600 buckets of sand');
  unnumbered BUY lists split on 'and' (crafting/action verbs end the
  list); flour->pot of flour (bare 'Flour' EXISTS but unobtainable —
  existence audits can't catch semantic mismatches, colloquials can);
  substitute/family badge names borrow a member's icon (pickaxe,
  beads); the panel Go button falls back to targetFor's chain (text
  place -> 📍 tag) when no ⌖ captured. Hub pin still cf9be08 — the
  wave 3 commits are play-tested but NOT pinned; bump when it next
  matters.

- SESSION WAVE 4 (2026-08-04/05, live play-test session, all pushed):
  quest-NAME place links act as places when the quest isn't the step's
  task (article-tolerant match vs step quest metadata; FINISHED quests
  always just route); handoff RETURN — ClientToolbar.openPanel pulls our
  panel back when the handed-off quest FINISHES (handedOffQuest tracked
  on the lastQuestOwnsGuidance edge); bank filter keeps withdrawn items
  (done subs keep items while stillMet) and the widget join falls back
  to nameMatchesGoal (any axe tier clickable); NPC outlines match
  PLURALS (pluralVariants: imps/wolves/fairies); family sums + iconIdFor
  match CANONICALLY ("beads" counts the singular "Black bead" imp
  drops); **ERRAND CHAINS** — annotation `errands` (ordered stages
  {x,y,plane,item,note}, sub-keyed "624c2f822c:0" = TGV pebble: key
  crate 2548,9565 -> Golrie 2514,9580, coords from QH source): active
  from quest START until items owned, OUTLIVES the quest, blocks the
  quest-completion subsume/atomic ticks while unsatisfied, stage = first
  unsatisfied (sticky per session; owning a LATER stage's item satisfies
  earlier ones; intermediate stages count CARRIED only — bank items
  named "Key" are impostors), errand outranks jumped-ahead + QH-owns in
  nav, route re-posts every 10 ticks, ⌖ marker + nearest-NPC outline +
  item overhead + 30-tile one-shot chat nudge; **ITEM SOURCES**
  (places/item_sources.json, place namespace + `note` chatted on click;
  RichText overrides an author wiki link whose WHOLE text is a nav name;
  navigateToPlace routes chain-aware — clicking the pebble goes to the
  CURRENT stage); jumped-ahead minStepIndexByQuest also learns from
  metadata quest tags (Barcrawl's start step never says the full name —
  it looked permanently jumped-ahead and CLEARED ALL AUTO-NAV);
  panel item badge lines CLICKABLE (route via place/source/chain if
  known, else wiki page; RichText.wikiUrl); right-click ⌖ = "Remove
  captured location" (AnnotationManager.clearTarget, local only,
  releases navHold); TravelMenuOverlay highlights matching entries in
  interface 187 (Spirit Tree Locations, gliders — word-SET match of sub
  text + 📍 tag, InterfaceID.Menu.LJ_LAYER1); "use the spirit tree" subs
  route to the NEAREST of the 5 permanent spirit trees when far from the
  destination (SPIRIT_TREES, nearestOf); tools/qh-lookup.mjs pulls
  WorldPoints + ItemRequirements from Quest Helper source ("Quest Name",
  --item code search via gh, cache gitignored). Hub pin still cf9be08.

- SESSION WAVE 5 (2026-08-05, live play-test, all pushed, main at
  8f00d68): PLACES FILE DIED from a duplicate "zmi bank" JSON key —
  Gson throws where lenient parsers shrug, every link/nav/arrival went
  dark with one swallowed warning; BundledPlacesLoadTest now fails the
  build on load regressions, PlaceLinkProbe (test main()) answers "why
  doesn't X linkify". ZMI bank re-anchored at the Ourania Cave surface
  entrance 2452,3231 (SP can't path into cave interiors — anchor
  routable points at entrances). Teleport arrivals: text place NULL
  falls back to the step 📍 tag via getLoose (word-order flips like
  "battlefield of khazard" vs "Khazard Battlefield"), and a fresh
  teleport widens the arrive radius to 45 (pads sit at area edges).
  ERRANDS extended: quest-less steps allowed (active from step start),
  WAYPOINT stages (item:null, satisfied by proximity ≤12 OR being
  closer to the NEXT stage — teleport skips can't wedge), ZMI chain
  seeded (entrance -> warriors 3015,5583 + rune scimitar). NPC
  matching: species-suffix rule for COMBAT subs only ("a rat" matches
  "Giant rat", "a bear" "Black bear"; specific-shadows-generic prefers
  them); nearest-to-anchor nominee STICKY-disabled per sub once text
  ever matched a scene NPC (dead-warrior gap crowned a Zamorak
  crafter). Overlays: ModelOutlineRenderer everywhere (QH-crisp
  silhouettes, no more hull blobs); TravelMenuOverlay highlights
  matching entries in MenuNew (947, confirmed by the widget-load
  probe, which logs groups while a travel sub is current) + classic
  Menu 187 + last-loaded group; "Safespot" label floats over ⌖ tiles
  when the sub says safespot; ⌖ right-click = remove captured location
  (local only, releases navHold). Spirit tree: transport NETWORK type
  in item_sources ("spirit tree" linkifies, click routes to NEAREST of
  the 5 permanent trees; type "transport" excluded from
  firstPlaceIn/lastPlaceIn so arrival can't false-tick at the origin
  tree); "use the spirit tree" subs auto-route to the nearest tree
  when far from the destination. TOOLS: audit-drops.mjs (combat-
  acquisition goals vs wiki drop tables, species-page fallback via
  intitle search — caught rat/bear meat semantics, validated the
  Zamorak warrior scim); cross-check-quest-kits.mjs (our kit-step
  annotation items vs QH getItemRequirements, guide-wide; 62 kit items
  seeded across 28 quests from its first sweep — tentative entries
  owner will correct in play; in-quest trackables deliberately NOT
  seeded, they'd sit red forever); qh-lookup cache + wiki cache
  gitignored. item_ids added: key 293, slayer gloves 6720, willow
  branches 5933. Item sources seeded: bat bones (Keep Le Faye giant
  bats), black candle (Catherby candle maker) from QH's Merlin's
  Crystal WorldPoints. Giant-rat-for-meat confirmed by owner. LATE
  WAVE 5: hub pin BUMPED to a07a1eb (fork commit ee63078) — awaiting
  re-review; cross-check-quest-kits filters in-quest acquisitions
  (step-text verbs) AND untradeables (price mapping) — actionable
  QH-only list guide-wide is EMPTY (Tribal Totem glory excluded by
  kit policy); tools/mine-session-log.mjs distills a client log into
  warnings/exceptions/IRONSCAPE lines (no arg = newest task log;
  known third-party noise: DoinkOink loot-list throws EVERY tick,
  sailing CargoHoldTracker + NpcAggroArea throw on login — NOT ours);
  Cabin Fever kit +hammer+swamp paste. CONFIRMED IN PLAY: farming the
  scim on MOBILE then logging into the dev client ticked the step on
  login (login-in-place auto-completion works).

- SESSION WAVE 6 (2026-08-05 evening, live play-test): BARCRAWL STAMP
  CHECKPOINTS — all 10 "get a drink" steps used to tick on ARRIVAL at
  the bar; now each carries a sub-keyed varp-BIT annotation (varp 77
  packs one bit per signed bar, positions from QH's
  AlfredGrimhandsBarcrawl.java: BlueMoon 3, Blurberry 4, DeadMansChest
  5, DragonInn 6, FlyingHorse 7, ForestersArms 8, JollyBoar 9, Zambo
  10, RisingSun 11, RustyAnchor 12). Requirement gains `bit` (bitfield
  test — >= would let other bars' stamps fake it), `icon` (item NAME
  for the badge sprite) and `label`; a sub with ANY varbit/varp
  checkpoint completes ONLY off it — heuristics don't get a vote (the
  annotation exists precisely because they fired early) — enforced in
  the window loop AND the xp-drop path. The old "signs your card" chat
  hook was DELETED: each pub prints its own flavor text ("signing your
  barcrawl card" at the Flying Horse) and it never matched. Panel shows
  a "stamp 0/1" badge with the Barcrawl card sprite (id 455 bundled)
  fed by checkpointMetBySub, a per-tick Swing-readable cache — badges
  can't read varps off the client thread. BUNDLED ⌖ REMOVABLE — right-
  click remove now returns ClearResult: local capture deleted normally;
  when a bundled pin sits underneath it's masked with a local
  {cleared:true} TOMBSTONE (capture replaces it) — a wrong SEEDED pin
  must be fixable in-game. tools/audit-shops.mjs — cross-checks every
  seeded shop pin: its wiki page must BE a shop page (stock table),
  SELL the step's item (word-normalized, possessive/plural/containment
  tolerant, any part of a comma/and list), and the pin must match the
  page's {{Map}} (drift >15 flags). First sweep caught FOUR town-page
  pins whose {{Map}} was a random landmark: Ardy farming shop (church!
  -> Richard's Farming shop 2645,3360 — the owner's compost-icon-over-
  Councillor-Halgrive report), ardy general store (-> Aemad's 2614,
  3293), Seers bar stew (-> Forester's Arms 2695,3493), Shilo fishing
  shop (-> Fernahei's 2871,2970). Now 17 OK / 5 hand-pinned / 0 flags.
  Gotcha: wiki action=raw does NOT follow redirects — chase #REDIRECT
  manually (audit-drops' species fallback masked this).
  LATE WAVE 6: SHOP PHRASES are place links (seed-shops --places writes
  distinctive applied phrases into places.json — display MUST be the
  guide's phrase, linkify matches DISPLAYS not keys; generic "general
  store"/"the bar" stay unseeded). Badge-icon width bug: an icon's
  width comes OUT of the badge's 170px html body or every row widens
  and Go/⌖ fall off-panel. SHOPKEEPERS seeded (seed-shops --npcs ->
  places/shop_npcs.json, infobox |owner=, 17 incl. hand-added Ordan
  x2); purchase subs join the keeper into the NAMED-NPC scan — named
  beats nearest-to-pin (Master Farmer wore the compost icon).
  JUMPED-AHEAD REWRITTEN: the old "any later-step quest IN_PROGRESS"
  test was ON almost permanently (owner's journal: 17 quests parked
  yellow at once by route design) — every auto-nav silently dead, THE
  recurring "nav is broken" report. Now only a LIVE NOT_STARTED ->
  IN_PROGRESS transition of a later-step quest arms it
  (lastQuestState baseline map, login-grace gated, cleared on relog);
  disarms on quest finish, frontier catch-up, or ANY route progress
  (completeSubGoal). Every maybeNavigateToNext outcome logs one INFO
  line on change (logNavDecision) — mine-session-log makes nav
  stand-downs greppable. seed-npc-spots.mjs: grind steps ("train 42
  magic at Moss giants near fishing guild") get a ⌖ at the NPC's wiki
  {{LocLine}} surface cluster nearest the step's place (moss giants
  2553,3406 seeded — nav was routing to the guild gate). Skill icons
  on level/counted/requirement badges (SkillIconManager via
  panel.setSkillIconSupplier). SAFESPOT capture: ⌖ right-click menu
  "Capture as safespot" (Target.safespot flag) — tile gets the
  floating "Safespot" label on steps whose text never says the word.

- SESSION WAVE 7 (2026-08-05 late night, live play-test, main at
  fcae7ab): DEATH ROUTING — ActorDeath pins nav on the death tile
  above everything (Gravestone tile marker, 10-tick re-post, clears
  within 8 tiles; teleport detector MUTED while a grave waits — the
  respawn jump must not tick travel subs). CARDS RESTYLE shipped in 3
  rounds: mockup artifact -> variant C; ACTIVE cards near-black
  #1f1e1b vs DONE flat grey #2b2b2b + 0.55 alpha (owner wanted the
  inversion); the REAL clipping culprit after 2 blind rounds was the
  scroll wrapper — a plain JPanel lays out at PREFERRED width inside
  a JViewport and HORIZONTAL_SCROLLBAR_NEVER hides the overflow;
  ViewportWidthPanel (Scrollable, tracksViewportWidth) fixed it and
  StepRow self-logs "card wider than viewport" naming the widest
  child. Capitalize display names (ItemTracker.capitalize; skill
  badges stopped lowercasing) — display only, matching stays
  lowercase. ROUTE-AWARE TELEPORT HINTS: minigame_landings.json
  (13 surface landings, interiors use surface exits) — when the
  frontier target (or gravestone) is >100 tiles and a landing is
  <60% of player distance, the Grouping click-path lights unprompted;
  gated on the 20-min cooldown (VarPlayer.LAST_MINIGAME_TELEPORT=888);
  presence/region learning kills it post-teleport (interiors keep 2D
  distances huge). SPELL fallback: TELEPORT_SPELLS (standard book,
  level + LAW runes carried + quest gates; elements unchecked —
  staves) highlights the spell widget/Magic tab via the same overlay.
  BARCRAWL bar pins re-seeded to QH's exact bartender WorldPoints
  (all 10 were door-adjacent); bartenders joined shop_npcs.json
  (Bartender/Zembo/Kaylee/Blurberry) and the keeper join DROPPED its
  purchase-goal gate (curated entry = intent; barcrawl subs carry varp
  checkpoints). CONFIRMED IN PLAY: fight-pit hint end-to-end, stamp
  tick, bartender outline. WATERFALL WEDGE: "Drop runes outside
  glarial's tomb" (no quest tag, items consumed) seeded varp 65>=5.
  CHARTER_DOCKS network (8 docks, spirit-tree treatment) for
  "Charter to X" subs. QH INTERFERENCE FOUND: Quest.getState runs a
  CLIENTSCRIPT and the jumped-ahead scan called it ~100x EVERY TICK
  (+ per-sub subsume calls) — enough script load to break QH pathing
  (owner had to reload quest state); lastQuestState is now the cache,
  scan every 5 ticks, all per-tick readers use cachedQuestState.
  NULODION'S NOTES: kit name was invented ("notes for dwarf cannon",
  id 3 correct) — sprite fine, counting dead; AUDIT SECTION 4
  (GoalAuditDumpTest dumps gameval ItemID constants ->
  build/item-id-constants.tsv; audit-goals verifies every item_ids
  KEY against its id's constant name, word-form tolerant, VERIFIED
  allow-list for hand-checked colloquials). ONE OPEN SUSPECT: "big
  frog leg" -> 7908 RAG_MEDIUM_FROG_BONE — ask owner (wishlist bone
  vs food). BANK FILTER FREEZE: the section window re-anchored on the
  live frontier every rebuild — withdrawals auto-ticked steps and the
  layout jumped mid-banking; frozenFilterStepIds fixes composition at
  filter activation (live counts, done steps stay green), unfreezes
  on deactivate/fresh bank open. NETWORK-TRAVEL ARRIVAL: a ⌖ on "Charter to
  X" marks the BOARDING dock — charter/spirit-tree subs skip the
  precise-target arrival branch, only the text destination (or 📍)
  proves arrival; "charter" joined MOVEMENT_WORD (keeps the sub
  current so the travel overlay scans the ship Destination interface
  — probe saw group 72). GP-COST BADGES (seed-gp-costs.mjs): buy
  steps get a coins ItemNeed = wiki item |value| x qty (30 seeded;
  Barrows gloves hand-set 130k; Zeah compost hand row); coins are
  EXCLUDED from the annotationItemsCarried arrival gate (money spent
  mid-step must not wedge the destination tick). Zeah step: saltpetre
  (dig spot 1700,3522) + compost (Vannah 1763,3594) item_sources;
  Vannah keepered; GATHER subs anchor at their item goal SOURCE when
  no purchase goal (item_sources precision = safe). INHERITED AND-
  TAIL goals: "get 100 compost and saltpetre" gives the bare tail
  the same number (AND_TAIL_STOP filters verbs/adverbs — the audit
  sweep caught 5 garbage tails on the first run, stop-list extended).
  Zeah = Veos to Port Piscarilius (owner) — no charter alias needed.
  Session ended at HEAD, all pushed, audits 0/0/1. OPEN: big frog leg
  verdict (item_ids 7908 RAG_MEDIUM_FROG_BONE — wishlist bone vs
  food?); death test for gravestone routing; minigame landings,
  charter docks + gp costs owner-tentative; hub pin still a07a1eb —
  bump after a calm session on this build.

- SESSION WAVE 8 (2026-08-06, all-day live play-test, ~50 commits):
  ERRAND CHAINS V2 — stages gained: varbit/varp+value GATES (quest
  progress orders stages proximity can't: RFD Cook varp 1850>=2 ->
  doors >=3; PAR Osman varp 273>=20 unlocks crafting), preQuest flag
  (prep chains guide before quest start), route/satisfaction SPLIT
  (routeX/Y/Plane: SP can't draw into interiors — route the surface
  trapdoor, satisfy at the ladder bottom; milk chest, boots of
  lightness, ghost's skull), per-stage radius (chest sat 10 tiles
  from the ladder inside the default 12), npc (NAMED outline — Aggie
  mid-wander; named beats nearest everywhere now), items (stage-
  focused inventory hints, QH-style), dialog (chat options recolor
  blue via Chatmenu.OPTIONS, QH strings); waypoint stages never
  nominate NPCs; chain-complete = nav HOLD + no first-leg hints.
  Chains seeded: RFD start (fruit blast must be MADE — premade
  fails; owner-captured doors 3213,3221 = QH's exact tile), milk
  (Culinaromancer chest = object "Chest", vendor outline nearest-
  only + goal-item icon overhead on ObjectTargetOverlay), PAR prep
  (Aggie/Ned/Keli + dialogs), Mordred (bat bones + black candle),
  boots of lightness, ghost's skull. FIRST-LEG HINTS rewritten:
  errand-aware target, spirit-tree EFFECTIVE distance (5-tree
  network, +20 toll, TGV-gated), minigame/spell/FREE-home-teleport
  (varp 892, 30min) COMPETE, per-minigame entry gates (NMZ 5-of-38
  boss quests, PC/SW cb40, Shades quest, TB CabinFever+40cook),
  labeled highlight names the pick, no hints across the dungeon
  y-offset (basement "teleport to Lumbridge" fiction), prescribed-
  transport subs get no suggestions. NAV: unstarted quest goals
  route to the quest-start pin in targetFor; QH-owns branch honors
  explicit ⌖; BANK-FIRST reads annotation kits + runs mid-quest +
  fires on ANY banked shortfall (unowned items don't veto); login
  resume (navOnLoginPending post-grace) — OPEN: one session showed
  total nav silence with config ON; game-state transition + config
  logs shipped, read next session's log. DIARY TASKS (Tier 3
  RETIRED): seed-diary-tasks.mjs mines QH's 40 diary helpers into a
  420-task atlas (varp-bit + varbit not-form flipped to >=1), draft/
  pick/apply; 10 checkpoints live incl. first requiresAll (dying
  tree + plank both bits) and tier-complete gates (Varrock easy
  4479, Fremmy hard 4493). NOTES: annotation `note` renders as NOTE
  block, \n lines, bold "Topic:" lead-ins; seed-recipes MULTI-
  product (shared mats sum: 13 dyes = 65 coins) + ingredient flag
  (indented "(ingredient)", CARRIED-ONLY counts — bank coins lied)
  + optional flag ("(optional)", grey unmet, never gates; full HAM
  clothing set). DETECTORS: LOOT_FOR_ITEM ("pickpocket X for a
  rusty sword"; 3-word tail cap + Quest-enum + stop-list guards);
  arrival gate moved ABOVE the ⌖ branch (HAM hideout false-ticked
  on entry); grind-object outlines (STALL_PHRASE + pick-plants
  share their item's name — onions/cabbage/flax) + XP_PER_ACTION
  "N to go" label (fruit stall 28.5, wiki training-guide cross-check
  lives in seed-npc-spots drafts as guideContext; {{ObjectLocLine}}
  + Windows case-collision cache fix). NAME ALIASES (colloquial vs
  real): skin paste->Paste 2424, key imprint->Key print 2423, rune
  mysteries notes/package->Research notes/package, teleports->
  Teleport card 13658, + rusty sword 686 DIGSITESWORD, buttons 688.
  item_sources grew vendor (String NPC name -> named outline;
  Diango, Vannah) and npc:false (object vendors skip ALL nomination
  incl. firstNominatingPlaceIn on purchase text). Pins: lumbridge ->
  castle courtyard 3222,3218; dye step = Aggie 3086,3257 + 3-recipe
  kit; Traiborn/Harlow/Funch/beggar/Thrantax/Osman/Duke ⌖s.
  audit-nav now counts errands + item sources as coverage (169/575
  uncovered, near-route clean, rest grinds/Various). OPEN: nav
  silence root cause; bank filter vanishing icons (composition log
  shipped — if icons vanish with no new line, the bank redrew
  without our pass); deliberate death test; Imp Catcher kit's
  rune-mysteries-notes intent (tower-trip convenience vs prune);
  onion-gate pin uncaptured; hub pin at b8c994d (pre-feature-storm)
  — bump ONLY after a calm session on this build.

- SESSION WAVE 9 (2026-08-07, morning play-test): LOGIN RESUME ROOT
  CAUSE FIXED — fresh logins run LOGGING_IN -> LOADING -> LOGGED_IN,
  so the old "lastGameState != LOADING" real-login test NEVER fired
  on fresh logins (grace, baselines and the resume were all silently
  skipped since forever); real logins now tracked via a sawLoggingIn
  flag, log-proven working. CHAIN RULE: an unsatisfied errand chain
  blocks the sub's ITEM-GOAL ticks too (the scrying orb ticked at the
  Chaos Temple with the essence-mine leg ahead) — the chain defines
  "done". Chains seeded: Enter-the-Abyss orb (Varrock mage 3259,3383
  -> Aubury route/essence-mine ~2920,4830 satisfaction, dialogs),
  R&J Apothecary (3195,3405, berries item-hints, dialogs), Traiborn
  key (LOOT_FOR gained give|hand: the KEY 2401 is the goal, bones
  x25 ingredient badge). Dialog recolor needs the PER-TICK reapply
  (chat menus rebuild children without a widget-load). Chronicle
  worn-slot teleport hint (equipment tab STONE4 -> Wornitems.SLOT5,
  labeled). KIT CORRECTIONS (finishing steps must list what the
  FINALE needs, not the quest-wide wiki list): Demon Slayer = 3x
  silverlight key (bones/coin/bucket were consumed on earlier key
  legs — permanently red); R&J = cadava potion (berries spent at the
  Apothecary); transient Message pruned (Romeo eats it — policy).
  seed-quest-gear-notes.mjs: QH's generic Bring/Recommended gear
  guidance (combat gear/food/potions — the class the kit JUNK filter
  drops) appended as notes on 39 quest kit steps. cross-check gained
  a STALE? flag (ours-only + consume-verb in QH text) — REVIEW AID:
  bring-then-consume-in-finale items are GOOD kit entries; only
  earlier-step-consumed ones prune. Ids/aliases: message 755, cadava
  potion 756, pirate message 433, scrying orb 5519, silverlight/
  demon slayer key 2401, research notes/package aliases. Junk
  "Tutorial Island" 3100,3100 pin removed (a real route went there).
  Varrock pin -> square 3212,3424. OPEN: bank filter vanishing icons
  (composition self-log armed, NO repro captured yet — bank with the
  filter on and mine the log); deliberate death test; onion-gate
  capture; hub pin at b8c994d, now ~70 commits behind — bump after a
  calm session.

- SESSION WAVE 28 (2026-08-14, long live play-test 287 -> 290; main at
  **`9ddfe8c`**, **15** commits, PUSHED; hub pin still `ae9f062`, gap **32** —
  counted, not carried):
  **THE PANEL SCROLL IS SOLVED, AFTER SIX ROUNDS, AND IT WAS NEVER IN THE
  SCROLL CODE.** Every step's text is a `JEditorPane`, and every one carries a
  caret. When its document changes — which happens to any row whose item
  counts are rewritten, i.e. every row, whenever a BANK or SHOP opens — the
  caret moves, and a moving caret calls `scrollRectToVisible` on itself. That
  walks up to the JScrollPane and drags the view to that row. So a row twenty
  steps down could haul the view **6,489px** with the panel having scrolled
  nothing, which is why the log was empty every single time and why five
  rounds of anchors, timing and re-assertion never touched it. `muteCaret()`
  (NEVER_UPDATE + non-focusable) on both pane construction sites;
  `CaretMutedTest` asserts the pairing, verified to fail with one call
  removed. CONFIRMED IN PLAY: bank opened, no jumping.
  **WHAT ACTUALLY FOUND IT WAS MAKING THE VIEWPORT NAME ITS OWN CALLER.** A
  `ChangeListener` that logs every movement which is neither ours nor a real
  gesture, with the stack. It named `DefaultCaret.adjustVisibility` in one
  read. **I shipped it two rounds later than I should have** — wave 26 and 27
  both wrote down "when the failure has several indistinguishable causes, ship
  the diagnostic before the fix", and I still offered two more theories first.
  The probe is LEFT IN, and it earned its keep immediately by mis-blaming
  three ordinary wheel clicks: FlatLaf installs its own wheel listener ahead
  of ours, so the viewport moves before our flag is set. Fixed by claiming the
  gesture while a `MouseWheelEvent` is in flight.
  **THE OTHER HALF OF THE SCROLL: STOP INFERRING WHETHER THE PLAYER
  SCROLLED.** Every version compared the viewport against where we last put
  it — but Swing moves it too (a rebuild empties the content and the position
  is CLAMPED), so a rebuild was indistinguishable from a hand on the wheel,
  and the landing stood down mid-retry **with no log line**. Now a real wheel
  turn or thumb drag is the only thing that counts, and both stand-down paths
  log. Also: the anchor is released when a new landing begins (they were
  briefly tugging — `holding step 288` while landing on 289; benign, but two
  parts pulling opposite ways is how the next mystery starts).
  **SECTIONS ADVANCE THEMSELVES** — finishing the last step of a section left
  a finished section and a "Next" button, because a rebuild only redraws the
  section already open and the frontier-follow runs on a path a completed step
  never triggers. Only advances out of the section it was ALREADY following.
  CONFIRMED IN PLAY.
  **"OPTIONAL" NEVER DID ANYTHING, AND IT IS THE DOCUMENTED FIX FOR A WHOLE
  BUG CLASS.** The arrival gate (`annotationItemsCarried`) skipped only
  `consumed` and money — never `optional` or `granted` — while the
  possession and purchase gates skipped both. So marking an item optional,
  applied three times across waves 19/26/27 (ghost's skull, Camelot lit
  candle, step 280's dyes), **unwedged nothing**: all three were still blocked
  when measured, along with 6 more. **9 steps guide-wide.** Now skipped;
  `ItemGateConsistencyTest` asserts the three gates agree, verified both ways.
  MEASURED AND LEFT ALONE: 51 steps list unnumbered carry-kit items that
  SHOULD gate ("Lumby" + axe/rope/hammer/spade = arriving PREPARED, wave 18).
  **STEP 288 TOOK FOUR PASSES AND EVERY ONE TAUGHT SOMETHING.** "Smelt the 5
  silver, make a sickle and unstrung holy symbol, keep 3 bars": (1) the alias
  map turned bare "silver" into *silver bar*, so it asked for 5 bars to make 5
  bars — the word appears in ONE step guide-wide and means ore; (2) "keep 3
  bars" was an acquisition goal sitting green at 3/3 off banked bars — the
  numbered scan never looks at the word BEFORE the number, and the general
  rule (veto any number after a not-an-item verb) was MEASURED AND REJECTED,
  2 of its 3 hits being real goals ("Bank 7 logs", "Fill 3 buckets with
  milk"); (3) it then ticked on WITHDRAWING the ore — what you smelt is
  consumed, so it is the material, never the objective (the only other smelt
  step was asking for **28 coins**); (4) removing that goal is what let the
  "make ..." rule run at all, since the numbered pass RETURNS the moment it
  finds anything. "make A and B" now takes both — and promptly invented a
  "birdhouses" goal on "Get 58 slayer, make SURE you're consistent with all
  your farming and birdhouses", caught in the goal dump, not in play.
  **I SAW RISK (3) COMING WHILE MAKING FIX (1) AND SHIPPED WITHOUT SAYING SO.
  The owner met it in game. Say the thing.** CONFIRMED IN PLAY: stayed
  unticked through smelting, ticked when both items existed.
  **NEW `StepAnnotation.completeOnItems`** — a step may DECLARE that holding
  its items finishes it, since no phrasing rule can tell that a step ends with
  a sickle and a symbol in the bag. Seeded then REMOVED from 288 within the
  hour: once the two product goals existed they did the job, and left in place
  with only the moulds listed it would have completed the step for owning the
  moulds. **The field stays; the lesson is that a completion path and an item
  list are different things.**
  **SHOP-FIRST ROUTING** (owner: "we need to navigate to them to buy them").
  Mirrors bank-first: own ZERO of a numbered requirement and we know a seeded
  source -> route to the shop, once per step, chat line naming item and
  seller. Bounded by data (~24 sourced items), not by cleverness. CONFIRMED IN
  PLAY on its first login. **It then failed to come back** — buying is no more
  an event than withdrawing, which the bank branch learned in wave 18 and I
  did not carry across; same 10-tick re-check now. And the vendor was routed
  to but NOT outlined: the vendor scan read only goals detected from step
  TEXT, and the moulds are annotated. Both fixed, NEITHER CONFIRMED.
  **DATA:** desert disguise is a FAKE BEARD + KHARIDIAN HEADPIECE combined (no
  such item is sold, so the step asked for something unbuyable); both moulds
  from **Dommik**, Al Kharid 3321,3194 (shop page and NPC page agree);
  step 289 gained key print + bronze bar. **Its note was INVENTED** — said
  Osman has the key made; three wiki pages and then the owner say YOU smelt it
  at the furnace. **Second invented note after wave 15's Candle maker: a note
  describing a MECHANIC needs a source.** A ⌖ on a furnace now carries
  `npc:false` (a level-2 Man was outlined wearing a sickle icon).
  **PROCESS:** every build in a throwaway worktree, all removed same-task; a
  `git checkout` to revert a test edit silently reverted the FIX too (re-check
  what survived); and a monitor grepping "Exception" caught two known
  third-party throws — wave 27's exact trap, walked into again.
  **OPEN:** vendor outline, shop chat line, buy-then-move-on, and the anchor
  stand-down are all UNPLAYED; 282 and 316 still unplayed; WK-1 is
  SUPERSEDED at the root (optional now works) but the audit for kit items
  belonging to a LATER step is still unbuilt; hub gap 32.

- SESSION WAVE 27 (2026-08-13, long live play-test 276 -> 282; main at
  **`3710ba7`**, **14** commits, PUSHED; hub pin still `ae9f062`, gap **14** —
  counted. **RECOMMENDED: DO NOT PIN YET** — see the end of this entry):
  **THE THEME IS THAT FOUR CONSECUTIVE DIAGNOSES OF ONE BUG WERE WRONG, AND
  THE THING THAT SETTLED IT WAS MAKING THE PANEL REPORT ITSELF.** Wave 26
  wrote the rule — *when the failure has several indistinguishable causes,
  ship the diagnostic before the fix* — and this session broke it twice
  before following it.
  **THE PANEL SCROLL, IN FOUR ACTS.** The owner's report never changed ("it
  scrolled to steps I've completed"), and each attempt was a real defect that
  was not his: (1) the settle test compared the raw target offset rather than
  the position APPLIED, so a still-growing view could go quiet while the
  clamp was still moving; (2) a step near the END of a section cannot be
  lifted to the top at all — there is no list below it — and step 278 is the
  **279th of 286**, so a tail was needed; (3) nothing re-landed the panel when
  the frontier moved, because completing a step fires no rebuild and the
  per-tick path only RESTYLES rows. Only then did the diagnostic go in, and it
  was conclusive in one read: **exactly ONE scroll line existed for the whole
  session** — the login landing — while the view drifted repeatedly. The code
  was not scrolling to the wrong place; **it was not running at all**. Two real
  causes followed: `rebuild()` passed **no scroll target**, so any rebuild
  (`::ironreload`, a config change, a profile switch) threw the position away;
  and the position is a PIXEL offset into a **66,523px** view shown through an
  **847px** viewport, so item icons loading in the rows above carry your step
  a long way down with nothing having scrolled. Anchor the ROW, not the pixel,
  and drop the anchor the instant the viewport is not where we left it.
  CONFIRMED IN PLAY. **The general lesson: "it lands in the wrong place" and
  "nothing re-lands it" look identical on screen and want opposite fixes.**
  **A FIX WAS DISPROVED BY ITS OWN OPPOSITE WITHIN THE HOUR.** Following the
  router's chosen leg kept highlighting a Lumbridge home teleport while the
  player was in the tunnels UNDER Lumbridge, long past using it — so legs
  whose ORIGIN was far away were declined. An hour later that rule hid a
  Lumbridge home teleport he genuinely needed, **from the very same tunnels**.
  Same position, opposite correct answers, so position could never have
  separated them. The discriminator is FRESHNESS: a position jump now clears
  the router's last word, and anything still wanted is re-reported within a
  second by the re-post that already follows a jump. **Ask what could tell the
  two cases apart BEFORE writing the rule.**
  **FOLLOWING THE ROUTER INHERITS ITS JUDGEMENT, INCLUDING THE SILLY BITS.**
  Standing in Falador, GPS picked *Falador Teleport* — a **33-tile** hop,
  2995,3366 -> 2964,3378 — because its cost model rates a teleport as nearly
  free, and we lit up the spell. Our OWN hints have had a 75-tile floor since
  wave 23; the follow bypassed it entirely. Same floor both ways now, so a
  highlight means the same thing whoever proposed it. We still never argue
  with the route — the router draws what it likes; we decline to point at the
  button.
  **ONE WRONG KIT ITEM SILENTLY BLOCKS A STEP — SECOND TIME IN TWO SESSIONS**
  (wave 26's "Lit candle"). Step 280 "Falador teleport" carried Goblin
  Diplomacy's blue and orange dyes as plain requirements, so arrival could
  never fire. They belong to 281 and are `bringAhead` + `optional` now.
  **A CLASS WORTH AN AUDIT AND NOT YET BUILT: any item on step N that also
  appears on step N+1, where N+1 is what consumes it.** Note also that a data
  fix CANNOT retroactively fire an arrival — he was already standing there, so
  280 needed a hand tick.
  **EMOTE OVERLAY SHIPPED, worked first try, and its real lesson is about
  knowing when to STOP.** "Perform the Goblin Bow emote next to Mistag" left
  him scrolling eighty icons. Matched by **SPRITE, never list position** (the
  order shifts as emotes unlock — Quest Helper matches by sprite for the same
  reason); names from QH's `QuestEmote`, resolved through RuneLite gameval so
  a dead name is a compile error. Deliberately does **NOT** auto-scroll the
  list as QH does: that needs a `runScript` from inside a render, which is
  what hard-froze this client twice in the bank-filter work. Then two rounds
  on stopping it: the annotation hangs the emote on a STEP, and "Continue Lost
  tribe" spans far more of the quest than the moment the emote is wanted, so
  the step can never say when to stop pointing. Clicking the emote is the only
  unambiguous "found it" — and that flag **must persist**, since a session-only
  one brought the hint straight back on the next restart. New annotation field
  `emote`; new `emotedone_<VARIANT>` progress key. Seeded on The Lost Tribe
  only; QH's source lists the rest for a bulk pass.
  **ALSO SHIPPED:** the Seers' church organ as an OPTIONAL diary stage on the
  boots-of-lightness chain (coords/object/bit from QH's KandarinEasy — memory
  put the church 25 tiles out; CONFIRMED IN PLAY, he played the organ);
  `Errand.icon` because a stage with no item inherited the STEP's goal and
  hung Boots of lightness over the organ (a NAME not an id, matching
  ItemNeed.icon, so the id audits stay meaningful); a stage naming a GENERIC
  way up or down now accepts any traversal object at the point, since the game
  says stairs/staircase/steps and an exact guess one letter out silently
  outlines nothing.
  **OPTIONAL ERRAND STAGES ALREADY ROUTE.** Reported here as nudge-only and
  offered as work to build — the log shows them routing, and he played the
  organ because of it. Read the behaviour before offering to build it.
  **FOUR STALE WORKTREES HELD REAL UNCOMMITTED WORK.** Called them harmless
  first, which was wrong: `isc-verify` alone had **458 added lines across 11
  files** (confirmed real, not line-ending noise, by re-diffing with
  `--ignore-cr-at-eol`). Sampling distinctive added lines against today's main
  showed three fully redundant and the fourth a superseded `::ironwrong`
  draft — nothing lost. Patches saved to the session scratchpad before
  removing. **A registered worktree is invisible storage for uncommitted work;
  create and remove them within the same task, as every build this session
  did.**
  **TOOLING BIT BACK THREE TIMES, ALL MINE:** gradle reported "BUILD
  SUCCESSFUL in 2s" for a `test` task it had not run (verify from
  `build/test-results/*.xml`, never the summary line — wave 25's lesson, again);
  a watcher grepping `error:` matched a third-party resource pack's parse
  errors and called a healthy launch a build failure; another grepping
  `com.ironscape` matched the compiler printing a FILE PATH and declared the
  plugin up before it was.
  **CONFIRMED IN PLAY:** panel scroll (after four attempts), the emote outline,
  the home-teleport highlight, route freshness after a jump, the organ stage,
  step 280 unblocked. **NOT CONFIRMED:** the Emotes TAB highlight (STONE12 is a
  reading of the tab order, never exercised — he had the panel open), emote
  persistence across a restart, the 75-tile floor on a relayed leg, the organ's
  diary icon, the stairs outline.
  **HUB PIN — RECOMMEND WAITING.** 14 commits, five of them unplayed, and the
  standing rule is not to pin until a session goes quietly; this one had four
  wrong diagnoses of a single bug. Play a calm session on this build first,
  then push -> re-pin -> **wait for the `build` check**
  (`gh pr checks 14207 --repo runelite/plugin-hub`).
  **OPEN:** step 276's chain models only Merlin upstairs, not King Arthur
  downstairs — CONFIRMED as a real gap this session, left alone because he is
  past it; the duplicate-kit-item audit is unbuilt; emote seeding is one step
  deep; 282 and 316 still unplayed.

- SESSION WAVE 26 (2026-08-12, desk + long live play-test 268 -> 276; main
  ended at **`ae9f062`**, PUSHED; **hub pin BUMPED to `ae9f062`, hub `build`
  check PASSING** — the gap from `bb3e11e` was **68** commits, not the 57 an
  earlier draft of this entry claimed, so COUNT it rather than carrying it):
  **THE PIN BUMP FAILED THE HUB FIRST TIME, AND A CLEAN LOCAL BUILD DID NOT
  PREDICT IT.** `gradlew clean build` from a fresh clone at the pinned sha
  passed (jar built, 120 tests, 0 failures) and the hub still rejected it:
  *"plugin uses terminally deprecated APIs: Do not create fresh Gson instances,
  always @Inject the client's Gson."* Same day's own work — the teleport-item
  index initialised its field with `new Gson()`, which was redundant as well as
  forbidden (startUp and `::ironreload` both load it with the injected Gson);
  it starts `TeleportItems.empty()` now. **The rule exists ONLY in the hub's
  checker**, so it surfaces hours later on a public PR — hence
  `HubComplianceTest`, which scans MAIN sources for fresh Gson instances and
  for reflection (the rule that cost the QH handoff in review round 1),
  verified BOTH ways. Add a rule to it whenever the hub teaches us one.
  **Bumping the pin is therefore a two-step job: push, re-pin, then WAIT for
  the hub's `build` check** — `gh pr checks 14207 --repo runelite/plugin-hub`.
  Its red X is normally "Requires maintainer review", which fails by design; a
  failing check named `build` is real.):
  **DX-6 AND DX-5 BOTH SHIPPED, AND THE FIRST THING DX-6 DID WAS PROVE US
  RIGHT.** Shortest Path's `postPluginMessages()` posts back every transport on
  the route it chose — `origin`/`destination` as WorldPoints, `objectInfo` and
  `displayInfo` as strings, `displayInfo` documented as *"the destination option
  to pick"* (`Ardougne cloak: Kandarin Monastery`). Four things checked before
  trusting it, each of which could have killed it: `postTransports` defaults
  FALSE (Debug section) but `override()` reads their static configOverride
  first, so **our own "path" message switches it on** via the `config` key; the
  HUB-RELEASED build has it (checked the plugin-hub pin `9953d527`, not master —
  coding against master while everyone runs the release is the obvious trap);
  the callback runs on **SP's pathfinding worker thread**, so store-and-log
  only; and their override map is **wiped by any `clear`**, so the config must
  ride on EVERY path post — hence one `postPath` / `postClear`.
  **WAVE 25'S VARROCK ANOMALY IS NOT A DEFECT.** Asked directly, SP picked the
  SAME Varrock teleport: the real route is Varrock -> GE spirit tree ->
  Battlefield of Khazard -> Ardougne wall door, and Khazard lands **40 tiles**
  from that door while the cloak's only landings sit ~92 away. Our overlay and
  SP's line had been agreeing all along. Settled by ASKING rather than
  re-deriving our own arithmetic.
  **DX-5: `teleportation_items.tsv` WAS ALREADY IN OUR CACHE** — SP maintains
  it. 319 destinations, 225 item ids, with landing tile, menu option and the
  unlock/charge varbits (diary cloaks, jewellery, tablets, memoirs). Do NOT
  wiki-scrape this. Two traps in their data, both handled and TESTED: the
  SKILLS column also carries the max cape's TOTAL level and the quest cape's
  QUEST points, which are not skills; and a `&` clause is a BITMASK needing that
  exact bit, not a threshold (the barcrawl trap). Everything unevaluable fails
  CLOSED.
  **OUR OWN RANKING COULD NEVER HAVE PICKED A TELEPORT ITEM, and that is the
  lesson.** `TravelDistances` holds one precomputed field per NAMED landing —
  25 of them — and looks up BY NAME, so all 319 item landings answered UNKNOWN
  and lost every contest SILENTLY. Adding 319 fields was the obvious fix and the
  wrong one: **SP's pick now outranks ours whenever it names an item**, ours is
  the fallback. CONFIRMED IN PLAY once the owner lowered his own
  `costNonConsumableTeleportationItems` (50 vs 15 for spells is why the spell
  kept winning — **that dial is the player's, not ours; do not add a thumb to
  the scale**).
  **A CORRECT ANSWER DRAWN WHERE NOBODY LOOKS IS STILL A BUG.** The cloak hint
  fired perfectly first try and was invisible: the outline draws on the WORN
  EQUIPMENT panel and he was on the inventory tab. The Chronicle has had the
  answer since wave 9 — highlight the worn slot, else the equipment TAB,
  labelled — now reused for any worn teleport item.
  **THE PANEL "JUMPING" WAS THE SCROLL, NOT THE TARGET**, and it was settled
  OFFLINE from the manifest + saved progress rather than guessed: position 270,
  everything from 271 incomplete, so it correctly chose 271 and displayed 275.
  The scroll fires once the target row has a height — but rows keep growing
  after that (item icons load async, html panes size late), and a row above
  growing slides the target out of the view just set. Step 274 lists twelve
  items. Re-asserts until the position stops moving now.
  **PETS ARE NEVER THE ANSWER**, and the owner's question behind it was the
  better one: *"how do we still have no NPC names for all quests?"* We only ever
  seeded the GIVER. The nearest-to-the-pin fallback has crowned rats, a Zamorak
  crafter, a Market Guard, a Master Farmer and his CAT — each patched
  separately, because it had nothing to check itself against. The 4-tile cap
  protects nothing when you are STANDING on the target. **`seed-quest-npcs.mjs`:
  112 of 114 quests, 1329 NPC ids, from Quest Helper, BY ID** (`NpcID.TRUFITUS`
  + every scene NPC carries an id), which sidesteps the whole family of name
  faults — articles, plurals, species suffixes, a name inside a place name.
  Quests with no index keep the old behaviour exactly. Two tool lessons: listing
  a directory per quest through the UNAUTHENTICATED GitHub API blew the 60/hour
  cap and took the run from 96 quests to 20 — **which reads identically to
  "Quest Helper has no file for this quest"** (goes through `gh` now, raw fetches
  stay the fast path); and the last four misses are hand-aliased, because
  anything loose enough to catch `Vampire Slayer -> vampyreslayer` also pairs
  Dragon Slayer I with dragonslayerii.
  **NEW `ItemNeed.icon`** — a row standing for a CATEGORY ("Food", shark
  sprite). Deliberately NOT `id`: an id changes what is COUNTED and would trip
  the audit that catches genuinely wrong ids, which is a rule worth keeping.
  **SEEDED:** Gertrude pin on the kitten step + gated on holding a **Pet
  kitten** (owner-confirmed name; 1554-1560 are colours, counting is by NAME so
  every colour counts, `KITTENOBJECT` is only the constant); the five bare
  "Chronicle tele" steps pinned at the LANDING (owner's call: ticking there is
  what hands routing to the next step — `audit-teleport-landings.mjs`, 9 hits of
  which 4 are correctly left alone); and 9 carry items that lived only in NOTE
  prose across 5 steps (`audit-note-items.mjs`), each read first, since caviar is
  what you PRODUCE and "25k buckets worth of sand" is a measure — both recorded
  as declined WITH THE REASON.
  **MEASURED AND RULED OUT:** 107 of 118 location tags already resolve, so the
  39 no-route steps are NOT a missing-places problem
  (`audit-location-tags.mjs`).
  **PROCESS:** every build ran in a throwaway worktree while the client was
  live. I killed one client launch myself with `Select-Object -First 30`, which
  closes the pipeline and terminates the process it reads from. And I hand-
  escaped JS through PowerShell twice and it failed both times — **write the
  script to a FILE and run it**, exactly as wave 25 recorded.
  **LATE WAVE 26 — GPS, AND WE WERE THE ONES BREAKING QUEST HELPER.**
  **POLICY (owner): SUPPORT BOTH PATHERS, GPS PREFERRED** — he switched himself
  (`runelite.gpsplugin=true`, `shortestpathplugin=false`) and rates it better,
  but Shortest Path stays supported for users who want it. That costs nothing
  and needs no branching, which is exactly why the channel choice below is the
  load-bearing decision. GPS is a FORK of Shortest Path that keeps
  `"shortestpath"` as a documented compatibility namespace — inbound accepted on
  either, broadcasts on BOTH — so every message we send and this morning's
  transports listener work UNCHANGED, and it honours the same `postTransports`
  override. **Keep posting on `"shortestpath"`**: one message then serves either
  plugin, where GPS's own `"gps"` namespace would silently do nothing for SP
  users. It republishes when the DISPLAYED route changes, so when the player
  picks a different alternative in GPS's panel our highlight follows THEIR
  choice. Never run both — one message draws two routes. Its cost dials are a
  separate config group, so his SP tuning does not carry over. Its directions
  panel ("Walk / Open Gate / Glider to Gandius", ETA 38s) is genuinely better
  information than a line on the ground, and it names the requesting plugin —
  we now send `source` so a route of ours reads IRONSCAPE Optimal rather than
  "another plugin".
  **TWO FAULTS, ONE ROOT: we treated the pathing plugin as OURS ALONE.**
  (1) **A "clear" wipes whatever is displayed, whoever set it** — and standing
  down for QH posted one on EVERY evaluation. So a route QH had set died the
  moment anything made us re-evaluate, and came back on "reload quest". The
  owner's report was exact: *"QH navigation got stopped when i used Lumbridge
  teleport. Which was what it wanted to do."* A clear now only fires when we
  have something posted; `clearPath` delegates to the same rule rather than
  keeping a second copy of "when may we clear".
  (2) Following the router's pick covered teleport ITEMS only, so a spell, the
  home teleport or a minigame teleport lit nothing (*"GPS wants to take me to
  Lumbridge, but there are no overlays in our TP book"*). `applyRouterChoice`
  resolves all four, colon-suffixed spell variants included — and now runs even
  while we have stood down for QH. That REVERSES wave 23 deliberately, on the
  owner's call, and is safe for a reason wave 23 could not use: the hint no
  longer proposes its own destination, it highlights the leg the router CHOSE,
  so it cannot disagree with the line on screen.
  Earlier the same evening: an unchanged target is no longer re-posted at all
  (the 10-tick re-check was overwriting the route every six seconds with
  nothing in the log, since logNavDecision prints only on CHANGE — wave 20's
  bank-nudge shape, general all along). Forced posts keep their designed
  precedence: gravestone, active errand leg, and anything the player clicks.
  **CONFIRMED IN PLAY (late 2026-08-12, on GPS):** *"no reload required"* — the
  clear fix holds, QH's route survives a teleport. GPS's header reads
  **"Destination set by IRONSCAPE Optimal"**, so our routes are now
  distinguishable from QH's. And the router-choice follow FIRED — GPS listed
  "Use Lumbridge Home Teleport" and our overlay highlighted it — on its THIRD
  attempt, each earlier round broken by the previous fix: (a) `applyRouterChoice`
  handled teleport ITEMS only, (b) `postClear` nulled `spRoute` before its early
  return, so every stand-down threw the answer away, and (c) reporting is
  off by default and we only enabled it on OUR path posts, so on a quest step it
  was never switched on at all. Fixed by `enableRouteReporting()` — **a path
  message carrying ONLY a config is honoured and returns before it needs a
  target**, in both plugins, so it sets the flag and draws nothing.
  **THE DIAGNOSTIC SHOULD HAVE COME FIRST.** Three rounds were spent guessing
  which of four indistinguishable cases applied (no route reported / empty route
  / leg with no label / label we cannot match), all of which want opposite
  fixes. `router-choice:` now names the case, and earned its place within
  minutes by printing "first leg has no label to match" for a stepping-stone
  crossing — a transport with no button to click, where declining is correct.
  **BANK-FIRST SENT HIM TO ZANARIS FROM THE BOTTOM OF A CAVERN** (~4,900 tiles,
  behind a dramen staff he did not have; GPS planned a home teleport, a boat and
  three ladders, then said "Destination could not be reached" — which was GPS
  being RIGHT, not a defect). The band rule stops surface/underground distance
  fiction but treats ALL of underground as one place, so from the Shilo caverns
  every surface bank was filtered out and Zanaris won by being the last
  candidate standing. `nearestOf` now answers nothing beyond
  `MAX_SENSIBLE_DETOUR` (1000 tiles) — its own comment already said "nothing to
  compare against beats a confident wrong answer"; it just never acted on that
  when a bad candidate survived the filter. Verified against real positions:
  Shilo caverns rejected, Lumbridge (14), Zanaris-in-Zanaris (19) and the
  essence mine (653) kept. **The essence-mine case is the old wave 10 one and is
  STILL ALLOWED** — possibly also wrong, deliberately left because it was not
  reported and could not be tested.
  **CLOSING STRETCH — FOUR MORE, AND `::ironwrong` PAID FOR ITSELF FIRST USE.**
  **The panel scroll was the ALIGNMENT, not the timing** — the target was right
  both times it was reported (computed offline from manifest + progress), but
  "top-align on the first unticked SUB" is built for multi-action steps taller
  than the viewport, and on this ATOMIC guide the sub row sits BELOW the item
  list, so it scrolled past the step's own heading and the items it tells you
  to bring. `StepRow.scrollOffset()` returns 0 for a single-sub step. Same shape
  as the "Step N" header: panel machinery for multi-action steps, silently
  wrong on a guide that has none. **My earlier re-assert fix was a real bug but
  NOT his bug** — I should have checked WHERE it landed before assuming timing.
  **ONE WRONG ITEM IN A KIT SILENTLY BLOCKS A STEP.** "Teleport to Camelot"
  would not tick because arrival needs the step's items IN HAND and "Lit candle"
  sat red at 0. The wiki settles it — the ritual wants Excalibur, black candle,
  tinderbox, bat bones, and you LIGHT the black candle there — so no lit candle
  is carried and a black candle can never satisfy one. Removed from 274 and 335.
  **The owner diagnosed this himself**, including the mechanism.
  **A PRESCRIBED TRANSPORT BEATS THE ROUTER.** The router-follow shipped hours
  earlier overruled the step, highlighting VARROCK while the reason line read
  "prescribed spell Camelot Teleport". The router answers "what is quickest from
  here"; a step that NAMES its transport is answering "what does this step tell
  me to do", and there the step wins (wave 12 settled that for suggestions; the
  follow simply did not know).
  **AN OLD WORKAROUND OUTLIVED ITS REASON.** The Camelot stages routed to the
  STAIRCASE (p0) and satisfied at the NPC (p1) because SP could not draw between
  floors. It can now — tonight's logs show it emitting `Bottom-floor Staircase`
  and `Climb-down Ladder` itself, and the Camelot stairs ARE in transports.tsv
  (`2751 3508 0 -> 2751 3513 1`). So the split had become the fault: climb up and
  the route still pointed at the stairs you just used. Cave and one-way-interior
  splits deliberately untouched — nothing shows those can be drawn.
  **THEN THE REAL ONE: A QH COORDINATE IS WHERE AN NPC *STOOD*.** `::ironwrong`
  settled in one read what three screenshots could not — standing NEXT to Merlin,
  plane 1, route pointing 12 tiles away in another room. **Merlin wanders.** A
  stage already NAMES its npc and we already outline him, so
  `errandRouteTarget()` routes to the LIVE npc when he is in the scene and falls
  back to the recorded tile otherwise (which still SATISFIES the stage); an
  explicit routeX/routeY is never second-guessed. **Expect this class wherever a
  stage names a wandering NPC** (wave 8 already hit it with Aggie).
  **TWO FLAWS IN `::ironwrong`, BOTH FOUND BY USING IT:** it printed
  `targetFor()` as "routes to" — only ONE of the sources a route can come from,
  and on an errand step it showed a different point entirely, costing a round of
  doubting the plane; it now prints what was ACTUALLY posted. And one-file-per-
  step OVERWROTE: he pressed it twice on purpose (where the route led, then
  beside the NPC) and the second erased the first, destroying the comparison.
  **CAUTION FOR THE NEXT SESSION:** three separate explanations were offered
  for route interference in one evening (10-tick re-posting, both pathers
  enabled at once, and the clear-wipes-everything bug). Only the last was
  proven end to end from a report. If interference continues, that ordering is
  wrong — get `::ironwrong` at the moment it happens rather than another
  theory. Same lesson as the highlight: **when the failure has several
  indistinguishable causes, ship the diagnostic before the fix.**
  **OPEN:** everything from the scroll fix onward is UNPLAYED (equipment-tab
  signpost, kitten gate, Chronicle pins, note items, quest-NPC gating, both
  route-interference fixes, the GPS switch itself); 282 and 316 still unplayed;
  `::ironwrong` still never used; hub pin 57 behind.

- SESSION WAVE 25 (2026-08-11 evening, long live play-test 268 -> 270; main at
  `2ecd06a`, ~14 commits, PUSHED; hub pin `bb3e11e`, **44** behind — counted):
  **THE THEME IS READING THE EVIDENCE THAT IS ALREADY IN FRONT OF ME.** Three
  separate faults today were visible in the owner's first screenshot and I
  re-read them as something else, costing him five rebuilds.
  **THE CHECKLIST TRUNCATION WAS A BROKEN STRING, NOT THE LAYOUT.** Task lines
  read "A", "Sell", "U", "Steal a cake from the Ea". I fixed the label to a
  JEditorPane (right, wave 22's lesson), then the pane width (right), then
  narrowed it further (unnecessary) — three rounds on a layout that had been
  correct after the first. The tell was in the data all along: **A|sk, Ea|st,
  pet|s, U|se, Wilderne|ss, ru|sty, fi|shing, Two-pint|s** — every label cut at
  its first lowercase s. `split("(?<=\.)\s")` had reached the file as
  `split("(?<=.)s")`, the escapes eaten by generating that method through a JS
  script. **FOURTH casualty of that habit in one day** after a literal NUL byte
  and two runs of literal newlines. Rule: write Java to a FILE and splice it,
  never hand-escape one language through another. Corollary: "A" and "Sell" are
  whole FIRST LINES — wrapping and clipping cannot produce those, only the data
  can.
  **THE CHECKLIST VANISHED when its step completed**, and the cause was wider:
  it read both the LIST and the STATE from the per-tick cache, which is only
  written for the step being guided. So every other chain step rendered blank
  too. List now comes from the annotation (always present), state from the
  cache, absent state = all DONE on a finished step / all TODO on an unreached
  one. `checklistLabel` moved into ErrandProgress so panel and plugin build the
  same key from one copy.
  **ARDOUGNE DIARY CONFIRMED IN PLAY** — routes to the unfinished tasks only,
  advances as each completes, chain-complete ticked the step. Varrock easy (14
  tasks) seeded the same way, unplayed. Death runes now name the **Civilian**
  via item_sources: the step names nobody, so the nearest-NPC fallback had hung
  the death-rune icon on RATS.
  **CAT STEPS, and the wiki lookup earned its keep twice**: 200 death runes is
  not the base rate — it is 100, and 200 only WITH the easy Ardougne diary,
  which is exactly why the guide puts that step immediately before. And the
  trade needs a GROWN cat: a kitten becomes one after 3 hours following you and
  until then needs feeding and attention or it runs away.
  **THE PURCHASE FILTER WAS TOO BROAD**: audit-teleport-items skipped any step
  containing "buy", so "Chronicle tele and buy a new KITTEN" lost its Chronicle
  (owner). The item must appear AFTER the verb to count as bought.
  **`check-all` BUILT — one command, one verdict** — and it earned its place by
  being WRONG on its first outing twice. It reported "ok" over a deliberately
  broken item name (it read 1028 out of "1 of 1028 annotation items
  unresolvable"), found only because the fault was INJECTED rather than
  assumed. Then it reported "unit tests FAILED" when gradle had never started —
  wrong wrapper for Windows, then Node refusing to spawn a .bat, then an
  unquoted path with a space. A false alarm is not a safe failure; it is how a
  check gets ignored. What made each round diagnosable was printing the error
  when nothing matches the expected pattern.
  **DX-4 SHIPPED: `::ironwrong`** writes position, step, sub, quest state, where
  the route pointed, player position, whether QH is installed, and the last 25
  nav/hint decisions to one file. **DX-2 and DX-3 shipped in wave 24.**
  **THREE HAND-TICK STEPS AUTOMATED**, and the measurement mattered more than
  the fixes: 86 hand-tick steps, of which **64 are genuinely advice** ("Use
  Authenticator", "Bank", "Sell your silk"). New `Requirement.quest` ("this
  quest is finished") for the 13-quest step; all 24 easy/medium diary tier
  flags for another; Karamja is the exception with no COMPLETE flag, only a
  count (10 and 19). Four quest names differ from the guide's wording beyond
  case — **"Rat catchers" is one word in game (Ratcatchers)** — and an
  unresolved name now fails CLOSED and warns. 86 -> 83.
  **`audit-manual-steps` FIRST REPORTED 130 AGAINST PREFLIGHT'S 86** because it
  re-derived the population and did not know a step can tick by ARRIVING. It
  now shells out to `preflight --manual-list`: one tool owns "can anything tick
  this?".
  **`check-client` CAN NOW TELL A DEAD CLIENT'S LAST LINE FROM A LIVE ONE'S** —
  when a recent line is the only evidence and no dev-client PROCESS exists, it
  re-reads two seconds later; a live client keeps writing. Four launches had
  each waited out the 120s window.
  **OPEN — the hint fired for VARROCK teleport toward a WEST ARDOUGNE target
  while the player was ~350 tiles away** (widget 14286874, confirmed against
  the API constants). Varrock lands ~750 from there, so the 60%-and-75-tiles
  rule should have rejected it. Reason line now prints the leg distance AND the
  bar it had to beat. **SUSPICION, not yet checked:** the target is INSIDE West
  Ardougne, reachable only via the Plague City route, and the bundled walked-
  distance table floods through UNGATED transports — if it walks through that
  wall, every distance to that corner is wrong and the hint is a symptom.
  Also open: 282 unplayed; Varrock diary chain unplayed; DX-5 (index every
  teleport item — diary cloaks, jewellery, tablets) and DX-6 (stop
  second-guessing SP's route) added to BACKLOG.md.

- SESSION WAVE 24 (2026-08-11, live play-test 265 -> 269 plus a long desk
  stretch; ~12 commits, PUSHED; hub pin `bb3e11e`, now **25** behind — counted):
  **DX-1 PAID FOR ITSELF WITHIN THE HOUR.** Every data fix this session landed
  via `::ironreload` with the client running ("reloaded from data folder",
  confirmed). USE IT: edit the repo file, tell him to reload. Only code needs a
  restart now, and the session's restarts were all for code.
  **DX-2 SHIPPED:** preflight speaks plainly by DEFAULT (`--detail` restores the
  old output). It always computed the right answers and printed them in a
  register its only reader cannot use, so it helped only when Claude remembered
  to run it and translate.
  **DX-3 SHIPPED AND CLOSED OUT:** `review-quest-kits.mjs` cross-checks every
  quest kit against the wiki's items list. 101 quests, 117 rows. `--auto`
  settles FLAT wiki statements ("Tinderbox (obtainable during quest)") and hands
  over only HEDGED ones; the owner kept all 34 of those as requirements — red
  and unnecessary is a question he can ask, grey and necessary is an item he
  turns up without. **39 settled, 0 outstanding, 86 items greyed.**
  **THREE WRONG GRANTS, ALL FOUND BY AUDITING WHAT I HAD JUST APPLIED**, all in
  the harmful direction: Prince Ali `coins` (the line was a PRICE — "bought from
  Ned for 18 coins"), Watchtower `rope` ("2nd rope obtainable"), Sheep Shearer
  `shears` (the phrase attached to the WOOL). Money is now never auto-settled,
  and any purchase or ordinal wording goes to the owner. **A FALSE alarm too:**
  Daddy's Home's `saw` flagged only because the audit matched "Sawmill" on
  another line — the grant is correct.
  **THE WIKI ANNOTATES ITS ITEMS LIST UNEVENLY** and the missing half is in the
  FOOTNOTES: Cook's Assistant marks the egg and leaves milk and flour bare while
  its own footnotes say "if obtaining the milk during the quest". Two footnote
  shapes, handled oppositely — "X (if obtaining the PARENT…)" grants the PARENT
  (X is a bring item), "X (obtainable during…)" grants X. Owner confirmed all
  three Cook's items.
  **TELEPORT ITEMS (owner report, standing on 264):** "Chronicle tele and start
  Dragon slayer" never tells you to bring a Chronicle, and by the time you read
  it you have left the bank. `audit-teleport-items.mjs`: 18 steps guide-wide.
  New **`ItemNeed.bringAhead`** puts the item on the step BEFORE with its icon
  and 0/1, tagged "(for next step)" — a sentence in the note was the first cut
  and he asked for the icon immediately, because prose under a list of item rows
  does not read as one of them. ALWAYS written with `optional` so it cannot gate
  the step it sits on. **Law runes ARE required** (owner: no staff supplies them
  — which is exactly the asymmetry `castable()` already models). 0 outstanding.
  The tool was NOT idempotent at first: adding the item stopped the step being a
  finding, so the warning on the step before could never be written on a later
  run. `needs` and `missing` are separate now.
  **DIARIES ARE SEEDABLE AND THE ATLAS WAS ALREADY THERE** (owner: "we can read
  diary progress and QH has the steps, why is this a hand tick?"). Wave 8's
  `diary-tasks.json` had all 420 tasks and their bits; what was missing was
  (a) chains could only test a var THRESHOLD and (b) no coordinates. New
  **`Errand.bit`** (a tier is packed into one varp — `>=` would let any other
  task satisfy the stage, the barcrawl trap); coordinates from QH's own diary
  helpers. Seeded **Ardougne easy (10 tasks)** and **Varrock easy (14)**, each
  ordered to cluster the walking and ending at the reward NPC.
  **THE CASCADE WAS THE BUG, NOT THE BIT TEST.** In play the Ardougne chain
  skipped all ten and routed to the cloak; his DIARY TAB settled it — three were
  open (Trawler, combat camp, Tindel) and all three read done. A satisfied stage
  marks every stage BEFORE it done, which is right for an item chain and for a
  threshold (ordinal) but **one bit implies nothing about another**: Aleck's in
  Yanille (bit 11, done) cascaded over three unrelated errands. Stated as a rule
  so the next diary cannot inherit it; hand-ins already had the same exemption.
  `ErrandProgressTest.bitGatedStagesAreIndependent` VERIFIED TO FAIL with the fix
  backed out — the seven finished tasks look satisfied either way, so only the
  unfinished ones can prove anything.
  **16 OF 18 DIARY STEPS ALREADY TICK THEMSELVES** — the gap was never detection,
  it was guidance. Remaining unguided multi-task step: **522 Fremmy hard**. 493
  "Do all easy and medium diaries" is deliberately NOT chained (it spans ~20
  tiers; a chain of hundreds of stages is unusable).
  **TWO CHECKS WERE STRICTER THAN THE PLUGIN**, same day: `audit-errand-chains`
  mirrored `varGated` from before `bit` existed and called every diary stage a
  proximity waypoint (the Varrock sawmill stages sit 7 tiles apart because the
  tree and the plank really are next to each other); and an ad-hoc name check
  flagged `digsite pendant` when `canonical()` already strips charge suffixes.
  **OPEN:** 282 still unplayed; the Ardougne chain fix and the Varrock chain both
  UNTESTED in play; 522 unchained; hub pin 25 behind; ⌖ harvest not run this
  session.

- SESSION WAVE 23 (2026-08-10 evening, live play-test 262 -> 265; main at
  `9f79d9d`, 6 commits, PUSHED; hub pin `bb3e11e`, now **13** behind — counted,
  not carried): **the owner corrected a premise this file had wrong from the
  first line.** He has NO coding background at all; the "new to Java but knows
  React/Supabase and Apps Script" note at the top of this file was wrong and had
  shaped months of over-technical reports. He then corrected my over-swing
  ("decide silently") in the same session: he WANTS to learn what development
  involves and wants a heads-up on build decisions so he can track what has been
  added. **The failure was density, not consultation.** Both settings are now in
  the Developer context section and in [[owner-is-not-a-developer]].
  **261 AND 263 BOTH CONFIRMED IN PLAY.** 261 (the wave 22 skill-gate fix) ticked
  at 22:19:01, one second after the QH stand-down lifted — and the PROOF is the
  timing, not the log line, which reads the lumped "goal satisfied
  (items/quest/level/arrival)" and cannot say which goal did it. Runecraft was
  already 10 all evening, so a skill gate would have ticked it hours earlier.
  263 needed no such reasoning: `auto-completed sub 58ad4841dd:0 (quest
  checkpoint (varbit/varp/region))`, off `varp 68` reaching 14 exactly as wave 18
  predicted. **282 is the last unplayed one.** I proposed making the lumped label
  name its goal, then DROPPED it on checking: 263/282 complete via the checkpoint
  path, which already logs a distinct line, so the work would have improved
  nothing we were about to test.
  **THE TELEPORT HINT NEVER ASKED WHETHER NAV HAD STOOD DOWN** (owner: "our
  navigation is still interrupting... we have a lumbridge tp overlay while im on
  the quest helper"). The log was conclusive and the shape is wave 12's exactly:
  nav logged `standing down` at 21:44:25 and the hint went on offering a home
  teleport at 21:47, 21:48, 21:49 and 21:51, because it computes its own
  destination via `targetFor()` and had no idea the router had abandoned it. Two
  parts answering the same question separately, drifting. Gated on the same
  condition, placed AFTER the gravestone and errand branches so their designed
  precedence over QH survives. Deliberately silent rather than aiming at the
  bank when a kit is banked — a hint pointing anywhere while QH guides IS the
  complaint. CONFIRMED IN PLAY: `teleport-hint: none - Quest Helper owns this
  step's route`.
  **THE BANK NUDGE'S "ONCE" WAS KEYED ON THE WRONG THING.** Wave 20 made it one
  suggestion per (step, bank); the branch picks the NEAREST bank, so walking far
  enough changed the key and restarted the count — four banks in two minutes
  (Varrock east, Al Kharid, Falador, one underground). **Whenever something is
  meant to happen once, the whole behaviour hinges on ONCE PER WHAT**, and
  getting it wrong makes the limit quietly do nothing while looking fixed for a
  whole session. Keyed by step alone now.
  **CASTABLE() NEVER CHECKED ELEMENTAL RUNES** (owner, off a fired hint): it
  tested Magic level, LAW runes and quest unlocks only, so a Varrock teleport was
  offered to a player holding one law rune and no air or fire. The elements were
  skipped because a staff makes a rune count meaningless — the cautious way not
  to hide the hint from someone wielding one. **Model the staff, do not stop
  asking**: each spell now carries its elemental cost and `ELEMENT_STAVES` pays
  it, combination staves counting for BOTH elements (a mud battlestaff is never
  called a water staff, which is why it is a table and not a name rule). Owner
  also set `MIN_TILES_SAVED = 75` on top of the 60% test — 60% of a 150-tile jog
  is a 90-tile jog, not worth the runes. Floor binds short journeys, percentage
  long ones. NEITHER PLAY-TESTED.
  **DIG SITE KIT** (owner report, wiki-confirmed): opal and charcoal ARE
  obtainable in quest — and the wiki's items list UNDERSELLS the opal as a "small
  chance while panning" where the walkthrough says plainly to pan until you get
  one for the level 3 test. **Read the walkthrough, not just the items list.**
  Found a third next door: the trowel is handed over with the Level 1
  certificate and was the only one of four quest-issued tools not flagged, so it
  sat red beside three that did not. Caught before shipping: **ItemNeed has no
  `note` field**, so the per-item guidance I first wrote would have been dropped
  by Gson in silence, with the panel looking identical. Moved to the step note.
  **ITEM-ID REVIEW: ALL 88 LEFT AS NAMES** (owner's call, and correct). The first
  paste pinned 74 of them, which the tool's own header names as a regression
  (`fire rune` is its worked example). Evidence: `tinderbox` is one of six
  SUBSTITUTES keys and appears in **29 annotation entries** — pinning it would
  have killed the bruma-torch substitute across all of them. New `--leave-all`
  flag; verified both ways (re-run reports 0 to review, 88 settled). Two rows the
  tool calls genuine pins are settled as leave and worth knowing: `ugthanki dung`
  (4601 vs 4602, and The Feud makes the poisoned one FROM the plain one, so you
  can hold both) and `coins` (617 FAKE_COINS).
  **DX-1 SHIPPED AND CONFIRMED IN PLAY — data fixes no longer need a restart.**
  Three restarts in one evening, one of them for the Dig Site data alone, is what
  motivated it. `DataFiles` routes every data read through an optional folder
  (config `dataFolder`, empty by default so a real install is untouched); point
  it at a checkout's `resources/com/ironscape` and those files win, keeping the
  REPO as the single source rather than a copy that drifts. Lookup is by file
  name, searched recursively, because resources sit in per-topic subfolders; an
  unreadable override falls back to the bundled copy so a half-written file
  cannot blank the data. `::ironreload` re-reads and calls `loadGuideState()`,
  which already rebuilds everything derived. Owner set the folder and got
  "reloaded guide data from data folder". **USE THIS FOR EVERY DATA FIX FROM NOW
  ON** — edit the repo file, tell him to reload.
  DX-2..DX-4 agreed and in BACKLOG.md: a plain-language pre-session briefing
  (preflight exists but prints developer output nobody runs), a bulk
  granted-vs-bring review page for 100+ quest kits (tonight's trowel is one
  instance of that class), and a panel "something is wrong here" button that
  captures state so his reports arrive with evidence.
  **HUB:** PR 14207 open, `build` PASSES, the red X is its own
  "Requires maintainer review." No reviewer response since 2026-07-28; owner
  replied 07-29, nudged 08-03. Queue measured: 197 open, **56 older than ours**,
  but **140 merged in the last week** — moving fast and NOT in order. Advised
  against a second nudge this week (Discord #development is the better lever);
  owner agreed to hold.
  **PROCESS:** every build ran in a throwaway `git worktree` while the client was
  live — no repeat of wave 17. `check-client` FALSE-BLOCKED TWICE, both times on
  the just-exited client's dying line inside the 120s freshness window (78s, 76s);
  waited it out rather than overriding, since learning to override it is exactly
  how wave 17 happened. If it costs a third session a minute a restart, teach it
  to tell a dead client's last line from a live one's.
  **OPEN:** 282 unplayed; the teleport castability + 75-tile floor unproven in
  play; DX-2..DX-4 unstarted; hub pin 13 behind.

- SESSION WAVE 22 (2026-08-10, long live play-test 254 -> 262; main at
  `907aef7`, ~18 commits, PUSHED; **hub pin BUMPED `f634cbf` -> `bb3e11e`**,
  22 commits, now 6 behind again): **step 258 is CONFIRMED IN PLAY** — the
  thing that had been shipping untested since wave 19. Log is conclusive:
  `checkpoint 7ca10e694f:0: varbit 532=6 (need 6)` then `auto-completed …
  (quest checkpoint)`, no route to Goblin Village, stand-down fired. 263
  and 282 are the same mechanism and STILL UNPLAYED.
  Pin bump verified the way a reviewer does — clean build from a FRESH
  CLONE at that sha, not the incremental tree. `build` passed; the red X
  reads "Requires maintainer review." from the check's own title.
  **THE GUIDE'S OWN NOTE HAD THE ANSWER ALL ALONG** (wave 21's finding,
  now played): Goblin Diplomacy really is required before The Lost Tribe —
  wiki `requirements` lists it AND Rune Mysteries, and Rune Mysteries
  finishes at 192, so Goblin Diplomacy at 281 is the only out-of-order
  one. Resolve these notes by NAME: the note calls our 281 "278".
  Shipped for it: right-click **"Start from here"**, `obsolete` and
  `prerequisiteQuest` annotations, a **GO TO STEP** button and its inverse
  **BACK TO STEP** (derived by INVERTING prerequisiteQuest, not remembered
  from the click — session state would die on restart and offer nothing to
  someone arriving by playing forwards), and an **out-of-order warning**:
  any step whose own quest has an EARLIER unfinished leg says so and links
  back. Computed from quest tags + progress, so it covers every quest the
  guide splits up (The Lost Tribe runs 256, 258, 282, 284) and re-points
  itself as you go.
  **STEP NUMBERS** now render (global index, green + colon, greys when
  done) — the "Step N" header they mirror had never once drawn on this
  guide, because it is built for multi-action steps and Oziris is atomic.
  **WIDTH: a JLabel cannot be constrained by an html body width.** It
  reports the UNWRAPPED width and never caps its own maximum size
  (Component#getMaximumSize returns Short.MAX_VALUE), so a Y_AXIS
  BoxLayout hands it whatever it asks — a label given 148px measured
  228px, and "fixing" it that way made the card WIDER (228 -> 255).
  `htmlPane` has always done it properly (explicit setSize, preferred AND
  maximum pinned); use it, do not invent a third mechanism. Fixed the
  Quest Helper tip line too, which had been overflowing since long before
  today (step 278's self-check line proves it).
  **TWO TOOLS ANSWERED A DIFFERENT QUESTION THAN THEY PRINTED** (wave 21
  theme, again): `check-client` timed the log FILE and reported it as our
  plugin's age — a worldhopper ping kept it warm while our newest line was
  47 min old. Times the last matching LINE now, and immediately blocked a
  build correctly. `preflight` started its window at `position` while
  findWindow starts at `position + 1`, so every report opened with an
  already-finished step; fixing that then exposed a SECOND bug — the row
  labels came from the old start, silently renaming every row by one,
  caught by checking a printed id against the guide.
  **PRIEST ROBES — TWO BUGS, AND MY FIRST HYPOTHESIS WAS WRONG BOTH
  TIMES.** (1) The step ticked after one gown half because
  `purchaseListAcquired` had ARMED ON AN EMPTY LIST long ago: the flag
  persists, so seeding items later could never re-engage the gate. Empty
  lists no longer arm, and the list's CONTENTS are part of the key so
  EDITING one re-gates. (2) I then blamed name-collapse, wrote a test, and
  the test DISPROVED it — because the premise was wrong in the other
  direction: **"Priest gown (top)" is a WIKI PAGE TITLE, not an item
  name.** Both halves are called exactly "Priest gown" (426 PRIEST_GOWN,
  428 PRIEST_ROBE), counts are keyed by in-game name and SUMMED, and the
  alias chain drops trailing parentheticals — so two entries could only
  ever report the pair's total. Second time a wiki title stood in for an
  item name (wave 19's "black wizards hat"): **treat page titles as
  suspect.** Fix: new **`ItemNeed.id`** counts one exact item, with `name`
  free as a label; id-keyed tallies sit beside the name ones (in memory
  only — the persisted bank snapshot is names, so a banked half is
  invisible until the bank is opened once). Because the label is
  deliberately unlike any real name, NOTHING could verify these — so
  `audit-goals` section 5 now checks each id against RuneLite's id->name
  cache, verified in BOTH directions (pointed one at 1038 Red partyhat and
  one at a nonexistent id; it named both).
  **`tools/review-item-ids.mjs`** — 88 names shared by more than one real
  item, with each candidate's sprite AND gameval constant, since the
  constant is the giveaway (`…_WORN`, `ROGUETRADER_…` = variant). A shared
  name is usually FINE and pinning an id there would break substitutes and
  family sums; only pin non-interchangeable pairs. **The naive count was
  279 — nearly all noted twins, which canonicalize() already folds.**
  **48 SKILL REQUIREMENTS HAD NEVER RENDERED A BADGE**, and it took two
  fixes: the badge row asks by SUB id while a bare-step `requires` is
  keyed by STEP id, AND `addItemBadge` renders the item list then RETURNS,
  so any step listing items skipped the badge entirely. Completion always
  read them by step id, so the data was right and only the panel was
  silent. I reported the first fix as done before it was visible.
  **GRIND-THEN-QUEST STEPS** (3 guide-wide): new `trainAt` routes to the
  training spot while a skill gate is unmet, plus **`trainWith`** naming
  what the training CONSUMES — because it is a LOOP, and a pin describes
  only half of it (owner: "it should pull us to the bank to grab more
  essence"). Essence in the bag = altar, empty = nearest bank, both ending
  at the level. Bank-first stands down on these steps: the quest kit
  belongs to the job AFTER the grind and it was seizing a route it never
  released (once per step+bank since wave 20). Chosen over an errand chain
  deliberately — a chain on a quest step re-posts over QH every 10 ticks.
  **AND THE SKILL GATE NO LONGER COMPLETES THE STEP**: an annotation
  `requires` is a COMPLETION condition, so hitting Runecraft 10 ticked off
  a step whose quest was untouched. A skill-only list on a quest step now
  defers to the quest goal. Var/region/equipped checkpoints untouched —
  those mark where a step STOPS, the opposite case.
  **SHORTEST PATH MISATTRIBUTION, 5th+ TIME, and the general answer was
  not enough**: "Pick up: 1 Air rune…" is SP's, proven from the OWNER'S
  CONFIG rather than argued — `includeBankPath=true` +
  `showBankPickupInfo=true`, and `drawTransports=false` disproved my own
  first theory. Read the config first next time.
  **OPEN:** 263 Biohazard and 282 unplayed; 261 needs Temple of the Eye
  FINISHED to confirm it ticks off the quest; the 88-row item-id review
  page is unanswered; `trainAt` pins wanted for 485 (Perilous Moons, 48
  Slayer) and 509 (Lunar Diplomacy, 70 Mining); hub pin 6 behind.

- SESSION WAVE 21 (2026-08-09 late/2026-08-10, desk run + a brief look in
  game; main at `ca9328e`, 4 commits, PUSHED; hub pin `f634cbf`, gap **5**
  — counted at the end, and only ONE commit touches the plugin; the rest
  are tools and this record): **step 258 was NEVER
  TESTABLE, and the guide had said so all along in a note nobody had
  read.** Its own "Modern alternative" says Goblin Diplomacy is now
  required BEFORE The Lost Tribe, which the guide does 25 steps LATER at
  281 — so the route as written stalls at a quest you cannot start, and
  `varbit 532 = 0` was correct, not a defect. Confirmed against the wiki
  afterwards (`Quest details|requirements`): Goblin Diplomacy AND Rune
  Mysteries; Rune Mysteries finishes at 192, so Goblin Diplomacy is the
  only out-of-order one. The wiki's `startmap = 3210,3222,plane:1` also
  independently confirms our `the lost tribe` pin.
  **258 ITSELF DESK-VERIFIED SOUND** (all three watch items): the quest
  tag resolves through stepQuest's annotation branch, the checkpoint is
  `varbit 532 >= 6` (state 6 IS the Goblin Village), and the stopping
  clause strips to leave 📍Varrock. A fourth risk found and already
  closed: the stripped text still contains a quest NAME, and `the lost
  tribe` is a quest-type place at the Lumbridge giver — `firstPlaceIn`
  only routes a quest place after start/begin/do/complete/finish, and
  "Continue" is deliberately not among them. So the failure would have
  been Lumbridge, not Goblin Village.
  **THE NOTES WERE MEASURED BEFORE ANYTHING WAS BUILT.** 14 "Modern
  alternative" notes: exactly **1** asks for a reorder, **1** marks a step
  dead (431 Kourend favour, removed Jan 2024), the other 12 are training
  advice. A note-parsing reorder engine would have been built for N=1 —
  and their step references do not map onto our index by any constant
  offset (256's note says "278" for our 281; 533's says "step 1.1.145a",
  a different scheme entirely). **Resolve these by NAME, never by number.**
  Shipped instead: right-click **"Start from here"** (sets position to
  index-1, since findWindow starts at position+1; ticks nothing, and
  steps behind position already drop out of the window, which is what
  makes it reversible), plus two annotation fields — `obsolete` (a
  reason string) and `prerequisiteQuest`. Both INFORMATIONAL: obsolete
  deliberately does not auto-skip (it would move the frontier on evidence
  the plugin cannot check, and a wrong auto-skip is invisible), and
  prerequisiteQuest never gates completion. Their chips do NOT reuse
  `chip()` — that is a navigation affordance with a hand cursor and a
  "show the route" tooltip, and a warning has no destination.
  **STEP NUMBERS: the panel had a "Step N" header that has never once
  rendered on this guide** — it is built for multi-action steps and Oziris
  is atomic, so every card takes the headerless path. "Hard to know where
  I'm up to" was the panel simply never showing one. Numbered with the
  GLOBAL index (what position stores and preflight prints), not the
  header's section-relative one, so a step named in conversation is the
  step on screen. Green + colon, greys when done (owner's restyle).
  Prefixed INTO the html: a fourth column takes width from the text, which
  is what pushes ⌖/Go off the edge — and it goes inside `<body>`, since
  runsHtml returns a whole document and Swing drops anything before it.
  **TWO TOOLS WERE ANSWERING A DIFFERENT QUESTION THAN THEY PRINTED.**
  `check-client` timed the log FILE and reported that as the age of OUR
  output — any plugin keeps the file warm (a worldhopper ping wrote at
  23:29 while our newest line was 22:45), so it announced "117s ago" for
  a line 47 minutes old and blocked. Both directions follow: a stale line
  plus the everyday RuneLite is a false BLOCK, one stack trace pushing our
  lines out of a fixed 20KB window is a false CLEAR. It now times the last
  matching LINE. It immediately proved itself both ways — cleared a dead
  client, then correctly blocked on a genuinely live one (119s).
  `preflight` decided "quest step" from the DETECTOR PATH alone, so it
  could not see a step whose quest comes from an annotation tag — which is
  exactly what the tag exists for. **All 8 tagged steps were invisible**,
  including the one under test. Now mirrors stepQuest's real chain;
  144 -> 152 quest steps, +8, which is the 8 tags and nothing else.
  Recounted while there: **87** hand-tick and **39** no-route guide-wide
  (CLAUDE.md said 89).
  **HARVEST: correctly EMPTY.** 23 unbundled local targets = 22 stale
  BRUHsailer keys (multi-sub ids; Oziris is atomic so live keys end `:0`)
  + 1 live, which is the Brimstail interior already DECLINED with its
  reason in `decisions-declined.json`. The record did its job and stopped
  the re-ask.
  **OPEN:** 258/263/282 still UNPLAYED and now blocked behind Goblin
  Diplomacy at 281 — the natural next run is "Start from here" on 281,
  then back to 256. Lady of the Lake outline still unconfirmed (data side
  verified: the duplicate alias is gone, audit-place-spans clean). Audits
  0/0/0/0 and 6 known NOGOAL buy subs.

- SESSION WAVE 20 (2026-08-09 late, short desk run; main at `d684bb3`,
  2 commits, PUSHED; hub pin still `3638c2f`): **both review pages
  answered in one pass, and four of the five pin verdicts were no-ops.**
  Item names: all 5 approved and shipped as COLLOQUIAL entries (b gloves,
  dramen branches, pack of normal compost, translation notes, range
  void). `range void` counts the **helm only** — the step buys the whole
  ranged set but the top and robe share names with the melee/mage sets,
  so the badge reads 1/1 off the piece that identifies the set. Flagged
  to the owner; not yet re-decided.
  **THE PAGE ASKED FOUR SETTLED QUESTIONS.** Its quest-start rows read
  `build/quest-start-review.json`, written 14 minutes BEFORE wave 19's own
  "fix four pins" commit — so four approvals landed on pins that already
  held the proposed coordinates. Fresh audit: **1 row, not 5.** Only Tai
  Bwo Wannai Trio was live (re-pinned to QH's 2791,3019, overriding wave
  19's hold — owner's call). Exactly what `decisions-declined.json` exists
  to prevent, through the back door: **a stale artifact re-asks a settled
  question as effectively as no record does.** Every other input on that
  page reads live from resources, so this was the only source that could
  drift; it now RUNS the audit instead of trusting `build/`. Regenerated:
  0 rows.
  **AUDIT-GOALS SECTION 1 CRIED WOLF ON BOTH NAMES JUST APPROVED** —
  it resolved against the prices mapping, which cannot see UNTRADEABLES,
  and the workaround was hand-adding each real one to `SPECIAL`, where
  "this is a substitute family" and "trust me, this item exists" read
  identically. Section 4 moved to RuneLite's 21k name cache in wave 19;
  section 1 does now too. All four sections clean.
  `check-client` counted ONE client as two (the gradlew launcher's
  classpath names the project) — verdict was always right, only the count
  was wrong, but "two clients" reads exactly like the wave 11 accident.
  Fix confirmed on a real launch.
  **THE HUB-PIN GAP IN THESE NOTES WAS WRONG BY 5x**: recorded as "10
  behind" (wave 18) and repeated as "~16", actually **84** — pin
  `3638c2f` is 2026-08-08 12:05, mid wave 12. Same class as wave 17's
  inflated "132 unroutable" that got written in here and repeated to the
  owner. **Count it, do not carry it forward.**
  **THEN A LIVE PLAY-TEST (position 251 -> 254), three fixes, all
  CONFIRMED IN PLAY.**
  **(1) A STEP THAT COULD NOT COMPLETE BY ANY ROUTE.** "decant them until
  you have like 6 full pots" showed a green 6/6 over a checkbox nothing
  could tick: the detector cannot name "6 full pots", so the count lives
  in an annotation, and annotation items are DISPLAY-ONLY.
  `completion-paths` said `none`. The obvious rule (no path + numbered
  annotation items held) was MEASURED AND REJECTED — 25 steps, ~2 right,
  because annotation items are what you BRING: a spade would tick all six
  "dig up the clue" steps and the barcrawl card all ten bars. **Wave 13's
  finding arriving from the opposite direction.** What separates them is
  the SENTENCE, so possession completion matches "until you have" /
  "make sure you have": **2 steps guide-wide.** Two guards, and
  `PossessionObjectiveTest` records that they are NOT equally
  load-bearing — parenthetical is PROVEN (disable it and the Brimstail
  reminder, "(scrying orb 2/3, make sure you have it with you)",
  qualifies on text alone), checkpoint is PRECAUTIONARY (disable it and
  nothing fails today). Verified by disabling each, not by assuming.
  Ticked in play, frontier advanced.
  **The dump that HID it:** completion-paths.tsv read only the DETECTOR,
  so annotation-driven completion was invisible — 164 subs called
  unreachable and the FIRST one examined completes fine off a region
  checkpoint. Consumers read "none" as "only a human can tick this", so
  an undercount here is an overcount of work everywhere downstream. Now
  emits `checkpoint` and `possession`; **164 -> 132**.
  **(2) NAV HIJACKED QUEST HELPER EVERY SIX SECONDS** (owner, mid-
  Observatory: "it goes to the correct spot briefly then something takes
  it over again"). The log named the tile — Castle Wars bank — **once**,
  at 22:27:22, and never again, which IS the tell: `logNavDecision` only
  prints on CHANGE, so the decision never changed while the POST kept
  happening. Loop: QH owns guidance -> kit banked -> bank-first posts and
  RETURNS before the stand-down -> the 10-tick re-check recomputes the
  same bank -> posts again. Wave 19 added that re-check for a real reason
  (withdrawing fires no event, so the route sat on the bank forever and
  the handoff never arrived): it must keep RUNNING, it must not keep
  POSTING. Now **one suggestion per (step, bank)** — the re-check still
  notices the kit leaving and still falls through to the stand-down, and
  a player who would rather crack on can walk away from it. Non-quest
  bank routing untouched (nothing else draws there).
  **AUDITED THE WHOLE QUESTION afterwards rather than declaring it
  fixed:** of every SP post while QH owns guidance, only ERRAND CHAINS
  still re-post (every 10 ticks, deliberately outranking QH since waves
  4/8 — a chain guides legs QH's step does not) and the gravestone.
  **14 chains exist; 4 sit on quest-tagged steps** (TGV pebble, RFD,
  Merlin's Crystal, Holy Grail) where they could produce the same
  symptom. Left alone on purpose: bank-first was a suggestion you could
  not decline, an errand is us actively leading. Same one-shot treatment
  applies if one ever misbehaves.
  **(3) MID-QUEST LOGIN PROMPT** (owner's idea, and it exposed a
  structural gap): STOP and START both fire on an EDGE, and logging in
  has no edge — guidance was already QH's when you logged out, so the one
  moment you have forgotten where you were is the one moment nothing
  fires. New `Kind.RESUME` off the login-resume hook. Narrow: real login,
  once, frontier quest IN_PROGRESS only, skipped if the stand-down
  already announced that step, QH-installed only. Does NOT say "our route
  stops here" — the Observatory kit was banked and bank-first was
  actively routing him, so START's wording would have been false.
  **CHECK-CLIENT FALSE-BLOCKED TWICE IN TEN MINUTES, same root — evidence
  that cannot tell "our plugin is running" from "a client exists".**
  (a) `gradlew test` logs to the SAME client.log as `[Test worker]`, so
  the corroboration read MY OWN test output back as proof of a live
  client — circular; only `[Client]` lines count now. (b) The installed
  RuneLite.exe was blocking ON ITS OWN with the log demoted to a
  footnote, so the owner's everyday client blocked every build and a
  wait-for-clear loop span forever, launching nothing. The header already
  had the right rule and the code did not implement it: names cannot
  answer it, only the log can. RuneLite.exe is now a SUSPECT, convicted
  only by fresh `com.ironscape [Client]` output; the dev client still
  blocks on sight. **A false block is not a safe failure — it is how you
  learn to override the check, which is exactly how wave 17 ran two
  builds under a live client.**
  **HUB PIN BUMPED (owner's call): `3638c2f` -> `f634cbf`, 89 commits.**
  Verified the way a reviewer will: clean `gradlew clean build` from a
  FRESH checkout of that exact sha, not the incremental working tree.
  Hub `build` check PASSED (52s). The red X is
  **"Requires maintainer review."** — fails by design on every new-plugin
  PR, read from the check's own title rather than assumed (wave 17's
  lesson); `upload` skipping is normal. PR 14207 open, mergeable, one
  file.
  **STILL UNPLAYED, and it shipped in the pinned build:** the 8
  annotation quest tags and step 258's stopping-point fix. Position 254
  sits right in front of 258 ("Continue Lost tribe until you need to go
  to the goblin village"), then 263 Biohazard and 282. Owner's call to
  pin ahead of that, explicitly on the grounds that the pin is a
  two-minute change if 258 misbehaves. The Lady of the Lake outline is
  also still unconfirmed.

- SESSION WAVE 19 (2026-08-09 late, LIVE play-test then a long desk run;
  main at `354056a`, 5 commits, PUSHED; hub pin still `3638c2f`):
  **the theme was audits that confirmed the wrong answer.**
  **BANK -> HANDOFF NEVER COMPLETED.** The bank-first branch INSIDE the
  quest-owns-guidance path never set `navRoutedToBank`, so wave 18's
  10-tick re-check could not run on a quest step: withdrawing the kit
  completes nothing and fires no event, so the route stayed pinned to the
  bank and the stand-down below it could never arrive. The owner's report
  was "it should take us to the bank, then hand over to QH" — it did the
  first and could not do the second. That branch is also the ONLY one
  that logs "routing to a bank first", which is what proved his quest was
  already IN_PROGRESS (so the Falador town pin was correct, not a bug).
  Handoff banner gained a START direction, fired on the stand-down EDGE,
  NOT per quest step: 144 of 575 steps are quest-tagged and the STOP
  banner only fires ~30 times, so equal weights would bury the rare
  critical one 4:1.
  **ITEM NAMES: 20 FIXED, AND THE AUDIT IS WHY THEY LASTED.** "black
  wizards hat" sat 0/1 with the hat in the bag — item_ids gave the right
  SPRITE while counting matched by NAME. `audit-goals` section 4 compared
  each key to its id's GAMEVAL CONSTANT, and 1017's is BLACKWIZHAT, which
  reads like a name and CONFIRMS the wrong answer. My first fix ("black
  wizard hat") was ALSO wrong — no such item — and the owner caught it in
  game ("its just called a wizard hat"). Switching the authority to
  RuneLite's own name cache (21,339 entries; covers the 67 untradeables
  the price mapping cannot see) found 19 more, all in use. THREE sat in
  the VERIFIED allow-list: that list answered "is the id right?" and
  recorded blanket approval, silencing the different question "can the
  NAME ever match" — an id-level exemption must never suppress a
  name-level defect. 16 teleport-tab targets pointed at names that do not
  exist ("(tablet)" suffix); the 9 POH tabs genuinely have none, verified
  and left alone. Reimplementing the alias chain in JS cried wolf TWICE,
  so GoalAuditDumpTest now dumps what ItemTracker really produces
  (`build/item-aliases.tsv`) and the audit reads that — do not mirror
  that chain again.
  **DOSE RULE:** a goal that NAMES a dose means that dose (24 one-dose
  vials read 6/6 against "6 full pots"). Dose-less goals unchanged. It
  immediately broke `dueling ring -> ring of dueling(8)` — CHARGE counts
  are not doses — found by reading the map, not by a test.
  **CLICKABLE REVIEW PAGES are now how to ask for input** (owner: "this
  click ui/table format works really well! lets do that").
  `review-item-names` and `review-decisions` generate self-contained HTML
  with the evidence inline (item SPRITE from RuneLite's icon endpoint,
  usage counts, and for pins an "INERT IF BUNDLED" warning). 20 item
  names and 8 quest tags decided in two passes instead of 20 play
  sessions. Both carry reviewed/declined files so a settled question is
  never re-asked. See [[review-ui-for-input]].
  **NEW `StepAnnotation.quest`:** 8 steps whose task IS a quest leg
  ("Continue Lost tribe", "Continue Biohazard", both Gertrude's Cat legs)
  had no tag and no quest goal, so stepQuest() returned null — no QH
  stand-down, no tip line, our route arguing with QH's for the whole leg.
  It lives in annotations because metadata comes from the scraper and a
  hand-edit there dies at the next re-scrape. PREP steps excluded (wave
  13's lesson: tagging "buy a bronze sword for Horror from the deep"
  hands a shopping trip to QH). My own measurement missed 2 of the 8 by
  doing the ARTICLE thing — "Continue Lost tribe" does not contain "The
  Lost Tribe".
  **AUDITS DE-WOLFED:** target-drift 34 -> 26 (it did not know a quest
  goal outranks the text pin, so "Start the Lost tribe" read as a
  206-tile hijack when the live code routes it to Duke Horacio exactly as
  designed); quest-start-pins 10 -> 5 (five underground givers were
  misfiled as disagreements purely because no giver NAME was recorded —
  whether we wrote a name down is not evidence about a pin); audit-goals
  section 1 was STRICTER than the plugin (exact names vs canonical) and
  so flagged two deliberately dose-less colloquials as broken.
  **NEW `audit-place-pins`:** Goblin Village was pinned at 3525,2975 —
  the Kharidian Desert, 547 tiles from itself — because a `{{Map}}` can
  be a POLYGON and the seeder read the first "N,N" in the body,
  straddling two vertices into a TRANSPOSED coordinate. Only casualty of
  197 checked. Fixing it made a SECOND bug reachable: "Continue Lost
  tribe until you need to go to the goblin village" then routed 268 tiles
  to the place the step ends BEFORE reaching, so `STOPPING_POINT` strips
  "until you need to go to X" (27 steps say "until"; exactly 2 have that
  shape).
  **SEED-FACILITIES had three compounding bugs:** it skipped every
  template carrying `mapID=`, but `mapID=0` IS the surface map — the
  Anvil page has 61 templates and we were reading FOUR. Plus only the
  first pin per template, plus `Range (cooking)` being a 404 that printed
  as "0 surface pins", indistinguishable from "the wiki has no data".
  Anvil pins 4 -> 111; Varrock and Keldagrim anvils seeded (underground
  towns could never be served — pins were filtered to y<8000 BEFORE the
  proximity test ran). Wave 18's "range"-the-skill landmine closed: a
  facility word inside a place name ("mind altar", "blast furnace") is a
  place reference, decided from places.json rather than a word list.
  **PROCESS, twice:** launched a second client on top of the owner's
  because the check grepped for "RuneLite" while the dev client is
  `java.exe`. New `tools/check-client.mjs` — whose FIRST outing then
  blocked a launch on log lines 70s old from a client that had already
  exited. A log file cannot run: PROCESS first, log only to say WHICH
  process is ours. See [[check-client-before-launching]].
  **CONFIRMED IN PLAY:** the bank->stand-down sequence end to end, the
  START banner, the Ghost's-skull diagnosis (null-quantity annotation
  items count as required:1, so an unobtainable carry-list item blocks
  arrival forever — now `optional`).
  **OPEN:** tai bwo wannai trio pin (QH opens with "catch 23 karambwanji",
  a prerequisite 69 tiles from the giver — held back for the owner);
  Brimstail interior capture DECLINED, entrance pin kept
  (tools/decisions-declined.json); 5 item names awaiting review; 26 drift
  rows wanting per-step ⌖ captures; and step 258's stopping-point fix
  plus all 8 quest tags are UNPLAYED.
- SESSION WAVE 18 (2026-08-09 evening, LIVE play-test, main at `0bb0f05`,
  10 commits, all pushed; hub pin still `3638c2f`, now 10 behind):
  **the word "the" caused three separate failures, and a stale backlog
  number nearly sent the session down the wrong road.**
  **CHECKPOINTS VERIFIED, NOT CHANGED.** Biohazard `varp 68 >= 14` and Lost
  Tribe `varbit 532 >= 10` are both CORRECT, checked against `qh-tree.mjs`
  rather than recalled. The convention is exact and now recorded: **the value
  is the QH state whose work the NEXT step begins.** Two independent textual
  matches prove it — state 5 IS the Varrock library and state 6 IS the Goblin
  Village, which is precisely where the guide's two "until you need to go
  to…" steps stop. Biohazard state 12 is the Varrock pub leg, 14 is "return
  to Elena", so "Continue Biohazard" ends at Guidor. NOTE for next time: I
  first claimed these earlier values were "confirmed in play". They were not
  — steps 256/258 sit AHEAD of position 240. The textual argument stands
  alone and needed no play data.
  **THE "23 TRAVEL STEPS" WAS STALE.** Top of the value list, and wave 16b's
  arrival correction had already absorbed nearly all of it. Measured with
  preflight's own classifier: the real class was **6 steps with no movement
  VERB**, in two halves. Three verbless journeys ("Make your way to
  Wintertodt", "Take the cart to Shilo Village", "Carpet back to Shantay
  pass") — each added word measured against the whole guide first, each
  matches exactly one step. Three bare place names ("Lumby", "Varrock
  east/west bank"): safe only because `annotationItemsCarried` already
  demands the kit in hand, so "Lumby" ticks on arriving PREPARED. The naive
  bare-name rule was MEASURED AND NARROWED — 2 of its 5 hits were QUESTS
  ("Cabin fever", "One small favour") living in the place namespace as giver
  pins, so walking past a giver would have ticked off a whole quest. They are
  excluded on `type`, a data field, not a guess.
  **ARRIVING IS NOT TALKING** (owner report). "Go under the mountain and
  speak to Lady of the lake" ticked on reaching the pin. **One fault, three
  symptoms**: the tick advanced the frontier, so the sub stopped being
  current, which is what removed the NPC outline and the dialogue help at the
  exact moment they were due. Fixed in two stages — a guard (`TALK_INSTRUCTION`
  blocks arrival; exactly 3 steps guide-wide), then a real **conversation
  detector** reading the dialogue box's own NAME widget (`InterfaceID.ChatLeft
  .NAME`). No proxy, no radius, no timing window. Narrow by design: the step
  must NAME the speaker (so #330 "ask every question" stays a hand tick), only
  subs `hasAnyGoal` rejects are eligible (so "talk to the duke to START Rune
  mysteries" keeps completing off quest state, not off hello), and checkpoints
  and chains still outrank it. Closes the talk steps open since wave 16b —
  Juliet, Reldo, the Oracle, Martin. **101 -> 89 hand-tick steps.**
  **"THE" BROKE THREE THINGS.** The menu says "**The** Lady of the Lake"; the
  step says "Lady of the lake". (1) The conversation detector compared full
  names then fell back to the leading word — "the", under its own 4-char floor
  — so it **would have failed on the very step it was written for**, caught
  from a SCREENSHOT before it ever ran. (2) The scene NPC matcher had the
  identical bug, which is why she was never outlined. (3) The 4-char floor
  itself nearly hid it. If a fourth turns up, build a shared name-normaliser;
  three data points on one NPC did not justify it yet. Same class as wave 13's
  quest-name aliases — the guide and the game disagree about articles as a
  matter of course.
  **HER OUTLINE HAD A SECOND CAUSE, and it is a whole bug class.**
  `places.json` held BOTH `lady of the lake` and `lady of the lake in taverly`
  at IDENTICAL coordinates. An NPC name inside a LONGER place span is read as
  the place talking (the rule that stops "Barbarian" lighting up in "Barbarian
  Village"), so the alias suppressed her. Silent failure — no warning, no log
  line. **NEW `tools/audit-place-spans.mjs`.** Its first version was WRONG and
  the record matters: "one display contains another at the same spot" reported
  ~40 pairs, ALL fine ("Desert Treasure I" contains "Desert Treasure"; "Ceril
  Carnillean" contains "Ceril") because a fuller proper NAME hides nothing —
  the NPC matches at full length and the equal-length rule keeps it. The real
  shape is a name plus a **locational qualifier**. Validated in BOTH
  directions via `--places` against the buggy revision: finds the Lady and
  nothing else, clean on current. Two things mirrored from `PlaceManager`
  rather than recalled, both of which I had wrong: the alternation is built
  from **displays**, not keys, and sorted **longest-first**, which is exactly
  why one scan returns the alias and never the name inside it.
  **NAV STAYED AT THE BANK** (owner report, log-confirmed). Bank-first routed
  him to Falador west bank; with the clay and ore in hand it never moved on.
  Cause: nav recomputes on EVENTS — death, login, teleport, stage change,
  progress — and **withdrawing a kit is none of them**. `navRoutedToBank` now
  re-checks every 10 ticks while a bank is why it routed, the cadence errands
  already use. CONFIRMED IN PLAY.
  **47 CAPTURES HAD NEVER SHIPPED.** ⌖ captures are local by design, but the
  owner makes them *so users do not have to*, which only works if bundled —
  and a reinstall would have taken them. **21 harvested.** Held back with
  reasons: 22 stale BRUHsailer-era keys (the multi-sub ids give them away —
  Oziris is atomic, every live key ends `:0`), 3 CAVE INTERIORS (blurite, ZMI
  warriors, Brimstail — SP cannot draw into an interior and Brimstail already
  has a bundled ENTRANCE pin these would override; **owner's call, still
  open**), 1 tombstone. "Charter to port sarim" landing 341 tiles away at Port
  Khazard is CORRECT — wave 7 makes a charter ⌖ the BOARDING dock. Merged
  never replaced: 10 keys already carried scraper item lists; verified after,
  0 keys lost, 0 fields changed. **Harvest at the end of every session.**
  **FACILITIES: small class, and the bottleneck is the WIKI.** "Make 5 molten
  glass" needs a furnace and never says so, which is why it was hand-pinned.
  `seed-facilities` now also reads the facility a step's PRODUCT implies, and
  `tools/audit-implied-facilities.mjs` measures it: **9 steps, 2 pinned**. The
  DRY RUN mattered more than the audit — it found a landmine predating the
  change: **six steps match the `range` facility while meaning the SKILL**
  ("Get range void from PC", "chin to at least 87 range"), rejected only
  because that wiki page returns no pins. Luck, not correctness; the day it
  resolves the seeder sends people to a fire in Lumbridge to get void. A range
  now needs cooking evidence. **The real blocker is template disagreement:**
  Furnace publishes `{{Map}}` (why furnaces always worked), Potter's Wheel uses
  `{{ObjectLocLine}}` AND `Potter's_wheel` is a REDIRECT `action=raw` will not
  follow (wave 6's gotcha again), and Range/Altar publish no location list at
  all so no page-scraping will ever seed them. `seed-npc-spots` already parses
  ObjectLocLine — reuse it. Also caught before shipping: BLOWING molten glass
  into orbs uses a pipe and happens anywhere, so only MAKING it implies a
  furnace.
  **THE 39 UNROUTABLE, MEASURED: mostly a LABELLING problem.** ~24 are advice,
  grinds or structural markers where a pin would be arbitrary; ~8 are endgame
  bosses far past where nav matters; **~7 are genuinely worth pinning** (smith
  dart tips, mithril grapple, ammonite crabs, compost bins, toadflax, tree
  spirits, molten glass+gems). Much smaller than it looked, and what blocks
  the pinnable ones is missing wiki pin data, not logic.
  **CONFIRMED IN PLAY:** the conversation detector, nav leaving the bank, both
  ticking end to end. **NOT confirmed:** the Lady's OUTLINE (fixed after he
  last stood there).
  **PROCESS:** built in a throwaway `git worktree` twice while the client was
  live, and checked for a running client before EVERY gradle command — the
  wave 17 failure did not recur. Owner asked for SHORTER session reports.

- SESSION WAVE 17 (2026-08-09, all-day LIVE play-test, main at `3be94ca`,
  13 commits, all pushed; hub pin still `3638c2f`): **a UI-and-information
  session. Almost nothing was a detection bug; nearly everything was the
  panel or an overlay telling the owner something untrue.**
  **THE PLUGIN HUB IS NOT STUCK ON US.** Read the checks properly: the red
  X is titled *"Requires maintainer review"* and fails BY DESIGN until a
  human approves. `build` passes, `mergeable: true`, one file, two lines.
  106 open new-plugin PRs, oldest 22 days, ours 17 — mid-pack, though the
  queue is NOT FIFO (PRs numbered 600 later merge same-day; small plugins
  get picked off). A reviewer on Discord (`/ghauth` unlocks #development)
  asked "are you doing networking" and pointed at `import
  java.net.URLEncoder`. We never made a request — it was a string helper
  stashing a place name in a Swing href — but **the import is what a scan
  sees**, so both it and URLDecoder are now a hand-rolled percent codec,
  with encode+decode in ONE class so they cannot drift (the existing tests
  caught the one real difference immediately: URLEncoder writes space as
  "+", ours "%20"). His HTML remark was NOT acted on: there is no HTML in
  the properties file, the PR body or the README, only Swing JLabel
  markup, which RuneLite's own plugins use throughout. Asked for
  specifics.
  **THE PANEL WAS LYING IN THREE WAYS.** (1) Quest kits showed seven
  numberless carry-list items where QH said plainly "1 x Pot" — we HAD the
  numbers and were throwing them away, since cross-check parsed
  getItemRequirements for names only. `seed-quest-requirements.mjs` fills
  in 38 quantities across 20 quest steps, and dodges the trap that QH's
  constructor DEFAULTS to 1: taken literally that puts a green "Astral
  rune 1/1" on While Guthix Sleeps, so a defaulted 1 is trusted only for
  things you hold one of, never a stackable (9 left alone). (2) A
  requirement badge counted the BANK past what the step asked —
  "clay 10/6" with six in the bag — now capped at the requirement, because
  where the items are is what the colour and 🏦 already say. (3) "copper
  0/4" beside four in the bank: `item_ids.json` has `"copper" -> 436` and
  that map is read in exactly ONE place, `lookupIconId`, so the name got a
  copper-ore SPRITE while counting matched by NAME and found nothing. A
  right picture over a wrong count is the most convincing way to be wrong.
  `audit-goals` could not catch it because it treats item_ids KEYS as
  proof a name is real — same audit-vs-plugin drift as the codec. Fixed as
  a CLASS: the alias chain already turns "mind" into "mind rune", so it
  now turns "copper" into "copper ore" (measured first — exactly two keys
  are one word short of a real item, and "laws" already worked).
  **OVERLAYS: green means us, cyan means Quest Helper.** Four overlays
  drew in (0,255,255) — QH's exact cyan — so the two plugins painted over
  each other with nothing to tell them apart. All four now take a
  configurable colour (the first colour setting the plugin has had),
  defaulting to green. Both ITEM overlays drew a rounded rect around the
  SLOT; they now use `ItemManager.getItemOutline`, the same API QH uses,
  so ours trace the sprite. Inventory hints also NARROWED — the default
  branch unioned step kit + sub kit + every goal, so seven items glowed
  where QH lit one garlic. Now: active errand stage's items, else the
  sub's detected goals, else NUMBERED requirements, never the carry list.
  Murder Mystery went 7 outlines -> 1. The SHOP overlay deliberately keeps
  the wide list.
  **NEW: `tools/preflight.mjs`** — reads the persisted route position and
  says what the next N steps can and cannot do (MANUAL ONLY / NO ROUTE /
  CARRY-LIST KIT). **Its own numbers were wrong three times before they
  were right**, each time because it modelled `targetFor` from memory
  instead of its sources: it did not know quest steps route to the GIVER,
  that place names resolve via `firstPlaceIn`, or — the big one — that a
  step's own 📍 LOCATION tag is a routing source. That last correction took
  "nowhere to route" from **132 to 44** guide-wide, and the inflated figure
  had already been written into CLAUDE.md and repeated to the owner.
  Corrected in place. Also counts ARRIVAL as a completion path (a step with
  no detector still ticks by walking there), taking hand-tick from 139 to
  **101**. A check nobody believes is worse than no check.
  **EARLY-GUIDE PASS** (the owner's question: have the improvements been
  applied backwards?). Audits and seeders always were — they are
  guide-wide. Play-verification never can be, since he will not re-walk
  those steps, so their defects can only ever be found by a NEW user, and
  the hand-tick recording that would have been evidence only started two
  days ago (2 data points, both on steps we had just "fixed"). Measured:
  the completed region is in BETTER shape than what is ahead (11% vs 23%
  hand-tick). Real finds: **X Marks the Spot**, the first quest a new user
  meets, had four dig steps with no pins and no detection — now pinned and
  checkpointed off `VarbitID.CLUEQUEST` (8063), resolved through QH's
  QuestVarbits registry rather than guessed; and the clue hunter gloves/
  boots dig at 2579,3378, whose wiki page independently confirmed the garb
  pin we already had. **Ten missing PLACES seeded** from the guide's own
  unresolved 📍 tags (Keldagrim 9 steps, Dorgesh-Kaan 4, Trollheim, Desert
  Quarry, Weiss...); God Wars Dungeon pinned at its SURFACE entrance per
  the ZMI rule, and "South of Khazard" keyed `khazard` because getLoose
  strips the direction. 22 unresolved tags -> 12, of which 10 are not
  places ("Various" alone is 59 steps).
  **CHECKPOINTS: only 2 of 8 "continue quest" steps can carry one.** Lost
  Tribe `varbit 532 >= 10` and Biohazard `varp 68 >= 14`. Recorded why the
  rest cannot: **Dragon Slayer's DRAGONQUESTVAR (177) also carries bit
  flags 11-20**, so any `>=` is true the moment one is set (the barcrawl
  trap); Gertrude's Cat references no var at all; and Merlin's magic words
  is a step QH ITSELF says to tick by hand.
  **CONFIRMED IN PLAY:** the QH stand-down, chain-completion ticking the
  Merlin step on login, dialogue highlighting actually colouring an option
  (which retroactively settles wave 16's open question — the path works,
  so Morgan's failure really was the two causes found there), bank-first
  routing to Catherby for banked copper.
  **NOT A BUG, third occurrence:** a teleport marker on a WORLD TILE is
  Shortest Path's own transport suggestion. Ours only ever highlight UI
  widgets. Our hint had correctly logged `none` — because it measures the
  PLAYER's leg as a straight line (Catherby->Taverley reads 123 tiles;
  the real walk crosses White Wolf Mountain). That is the exact limitation
  wave 14 measured and left open, on the very same mountain that started
  it. Both obvious fixes were already rejected on data.
  **PROCESS FAILURE worth keeping: I ran `gradlew test` twice while the
  owner's client was live**, the thing wave 11 recorded as breaking a
  running client. No damage this time (a later `clean` build removed any
  half-written classes), but the rule exists because the failure is
  invisible until the panel dies. Check for the client before EVERY build,
  not just the first.

- SESSION WAVE 16b (2026-08-08 late, LIVE play-test after the desk work;
  main at `c913f26`, pushed; hub pin stays `3638c2f`): **three reports, and
  the lesson is that none of them needed a game to find.**
  (1) **The descent legs wedged before he ever saw them** — seeded as "arrive
  at floor N", which is unanswerable once you are OUTSIDE the keep, so from
  Catherby the chain sat on "climb down from the top floor" forever. Caught by
  reasoning about his actual position before he launched. Descent legs now say
  **`leave` the floor you are on**, which is satisfied from anywhere outside.
  CONFIRMED IN PLAY: from Catherby the chain fell straight through to the
  candle. Same fault as the crate: a positional condition anchored on the near
  side of the thing it describes.
  (2) **The step could not tick by any route.** "Kill Mordred and get bat
  bones/black candle" parses to NO goal (no kill-a-named-NPC detector, and the
  slash is not a list), so `completion-paths.tsv` read `none` and nav sat
  logging "holding until the sub's goal ticks" for a goal that does not exist.
  Now a chain completes its own step, scoped to subs with no goal of their own
  — exactly 2 steps guide-wide, both previously uncompletable. Incidental
  chains are untouched (the pebble chain must never tick "Do Tree Gnome
  Village"). CONFIRMED IN PLAY on login.
  (3) **Diary legs had never guided anyone.** Sherlock and the flax sit in
  front of the bat bones, and items look ahead and cascade, so carrying bones
  marked both done behind them. The obvious repair is WRONG — making them
  independent would let an optional diary task BLOCK a quest chain, which the
  seeded note already feared. New **`optional`**: outside the ordering in both
  directions, nothing implies it, it implies nothing, the chain completes
  without it, and it asks within 40 tiles. Writing the test found the shape:
  the window where it can usefully ask is between its own satisfaction radius
  and the nudge radius, since inside 12 tiles a waypoint is simply done.
  (4) **NAV WAS FIGHTING QUEST HELPER on every quest step** — the "quest owns
  guidance" branch posted the step's area rather than clearing, a wave-2
  compromise for players without QH after a blanket stand-down was tried and
  reverted. Both cases are servable now that we can TELL THEM APART with no
  reflection: `runelite.externalPlugins` lists installed hub plugins and
  `runelite.questhelperplugin` records an explicit disable. **The comma split
  is load-bearing — "sea-charting-quest-helper" CONTAINS "quest-helper"** —
  and the toggle is ABSENT at default, so only a literal "false" is off.
  Limit: says INSTALLED, not "actively guiding"; closing QH's sidebar is
  invisible to us.
  (5) **THE BANKING REPORT WAS AN INFORMATION BUG, AND I CHASED THE ROUTER
  FIRST.** Owner: we have QH and the wiki, so if we lack quest items we should
  bank. Measured instead of assumed: `cross-check-quest-kits` guide-wide finds
  QH requiring exactly TWO items we omit (Demon Slayer's bucket of water,
  Tribal Totem's glory), both deliberate policy exclusions. **The kits are
  complete and the behaviour was right.** Murder Mystery's only requirement is
  a pot, which the wiki marks obtainable in-quest and he was already carrying.
  What failed is that the panel showed seven NUMBERLESS carry-list items
  (gp, barcrawl card, spade...) with no way to tell them from requirements. I
  had proposed a "one bank stop per step" rule before measuring; the
  measurement killed it. 20 steps have entirely unnumbered kits and they are
  the site's running inventory advice ("few cakes", "all of your mind and air
  runes") — numbers do not exist for them and seeding numbers would be wrong.
  **NEW TOOL — `tools/preflight.mjs`**, the systemic answer to "why am I
  reporting the same thing over and over": it reads the route position the
  plugin already persists (`ironscape.position_OZIRIS`, no argument, because a
  check you must configure is a check nobody runs) and reports what the next N
  steps can and cannot do — MANUAL ONLY / NO ROUTE / CARRY-LIST KIT. All three
  of tonight's reports appear in it. Its FIRST run called 8 of 12 steps
  unroutable because it did not know quest steps route to the GIVER or that
  place names resolve through `firstPlaceIn`; **a check nobody believes is
  worse than no check**. Guide-wide it measures **101 steps that can only be
  ticked by hand** and **44 with nowhere to route** (first measured as 132, before the check learned about 📍 location tags) — those numbers ARE the
  recurring-report problem, since they were always going to arrive one at a
  time. Breakdown of the 107 (before the arrival correction, 139): 74 genuine advice (want a panel LABEL, not a
  fix), 23 travel steps with no travel goal, 19 "Continue quest X", 6 talk,
  5 combat, 6 banking advice.

- SESSION WAVE 16 (2026-08-08 late, desk session — owner away, NOTHING
  play-tested; main at `20b2dc9`, pushed; hub pin stays `3638c2f`):
  **the model could not say "am I in yet", and once it could, the rest was
  small.** Wave 15's instruction was to ask whether the MODEL can express the
  thing before fixing data, and the answer here was no twice over.
  **FAULT 1 — the chain could not express a journey that comes back on
  itself.** Every stage was judged wherever it sat, and any satisfied stage
  cascaded its predecessors done. So a "back down to the ground floor" leg
  would be satisfied by WALKING IN at ground level, and the cascade would mark
  the Mordred fight done on the way in — meaning the descent legs the owner
  asked for were not seedable at all until the rule changed. The
  discriminator is whether a condition can COME UNDONE: a quest var never
  counts back down and an item you hold proves the legs before it served, so
  those may look ahead (that look-ahead is what rescues a leg you teleported
  past); where you are standing is reversible, so it is judged at the FRONT
  of the chain and nowhere else. The same rule already governed
  `requires.equipped` (frontier-only because worn gear comes off) — this is
  that rule applied where it was missing, not a new idea. The order rule moved
  out to **`ErrandProgress`**, away from the client, so it is TESTABLE:
  `ErrandProgressTest` walks the whole Keep Le Faye journey leg by leg, and
  was **verified to fail under the old rule** before being kept.
  **FAULT 2 — `region` was the right idea at the wrong granularity, and its
  very first use already overlapped.** A region is 64x64: **11061 holds Keep
  Le Faye AND the giant bats at 2757,3401 that the same chain sends you to two
  stages earlier**, so "am I in the keep?" was answerable 13 tiles outside it.
  QH has zones for exactly this reason. New **`Errand.zone`** (a box on ONE
  plane, seeded straight from QH's `setupZones()`) is the only condition that
  tells FLOORS apart; `region` stays, documented as coarse, for places whose
  bounds nobody has written down. Also new: **`leave`** (inverts zone/region —
  the way OUT of a one-way interior is a leg like any other, and no coordinate
  can express it because every tile outside a door is a few tiles from every
  tile inside it) and **`object`** (name the thing to click instead of
  guessing from the hardcoded traversal-word list). Stage KEYS now carry their
  index, since a chain may visit one place twice and a coordinate key let the
  first visit tick the second. **`hold` gained its second, equally real
  reason**: not only "this stage is quest progress" but "SP cannot draw this
  leg" — from inside the keep it proposed a Lumbridge home teleport, the crate
  being one-way.
  **KEEP LE FAYE RESEEDED** subtractively from `qh-tree.mjs` states 3 and 4,
  which carry every leg with exact objects and coords: 2 stages -> 7 (crate ->
  stairs 2770,3405 p0 -> stairs 2770,3399 p1 -> Mordred -> down 2769,3399 p2
  -> down 2769,3405 p1 -> candle), the three interior legs holding. **NOT
  seeded, because it would be invented: the keep's front door.** QH does not
  model it either — its own stuck-inside step just says "Return to Catherby".
  One in-game ⌖ capture closes it.
  **THE SWEEP WAS SMALLER THAN IT LOOKED, and the reason is worth keeping:**
  the route/satisfaction split already covers a traversal whose satisfaction
  sits on the FAR side (Lumbridge cellar, both ladders, the ess mine all
  work). The gap was only ever legs satisfied on the NEAR side — the crate,
  and exactly one other, the **ZMI cave** (entrance now names its object; the
  Zamorak warriors leg holds rather than asking SP to route through a cave).
  `audit-errand-chains` gained the three structural checks (SELF-SATISFYING /
  UNGUIDED TRAVERSAL / COARSE REGION, no QH needed). Its first run reported
  six; **read line by line**, four were var-gated stages (a gate is quest
  progress, so no radius decides anything) and two were ordinary 700-tile
  walks SP draws fine — both exemptions are now in the checks.
  **MORGAN'S DIALOGUE — TWO CAUSES, NEITHER THE MATCHING CODE.** The log is
  conclusive: (1) QH's `addDialogStep` says "Ok I will do all that.", the game
  says **"Ok, I will go do all that."** — `dialogKey` strips punctuation but
  cannot insert a word, so it could never have matched (both are seeded now);
  (2) the stage carrying it is gated on `varp 14 >= 4`, which is what her
  dialogue DOES — the log has the chain rerouting to the Candle maker at
  22:45:22 and the player picking that option at **22:45:42**. A
  quest-progress stage is satisfied by the conversation it is guiding, so it
  can never own its own final option; **every "talk to X until the var moves"
  stage has that shape**. Options now come from the WHOLE live chain (exact
  strings, so an option not on screen simply does not match). NOTE the first
  option ("Tell me how to untrap Merlin and I might.") matched exactly and the
  path provably ran — whether it recoloured is still unconfirmed. That path
  logged NOTHING, which is why last session could only theorise and why the
  log only settled it because a THIRD-PARTY plugin happened to print the
  chosen option; `dialog-highlight:` now names offered vs wanted.
  **REVIEW LIST READ, NOT ACTED ON** (`audit-quest-start-pins`, 10 open):
  five are the already-settled underground-giver class misfiled into REVIEW
  (between a rock, land of the goblins, darkness of hallowvale, troll romance,
  in search of knowledge); two are QH-opens-with-an-approach (tai bwo wannai
  trio's karambwanji, mountain daughter's boulder); the ones that look real
  are **`creature of fenkenstrain`** (ours records a SIGNPOST, QH's step says
  "Talk to Dr. Fenkenstrain to start the quest" 89 tiles away) and possibly
  `the queen of thieves` (45 tiles) and `the great brain robbery` (ours on Mos
  Le'Harmless, QH at Bill Teach's ship). Left for the owner per the standing
  rule about confirming game facts firsthand.

- SESSION WAVE 15 (2026-08-08 late, LIVE play-test on the Merlin's Crystal
  step `a8014d6a77`, main at `bbc7cdb`, all pushed): **the whole evening was
  one errand chain, and the lesson is that it was never a seeding problem.**
  Six rounds of fixes, each found in play: (1) the seeded note was INVENTED
  ("insist when he warns you") — the wiki and QH both say the Candle maker
  MAKES the candle in exchange for a bucket of wax; (2) the three wax legs
  (repellent -> bucket -> beehive) were missing entirely; (3) no STATE GATE —
  QH puts the whole black-candle branch in quest state 4 and the player was in
  3, so the dialogue did not exist and the chain guided a wasted trip
  (`varp 14 >= 4`, CONFIRMED in play); (4) the way IN was missing — the keep
  door is locked forever and Arhein's CRATE behind the Candle Maker's shop is
  the entrance; (5) as a proximity WAYPOINT the crate self-satisfied because it
  sits where the wax legs already put you; (6) merged into the next stage as a
  static route point it then pointed back OUT of the keep from inside.
  **ROOT CAUSE of 5 and 6: the stage model could not express "am I in yet?".**
  Stages could be satisfied by item, var, hand-in or proximity — and a leg
  whose whole point is GETTING SOMEWHERE is none of those. QH has had this all
  along (`inFayeGround`/`inFaye1`/`inFaye2` are zone checks it evaluates every
  tick), which is why its guidance looks seamless. NEW FIELD `Errand.region`
  (satisfied when `getRegionID()` matches; checked BEFORE the item branches so
  a region stage can still carry an item for its badge). Keep Le Faye = 11061.
  **This is the model for EVERY "go through this thing" leg in the guide** —
  cave entrances, boats, trapdoors, the ZMI and Brimstail cases still
  approximated with proximity coords. A sweep, not a report-at-a-time fix.
  **NEW TOOLS**: `qh-tree.mjs` reads QH as the STATE MACHINE it is — parses
  `steps.put(N,…)` plus the ConditionalStep tree and prints, per quest state,
  the branches with their conditions and each leaf's coords/description/dialog
  (`--draft` emits trimmable errand stages). Seeding becomes SUBTRACTIVE: take
  the state your step covers and DELETE what it does not own. The old additive
  way silently missed whatever nobody thought to add — four times, on one
  chain. It found Morgan's dialogue within an hour of being written.
  `audit-errand-chains.mjs` (UNCOVERED = a QH step no stage goes near; NO GATE
  = chain spanning several quest states with no var gate). **It printed the
  crate leg, twice, and I skimmed past it** — a tool only helps if its output
  is read line by line for the chain you are standing in.
  **ALSO SHIPPED**: errand stage BADGES (per-stage NEEDED/HELD/SPENT state
  decided by the chain, never a raw count — half these items are consumed into
  the next stage and plain "0/1" would sit red forever; display-only, never
  annotation items, so the arrival gate and bank-first are untouched); ground
  items now match the ACTIVE ERRAND STAGE's item and the nearest-NPC fallback
  stands down when it is in the scene (a bystander was outlined instead of the
  repellent on a table — `findWantedGroundItems` read `itemGoalsBySub` and
  nothing else, same root as the missing badges); stage `items` lists drive
  QH-style use-order inventory hints. **PANEL BLANKED IN PLAY** — the badge
  cache called `panel::refresh`, which rebuilds the whole view incl. scroll and
  jump-to-current, from the per-tick path once per chain. Never do that from a
  badge update; `refreshItemCounts` cannot blank anything but also cannot ADD
  rows, so stage badges appear from the next natural rebuild.
  **CONFIRMED IN PLAY**: crate entry, region release, Mordred outlined,
  `varp 14 >= 4`, badges, ground-item highlight, nav to the candle.
  **STILL OPEN**: no plane awareness (guided up the FIRST staircase only, and
  cannot guide back down and out — SP has no path out of the keep interior, so
  the exit wants OUR object outlines, not SP's line); the dialogue recolour
  never fired for Morgan even though the strings match (`dialogKey` lowercases
  and strips punctuation, so it is NOT case) — read the log, it is a code path.
  **A teleport marker on a world TILE is Shortest Path's own suggestion** — our
  hint logged `none` while SP drew a Lumbridge home teleport, because the crate
  is one-way and SP cannot path out of the interior.

- SESSION WAVE 14 (2026-08-08, desk session — owner away, NOTHING play-tested):
  **P1-08 CLOSED: first legs now rank by WALKED distance.** The question the
  owner asked first — are SP's 25 transport TSVs enough to reason about
  connectivity offline? — is **no**, and connectivity was the wrong question
  anyway. The TSVs model CROSSINGS, not terrain, so nothing in them says
  Burthorpe -> Keep Le Faye is a 531-tile walk; and White Wolf Mountain is
  WALKABLE, so the two are connected — the lie was path LENGTH, not
  reachability. What answers it is SP's `collision-map.zip` (1.2MB, two bits
  per tile: can-step-north, can-step-east, layout in `SplitFlagMap.java`),
  which the hub does not stop us READING even though it stops us calling their
  pathfinder. Neither half suffices: collision alone still ranks Burthorpe
  first (531 vs Port Sarim 692), transports alone cannot see the mountain.
  SHIPPED: `tools/build-travel-distances.mjs` runs SP's own search offline at
  full tile resolution and bundles a distance FIELD per landing (32-tile cells,
  25 fields, **52KB gzipped**, `travel/travel_distances.bin.gz` +
  `TravelDistances.java`). No collision map in the jar, no runtime search, no
  new thread — which matters in a plugin that has hard-frozen twice on
  re-entrancy. Transports counted are ONLY the ungated ones (no quest, no
  skill, no items beyond coins) and NO long-distance networks: we cannot know
  an account's unlocks at build time, a missing shortcut reads LONG which
  suggests FEWER teleports, and baking networks in would let the hint argue
  with itself. Spirit trees are origins as well as targets so the existing
  network shortcut survives in walked tiles.
  **MEASURED, NOT ASSERTED** (340 (player, target) pairs from the guide's own
  pins, scored against full-resolution truth): right landing **64% -> 84%**,
  fire/don't-fire **82% -> 87%**. TWO THINGS THE MEASUREMENT KILLED. (1) The
  tempting argument that a straight-line player leg is safely conservative is
  FALSE — straight line is not a lower bound on travel, because **32% of pin
  pairs travel SHORTER than the straight line** (a boat costs nine tiles and
  the water it crosses is hundreds). (2) Scaling the player's straight line by
  the measured median walk ratio, to make both legs comparable, scored WORSE on
  both counts, so it is not in the code. A coarse arbitrary-to-arbitrary
  navigation graph was built and REJECTED: 110KB for 92% decision agreement
  against 52KB for a perfect landing ranking. Metrics are never MIXED within
  one decision — when the table cannot speak about a target every candidate
  falls back to straight lines together, and a landing with no ungated route
  answers UNKNOWN and drops out (the barrier case stated as a distance).
  `TravelDistancesTest` fails the build if a landing name drifts from
  minigame_landings.json / TELEPORT_SPELLS / SPIRIT_TREE_ORIGINS, since a
  renamed landing would silently answer UNKNOWN and fall back to the very
  straight lines the table replaces. `tools/audit-first-legs.mjs`: 188 of 558
  targets got the wrong landing before, 21 with no ungated route at all.
  **audit-pin-reachability REWRITTEN from proxy to proof** — it used to ask
  "is a transport endpoint within 40 tiles" and report DEFINITE/LIKELY/
  BORDERLINE tiers because it could not tell walking from teleporting. It now
  runs SP's flood fill across every plane through every transport INCLUDING
  gated ones (the question is whether SP can draw a path for a player who
  qualifies): 1,281,364 tiles reachable from Lumbridge. Three tiers now mean
  something: WRONG SPOT (nearest standable tile well clear — re-anchor),
  NO ROUTE (nothing within 120 tiles; **14 of the 17 are Sailing islands**),
  and FINE (within 5 tiles — **NOT a defect**, because SP's own pathfinder
  walks to the closest reachable tile when the target is blocked, so a pin on
  a bank booth routes correctly; the old tool would have reported all 16).
  Filtered to names the GUIDE mentions, since a broken pin only costs a play
  session if a step can route to it: 70 findings -> a 14-line review list, on
  which `castle wars` (25 tiles out) and `pest control` (13) are on the route.
  **audit-quest-start-pins CLASSIFIED**: it reported 24 drifts as if drift
  meant we were wrong, so the same 24 returned every session. QH's FIRST step
  is very often not the giver but the approach ("go to Port Sarim to get a
  boat to Entrana" before Auguste; "catch 23 raw karambwanji"; "climb to the
  second floor" before Sir Tiffy) — our giver is the better routing target in
  every one. Now 10 to REVIEW (QH's own first step says it starts the quest
  and still disagrees, or we have no recorded giver — `creature of
  fenkenstrain` records its giver as a SIGNPOST) and 14 labelled not-defects.
  Nine of those 14 are underground givers vs QH's surface entrance, the
  ~6,400-tile rows: that is the ZMI/Brimstail shape, so it is stated
  explicitly that it is NOT one here — reachability confirms SP paths to all
  nine, making ours the more precise target.
  **BOAT GATE: no behaviour changed on one data point** (owner's call). The
  log confirmed its first exercise went "holding, gangplank loaded but not
  crossed" -> "open, no gangplank in range" 13s later, so the crossing was
  never accepted — but the line could not say WHY, and two faults produce it
  (no click recorded at all vs a click recorded then rejected by the
  near-the-destination test), wanting OPPOSITE fixes. The gate lines now carry
  what the recorder holds: tile, age in ticks, STALE flag, distance from the
  destination against the radius needed. The next trip is conclusive.
  **OWNER DECISIONS**: Fremennik Trials lyre -> `granted` (the kit already
  lists the knife and axe that BUILD it, so it is an in-quest acquisition and
  the kit policy says those never sit red) — audit-quest-granted is now 0
  flagged, the list is closed. Rag and Bone Man start step stays ticking on
  quest start: the step says "start", and its pots/logs/tinderbox belong to
  burning steps much later; if the nag is wanted, move that kit rather than
  gate the start.

- SESSION WAVE 13 (2026-08-08, desk session — owner away, NOTHING play-tested):
  **ANNOTATION ITEMS NOW GATE PURCHASE STEPS.** The reported shape: "Buy 1
  pack of normal compost and all farming tools, store everything in
  leprechaun" (`5bf54fe229`) yields ONE detected goal — the pack — so buying
  it ticked the whole step while the five seeded tools had no vote.
  The proposed narrow rule (explicit quantity, non-quest steps, minus
  granted/consumed/optional/ingredient) was MEASURED AND REJECTED: it changes
  30 steps and **29 are wrong**, because annotation items are overwhelmingly
  TOOLS and INGREDIENTS, not objectives, and explicit quantity does not
  separate them (`seed-tools` writes x1 like anything else). Three that wedge
  permanently: "Get 61 Crafting" would demand 1,200 buckets you spend
  crafting ON A LEVEL GOAL; "Hunt 15k red chins" wants a carried box trap
  while chins count the BANK; "give the bread to the beggar to get the
  excalibur" demands bread you already handed over. That last is the
  `consumed` failure mode arriving through an unflagged item — exactly what
  P0-07 kept the arrival gate tight for.
  SHIPPED INSTEAD: `purchaseListAcquired` gates only steps whose detected
  goal is an ACQUISITION. On a buy step the relationship inverts — the
  annotated list is the rest of what the sentence told you to buy. Blast
  radius is the one reported step, and it covers future "buy A and B" where
  only one half parses. The list ARMS once seen complete, recorded as a
  reserved `<subId>|@purchase-list` acquisition baseline so it survives a
  restart AND is cleared by an untick (both needed: the step ends by putting
  the tools INTO the leprechaun, which no readable container holds, so an
  in-hand-now gate would slam shut on the deposit). Arming runs BEFORE the
  item goals so buy order cannot change the outcome. Gertrude's Cat is
  correctly untouched — its items are the scraper's null-quantity CARRY
  LIST (bucket/barcrawl card/rune mysteries package), not its objective.
  **NEW DUMP + TOOL**: `GoalAuditDumpTest.dumpCompletionPaths` ->
  `build/completion-paths.tsv` (which detector path can tick each sub, from
  the DETECTOR rather than inferred from step text — inference over-counted
  by 31 steps that travel/arrival already gate via annotationItemsCarried);
  `tools/audit-item-gating.mjs` joins it against the annotation corpus and
  keeps the rejected wider rule listed so nobody re-proposes it blind.
  **QUEST-GRANTED AUDIT CAN NOW SAY NO**: `granted` recorded "yes" and
  nothing recorded "reviewed, genuine fetch", so settled questions
  re-reported forever — 4 of 7 findings were already answered (phoenix
  feather/barronite deposit wave 12, silverlight keys wave 9). New
  `tools/quest-granted-reviewed.json` (same shape as audit-goals' VERIFIED
  list); the filter ALSO had to be applied to the annotation loop, which
  only checked `granted` — every finding this audit produces arrives there,
  so a rejection suppressed nothing. Verdicts settled from the wiki's
  "Items required", where placement under "Obtainable during quest" is the
  discriminator: pink dye (WGS) GRANTED and seeded; ring of charos(a) "or
  500 coins" = bring; Plague City hangover cure "or the ingredients to make
  one", and the guide MAKES it two steps earlier (`fa497e8b66`) — bring-
  then-consume-in-finale is a good kit entry per wave 9. Down to ONE open
  question: the Fremennik Trials lyre (`80a3ae4d44`), which the wiki calls
  a drop from the trial NPCs "or the skills and materials to make one" —
  owner's call whether it should sit red.
  **GANGPLANK GATE, STATIC FINDING (still not exercised)**: per the wiki's
  gangplank location list, ALL SIX boat destinations have a plank — Port
  Piscarilius (Veos), Port Sarim (to Musa Point), Musa Point, Rimmington
  (Barnaby), Ardougne (to Brimhaven), Brimhaven (to Ardougne). So the
  release valve NEVER fires at any of them and the gate is load-bearing on
  every one. Code review found it sound (all five GAME_OBJECT_*_OPTION
  cases record the crossing, both name comparisons lowercase, 8-tile plank
  proximity vs 25-tile crossing-near-destination). The case to watch is a
  route that lands you ASHORE with a plank inside 8 tiles: it holds until
  you walk away — self-releasing, not a wedge, but it shows as a late tick.
  **TARGET DRIFT — NO SAFE GENERAL RULE.** Tried to turn the 35 (not 36 —
  the Catherby bin got pinned) into code and failed honestly. Distance
  alone has real counterexamples: "Go to Zeah and get 100 compost and
  saltpetre" drifts 219 tiles to the saltpetre DIG SPOT, which is where you
  actually go, so a blanket threshold regresses it. A type-based rule
  ("another quest's giver must not win") also collapsed: Shilo Village and
  Tower of Life are place AND quest names with drift 0, several steps carry
  no `quest` metadata even when the quest is theirs, and `firstPlaceIn`
  picks the EARLIEST match so an own-quest name usually wins anyway. Left
  for per-step ⌖ captures, as the owner said. TRIAGE for when he does them
  — real hijacks: `a6c22a24cb` Evil Dave stew, `9c34f09b9e` Vannaka,
  `5b504dfa2a`/`cf82191582`/`4e5d813136` wintertodt-as-provenance,
  `4c0560fff4`/`1763f4c272` "compost"->Vannah, `a18966b61b` Heckel Funch
  ->"bucket of milk", `b8e1b2bf8a` pest control->"lumby", `bbbd9a9020`
  blurite->"falador", `a90532b6e2`/`b23fd9c74f`/`f27be2a275` ->"dragon
  slayer" giver, `13f33630f0`/`7ca10e694f` Lost Tribe, `8fe077da99`
  karambwans->Zanaris. NOT defects: `21637f4eeb` (Varrock east bank IS the
  destination), `930916ba4a` (saltpetre dig spot), the three Clan Wars
  steps (Clan Wars is the first leg). `ce8c0e36d3` is its own case — a ⌖ on
  a boat sub marks the BOARDING dock (wave 7), so it wants a pin at the Ardy
  dock, not Brimhaven.
  **11 QUEST STEPS COULD NEVER AUTO-TICK** (the biggest find of the
  session, and it came out of the gating measurement rather than a report).
  14 quest-tagged steps had NO detected quest goal; with no goal their only
  auto-completion is ARRIVAL, which is gated on the step's annotation kit —
  and on a FINISHING step that kit is what the quest CONSUMES. So "Do
  Desert Treasure", "Cabin fever", "Watchtower quest", "One small favour",
  "Rum deal", "Garden of tranquility quest", "Forgettable tale...", "Do
  Hand in the sand quest", "Finish Evil Dave subquest" and two more could
  not complete by any route. TWO independent causes:
  (1) NAME RESOLUTION — the metadata fallback required an exact match on
  the RuneLite Quest enum and SEVEN tags miss it: "Vampire Slayer" ->
  Vampyre (renamed), "Garden of Tranquility" -> Tranquillity (two Ls),
  "Hand in the Sand" -> "The Hand in the Sand" (article), "Desert Treasure"
  -> "Desert Treasure I", "Desert Treasure II" -> "...- The Fallen Empire",
  "Rag and Bone Man" -> "Rag and Bone Man I", "Recipe for Disaster (Evil
  Dave)" -> "Recipe for Disaster - Evil Dave". Hand-authored alias map, NOT
  fuzzy matching — "Desert Treasure" and "Rag and Bone Man" each prefix
  TWO real quests, so a prefix rule picks wrong. New
  `GoalAuditDumpTest.dumpQuestNames` -> `build/quest-names.tsv` makes the
  next such diff mechanical (it also caught that the enum constant is
  `DESERT_TREASURE_I`, not `DESERT_TREASURE`).
  (2) THE VERB GATE rejected any step whose text lacked
  start/do/finish/etc, killing bare-name steps. FIRST ATTEMPT KEYED THE
  EXEMPTION ON `questStatus` AND GoalDetectorTest CAUGHT IT: the site tags
  PREP steps with a status too, so "Make the hangover cure for plague city
  quest" would have begun ticking off Plague City's quest state. The
  premise "prep steps carry no tag" was true of the shipped data snapshot
  and false as a contract — the TEST held the knowledge the data did not.
  Discriminator is now "the step's text IS the quest name, give or take a
  trailing 'quest' and a parenthetical"; the prep step fails it because it
  leads with its own action. Verified by DIFFING completion-paths.tsv
  before/after: exactly 12 subs change, 9 -> quest-finish, 3 ->
  quest-start. "Start Vampire slayer, get 3 garlic" gains a quest goal
  ALONGSIDE its item goal, which is STRICTER (the atomic branch makes
  STARTED fall through to the items). WATCH IN PLAY: "Start Rag and bone
  man on the way to the temple" previously needed its pots/logs/tinderbox
  through the arrival gate and now ticks when the quest starts — consistent
  with every other start step, but it is the one loosening.
  **INERT GAP RECORDED**: `annotationItemsCarried` reads `step.getId()`
  whenever a step has ONE sub — which on the atomic Oziris guide is ALWAYS
  — so the 5 sub-keyed annotation item entries are invisible to the arrival
  gate. Checked all five: none sit on an arrival-completed step (the gas
  mask and ghostspeak amulet steps complete off their sub-keyed
  `requires.equipped`, the other three off item goals). Real but currently
  harmless; do NOT "fix" it by tightening the arrival gate without a
  specific report. `gateableItems` (the new purchase gate) reads BOTH keys.

- SESSION WAVE 12 (2026-08-08, live play-test then a backlog pass, main
  at `54774f3`, PUSHED, 12 commits): **P0-04 CONFIRMED** — a warm
  teleport ticked any travel sub regardless of where it landed; the jump
  must now land within `TELEPORT_ARRIVE_RADIUS` of `travelDestination()`,
  a shared helper so the jump path and the arrival path cannot disagree.
  Watched hold "Use mind bomb and camelot tele" from inside the ess mine.
  **GANGPLANK GATE** (still NEVER exercised): a docked ship's deck is
  inside the destination radius, so boat steps ticked aboard and SP routed
  from a tile with no path off. Boat subs need a gangplank crossing made
  NEAR THE DESTINATION — the crossing TILE is recorded because boarding at
  the far end crosses the same object. Release valve: no plank loaded
  within 8 tiles = nothing to cross = gate opens, so a route that lands
  you on the dock cannot wedge. There are SIX boat steps, not the five
  the backlog listed (`81b0064c8c` "Boat back to Karamja" was missed).
  **PRESCRIBED SPELL HINTS**: `PRESCRIBED_TRANSPORT` suppressed all hints
  for a sub naming its own transport — right for not suggesting an
  ALTERNATIVE, wrong for pointing at the spell the guide NAMED. Now
  resolves the named spell and highlights its widget with NO distance test
  (which also gets past the surface-band guard). Castability deliberately
  NOT required: "use mind bomb and camelot tele" means the real Magic
  level is under 45 BY DESIGN, and gating on it silenced the one step that
  most needs the prompt. Destination must sit either side of a tele word —
  "falador teletab", "...run back to Falador" and "Home tele to lumby...
  Varrock east bank" all correctly reject.
  **MIXED PURCHASE LISTS**: the goal parser returned on the first NUMBERED
  item, so "Buy candle, 2 fishing rods, lobster pot" gave ONE goal of
  three. Purchases now keep parsing comma siblings; +6 goals guide-wide,
  0 removed (the dorgeshuun crossbow and a chronicle were also being
  dropped). The audit caught two junk goals it introduced, needing
  OPPOSITE fixes: `premade blurb' sp` is real (item_ids 2028), a
  player-owned house is not an item ("player" -> NOT_AN_ITEM_FIRST_WORD).
  **PERSISTED ACQUISITION BASELINES** (`acqbase_<VARIANT>` in
  ProgressManager, mirroring the counted counters): they were session-only,
  so buying items then restarting re-based with the goods in hand and the
  step sat green-but-unticked forever. Downward rebase and untick-clears
  both kept. Does NOT rescue a step whose goals did not exist in an
  earlier session — no pre-purchase baseline to restore.
  **NEW FIELDS**: `ItemNeed.consumed` (spent during its own step — a true
  requirement excluded from the arrival gate, "(used here)"; the mind bomb
  would otherwise wedge its step), `Errand.hold` (this stage IS quest
  progress: clear the route rather than fight QH — OPT-IN because the
  blanket stand-down was tried and reverted).
  **QUEST-START SWEEP**: a start step with extra actions ticked on
  IN_PROGRESS. Swept all 33 `questStatus=start`; only 3 needed seeding
  (Merlin's `varp 14>=3`, PAR `varp 273>=20`, Holy Grail `varp 5>=3`,
  values from QH `steps.put`). The rest are already gated by item goals on
  the same sub, already checkpointed, or genuinely end at the start.
  Errand chains seeded for the two conversation steps (Gawain -> Lancelot
  upstairs, Merlin) with QH's exact `addDialogStep` strings — generic
  dialogue highlighting does NOT cover specific options.
  **PER-NPC ICONS**: the overhead icon was one shared value, so a
  two-shop step hung a fishing rod over the candle maker. `item_sources`
  vendors now wear their own stock. Catherby sources seeded from the SHOP
  pages (verified as shop pages whose stock lists the item).
  **SHOP OVERLAY** (`ShopItemHintOverlay`, groups 300/301): matches by
  NAME, because what you are there to BUY has no inventory id yet.
  **UX-01**: hand ticks recorded per profile (`manual_<VARIANT>`), per
  step AND sub, cleared on every untick path.
  **DATA**: 14 quest items marked `granted` — verified against each
  quest's wiki "Items required", where "(obtained during the quest)" is
  the discriminator; being LISTED is not enough. That overturned four in
  BOTH directions (priest gowns/pigeon cages ARE granted; phoenix feather
  and barronite deposit are genuine fetches). Karamja pin moved from the
  jungle (2843,3070) to Musa Point — ~120 tiles from any landing, the
  "ticked in the field" cause. Barcrawl card on the 10 drink steps.
  Pirate's Treasure `varp 71>=2`. Chronicle hint resolves worn vs carried.
  **THREE BACKLOG ITEMS WERE NOT DEFECTS**: P2-01 (the hunter shop step
  costs 12gp; the seeder skips <100gp on purpose and re-running applied
  0), P2-07 (QH tracks the message read with a conditional step on holding
  the item, NOT a var — the proposed checkpoint cannot exist), D3 sticky
  transport (the "wrong dock" cases are BOAT steps whose route the GUIDE
  names; the plugin never chose them).
  **TARGET DRIFT** (`tools/audit-target-drift.mjs`, built from an owner
  report): `targetFor` prefers a place name found in the STEP TEXT over
  the step's 📍 area, so a step with no ⌖ whose text merely MENTIONS a
  nav name gets that name's pin as its destination. "Put pineapples into
  the compost bin" (📍Catherby) resolved through the `compost` item_source
  at Vannah in Hosidius, ~1,900 tiles away, and the first-leg hint
  correctly offered a Tithe Farm teleport for a bin 20 tiles up the hill.
  36 steps drift >200 tiles; the audit excludes ⌖'d and errand-chained
  steps, and quest names (routing to the giver is designed). Fix per step
  is a ⌖ pin — capturable in game.
  **HUB PIN BUMPED to `3638c2f`** (owner's call, ending wave 12) — PR 14207 now builds the end of this
  wave; the never-exercised list in BACKLOG.md is the first place to look if a report comes in.
  **PROCESS**: `git add -p` is unavailable non-interactively — splitting
  one file across commits needs hunk patches (`git diff` -> split -> `git
  apply`), and the reassembled file must be diffed against the
  play-tested copy. Every commit was compile-verified in a throwaway
  `git worktree`, which is also how to build while a client is running.
- SESSION WAVE 11 (2026-08-08, live play-test, main at `105a0ff`, pushed):
  **BANK FILTER DEPOSITS — ROOT CAUSE WAS US.** Our `bankSearchFilter`
  callback answered **0 ("hide") for every slot**, and the decompiled
  `BankMainBuild.rs2asm` shows that answer is permission to lay the slot out
  AT ALL (`invoke 279 ~bankmain_filteritem` -> `if_icmpne` skips the slot
  before `cc_setobject`). A rejected slot never gets a widget, so widgets
  that existed when the filter came on survived (WITHDRAW looked fine) but a
  DEPOSITED item got none — nothing to move, so an unclickable ghost. Answer
  **1** instead; `BankMissingSection` already hides what it doesn't move,
  which is what makes the view clean. Every forced-rebuild attempt failed
  because the re-run asked the same callback again. CONFIRMED IN PLAY.
  Diagnostics: pass line carries `N/M widgets populated, via <trigger>`
  and `STALE`. **Two counts are NOT comparable** — a healthy bank runs ~30
  stacks ahead of its widgets (302/330 working), so `populated < container`
  fires constantly; STALE now means the narrow per-item thing (a ghost drawn
  for an item the container really holds, exact aliases only).
  **TOOL-01 BUILT** (`tools/audit-quest-granted.mjs`): reads
  `build/goal-audit.tsv` so it sees DETECTOR output the seeders never touch
  (the plague sample is a text goal — `cross-check-quest-kits` could never
  have caught it), checks each demanded item against QH's two lists
  (`getItemRequirements()` = bring, other `ItemRequirement`s = tracked
  in-quest). **Tradeability is the discriminator**; without it QH's short
  bring-lists flag rope/planks/buckets/coins. Caught P0-02 (`bark sample` on
  the Grand Tree start step) independently. 21 findings left, ALL on quest
  FINISHING steps = owner review, not defects.
  **NEW ANNOTATION FIELDS**: `ItemNeed.granted` (quest hands it to you —
  muted "(from the quest)", never routes, still lists and auto-ticks; a
  DETECTED goal inherits the flag through StepRow's merge, which is the only
  way to reach one); `travelVia` (the network stop a step means but never
  names — "Spirit tree to ardy" = Battlefield of Khazard; feeds
  `travelMenuWords`, NOT arrival proof); `Errand.given` (stage stands down
  once the item LEAVES your hands, and skips the usual cascade since
  hand-ins are independent).
  **BIOHAZARD**: 3-stage hand-in chain on the touch-paper step (Hops
  <-sulphuric broline, Chancy<-liquid honey, Da Vinci<-ethenea) at
  **Rimmington 2928,3220** — the wiki lists Chancy/Da Vinci at BOTH
  Rimmington and Varrock 3271,3388 (they carry the chemicals past the
  Varrock guards), and QH's step text describes the LATER visit. Owner beat
  QH on this. NO varp checkpoint: varp 68 stays **12** across the whole
  smuggling phase (measured in play) — wave 9's chain rule is the gate.
  Adding a checkpoint had OVERRIDDEN a case the plugin already handled.
  **HANDOFF** ("stop following Quest Helper"): never fired for anyone ever —
  gated on `isFrontierStep(step)` AFTER `advancePositionTo()` moved the
  frontier past it; uses the pre-mutation `atFrontier` now, and no longer
  needs `handedOffQuest`. Plus `QuestHandoffOverlay` (green viewport banner,
  ~18s, expires rather than needing dismissal) + `Notifier`, config
  `showHandoffBanner`. **GLYPH SWEEP**: the game font has no check
  mark/return arrow/crosshair/em dash — they render "?" (owner saw
  "IRONSCAPE: ? Start biohazard"). All chat messages ASCII + colour now.
  **DIALOGUE**: matching compares letters+digits only (exact match missed
  "Your quest." even when seeded — some seeded entries were probably failing
  silently); generic options ("Your quest", the quest name, "Talk about X")
  highlight with NO seeding while the quest is IN_PROGRESS. CONFIRMED.
  **TRAVEL NPCs** (P0-06) into `shop_npcs.json` (already the curated
  named-NPC roster): Captain Barnaby x3 (wiki: Ardougne/Brimhaven/Rimmington
  30gp), Trader Crewmember x4, Veos, Customs officer (TENTATIVE).
  CONFIRMED: Ardy docks -> Rimmington.
  **CHECKPOINT DIAGNOSTIC**: `checkpoint <sub>: varp 68=12 (need 14)` on
  change — QH's `loadSteps()` only lists values IT handles, so thresholds
  are guesswork without it.
  **UNCOMMITTED, compiles + tests pass, NOT play-tested**: P0-04 teleport
  destination proof — the travel-goal branch ticked any travel sub while a
  teleport was warm, so Brimstail's jump into the ess mine ticked "Use mind
  bomb and camelot tele" from 1,300 tiles away. Now the jump must land at
  the sub's destination (shared `travelDestination()` helper so the jump and
  arrival paths can't disagree).
  **PROCESS**: never run ANY gradle command while the dev client is live —
  `gradlew test` rewrote `build/classes` under it and the panel died with
  `NoClassDefFoundError: StepRow$SubRowUi` ("the whole guide disappeared").
  Never launch with a bare `&` — it detaches from tracking and left THREE
  clients running.

- SESSION WAVE 10 (2026-08-07, live play-test): **INSTANCED REGIONS** —
  every position read went through `Actor#getWorldLocation`, which inside
  a dynamic region returns THAT COPY's coordinates; `fromLocalInstance`
  appeared nowhere. The rune essence mine makes a fresh copy every 5
  entrants, so its `region: 11595` checkpoint compared against a
  different map and "Use Brimstails to go to ess mines" never ticked
  (the P0-01 the backlog blamed on wave 9's chain rule — chain blocking
  is per-step and was never involved). `playerPoint()`/`realPoint(actor)`
  now map to TEMPLATE coords; 20 of 30 sites converted, 4 deliberately
  left scene-local (they feed `LocalPoint.fromWorld` for rendering, or
  are scene-only nearest-object searches). CONFIRMED IN PLAY. Learned
  minigame regions are now template ids, so pre-wave-10 `minigamePresence`
  values go stale once and relearn.
  **SURFACE BAND**: the "dungeons live at y+6400" guard tested
  `|Δy| > 4000` and missed the essence mine at y≈4830 — from inside it
  the Gnome Stronghold read as 1,372 tiles away and a Barbarian Assault
  teleport won the first leg while the exit portal sat three tiles away.
  Now tests the BAND (`SURFACE_MAX_Y = 4000`) on both sides, in
  `firstLegTowards` AND `nearestOf` (which had picked the ZANARIS bank
  chest, y=4459, for a bank stop from inside the mine).
  **NEW ANNOTATION FIELDS**: `Target.npc:false` (a pin marks a place, not
  a person — a door pin was outlining a loitering gnome); top-level
  `dialog` (chat-option recolor for steps with no errand chain — it was
  errand-stage-only); `requires.equipped` (worn-only count in
  ItemTracker; checkpoint-exclusive and FRONTIER-ONLY, since worn state
  is reversible). equip/wear/wield stay out of the goal detector — only
  4 such clauses guide-wide, so it's seeding, not a detector change.
  **DATA**: ⌖ pins on SUB keys (the scraper regenerates step keys and
  would wipe them) for the Brimstail cave ENTRANCE 2403,3418 (+`npc:false`,
  +dialog) and Wizard Cromperty 2684,3323 (+dialog, string verified from
  QH source); items+`equipped` on the gas mask / ghostspeak steps, gloves
  on the nettles step; gas mask id 1506 (untradeable → no sprite without
  it); `BANKS` gained both Gnome Stronghold banks at **plane 1**.
  **SEEDING LESSON**: a wiki LOCATION page's `{{Map}}` pin is the
  building's ground entrance, not the thing inside — both gnome banks
  pinned at plane 0 that way, while the **Gnome banker** NPC page gave
  the real booths at plane 1. Same shape as Brimstail. For anything
  upstairs or underground, use the NPC/object page.
  **DIAGNOSTICS**: `logHintDecision` ("teleport-hint: ...") mirrors
  logNavDecision and names which of the five hint sources fired — a
  screenshot can't tell them apart; `+` add-place now prints the plane.
  A world-tile teleport marker is SHORTEST PATH's own transport
  suggestion, never ours (we only highlight widgets) — they can disagree.
  Also fixed a pre-existing `compileTestJava` break (GoalAuditDumpTest
  couldn't reach a package-private PlaceManager ctor) that blocked the
  whole test task.

- KIT-SEEDING POLICY (owner, 2026-08-05): quest-kit annotation items
  carry TRUE REQUIREMENTS always; items a quest hands you MID-QUEST
  never (they sit permanently red — misinformation); "nice to have"
  convenience items ONLY when cheaply acquirable by an ironman at that
  route position — the guide's earlier steps already produced one, or
  QH/wiki shows a trivial nearby source (then also consider an
  item_sources entry so it's click-to-acquire). Required/recommended
  labels don't decide; acquisition COST does. (Tribal Totem's Amulet
  of glory stays OUT under this rule.)

- The ONLY guide is **GuideVariant.OZIRIS** — Ironman Efficiency Guide
  v4, community "Enhanced 2026" edition from https://ironman.guide/,
  scraped by `tools/scrape-oziris.mjs` (575 steps, 7 sections). The
  site's React flight payload carries author-structured steps (stable
  ids like "1.1.76a", location, quest+questStatus, skillGoal, items[],
  note, hcim, links, enhanced:true flags) — the scraper emits our guide
  JSON plus `annotations_oziris.json` (82 skill/item annotations; hand-
  authored keys survive re-scrapes). Progress key `progress_OZIRIS`.
- Guide is ~1000 steps across three chapters. Panel performance matters —
  don't naively render every step as a live Swing component at once.

Pending work is tracked in BACKLOG.md. Screenshots referenced as SS-NN are in docs/screenshots/.
Run the INV-01..INV-03 investigation tasks before starting implementation.
