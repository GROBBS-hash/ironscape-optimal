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