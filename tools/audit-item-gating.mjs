// Blast radius for "annotation items gate step completion".
//
// Today a step's annotation items feed the panel badges and the ARRIVAL
// gate (annotationItemsCarried) only. They do not decide whether a step
// completes, so a step with several objectives ticks off whichever ONE
// the detector happens to see:
//
//   "Buy 1 pack of normal compost and all farming tools, store everything
//    in leprechaun"   -> ticks the moment the compost pack is bought;
//    the five tools are seeded items with no vote.
//
// The rule cannot simply become "all annotation items must be held",
// because quest kits get CONSUMED mid-quest and would wedge their steps
// forever. This lists exactly which steps a candidate narrow rule would
// change, so the set can be read against the guide instead of reasoned
// about abstractly.
//
// Candidate rule (what this audit measures):
//   gate on annotation items that have an EXPLICIT quantity, on steps
//   that are NOT quest steps, excluding granted/consumed/optional/
//   ingredient items and coins.
//
// A step only CHANGES behaviour if such an item is not already covered
// by a detected item goal on the same step — if the detector already
// demands it, the gate is redundant.
//
// Steps are split by HOW they complete today, because two of those paths
// (travel-by-teleport, and arrival) already call annotationItemsCarried —
// gating them changes nothing. Only the ungated paths are blast radius.
//
//   node tools/audit-item-gating.mjs [--all] [--rejected]
//
// Needs build/goal-audit.tsv + build/completion-paths.tsv
// (gradlew test --tests "*.GoalAuditDumpTest").
import fs from 'fs';
import path from 'path';
import crypto from 'crypto';
import { fileURLToPath } from 'url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const res = path.join(root, 'src/main/resources/com/ironscape');
const read = p => JSON.parse(fs.readFileSync(path.join(res, p), 'utf8'));

const guide = read('guide/guide_data_oziris.json');
const annotations = read('annotations/annotations_oziris.json').annotations;

const stepId = text => crypto.createHash('sha256')
  .update(text.replace(/\s+/g, ' ').trim().toLowerCase(), 'utf8')
  .digest('hex').slice(0, 10);

// Detected item goals, keyed by the step id they hang off. The dump is
// sub-keyed ("<step>:0"); Oziris is an atomic guide, so sub 0 IS the step.
const detected = new Map();
const auditFile = path.join(root, 'build/goal-audit.tsv');
if (!fs.existsSync(auditFile)) {
  console.error('build/goal-audit.tsv missing - run GoalAuditDumpTest first');
  process.exit(1);
}
for (const line of fs.readFileSync(auditFile, 'utf8').split(/\r?\n/)) {
  const [kind, sub, qty, name] = line.split('\t');
  if (kind !== 'ITEM') continue;
  const id = sub.split(':')[0];
  if (!detected.has(id)) detected.set(id, []);
  detected.get(id).push({ name, qty: Number(qty) });
}

// How each sub completes today, per the detector itself.
const pathFile = path.join(root, 'build/completion-paths.tsv');
if (!fs.existsSync(pathFile)) {
  console.error('build/completion-paths.tsv missing - run GoalAuditDumpTest first');
  process.exit(1);
}
const paths = new Map();
for (const line of fs.readFileSync(pathFile, 'utf8').split(/\r?\n/)) {
  const [kind, sub, kinds] = line.split('\t');
  if (kind !== 'PATH') continue;
  paths.set(sub.split(':')[0], kinds === 'none' ? [] : kinds.split(','));
}

// Paths that ALREADY require the annotation items to be in hand:
//  - travel: the teleport branch calls annotationItemsCarried
//  - no goal at all: the only auto-completion left is arrival, which
//    calls it too (a step with no goal and no place just never ticks)
const alreadyGated = kinds => kinds.length === 0 || kinds.every(k => k === 'travel');

// Items the rule deliberately never gates on. Each exclusion is load
// bearing: see StepAnnotation.ItemNeed for why the flags exist.
const EXCLUDED_FLAGS = ['granted', 'consumed', 'optional', 'ingredient'];
const isCoins = name => ['coins', 'gp'].includes(name.toLowerCase());

const excludeReason = item => {
  for (const flag of EXCLUDED_FLAGS) if (item[flag]) return flag;
  if (isCoins(item.name)) return 'coins';
  if (item.quantity == null) return 'unspecified quantity';
  return null;
};

const all = process.argv.includes('--all');
const showRejected = process.argv.includes('--rejected');

const changed = [];   // steps the rule would newly gate
const questSkipped = []; // quest steps the rule deliberately leaves alone
const redundant = [];  // gate adds nothing - detector already demands it
const gatedToday = []; // travel/arrival: annotationItemsCarried applies already
const shipped = [];    // what purchaseListAcquired actually gates today

