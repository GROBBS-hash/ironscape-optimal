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
console.log('y/1-9 approve, n reject, s skip, q save & quit.\n');

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

let quit = false;
for (let i = 0; i < pending.length && !quit; i++) {
  const d = pending[i];
  console.log(`\n[skill ${i + 1}/${pending.length}] ${d.where}`);
  console.log(`  "${d.excerpt}"`);
  d.candidates.forEach((c, n) =>
    console.log(`  ${n + 1}) ${c.skill} ${c.level}  (${c.confidence}: "${c.evidence}")`));

  const answer = (await ask('> ')).trim().toLowerCase();

  if (answer === 'q') { quit = true; break; }
  if (answer === 's' || answer === '') continue;
  if (answer === 'n') {
    d.rejected = true;
    rejected++;
    continue;
  }

  const pick = answer === 'y' ? 0 : parseInt(answer, 10) - 1;
  const chosen = d.candidates[pick];
  if (!chosen) {
    console.log('  ...didn\'t understand, skipping.');
    continue;
  }
  bundled.annotations[d.stepId] = bundled.annotations[d.stepId] || {};
  bundled.annotations[d.stepId].requires = { skill: chosen.skill, level: chosen.level };
  approved++;
}

for (let i = 0; i < pendingItems.length && !quit; i++) {
  const d = pendingItems[i];
  console.log(`\n[items ${i + 1}/${pendingItems.length}] ${d.where}`);
  console.log(`  raw: "${d.raw}"`);
  console.log('  parsed: ' + d.items.map((it) => `${it.quantity || 1}x ${it.name}`).join(', '));

  const answer = (await ask('> ')).trim().toLowerCase();
  if (answer === 'q') break;
  if (answer === 's' || answer === '') continue;
  if (answer === 'n') {
    d.rejected = true;
    rejected++;
    continue;
  }
  if (answer === 'y') {
    bundled.annotations[d.stepId] = bundled.annotations[d.stepId] || {};
    bundled.annotations[d.stepId].items = d.items;
    approved++;
  }
}

rl.close();
save();
