#!/usr/bin/env node
// Seeds places.json from steps the nav audit found UNCOVERED — no place
// link in the text and no ⌖ target. Two candidate sources:
//
//  1. A curated synonym map for guide slang the wiki won't find verbatim
//     ("mage tutor" -> Magic tutor, "Lumby" -> Lumbridge). The key is the
//     guide's phrase (that's what must linkify); coords come from the
//     mapped wiki page.
//  2. Capitalized names after a travel/interaction word ("north of Fred",
//     "Give the package to Aubury") — the wiki lookup weeds out non-places.
//
// SAFETY: a phrase only becomes a GLOBAL places key if it appears in just
// one step of the whole guide (or is curated). "general store" appears in
// a dozen towns — one global coordinate would be wrong for most of them;
// those stay for the step-keyed facility/shop passes.
//
// Usage: node tools/seed-uncovered.mjs [--dry-run]

import crypto from 'crypto';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const RES = path.join(__dirname, '../src/main/resources/com/ironscape');
const PLACES_FILE = path.join(RES, 'places/places.json');
const guide = JSON.parse(fs.readFileSync(path.join(RES, 'guide/guide_data_oziris.json'), 'utf8'));
const placesFile = JSON.parse(fs.readFileSync(PLACES_FILE, 'utf8'));
const places = placesFile.places;
const annotations = JSON.parse(
  fs.readFileSync(path.join(RES, 'annotations/annotations_oziris.json'), 'utf8')).annotations;

const USER_AGENT = 'ironscape-runelite-plugin dev tooling (uncovered-step seeding)';
const REQUEST_DELAY_MS = 700;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// Guide phrase (lowercase) -> exact wiki page. Curated = I checked these
// mean ONE in-game spot, so a global key is safe even if the phrase
// repeats across steps.
const SYNONYMS = {
  'mage tutor': 'Magic tutor',
  'magic tutor': 'Magic tutor',
  'lumby': 'Lumbridge',
  'fally': 'Falador',
  'burthrope': 'Burthorpe', // guide's spelling
  'al-kharid': 'Al Kharid',
  'zeah': 'Great Kourend',
  'achievement diary guy': "Twiggy O'Korn",
  'barb agility course': 'Barbarian Outpost Agility Course',
  "glarial's tomb": "Glarial's Tomb",
  'chemist': 'Chemist',
  'tea stall': 'Tea stall',
};

