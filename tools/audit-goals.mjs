#!/usr/bin/env node
// Full-guide item-goal audit: reads build/goal-audit.tsv (written by
// GoalAuditDumpTest) plus the bundled annotation item lists, and checks
// every name's alias chain against the real OSRS item list (wiki prices
// mapping for tradeables + bundled item_ids.json for untradeables).
// Anything reported here would show a forever-red 0/N badge in game.
//
// Run:  gradlew test --tests "*.GoalAuditDumpTest"   (writes the tsv)
//       node tools/audit-goals.mjs

import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.join(__dirname, '..');
const RES = path.join(ROOT, 'src/main/resources/com/ironscape');

// ---- known real item names --------------------------------------------
const known = new Set();
const mappingCache = path.join(__dirname, 'wiki-item-mapping.json');
if (!fs.existsSync(mappingCache)) {
  const res = await fetch('https://prices.runescape.wiki/api/v1/osrs/mapping',
    { headers: { 'User-Agent': 'ironscape-runelite-plugin dev tooling (item audit)' } });
  fs.writeFileSync(mappingCache, JSON.stringify(await res.json()));
}
for (const item of JSON.parse(fs.readFileSync(mappingCache, 'utf8'))) {
  known.add(item.name.toLowerCase());
}
for (const name of Object.keys(JSON.parse(
  fs.readFileSync(path.join(RES, 'items/item_ids.json'), 'utf8')))) {
  known.add(name.toLowerCase());
}

// Names ItemTracker resolves via SUBSTITUTES or special-cases, not
// aliases — keep in sync with ItemTracker.java.
const SPECIAL = new Set(['gloves', 'glove', 'boots', 'boot', 'pickaxe', 'pickaxes',
  'axe', 'axes', 'hammer', 'tinderbox', 'runes', 'all runes', 'all of your runes',
  'coins', 'gp', 'gold', 'cash', 'money', 'bars', 'beads',
  // Real UNTRADEABLE items the prices mapping can't know — the tracker
  // counts them by name at runtime just fine.
  'message', 'plague sample', 'touch paper', 'celestial ring', 'camulet',
  'graceful', 'brooch', 'varrock armor 2', 'rune pick', 'bucket of slime', 'eye of newt pack', 'compost pack',
  'priest gown top', 'gas mask', 'dramen staff', 'ecto-token', 'ecto-tokens',
  'h.a.m. robes', 'chaos core', 'barronite deposit', 'ring of visibility',
  'catspeak amulet', 'goutweed', 'seal of passage', 'elemental metal',
  'battered key', "nuff's certificate", 'red vine worm', 'ring of charos',
  'ring of charos(a)', 'nettle tea', 'ice gloves', 'dusty key', 'antidote++',
  'stamina potion', 'restore potion', 'sapphire lantern', 'pink dye',
  'specimen brush', 'specimen jar', 'panning tray', 'trowel', 'snake charm',
  'snake basket', 'lyre', 'phoenix feather', "rat's tail", 'nails', 'nail',
  'ugthanki dung', 'guthix rest', 'pigeon cage', 'karamjan rum', 'picture',
  "captain's log", 'fishbowl', 'cat', 'ogre bellows', 'ogre bow',
  "m'speak amulet", 'gorilla greegree', 'strip of cloth', 'silverlight',
  'strange implement', 'black dye', 'phoenix crossbow', 'torch', 'flowers']);

// Mirror of ItemTracker.COLLOQUIAL additions the audit must not re-flag.
const COLLOQUIAL = {
  'wine': 'jug of wine', 'wines': 'jug of wine', 'teleports': 'teleport card',
  'chocolate': 'chocolate bar', 'dueling ring': 'ring of dueling(8)',
  'dueling rings': 'ring of dueling(8)', 'soft leather': 'leather',
  'priest robes': 'priest gown (top)', 'silver': 'silver bar',
  'regular plank': 'plank', 'regular planks': 'plank', 'normal compost pack': 'compost pack',
};

const resolves = (name, aliases) => {
  const lower = name.toLowerCase().trim();
  if (SPECIAL.has(lower)) return true;
  return aliases.some((a) => SPECIAL.has(a) || known.has(a));
};

