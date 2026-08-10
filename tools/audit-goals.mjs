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
import { canonical, flaggedNames, liveItemNames } from './lib/item-names.mjs';

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
// RuneLite's own name cache — 21k names, and unlike the prices mapping it
// can see UNTRADEABLES. Section 4 switched to this authority in wave 19;
// section 1 did not, so it kept flagging real items purely because they
// cannot be traded, and the workaround was to hand-add each one to
// SPECIAL below. That list then does double duty and hides things: an
// entry meaning "this is a substitute family, not an item name" reads
// identically to one meaning "trust me, this item exists".
//
// It cried wolf on both of tonight's approved colloquials at once —
// Barrows gloves and the Void ranger helm are as real as items get.
{
  const live = await liveItemNames(path.join(__dirname, '.wiki-cache/item-names-cache.json'));
  if (live) {
    for (const name of live.values()) {
      known.add(name.toLowerCase());
    }
  } else {
    console.warn('WARNING: live item names unavailable — untradeables may flag as unresolvable.\n');
  }
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
  'strange implement', 'black dye', 'phoenix crossbow', 'torch', 'flowers',
  'dramen branch', 'ninja greegree', 'zombie greegree', 'priest gown bottom']);

// Mirror of ItemTracker.COLLOQUIAL additions the audit must not re-flag.
const COLLOQUIAL = {
  'wine': 'jug of wine', 'wines': 'jug of wine', 'teleports': 'teleport card',
  'chocolate': 'chocolate bar', 'dueling ring': 'ring of dueling(8)',
  'dueling rings': 'ring of dueling(8)', 'soft leather': 'leather',
  'priest robes': 'priest gown (top)', 'silver': 'silver bar',
  'regular plank': 'plank', 'regular planks': 'plank', 'normal compost pack': 'compost pack', 'flour': 'pot of flour', 'flours': 'pot of flour',
};

// Exact names are STRICTER than the tracker, which falls back to a
// canonical comparison — doses collapse, so "ring of dueling" matches the
// real "Ring of dueling(8)". Testing only exact names reported two goals
// as unresolvable that the plugin resolves perfectly well, purely because
// the deliberately dose-less colloquial has no exact item behind it.
// Mirror the real last resort instead of a stricter approximation.
const canonicalKnown = new Set([...known].map((n) => canonical(n)));

