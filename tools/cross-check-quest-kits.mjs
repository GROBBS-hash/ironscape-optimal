#!/usr/bin/env node
// Cross-checks our seeded quest ITEM KITS (wiki {{Quest details|items}},
// seed-quest-items.mjs) against Quest Helper's hand-authored
// ItemRequirements — two independent sources agreeing is strong evidence
// the kit is right; disagreement is a review list, not a failure:
//
//   "QH also requires" = candidates to ADD to our kit (investigate)
//   "ours only"        = usually fine (wiki lists things QH acquires
//                        mid-quest, or names them differently)
//
// Usage: node tools/cross-check-quest-kits.mjs [--quest "Name"]

import crypto from 'crypto';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const RES = path.join(__dirname, '../src/main/resources/com/ironscape');
const CACHE_DIR = path.join(__dirname, '.qh-cache');
const RAW_BASE = 'https://raw.githubusercontent.com/Zoinkwiz/quest-helper/master/';
const SRC_ROOT = 'src/main/java/com/questhelper/helpers/quests/';

const stepId = (t) => crypto.createHash('sha256')
  .update(t.replace(/\s+/g, ' ').trim().toLowerCase(), 'utf8').digest('hex').slice(0, 10);

// Tradeable item names (audit-goals' cache): a QH-only item that is NOT
// tradeable is almost always quest-internal (Hazeel scroll, research
// package) — you can't pre-buy it, so it's not kit material.
const mappingCache = path.join(__dirname, 'wiki-item-mapping.json');
if (!fs.existsSync(mappingCache)) {
  const res = await fetch('https://prices.runescape.wiki/api/v1/osrs/mapping',
    { headers: { 'User-Agent': 'ironscape-runelite-plugin dev tooling (kit cross-check)' } });
  fs.writeFileSync(mappingCache, JSON.stringify(await res.json()));
}
const tradeable = new Set(JSON.parse(fs.readFileSync(mappingCache, 'utf8'))
  .map((i) => i.name.toLowerCase()));
const isTradeable = (name) => {
  const n = name.toLowerCase().trim();
  return tradeable.has(n)
    || (n.endsWith('s') && tradeable.has(n.slice(0, -1)))
    || tradeable.has(n + 's');
};
const runText = (rs) => (rs || []).map((r) => r.text).join('');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---- our side: quest -> kit items on its completing step ---------------
const guide = JSON.parse(fs.readFileSync(path.join(RES, 'guide/guide_data_oziris.json'), 'utf8'));
const annotations = JSON.parse(fs.readFileSync(
  path.join(RES, 'annotations/annotations_oziris.json'), 'utf8')).annotations;

const kitStepByQuest = new Map(); // quest -> step id carrying the kit
guide.chapters.forEach((ch) => ch.sections.forEach((sec) => sec.steps.forEach((step) => {
  const quest = step.metadata?.quest?.trim();
  if (!quest) return;
  const id = stepId(runText(step.content));
  const complete = 'complete'.localeCompare(step.metadata?.questStatus ?? '',
    undefined, { sensitivity: 'base' }) === 0;
  if (complete || !kitStepByQuest.has(quest)) {
    if (complete || !kitStepByQuest.has(quest)) kitStepByQuest.set(quest, id);
  }
})));

// ---- QH side -----------------------------------------------------------
function guessPath(questName) {
  const words = questName.split(/[^a-zA-Z0-9]+/).filter(Boolean);
  const cls = words.map(w => w[0].toUpperCase() + w.slice(1).toLowerCase()).join('');
  const slug = words.join('').toLowerCase();
  return `${SRC_ROOT}${slug}/${cls}.java`;
}

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

// QH's REQUIRED items: the variables its getItemRequirements() returns,
// resolved to names via their `x = new ItemRequirement("Name", ...)`
// assignments. Falls back to every ItemRequirement in the file when the
// method isn't found (some helpers structure it differently).
function qhRequiredItems(java) {
  const nameByVariable = new Map();
  for (const m of java.matchAll(
    /(\w+)\s*=\s*new\s+ItemRequirements?\(\s*(?:LogicType\.\w+\s*,\s*)?"((?:[^"\\]|\\.)*)"/g)) {
    nameByVariable.set(m[1], m[2]);
  }
  const method = java.match(
    /List<ItemRequirement>\s+getItemRequirements\s*\(\)\s*\{([\s\S]*?)\n\t?\}/);
  if (method) {
    const names = [];
    for (const id of method[1].matchAll(/\b([a-zA-Z_]\w*)\b/g)) {
      const name = nameByVariable.get(id[1]);
      if (name && !names.includes(name)) names.push(name);
    }
    if (names.length) return names;
  }
  return [...new Set(nameByVariable.values())];
}

