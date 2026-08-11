// Are our errand chains telling the same story Quest Helper does?
//
// Written after ONE chain produced two seeding faults in a single play
// session, both mechanically checkable against source already sitting in
// tools/.qh-cache:
//
//   the black candle note was INVENTED  ("insist when he warns you"). QH and
//     the wiki both say the Candle maker makes it in exchange for a bucket of
//     wax, and the three legs producing that wax were missing entirely.
//   the candle stage had NO STATE GATE. QH puts the whole black-candle branch
//     in quest state 4; the player was in state 3, so the dialogue did not
//     exist and the chain guided a wasted trip.
//
// Both are the same shape: the chain knows WHERE, and does not know WHEN or
// WHAT ELSE. So this reports, per chain:
//
//   UNCOVERED   a QH step whose location no stage of ours goes near. Either a
//               missing leg, or a leg QH models and the guide does not care
//               about -- a human decides which.
//   NO GATE     our chain spans a quest whose QH helper has several states
//               and carries no varbit/varp gate at all. Weak on its own, and
//               it is exactly what today's bug looked like.
//
// This is a REVIEW AID, not a prover. QH's step list includes plenty our
// guide deliberately skips, and a chain can be legitimately shorter.
//
//   node tools/audit-errand-chains.mjs [--all]
//
// Needs build/completion-paths.tsv (GoalAuditDumpTest) for step text.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const RES = path.join(__dirname, '../src/main/resources/com/ironscape');
const CACHE = path.join(__dirname, '.qh-cache');
const BASE = 'https://raw.githubusercontent.com/Zoinkwiz/quest-helper/master/'
  + 'src/main/java/com/questhelper/helpers/quests/';
const showAll = process.argv.includes('--all');
const NEAR = 30;   // tiles: within this, a QH step is "covered" by a stage

const annotations = JSON.parse(
  fs.readFileSync(path.join(RES, 'annotations/annotations_oziris.json'), 'utf8'));
const ann = annotations.annotations || annotations;

// sub id -> the step's text, so a chain can be named and its quest found.
const textById = new Map();
const paths = path.join(__dirname, '../build/completion-paths.tsv');
if (!fs.existsSync(paths)) {
  console.error('build/completion-paths.tsv missing -- run:\n'
    + '  gradlew test --tests "*.GoalAuditDumpTest"');
  process.exit(1);
}
for (const line of fs.readFileSync(paths, 'utf8').split(/\r?\n/)) {
  const c = line.split('\t');
  if (c[0] === 'PATH' && c[1]) {
    textById.set(c[1], c[3] || '');
    textById.set(c[1].split(':')[0], c[3] || '');
  }
}

// Quest names the client knows, longest first so "Desert Treasure II" wins
// over "Desert Treasure".
const questNames = [];
const namesFile = path.join(__dirname, '../build/quest-names.tsv');
if (fs.existsSync(namesFile)) {
  for (const line of fs.readFileSync(namesFile, 'utf8').split(/\r?\n/)) {
    const c = line.split('\t');
    for (const cell of c.slice(1)) {
      if (cell && /[a-z]/.test(cell) && cell.length > 3) questNames.push(cell.trim());
    }
  }
}
questNames.sort((a, b) => b.length - a.length);

const qhPath = (quest) => {
  const dir = quest.toLowerCase().replace(/[^a-z0-9]/g, '');
  const file = quest.replace(/[^A-Za-z0-9 ]/g, '').split(/\s+/)
    .map((w) => w[0].toUpperCase() + w.slice(1).toLowerCase()).join('');
  return `${dir}/${file}.java`;
};

async function fetchQh(quest) {
  fs.mkdirSync(CACHE, { recursive: true });
  const url = BASE + qhPath(quest);
  const file = path.join(CACHE, url.replace(/[:/]+/g, '_'));
  if (fs.existsSync(file)) {
    const cached = fs.readFileSync(file, 'utf8');
    return cached.startsWith('404') ? null : cached;
  }
  const res = await fetch(url, { headers: { 'User-Agent': 'ironscape-dev tooling' } });
  const body = res.ok ? await res.text() : '404';
  fs.writeFileSync(file, body);
  return res.ok ? body : null;
}

const dist = (a, b) => Math.round(Math.hypot(a.x - b.x, a.y - b.y));

let chains = 0;
let uncoveredTotal = 0;
const gateless = [];

