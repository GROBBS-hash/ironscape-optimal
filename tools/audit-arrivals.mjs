#!/usr/bin/env node
// Arrival-resolution audit: reads build/arrival-audit.tsv (written by
// GoalAuditDumpTest.dumpArrivalResolution) and flags movement subs whose
// arrival would anchor on the step's 📍 tag FALLBACK ("PIN") — the
// 'go to ess mines' class, where the tag names the ORIGIN and the step
// false-ticks the moment its item gate opens.
//
// Tier 1 (review these): PIN subs whose step shares its 📍 tag with the
//   PREVIOUS step — the player is already standing at the pin when the
//   sub becomes current; leaving briefly (a bank trip) and returning
//   ticks it. Fix candidates: region checkpoint, ⌖ at the true
//   destination, or a places.json entry for the text's destination.
// Tier 2 (--all): remaining PIN subs (pin may still be the destination
//   — often correct) and NONE subs (nothing resolves; manual tick only).
//
// Run:  gradlew test --tests "*.GoalAuditDumpTest"
//       node tools/audit-arrivals.mjs [--all]

import crypto from 'crypto';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.join(__dirname, '..');
const RES = path.join(ROOT, 'src/main/resources/com/ironscape');
const ALL = process.argv.includes('--all');

// Guide order + location tags, with GuideLoader's hash ids (-2 dupes).
const stepId = (t) => crypto.createHash('sha256')
  .update(t.replace(/\s+/g, ' ').trim().toLowerCase(), 'utf8')
  .digest('hex').slice(0, 10);
const guide = JSON.parse(fs.readFileSync(
  path.join(RES, 'guide/guide_data_oziris.json'), 'utf8'));
const prevLocation = new Map(); // stepId -> previous step's location tag
const locationOf = new Map();
const idCounts = new Map();
let prev = null;
for (const ch of guide.chapters) for (const sec of ch.sections) for (const st of sec.steps) {
  const text = (st.content || []).map((c) => c.text).join('');
  let id = stepId(text);
  const seen = (idCounts.get(id) || 0) + 1;
  idCounts.set(id, seen);
  if (seen > 1) id = `${id}-${seen}`;
  const loc = (st.location || '').trim().toLowerCase();
  locationOf.set(id, loc);
  prevLocation.set(id, prev);
  prev = loc;
}

const tsv = path.join(ROOT, 'build/arrival-audit.tsv');
if (!fs.existsSync(tsv)) {
  console.error('build/arrival-audit.tsv missing — run: gradlew test --tests "*.GoalAuditDumpTest"');
  process.exit(1);
}
let tier1 = 0;
const pinRest = [];
const none = [];
console.log('=== Tier 1: PIN-fallback arrivals where the 📍 tag = the PREVIOUS step\'s tag (origin anchor) ===');
for (const line of fs.readFileSync(tsv, 'utf8').split('\n')) {
  const [kind, subId, source, location, text] = line.split('\t');
  if (kind !== 'ARRIVAL') continue;
  const sid = subId.slice(0, subId.indexOf(':'));
  if (source === 'PIN') {
    const loc = (location || '').trim().toLowerCase();
    if (loc && loc === prevLocation.get(sid)) {
      tier1++;
      console.log(`  [${subId}] 📍${location} | ${text.slice(0, 100)}`);
    } else {
      pinRest.push(`  [${subId}] 📍${location} | ${text.slice(0, 90)}`);
    }
  } else if (source === 'NONE') {
    none.push(`  [${subId}] | ${text.slice(0, 90)}`);
  }
}
console.log(`${tier1} origin-anchored PIN arrivals`);
console.log(`\n=== Tier 2: other PIN-fallback arrivals: ${pinRest.length} ${ALL ? '' : '(--all to list)'} ===`);
if (ALL) console.log(pinRest.join('\n'));
console.log(`\n=== NONE (manual tick only): ${none.length} ${ALL ? '' : '(--all to list)'} ===`);
if (ALL) console.log(none.join('\n'));
