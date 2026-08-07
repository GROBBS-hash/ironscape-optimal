#!/usr/bin/env node
// Guide-wide sweep for item names MENTIONED in step prose that produce
// no badge: not a detected item goal (build/goal-audit.tsv), not an
// annotation item, not an errand-stage item. These are the "scrying orb
// 2/3, make sure you have it with you" class — the guide tells the
// player to carry something and the panel shows nothing.
//
// Section A (review these): steps whose text ALSO has carry-phrasing
//   ("bring", "make sure you have", "with you", ...) — high signal.
// Section B (--all): every uncovered mention guide-wide — noisy, since
//   prose mentions items it consumes/produces in place.
//
// Run:  gradlew test --tests "*.GoalAuditDumpTest"   (writes the tsv)
//       node tools/audit-mentions.mjs [--all]

import crypto from 'crypto';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.join(__dirname, '..');
const RES = path.join(ROOT, 'src/main/resources/com/ironscape');
const ALL = process.argv.includes('--all');

// ---- guide steps, with GuideLoader's hash ids (incl. -2 dupes) --------
const stepId = (t) => crypto.createHash('sha256')
  .update(t.replace(/\s+/g, ' ').trim().toLowerCase(), 'utf8')
  .digest('hex').slice(0, 10);
const guide = JSON.parse(fs.readFileSync(
  path.join(RES, 'guide/guide_data_oziris.json'), 'utf8'));
const steps = [];
const idCounts = new Map();
for (const ch of guide.chapters) for (const sec of ch.sections) for (const st of sec.steps) {
  const text = (st.content || []).map((c) => c.text).join('');
  let id = stepId(text);
  const seen = (idCounts.get(id) || 0) + 1;
  idCounts.set(id, seen);
  if (seen > 1) id = `${id}-${seen}`;
  steps.push({ id, text });
}

// ---- item-name dictionary ---------------------------------------------
const mappingCache = path.join(__dirname, 'wiki-item-mapping.json');
if (!fs.existsSync(mappingCache)) {
  const res = await fetch('https://prices.runescape.wiki/api/v1/osrs/mapping',
    { headers: { 'User-Agent': 'ironscape-runelite-plugin dev tooling (mention audit)' } });
  fs.writeFileSync(mappingCache, JSON.stringify(await res.json()));
}
const names = new Set();
for (const item of JSON.parse(fs.readFileSync(mappingCache, 'utf8'))) {
  names.add(item.name.toLowerCase());
}
for (const n of Object.keys(JSON.parse(
  fs.readFileSync(path.join(RES, 'items/item_ids.json'), 'utf8')))) {
  names.add(n.toLowerCase());
}
// Item names that are ordinary guide vocabulary — matched constantly,
// almost never a carry requirement. Extend as review finds more.
const STOP = new Set(['coins', 'cannon', 'clue scroll', 'lamp', 'oak plank',
  'steel bar', 'iron bar', 'bronze bar', 'silver bar', 'gold bar',
  'paste', 'cat', 'torch', 'flowers', 'picture', 'seed pod']);
for (const s of STOP) names.delete(s);
// Longest-first so "bucket of water" wins over "bucket".
const dict = [...names].filter((n) => n.length >= 3).sort((a, b) => b.length - a.length);

// ---- coverage: detected goals + annotation items + errand items -------
const covered = new Map(); // stepId -> Set(lowercase names)
const addCover = (id, name) => {
  if (!name) return;
  const key = id.includes(':') ? id.slice(0, id.indexOf(':')) : id;
  if (!covered.has(key)) covered.set(key, new Set());
  covered.get(key).add(name.toLowerCase().trim());
};
const tsv = path.join(ROOT, 'build/goal-audit.tsv');
if (!fs.existsSync(tsv)) {
  console.error('build/goal-audit.tsv missing — run: gradlew test --tests "*.GoalAuditDumpTest"');
  process.exit(1);
}
for (const line of fs.readFileSync(tsv, 'utf8').split('\n')) {
  const cols = line.split('\t');
  if (cols[0] !== 'ITEM') continue;
  addCover(cols[1], cols[3]);
}
const annFile = JSON.parse(fs.readFileSync(
  path.join(RES, 'annotations/annotations_oziris.json'), 'utf8'));
for (const [id, ann] of Object.entries(annFile.annotations)) {
  for (const it of ann.items || []) addCover(id, it.name);
  for (const st of ann.errands || []) addCover(id, st.item);
}

// A mention is covered when either name contains the other after a
// crude singular fold ("logs" covers "log", "cadava potion" covers
// "potion" — containment keeps this audit quiet, review keeps it honest).
const fold = (s) => s.toLowerCase().replace(/s$/, '');
const isCovered = (id, mention) => {
  const set = covered.get(id.replace(/-\d+$/, '')) || covered.get(id);
  if (!set) return false;
  const m = fold(mention);
  return [...set].some((c) => fold(c).includes(m) || m.includes(fold(c)));
};

const CARRY = /\b(bring|make sure you have|with you|you'?ll need|you will need|don'?t forget|take (?:a|an|the|your|some)\b|grab|withdraw|equip|wear(?:ing)?\b|keep your)/i;

// ---- sweep ------------------------------------------------------------
const findMentions = (text) => {
  const lower = text.toLowerCase();
  const taken = []; // [start,end) spans already claimed by longer names
  const hits = [];
  for (const name of dict) {
    let idx = lower.indexOf(name);
    while (idx >= 0) {
      const end = idx + name.length;
      const wordStart = idx === 0 || !/[a-z0-9]/.test(lower[idx - 1]);
      // allow a plural 's' after the match
      const after = lower.slice(end, end + 2);
      const wordEnd = !/[a-z0-9]/.test(after[0] || '') || (after[0] === 's' && !/[a-z0-9]/.test(after[1] || ''));
      const overlaps = taken.some(([s, e]) => idx < e && end > s);
      if (wordStart && wordEnd && !overlaps) {
        taken.push([idx, end]);
        hits.push(name);
      }
      idx = lower.indexOf(name, idx + 1);
    }
  }
  return [...new Set(hits)];
};

let carrySteps = 0;
let broadCount = 0;
const broad = [];
console.log('=== A. carry-phrase steps with UNCOVERED item mentions ===');
for (const st of steps) {
  const mentions = findMentions(st.text).filter((m) => !isCovered(st.id, m));
  if (!mentions.length) continue;
  if (CARRY.test(st.text)) {
    carrySteps++;
    console.log(`  [${st.id}] ${st.text.slice(0, 100)}`);
    console.log(`      missing: ${mentions.join(', ')}`);
  } else {
    broadCount++;
    broad.push(`  [${st.id}] ${mentions.join(', ')} | ${st.text.slice(0, 90)}`);
  }
}
console.log(`${carrySteps} carry-phrase steps with uncovered mentions`);
console.log(`\n=== B. other steps with uncovered mentions: ${broadCount} ${ALL ? '' : '(run with --all to list)'} ===`);
if (ALL) console.log(broad.join('\n'));
