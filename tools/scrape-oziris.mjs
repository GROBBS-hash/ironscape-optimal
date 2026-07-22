#!/usr/bin/env node
// Scrapes the Ironman Efficiency Guide (Oziris v4, community "Enhanced
// 2026" edition) from ironman.guide into the plugin's guide format.
//
// ironman.guide is a server-rendered Next.js app. Each section page embeds
// its full step data in the React flight payload (self.__next_f.push
// chunks) as structured JSON the client uses to render both guide modes:
//
//   { "id": "1.1.11", "location": "Lumbridge Castle",
//     "text": "Light the 4 logs until 15 Firemaking",
//     "skillGoal": { "skill": "firemaking", "level": 15 },
//     "quest": "Rune Mysteries", "questStatus": "start",
//     "items": ["GP", "120 noted planks"], "note": "...",
//     "links": [{ "type": "image", "url": "...", "text": "..." }],
//     "enhanced": true, "hcim": true }
//
// We keep EVERY step (original + "enhanced": the 2026 edition is a
// superset) and emit two files:
//
//   src/main/resources/com/bruhsailer/guide/guide_data_oziris.json
//       — the RawGuide shape GuideLoader parses (same as BRUHsailer's)
//   src/main/resources/com/bruhsailer/annotations/annotations_oziris.json
//       — skill requirements + item lists straight from the author data,
//         keyed by the same content-hash step ids GuideLoader computes
//
// Usage: node tools/scrape-oziris.mjs [--dry-run]
//
// NOTE: guide content belongs to Oziris & the ironman.guide maintainers.
// This tool exists for local development; get their blessing before
// distributing the bundled data.

import fs from 'fs';
import path from 'path';
import crypto from 'crypto';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const GUIDE_OUT = path.join(__dirname, '../src/main/resources/com/bruhsailer/guide/guide_data_oziris.json');
const ANNOTATIONS_OUT = path.join(__dirname, '../src/main/resources/com/bruhsailer/annotations/annotations_oziris.json');

const USER_AGENT = 'IRONSCAPE-Optimal RuneLite plugin dev tooling (contact: see repo)';
const REQUEST_DELAY_MS = 800;

// Section pages in guide order -> the chapter each belongs to.
const SECTIONS = [
  { slug: 'early-game', title: '1.1: Early quests, wintertodt and ardy cloak 1', chapter: 'Chapter 1' },
  { slug: 'thieving-fishing-mining', title: '1.2: Thieving, fishing and mining', chapter: 'Chapter 1' },
  { slug: 'fairy-rings-prayer-kingdom', title: '1.3: Fairy rings, 43 prayer, kingdom, 99 thieving', chapter: 'Chapter 1' },
  { slug: 'skilling-graceful', title: '1.4: Various skilling, agility for graceful', chapter: 'Chapter 1' },
  { slug: 'diaries-rfd', title: '1.5: Diaries and finishing RFD', chapter: 'Chapter 1' },
  { slug: 'after-barrows-gloves', title: '2.0: Goals after Barrows gloves', chapter: 'Chapter 2' },
  { slug: 'sailing', title: 'Sailing Track (optional)', chapter: 'Sailing' },
];

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// Same normalization + hash as GuideLoader.stepId / extract-annotations.
function stepId(plainText) {
  const normalized = plainText.replace(/\s+/g, ' ').trim().toLowerCase();
  return crypto.createHash('sha256').update(normalized, 'utf8').digest('hex').slice(0, 10);
}