for (const [key, entry] of Object.entries(ann)) {
  if (!entry.errands || !entry.errands.length) continue;
  chains++;
  const text = textById.get(key) || textById.get(key.split(':')[0]) || '(unknown step)';
  const quest = questNames.find((n) =>
    text.toLowerCase().includes(n.toLowerCase()));
  const stages = entry.errands;
  const gated = stages.some((s) => s.varbit != null || s.varp != null);

  if (!quest) {
    if (showAll) {
      console.log(`\n${key}  "${text}"\n  no quest named in the step text -- nothing to compare`);
    }
    continue;
  }
  const source = await fetchQh(quest);
  if (!source) {
    if (showAll) console.log(`\n${key}  "${text}"\n  no QH helper found for "${quest}"`);
    continue;
  }

  // Every WorldPoint QH mentions, with the description on the same statement.
  const qhSteps = [];
  const re = /new WorldPoint\((\d+),\s*(\d+),\s*(\d+)\)[^;]*?"((?:[^"\\]|\\.)*)"/g;
  let m;
  while ((m = re.exec(source)) !== null) {
    qhSteps.push({ x: +m[1], y: +m[2], plane: +m[3], text: m[4] });
  }
  const states = (source.match(/steps\.put\(/g) || []).length;

  // A chain covers ONE STEP's legs, not a whole quest, so most of QH's
  // points belong to other steps of the guide and reporting them all buries
  // the finding -- the first run produced 88 rows for 14 chains. Keep only
  // points in the chain's own neighbourhood: near enough that a leg there
  // plausibly belongs to this step, far enough out to catch a missing one.
  // Merlin's "Talk to King Arthur to start" drops out (it is its own guide
  // step); the wax legs this audit was written for would not have.
  const NEIGHBOURHOOD = 150;
  const covers = (q, within) => stages.some((s) => dist(s, q) <= within
    || (s.routeX != null && dist({ x: s.routeX, y: s.routeY }, q) <= within));
  const all = qhSteps.filter((q) => !covers(q, NEAR));
  const uncovered = all.filter((q) => covers(q, NEIGHBOURHOOD));
  const elsewhere = all.length - uncovered.length;

  if (!gated && states > 1) gateless.push({ key, quest, states, text });

  if (uncovered.length || showAll) {
    console.log(`\n${key}  "${text}"`);
    console.log(`  quest ${quest} -- ${stages.length} stages, ${qhSteps.length} QH points,`
      + ` ${states} quest states${gated ? ', gated' : ', NO GATE'}`
      + (elsewhere ? `  (${elsewhere} more QH points elsewhere in the quest, not this step's job)` : ''));
    for (const u of uncovered.slice(0, showAll ? 99 : 6)) {
      console.log(`    UNCOVERED ${String(u.x).padStart(4)},${u.y} p${u.plane}  "${u.text}"`);
    }
    if (uncovered.length > 6 && !showAll) {
      console.log(`    ... and ${uncovered.length - 6} more (--all)`);
    }
  }
  uncoveredTotal += uncovered.length;
}

console.log(`\n${chains} errand chains checked, ${uncoveredTotal} QH points no stage goes near`);
if (gateless.length) {
  console.log(`\n=== chains with NO var gate whose quest has several states ===`);
  console.log(`Today's bug exactly: the chain guided to an NPC whose dialogue did not`);
  console.log(`exist yet. Not every chain needs a gate -- check the ones that cross a`);
  console.log(`quest milestone.\n`);
  for (const g of gateless) {
    console.log(`  ${g.key}  ${g.quest} (${g.states} states)  "${g.text.slice(0, 60)}"`);
  }
}

// ---------------------------------------------------------------------------
// STRUCTURAL checks -- no Quest Helper needed, and these are the ones wave 15
// paid for one at a time. All three are the same underlying fault: a leg whose
// whole point is GETTING SOMEWHERE, modelled as a coordinate. A proximity
// waypoint can only ever say "be here", never "be through".
// ---------------------------------------------------------------------------
const UNDERGROUND = 4000;      // y at or above this is not the surface
const band = (p) => (p.y >= UNDERGROUND ? 'underground' : 'surface');
// Quest progress, not a place: neither a radius nor the ground between legs
// decides anything for these, so both checks below leave them alone.
// Mirrors ErrandProgress.varGated: a THRESHOLD or a single BIT. Diary
// chains gate on one bit each with no `value`, and without the bit half
// this read every one of them as a proximity waypoint — so the Varrock
// chain's sawmill stages, 7 tiles apart because the tree and the plank
// really are next to each other, came back as self-satisfying. A gate is
// a gate; distance decides nothing there.
const varGated = (s) => (s.value != null || s.bit != null)
  && (s.varbit != null || s.varp != null);