// Freeform QH names that aren't concrete items ("Combat gear + food").
const JUNK = /\+|\bgear\b|\bfood\b|teleport|\bcombat\b|\bweapon\b|armou?r|\bpotions?\b|\brunes? for\b|optional|if you|\(level/i;

// An item the quest HANDS YOU mid-quest is not kit material — seeding
// it would sit permanently red. Heuristic: the same helper file has a
// step description that ACQUIRES it ("Search the crate for the key",
// "Pick up the scroll"). Review aid, not proof — flagged separately.
const ACQUIRE_VERB = /\b(?:pick ?up|search(?:es)?|take|grab|steal|loot|receive|gives? you|hands? you|found in|he gives|she gives)\b/i;

function inQuestItems(java, names) {
  const acquireStrings = [];
  for (const m of java.matchAll(/"((?:[^"\\]|\\.)*)"/g)) {
    if (m[1].length > 15 && ACQUIRE_VERB.test(m[1])) {
      acquireStrings.push(m[1].toLowerCase());
    }
  }
  return new Set(names.filter((n) =>
    acquireStrings.some((s) => s.includes(n.toLowerCase()))));
}

function normalize(name) {
  let n = name.toLowerCase().replace(/\([^)]*\)/g, '').trim();
  n = n.split(/\s+or\s+|\//)[0];          // "climbing boots or 12 coins" -> first side
  n = n.split(',')[0];                    // "rope, multiple in case..." -> "rope"
  n = n.replace(/^(?:any|a|an|some|few|many|lit|full)\s+/g, '');
  n = n.replace(/^\d+[\d,]*\s*x?\s*/, '').replace(/^x\d+\s*/, '');
  n = n.replace(/\s+/g, ' ').trim();
  // Every word depluralizes ("buckets of water" == "bucket of water").
  n = n.split(' ').map((w) =>
    w.length > 3 && w.endsWith('s') && !w.endsWith('ss') ? w.slice(0, -1) : w).join(' ');
  return n;
}

// Matched when equal OR one side's words contain the other's whole name
// ("pickaxe" satisfies "any pickaxe"; "rune essence" satisfies "rune or
// pure essence" via the first-side split).
const covered = (key, otherKeys) => otherKeys.has(key)
  || [...otherKeys].some((o) => o.includes(key) || key.includes(o));
const only = (a, b) => [...a.keys()]
  .filter((k) => !covered(k, new Set(b.keys()))).map((k) => a.get(k));

// ---- run ---------------------------------------------------------------
const filter = process.argv.indexOf('--quest');
const wanted = filter >= 0 ? process.argv[filter + 1] : null;

let checked = 0;
let skipped = 0;
let flaggedQuests = 0;
for (const [quest, id] of kitStepByQuest) {
  if (wanted && quest.toLowerCase() !== wanted.toLowerCase()) continue;
  const ours = (annotations[id]?.items ?? []).map((i) => i.name);
  const java = await fetchCached(RAW_BASE + guessPath(quest));
  if (java == null) {
    skipped++;
    continue;
  }
  checked++;
  const qh = qhRequiredItems(java).filter((n) => !JUNK.test(n));
  const inQuest = inQuestItems(java, qh);
  const oursNorm = new Map(ours.map((n) => [normalize(n), n]));
  const qhNorm = new Map(qh.filter((n) => !inQuest.has(n)).map((n) => [normalize(n), n]));
  const qhOnlyAll = only(qhNorm, oursNorm);
  const qhOnly = qhOnlyAll.filter(isTradeable);
  const untradeable = qhOnlyAll.filter((n) => !isTradeable(n));
  const oursOnly = only(oursNorm, qhNorm);
  // OUR kit item that QH never requires AND whose name shows up in the
  // helper's step text with a CONSUME verb ("use the bucket of water on
  // the drain") is likely spent MID-QUEST — on the finishing step it
  // sits permanently red (Demon Slayer's bones, owner-hit). Review list.
  const CONSUME_VERB = /\b(?:use|pour|give|hand|add|put|insert|place|empty|combine)\b/i;
  const consumeStrings = [...java.matchAll(/"((?:[^"\\]|\\.)*)"/g)]
    .map((m) => m[1].toLowerCase())
    .filter((s) => s.length > 15 && CONSUME_VERB.test(s));
  const staleSuspects = oursOnly.filter((n) =>
    consumeStrings.some((s) => s.includes(normalize(n))));
  if (!qhOnly.length && !oursOnly.length && !untradeable.length) continue;
  flaggedQuests++;
  console.log(`\n${quest} [${id}]`);
  if (qhOnly.length) console.log(`  QH also requires: ${qhOnly.join(', ')}`);
  if (untradeable.length) console.log(`  (untradeable, likely in-quest: ${untradeable.join(', ')})`);
  if (inQuest.size) console.log(`  (obtained in-quest, ignored: ${[...inQuest].join(', ')})`);
  if (oursOnly.length) console.log(`  ours only:        ${oursOnly.join(', ')}`);
  if (staleSuspects.length) console.log(`  STALE? consumed mid-quest: ${staleSuspects.join(', ')}`);
}
console.log(`\n${checked} quests checked (${skipped} without a matching QH file), `
  + `${flaggedQuests} with differences`);
