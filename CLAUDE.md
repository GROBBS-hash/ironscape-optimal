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