for (const chapter of guide.chapters) {
  for (const section of chapter.sections) {
    for (const step of section.steps) {
      const text = (step.content || []).map(r => r.text).join('');
      const id = stepId(text);
      const meta = step.metadata || {};

      // Annotation items live on the step key or on any of its sub keys.
      const items = [];
      for (const [key, value] of Object.entries(annotations)) {
        if (key !== id && !key.startsWith(id + ':')) continue;
        for (const item of value.items || []) items.push({ ...item, key });
      }
      if (!items.length) continue;

      const gating = [];
      const rejected = [];
      for (const item of items) {
        const reason = excludeReason(item);
        if (reason) { rejected.push({ ...item, reason }); continue; }
        gating.push(item);
      }
      if (!gating.length) continue;

      // Already demanded by the detector at >= the same count? Then the
      // step's completion is gated on it today and nothing changes.
      const goals = detected.get(id) || [];
      const novel = gating.filter(item => !goals.some(g =>
        g.name.toLowerCase() === item.name.toLowerCase() && g.qty >= item.quantity));
      if (!novel.length) {
        redundant.push({ id, text, items: gating });
        continue;
      }

      const kinds = paths.get(id) || [];
      const row = { id, text, meta, novel, rejected, goals, kinds };
      if (alreadyGated(kinds)) { gatedToday.push(row); continue; }
      // What SHIPPED: purchase steps only (purchaseListAcquired).
      if (kinds.includes('item-buy') && !meta.quest) shipped.push(row);
      if (meta.quest) questSkipped.push(row);
      else changed.push(row);
    }
  }
}

const show = rows => {
  for (const r of (all ? rows : rows.slice(0, 30))) {
    const tags = [r.meta.location && `📍${r.meta.location}`,
      r.meta.quest && `quest:${r.meta.quest}${r.meta.questStatus ? '/' + r.meta.questStatus : ''}`]
      .filter(Boolean).join('  ');
    console.log(`\n  [${r.id}] ${tags}  path:${r.kinds.length ? r.kinds.join(',') : 'arrival-only'}`);
    console.log(`    ${r.text.slice(0, 100)}`);
    console.log(`    would newly require: ${r.novel.map(i => `${i.name} x${i.quantity}`).join(', ')}`);
    if (r.goals.length) {
      console.log(`    already detects:     ${r.goals.map(g => `${g.name} x${g.qty}`).join(', ')}`);
    } else {
      console.log('    already detects:     (nothing - step has no item goal)');
    }
    if (showRejected && r.rejected.length) {
      console.log(`    excluded:            ${r.rejected.map(i => `${i.name} (${i.reason})`).join(', ')}`);
    }
  }
};

console.log('=== SHIPPED: purchase steps whose annotated list now gates completion ===');
console.log('(IronscapePlugin.purchaseListAcquired — review each: is the list really'
  + ' what the step tells you to BUY?)');
show(shipped);
console.log(`\n${shipped.length} purchase steps gated by the shipped rule`);

console.log('\n\n=== REJECTED WIDER RULE: every explicit-quantity item on a non-quest step ===');
console.log('(measured 2026-08-08 and NOT shipped — annotation items are mostly TOOLS and');
console.log(' INGREDIENTS, so gating on them wedges. Kept so the next person can re-check');
console.log(' the evidence instead of re-proposing it.)');
show(changed);
console.log(`\n${changed.length} non-quest steps the wider rule would change`
  + (all || changed.length <= 30 ? '' : ' (--all to list)'));

console.log('\n=== QUEST steps the rule leaves alone (kit consumption risk) ===');
if (all) show(questSkipped);
console.log(`${questSkipped.length} quest steps carry explicit-quantity items and are SKIPPED`
  + (all ? '' : ' (--all to list)'));

console.log(`\n${gatedToday.length} steps already gated today (travel/arrival call annotationItemsCarried)`);
console.log(`${redundant.length} steps where the detector already demands the same items (no change)`);

// Which completion paths the change actually touches — each one wedges
// differently when an item is missing, so this is the shape of the risk.
const byPath = new Map();
for (const r of changed) {
  const key = r.kinds.length ? r.kinds.join(',') : 'arrival-only';
  byPath.set(key, (byPath.get(key) || 0) + 1);
}
console.log('\nnon-quest changes by completion path:');
for (const [k, n] of [...byPath].sort((a, b) => b[1] - a[1])) {
  console.log(`  ${String(n).padStart(3)}  ${k}`);
}
