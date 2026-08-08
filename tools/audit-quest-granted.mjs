#!/usr/bin/env node
// TOOL-01. Flags items the plugin will DEMAND on a quest step that the
// quest itself hands you — the "plague sample 0/1, permanently red" class.
//
// This is the KIT-SEEDING POLICY expressed as a test:
//   "items a quest hands you mid-quest never carry requirements — they sit
//    permanently red, and that's misinformation" (owner, 2026-08-05)
//
// It differs from cross-check-quest-kits.mjs in WHAT it checks. That tool
// compares our seeded annotation kit on a quest's finishing step against
// Quest Helper. The plague sample is neither: it is a goal the DETECTOR
// found in step text ("get the plague sample"), on the quest's START step.
// So this audit reads the goals the plugin actually resolves —
// build/goal-audit.tsv, written by GoalAuditDumpTest — plus annotation
// items, and checks every one of them against Quest Helper.
//
// Quest Helper is the oracle because it separates the two cases cleanly:
//   getItemRequirements()  = what you must BRING
//   other ItemRequirements = things it tracks DURING the quest
// An item in the second set and not the first is granted mid-quest.
//
// Verdicts, most to least confident:
//   GRANTED    QH tracks it but never asks you to bring it, and its step
//              text acquires it ("Search the crate", "Elena gives you").
//   LIKELY     same, minus the acquire verb, but the item is untradeable
//              — an ironman cannot pre-buy it, so it can't be kit.
//   UNKNOWN    QH never mentions it. Usually fine (generic carry-list
//              items like a pickaxe), occasionally a wrong item name.
//
// Usage:
//   gradlew test --tests "*.GoalAuditDumpTest"   # refresh goal-audit.tsv
//   node tools/audit-quest-granted.mjs [--quest "Name"] [--all]
//
// --all also prints UNKNOWN, which is long and mostly carry-list noise.

import crypto from 'crypto';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const RES = path.join(__dirname, '../src/main/resources/com/ironscape');
const CACHE_DIR = path.join(__dirname, '.qh-cache');
const RAW_BASE = 'https://raw.githubusercontent.com/Zoinkwiz/quest-helper/master/';
const SRC_ROOT = 'src/main/java/com/questhelper/helpers/quests/';
const AUDIT_TSV = path.join(__dirname, '../build/goal-audit.tsv');

const args = process.argv.slice(2);
const wanted = args.indexOf('--quest') >= 0 ? args[args.indexOf('--quest') + 1] : null;
const showUnknown = args.includes('--all');

const stepId = (t) => crypto.createHash('sha256')
  .update(t.replace(/\s+/g, ' ').trim().toLowerCase(), 'utf8').digest('hex').slice(0, 10);
const runText = (rs) => (rs || []).map((r) => r.text).join('');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---- our side ----------------------------------------------------------
const guide = JSON.parse(fs.readFileSync(
  path.join(RES, 'guide/guide_data_oziris.json'), 'utf8'));
const annotations = JSON.parse(fs.readFileSync(
  path.join(RES, 'annotations/annotations_oziris.json'), 'utf8')).annotations;

// step id -> {quest, questStatus, text}. Only quest-tagged steps matter:
// an item demanded away from a quest can't be "granted by the quest".
const questSteps = new Map();
guide.chapters.forEach((ch) => ch.sections.forEach((sec) => sec.steps.forEach((step) => {
  const quest = step.metadata?.quest?.trim();
  if (!quest) return;
  const text = runText(step.content);
  questSteps.set(stepId(text), {
    quest, status: step.metadata?.questStatus?.trim() ?? '', text,
  });
})));

// Everything the plugin will ask for, per step: detected goals first
// (the class the seeder never sees), then annotation items.
const demands = new Map(); // stepId -> [{name, qty, source, where}]
const add = (sid, item) => {
  if (!questSteps.has(sid)) return;
  const list = demands.get(sid) ?? [];
  if (!list.some((i) => i.name === item.name)) list.push(item);
  demands.set(sid, list);
};

// Already marked `granted`? Then it has been reviewed and the plugin
// already renders it muted and keeps it out of routing — the audit's job
// is the list of things NOT yet handled, so drop it (and the same-named
// detected goal it carries the flag onto). This is what makes the output
// converge; without it every fixed item reports forever.
const handled = new Set();
for (const [key, entry] of Object.entries(annotations)) {
  for (const item of entry.items ?? []) {
    if (item.granted) handled.add(`${key.split(':')[0]} ${item.name.toLowerCase()}`);
  }
}

