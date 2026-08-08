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
