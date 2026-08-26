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

## START HERE — next session (written 2026-08-26, end of wave 31)

Main at **`3afe7fe`**, PUSHED, tree clean. **3 commits this session.** Hub pin
still `bc23c9b` — gap **4**.

Before ANY gradle command: `node tools/check-client.mjs`.
Before handing over a build: `node tools/check-all.mjs --tests`, reading the
COUNT from `build/test-results/*.xml` (**132** at the end of this session).
Note that `check-all --tests` REFUSES while a client is running, even from a
worktree; run `gradlew test` inside the worktree instead — its build folder is
its own, so the live client's classes are untouched.
For data-only fixes: edit the repo file, have him type `::ironreload`.
**Build in a throwaway `git worktree` when the client is live**, created and
removed in the same task.

**HE IS AT POSITION 301 AND HAS NOW PLAYED TEMPOROSS** — 8 kills, 4 permits.
Two things still unplayed:

1. **The step ticking itself** once he holds BOTH the Fish barrel and the
   Tackle box. Neither has dropped yet, so `completeOnItems` has had nothing
   to fire on. Watch that the barrel counts whether open (25584) or closed
   (25582).
2. **The EASIER VIA STEP button** on the gem step — green, no red warning
   chip. A red "Needs X first" means the wrong annotation field is in use
   (`prerequisiteQuest`, not `alternativeQuest`).

Both routing fixes from this session ARE confirmed in play: the ladder pin,
and the router going quiet inside an instance.

**AN AUDIT HAD BEEN REPORTING THIS SESSION'S HEADLINE BUG FOR WEEKS.**
`audit-pin-reachability` listed the Tempoross pin under WRONG SPOT the whole
time. **Run the audits against the steps he is ABOUT to play, not only when
something breaks.** `preflight` and the audit list are cheap.

**NEW TOOL:** `node tools/check-pin.mjs x,y[,plane]` says whether Shortest
Path can stand on a tile, before you commit it. Three of this session's first
guesses were wrong. Use it for every coordinate.

**BUILD NEXT, when there is desk time:**

- **The 19 steps whose item list has no quantities**, so no bank stop can act
  on them. Never measured for whether numbers exist to seed.
- **Quiet the travel-menu probe.** It has done its job — it is how we know
  which interface groups the menus use — and still logs a line per interface
  load while any travel step is current. It fired again all through this
  session's Tempoross trips.
- **Throne of Miscellania's three emotes** still need a way to say WHICH step
  owns which; the annotation holds one name.
- **The 10 unroutable pins are a category, not a task.** Nine are Sailing
  ocean content and one is a quest realm. Do not "fix" them with a nearby
  land tile — there is no approach to anchor at. Only `troubled tortugans` is
  named by the guide.

