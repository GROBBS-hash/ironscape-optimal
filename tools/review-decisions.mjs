#!/usr/bin/env node
// The open decisions that need the owner, as a clickable page.
//
// Two kinds live here:
//
//   CAPTURES — ⌖ pins he set in game that are still LOCAL. Captures are
//   local by design, but he makes them so users do not have to, which
//   only works once they are bundled. A reinstall would take them.
//
//   PLACE PINS — findings from audit-place-pins where the fix needs a
//   judgement rather than a rule (an interior vs its entrance, a
//   malformed key).
//
//   node tools/review-decisions.mjs   -> build/decisions-review.html
//
// Approve/reject each, press Copy, paste the JSON back.

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const OUT = path.join(ROOT, 'build/decisions-review.html');

const localFile = path.join(os.homedir(), '.runelite', 'ironscape', 'annotations.json');
const localAll = fs.existsSync(localFile)
  ? JSON.parse(fs.readFileSync(localFile, 'utf8')) : { annotations: {} };
const local = localAll.annotations || localAll;
const bundled = JSON.parse(fs.readFileSync(path.join(ROOT,
  'src/main/resources/com/ironscape/annotations/annotations_oziris.json'), 'utf8')).annotations;
const guide = JSON.parse(fs.readFileSync(path.join(ROOT,
  'src/main/resources/com/ironscape/guide/guide_data_oziris.json'), 'utf8'));

const stepId = (t) => crypto.createHash('sha256')
  .update(t.replace(/\s+/g, ' ').trim().toLowerCase(), 'utf8').digest('hex').slice(0, 10);
const stepText = new Map();
for (const ch of guide.chapters) {
  for (const se of ch.sections) {
    for (const st of se.steps) {
      const t = (st.content || []).map((c) => c.text).join('');
      stepText.set(stepId(t), t);
    }
  }
}

// Underground/interior coordinates. SP cannot draw a route into one, so
// these are the pins that need a human to say "yes, anyway".
const SURFACE_MAX_Y = 4000;

const declined = JSON.parse(fs.readFileSync(
  path.join(ROOT, 'tools/decisions-declined.json'), 'utf8')).declined;

const rows = [];
let stale = 0;
let shipped = 0;
for (const [key, entry] of Object.entries(local)) {
  if (!entry.target) {
    continue;
  }
  const text = stepText.get(key.split(':')[0]);
  if (!text) {
    stale++; // step no longer in the guide (old ids); never ship these
    continue;
  }
  if (declined[key]) {
    continue; // already considered and rejected — see decisions-declined.json
  }
  const b = bundled[key] && bundled[key].target;
  const t = entry.target;
  if (b && b.x === t.x && b.y === t.y && (b.plane || 0) === (t.plane || 0)) {
    shipped++;
    continue;
  }
  const detail = [];
  if (b) {
    const drift = Math.round(Math.hypot(b.x - t.x, b.y - t.y));
    detail.push(`overrides the bundled pin ${b.x},${b.y} (${drift} tiles away)`);
  } else {
    detail.push('no bundled pin for this step yet');
  }
  if (t.plane) {
    detail.push(`plane ${t.plane}`);
  }
  if (t.safespot) {
    detail.push('captured as a SAFESPOT — adds the floating label');
  }
  if (t.y > SURFACE_MAX_Y) {
    detail.push('UNDERGROUND: Shortest Path cannot draw a route into an '
      + 'interior, so this pin marks the spot but will not produce a line. '
      + 'Bundling it still gives the tile marker and arrival.');
  }
  // targetFor reads the SUB key before the step key, so a step-level pin
  // under an already-pinned sub is data that nothing will ever read.
  const subPin = Object.entries(bundled)
    .find(([k, v]) => k.startsWith(key + ':') && v.target);
  if (!key.includes(':') && subPin) {
    detail.push(`INERT IF BUNDLED: ${subPin[0]} already pins `
      + `${subPin[1].target.x},${subPin[1].target.y}, and targetFor reads the `
      + 'sub key first — a step-level pin here would never be read. Approve '
      + 'only if you want it to REPLACE that pin.');
  }
  rows.push({
    id: key,
    section: 'Captures to ship',
    title: `${t.x},${t.y}${t.plane ? ' p' + t.plane : ''}`,
    context: text,
    detail,
    proposal: 'Bundle this capture so every user gets it',
  });
}

// Place pins still wrong RIGHT NOW. Computed, not hardcoded, so a row
// disappears the moment it is fixed rather than being re-reported.
const places = JSON.parse(fs.readFileSync(path.join(ROOT,
  'src/main/resources/com/ironscape/places/places.json'), 'utf8')).places;