// The other half of converging: a REJECTED verdict. Marking an item
// `granted` records "yes"; nothing records "reviewed, and the quest really
// does not hand it over", so those re-reported on every run — four of the
// seven findings in this audit's second sweep were questions the owner had
// already answered (phoenix feather and barronite deposit in wave 12, the
// silverlight keys back in wave 9). Same shape as audit-goals' VERIFIED
// allow-list for hand-checked item_ids keys.
const reviewedFile = path.join(__dirname, 'quest-granted-reviewed.json');
if (fs.existsSync(reviewedFile)) {
  const reviewed = JSON.parse(fs.readFileSync(reviewedFile, 'utf8')).reviewed ?? {};
  for (const key of Object.keys(reviewed)) handled.add(key.toLowerCase());
}

if (!fs.existsSync(AUDIT_TSV)) {
  console.error(`missing ${path.relative(process.cwd(), AUDIT_TSV)} — run:\n`
    + '  gradlew test --tests "*.GoalAuditDumpTest"');
  process.exit(1);
}
for (const line of fs.readFileSync(AUDIT_TSV, 'utf8').split('\n')) {
  const cells = line.split('\t');
  if (cells[0] !== 'ITEM') continue;
  const [, subKey, qty, name, , subText] = cells;
  const sid = subKey.split(':')[0];
  if (handled.has(`${sid} ${name.toLowerCase()}`)) continue;
  add(sid, { name, qty, source: 'goal', where: subText ?? '' });
}
for (const [key, entry] of Object.entries(annotations)) {
  for (const item of entry.items ?? []) {
    if (item.granted) continue;
    // Annotation items need the same handled check the goal loop does,
    // or a reviewed REJECTION never suppresses anything — every finding
    // this audit has ever produced came in through here.
    if (handled.has(`${key.split(':')[0]} ${item.name.toLowerCase()}`)) continue;
    add(key.split(':')[0], {
      name: item.name,
      qty: item.quantity == null ? '-' : String(item.quantity),
      source: 'annotation',
      where: '',
    });
  }
}

// ---- Quest Helper ------------------------------------------------------
function guessPath(questName) {
  const words = questName.split(/[^a-zA-Z0-9]+/).filter(Boolean);
  const cls = words.map((w) => w[0].toUpperCase() + w.slice(1).toLowerCase()).join('');
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

// Tradeable names (shared cache with cross-check-quest-kits/audit-goals).
const mappingCache = path.join(__dirname, 'wiki-item-mapping.json');
if (!fs.existsSync(mappingCache)) {
  const res = await fetch('https://prices.runescape.wiki/api/v1/osrs/mapping',
    { headers: { 'User-Agent': 'ironscape-runelite-plugin dev tooling (quest-granted audit)' } });
  fs.writeFileSync(mappingCache, JSON.stringify(await res.json()));
}
const tradeable = new Set(JSON.parse(fs.readFileSync(mappingCache, 'utf8'))
  .map((i) => i.name.toLowerCase()));

function normalize(name) {
  let n = name.toLowerCase().replace(/\([^)]*\)/g, '').trim();
  n = n.split(/\s+or\s+|\//)[0];
  n = n.split(',')[0];
  n = n.replace(/^(?:any|a|an|some|few|many|lit|full|the)\s+/g, '');
  n = n.replace(/^\d+[\d,]*\s*x?\s*/, '').replace(/^x\d+\s*/, '');
  n = n.replace(/\s+/g, ' ').trim();
  return n.split(' ').map((w) =>
    w.length > 3 && w.endsWith('s') && !w.endsWith('ss') ? w.slice(0, -1) : w).join(' ');
}
// Not on the GE, but every ironman has them / can make them — the prices
// mapping is GE-tradeable only, so it calls these untradeable.
const OFF_GE = new Set(['coin', 'coins', 'gp']);

const isTradeable = (name) => {
  const n = normalize(name);
  if (OFF_GE.has(n)) return true;
  if (tradeable.has(n) || tradeable.has(`${n}s`)) return true;
  return [...tradeable].some((t) => {
    const tn = normalize(t);
    // A FAMILY ROOT is satisfiable: the guide says "pickaxe" or "bar" and
    // the plugin's substitute families accept any tier, so a bare root
    // that some real item ends with ("Bronze pickaxe") is obtainable.
    return tn === n || tn.endsWith(` ${n}`);
  });
};

// Same shape as cross-check-quest-kits: variable -> requirement name, and
// the subset getItemRequirements() returns.
function qhItems(java) {
  const nameByVariable = new Map();
  for (const m of java.matchAll(
    /(\w+)\s*=\s*new\s+ItemRequirements?\(\s*(?:LogicType\.\w+\s*,\s*)?"((?:[^"\\]|\\.)*)"/g)) {
    nameByVariable.set(m[1], m[2]);
  }
  const bring = new Set();
  const method = java.match(
    /List<ItemRequirement>\s+getItemRequirements\s*\(\)\s*\{([\s\S]*?)\n\t?\}/);
  if (method) {
    for (const id of method[1].matchAll(/\b([a-zA-Z_]\w*)\b/g)) {
      const name = nameByVariable.get(id[1]);
      if (name) bring.add(name);
    }
  }
  return { all: [...new Set(nameByVariable.values())], bring, hasBringList: bring.size > 0 };
}