**Standing rules re-earned:** anchor an interior at the way IN, not the thing
inside (wave 5's ZMI rule, applied six more times); read the NAMED field on a
wiki page, never the first regex match (my Hallowvale coordinate was wrong and
`audit-quest-start-pins` caught it); and detect a file's own line endings
before a bulk edit — `places.json` is CRLF, the same family of trap as the
heredoc eating backslashes.

**Check the folder you opened in.** This session started in a completely
different project on the Desktop.

---

## Previous START HERE (written 2026-08-26, end of wave 30)

Main at **`f2f5424`**, PUSHED, tree clean. **13 commits this session.** Hub
pin **BUMPED to `bc23c9b`** and its `build` check PASSES — gap is **1**, and
that one commit is the Fishing fix below.

Before ANY gradle command: `node tools/check-client.mjs`.
Before handing over a build: `node tools/check-all.mjs --tests`, reading the
COUNT from `build/test-results/*.xml` (**132** at the end of this session).
For data-only fixes: edit the repo file, have him type `::ironreload`. Half
this session's changes needed no restart.
**Build in a throwaway `git worktree` when the client is live** — that is how
every code change this session was compiled and tested without interrupting
him. Create and remove it in the same task.

**HE IS AT POSITION 301, and Tempoross (302) is next.** That step is one of
the two things never played:

1. **Tempoross ticking itself** once he holds BOTH the Fish barrel and the
   Tackle box. Watch that the barrel counts whether open (25584) or closed
   (25582).
2. **The EASIER VIA STEP button** on the gem step — green, no red warning
   chip. If a red "Needs X first" appears anywhere, the wrong annotation
   field is in use (`prerequisiteQuest`, not `alternativeQuest`).

**FIVE OF THIS SESSION'S SIX REPORTS WERE THE SAME SHAPE:** a step that
could not finish, for a reason the panel was already displaying. When he says
"this should have ticked", read the badges as CAUSES, not decoration — a
level shown in the list is a level being waited on.

**THE TRAP THAT KEEPS BITING:** generating code or regexes through a shell
heredoc eats backslashes. `\d` became `d` this session and a measurement
returned a silent 0. Write the script to a FILE, or use escape-free patterns
(`[0-9]`, `[ ]*`). Also: `git add -A` swept an untested code change into a
data commit — stage explicitly when a build is pending.

**BUILD NEXT, when there is desk time:**

- **The 19 steps whose item list has no quantities**, so no bank stop can act
  on them. Never measured for whether numbers exist to seed.
- **Quiet the travel-menu probe.** It has done its job — it is how we know
  which interface groups the menus use — and now logs a line per interface
  load while any travel step is current.
- **Throne of Miscellania's three emotes** still need a way to say WHICH step
  owns which; the annotation holds one name.

**Standing rules re-earned:** if you notice a risk while shipping, fix it or
do not mention it (the "Needs The Golem first" chip was flagged and shipped
anyway); measure a proposed rule guide-wide before writing it (the
annotation-beats-detector rule would have wrongly loosened two steps); and a
step NOTE says what to DO — the why-it-was-broken belongs in the commit.

---

## Previous START HERE (written 2026-08-17, end of wave 29)

Main at **`acdabaf`**, PUSHED, tree clean. **8 commits this session.** Hub pin
`057ab9b`, gap **9** — counted at the end, do not carry it forward.

Before ANY gradle command: `node tools/check-client.mjs`.
Before handing over a build: `node tools/check-all.mjs --tests`, and read the
test COUNT from `build/test-results/*.xml` (**129** at the end of this
session), because gradle prints "BUILD SUCCESSFUL" for tests it never ran.
For data-only fixes: edit the repo file, have him type `::ironreload`. Six of
this session's changes needed no restart at all.

**HE IS ON A GRIND.** Position **292**, "Blackjack until 50 thieving" at
Pollnivneach — a skill gate, so the step will not tick for a good while and
the quest steps behind it are further off still. Plan desk work accordingly;
a long grind is the best time for audits and seeding.

**PLAY-TEST FIRST — and note the first one fails SILENTLY:**

1. **The 41 pins that no longer nominate an NPC.** The failure mode is a
   shopkeeper who STOPS being outlined, and nobody notices a highlight that
   is missing — so this has to be looked FOR, not waited for. Every verdict
   and its reason is in `tools/target-npc-reviewed.json`; flipping one back
   is a one-line data change.
2. **The carpet/boat destination highlight.** Needs a travel menu served as
   ordinary chat options. Three launches this session and none came up.
3. **The shop detour** — vendor outlined, chat line naming the seller, and
   the route moving on after you buy. Unplayed since wave 28.
4. **Step 316**, still unplayed from earlier waves.

**IF A BUNDLED PIN OR ITEM NEEDS FIXING, REMEMBER THE SHADOW.** 28 of the 98
bundled ⌖ targets are ALSO captured locally on his machine, and
`AnnotationManager.getTarget` reads local first — a captured pin replaces the
bundled one outright, flag included. A bundled-only fix is invisible on the
one machine that play-tests it. `review-target-npc.mjs --apply` patches both
and backs the local file up first.

**BUILD NEXT, when there is desk time:**

- **The 39 steps with nowhere to route.** `check-all` reports the number every
  run and nobody has ever measured how many could actually carry a pin —
  wave 18 did exactly this for a different class and found it was mostly a
  labelling problem, not a data one.
- **Quiet the travel-menu probe.** It has done its job (it is how we know
  which interface groups these menus use) and now logs a line per interface
  load while any travel step is current.
- **Throne of Miscellania's three emotes.** The annotation holds ONE emote
  name, and that quest wants Dance, Blow kiss and Clap at three different
  moments inside a single step of ours. Needs a way to say which step owns
  which before it can be seeded at all.

**Standing lessons re-earned this session:** `git checkout` to revert a
deliberate test break takes the REAL fix with it (wave 28 recorded it; it
happened again); write generated code to a FILE rather than escaping it
through a shell (failed twice, once through bash and once through node); and
measure a proposed rule guide-wide before writing it — WK-1's obvious rule
flagged 312 items and nearly every one was correct as it stood.

---

## Previous START HERE (written 2026-08-14, end of wave 28)

Main at **`9ddfe8c`**, PUSHED, tree clean. **15 commits this session.** Hub pin
still `ae9f062`, gap **32** — counted at the end, do not carry it forward.

Before ANY gradle command: `node tools/check-client.mjs`.
Before handing over a build: `node tools/check-all.mjs --tests`, and read the
test COUNT from `build/test-results/*.xml` (124 at the end of this session).
For data-only fixes: edit the repo file, have him type `::ironreload`. Six of
this session's fixes needed no restart at all.

**THE PANEL SCROLL IS FIXED AND CONFIRMED.** Six rounds, and it was never in
the scroll code: each step's text pane carries a caret, and a moving caret
scrolls the whole panel to itself. If anything like it recurs, the viewport
probe is still installed — grep the log for `VIEW MOVED BY SOMETHING ELSE` and
it prints the stack of whoever did it. **That probe is the reusable asset from
this session; it answered in one read what five rounds of reasoning could not.**

**PLAY-TEST FIRST — four things shipped in the last hour and NONE have run in
a game:**

1. **Dommik gets outlined** when the route sends you shopping (the vendor scan
   now reads annotation items, not just goals detected from step text).
2. **The chat line** — "routing to a shop first - you need X, sold by Y".
3. **Buying it moves the route on.** It previously parked outside the shop.
   Easiest test: untick a step whose items you lack, let it route, buy, wait a
   few seconds.
4. **The scroll anchor stand-down** — it no longer tugs against a new landing.

Then the leftovers from earlier waves, still unplayed: the Emotes TAB
highlight, emote persistence across a restart, the 75-tile floor on a
router-picked teleport, the organ's diary icon and the dungeon stairs outline,
then **282** and **316**.

**AGREED NEXT SESSION (owner, end of wave 28): the ⌖ PINS AUDIT.** Do this
first, before the play-test list.

A ⌖ target nominates the nearest NPC to it unless the pin carries
`"npc": false`. That is how a level-2 Man came to be outlined wearing a sickle
icon: the pin was the FURNACE. **Measured: 98 bundled targets, and 96 of them
leave the flag unset**, so they all nominate by default. Nobody has ever
checked how many of those mark a furnace, a door, a dig spot or a stairwell
rather than a person.

`item_sources.json` has the same shape and is already mostly handled — 15 name
a vendor, 3 say `npc:false`, and **6 do neither** and fall back to nearest-NPC.

Do NOT bulk-apply. The wrong direction here is silent: setting `npc:false` on a
pin that really does mark a person removes an outline nobody will notice is
gone. Generate a clickable review page (his preferred format — see
[[review-ui-for-input]]) with the step text, the pin, and what is near it, and
let him settle the ambiguous ones.

**BUILD NEXT:**

- **WK-1 is half-dead.** Its root cause turned out to be that `optional` was
  never honoured by the arrival gate, which is now fixed, so the three
  historical instances are unblocked. What remains worth building is the
  narrower audit: **an item on step N that also appears on step N+1, where
  N+1 is what consumes it.** Measure before applying — that was always the
  rule and it saved two bad changes this session.
- **WK-2, bulk emote seeding** — unchanged, still data-only.
- **An audit for ⌖ pins that mark a PLACE but lack `npc:false`.** One caused a
  level-2 Man to be outlined wearing a sickle icon. Nobody has counted how
  many others there are.

**Standing lessons re-earned, again:** ship the diagnostic BEFORE the fix when
causes are indistinguishable (cost two extra rounds tonight); measure a
proposed rule guide-wide before writing it (killed two rules, one of which
would have broken "Bank 7 logs"); a note describing a MECHANIC needs a wiki
source, because an invented one reads perfectly (second occurrence); and if
you notice a risk while making a fix, SAY IT rather than shipping and letting
him meet it in game.

---

## Previous START HERE (written 2026-08-13, end of wave 27)

Main at **`075d610`**, PUSHED, tree clean. **15 commits this session.** Hub pin
still `ae9f062`, gap **16** — counted at the end, do not carry it forward.

**DO NOT BUMP THE PIN YET.** Five things shipped today have never run in a
game, and the standing rule is to pin only after a session goes quietly. This
one had four wrong diagnoses of a single bug. When you do bump: push, re-pin,
then **wait for the `build` check** — `gh pr checks 14207 --repo
runelite/plugin-hub`. A red X named "Requires maintainer review" is normal; a
red `build` is real.

Before ANY gradle command: `node tools/check-client.mjs`.
Before handing over a build: `node tools/check-all.mjs --tests` — and read the
test COUNT from `build/test-results/*.xml`, because gradle will happily print
"BUILD SUCCESSFUL in 2s" for tests it never ran.
For data-only fixes: edit the repo file, have him type `::ironreload`. Two of
wave 27's three step-blocking bugs were data and needed no restart at all —
reach for this FIRST when a step's items look wrong.

**When he reports a problem, ask him to type `::ironwrong` at that moment**
and read `~/.runelite/ironscape/reports/`. It records position, step, sub,
quest state, where the route actually pointed, and the last 25 nav/hint
decisions. It no longer overwrites (a second press on one step writes `-2`).
Wave 27 wasted three rounds reasoning from screenshots before reading a log;
the reports and the `scroll:` / `router-choice:` lines settle in one read what
screenshots cannot settle at all.

**PATHING POLICY (owner, 2026-08-12): SUPPORT BOTH, GPS PREFERRED.** He runs
GPS (`runelite.gpsplugin=true`, `shortestpathplugin=false`); Shortest Path
stays supported for users who want it. **Never run both — one message draws
two routes.** Keep posting on the `"shortestpath"` channel: GPS is a fork that
accepts it as a documented compatibility namespace, so one message serves
either, where GPS's own `"gps"` namespace would silently do nothing for
Shortest Path users.

**PLAY-TEST FIRST, in the order he will meet it:**

1. **The Emotes TAB highlight.** The emote itself is confirmed working; the
   TAB is not, because he had the panel open. `STONE12` is a reading of the
   tab order — if it lights up Music or Settings, that is the bug and it is a
   one-line fix.
2. **Emote persistence.** Perform a seeded emote, restart, and check the hint
   stays gone. That was the whole point of persisting it.
3. **The 75-tile floor on a relayed leg** — a short teleport the router picks
   should now be declined, with the distance named in the log.
4. **The organ's diary icon and the dungeon stairs outline.** Both shipped
   with the organ stage; only the routing half was confirmed.
5. **Step 284 "Finish Lost tribe"** — its kit was cut from eight items to
   four (none of which can gate) right at the end of the session, applied by
   `::ironreload` but never seen ticking. It should complete on the
   conversation alone.
6. Then **282** and **316**, still unplayed from earlier waves.

**THE PANEL SCROLL IS FIXED — and the story matters more than the fix.** Four
diagnoses failed because "it lands in the wrong place" and "nothing re-lands
it" look identical from a screenshot. What settled it was making the panel log
every landing: exactly ONE line existed for a whole session, proving the code
was not running at all. **If it drifts again, read the log first** — there are
`scroll:` lines for landings, for the frontier moving, and for re-anchoring.

**BUILD NEXT, when there is desk time:**

- **WK-1, the carried-ahead / already-spent kit audit** — now THREE instances
  in two sessions (Lit candle, step 280’s dyes, step 284’s eight-item finale
  kit of which none were needed). See the PLANNED section below for the two
  shapes it must tell apart.
- **WK-2, bulk emote seeding** — the overlay is confirmed working; the rest
  is data. See PLANNED for the step-granularity problem.
- **Step 276's chain** models only Merlin upstairs, not King Arthur
  downstairs. CONFIRMED as a real gap; left alone because he is past it, so it
  only bites a new player.

**Standing lessons re-earned today:** write scripts to a FILE and run them;
create and remove a worktree within the same task (four stale ones held real
uncommitted work); and when a failure has several indistinguishable causes,
ship the diagnostic BEFORE the fix rather than after three attempts.

---

## PLANNED — next build (agreed with owner 2026-08-13, end of wave 27)

Two items, both agreed. Neither is urgent enough to interrupt a play session;
both remove a whole class of report rather than one instance.

**WK-1 — Audit kit items that belong to a LATER step. BUILT 2026-08-17**
(`tools/audit-misplaced-kit.mjs`.) Found ONE live instance: "Teleport to
Camelot" still listed bat bones and a black candle, both gathered AFTER you
arrive. Three obvious rules were measured and REJECTED on the way — read that
file's header before proposing any of them again.

The highest-value one, because the class has now cost a blocked step **three
times in two sessions** and each was found by walking into it:

- wave 26: a "Lit candle" on the Camelot step, which the ritual never carries;
- wave 27: step 280 "Falador teleport" carrying Goblin Diplomacy's blue and
  orange dyes as hard requirements;
- wave 27: step 284 "Finish Lost tribe" carrying **eight** items of which
  **none** were needed at the finale — four were for the smelting step four
  steps later, four were spent in the tunnels legs long before.

The failure is always silent and always looks like broken DETECTION: arrival
needs the step's items in hand, so one item that can never be held stops the
step completing, with nothing in the log to say why. The owner reads it as
"this step won't tick".

**Two distinct shapes, and the audit must tell them apart:**

1. *Carried ahead* — the item also appears on a LATER step which is what
   actually consumes it. Fix: `bringAhead` + `optional`, so it keeps its icon
   and its place in the list but cannot gate.
2. *Already spent* — a genuine quest item consumed on an EARLIER leg, sitting
   on the step that FINISHES the quest (wave 9's rule: a finishing step lists
   what the FINALE needs, not the quest-wide wiki list). Fix: drop it.

Mechanical inputs, all of which already exist: the annotation corpus, the
guide text, `questStatus` metadata, and `build/completion-paths.tsv` for
whether a step can complete by ARRIVAL at all (only those can be blocked this
way — a step ticking off quest state does not care).

Suggested rule to start from, then measure before trusting it: flag any
NUMBERED, non-optional, non-granted, non-consumed item on an arrival-completed
step where the same canonical name appears on another step within a window
either side. Report which side it appears on, since that decides which of the
two fixes applies. **Measure the hit count before applying anything** — wave 13
proposed a similar rule, measured it, and found 29 of 30 changes would have
been wrong, so the rule was narrowed instead of shipped.

**WK-2 — Seed emotes in bulk from Quest Helper. DONE 2026-08-17**
(`tools/seed-emotes.mjs`.) "Bulk" turned out to be four quests guide-wide, of
which exactly one needed seeding (Song of the Elves -> Spin). Throne of
Miscellania is the only one outstanding and it needs the step-granularity
answer described below.

One step is seeded today: The Lost Tribe's Goblin bow (`1ba377b2f6:0`). The
overlay works — confirmed in play, matched by sprite — so the remaining work
is entirely data.

Source: QH constructs `EmoteStep(helper, QuestEmote.X, ...)` in its quest
files, and `QuestEmote` carries the display name. A scraper pass like
`seed-quest-npcs.mjs`: for each quest we tag, find its EmoteStep(s) and write
`emote` onto our step for that quest.

**The known ambiguity, which is why this needs thought rather than a loop:**
our steps are coarse. "Continue Lost tribe" spans far more of the quest than
the moment an emote is wanted, and a quest split across four steps gives no
way to say WHICH step owns the emote. Options, cheapest first:

- seed onto the step whose quest tag matches and let the click stand-down do
  the rest (what the Lost Tribe entry does today — the hint may appear early,
  but one click dismisses it for good);
- gate the annotation on a quest var, the way the checkpoint annotations
  already do, if appearing early proves annoying in play.

Do NOT auto-scroll the emote list to the emote as QH does. That needs a
`runScript` from inside a render, which hard-froze this client twice during
the bank-filter work.

---

## Previous START HERE (written 2026-08-12, end of wave 26)

Main at **`ae9f062`**, PUSHED, tree clean. **Hub pin BUMPED to `ae9f062`
(2026-08-12) and the hub's own `build` check is PASSING.** The gap had been
**68** commits, not the 57 these notes claimed — count it, do not carry it.

**The first bump FAILED the hub** on a rule our build does not enforce ("Do
not create fresh Gson instances, always @Inject the client's Gson").
`HubComplianceTest` now catches that class locally. **When bumping the pin,
always wait for the check**: `gh pr checks 14207 --repo runelite/plugin-hub`.
A red X named "Requires maintainer review" is normal and fails by design; a
red `build` is real.

Run `node tools/check-all.mjs --tests` before handing over a
build, and `node tools/check-client.mjs` before ANY gradle command.

**POLICY (owner, 2026-08-12): SUPPORT BOTH PATHERS, GPS PREFERRED.** He
switched himself to **GPS** (`runelite.gpsplugin=true`,
`shortestpathplugin=false`) and rates it better, but Shortest Path stays
supported for users who want it — *"worth keeping Shortest path available
(incase certain users want it) but GPS seems better"*. **This costs us
nothing and requires no branching**, which is the whole reason the channel
choice below matters. GPS is a FORK of Shortest Path that keeps
`"shortestpath"` as a
documented compatibility namespace — it accepts inbound messages on either
and broadcasts on both — so everything we send and the transports listener
we added this morning work UNCHANGED. Keep posting on `"shortestpath"`: that
is what makes one message serve either plugin, where GPS's own `"gps"`
namespace would serve GPS users and silently do nothing for everyone else.
It honours the same `postTransports` override, and republishes when the
DISPLAYED route changes — so if the player picks a different alternative in
GPS's panel, our highlight follows their choice.
**Never run both**: one message would draw two routes.
His Shortest Path cost dials do NOT carry over (different config group).

**TWO ROUTE-INTERFERENCE FAULTS FIXED TONIGHT, BOTH UNPLAYED:**
- **We erased routes we did not draw.** Standing down for Quest Helper
  posted a `clear` on EVERY evaluation, and a clear wipes whatever is on
  screen whoever set it. QH's route died the moment anything made us
  re-evaluate — taking a teleport, for instance — and came back on "reload
  quest". Now a clear only fires when we have something posted.
- **Following the router's pick covered teleport ITEMS only**, so a route
  choosing a spell / home teleport / minigame teleport highlighted nothing.
  `applyRouterChoice` resolves all four, and now runs even while we have
  stood down for QH — a deliberate reversal of wave 23, on the owner's call,
  safe because it highlights the leg the router CHOSE rather than proposing
  our own.

**CONFIRMED IN PLAY on 2026-08-12:** QH's route survives a teleport (no
"reload quest" needed); GPS reads "Destination set by IRONSCAPE Optimal";
the router-choice follow highlights the leg GPS picked; step 274 blocked by
a bogus "Lit candle" is fixed; `::ironwrong` works and settled a bug in one
read.

**STILL UNPLAYED, all shipped late 2026-08-12 —** the panel scroll alignment
(`StepRow.scrollOffset`), the prescribed-transport rule, the Camelot
stairs routing, **routing to a live wandering NPC** (`errandRouteTarget`),
and both `::ironwrong` fixes. Also everything from earlier that day: kitten
gate, Chronicle pins, quest-NPC gating, note items.

**KNOWN AND NOT YET DONE:** step 276 is "start Holy Grail (King Arthur,
DOWNSTAIRS) then talk to Merlin UPSTAIRS" — two actions — and the chain may
not reflect that ordering (owner, 2026-08-12). Left alone deliberately until
the routing fix is confirmed, rather than changing two things at once.

