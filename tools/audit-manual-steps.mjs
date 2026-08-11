#!/usr/bin/env node
// Every step that can ONLY be ticked by hand, sorted into what could
// plausibly tick it instead.
//
// The point is to stop fixing these one report at a time. 86 steps
// guide-wide have no automatic completion of any kind, and they arrive at
// the player one per session for months. Most are not defects — "Use
// Authenticator", "bank everything" — and calling those broken would be
// its own kind of wrong. What matters is separating the ones a signal
// could genuinely close from the ones that only want a clearer label.
//
// Buckets, and the mechanism each implies:
//   DIARY     an achievement-diary task -> varp/varbit bit checkpoint,
//             exactly what Ardougne and Varrock easy now use
//   QUEST     a quest leg with no goal -> annotation quest tag, or a
//             mid-quest var checkpoint
//   SKILL     "train X to N" / "get N skill" -> level requirement
//   ITEM      "buy/get N of X" -> item goal or annotation items
//   TRAVEL    "go to X" with no movement verb the detector knows
//   MINIGAME  points/rewards from a minigame -> usually a varbit
//   ADVICE    genuinely nothing to detect; wants a LABEL, not a fix
//
// Deliberately conservative: a step lands in ADVICE unless something in
// its text points at a real signal, because over-claiming here produces
// exactly the silent wrong ticks the whole project is careful about.
//
//   node tools/audit-manual-steps.mjs           # counts + the fixable lists
//   node tools/audit-manual-steps.mjs --all     # every step, including ADVICE
import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');

// The population comes from PREFLIGHT, not from a second reading of the
// dumps. Deriving it here independently produced 130 against preflight's
// 86, because this file did not know that a step with no detector still
// ticks by walking there — the same over-count wave 17 had to correct
// once already. One tool owns "can anything tick this?".
const manual = execFileSync('node',
  [path.join(ROOT, 'tools/preflight.mjs'), '--manual-list'], { encoding: 'utf8' })
  .split('\n')
  .filter((l) => l.startsWith('MANUAL\t'))
  .map((l) => {
    const [, index, stepId, text] = l.split('\t');
    return { index: Number(index), stepId, text };
  });

const RULES = [
  ['DIARY', /\bdiar(y|ies)\b|achievement diary|\bcloak\b.*\bdiary|easy tasks|medium tasks|hard tasks|elite tasks/i],
  ['MINIGAME', /\bpest control\b|\bbarbarian assault\b|\bwintertodt\b|\btithe\b|\bsoul wars\b|\bfight caves?\b|\binferno\b|\bgauntlet\b|\bnightmare zone\b|\bnmz\b|\bpoints?\b/i],
  ['QUEST', /\bquest\b|\bcontinue\b|\bfinish\b.*\b(quest|tribe|biohazard)\b/i],
  ['SKILL', /\b(get|train|reach|until)\b.*\b\d+\s*(attack|strength|defence|ranged|prayer|magic|runecraft|construction|hitpoints|agility|herblore|thieving|crafting|fletching|slayer|hunter|mining|smithing|fishing|cooking|firemaking|woodcutting|farming)\b|\b\d+\s*(cb|combat)\b/i],
  ['ITEM', /\bbuy\b|\bpurchase\b|\bget \d+\b|\bmake \d+\b|\bcollect\b|\bstock up\b/i],
  ['TRAVEL', /\bgo to\b|\bhead to\b|\btravel\b|\brun to\b|\bmake your way\b|\bteleport\b|\bboat\b|\bcharter\b/i],
];

const bucketOf = (text) => {
  for (const [name, re] of RULES) {
    if (re.test(text)) return name;
  }
  return 'ADVICE';
};

const buckets = new Map();
for (const row of manual) {
  const b = bucketOf(row.text);
  if (!buckets.has(b)) buckets.set(b, []);
  buckets.get(b).push(row);
}

console.log(`${manual.length} steps can only be ticked by hand\n`);
const order = ['DIARY', 'MINIGAME', 'QUEST', 'SKILL', 'ITEM', 'TRAVEL', 'ADVICE'];
for (const name of order) {
  const rows = buckets.get(name) || [];
  console.log(`${String(rows.length).padStart(3)}  ${name}`);
}
console.log('');

const showAll = process.argv.includes('--all');
for (const name of order) {
  if (name === 'ADVICE' && !showAll) continue;
  const rows = buckets.get(name) || [];
  if (!rows.length) continue;
  console.log(`=== ${name} (${rows.length}) ===`);
  for (const r of rows) {
    console.log(`  ${String(r.index).padStart(3)}  ${r.stepId}  ${r.text.slice(0, 84)}`);
  }
  console.log('');
}
if (!showAll) {
  console.log('(ADVICE hidden — pass --all. Those want a clearer label, not a detector.)');
}
