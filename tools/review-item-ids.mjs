#!/usr/bin/env node
// Builds a clickable review page for item names that more than one REAL
// item shares — the candidates for pinning an annotation to an item ID.
//
// Why this needs a human at all: a shared name is usually FINE. "Fire
// rune" is 554 and also 6428 (the Rogue Trader minigame copy), and
// summing them by name is exactly right — any fire rune counts. Pinning
// an id there would be a regression, because ItemNeed.id deliberately
// skips the alias, substitute and family logic that makes "pickaxe"
// match any tier.
//
// It is only WRONG when the same name covers items that are not
// interchangeable and the step needs a particular one:
//
//   Priest gown   426 PRIEST_GOWN / 428 PRIEST_ROBE   -> both needed
//   Ugthanki dung 4601 ..._POOH / 4602 ..._POISON_...  -> The Feud wants one
//   Coins         617 FAKE_COINS / 995 COINS           -> never the same thing
//
// So the page shows every candidate id with its sprite AND its gameval
// constant, because the constant is what usually gives the game away:
// "…WORN" or "ROGUETRADER_…" reads as a variant, two plain nouns read as
// two different items.
//
//   node tools/review-item-ids.mjs      -> build/item-ids-review.html
//
// Open it, click through, press Copy, paste the result back. Decisions
// are remembered in tools/item-id-review-decisions.json so a settled
// question is never asked twice (the lesson from quest-granted-reviewed
// and decisions-declined).
//
// NOTED items are excluded before anything else: nearly every item has a
// noted twin with an identical name, ItemTracker.canonicalize() already
// folds them together, and leaving them in reported 279 candidates where
// there are 88. The gameval constants dump lists only real items, which
// is what makes that filter possible.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { liveItemNames } from './lib/item-names.mjs';

const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const RES = path.join(ROOT, 'src/main/resources/com/ironscape');
const CONSTANTS = path.join(ROOT, 'build/item-id-constants.tsv');
const DECISIONS = path.join(ROOT, 'tools/item-id-review-decisions.json');
const OUT = path.join(ROOT, 'build/item-ids-review.html');

if (!fs.existsSync(CONSTANTS)) {
  console.error('Missing build/item-id-constants.tsv — run: gradlew test');
  process.exit(1);
}
const liveNames = await liveItemNames(path.join(ROOT, 'tools/.wiki-cache/item-names-cache.json'));
if (!liveNames) {
  console.error('Could not reach the live item mapping.');
  process.exit(1);
}

// Real items only, with their gameval constant.
const constantById = new Map();
for (const line of fs.readFileSync(CONSTANTS, 'utf8').split('\n')) {
  const [id, name] = line.split('\t');
  if (id && name && !constantById.has(Number(id))) {
    constantById.set(Number(id), name.trim());
  }
}

const idsByName = new Map();
for (const [id, name] of liveNames) {
  if (!constantById.has(id)) continue; // noted / placeholder / not a real item
  const key = String(name).toLowerCase();
  if (!idsByName.has(key)) idsByName.set(key, []);
  idsByName.get(key).push(id);
}

// Only names WE use, and only those not already pinned to an id.
const annPath = path.join(RES, 'annotations/annotations_oziris.json');
const ann = JSON.parse(fs.readFileSync(annPath, 'utf8')).annotations;
const used = new Map(); // name -> {annotations, goals, steps:Set}
const note = (name, kind, stepKey) => {
  const key = String(name).toLowerCase();
  if (!used.has(key)) used.set(key, { annotations: 0, goals: 0, steps: new Set() });
  const row = used.get(key);
  row[kind]++;
  if (stepKey) row.steps.add(stepKey);
};
for (const [key, entry] of Object.entries(ann)) {
  for (const need of entry.items || []) {
    if (!need.name || need.id != null) continue; // already decided
    note(need.name, 'annotations', key);
  }
}
const goalAudit = path.join(ROOT, 'build/goal-audit.tsv');
if (fs.existsSync(goalAudit)) {
  for (const line of fs.readFileSync(goalAudit, 'utf8').split('\n')) {
    const c = line.split('\t');
    if (c[0] === 'ITEM' && c[3]) note(c[3], 'goals', (c[1] || '').split(':')[0]);
  }
}

const decided = fs.existsSync(DECISIONS)
  ? JSON.parse(fs.readFileSync(DECISIONS, 'utf8')) : { decisions: {} };

const rows = [];
for (const [name, usage] of used) {
  if (decided.decisions[name]) continue; // settled; never ask again
  const ids = idsByName.get(name);
  if (!ids || ids.length < 2) continue;
  rows.push({
    name,
    annotations: usage.annotations,
    goals: usage.goals,
    steps: [...usage.steps].slice(0, 6),
    // Cap the candidate list: "cabbage" has 50+ farming variants and no
    // human needs to see them to answer "is any cabbage a cabbage?".
    candidates: ids.slice(0, 8).map((id) => ({ id, constant: constantById.get(id) || '?' })),
    more: Math.max(0, ids.length - 8),
  });
}
rows.sort((a, b) => (b.annotations + b.goals) - (a.annotations + a.goals));

