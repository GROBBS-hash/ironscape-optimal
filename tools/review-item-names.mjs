#!/usr/bin/env node
// Builds a clickable review page for item names whose alias chain can
// never match the item they point at.
//
// The TSV these come from is a debugging dump, not a review surface —
// reading it means holding four columns in your head and deciding which
// of eight aliases SHOULD have matched. The decision is actually much
// smaller than that: for each row the real item name is already known,
// so the only question is "does the guide's phrase mean this item?" —
// yes, no, or "no, it means this other one". That is three buttons.
//
//   node tools/review-item-names.mjs      -> build/item-names-review.html
//
// Open it, click through, press Copy, paste the result back. Approved
// rows become COLLOQUIAL entries in ItemTracker.
//
// Sprites come from RuneLite's own icon endpoint, so a row is checkable
// at a glance: if the picture is a nature rune and the phrase is "nats",
// the mapping is right. Offline the images simply do not draw and the
// text still reads fine.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { flaggedNames, liveItemNames } from './lib/item-names.mjs';

const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const ALIAS_TSV = path.join(ROOT, 'build/item-aliases.tsv');
const OUT = path.join(ROOT, 'build/item-names-review.html');

if (!fs.existsSync(ALIAS_TSV)) {
  console.error('Missing build/item-aliases.tsv — run: gradlew test');
  process.exit(1);
}
const liveNames = await liveItemNames(path.join(ROOT, 'tools/.wiki-cache/item-names-cache.json'));
if (!liveNames) {
  console.error('Could not reach the live item mapping.');
  process.exit(1);
}

const { flagged } = flaggedNames(fs.readFileSync(ALIAS_TSV, 'utf8'), liveNames);

// Where each phrase is actually used — a name used by three goals is
// worth more care than one used by none.
const goalAudit = fs.existsSync(path.join(ROOT, 'build/goal-audit.tsv'))
  ? fs.readFileSync(path.join(ROOT, 'build/goal-audit.tsv'), 'utf8') : '';
const annotations = fs.readFileSync(
  path.join(ROOT, 'src/main/resources/com/ironscape/annotations/annotations_oziris.json'), 'utf8');
const usage = (key) => ({
  goals: goalAudit.split('\n').filter((l) => l.split('\t')[3] === key).length,
  annotations: annotations.split('\n').filter((l) => l.includes(`"${key}"`)).length,
});

const rows = flagged.map((row) => ({ ...row, ...usage(row.key) }));
const esc = (s) => String(s).replace(/[&<>"]/g, (c) =>
  ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

const html = `<!doctype html>
<meta charset="utf-8">
<title>Item name review — IRONSCAPE</title>
<style>
  :root { color-scheme: dark light; }
  body { font: 15px/1.5 system-ui, sans-serif; margin: 0; padding: 24px;
         background: #1b1a17; color: #e8e6e1; }
  h1 { font-size: 20px; margin: 0 0 4px; }
  p.sub { margin: 0 0 20px; color: #a8a49c; max-width: 70ch; }
  .row { display: grid; grid-template-columns: 40px 1fr auto; gap: 14px;
         align-items: center; padding: 12px 14px; margin-bottom: 8px;
         background: #232220; border: 1px solid #34322e; border-radius: 8px; }
  .row.approved { border-color: #2e8b4a; background: #1e2a20; }
  .row.rejected { opacity: .45; }
  img { width: 32px; height: 32px; image-rendering: pixelated; }
  .phrase { font-weight: 600; }
  .arrow { color: #a8a49c; }
  .real { color: #ffb35c; }
  .meta { font-size: 12px; color: #8d8981; margin-top: 2px; }
  button { font: inherit; padding: 6px 12px; margin-left: 6px; cursor: pointer;
           background: #2f2d29; color: #e8e6e1; border: 1px solid #45423c;
           border-radius: 6px; }
  button.on { background: #2e8b4a; border-color: #2e8b4a; color: #fff; }
  input { font: inherit; background: #1b1a17; color: #e8e6e1; width: 220px;
          border: 1px solid #45423c; border-radius: 6px; padding: 5px 8px; }
  #bar { position: sticky; top: 0; background: #1b1a17; padding: 12px 0 16px;
         border-bottom: 1px solid #34322e; margin-bottom: 16px; z-index: 1; }
  #out { width: 100%; height: 150px; margin-top: 14px; font-family: ui-monospace, monospace;
         font-size: 12px; background: #131211; color: #e8e6e1;
         border: 1px solid #45423c; border-radius: 6px; padding: 10px; }
</style>
<div id="bar">
  <h1>Item names that can never count</h1>
  <p class="sub">Each phrase below is used by the guide, has the right sprite, and matches
  nothing you carry. Approve the ones where the phrase really does mean that item; use
  <em>Other</em> if it means something else. Then press Copy and paste the result back.</p>
  <button id="approveAll">Approve all</button>
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
  el.innerHTML =
    '<img src="https://static.runelite.net/cache/item/icon/' + r.id + '.png" ' +
      'onerror="this.style.visibility=\\'hidden\\'">' +
    '<div><span class="phrase">' + ${JSON.stringify('')} + r.key + '</span> ' +
      '<span class="arrow">&rarr;</span> <span class="real">' + r.real + '</span>' +
      '<div class="meta">id ' + r.id + ' &middot; ' + r.goals + ' goal(s), ' +
      r.annotations + ' annotation(s) &middot; tries: ' + r.aliases.slice(0, 4).join(', ') + '</div></div>' +
    '<div><button data-act="yes">Approve</button>' +
      '<button data-act="no">Not this</button>' +
      '<input placeholder="other item name" data-act="other"></div>';
  const set = (verdict, target) => {
    state.set(r.key, verdict === 'yes' ? { key: r.key, to: target || r.real } : null);
    el.classList.toggle('approved', verdict === 'yes');
    el.classList.toggle('rejected', verdict === 'no');
    el.querySelectorAll('button').forEach((b) =>
      b.classList.toggle('on', b.dataset.act === verdict));
    render();
  };
  el.querySelector('[data-act=yes]').onclick = () => set('yes');
  el.querySelector('[data-act=no]').onclick = () => set('no');
  const other = el.querySelector('input');
  other.oninput = () => { if (other.value.trim()) set('yes', other.value.trim()); };
  list.appendChild(el);
  el._set = set;
}
function render() {
  const approved = [...state.values()].filter(Boolean);
  document.getElementById('count').textContent =
    approved.length + ' of ' + ROWS.length + ' approved';
  document.getElementById('out').value = approved.length
    ? JSON.stringify(approved, null, 1) : '';
}
document.getElementById('approveAll').onclick = () =>
  list.querySelectorAll('.row').forEach((el) => el._set('yes'));
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

fs.writeFileSync(OUT, html);
console.log(`${rows.length} name(s) to review -> ${path.relative(ROOT, OUT)}`);
console.log('Open it, click through, press Copy, paste the result back.');
