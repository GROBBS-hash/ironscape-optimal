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

## Investigation tasks — do these first

### INV-A — Why didn't the ess-mine step tick? (P0-01)
**Leading hypothesis: wave 9's CHAIN RULE, landed the same morning as this session.**

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

### TOOL-01 — Kit satisfiability audit
**Prior work:** six audit tools already exist in the same family — `audit-goals`, `audit-nav`, `audit-shops`,
`audit-drops`, `audit-arrivals`, `cross-check-quest-kits` (which already has a STALE? flag), plus
`GoalAuditDumpTest` and `BundledAnnotationKeysTest`.

**Add:** an audit that flags any step whose annotation items are **unsatisfiable at that route position** — items
the guide's earlier steps never produced and no `item_sources` entry provides. This is the KIT-SEEDING POLICY
expressed as a test, and it would have caught SS-04/05 before play.

**Open question:** does the guide model what a step *grants*? `item_sources`, recipes and acquisition baselines
exist; whether they compose into a route-position ledger is unknown. Ask before building.

---

## Execution order

**Phase 0 — investigate (no code)**
1. INV-A — ess-mine chain satisfaction (the freshest regression)
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
- Still open from wave 9, unrelated to this session: bank filter vanishing icons (self-log armed, no repro),
  deliberate death test, onion-gate capture, "big frog leg" → 7908 verdict.
