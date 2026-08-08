#!/usr/bin/env node
// Put NUMBERS on the items a quest actually requires.
//
// The owner's report, twice: "we have access to Quest Helper and the wiki,
// we know what items we need". We do -- and we were throwing the numbers
// away. `cross-check-quest-kits` parses QH's getItemRequirements() for
// NAMES only, so a step ended up showing seven items with no quantity on
// any of them, all rendered identically, when QH's own panel said plainly
// "Item requirements: 1 x Pot".
//
// The other six on that step were the Oziris site's RUNNING CARRY LIST --
// gp, a barcrawl card, a spade -- which are not that quest's requirements
// in any source and genuinely have no number. Mixing the two lists and
// labelling them the same way is what made the panel useless there.
//
// So this reads QH's requirement quantities and applies them to items our
// kit ALREADY lists. Deliberately narrow:
//
//   * it only ever fills in a quantity that is currently null. It never
//     adds an item, never removes one, and never overrides a number a
//     human put there.
//   * adding QH-only items was considered and rejected: the cross-check
//     says QH requires exactly two items we omit, guide-wide, and both are
//     deliberate exclusions under the kit policy.
//
// Everything left unnumbered is then, by construction, carry advice -- and
// the panel can finally style the two differently.
//
//   node tools/seed-quest-requirements.mjs            # draft
//   node tools/seed-quest-requirements.mjs --apply
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const RES = path.join(__dirname, '../src/main/resources/com/ironscape');
const CACHE_DIR = path.join(__dirname, '.qh-cache');
const RAW_BASE = 'https://raw.githubusercontent.com/Zoinkwiz/quest-helper/master/';
const SRC_ROOT = 'src/main/java/com/questhelper/helpers/quests/';
const apply = process.argv.includes('--apply');

const stepId = (t) => crypto.createHash('sha256')
  .update(t.replace(/\s+/g, ' ').trim().toLowerCase(), 'utf8').digest('hex').slice(0, 10);
const runText = (rs) => (rs || []).map((r) => r.text).join('');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const guide = JSON.parse(fs.readFileSync(path.join(RES, 'guide/guide_data_oziris.json'), 'utf8'));
const annFile = path.join(RES, 'annotations/annotations_oziris.json');
const doc = JSON.parse(fs.readFileSync(annFile, 'utf8'));
const annotations = doc.annotations;

// quest -> the step carrying its kit (the COMPLETING step, per wave 3).
const kitStepByQuest = new Map();
guide.chapters.forEach((ch) => ch.sections.forEach((sec) => sec.steps.forEach((step) => {
  const quest = step.metadata?.quest?.trim();
  if (!quest) return;
  const id = stepId(runText(step.content));
  const complete = 'complete'.localeCompare(step.metadata?.questStatus ?? '',
    undefined, { sensitivity: 'base' }) === 0;
  if (complete || !kitStepByQuest.has(quest)) kitStepByQuest.set(quest, id);
})));

const guessPath = (questName) => {
  const words = questName.split(/[^a-zA-Z0-9]+/).filter(Boolean);
  const cls = words.map((w) => w[0].toUpperCase() + w.slice(1).toLowerCase()).join('');
  return `${SRC_ROOT}${words.join('').toLowerCase()}/${cls}.java`;
};

async function fetchCached(url) {
  fs.mkdirSync(CACHE_DIR, { recursive: true });
  const cacheFile = path.join(CACHE_DIR, url.replace(/[^a-z0-9.]+/gi, '_'));
  if (fs.existsSync(cacheFile)) return fs.readFileSync(cacheFile, 'utf8');
  await sleep(150);
  const res = await fetch(url);
  if (!res.ok) return null;
  const text = await res.text();
  fs.writeFileSync(cacheFile, text);
  return text;
}