if (places['mage bank'] && places['mage bank'].y > SURFACE_MAX_Y) {
  rows.push({
    id: 'place:mage bank',
    section: 'Place pins',
    title: 'mage bank sits inside the arena, not at its entrance',
    context: 'Step: "Go to mage bank and buy 6k nats…"',
    detail: [`ours: ${places['mage bank'].x},${places['mage bank'].y} (interior)`,
      'our own "mage arena" pin is the surface at 3095,3955'],
    proposal: 'Re-anchor "mage bank" to 3095,3955',
  });
}
for (const junk of ['song of', 'wgs']) {
  if (places[junk]) {
    rows.push({
      id: `place:${junk}`,
      section: 'Place pins',
      title: `delete the "${junk}" place`,
      context: `pinned at ${places[junk].x},${places[junk].y}`,
      detail: ['it is a fragment or an abbreviation, not a destination, '
        + 'and it hijacks the route of any step whose text contains it'],
      proposal: `Delete the "${junk}" entry`,
    });
  }
}

// Steps whose TASK is a quest leg but which carry no quest tag, so
// stepQuest() returns null: no Quest Helper stand-down, no green tip
// line, and our route argues with QH's for that whole leg.
//
// Prep steps are excluded on purpose — wave 13 established that tagging
// one ("buy a bronze sword for Horror from the deep") would hand a
// shopping trip to Quest Helper. Only steps whose sentence OPENS with
// continue/finish/complete/do, or says "parts of", qualify. Matching is
// article-tolerant: "Continue Lost tribe" does not contain "The Lost
// Tribe", and that alone hid two of these.
const strip = (s) => s.toLowerCase().replace(/^the\s+/, '').replace(/[^a-z0-9 ]/g, '').trim();
const questNames = new Set();
const allSteps = [];
for (const ch of guide.chapters) {
  for (const se of ch.sections) {
    for (const st of se.steps) {
      const t = (st.content || []).map((c) => c.text).join('');
      allSteps.push({ id: stepId(t), t, meta: st.metadata || {} });
      if (st.metadata && st.metadata.quest) {
        questNames.add(st.metadata.quest);
      }
    }
  }
}
const IS_THE_TASK = /^(?:go to [^,]+ and )?(?:continue|finish|complete|do)\b/i;
const PARTS_OF = /\bparts? of\b/i;
for (const s of allSteps) {
  // Either source counts as tagged. Checking only the guide's metadata
  // meant every step tagged via an ANNOTATION was re-proposed on the next
  // run — the tool asking for work it had already been given.
  if (s.meta.quest || (bundled[s.id] && bundled[s.id].quest)) {
    continue;
  }
  // "(requires Children of the Sun)" names a PREREQUISITE, not the task.
  const withoutParens = s.t.replace(/\((?:requires|needs)[^)]*\)/gi, '');
  const hay = ' ' + strip(withoutParens) + ' ';
  const quest = [...questNames].find((q) => hay.includes(' ' + strip(q) + ' '));
  if (!quest || (!IS_THE_TASK.test(s.t.trim()) && !PARTS_OF.test(s.t))) {
    continue;
  }
  rows.push({
    id: `quest:${s.id}`,
    section: 'Quest legs with no quest tag',
    title: `tag this step as "${quest}"`,
    context: s.t,
    detail: [
      'no quest goal and no quest metadata, so stepQuest() returns null',
      'result: Quest Helper never takes over, the green tip line never '
        + 'shows, and our route competes with QH\'s for this leg',
      `the step's own area tag is ${s.meta.location || '(none)'}`,
    ],
    proposal: `Add an annotation quest tag: ${quest}`,
  });
}

// Quest-start pins that still disagree with Quest Helper after the
// not-a-defect classes are stripped out (audit-quest-start-pins --json).
const questStartFile = path.join(ROOT, 'build/quest-start-review.json');
if (fs.existsSync(questStartFile)) {
  for (const r of JSON.parse(fs.readFileSync(questStartFile, 'utf8'))) {
    rows.push({
      id: `queststart:${r.key}`,
      section: 'Quest start pins vs Quest Helper',
      title: `${r.key} — ${r.drift} tiles apart`,
      context: r.qh.description || `QH's first step is a ${r.qh.type}`,
      detail: [
        `ours: ${r.ours.x},${r.ours.y}` + (r.giver ? ` (recorded giver: ${r.giver})` : ' (no giver recorded)'),
        `Quest Helper: ${r.qh.x},${r.qh.y}`,
        'Approve to move our pin to QH\'s point; reject if ours is the '
          + 'better routing target and QH is opening with an approach.',
      ],
      proposal: `Re-pin "${r.key}" to ${r.qh.x},${r.qh.y}`,
    });
  }
}