// ---- uncovered steps (same logic as audit-nav.mjs) -------------------
const tolerant = (name) => name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  .replace(/['’]/g, "['’]").replace(/&/g, '(?:&amp;|&)');
const displays = Object.values(places).map((p) => p.display)
  .sort((a, b) => b.length - a.length);
const placePattern = new RegExp('\\b(?:' + displays.map(tolerant).join('|') + ')\\b', 'i');

const targeted = new Set();
for (const [k, ann] of Object.entries(annotations)) {
  if (ann.target) targeted.add(k.split(':')[0]);
}

const runText = (runs) => (runs || []).map((r) => r.text).join('');
const stepText = (step) => [
  runText(step.content),
  ...(step.nestedContent || []).map((n) => runText(n.content)),
  ...(step.additionalContent || []).map(runText),
].join('\n');
const stepId = (t) => crypto.createHash('sha256')
  .update(t.replace(/\s+/g, ' ').trim().toLowerCase(), 'utf8').digest('hex').slice(0, 10);

const uncovered = [];
const allTexts = [];
guide.chapters.forEach((ch) => ch.sections.forEach((sec) => sec.steps.forEach((step) => {
  const text = stepText(step);
  allTexts.push(text.toLowerCase());
  if (targeted.has(stepId(runText(step.content)))) return;
  if (placePattern.test(text)) return;
  uncovered.push(text.replace(/\s+/g, ' ').trim());
})));

/** How many steps of the WHOLE guide mention this phrase. */
function occurrences(phrase) {
  const re = new RegExp('\\b' + tolerant(phrase) + '\\b', 'i');
  return allTexts.filter((t) => re.test(t)).length;
}

// ---- candidates ------------------------------------------------------
const candidates = new Map(); // guide phrase -> wiki page to look up

for (const [phrase, page] of Object.entries(SYNONYMS)) {
  if (uncovered.some((t) => new RegExp('\\b' + tolerant(phrase) + '\\b', 'i').test(t))) {
    candidates.set(phrase, page);
  }
}

// Capitalized names after a travel/interaction word. Mid-word-of-sentence
// capitals only — sentence-leading verbs never precede these patterns.
const CAP_NAME = /\b(?:to|from|near|at|behind|outside|with|use)\s+((?:[A-Z][a-z'’-]{2,})(?:\s+[A-Z][a-z'’-]{2,}){0,2})\b/g;
const STOPWORDS = new Set(['note', 'buy', 'get', 'make', 'bank', 'store', 'video', 'map',
  'update', 'phase', 'complete', 'the', 'you', 'hcim', 'safespot', 'reminder', 'walk',
  'moderate', 'location', 'various', 'al-', 'barb', 'cooking']);

for (const text of uncovered) {
  for (const m of text.matchAll(CAP_NAME)) {
    const name = m[1].trim();
    const lower = name.toLowerCase();
    if (STOPWORDS.has(lower) || candidates.has(lower)) continue;
    if (places[lower]) continue;
    // No uniqueness guard here: a capitalized name is an entity (NPC,
    // building), and an entity is one spot however often it's mentioned.
    // Junk matches die in the wiki lookup (no map template -> no entry).
    candidates.set(lower, name);
  }
}

console.log(`${candidates.size} candidate phrase(s) from ${uncovered.length} uncovered steps:`);
for (const [phrase, page] of candidates) console.log(`  ${phrase} -> ${page}`);
if (process.argv.includes('--dry-run')) process.exit(0);

// ---- wiki lookup (same template parsing as seed-places) --------------
async function fetchCoords(page) {
  const url = 'https://oldschool.runescape.wiki/api.php?action=parse&prop=wikitext&format=json&redirects=1&page='
    + encodeURIComponent(page);
  const res = await fetch(url, { headers: { 'User-Agent': USER_AGENT } });
  if (!res.ok) return null;
  const json = await res.json();
  const wikitext = json?.parse?.wikitext?.['*'];
  if (!wikitext) return null;
  const finalTitle = json?.parse?.title || '';
  if (finalTitle !== page && finalTitle.includes('/')) return null;
  const templates = [...wikitext.matchAll(/\{\{(?:NPC[ _]map|Object[ _]map|Map)\s*\|([^{}]*)\}\}/gi)];
  let fallback = null;
  for (const tpl of templates) {
    const body = tpl[1];
    let x; let y;
    const xm = body.match(/(?:^|\|)\s*x\s*=\s*(\d{3,5})/i);
    const ym = body.match(/(?:^|\|)\s*y\s*=\s*(\d{3,5})/i);
    if (xm && ym) { x = +xm[1]; y = +ym[1]; }
    else {
      const pair = body.match(/(\d{3,5}),\s*(\d{3,5})/);
      if (!pair) continue;
      x = +pair[1]; y = +pair[2];
    }
    const plane = body.match(/plane\s*=\s*(\d)/);
    const coords = { x, y, plane: plane ? +plane[1] : 0 };
    if (y < 8000) return coords;
    fallback = fallback || coords;
  }
  return fallback;
}

let added = 0;
const misses = [];
for (const [phrase, page] of candidates) {
  await sleep(REQUEST_DELAY_MS);
  let coords = await fetchCoords(page);
  if (!coords && /^[a-z]/.test(page)) {
    await sleep(REQUEST_DELAY_MS);
    coords = await fetchCoords(page.replace(/\b[a-z]/g, (c) => c.toUpperCase()));
  }
  if (!coords) {
    misses.push(phrase);
    continue;
  }
  places[phrase] = { display: phrase, ...coords };
  added++;
  console.log(`  + ${phrase} -> ${coords.x},${coords.y}${coords.plane ? ' plane ' + coords.plane : ''} (${page})`);
}

fs.writeFileSync(PLACES_FILE, JSON.stringify(placesFile, null, 2) + '\n');
console.log(`\nDone: ${added} added, ${misses.length} not found.`);
misses.forEach((n) => console.log(`  miss: ${n}`));
console.log('Rebuild the plugin to bundle the new places.');