// QH step descriptions that ACQUIRE something. The plague sample's line is
// "Elena will give you the plague sample" — the giver phrasing matters as
// much as the search/loot ones.
const ACQUIRE_VERB =
  /\b(?:pick ?up|search(?:es)?|take|grab|steal|loot|receive|collect|will give|gives? you|hands? you|found in|obtain)\b/i;

function acquiredNames(java, names) {
  const lines = [...java.matchAll(/"((?:[^"\\]|\\.)*)"/g)]
    .map((m) => m[1].toLowerCase())
    .filter((s) => s.length > 15 && ACQUIRE_VERB.test(s));
  return new Set(names.filter((n) => {
    const norm = normalize(n);
    return lines.some((s) => s.includes(norm));
  }));
}

const matches = (ours, theirs) => {
  const a = normalize(ours);
  const b = normalize(theirs);
  return a === b || a.includes(b) || b.includes(a);
};

// ---- run ---------------------------------------------------------------
const byQuest = new Map();
for (const [sid, meta] of questSteps) {
  if (!demands.has(sid)) continue;
  if (wanted && meta.quest.toLowerCase() !== wanted.toLowerCase()) continue;
  const list = byQuest.get(meta.quest) ?? [];
  list.push({ sid, meta, items: demands.get(sid) });
  byQuest.set(meta.quest, list);
}

let granted = 0;
let likely = 0;
let unknown = 0;
let skipped = 0;
let flaggedQuests = 0;

for (const [quest, steps] of byQuest) {
  const java = await fetchCached(RAW_BASE + guessPath(quest));
  if (java == null) {
    skipped++;
    continue;
  }
  const { all, bring, hasBringList } = qhItems(java);
  // No getItemRequirements() to compare against: every name would look
  // "granted". Report nothing rather than a wall of false positives.
  if (!hasBringList) {
    skipped++;
    continue;
  }
  const tracked = all.filter((n) => ![...bring].some((b) => matches(n, b)));
  const acquired = acquiredNames(java, tracked);

  const findings = [];
  for (const { sid, meta, items } of steps) {
    for (const item of items) {
      const hit = tracked.find((n) => matches(item.name, n));
      let verdict = null;
      // TRADEABILITY IS THE DISCRIMINATOR, and without it this audit is
      // mostly false positives. QH's getItemRequirements() is often a
      // short list even when the wiki requires more (Royal Trouble returns
      // just coalOrPickaxe + combatGear), so "QH tracks it but doesn't ask
      // for it" alone flagged rope, planks, buckets and coins — all things
      // you genuinely bring. An item an ironman can buy or bank is
      // satisfiable at that route position by definition; only the
      // untradeable ones can sit permanently red.
      if (hit && !isTradeable(item.name)) verdict = acquired.has(hit) ? 'GRANTED' : 'LIKELY ';
      else if (!hit && !all.some((n) => matches(item.name, n))) verdict = 'UNKNOWN';
      if (!verdict) continue;
      if (verdict === 'UNKNOWN' && !showUnknown) {
        unknown++;
        continue;
      }
      if (verdict === 'GRANTED') granted++;
      else if (verdict.trim() === 'LIKELY') likely++;
      else unknown++;
      findings.push({ sid, meta, item, verdict, hit });
    }
  }
  if (!findings.length) continue;
  flaggedQuests++;
  console.log(`\n${quest}`);
  for (const f of findings) {
    const qty = f.item.qty === '-' ? 'unspecified' : f.item.qty;
    console.log(`  ${f.verdict}  ${f.item.name} (x${qty}, ${f.item.source})`
      + `  [${f.sid} ${f.meta.status || 'step'}]`);
    if (f.hit) console.log(`           QH tracks it as "${f.hit}", never asks you to bring it`);
    if (f.item.where) console.log(`           from: "${f.item.where}"`);
  }
}

console.log(`\n${byQuest.size} quests with demanded items, ${flaggedQuests} flagged`
  + ` (${skipped} without a comparable QH helper)`);
console.log(`GRANTED ${granted}   LIKELY ${likely}   UNKNOWN ${unknown}`
  + `${showUnknown ? '' : ' (hidden; --all to see)'}`);
