// Read Quest Helper the way it is WRITTEN: as a state machine.
//
// Every seeding fault on the Merlin's Crystal chain came from the same habit
// -- grepping QH for `new WorldPoint(...)` and taking the leaf, while the
// ConditionalStep tree above it (which says WHEN a leaf applies, WHAT must be
// true first, and in WHAT ORDER) went unread. Three faults, all of them sitting
// in that structure:
//
//   the wax legs      a whole branch that PRODUCES the item, not just the NPC
//                     who takes it
//   the state gate    the black candle branch lives in quest state 4; the
//                     player was in 3, so the dialogue did not exist
//   the crate         the entrance that makes the stairs reachable at all
//
// So stop grepping. This parses the tree and prints, per quest state, the
// branches and their conditions, with each leaf's coordinates, description and
// dialogue. Seeding then becomes: take the state your guide step covers,
// DELETE what the step does not own, and keep the rest -- subtractive, where
// the old way was additive and silently missed whatever nobody thought to add.
//
//   node tools/qh-tree.mjs "Merlin's Crystal"
//   node tools/qh-tree.mjs "Merlin's Crystal" --state 4
//   node tools/qh-tree.mjs "Merlin's Crystal" --state 4 --draft
//
// --draft emits errand stages ready to trim into annotations_oziris.json.
//
// It is a DRAFT GENERATOR, not an oracle. QH's ConditionalStep picks among its
// branches at runtime by conditions this cannot evaluate, so the printed order
// is source order, not necessarily play order. A human still decides.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const CACHE = path.join(__dirname, '.qh-cache');
const BASE = 'https://raw.githubusercontent.com/Zoinkwiz/quest-helper/master/'
  + 'src/main/java/com/questhelper/helpers/quests/';

const args = process.argv.slice(2);
const quest = args.find((a) => !a.startsWith('--'));
if (!quest) {
  console.error('usage: node tools/qh-tree.mjs "Quest Name" [--state N] [--draft]');
  process.exit(1);
}
const wantState = args.indexOf('--state') >= 0 ? Number(args[args.indexOf('--state') + 1]) : null;
const draft = args.includes('--draft');

const qhPath = (name) => {
  const dir = name.toLowerCase().replace(/[^a-z0-9]/g, '');
  const file = name.replace(/[^A-Za-z0-9 ]/g, '').split(/\s+/)
    .map((w) => w[0].toUpperCase() + w.slice(1).toLowerCase()).join('');
  return `${dir}/${file}.java`;
};

async function source(name) {
  fs.mkdirSync(CACHE, { recursive: true });
  const url = BASE + qhPath(name);
  const file = path.join(CACHE, url.replace(/[:/]+/g, '_'));
  if (fs.existsSync(file)) {
    const text = fs.readFileSync(file, 'utf8');
    return text.startsWith('404') ? null : text;
  }
  const res = await fetch(url, { headers: { 'User-Agent': 'ironscape-dev tooling' } });
  const body = res.ok ? await res.text() : '404';
  fs.writeFileSync(file, body);
  return res.ok ? body : null;
}

const java = await source(quest);
if (!java) {
  console.error(`no Quest Helper file for "${quest}" (tried ${qhPath(quest)})`);
  process.exit(1);
}

// ---- leaves: a step variable with a place, a description and maybe dialogue.
// QH writes these as one statement, so match to the terminating semicolon.
const leaves = new Map();
const leafRe = /(\w+)\s*=\s*new (\w*(?:Step|QuestStep))\(this,([^;]*);/g;
let m;
while ((m = leafRe.exec(java)) !== null) {
  const [, name, type, body] = m;
  const point = body.match(/new WorldPoint\((\d+),\s*(\d+),\s*(\d+)\)/);
  const text = body.match(/"((?:[^"\\]|\\.)*)"/);
  leaves.set(name, {
    name, type,
    x: point ? +point[1] : null,
    y: point ? +point[2] : null,
    plane: point ? +point[3] : 0,
    text: text ? text[1] : '',
    dialog: [],
  });
}
// dialogue is added afterwards, statement by statement
const dialogRe = /(\w+)\.addDialogStep(?:s)?\(([^;]*)\);/g;
while ((m = dialogRe.exec(java)) !== null) {
  const leaf = leaves.get(m[1]);
  if (!leaf) continue;
  for (const d of m[2].matchAll(/"((?:[^"\\]|\\.)*)"/g)) leaf.dialog.push(d[1]);
}

