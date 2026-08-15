#!/usr/bin/env node
// WK-1 — kit items that belong to a DIFFERENT step.
//
//   node tools/audit-misplaced-kit.mjs [--all]
//
// THE FAILURE IS SILENT AND ALWAYS LOOKS LIKE BROKEN DETECTION. Arrival
// (and a region checkpoint, and a teleport landing) requires every one
// of the step's annotation items to be IN HAND. One item that cannot be
// held stops the step completing, with nothing in the log to say why,
// and the owner reads it as "this step won't tick". It has cost a
// blocked step three times in two sessions:
//
//   wave 26 — a "Lit candle" on the Camelot step, which the ritual never
//             carries (you light the black candle when you get there);
//   wave 27 — step 280 "Falador teleport" carrying Goblin Diplomacy's
//             blue and orange dyes, which belong to 281;
//   wave 27 — step 284 "Finish Lost tribe" carrying eight items of which
//             NONE were needed at the finale.
//
// THREE SHAPES, WANTING DIFFERENT FIXES:
//
//   NOT YET OBTAINABLE  the guide does not tell you to get this until a
//                       LATER step, so it cannot be in your bag here.
//   CARRIED AHEAD       also listed on a later step, which is what
//                       consumes it. Fix: bringAhead + optional, so it
//                       keeps its icon and its place in the list but
//                       cannot gate.
//   ALREADY SPENT       a quest item consumed on an EARLIER leg, sitting
//                       on the step that FINISHES the quest (wave 9: a
//                       finishing step lists what the FINALE needs, not
//                       the quest-wide wiki list). Fix: drop it.
//
// ITEMS THAT ALREADY CARRY A FLAG ARE NOT FINDINGS. optional, granted
// and consumed are all skipped by the arrival gate, so they cannot
// block anything — that skip is what wave 28 fixed. Only a plain,
// unflagged requirement can wedge a step.
//
// THREE RULES WERE MEASURED AND REJECTED BEFORE THE ONE THAT WORKS, and
// the rejections are the useful part of this file:
//
//   1. "Flag any unflagged item also listed within N steps either side"
//      returns 312 items across 167 steps, and nearly every one is
//      CORRECT — a spade really is carried to all six dig steps, the
//      barcrawl card to all ten bars. Repetition is what a carry-list
//      LOOKS like, so it cannot be the signal.
//   2. "An item listed on 3+ steps is a tool, skip it" hid step 281's
//      blue dye — the exact case this audit exists for — because the dye
//      is also used in an unrelated job 120 steps later. Verified by
//      un-flagging that dye and watching the audit stay silent.
//   3. Neither count nor SPAN separates a tool from a consumable: the
//      barcrawl card spans 180 steps and silk spans 172, and only one is
//      a tool.
//
// What works is asking whether the item can be HELD yet. "Teleport to
// Camelot" listing a pickaxe is wave 18's intended design — you own a
// pickaxe, so arriving with it is real evidence you are ready — while
// "Falador teleport" listing blue dye could never fire. Obtainability
// tells those two apart; nothing about repetition does.
//
// One further exclusion survived measurement: a tool the step's own VERB
// implies ("dig" needs a spade whether or not it says so). That is about
// this step's task, not about how often the item appears.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { stepIdOf } from './lib/target-npc.mjs';

const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const RES = path.join(ROOT, 'src/main/resources/com/ironscape');
const read = (rel) => JSON.parse(fs.readFileSync(path.join(RES, rel), 'utf8'));
const all = process.argv.includes('--all');

const guide = read('guide/guide_data_oziris.json');
const annotations = read('annotations/annotations_oziris.json').annotations;

// How far either side counts as "the same errand". A quest is often four
// or five steps in this guide; beyond that a repeated item name is much
// more likely to be an ordinary restock than a misplaced kit entry.
const WINDOW = 8;

