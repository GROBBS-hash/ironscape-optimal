#!/usr/bin/env node
// Surfaces Quest Helper's GENERIC gear guidance ("Combat gear", "Food",
// "Armour", prayer/energy potions) as NOTE lines on each quest's kit
// step — these are deliberately junk-filtered out of item kits (no
// countable item exists), but players who never open QH still deserve
// the warning that a fight is coming.
//
// Reads QH's getItemRequirements()/getItemRecommended() per quest (same
// fetch + variable resolution as cross-check-quest-kits), keeps ONLY the
// generic entries, and appends one "Bring: ..." line to the kit step's
// note. Idempotent: steps whose note already carries a "Bring:" line are
// skipped; hand-authored notes are appended to, never replaced.
//
// Usage: node tools/seed-quest-gear-notes.mjs           (dry run, prints)
//        node tools/seed-quest-gear-notes.mjs --apply

import crypto from 'crypto';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const RES = path.join(__dirname, '../src/main/resources/com/ironscape');
const CACHE_DIR = path.join(__dirname, '.qh-cache');
const RAW_BASE = 'https://raw.githubusercontent.com/Zoinkwiz/quest-helper/master/';
const SRC_ROOT = 'src/main/java/com/questhelper/helpers/quests/';
const ANNOTATIONS_FILE = path.join(RES, 'annotations/annotations_oziris.json');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const stepId = (t) => crypto.createHash('sha256')
  .update(t.replace(/\s+/g, ' ').trim().toLowerCase(), 'utf8').digest('hex').slice(0, 10);
const runText = (rs) => (rs || []).map((r) => r.text).join('');

// The GENERIC prep entries worth a note — the same class the kit JUNK
// filter drops. Concrete items stay in kits, not notes.
const GENERIC = /^(?:combat gear|armou?r|food|good food|weapon|a weapon|combat equipment|prayer potions?|energy potions?|stamina potions?|antipoisons?|combat gear \+ food|.*combat gear.*)$/i;

const guide = JSON.parse(fs.readFileSync(path.join(RES, 'guide/guide_data_oziris.json'), 'utf8'));
const annotations = JSON.parse(fs.readFileSync(ANNOTATIONS_FILE, 'utf8'));

const kitStepByQuest = new Map();
guide.chapters.forEach((ch) => ch.sections.forEach((sec) => sec.steps.forEach((step) => {
  const quest = step.metadata?.quest?.trim();
  if (!quest) return;
  const id = stepId(runText(step.content));
  const complete = 'complete'.localeCompare(step.metadata?.questStatus ?? '',
    undefined, { sensitivity: 'base' }) === 0;
  if (complete || !kitStepByQuest.has(quest)) kitStepByQuest.set(quest, id);
})));

function guessPath(questName) {
  const words = questName.split(/[^a-zA-Z0-9]+/).filter(Boolean);
  const cls = words.map((w) => w[0].toUpperCase() + w.slice(1).toLowerCase()).join('');
  return `${SRC_ROOT}${words.join('').toLowerCase()}/${cls}.java`;
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

/** Names a getter method's returned variables resolve to. */
function methodItemNames(java, methodName) {
  const nameByVariable = new Map();
  for (const m of java.matchAll(
    /(\w+)\s*=\s*new\s+ItemRequirements?\(\s*(?:LogicType\.\w+\s*,\s*)?"((?:[^"\\]|\\.)*)"/g)) {
    nameByVariable.set(m[1], m[2]);
  }
  const method = java.match(new RegExp(
    'List<ItemRequirement>\\s+' + methodName + '\\s*\\(\\)\\s*\\{([\\s\\S]*?)\\n\\t?\\}'));
  if (!method) return [];
  const names = [];
  for (const id of method[1].matchAll(/\b([a-zA-Z_]\w*)\b/g)) {
    const name = nameByVariable.get(id[1]);
    if (name && !names.includes(name)) names.push(name);
  }
  return names;
}

const apply = process.argv.includes('--apply');
let noted = 0;
for (const [quest, kitStep] of kitStepByQuest) {
  const java = await fetchCached(RAW_BASE + guessPath(quest));
  if (!java) continue;
  const required = methodItemNames(java, 'getItemRequirements')
    .filter((n) => GENERIC.test(n.trim()));
  const recommended = methodItemNames(java, 'getItemRecommended')
    .filter((n) => GENERIC.test(n.trim()));
  if (!required.length && !recommended.length) continue;
  const parts = [];
  if (required.length) parts.push('Bring: ' + [...new Set(required)].join(', ') + '.');
  if (recommended.length) parts.push('Recommended: ' + [...new Set(recommended)].join(', ') + '.');
  const line = parts.join(' ');
  const existing = annotations.annotations[kitStep]?.note || '';
  if (/\bBring:/.test(existing)) continue; // already noted
  console.log(`${apply ? 'NOTE ' : 'would'} ${kitStep} [${quest}] ${line}`);
  if (apply) {
    annotations.annotations[kitStep] = {
      ...(annotations.annotations[kitStep] || {}),
      note: existing ? existing + '\n' + line : line,
    };
    noted++;
  }
}
if (noted > 0) {
  fs.writeFileSync(ANNOTATIONS_FILE, JSON.stringify(annotations, null, 1) + '\n');
}
console.log(`\n${apply ? 'noted' : 'candidates:'} ${noted || '(dry run)'}`);
