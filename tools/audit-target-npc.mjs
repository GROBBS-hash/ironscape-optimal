#!/usr/bin/env node
// Measures the ⌖ pin / nearest-NPC population.
//
//   node tools/audit-target-npc.mjs           summary
//   node tools/audit-target-npc.mjs --list    every row, grouped by verdict
//
// See tools/lib/target-npc.mjs for what is being measured and why this is
// deliberately not a bulk fix. The clickable page is
// tools/review-target-npc.mjs.

import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { analyseTargets, analyseSources, readDecisions } from './lib/target-npc.mjs';

const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const rows = analyseTargets(ROOT);
const sources = analyseSources(ROOT);
const decided = readDecisions(ROOT).reviewed;
const list = process.argv.includes('--list');

const by = (v) => rows.filter((r) => r.verdict === v);
const open = rows.filter((r) => !decided[r.key] && r.verdict !== 'already-flagged'
  && r.verdict !== 'suppressed');

console.log(`${rows.length} bundled ⌖ targets`);
console.log(`  ${by('already-flagged').length} already carry npc:false`);
console.log(`  ${by('suppressed').length} can never nominate — chain, seeded keeper or seeded giver`);
console.log(`  ${by('probably-suppressed').length} name a known person in their text (very likely safe)`);
console.log(`  ${by('likely-place').length} look like a PLACE (propose npc:false)`);
console.log(`  ${by('likely-person').length} look like a PERSON (leave alone)`);
console.log(`  ${by('unclear').length} unclear`);
console.log(`  ${Object.keys(decided).length} already settled by review`);
console.log(`  -> ${open.length} to decide`);

// Only meaningful on an install that has captured pins of its own.
const shadowed = rows.filter((r) => r.shadowed);
if (shadowed.length) {
  console.log(`\n${shadowed.length} of these are SHADOWED by your own captured pins.`);
  console.log('  A captured pin replaces the bundled one outright, flag included, so a');
  console.log('  bundled-only fix is invisible here. --apply patches both.');
}

console.log(`\n${sources.length} item sources`);
console.log(`  ${sources.filter((s) => s.vendor).length} name a vendor`);
console.log(`  ${sources.filter((s) => s.npcFlag === false).length} say npc:false`);
console.log(`  ${sources.filter((s) => s.type === 'transport').length} are transport networks (never nominate)`);
const loose = sources.filter((s) => !s.settled);
console.log(`  ${loose.length} fall back to nearest-NPC`);
for (const s of loose) {
  console.log(`      ${s.display} (${s.x},${s.y},p${s.plane})`
    + (s.note ? ` — ${s.note.slice(0, 90)}` : ''));
}

if (list) {
  for (const verdict of ['likely-place', 'unclear', 'likely-person', 'probably-suppressed', 'suppressed', 'already-flagged']) {
    const group = by(verdict);
    if (!group.length) {
      continue;
    }
    console.log(`\n=== ${verdict} (${group.length}) ===`);
    for (const r of group) {
      console.log(`  ${r.index ? `#${r.index}` : '(stale)'} ${r.key}  (${r.x},${r.y},p${r.plane})`
        + (decided[r.key] ? '  [settled]' : ''));
      console.log(`      ${r.text.slice(0, 110)}`);
      if (r.blockers.length) {
        console.log(`      blocked by: ${r.blockers.join('; ')}`);
      }
      if (r.placeHits.length || r.personHits.length) {
        console.log(`      words: ${[...r.placeHits.map((w) => `place:${w}`),
          ...r.personHits.map((w) => `person:${w}`)].join(', ')}`);
      }
      if (r.near.length) {
        console.log(`      near: ${r.near.map((n) => `${n.display} (${n.distance})`).join(', ')}`);
      }
    }
  }
}