**PLAY-TEST FIRST — a lot shipped today and most of it has never run in a
game.** In the order he will meet it:

1. **The panel scroll** — it should sit on the frontier step and STAY there,
   including after switching sidebar tabs away and back. Fixed from computed
   evidence, never watched. If it jumps: **`::ironwrong` before anything
   else** (still never used, `~/.runelite/ironscape/reports/`).
2. **The equipment-tab signpost** when a route picks a worn teleport item.
   The cloak outline itself is CONFIRMED; the tab highlight that tells you
   where to look is not.
3. **The kitten step** — should stay unticked until a Pet kitten is in the
   inventory, rather than ticking on walking up to Gertrude.
4. **The five bare "Chronicle tele" steps** — should tick on landing at the
   Champions' Guild and hand routing to the next step.
5. **Quest-NPC gating** — on an unstarted quest step the outline should pick
   someone in that quest's cast (Trufitus for Jungle Potion), not the nearest
   body. His cat is excluded outright either way.
6. **Note items** on 5 steps (Wintertodt warm clothing, the barb-fishing
   knife, Crandor swordfish, the Swordchick pair, Lunar waterskins).

Then **282** and **316**, both still unplayed from wave 25.

**Do not pin the hub until a session goes quietly.** 57 commits is a large
gap, but the cost of pinning a bad build is a review round.

**Standing lesson re-learned twice today:** write scripts to a FILE and run
them — hand-escaping JS through PowerShell failed both times it was tried.
And `Select-Object -First N` in PowerShell CLOSES the pipeline, which kills
a `gradlew run` mid-launch.

---

## Wave 26 detail (2026-08-12)

**WAVE 25'S VARROCK ANOMALY IS NOT A DEFECT — CLOSED.** The suspicion was
that our walked-distance table was cheating through the Plague City wall.
It was not: Shortest Path, asked directly, picked *the same Varrock
teleport*, because the real route is Varrock -> GE spirit tree ->
Battlefield of Khazard -> Ardougne wall door, and Khazard lands **40 tiles**
from that door. Our overlay and SP's line were agreeing all along. This is
the first thing DX-6 paid for: the question was settled by ASKING rather
than by re-deriving our own arithmetic.

**WHAT TO WATCH NEXT SESSION:** the SP-picks-a-teleport-item branch has
never fired. Every route observed chose a spell, because the owner's
`costNonConsumableTeleportationItems=50` against `costTeleportationSpells=15`
makes an item teleport lose all but the clearest wins — and on the one route
tested the cloak was genuinely worse (its only landings, Kandarin Monastery
and Ardougne Farm, are ~92 tiles from the wall door). He declined to change
the cost to force the case. So: when a route DOES pick an item, confirm the
equipment-tab outline and the destination label appear. If SP flips and our
overlay does not follow, that is a real bug and the log line to grep is
`shortest path picked a teleport item`.

Otherwise the wave 25 list below still stands: **step 282**, **step 316
Varrock easy diary**, and `::ironwrong` (still never used — no reports
folder exists).

---

## Previous START HERE (written 2026-08-11, end of wave 25)

Route position **270**, mid Ardougne. Main at `51a5a00`, everything pushed,
tree clean. Run `node tools/check-all.mjs --tests` before handing over any
build, and `node tools/check-client.mjs` before ANY gradle command.

**Play-test first, build second.** 44 commits have gone in since the last
version pinned for the Plugin Hub and much of it has never been seen in a
running game. In order:

1. **Step 282 "Continue Lost tribe"** — the last of the three annotation
   quest tags, unplayed since wave 19. Same mechanism as 263, which passed.
   Expect a stopping-point checkpoint on `varbit 532 >= 10`. Goblin
   Diplomacy at 281 is a PREREQUISITE the guide does immediately before, so
   watch for the out-of-order warning and its BACK TO STEP link — also
   never seen in play.