// ---- 1. text-detected goals from the dump -----------------------------
const tsv = path.join(ROOT, 'build/goal-audit.tsv');
if (!fs.existsSync(tsv)) {
  console.error('build/goal-audit.tsv missing — run: gradlew test --tests "*.GoalAuditDumpTest"');
  process.exit(1);
}
console.log('=== TEXT-DETECTED item goals that resolve to NO real item ===');
let flagged = 0;
let total = 0;
for (const line of fs.readFileSync(tsv, 'utf8').split('\n')) {
  if (!line.startsWith('ITEM\t')) continue;
  total++;
  const [, subId, qty, name, aliasStr, text] = line.split('\t');
  if (resolves(name, aliasStr.split('|'))) continue;
  flagged++;
  console.log(`  [${subId}] "${name}" x${qty}  <-  ${text.slice(0, 90)}`);
}
console.log(`${flagged} of ${total} text goals unresolvable\n`);

// ---- 2. bundled annotation item lists ---------------------------------
// Mirror of ItemTracker.aliases for the annotation names (no JVM here);
// keep loosely in sync — the SPECIAL set covers the substitutes.
function aliases(name) {
  let key = name.toLowerCase().trim()
    .replace(/\s*\((?:equip(?:ped)?|wear|worn)\)$/, '');
  if (['gp', 'gold', 'cash', 'money'].includes(key)) key = 'coins';
  key = key.replace(/^(?:a )?(?:few|couple|plenty|some|bunch) (?:of )?/, '');
  if (key.startsWith('noted ')) key = key.slice(6);
  key = key.replace(/^(bronze|iron|steel|mithril|adamant|rune|amethyst) arrowheads?$/, '$1 arrowtips');
  if (COLLOQUIAL[key]) key = COLLOQUIAL[key];
  const singular = key.endsWith('s') ? key.slice(0, -1) : key;
  const words = key.split(' ');
  const firstSingular = words[0].endsWith('s')
    ? [words[0].slice(0, -1), ...words.slice(1)].join(' ') : key;
  const out = [key, singular, firstSingular, key + 's', key + ' rune', singular + ' rune'];
  if (key.endsWith(' pack')) out.push('empty ' + key);
  const stem = singular.endsWith(' staff') ? singular.slice(0, -6) : null;
  if (stem && ['fire', 'water', 'air', 'earth'].includes(stem)) out.push('staff of ' + stem);
  const noParen = key.replace(/\s*\([^)]*\)$/, '').trim();
  if (noParen && noParen !== key) {
    out.push(noParen, noParen.endsWith('s') ? noParen.slice(0, -1) : noParen + 's');
  }
  return out;
}

// Mirror of AnnotationManager.splitCompoundRunes: "all of your mind and
// air runes" arrives at the tracker as per-type names, never whole.
const COMPOUND_RUNES = /^(?:all (?:of )?(?:your )?)?(\w+(?:, ?\w+)*,? and \w+) runes?$/;

console.log('=== BUNDLED ANNOTATION items that resolve to NO real item ===');
const ann = JSON.parse(fs.readFileSync(
  path.join(RES, 'annotations/annotations_oziris.json'), 'utf8')).annotations;
let aFlagged = 0;
let aTotal = 0;
for (const [key, entry] of Object.entries(ann)) {
  for (const item of entry.items || []) {
    aTotal++;
    if (!item.name || COMPOUND_RUNES.test(item.name.toLowerCase().trim())) continue;
    if (resolves(item.name, aliases(item.name))) continue;
    aFlagged++;
    console.log(`  [${key}] "${item.name}" x${item.quantity ?? 1}`);
  }
}
console.log(`${aFlagged} of ${aTotal} annotation items unresolvable`);

// ---- 3. buy-subs where detection produced NOTHING ---------------------
console.log('\n=== BUY subs with NO detected item goal (silently untracked) ===');
let nogoals = 0;
for (const line of fs.readFileSync(tsv, 'utf8').split('\n')) {
  if (!line.startsWith('NOGOAL\t')) continue;
  const [, subId, text] = line.split('\t');
  // A sub whose STEP has bundled annotation items is tracked that way.
  if (ann[subId.split(':')[0]]?.items?.length) continue;
  nogoals++;
  console.log(`  [${subId}] ${text.slice(0, 100)}`);
}
console.log(`${nogoals} untracked buy subs`);
