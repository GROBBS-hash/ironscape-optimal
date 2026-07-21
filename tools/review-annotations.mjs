#!/usr/bin/env node
// Interactive review of the drafts produced by extract-annotations.mjs.
// Approved requirements are written into the BUNDLED annotations file
// (src/main/resources/com/bruhsailer/annotations/annotations.json), which
// ships inside the plugin — targets you captured in-game are separate and
// untouched.
//
// Usage: node tools/review-annotations.mjs
// Keys:  y / 1..9 = approve   n = reject   s = skip for now   q = save & quit
//
// Rejections are remembered in the draft file, so re-running only shows
// what you haven't decided yet.

import fs from 'fs';
import path from 'path';
import readline from 'readline';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DRAFT_FILE = path.join(__dirname, 'draft-annotations.json');
const BUNDLED_FILE = path.join(__dirname, '../src/main/resources/com/bruhsailer/annotations/annotations.json');

const draft = JSON.parse(fs.readFileSync(DRAFT_FILE, 'utf8'));
const bundled = JSON.parse(fs.readFileSync(BUNDLED_FILE, 'utf8'));
bundled.annotations = bundled.annotations || {};

const pending = draft.drafts.filter(
  (d) => !d.rejected && !(bundled.annotations[d.stepId] && bundled.annotations[d.stepId].requires)
);
const pendingItems = (draft.itemDrafts || []).filter(
  (d) => !d.rejected && !(bundled.annotations[d.stepId] && bundled.annotations[d.stepId].items)
);

if (pending.length === 0 && pendingItems.length === 0) {
  console.log('Nothing to review — every draft is already approved or rejected.');
  process.exit(0);
}

console.log(`${pending.length} skill drafts and ${pendingItems.length} item drafts to review.`);
console.log('y/1-9 approve, n reject, s skip, q save & quit.');
console.log('With verifier verdicts (run verify-annotations.mjs first):');
console.log('  a = accept the verifier\'s recommendation for this draft');
console.log('  b = bulk-approve every REMAINING verifier-confirmed draft (confidence >= 0.8)\n');

const verifierLine = (d) => {
  if (!d.verifier) return null;
  const v = d.verifier;
  const flags = v.flags && v.flags.length ? ` [${v.flags.join(', ')}]` : '';
  const notes = v.notes ? ` — ${v.notes}` : '';
  return `  verifier: ${v.verdict.toUpperCase()} (${v.confidence})${flags}${notes}`;
};

const verifierConfirmed = (d) =>
  d.verifier && d.verifier.verdict === 'confirm' && d.verifier.confidence >= 0.8;

const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
const ask = (q) => new Promise((resolve) => rl.question(q, resolve));

let approved = 0;
let rejected = 0;

const save = () => {
  fs.writeFileSync(BUNDLED_FILE, JSON.stringify(bundled, null, 2) + '\n');
  fs.writeFileSync(DRAFT_FILE, JSON.stringify(draft, null, 2) + '\n');
  console.log(`\nSaved: ${approved} approved, ${rejected} rejected.`);
  console.log('Approved requirements are in the bundled annotations.json — rebuild the plugin to use them.');
};

// The requirement the verifier stands behind: an adjusted correction wins,
// else the top-ranked extracted candidate.
const skillRecommendation = (d) =>
  (d.verifier && d.verifier.correctedSkills && d.verifier.correctedSkills[0]) || d.candidates[0];

const approveSkill = (d, chosen) => {
  bundled.annotations[d.stepId] = bundled.annotations[d.stepId] || {};
  bundled.annotations[d.stepId].requires = { skill: chosen.skill, level: chosen.level };
  approved++;
};

// The item list the verifier stands behind: its correction if any, else the parse.
const itemRecommendation = (d) =>
  (d.verifier && d.verifier.correctedItems) || d.items;

const approveItems = (d, items) => {
  bundled.annotations[d.stepId] = bundled.annotations[d.stepId] || {};
  bundled.annotations[d.stepId].items = items;
  approved++;
};