2. **Step 316 Varrock easy diary** — 14 seeded tasks, unplayed. The
   Ardougne one is confirmed working, so this is the same shape.
3. **The teleport hint's Varrock anomaly** (see wave 25). The reason line
   now prints the leg distance and the bar it had to beat; one occurrence
   settles whether the arithmetic or the input is wrong. First place to
   look is whether the walked-distance table thinks it can walk INTO West
   Ardougne through the Plague City wall.
4. **`::ironwrong`** is live — ask him to use it rather than describe a
   problem, and read `~/.runelite/ironscape/reports/`.

**Then, if there is desk time:** DX-6 (find out whether Shortest Path will
tell us the route it picked, so the hint stops computing a rival answer) is
the highest-leverage item, because it retires a whole class of report. DX-5
(index every teleport item) is the one the owner asked for and gets more
useful with every diary he finishes.

**Do not pin the hub until a session goes quietly.** The gap is large but
the cost of pinning a bad build is a review round.

---

## PLANNED — developer-experience work (agreed with owner 2026-08-10)

Four items, ranked by friction removed. Owner approved all four; **DX-1 started
first**. These are about the speed of the loop, not the plugin's behaviour —
every one of them exists because a fix that took two minutes to write took
fifteen to get in front of the owner, or because a defect was found by walking
into it rather than by a check.

**DX-1 — Load data files from disk so a data fix needs no rebuild or restart.**
Most corrections are DATA (kit items, pins, notes, `granted` flags), not code.
The Dig Site fix on 2026-08-10 was pure data and still cost a rebuild, a
two-minute safety wait and a client restart, mid-session, three times in one
evening. Annotations and places bundle into the jar, so today the only way to
change one is to build the jar again. Plan: an optional override FOLDER (config,
empty by default so nothing changes for real users) that is preferred over the
bundled copy when a file of the same name exists, plus a reload trigger, so the
repo file itself can be the live source during a play-test.
Prior work: `AnnotationManager` already merges a LOCAL file over the bundled one
for ⌖ captures — this generalises that idea rather than inventing it.
**Care:** reload must happen on the client thread, must rebuild the derived
caches (goal detection, place lookup, checkpoint maps), and must not disturb
progress or the frontier position.

