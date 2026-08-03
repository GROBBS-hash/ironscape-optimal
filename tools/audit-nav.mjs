#!/usr/bin/env node
// Coverage audit: which guide steps have NO navigation handle — no phrase
// that matches a places.json entry (clickable link + nav fallback) and no
// ⌖ target in the bundled annotations?
//
// Usage: node tools/audit-nav.mjs [--phrases]
//   default:   list every uncovered step (id + text)
//   --phrases: also extract candidate lookup phrases from uncovered steps
//              (role NPCs like "mage tutor", "from the X" objects) with
//              counts, ready to feed to seed-places --roles.

import crypto from 'crypto';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const RES = path.join(__dirname, '../src/main/resources/com/ironscape');
const guide = JSON.parse(fs.readFileSync(path.join(RES, 'guide/guide_data_oziris.json'), 'utf8'));
const places = JSON.parse(fs.readFileSync(path.join(RES, 'places/places.json'), 'utf8')).places;
const annotations = JSON.parse(
  fs.readFileSync(path.join(RES, 'annotations/annotations_oziris.json'), 'utf8')).annotations;

// Mirror PlaceManager.tolerantPattern: word-boundary, case-insensitive,
// apostrophe/ampersand tolerant match on each place's display name.
const tolerant = (name) => name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  .replace(/['’]/g, "['’]").replace(/&/g, '(?:&amp;|&)');
const displays = Object.values(places).map((p) => p.display)
  .sort((a, b) => b.length - a.length);
const placePattern = new RegExp('\\b(?:' + displays.map(tolerant).join('|') + ')\\b', 'i');

// Step ids (or "stepId:sub" keys) that have a ⌖ target annotation.
const targeted = new Set();
for (const [key, ann] of Object.entries(annotations)) {
  if (ann.target) targeted.add(key.split(':')[0]);
}

const runText = (runs) => (runs || []).map((r) => r.text).join('');
const stepText = (step) => [
  runText(step.content),
  ...(step.nestedContent || []).map((n) => runText(n.content)),
  ...(step.additionalContent || []).map(runText),
].join('\n');

// Same id scheme as GuideLoader.stepId: first 10 hex chars of SHA-256 of
// the MAIN content runs, whitespace-collapsed and lowercased; duplicate
// texts get -2, -3... suffixes.
const idCounts = new Map();
function stepId(step) {
  const normalized = runText(step.content).replace(/\s+/g, ' ').trim().toLowerCase();
  const base = crypto.createHash('sha256').update(normalized, 'utf8').digest('hex').slice(0, 10);
  const n = (idCounts.get(base) || 0) + 1;
  idCounts.set(base, n);
  return n === 1 ? base : `${base}-${n}`;
}

const uncovered = [];
let total = 0;
let locationOnly = 0;
guide.chapters.forEach((ch) => ch.sections.forEach((sec) => sec.steps.forEach((step) => {
  total++;
  const id = stepId(step);
  const text = stepText(step);
  if (targeted.has(id)) return;
  if (placePattern.test(text)) return;
  // The 📍 location tag gives area-level nav already — weaker than a
  // phrase link, but not "silently going nowhere".
  const hasLocationTag = !!step.metadata?.location;
  if (hasLocationTag) locationOnly++;
  uncovered.push({
    id, section: sec.title, tag: step.metadata?.location || null,
    text: text.replace(/\s+/g, ' ').trim(),
  });
})));

console.log(`${uncovered.length} of ${total} steps have no place link and no target`
  + ` (${locationOnly} of those at least carry a 📍 location tag).\n`);
for (const s of uncovered) {
  console.log(`[${s.id}]${s.tag ? ' 📍' + s.tag : ''} ${s.text.slice(0, 130)}`);
}

if (!process.argv.includes('--phrases')) process.exit(0);

// Candidate phrases from uncovered steps: lowercase role NPCs and
// "from/at/to the <thing>" objects the capitalized scans can't see.
const PHRASE_PATTERNS = [
  // "from mage tutor", "from the master farmer", "at the sawmill operator"
  /\b(?:from|at|to|near)\s+(?:the\s+)?([a-z]+(?:\s+[a-z]+)?\s+(?:tutor|instructor|master|apprentice|operator|salesman|trader|keeper|monk|guard|farmer|banker|tanner))\b/g,
  /\b(?:from|at|to|near)\s+(?:the\s+)?((?:tutor|instructor|sawmill|anvil|furnace|windmill|spinning wheel|loom|well|altar)[a-z ]{0,12}?)\b/g,
  // "talk to the wine seller" style: verb + the + lowercase phrase
  /\b(?:talk|speak)\s+(?:to|with)\s+the\s+([a-z]+(?:\s+[a-z]+){0,2})\b/g,
];

const phrases = new Map();
for (const s of uncovered) {
  const text = s.text.toLowerCase();
  for (const p of PHRASE_PATTERNS) {
    for (const m of text.matchAll(p)) {
      const phrase = m[1].trim();
      if (phrase.length < 5 || phrase.length > 30) continue;
      if (!phrases.has(phrase)) phrases.set(phrase, []);
      phrases.get(phrase).push(s.id);
    }
  }
}

console.log(`\n--- ${phrases.size} candidate phrases in uncovered steps ---`);
for (const [phrase, ids] of [...phrases.entries()].sort((a, b) => b[1].length - a[1].length)) {
  console.log(`  ${phrase}  (${ids.length}x: ${ids.slice(0, 5).join(', ')}${ids.length > 5 ? '…' : ''})`);
}