// QH's required items, WITH the quantity its constructor carries. The third
// constructor argument is the count; absent means one. The variables that
// getItemRequirements() actually returns are the bring-list -- every other
// ItemRequirement in the file is something the quest tracks mid-way.
function qhRequirements(java) {
  const byVariable = new Map();
  for (const m of java.matchAll(
    /(\w+)\s*=\s*new\s+ItemRequirements?\(\s*(?:LogicType\.\w+\s*,\s*)?"((?:[^"\\]|\\.)*)"\s*,\s*([^;]*?)\)\s*;/g)) {
    const args = m[3];
    const qty = args.match(/,\s*(\d+)\s*$/);
    // Whether QH WROTE the number matters. Its constructor defaults to 1,
    // and for a stackable consumable that default is a floor, not a
    // requirement -- "Astral rune" appears once in While Guthix Sleeps and
    // you will spend dozens. Taking it literally would put a green 1/1 on
    // a step you are nowhere near ready for, which is the false green
    // wave 13 removed.
    byVariable.set(m[1], { name: m[2], quantity: qty ? +qty[1] : 1, explicit: !!qty });
  }
  const method = java.match(/List<ItemRequirement>\s+getItemRequirements\s*\(\)\s*\{([\s\S]*?)\n\t?\}/);
  if (!method) return [];
  const out = [];
  for (const id of method[1].matchAll(/\b([a-zA-Z_]\w*)\b/g)) {
    const hit = byVariable.get(id[1]);
    if (hit && !out.some((o) => o.name === hit.name)) out.push(hit);
  }
  return out;
}

// Same normalisation the cross-check uses, so the two tools agree about
// what "the same item" means.
function normalize(name) {
  let n = name.toLowerCase().replace(/\([^)]*\)/g, '').trim();
  n = n.split(/\s+or\s+|\//)[0];
  n = n.split(',')[0];
  n = n.replace(/^(?:any|a|an|some|few|many|lit|full)\s+/g, '');
  n = n.replace(/^\d+[\d,]*\s*x?\s*/, '').replace(/^x\d+\s*/, '');
  n = n.replace(/\s+/g, ' ').trim();
  return n.split(' ').map((w) =>
    w.length > 3 && w.endsWith('s') && !w.endsWith('ss') ? w.slice(0, -1) : w).join(' ');
}

const JUNK = /\+|\bgear\b|\bfood\b|teleport|\bcombat\b|\bweapon\b|armou?r|\bpotions?\b|\brunes? for\b|optional|if you|\(level/i;

// Things you hold a variable pile of. A defaulted quantity of 1 on any of
// these is a marker, not a measurement.
const STACKABLE = /\b(?:rune|runes|arrow|arrows|bolt|bolts|dart|darts|coins?|gp|essence|feather|feathers|seed|seeds|bone|bones|ore|bar|bars|log|logs|plank|planks|nail|nails|shaft|shafts|cake|cakes)\b/i;

const skippedDefaults = [];
let filled = 0;
let questsTouched = 0;
for (const [quest, id] of kitStepByQuest) {
  const entry = annotations[id];
  if (!entry?.items?.length) continue;
  const java = await fetchCached(RAW_BASE + guessPath(quest));
  if (!java) continue;
  const required = qhRequirements(java).filter((r) => !JUNK.test(r.name));
  if (!required.length) continue;

  const hits = [];
  for (const item of entry.items) {
    if (item.quantity != null) continue;            // a human or the wiki set it
    const key = normalize(item.name);
    const match = required.find((r) => {
      const rk = normalize(r.name);
      return rk === key || rk.includes(key) || key.includes(rk);
    });
    if (!match) continue;
    // A defaulted 1 is only believable for something you hold ONE of. For
    // ammo, runes and coins it means "you need these", not "you need one".
    if (!match.explicit && STACKABLE.test(item.name)) {
      skippedDefaults.push(`${quest}: ${item.name} (QH says 1, but it stacks — left as carry advice)`);
      continue;
    }
    hits.push({ item, name: item.name, from: match.name, quantity: match.quantity,
      explicit: match.explicit });
  }
  if (!hits.length) continue;
  questsTouched++;
  console.log(`\n${quest} [${id}]`);
  for (const h of hits) {
    console.log(`  ${h.name} -> quantity ${h.quantity}`
      + `${h.explicit ? '' : '  [QH default of 1]'}   (QH: "${h.from}")`);
    if (apply) h.item.quantity = h.quantity;
    filled++;
  }
}

if (skippedDefaults.length) {
  console.log(`\nLEFT ALONE (QH's default 1 on something stackable — a marker, not a count):`);
  for (const s of skippedDefaults) console.log(`  ${s}`);
}
console.log(`\n${filled} item quantities from Quest Helper across ${questsTouched} quest steps`);
if (apply) {
  fs.writeFileSync(annFile, JSON.stringify(doc, null, 1).replace(/\n/g, '\r\n') + '\r\n');
  console.log('applied to annotations_oziris.json');
} else {
  console.log('draft only — re-run with --apply to write');
}