**DX-2 — A pre-session briefing the OWNER can read.**
`tools/preflight.mjs` already computes it and prints developer output, so it
helps nobody unless Claude remembers to run it. Wanted: plain language, run
automatically at session start, and stated in his terms ("these two steps ahead
of you can only be ticked by hand"). ~89 hand-tick and ~39 no-route steps exist
guide-wide; without this they arrive one per session forever.

**DX-3 — Clear the quest-kit "granted vs bring" backlog in one sitting.**
Tonight's trowel was one instance of a class: kits listing items the quest hands
you, which sit permanently red. 100+ quests, checkable against the wiki in bulk,
then a click-through review page (the format that worked for item names —
see [[review-ui-for-input]]). Needs an evening of the OWNER's attention, so
schedule it rather than squeeze it in.
Prior work: `audit-quest-granted.mjs`, `quest-granted-reviewed.json`, and the
KIT-SEEDING POLICY already exist. This is the review UI plus a bulk wiki pass.

**DX-5 — Index EVERY teleport item and use it once it is unlocked. SHIPPED
2026-08-12 (`22bd457`, `a3ba9f5`). Index CONFIRMED loading; the
SP-picks-an-item branch UNPROVEN in play.**

`tools/seed-teleport-items.mjs` -> `travel/teleport_items.json`: **319
destinations, 225 item ids**, all from Shortest Path's own maintained table
(diary cloaks, jewellery, tablets, memoirs, max/quest capes) with landing
tile, the menu option to pick, and unlock/charge varbits. `TeleportItems`
loads it, `TeleportItemHintOverlay` outlines the item in inventory OR
equipment and labels it with the destination.

**THE LESSON, and it cost a build:** our own ranking could NEVER have
picked one. `TravelDistances` holds a precomputed field per NAMED landing —
25 of them — and looks up **by name**, so all 319 item landings answered
UNKNOWN and lost silently in walked-distance mode, which is most routes.
Adding 319 fields was the obvious fix and the wrong one; SP already ranks
this choice better than we can and now tells us. **Its pick outranks ours
whenever it names an item**; ours is the fallback when SP is silent.

**Two traps in their data, both handled and both TESTED**
(`TeleportItemsTest`): the SKILLS column also carries the max cape's TOTAL
level and the quest cape's QUEST points, which are not skills; and a `&`
condition is a BITMASK needing that exact bit, not a threshold — the
barcrawl trap. Everything unevaluable fails CLOSED.

**The possession check is load-bearing**: `useTeleportationItems` can be
`INVENTORY_AND_BANK` (the owner's is), so SP will route through a glory in
the BANK — a fine route and a useless highlight.

**THE DIAL THAT DECIDES HOW OFTEN THIS FIRES IS THE PLAYER'S, NOT OURS:**
`shortestpath.costNonConsumableTeleportationItems` (owner: 50) against
`costTeleportationSpells` (15). At 50 an item teleport must win clearly.
Lowering it moves SP's line and our highlight TOGETHER, which is the point
of not holding a second opinion. Do not add our own thumb on the scale.

**Original entry:**
Owner, 2026-08-11, having just earned the Ardougne cloak: the first-leg
hint knows minigame teleports, standard spellbook teleports, the free home
teleport and the Chronicle, and nothing else. It does not know about diary
cloaks, teleport jewellery (glory, games necklace, ring of dueling, skills
necklace, combat bracelet, digsite pendant), or the tablet family — so it
kept offering a Varrock teleport for a West Ardougne target while an
Ardougne cloak sat in the bag that lands next door. Wiki-search the lot,
index destination + charge/unlock condition, and let them compete like the
spells do. This grows with the account: every diary finished adds options,
and the hint should start using them the day they unlock.
Prior work: `TELEPORT_SPELLS` and `ELEMENT_STAVES` are the shape to copy;
`minigame_landings.json` is the shape for the data file. Charges matter —
"ring of dueling" is really `Ring of dueling(8)`, so the count is part of
the name (checked while seeding teleport items, wave 25).

**MOST OF THIS DATA ALREADY EXISTS AND IS MAINTAINED BY SOMEONE ELSE**
(found 2026-08-12 while answering DX-6): `tools/.sp-cache/teleportation_items.tsv`
(31KB, already in our cache) is Shortest Path's teleport-item table — one row
per destination with the item ids that provide it (`13121=1||13122=1||…`, i.e.
any cloak tier), the destination coords, the display option
("Ardougne cloak: Kandarin Monastery"), consumable flag, wilderness limit, and
the **unlock varbits** (`4458=1` = the diary tier). Diary cloaks, jewellery and
tablets are all in there. Do NOT hand-seed this from the wiki.
Note DX-6 makes much of DX-5 moot for the HINT specifically — SP already picks
teleport items when routing. DX-5's remaining value is the panel telling you
what you are CARRYING that would help, and coverage when SP is not installed.

**DX-6 — Stop second-guessing Shortest Path's route. ANSWERED 2026-08-12: YES.
Listener shipped (`dbbd9f7`), NOT play-tested, nothing consumes it yet.**

SP's `postPluginMessages()` fires on pathfinding completion and posts
`PluginMessage("shortestpath", "transports", …)` carrying four parallel lists —
`origin` / `destination` (`List<WorldPoint>`) and `objectInfo` / `displayInfo`
(`List<String>`) — one entry per transport on the route it chose, in travel
order. `displayInfo` is documented as *"the destination option to pick"*
(`Ardougne cloak: Kandarin Monastery`, `Barbarian Assault Minigame Teleport`,
`Travel Spirit tree`), `objectInfo` is the object to click. That is exactly
what the hint needs, decided by the side that knows the player's real unlocks.

**Verified, because each of these could have killed it:**
- `postTransports` **defaults to false** (Debug section) — but `override()`
  reads their static `configOverride` FIRST, and our own `"path"` message can
  set it via the `"config"` key. No user setting to find.
- **The hub-released build has it** — checked the plugin-hub pin
  (`9953d527…`), not just master, and `postPluginMessages` is byte-identical
  there. Coding against master would have been the trap.
- **The callback runs on SP's pathfinding worker thread**
  (`Pathfinder implements Runnable`, `completionCallback.run()` at the end of
  `run()`). Store-and-log only; hop to the client thread before touching
  anything.
- Their `configOverride` is a static that is **cleared by any `clear`**, so the
  config must ride on EVERY path post — hence one `postPath` / `postClear`.

**Limits:** transports only, so a walking route comes back EMPTY (meaningful —
"offer no teleport" — hence empty ≠ null), and there are no distances, so the
walked-distance model stays as the fallback when SP is silent or absent.

**Next:** get real payloads on screen (`grep "shortest path chose:"`) before
wiring the hint to them. Guessing at the strings is the wave 15 mistake.
This is also the first thing to try against wave 25's Varrock/West Ardougne
anomaly — if SP names the transport it picked, our rival arithmetic stops
mattering there.

**Original entry:**
Owner's question: how do Quest Helper and friends send data to Shortest
Path, and why do we not just do that? **We already do** — the same
documented PluginMessage API ("shortestpath" / "path" / target), and we
post a destination for every step. What SP owns is the ROUTE, transports
included. Our first-leg hint is a SECOND, cruder journey calculation done
here, and its only real job is to highlight the button to click, which SP
cannot do. That duplication is exactly why the two disagree on screen.
Worth investigating: does SP publish its chosen path back (a message, a
readable state) so the hint can highlight the leg SP ALREADY PICKED
instead of computing a rival answer? If it does, the whole first-leg
distance model becomes a fallback for when SP is silent, and the class of
"our overlay says one thing, the line says another" reports disappears.

**DX-4 — A "something is wrong here" button in the panel.**
Captures position, step, sub, what nav was routing to and why, and the recent
decision lines, into one file. Today a report costs Claude a reconstruction from
four other log lines, and it only went smoothly on 2026-08-10 because the log
was open live. Do this once Claude is no longer watching the log in real time.

---

## Reconciliation summary — what the session log already covers

| Original assumption | Reality per CLAUDE.md |
|---|---|
| Substeps need designing and building | **Errand chains already do this** — ordered stages with coords, items, NPCs, dialog, per-stage radius, route/satisfaction split, varbit/varp gates, waypoint stages, sticky first-unsatisfied, chain-complete = nav HOLD |
| Quest state seeding is a new system | **Mid-quest varbit/varp checkpoint annotations exist** and override heuristics entirely (wave 6). `cachedQuestState` exists (wave 7). Partial-quest cases are *seeding*, not architecture |
| Requirement scoping is an undecided policy | **KIT-SEEDING POLICY exists** (owner, 2026-08-05). Mid-quest-handed items never carry requirements. The policy is written; enforcement is the gap |
| GP cost display is missing | **`seed-gp-costs.mjs` exists**, 30 buy steps seeded (wave 7). The hunter shop step costs **12gp** — under the seeder's deliberate 100gp floor, so its absence is correct (wave 12) |
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
**ANSWERED (wave 12): Tier 1 = 0.** The origin-anchored class P0-01 belonged to no longer exists. Tier 2 = 18, almost all Chronicle/minigame-teleport steps where the 📍 fallback is correct. NONE = 3, all advice steps that *should* be manual-tick.
**Tooling exists. Do not rebuild.**
```
gradlew test --tests "*.GoalAuditDumpTest"     # writes build/arrival-audit.tsv
node tools/audit-arrivals.mjs                  # Tier 1
node tools/audit-arrivals.mjs --all            # + Tier 2 + NONE
```
Report Tier 1 (origin-anchored PIN arrivals — the SS-01 class), Tier 2, and NONE counts.

---

### INV-C — Which compound steps need errand chains?
**ANSWERED (wave 12).** Only steps whose two places belong to the SAME step and sit far apart qualify — Hazelmere, now seeded. `questStatus` is only ever start/complete/absent, every "do X until the part where…" step already had a checkpoint or chain, and for "Do Dwarf cannon until…"-style steps the place named in the text is where the NEXT step goes.
Guide is `GuideVariant.OZIRIS`, 575 steps, 7 sections
(`src/main/resources/com/ironscape/guide/guide_data_oziris.json` + `annotations_oziris.json`, 82 annotations).

List steps describing a multi-leg journey **not** already covered by an `errands` chain — SS-12
(*"Go to Hazelmere and continue the grand tree until you are at Karamja shipyard"*) is the exemplar.
`audit-nav` already reports 169/575 uncovered; cross-reference rather than starting fresh.

**Note:** the Grand Tree shipyard checkpoint (**varp 150 >= 80**) is *already seeded*. So SS-12 has a
completion checkpoint but no chain to navigate the legs. That's the shape of the gap.

---

### INV-D — Charter/boat network selection
**ANSWERED (wave 12): no defect.** Selection is "nearest charter dock, if closer than the destination". The SS-15 cases are BOAT steps whose routes the guide names — the plugin never chose them. See D3.
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
**DONE (wave 12)** — seeded `varp 71 >= 2`, the point where Redbeard has his rum.
**SS-16, SS-17** · **Prior work:** mid-quest varp/varbit checkpoint annotations (wave 6) — the exact mechanism.
Pirate message id 433 already bundled.

**This is a seeding task.** Pull the Pirate's Treasure stage varp from Quest Helper source via
`tools/qh-lookup.mjs` and add checkpoint annotations sub-keyed to the "Karamja and port sarim parts" step, the
same way Grand Tree (varp 150>=80), Waterfall (varp 65>=3) and Lost Tribe (varbit 532>=5/6) are already seeded.

**Broader question worth raising with the owner:** how many other guide steps describe *part* of a quest? Those
are the ones that mis-report on accounts with prior progress. `PrintSubIdProbe` prints the ids for authoring.

---

### P0-04 — Chronicle teleport ticked the Castle Wars step
**DONE (wave 12), confirmed in play** — the jump must land within `TELEPORT_ARRIVE_RADIUS` of `travelDestination()`.
**SS-06** · **Prior work:** teleport position-jump detector; wave 5 widened post-teleport arrive radius to 45;
wave 7 network-travel arrival requires the text destination to prove arrival.

**Diagnosis:** a position-jump teleport satisfied a teleport-type sub without verifying *which* destination. The
network-travel branch already solved this shape for charter/spirit-tree — extend the same "text destination or 📍
proves arrival" rule to minigame/Chronicle teleport subs.

**Also consider:** a varp checkpoint on the Castle Wars sub would make it immune, per the wave 6 precedent that a
sub with a checkpoint completes only off it.

---

### P0-05 — Nav re-routes mid-quest instead of standing down
**DONE (wave 12)** — new opt-in `Errand.hold`; blanket stand-down stays rejected.
**Prior work:** `questHelperOwnsGuidance` — wave 2 deliberately made mid-quest nav route to the step's 📍 area
rather than clearing. Wave 7 fixed a *separate* QH interference (clientscript load).

**So this is working as designed, and the design is wrong for compound steps.** When a step's remaining work is
"continue quest X until Y", routing to the step's 📍 area actively fights QH.

**Fix:** full **nav HOLD** while a quest-progress stage is live — the same behaviour chain-complete already
implements. See P1-06.

---

### P0-06 — Travel NPC not offered; wrong NPC highlighted
**DONE (waves 11-12)** — travel NPCs + Cromperty + the sixth boat step keepered.
**SS-14** (tooltip reads `Attack Foreman (level-23)`) · **Prior work:** named-NPC scan with named-beats-nearest
(waves 5–6); `shop_npcs.json` with keepers/bartenders; `CHARTER_DOCKS` network.

**Diagnosis:** travel NPCs aren't in the named roster, so the sub fell back to nearest-to-pin and crowned the
Foreman. Bartenders and shopkeepers were seeded for exactly this reason — **do the same for travel/charter NPCs.**

**Done when:** "Take boat to Port Sarim" outlines the correct travel NPC and highlights the travel menu entry
(`TravelMenuOverlay` already handles interface 187 / MenuNew 947 / group 72).

---

## P1

### P1-01 — No route out of the Essence Mine
**DONE (wave 10)**.
**SS-03** · **Prior work:** the ZMI precedent — "SP can't path into cave interiors — anchor routable points at
entrances" (wave 5). Errand stages have a **route/satisfaction split** for precisely this.
**Fix:** anchor the ess-mine exit at its surface-routable point, same pattern as ZMI at Ourania Cave 2452,3231.

---

### P1-02 — Routed to Fishing Guild bank instead of King Narnode
**SS-04, SS-05** · **Prior work:** BANK-FIRST fires on any banked shortfall, including unowned items (wave 8).
**Almost certainly a consequence of P0-02** — bogus requirements triggered BANK-FIRST. Retest after INV-E.

---

### P1-03 — Karamja step ticked in the field, not at the docks
**DONE (wave 12)** — the PIN was in the jungle at 2843,3070, ~120 tiles from any landing. Now Musa Point 2904,3162. No radius change needed.
**SS-13** · **Prior work:** wave 5 widens arrive radius to **45** after a teleport; wave 7 makes charter subs prove
arrival by text destination or 📍 only.
**Diagnosis:** either the boat landing reads as a teleport (radius 45 covers the field) or the "Karamja" place pin
sits in the field. Check the pin first — cheaper.

---

### P1-04 — Wrong dock chosen; legs inconsistent
**NOT A DEFECT (wave 12)** — those are boat steps whose routes the guide names. See D3.
**SS-15** · **Gated on INV-D**, then D3 sticky transport.

---

### P1-05 — Barcrawl card and coins not required at the pub
**DONE (wave 12)** — card seeded on all ten drink steps; coins stay off (a drink is a few gp).
**SS-09** · **Prior work:** wave 6 barcrawl stamp varp-bit checkpoints (varp 77, per-bar bits); Barcrawl card
sprite id 455 bundled; wave 7 re-seeded bar pins to QH's exact bartender WorldPoints and dropped the keeper's
purchase-goal gate; coins deliberately **excluded** from the arrival gate so mid-step spending can't wedge a
destination tick.

**What's actually missing:** the Barcrawl card as an annotation **item** on the drink steps, so the panel warns
before the walk. Coins are a display-only need here — do not re-add them to the arrival gate.

---

### P1-06 — Compound steps don't chain (Hazelmere → shipyard)
**DONE (wave 12)** — Hazelmere seeded as two stages, the second an `Errand.hold`.
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
**NOT A DEFECT (wave 12)** — that step costs 12gp; the seeder skips <100gp on purpose.
**SS-10** · **Prior work:** `seed-gp-costs.mjs`, 30 buy steps seeded (wave 7); SS-19 proves the badge renders.
**Data gap.** Re-run the seeder over the remaining buy steps; hand-set where the wiki value is missing (Barrows
gloves and Zeah compost are the existing hand-set precedents).

---

### P2-02 — Shop interface items not highlighted
**DONE (wave 12)** — `ShopItemHintOverlay`, matches by NAME (shop stock has no inventory id yet).
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
**DONE (wave 12)** — container resolved at render time; the worn-slot hint is unchanged.
**SS-18** · **Prior work: added deliberately in wave 9** — "Chronicle worn-slot teleport hint (equipment tab
STONE4 → `WornItems.SLOT5`, labeled)".
**Not a bug — an unhandled case.** The Chronicle was in the inventory, not worn. Resolve the actual container at
render time and point at whichever holds it. **Do not remove the worn-slot hint.**

---

### P2-05 — Outline doors, stairs, ladders
**DONE (wave 12)** — a stage with a route/satisfaction split outlines the traversal object at its route point.
**SS-19, SS-20** · **Prior work:** `ObjectTargetOverlay` outlines ore rocks, chests, grind objects, with
impostor resolution and goal-item icon overhead; `ModelOutlineRenderer` gives QH-crisp silhouettes.
**Extend the existing overlay** to traversal objects on the active route. Errand stages already model interior
legs via route/satisfaction, so the data to know *which* door is often present.

---

### P2-06 — Match QH icon styling
**SS-19** · Cosmetic. `ModelOutlineRenderer` already shipped for this reason. Low priority.

---

### P2-07 — Pirate's message ticked on pickup, not on read
**NOT ACHIEVABLE as specified (wave 12)** — QH tracks the read by holding the item, not a var.
**SS-19** · **Prior work:** pirate message id 433 bundled (wave 9); checkpoint annotations complete a sub only off
the checkpoint (wave 6).
**Fix:** varp/varbit checkpoint on the read, sub-keyed. Same pattern as the barcrawl stamps.

---

### UX-01 — Distinguish manual overrides from auto-completions
**DONE (wave 12)** — hand ticks stored per profile in `manual_<VARIANT>`, per step AND sub.
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

## CLOSED IN WAVE 12 (2026-08-08, main at `54774f3`, pushed)

Wave 11's "next session" list is fully cleared, and a backlog pass took the rest. Anything below marked
**DONE** in the P-sections is shipped; three entries turned out **not to be defects at all**.

**Confirmed in play:** P0-04 (held "Use mind bomb and camelot tele" from inside the ess mine), the
Merlin's Crystal checkpoint + Gawain→Lancelot chain end to end, the handoff banner (its first ever
firing — wave 11 fixed it but nobody had seen it), the mixed-purchase-list goals, the mind bomb badge
and inventory outline.

