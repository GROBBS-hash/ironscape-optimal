# Ironscape Optimal — Backlog

> **Naming:** repo was renamed from `BRUHsailer` to Ironscape Optimal. Confirm exact casing/slug and correct any
> stale references in this file, `CLAUDE.md`, and the Gradle project name. The plugin already emits under
> `IRONSCAPE:` at runtime (see SS-01 chat log).

Captured from a live play session (Gnome Stronghold → Grand Tree → Karamja → Pirate's Treasure, steps ~205–220).
Screenshot references are `SS-01` … `SS-20`, stored in `docs/screenshots/`.

**How to use this file:** run the investigation tasks first — they're cheap and they change what gets built.
Then work top-down within each priority tier. Each item states what to change, why, where to look, and how to know
it's done.

**Read the cross-cutting themes first.** Roughly half the individual bugs below are symptoms of four underlying
gaps. Fixing the theme fixes the symptoms; fixing symptoms one at a time will not converge.

---

## Investigation tasks — do these first

These are counts/dumps against the repo and guide pack. Each one gates a build decision. Report the number, don't
act on it yet.

### INV-01 — Split-quest count
**Query:** In the loaded guide pack, how many **distinct quests are referenced by more than one step**?
Also report the total distinct quest count.

**Why:** Quests referenced by a single step can be seeded from `Quest.getState(client)` alone — free, no data entry.
Quests split across multiple steps need hand-entered varp stage thresholds. This number is the entire cost of
D1 / P0-03.

**Decision it gates:** If the split count is small (≲20), hand-enter stage thresholds for all of them. If large
(≳60), scope down — seed only `NOT_STARTED` / `FINISHED` initially and fall back to manual checkboxes for
partial progress.

---

### INV-02 — Compound step classification
**Query:** Of the steps that describe more than one action, classify each as:
- **(a) Pure quest progression** — text is essentially "continue/do quest X until Y" with no other actions
- **(b) Action chain** — genuine multi-location/multi-object sequence (travel → talk → travel → interact)
- **(c) Mixed** — both, e.g. SS-12 "Go to Hazelmere and continue the grand tree until you are at Karamja shipyard"

Report counts for each, and list the (b) and (c) steps.

**Why:** Type (a) needs **zero authoring** — it delegates to a quest stage range. Only (b) and the non-quest
portions of (c) need hand-authored substeps. This number is the entire cost of P1-06.

**Decision it gates:** Whether substep authoring is an afternoon or a multi-week data-entry project.

---

### INV-03 — Transport node dump for the Karamja legs
**Query:** For the route that produced SS-15 (barcrawl step routing via Rimmington), dump the candidate transport
nodes the router considered and the cost assigned to each. Do the same for the preceding "Boat back to Karamja"
leg that chose Brimhaven.

**Why:** Two very different faults produce this symptom. Either (i) the router legitimately costed Rimmington as
cheapest — a policy problem — or (ii) there are mis-tagged/phantom transport nodes — a data problem. Policy tuning
cannot fix a data problem.

**Decision it gates:** D3. See that decision for both branches.

---

### INV-04 — Run the existing arrival-resolution audit
**This tooling already exists — do not rebuild it.** `tools/audit-arrivals.mjs` + `GoalAuditDumpTest`.

**Run:**
```
gradlew test --tests "*.GoalAuditDumpTest"     # writes build/arrival-audit.tsv
node tools/audit-arrivals.mjs                  # Tier 1 only
node tools/audit-arrivals.mjs --all            # Tier 1 + Tier 2 + NONE
```

**Report:** the Tier 1 count (PIN subs whose step shares its 📍 tag with the *previous* step — origin-anchored,
the SS-01 class), the Tier 2 count (remaining PIN subs, often correct), and the NONE count (nothing resolves,
manual tick only).

**Why:** Tier 1 is the definitive list of steps affected by P0-01. NONE is the definitive list of steps that
*cannot* auto-complete at all and therefore rely entirely on the user ticking them by hand. Both numbers size real work.

**Caveat:** this tool was mid-development when the session ended. Verify it runs before trusting the counts.

---

## Resolved decisions

### D1 — Quest state: direct varbit/varp reads, three-tier
**Resolved:** Direct reads. Do **not** take a code dependency on Quest Helper.

**Rationale:** RuneLite's plugin hub builds each plugin against the RuneLite API only; hub plugins generally cannot
declare other hub plugins as Gradle dependencies. **Verify this against current hub submission rules before
building on it** — but if it holds, the decision is forced regardless of design preference.

**Tiering** (maps to the existing three annotation tiers):
1. **Tier 1 — free:** `Quest.getState(client)` → `NOT_STARTED` / `IN_PROGRESS` / `FINISHED`. Covers every quest the
   guide references from a single step. No data entry.
2. **Tier 3 — hand-entered:** quest varp value + stage thresholds, per quest, from the wiki. Only needed for the
   quests INV-01 identifies as split across multiple steps.
3. **Fallback — manual checkbox:** for anything not worth mapping, or not yet mapped.

**Note:** Suppressing our own navigation in favour of Quest Helper (see the substep delegation contract below)
requires **no code dependency** — we are suppressing ourselves, not calling into QH. This is why the delegation
design is compatible with this decision.

---

### D2 — Requirement inheritance: flip the default to inherit-nothing
**Resolved:** Steps inherit **no** requirements by default. Requirements are attached explicitly, at substep
granularity where substeps exist.

**Rationale:** The current default (inherit the quest's full item list) produces **unsatisfiable** states — SS-04/05
demands `Bark sample 0/1` at quest start, an item only obtainable during the quest. A permanently-red panel trains
users to ignore requirements entirely, which wastes every correct warning the plugin will ever show.
Under-warning is a cheap failure (user walks back to a shop); over-warning breaks the feature.

**Step intent** (`START` / `CONTINUE` / `FINISH`) is inferred from step text as a convenience default, with
per-step annotation override. With inheritance flipped off, intent classification is no longer load-bearing for
correctness, so text inference being imperfect is acceptable.

**Companion work — see TOOL-01.** Do not hand-audit 700+ steps.

---

### D3 — Route consistency: diagnose before deciding
**Resolved:** Blocked on INV-03. Two branches:

**If INV-03 shows bad/phantom transport nodes** → data fix. Correct the nodes; no policy change. Cost-function
tuning cannot fix this and would mask it.

**If INV-03 shows a legitimate cost decision** → implement **sticky transport**, not multi-step lookahead:
when the router selects a transport to reach a landmass, record it and apply a cost discount to its reciprocal on
the return leg. Handles the observed Brimhaven-out/Rimmington-back inconsistency in ~a dozen lines. A true
multi-leg optimiser is disproportionate machinery for one symptom.

**Principle:** prefer consistency with a cost tiebreak. Users navigate by mental map; a cheaper but incoherent
route is worse than a slightly costlier coherent one.

---

## Cross-cutting themes

| Theme | Symptom items | Core problem |
|---|---|---|
| **T1 — Requirement scoping** | P0-02, P1-05, P1-06 | Steps don't distinguish *start* / *continue* / *finish*, and inherit whole-quest item lists. Resolved by D2 + substeps. |
| **T2 — Quest state seeding** | P0-03, P0-04, P1-05 | No authoritative read of quest stage, so pre-existing progress isn't recognised. Resolved by D1. |
| **T3 — Transport node selection** | P0-06, P1-01, P1-04 | Router picks wrong transport and doesn't model NPC-operated travel. Gated on INV-03 / D3. |
| **T4 — Overlay parity with Quest Helper** | P2-01 … P2-07 | QH's highlighting (containers, doors/stairs, teleport menus, shop items) is materially better; ours is inconsistent. |

---

# DESIGN — Substep architecture (P1-06)

**This is the keystone item. Build it before most of the P0 tier** — it changes the shape of those fixes.

## Why it comes first

Once requirements and completion predicates attach at *substep* granularity rather than *step* granularity,
several separately-filed bugs dissolve rather than needing individual fixes:

| Item | How substeps resolve it |
|---|---|
| **P1-05** (Blue Moon key not listed) | Key attaches to the "open chest" substep. Panel can distinguish *needed now* from *needed later in this step*. |
| **P2-07** (message completes on pickup, not read) | "Read message" becomes its own substep with its own predicate. |
| **P1-03** (Karamja completes at field, not docks) | Final substep is *arrive at docks*, not *arrive on landmass*. |
| **P0-02** (Grand Tree item spam) | Bark sample attaches to the substep that consumes it — inside a delegated quest range — so it never surfaces at quest start. |
| **P0-05** (QH steals/loses nav control) | Replaced entirely by the delegation contract below, which has a defined automatic end condition. |

## The core insight — two kinds of substep, don't conflate them

**Type A — authored action chains.** Static, known in advance, each with a concrete completion predicate.
Example: travel to Hazelmere → talk to Hazelmere → travel to Karamja shipyard.

**Type B — quest progression.** The internal steps are the *quest's own stages*, which the game already tracks in
a varp. **Never author these.** Transcribing them is hundreds of steps of data entry that the game provides for
free, and it rots every time Jagex touches a quest.

SS-12 decomposes as:

```
Step 218 — "Go to Hazelmere and continue the grand tree until you are at Karamja shipyard"
  218.1  TRAVEL       → Hazelmere                        [Type A, authored]
  218.2  QUEST_RANGE  → Grand Tree, stages N→M           [Type B, delegated — zero authoring]
  218.3  ARRIVE       → Karamja shipyard                 [Type A, authored]
```

One authored travel substep, one delegated range, one arrival predicate. INV-02 tells you how often this pattern
holds across the pack.

## The delegation contract (replaces P0-05)

While a `QUEST_RANGE` substep is active:

- Ironscape Optimal renders **no navigation at all** — no path, no highlights, no destination marker
- The panel states what is happening and what will end it:
  *"Quest Helper is guiding this section — resumes at Karamja shipyard."*
- Ownership returns **automatically** when the quest varp reaches the declared exit stage
- Region changes, teleports and zone transitions **do not** reset ownership (this is the specific P0-05 bug)

**Why this is better than the P0-05 stopgap:** "QH owns nav until the user dismisses it" has no defined end
condition. A stage-range contract has an automatic one.

**No QH dependency required.** We are suppressing ourselves, not calling into Quest Helper. Consistent with D1.

**Degradation when QH is absent:** show the wiki stage text for the range plus a link. The range still completes
on the varp threshold, so progression is unaffected.

## Data model

```
Substep
  id              parent-scoped, stable        e.g. 218.2
  kind            TRAVEL | ACTION | INTERACT | ARRIVE | QUEST_RANGE
  target          npc | object | location | transport node   (null for QUEST_RANGE)
  quest_range     { quest, entry_stage, exit_stage }         (QUEST_RANGE only)
  requirements    items needed *for this substep only*
  completes_when  predicate: arrival | dialogue | varp | inventory | object_interaction
```

**Stable IDs are load-bearing.** The annotation overlay keys against them. They must not shift when a guide pack
updates and inserts or removes a step — do not use array position.

> **⚠ RISK — resolve before building substeps.**
> `tools/audit-arrivals.mjs` shows `GuideLoader` derives step IDs by **hashing the step's normalised text**
> (`sha256(text).slice(0,10)`, with a `-2` suffix for duplicates). Text-derived IDs are stable across re-scrapes
> *only while the wording is unchanged*. Any upstream edit to a step's text silently orphans its annotations —
> and substeps would inherit that fragility.
>
> Meanwhile `CLAUDE.md` notes the source site's payload already carries **author-assigned stable IDs**
> (e.g. `"1.1.76a"`). If those are genuinely stable upstream, keying off them is strictly better than hashing text.
>
> **Investigate:** are the site's authored IDs stable across scrapes? If yes, migrate keying to them (with the
> text hash as a fallback for steps lacking one). If no, keep hashing but add a re-scrape diff report that flags
> orphaned annotation keys. Either way, decide **before** substep IDs are minted on top of the existing scheme.

## UX rules — in priority order

These exist to make the panel followable without the user having to work out which line applies to them.

1. **One live thing at a time.** Current substep rendered bright; parent step dimmed above it for context;
   remaining substeps collapsed. Never two active highlights.
2. **Never reflow while active.** No renumbering, no reordering, no list jumping under the cursor as state changes.
3. **Make completion visible.** Tick plus a brief highlight flash, so the user sees *why* it advanced. Silent
   advancement is how P0-04-style false completions go unnoticed.
4. **Every substep independently tickable.** Per-step manual override already exists and persists; substeps
   must not regress this to all-or-nothing. See UX-01.

## Panel sketch

```
Step 218 — Go to Hazelmere, continue Grand Tree to Karamja shipyard
  ✓ Travel to Hazelmere
  ▶ Grand Tree — Quest Helper guiding        [2 of 4]
      resumes automatically at Karamja shipyard
    ○ Travel to Karamja shipyard
    ○ Board charter to Musa Point
                                          [◀ back]  [skip ▶]
```

---

### UX-01 — Distinguish manual overrides from auto-completions
**Priority: P2.** Small diagnostic improvement.

**Already implemented — do not rebuild:** users can tick and un-tick any step manually, and un-ticking a falsely
auto-completed step **persists** (the detector does not immediately re-fire and overwrite it). The override
mechanism works.

**What's left:** render manually-overridden steps differently from auto-detected ones — a distinct colour, icon,
or marker in the side panel and the persisted progress state.

**Why it's worth doing:** an override is a signal that detection failed on that step. If overrides are
distinguishable in the saved progress file, a user's progress becomes a free bug report — it names exactly which
steps the detector is getting wrong, without the user having to write anything up. Complements INV-04, which finds
the same class of problem statically.

**Constraint for the substep work:** when substeps land, **each substep needs its own independent tick/un-tick**.
Otherwise compound steps regress to all-or-nothing override, which is worse than the current per-step behaviour.
Fold this into P1-06 rather than treating it as separate work.

**Done when:** manual and automatic completions are visually distinct in the panel and separable in the progress
file.

---

### TOOL-01 — Requirement satisfiability validator
**Priority: P0.** Companion to D2. **Do not hand-audit 700+ steps.**

**What:** A validation pass over the guide pack. For each step/substep, check whether its requirements are
satisfiable at the moment that step becomes active, given what prior steps grant (purchases, quest rewards,
pickups). Print every unsatisfiable case.

**Why it matters:** Turns a 700-step manual audit into a list of perhaps ~30 genuine failures. More importantly it
becomes a **regression test** — re-runnable whenever the guide pack updates, catching the SS-04/05 class of bug
before a user ever sees it.

This is probably the highest-leverage item in the backlog relative to its size.

**Done when:** The validator runs from Gradle, flags the known Grand Tree bark-sample/translation-notes case, and
exits non-zero on any unsatisfiable requirement.

**Open question:** Does the guide pack encode what a step *grants*, or only what it *requires*? If only the latter,
the validator needs a grants field added — cheap, but it's schema work.

---

## P0 — Correctness blockers

### P0-01 — Step completion detection fires late on teleport arrival
**Screenshots:** SS-01 (unticked, already inside ess mine), SS-02 (ticked, after moving on)

**What:** Step 205 *"Use Brimstails to go to ess mines"* did not tick on arrival. It ticked later, by SS-02.

**Root cause — already diagnosed. Do not re-investigate.** `tools/audit-arrivals.mjs` (uncommitted at time of
writing; see Notes) documents the mechanism in its header comment:

> Arrival would anchor on the step's 📍 tag FALLBACK ("PIN") — the *"go to ess mines"* class, where the tag names
> the **ORIGIN** and the step false-ticks the moment its item gate opens.

So the step's location tag points at where the player *starts*, not where they're going. Arrival resolution finds
no destination, falls back to the pin, and the pin is already satisfied — so completion is gated only by the item
check, and fires when that opens rather than on actual arrival.

**This supersedes the earlier "region-change polling latency" hypothesis, which was wrong.**

**Fix candidates** (from the same header): region checkpoint; a ⊕ marker at the true destination; or a
`places.json` entry for the destination named in the step text.

**Done when:** Arrival steps tick on entering the target area, verified for (a) Brimstail teleport, (b) spirit
tree, (c) charter boat, (d) fairy ring. Re-run INV-04 and confirm the Tier 1 count drops to zero.

**Related:** P0-04 is plausibly the same family — a false tick when the gate opens rather than on the named action.
Check whether the Chronicle case appears in the audit output before treating it as a separate bug.

---

### P0-02 — "Start quest" steps demand full-quest item requirements
**Screenshots:** SS-04, SS-05 · **Resolved by:** D2 + substeps + TOOL-01

**What:** Step 206 is *"Start Grand tree"*. Requirements list `Gp 2,966/1`, `Bark sample 0/1`,
`Translation notes 0/1`, plus runes and misc. Bark sample and translation notes are **obtained during the quest** —
unsatisfiable at start.

**Done when:** *"Start Grand tree"* shows zero or minimal requirements; mid-quest items appear only on the substeps
that consume them; TOOL-01 passes.

---

### P0-03 — Quest state is not seeded for pre-existing progress
**Screenshots:** SS-16 (step unticked), SS-17 (QH panel showing prior stages already complete) · **Decision:** D1

**What:** *"Do Karamja and port sarim parts of the Pirate's treasure quest"* is unticked, but those parts were
completed before the plugin was installed. SS-17 shows Quest Helper correctly greying out *Talk to Redbeard Frank*,
*Rum smuggling* and *Back to Port Sarim*, with *Discover the treasure* live.

**Why it matters:** Any account not starting from a fresh tutorial-island state gets told to redo content. This is
the single biggest credibility problem for the plugin — it makes it unusable for existing ironmen.

**Done when:** Loading the guide on the existing account (292 QP) auto-ticks all already-completed quest steps.

**Gated on:** INV-01 for the tier-3 scope.

---

### P0-04 — Steps auto-complete on unrelated actions
**Screenshot:** SS-06

**What:** *"Clan wars minigame tele, recharge energy and go to castle wars"* auto-completed when the user used a
**Chronicle** teleport. Wrong destination, wrong action.

**Why it matters:** Silent false completion skips content, and the user doesn't notice — worse than a missed
completion.

**Fix shape:** Completion predicates must assert the *specific* transport/destination, not a category. Add a
regression case: "unrelated teleport must not complete step."

---

### P0-05 — Quest Helper navigation handoff
**Superseded by the substep delegation contract.** See the DESIGN section.

If substeps slip, the stopgap remains: an explicit navigation ownership token that survives region changes. But
build the delegation contract instead if at all possible — it has a defined end condition and this doesn't.

---

### P0-06 — NPC-operated travel not offered on travel steps
**Screenshot:** SS-14

**What:** Step *"Take boat to Port Sarim"*. The NPC is icon-marked but offers no travel/charter interaction.
Note the tooltip reads `Attack Foreman (level-23)` — **the highlighted NPC appears to be the wrong entity entirely.**

**Check git history first** — the user reports this previously worked, so it may be a regression rather than a
gap. Cheaper to find the breaking commit than to rewrite the transport table.

**Done when:** The step highlights the correct travel NPC and the correct menu option.

---

## P1 — Functional gaps

### P1-01 — No route out of the Essence Mine
**Screenshots:** SS-02, SS-03 (`Destination could not be reached`)

Transport graph is missing the ess mine exit portal and tunnel as edges. Correct route: portal inside the mine →
tunnel/cave exit → Gnome Stronghold.

**Done when:** Pathing from inside the ess mine to King Narnode resolves.

---

### P1-02 — Router navigates to wrong bank / wrong destination
**Screenshots:** SS-04, SS-05

Router sent the player to the **Fishing Guild bank** while they stood directly beneath the **Tree Gnome Stronghold
bank**, for a step whose target (King Narnode) was a few tiles away.

**Retest after P0-02.** The bank detour is probably a *consequence* of the bogus requirement list — with nothing to
buy, there's no reason to route to a bank at all. Fix requirements first, then see whether a routing bug remains.

---

### P1-03 — Step completes on region entry rather than at the actual destination
**Screenshot:** SS-13 · **Resolved by:** substeps (final substep = `ARRIVE docks`)

*"From Karamja shipyard, charter to Karamja, Musa point"* completed on entering the Karamja field; the docks are
still a dialogue and a short walk away.

---

### P1-04 — Wrong port chosen for return travel, inconsistent with outbound
**Screenshot:** SS-15 · **Gated on:** INV-03 / D3

*"Barcrawl from Karamja bar"* routed via **Rimmington**; the preceding *"Boat back to Karamja"* used the
**Brimhaven** boat rather than the charter to Musa Point. The two legs disagree about which port is in play.

---

### P1-05 — Steps don't verify their own prerequisites
**Screenshots:** SS-09 (Yanille pub), SS-19 + SS-20 (Blue Moon Inn) · **Resolved by:** substeps + D2

- *"Get a drink for barcrawl from Yanille pub"* — navigation correct, but no check for the **barcrawl card**
  (no card = no stamp) or **coins** to pay.
- *"Get pirate's message from Blue moon inn"* — navigates to the inn but not to the chest, doesn't explain how to
  obtain the message, and **the key is not listed as a requirement**. SS-20 shows QH listing the chest/key sequence
  correctly.

---

### P1-06 — Substep system
**See the DESIGN section above.** Gated on INV-02 for scope.

---

## P2 — Overlay & UI parity (Theme T4)

### P2-01 — GP cost not shown on purchase steps
**Screenshots:** SS-10 (no cost shown), SS-19 (`Coins 2,495/1,000` — renders correctly)

**This is a data gap, not a missing feature.** SS-19 proves the coins-requirement widget already works. The
annotation for the hunter-shop step simply has no cost attached. **Do not rewrite the widget.**

**Done when:** Purchase steps show a GP icon and required amount. Audit all shop steps for missing cost
annotations — fold this into TOOL-01 if practical.

---

### P2-02 — Shop interface items not highlighted
**Screenshot:** SS-11 (Aleck's Hunter Emporium)

Required purchases should carry the same cyan highlight square used for inventory items.

---

### P2-03 — Teleport menu entries highlighted inconsistently
**Screenshots:** SS-07 (Battlefield of Khazard correctly highlighted), SS-08 (Spirit Tree Locations dialog — target
not highlighted)

**Done when:** Every teleport selection interface — spirit tree, fairy ring, minigame teleport, Chronicle, jewellery
— highlights the target entry. Enumerate and test each.

---

### P2-04 — Overlay points to the wrong interface tab
**Screenshot:** SS-18

*"Chronicle tele"* highlights the **equipment** tab; the Chronicle is in the **inventory**. Resolve the item's
actual container at render time rather than assuming a fixed tab per item, and update if the item moves.

---

### P2-05 — Add scene-object outlines for doors, stairs, ladders
**Screenshots:** SS-19, SS-20 (QH reference behaviour)

QH outlines traversal objects in cyan; we don't. On multi-level navigation (Blue Moon Inn upstairs → chest) the
user has no indication of what to click.

---

### P2-06 — Match Quest Helper's target icon styling
**Screenshot:** SS-19

Cosmetic, low priority, taste-dependent. Get a specific side-by-side comparison before changing anything.

---

### P2-07 — Pirate's message completes on pickup rather than on read
**Screenshot:** SS-19 · **Resolved by:** substeps

Step text is explicit: *"you can drop it AFTER reading the message"*. Completion fired on taking the message from
the chest. Should fire on the read action / resulting varbit change.

---

## Suggested execution order

**Phase 0 — investigate (cheap, gates everything)**
1. **INV-04 arrival audit** — run first; the tooling already exists and it sizes P0-01
2. INV-01 split-quest count
3. INV-02 compound step classification
4. INV-03 transport node dump
5. Verify the plugin-hub dependency rule underpinning D1
6. Resolve the step-ID stability risk in the DESIGN section — gates substeps

**Phase 1 — foundations**
7. **TOOL-01** satisfiability validator — small, and it turns D2 from an audit into a test
8. **P1-06** substeps per the DESIGN section — the keystone. Include per-substep tick/un-tick (see UX-01 constraint)

**Phase 2 — correctness, now cheaper**
9. P0-03 quest state seeding (D1)
10. P0-02 requirement scoping (D2) → then retest P1-02
11. P0-01 + P0-04 completion predicates — same subsystem, do together
12. P1-03, P1-05, P2-07 — should mostly fall out of substeps; verify rather than rebuild

**Phase 3 — transport graph**
13. P0-06 (check git history first), P1-01, P1-04 per D3 branch

**Phase 4 — overlay parity**
14. P2 tier — largely independent, good filler work

---

## Notes for the next session

- Screenshots need saving to `docs/screenshots/` as `SS-01.png` … `SS-20.png` before this file is useful to anyone
  but the author.
- Three items are **regressions or data gaps, not missing features** — P2-01 (widget exists), P0-06 (previously
  worked), P0-01 (trigger exists, fires late). Check git history before writing new code on any of them.
- The test account has significant pre-existing quest progress (292 QP) — ideal for testing P0-03, poor for testing
  fresh-account step ordering. A second clean account may be needed for guide-order regression testing.

## Guide pack specifics

Per `CLAUDE.md`: the only guide is `GuideVariant.OZIRIS` — Ironman Efficiency Guide v4, "Enhanced 2026" edition
from ironman.guide, scraped by `tools/scrape-oziris.mjs`. Relevant files:

- `src/main/resources/com/ironscape/guide/guide_data_oziris.json` — 575 steps, 7 sections
- `annotations_oziris.json` — 82 hand-authored skill/item annotations
- Progress key: `progress_OZIRIS`

**INV-01 and INV-02 should be run against these two files.** (The earlier "700+ steps" figure in this backlog was
an estimate; 575 is the real count.)

## Work in progress at session end

Two files were uncommitted when the weekly quota ran out:

- `tools/audit-arrivals.mjs` — arrival-resolution audit. **Substantially complete and already valuable** — its
  header comment is the source of the P0-01 root cause. See INV-04.
- `src/test/java/com/ironscape/goals/GoalAuditDumpTest.java` — modified; writes `build/arrival-audit.tsv`, which
  the above script consumes.

Neither had been verified end-to-end. Confirm the Gradle test runs and produces the TSV before relying on the
audit output.