const esc = (s) => String(s).replace(/[&<>"]/g, (c) =>
  ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

const sections = [...new Set(rows.map((r) => r.section))];
const html = `<!doctype html>
<meta charset="utf-8">
<title>Open decisions — IRONSCAPE</title>
<style>
  :root { color-scheme: dark light; }
  body { font: 15px/1.55 system-ui, sans-serif; margin: 0; padding: 24px;
         background: #1b1a17; color: #e8e6e1; }
  h1 { font-size: 20px; margin: 0 0 4px; }
  h2 { font-size: 14px; text-transform: uppercase; letter-spacing: .06em;
       color: #a8a49c; margin: 26px 0 10px; }
  p.sub { margin: 0 0 8px; color: #a8a49c; max-width: 72ch; }
  .row { padding: 12px 14px; margin-bottom: 8px; background: #232220;
         border: 1px solid #34322e; border-radius: 8px;
         display: grid; grid-template-columns: 1fr auto; gap: 14px; align-items: start; }
  .row.approved { border-color: #2e8b4a; background: #1e2a20; }
  .row.rejected { opacity: .45; }
  .title { font-weight: 600; }
  .ctx { color: #cfcbc3; font-size: 13px; margin: 3px 0 6px; }
  ul { margin: 0; padding-left: 18px; color: #8d8981; font-size: 12.5px; }
  .prop { margin-top: 7px; color: #ffb35c; font-size: 13px; }
  button { font: inherit; padding: 6px 12px; margin-left: 6px; cursor: pointer;
           background: #2f2d29; color: #e8e6e1; border: 1px solid #45423c; border-radius: 6px; }
  button.on { background: #2e8b4a; border-color: #2e8b4a; color: #fff; }
  button.on.no { background: #8b3a2e; border-color: #8b3a2e; }
  #bar { position: sticky; top: 0; background: #1b1a17; padding: 12px 0 14px;
         border-bottom: 1px solid #34322e; z-index: 1; }
  #out { width: 100%; height: 130px; margin-top: 14px; font-family: ui-monospace, monospace;
         font-size: 12px; background: #131211; color: #e8e6e1;
         border: 1px solid #45423c; border-radius: 6px; padding: 10px; }
  .note { font-size: 12px; color: #8d8981; }
</style>
<div id="bar">
  <h1>Open decisions</h1>
  <p class="sub">Approve what you want done. Everything here is reversible.</p>
  <button id="copy">Copy result</button> <span id="count" class="note"></span>
</div>
<div id="rows"></div>
<textarea id="out" readonly placeholder="Your decisions appear here."></textarea>
<p class="note">${stale} local capture(s) skipped — their steps no longer exist in the guide.
${shipped} already bundled.</p>
<script>
const ROWS = ${JSON.stringify(rows)};
const SECTIONS = ${JSON.stringify(sections)};
const state = new Map();
const host = document.getElementById('rows');
for (const s of SECTIONS) {
  const h = document.createElement('h2');
  h.textContent = s;
  host.appendChild(h);
  for (const r of ROWS.filter((x) => x.section === s)) {
    const el = document.createElement('div');
    el.className = 'row';
    el.innerHTML = '<div><div class="title"></div><div class="ctx"></div>'
      + '<ul></ul><div class="prop"></div></div>'
      + '<div><button data-act="yes">Approve</button><button data-act="no" class="no">No</button></div>';
    el.querySelector('.title').textContent = r.title;
    el.querySelector('.ctx').textContent = r.context;
    el.querySelector('.prop').textContent = '\\u2192 ' + r.proposal;
    const ul = el.querySelector('ul');
    r.detail.forEach((d) => { const li = document.createElement('li'); li.textContent = d; ul.appendChild(li); });
    const set = (v) => {
      state.set(r.id, v === 'yes' ? { id: r.id, approved: true } : { id: r.id, approved: false });
      el.classList.toggle('approved', v === 'yes');
      el.classList.toggle('rejected', v === 'no');
      el.querySelectorAll('button').forEach((b) => b.classList.toggle('on', b.dataset.act === v));
      render();
    };
    el.querySelector('[data-act=yes]').onclick = () => set('yes');
    el.querySelector('[data-act=no]').onclick = () => set('no');
    host.appendChild(el);
  }
}
function render() {
  const decided = [...state.values()];
  document.getElementById('count').textContent =
    decided.filter((d) => d.approved).length + ' approved, '
    + decided.filter((d) => !d.approved).length + ' rejected, of ' + ROWS.length;
  document.getElementById('out').value = decided.length ? JSON.stringify(decided, null, 1) : '';
}
document.getElementById('copy').onclick = async () => {
  const out = document.getElementById('out');
  out.select();
  try { await navigator.clipboard.writeText(out.value); } catch (e) { document.execCommand('copy'); }
  const b = document.getElementById('copy');
  b.textContent = 'Copied';
  setTimeout(() => { b.textContent = 'Copy result'; }, 1200);
};
render();
</script>
`;

fs.writeFileSync(OUT, html);
console.log(`${rows.length} decision(s) -> ${path.relative(ROOT, OUT)}`);
console.log(`(${stale} stale local captures skipped, ${shipped} already bundled)`);