**Shipped but NEVER exercised — test these first:**
- the **gangplank gate**. The release valve means it cannot wedge, but nothing has confirmed it *holds*.
  Still open: which of the **six** boat trips end at a plank versus dropping you on the dock. (Wave 11
  listed five; `81b0064c8c` "Boat back to Karamja" was missed, and is now keepered to the Customs officer.)
- **persisted acquisition baselines** — only provable across a restart: buy, restart, confirm it still ticks.
- the **shop overlay**. Most likely failure is over-matching: name matching runs through the alias/family
  rules, so a broad stock list could light up every axe tier when the step wants one.
- **PAR / Holy Grail** start checkpoints, the **staircase outline**, **per-NPC shop icons**, the
  **Karamja pin** at the docks, the **manual-tick tooltip**.

### Not defects — do not "fix" these
- **P2-01** (no gp cost on the hunter shop step): that step costs **12gp**. The seeder skips anything
  under 100gp deliberately, and re-running it applied **0** changes — wave 7 already covered everything
  meaningful. A "coins 0/12" badge is noise.
- **P2-07** (pirate message ticks on pickup, not read): the proposed varp/varbit checkpoint **cannot
  exist**. QH tracks the read with a conditional step on *holding the item*, not a var; reading it moves
  nothing. Would need a widget-text detector — new machinery for one step.
- **D3 sticky transport**: the SS-15 "wrong dock" cases are **boat** steps whose routes the *guide* names
  ("Take the boat from Ardy docks to Rimmington"). The plugin never chose them. Dock selection for real
  charter subs is "nearest dock, if closer than the destination", which is sound.

### Also learned
- `questStatus` is only ever `start` / `complete` / absent — there is no hidden part-quest population, and
  every "do X until the part where…" step already had a checkpoint or chain. The gap was **start** steps
  carrying extra actions, now swept (3 of 33 needed seeding).
- Hold chains are only worth it when both places belong to the **same step and sit far apart** (Hazelmere).
  For "Do Dwarf cannon until…", "Continue Lost tribe until…" etc. the place named in the text is where the
  **next** step goes; each step's own work is in one area, so routing to its 📍 stays correct.
- Verifying `granted` needs the quest's wiki **"Items required" annotation** ("(obtained during the
  quest)"), not mere presence in the list. That distinction overturned four items in both directions.

---

## NEXT SESSION — start here (rewritten 2026-08-09, end of wave 18)

**Run `node tools/preflight.mjs` before playing.** 89 hand-tick steps now
(was 101 at the start of wave 18).

### 1. Confirm the Lady of the Lake's OUTLINE — the only unverified fix

Everything else from wave 18 was confirmed in play. This one was fixed
after you had already left her. Untick the step, stand next to her, and
check she gets a silhouette.

Two causes were fixed: her name carries an article the step omits, and a
duplicate pin (`lady of the lake in taverly`) was suppressing her. If she
is STILL dark, it is a third cause and the log will not show it — say so
and I will read `NpcTargetOverlay` directly rather than guess again.

### 2. Three captures need your call

Held back from the harvest because Shortest Path cannot draw a route into
a cave interior, and the rule since wave 5 is to anchor at the entrance:

- blurite mine (`bbbd9a9020` → 3036,9582)
- ZMI Zamorak warriors (`b3a38e5fec` → 3018,5615)
- Brimstail ess mine (`06b3df5fd7` → 2409,9812) — this one already has a
  bundled ENTRANCE pin at 2403,3418 that yours would override

If you meant them as the precise SPOT rather than the route target, the
mechanism is an errand chain's route/satisfaction split, which expresses
both. Your call per pin.

### 3. Then, in value order

