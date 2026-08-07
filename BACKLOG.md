# Ironscape Optimal — Backlog

Captured from a live play session (Gnome Stronghold → Grand Tree → Karamja → Pirate's Treasure, steps ~205–220).
Screenshots `SS-01` … `SS-20` in `docs/screenshots/`.

> **RECONCILED against CLAUDE.md session waves 1–9.** The original draft of this file was written from the play
> session alone, without the session log, and over-scoped badly — it proposed building several systems that already
> exist. Every item below now carries a **Prior work** line. **Read that line before writing any code.**
>
> The single biggest correction: **the substep system already exists.** It is called **errand chains**
> (annotation `errands`, waves 4–9). Do not design a new one.

---

## Reconciliation summary — what the session log already covers

| Original assumption | Reality per CLAUDE.md |
|---|---|
| Substeps need designing and building | **Errand chains already do this** — ordered stages with coords, items, NPCs, dialog, per-stage radius, route/satisfaction split, varbit/varp gates, waypoint stages, sticky first-unsatisfied, chain-complete = nav HOLD |
| Quest state seeding is a new system | **Mid-quest varbit/varp checkpoint annotations exist** and override heuristics entirely (wave 6). `cachedQuestState` exists (wave 7). Partial-quest cases are *seeding*, not architecture |
| Requirement scoping is an undecided policy | **KIT-SEEDING POLICY exists** (owner, 2026-08-05). Mid-quest-handed items never carry requirements. The policy is written; enforcement is the gap |
| GP cost display is missing | **`seed-gp-costs.mjs` exists**, 30 buy steps seeded (wave 7). Hunter shop simply isn't among them |
| QH steals navigation on zone change | **`questHelperOwnsGuidance` deliberately routes mid-quest nav to the step's 📍 area.** Working as designed; the design is wrong for compound steps |
| No validator tooling exists | **Six audit tools exist**: audit-goals, audit-nav, audit-shops, audit-drops, audit-arrivals, cross-check-quest-kits, plus GoalAuditDumpTest |
| Step-ID stability is an open risk | **GuideManifest v2 already remaps ids** across upstream edits via sub-clause fingerprints. Largely mitigated |

---

## RESOLVED — live play-test session 2026-08-07

INV-A ran, and its answer invalidated a chunk of this file. Recorded before the details evaporate.

**Root cause of P0-01: instanced regions, not the chain rule.** The rune essence mine spawns a per-party
copy every 5 entrants (J-Mod quote on the wiki). In a dynamic region `Actor#getWorldLocation` returns the
COPY's coordinates, so the `region: 11595` checkpoint compared against a different map and never fired.
The plugin called `getWorldLocation()` at 30 sites and `WorldPoint.fromLocalInstance` at **none**. Fixed with
`playerPoint()` / `realPoint(actor)`, applied to 20 of those sites — the other 4 stay scene-local on purpose
(they feed `LocalPoint.fromWorld` for rendering, or are scene-only nearest-object searches).
**Confirmed in play:** `auto-completed sub 06b3df5fd7:0 (quest checkpoint (varbit/varp/region))`.

Two premises in the original INV-A were wrong: `2409,9812` is Brimstail's cave, not a mine tile, and the
Enter-the-Abyss chain is on a DIFFERENT step (`29c5dcb92e`), so wave 9's CHAIN RULE was never involved —
chain blocking is per-step (`currentSubSatisfied` → `unsatisfiedErrandStage(step, sub)`).

Also fixed and confirmed:
- **P1-01** (no route to/from the ess mine): ⌖ pins seeded at the cave ENTRANCE `2403,3418` and at Wizard
  Cromperty `2684,3323`, both on SUB keys — the scraper regenerates step keys and would wipe them.
- **Distance fiction beyond dungeons.** The `firstLegTowards` guard tested `|Δy| > 4000` for the y+6400
  dungeon band, but the essence mine sits at y≈4830: from inside it the Gnome Stronghold read as 1,372
  tiles away, so a Barbarian Assault teleport "won" the first leg while the exit portal was three tiles
  away. Now tests the surface BAND (`SURFACE_MAX_Y = 4000`) on both sides. Same fix applied to `nearestOf`,
  where it had sent a bank stop to the **Zanaris** chest (y=4459, "370 tiles away") from inside the mine.
- **`BANKS` had no Gnome Stronghold entry** — a stronghold bank stop routed to the Fishing Guild. Added
  both, at **plane 1**.
- **Equip steps tracked nothing.** `equip`/`wear`/`wield` are in `NOT_AN_ITEM_FIRST_WORD` by design, and the
  whole guide has only 4 such clauses, so this is seeding, not a detector change. Added an `equipped`
  annotation requirement (worn-only count in `ItemTracker`, frontier-only since worn state is reversible)
  plus item annotations. Gas mask is untradeable → id 1506 added to `item_ids.json` for the sprite.
- **`Target.npc: false`** — a pin on a door nominated the nearest NPC to it and outlined a random gnome.
- **`dialog` generalised** from errand stages to any step/sub annotation.

**New diagnostics:** `logHintDecision` ("teleport-hint: ...") mirrors `logNavDecision`; `+` add-place now
prints the plane. Both already flow into `mine-session-log.mjs`.

**Seeding lesson worth keeping:** a wiki *location* page's `{{Map}}` pin is the building's ground entrance,
not the thing inside it. Both gnome banks pinned at plane 0 that way; the **Gnome banker** NPC page gave the
real booths at plane 1. Same shape as Brimstail (entrance ≠ destination). For anything upstairs or
underground, use the NPC/object page.

---

## Investigation tasks — do these first

### INV-A — Why didn't the ess-mine step tick? (P0-01)
**ANSWERED — see the RESOLVED section above. P0-01 and P1-01 are closed.**

**Leading hypothesis (WRONG, kept for the record): wave 9's CHAIN RULE, landed the same morning.**

Wave 9 made an unsatisfied errand chain block the sub's item-goal ticks — seeded specifically for the scrying orb
(`Enter-the-Abyss orb`: Varrock mage 3259,3383 → Aubury route / essence-mine **~2920,4830** satisfaction).

SS-01's chat log shows the player at **2409,9812** — an essence-mine interior tile. If the chain's satisfaction
coordinate or radius doesn't cover where the player actually lands, the chain never satisfies and the step cannot
tick. The fix that stopped the *premature* tick may now be causing a *missed* one.

**Check:** the Enter-the-Abyss chain's satisfaction stage coords/radius vs the real arrival tile. Note the
route/satisfaction split exists precisely for this (SP can't path into interiors) — verify both halves.

**Secondary hypothesis:** `tools/audit-arrivals.mjs` header describes origin-anchored 📍 PIN fallback — the
*"go to ess mines"* class named explicitly. Run INV-B; if the step appears in Tier 1, both faults may be present.

---

### INV-B — Run the existing arrival audit
**Tooling exists. Do not rebuild.**
```
gradlew test --tests "*.GoalAuditDumpTest"     # writes build/arrival-audit.tsv
node tools/audit-arrivals.mjs                  # Tier 1
node tools/audit-arrivals.mjs --all            # + Tier 2 + NONE
```
Report Tier 1 (origin-anchored PIN arrivals — the SS-01 class), Tier 2, and NONE counts.

---

### INV-C — Which compound steps need errand chains?
Guide is `GuideVariant.OZIRIS`, 575 steps, 7 sections
(`src/main/resources/com/ironscape/guide/guide_data_oziris.json` + `annotations_oziris.json`, 82 annotations).

List steps describing a multi-leg journey **not** already covered by an `errands` chain — SS-12
(*"Go to Hazelmere and continue the grand tree until you are at Karamja shipyard"*) is the exemplar.
`audit-nav` already reports 169/575 uncovered; cross-reference rather than starting fresh.

**Note:** the Grand Tree shipyard checkpoint (**varp 150 >= 80**) is *already seeded*. So SS-12 has a
completion checkpoint but no chain to navigate the legs. That's the shape of the gap.

---

### INV-D — Charter/boat network selection
For SS-15 (barcrawl routed via Rimmington) and the preceding leg (Brimhaven rather than charter to Musa Point):
dump which `CHARTER_DOCKS` node was selected and why.

`CHARTER_DOCKS` is a real 8-dock network with spirit-tree treatment (wave 7), so this is **selection policy, not
phantom data** — D3's data branch is effectively ruled out. Confirm, then see D3.

---

### INV-E — Where do "Start Grand tree" requirements come from?
Wave 3 moved quest kits to the step that **finishes** the quest, and SS-19 confirms it (*"Finish The Grand tree —
Coins 2,495/1,000"*). Yet SS-04/05 show the **start** step demanding `Bark sample 0/1`, `Translation notes 0/1`,
`Gp 2,966/1`.

**So these are not coming from the kit seeder.** Most likely the scraper's per-step `items[]` from the upstream
site payload. Determine the source before "fixing" anything — the fix differs completely depending on whether it's
scraped data, a stale annotation, or a kit-migration miss.

---

## Decisions

### D1 — Quest state: direct reads, no Quest Helper dependency
**Resolved, and already enforced in shipped code.**

The binding constraint is that **the Plugin Hub forbids reflection** — the QH handoff was removed for exactly this
reason and it was raised in review round 1 (Alexsuperfly, 2026-07-28). It now degrades to a chat message / green
tip line. (My earlier Gradle-dependency reasoning was wrong; the conclusion stands.)

**Performance constraint — do not regress this:** `Quest.getState` runs a **clientscript**. Wave 7 found the
jumped-ahead scan calling it ~100×/tick, enough script load to break Quest Helper's own pathing. All per-tick
readers must use `cachedQuestState` (scan every 5 ticks). Any new quest-state work follows the same rule.

**Partial quest progress** uses the existing mid-quest checkpoint annotations (`{"varbit"|"varp": id, "value": n}`,
optionally `bit`), which complete a sub **only** off the checkpoint — heuristics get no vote. This is seeding work,
not new architecture.

---

### D2 — Requirement scoping: policy exists, enforcement doesn't
The **KIT-SEEDING POLICY** (owner, 2026-08-05) already states the rule: kit items carry true requirements always;
items a quest hands you mid-quest never, because they sit permanently red and are misinformation. Wave 9's KIT
CORRECTIONS restates it for finishing steps ("must list what the FINALE needs, not the quest-wide wiki list").

SS-04/05 is a **violation of existing policy**, not a missing decision. Resolve via INV-E, then TOOL-01.

---

### D3 — Route consistency: sticky transport
INV-D should confirm the nodes are legitimate. If so, implement **sticky transport**: when a network transport is
chosen to reach a landmass, record it and discount its reciprocal on the return leg. Avoid a multi-leg optimiser.

Relevant existing behaviour: network-travel arrival already treats a ⌖ on "Charter to X" as the **boarding** dock,
with only the text destination or 📍 proving arrival (wave 7). Sticky selection layers on top of `nearestOf`.

---

## P0 — Correctness

### P0-01 — Ess-mine step didn't tick
**SS-01, SS-02** · **Prior work:** wave 9 CHAIN RULE; Enter-the-Abyss chain seeded; scrying orb id 5519;
`audit-arrivals.mjs`.
**Gated on INV-A + INV-B.** Do not write new detection logic until those report.

---

### P0-02 — "Start Grand tree" demands unsatisfiable mid-quest items
**SS-04, SS-05** · **Prior work:** wave 3 kit migration to finishing steps; KIT-SEEDING POLICY;
`cross-check-quest-kits.mjs` with its STALE? flag.
**Gated on INV-E.** Then enforce via TOOL-01.

---

### P0-03 — Pirate's Treasure parts not recognised as done
**SS-16, SS-17** · **Prior work:** mid-quest varp/varbit checkpoint annotations (wave 6) — the exact mechanism.
Pirate message id 433 already bundled.

**This is a seeding task.** Pull the Pirate's Treasure stage varp from Quest Helper source via
`tools/qh-lookup.mjs` and add checkpoint annotations sub-keyed to the "Karamja and port sarim parts" step, the
same way Grand Tree (varp 150>=80), Waterfall (varp 65>=3) and Lost Tribe (varbit 532>=5/6) are already seeded.

**Broader question worth raising with the owner:** how many other guide steps describe *part* of a quest? Those
are the ones that mis-report on accounts with prior progress. `PrintSubIdProbe` prints the ids for authoring.

---

### P0-04 — Chronicle teleport ticked the Castle Wars step
**SS-06** · **Prior work:** teleport position-jump detector; wave 5 widened post-teleport arrive radius to 45;
wave 7 network-travel arrival requires the text destination to prove arrival.

**Diagnosis:** a position-jump teleport satisfied a teleport-type sub without verifying *which* destination. The
network-travel branch already solved this shape for charter/spirit-tree — extend the same "text destination or 📍
proves arrival" rule to minigame/Chronicle teleport subs.

**Also consider:** a varp checkpoint on the Castle Wars sub would make it immune, per the wave 6 precedent that a
sub with a checkpoint completes only off it.

---

### P0-05 — Nav re-routes mid-quest instead of standing down
**Prior work:** `questHelperOwnsGuidance` — wave 2 deliberately made mid-quest nav route to the step's 📍 area
rather than clearing. Wave 7 fixed a *separate* QH interference (clientscript load).

**So this is working as designed, and the design is wrong for compound steps.** When a step's remaining work is
"continue quest X until Y", routing to the step's 📍 area actively fights QH.

**Fix:** full **nav HOLD** while a quest-progress stage is live — the same behaviour chain-complete already
implements. See P1-06.

---

### P0-06 — Travel NPC not offered; wrong NPC highlighted
**SS-14** (tooltip reads `Attack Foreman (level-23)`) · **Prior work:** named-NPC scan with named-beats-nearest
(waves 5–6); `shop_npcs.json` with keepers/bartenders; `CHARTER_DOCKS` network.

**Diagnosis:** travel NPCs aren't in the named roster, so the sub fell back to nearest-to-pin and crowned the
Foreman. Bartenders and shopkeepers were seeded for exactly this reason — **do the same for travel/charter NPCs.**

**Done when:** "Take boat to Port Sarim" outlines the correct travel NPC and highlights the travel menu entry
(`TravelMenuOverlay` already handles interface 187 / MenuNew 947 / group 72).

---

## P1

### P1-01 — No route out of the Essence Mine
**SS-03** · **Prior work:** the ZMI precedent — "SP can't path into cave interiors — anchor routable points at
entrances" (wave 5). Errand stages have a **route/satisfaction split** for precisely this.
**Fix:** anchor the ess-mine exit at its surface-routable point, same pattern as ZMI at Ourania Cave 2452,3231.

---

### P1-02 — Routed to Fishing Guild bank instead of King Narnode
**SS-04, SS-05** · **Prior work:** BANK-FIRST fires on any banked shortfall, including unowned items (wave 8).
**Almost certainly a consequence of P0-02** — bogus requirements triggered BANK-FIRST. Retest after INV-E.

---

### P1-03 — Karamja step ticked in the field, not at the docks
**SS-13** · **Prior work:** wave 5 widens arrive radius to **45** after a teleport; wave 7 makes charter subs prove
arrival by text destination or 📍 only.
**Diagnosis:** either the boat landing reads as a teleport (radius 45 covers the field) or the "Karamja" place pin
sits in the field. Check the pin first — cheaper.

---

### P1-04 — Wrong dock chosen; legs inconsistent
**SS-15** · **Gated on INV-D**, then D3 sticky transport.

---

### P1-05 — Barcrawl card and coins not required at the pub
**SS-09** · **Prior work:** wave 6 barcrawl stamp varp-bit checkpoints (varp 77, per-bar bits); Barcrawl card
sprite id 455 bundled; wave 7 re-seeded bar pins to QH's exact bartender WorldPoints and dropped the keeper's
purchase-goal gate; coins deliberately **excluded** from the arrival gate so mid-step spending can't wedge a
destination tick.

**What's actually missing:** the Barcrawl card as an annotation **item** on the drink steps, so the panel warns
before the walk. Coins are a display-only need here — do not re-add them to the arrival gate.

---

### P1-06 — Compound steps don't chain (Hazelmere → shipyard)
**SS-12** · **Prior work: errand chains already implement this.**

Existing stage capabilities (waves 4–9): ordered stages; `{x,y,plane,item,note}`; `npc` named outline; `items`
stage-focused inventory hints; `dialog` chat-option recolor; per-stage `radius`; route/satisfaction split
(`routeX/Y/Plane`); **varbit/varp + value gates**; `preQuest` flag; waypoint stages (`item:null`, satisfied by
proximity ≤12 or being closer to the next stage); sticky first-unsatisfied; chain-complete = nav HOLD.

**The Hazelmere case is seedable with what exists today:**
```
stage 1  Hazelmere            npc + dialog
stage 2  gate: Grand Tree varp 150 >= 80      ← already seeded as a checkpoint
stage 3  Karamja shipyard     waypoint
```

**The one genuine gap:** while a quest-progress-gated stage is live, nav should **HOLD** (render nothing) rather
than route to the step's 📍 area — that's the P0-05 fix. Chain-complete already holds; extend the same behaviour
to gated stages, with the panel stating what will release it ("resumes at Karamja shipyard").

**Work is therefore:** (a) nav HOLD on gated stages, (b) seed chains for the steps INV-C identifies. Not a new
subsystem.

---

## P2

### P2-01 — No GP cost on the hunter shop step
**SS-10** · **Prior work:** `seed-gp-costs.mjs`, 30 buy steps seeded (wave 7); SS-19 proves the badge renders.
**Data gap.** Re-run the seeder over the remaining buy steps; hand-set where the wiki value is missing (Barrows
gloves and Zeah compost are the existing hand-set precedents).

---

### P2-02 — Shop interface items not highlighted
**SS-11** · **Prior work:** `InventoryItemHintOverlay` outlines carried step items; bank filter renders per-step
sections. No equivalent for shop interfaces.
**Genuinely new work**, small. Follow the bank filter's widget-join pattern (`nameMatchesGoal`).

---

### P0-07 — Quest kit QUANTITIES are all 1, and mid-quest items are in the kit (owner, 2026-08-07)
**SS: "Start biohazard" step.** Owner: *"our plugin shows we need 1 of each rune, when we likely need many
runes to cast enough spells. Same with the GP, we dont need just 1 as im assuming we need to buy items."*

**CORRECTION — my first diagnosis here blamed `seed-quest-items.mjs` and was WRONG.** Biohazard's wiki kit
is only *priest gown top / bottom / gas mask* — no runes, no coins. The badges come from the **scraper**:
`annotations_oziris.json` entry `27405fdda8` lists gp, scrying orb, pickaxe, fire/air/mind runes with
**`quantity: null`**, straight off the Oziris site's per-step carry list. This is the answer to **INV-E**
as well — the "Start Grand tree" requirements have the same origin.

`null` was then read as "exactly 1" everywhere, so a carry list rendered as `Fire runes 134/1` — green,
implying you're ready when the guide never said a number at all. **222 items across 61 steps** are affected.

**Fixed 2026-08-07 (untested in play):** unspecified quantity now means "bring some", not "need one".
The badge shows the carried count with no threshold and no false green; `bankFirstTarget` skips
unspecified items entirely, which is what sent the player to a second bank for a banked pickaxe.

**Deliberately NOT changed:** `annotationItemsCarried` (the arrival gate) still treats `null` as 1.
Loosening it would make arrival ticks MORE eager, and eager arrival ticks are the failure mode this
project has fought repeatedly. Revisit only with a specific report.

**Still open:** where the wiki DOES give a count the seeder reads it, but `~400 [[Coins]]` (Ghosts Ahoy)
loses its number to the tilde. Minor next to the above.

**Quest-granted items — mechanism added 2026-08-08, NOT YET PLAY-TESTED.** New annotation flag
`ItemNeed.granted`: the quest hands you this, so there is nothing to fetch. Renders muted with a
"(from the quest)" tag instead of alarm red, and never justifies a bank stop. It still lists and still
auto-ticks when the item lands — knowing the quest will give it to you is useful, being sent shopping for
it is not.

Reaching DETECTED goals (the plague sample came from step text, not a seeder) works by declaring the flag
on an annotation entry of the same name: `StepRow`'s merge already lets a goal shadow a same-named
annotation item, so the goal now inherits that one flag on the way through.

Seeded on the three quest-START steps, where the item provably cannot exist yet:
- Biohazard `27405fdda8:0` — plague sample, liquid honey, ethenea, sulphuric broline (the note already on
  that step says Elena hands over all four)
- The Grand Tree `8e5edf7f76` — bark sample, translation notes (this is P0-02)
- The Lost Tribe `13f33630f0` — brooch

**Left for the owner: 21 items on quest FINISHING steps** (`node tools/audit-quest-granted.mjs`). These are
judgement calls, not defects — wave 9's rule is that a finishing step lists what the FINALE needs, and
bring-then-consume-in-finale items are good entries. Demon Slayer's `silverlight key x3` is in the list and
was an explicit owner correction, so nothing there was touched. The question per item is whether seeing it
greyed as "(from the quest)" beats seeing it red.

**Related, same step, two more faults:**
- **`Plague sample 0/1` sits permanently red.** It's a TEXT-detected goal from "get the plague sample", and
  the sample is handed to you DURING Biohazard. This is the KIT-SEEDING POLICY / D2 case exactly ("items a
  quest hands you mid-quest never carry requirements — they sit permanently red, and that's
  misinformation"), except it comes from the detector, not the seeder, so the policy was never enforced
  against it. Owner: *"The plague sample needs to be acquired as part of the quest, not from the bank."*
  Errand chains are the right mechanism if it needs guidance at all.
- **BANK-FIRST keeps routing to banks.** Log: after the Gnome bank stop, `routing to 2615,3332`
  (Ardougne north bank). Cause is the kit, not the nav: `Pickaxe 2/1 🏦` is BANKED, and any banked shortfall
  justifies a stop (wave 8, deliberate). A pickaxe is not a Biohazard requirement — it's kit noise. Fixing
  the kit fixes the nav. **This is what TOOL-01 was for**; the quantity bug gives it a second job.

---

### P1-07 — Travel destination differs from the step's wording (owner, 2026-08-07)
**"Spirit tree to ardy"** — the spirit tree network has no Ardougne stop. The one to take is **Battlefield
of Khazard** (`2555,3259`, already in `SPIRIT_TREES`), then run north. Owner: *"i know the step doesnt label
it like this but for our overlay and shortestpath thats the one we want."*

`travelMenuWords` is built from the sub text + the step's 📍 tag — here "spirit tree to ardy" and
"Ardougne", neither of which matches the menu entry "Battlefield of Khazard", so `TravelMenuOverlay`
highlights nothing. **This is almost certainly the SS-07-works / SS-08-doesn't split in P2-03** — the same
root, so do them together.

**Fix:** an annotation field naming the network STOP to take (e.g. `"travelVia": "Battlefield of Khazard"`),
fed into `travelMenuWords` and preferred as the boarding target. Must NOT become the arrival proof — wave 7
already rules that a ⌖ on a spirit-tree/charter sub marks the BOARDING point and only the text destination
or 📍 proves arrival. Then sweep the guide for other steps whose named destination isn't a network stop.

---

### P2-03 — Teleport menu highlighting inconsistent
**SS-07** (works) vs **SS-08** (doesn't) · **Prior work:** `TravelMenuOverlay` matches interface 187, MenuNew 947,
last-loaded group; word-SET match of sub text + 📍 tag.
**Diagnosis:** the Spirit Tree Locations dialog matched in SS-07 but not SS-08 — likely the word-set match failing
on that phrasing, or a different widget group. The widget-load probe logs groups while a travel sub is current;
use it.

---

### P2-04 — Chronicle hint points at the equipment tab
**SS-18** · **Prior work: added deliberately in wave 9** — "Chronicle worn-slot teleport hint (equipment tab
STONE4 → `WornItems.SLOT5`, labeled)".
**Not a bug — an unhandled case.** The Chronicle was in the inventory, not worn. Resolve the actual container at
render time and point at whichever holds it. **Do not remove the worn-slot hint.**

---

### P2-05 — Outline doors, stairs, ladders
**SS-19, SS-20** · **Prior work:** `ObjectTargetOverlay` outlines ore rocks, chests, grind objects, with
impostor resolution and goal-item icon overhead; `ModelOutlineRenderer` gives QH-crisp silhouettes.
**Extend the existing overlay** to traversal objects on the active route. Errand stages already model interior
legs via route/satisfaction, so the data to know *which* door is often present.

---

### P2-06 — Match QH icon styling
**SS-19** · Cosmetic. `ModelOutlineRenderer` already shipped for this reason. Low priority.

---

### P2-07 — Pirate's message ticked on pickup, not on read
**SS-19** · **Prior work:** pirate message id 433 bundled (wave 9); checkpoint annotations complete a sub only off
the checkpoint (wave 6).
**Fix:** varp/varbit checkpoint on the read, sub-keyed. Same pattern as the barcrawl stamps.

---

### UX-01 — Distinguish manual overrides from auto-completions
**Already implemented:** manual tick/un-tick exists and persists. Remaining work is display-only — mark overridden
steps distinctly so the progress file reveals which steps detection is failing on.
**Constraint:** errand stages must remain individually inspectable; don't regress to all-or-nothing.

---

### TOOL-01 — Kit satisfiability audit — **BUILT 2026-08-08** (`tools/audit-quest-granted.mjs`)

Scoped to the fault that was actually reported rather than a full route-position ledger: it flags items the
plugin will DEMAND on a quest step **that the quest itself hands you**. Reads `build/goal-audit.tsv` (the
plugin's real resolved goals, so it sees detector output the seeders never touch — the plague sample is a
detected goal, which is why `cross-check-quest-kits` could never have caught it) plus annotation items, and
checks each against Quest Helper: `getItemRequirements()` = what you BRING, other `ItemRequirement`s = what
it tracks DURING the quest.

**Tradeability is the discriminator.** Without it the audit is mostly false positives — QH's bring-list is
often short (Royal Trouble returns just `coalOrPickaxe` + `combatGear`), so "QH tracks it but doesn't ask
for it" alone flagged rope, planks, buckets and coins. An item an ironman can buy or bank is satisfiable by
definition; only untradeable ones can sit permanently red. Family roots ("pickaxe", "bar") count as
tradeable, and coins are hand-listed since the GE mapping omits them.

It also independently caught **P0-02 / INV-E** — `bark sample` on the Grand Tree START step, the SS-04/05
case this item was written to catch before play.

Items already marked `granted` drop out, so the output converges to "not yet reviewed".

**Open question from the original entry — does the guide model what a step GRANTS? — was not needed.** QH's
own two-list structure answers it per quest.
**Prior work:** six audit tools already exist in the same family — `audit-goals`, `audit-nav`, `audit-shops`,
`audit-drops`, `audit-arrivals`, `cross-check-quest-kits` (which already has a STALE? flag), plus
`GoalAuditDumpTest` and `BundledAnnotationKeysTest`.

**Add:** an audit that flags any step whose annotation items are **unsatisfiable at that route position** — items
the guide's earlier steps never produced and no `item_sources` entry provides. This is the KIT-SEEDING POLICY
expressed as a test, and it would have caught SS-04/05 before play.

**Open question:** does the guide model what a step *grants*? `item_sources`, recipes and acquisition baselines
exist; whether they compose into a route-position ledger is unknown. Ask before building.

---

## NEXT SESSION — start here (written 2026-08-08, end of wave 11)

Everything below is from live play; the first two were reported and diagnosed but NOT finished.

### 1. P0-04 teleport destination proof — **written, uncommitted, never play-tested**
`src/main/java/com/ironscape/IronscapePlugin.java` has an uncommitted change in the working tree.
It compiles and tests pass. **Play-test it, then commit it.**

The travel-goal branch ticked any travel sub that happened to be current while `recentTeleportTicks > 0`,
without checking where the jump landed. Brimstail's teleport into the essence mine ticked the ess-mine
region checkpoint, the loop cascaded, and "Use mind bomb and camelot tele" completed from inside the mine
with Camelot 1,300 tiles away. Fix requires the landing to be within `TELEPORT_ARRIVE_RADIUS` of the sub's
destination, via a new shared `travelDestination()` helper so the jump path and the arrival path resolve
the destination identically. Unresolvable destination falls through to the arrival check rather than
ticking on the jump.

### 2. Boat steps tick on the deck, before the gangplank (owner, 2026-08-08)
"Take the boat back to Ardy" ticks on arrival while the player is still ON the ship. You must cross the
gangplank to be on land, so navigation "bricks" — Shortest Path is routing from a deck tile.

Same branch as (1), but the destination fix does NOT help: the deck is well within the radius of Ardougne.
Owner's suggestion, and the right signal: gate boat subs on the **"Cross gangplank" object click**
(`onMenuOptionClicked` already tracks object clicks for minigame presence).

**Check before wiring:** whether any boat route in the guide drops you straight onto the dock with no plank
to cross. If one does, gating on the plank would wedge that step forever. There are only 5 boat steps
(`Take the boat to Great Kourend`, `Take boat to Port Sarim`, `Take the boat from Ardy docks to
Rimmington`, `Take the boat back to Ardy`, `Ardy cloak tele and take the boat from Ardy to brimhaven`).

### 3. Wizard Cromperty not outlined on the ess-mine step (owner, 2026-08-08)
Same class as P0-06 and a one-line data fix: add him to `places/shop_npcs.json` keyed to the "Visit Ardy
ess mines" step. The ⌖ pin at 2684,3323 exists; the nearest-NPC fallback just isn't nominating him.

### 4. 21 quest-kit items awaiting the owner's ruling
`node tools/audit-quest-granted.mjs`. All on quest FINISHING steps, so they are judgement calls, not
defects — wave 9's rule is that a finishing step lists what the FINALE needs. Demon Slayer's
`silverlight key x3` is the owner's own correction and was deliberately left alone. The question per item:
does a muted "(from the quest)" read better than red?

### 5. Still unverified from wave 11
- the handoff banner + notification (never seen one fire — needs a step to finish mid-quest)
- `Customs officer` on the Musa Point -> Port Sarim boat (tentative name, still far off in the route)
- the ASCII glyph sweep on messages nobody has triggered yet (death, gravestone, on-the-way pickup)

---

## Execution order

**Phase 0 — investigate (no code)**
1. ~~INV-A — ess-mine chain satisfaction~~ **DONE 2026-08-07 — instanced regions; see RESOLVED at top**
2. INV-B — arrival audit counts
3. INV-E — source of the Grand Tree start-step requirements
4. INV-C — compound steps needing chains
5. INV-D — charter dock selection

**Phase 1 — small, high-confidence fixes**
6. P2-04 Chronicle container resolution
7. P2-01 re-run gp-cost seeder
8. P1-01 ess-mine exit anchored at a routable point
9. P0-06 seed travel/charter NPCs into the named roster

**Phase 2 — chains and nav**
10. P0-05 / P1-06 nav HOLD on gated stages
11. Seed chains for INV-C's list
12. P0-03 Pirate's Treasure checkpoint, then survey other part-quest steps

**Phase 3 — detection**
13. P0-01 per INV-A/B
14. P0-04 destination-verified teleport arrival
15. P1-03 Karamja pin / radius
16. P2-07 pirate message read checkpoint

**Phase 4 — policy enforcement and polish**
17. TOOL-01, then P0-02 / P1-02
18. P1-04 sticky transport (D3)
19. P1-05, P2-02, P2-03, P2-05, UX-01

---

## Notes

- **Hub pin is at `b8c994d`, ~70 commits behind.** Owner's standing rule: bump only after a calm session. A
  backlog-clearing session is not a calm session — don't bump mid-flight.
- **Never add Claude co-author trailers to commits.** History was rewritten and force-pushed on 2026-07-28 to
  strip them.
- Three items are prior work misread as bugs: **P2-04** (deliberate), **P2-01** (seeder exists), **P0-05**
  (designed behaviour). Check the Prior work line before coding.
- Test account has 292 QP — ideal for P0-03, poor for fresh-account ordering tests.
### BANK FILTER — mostly fixed 2026-08-08, ONE case left

**Fixed and confirmed in play:** icons no longer move or vanish while WITHDRAWING, and turning the filter off
restores the native bank.

Root cause was the widget lifecycle, found by copying Quest Helper (owner's suggestion — `QuestBankTab.java`):
1. **Timing.** We hooked `BANKMAIN_BUILD` and laid out inline, i.e. *during* the bank's own build. QH hooks
   **`BANKMAIN_FINISHBUILDING`** and defers with **`clientThread.invokeAtTickEnd`**. Ours now does both.
2. **Pooling.** We reused widgets by index, so each carried the previous pass's state — **opacity above all**.
   A slot that had been a faded ghost, reused for an owned item, relied on `setOpacity(0)` restoring it and
   just went blank. That is why the blanks were always the items the player was CARRYING (ghost branch,
   `met == true`) while unowned items rendered fine. QH never re-styles a used widget: it truncates its
   children away and builds fresh. Ours now does too, and the **stale-pool machinery from wave 8 is gone**.
3. **Widget stealing.** The fuzzy `nameMatchesGoal` fallback let an item you own NONE of claim a real bank
   widget (`plague sample=moved!` at 0/1) and `kept` it, robbing the item that needed it — and since
   `nativeByName` follows bank child order, a different item got robbed each pass. Owning none now always
   means ghost.
4. **Composition churn.** Within a step, items were dropped when `subDone && !stillMet`, so withdrawing made
   items enter/leave the `LinkedHashMap` and everything after them shifted. Wave 7 froze which STEPS show;
   `frozenSections` now freezes the ITEMS too. Counts stay live.
5. **Teardown.** Restoring moved widgets' x/y and un-hiding what we hid is now OUR job — a regression from
   (1), since running at tick end means our changes land last and stick. Before, the client's own build
   overwrote them ("nothing is permanently lost" — no longer true).

**DEPOSITS — fix written 2026-08-08, NOT YET PLAY-TESTED.**

Symptom: items you DEPOSIT while the filter is on render as unclickable ghosts until you toggle the filter
off and on.

**One premise recorded here was wrong, and it sent two fixes at the wrong target.** "A deposit triggers no
bank build at all" was inferred from an absence of log lines — but `bank filter pass:` and `bank filter
items:` only print when their content *changes*. Re-reading the session log:

```
00:45:07  pass: 10 sections, 3 moved, 13 ghosts, ..., 291 native items   <- last withdrawal
00:45:09  items: (every item now "!", i.e. carried counts dropped)       <- the deposit
          ^ an "items:" line with NO "pass:" line = a pass DID run and the shape
            was byte-identical: still 291 native items
```

`bankMissingSection.update()` has exactly one caller — the `BANKMAIN_FINISHBUILDING` hook. So a deposit
**does** trigger a build and our pass **does** run. What it reads is a container whose item widgets still
show the pre-deposit set, so the deposited item has no widget to move and can only be drawn as a ghost.

Why the two reverted attempts did nothing: `BankSearch.layoutBank()` runs the bank's inv-transmit script
**synchronously** (`client.runScript`, no deferral — confirmed in RuneLite's source). Called inline from
`onItemContainerChanged` that is the same script-engine re-entrancy that hard-froze the client twice before.
Both attempts were also aimed at forcing a rebuild that was already happening.

**What is wired in now:** no client rebuild. A bank container change sets `bankRelayoutTicks = 3`, and
`onGameTick` re-runs OUR layout at tick end for those ticks — the pass that lands after the widgets
repopulate turns the ghosts back into real, withdrawable ones. If a pass still measures a mismatch on the
last tick of that window, and only then, `layoutBank()` is called via `clientThread.invokeLater` (a clean
stack) as a fallback. So the forced rebuild costs nothing unless the plugin has *observed* the state that
needs it.

**Diagnostic:** the pass line carries `N/M widgets populated, via <trigger>` plus `STALE` when the true
condition holds.

**ROOT CAUSE FOUND 2026-08-08, AND IT WAS US.** Our `bankSearchFilter` callback answered **0 ("hide") for
every slot** while the filter was on. The decompiled build script (`BankMainBuild.rs2asm`) shows that answer
is permission to lay the slot out at all:

```
filtertest:
  invoke 279          ; ~bankmain_filteritem -> our callback
  if_icmpne LABEL972  ; answer != 1 -> skip the slot ENTIRELY
LABEL929:
  cc_sethide / cc_setobject / cc_setposition
```

A rejected slot never reaches `cc_setobject`, so the client never gives that item a widget. Widgets that
already existed when the filter came on survived — which is exactly why WITHDRAWING looked fine and only
DEPOSITS broke: a deposited item needs a NEW widget, and we were refusing it one. With nothing to move, the
item could only be drawn as an unclickable ghost.

It also explains why every attempt to force a rebuild failed, including the guarded one: the re-run asked
our callback again and got the same "hide". Confirmed in play — `forcing a rebuild` left native items at
**291 of 330**, unchanged.

Fix: answer **1** (lay it out). Blanking was never this callback's job; `BankMissingSection` hides every
native child it didn't move, which is what actually produces the clean view. The forced-rebuild fallback is
removed as proven useless.

**Also learned and fixed: do NOT compare the two counts.** A healthy bank runs ~30 stacks ahead of its populated widgets — `302/330` on a freshly
activated, fully working filter — so `populated < inContainer` is the NORMAL state and a fallback gated on
it fires constantly (it did, twice, and the forced rebuild changed nothing because nothing was missing).

The condition that actually matters is per item and narrow: **we drew a ghost for something the bank
container really holds.** That is the deposit symptom exactly — the item is back in the bank, the client
has not given it a widget, so it renders unclickable with no tooltip. `lastPassStale` now means that, using
the same alias/family matching as the widget join, so the two can only ever disagree about the WIDGET.

**Still unverified:** whether the tick-end relayout fixes the deposit. The `via bank-change` passes do run,
so the plumbing works, but the run died before the deposit (see the class-loading note below) and no
post-deposit pass was captured. Owner's live report that deposits still ghost is from that same broken run.

**Do not rebuild while the dev client is running.** `gradlew test` rewrote `build/classes` under a live
client and the panel died with `NoClassDefFoundError: com/ironscape/panel/StepRow$SubRowUi` — the guide
"disappeared" (progress intact, only one section drawn). Not a plugin bug; restart clears it.

---

- **Bank filter vanishing icons — REPRO CAPTURED 2026-08-07.** The wave-8 self-log caught it while the owner
  withdrew with the filter on:
  ```
  pass: 10 sections, 5 moved,  6 ghosts, 17 texts, 1438 container children
  pass: 10 sections, 9 moved,  2 ghosts, 17 texts, 1461 container children
  bank filter pools went stale (container resized) — recreating
  pass: 10 sections, 0 moved, 11 ghosts, 17 texts, 1438 container children   <-- every icon a ghost
  pass: 10 sections, 10 moved, 1 ghosts, 17 texts, 1466 container children
  ```
  A withdrawal rebuilds the bank container; our pooled widgets go stale and are cleared; the pass that runs
  on that tick reads an INCOMPLETE container (1438 children, down from 1461), so `nativeByName` finds none
  of the real item widgets and draws **everything** as a ghost. The next pass (1466 children) fixes it —
  hence "icons move/disappear as I withdraw". **Fix shape:** on a stale-pool pass, skip the rebuild for one
  tick (or keep the previous composition) rather than laying out from a half-built container. Do not chase
  this with a repaint hack — the log line names the mechanism.
- Still open from wave 9: deliberate death test, onion-gate capture, "big frog leg" → 7908 verdict.
- Still hardcoded in Java, so not fixable in-game the way ⌖ pins are: `BANKS`, `SPIRIT_TREES`,
  `CHARTER_DOCKS`. Two of today's bugs were wrong/missing entries in `BANKS`. Moving them into
  `places.json` would make them capturable.
