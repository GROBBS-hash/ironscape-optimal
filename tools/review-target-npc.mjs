#!/usr/bin/env node
// Clickable review page for the ⌖ pin / nearest-NPC question.
//
//   node tools/review-target-npc.mjs           -> build/target-npc-review.html
//   node tools/review-target-npc.mjs --apply <file.json>
//
// Open the page, click through, press Copy, paste the result into a file
// and run --apply. Every verdict is recorded in tools/target-npc-reviewed.json
// with its reason, so a settled pin is never asked about again.
//
// IT RUNS THE ANALYSIS RATHER THAN READING A build/ FILE. Wave 20's
// review page asked four questions that had been settled fourteen
// minutes before it was generated, because it trusted an artifact on
// disk. A stale input re-asks a settled question as effectively as
// having no record at all.

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { analyseTargets, readDecisions } from './lib/target-npc.mjs';

const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const OUT = path.join(ROOT, 'build/target-npc-review.html');
const DECISIONS = path.join(ROOT, 'tools/target-npc-reviewed.json');
const ANNOTATIONS = path.join(
  ROOT, 'src/main/resources/com/ironscape/annotations/annotations_oziris.json');

const applyAt = process.argv.indexOf('--apply');
if (applyAt !== -1) {
  applyDecisions(process.argv[applyAt + 1]);
} else {
  writePage();
}

/**
 * Writes the verdicts into the bundled annotations and records every one
 * — including the leaves — so the question stays settled.
 */
function applyDecisions(file) {
  if (!file || !fs.existsSync(file)) {
    console.error('Usage: node tools/review-target-npc.mjs --apply <pasted.json>');
    process.exit(1);
  }
  const verdicts = JSON.parse(fs.readFileSync(file, 'utf8'));
  const data = JSON.parse(fs.readFileSync(ANNOTATIONS, 'utf8'));
  const record = readDecisions(ROOT);
  record.reviewed = record.reviewed || {};

  let flagged = 0;
  let left = 0;
  for (const v of verdicts) {
    const entry = data.annotations[v.key];
    if (!entry || !entry.target) {
      console.warn(`  no ⌖ on ${v.key} — skipped`);
      continue;
    }
    if (v.verdict === 'place') {
      entry.target.npc = false;
      flagged += 1;
    } else if (entry.target.npc === false) {
      // He can also UNDO a flag: a pin marked as a place that turns out
      // to be a person gets its nomination back.
      delete entry.target.npc;
      left += 1;
    } else {
      left += 1;
    }
    record.reviewed[v.key] = {
      verdict: v.verdict,
      reason: v.reason || (v.verdict === 'place'
        ? 'Owner: this pin marks a place, not a person.'
        : 'Owner: this pin marks a person — keep the outline.'),
      on: new Date().toISOString().slice(0, 10),
    };
  }
  // 1-space indent to match every other seeder — writing 2 would reformat
  // the whole file and bury the real change in a 20,000-line diff.
  fs.writeFileSync(ANNOTATIONS, `${JSON.stringify(data, null, 1)}\n`);
  fs.writeFileSync(DECISIONS, `${JSON.stringify(record, null, 2)}\n`);
  console.log(`${flagged} pin(s) now npc:false, ${left} left nominating.`);
  console.log(`Recorded ${verdicts.length} verdict(s) in ${path.relative(ROOT, DECISIONS)}.`);

  // AND THE SAME VERDICT ON THIS INSTALL'S OWN CAPTURES. A captured pin
  // REPLACES the bundled one outright, flag included, so on a machine
  // that has captured the step the bundled fix is invisible — which is
  // the machine the fix gets play-tested on. Backed up first.
  patchLocal(verdicts);
  console.log('Data-only change — type ::ironreload in game, no rebuild needed.');
}

/** Mirrors the verdicts onto this install's captured pins, if any. */
function patchLocal(verdicts) {
  const file = path.join(os.homedir(), '.runelite/ironscape/annotations.json');
  if (!fs.existsSync(file)) {
    return;
  }
  const raw = fs.readFileSync(file, 'utf8');
  const data = JSON.parse(raw);
  const entries = data.annotations || data;
  let touched = 0;
  for (const v of verdicts) {
    const entry = entries[v.key];
    if (!entry || !entry.target || entry.target.cleared === true) {
      continue;
    }
    if (v.verdict === 'place' && entry.target.npc !== false) {
      entry.target.npc = false;
      touched += 1;
    } else if (v.verdict === 'person' && entry.target.npc === false) {
      delete entry.target.npc;
      touched += 1;
    }
  }
  if (!touched) {
    return;
  }
  fs.writeFileSync(`${file}.bak`, raw);
  fs.writeFileSync(file, `${JSON.stringify(data, null, 2)}\n`);
  console.log(`${touched} of your own captured pin(s) updated too `
    + '(they override the bundled ones) — previous file kept as annotations.json.bak.');
}