- **Target drift** (wave 13's list) — now the single biggest driver of
  manual pinning. Per-step ⌖ captures; only you can make them. Wave 13
  tried and failed to find a general rule twice, so this is hand work.
- **`{{ObjectLocLine}}` parsing in `seed-facilities`** — cheap, and
  `seed-npc-spots` already has the parser. Unlocks the pottery wheel;
  also fix `Potter's_wheel`, a redirect `action=raw` will not follow.
  Range and Altar are NOT fixable this way — no location list exists.
- **The ~7 genuinely pinnable steps** out of the 39 (smith dart tips,
  mithril grapple, ammonite crabs, compost bins, toadflax, tree spirits,
  molten glass+gems). The other ~32 want a LABEL, not a pin.

### Standing habits worth keeping

- **Harvest ⌖ captures at the end of every session.** 47 had accumulated
  locally and none of them shipped. That is the whole point of you making
  them.
- Hub pin still `3638c2f`, now **10 commits behind**. Bump after a calm
  session — wave 18 was not calm.

---

## Previous session list (written 2026-08-09, end of wave 17)

**Run `node tools/preflight.mjs` before playing.** It now knows all of
`targetFor`'s sources, so its numbers can be trusted — which they could not
be for most of wave 17.

### Unproven, in the order you will meet them

1. **"Continue Biohazard"** (`varp 68 >= 14`) and **"Continue Lost tribe"**
   (`varbit 532 >= 10`). Both on your route, neither has ever fired. If either
   ticks early or never, the value is wrong and I want to hear it from you
   rather than assume.
2. **The narrowed inventory outlines.** Watch whether the one item you need is
   the one glowing. On a step with no detected goal you now get the numbered
   requirements only — if that ever feels too sparse, the fallback is the thing
   to loosen.
3. **Green outlines against scenery.** Item sprites sit on a dark inventory
   background; a green NPC silhouette on grass is a different test. There is a
   **Highlight colour** setting now — retune it yourself, no rebuild needed.
4. **Keep Le Faye's descent legs** still cannot be reached on this account.
   Needs a fresh Merlin's Crystal or another one-way interior.

### Then, in value order

- **23 travel steps with no travel goal** — the biggest remaining detector
  win, and the one that wants you at the keyboard because of its blast radius.
- **39 unroutable steps** guide-wide. Sampled: most are grinds ("do slayer
  until 65") where a pin would be arbitrary. Genuinely pinnable stragglers:
  "Mine 15 clay south of Khazard", "Camp Cave horrors for mask".
- **The wave 13 target-drift list** — per-step ⌖ captures, which only you can
  make.

### Plugin Hub

Nothing is failing: the red check is the standard "requires maintainer
review" gate. The networking flag ron pointed at is cleared. **His HTML
remark is unanswered** — there is no HTML in the properties file, PR body or
README, only Swing JLabel markup, so it needs specifics before anyone acts.
Also worth asking him whether an auto-accept path exists; if it does,
clearing flags beats waiting.

**Pin stays at `3638c2f`.** When it moves it should point at a build that
INCLUDES the java.net removal, or the review will look at the version that
raised the question.

---

## Previous session list (written 2026-08-09, end of wave 16's play-test)

**Run this first, every session, before you play:**

```
node tools/preflight.mjs
```

It reads your saved route position and says what the next 15 steps can and
cannot do. Tonight's three complaints were all in it before they happened.

### Look at these two labels first — they are new and unseen

**Built overnight, needs your eyes, not your debugging.**

- Steps nothing can complete now carry a muted **"tick by hand — nothing here
  to detect"**. 107 steps guide-wide; most are genuinely advice.
- Items with no quantity are tagged **"(bring some)"** — the guide's running
  carry advice, as opposed to a requirement with a number.

Wording and placement are guesses; say if either is wrong.

**One thing nearly shipped as a lie and is worth knowing about:** the first
version labelled "Run south to Port sarim" as manual-only. It has no detector
at all, but it ticks on ARRIVAL, and so do 32 others. Both the panel and
`preflight.mjs` now test the same movement-instruction-plus-resolvable-place
pair `currentSubSatisfied` does. That is the difference between 139 and 107,
and between two wrong labels in your next twelve steps and none.

### Then, in value order

1. **23 travel steps have no travel goal** — "Run to draynor village", "Walk to
   Falador". The travel detector exists and covers 52 other steps, so these are
   a gap with a shape, not a missing feature. Worth finding why before changing
   the detector: it has wide blast radius and `GoalDetectorTest` is the guard.
2. **74 of them are genuinely advice** ("Use Authenticator AND 2-step
   verification"). Those want the label above, not a fix.
3. **6 talk steps and 5 combat steps** could tick — "Talk to juliet", "Kill a
   giant bat (Rag and bone man)". `LOOT_FOR_ITEM` (wave 8) should have caught
   the combat ones; find out why it did not.
4. **19 "Continue quest X" steps** are mid-quest and correctly untickable by
   quest state. They want varp checkpoints — seeding, per step.

### Still unverified from tonight

- The **second staircase and both descent legs** in Keep Le Faye. You are past
  the crate, so this account cannot reach them; needs a fresh Merlin's Crystal.
- **Optional legs.** The flax and Sherlock legs now survive the item cascade
  and ask within 40 tiles. Untested in play.
- **The QH stand-down.** Verify our line no longer fights QH's on a quest step,
  and that turning the QH plugin OFF brings our routing back.
- **Chain-completion ticks.** Confirmed once tonight on the Merlin step.

### Two open questions on the flax leg
Proximity still satisfies it, so walking within 12 tiles counts as done whether
or not you pick anything — that was the old anti-wedge choice, and `optional`
now handles wedging instead, so the reason is gone. Making it an item leg would
need actual flax. And `Errand.item` has no quantity, so "5 flax" cannot be
expressed without a new field. Both are your call.

---

## Previous session list (written 2026-08-08, end of wave 16)

Wave 16 was a desk session. **Nothing in it is play-tested**, and it changed
the rule that decides how far along every errand chain you are — so the first
job is watching chains behave, not adding more.

### 1. Walk the Keep Le Faye chain end to end

Seven legs now instead of two. What to watch, in order: the crate outlines and
routes from Catherby; the first staircase; **the second staircase** (this is
the one that never existed — the chain used to stop here); Mordred; then the
two descent legs, which are the half that has never been guided at all.

The interior legs draw **no Shortest Path line on purpose** — the log says
`holding: stage draws no route`. If SP starts drawing something anyway, that
is a finding.

**Not seeded, deliberately: the front door.** QH does not model it either. When
you are standing at it, right-click ⌖ and capture — that is the whole fix, and
guessing the object name was the wave 15 mistake.

### 2. Watch the dialogue line

`grep "dialog-highlight:"` — it prints the options offered and the options
wanted, side by side. Two things were provably wrong (QH's string is missing a
word, and the stage had stood down twenty seconds before the player chose);
both are fixed. What is still **unconfirmed** is whether the first option
recoloured, because that path logged nothing and nobody could tell. One
Merlin's Crystal conversation settles it.

### 3. Check nothing else regressed

The order rule changed for **every** chain: positions are judged only at the
front now. `ErrandProgressTest` walks the keep journey and was verified to fail
under the old rule, but the corpus is 14 chains and only one is tested. The
shape to watch for is a chain **wedging** — sitting on a leg you have plainly
done. The look-ahead that rescues a skipped leg is deliberately kept for items
and vars, so a wedge would mean a WAYPOINT you walked past out of order.

### Then the wave 15 and 14 lists below, unchanged

Wave 15's remaining item was the exit, which is (1) above. Nothing in wave 14
is play-tested except the first-leg hints.

**Hub pin stays at `3638c2f`.** Three unverified waves now sit on top.

---

## Previous session list (written 2026-08-08, end of wave 15)

**Two things, in this order. Both are MODEL work, not seeding.**

### 1. Plane-aware region stages — the biggest one

`Errand.region` (wave 15) got navigation INTO Keep Le Faye. It ignores plane, so
it cannot tell floors apart: the chain guided up the FIRST staircase and then
stopped, and it cannot guide back down and out at all.

Shortest Path will never solve the exit — the crate is one-way and SP has no
modelled path out of the interior, which is why it drew a Lumbridge home
teleport from in there. **The exit wants OUR object outlines**, which already
exist for this: wave 12's "a stage with a route/satisfaction split outlines the
traversal object at its route point".

So: a stage condition that is region AND plane, plus stages for each traversal
leg (up to p1, up to p2, back down, out the door), each outlining its own
staircase or door. Decide the model first — this chain went waypoint -> route
point -> region stage across three builds before it was right, and each patch
was made in sequence rather than modelled.

**Then sweep it.** Every "go through this thing" leg in the guide is the same
shape and is currently approximated with proximity coordinates: cave entrances,
boats, trapdoors, ZMI, Brimstail. That is the real payoff.

### 2. Morgan's dialogue never recoloured

Seeded on the gate stage with QH's exact strings, and it did not fire in play.
NOT case: `dialogKey` strips punctuation and lowercases, so "Ok I will do all
that." and the game's "OK I will do all that." both normalise the same. So the
fault is upstream of the comparison — most likely the active stage's `dialog`
never reaching `highlightStageDialog`. **Read the session log before touching
code**; this is a code path, not data.

### Then the wave 14 list below, unchanged
Nothing in wave 14 has been play-tested except the first-leg hints.

### Standing lessons from wave 15
- **Seed SUBTRACTIVELY**: `node tools/qh-tree.mjs "Quest" --state N --draft`,
  then delete what the guide's step does not own. The additive way missed four
  legs on one chain.
- **A waypoint models "be here", never "do this."** Actions need a var, an
  item, a region, or nothing.
- **Read the audit line by line** for the chain you are standing in.
  `audit-errand-chains` printed the missing crate leg twice and it was skimmed.
- **Never call `panel::refresh` from a per-tick path.** It blanked the guide.

---

## Previous session list (written 2026-08-08, end of wave 14)

Wave 14 was a desk session. **P1-08 is closed**; everything else was audit
work, and **nothing in wave 13 or 14 has been play-tested.**

1. **Wave 13's list below is untouched and still first** — the compost step
   (`5bf54fe229`), the 11 revived quest steps, the Go button on un-pinned steps.
   Two of its open questions are now settled and need no play: the Fremennik
   lyre is `granted`, and the Rag and Bone Man start step stays as it is (the
   pots and logs belong to the burning steps much later; if you want the nag,
   the fix is moving that kit, not gating the start).
2. **Watch the first-leg hints.** They now rank by walked distance. The
   forensic line says which metric it used — `(walked distances)` or
   `(straight lines)` — so `mine-session-log.mjs | grep teleport-hint` tells
   you whether the table was in play. The case to re-run is the one that
   started this: from a distance, "Kill Mordred and get bat bones/black
   candle" should now offer **Camelot Teleport**, not Burthorpe.
3. **The Karamja boat still needs its second data point** (#214). No
   behaviour changed. The gate lines now carry what the crossing recorder is
   holding, so one more trip says whether the click was never recorded or
   recorded-then-rejected. `grep "boat gate:"`.
4. **Two review lists are waiting**, both now filtered down to things that
   matter rather than dumps:
   - `node tools/audit-pin-reachability.mjs` — **14 pins the guide names that
     Shortest Path cannot stand on.** `castle wars` (25 tiles out) and
     `pest control` (13) are on the route. The suggested anchor is the nearest
     standable tile, which is mechanically right and not always semantically
     right — read before applying.
   - `node tools/audit-quest-start-pins.mjs` — **10 to review** (was 24).
     `creature of fenkenstrain` records its giver as a *signpost* while QH
     talks to Dr. Fenkenstrain 89 tiles away; that one looks like a seeding
     artifact.
5. `node tools/audit-first-legs.mjs` lists the 188 targets whose first leg the
   straight line got wrong. Nothing to fix — it is the regression tool for the
   class, and a spot-check against your own game sense is worth more than
   anything I can assert about it.
6. Long-standing and still open: deliberate death test, onion-gate capture,
   "big frog leg" -> 7908 verdict, Gertrude's Cat ticking on quest start.

**Hub pin stays at `3638c2f`.** Two waves of unverified work now sit on top.

---

## Previous session list (written 2026-08-08, end of wave 13)

Wave 13 was a desk session with the owner away. **Nothing in it is play-tested.**

1. **Play-test the wave 13 change first**: `5bf54fe229` ("Buy 1 pack of normal compost and all
   farming tools") must now wait for all five tools AND the pack, in either buying order, and must
   still tick after the tools go into the leprechaun. If the tools were ALREADY in the leprechaun
   from an earlier visit nothing can see them — tick by hand; the note on the step says so.
2. **Watch the 11 revived quest steps.** They previously could not auto-tick at all (no quest
   goal -> arrival only -> gated on a kit the quest consumes). Now 9 tick on quest completion and
   3 on quest start. The one loosening to watch: `5291ef1de9` "Start Rag and bone man on the way
   to the temple" used to need its pots/logs/tinderbox via the arrival gate and now ticks when the
   quest starts. If that is wrong, the fix is a varp checkpoint, the same shape as wave 12's
   quest-start sweep.
3. **Exercise the never-run list** (unchanged from wave 12), gangplank gate first. Wave 13 found
   statically that **all six boat destinations have a plank**, so the release valve never fires and
   the gate is load-bearing on every trip. Watch `mine-session-log.mjs | grep "boat gate:"` for
   `holding, gangplank loaded but not crossed` then `ashore, gangplank crossed`.
4. **The 35 target-drift steps** — wave 13 tried and failed to find a safe general rule (see
   CLAUDE.md wave 13 for why distance and place-type both collapse), so these really are per-step
   ⌖ captures. The triage of which are real hijacks vs correct-as-is is in that same entry; do the
   real hijacks and skip the rest.
5. **One open data question**: the Fremennik Trials lyre (`80a3ae4d44`). The wiki calls it a drop
   from the trial NPCs "or the skills and materials to make one", so it is obtainable in-quest —
   should it be `granted` (muted, never routes) or stay a red requirement? Everything else the
   granted audit flagged is now settled; `node tools/audit-quest-granted.mjs` reports exactly this
   one item.
6. Gertrude's Cat still ticks on quest start — the wave 13 gating change deliberately does NOT
   cover it (its items are the scraper's null-quantity carry list, not its objective). Still needs
   a "use X on Y" detector or leaving alone. **Owner's call.**
7. **P1-08 barrier-blind first-leg hints** (below) — a design question, not a quick fix.
8. Long-standing: deliberate death test, onion-gate capture, "big frog leg" -> 7908 verdict.

**Hub pin is at `3638c2f` and should NOT move** until wave 13 has been through a play session.

---

### P1-08 — First-leg hints can't see a BARRIER — **CLOSED (wave 14)**

**The connectivity question, answered before designing anything:** the 25
transport TSVs are **not** enough on their own. They model *crossings*, not
terrain, so nothing in them can tell you Burthorpe -> Keep Le Faye is a
531-tile walk. And connectivity was the wrong question anyway — White Wolf
Mountain is walkable, so Burthorpe and Keep Le Faye are connected. What was
needed is PATH DISTANCE.

The data for that is SP's `collision-map.zip` (1.2MB, two bits per tile:
can-step-north, can-step-east), which the hub does not stop us reading. Neither
half suffices alone: collision without transports still ranks Burthorpe first
(531 vs Port Sarim's 692); transports without collision cannot see the mountain.
Together they reproduce the right answer.

**Shipped:** `tools/build-travel-distances.mjs` runs SP's search offline at full
tile resolution and bundles a distance field per landing, 32-tile cells, 25
fields, **52KB gzipped**. No collision map in the jar, no runtime search, no new
thread. `firstLegTowards` measures candidates with it and keeps straight lines
for the player's own leg, which cannot be precomputed from an arbitrary position.

**Measured, not asserted** (340 (player, target) pairs from the guide's own pins,
scored against full-resolution truth): the right landing **64% -> 84%**, the
fire/don't-fire call **82% -> 87%**. Two things the measurement killed:
straight-line is **not** a lower bound on travel (32% of pin pairs travel
shorter, because a boat costs nine tiles and the water is hundreds), and
scaling the player's leg by the median walk ratio to make the legs comparable
scored **worse** on both counts.

A coarse navigation graph answering arbitrary-to-arbitrary was built and
rejected: 110KB for 92% agreement, against 52KB for a perfect landing ranking.

`node tools/audit-first-legs.mjs` is the regression tool — 188 of 558 targets
got the wrong landing under the straight line, 21 of them with no ungated
walking route at all from the landing it chose.

<details><summary>Original report (2026-08-08, live)</summary>

**Observed:** on "Kill Mordred and get bat bones/black candle", the hint offered a **Burthorpe Games
Room** minigame teleport as the first leg toward Keep Le Faye (`2757,3401`). The owner took it,
walked out, and found Shortest Path proposing a Lumbridge home teleport — reading, reasonably, as
"navigation is not working".

**Nav was right.** `auto-nav: routing to errand stage (2757,3401) for bat bones` — Keep Le Faye,
correct for the step. The Lumbridge tile was SP's own transport suggestion, never ours.

**The fault is the hint.** `firstLegTowards` ranks candidates by EUCLIDEAN distance: Burthorpe is
~214 tiles from Keep Le Faye against ~318 from Port Sarim, so it wins easily. But Keep Le Faye is on
the far side of **White Wolf Mountain**, whose tunnel is gated behind Fishing Contest — which the
owner had only just started. The hint teleported him into a corner, and SP then had to route the
long way round.

**This is wave 10's "distance fiction" again**, one class over. There, dungeons at y≈4830 read as
1,372 tiles away and a Barbarian Assault teleport won the first leg while the exit portal sat three
tiles off; fixed by testing the surface BAND. Same mistake here, with a landmass barrier rather than
a plane offset. Euclidean distance cannot see a mountain.

**Why it is not a quick fix, and what NOT to do:**
- The honest fix needs PATH distance, which only SP's pathfinder has, and the hub forbids reaching
  into it across classloaders.
- A bespoke "White Wolf Mountain is gated" rule fixes one mountain, not the class — the Ardougne
  wall, the Fremennik approach and Karamja are all the same shape.
- `tools/.sp-cache` now holds SP's 25 transport files (14,410 endpoints, see
  `audit-pin-reachability`). Whether those are enough to approximate connectivity offline is an open
  question and the first thing to test before designing anything.

**Cheapest honest mitigation if a full fix is out of reach:** require a much larger margin before a
teleport "wins" a first leg when the straight line crosses a known barrier region, or suppress
first-leg hints entirely while the step's destination sits behind a gate the account has not
unlocked. Both need the connectivity question answered first.

</details>

---

## Previous session list (written 2026-08-08, end of wave 12)

1. **Exercise the never-run list above**, gangplank gate first.
2. **Gertrude's Cat still ticks on quest start.** Its extra actions are item-based ("use the leaves on a
   sardine"), so no varp fits and annotation items don't gate sub completion. Needs either a detector for
   "use X on Y" or leaving alone — **owner's call**, it's a blast-radius change.
3. **Hub pin BUMPED to `3638c2f` (2026-08-08, owner's call).** PR 14207 now builds from the end of wave 12. Much of that build is unverified in play — if a hub reviewer or user reports something, suspect the never-exercised list above first.
4. Long-standing: deliberate death test, onion-gate capture, "big frog leg" → 7908 verdict.

---

## Execution order — COMPLETE as of wave 12

Every phase below is closed. Kept as a record of what was done and in what order.

- **Phase 0 — investigate.** INV-A (wave 10, instanced regions), INV-B, INV-C, INV-D, INV-E — all answered.
- **Phase 1 — small fixes.** P2-04, P2-01 (not a defect), P1-01, P0-06.
- **Phase 2 — chains and nav.** P0-05 / P1-06 via `Errand.hold`; Hazelmere seeded; P0-03 plus the
  part-quest survey, which found the real gap was `questStatus=start` steps carrying extra actions.
- **Phase 3 — detection.** P0-01, P0-04, P1-03; P2-07 shown to be unachievable as specified.
- **Phase 4 — policy and polish.** TOOL-01 and P0-02 (wave 11), P1-02, D3 dropped as a phantom,
  P1-05, P2-02, P2-03, P2-05, UX-01.

**What remains is verification, not implementation** — see NEXT SESSION above. The one open design
question is Gertrude's Cat, which needs an owner decision rather than a plan.

---

## Notes

- **Hub pin is at `3638c2f`** (bumped 2026-08-08 on the owner's explicit call, ending wave 12). The standing
  rule is otherwise: bump only after a calm session.
- **Never add Claude co-author trailers to commits.** History was rewritten and force-pushed on 2026-07-28 to
  strip them.
- Three items were prior work misread as bugs: **P2-04** (deliberate), **P2-01** (seeder exists), **P0-05**
  (designed behaviour). Check the Prior work line before coding. Wave 12 added three more that were not
  defects at all: **P2-01** again (12gp), **P2-07** (no such var), **D3** (guide-prescribed routes).
- Test account has 292 QP — ideal for P0-03, poor for fresh-account ordering tests.
- **Splitting one file across several commits** needs hunk patches: `git diff` → split by `@@` → `git apply`
  per group. `git add -p` is unavailable non-interactively. Diff the reassembled file against the
  play-tested copy afterwards, and compile each commit in a throwaway `git worktree` — which is also the way
  to build anything while a client is running.
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