const resolves = (name, aliases) => {
  const lower = name.toLowerCase().trim();
  if (SPECIAL.has(lower)) return true;
  return aliases.some((a) => SPECIAL.has(a) || known.has(a)
    || canonicalKnown.has(canonical(a)));
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

// ---- 4. item_ids.json keys vs their id's REAL name --------------------
// A key with the right id but an invented NAME ("notes for dwarf cannon"
// -> NULODIONS_NOTES) gets a correct sprite, passes section 2 (item_ids
// keys feed the known set), and never counts anything in play — the
// tracker matches names against what you carry. Deliberate colloquials
// are fine when every key word survives inside the constant name
// ("airs" -> AIRRUNE, "barcrawl card" -> BARCRAWL_CARD); anything else
// needs a look. Requires build/item-id-constants.tsv from the dump test.
console.log('\n=== item_ids.json keys that do not match their id\'s real name ===');
const constantsTsv = path.join(ROOT, 'build/item-id-constants.tsv');
if (!fs.existsSync(constantsTsv)) {
  console.log('(skipped — run gradlew test --tests "*.GoalAuditDumpTest" for build/item-id-constants.tsv)');
} else {
  const constantById = new Map();
  for (const line of fs.readFileSync(constantsTsv, 'utf8').split('\n')) {
    const [id, name] = line.split('\t');
    if (id && name && !constantById.has(id)) {
      constantById.set(id, name.trim());
    }
  }
  // Keys verified in play (or hand-checked) whose wording legitimately
  // differs from the constant: extend as new ones are confirmed.
  // (First sweep 2026-08-05: every flag hand-checked against the wiki —
  // these are correct ids under colloquial/abbreviated constant names.)
  // (2026-08-06: 'big frog leg' -> 7908 RAG_MEDIUM_FROG_BONE confirmed by
  // owner against the wiki — the Rag & Bone Man wishlist bone from Big
  // frogs in Lumbridge Swamp; Jagex's constant calls the size "medium".)
  // ('rusty sword' -> 686 DIGSITESWORD: the HAM-pickpocket Rusty sword
  // debuted with The Dig Site, hence the constant name.)
  // ('skin paste' -> 2424 SKINPASTE displays as "Paste"; 'key imprint'
  // -> 2423 KEYPRINT as "Key print" — PAR's colloquials, owner-hit.)
  const VERIFIED = new Set(['big frog leg', 'rusty sword', 'buttons',
    'skin paste', 'key imprint', 'teleport card',
    'silverlight key', 'demon slayer key',
    'message', 'cadava potion', 'pirate message', "pirate's message",
    'agility pots', 'b gloves', 'black wizards hat',
    'blurite sword', 'bronze arrowtips', 'buckets of slime', 'cadava potion',
    'digsite pendants', 'dragon defender', 'fally teletab', "green d'hide top",
    'helm of neitiznot', 'house teletabs', 'jugs of vinegar', 'key imprint',
    'lantern lenses', 'maze key', 'normal compost', 'normal log',
    'pack of normal compost', 'rainbow scarf', 'range void', 'regular plank',
    'rune mysteries notes', 'rune mysteries package', 'small fishing net',
    'steel nails', 'teleport cards', 'translation notes', 'armor seeds',
    'bolts of cloth']);
  const itemIds = JSON.parse(fs.readFileSync(
    path.join(RES, 'items/item_ids.json'), 'utf8'));
  let idFlagged = 0;
  for (const [key, id] of Object.entries(itemIds)) {
    if (VERIFIED.has(key)) continue;
    const constant = constantById.get(String(id));
    if (!constant) {
      idFlagged++;
      console.log(`  "${key}" -> ${id} (no such item id!)`);
      continue;
    }
    const squashed = constant.toLowerCase().replace(/_/g, '');
    // every key word (minus plural s / possessive) must appear in the
    // constant name — "nulodion's notes" -> NULODIONS_NOTES passes,
    // "notes for dwarf cannon" -> NULODIONS_NOTES fails on "dwarf".
    // Every word must match under SOME form (raw / -s / -es / -ves->f /
    // armour) — chaining the rules mangled words ("gloves" -> "glof").
    const words = key.toLowerCase().replace(/'/g, '')
      .split(/[^a-z0-9]+/).filter((w) => w && !['of', 'the', 'a'].includes(w));
    const matches = (w) => [w, w.replace(/s$/, ''), w.replace(/es$/, ''),
      w.replace(/ves$/, 'f'), w.replace(/^armor$/, 'armour')]
      .some((form) => squashed.includes(form));
    if (words.every(matches)) continue;
    idFlagged++;
    console.log(`  "${key}" -> ${id} = ${constant} (name mismatch — sprite right, counting broken?)`);
  }
  console.log(`${idFlagged} suspicious item_ids entr${idFlagged === 1 ? 'y' : 'ies'}`);

  // ---- 4b. the same keys against their id's LIVE DISPLAY NAME --------
  // 4a asks whether the ID is right. This asks whether the NAME will ever
  // match what the player is carrying, and they are NOT the same question:
  // every item below had a correct id and a correct sprite while its badge
  // sat at 0 (owner, in play, 2026-08-09 — six of them in one evening).
  //
  // VERIFIED deliberately does NOT apply here. Three of the six were on
  // that list, hand-checked in an earlier sweep that answered the id
  // question and recorded blanket approval, which silenced this one for
  // months. An id-level exemption must not be able to hide a name-level
  // defect. If a name legitimately differs, give ItemTracker a COLLOQUIAL
  // entry — then it is bridged in the CODE, where it also works.
  console.log('\n=== item_ids keys whose name can never match what you carry ===');
  const aliasTsv = path.join(ROOT, 'build/item-aliases.tsv');
  const liveNames = await liveItemNames(path.join(ROOT, 'tools/.wiki-cache/item-names-cache.json'));
  if (!fs.existsSync(aliasTsv)) {
    console.log('(skipped — run gradlew test for build/item-aliases.tsv)');
  } else if (!liveNames) {
    console.log('(skipped — could not reach the live item mapping)');
  } else {
    const { flagged, untradeable } = flaggedNames(
      fs.readFileSync(aliasTsv, 'utf8'), liveNames);
    for (const row of flagged) {
      console.log(`  "${row.key}" -> ${row.id} is really "${row.real}"`
        + ' (sprite right, count stuck at 0 — needs a COLLOQUIAL entry)');
    }
    console.log(`${flagged.length} unmatchable name(s); `
      + `${untradeable} untradeable id(s) left to the constant check above`);
    if (flagged.length) {
      console.log('Review them by hand: node tools/review-item-names.mjs');
    }
  }
}

// 5. ANNOTATION ITEMS THAT NAME AN ID. ItemNeed.id counts one exact item,
//    which means its `name` is only a LABEL — nothing matches on it, so
//    sections 1-2 above cannot say anything useful about these entries. A
//    wrong id would sit there counting the wrong item, or nothing at all,
//    and look perfectly healthy.
//
//    The id is checked against RuneLite's own id -> name cache, and the
//    label against the real name with any parenthetical dropped: the whole
//    point of these entries is that the parenthetical is ours, added to
//    tell apart two items the game gives the SAME name ("Priest gown" is
//    both 426 and 428).
console.log('\n=== annotation items whose ID does not match their label ===');
{
  const liveNames = await liveItemNames(path.join(ROOT, 'tools/.wiki-cache/item-names-cache.json'));
  if (!liveNames) {
    console.log('(skipped — could not reach the live item mapping)');
  } else {
    let checked = 0;
    let flagged = 0;
    for (const [key, entry] of Object.entries(ann)) {
      for (const need of entry.items || []) {
        if (need.id == null) continue;
        checked++;
        const real = liveNames.get(Number(need.id));
        if (!real) {
          flagged++;
          console.log(`  [${key}] "${need.name}" -> id ${need.id} (NO SUCH ITEM ID)`);
          continue;
        }
        const label = String(need.name).replace(/\s*\([^)]*\)\s*$/, '').trim();
        if (canonical(label) !== canonical(real)) {
          flagged++;
          console.log(`  [${key}] "${need.name}" -> ${need.id} is really "${real}"`);
        }
      }
    }
    console.log(`${flagged} mismatched of ${checked} id-keyed annotation item(s)`);
  }
}