/** Decode the React flight payload: concatenated self.__next_f string chunks. */
function decodeFlightPayload(html) {
  let payload = '';
  for (const m of html.matchAll(/self\.__next_f\.push\(\[1,("(?:[^"\\]|\\.)*")\]\)/g)) {
    payload += JSON.parse(m[1]);
  }
  return payload;
}

/** Bracket-match a JSON array starting at `start` (which points at '['). */
function sliceBalancedArray(text, start) {
  let depth = 0;
  let inString = false;
  for (let i = start; i < text.length; i++) {
    const c = text[i];
    if (inString) {
      if (c === '\\') i++;
      else if (c === '"') inString = false;
    } else if (c === '"') {
      inString = true;
    } else if (c === '[') {
      depth++;
    } else if (c === ']' && --depth === 0) {
      return text.slice(start, i + 1);
    }
  }
  return null;
}

/** Every "steps":[...] array in the payload; the section's is the longest. */
function extractSteps(payload) {
  let best = null;
  let at = -1;
  while ((at = payload.indexOf('"steps":[', at + 1)) >= 0) {
    const raw = sliceBalancedArray(payload, at + '"steps":'.length);
    if (!raw) continue;
    try {
      const arr = JSON.parse(raw);
      if (Array.isArray(arr) && arr.length && arr[0].id && arr[0].text
        && (!best || arr.length > best.length)) {
        best = arr;
      }
    } catch {
      // some other "steps" key; skip
    }
  }
  return best;
}

// ---------------------------------------------------------------------
// Transform one scraped step into the RawStep shape GuideLoader parses.
// The step TEXT stays byte-identical to the author's (it is the id hash
// input); notes/warnings/links become additionalContent paragraphs so
// they render without becoming tickboxes.
// ---------------------------------------------------------------------

// Every field the site currently ships. An unknown field means the site
// grew new data we'd silently drop — warn so the scraper gets taught.
const KNOWN_STEP_KEYS = new Set(['id', 'text', 'location', 'note', 'quest', 'questStatus',
  'skillGoal', 'enhanced', 'items', 'links', 'hcim', 'updateNote', 'phase', 'newQuest',
  'sailingRelated']);

// The site's green "Modern Alternative" callout color, as RawColor floats.
const CALLOUT_GREEN = { r: 0.298, g: 0.686, b: 0.31 };

function toRawStep(step) {
  for (const key of Object.keys(step)) {
    if (!KNOWN_STEP_KEYS.has(key)) {
      console.warn(`  ! step ${step.id} has unknown field "${key}" — content dropped, teach the scraper`);
    }
  }
  const additional = [];
  if (step.note) {
    additional.push([{ text: 'Note: ' + step.note, formatting: { italic: true } }]);
  }
  if (step.updateNote) {
    // The site's collapsible "Modern Alternative" block — often corrects
    // outdated advice, vital for new players, so always visible here.
    additional.push([{
      text: 'Modern alternative: ' + step.updateNote,
      formatting: { italic: true, color: CALLOUT_GREEN },
    }]);
  }
  if (step.hcim) {
    additional.push([{ text: '⚠ HCIM warning — see note above.', formatting: { italic: true } }]);
  }
  for (const link of step.links || []) {
    additional.push([{
      text: link.text || link.url,
      formatting: { underline: true, url: link.url, isLink: true },
    }]);
  }
  // Author-structured tags ride along as metadata; the panel renders
  // location/quest as the same chips the website shows, the rest is
  // kept for future use.
  const metadata = {};
  if (step.location) metadata.location = step.location;
  if (step.quest) metadata.quest = step.quest;
  if (step.questStatus) metadata.questStatus = step.questStatus;
  if (step.phase) metadata.phase = String(step.phase);
  if (step.newQuest) metadata.newQuest = String(step.newQuest);
  if (step.sailingRelated) metadata.sailingRelated = String(step.sailingRelated);
  return {
    content: [{ text: step.text, formatting: {} }],
    ...(additional.length ? { additionalContent: additional } : {}),
    ...(Object.keys(metadata).length ? { metadata } : {}),
  };
}

const SKILLS = new Set(['attack', 'strength', 'defence', 'hitpoints', 'ranged', 'prayer',
  'magic', 'cooking', 'woodcutting', 'fletching', 'fishing', 'firemaking', 'crafting',
  'smithing', 'mining', 'herblore', 'agility', 'thieving', 'slayer', 'farming',
  'runecraft', 'hunter', 'construction']);

/** "120 noted planks" -> {name:"noted planks", quantity:120}; "1k arrow shafts" -> 1000. */
function parseItem(raw) {
  const m = raw.trim().match(/^(\d+(?:\.\d+)?)(k)?\s+(.+)$/i);
  if (!m) {
    return { name: raw.trim().toLowerCase(), quantity: null };
  }
  const quantity = Math.round(parseFloat(m[1]) * (m[2] ? 1000 : 1));
  return { name: m[3].toLowerCase(), quantity };
}

function toAnnotation(step) {
  const annotation = {};
  if (step.skillGoal && step.skillGoal.skill && step.skillGoal.level) {
    const skill = String(step.skillGoal.skill).toLowerCase();
    if (SKILLS.has(skill)) {
      annotation.requires = { skill: skill.toUpperCase(), level: step.skillGoal.level };
    }
  }
  if (Array.isArray(step.items) && step.items.length) {
    annotation.items = step.items.map(parseItem);
  }
  return Object.keys(annotation).length ? annotation : null;
}

// ---------------------------------------------------------------------

const dryRun = process.argv.includes('--dry-run');
const chapters = new Map(); // chapter title -> {title, sections: []}
const annotations = {};
const idCounts = new Map();
let total = 0;
let enhanced = 0;

for (const section of SECTIONS) {
  const url = `https://ironman.guide/guide/${section.slug}`;
  const res = await fetch(url, { headers: { 'User-Agent': USER_AGENT } });
  if (!res.ok) {
    console.error(`FAILED ${url}: HTTP ${res.status}`);
    process.exit(1);
  }
  const steps = extractSteps(decodeFlightPayload(await res.text()));
  if (!steps) {
    console.error(`FAILED ${url}: no steps array in flight payload (site layout changed?)`);
    process.exit(1);
  }
  console.log(`${section.slug}: ${steps.length} steps`
    + ` (${steps.filter((s) => s.enhanced).length} enhanced-only)`);
  total += steps.length;
  enhanced += steps.filter((s) => s.enhanced).length;

  const rawSteps = [];
  for (const step of steps) {
    rawSteps.push(toRawStep(step));
    // Annotation keyed by the SAME hash id GuideLoader will compute.
    let id = stepId(step.text);
    const seen = (idCounts.get(id) || 0) + 1;
    idCounts.set(id, seen);
    if (seen > 1) id = `${id}-${seen}`;
    const annotation = toAnnotation(step);
    if (annotation) {
      annotations[id] = annotation;
    }
  }

  if (!chapters.has(section.chapter)) {
    chapters.set(section.chapter, { title: section.chapter, sections: [] });
  }
  chapters.get(section.chapter).sections.push({ title: section.title, steps: rawSteps });
  await sleep(REQUEST_DELAY_MS);
}

const guide = {
  updatedOn: new Date().toISOString().slice(0, 10),
  title: 'Ironman Efficiency Guide (Oziris v4, Enhanced 2026)',
  chapters: [...chapters.values()],
};

console.log(`\nTotal: ${total} steps (${enhanced} enhanced-only), `
  + `${Object.keys(annotations).length} annotated (skill goals / item lists).`);

if (dryRun) {
  console.log('Dry run: nothing written.');
  process.exit(0);
}

fs.writeFileSync(GUIDE_OUT, JSON.stringify(guide, null, 2) + '\n');
fs.writeFileSync(ANNOTATIONS_OUT, JSON.stringify({ version: 1, annotations }, null, 2) + '\n');
console.log(`Wrote ${GUIDE_OUT}`);
console.log(`Wrote ${ANNOTATIONS_OUT}`);
console.log('Rebuild the plugin to bundle the new guide.');