const findings = [];
for (const [key, entry] of Object.entries(ann)) {
  const chain = entry.errands;
  if (!chain) continue;
  const text = textById.get(key.split(':')[0]) || '';
  for (let i = 0; i < chain.length; i++) {
    const s = chain[i];
    const prev = i > 0 ? chain[i - 1] : null;

    // (A) SELF-SATISFYING. The stage's own satisfaction circle contains the
    // place the PREVIOUS leg leaves you standing, so it ticks the instant the
    // leg before it does and guides nothing. Arhein's crate, exactly: it sits
    // in Catherby where the three wax legs already put you.
    // (A var-gated stage is exempt from both: its gate is quest progress,
    // so neither its radius nor the ground between legs decides anything.)
    if (prev && !varGated(s) && s.zone == null && s.region == null && s.item == null
        && dist(s, prev) <= (s.radius ?? 12)) {
      findings.push({ key, i, text,
        what: `SELF-SATISFYING  waypoint ${s.x},${s.y} is ${dist(s, prev)} tiles from the`
          + ` previous stage, inside its own ${s.radius ?? 12}-tile radius` });
    }

    // (B) UNGUIDED TRAVERSAL. Consecutive stages either side of the
    // underground band (which you can only cross THROUGH something), or on
    // different floors CLOSE together (a staircase, not a journey) -- where
    // the later one names neither a route point nor an object, so something
    // is crossed that the chain never says how to cross.
    //
    // The distance test is what separates this from an ordinary long walk:
    // the Blue Moon Inn upstairs to the Gnome bar upstairs is two floors and
    // 700 tiles, and Shortest Path draws it perfectly well.
    //
    // `hold` exempts a stage: it is the annotation saying out loud that no
    // route can be drawn for this leg, which is an answer to the question --
    // not a good one, but a deliberate one. A held leg with nothing to click
    // still leaves the player to find their own way; that wants zone bounds,
    // and bounds have to be captured in game.
    const traversal = prev && !varGated(s) && s.hold !== true
      && (band(prev) !== band(s)
        || ((prev.plane ?? 0) !== (s.plane ?? 0) && dist(prev, s) <= 30));
    if (traversal && s.routeX == null && s.object == null) {
      findings.push({ key, i, text,
        what: `UNGUIDED TRAVERSAL  ${prev.x},${prev.y} p${prev.plane ?? 0} (${band(prev)})`
          + ` -> ${s.x},${s.y} p${s.plane ?? 0} (${band(s)}) with no route point`
          + ` and no object to click` });
    }

    // (C) COARSE REGION. A region is 64x64. If another stage of the SAME chain
    // stands in it and is plainly somewhere else, the region cannot mean "am I
    // in yet" -- region 11061 holds Keep Le Faye AND the giant bats the same
    // chain sends you to two stages earlier. Zones tell them apart.
    if (s.region != null) {
      const regionOf = (p) => ((p.x >> 6) << 8) | (p.y >> 6);
      for (let j = 0; j < chain.length; j++) {
        if (j === i || regionOf(chain[j]) !== s.region || dist(chain[j], s) <= 20) continue;
        findings.push({ key, i, text,
          what: `COARSE REGION  region ${s.region} also contains stage ${j}`
            + ` (${chain[j].x},${chain[j].y}, ${dist(chain[j], s)} tiles away)`
            + ` -- prefer a zone` });
        break;
      }
    }
  }
}

console.log(`\n=== legs modelled as coordinates that are really "go through this thing" ===`);
if (!findings.length) {
  console.log('  none');
} else {
  console.log(`A waypoint models "be here", never "do this". These want a zone`);
  console.log(`(with its plane), a route point, and the object to click.\n`);
  let last = null;
  for (const f of findings) {
    if (f.key !== last) {
      console.log(`  ${f.key}  "${f.text.slice(0, 62)}"`);
      last = f.key;
    }
    console.log(`    stage ${f.i}  ${f.what}`);
  }
}