const html = `<!doctype html>
<meta charset="utf-8">
<title>Item id review — IRONSCAPE</title>
<style>
  :root { color-scheme: dark light; }
  body { font: 15px/1.5 system-ui, sans-serif; margin: 0; padding: 24px;
         background: #1b1a17; color: #e8e6e1; }
  h1 { font-size: 20px; margin: 0 0 4px; }
  p.sub { margin: 0 0 16px; color: #a8a49c; max-width: 78ch; }
  .row { padding: 12px 14px; margin-bottom: 8px; background: #232220;
         border: 1px solid #34322e; border-radius: 8px; }
  .row.pinned { border-color: #2e8b4a; background: #1e2a20; }
  .row.left { opacity: .45; }
  .name { font-weight: 600; font-size: 16px; }
  .meta { font-size: 12px; color: #8d8981; margin-top: 2px; }
  .cands { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 10px; }
  .cand { display: flex; align-items: center; gap: 8px; padding: 6px 10px;
          background: #1b1a17; border: 1px solid #45423c; border-radius: 6px;
          cursor: pointer; font-size: 13px; }
  .cand.on { border-color: #2e8b4a; background: #21301f; }
  .cand img { width: 28px; height: 28px; image-rendering: pixelated; }
  .const { color: #ffb35c; font-family: ui-monospace, monospace; font-size: 12px; }
  button { font: inherit; padding: 6px 12px; margin-right: 6px; cursor: pointer;
           background: #2f2d29; color: #e8e6e1; border: 1px solid #45423c;
           border-radius: 6px; }
  button.on { background: #2e8b4a; border-color: #2e8b4a; color: #fff; }
  #bar { position: sticky; top: 0; background: #1b1a17; padding: 12px 0 16px;
         border-bottom: 1px solid #34322e; margin-bottom: 16px; z-index: 1; }
  #out { width: 100%; height: 160px; margin-top: 14px; font-family: ui-monospace, monospace;
         font-size: 12px; background: #131211; color: #e8e6e1;
         border: 1px solid #45423c; border-radius: 6px; padding: 10px; }
</style>
<div id="bar">
  <h1>Names shared by more than one item</h1>
  <p class="sub">A shared name is usually <b>fine</b> &mdash; any fire rune is a fire rune, and
  pinning an id there would break substitutes and family sums. Only pin when the items are
  <b>not interchangeable</b> and a step needs a particular one (Priest gown top vs bottom,
  real vs fake coins). The <span class="const">GAMEVAL_CONSTANT</span> under each sprite is
  usually the giveaway: <span class="const">&hellip;_WORN</span> or
  <span class="const">ROGUETRADER_&hellip;</span> is a variant; two plain nouns are two items.</p>
  <button id="leaveAll">Leave all as names</button>
  <button id="copy">Copy result</button>
  <span id="count" class="meta"></span>
</div>
<div id="rows"></div>
<textarea id="out" readonly placeholder="Your decisions appear here."></textarea>
<script>
const ROWS = ${JSON.stringify(rows)};
const state = new Map();
const list = document.getElementById('rows');
for (const r of ROWS) {
  const el = document.createElement('div');
  el.className = 'row';
  const cands = r.candidates.map((c) =>
    '<div class="cand" data-id="' + c.id + '">' +
      '<img src="https://static.runelite.net/cache/item/icon/' + c.id + '.png" ' +
        'onerror="this.style.visibility=\\'hidden\\'">' +
      '<div>' + c.id + '<div class="const">' + c.constant + '</div></div></div>').join('');
  el.innerHTML =
    '<div class="name">' + r.name + '</div>' +
    '<div class="meta">' + r.goals + ' goal(s), ' + r.annotations + ' annotation(s)' +
      (r.more ? ' &middot; ' + r.more + ' more id(s) not shown' : '') +
      (r.steps.length ? ' &middot; ' + r.steps.join(', ') : '') + '</div>' +
    '<div class="cands">' + cands + '</div>' +
    '<div style="margin-top:10px"><button data-act="leave">Leave as name</button>' +
      '<span class="meta">&hellip;or click the id this should be pinned to</span></div>';
  const paint = () => {
    const v = state.get(r.name);
    el.classList.toggle('pinned', !!(v && v.id));
    el.classList.toggle('left', !!(v && v.leave));
    el.querySelector('[data-act=leave]').classList.toggle('on', !!(v && v.leave));
    el.querySelectorAll('.cand').forEach((c) =>
      c.classList.toggle('on', !!(v && v.id === Number(c.dataset.id))));
    render();
  };
  el.querySelector('[data-act=leave]').onclick = () => {
    state.set(r.name, { name: r.name, leave: true }); paint();
  };
  el.querySelectorAll('.cand').forEach((c) => {
    c.onclick = () => {
      state.set(r.name, { name: r.name, id: Number(c.dataset.id) });
      paint();
    };
  });
  list.appendChild(el);
  el._leave = () => { state.set(r.name, { name: r.name, leave: true }); paint(); };
}
function render() {
  const all = [...state.values()];
  const pinned = all.filter((v) => v.id);
  document.getElementById('count').textContent =
    all.length + ' of ' + ROWS.length + ' decided, ' + pinned.length + ' pinned';
  document.getElementById('out').value = all.length
    ? JSON.stringify(all, null, 1) : '';
}
document.getElementById('leaveAll').onclick = () =>
  list.querySelectorAll('.row').forEach((el) => el._leave());
document.getElementById('copy').onclick = async () => {
  const out = document.getElementById('out');
  out.select();
  try { await navigator.clipboard.writeText(out.value); } catch (e) { document.execCommand('copy'); }
  document.getElementById('copy').textContent = 'Copied';
  setTimeout(() => { document.getElementById('copy').textContent = 'Copy result'; }, 1200);
};
render();
</script>
`;

fs.mkdirSync(path.dirname(OUT), { recursive: true });
fs.writeFileSync(OUT, html);
console.log(`${rows.length} shared-name item(s) to review -> ${path.relative(ROOT, OUT)}`);
if (Object.keys(decided.decisions).length) {
  console.log(`(${Object.keys(decided.decisions).length} already settled, not shown)`);
}
