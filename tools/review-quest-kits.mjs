#!/usr/bin/env node
// DX-3. Every quest kit item the WIKI says you get during the quest, but
// that we still demand you bring — reviewed in one sitting instead of one
// per play session.
//
// The class, from 2026-08-10: The Dig Site lists opal, charcoal and a
// trowel. All three come from the quest, so all three sat RED next to
// three identical items that were correctly flagged. The owner met that
// by standing in front of it. There are 100+ quests, and every one of
// them can produce the same finding.
//
// WHY THE WIKI AND NOT QUEST HELPER: audit-quest-granted.mjs already asks
// QH, whose split (bring vs tracked-during) is cleaner but only covers
// what QH models. The wiki's "Items required" list annotates each entry in
// prose — "(obtained during the quest)", "(can be stolen from the tea
// stall)" — and that prose is the thing a player reads. The two tools
// disagree usefully; this one is not a replacement.
//
// READ THE PARENTHETICAL, NOT JUST THE LINK. The Dig Site's items list
// undersells the opal as a "small chance to acquire while panning", while
// its walkthrough says plainly to pan until you get one. So a row here is
// a PROPOSAL with the wiki's own words attached, never an automatic
// change: the words are what the owner judges.
//
//   node tools/review-quest-kits.mjs           -> build/quest-kits-review.html
//   node tools/review-quest-kits.mjs --apply   -> write the approved decisions
//
// Decisions live in tools/quest-kit-review-decisions.json so a settled
// question is never asked twice (the lesson from quest-granted-reviewed
// and decisions-declined). --apply reads that file and sets granted:true
// on the annotation items that were approved.
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { liveItemNames } from './lib/item-names.mjs';

const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const RES = path.join(ROOT, 'src/main/resources/com/ironscape');
const ANNOTATIONS = path.join(RES, 'annotations/annotations_oziris.json');
const DECISIONS = path.join(ROOT, 'tools/quest-kit-review-decisions.json');
const OUT = path.join(ROOT, 'build/quest-kits-review.html');
const CACHE = path.join(ROOT, 'tools/.wiki-cache/quest-items-raw.json');

const USER_AGENT = 'ironscape-runelite-plugin dev tooling (quest kit review)';
const REQUEST_DELAY_MS = 700;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
// MIRRORS the rest of the toolchain exactly — whitespace collapsed, then
// lowercased, THEN hashed. Getting this wrong does not error: every id
// simply fails to match an annotation and the tool reports a clean zero,
// which reads identically to "there is nothing wrong". Checked against
// seed-quest-items.mjs rather than remembered.
const stepId = (t) => crypto.createHash('sha256')
  .update(t.replace(/\s+/g, ' ').trim().toLowerCase(), 'utf8').digest('hex').slice(0, 10);
const runText = (content) => (content || []).map((c) => c.text).join('');

// Phrases in an items-list entry that mean "the quest provides this".
// Deliberately generous: a false PROPOSAL costs one click, a missed one
// costs a play session staring at a red item that can never go green.
// MEASURED, then narrowed. The first list was deliberately generous on the
// theory that a false proposal costs one click — but reading the 124 rows it
// produced showed the loose phrases answer a DIFFERENT QUESTION. "Cabbage
// (can be obtained from the cabbage patch in Edgeville Monastery)" and "Cup
// of tea (can be stolen from the Varrock tea stall)" tell you WHERE TO GET
// one; the quest does not hand it over, and marking them granted would turn
// a real requirement grey. That is the harmful direction: a missed grant
// leaves an item red and the owner asks about it, a wrong grant hides an
// item he then arrives without.
//
// So only phrases that say the QUEST is the source survive. Removed after
// review: "can be obtained", "obtained from", "can be stolen", "provided".
const IN_QUEST = [
  'during the quest', 'obtained during', 'obtainable during', 'given during',
  'given to you', 'received during', 'found during', 'acquired during',
  'while panning', 'from the quest', 'you will receive', 'is given',
];
const inQuestPhrase = (text) => {
  const lower = text.toLowerCase();
  return IN_QUEST.find((p) => lower.includes(p)) || null;
};

