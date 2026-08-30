#!/usr/bin/env node
// Number the carry kits that a bank stop cannot currently act on.
//
// bankFirstTarget deliberately skips an item with no quantity: an unnumbered
// entry is the site's running carry advice ("cakes", "all of your mind and air
// runes"), not a requirement, and no number for it exists anywhere. But a kit
// made ENTIRELY of unnumbered items is invisible to the bank stop, and 19
// steps were in that state -- including "Lumby" with axe/rope/hammer/spade,
// which is a list of TOOLS with an obvious count of one.
//
// The corpus answers most of this about itself: 25 of the 36 distinct names
// are numbered on some OTHER step, and 18 of those say 1 every time. That is
// the guide agreeing with itself rather than me guessing.
//
// Rules, in order:
//   1. A BUY step is skipped whole. A number is what ARMS the purchase gate
//      (gateableItems skips unnumbered items on purpose), so numbering one
//      there adds it to the shopping list the step waits to see acquired --
//      and "Buy 2 ropes, 5 vials ... from ardy general store" also carries a
//      spade, which he already owns and can never be seen to acquire. The
//      step would wedge shut. One step guide-wide.
//   2. A name the corpus only ever numbers as 1 -> 1.
//   3. A single-object name never numbered anywhere -> 1, hand-listed below.
//   4. Money, food and runes -> LEFT ALONE. This is the class the skip exists
//      for; "few cakes" has no number, and inventing one nags at every bank.
//   5. A name the corpus numbers inconsistently -> LEFT ALONE, reported.
//
// Under-numbering is the safe direction: a bank stop asking for 1 rope when
// the step wants 2 still walks you to the bank. Over-numbering nags forever.
//
//   node tools/seed-kit-quantities.mjs           # report
//   node tools/seed-kit-quantities.mjs --apply   # write

import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const RES = path.join(ROOT, 'src/main/resources/com/ironscape');
const ANN = path.join(RES, 'annotations/annotations_oziris.json');
const PATHS = path.join(ROOT, 'build/completion-paths.tsv');

// Flags that already mean "this item has no vote"; see ItemGateConsistencyTest.
const NOT_GATING = ['granted', 'consumed', 'optional', 'ingredient',
  'bringAhead', 'excludeFromCompletion'];

const PURCHASE_VERB = /\b(buy|buys|buying|purchase|purchases|purchasing)\b/i;

// Rule 4: no number exists for these, and the guide never pretends otherwise.
const RUNNING_ADVICE = /^(gp|coins|cakes|few cakes|food|runes|.* runes|all of .*)$/i;

// Rule 3: one object, never numbered elsewhere in the corpus. Hand-listed
// because "is this a single thing" is a judgement, not something the data says.
const SINGLE_OBJECT = new Set([
  'bird snare', 'fire staff (equip)', 'rune scim', 'steel axe',
  'stake (vampire slayer)', 'rune mysteries package',
]);

const doc = JSON.parse(fs.readFileSync(ANN, 'utf8'));
const map = doc.annotations || doc;
const gatingItems = (entry) => (entry.items || [])
  .filter((i) => !NOT_GATING.some((f) => i[f]));

if (!fs.existsSync(PATHS)) {
  console.error('build/completion-paths.tsv is missing - run the tests first;'
    + ' without it the buy-step guard cannot run.');
  process.exit(1);
}
const textById = new Map();
for (const line of fs.readFileSync(PATHS, 'utf8').split('\n')) {
  if (!line.startsWith('PATH')) continue;
  const cols = line.split('\t');
  textById.set(cols[1].split(':')[0], cols.slice(3).join('\t'));
}

// What the corpus already says about each name.
const seen = new Map();
for (const entry of Object.values(map)) {
  for (const item of entry?.items || []) {
    if (item.quantity != null) {
      if (!seen.has(item.name)) seen.set(item.name, new Set());
      seen.get(item.name).add(item.quantity);
    }
  }
}
const corpusSaysOne = (name) => {
  const q = seen.get(name);
  return q && q.size === 1 && q.has(1);
};

const applied = [];
const left = new Map();
const skippedBuySteps = [];
let stepsFixed = 0;
for (const [id, entry] of Object.entries(map)) {
  const gating = gatingItems(entry);
  if (!gating.length || !gating.every((i) => i.quantity == null)) continue;
  const text = textById.get(id.split(':')[0]) || '';
  if (PURCHASE_VERB.test(text)) {
    skippedBuySteps.push(id + '  ' + text.slice(0, 80));
    continue;
  }
  let touched = 0;
  for (const item of gating) {
    if (RUNNING_ADVICE.test(item.name)) {
      left.set(item.name, 'running advice - no number exists');
      continue;
    }
    if (corpusSaysOne(item.name) || SINGLE_OBJECT.has(item.name)) {
      item.quantity = 1;
      applied.push(id + '  ' + item.name);
      touched++;
      continue;
    }
    left.set(item.name, 'numbered inconsistently elsewhere: '
      + [...(seen.get(item.name) || [])].sort((a, b) => a - b).join(', '));
  }
  if (touched) stepsFixed++;
}

console.log(applied.length + ' item(s) numbered across ' + stepsFixed + ' step(s)');
for (const line of applied) console.log('  + ' + line);
if (skippedBuySteps.length) {
  console.log('\n' + skippedBuySteps.length + ' buy step(s) skipped - a number there'
    + ' would join the purchase gate and wait for an item already owned');
  for (const line of skippedBuySteps) console.log('  ! ' + line);
}
console.log('\n' + left.size + ' name(s) deliberately left unnumbered');
for (const [name, why] of [...left].sort()) console.log('  - ' + name + '  (' + why + ')');

if (process.argv.includes('--apply')) {
  const raw = fs.readFileSync(ANN, 'utf8');
  const nl = raw.includes('\r\n') ? '\r\n' : '\n';
  fs.writeFileSync(ANN, JSON.stringify(doc, null, 1).split('\n').join(nl) + nl);
  console.log('\nwritten to ' + path.relative(ROOT, ANN));
} else {
  console.log('\n(dry run - pass --apply to write)');
}