// ---- conditionals: default branch plus condition/step pairs, in source order.
const conditionals = new Map();
const condRe = /(?:var\s+)?(\w+)\s*=\s*new ConditionalStep\(this,\s*(\w+)/g;
while ((m = condRe.exec(java)) !== null) {
  conditionals.set(m[1], { name: m[1], fallback: m[2], branches: [] });
}
const addRe = /(\w+)\.addStep\(([^;]*?),\s*(\w+)\);/g;
while ((m = addRe.exec(java)) !== null) {
  const c = conditionals.get(m[1]);
  if (c) c.branches.push({ when: m[2].replace(/\s+/g, ' ').trim(), step: m[3] });
}
// a locking condition means "this whole branch is done once X is true"
const lockRe = /(\w+)\.setLockingCondition\((\w+)\)/g;
while ((m = lockRe.exec(java)) !== null) {
  const c = conditionals.get(m[1]);
  if (c) c.lock = m[2];
}

// ---- states
const states = [];
const putRe = /steps\.put\((\d+),\s*(\w+)\)/g;
while ((m = putRe.exec(java)) !== null) states.push({ state: +m[1], root: m[2] });

const collected = [];
function walk(name, depth, when, seen = new Set()) {
  if (seen.has(name) || depth > 6) return;
  seen.add(name);
  const pad = '  '.repeat(depth);
  const cond = conditionals.get(name);
  if (cond) {
    console.log(`${pad}${name}${cond.lock ? `   [done when ${cond.lock}]` : ''}`);
    // Branches first: they are the specific cases, the fallback is the default.
    for (const b of cond.branches) {
      console.log(`${pad}  when ${b.when}:`);
      walk(b.step, depth + 2, b.when, seen);
    }
    console.log(`${pad}  otherwise:`);
    walk(cond.fallback, depth + 2, when, seen);
    return;
  }
  const leaf = leaves.get(name);
  if (!leaf) {
    console.log(`${pad}${name}  (not a step in this file)`);
    return;
  }
  const where = leaf.x == null ? '' : `${leaf.x},${leaf.y} p${leaf.plane}`;
  console.log(`${pad}${leaf.type.padEnd(16)} ${where.padEnd(16)} "${leaf.text}"`);
  for (const d of leaf.dialog) console.log(`${pad}${' '.repeat(17)}dialog: "${d}"`);
  if (leaf.x != null) collected.push({ ...leaf, when });
}

console.log(`${quest} -- ${states.length} quest states, ${leaves.size} steps,`
  + ` ${conditionals.size} conditionals\n`);
for (const s of states) {
  if (wantState != null && s.state !== wantState) continue;
  console.log(`=== state ${s.state} =========================================`);
  walk(s.root, 1, null);
  console.log('');
}

if (draft) {
  console.log('--- draft errand stages (TRIM to what this guide step owns) ---');
  const stages = collected.map((c) => {
    const stage = { x: c.x, y: c.y, plane: c.plane, note: c.text };
    if (c.dialog.length) stage.dialog = c.dialog;
    if (c.when) stage.note = `${c.text}   [QH condition: ${c.when}]`;
    return stage;
  });
  console.log(JSON.stringify({ errands: stages }, null, 1));
  console.log('\nREMINDER: a stage GATES the step. Anything optional wants item:null');
  console.log('(a waypoint, satisfied by arriving) or it can wedge the step forever.');
}
