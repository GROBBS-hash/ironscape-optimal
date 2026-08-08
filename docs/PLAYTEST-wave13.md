# Play-test checklist — wave 13

Nothing in wave 13 has been played. Seven commits, all local and unpushed; hub pin stays at
`3638c2f` until this list is done.

Step numbers are guide order out of 575. Your last recorded position was around **#210–220**
(Karamja / Pirate's Treasure), so most of this is right in front of you.

Run this once at the end of a session and it answers most of the checks:

```bash
node tools/mine-session-log.mjs | grep -E "boat gate:|auto-completed|purchase|nav-decision"
```

---

## A. Right where you are — #211 to #235

### A1 · Boat gates ⛵ — #211, #214, #226, #228
**Never exercised.** Four of the six boat trips sit in this stretch.

| # | Step |
|---|---|
| 211 | Take boat to Port Sarim |
| 214 | Boat back to Karamja |
| 226 | Take the boat from Ardy docks to Rimmington |
| 228 | Take the boat back to Ardy |

**Do:** take each boat normally.

**Should happen:** the step does *not* tick while you're stood on the deck. It ticks after you
walk down the gangplank.

**In the log:**
```
boat gate: <id>: holding, gangplank loaded but not crossed     <- while aboard
boat gate: <id>: ashore, gangplank crossed                     <- after you walk off
```

**Worth knowing:** I checked the wiki's gangplank list and **all six destinations have a plank**,
so the "no plank nearby, open the gate" escape hatch never fires here. The gate is doing real work
on every one of these.

- ✅ **Good:** holding → crossed, then ticks.
- ⚠️ **Acceptable:** if a boat drops you *ashore* rather than on the deck, it holds until you walk
  ~8 tiles from the plank, then ticks. Late, not stuck.
- ❌ **Bad:** ticks while you're still on the deck, or never ticks after you're well inland.

> Please also just note **which boats put you on the deck vs straight onto the dock** — that's the
> one fact I couldn't establish without playing it.

---

### A2 · The compost / farming tools step 🌱 — #235
**This is the main fix of the session.** *"Buy 1 pack of normal compost and all farming tools,
store everything in leprechaun"* (`5bf54fe229`).

**Before:** ticked the instant the compost pack was bought — the five tools had no say.

**Now:** waits for **rake, seed dibber, spade, watering can, secateurs, and the pack**.

**Do:** buy them in whatever order you like, then put the tools in the leprechaun.

**Should happen:**
- Buying only the pack → step stays open.
- Once all five tools + the pack are on you → ticks.
- **Still ticked after you deposit into the leprechaun** ← the important one.
- Buying tools *before* the pack works the same as after.

❌ **Bad:** it re-opens when the tools go into the leprechaun. That means the arming didn't stick.

> **Known limit, not a bug:** if those tools are already sitting in the leprechaun from an earlier
> visit, nothing can see them — no container we can read holds leprechaun storage. Tick it by hand;
> the step's own note says so.

**Bonus check while you're there (costs nothing):** buy the tools, then **restart the client**
before the step ticks. It should still tick. That's the persisted-baseline fix from wave 12, which
has also never been proven across a restart.

---

## B. A bit further on — #293 to #375

| # | What | Watch for |
|---|---|---|
| 293 | Boat Ardy → Brimhaven | Same gangplank check as A1. Also: nav should route you to the **Ardy dock** (boarding), not to Brimhaven. |
| 324 | **Start Rag and bone man** ⚠️ | See below — the one thing I loosened. |
| 348 | Flinch Tanglefoot (Fairytale I) | Should now tick when Fairytale I is complete. |
| 352 | Rescue prince ali | Should now tick when Prince Ali Rescue is complete. |
| 375 | Finish Evil Dave subquest | Should now tick when that RFD subquest is complete. |

### ⚠️ #324 — the one loosening
*"Start Rag and bone man on the way to the temple"*

It used to require your **pots / logs / tinderbox** before it would tick (through the arrival gate).
Now it ticks **when the quest starts**, like every other start step.

**Your call:** if you'd rather it kept waiting for the burning kit, say so — the fix is a varp
checkpoint, same as the three you seeded in wave 12.

---

## C. Later — #441 to #499 (section 1.4 / 1.5)

**These eleven steps could never auto-tick at all before this session.** No quest goal was detected,
so the only route was "arrive carrying the full kit" — and on a quest *finishing* step that kit is
exactly what the quest consumes. Permanently stuck.

| # | Step | Quest |
|---|---|---|
| 441 | Forgettable tale of a drunken dwarf quest | Forgettable Tale... |
| 442 | Garden of tranquility quest | Garden of Tranquillity |
| 479 | Do Hand in the sand quest | The Hand in the Sand |
| 489 | Rum deal (...) | Rum Deal |
| 490 | Cabin fever | Cabin Fever |
| 491 | One small favour | One Small Favour |
| 492 | Watchtower quest | Watchtower |
| 499 | Do Desert Treasure | Desert Treasure I |

**No need to hunt these down.** Your account has 292 QP, so several are already complete — when the
guide reaches this stretch they should tick in a **burst** as they come into the 8-step window.
That burst *is* the test.

If one of them sits there unticked with the quest complete, grab:
```bash
node tools/mine-session-log.mjs | grep "auto-completed"
```

---

## D. Two decisions I need from you

**D1 · The Fremennik Trials lyre** (step `80a3ae4d44`)
The wiki says it's a drop from the trial NPCs *"or the skills and materials to make one"* — so it's
obtainable during the quest, but you might reasonably bring one.

- Mark it `granted` → shows muted "(from the quest)", never sends you to a bank.
- Leave it → sits red until you have one.

Which matches how you actually play it? It's the only open item in
`node tools/audit-quest-granted.mjs`.

**D2 · Rag and bone man** — see the ⚠️ above.

---

## E. Also unexercised (older, if you happen to pass them)

- **Shop overlay** — likely failure is *over*-matching: name matching runs through the alias/family
  rules, so a broad stock list could light up every axe tier when the step wants one.
- **Deliberate death** → gravestone routing.
- **PAR / Holy Grail** start checkpoints, staircase outline, per-NPC shop icons, Karamja pin at the
  docks, manual-tick tooltip.

---

## F. Not being tested — I tried and stopped

The **35 target-drift steps** need per-step ⌖ captures, as you said. I tried to turn them into one
rule and couldn't: distance alone breaks the saltpetre dig spot (219 tiles away and genuinely where
you go), and a place-type rule breaks because Shilo Village and Tower of Life are place *and* quest
names sitting at zero drift.

What I could do is triage them, so it's **~15 pins, not 35**. The list of which are real hijacks and
which are already correct is in `CLAUDE.md` under wave 13.

---

## Housekeeping

- Everything is **committed locally, not pushed**. Say the word.
- Build worktree at `%TEMP%/ironscape-audit` — safe to delete.
- Reminder: no gradle while the client is running.