// Canonical enough to pair "blue dye" with "blue dyes" and "Priest gown"
// with "priest gowns". Deliberately NOT a second copy of ItemTracker's
// alias chain — wave 19 reimplemented that chain in JS and it cried wolf
// twice. Anything subtler than plurals is left to the reader.
const canonical = (name) => name.toLowerCase().trim()
  .replace(/[’']s\b/g, '')
  .replace(/\(\d+\)$/, '')
  .replace(/ies$/, 'y')
  .replace(/ves$/, 'f')
  .replace(/s$/, '');

// A step that says "dig" needs a spade whether or not it says "spade".
// This is the one exclusion that survived measurement: it is about the
// step's OWN task implying the item, not about how often the item
// appears, which is what the rejected count and span tests measured.
const TOOL_FOR_VERB = [
  [/\bdig\b|\bdug\b/, ['spade']],
  [/\bmine\b|\bmining\b/, ['pickaxe']],
  [/\bchop\b|\bcut\b.*\btree\b|\bwoodcut/, ['axe', 'hatchet']],
  [/\bfish\b|\bfishing\b|\bcatch\b/, ['fishing rod', 'small fishing net', 'lobster pot',
    'harpoon', 'big fishing net', 'fly fishing rod', 'feather']],
  [/\bsmith\b|\bsmelt\b|\bforge\b|\banvil\b/, ['hammer']],
  [/\bburn\b|\blight\b|\bfiremaking\b/, ['tinderbox']],
  [/\bplank\b|\bbuild\b|\bconstruct/, ['saw', 'hammer']],
  [/\bcraft\b|\btan\b|\bleather\b/, ['needle', 'thread']],
  [/\bspin\b|\bspinning\b/, ['wool', 'balls of wool']],
  [/\bpick\b.*\bflax\b|\bflax\b/, ['flax']],
];

const impliedTool = (text, itemName) => TOOL_FOR_VERB.some(([verb, tools]) =>
  verb.test(text) && tools.some((t) => itemName.includes(t) || t.includes(itemName)));

const plainText = (runs) => (runs || []).map((r) => r.text || '').join('');

// Every step in route order, with its annotation key.
const steps = [];
const idCounts = new Map();
for (const chapter of guide.chapters) {
  for (const section of chapter.sections) {
    for (const step of section.steps) {
      const text = plainText(step.content);
      const base = stepIdOf(text);
      const seen = (idCounts.get(base) || 0) + 1;
      idCounts.set(base, seen);
      const id = seen === 1 ? base : `${base}-${seen}`;
      steps.push({
        index: steps.length + 1, id, text,
        notes: (step.additionalContent || []).map(plainText).join(' '),
        metadata: step.metadata || {},
      });
    }
  }
}
const posOf = new Map(steps.map((s) => [s.id, s.index]));

// Completion paths, for context: a step that ticks off quest state or a
// level does not care what you are carrying, so a misplaced item there
// is untidy rather than blocking.
const pathsFile = path.join(ROOT, 'build/completion-paths.tsv');
const pathOf = new Map();
if (fs.existsSync(pathsFile)) {
  for (const line of fs.readFileSync(pathsFile, 'utf8').split('\n')) {
    const [, subId, kind] = line.split('\t');
    if (subId) {
      pathOf.set(subId.split(':')[0], kind);
    }
  }
} else {
  console.log('(no build/completion-paths.tsv — run gradlew test for the blocking column)\n');
}

// Where every canonical item name is listed, in route order.
const listedAt = new Map();
for (const [key, entry] of Object.entries(annotations)) {
  const stepId = key.includes(':') ? key.split(':')[0] : key;
  const at = posOf.get(stepId);
  if (at === undefined) {
    continue;
  }
  for (const need of entry.items || []) {
    if (!need || !need.name) {
      continue;
    }
    const c = canonical(need.name);
    if (!listedAt.has(c)) {
      listedAt.set(c, []);
    }
    listedAt.get(c).push({ at, stepId, need });
  }
}

// WHERE THE GUIDE TELLS YOU TO GET EACH ITEM, from the detector's own
// goal dump. This is the discriminator that separates a DEFECT from the
// deliberate carry-kit pattern, and it took three rejected rules to get
// here: "Teleport to Camelot" listing a pickaxe is wave 18's intended
// design (you own a pickaxe, so arriving with it is real evidence you
// are ready), while "Falador teleport" listing blue dye could never
// fire, because the dye is not obtained until much later. Repetition,
// count and span all failed to tell those two apart. "Can you even hold
// this yet" tells them apart exactly.
//
// USED ONLY IN THE POSITIVE DIRECTION. Plenty of items have no goal
// anywhere (orange dye, coal, bat bones are acquired in prose the
// detector does not parse), and reading that absence as "never
// obtainable" would flag everything. Silence means unknown.
const acquiredAt = new Map();
const goalFile = path.join(ROOT, 'build/goal-audit.tsv');
if (fs.existsSync(goalFile)) {
  for (const line of fs.readFileSync(goalFile, 'utf8').split('\n')) {
    const cols = line.split('\t');
    if (cols[0] !== 'ITEM') {
      continue;
    }
    const at = posOf.get(cols[1].split(':')[0]);
    const c = canonical(cols[3] || '');
    if (at === undefined || !c) {
      continue;
    }
    if (!acquiredAt.has(c) || acquiredAt.get(c) > at) {
      acquiredAt.set(c, at);
    }
  }
}

const findings = [];
for (const [key, entry] of Object.entries(annotations)) {
  const stepId = key.includes(':') ? key.split(':')[0] : key;
  const at = posOf.get(stepId);
  if (at === undefined) {
    continue;
  }
  const step = steps[at - 1];
  for (const need of entry.items || []) {
    if (!need || !need.name) {
      continue;
    }
    // Already exempt from the gate: cannot block, so not a finding.
    if (need.optional || need.granted || need.consumed || need.bringAhead) {
      continue;
    }
    const c = canonical(need.name);
    // The step's own sentence naming the item is the strongest possible
    // evidence that it belongs here. "Buy 4 vodka" needs the vodka.
    const own = `${step.text} ${step.notes}`.toLowerCase();
    if (own.includes(c) || own.includes(need.name.toLowerCase())) {
      continue;
    }
    if (impliedTool(own, c)) {
      continue;
    }
    // NO "IT IS A TOOL" EXCLUSION. The first cut dropped anything listed
    // on three or more steps, and that was a FALSE NEGATIVE machine: it
    // hid step 281's blue dye, the very case this audit was written for,
    // because the dye is also used in an unrelated job at #404. Measured
    // across the corpus, neither the count nor the span separates a tool
    // from a consumable — the barcrawl card spans 180 steps and silk
    // spans 172, and one is a tool while the other is not. The two SHAPE
    // tests below are the real discriminator, and they turn out to
    // exclude carry-list tools on their own: a spade sits on dig steps
    // that carry no quest tag, so neither shape can fire.
    // Evaluated BEFORE the twin test, and deliberately: an item the
    // guide does not hand you until later blocks this step whether or
    // not it happens to be listed twice. Gating it behind "is it listed
    // elsewhere" made it fire nowhere at all.
    const getsIt = acquiredAt.get(c);
    const notYetObtainable = getsIt !== undefined && getsIt > at;

    const twins = (listedAt.get(c) || []).filter((t) => t.at !== at
      && Math.abs(t.at - at) <= WINDOW);
    if (!twins.length && !notYetObtainable) {
      continue;
    }
    const later = twins.filter((t) => t.at > at);
    const earlier = twins.filter((t) => t.at < at);
    const quest = step.metadata.quest || null;

    // SHAPE A — a finishing step listing what an earlier leg consumed.
    const finishing = step.metadata.questStatus === 'complete'
      || step.metadata.questStatus === 'finish';
    const spentEarlier = finishing && earlier.some((t) =>
      steps[t.at - 1].metadata.quest === quest);

    // SHAPE B — this step has no job of its own and the item belongs to
    // a quest step just ahead.
    const aheadQuest = later.find((t) => {
      const q = steps[t.at - 1].metadata.quest;
      return q && q !== quest;
    });
    const carriedAhead = !quest && !!aheadQuest;


    if (!spentEarlier && !carriedAhead && !notYetObtainable) {
      continue;
    }
    findings.push({
      key, at, step, need, later, earlier, notYetObtainable, getsIt,
      shape: notYetObtainable ? 'NOT YET OBTAINABLE'
        : spentEarlier ? 'ALREADY SPENT' : 'CARRIED AHEAD',
      belongsTo: spentEarlier ? quest
        : aheadQuest ? steps[aheadQuest.at - 1].metadata.quest : null,
      path: pathOf.get(stepId) || '?',
    });
  }
}

// A step blocked on one item is blocked; group so the reader sees the
// step, not a list of items.
const byStep = new Map();
for (const f of findings) {
  if (!byStep.has(f.key)) {
    byStep.set(f.key, []);
  }
  byStep.get(f.key).push(f);
}

const BLOCKING = new Set(['travel', 'checkpoint', 'none', '?']);
const blocking = [...byStep.entries()].filter(([, fs_]) => BLOCKING.has(fs_[0].path));

console.log(`${findings.length} unflagged kit item(s) listed on another step within `
  + `${WINDOW} steps, across ${byStep.size} step(s).`);
console.log(`${blocking.length} of those steps complete by ARRIVAL or a checkpoint, `
  + 'so an item that cannot be held there blocks them.\n');

const show = all ? [...byStep.entries()] : blocking;
for (const [key, items] of show.sort((a, b) => a[1][0].at - b[1][0].at)) {
  const f = items[0];
  console.log(`#${f.at} ${key}  [${f.path}]`);
  console.log(`    ${f.step.text.slice(0, 110)}`);
  if (f.step.metadata.quest) {
    console.log(`    quest: ${f.step.metadata.quest}`
      + (f.step.metadata.questStatus ? ` (${f.step.metadata.questStatus})` : ''));
  }
  for (const item of items) {
    const where = [...item.earlier, ...item.later]
      .map((t) => `#${t.at}`).join(', ');
    console.log(`    ${item.shape}: ${item.need.name}`
      + (item.need.quantity ? ` x${item.need.quantity}` : ' (unnumbered)')
      + `  also on ${where}`);
  }
  console.log('');
}
if (!all && byStep.size > blocking.length) {
  console.log(`(${byStep.size - blocking.length} more on steps that tick off quest state `
    + 'or a level, where a stray item is untidy but harmless — --all to see them)');
}
