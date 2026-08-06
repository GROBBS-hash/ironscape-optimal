#!/usr/bin/env node
// Mines Quest Helper's achievement-diary helpers for each task's
// completion var — Lumbridge-style diaries pack one BIT per task into a
// varp, Karamja uses individual varbits — and builds a guide-wide atlas
// (tools/diary-tasks.json). Then drafts checkpoint annotations for guide
// steps that mention diary tasks: review the draft (set "pick" to the
// task name), then --apply seeds sub-keyed requires — the barcrawl-stamp
// mechanism, one checkpoint per diary task. Numeric ids come from javap
// over the runelite-api jar in the gradle cache, same as hand-authoring.
//
// Usage: node tools/seed-diary-tasks.mjs           (mine + draft)
//        node tools/seed-diary-tasks.mjs --apply   (apply picked rows)

import fs from 'fs';
import path from 'path';
import crypto from 'crypto';
import { execSync } from 'child_process';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.join(__dirname, '..');
const RES = path.join(ROOT, 'src/main/resources/com/ironscape');
const ANNOTATIONS_FILE = path.join(RES, 'annotations/annotations_oziris.json');
const ATLAS_FILE = path.join(__dirname, 'diary-tasks.json');
const DRAFT_FILE = path.join(__dirname, 'diary-tasks-draft.json');
const CACHE_DIR = path.join(__dirname, '.qh-cache');
const QH_BASE = 'https://raw.githubusercontent.com/Zoinkwiz/quest-helper/master/src/main/java/com/questhelper/helpers/achievementdiaries';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const stepId = (t) => crypto.createHash('sha256')
  .update(t.replace(/\s+/g, ' ').trim().toLowerCase(), 'utf8').digest('hex').slice(0, 10);