function writePage() {
  const decided = readDecisions(ROOT).reviewed || {};
  const rows = analyseTargets(ROOT).filter((r) => !decided[r.key]);

  // Group order is the order he should read them in: the two confident
  // piles first (scan and accept), the genuine questions last.
  const groups = [
    {
      id: 'likely-place',
      title: 'Looks like a place — recommend switching the outline off',
      blurb: 'The step sends you to a thing, not a person. Accepting these stops the '
        + 'plugin outlining whoever happens to be standing there.',
      pre: 'place',
    },
    {
      id: 'likely-person',
      title: 'Looks like a person — recommend leaving alone',
      blurb: 'The pin sits on a shop counter or a named character. The outline is doing '
        + 'its job here. Check none of these is really a signpost or a door.',
      pre: 'person',
    },
    {
      id: 'unclear',
      title: 'Needs you',
      blurb: 'Could go either way from the text. The question is only: standing at that '
        + 'pin, is there a person you are meant to deal with?',
      pre: null,
    },
    {
      id: 'probably-suppressed',
      title: 'Almost certainly harmless — the step names a person already',
      blurb: 'When a step names someone, that name wins and the nearest-pin guess never '
        + 'runs. Left here only so nothing is hidden from you.',
      pre: null,
    },
    {
      id: 'suppressed',
      title: 'No action — a chain, a seeded shopkeeper or a quest giver already owns these',
      blurb: 'The outline is decided by real data on these steps, so the pin never gets '
        + 'a vote whatever it marks.',
      pre: null,
    },
  ];

  const payload = groups.map((g) => ({
    ...g,
    rows: rows.filter((r) => r.verdict === g.id),
  })).filter((g) => g.rows.length);

  const html = `<!doctype html>
<meta charset="utf-8">
<title>⌖ pin review — IRONSCAPE</title>
<style>
  :root { color-scheme: dark light; }
  body { font: 15px/1.55 system-ui, sans-serif; margin: 0; padding: 24px 24px 80px;
         background: #1b1a17; color: #e8e6e1; }
  h1 { font-size: 21px; margin: 0 0 6px; }
  h2 { font-size: 16px; margin: 30px 0 2px; }
  p.sub, p.blurb { margin: 0 0 14px; color: #a8a49c; max-width: 78ch; }
  p.blurb { margin: 0 0 12px; font-size: 13px; }
  .row { padding: 12px 14px; margin-bottom: 8px; background: #232220;
         border: 1px solid #34322e; border-radius: 8px; }
  .row.place { border-color: #b5761f; background: #2a2318; }
  .row.person { border-color: #2e8b4a; background: #1e2a20; }
  .head { display: flex; gap: 10px; align-items: baseline; flex-wrap: wrap; }
  .num { color: #8d8981; font-size: 12px; font-family: ui-monospace, monospace; }
  .text { font-weight: 600; }
  .meta { font-size: 12.5px; color: #8d8981; margin-top: 5px; }
  .meta b { color: #a8a49c; font-weight: 600; }
  .acts { margin-top: 9px; display: flex; gap: 6px; align-items: center; flex-wrap: wrap; }
  button { font: inherit; font-size: 13px; padding: 5px 11px; cursor: pointer;
           background: #2f2d29; color: #e8e6e1; border: 1px solid #45423c;
           border-radius: 6px; }
  button.on.place { background: #b5761f; border-color: #b5761f; color: #17150f; }
  button.on.person { background: #2e8b4a; border-color: #2e8b4a; color: #fff; }
  a.map { font-size: 12.5px; color: #7fb2e5; text-decoration: none; margin-left: 4px; }
  a.map:hover { text-decoration: underline; }
  input.why { font: inherit; font-size: 13px; background: #1b1a17; color: #e8e6e1;
              border: 1px solid #45423c; border-radius: 6px; padding: 4px 8px; flex: 1;
              min-width: 180px; }
  #bar { position: sticky; top: 0; background: #1b1a17; padding: 14px 0 14px;
         border-bottom: 1px solid #34322e; margin-bottom: 8px; z-index: 2; }
  #out { width: 100%; height: 140px; margin-top: 16px; font-family: ui-monospace, monospace;
         font-size: 12px; background: #131211; color: #e8e6e1;
         border: 1px solid #45423c; border-radius: 6px; padding: 10px; }
  .groupbtn { margin-left: 8px; font-size: 12px; }
</style>
<div id="bar">
  <h1>Which of these pins mark a person?</h1>
  <p class="sub">Each pin below is a saved location for a step. Today the plugin also
  outlines <em>whoever is standing nearest to it</em> and floats the step's item over
  their head. That is right at a shop counter and wrong at a furnace &mdash; which is how
  a level&#8209;2 Man ended up wearing a sickle. Say which each one is; the map link shows
  you the exact tile.</p>
  <button id="copy">Copy result</button>
  <span id="count" class="meta"></span>
</div>
<div id="groups"></div>
<textarea id="out" readonly placeholder="Your decisions appear here."></textarea>
<script>
const GROUPS = ${JSON.stringify(payload)};
const state = new Map();
const host = document.getElementById('groups');

for (const g of GROUPS) {
  const h = document.createElement('h2');
  h.textContent = g.title + ' (' + g.rows.length + ')';
  // Only a group that CARRIES a recommendation gets a bulk button. The
  // whole point of this page is that setting the flag on a person's pin
  // removes an outline nobody notices is gone, so "mark all as place"
  // must never be one click away from a group nobody has judged.
  const accept = g.pre ? document.createElement('button') : null;
  if (accept) {
    accept.className = 'groupbtn';
    accept.textContent = 'Accept all in this group';
    h.appendChild(accept);
  }
  host.appendChild(h);
  const blurb = document.createElement('p');
  blurb.className = 'blurb';
  blurb.textContent = g.blurb;
  host.appendChild(blurb);

  const setters = [];
  for (const r of g.rows) {
    const el = document.createElement('div');
    el.className = 'row';
    const bits = [];
    if (r.location) bits.push('<b>' + r.location + '</b>');
    bits.push(r.x + ', ' + r.y + (r.plane ? ' &middot; floor ' + r.plane : ''));
    if (r.placeHits.length) bits.push('reads like ' + r.placeHits.join(', '));
    if (r.personHits.length) bits.push(r.personHits.join(', '));
    if (r.near.length) bits.push('nearby: ' + r.near
      .map(n => n.display + ' (' + n.distance + ' tiles)').join(', '));
    if (r.items.length) bits.push('would float: ' + r.items.join(', '));
    if (r.shadowed) bits.push('<b>you captured this pin yourself</b>');
    if (r.blockers.length) bits.push('already named: ' + r.blockers.join('; '));
    if (r.namedPeople.length) bits.push('text names: ' + r.namedPeople.join(', '));

    const map = 'https://explv.github.io/?centreX=' + r.x + '&centreY=' + r.y
      + '&centreZone=8&zoom=9&plane=' + r.plane;

    el.innerHTML =
      '<div class="head"><span class="num">' + (r.index ? '#' + r.index : r.key) + '</span>'
      + '<span class="text"></span>'
      + '<a class="map" target="_blank" href="' + map + '">show me the tile &rarr;</a></div>'
      + '<div class="meta">' + bits.join(' &middot; ') + '</div>'
      + '<div class="acts">'
      + '<button data-act="place">It is a place &mdash; stop outlining</button>'
      + '<button data-act="person">It is a person &mdash; keep it</button>'
      + '<input class="why" placeholder="why (optional, kept in the record)">'
      + '</div>';
    el.querySelector('.text').textContent = r.text;

    const why = el.querySelector('.why');
    const set = (verdict) => {
      state.set(r.key, verdict ? { key: r.key, verdict, reason: why.value.trim() || undefined } : null);
      el.classList.toggle('place', verdict === 'place');
      el.classList.toggle('person', verdict === 'person');
      el.querySelectorAll('button').forEach(b => {
        b.classList.toggle('on', b.dataset.act === verdict);
        b.classList.toggle('place', b.dataset.act === 'place');
        b.classList.toggle('person', b.dataset.act === 'person');
      });
      render();
    };
    el.querySelector('[data-act=place]').onclick = () => set('place');
    el.querySelector('[data-act=person]').onclick = () => set('person');
    why.oninput = () => { if (state.get(r.key)) set(state.get(r.key).verdict); };
    host.appendChild(el);
    setters.push(set);
    if (g.pre) set(g.pre);
  }
  if (accept) accept.onclick = () => setters.forEach(s => s(g.pre));
}

function render() {
  const out = [...state.values()].filter(Boolean);
  document.getElementById('count').textContent = out.length + ' decided';
  document.getElementById('out').value = out.length ? JSON.stringify(out, null, 1) : '';
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

  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  fs.writeFileSync(OUT, html);
  const needsHim = rows.filter((r) => r.verdict === 'unclear').length;
  console.log(`${rows.length} pin(s) on the page, ${needsHim} of them genuinely open`
    + ` -> ${path.relative(ROOT, OUT)}`);
}