// --trust: apply every verifier verdict at or above the threshold without
// asking (approve confirms/adjusts, reject rejects), leaving only the
// low-confidence stragglers for the interactive pass. Non-interactive.
if (process.argv.includes('--trust')) {
  const THRESHOLD = 0.8;
  let left = 0;
  const applyTrusted = (list, approve, recommend) => {
    for (const d of list) {
      const v = d.verifier;
      if (!v || v.confidence < THRESHOLD) { left++; continue; }
      if (v.verdict === 'reject') {
        d.rejected = true;
        rejected++;
      } else {
        approve(d, recommend(d));
      }
    }
  };
  applyTrusted(pending, approveSkill, skillRecommendation);
  applyTrusted(pendingItems, approveItems, itemRecommendation);
  save();
  console.log(left > 0
    ? `${left} draft(s) below confidence ${THRESHOLD} still need eyes — run without --trust to review them.`
    : 'Every draft handled — nothing left to review.');
  process.exit(0);
}

let quit = false;
let bulk = false;
for (let i = 0; i < pending.length && !quit; i++) {
  const d = pending[i];
  if (bulk) {
    if (verifierConfirmed(d)) approveSkill(d, skillRecommendation(d));
    continue;
  }
  console.log(`\n[skill ${i + 1}/${pending.length}] ${d.where}`);
  console.log(`  "${d.excerpt}"`);
  d.candidates.forEach((c, n) =>
    console.log(`  ${n + 1}) ${c.skill} ${c.level}  (${c.confidence}: "${c.evidence}")`));
  const vline = verifierLine(d);
  if (vline) console.log(vline);
  if (d.verifier && d.verifier.correctedSkills) {
    console.log('  verifier suggests: '
      + d.verifier.correctedSkills.map((s) => `${s.skill} ${s.level}`).join(', '));
  }

  const answer = (await ask('> ')).trim().toLowerCase();

  if (answer === 'q') { quit = true; break; }
  if (answer === 's' || answer === '') continue;
  if (answer === 'n') {
    d.rejected = true;
    rejected++;
    continue;
  }
  if (answer === 'b') {
    bulk = true;
    if (verifierConfirmed(d)) approveSkill(d, skillRecommendation(d));
    continue;
  }
  if (answer === 'a') {
    if (!d.verifier || d.verifier.verdict === 'reject') {
      console.log('  ...no verifier recommendation to accept here.');
      i--; // re-show this draft
      continue;
    }
    approveSkill(d, skillRecommendation(d));
    continue;
  }

  const pick = answer === 'y' ? 0 : parseInt(answer, 10) - 1;
  const chosen = d.candidates[pick];
  if (!chosen) {
    console.log('  ...didn\'t understand, skipping.');
    continue;
  }
  approveSkill(d, chosen);
}

for (let i = 0; i < pendingItems.length && !quit; i++) {
  const d = pendingItems[i];
  if (bulk) {
    if (verifierConfirmed(d)) approveItems(d, itemRecommendation(d));
    continue;
  }
  console.log(`\n[items ${i + 1}/${pendingItems.length}] ${d.where}`);
  console.log(`  raw: "${d.raw}"`);
  console.log('  parsed: ' + d.items.map((it) => `${it.quantity || 1}x ${it.name}`).join(', '));
  const vline = verifierLine(d);
  if (vline) console.log(vline);
  if (d.verifier && d.verifier.correctedItems) {
    console.log('  verifier suggests: '
      + d.verifier.correctedItems.map((it) => `${it.quantity || 1}x ${it.name}`).join(', '));
  }

  const answer = (await ask('> ')).trim().toLowerCase();
  if (answer === 'q') break;
  if (answer === 's' || answer === '') continue;
  if (answer === 'n') {
    d.rejected = true;
    rejected++;
    continue;
  }
  if (answer === 'b') {
    bulk = true;
    if (verifierConfirmed(d)) approveItems(d, itemRecommendation(d));
    continue;
  }
  if (answer === 'a') {
    if (!d.verifier || d.verifier.verdict === 'reject') {
      console.log('  ...no verifier recommendation to accept here.');
      i--; // re-show this draft
      continue;
    }
    approveItems(d, itemRecommendation(d));
    continue;
  }
  if (answer === 'y') {
    approveItems(d, d.items);
  }
}

rl.close();
save();