// ---- numeric ids for VarPlayerID / VarbitID constants, via javap ----
function constantMap(className) {
  const jar = execSync(
    'find ~/.gradle/caches -name "runelite-api-*.jar" | grep -v sources | head -1',
    { shell: 'bash' }).toString().trim()
    // Git Bash prints /c/... — the Windows JVM needs C:/...
    .replace(/^\/([a-z])\//, (all, drive) => drive.toUpperCase() + ':/');
  const out = execSync(`javap -constants -classpath "${jar}" ${className}`).toString();
  const map = {};
  for (const m of out.matchAll(/public static final int (\w+) = (\d+);/g)) {
    map[m[1]] = +m[2];
  }
  return map;
}

async function fetchCached(url) {
  fs.mkdirSync(CACHE_DIR, { recursive: true });
  const cacheFile = path.join(CACHE_DIR, url.replace(/[^a-z0-9.]+/gi, '_')
    + '_' + crypto.createHash('sha256').update(url).digest('hex').slice(0, 8));
  if (fs.existsSync(cacheFile)) {
    return fs.readFileSync(cacheFile, 'utf8');
  }
  await sleep(500);
  const res = await fetch(url);
  if (!res.ok) {
    return null;
  }
  const text = await res.text();
  fs.writeFileSync(cacheFile, text);
  return text;
}

/** One QH diary helper file -> [{task, desc, varp?, varbit?, bit?, value?}] */
function parseHelper(java, varps, varbits) {
  const tasks = [];
  // notDrayAgi = new VarplayerRequirement(VarPlayerID.LUMB_DRAY_ACHIEVEMENT_DIARY, false, 1);
  for (const m of java.matchAll(
    /not(\w+)\s*=\s*new VarplayerRequirement\(VarPlayerID\.(\w+),\s*false,\s*(\d+)\)/g)) {
    tasks.push({ task: m[1], varp: varps[m[2]], bit: +m[3] });
  }
  // notCaughtFish = new VarbitRequirement(VarbitID.ATJUN_EASY_FISHING, 1) / (3572, 5) forms
  for (const m of java.matchAll(
    /not(\w+)\s*=\s*new VarbitRequirement\((?:VarbitID\.(\w+)|(\d+)),\s*(?:Operation\.\w+,\s*)?(\d+)/g)) {
    const id = m[2] ? varbits[m[2]] : +m[3];
    tasks.push({ task: m[1], varbit: id, value: +m[4] });
  }
  // First quoted string of the task's own step = human description.
  for (const t of tasks) {
    const lower = t.task[0].toLowerCase() + t.task.slice(1);
    const step = java.match(new RegExp(
      `\\b${lower}\\s*=\\s*new \\w+Step\\([^;]*?"([^"]+)"`, 's'));
    t.desc = step ? step[1] : null;
  }
  return tasks.filter((t) => t.varp !== undefined || t.varbit !== undefined);
}

const apply = process.argv.includes('--apply');
const annotations = JSON.parse(fs.readFileSync(ANNOTATIONS_FILE, 'utf8'));

if (apply) {
  const draft = JSON.parse(fs.readFileSync(DRAFT_FILE, 'utf8'));
  let applied = 0;
  for (const row of draft) {
    if (!row.pick) {
      continue;
    }
    const task = row.candidates.find((c) => c.task === row.pick);
    if (!task) {
      console.log(`pick "${row.pick}" not among candidates for ${row.stepId}`);
      continue;
    }
    const key = row.stepId + ':0';
    // QH's varbit rows come from the NOT-done form (value 0 = not yet):
    // the done condition is >= 1. Varp rows carry the bit directly.
    const requires = task.varp !== undefined
      ? { varp: task.varp, bit: task.bit, label: 'diary task' }
      : { varbit: task.varbit, value: Math.max(1, task.value), label: 'diary task' };
    annotations.annotations[key] = { ...(annotations.annotations[key] || {}), requires };
    console.log(`applied ${key} <- ${row.diary}/${task.task}`);
    applied++;
  }
  fs.writeFileSync(ANNOTATIONS_FILE, JSON.stringify(annotations, null, 1) + '\n');
  console.log(`\napplied ${applied} diary checkpoint(s)`);
  process.exit(0);
}

const varps = constantMap('net.runelite.api.gameval.VarPlayerID');
const varbits = constantMap('net.runelite.api.gameval.VarbitID');

// Region directories and tier files as they exist in the QH repo.
const REGIONS = ['ardougne', 'desert', 'falador', 'fremennik', 'kandarin',
  'karamja', 'kourendkebos', 'lumbridgeanddraynor', 'morytania', 'varrock',
  'westernprovinces', 'wilderness'];
const TIERS = ['Easy', 'Medium', 'Hard', 'Elite'];
const CLASS_PREFIX = { // directory -> file name prefix in the QH repo
  ardougne: 'Ardougne', desert: 'Desert', falador: 'Falador',
  fremennik: 'Fremennik', kandarin: 'Kandarin', karamja: 'Karamja',
  kourendkebos: 'KourendKebos', lumbridgeanddraynor: 'Lumbridge',
  morytania: 'Morytania', varrock: 'Varrock',
  westernprovinces: 'WesternProvinces', wilderness: 'Wilderness',
};

const atlas = {};
for (const region of REGIONS) {
  for (const tier of TIERS) {
    const url = `${QH_BASE}/${region}/${CLASS_PREFIX[region]}${tier}.java`;
    const java = await fetchCached(url);
    if (!java || java.startsWith('404')) {
      continue;
    }
    const tasks = parseHelper(java, varps, varbits);
    if (tasks.length) {
      atlas[`${CLASS_PREFIX[region]} ${tier}`] = tasks;
    }
  }
}
fs.writeFileSync(ATLAS_FILE, JSON.stringify(atlas, null, 1) + '\n');
console.log(`atlas: ${Object.keys(atlas).length} diaries, `
  + Object.values(atlas).reduce((s, t) => s + t.length, 0) + ' tasks -> ' + ATLAS_FILE);

// ---- draft: guide steps that mention diaries, region-guessed ----
const REGION_HINTS = [
  [/karamja|banana|brimhaven|tai bwo/i, 'Karamja'],
  [/fally|falador|sarah/i, 'Falador'],
  [/ardy|ardougne/i, 'Ardougne'],
  [/varrock/i, 'Varrock'],
  [/lumbridge|draynor|lumby/i, 'Lumbridge'],
  [/seers|catherby|kandarin/i, 'Kandarin'],
  [/fremennik|rellekka/i, 'Fremennik'],
  [/morytania|canifis|phasmatys/i, 'Morytania'],
  [/kourend|hosidius|zeah/i, 'KourendKebos'],
  [/wilderness|wildy/i, 'Wilderness'],
  [/desert|kharid|nardah/i, 'Desert'],
  [/gnome|western|yanille/i, 'WesternProvinces'],
];
const guide = JSON.parse(fs.readFileSync(
  path.join(RES, 'guide/guide_data_oziris.json'), 'utf8'));
const draft = [];
for (const ch of guide.chapters) {
  for (const sec of ch.sections) {
    for (const step of sec.steps) {
      const text = (step.content || []).map((c) => c.text).join('');
      if (!/\bdiar(y|ies)\b/i.test(text)) {
        continue;
      }
      const id = stepId(text);
      if (annotations.annotations[id + ':0']?.requires) {
        continue; // already checkpointed (the Draynor lap)
      }
      const context = text + ' ' + (step.metadata?.location || '');
      const region = REGION_HINTS.find(([re]) => re.test(context))?.[1];
      const candidates = Object.entries(atlas)
        .filter(([diary]) => !region || diary.startsWith(region))
        .flatMap(([diary, tasks]) => tasks.map((t) => ({ diary, ...t })));
      draft.push({
        stepId: id, text: text.slice(0, 90), region: region || null,
        pick: null,
        candidates: candidates.map((c) => ({
          diary: c.diary, task: c.task, desc: c.desc,
          varp: c.varp, bit: c.bit, varbit: c.varbit, value: c.value,
        })),
      });
    }
  }
}
fs.writeFileSync(DRAFT_FILE, JSON.stringify(draft, null, 1) + '\n');
console.log(`draft: ${draft.length} diary step(s) -> ${DRAFT_FILE} — set "pick" to a task name, rerun with --apply`);