// ---- what we currently demand -------------------------------------------
const guide = JSON.parse(fs.readFileSync(path.join(RES, 'guide/guide_data_oziris.json'), 'utf8'));
const kitStepByQuest = new Map(); // quest -> {id, text}
guide.chapters.forEach((ch) => ch.sections.forEach((sec) => sec.steps.forEach((step) => {
  const quest = step.metadata?.quest?.trim();
  if (!quest) return;
  const text = runText(step.content);
  const id = stepId(text);
  const complete = 'complete'.localeCompare(step.metadata?.questStatus ?? '',
    undefined, { sensitivity: 'base' }) === 0;
  if (complete && !kitStepByQuest.has(quest)) kitStepByQuest.set(quest, { id, text });
})));

const doc = JSON.parse(fs.readFileSync(ANNOTATIONS, 'utf8'));
const annotations = doc.annotations;

// ---- what the wiki says --------------------------------------------------
fs.mkdirSync(path.dirname(CACHE), { recursive: true });
const cache = fs.existsSync(CACHE) ? JSON.parse(fs.readFileSync(CACHE, 'utf8')) : {};

async function itemLines(quest) {
  if (cache[quest] !== undefined) return cache[quest];
  await sleep(REQUEST_DELAY_MS);
  // api.php FOLLOWS REDIRECTS; action=raw does not (wave 6's gotcha, which
  // has now cost two tools a silent empty result).
  const url = 'https://oldschool.runescape.wiki/api.php?action=parse&prop=wikitext'
    + '&format=json&redirects=1&page=' + encodeURIComponent(quest);
  let lines = null;
  try {
    const res = await fetch(url, { headers: { 'User-Agent': USER_AGENT } });
    const wikitext = (await res.json())?.parse?.wikitext?.['*'] || '';
    const block = wikitext.match(/\|\s*items\s*=\s*([\s\S]*?)\n\s*\|\s*[a-z]+\s*=/i);
    if (block) {
      lines = block[1].split('\n')
        .map((l) => l.trim())
        .filter((l) => l.startsWith('*') && !/^\*\s*none/i.test(l));
    }
  }
  catch (e) {
    console.error(`  ! ${quest}: ${e.message}`);
  }
  cache[quest] = lines;
  fs.writeFileSync(CACHE, JSON.stringify(cache, null, 1));
  return lines;
}

