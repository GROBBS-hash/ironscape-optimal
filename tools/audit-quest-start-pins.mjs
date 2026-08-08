// Cross-checks every QUEST place pin against where Quest Helper actually
// sends you to start that quest.
//
// Why this exists: a quest pin is what nav routes to when a step names an
// unstarted quest, so a wrong one means "the plugin won't navigate me".
// The pins were seeded from wiki infobox/{{Map}} data, which points at the
// quest's LANDMARK rather than its giver — the same fault audit-shops
// found in four shop pins (a church instead of a farming shop) and the
// same one the gnome banks had (ground entrance instead of the booths
// upstairs).
//
// Worse, a landmark pin can be somewhere Shortest Path cannot path to at
// all, and then nothing is drawn and there is no error anywhere. Live
// example: Fishing Contest was pinned at 2876,3481 — up White Wolf
// Mountain — while QH starts it at Vestri, 2821,3486, just north of
// Catherby. 55 tiles, and the difference between a route and silence.
//
// QH is the oracle because its steps are play-tested by a lot of people:
// the FIRST NpcStep or ObjectStep in a quest's helper is where it tells
// you to go first.
//
//   node tools/audit-quest-start-pins.mjs [--all] [--quest "Name"]
//
// --all also lists pins that agree, and quests with no QH helper found.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const RES = path.join(__dirname, '../src/main/resources/com/ironscape');
const RAW_BASE = 'https://raw.githubusercontent.com/Zoinkwiz/quest-helper/master/';
const SRC_ROOT = 'src/main/java/com/questhelper/helpers/quests/';
const CACHE_DIR = path.join(__dirname, '.qh-cache');

const args = process.argv.slice(2);
const showAll = args.includes('--all');
const only = args.indexOf('--quest') >= 0 ? args[args.indexOf('--quest') + 1] : null;

const read = (p) => JSON.parse(fs.readFileSync(path.join(RES, p), 'utf8'));
const places = read('places/places.json').places;
const givers = (() => {
  const g = read('places/quest_givers.json');
  return g.givers ?? g;
})();

// Only pins the guide can actually route to: type "quest".
const questPins = Object.entries(places)
  .filter(([, v]) => v && v.type === 'quest' && typeof v.x === 'number')
  .filter(([k]) => !only || k.toLowerCase() === only.toLowerCase());

async function fetchCached(url) {
  fs.mkdirSync(CACHE_DIR, { recursive: true });
  const cacheFile = path.join(CACHE_DIR, url.replace(/[^a-z0-9.]+/gi, '_'));
  if (fs.existsSync(cacheFile)) return fs.readFileSync(cacheFile, 'utf8');
  const res = await fetch(url);
  if (!res.ok) {
    fs.writeFileSync(cacheFile, ''); // negative-cache: don't re-ask every run
    return '';
  }
  const text = await res.text();
  fs.writeFileSync(cacheFile, text);
  return text;
}

function guessPath(questName) {
  const words = questName.split(/[^a-zA-Z0-9]+/).filter(Boolean);
  const cls = words.map((w) => w[0].toUpperCase() + w.slice(1).toLowerCase()).join('');
  const slug = words.join('').toLowerCase();
  return `${SRC_ROOT}${slug}/${cls}.java`;
}

/**
 * QH's first "go here" step. NpcStep and ObjectStep are the ones that
 * mean a place; DetailedQuestStep and Zone are often region tests or
 * conditions, so they are only used when nothing better exists.
 */
function firstStep(java) {
  const statements = java.split(';');
  let fallback = null;
  for (const st of statements) {
    if (!st.includes('new WorldPoint(')) continue;
    const decl = st.match(/(\w+)\s*=\s*new\s+(\w+)\(/);
    const pt = st.match(/new WorldPoint\((\d+),\s*(\d+),\s*(\d+)\)/);
    if (!decl || !pt) continue;
    const type = decl[2];
    const description = st.match(/"((?:[^"\\]|\\.)*)"/);
    const hit = {
      variable: decl[1],
      type,
      x: Number(pt[1]), y: Number(pt[2]), plane: Number(pt[3]),
      description: description ? description[1] : '',
    };
    if (type === 'NpcStep' || type === 'ObjectStep') return hit;
    if (!fallback && type !== 'Zone') fallback = hit;
  }
  return fallback;
}

const dist = (a, b) => Math.round(Math.hypot(a.x - b.x, a.y - b.y));
const DRIFT = 30; // beyond this the pin is a different place, not a rounding

const drifted = [];
const agreed = [];
const noHelper = [];

for (const [key, pin] of questPins) {
  const java = await fetchCached(RAW_BASE + guessPath(pin.display || key));
  if (!java) { noHelper.push(key); continue; }
  const step = firstStep(java);
  if (!step) { noHelper.push(key); continue; }
  const drift = dist(pin, step);
  const row = { key, pin, step, drift, giver: givers[key] };
  if (drift > DRIFT) drifted.push(row); else agreed.push(row);
}

drifted.sort((a, b) => b.drift - a.drift);

console.log('=== quest pins that disagree with Quest Helper\'s first step ===');
console.log('(a big drift usually means the pin is a landmark, not the giver —');
console.log(' and a landmark can be somewhere Shortest Path cannot reach at all)\n');
for (const r of drifted) {
  console.log(`  ${String(r.drift).padStart(5)} tiles  "${r.key}"`);
  console.log(`         ours: ${r.pin.x},${r.pin.y}` + (r.pin.plane ? ` p${r.pin.plane}` : '')
    + (r.giver ? `  giver recorded as "${r.giver}"` : ''));
  console.log(`         QH:   ${r.step.x},${r.step.y}` + (r.step.plane ? ` p${r.step.plane}` : '')
    + `  (${r.step.type} ${r.step.variable})`);
  if (r.step.description) console.log(`         "${r.step.description}"`);
}

console.log(`\n${drifted.length} pins drift >${DRIFT} tiles`);
console.log(`${agreed.length} agree with QH`);
console.log(`${noHelper.length} have no QH helper to compare against`);
if (showAll && noHelper.length) {
  console.log('\nno helper: ' + noHelper.join(', '));
}