// Strip wiki markup down to what a reader sees, so the page can quote it.
const readable = (line) => line
  .replace(/^\*+\s*/, '')
  .replace(/\[\[[^\]|]*\|([^\]]*)\]\]/g, '$1')
  .replace(/\[\[([^\]]*)\]\]/g, '$1')
  .replace(/\{\{plink\|([^}|]+)[^}]*\}\}/gi, '$1')
  .replace(/\{\{[^}]*\}\}/g, '')
  .replace(/'''?/g, '')
  .replace(/\s+/g, ' ')
  .trim();

const names = (line) => [
  ...line.matchAll(/\[\[([^\]|#]+)/g), ...line.matchAll(/\{\{plink\|([^}|]+)/gi),
].map((m) => m[1].trim().toLowerCase());

const decided = fs.existsSync(DECISIONS)
  ? JSON.parse(fs.readFileSync(DECISIONS, 'utf8')) : { decisions: {} };

// ---- apply ---------------------------------------------------------------
if (process.argv.includes('--apply')) {
  let changed = 0;
  for (const [key, verdict] of Object.entries(decided.decisions)) {
    if (verdict.granted !== true) continue;
    const [sid, ...rest] = key.split(' ');
    const item = rest.join(' ');
    const entry = annotations[sid];
    const need = entry?.items?.find((i) => (i.name || '').toLowerCase() === item);
    if (!need) {
      console.log(`  ? ${key} — no such item on that step any more, skipped`);
      continue;
    }
    if (need.granted === true) continue;
    need.granted = true;
    changed++;
  }
  fs.writeFileSync(ANNOTATIONS, JSON.stringify(doc, null, 1) + '\n');
  console.log(`${changed} item(s) marked as supplied by the quest.`);
  process.exit(0);
}

// ---- collect proposals ---------------------------------------------------
const liveNames = await liveItemNames(path.join(ROOT, 'tools/.wiki-cache/item-names-cache.json'));
const idByName = new Map();
if (liveNames) {
  for (const [id, name] of liveNames) {
    const key = String(name).toLowerCase();
    if (!idByName.has(key)) idByName.set(key, id);
  }
}

const rows = [];
let quests = 0;
for (const [quest, step] of [...kitStepByQuest.entries()].sort()) {
  const entry = annotations[step.id];
  if (!entry?.items?.length) continue;
  quests++;
  const lines = await itemLines(quest);
  if (!lines) continue;
  process.stdout.write(`\r  ${quests} quests checked…`);
  for (const line of lines) {
    const phrase = inQuestPhrase(line);
    if (!phrase) continue;
    for (const name of names(line)) {
      const need = entry.items.find((i) => (i.name || '').toLowerCase() === name);
      if (!need || need.granted === true || need.optional === true) continue;
      const key = `${step.id} ${name}`;
      if (decided.decisions[key]) continue; // settled
      // One item can appear on two lines of an items list (One Small
      // Favour names a chisel twice). Same key, same decision — asking
      // twice just wastes a click and lets the two answers disagree.
      if (rows.some((r) => r.key === key)) continue;
      rows.push({
        key, quest, name, step: step.text,
        id: idByName.get(name) ?? null,
        says: readable(line), phrase,
      });
    }
  }
}
console.log(`\r${quests} quests with a kit checked; ${rows.length} item(s) to review.`);

// Most rows are not judgement calls. "Tinderbox (obtainable during quest)"
// is the wiki stating a fact, and 117 of those in a row is a page nobody
// finishes — the owner said as much on first sight. What actually needs a
// human is the HEDGED wording, where the sentence carries a condition or an
// alternative: "(obtainable during quest) or Imcando hammer", "obtainable
// during quest IF YOU BRING A BUCKET", "chance to receive robes is higher
// during". Those change what you should carry; a flat statement does not.
//
// --auto settles the flat ones and leaves the hedged ones on the page.
// Every one is still written to the decisions file with the wiki's words,
// so an auto verdict is as reviewable and as reversible as a clicked one.
// ANY conditional or alternative sends the row to a human. `\bif\b` rather
// than "if you", because the first --auto run granted Sheep Shearer's
// "Shears IF OBTAINING wool to spin into balls of wool (obtained during the
// quest)" — where "obtained during the quest" attaches to the WOOL, not the
// shears. One clause of distance between the phrase and the item is enough
// to make the sentence unreadable by rule, and a wrong grant hides an item
// the player then arrives without.
const HEDGED = /small chance|may take|some time|\bchance\b|\bif\b|\bor\b|possibl|rare|might|instead|unless/i;
const hedged = rows.filter((r) => HEDGED.test(r.says));
const flat = rows.filter((r) => !HEDGED.test(r.says));

if (process.argv.includes('--auto')) {
  for (const r of flat) {
    decided.decisions[r.key] = {
      granted: true, item: r.name, quest: r.quest, says: r.says, via: '--auto (flat wiki statement)',
    };
  }
  fs.writeFileSync(DECISIONS, JSON.stringify(decided, null, 1) + '\n');
  console.log(`  ${flat.length} flat statement(s) settled automatically`
    + ` — run --apply to write them into the annotations.`);
  console.log(`  ${hedged.length} hedged row(s) left for review.`);
  rows.length = 0;
  rows.push(...hedged);
}

// ---- page ----------------------------------------------------------------
const html = `<!doctype html>
<meta charset="utf-8">
<title>Quest kit review — IRONSCAPE</title>
<style>
  :root { color-scheme: dark light; }
  body { font: 15px/1.5 system-ui, sans-serif; margin: 0; padding: 24px;
         background: #1b1a17; color: #e8e6e1; }
  h1 { font-size: 20px; margin: 0 0 4px; }
  p.sub { margin: 0 0 16px; color: #a8a49c; max-width: 80ch; }
  .row { padding: 12px 14px; margin-bottom: 8px; background: #232220;
         border: 1px solid #34322e; border-radius: 8px; }
  .row.granted { border-color: #2e8b4a; background: #1e2a20; }
  .row.kept { opacity: .45; }
  .head { display: flex; align-items: center; gap: 10px; }
  .name { font-weight: 600; font-size: 16px; text-transform: capitalize; }
  .quest { font-size: 12px; color: #8d8981; }
  .says { margin: 8px 0 0; padding: 8px 10px; background: #1b1a17;
          border-left: 3px solid #6b6459; border-radius: 4px; font-size: 13px; }
  .says b { color: #e8c07a; }
  img { width: 32px; height: 32px; object-fit: contain; }
  button { font: inherit; padding: 5px 12px; margin-right: 6px; cursor: pointer;
           background: #2f2d29; color: #e8e6e1; border: 1px solid #4a4740;
           border-radius: 6px; }
  button.on { background: #2e8b4a; border-color: #2e8b4a; }
  #bar { position: sticky; top: 0; background: #1b1a17; padding-bottom: 10px; z-index: 2; }
  textarea { width: 100%; height: 160px; margin-top: 14px; font-family: ui-monospace, monospace;
             font-size: 12px; background: #131211; color: #e8e6e1;
             border: 1px solid #45423c; border-radius: 6px; padding: 10px; }
</style>
<div id="bar">
  <h1>Quest items you might not need to bring</h1>
  <p class="sub">Each row is an item our guide asks you to bring, where the wiki's own
  items list says the quest gives it to you. Marked <b>from the quest</b>, it turns grey
  in the panel instead of sitting red for ever. The wiki's exact words are quoted
  underneath &mdash; judge those, not the item name. If the wording is vague
  ("small chance"), the walkthrough usually settles it, so keep it as a requirement
  when in doubt.</p>
  <button id="keepAll">Keep all as requirements</button>
  <button id="copy">Copy result</button>
  <span id="count" class="quest"></span>
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
  const icon = r.id
    ? '<img src="https://static.runelite.net/cache/item/icon/' + r.id + '.png" onerror="this.style.visibility=\\'hidden\\'">'
    : '<img style="visibility:hidden">';
  const quoted = r.says.replace(/&/g, '&amp;').replace(/</g, '&lt;');
  el.innerHTML =
    '<div class="head">' + icon + '<div><div class="name">' + r.name + '</div>' +
      '<div class="quest">' + r.quest + '</div></div></div>' +
    '<div class="says">wiki: ' + quoted + '</div>' +
    '<div style="margin-top:10px">' +
      '<button data-act="grant">Comes from the quest</button>' +
      '<button data-act="keep">Keep as a requirement</button></div>';
  const paint = () => {
    const v = state.get(r.key);
    el.classList.toggle('granted', !!(v && v.granted));
    el.classList.toggle('kept', !!(v && v.granted === false));
    el.querySelector('[data-act=grant]').classList.toggle('on', !!(v && v.granted));
    el.querySelector('[data-act=keep]').classList.toggle('on', !!(v && v.granted === false));
    render();
  };
  el.querySelector('[data-act=grant]').onclick = () => {
    state.set(r.key, { key: r.key, item: r.name, quest: r.quest, granted: true }); paint();
  };
  el.querySelector('[data-act=keep]').onclick = () => {
    state.set(r.key, { key: r.key, item: r.name, quest: r.quest, granted: false }); paint();
  };
  el._keep = () => {
    state.set(r.key, { key: r.key, item: r.name, quest: r.quest, granted: false }); paint();
  };
  list.appendChild(el);
}
function render() {
  const all = [...state.values()];
  document.getElementById('count').textContent =
    all.length + ' of ' + ROWS.length + ' decided, '
    + all.filter((v) => v.granted).length + ' from the quest';
  document.getElementById('out').value = all.length ? JSON.stringify(all, null, 1) : '';
}
document.getElementById('keepAll').onclick = () =>
  list.querySelectorAll('.row').forEach((el) => el._keep());
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
console.log(`-> ${path.relative(ROOT, OUT)}`);
if (Object.keys(decided.decisions).length) {
  console.log(`(${Object.keys(decided.decisions).length} already settled, not shown)`);
}
